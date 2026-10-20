package com.convertx2x.excel2img.drawing;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;
import org.openxmlformats.schemas.drawingml.x2006.main.STTextStrikeType;

class ExcelDrawingLayerTest {
    private static final SheetGeometry GRID = new SheetGeometry() {
        public double x(int column) { return column * 40d; }
        public double y(int row) { return row * 20d; }
    };

    @Test void xlsxEmuAnchorAndPictureRotationAreApplied() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            int pictureId = workbook.addPicture(png(2, 2, Color.RED), Workbook.PICTURE_TYPE_PNG);
            var anchor = new XSSFClientAnchor(12700 * 5, 12700 * 3, 12700 * 5, 12700 * 3, 1, 2, 3, 4);
            var picture = sheet.createDrawingPatriarch().createPicture(anchor, pictureId);
            picture.getCTPicture().getSpPr().getXfrm().setRot(90 * 60000);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, warnings::add);
            assertEquals(new Rectangle2D.Double(65, 23, 40, 80), layer.bounds());
            var rendered = render(layer);
            assertEquals(Color.RED.getRGB(), rendered.getRGB(80, 50));
            assertEquals(0, rendered.getRGB(50, 50));
            assertFalse(layer.hasUnsupported());
        }
    }

    @Test void xlsAnchorsUseColumnAndRowFractions() throws Exception {
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet();
            int pictureId = workbook.addPicture(png(2, 2, Color.GREEN), Workbook.PICTURE_TYPE_PNG);
            sheet.createDrawingPatriarch().createPicture(new HSSFClientAnchor(512, 128, 512, 128, (short) 1, 2, (short) 3, 4), pictureId);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, warnings::add);
            assertEquals(new Rectangle2D.Double(60, 50, 80, 40), layer.bounds());
            assertEquals(Color.GREEN.getRGB(), render(layer).getRGB(90, 60));
            assertFalse(layer.hasUnsupported(), warnings.toString());
        }
    }

    @Test void sourcePixelLimitRejectsAtDrawAndKeepsOtherImages() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(); var drawing = sheet.createDrawingPatriarch();
            int large = workbook.addPicture(png(20, 20, Color.RED), Workbook.PICTURE_TYPE_PNG);
            int small = workbook.addPicture(png(2, 2, Color.BLUE), Workbook.PICTURE_TYPE_PNG);
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 2, 4), large);
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 3, 0, 5, 4), small);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 100, warnings::add);
            assertTrue(warnings.isEmpty(), "Preparation must not decode source images");
            assertFalse(layer.hasUnsupported());
            assertTrue(layer.intersectsDrawing(new Rectangle2D.Double(20, 20, 20, 10)));
            assertTrue(warnings.isEmpty(), "Coverage queries must not decode source images");
            var image = render(layer);
            assertTrue(layer.hasUnsupported());
            assertTrue(warnings.stream().anyMatch(w -> w.code().equals("IMAGE_SOURCE_PIXEL_LIMIT")));
            assertNotEquals(Color.RED.getRGB(), image.getRGB(40, 40));
            assertEquals(Color.BLUE.getRGB(), image.getRGB(150, 40));
            render(layer);
            assertEquals(1, warnings.stream().filter(w -> w.code().equals("IMAGE_SOURCE_PIXEL_LIMIT")).count());
        }
    }

    @Test void cropNeverRevealsClippedSourcePixels() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var source = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
            var g = source.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 10, 10); g.setColor(Color.BLUE); g.fillRect(10, 0, 10, 10); g.dispose();
            var bytes = new ByteArrayOutputStream(); ImageIO.write(source, "png", bytes);
            var sheet = workbook.createSheet();
            var picture = sheet.createDrawingPatriarch().createPicture(new XSSFClientAnchor(0, 0, 0, 0, 1, 1, 3, 5), workbook.addPicture(bytes.toByteArray(), Workbook.PICTURE_TYPE_PNG));
            picture.getCTPicture().getBlipFill().addNewSrcRect().setL(50000);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, warnings::add);
            var output = render(layer);
            assertEquals(Color.BLUE.getRGB(), output.getRGB(40, 40), "Crop boundary must not interpolate hidden red pixels");
            assertEquals(Color.BLUE.getRGB(), output.getRGB(50, 40));
            assertEquals(Color.BLUE.getRGB(), output.getRGB(110, 90));
            assertFalse(layer.hasUnsupported(), warnings.toString());
        }
    }

    @Test void strikeThroughTextIsRetainedAndRendered() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            var shape = sheet.createDrawingPatriarch().createTextbox(new XSSFClientAnchor(0, 0, 0, 0, 1, 1, 5, 4));
            shape.setText("KEEP THIS TEXT");
            var run = shape.getCTShape().getTxBody().getPArray(0).getRArray(0);
            (run.isSetRPr() ? run.getRPr() : run.addNewRPr()).setStrike(STTextStrikeType.SNG_STRIKE);
            var paragraphs = ShapeText.readXssf(shape.getCTShape().getDomNode(), workbook);
            assertEquals("KEEP THIS TEXT", paragraphs.getFirst().runs().getFirst().text());
            assertTrue(paragraphs.getFirst().runs().getFirst().strike());
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, ignored -> {});
            var image = render(layer);
            assertTrue(nonWhite(image, 48, 24, 140, 40) > 80, "Text pixels should be visible");
        }
    }

    @Test void bothConnectorArrowHeadsAndFlipAreRendered() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            var line = sheet.createDrawingPatriarch().createConnector(new XSSFClientAnchor(0, 0, 0, 0, 1, 2, 5, 2));
            line.setShapeType(ShapeTypes.LINE);
            var properties = line.getCTConnector().getSpPr();
            var ln = properties.isSetLn() ? properties.getLn() : properties.addNewLn();
            ln.addNewHeadEnd().setType(STLineEndType.TRIANGLE);
            ln.addNewTailEnd().setType(STLineEndType.TRIANGLE);
            properties.getXfrm().setFlipH(true);
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, ignored -> {});
            var image = render(layer);
            assertTrue(nonWhite(image, 41, 37, 6, 6) > 8, "Start arrow must have off-line pixels");
            assertTrue(nonWhite(image, 193, 37, 6, 6) > 8, "End arrow must have off-line pixels");
            assertFalse(layer.hasUnsupported());
        }
    }

    @Test void unsupportedShapeKeepsTextAndDoesNotSuppressSupportedShape() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(); var drawing = sheet.createDrawingPatriarch();
            var unsupported = drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 2, 5));
            unsupported.setShapeType(ShapeTypes.CLOUD); unsupported.setText("RETAINED");
            var supported = drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 3, 0, 5, 5));
            supported.setShapeType(ShapeTypes.DIAMOND); supported.setFillColor(0, 255, 0);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, warnings::add);
            assertTrue(layer.hasUnsupported());
            assertTrue(warnings.stream().anyMatch(w -> w.code().equals("SHAPE_UNSUPPORTED")));
            assertEquals("RETAINED", ShapeText.readXssf(unsupported.getCTShape().getDomNode(), workbook).getFirst().runs().getFirst().text());
            assertEquals(Color.GREEN.getRGB(), render(layer).getRGB(160, 50));
        }
    }

    @Test void hiddenDimensionsCollapsePictureAnchorWithoutDroppingIt() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            sheet.createDrawingPatriarch().createPicture(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 3, 3), workbook.addPicture(png(2, 2, Color.RED), Workbook.PICTURE_TYPE_PNG));
            SheetGeometry hidden = new SheetGeometry() { public double x(int c) { return Math.max(0, c - 1) * 40d; } public double y(int r) { return Math.max(0, r - 1) * 20d; } };
            var layer = ExcelDrawingLayer.prepare(sheet, hidden, 1000, ignored -> {});
            assertEquals(new Rectangle2D.Double(0, 0, 80, 40), layer.bounds());
            assertEquals(Color.RED.getRGB(), render(layer).getRGB(40, 20));
        }
    }

    @Test void xlsxAndXlsGroupCoordinatesPreserveChildShapes() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            var group = sheet.createDrawingPatriarch().createGroup(new XSSFClientAnchor(0, 0, 0, 0, 1, 1, 5, 5));
            group.setCoordinates(0, 0, 1270000, 1270000);
            var child = group.createSimpleShape(new XSSFChildAnchor(317500, 317500, 952500, 952500));
            child.setShapeType(ShapeTypes.RECT); child.setFillColor(255, 0, 0);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, warnings::add);
            assertFalse(layer.hasUnsupported(), warnings.toString());
            var output = render(layer);
            assertEquals(Color.RED.getRGB(), output.getRGB(100, 50));
            assertEquals(0, output.getRGB(60, 30));
        }
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet();
            var group = sheet.createDrawingPatriarch().createGroup(new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 1, (short) 5, 5));
            group.setCoordinates(0, 0, 1000, 1000);
            var child = group.createShape(new HSSFChildAnchor(250, 250, 750, 750));
            child.setShapeType(HSSFShapeTypes.Rectangle); child.setFillColor(0, 0, 255);
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, warnings::add);
            assertFalse(layer.hasUnsupported(), warnings.toString());
            var output = render(layer);
            assertEquals(Color.BLUE.getRGB(), output.getRGB(100, 50));
            assertEquals(0, output.getRGB(60, 30));
        }
    }

    @Test void corruptHugeRasterHeaderIsRejectedBeforeAllocation() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            byte[] bytes = png(2, 2, Color.RED);
            var header = java.nio.ByteBuffer.wrap(bytes);
            header.putInt(16, 100000); header.putInt(20, 100000);
            var crc = new java.util.zip.CRC32(); crc.update(bytes, 12, 17);
            header.putInt(29, (int) crc.getValue());
            var sheet = workbook.createSheet();
            sheet.createDrawingPatriarch().createPicture(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 2, 3), workbook.addPicture(bytes, Workbook.PICTURE_TYPE_PNG));
            var warnings = new ArrayList<ExcelDrawingLayer.Warning>();
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000000, warnings::add);
            render(layer);
            assertTrue(warnings.stream().anyMatch(w -> w.code().equals("IMAGE_SOURCE_PIXEL_LIMIT")));
            assertTrue(layer.hasUnsupported());
        }
    }

    @Test void coverageChecksIndividualDrawingsAndPreservesEmptyGaps() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(); var drawing = sheet.createDrawingPatriarch();
            var left = drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 1, 3));
            left.setShapeType(ShapeTypes.RECT);
            var right = drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 4, 0, 5, 3));
            right.setShapeType(ShapeTypes.RECT);
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, ignored -> {});
            assertTrue(layer.intersectsDrawing(new Rectangle2D.Double(5, 10, 15, 10)));
            assertTrue(layer.intersectsDrawing(new Rectangle2D.Double(170, 10, 15, 10)));
            assertFalse(layer.intersectsDrawing(new Rectangle2D.Double(80, 10, 20, 10)), "The union's empty gap is not coverage");
            assertFalse(layer.intersectsDrawing(new Rectangle2D.Double(20, 100, 10, 10)));
            assertFalse(layer.intersectsDrawing(new Rectangle2D.Double(10, 10, 0, 10)));
            assertFalse(layer.intersectsDrawing(new Rectangle2D.Double(Double.NaN, 0, 10, 10)));
        }
    }

    @Test void coverageIncludesRotatedShapesAndZeroHeightConnectors() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(); var drawing = sheet.createDrawingPatriarch();
            var rotated = drawing.createSimpleShape(new XSSFClientAnchor(0, 0, 0, 0, 1, 2, 3, 4));
            rotated.setShapeType(ShapeTypes.RECT);
            rotated.getCTShape().getSpPr().getXfrm().setRot(90 * 60000);
            var connector = drawing.createConnector(new XSSFClientAnchor(0, 0, 0, 0, 1, 7, 5, 7));
            connector.setShapeType(ShapeTypes.LINE);
            var layer = ExcelDrawingLayer.prepare(sheet, GRID, 1000, ignored -> {});
            assertTrue(layer.intersectsDrawing(new Rectangle2D.Double(70, 21, 10, 10)), "Rotation extends above the original anchor");
            assertFalse(layer.intersectsDrawing(new Rectangle2D.Double(41, 50, 10, 10)), "Original anchor corner is outside the rotated bounds");
            assertTrue(layer.intersectsDrawing(new Rectangle2D.Double(100, 139.5, 10, 1)), "Zero-height line still has stroke coverage");
            assertFalse(layer.intersectsDrawing(new Rectangle2D.Double(100, 145, 10, 1)));
        }
    }

    private static BufferedImage render(ExcelDrawingLayer layer) {
        var image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); try { layer.draw(g); } finally { g.dispose(); } return image;
    }
    private static byte[] png(int width, int height, Color color) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(color); g.fillRect(0, 0, width, height); g.dispose();
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }
    private static int nonWhite(BufferedImage image, int x, int y, int width, int height) {
        int count = 0;
        for (int yy = y; yy < y + height; yy++) for (int xx = x; xx < x + width; xx++) {
            int pixel = image.getRGB(xx, yy);
            if ((pixel >>> 24) != 0 && (pixel & 0xffffff) != 0xffffff) count++;
        }
        return count;
    }
}
