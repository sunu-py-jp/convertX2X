package com.convertx2x.office2md.conversion;

import static org.junit.jupiter.api.Assertions.*;

import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFChildAnchor;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSimpleShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;

/** Round-trip real XLSX drawings through the same reader used by HTTP and Queue. */
class CompositeExcelImageIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Predicate<Color> RED =
            c -> c.getAlpha() > 200 && c.getRed() > 200 && c.getGreen() < 60 && c.getBlue() < 60;
    private static final Predicate<Color> GREEN =
            c -> c.getAlpha() > 200 && c.getGreen() > 200 && c.getRed() < 60 && c.getBlue() < 60;
    private static final Predicate<Color> BLUE =
            c -> c.getAlpha() > 200 && c.getBlue() > 200 && c.getRed() < 60 && c.getGreen() < 60;
    private static final Predicate<Color> WHITE =
            c -> c.getAlpha() > 200 && c.getRed() > 240 && c.getGreen() > 240 && c.getBlue() > 240;
    private static final Predicate<Color> YELLOW =
            c -> c.getAlpha() > 200 && c.getRed() > 200 && c.getGreen() > 200 && c.getBlue() < 60;

    @Test void internalFrameAndProtrudingAnnotationShareOneTransparentImageWhileDistantShapeStaysSeparate()
            throws Exception {
        byte[] source = solid(Color.WHITE);
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("注釈付き画像").createDrawingPatriarch();
            picture(book, drawing, source, 40, 40, 120, 80);
            frame(drawing, 55, 55, 65, 45, Color.RED);
            var arrow = shape(drawing, 135, 80, 65, 35, ShapeTypes.RIGHT_ARROW, Color.GREEN);
            arrow.setText("画像の注釈");
            shape(drawing, 300, 40, 70, 40, ShapeTypes.RECT, Color.BLUE).setText("離れた図形");
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertEquals(1, blocks(report, "compositeImage").size());
                assertEquals(0, blocks(report, "embeddedImage").size(), "Original image must not be emitted again");
                assertAssetsAndReferences(result, report, 2);
                String markdown = markdown(result);
                assertTrue(markdown.contains("画像の注釈"));
                assertTrue(markdown.contains("離れた図形"));
                assertNoOriginalAsset(result, source);
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(image.getWidth() > image.getHeight() * 1.7,
                            "The right-hand annotation must extend the image canvas");
                    assertTrue(pixels(image, RED).count() > 100, "The internal red frame must remain visible");
                    assertTrue(pixels(image, GREEN).count() > 100, "The protruding arrow must remain visible");
                    assertTrue(pixels(image, WHITE).count() > 1000, "The original image must remain visible");
                    assertTrue(pixels(image, c -> c.getAlpha() == 0).count() > image.getWidth() * image.getHeight() / 10,
                            "Unoccupied space around the protruding annotation must remain transparent");
                    assertEquals(0, pixels(image, BLUE).count(), "An unrelated distant shape must not join the image");
                } finally { image.flush(); }
            }
        }
    }

    @Test void repeatedOriginalWithDifferentFramesProducesTwoCompositesButRecognizesOriginalOnce() throws Exception {
        byte[] source = solid(Color.WHITE);
        var ocr = new RecordingOcr(bytes -> assertArrayEquals(source, bytes,
                "Only the original visible image, without annotation pixels, may be submitted to OCR"));
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("同じ画像の別配置").createDrawingPatriarch();
            int imageId = book.addPicture(source, Workbook.PICTURE_TYPE_PNG);
            drawing.createPicture(anchor(20, 20, 120, 80), imageId);
            frame(drawing, 35, 35, 30, 50, Color.RED);
            drawing.createPicture(anchor(240, 20, 120, 80), imageId);
            frame(drawing, 320, 35, 30, 50, Color.RED);
            try (var result = convert(book, ocr, ImageMode.OCR)) {
                JsonNode report = report(result);
                var composites = blocks(report, "compositeImage");
                assertEquals(2, composites.size());
                assertNotEquals(composites.get(0).path("path").asText(), composites.get(1).path("path").asText(),
                        "Placement-specific annotations must not be deduplicated by original image hash");
                assertAssetsAndReferences(result, report, 2);
                assertNoOriginalAsset(result, source);
                assertEquals(1, ocr.calls, "The repeated original must use the shared OCR cache");
                assertEquals(2, occurrences(markdown(result), "元画像の文字"), "OCR text belongs to both placements");
                for (int i = 0; i < 2; i++) {
                    BufferedImage image = compositeImage(result, report, i);
                    try {
                        PixelStats red = pixels(image, RED);
                        assertTrue(red.count() > 100);
                        if (i == 0) assertTrue(red.centerX() < image.getWidth() * .4);
                        else assertTrue(red.centerX() > image.getWidth() * .6);
                    } finally { image.flush(); }
                }
            }
        }
    }

    @Test void shapeCrossingTwoImagesMakesOneComponentWithoutDuplicatingEitherSource() throws Exception {
        byte[] blue = solid(Color.BLUE), yellow = solid(Color.YELLOW);
        var ocr = new RecordingOcr(bytes -> assertTrue(Arrays.equals(blue, bytes) || Arrays.equals(yellow, bytes)));
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("画像を跨ぐ枠").createDrawingPatriarch();
            picture(book, drawing, blue, 20, 30, 100, 80);
            picture(book, drawing, yellow, 150, 30, 100, 80);
            frame(drawing, 90, 40, 100, 60, Color.RED).setText("両画面を確認");
            try (var result = convert(book, ocr, ImageMode.OCR)) {
                JsonNode report = report(result);
                var composite = blocks(report, "compositeImage");
                assertEquals(1, composite.size());
                assertEquals(2, composite.getFirst().path("sourceImages").size());
                assertEquals(0, blocks(report, "embeddedImage").size());
                assertAssetsAndReferences(result, report, 1);
                assertEquals(2, ocr.calls, "Both distinct source images must be recognized once");
                assertTrue(markdown(result).contains("両画面を確認"));
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(pixels(image, BLUE).count() > 1000);
                    assertTrue(pixels(image, YELLOW).count() > 1000);
                    assertTrue(pixels(image, RED).count() > 100);
                } finally { image.flush(); }
            }
        }
    }

    @Test void savedZOrderPreservesShapesBehindAndInFrontOfAnOpaquePicture() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("重なり順").createDrawingPatriarch();
            shape(drawing, 20, 20, 140, 80, ShapeTypes.RECT, Color.RED);
            picture(book, drawing, solid(Color.WHITE), 40, 30, 100, 60);
            shape(drawing, 65, 45, 40, 25, ShapeTypes.RECT, Color.BLUE);
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(pixels(image, RED).count() > 1000, "Back shape remains visible outside the picture");
                    assertTrue(pixels(image, WHITE).count() > 1000, "Opaque picture must cover the back shape");
                    assertTrue(pixels(image, BLUE).count() > 500, "Front shape must cover the picture");
                } finally { image.flush(); }
            }
        }
    }

    @Test void cropRotationAndFlipApplyOnceAndHiddenSourcePixelsNeverReachCompositeOrOcr() throws Exception {
        byte[] source = croppedOrientationSource();
        var ocr = new RecordingOcr(bytes -> {
            BufferedImage image = decode(bytes);
            try {
                assertEquals(0, pixels(image, RED).count(), "The cropped-off red half must not reach OCR");
                assertTrue(pixels(image, BLUE).centerY() < pixels(image, GREEN).centerY(),
                        "After horizontal flip and clockwise rotation, blue must precede green vertically");
            } finally { image.flush(); }
        });
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("切抜き回転反転").createDrawingPatriarch();
            XSSFPicture picture = picture(book, drawing, source, 60, 40, 120, 60);
            picture.getCTPicture().getBlipFill().addNewSrcRect().setL(50000);
            var transform = picture.getCTPicture().getSpPr().getXfrm();
            transform.setRot(90 * 60000);
            transform.setFlipH(true);
            frame(drawing, 100, 40, 30, 40, Color.BLACK);
            try (var result = convert(book, ocr, ImageMode.OCR)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                assertEquals(1, ocr.calls);
                assertNoOriginalAsset(result, source);
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(image.getHeight() > image.getWidth() * 1.5, "Rotated image needs a portrait canvas");
                    assertEquals(0, pixels(image, RED).count(), "The cropped-off red half must not reappear");
                    PixelStats blue = pixels(image, BLUE), green = pixels(image, GREEN);
                    assertTrue(blue.count() > 1000 && green.count() > 1000);
                    assertTrue(blue.centerY() < green.centerY(), "The prepared image must not be transformed twice");
                } finally { image.flush(); }
            }
        }
    }

    @Test void overlapPreservesGroupAndSavedConnectorGraphInTheSameComposite() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("グループと接続").createDrawingPatriarch();
            picture(book, drawing, solid(Color.WHITE), 20, 20, 100, 80);
            var group = drawing.createGroup(anchor(30, 30, 200, 100));
            group.setCoordinates(0, 0, Units.toEMU(200), Units.toEMU(100));
            var overlapping = group.createSimpleShape(childAnchor(0, 0, 50, 40));
            style(overlapping, ShapeTypes.RECT, null, Color.RED);
            overlapping.getCTShape().getNvSpPr().getCNvPr().setId(101);
            overlapping.setText("画像の受付");
            var sibling = group.createSimpleShape(childAnchor(130, 0, 60, 40));
            style(sibling, ShapeTypes.RECT, Color.GREEN, Color.GREEN);
            sibling.getCTShape().getNvSpPr().getCNvPr().setId(102);
            sibling.setText("グループ注記");
            var outside = shape(drawing, 320, 40, 80, 40, ShapeTypes.RECT, Color.BLUE);
            outside.getCTShape().getNvSpPr().getCNvPr().setId(103);
            outside.setText("承認先");
            var connector = drawing.createConnector(anchor(220, 60, 100, 0));
            connector.setShapeType(ShapeTypes.LINE);
            connector.getCTConnector().getNvCxnSpPr().getCNvPr().setId(999);
            var connections = connector.getCTConnector().getNvCxnSpPr().getCNvCxnSpPr();
            connections.addNewStCxn().setId(102);
            connections.addNewEndCxn().setId(103);
            var properties = connector.getCTConnector().getSpPr();
            var line = properties.isSetLn() ? properties.getLn() : properties.addNewLn();
            line.addNewTailEnd().setType(STLineEndType.TRIANGLE);
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                assertEquals(1, blocks(report, "compositeImage").size());
                String markdown = markdown(result);
                assertTrue(markdown.contains("画像の受付"));
                assertTrue(markdown.contains("グループ注記"), "A non-overlapping sibling must stay with its explicit group");
                assertTrue(markdown.contains("承認先"));
                assertTrue(markdown.contains("接続関係:"));
                var drawingBlock = blocks(report, "drawing").getFirst();
                var edges = drawingBlock.path("edges");
                assertEquals(1, edges.size(), "Visual overlap must not invent additional semantic connections");
                assertEquals("resolved", edges.get(0).path("connectionResolutionStatus").asText());
                assertEquals("shape-102", edges.get(0).path("fromId").asText());
                assertEquals("shape-103", edges.get(0).path("toId").asText());
            }
        }
    }

    @Test void touchingImageBoundaryWithoutPositiveOverlapDoesNotCreateAComposite() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("境界接触").createDrawingPatriarch();
            picture(book, drawing, solid(Color.WHITE), 20, 20, 100, 80);
            shape(drawing, 120, 20, 40, 40, ShapeTypes.RECT, Color.RED).setText("隣の図形");
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertEquals(0, blocks(report, "compositeImage").size());
                assertEquals(1, blocks(report, "embeddedImage").size());
                assertAssetsAndReferences(result, report, 2);
                assertTrue(markdown(result).contains("隣の図形"));
            }
        }
    }

    @Test void containedAndProtrudingTextBoxesJoinThePictureAndKeepSearchableText() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("画像上のテキスト").createDrawingPatriarch();
            picture(book, drawing, solid(Color.WHITE), 20, 20, 120, 80);
            var inside = drawing.createTextbox(anchor(35, 35, 70, 30));
            inside.setText("入力欄");
            inside.setNoFill(true);
            var outside = drawing.createTextbox(anchor(130, 65, 90, 45));
            outside.setText("確認して送信");
            outside.setNoFill(true);
            outside.setLineStyleColor(0, 0, 255);
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertEquals(1, blocks(report, "compositeImage").size());
                assertEquals(0, blocks(report, "embeddedImage").size());
                assertAssetsAndReferences(result, report, 1);
                assertTrue(markdown(result).contains("入力欄"));
                assertTrue(markdown(result).contains("確認して送信"));
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(image.getWidth() > 250, "The protruding textbox must extend the canvas");
                    assertTrue(pixels(image, BLUE).count() > 50);
                    assertTrue(pixels(image, c -> c.getAlpha() == 0).count() > 1000);
                } finally { image.flush(); }
            }
        }
    }

    @Test void picturesAloneComposeTransitivelyInSavedZOrderAndDoNotInventConnections() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("画像だけの重なり").createDrawingPatriarch();
            picture(book, drawing, solid(Color.RED), 20, 20, 100, 80);
            picture(book, drawing, solid(Color.GREEN), 90, 20, 100, 80);
            picture(book, drawing, solid(Color.BLUE), 160, 20, 100, 80);
            picture(book, drawing, solid(Color.YELLOW), 260, 20, 100, 80); // Touch only.
            var ocr = new RecordingOcr(bytes -> {});
            try (var result = convert(book, ocr, ImageMode.OCR)) {
                JsonNode report = report(result);
                assertEquals(1, blocks(report, "compositeImage").size());
                assertEquals(3, blocks(report, "compositeImage").getFirst().path("sourceImages").size());
                assertEquals(1, blocks(report, "embeddedImage").size());
                assertAssetsAndReferences(result, report, 2);
                assertEquals(4, ocr.calls);
                assertFalse(markdown(result).contains("接続関係:"));
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(pixels(image, RED).count() > 1000);
                    assertTrue(pixels(image, GREEN).count() > 1000);
                    assertTrue(pixels(image, BLUE).count() > pixels(image, GREEN).count());
                    assertEquals(0, pixels(image, YELLOW).count());
                } finally { image.flush(); }
            }
        }
    }

    @Test void tableAndOverlappingPictureAndFrameRemainOneVisualWithSearchableMarkdownTable() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("表の注釈");
            table(book, sheet);
            var drawing = sheet.createDrawingPatriarch();
            picture(book, drawing, solid(Color.BLUE), 135, 25, 60, 45);
            frame(drawing, 100, 15, 125, 65, Color.RED).setText("確認対象");
            var ocr = new RecordingOcr(bytes -> assertArrayEquals(solidUnchecked(Color.BLUE), bytes));
            try (var result = convert(book, ocr, ImageMode.OCR)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                assertEquals(1, blocks(report, "table").size());
                assertTrue(markdown(result).contains("| **項目** | **状態** |"));
                assertEquals(1, occurrences(markdown(result), "試験項目"));
                assertTrue(markdown(result).contains("確認対象"));
                assertEquals("A1:B3", blocks(report, "compositeImage").getFirst().path("metadata").path("cellRanges").get(0).asText());
                assertEquals(1, ocr.calls, "Cells and native shapes must not be sent to OCR");
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    assertTrue(pixels(image, YELLOW).count() > 2000, "Header fill must appear behind the overlay");
                    assertTrue(pixels(image, BLUE).count() > 1000);
                    assertTrue(pixels(image, RED).count() > 100);
                    assertTrue(pixels(image, c -> c.getAlpha() == 0).count() > 100);
                } finally { image.flush(); }
            }
        }
    }

    @Test void tableWithNativeAnnotationOnlyNeedsNoEmbeddedSourceOrOcr() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("表だけに注釈");
            table(book, sheet);
            var drawing = sheet.createDrawingPatriarch();
            frame(drawing, 110, 25, 80, 55, Color.RED).setText("再確認");
            var ocr = new RecordingOcr(bytes -> fail("Native table contents must not trigger OCR"));
            try (var result = convert(book, ocr, ImageMode.OCR)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                assertEquals(1, blocks(report, "table").size());
                assertEquals(0, blocks(report, "compositeImage").getFirst().path("sourceImages").size());
                assertEquals(0, ocr.calls);
                assertTrue(markdown(result).contains("試験項目"));
            }
        }
    }

    @Test void touchingMergedCellTextExpandsCanvasAndRemovesStrikeThroughBeforeDrawing() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("結合本文");
            sheet.setColumnWidth(0, 16 * 256); sheet.setColumnWidth(1, 16 * 256);
            var row = sheet.createRow(0); row.setHeightInPoints(40);
            var cell = row.createCell(0);
            cell.setCellValue("残す");
            var style = book.createCellStyle();
            style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex());
            style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            cell.setCellStyle(style);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
            var removed = sheet.createRow(2).createCell(0);
            removed.setCellValue("削除文字");
            var deletedFont = book.createFont(); deletedFont.setStrikeout(true);
            var deletedStyle = book.createCellStyle(); deletedStyle.setFont(deletedFont); removed.setCellStyle(deletedStyle);
            var drawing = sheet.createDrawingPatriarch();
            frame(drawing, 130, 10, 40, 20, Color.RED);
            frame(drawing, 10, 60, 40, 15, Color.RED);
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertEquals(1, occurrences(markdown(result), "残す"));
                assertFalse(markdown(result).contains("削除文字"));
                var composites = blocks(report, "compositeImage");
                assertEquals(2, composites.size());
                assertEquals("A1:B1", composites.getFirst().path("metadata").path("cellRanges").get(0).asText());
                BufferedImage image = compositeImage(result, report, 0);
                try { assertTrue(pixels(image, YELLOW).count() > 5000); }
                finally { image.flush(); }
            }
        }
    }

    @Test void standaloneTableDoesNotGainAnUnnecessaryImage() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("通常表");
            table(book, sheet);
            try (var result = convert(book)) {
                assertEquals(1, blocks(report(result), "table").size());
                assertEquals(0, report(result).path("assets").size());
            }
        }
    }

    @Test void diamondAndCurvedConnectorRenderWithTheirPictureInsteadOfBeingDropped() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var drawing = book.createSheet("図形の形状").createDrawingPatriarch();
            picture(book, drawing, solid(Color.WHITE), 0, 0, 120, 100);
            shape(drawing, 10, 10, 60, 60, ShapeTypes.DIAMOND, Color.GREEN).setText("判断");
            var connector = drawing.createConnector(anchor(10, 20, 100, 70));
            connector.setShapeType(ShapeTypes.CURVED_CONNECTOR_3);
            var line = connector.getCTConnector().getSpPr().addNewLn();
            line.addNewSolidFill().addNewSrgbClr().setVal(new byte[]{(byte)255, 0, 0});
            line.setW(3 * 12700);
            line.addNewTailEnd().setType(STLineEndType.TRIANGLE);
            try (var result = convert(book)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                assertTrue(markdown(result).contains("判断"));
                assertFalse(report.toString().contains("SHAPE_UNSUPPORTED"));
                BufferedImage image = compositeImage(result, report, 0);
                try {
                    long green = pixels(image, GREEN).count();
                    assertTrue(green > 1500 && green < 4000, "Diamond must occupy about half its rectangular bounds");
                    assertTrue(pixels(image, RED).count() > 100, "Curved connector and arrowhead must be visible");
                } finally { image.flush(); }
            }
        }
    }

    @Test void legacyXlsPicturesAlsoComposeWhenTheyOverlap() throws Exception {
        try (var book = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            var sheet = book.createSheet("XLS画像");
            var drawing = sheet.createDrawingPatriarch();
            drawing.createPicture(new org.apache.poi.hssf.usermodel.HSSFClientAnchor(0, 0, 0, 0,
                    (short)0, 0, (short)3, 8), book.addPicture(solid(Color.BLUE), Workbook.PICTURE_TYPE_PNG));
            drawing.createPicture(new org.apache.poi.hssf.usermodel.HSSFClientAnchor(0, 0, 0, 0,
                    (short)2, 3, (short)5, 11), book.addPicture(solid(Color.RED), Workbook.PICTURE_TYPE_PNG));
            try (var result = convert(book, OcrClient.disabled(), ImageMode.IGNORE)) {
                JsonNode report = report(result);
                assertAssetsAndReferences(result, report, 1);
                assertEquals(2, blocks(report, "compositeImage").getFirst().path("sourceImages").size());
            }
        }
    }

    private static void table(XSSFWorkbook book, org.apache.poi.xssf.usermodel.XSSFSheet sheet) {
        for (int c = 0; c < 2; c++) sheet.setColumnWidth(c, 20 * 256);
        var body = book.createCellStyle();
        body.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        body.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        body.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        body.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        var header = book.createCellStyle(); header.cloneStyleFrom(body);
        header.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.YELLOW.getIndex());
        header.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        var bold = book.createFont(); bold.setBold(true); header.setFont(bold);
        String[][] rows = {{"項目", "状態"}, {"試験項目", "確認中"}, {"別の項目", "済"}};
        for (int r = 0; r < rows.length; r++) {
            var row = sheet.createRow(r); row.setHeightInPoints(30);
            for (int c = 0; c < rows[r].length; c++) {
                var cell = row.createCell(c); cell.setCellValue(rows[r][c]); cell.setCellStyle(r == 0 ? header : body);
            }
        }
    }

    private static byte[] solidUnchecked(Color color) {
        try { return solid(color); } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static ConversionResult convert(XSSFWorkbook book) throws Exception {
        return convert(book, OcrClient.disabled(), ImageMode.IGNORE);
    }

    private static ConversionResult convert(Workbook book, OcrClient ocr, ImageMode mode) throws Exception {
        var saved = new ByteArrayOutputStream();
        book.write(saved);
        return new OfficeMarkdownService(ConversionLimits.defaults(), ocr)
                .convert(saved.toByteArray(), book instanceof XSSFWorkbook ? "composite.xlsx" : "composite.xls", mode);
    }

    private static XSSFPicture picture(XSSFWorkbook book, XSSFDrawing drawing, byte[] bytes,
            int x, int y, int width, int height) {
        return drawing.createPicture(anchor(x, y, width, height), book.addPicture(bytes, Workbook.PICTURE_TYPE_PNG));
    }

    private static XSSFSimpleShape frame(XSSFDrawing drawing, int x, int y, int width, int height, Color color) {
        var shape = drawing.createSimpleShape(anchor(x, y, width, height));
        style(shape, ShapeTypes.RECT, null, color);
        return shape;
    }

    private static XSSFSimpleShape shape(XSSFDrawing drawing, int x, int y, int width, int height,
            int type, Color fill) {
        var shape = drawing.createSimpleShape(anchor(x, y, width, height));
        style(shape, type, fill, fill);
        return shape;
    }

    private static void style(XSSFSimpleShape shape, int type, Color fill, Color line) {
        shape.setShapeType(type);
        if (fill == null) shape.setNoFill(true);
        else shape.setFillColor(fill.getRed(), fill.getGreen(), fill.getBlue());
        shape.setLineStyleColor(line.getRed(), line.getGreen(), line.getBlue());
        shape.setLineWidth(3);
    }

    /** Point offsets keep the intended geometry independent of installed default worksheet fonts. */
    private static XSSFClientAnchor anchor(int x, int y, int width, int height) {
        return new XSSFClientAnchor(Units.toEMU(x), Units.toEMU(y), Units.toEMU(x + width),
                Units.toEMU(y + height), 0, 0, 0, 0);
    }

    private static XSSFChildAnchor childAnchor(int x, int y, int width, int height) {
        return new XSSFChildAnchor(Units.toEMU(x), Units.toEMU(y),
                Units.toEMU(x + width), Units.toEMU(y + height));
    }

    private static byte[] solid(Color color) throws Exception {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            return png(image);
        } finally { graphics.dispose(); image.flush(); }
    }

    private static byte[] croppedOrientationSource() throws Exception {
        BufferedImage image = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED); graphics.fillRect(0, 0, 60, 60);
            graphics.setColor(Color.GREEN); graphics.fillRect(60, 0, 30, 60);
            graphics.setColor(Color.BLUE); graphics.fillRect(90, 0, 30, 60);
            return png(image);
        } finally { graphics.dispose(); image.flush(); }
    }

    private static byte[] png(BufferedImage image) throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    private static JsonNode report(ConversionResult result) throws Exception {
        return JSON.readTree(result.files().get("report.json").toFile());
    }

    private static String markdown(ConversionResult result) throws Exception {
        return Files.readString(result.files().get("document.md"));
    }

    private static List<JsonNode> blocks(JsonNode report, String type) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode block : report.path("blocks")) if (type.equals(block.path("type").asText())) matches.add(block);
        return matches;
    }

    private static BufferedImage compositeImage(ConversionResult result, JsonNode report, int index) throws Exception {
        var composites = blocks(report, "compositeImage");
        assertTrue(composites.size() > index, "Expected a reported composite image");
        var path = result.files().get(composites.get(index).path("path").asText());
        assertNotNull(path, "The reported composite must exist in the delivered files");
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image);
        return image;
    }

    private static void assertAssetsAndReferences(ConversionResult result, JsonNode report, int expected) throws Exception {
        assertEquals(expected, report.path("assets").size(), "No separate original/annotation asset should leak out");
        assertEquals(expected, result.files().keySet().stream().filter(path -> path.startsWith("images/")).count());
        assertEquals(expected, Pattern.compile("!\\[[^\\r\\n]*\\]\\(images/[^)]+\\)")
                .matcher(markdown(result)).results().count(), "One Markdown image per component");
    }

    private static void assertNoOriginalAsset(ConversionResult result, byte[] original) throws Exception {
        for (var entry : result.files().entrySet()) if (entry.getKey().startsWith("images/"))
            assertFalse(Arrays.equals(original, Files.readAllBytes(entry.getValue())),
                    "The composited original must not also be exported: " + entry.getKey());
    }

    private static int occurrences(String value, String text) {
        int count = 0, offset = 0;
        while ((offset = value.indexOf(text, offset)) >= 0) { count++; offset += text.length(); }
        return count;
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertNotNull(image);
            return image;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static PixelStats pixels(BufferedImage image, Predicate<Color> predicate) {
        long count = 0, xSum = 0, ySum = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if (predicate.test(new Color(image.getRGB(x, y), true))) { count++; xSum += x; ySum += y; }
        return new PixelStats(count, count == 0 ? Double.NaN : (double) xSum / count,
                count == 0 ? Double.NaN : (double) ySum / count);
    }

    private record PixelStats(long count, double centerX, double centerY) { }

    private static final class RecordingOcr implements OcrClient {
        private final Consumer<byte[]> inspect;
        int calls;
        RecordingOcr(Consumer<byte[]> inspect) { this.inspect = inspect; }
        @Override public boolean configured() { return true; }
        @Override public OcrResult recognize(byte[] image, String contentType) {
            calls++;
            assertEquals("image/png", contentType);
            inspect.accept(image);
            return new OcrResult("元画像の文字");
        }
    }
}
