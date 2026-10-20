package com.convertx2x.excel2img.conversion;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelImageServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConversionLimits DEFAULTS = ConversionLimits.defaults();
    private static byte[] bytes(Workbook book) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) { book.write(output); return output.toByteArray(); }
    }
    private static JsonNode report(ConversionResult result) throws IOException {
        return JSON.readTree(Files.readAllBytes(result.files().get("report.json")));
    }
    private static String text(ConversionResult result) throws IOException {
        try (var pdf = Loader.loadPDF(result.pdfBytes())) { return new PDFTextStripper().getText(pdf); }
    }
    private static Cell value(Sheet sheet, int row, int col, String text) {
        var r = sheet.getRow(row); if (r == null) r = sheet.createRow(row);
        Cell cell = r.createCell(col); cell.setCellValue(text); return cell;
    }
    private static byte[] simple() throws IOException {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Main"); sheet.setColumnWidth(0, 30 * 256);
            value(sheet, 0, 0, "Searchable 日本語"); return bytes(book);
        }
    }
    @Test void optionsAndPolicyValidateBeforeAnyDocumentWork() {
        assertEquals("png", RenderOptions.defaults().format());
        assertEquals("B2:D4", new RenderOptions("PDF", "Main", "$B$2:$D$4").range());
        for (String range : new String[]{"A:A", "1:4", "A0:B2", "XFE1:XFE3", "A1:A1048577", "Other!A1:B2", "A1,B2"})
            assertThrows(ConversionException.class, () -> new RenderOptions("png", "Main", range), range);
        assertThrows(ConversionException.class, () -> new RenderOptions("png", null, "A1"));
        assertThrows(ConversionException.class, () -> new RenderOptions("svg", null, null));
        assertThrows(ConversionException.class, () -> new RenderOptions("png", "bad/name", null));
        assertThrows(IllegalArgumentException.class, () -> new RenderPolicy(100, 100, Double.NaN, 2, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> RenderPolicy.from(Map.of("CONVERSION_RENDER_PIXELS_PER_POINT", "Infinity")));
        assertEquals(640, RenderPolicy.from(Map.of("CONVERSION_RENDER_MAX_WIDTH_PIXELS", "640")).maxWidthPixels());
        assertEquals(50, DEFAULTS.maxPages());
    }
    @Test void pngPreservesMergedGeometryFillAndHiddenDimensions() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Layout");
            sheet.setColumnWidth(0, 12 * 256); sheet.setColumnWidth(1, 12 * 256); sheet.setColumnWidth(2, 12 * 256);
            Cell cell = value(sheet, 0, 0, "Merged"); sheet.getRow(0).setHeightInPoints(30);
            var style = book.createCellStyle(); style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setBorderBottom(BorderStyle.THICK);
            var font = book.createFont(); font.setBold(true); font.setStrikeout(true); font.setFontHeightInPoints((short) 12); style.setFont(font);
            cell.setCellStyle(style); sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            sheet.setColumnHidden(1, true); value(sheet, 1, 0, "Hidden"); sheet.getRow(1).setZeroHeight(true);
            value(sheet, 2, 0, "Last");
            SheetLayout geometry = new SheetLayout(sheet);
            assertEquals(geometry.x(1), geometry.x(2)); assertEquals(geometry.y(1), geometry.y(2));
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "merged.xlsx")) {
                assertEquals(1, result.pageCount());
                BufferedImage image = ImageIO.read(result.files().get("pages/page-001.png").toFile());
                assertEquals(RangePlanner.positiveCeil(geometry.x(3) * 2), image.getWidth());
                assertEquals(Color.YELLOW.getRGB(), image.getRGB(image.getWidth() - 8, 8));
                assertEquals("A1:C3", report(result).path("ranges").get(0).path("range").asText());
                assertTrue(result.zipBytes().length > 0);
            }
        }
    }
    @Test void printAreaWinsAndExplicitRectangleOverridesIt() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Main");
            sheet.setColumnWidth(0, 18 * 256); sheet.setColumnWidth(1, 18 * 256);
            value(sheet, 0, 0, "Outside"); value(sheet, 2, 1, "Inside");
            book.setPrintArea(0, 1, 1, 2, 3);
            byte[] input = bytes(book);
            try (var result = new ExcelImageService(DEFAULTS).convert(input, "area.xlsx", new RenderOptions("pdf", null, null))) {
                JsonNode range = report(result).path("ranges").get(0);
                assertEquals("printArea", range.path("selection").asText());
                assertEquals("B3:B4", range.path("range").asText());
                assertTrue(text(result).contains("Inside")); assertFalse(text(result).contains("Outside"));
            }
            try (var result = new ExcelImageService(DEFAULTS).convert(input, "area.xlsx", new RenderOptions("pdf", "Main", "A1:A1"))) {
                assertEquals("explicit", report(result).path("ranges").get(0).path("selection").asText());
                assertTrue(text(result).contains("Outside")); assertFalse(text(result).contains("Inside"));
            }
        }
    }
    @Test void occupiedRangeKeepsItsLocationAndOmitsBlankOuterRows() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Offset");
            value(sheet, 4, 3, "At D5"); sheet.createRow(5000);
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "offset.xlsx")) {
                var range = report(result).path("ranges").get(0);
                assertEquals("D5", range.path("range").asText());
                assertEquals("occupied", range.path("selection").asText());
                assertTrue(range.path("sourceBoundsPoints").path("y").asDouble() > 0);
            }
        }
    }
    @Test void unreadableRangeIsSkippedWhileUsefulSheetSurvives() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet first = book.createSheet("Good"); first.setColumnWidth(0, 18 * 256); value(first, 0, 0, "Useful");
            Sheet second = book.createSheet("Wide"); value(second, 0, 0, "Left"); value(second, 0, 100, "Far right");
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "wide.xlsx")) {
                assertEquals(1, result.sectionCount()); assertEquals(1, result.pageCount());
                JsonNode report = report(result); assertEquals("partial", report.path("outcome").asText());
                assertEquals("WIDTH_UNREADABLE", report.path("ranges").get(1).path("reason").asText());
                assertEquals(1, report.path("ranges").get(0).path("pages").size());
            }
        }
    }
    @Test void allSkippedCarriesActionableSafeReport() throws Exception {
        RenderPolicy policy = new RenderPolicy(10, 200, 12, 2, 200_000, 20_000_000);
        ConversionException failure = assertThrows(ConversionException.class,
                () -> new ExcelImageService(DEFAULTS, policy).convert(simple(), "wide.xlsx"));
        assertEquals(422, failure.statusCode()); assertEquals("NO_RENDERABLE_RANGE", failure.code());
        JsonNode details = JSON.valueToTree(failure.details());
        assertEquals("WIDTH_UNREADABLE", details.path("ranges").get(0).path("reason").asText());
        assertEquals("partial", details.path("outcome").asText());
        assertThrows(UnsupportedOperationException.class, () -> failure.details().put("bad", true));
    }
    @Test void cachedFormulasAreUsedAndMissingCacheNeverGetsEvaluated() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Formulas"); sheet.setColumnWidth(0, 30 * 256);
            XSSFCell cached = (XSSFCell) value(sheet, 0, 0, ""); cached.setCellFormula("1+1"); cached.setCellValue(123);
            XSSFCell missing = (XSSFCell) value(sheet, 1, 0, ""); missing.setCellFormula("2+2"); missing.getCTCell().unsetV();
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "formulas.xlsx", new RenderOptions("pdf", null, null))) {
                String text = text(result); assertTrue(text.contains("123")); assertTrue(text.contains("#UNCALCULATED"));
                assertFalse(text.contains("1+1")); assertFalse(text.contains("2+2"));
                assertTrue(report(result).path("warnings").toString().contains("FORMULA_CACHE_MISSING"));
            }
        }
    }
    @Test void pdfHasUnicodeSearchableTextAtTheSamePageDimensionsAsPng() throws Exception {
        byte[] input = simple();
        try (var png = new ExcelImageService(DEFAULTS).convert(input, "main.xlsx");
             var pdf = new ExcelImageService(DEFAULTS).convert(input, "main.xlsx", new RenderOptions("pdf", null, null))) {
            assertTrue(text(pdf).contains("Searchable 日本語"), text(pdf));
            assertEquals(png.pageCount(), pdf.pageCount());
            assertEquals(report(png).path("pages").get(0).path("widthPixels"), report(pdf).path("pages").get(0).path("widthPixels"));
            try (var loaded = Loader.loadPDF(pdf.pdfBytes())) {
                assertEquals(report(pdf).path("pages").get(0).path("widthPixels").asDouble() / 2,
                        loaded.getPage(0).getMediaBox().getWidth(), .01);
            }
        }
    }
    @Test void cachedEmptyStringFormulaIsNotMistakenForMissingCache() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = book.createSheet("Empty result");
            XSSFCell cell = (XSSFCell) value(sheet, 0, 0, "");
            cell.setCellFormula("\"\"");
            cell.setCellValue("");
            assertEquals("", new CellValues(workspace).text(cell));
            assertFalse(workspace.warnings().toString().contains("FORMULA_CACHE_MISSING"));
        }
    }
    @Test void opaqueDrawingHidesUnderlyingCellFromPdfSearchButLeavesVisibleCellsSearchable() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Covered");
            sheet.setColumnWidth(0, 30 * 256);
            value(sheet, 0, 0, "Hidden secret");
            value(sheet, 2, 0, "Visible content");
            var drawing = sheet.createDrawingPatriarch();
            var anchor = book.getCreationHelper().createClientAnchor();
            anchor.setCol1(0); anchor.setCol2(1); anchor.setRow1(0); anchor.setRow2(1);
            var cover = drawing.createSimpleShape(anchor);
            cover.setShapeType(org.apache.poi.ss.usermodel.ShapeTypes.RECT);
            cover.setFillColor(240, 240, 240);
            cover.setNoFill(false);
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "covered.xlsx",
                    new RenderOptions("pdf", null, null))) {
                assertFalse(text(result).contains("Hidden secret"));
                assertTrue(text(result).contains("Visible content"));
                assertTrue(report(result).path("warnings").toString().contains("PDF_TEXT_OVERLAPS_DRAWING"));
                assertEquals("partial", report(result).path("outcome").asText());
            }
        }
    }
    @Test void verticalPagesCoverOversizeMergedRegionWithoutHorizontalPagination() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Tall"); sheet.setColumnWidth(0, 20 * 256);
            value(sheet, 0, 0, "Merged tall");
            for (int row = 0; row < 10; row++) { var r = sheet.getRow(row); if (r == null) r = sheet.createRow(row); r.setHeightInPoints(40); }
            sheet.addMergedRegion(new CellRangeAddress(0, 9, 0, 0));
            RenderPolicy policy = new RenderPolicy(2048, 200, 12, 2, 200_000, 20_000_000);
            try (var result = new ExcelImageService(DEFAULTS, policy).convert(bytes(book), "tall.xlsx")) {
                JsonNode report = report(result); assertTrue(result.pageCount() >= 4);
                double end = 0;
                for (JsonNode page : report.path("pages")) {
                    JsonNode b = page.path("sourceBoundsPoints"); assertEquals(end, b.path("y").asDouble(), .001);
                    end += b.path("height").asDouble(); assertEquals(0, b.path("x").asDouble());
                    assertTrue(page.path("heightPixels").asInt() <= 200);
                }
                assertEquals(400, end, .001);
                assertTrue(report.path("warnings").toString().contains("MERGE_SPLIT_ACROSS_PAGES"));
            }
        }
    }
    @Test void xlsAndExplicitlySelectedHiddenSheetAreSupported() throws Exception {
        try (var book = new HSSFWorkbook()) {
            Sheet first = book.createSheet("Visible"); value(first, 0, 0, "Visible");
            Sheet hidden = book.createSheet("Hidden"); hidden.setColumnWidth(0, 25 * 256); value(hidden, 0, 0, "Hidden requested"); book.setSheetHidden(1, true);
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "legacy.xls", new RenderOptions("pdf", "Hidden", null))) {
                assertTrue(text(result).contains("Hidden requested")); assertEquals(1, result.sectionCount());
            }
        }
    }
    @Test void oversizedSingleRowHasContinuousWindowsAndLaterTextSurvives() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Tall row"); sheet.setColumnWidth(0, 25 * 256);
            value(sheet, 0, 0, "Bottom of tall row"); sheet.getRow(0).setHeightInPoints(350);
            value(sheet, 1, 0, "Next row");
            RenderPolicy policy = new RenderPolicy(2048, 200, 12, 2, 200_000, 20_000_000);
            try (var result = new ExcelImageService(DEFAULTS, policy).convert(bytes(book), "row.xlsx", new RenderOptions("pdf", null, null))) {
                double end = 0;
                for (JsonNode page : report(result).path("pages")) {
                    JsonNode bounds = page.path("sourceBoundsPoints");
                    assertEquals(end, bounds.path("y").asDouble(), .001);
                    end += bounds.path("height").asDouble();
                }
                assertEquals(365, end, .001);
                assertTrue(text(result).contains("Bottom of tall row"));
                assertTrue(text(result).contains("Next row"));
                assertTrue(report(result).path("warnings").toString().contains("ROW_SPLIT_ACROSS_PAGES"));
            }
        }
    }
    @Test void disjointPrintAreasBecomeIndependentPagesAndDoNotExpandIntoTheGap() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Two areas"); sheet.setColumnWidth(0, 25 * 256);
            value(sheet, 0, 0, "First area"); value(sheet, 4, 0, "Gap excluded"); value(sheet, 9, 0, "Second area");
            book.setPrintArea(0, "$A$1:$A$1,$A$10:$A$10");
            try (var result = new ExcelImageService(DEFAULTS).convert(bytes(book), "areas.xlsx", new RenderOptions("pdf", null, null))) {
                assertEquals(2, result.pageCount()); assertEquals(2, report(result).path("ranges").size());
                assertTrue(text(result).contains("First area")); assertTrue(text(result).contains("Second area"));
                assertFalse(text(result).contains("Gap excluded"));
            }
        }
    }
    @Test void inputOutputPagePixelAndCellLimitsAreEnforced() throws Exception {
        byte[] input = simple();
        assertEquals("INPUT_BYTES_LIMIT", assertThrows(ConversionException.class, () -> new ExcelImageService(
                new ConversionLimits(10, 100_000, 50, 20_000_000)).convert(input, "main.xlsx")).code());
        assertEquals("OUTPUT_BYTES_LIMIT", assertThrows(ConversionException.class, () -> new ExcelImageService(
                new ConversionLimits(100_000, 100, 50, 20_000_000)).convert(input, "main.xlsx")).code());
        assertEquals("IMAGE_PIXELS_LIMIT", assertThrows(ConversionException.class, () -> new ExcelImageService(
                new ConversionLimits(100_000, 100_000, 50, 10)).convert(input, "main.xlsx")).code());
        RenderPolicy tinyCells = new RenderPolicy(2048, 2800, 12, 2, 1, 20_000_000);
        ConversionException failure = assertThrows(ConversionException.class, () -> new ExcelImageService(DEFAULTS, tinyCells)
                .convert(input, "main.xlsx", new RenderOptions("png", "Main", "A1:B2")));
        assertEquals("CELL_LIMIT", JSON.valueToTree(failure.details()).path("ranges").get(0).path("reason").asText());
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Pages"); value(sheet, 0, 0, "First"); value(sheet, 20, 0, "Last");
            RenderPolicy shortPage = new RenderPolicy(2048, 100, 12, 2, 200_000, 20_000_000);
            assertEquals("PAGE_LIMIT", assertThrows(ConversionException.class, () -> new ExcelImageService(
                    new ConversionLimits(100_000, 100_000, 1, 20_000_000), shortPage).convert(bytes(book), "pages.xlsx")).code());
        }
    }
    @Test void invalidInputAndUnknownExactSheetAreRejected() throws Exception {
        assertEquals("UNSUPPORTED_FORMAT", assertThrows(ConversionException.class, () -> new ExcelImageService(DEFAULTS).convert(simple(), "file.docx")).code());
        assertEquals("SHEET_NOT_FOUND", assertThrows(ConversionException.class, () -> new ExcelImageService(DEFAULTS)
                .convert(simple(), "file.xlsx", new RenderOptions("png", "main", null))).code());
        assertEquals("EMPTY_INPUT", assertThrows(ConversionException.class, () -> new ExcelImageService(DEFAULTS).convert(new byte[0], "file.xlsx")).code());
    }
}
