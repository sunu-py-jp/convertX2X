package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.*;
import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PowerPointCompositeImageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void insideFrameAndPartialTextboxExpandCanvasAndKeepFarShapeSeparate() throws Exception {
        byte[] original = png();
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide();
            picture(slide, deck.addPicture(original, PictureType.PNG), 100, 100, 200, 100);
            var frame = box(slide, 120, 120, 80, 40, null); frame.setLineColor(Color.RED); frame.setLineWidth(3);
            var inside = slide.createTextBox(); inside.setAnchor(rect(210, 115, 60, 25)); inside.setText("内側説明");
            var outside = slide.createTextBox(); outside.setAnchor(rect(280, 180, 140, 50)); outside.setText("はみ出す説明");
            var far = box(slide, 500, 100, 70, 50, Color.GREEN); far.setText("独立図形");
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result), composite = composite(report);
                assertEquals(2, report.path("assets").size(), "One composite and one independent diagram only");
                assertEquals(2, imageCount(md(result)));
                assertEquals(1, calls.size()); assertArrayEquals(original, calls.getFirst());
                assertEquals(1, composite.path("sourceImages").size());
                assertEquals(4, composite.path("metadata").path("sourceIds").size());
                assertEquals(320, composite.path("metadata").path("width").asInt());
                assertEquals(130, composite.path("metadata").path("height").asInt());
                assertEquals(1, md(result).lines().filter(line -> line.equals("内側説明")).count());
                assertEquals(1, md(result).lines().filter(line -> line.equals("はみ出す説明")).count());
                assertEquals(0, report.path("assets").findValuesAsText("sourceKind").stream().filter("embeddedImage"::equals).count());
                BufferedImage image = image(result, composite);
                try {
                    assertEquals(0, alphaAt(image, composite, 400, 110), "Expanded area outside annotation has transparent background");
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 150, 180));
                    assertEquals(Color.RED.getRGB(), at(image, composite, 120, 140));
                    assertTrue(darkPixels(image, composite, 300, 180, 115, 45) > 10,
                            "The part of the textbox beyond the picture is rendered, not erased");
                } finally { image.flush(); }
                for (JsonNode block : report.path("blocks")) if (block.path("type").asText().equals("diagram") && block.has("path")) {
                    BufferedImage standalone = image(result, block);
                    try { assertEquals(Color.WHITE.getRGB(), standalone.getRGB(px(150), px(180)), "Composite layers do not leak into other diagram previews"); }
                    finally { standalone.flush(); }
                }
            }
        }
    }

    @Test void samePictureWithDifferentAnnotationsSharesOriginalOcrButKeepsTwoCompositeImages() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); var data = deck.addPicture(png(), PictureType.PNG);
            picture(slide, data, 50, 100, 200, 100); box(slide, 70, 120, 30, 30, Color.RED);
            picture(slide, data, 350, 100, 200, 100); box(slide, 390, 150, 50, 30, Color.GREEN);
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result);
                assertEquals(2, composites(report).size()); assertEquals(2, report.path("assets").size());
                assertEquals(2, imageCount(md(result))); assertEquals(1, calls.size());
                assertNotEquals(report.path("assets").get(0).path("sha256"), report.path("assets").get(1).path("sha256"));
                assertEquals(2, md(result).lines().filter(line -> line.contains("OCR元画像の文字")).count());
            }
        }
    }

    @Test void shapeAcrossTwoPicturesProducesOneComponentAndNoInventedConnection() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); var data = deck.addPicture(png(), PictureType.PNG);
            picture(slide, data, 100, 100, 100, 80); picture(slide, data, 260, 100, 100, 80);
            box(slide, 180, 120, 100, 40, Color.RED);
            try (var result = convert(deck, new ArrayList<>())) {
                JsonNode report = report(result), composite = composite(report);
                assertEquals(1, report.path("assets").size()); assertEquals(1, imageCount(md(result)));
                assertEquals(2, composite.path("sourceImages").size());
                assertEquals(260, composite.path("metadata").path("width").asInt());
                assertTrue(diagrams(report).stream().allMatch(block -> block.path("edges").isEmpty()));
                BufferedImage image = image(result, composite);
                try { assertEquals(Color.RED.getRGB(), at(image, composite, 230, 140)); }
                finally { image.flush(); }
            }
        }
    }

    @Test void sourceZOrderIsPreservedForShapesBehindAndInFrontOfPicture() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); box(slide, 70, 70, 100, 100, Color.RED);
            picture(slide, deck.addPicture(png(), PictureType.PNG), 100, 100, 120, 100);
            box(slide, 160, 160, 80, 80, Color.GREEN);
            try (var result = convert(deck, new ArrayList<>())) {
                JsonNode composite = composite(report(result)); BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.RED.getRGB(), at(image, composite, 80, 80));
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 120, 120));
                    assertEquals(Color.GREEN.getRGB(), at(image, composite, 180, 180));
                    assertEquals(0, alphaAt(image, composite, 230, 80));
                } finally { image.flush(); }
            }
        }
    }

    @Test void croppedRotatedAndFlippedPictureUsesOnlyVisiblePixelsForCompositeAndOcr() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide();
            var picture = picture(slide, deck.addPicture(twoColorPng(), PictureType.PNG), 100, 100, 200, 100);
            ((CTPicture) picture.getXmlObject()).getBlipFill().addNewSrcRect().setL(50000);
            picture.setRotation(90); picture.setFlipHorizontal(true);
            box(slide, 160, 70, 30, 30, Color.GREEN);
            try (var result = convert(deck, calls)) {
                assertEquals(1, calls.size());
                BufferedImage ocr = ImageIO.read(new ByteArrayInputStream(calls.getFirst()));
                try {
                    assertTrue(ocr.getHeight() > ocr.getWidth());
                    assertEquals(0, colorCount(ocr, Color.RED)); assertTrue(colorCount(ocr, Color.BLUE) > 0);
                } finally { ocr.flush(); }
                JsonNode composite = composite(report(result)); BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 220, 200));
                    assertEquals(Color.GREEN.getRGB(), at(image, composite, 175, 85));
                    assertEquals(0, colorCount(image, Color.RED), "Cropped-out source pixels are never redrawn");
                } finally { image.flush(); }
            }
        }
    }

    @Test void rotatedGroupAndSavedConnectorKeepEntireDiagramAndKnownRelation() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); var group = slide.createGroup();
            group.setAnchor(rect(200, 100, 240, 160)); group.setInteriorAnchor(rect(0, 0, 120, 80)); group.setRotation(90);
            var picture = group.createPicture(deck.addPicture(png(), PictureType.PNG)); picture.setAnchor(rect(10, 10, 60, 20));
            var frame = group.createAutoShape(); frame.setShapeType(ShapeType.RECT); frame.setAnchor(rect(15, 15, 20, 10)); frame.setFillColor(Color.RED);
            var outside = box(slide, 500, 250, 70, 50, Color.GREEN); outside.setText("接続先");
            var line = slide.createConnector(); line.setAnchor(rect(360, 140, 140, 135)); line.setLineColor(Color.BLACK);
            var links = ((CTConnector) line.getXmlObject()).getNvCxnSpPr().getCNvCxnSpPr();
            links.addNewStCxn().setId(frame.getShapeId()); links.addNewEndCxn().setId(outside.getShapeId());
            try (var result = convert(deck, new ArrayList<>())) {
                JsonNode report = report(result), composite = composite(report);
                assertEquals(1, report.path("assets").size()); assertEquals(1, imageCount(md(result)));
                assertEquals(4, composite.path("metadata").path("sourceIds").size());
                assertEquals("resolved", diagrams(report).getFirst().path("edges").get(0).path("connectionResolutionStatus").asText());
                assertTrue(md(result).contains("接続先"));
                BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 350, 175));
                    assertEquals(Color.RED.getRGB(), at(image, composite, 360, 110));
                    assertEquals(Color.GREEN.getRGB(), at(image, composite, 560, 290));
                } finally { image.flush(); }
            }
        }
    }

    @Test void touchOnlyPicturesAndShapesStaySeparateAndUnsupportedMaskFallsBack() throws Exception {
        for (boolean unsupported : new boolean[]{false, true}) try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); var data = deck.addPicture(png(), PictureType.PNG);
            var picture = picture(slide, data, 100, 100, 200, 100);
            if (unsupported) ((CTPicture) picture.getXmlObject()).getSpPr().getPrstGeom()
                    .setPrst(org.openxmlformats.schemas.drawingml.x2006.main.STShapeType.ELLIPSE);
            else picture(slide, data, 300, 100, 150, 100);
            box(slide, unsupported ? 130 : 450, 100, 60, 60, Color.RED);
            List<byte[]> calls = new ArrayList<>();
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result);
                assertEquals(0, composites(report).size());
                assertEquals(2, report.path("assets").size());
                assertEquals(unsupported ? 2 : 3, imageCount(md(result)));
                assertEquals(unsupported ? 0 : 1, calls.size());
                if (unsupported) assertTrue(report.toString().contains("IMAGE_VISIBLE_AREA_UNAVAILABLE"));
            }
        }
    }

    @Test void overlappingPictureChainUsesOneCanvasAndPreservesLayerOrder() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide();
            picture(slide, deck.addPicture(png(Color.BLUE), PictureType.PNG), 100, 100, 120, 100);
            picture(slide, deck.addPicture(png(Color.RED), PictureType.PNG), 200, 100, 120, 100);
            picture(slide, deck.addPicture(png(Color.GREEN), PictureType.PNG), 300, 100, 120, 100);
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result), composite = composite(report);
                assertEquals(1, report.path("assets").size()); assertEquals(1, imageCount(md(result)));
                assertEquals(3, composite.path("sourceImages").size()); assertEquals(3, calls.size());
                assertEquals(320, composite.path("metadata").path("width").asInt());
                assertTrue(diagrams(report).stream().allMatch(block -> block.path("edges").isEmpty()),
                        "Visual overlap does not imply a directional or semantic relationship");
                BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 110, 150));
                    assertEquals(Color.RED.getRGB(), at(image, composite, 210, 150));
                    assertEquals(Color.GREEN.getRGB(), at(image, composite, 310, 150));
                } finally { image.flush(); }
            }
        }
    }

    @Test void rotatedPicturesWithOnlyOverlappingBoundingBoxesDoNotMerge() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); var data = deck.addPicture(png(), PictureType.PNG);
            var rotated = picture(slide, data, 100, 100, 100, 100); rotated.setRotation(45);
            picture(slide, data, 80, 80, 10, 10);
            try (var result = convert(deck, new ArrayList<>())) {
                assertTrue(composites(report(result)).isEmpty());
                assertEquals(2, imageCount(md(result)),
                        "An empty corner of the rotated picture bounds is not a visual overlap");
            }
        }
    }

    @Test void nonFiniteOrOverflowingGroupTransformsAreNotPassedToOverlapGeometry() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var shape = box(deck.createSlide(), 100, 100, 50, 50, Color.RED);
            var identity = new java.awt.geom.AffineTransform();
            for (double scale : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
                var invalid = java.awt.geom.AffineTransform.getScaleInstance(scale, scale);
                assertFalse(PresentationOverlap.intersects(rect(100, 100, 50, 50), invalid,
                        rect(100, 100, 50, 50), identity));
                assertFalse(PresentationOverlap.intersects(rect(100, 100, 50, 50), identity, shape, invalid));
            }
        }
    }

    @Test void tableWithShapeAndTextboxKeepsMarkdownOnceAndUsesNoOcr() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); table(slide, Color.YELLOW);
            box(slide, 270, 120, 70, 40, Color.RED);
            var note = slide.createTextBox(); note.setAnchor(rect(110, 140, 130, 25)); note.setText("ここを確認");
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result), composite = composite(report);
                assertEquals(1, report.path("assets").size()); assertEquals(1, imageCount(md(result)));
                assertEquals(3, composite.path("metadata").path("sourceIds").size());
                assertEquals(240, composite.path("metadata").path("width").asInt());
                assertTrue(composite.path("sourceImages").isEmpty()); assertTrue(calls.isEmpty());
                assertEquals(1, md(result).lines().filter(line -> line.equals("| 品名 | 金額 |")).count());
                assertEquals(1, md(result).lines().filter(line -> line.equals("| サンプル | 500 |")).count());
                assertEquals(1, md(result).lines().filter(line -> line.equals("ここを確認")).count());
                assertTrue(diagrams(report).stream().allMatch(block -> block.path("edges").isEmpty()));
                BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.RED.getRGB(), at(image, composite, 320, 140));
                    assertEquals(Color.YELLOW.getRGB(), at(image, composite, 260, 170));
                    assertEquals(0, alphaAt(image, composite, 330, 110));
                } finally { image.flush(); }
            }
        }
    }

    @Test void tableWithOnlyOverlappingTextboxProducesCompositeWithoutCreatingDiagramNode() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); table(slide, Color.YELLOW);
            var note = slide.createTextBox(); note.setAnchor(rect(250, 140, 120, 30)); note.setText("重なる注記");
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result);
                assertEquals(1, composites(report).size()); assertEquals(1, imageCount(md(result)));
                assertEquals(1, md(result).lines().filter(line -> line.equals("重なる注記")).count());
                assertTrue(diagrams(report).getFirst().path("nodes").isEmpty());
                assertTrue(calls.isEmpty());
            }
        }
    }

    @Test void tableAndPictureKeepOriginalStackingAndDoNotDuplicateEmbeddedImage() throws Exception {
        for (boolean pictureOnTop : new boolean[]{false, true}) try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); var data = deck.addPicture(png(), PictureType.PNG);
            if (!pictureOnTop) picture(slide, data, 250, 140, 100, 80);
            table(slide, Color.YELLOW);
            if (pictureOnTop) picture(slide, data, 250, 140, 100, 80);
            List<byte[]> calls = new ArrayList<>();
            try (var result = convert(deck, calls)) {
                JsonNode report = report(result), composite = composite(report);
                assertEquals(1, report.path("assets").size()); assertEquals(1, imageCount(md(result)));
                assertEquals(1, composite.path("sourceImages").size()); assertEquals(1, calls.size());
                assertEquals(1, md(result).lines().filter(line -> line.equals("| 品名 | 金額 |")).count());
                BufferedImage image = image(result, composite);
                try {
                    assertEquals((pictureOnTop ? Color.BLUE : Color.YELLOW).getRGB(), at(image, composite, 290, 170));
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 330, 200));
                } finally { image.flush(); }
            }
        }
    }

    @Test void standaloneTableAndBoundaryTouchingTextboxRemainMarkdownOnly() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); table(slide, Color.YELLOW);
            var note = slide.createTextBox(); note.setAnchor(rect(300, 100, 120, 30)); note.setText("表の外の注記");
            try (var result = convert(deck, new ArrayList<>())) {
                assertTrue(report(result).path("assets").isEmpty()); assertEquals(0, imageCount(md(result)));
                assertTrue(md(result).contains("| 品名 | 金額 |")); assertTrue(md(result).contains("表の外の注記"));
            }
        }
    }

    @Test void supportedGifIsNormalizedBeforeCompositeAndOcr() throws Exception {
        BufferedImage original = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        var g = original.createGraphics(); g.setColor(Color.BLUE); g.fillRect(0, 0, 30, 20); g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(original, "gif", bytes); original.flush();
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide();
            picture(slide, deck.addPicture(bytes.toByteArray(), PictureType.GIF), 100, 100, 200, 100);
            box(slide, 150, 120, 60, 40, Color.RED);
            try (var result = convert(deck, calls)) {
                JsonNode composite = composite(report(result));
                assertEquals("image/png", composite.path("sourceImages").get(0).path("contentType").asText());
                assertEquals(1, calls.size());
                BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 120, 150));
                    assertEquals(Color.RED.getRGB(), at(image, composite, 180, 140));
                } finally { image.flush(); }
            }
        }
    }

    @Test void embeddedSvgUsesPreparedPixelsForCompositeWithoutNativeSvgRenderer() throws Exception {
        byte[] svg = ("<svg xmlns='http://www.w3.org/2000/svg' width='80' height='60' viewBox='0 0 80 60'>"
                + "<rect width='80' height='60' fill='#0000ff'/></svg>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<byte[]> calls = new ArrayList<>();
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide();
            picture(slide, deck.addPicture(svg, PictureType.SVG), 100, 100, 200, 100);
            box(slide, 150, 120, 60, 40, Color.RED);
            try (var result = convert(deck, calls)) {
                JsonNode composite = composite(report(result));
                assertEquals("image/png", composite.path("sourceImages").get(0).path("contentType").asText());
                assertEquals(1, calls.size());
                BufferedImage image = image(result, composite);
                try {
                    assertEquals(Color.BLUE.getRGB(), at(image, composite, 120, 150));
                    assertEquals(Color.RED.getRGB(), at(image, composite, 180, 140));
                } finally { image.flush(); }
            }
        }
    }

    @Test void titleAndPartialTextboxRenderInCompositeWhileMarkdownTextOccursOnce() throws Exception {
        try (XMLSlideShow deck = deck()) {
            var slide = deck.createSlide(); picture(slide, deck.addPicture(png(), PictureType.PNG), 100, 100, 200, 100);
            var title = slide.createTextBox(); title.setAnchor(rect(120, 110, 140, 25)); title.setText("元のタイトル"); title.setPlaceholder(Placeholder.TITLE);
            var partial = slide.createTextBox(); partial.setAnchor(rect(280, 170, 150, 55)); partial.setText("補足テキスト");
            try (var result = convert(deck, new ArrayList<>())) {
                JsonNode report = report(result);
                assertEquals(1, report.path("assets").size()); assertEquals(1, imageCount(md(result)));
                assertEquals(3, composite(report).path("metadata").path("sourceIds").size());
                assertEquals(1, md(result).lines().filter(line -> line.contains("元のタイトル")).count());
                assertEquals(1, md(result).lines().filter(line -> line.contains("補足テキスト")).count());
                assertTrue(md(result).contains("# 元のタイトル"));
                assertEquals(1, diagrams(report).getFirst().path("nodes").size(), "Ordinary textboxes do not gain duplicate diagram text");
            }
        }
    }

    private static XMLSlideShow deck() { XMLSlideShow deck = new XMLSlideShow(); deck.setPageSize(new Dimension(720, 540)); return deck; }
    private static Rectangle2D rect(double x, double y, double w, double h) { return new Rectangle2D.Double(x, y, w, h); }
    private static XSLFPictureShape picture(XSLFSlide slide, XSLFPictureData data, double x, double y, double w, double h) {
        var picture = slide.createPicture(data); picture.setAnchor(rect(x, y, w, h)); return picture;
    }
    private static XSLFAutoShape box(XSLFSlide slide, double x, double y, double w, double h, Color color) {
        var box = slide.createAutoShape(); box.setShapeType(ShapeType.RECT); box.setAnchor(rect(x, y, w, h)); box.setFillColor(color); box.setLineColor(null); return box;
    }
    private static XSLFTable table(XSLFSlide slide, Color color) {
        var table = slide.createTable(2, 2);
        table.setColumnWidth(0, 100); table.setColumnWidth(1, 100);
        table.setRowHeight(0, 40); table.setRowHeight(1, 40);
        table.setAnchor(rect(100, 100, 200, 80));
        table.getCTTable().getTblPr().setFirstRow(true);
        table.getCell(0, 0).setText("品名"); table.getCell(0, 1).setText("金額");
        table.getCell(1, 0).setText("サンプル"); table.getCell(1, 1).setText("500");
        for (var row : table.getRows()) for (var cell : row.getCells()) {
            cell.setFillColor(color);
            for (var paragraph : cell.getTextParagraphs()) for (var run : paragraph.getTextRuns())
                run.setFontSize(12d);
        }
        return table;
    }
    private static ConversionResult convert(XMLSlideShow deck, List<byte[]> calls) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); deck.write(output);
        return new PowerPointMarkdownConverter(ConversionLimits.defaults()).convert(output.toByteArray(), "composite.pptx", ImageMode.OCR,
                new OcrClient() {
                    public boolean configured() { return true; }
                    public OcrResult recognize(byte[] image, String type) { calls.add(image.clone()); return new OcrResult("OCR元画像の文字"); }
                });
    }
    private static JsonNode report(ConversionResult result) throws Exception { return JSON.readTree(Files.readAllBytes(result.files().get("report.json"))); }
    private static String md(ConversionResult result) throws Exception { return Files.readString(result.files().get("document.md")); }
    private static List<JsonNode> composites(JsonNode report) { return blocks(report, "compositeImage"); }
    private static List<JsonNode> diagrams(JsonNode report) { return blocks(report, "diagram"); }
    private static List<JsonNode> blocks(JsonNode report, String type) {
        List<JsonNode> values = new ArrayList<>(); for (JsonNode block : report.path("blocks")) if (block.path("type").asText().equals(type)) values.add(block); return values;
    }
    private static JsonNode composite(JsonNode report) { assertEquals(1, composites(report).size()); return composites(report).getFirst(); }
    private static BufferedImage image(ConversionResult result, JsonNode block) throws Exception { return ImageIO.read(result.files().get(block.path("path").asText()).toFile()); }
    private static long imageCount(String md) { return md.lines().filter(line -> line.startsWith("![")).count(); }
    private static int px(double point) { return (int) Math.round((point + 6) * 96 / 72); }
    private static int at(BufferedImage image, JsonNode block, double x, double y) {
        return image.getRGB(px(x - block.path("metadata").path("x").asDouble()), px(y - block.path("metadata").path("y").asDouble()));
    }
    private static int alphaAt(BufferedImage image, JsonNode block, double x, double y) { return at(image, block, x, y) >>> 24; }
    private static long darkPixels(BufferedImage image, JsonNode block, int x, int y, int w, int h) {
        long count = 0; for (int cy = y; cy < y + h; cy++) for (int cx = x; cx < x + w; cx++) {
            int rgb = at(image, block, cx, cy); if ((rgb >>> 24) > 0 && (rgb & 0x00ffffff) < 0x404040) count++;
        } return count;
    }
    private static long colorCount(BufferedImage image, Color color) {
        long count = 0; for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) == color.getRGB()) count++; return count;
    }
    private static byte[] png() throws Exception { return png(false); }
    private static byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
        graphics.setColor(color); graphics.fillRect(0, 0, 80, 60); graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); image.flush(); return output.toByteArray();
    }
    private static byte[] twoColorPng() throws Exception { return png(true); }
    private static byte[] png(boolean twoColors) throws Exception {
        BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 80, 60);
        if (twoColors) { graphics.setColor(Color.RED); graphics.fillRect(0, 0, 40, 60); }
        graphics.dispose(); ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); image.flush(); return output.toByteArray();
    }
}
