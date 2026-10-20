package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.ImageMode;
import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.DocumentDocument;
import static org.junit.jupiter.api.Assertions.*;

class WordCompositeImagesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NS = """
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:v="urn:schemas-microsoft-com:vml"
            """;

    @Test void separateAnchorsCombineContainedFrameAndPartlyOutsideTextboxWithoutDuplicateAssets() throws Exception {
        String content = text("前の本文")
                + anchor(picture(200, 100), 1, 10, 20, 200, 100, 1)
                + anchor(shape("2", "rect", 30, 30, "", false), 2, 30, 40, 30, 30, 2)
                + text("途中の本文")
                + anchor(shape("3", "textBox", 100, 80, "枠外の注釈", true), 3, 160, 70, 100, 80, 3)
                + anchor(shape("4", "rect", 40, 40, "独立", true), 4, 400, 20, 40, 40, 4)
                + text("後の本文");
        try (var result = convert(paragraph(content))) {
            JsonNode report = report(result), composite = blocks(report, "compositeImage").getFirst();
            assertEquals(1, blocks(report, "compositeImage").size());
            assertEquals(0, blocks(report, "embeddedImage").size());
            assertEquals(2, report.path("assets").size());
            assertEquals(250, composite.path("metadata").path("width").asInt());
            assertEquals(130, composite.path("metadata").path("height").asInt());
            assertEquals(3, composite.path("metadata").path("sourceIds").size());
            String markdown = markdown(result);
            assertEquals(2, markdown.split("!\\[", -1).length - 1);
            assertTrue(markdown.contains("枠外の注釈"));
            assertTrue(markdown.indexOf("前の本文") < markdown.indexOf("images/image-"));
            assertTrue(markdown.indexOf("images/image-") < markdown.indexOf("途中の本文"));
            assertTrue(markdown.indexOf("途中の本文") < markdown.indexOf("後の本文"));
            assertFalse(markdown.contains("\n\n\n\n\n\n"), "Absorbed anchors leave no extra blank drawing blocks");
            BufferedImage image = ImageIO.read(result.files().get(composite.path("path").asText()).toFile());
            assertTrue(image.getWidth() > 200 * 96 / 72);
            assertTrue(image.getHeight() > 100 * 96 / 72);
            assertEquals(0, alpha(image, .9, .1), "Expanded upper-right background is transparent");
            assertTrue(alpha(image, .9, .85) > 240, "The protruding textbox is drawn, not erased");
            image.flush();
        }
    }

    @Test void overlappingPicturesCombineWhileExactEdgeContactAndSeparateShapesStaySeparate() throws Exception {
        String picturesOnly = anchor(picture(200, 100), 1, 0, 0, 200, 100, 1)
                + anchor(picture(200, 100), 3, 20, 20, 200, 100, 3);
        String touchingOnly = anchor(picture(200, 100), 1, 0, 0, 200, 100, 1)
                + anchor(shape("2", "rect", 40, 60, "接触のみ", true), 2, 200, 0, 40, 60, 2)
                + anchor(shape("4", "rect", 40, 60, "離隔", true), 4, 400, 0, 40, 60, 4);
        try (var result = convert(paragraph(picturesOnly) + paragraph(touchingOnly))) {
            JsonNode report = report(result);
            assertEquals(1, blocks(report, "compositeImage").size());
            assertEquals(2, blocks(report, "compositeImage").getFirst().path("sourceImages").size());
            assertEquals(1, blocks(report, "embeddedImage").size());
            assertEquals(4, markdown(result).split("!\\[", -1).length - 1);
        }
    }

    @Test void picturePairsTouchingOnlyAtTheEdgeRemainSeparate() throws Exception {
        String content = anchor(picture(200, 100), 1, 0, 0, 200, 100, 1)
                + anchor(picture(200, 100), 2, 200, 0, 200, 100, 2);
        try (var result = convert(paragraph(content))) {
            assertEquals(0, blocks(report(result), "compositeImage").size());
            assertEquals(2, blocks(report(result), "embeddedImage").size());
        }
    }

    @Test void overlappingPicturesShareOcrAndExpandedCanvasIsTransparent() throws Exception {
        String content = anchor(picture(200, 100), 1, 0, 0, 200, 100, 1)
                + anchor(picture(200, 100), 2, 150, 60, 200, 100, 2);
        List<byte[]> calls = new ArrayList<>();
        OcrClient ocr = new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] bytes, String type) {
                calls.add(bytes); return new OcrResult("同じ画面");
            }
        };
        try (var result = new WordMarkdownConverter(ConversionLimits.defaults())
                .convert(document(paragraph(content)), "sample.docx", ImageMode.OCR, ocr)) {
            JsonNode composite = blocks(report(result), "compositeImage").getFirst();
            assertEquals(1, calls.size());
            assertEquals(1, report(result).path("assets").size());
            assertEquals(350, composite.path("metadata").path("width").asInt());
            assertEquals(160, composite.path("metadata").path("height").asInt());
            assertEquals(1, markdown(result).split("同じ画面", -1).length - 1);
            var image = ImageIO.read(result.files().get(composite.path("path").asText()).toFile());
            assertEquals(0, alpha(image, .9, .1));
            assertTrue(alpha(image, .9, .8) > 240);
            image.flush();
        }
    }

    @Test void sameCoordinatesAcrossParagraphsPagesOrDifferentReferencesStaySeparate() throws Exception {
        String image = anchor(picture(200, 100), 1, 0, 0, 200, 100, 1);
        String shape = anchor(shape("2", "textBox", 100, 60, "注釈", true), 2, 0, 0, 100, 60, 2);
        for (String body : List.of(paragraph(image) + paragraph(shape),
                paragraph(image + "<w:r><w:br w:type='page'/></w:r>" + shape),
                paragraph(image + shape.replace("relativeFrom='page'", "relativeFrom='margin'")),
                paragraph(image + shape.replace("<wp:posOffset>0</wp:posOffset>", "<wp:align>center</wp:align>")),
                paragraph(image + shape.replace("relativeFrom='page'", "relativeFrom='character'")),
                paragraph(image + shape.replace("<wp:anchor ", "<wp:anchor layoutInCell='0' ")),
                paragraph(image + shape.replace("<wp:anchor ", "<wp:anchor allowOverlap='0' ")))) {
            try (var result = convert(body)) {
                assertEquals(0, blocks(report(result), "compositeImage").size());
                assertEquals(2, markdown(result).split("!\\[", -1).length - 1);
            }
        }
    }

    @Test void relativeHeightControlsCompositingInsteadOfXmlOrder() throws Exception {
        String shape = anchor(shape("2", "rect", 200, 100, "", true), 2, 0, 0, 200, 100, 20);
        String picture = anchor(picture(200, 100), 1, 0, 0, 200, 100, 10);
        try (var front = convert(paragraph(shape + picture));
             var back = convert(paragraph(shape.replace("relativeHeight='20'", "relativeHeight='0'") + picture))) {
            var topShape = ImageIO.read(front.files().get("images/image-0001.png").toFile());
            var topPicture = ImageIO.read(back.files().get("images/image-0001.png").toFile());
            Color white = new Color(topShape.getRGB(topShape.getWidth() / 2, topShape.getHeight() / 2));
            Color blue = new Color(topPicture.getRGB(topPicture.getWidth() / 2, topPicture.getHeight() / 2));
            assertTrue(white.getRed() > 240 && white.getBlue() > 240);
            assertTrue(blue.getBlue() > 240 && blue.getRed() < 20);
            topShape.flush(); topPicture.flush();
        }
    }

    @Test void differentAnnotationsShareOriginalOcrAndEachKeepTheirOwnComposite() throws Exception {
        String first = paragraph(anchor(picture(200, 100), 1, 0, 0, 200, 100, 1)
                + anchor(shape("2", "textBox", 100, 60, "注釈A", true), 2, 150, 60, 100, 60, 2));
        String second = paragraph(anchor(picture(200, 100), 3, 0, 0, 200, 100, 1)
                + anchor(shape("4", "textBox", 100, 60, "注釈B", true), 4, 120, 30, 100, 60, 2));
        List<byte[]> calls = new ArrayList<>();
        OcrClient ocr = new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] bytes, String type) {
                calls.add(bytes.clone()); return new OcrResult("元画像内の文字");
            }
        };
        try (var result = new WordMarkdownConverter(ConversionLimits.defaults())
                .convert(document(first + second), "sample.docx", ImageMode.OCR, ocr)) {
            assertEquals(1, calls.size());
            assertArrayEquals(sourceImage(), calls.getFirst());
            assertEquals(2, blocks(report(result), "compositeImage").size());
            assertEquals(2, report(result).path("assets").size());
            assertTrue(markdown(result).contains("注釈A"));
            assertTrue(markdown(result).contains("注釈B"));
            assertEquals(2, markdown(result).split("元画像内の文字", -1).length - 1);
        }
    }

    @Test void oneTextboxOverlappingTwoPicturesCreatesOneComposite() throws Exception {
        String content = anchor(picture(200, 100), 1, 0, 0, 200, 100, 1)
                + anchor(picture(200, 100), 2, 250, 0, 200, 100, 2)
                + anchor(shape("3", "textBox", 100, 60, "両方を見る", true), 3, 180, 10, 100, 60, 3);
        try (var result = convert(paragraph(content))) {
            JsonNode report = report(result);
            assertEquals(1, report.path("assets").size());
            assertEquals(2, blocks(report, "compositeImage").getFirst().path("sourceImages").size());
            assertEquals(1, markdown(result).split("!\\[", -1).length - 1);
        }
    }

    @Test void vmlTextboxWithComparableSavedPageCoordinatesCanOverlayDrawingMlPicture() throws Exception {
        String vml = "<w:r><w:pict><v:shape id='vml-note' type='#_x0000_t202' "
                + "style='position:absolute;left:150pt;top:60pt;width:100pt;height:60pt;"
                + "mso-position-horizontal-relative:page;mso-position-vertical-relative:page;z-index:2'>"
                + "<v:textbox><w:txbxContent><w:p><w:r><w:t>VML注釈</w:t></w:r></w:p>"
                + "</w:txbxContent></v:textbox></v:shape></w:pict></w:r>";
        try (var result = convert(paragraph(anchor(picture(200, 100), 1, 0, 0, 200, 100, 1) + vml))) {
            assertEquals(1, blocks(report(result), "compositeImage").size());
            assertEquals(1, report(result).path("assets").size());
            assertTrue(markdown(result).contains("VML注釈"));
        }
    }

    private static ConversionResult convert(String body) throws Exception {
        return new WordMarkdownConverter(ConversionLimits.defaults()).convert(document(body), "sample.docx");
    }
    static byte[] document(String body) throws Exception {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            String relationship = document.addPictureData(sourceImage(), Document.PICTURE_TYPE_PNG);
            document.getDocument().setBody(DocumentDocument.Factory.parse("<w:document " + NS
                    + "><w:body>" + body.replace("PICTURE_RELATIONSHIP", relationship)
                    + "</w:body></w:document>").getDocument().getBody());
            document.write(bytes);
            return bytes.toByteArray();
        }
    }
    private static byte[] sourceImage() throws Exception {
        var image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 200, 100); graphics.dispose();
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); image.flush(); return out.toByteArray();
    }
    static String paragraph(String content) { return "<w:p>" + content + "</w:p>"; }
    static String text(String text) { return "<w:r><w:t>" + text + "</w:t></w:r>"; }
    static String anchor(String content, int id, int x, int y, int width, int height, int z) {
        return "<w:r><w:drawing><wp:anchor relativeHeight='" + z + "' behindDoc='0' simplePos='0'>"
                + "<wp:positionH relativeFrom='page'><wp:posOffset>" + x * 12700 + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom='page'><wp:posOffset>" + y * 12700 + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx='" + width * 12700 + "' cy='" + height * 12700 + "'/>"
                + "<wp:docPr id='" + id + "' name='item" + id + "'/><a:graphic><a:graphicData>"
                + content + "</a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }
    static String picture(int width, int height) {
        return "<pic:pic><pic:nvPicPr><pic:cNvPr id='1' name='screen'/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed='PICTURE_RELATIONSHIP'/></pic:blipFill><pic:spPr>"
                + transform(width, height) + "<a:prstGeom prst='rect'/></pic:spPr></pic:pic>";
    }
    static String shape(String id, String type, int width, int height, String text, boolean fill) {
        return "<wps:wsp><wps:cNvPr id='" + id + "'/><wps:cNvSpPr txBox='" + type.equals("textBox") + "'/>"
                + "<wps:spPr>" + transform(width, height) + "<a:prstGeom prst='" + type + "'/>"
                + (fill ? "<a:solidFill><a:srgbClr val='FFFFFF'/></a:solidFill>" : "<a:noFill/>")
                + "<a:ln w='25400'><a:solidFill><a:srgbClr val='FF0000'/></a:solidFill></a:ln></wps:spPr>"
                + "<wps:txbx><w:txbxContent><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:txbxContent></wps:txbx>"
                + "<wps:bodyPr/></wps:wsp>";
    }
    private static String transform(int width, int height) {
        return "<a:xfrm><a:off x='0' y='0'/><a:ext cx='" + width * 12700 + "' cy='" + height * 12700 + "'/></a:xfrm>";
    }
    private static JsonNode report(ConversionResult result) throws Exception {
        return JSON.readTree(Files.readString(result.files().get("report.json")));
    }
    private static String markdown(ConversionResult result) throws Exception { return Files.readString(result.files().get("document.md")); }
    private static List<JsonNode> blocks(JsonNode report, String type) {
        List<JsonNode> blocks = new ArrayList<>();
        for (JsonNode block : report.path("blocks")) if (block.path("type").asText().equals(type)) blocks.add(block);
        return blocks;
    }
    private static int alpha(BufferedImage image, double x, double y) {
        return (image.getRGB((int) (image.getWidth() * x), (int) (image.getHeight() * y)) >>> 24) & 255;
    }
}
