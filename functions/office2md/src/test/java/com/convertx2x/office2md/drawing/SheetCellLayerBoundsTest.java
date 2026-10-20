package com.convertx2x.office2md.drawing;

import static org.junit.jupiter.api.Assertions.*;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.OfficeMarkdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SheetCellLayerBoundsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void longWideTableKeepsAllMarkdownAndCompositesOnlyCellsNearThePicture() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("大きな表");
            var style = book.createCellStyle();
            style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
            for (int r = 0; r < 300; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < 30; c++) {
                    var cell = row.createCell(c); cell.setCellStyle(style);
                    cell.setCellValue(r == 299 && c == 29 ? "最後のセルも保持" : "値" + r + "-" + c);
                }
            }
            addPicture(book);
            try (var result = convert(book, 200_000)) {
                JsonNode report = report(result);
                JsonNode composite = blocks(report, "compositeImage").getFirst();
                assertTrue(Files.readString(result.files().get("document.md")).contains("最後のセルも保持"));
                assertTrue(report.path("warnings").toString().contains("TABLE_COMPOSITE_LOCAL_RANGE"));
                assertEquals(0, blocks(report, "embeddedImage").size());
                assertTrue(composite.path("metadata").path("height").asDouble() < 150);
                assertTrue(composite.path("metadata").path("width").asDouble() < 200);
                assertTrue(composite.path("metadata").path("cellRanges").size() < 30);
                BufferedImage image = ImageIO.read(result.files().get(composite.path("path").asText()).toFile());
                assertTrue((long) image.getWidth() * image.getHeight() <= 200_000);
                image.flush();
            }
        }
    }

    @Test void hugeMergedCellKeepsTextAndSeparateImageInsteadOfFailingConversion() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("巨大な結合セル");
            sheet.createRow(0).createCell(0).setCellValue("巨大な結合セルの文字を保持");
            sheet.addMergedRegion(new CellRangeAddress(0, 299, 0, 29));
            addPicture(book);
            try (var result = convert(book, 200_000)) {
                JsonNode report = report(result);
                assertEquals(0, blocks(report, "compositeImage").size());
                assertEquals(1, blocks(report, "embeddedImage").size());
                assertTrue(report.path("warnings").toString().contains("CELL_COMPOSITE_RANGE_SKIPPED"));
                assertTrue(Files.readString(result.files().get("document.md")).contains("巨大な結合セルの文字を保持"));
            }
        }
    }

    @Test void distantRowsAndDrawingsDoNotExpandLocalCellPreview() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            var sheet = book.createSheet("離れたセル");
            sheet.createRow(0).createCell(0).setCellValue("近くのセル");
            for (int i = 0; i < 2_000; i++)
                sheet.createRow(10_000 + i * 10).createCell(i % 20).setCellValue("離れたセル" + i);
            List<DrawingScene.Item> items = new ArrayList<>();
            items.add(rectangle(5, 2, 20, 10));
            for (int i = 0; i < 300; i++) items.add(rectangle(2_000, 600_000 + i * 100, 20, 10));
            List<SheetCellLayer.Region> regions = SheetCellLayer.touchingRegions(sheet, new SheetCoordinates(sheet),
                    List.of(), items, workspace);
            assertEquals(1, regions.size());
            assertEquals("A1", regions.getFirst().range().formatAsString());
            assertTrue(regions.getFirst().bounds().getHeight() < 30);
        }
    }

    @Test void smallTablesContinueToUseTheCompleteTableRegion() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            var sheet = book.createSheet("小さな表");
            for (int row = 0; row < 4; row++) for (int column = 0; column < 3; column++) {
                var source = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
                source.createCell(column).setCellValue("値");
            }
            List<SheetCellLayer.Region> regions = SheetCellLayer.touchingRegions(sheet, new SheetCoordinates(sheet),
                    List.of(new CellRangeAddress(0, 3, 0, 2)), List.of(rectangle(5, 2, 20, 10)), workspace);
            assertEquals(1, regions.size());
            assertEquals("A1:C4", regions.getFirst().range().formatAsString());
        }
    }

    @Test void oversizedTransitiveTableUnionRestoresSeparatePictureComponents() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("表による連鎖");
            var coordinates = new SheetCoordinates(sheet);
            double y = coordinates.y(4) + 3;
            addPictureAt(book, coordinates.x(4) - 15, y, 30, 15);
            addPictureAt(book, coordinates.x(8) - 15, y, 30, 15);
            long pixels = (long) Math.ceil((coordinates.x(8) + 23) * 96 / 72)
                    * (long) Math.ceil((coordinates.y(9) + 8) * 96 / 72);
            try (var workspace = new ConversionWorkspace(limits(pixels))) {
                List<DrawingBlock> drawings = new DrawingExtractor().extract(sheet, workspace, List.of(
                        new CellRangeAddress(0, 4, 4, 7),
                        new CellRangeAddress(4, 8, 0, 3),
                        new CellRangeAddress(4, 8, 8, 11)));
                workspace.finishReport("sample.xlsx", "test");
                JsonNode report = JSON.readTree(Files.readString(workspace.files().get("report.json")));
                assertTrue(report.path("warnings").toString().contains("COMPOSITE_CELL_CANVAS_SKIPPED"));
                assertEquals(2, drawings.size(), "Dropping cells must undo table-only grouping of the two images");
                assertEquals(0, blocks(report, "compositeImage").size());
                assertEquals(2, blocks(report, "embeddedImage").size(), "Both original image placements survive");
            }
        }
    }

    private static DrawingScene.Item rectangle(double x, double y, double width, double height) {
        var item = new DrawingScene.Item(); item.kind = DrawingScene.Kind.RECTANGLE;
        item.width = width; item.height = height;
        item.transform = AffineTransform.getTranslateInstance(x, y);
        return item;
    }
    private static void addPicture(XSSFWorkbook book) throws Exception {
        addPictureAt(book, 20, 20, 80, 60);
    }
    private static void addPictureAt(XSSFWorkbook book, double x, double y, double width, double height) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 8, 8); graphics.dispose();
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); image.flush();
        int picture = book.addPicture(bytes.toByteArray(), Workbook.PICTURE_TYPE_PNG);
        var sheet = book.getSheetAt(0);
        var drawing = sheet.getDrawingPatriarch() == null ? sheet.createDrawingPatriarch() : sheet.getDrawingPatriarch();
        drawing.createPicture(new XSSFClientAnchor(
                Units.toEMU(x), Units.toEMU(y), Units.toEMU(x + width), Units.toEMU(y + height), 0, 0, 0, 0), picture);
    }
    private static ConversionLimits limits(long pixels) {
        var d = ConversionLimits.defaults();
        return new ConversionLimits(d.maxInputBytes(), d.maxSections(), d.maxReadItems(), d.maxTableCells(),
                d.maxMarkdownBytes(), d.maxImages(), d.maxImageBytes(), d.maxOutputBytes(), d.maxShapes(),
                d.maxGroupDepth(), pixels);
    }
    private static ConversionResult convert(XSSFWorkbook book, long pixels) throws Exception {
        var bytes = new ByteArrayOutputStream(); book.write(bytes);
        return new OfficeMarkdownService(limits(pixels)).convert(bytes.toByteArray(), "sample.xlsx");
    }
    private static JsonNode report(ConversionResult result) throws Exception {
        return JSON.readTree(Files.readString(result.files().get("report.json")));
    }
    private static List<JsonNode> blocks(JsonNode report, String type) {
        List<JsonNode> blocks = new ArrayList<>();
        for (JsonNode block : report.path("blocks")) if (type.equals(block.path("type").asText())) blocks.add(block);
        return blocks;
    }
}
