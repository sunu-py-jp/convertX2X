package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlObject;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;
import static org.junit.jupiter.api.Assertions.*;

class WordDrawingsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMAGE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";
    private static final String NS = """
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
            xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:o="urn:schemas-microsoft-com:office:office"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            """;


    @Test void shapeTextIsNativeMarkdownAndJsonAltRoundTripsWithoutRotation() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String text = "保持\n[説明] <script> \\\"";
            String markdown = render(workspace, document, shape("1", "roundRect", 30, "RAW_SECRET"), n -> text);
            assertTrue(markdown.contains("図中の項目"));
            assertTrue(markdown.contains("保持<br>\\[説明\\] &lt;script&gt;"));
            assertFalse(markdown.contains("RAW_SECRET"));
            JsonNode alt = alt(markdown);
            assertEquals(text, alt.path("text").asText());
            assertEquals(-11.603, alt.path("x").asDouble());
            assertEquals(186.603, alt.path("height").asDouble());
            assertEquals(6, alt.size());
            assertFalse(alt.has("rotation")); assertFalse(alt.has("origin")); assertFalse(alt.has("unit"));
            var report = report(workspace);
            assertEquals(alt, report.path("blocks").get(0).path("metadata"));
            assertEquals(text, report.path("blocks").get(0).path("nodes").get(0).path("text").asText());
            assertFalse(report.toString().contains("RAW_SECRET"));
            assertPng(workspace, "images/diagram-0001.png");
        }
    }

    @Test void oneGroupProducesOneCompositePreviewIncludingChildTransforms() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String child = shape("1", "rect", 0, "").replace("x='0' y='0'", "x='127000' y='254000'")
                    .replace("cx='2540000' cy='1270000'", "cx='508000' cy='254000'");
            String content = group(child + shape("2", "ellipse", 0, ""), 90, 20, 30);
            String markdown = render(workspace, document, content, n -> "");
            assertEquals(1, markdown.split("!\\[", -1).length - 1);
            assertEquals(1, workspace.files().size());
            var nodes = report(workspace).path("blocks").get(0).path("nodes");
            assertEquals(2, nodes.size());
            assertEquals(130, nodes.get(0).path("x").asInt());
            assertEquals(-10, nodes.get(0).path("y").asInt());
            assertEquals(20, nodes.get(0).path("width").asInt());
            assertEquals(40, nodes.get(0).path("height").asInt());
        }
    }

    @Test void canvasRetainsSavedConnectionsWithoutGuessingNearbyLabels() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String canvas = "<wpc:wpc xmlns:wpc='http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas'>"
                    + shape("10", "rect", 0, "申請") + shape("20", "ellipse", 0, "承認")
                    + shape("30", "rect", 0, "至急") + connector("40", "10", "20", "oval", "triangle")
                    + connector("41", "20", "10", "triangle", "triangle")
                    + connector("42", "20", "10", "triangle", "diamond")
                    + connector("43", "20", "10", "oval", "diamond") + "</wpc:wpc>";
            String markdown = render(workspace, document, canvas, WordDrawingsTest::allText);
            assertTrue(markdown.contains("申請")); assertTrue(markdown.contains("承認"));
            assertTrue(markdown.contains("→")); assertTrue(markdown.contains("↔"));
            var report = report(workspace);
            var block = report.path("blocks").get(0);
            assertEquals(3, block.path("nodes").size());
            assertEquals(4, block.path("edges").size());
            assertEquals("start-to-end", block.path("edges").get(0).path("direction").asText());
            assertEquals("bidirectional", block.path("edges").get(1).path("direction").asText());
            assertEquals("end-to-start", block.path("edges").get(2).path("direction").asText());
            assertEquals("undirected", block.path("edges").get(3).path("direction").asText());
            var edges = block.path("edges");
            for (var edge : edges) {
                assertEquals("resolved", edge.path("status").asText()); assertEquals("", edge.path("reason").asText());
            }
            assertEquals("oval", edges.get(0).path("startArrow").asText());
            assertEquals("diamond", edges.get(2).path("endArrow").asText());
            assertEquals("oval", edges.get(3).path("startArrow").asText());
            assertEquals("diamond", edges.get(3).path("endArrow").asText());
            assertEquals(edges.get(0).path("startId"), edges.get(0).path("fromId"));
            assertEquals(edges.get(2).path("endId"), edges.get(2).path("fromId"));
            assertFalse(edges.get(3).has("fromId")); assertFalse(edges.get(3).has("toId"));
            assertTrue(markdown.contains("（向きなし）")); assertFalse(markdown.contains("接続関係不明"));
            assertFalse(report.path("warnings").toString().contains("DIAGRAM_CONNECTION_UNRESOLVED"));
            assertFalse(block.path("edges").toString().contains("至急"));
            assertEquals(1, workspace.files().keySet().stream().filter(k -> k.endsWith(".png")).count());
        }
    }

    @Test void missingHiddenDuplicateAndUnknownMarkerConnectionsRemainUnresolved() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String children = shape("10", "rect", 0, "保持")
                    + shape("20", "ellipse", 0, "HIDDEN_SECRET").replace("id='20'", "id='20' hidden='1'")
                    + shape("30", "rect", 0, "重複1") + shape("30", "rect", 0, "重複2")
                    + connector("40", "10", "20", "none", "triangle")
                    + connector("41", "10", "30", "none", "triangle")
                    + connector("42", "10", "", "oval", "diamond")
                    + connector("43", "10", "10", "none", "future-decoration");
            String markdown = render(workspace, document, group(children, 0, 0, 0), WordDrawingsTest::allText);
            assertTrue(markdown.contains("接続関係不明"));
            var report = report(workspace);
            var edges = report.path("blocks").get(0).path("edges");
            assertEquals(4, edges.size());
            for (var edge : edges) assertEquals("unresolved", edge.path("status").asText());
            assertEquals("TARGET_UNAVAILABLE", edges.get(0).path("reason").asText());
            assertEquals("AMBIGUOUS_TARGET", edges.get(1).path("reason").asText());
            assertEquals("MISSING_ENDPOINT", edges.get(2).path("reason").asText());
            assertEquals("undirected", edges.get(2).path("direction").asText());
            assertEquals("oval", edges.get(2).path("startArrow").asText());
            assertEquals("diamond", edges.get(2).path("endArrow").asText());
            assertEquals("UNKNOWN_ARROWHEAD", edges.get(3).path("reason").asText());
            assertEquals("unknown", edges.get(3).path("direction").asText());
            assertEquals("future-decoration", edges.get(3).path("endArrow").asText());
            assertFalse((markdown + report).contains("HIDDEN_SECRET"));
        }
    }

    @Test void independentInlineObjectsAreNeverJoinedAndIdsAreScopedPerDrawing() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String content = "<wp:inline>" + shape("1", "rect", 0, "A") + "</wp:inline>"
                    + "<wp:inline>" + shape("1", "ellipse", 0, "B") + connector("2", "1", "99", "none", "triangle") + "</wp:inline>";
            String markdown = render(workspace, document, content, WordDrawingsTest::allText);
            assertEquals(2, markdown.split("!\\[", -1).length - 1);
            var blocks = report(workspace).path("blocks");
            assertEquals(2, blocks.size());
            assertNotEquals(blocks.get(0).path("range"), blocks.get(1).path("range"));
            assertEquals("unresolved", blocks.get(1).path("edges").get(0).path("status").asText());
        }
    }

    @Test void embeddedPictureUsesSavedAltAndBakesRotationIntoPreviewPixels() throws Exception {
        byte[] original = coloredPicture();
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String id = document.addPictureData(original, Document.PICTURE_TYPE_PNG);
            String picture = picture(id).replace("<a:xfrm>", "<a:xfrm rot='5400000'>");
            String markdown = render(workspace, document, picture, n -> "");
            assertTrue(markdown.contains("元の画像"));
            assertEquals("画像", alt(markdown).path("type").asText());
            assertEquals(100, alt(markdown).path("width").asInt());
            var image = ImageIO.read(workspace.files().get("images/diagram-0001.png").toFile());
            assertTrue(image.getHeight() > image.getWidth());
            Color upper = new Color(image.getRGB(image.getWidth()/2, image.getHeight()/4), true);
            Color lower = new Color(image.getRGB(image.getWidth()/2, image.getHeight()*3/4), true);
            assertTrue(upper.getRed() > 200 && upper.getBlue() < 50);
            assertTrue(lower.getBlue() > 200 && lower.getRed() < 50);
            image.flush();
        }
    }

    @Test void pictureRelationshipUsesTheProvidedNotePartAndGroupsWithNativeShapes() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            var note = document.getPackage().createPart(PackagingURIHelper.createPartName("/word/footnotes.xml"),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml");
            try (var output = note.getOutputStream()) { output.write("<notes/>".getBytes(StandardCharsets.UTF_8)); }
            var image = document.getPackage().createPart(PackagingURIHelper.createPartName("/word/media/note.png"), "image/png");
            try (var output = image.getOutputStream()) { output.write(coloredPicture()); }
            note.addRelationship(image.getPartName(), TargetMode.INTERNAL, IMAGE_REL, "notePicture");
            String markdown = new WordDrawings(workspace).render(xml(group(picture("notePicture") + shape("2", "rect", 0, ""), 0, 0, 0)),
                    note, "脚注", "note1", n -> "");
            assertEquals(1, markdown.split("!\\[", -1).length - 1);
            assertEquals(2, report(workspace).path("blocks").get(0).path("nodes").size());
        }
    }

    @Test void unsupportedImageFormatIsKeptAsAttachmentWithoutParsingItsContent() throws Exception {
        byte[] original = "unsafe unparsed binary".getBytes(StandardCharsets.UTF_8);
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            var image = document.getPackage().createPart(PackagingURIHelper.createPartName("/word/media/test.emf"), "image/x-emf");
            try (var out = image.getOutputStream()) { out.write(original); }
            document.getPackagePart().addRelationship(image.getPartName(), TargetMode.INTERNAL, IMAGE_REL, "vector");
            String markdown = render(workspace, document, picture("vector"), n -> "");
            assertFalse(markdown.contains("!["));
            assertTrue(markdown.contains("images/image-0001.emf"));
            assertArrayEquals(original, Files.readAllBytes(workspace.files().get("images/image-0001.emf")));
            var report = report(workspace);
            assertTrue(report.toString().contains("IMAGE_FORMAT_ATTACHMENT"));
            var attachment = report.path("blocks").get(0).path("attachments").get(0);
            assertEquals("shape-1", attachment.path("id").asText());
            assertEquals("images/image-0001.emf", attachment.path("path").asText());
            assertEquals("元の画像 [説明]", attachment.path("metadata").path("text").asText());
            int altStart = markdown.lastIndexOf("[{") + 1;
            int altEnd = markdown.indexOf("](images/", altStart);
            assertEquals(JSON.readTree(markdown.substring(altStart, altEnd)), attachment.path("metadata"));
        }
    }

    @Test void externalPictureAndOleAreNotExpandedOrIncludedInAssets() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            document.getPackagePart().addExternalRelationship("http://127.0.0.1:1/REMOTE_SECRET", IMAGE_REL, "external");
            WordDrawings drawings = new WordDrawings(workspace);
            String external = drawings.render(xml(picture("external")), document.getPackagePart(), "本文", "p1", n -> "");
            String ole = drawings.render(xml("<w:object><o:OLEObject r:id='external'/></w:object>"), document.getPackagePart(), "本文", "p2", n -> "除去後の説明");
            assertTrue(external.contains("外部画像")); assertTrue(ole.contains("除去後の説明"));
            assertFalse((external + ole).contains("REMOTE_SECRET")); assertTrue(workspace.files().isEmpty());
        }
    }

    @Test void alternateContentChoosesOneBranchAndVmlHiddenShapesAreRemoved() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String content = "<mc:AlternateContent><mc:Choice Requires='wps'>" + shape("1", "rect", 0, "")
                    + "</mc:Choice><mc:Fallback><v:rect style='width:200pt;height:100pt'/></mc:Fallback></mc:AlternateContent>"
                    + "<v:oval style='left:220pt;top:0pt;width:100pt;height:100pt;rotation:-30'/>"
                    + "<v:rect style='visibility:hidden;width:100pt;height:100pt'><v:textbox>HIDDEN_SECRET</v:textbox></v:rect>";
            String markdown = render(workspace, document, content, n -> "");
            assertEquals(2, markdown.split("!\\[", -1).length - 1);
            var report = report(workspace);
            assertEquals(2, report.path("blocks").size());
            assertFalse((markdown + report).contains("HIDDEN_SECRET"));
        }
    }

    @Test void vmlLineArrowIsVisualOnlyWithoutSavedEndpointGraph() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String markdown = render(workspace, document, "<v:line from='100pt,0pt' to='0pt,100pt'><v:stroke endarrow='block'/></v:line>", n -> "");
            assertTrue(markdown.contains("接続関係不明"));
            assertEquals("MISSING_ENDPOINT", report(workspace).path("blocks").get(0).path("edges").get(0).path("reason").asText());
            assertPng(workspace, "images/diagram-0001.png");
        }
    }

    @Test void anchorPlacementStaysInReportSeparateFromLocalGeometry() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String content = "<wp:anchor><wp:positionH relativeFrom='page'><wp:posOffset>127000</wp:posOffset></wp:positionH>"
                    + "<wp:positionV relativeFrom='paragraph'><wp:posOffset>-254000</wp:posOffset></wp:positionV>"
                    + shape("1", "rect", 0, "").replace("x='0' y='0'", "x='381000' y='508000'") + "</wp:anchor>";
            String markdown = render(workspace, document, content, n -> "");
            assertEquals(30, alt(markdown).path("x").asInt());
            assertEquals(40, alt(markdown).path("y").asInt());
            var block = report(workspace).path("blocks").get(0);
            String placement = block.path("placement").asText();
            assertTrue(placement.contains("水平基準=page、保存オフセット=10pt"));
            assertTrue(placement.contains("垂直基準=paragraph、保存オフセット=-20pt"));
            assertTrue(placement.contains("ページ位置未算出"));
        }
    }

    @Test void missingCoordinatesAreNullRatherThanRendererFallbackZero() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String missing = "<wps:wsp><wps:spPr><a:prstGeom prst='rect'/></wps:spPr></wps:wsp>";
            String malformed = shape("2", "ellipse", 0, "").replace("x='0'", "x='NaN'");
            render(workspace, document, missing + malformed, n -> "");
            var blocks = report(workspace).path("blocks");
            assertEquals(2, blocks.size());
            for (var block : blocks) {
                assertTrue(block.path("metadata").path("x").isNull());
                assertTrue(block.path("nodes").get(0).path("x").isNull());
            }
        }
    }

    @Test void unsupportedPlaceholdersStayBetweenIndependentDrawings() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String markdown = render(workspace, document, shape("1", "rect", 0, "") + shape("2", "star5", 0, "")
                    + "<w:object><o:OLEObject/></w:object>" + shape("3", "ellipse", 0, ""), n -> "");
            assertTrue(markdown.indexOf("長方形") < markdown.indexOf("星（5角）"));
            assertTrue(markdown.indexOf("星（5角）") < markdown.indexOf("埋め込みオブジェクト"));
            assertTrue(markdown.indexOf("埋め込みオブジェクト") < markdown.indexOf("楕円"));
            assertEquals(2, workspace.files().size());
        }
    }

    @Test void drawingCanvasRespectsPixelLimit() throws Exception {
        var d = ConversionLimits.defaults();
        var limits = new ConversionLimits(d.maxInputBytes(), d.maxSections(), d.maxReadItems(), d.maxTableCells(),
                d.maxMarkdownBytes(), d.maxImages(), d.maxImageBytes(), d.maxOutputBytes(), d.maxShapes(), d.maxGroupDepth(), 10);
        try (var document = new XWPFDocument(); var workspace = new ConversionWorkspace(limits)) {
            var error = assertThrows(ConversionException.class, () -> render(workspace, document, shape("1", "rect", 0, ""), n -> ""));
            assertEquals("IMAGE_PIXEL_LIMIT", error.code()); assertTrue(workspace.files().isEmpty());
        }
    }

    @Test void realWordConversionKeepsDiagramAtParagraphAnchorAndRemovesHiddenStruckTextFromPreview() throws Exception {
        String clean = shape("1", "rect", 0, "保持");
        String dirty = clean.replace("</w:r></w:p>", "</w:r><w:r><w:rPr><w:strike/></w:rPr><w:t>STRIKE_SECRET</w:t></w:r>"
                + "<w:r><w:rPr><w:vanish/></w:rPr><w:t>HIDDEN_SECRET</w:t></w:r></w:p>");
        String hidden = shape("2", "ellipse", 0, "SHAPE_SECRET").replace("id='2'", "id='2' hidden='1'");
        byte[] cleanDoc = WordMarkdownConverterTest.doc(documentBody(clean), d -> { });
        byte[] dirtyDoc = WordMarkdownConverterTest.doc(documentBody(dirty + hidden), d -> { });
        try (var expected = new WordMarkdownConverter(ConversionLimits.defaults()).convert(cleanDoc, "clean.docx");
             var actual = new WordMarkdownConverter(ConversionLimits.defaults()).convert(dirtyDoc, "dirty.docx")) {
            String md = Files.readString(actual.files().get("document.md"));
            String json = Files.readString(actual.files().get("report.json"));
            assertTrue(md.indexOf("図の前") < md.indexOf("図中の項目"));
            assertTrue(md.indexOf("図中の項目") < md.indexOf("図の後"));
            assertTrue(md.contains("保持"));
            assertFalse((md + json).contains("SECRET"));
            assertArrayEquals(Files.readAllBytes(expected.files().get("images/diagram-0001.png")),
                    Files.readAllBytes(actual.files().get("images/diagram-0001.png")));
        }
    }

    @Test void savedArrowheadChangesPreviewAndIsNotOnlyTextMetadata() throws Exception {
        try (var document = new XWPFDocument(); var plain = workspace(); var arrow = workspace()) {
            render(plain, document, connector("1", "", "", "none", "none"), n -> "");
            render(arrow, document, connector("1", "", "", "none", "triangle"), n -> "");
            var simple = ImageIO.read(plain.files().get("images/diagram-0001.png").toFile());
            var directed = ImageIO.read(arrow.files().get("images/diagram-0001.png").toFile());
            assertTrue(directed.getHeight() > simple.getHeight());
            assertTrue(directed.getWidth() > simple.getWidth());
            simple.flush(); directed.flush();
        }
    }

    @Test void bentConnectorRetainsSavedRelationsAndNativeTextWithExplicitPreviewApproximation() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String connector = connector("3", "1", "2", "none", "triangle").replace("prst='line'", "prst='bentConnector3'")
                    .replace("<wps:bodyPr/>", "<wps:txbx><w:txbxContent><w:p><w:r><w:t>承認済</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr/>");
            String markdown = render(workspace, document,
                    group(shape("1", "rect", 0, "申請") + shape("2", "ellipse", 0, "受理") + connector, 0, 0, 0), WordDrawingsTest::allText);
            assertTrue(markdown.contains("承認済")); assertTrue(markdown.contains("→"));
            var report = report(workspace);
            var edge = report.path("blocks").get(0).path("edges").get(0);
            assertEquals("resolved", edge.path("status").asText());
            assertEquals("承認済", edge.path("text").asText());
            assertTrue(report.path("warnings").toString().contains("直線で近似"));
        }
    }

    @Test void unsupportedGeometryStillHasNativeVertexAndSavedConnections() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String markdown = render(workspace, document,
                    group(shape("1", "rect", 0, "申請") + shape("2", "diamond", 0, "金額を確認")
                            + connector("3", "1", "2", "none", "triangle"), 0, 0, 0), WordDrawingsTest::allText);
            assertTrue(markdown.contains("金額を確認")); assertTrue(markdown.contains("→"));
            var report = report(workspace);
            var block = report.path("blocks").get(0);
            assertEquals(2, block.path("nodes").size());
            assertEquals("金額を確認", block.path("nodes").get(1).path("text").asText());
            assertEquals("resolved", block.path("edges").get(0).path("status").asText());
            assertTrue(report.path("warnings").toString().contains("SHAPE_UNSUPPORTED"));
        }
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String markdown = render(workspace, document, shape("1", "diamond", 0, "判断"), WordDrawingsTest::allText);
            assertTrue(markdown.contains("判断")); assertFalse(markdown.contains("!["));
            var block = report(workspace).path("blocks").get(0);
            assertEquals(1, block.path("nodes").size()); assertFalse(block.has("path"));
        }
    }

    @Test void previewGeometryExcludesUnsupportedShapesAndAttachmentsWhileNodesKeepTheirPositions() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            var image = document.getPackage().createPart(PackagingURIHelper.createPartName("/word/media/test.emf"), "image/x-emf");
            try (var out = image.getOutputStream()) { out.write(new byte[]{1, 2, 3}); }
            document.getPackagePart().addRelationship(image.getPartName(), TargetMode.INTERNAL, IMAGE_REL, "vector");
            String content = shape("1", "rect", 0, "本文")
                    + shape("2", "diamond", 0, "判断").replace("x='0' y='0'", "x='12700000' y='12700000'")
                    + picture("vector").replace("x='0' y='0'", "x='25400000' y='25400000'");
            String markdown = render(workspace, document, group(content, 0, 0, 0), WordDrawingsTest::allText);
            var block = report(workspace).path("blocks").get(0);
            assertEquals(0, block.path("metadata").path("x").asInt());
            assertEquals(0, block.path("metadata").path("y").asInt());
            assertEquals(200, block.path("metadata").path("width").asInt());
            assertEquals(100, block.path("metadata").path("height").asInt());
            assertEquals(block.path("metadata"), alt(markdown));
            assertEquals(1000, block.path("nodes").get(1).path("x").asInt());
            assertEquals(2000, block.path("nodes").get(2).path("x").asInt());
            assertEquals(2000, block.path("attachments").get(0).path("metadata").path("x").asInt());
        }
    }

    private static String documentBody(String shapes) {
        return "<w:p><w:r><w:t>図の前</w:t></w:r></w:p><w:p><w:r><w:drawing " + NS
                + "><wp:inline><wp:extent cx='2540000' cy='1270000'/><wp:docPr id='80' name='flow'/>"
                + "<a:graphic><a:graphicData uri='http://schemas.microsoft.com/office/word/2010/wordprocessingGroup'>"
                + group(shapes, 0, 0, 0) + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
                + "<w:p><w:r><w:t>図の後</w:t></w:r></w:p>";
    }
    private static String allText(Node node) {
        StringBuilder result = new StringBuilder(WordXml.text(node));
        for (Node child : WordXml.children(node)) result.append(allText(child));
        return result.toString();
    }

    private static String shape(String id, String preset, int angle, String text) {
        return "<wps:wsp><wps:cNvPr id='" + id + "'/><wps:cNvSpPr/><wps:spPr><a:xfrm rot='" + angle * 60000
                + "'><a:off x='0' y='0'/><a:ext cx='2540000' cy='1270000'/></a:xfrm><a:prstGeom prst='" + preset
                + "'/></wps:spPr><wps:txbx><w:txbxContent><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp>";
    }
    private static String connector(String id, String start, String end, String head, String tail) {
        return "<wps:wsp><wps:cNvPr id='" + id + "'/><wps:cNvCnPr>"
                + (start.isEmpty() ? "" : "<a:stCxn id='" + start + "'/>") + (end.isEmpty() ? "" : "<a:endCxn id='" + end + "'/>")
                + "</wps:cNvCnPr><wps:spPr><a:xfrm><a:off x='2540000' y='635000'/><a:ext cx='1270000' cy='0'/></a:xfrm>"
                + "<a:prstGeom prst='line'/><a:ln><a:headEnd type='" + head + "'/><a:tailEnd type='" + tail + "'/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp>";
    }
    private static String group(String children, int angle, int x, int y) {
        return "<wpg:wgp><wpg:cNvGrpSpPr/><wpg:grpSpPr><a:xfrm rot='" + angle * 60000 + "'><a:off x='" + x * 12700 + "' y='" + y * 12700
                + "'/><a:ext cx='2540000' cy='1270000'/><a:chOff x='0' y='0'/><a:chExt cx='2540000' cy='1270000'/></a:xfrm></wpg:grpSpPr>" + children + "</wpg:wgp>";
    }
    private static String picture(String id) {
        return "<pic:pic><pic:nvPicPr><pic:cNvPr id='1' name='picture' descr='元の画像 [説明]'/></pic:nvPicPr><pic:blipFill><a:blip r:embed='" + id
                + "'/></pic:blipFill><pic:spPr><a:xfrm><a:off x='0' y='0'/><a:ext cx='2540000' cy='1270000'/></a:xfrm></pic:spPr></pic:pic>";
    }
    private static String render(ConversionWorkspace w, XWPFDocument d, String content, java.util.function.Function<Node,String> text) throws Exception {
        return new WordDrawings(w).render(xml(content), d.getPackagePart(), "本文", "paragraph:1", text);
    }
    private static JsonNode report(ConversionWorkspace workspace) throws Exception {
        workspace.finishReport("test.docx", "test");
        return JSON.readTree(Files.readString(workspace.files().get("report.json")));
    }
    private static JsonNode alt(String markdown) throws Exception {
        int start = markdown.indexOf("![") + 2, end = markdown.indexOf("](images/", start);
        return JSON.readTree(markdown.substring(start, end));
    }
    private static Node xml(String content) throws Exception { return XmlObject.Factory.parse("<w:drawing " + NS + ">" + content + "</w:drawing>").getDomNode(); }
    private static ConversionWorkspace workspace() { return new ConversionWorkspace(ConversionLimits.defaults()); }
    private static byte[] coloredPicture() throws Exception {
        var image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) for (int x = 0; x < 20; x++) image.setRGB(x, y, (x < 10 ? Color.RED : Color.BLUE).getRGB());
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); image.flush(); return output.toByteArray();
    }
    private static void assertPng(ConversionWorkspace workspace, String name) throws Exception {
        try (var stream = Files.newInputStream(workspace.files().get(name))) {
            var image = ImageIO.read(stream); assertNotNull(image); assertTrue(image.getWidth() > 0); image.flush();
        }
    }
}
