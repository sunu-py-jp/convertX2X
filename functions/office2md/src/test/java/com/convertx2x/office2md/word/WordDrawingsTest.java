package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
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

    @Test void drawingMlShapeUsesSanitizedCallbackTextAndClockwiseAlt() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String xml = shape("roundRect", 30, "<w:p><w:r><w:t>RAW_SECRET</w:t></w:r></w:p>");
            String markdown = new WordDrawings(workspace).render(xml(xml), document.getPackagePart(), "本文", "p1", node -> "保持\n[説明] <script>");
            assertTrue(markdown.startsWith("![角丸長方形、時計回り30度、保持 \\[説明\\] &lt;script&gt;、基準=本文内、p1、ローカル座標"));
            assertTrue(markdown.contains("外接矩形 X=\\-11.603pt、Y=\\-43.301pt、幅=223.205pt、高さ=186.603pt"));
            assertFalse(markdown.contains("RAW_SECRET"));
            assertTrue(workspace.warningCount() > 0);
            assertPng(workspace, "images/diagram-0001.png");
        }
    }

    @Test void groupComposesChildRotationsButEmitsOneImagePerLeaf() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String children = shape("rect", 15, "") + shape("ellipse", 0, "");
            String group = "<wpg:wgp><wpg:grpSpPr><a:xfrm rot='5400000'><a:off x='0' y='0'/><a:ext cx='2540000' cy='1270000'/><a:chOff x='0' y='0'/><a:chExt cx='2540000' cy='1270000'/></a:xfrm></wpg:grpSpPr>" + children + "</wpg:wgp>";
            String markdown = new WordDrawings(workspace).render(xml(group), document.getPackagePart(), "本文", "p1", node -> "");
            assertTrue(markdown.startsWith("![長方形、時計回り105度、"));
            assertTrue(markdown.contains("](images/diagram-0001.png)\n\n![楕円、時計回り90度、"));
            assertTrue(markdown.endsWith("](images/diagram-0002.png)"));
            assertEquals(2, workspace.files().size());
        }
    }

    @Test void embeddedPictureKeepsOriginalBytesAndAlternativeText() throws Exception {
        byte[] original = png(Color.BLUE);
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String id = document.addPictureData(original, Document.PICTURE_TYPE_PNG);
            String markdown = new WordDrawings(workspace).render(xml(picture(id)), document.getPackagePart(), "本文", "p1", node -> "");
            assertTrue(markdown.startsWith("![画像、時計回り0度、元の画像 \\[説明\\]、基準="));
            assertTrue(markdown.endsWith("](images/image-0001.png)"));
            assertTrue(markdown.contains("外接矩形 X=0pt、Y=0pt、幅=200pt、高さ=100pt"));
            assertArrayEquals(original, Files.readAllBytes(workspace.files().get("images/image-0001.png")));
        }
    }

    @Test void pictureRelationshipResolvesAgainstTheProvidedNotePart() throws Exception {
        byte[] original = png(Color.GREEN);
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            var note = document.getPackage().createPart(PackagingURIHelper.createPartName("/word/footnotes.xml"),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml");
            try (var output = note.getOutputStream()) { output.write("<notes/>".getBytes(StandardCharsets.UTF_8)); }
            var image = document.getPackage().createPart(PackagingURIHelper.createPartName("/word/media/note.png"), "image/png");
            try (var output = image.getOutputStream()) { output.write(original); }
            note.addRelationship(image.getPartName(), TargetMode.INTERNAL, IMAGE_REL, "notePicture");
            String markdown = new WordDrawings(workspace).render(xml(picture("notePicture")), note, "脚注", "note1", node -> "");
            assertTrue(markdown.contains("images/image-0001.png"));
            assertArrayEquals(original, Files.readAllBytes(workspace.files().get("images/image-0001.png")));
        }
    }

    @Test void externalPictureAndOleAreNotExpandedOrIncludedInAssets() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            document.getPackagePart().addExternalRelationship("http://127.0.0.1:1/REMOTE_SECRET", IMAGE_REL, "external");
            WordDrawings drawings = new WordDrawings(workspace);
            String external = drawings.render(xml(picture("external")), document.getPackagePart(), "本文", "p1", node -> "");
            String ole = drawings.render(xml("<w:object><o:OLEObject r:id='external'/></w:object>"), document.getPackagePart(), "本文", "p2", node -> "除去後の説明");
            assertTrue(external.contains("外部画像"));
            assertFalse((external + ole).contains("REMOTE_SECRET"));
            assertTrue(ole.contains("除去後の説明"));
            assertTrue(workspace.files().isEmpty());
        }
    }

    @Test void vmlDrawsNativeShapeSkipsHiddenAndDoesNotDuplicateAlternateFallback() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String body = """
                    <mc:AlternateContent>
                      <mc:Choice Requires="wps"><wps:wsp><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="2540000" cy="1270000"/></a:xfrm><a:prstGeom prst="rect"/></wps:spPr></wps:wsp></mc:Choice>
                      <mc:Fallback><v:rect style="width:200pt;height:100pt"/></mc:Fallback>
                    </mc:AlternateContent>
                    <v:oval style="left:220pt;width:100pt;height:100pt;rotation:-30" fillcolor="#19324d"/>
                    <v:rect style="visibility:hidden;width:100pt;height:100pt"><v:textbox><w:txbxContent><w:p><w:r><w:t>HIDDEN_SECRET</w:t></w:r></w:p></w:txbxContent></v:textbox></v:rect>
                    """;
            String markdown = new WordDrawings(workspace).render(xml(body), document.getPackagePart(), "本文", "p1", node -> "");
            assertTrue(markdown.startsWith("![長方形、時計回り0度、"));
            assertTrue(markdown.contains("](images/diagram-0001.png)\n\n![楕円、時計回り330度、"));
            assertEquals(2, workspace.files().size());
        }
    }

    @Test void reversedVmlLineAndUnsupportedPresetRemainExplicit() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String markdown = new WordDrawings(workspace).render(xml("<v:line from='100pt,0pt' to='0pt,100pt'/>" + shape("star5", 0, "")),
                    document.getPackagePart(), "本文", "p1", node -> "保持");
            assertTrue(markdown.contains("直線"));
            assertTrue(markdown.contains("星（5角）"));
            assertTrue(markdown.contains("未対応"));
            assertPng(workspace, "images/diagram-0001.png");
        }
    }

    @Test void drawingCanvasRespectsPixelLimit() throws Exception {
        var d = ConversionLimits.defaults();
        var limits = new ConversionLimits(d.maxInputBytes(), d.maxSections(), d.maxReadItems(), d.maxTableCells(),
                d.maxMarkdownBytes(), d.maxImages(), d.maxImageBytes(), d.maxOutputBytes(), d.maxShapes(), d.maxGroupDepth(), 10);
        try (var document = new XWPFDocument(); var workspace = new ConversionWorkspace(limits)) {
            var error = assertThrows(ConversionException.class, () -> new WordDrawings(workspace)
                    .render(xml(shape("rect", 0, "")), document.getPackagePart(), "本文", "p1", node -> ""));
            assertEquals("IMAGE_PIXEL_LIMIT", error.code());
            assertTrue(workspace.files().isEmpty());
        }
    }

    @Test void inlineCoordinatesAreLocalAndNeverClaimPagePosition() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String content = "<wp:inline><wp:extent cx='2540000' cy='1270000'/>" + shape("rect", 0, "") + "</wp:inline>";
            String markdown = new WordDrawings(workspace).render(xml(content), document.getPackagePart(), "document", "paragraph:7", n -> "本文");
            assertTrue(markdown.contains("基準=本文内、paragraph:7、ローカル座標（ページ位置未算出）"));
            assertTrue(markdown.contains("外接矩形 X=0pt、Y=0pt、幅=200pt、高さ=100pt"));
        }
    }

    @Test void anchorKeepsPageMarginOffsetsSeparateFromLocalShapeCoordinates() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String content = "<wp:anchor><wp:positionH relativeFrom='page'><wp:posOffset>127000</wp:posOffset></wp:positionH>"
                    + "<wp:positionV relativeFrom='paragraph'><wp:posOffset>-254000</wp:posOffset></wp:positionV>"
                    + shape("rect", 0, "").replace("x='0' y='0'", "x='381000' y='508000'") + "</wp:anchor>";
            String markdown = new WordDrawings(workspace).render(xml(content), document.getPackagePart(), "document", "paragraph:4", n -> "");
            assertTrue(markdown.contains("水平基準=page、保存オフセット=10pt"));
            assertTrue(markdown.contains("垂直基準=paragraph、保存オフセット=\\-20pt"));
            assertTrue(markdown.contains("ローカル座標（ページ位置未算出）"));
            assertTrue(markdown.contains("外接矩形 X=30pt、Y=40pt、幅=200pt、高さ=100pt"));
        }
    }

    @Test void anchorAlignmentAndPercentageRemainSavedUnresolvedSpecifications() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String content = "<wp:anchor><wp:positionH relativeFrom='margin'><wp:align>center</wp:align></wp:positionH>"
                    + "<wp:positionV relativeFrom='page'><wp:posOffsetPct>25000</wp:posOffsetPct></wp:positionV>"
                    + shape("rect", 0, "") + "</wp:anchor>";
            String markdown = new WordDrawings(workspace).render(xml(content), document.getPackagePart(), "document", "paragraph:4", n -> "");
            assertTrue(markdown.contains("水平基準=margin、保存align=center（オフセット未算出）"));
            assertTrue(markdown.contains("垂直基準=page、保存割合=25%（オフセット未算出）"));
            assertFalse(markdown.contains("保存オフセット=0pt"));
        }
    }

    @Test void groupChildBoundsIncludeParentRotationAndOffsetsWithoutRenderingPadding() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String child = shape("rect", 0, "").replace("x='0' y='0'", "x='127000' y='254000'")
                    .replace("cx='2540000' cy='1270000'", "cx='508000' cy='254000'");
            String group = "<wpg:wgp><wpg:grpSpPr><a:xfrm rot='5400000'><a:off x='254000' y='381000'/>"
                    + "<a:ext cx='2540000' cy='1270000'/><a:chOff x='0' y='0'/><a:chExt cx='2540000' cy='1270000'/>"
                    + "</a:xfrm></wpg:grpSpPr>" + child + "</wpg:wgp>";
            String markdown = new WordDrawings(workspace).render(xml(group), document.getPackagePart(), "document", "paragraph:3", n -> "");
            assertTrue(markdown.contains("時計回り90度"));
            assertTrue(markdown.contains("外接矩形 X=130pt、Y=\\-10pt、幅=20pt、高さ=40pt"));
            assertPng(workspace, "images/diagram-0001.png");
        }
    }

    @Test void missingAndNonfiniteCoordinatesNeverExposeDrawingFallbackAsMeasuredZero() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String missing = "<wps:wsp><wps:spPr><a:prstGeom prst='rect'/></wps:spPr></wps:wsp>";
            String malformed = shape("ellipse", 0, "").replace("x='0'", "x='NaN'");
            String markdown = new WordDrawings(workspace).render(xml(missing + malformed), document.getPackagePart(), "document", "paragraph:3", n -> "");
            assertEquals(2, markdown.split("位置・サイズ不明", -1).length - 1);
            assertFalse(markdown.contains("外接矩形 X=0pt"));
            assertTrue(markdown.contains("保存サイズ 幅=200pt、高さ=100pt"));
            assertFalse(markdown.contains("幅=240pt"));
            assertFalse(markdown.contains("NaN"));
        }
    }

    @Test void vmlRespectsSavedReferenceAndMarksAlignmentAsUnresolved() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String absolute = "<v:rect style='position:absolute;left:10pt;top:20pt;width:80pt;height:30pt;mso-position-horizontal-relative:margin;mso-position-vertical-relative:paragraph'/>";
            String centered = "<v:oval style='position:absolute;left:10pt;top:20pt;width:80pt;height:30pt;mso-position-horizontal:center;mso-position-horizontal-relative:page'/>";
            String markdown = new WordDrawings(workspace).render(xml(absolute + centered), document.getPackagePart(), "document", "paragraph:9", n -> "");
            String[] images = markdown.split("\n\n");
            assertTrue(images[0].contains("水平基準=margin、垂直基準=paragraph、保存left=10pt、保存top=20pt"));
            assertTrue(images[0].contains("外接矩形 X=10pt、Y=20pt、幅=80pt、高さ=30pt"));
            assertTrue(images[1].contains("保存horizontal=center"));
            assertTrue(images[1].contains("位置・サイズ不明"));
        }
    }

    @Test void unsupportedPlaceholderStaysBetweenItsNeighboringLeavesInXmlOrder() throws Exception {
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String markdown = new WordDrawings(workspace).render(xml(shape("rect", 0, "") + shape("star5", 0, "")
                    + "<w:object><o:OLEObject/></w:object>" + shape("ellipse", 0, "")),
                    document.getPackagePart(), "document", "paragraph:1", n -> "");
            assertTrue(markdown.indexOf("長方形") < markdown.indexOf("星（5角）"));
            assertTrue(markdown.indexOf("星（5角）") < markdown.indexOf("楕円"));
            assertTrue(markdown.indexOf("星（5角）") < markdown.indexOf("埋め込みオブジェクト"));
            assertTrue(markdown.indexOf("埋め込みオブジェクト") < markdown.indexOf("楕円"));
            assertEquals(2, workspace.files().size());
        }
    }

    @Test void groupedPicturesAreExtractedIndividuallyAndKeepOriginalBytes() throws Exception {
        byte[] original = png(Color.ORANGE);
        try (var document = new XWPFDocument(); var workspace = workspace()) {
            String id = document.addPictureData(original, Document.PICTURE_TYPE_PNG);
            String group = "<wpg:wgp><wpg:grpSpPr><a:xfrm><a:off x='0' y='0'/><a:ext cx='2540000' cy='1270000'/>"
                    + "<a:chOff x='0' y='0'/><a:chExt cx='2540000' cy='1270000'/></a:xfrm></wpg:grpSpPr>"
                    + picture(id) + shape("rect", 0, "") + picture(id).replace("x='0'", "x='127000'") + "</wpg:wgp>";
            String markdown = new WordDrawings(workspace).render(xml(group), document.getPackagePart(), "document", "paragraph:1", n -> "");
            String[] blocks = markdown.split("\n\n");
            assertEquals(3, blocks.length);
            assertTrue(blocks[0].startsWith("![画像")); assertTrue(blocks[1].startsWith("![長方形")); assertTrue(blocks[2].startsWith("![画像"));
            assertTrue(blocks[0].endsWith("](images/image-0001.png)"));
            assertTrue(blocks[2].endsWith("](images/image-0001.png)"));
            assertTrue(blocks[2].contains("外接矩形 X=10pt、Y=0pt"));
            assertArrayEquals(original, Files.readAllBytes(workspace.files().get("images/image-0001.png")));
            assertEquals(2, workspace.files().size());
        }
    }

    private static String shape(String preset, int angle, String paragraphs) {
        return "<wps:wsp><wps:spPr><a:xfrm rot='" + angle * 60000 + "'><a:off x='0' y='0'/><a:ext cx='2540000' cy='1270000'/></a:xfrm><a:prstGeom prst='" + preset
                + "'/></wps:spPr><wps:txbx><w:txbxContent>" + paragraphs + "</w:txbxContent></wps:txbx></wps:wsp>";
    }
    private static String picture(String id) {
        return "<pic:pic><pic:nvPicPr><pic:cNvPr id='1' name='picture' descr='元の画像 [説明]'/></pic:nvPicPr><pic:blipFill><a:blip r:embed='" + id
                + "'/></pic:blipFill><pic:spPr><a:xfrm><a:off x='0' y='0'/><a:ext cx='2540000' cy='1270000'/></a:xfrm></pic:spPr></pic:pic>";
    }
    private static Node xml(String content) throws Exception { return XmlObject.Factory.parse("<w:drawing " + NS + ">" + content + "</w:drawing>").getDomNode(); }
    private static ConversionWorkspace workspace() { return new ConversionWorkspace(ConversionLimits.defaults()); }
    private static byte[] png(Color color) throws Exception {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, color.getRGB());
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); image.flush(); return output.toByteArray();
    }
    private static void assertPng(ConversionWorkspace workspace, String name) throws Exception {
        try (var stream = Files.newInputStream(workspace.files().get(name))) {
            var image = ImageIO.read(stream); assertNotNull(image); assertTrue(image.getWidth() > 0); image.flush();
        }
    }
}
