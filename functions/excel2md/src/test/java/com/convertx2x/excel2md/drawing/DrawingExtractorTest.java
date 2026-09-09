package com.convertx2x.excel2md.drawing;

import com.convertx2x.excel2md.conversion.ConversionException;
import com.convertx2x.excel2md.conversion.ConversionLimits;
import com.convertx2x.excel2md.conversion.ConversionWorkspace;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.main.STShapeType;
import static org.junit.jupiter.api.Assertions.*;

class DrawingExtractorTest {
    private final DrawingExtractor extractor = new DrawingExtractor();

    @Test void xlsxPicturesKeepOriginalBytesDeduplicateAndRetainEachAnchor() throws Exception {
        byte[] original = png(Color.MAGENTA);
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("画像");
            var drawing = sheet.createDrawingPatriarch();
            int picture = book.addPicture(original, Workbook.PICTURE_TYPE_PNG);
            drawing.createPicture(anchor(2, 3, 4, 6), picture).getCTPicture().getNvPicPr().getCNvPr().setDescr("入力 [画像] <script>");
            drawing.createPicture(anchor(9, 12, 11, 15), picture);
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(2, blocks.size());
            assertEquals(3, blocks.getFirst().firstRow());
            assertEquals(2, blocks.getFirst().firstColumn());
            assertEquals(1, workspace.files().size());
            assertArrayEquals(original, Files.readAllBytes(workspace.files().values().iterator().next()));
            assertTrue(blocks.getFirst().markdown().contains("\\[画像\\] &lt;script&gt;"));
            assertTrue(blocks.getLast().markdown().contains("images/image-0001.png"));
        }
    }

    @Test void xlsPicturesSurviveWorkbookRoundTripAndRetainCoordinates() throws Exception {
        byte[] original = png(Color.ORANGE);
        try (var originalBook = new HSSFWorkbook()) {
            var sheet = originalBook.createSheet("画像");
            int picture = originalBook.addPicture(original, Workbook.PICTURE_TYPE_PNG);
            sheet.createDrawingPatriarch().createPicture(hssfAnchor(3, 4, 5, 7), picture);
            try (Workbook book = roundTrip(originalBook); var workspace = workspace()) {
                List<DrawingBlock> blocks = extractor.extract(book.getSheetAt(0), workspace);
                assertEquals(1, blocks.size());
                assertEquals(4, blocks.getFirst().firstRow());
                assertEquals(3, blocks.getFirst().firstColumn());
                assertArrayEquals(original, Files.readAllBytes(workspace.files().values().iterator().next()));
            }
        }
    }

    @Test void hiddenPictureAndHiddenAnchorAndHiddenSheetAreExcluded() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("非表示");
            var drawing = sheet.createDrawingPatriarch();
            int picture = book.addPicture(png(Color.BLUE), Workbook.PICTURE_TYPE_PNG);
            drawing.createPicture(anchor(1, 1, 3, 3), picture).getCTPicture().getNvPicPr().getCNvPr().setHidden(true);
            drawing.createPicture(anchor(5, 5, 7, 7), picture);
            sheet.createRow(5).setZeroHeight(true);
            drawing.createPicture(anchor(10, 10, 12, 12), picture);
            sheet.setColumnHidden(10, true);
            assertTrue(extractor.extract(sheet, workspace).isEmpty());
            assertTrue(workspace.files().isEmpty());
            book.setSheetHidden(0, true);
            assertTrue(extractor.extract(sheet, workspace).isEmpty());
        }
    }

    @Test void xlsxJapaneseBoldAndStrikeRemovalProduceSamePixelsAsCleanReference() throws Exception {
        byte[] actual = xlsxTextImage(true), reference = xlsxTextImage(false);
        assertArrayEquals(reference, actual, "Deleted text must not affect the rendering or its line wrapping");
        assertVisiblePng(actual);
        var font = DrawingText.font(new DrawingText.Run("日本語・あいうえお", "Missing Gothic", 16, true, false, null));
        assertEquals(-1, font.canDisplayUpTo("日本語・あいうえお"));
    }

    @Test void xlsJapaneseStrikeRemovalProducesSamePixelsAsCleanReference() throws Exception {
        byte[] actual = hssfTextImage(true), reference = hssfTextImage(false);
        assertArrayEquals(reference, actual);
        assertVisiblePng(actual);
    }

    @Test void groupIsOneImageAndAppliesItsChildCoordinateSpace() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("グループ");
            var group = sheet.createDrawingPatriarch().createGroup(anchor(1, 2, 9, 14));
            group.setCoordinates(0, 0, 400 * 12700, 200 * 12700);
            var red = group.createSimpleShape(new XSSFChildAnchor(0, 0, 180 * 12700, 200 * 12700));
            red.setShapeType(ShapeTypes.RECT); red.setFillColor(255, 0, 0); red.setLineStyleColor(255, 0, 0);
            var blue = group.createSimpleShape(new XSSFChildAnchor(220 * 12700, 0, 400 * 12700, 200 * 12700));
            blue.setShapeType(ShapeTypes.ELLIPSE); blue.setFillColor(0, 0, 255); blue.setLineStyleColor(0, 0, 255);
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size());
            assertTrue(blocks.getFirst().markdown().contains("images/diagram-0001.png"));
            BufferedImage image = image(workspace);
            try {
                assertTrue(countColor(image, Color.RED) > 1000);
                assertTrue(countColor(image, Color.BLUE) > 1000);
                assertTrue(image.getWidth() > image.getHeight());
                assertEquals(2, blocks.getFirst().firstRow());
            } finally { image.flush(); }
        }
    }

    @Test void overlappingShapesAndEmbeddedPictureAreComposedWithoutDuplicateImageBlock() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("図");
            var drawing = sheet.createDrawingPatriarch();
            var rectangle = drawing.createSimpleShape(anchor(1, 1, 8, 12));
            rectangle.setShapeType(ShapeTypes.RECT); rectangle.setFillColor(0, 128, 0);
            drawing.createPicture(anchor(2, 3, 6, 9), book.addPicture(png(Color.RED), Workbook.PICTURE_TYPE_PNG));
            List<DrawingBlock> blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size());
            assertEquals(1, workspace.files().size());
            assertTrue(workspace.files().containsKey("images/diagram-0001.png"));
            BufferedImage image = image(workspace);
            try {
                assertTrue(countColor(image, Color.RED) > 100);
                assertTrue(countColor(image, new Color(0, 128, 0)) > 100);
            } finally { image.flush(); }
        }
    }

    @Test void connectorReferencesMergeNonOverlappingObjectsWithoutRerouting() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("接続");
            var drawing = sheet.createDrawingPatriarch();
            var left = drawing.createSimpleShape(anchor(1, 1, 3, 4));
            left.setShapeType(ShapeTypes.RECT);
            var right = drawing.createSimpleShape(anchor(10, 1, 12, 4));
            right.setShapeType(ShapeTypes.ELLIPSE);
            // Deliberately off the boxes: saved connection IDs, not proximity, connect the scene.
            var connector = drawing.createConnector(anchor(4, 10, 9, 11));
            connector.setShapeType(ShapeTypes.LINE);
            var properties = connector.getCTConnector().getNvCxnSpPr().getCNvCxnSpPr();
            properties.addNewStCxn().setId(left.getShapeId());
            properties.addNewEndCxn().setId(right.getShapeId());
            List<DrawingBlock> blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size());
            assertEquals(1, workspace.files().size());
            assertTrue(blocks.getFirst().lastRow() >= 10, "The connector must keep its saved position");
        }
    }

    @Test void unsupportedChartAndShapeRemainLocatedAndDoNotLeakDeletedText() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("未対応");
            var drawing = sheet.createDrawingPatriarch();
            drawing.createChart(anchor(2, 2, 5, 8));
            var star = drawing.createSimpleShape(anchor(10, 20, 15, 26));
            star.getCTShape().getSpPr().getPrstGeom().setPrst(STShapeType.STAR_5);
            star.clearText();
            var paragraph = star.addNewTextParagraph();
            var removed = paragraph.addNewTextRun(); removed.setText("DO_NOT_LEAK"); removed.setStrikethrough(true);
            paragraph.addNewTextRun().setText("残す文字");
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(2, blocks.size());
            assertEquals(2, blocks.getFirst().firstRow());
            String text = blocks.toString();
            assertTrue(text.contains("未対応")); assertTrue(text.contains("残す文字")); assertFalse(text.contains("DO_NOT_LEAK"));
            workspace.finishReport("example.xlsx", "hash");
            assertFalse(Files.readString(workspace.files().get("report.json")).contains("DO_NOT_LEAK"));
        }
    }

    @Test void shapeCountGroupDepthPixelsAndImagePlacementLimitsFailTheConversion() throws Exception {
        ConversionLimits defaults = ConversionLimits.defaults();
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("上限");
            var group = sheet.createDrawingPatriarch().createGroup(anchor(0, 0, 10, 20));
            group.setCoordinates(0, 0, 200 * 12700, 100 * 12700);
            group.createSimpleShape(new XSSFChildAnchor(0, 0, 200 * 12700, 100 * 12700)).setShapeType(ShapeTypes.RECT);
            try (var workspace = new ConversionWorkspace(limits(defaults, 1, 16, 20_000_000, 200))) {
                assertEquals("SHAPE_LIMIT", assertThrows(ConversionException.class, () -> extractor.extract(sheet, workspace)).code());
            }
            try (var workspace = new ConversionWorkspace(limits(defaults, 1000, 1, 20_000_000, 200))) {
                assertEquals("GROUP_DEPTH_LIMIT", assertThrows(ConversionException.class, () -> extractor.extract(sheet, workspace)).code());
            }
            try (var workspace = new ConversionWorkspace(limits(defaults, 1000, 16, 20, 200))) {
                assertEquals("IMAGE_PIXEL_LIMIT", assertThrows(ConversionException.class, () -> extractor.extract(sheet, workspace)).code());
            }
        }
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(limits(defaults, 1000, 16, 20_000_000, 1))) {
            var sheet = book.createSheet("画像");
            var drawing = sheet.createDrawingPatriarch();
            int picture = book.addPicture(png(Color.RED), Workbook.PICTURE_TYPE_PNG);
            drawing.createPicture(anchor(0, 0, 1, 2), picture); drawing.createPicture(anchor(5, 5, 6, 7), picture);
            assertEquals("IMAGE_LIMIT", assertThrows(ConversionException.class, () -> extractor.extract(sheet, workspace)).code());
        }
    }

    @Test void xlsNativeGroupKeepsTwoShapesInOneImageAfterRoundTrip() throws Exception {
        try (var original = new HSSFWorkbook()) {
            var group = original.createSheet("グループ").createDrawingPatriarch().createGroup(hssfAnchor(1, 1, 9, 12));
            group.setCoordinates(0, 0, 1000, 1000);
            var rectangle = group.createShape(new HSSFChildAnchor(0, 0, 400, 1000));
            rectangle.setShapeType(HSSFShapeTypes.Rectangle); rectangle.setFillColor(255, 0, 0);
            var ellipse = group.createShape(new HSSFChildAnchor(600, 0, 1000, 1000));
            ellipse.setShapeType(HSSFShapeTypes.Ellipse); ellipse.setFillColor(0, 0, 255);
            try (Workbook book = roundTrip(original); var workspace = workspace()) {
                assertEquals(1, extractor.extract(book.getSheetAt(0), workspace).size());
                BufferedImage image = image(workspace);
                try { assertTrue(countColor(image, Color.RED) > 100); assertTrue(countColor(image, Color.BLUE) > 100); }
                finally { image.flush(); }
            }
        }
    }

    @Test void renderedDiagramByteLimitIsNotDowngradedToAWarning() throws Exception {
        var defaults = ConversionLimits.defaults();
        var limit = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSheets(), defaults.maxReadCells(), defaults.maxTableCells(),
                defaults.maxMarkdownBytes(), defaults.maxImages(), 100, defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(limit)) {
            var shape = book.createSheet("図形").createDrawingPatriarch().createSimpleShape(anchor(1, 1, 8, 12));
            shape.setShapeType(ShapeTypes.ELLIPSE);
            shape.setFillColor(123, 45, 210);
            assertEquals("IMAGE_BYTES_LIMIT", assertThrows(ConversionException.class,
                    () -> extractor.extract(book.getSheetAt(0), workspace)).code());
        }
    }

    @Test void explicitlyUnsupportedImageFormatRemainsAnOriginalAttachment() throws Exception {
        BufferedImage image = new BufferedImage(20, 15, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", output); image.flush();
        byte[] original = output.toByteArray();
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("添付");
            int picture = book.addPicture(original, Workbook.PICTURE_TYPE_DIB);
            sheet.createDrawingPatriarch().createPicture(anchor(1, 1, 3, 4), picture);
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size());
            assertFalse(blocks.getFirst().markdown().startsWith("!"));
            assertTrue(blocks.getFirst().markdown().contains(".bmp"));
            assertArrayEquals(original, Files.readAllBytes(workspace.files().values().iterator().next()));
            assertTrue(workspace.warningCount() > 0);
        }
    }

    @Test void shapeRotationChangesCanvasAndPreservesItsColor() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var shape = book.createSheet("回転").createDrawingPatriarch().createSimpleShape(anchor(1, 1, 7, 4));
            shape.setShapeType(ShapeTypes.RECT); shape.setFillColor(0, 0, 255);
            shape.getCTShape().getSpPr().getXfrm().setRot(90 * 60000);
            extractor.extract(book.getSheetAt(0), workspace);
            var image = image(workspace);
            try { assertTrue(image.getHeight() > image.getWidth() * 2); assertTrue(countColor(image, Color.BLUE) > 100); }
            finally { image.flush(); }
        }
    }

    private byte[] xlsxTextImage(boolean deletedRun) throws Exception {
        try (var original = new XSSFWorkbook()) {
            var shape = original.createSheet("日本語").createDrawingPatriarch().createSimpleShape(anchor(1, 1, 8, 12));
            shape.setShapeType(ShapeTypes.RECT); shape.setFillColor(255, 255, 255);
            shape.clearText();
            var paragraph = shape.addNewTextParagraph();
            if (deletedRun) {
                var run = paragraph.addNewTextRun(); run.setText("旧情報DO_NOT_LEAK"); run.setStrikethrough(true);
            }
            var run = paragraph.addNewTextRun(); run.setText("日本語の表示確認\n東京都・株式会社\n太字の売上報告");
            run.setFontFamily("ConvertX2X Missing Gothic", (byte) 0, (byte) 0, false); run.setFontSize(18); run.setBold(true);
            try (Workbook book = roundTrip(original); var workspace = workspace()) {
                assertEquals(1, extractor.extract(book.getSheetAt(0), workspace).size());
                return Files.readAllBytes(workspace.files().get("images/diagram-0001.png"));
            }
        }
    }

    private byte[] hssfTextImage(boolean deletedRun) throws Exception {
        try (var original = new HSSFWorkbook()) {
            var shape = original.createSheet("日本語").createDrawingPatriarch().createSimpleShape(hssfAnchor(1, 1, 8, 12));
            shape.setShapeType(HSSFShapeTypes.Rectangle); shape.setFillColor(255, 255, 255);
            var font = original.createFont(); font.setFontName("Missing Mincho"); font.setFontHeightInPoints((short) 18); font.setBold(true);
            var strike = original.createFont(); strike.setFontName("Missing Mincho"); strike.setStrikeout(true);
            String secret = deletedRun ? "旧情報DO_NOT_LEAK" : "";
            var text = new HSSFRichTextString(secret + "日本語の表示確認\n東京都・株式会社");
            if (deletedRun) text.applyFont(0, secret.length(), strike);
            text.applyFont(secret.length(), text.length(), font);
            shape.setString(text);
            try (Workbook book = roundTrip(original); var workspace = workspace()) {
                assertEquals(1, extractor.extract(book.getSheetAt(0), workspace).size());
                return Files.readAllBytes(workspace.files().get("images/diagram-0001.png"));
            }
        }
    }

    private static XSSFClientAnchor anchor(int column, int row, int lastColumn, int lastRow) {
        return new XSSFClientAnchor(0, 0, 0, 0, column, row, lastColumn, lastRow);
    }
    private static HSSFClientAnchor hssfAnchor(int column, int row, int lastColumn, int lastRow) {
        return new HSSFClientAnchor(0, 0, 0, 0, (short) column, row, (short) lastColumn, lastRow);
    }
    private static ConversionWorkspace workspace() { return new ConversionWorkspace(ConversionLimits.defaults()); }
    private static Workbook roundTrip(Workbook book) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); book.write(bytes);
        return WorkbookFactory.create(new ByteArrayInputStream(bytes.toByteArray()));
    }
    private static BufferedImage image(ConversionWorkspace workspace) throws Exception {
        return ImageIO.read(workspace.files().values().iterator().next().toFile());
    }
    private static byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color); graphics.fillRect(0, 0, 30, 20); graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); image.flush();
        return bytes.toByteArray();
    }
    private static int countColor(BufferedImage image, Color color) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if (image.getRGB(x, y) == color.getRGB()) count++;
        return count;
    }
    private static void assertVisiblePng(byte[] bytes) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        try { assertTrue(image.getWidth() > 100); assertTrue(countColor(image, Color.BLACK) > 100); }
        finally { image.flush(); }
    }
    private static ConversionLimits limits(ConversionLimits defaults, int shapes, int depth, long pixels, int images) {
        return new ConversionLimits(defaults.maxInputBytes(), defaults.maxSheets(), defaults.maxReadCells(), defaults.maxTableCells(),
                defaults.maxMarkdownBytes(), images, defaults.maxImageBytes(), defaults.maxOutputBytes(), shapes, depth, pixels);
    }
}
