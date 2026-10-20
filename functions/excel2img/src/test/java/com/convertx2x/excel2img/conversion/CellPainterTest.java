package com.convertx2x.excel2img.conversion;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CellPainterTest {
    private static final String HEADING = "プログラム名";
    @TempDir Path directory;

    @Test void blankNeighborsIncludingColoredAndEmptyConstantStringsAllowOverflowInBothFormats() throws Exception {
        for (boolean xlsx : new boolean[]{true, false}) {
            for (int blankKind = 0; blankKind < 3; blankKind++) {
                for (boolean filled : new boolean[]{true, false}) {
                    try (Workbook book = workbook(xlsx)) {
                        Sheet sheet = heading(book, 0);
                        for (int c = 1; c <= 2; c++) {
                            if (blankKind == 0) {
                                if (filled) sheet.setDefaultColumnStyle(c, style(book, true));
                            } else {
                                Cell blank = sheet.getRow(0).createCell(c, CellType.BLANK);
                                if (blankKind == 2) blank.setCellValue("");
                                blank.setCellStyle(style(book, filled));
                            }
                        }
                        // Save and reload an actual Excel fixture: keep the stored cell types/styles.
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        book.write(bytes);
                        try (Workbook reloaded = WorkbookFactory.create(new ByteArrayInputStream(bytes.toByteArray()))) {
                            Rendered result = render(reloaded.getSheetAt(0), "A1:C1");
                            assertEquals(HEADING, text(result), "format=" + xlsx + ", blankKind=" + blankKind + ", fill=" + filled);
                            assertFalse(warning(result, "CELL_TEXT_CLIPPED"));
                            assertTrue(darkPixels(result.image, (int) (new SheetLayout(sheet).x(1) * 2), 0,
                                    result.image.getWidth(), result.image.getHeight()) > 20, "ink extends into blank neighbors");
                            if (blankKind == 1 && filled) {
                                String extension = xlsx ? "xlsx" : "xls";
                                Files.write(directory.resolve("colored-overflow." + extension), bytes.toByteArray());
                                ImageIO.write(result.image, "png", directory.resolve("colored-overflow-" + extension + ".png").toFile());
                                try (SearchablePdfWriter writer = new SearchablePdfWriter(ConversionLimits.defaults(), ignored -> fail())) {
                                    writer.page(result.image, result.lines, result.plan, result.page, 2);
                                    ByteArrayOutputStream pdf = new ByteArrayOutputStream();
                                    writer.finish(pdf);
                                    Files.write(directory.resolve("colored-overflow-" + extension + ".pdf"), pdf.toByteArray());
                                    try (var document = Loader.loadPDF(pdf.toByteArray())) {
                                        assertTrue(new PDFTextStripper().getText(document).contains(HEADING));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void occupiedNeighborIsNotOverpaintedAndFormulaWithEmptyResultStillBlocks() throws Exception {
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = heading(book, 0);
            Cell neighbor = sheet.getRow(0).createCell(1);
            neighbor.setCellValue("別の値");
            neighbor.setCellStyle(style(book, true));
            Rendered result = render(sheet, "A1:C1");
            assertFalse(text(result).contains(HEADING));
            assertTrue(text(result).contains("別の値"));
            assertTrue(warning(result, "CELL_TEXT_CLIPPED"));
            sheet.getRow(0).getCell(0).setBlank();
            Rendered withoutHeading = render(sheet, "A1:C1");
            int neighborX = (int) Math.ceil(new SheetLayout(sheet).x(1) * 2);
            for (int y = 0; y < result.image.getHeight(); y++) {
                for (int x = neighborX; x < result.image.getWidth(); x++)
                    assertEquals(withoutHeading.image.getRGB(x, y), result.image.getRGB(x, y), "neighbor content must not be overpainted");
            }
            sheet.getRow(0).getCell(0).setCellValue(HEADING);
            neighbor.setCellFormula("\"\"");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(neighbor);
            assertFalse(text(render(sheet, "A1:C1")).contains(HEADING));
            neighbor.setBlank();
            neighbor.setCellValue(10);
            CellStyle hiddenNumber = style(book, true);
            hiddenNumber.setDataFormat(book.createDataFormat().getFormat(";;;"));
            neighbor.setCellStyle(hiddenNumber);
            assertFalse(text(render(sheet, "A1:C1")).contains(HEADING));
        }
    }

    @Test void mergedCellsAndSelectionEdgesStopOverflowWhileMergedHeadingUsesFullWidth() throws Exception {
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = heading(book, 0);
            sheet.addMergedRegion(CellRangeAddress.valueOf("B1:C1"));
            assertFalse(text(render(sheet, "A1:C1")).contains(HEADING));
            sheet.removeMergedRegion(0);
            assertFalse(text(render(sheet, "A1:A1")).contains(HEADING));
            sheet.addMergedRegion(CellRangeAddress.valueOf("A1:C1"));
            Rendered result = render(sheet, "A1:C1");
            assertEquals(HEADING, text(result));
            assertFalse(warning(result, "CELL_TEXT_CLIPPED"));
        }
    }

    @Test void shrinkWrapAndOrdinaryCenterDoNotSpillButCenterAcrossSelectionUsesStyledBlankCells() throws Exception {
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = heading(book, 0);
            CellStyle sourceStyle = sheet.getRow(0).getCell(0).getCellStyle();
            sourceStyle.setShrinkToFit(true);
            assertFalse(text(render(sheet, "A1:C1")).contains(HEADING));
            sourceStyle.setShrinkToFit(false);
            sourceStyle.setWrapText(true);
            sheet.getRow(0).setHeightInPoints(140);
            Rendered wrapped = render(sheet, "A1:C1");
            assertEquals(HEADING, text(wrapped));
            assertTrue(wrapped.lines.size() >= 3);
            double sourceRight = new SheetLayout(sheet).x(1);
            assertTrue(wrapped.lines.stream().allMatch(line -> line.x() + line.width() <= sourceRight));
            sourceStyle.setWrapText(false);
            sourceStyle.setAlignment(HorizontalAlignment.CENTER);
            assertFalse(text(render(sheet, "A1:C1")).contains(HEADING));
            sourceStyle.setAlignment(HorizontalAlignment.CENTER_SELECTION);
            for (int c = 1; c <= 2; c++) sheet.getRow(0).createCell(c).setCellStyle(sourceStyle);
            Rendered across = render(sheet, "A1:C1");
            assertEquals(HEADING, text(across));
            CellPainter.TextLine line = across.lines.getFirst();
            assertEquals(across.bounds.getCenterX(), line.x() + line.width() / 2, .01);
        }
    }

    @Test void overflowOriginatingBeforeHorizontalPageRemainsVisibleOnThatPage() throws Exception {
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = heading(book, 0);
            SheetLayout geometry = new SheetLayout(sheet);
            Rectangle2D viewport = new Rectangle2D.Double(geometry.x(1), 0,
                    geometry.x(3) - geometry.x(1), geometry.y(1));
            Rendered result = render(sheet, "A1:C1", viewport);
            assertFalse(result.lines.isEmpty());
            assertTrue(text(result).endsWith("グラム名"));
            assertTrue(darkPixels(result.image, 0, 0, result.image.getWidth(), result.image.getHeight()) > 20);
        }
    }

    @Test void ordinaryWrappedHeadingsAreCenteredInTallMergedCellsForBothFormats() throws Exception {
        String[] headings = {"起動", "表示内容", "表示順", "検索機能", "PG検証", "カーソル移動順"};
        for (boolean xlsx : new boolean[]{true, false}) {
            for (int columnWidth : new int[]{4, 7}) {
                try (Workbook book = workbook(xlsx)) {
                    for (String heading : headings) {
                        Sheet sheet = book.createSheet();
                        sheet.setColumnWidth(0, columnWidth * 256);
                        for (int r = 0; r < 13; r++) sheet.createRow(r).setHeightInPoints(24);
                        sheet.addMergedRegion(CellRangeAddress.valueOf("A1:A13"));
                        Cell cell = sheet.getRow(0).createCell(0);
                        cell.setCellValue(heading);
                        CellStyle style = style(book, true);
                        style.setWrapText(true);
                        style.setAlignment(HorizontalAlignment.CENTER);
                        style.setVerticalAlignment(VerticalAlignment.CENTER);
                        cell.setCellStyle(style);
                        Rendered result = render(sheet, "A1:A13");
                        assertEquals(heading, text(result));
                        assertFalse(warning(result, "CELL_TEXT_CLIPPED"));
                        assertTrue(result.lines.getFirst().baseline() > 60, "centered text must not be pinned to the top");
                        assertTrue(result.lines.getLast().baseline() < result.bounds.getMaxY() - 60);
                        for (CellPainter.TextLine line : result.lines) {
                            assertEquals(result.bounds.getCenterX(), line.x() + line.width() / 2, .01);
                            assertTrue(line.text().codePointCount(0, line.text().length()) <= (columnWidth == 4 ? 2 : 4));
                        }
                        assertTrue(result.lines.size() >= (heading.length() + (columnWidth == 4 ? 0 : 1)) / (columnWidth == 4 ? 1 : 2));
                    }
                }
            }
        }
    }

    @Test void stackedRotationIsUprightAndSearchableInXlsxAndXls() throws Exception {
        for (boolean xlsx : new boolean[]{true, false}) {
            try (Workbook book = workbook(xlsx)) {
                Sheet sheet = heading(book, 255);
                sheet.setColumnWidth(0, 4 * 256);
                sheet.getRow(0).setHeightInPoints(250);
                Cell cell = sheet.getRow(0).getCell(0);
                cell.setCellValue("表示内容");
                cell.getCellStyle().setAlignment(HorizontalAlignment.CENTER);
                cell.getCellStyle().setVerticalAlignment(VerticalAlignment.CENTER);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                book.write(bytes);
                try (Workbook reloaded = WorkbookFactory.create(new ByteArrayInputStream(bytes.toByteArray()))) {
                    Rendered result = render(reloaded.getSheetAt(0), "A1:A1");
                    assertEquals("表示内容", text(result));
                    assertEquals(4, result.lines.size());
                    assertFalse(warning(result, "TEXT_ROTATION_APPROXIMATED"));
                    assertFalse(warning(result, "CELL_TEXT_CLIPPED"));
                    for (int i = 1; i < result.lines.size(); i++) {
                        assertEquals(result.lines.getFirst().x(), result.lines.get(i).x(), .01);
                        assertTrue(result.lines.get(i).baseline() > result.lines.get(i - 1).baseline() + 10);
                    }
                    assertTrue(result.lines.getFirst().baseline() > 70);
                }
            }
        }
    }

    @Test void stackedWrappingAndExplicitNewlinesProduceSeparateUprightColumns() throws Exception {
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = heading(book, 255);
            sheet.setColumnWidth(0, 10 * 256);
            sheet.getRow(0).setHeightInPoints(76);
            Cell cell = sheet.getRow(0).getCell(0);
            cell.setCellValue("カーソル移動順");
            cell.getCellStyle().setWrapText(true);
            cell.getCellStyle().setAlignment(HorizontalAlignment.CENTER);
            cell.getCellStyle().setVerticalAlignment(VerticalAlignment.CENTER);
            Rendered wrapped = render(sheet, "A1:A1");
            assertEquals("カーソル移動順", text(wrapped));
            assertEquals(2, wrapped.lines.stream().map(line -> Math.round((line.x() + line.width() / 2) * 1000)).distinct().count());
            assertTrue(wrapped.lines.getFirst().x() > wrapped.lines.getLast().x());
            assertFalse(warning(wrapped, "CELL_TEXT_CLIPPED"));
            cell.setCellValue("起動\n表示");
            cell.getCellStyle().setWrapText(false);
            Rendered explicit = render(sheet, "A1:A1");
            assertEquals("起動表示", text(explicit));
            assertEquals(explicit.lines.get(0).baseline(), explicit.lines.get(2).baseline(), .01);
            assertTrue(explicit.lines.get(0).x() > explicit.lines.get(2).x());
        }
    }

    private static Workbook workbook(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }
    private static Sheet heading(Workbook book, int rotation) {
        Sheet sheet = book.createSheet();
        sheet.createRow(0).setHeightInPoints(30);
        sheet.setColumnWidth(0, 5 * 256);
        sheet.setColumnWidth(1, 10 * 256);
        sheet.setColumnWidth(2, 10 * 256);
        Cell cell = sheet.getRow(0).createCell(0);
        cell.setCellValue(HEADING);
        CellStyle style = style(book, true);
        style.setRotation((short) rotation);
        cell.setCellStyle(style);
        return sheet;
    }
    private static CellStyle style(Workbook book, boolean filled) {
        var font = book.createFont();
        font.setFontName("BIZ UDPGothic");
        font.setFontHeightInPoints((short) 14);
        CellStyle style = book.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (filled) {
            style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return style;
    }
    private record Rendered(BufferedImage image, List<CellPainter.TextLine> lines,
                            List<Map<String, Object>> warnings, Rectangle2D bounds,
                            RangePlanner.Plan plan, RangePlanner.Page page) {}
    private static Rendered render(Sheet sheet, String reference) {
        return render(sheet, reference, null);
    }
    private static Rendered render(Sheet sheet, String reference, Rectangle2D viewport) {
        CellRangeAddress range = CellRangeAddress.valueOf(reference);
        try (ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            SheetLayout geometry = new SheetLayout(sheet);
            CellValues values = new CellValues(workspace);
            RangePlanner.Plan plan = RangePlanner.plan(sheet, range, geometry, RenderPolicy.defaults(),
                    ConversionLimits.defaults(), values, workspace);
            Rectangle2D bounds = viewport == null ? plan.bounds() : viewport;
            BufferedImage image = new BufferedImage((int) Math.ceil(bounds.getWidth() * 2),
                    (int) Math.ceil(bounds.getHeight() * 2), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            List<CellPainter.TextLine> lines;
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.scale(2, 2);
                g.translate(-bounds.getX(), -bounds.getY());
                g.clip(bounds);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                lines = new CellPainter(sheet, geometry, values, workspace, range, 200_000)
                        .draw(g, plan.pages().getFirst(), bounds);
            } finally { g.dispose(); }
            return new Rendered(image, lines, workspace.warnings(), bounds, plan, plan.pages().getFirst());
        }
    }
    private static String text(Rendered result) { return result.lines.stream().map(CellPainter.TextLine::text).reduce("", String::concat); }
    private static boolean warning(Rendered result, String code) { return result.warnings.stream().anyMatch(w -> code.equals(w.get("code"))); }
    private static int darkPixels(BufferedImage image, int x1, int y1, int x2, int y2) {
        int count = 0;
        for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
            Color color = new Color(image.getRGB(x, y));
            if (color.getRed() < 90 && color.getGreen() < 90 && color.getBlue() < 90) count++;
        }
        return count;
    }
}
