package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcelDrawingAltTextTest {
    private final DrawingExtractor extractor = new DrawingExtractor();

    @Test void xlsxBodyContainsOnlyVisibleUnstruckTextAndAltIsParseableJson() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("図形");
            var drawing = sheet.createDrawingPatriarch();
            var arrow = drawing.createSimpleShape(anchor());
            arrow.setShapeType(ShapeTypes.RIGHT_ARROW);
            arrow.getCTShape().getSpPr().getXfrm().setRot(-30 * 60000);
            arrow.clearText();
            var paragraph = arrow.addNewTextParagraph();
            var deleted = paragraph.addNewTextRun(); deleted.setText("削除秘密"); deleted.setStrikethrough(true);
            var visible = paragraph.addNewTextRun(); visible.setText("承認\n[本文] <b>"); visible.setBold(true);
            var hidden = drawing.createSimpleShape(anchor());
            hidden.setShapeType(ShapeTypes.RECT); hidden.setText("非表示秘密");
            hidden.getCTShape().getNvSpPr().getCNvPr().setHidden(true);
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size());
            String md = blocks.getFirst().markdown();
            assertTrue(md.contains("**承認**<br>"), md);
            assertTrue(md.contains("\\[本文\\] &lt;b&gt;"), md);
            assertFalse(blocks.toString().contains("秘密"));
            assertFalse(md.contains("時計回り"));
            assertEquals(1, workspace.files().size());
            var metadata = new ObjectMapper().readTree(imageAlt(md));
            assertEquals("図", metadata.get("type").asText());
            assertEquals(6, metadata.size());
            assertFalse(metadata.has("rotation")); assertFalse(metadata.has("origin")); assertFalse(metadata.has("unit"));
            assertEquals(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(blocks.getFirst().metadata().get("metadata"))), metadata);
        }
    }

    @Test void xlsBodyUsesShapeKindAndStrikethroughFiltering() throws Exception {
        try (var book = new HSSFWorkbook(); var workspace = workspace()) {
            var shape = book.createSheet("図形").createDrawingPatriarch()
                    .createSimpleShape(new HSSFClientAnchor(0, 0, 0, 0, (short) 0, 0, (short) 5, 10));
            shape.setShapeType(HSSFShapeTypes.Ellipse); shape.setRotationDegree((short) 45);
            var text = new HSSFRichTextString("旧価格新価格");
            var strike = book.createFont(); strike.setStrikeout(true); text.applyFont(0, 3, strike);
            shape.setString(text);
            var block = extractor.extract(book.getSheetAt(0), workspace).getFirst();
            assertTrue(block.markdown().contains("楕円"));
            assertTrue(block.markdown().contains("新価格"));
            assertFalse(block.markdown().contains("旧価格"));
            assertFalse(block.markdown().contains("時計回り"));
            assertEquals("新価格", nodes(block).getFirst().get("text"));
        }
    }

    @Test void rotatedGroupIsOnePreviewWithAllNativeTextsInSourceOrder() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("グループ");
            var group = sheet.createDrawingPatriarch().createGroup(anchor());
            var bounds = new SheetCoordinates(sheet).anchor(anchor());
            int width = (int) Math.round(bounds.getWidth() * 12700), height = (int) Math.round(bounds.getHeight() * 12700);
            group.setCoordinates(0, 0, width, height);
            group.getCTGroupShape().getGrpSpPr().getXfrm().setRot(90 * 60000);
            var first = group.createSimpleShape(new XSSFChildAnchor(0, 0, width / 2, height));
            first.setShapeType(ShapeTypes.RECT); first.setText("受付");
            first.getCTShape().getSpPr().getXfrm().setRot(30 * 60000);
            var second = group.createSimpleShape(new XSSFChildAnchor(width / 2, 0, width, height));
            second.setShapeType(ShapeTypes.ELLIPSE); second.setText("完了");
            var hidden = group.createSimpleShape(new XSSFChildAnchor(0, 0, width, height));
            hidden.setShapeType(ShapeTypes.RECT); hidden.setText("非表示秘密");
            hidden.getCTShape().getNvSpPr().getCNvPr().setHidden(true);
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size()); assertEquals(1, workspace.files().size());
            var block = blocks.getFirst();
            assertTrue(block.markdown().indexOf("受付") < block.markdown().indexOf("完了"));
            assertEquals(2, nodes(block).size());
            assertFalse(blocks.toString().contains("非表示秘密"));
            assertTrue(((Number) nodes(block).getFirst().get("y")).doubleValue()
                    < ((Number) nodes(block).getLast().get("y")).doubleValue());
        }
    }

    @Test void reflectedGroupDoesNotExposeRotationMetadata() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("変形");
            var group = sheet.createDrawingPatriarch().createGroup(anchor());
            group.setCoordinates(0, 0, 100 * 12700, 100 * 12700);
            group.getCTGroupShape().getGrpSpPr().getXfrm().setFlipH(true);
            var shape = group.createSimpleShape(new XSSFChildAnchor(0, 0, 100 * 12700, 100 * 12700));
            shape.setShapeType(ShapeTypes.RECT); shape.setText("説明");
            shape.getCTShape().getSpPr().getXfrm().setRot(15 * 60000);
            var block = extractor.extract(sheet, workspace).getFirst();
            assertFalse(block.markdown().contains("時計回り"));
            assertFalse(nodes(block).getFirst().containsKey("rotation"));
            assertTrue(block.markdown().contains("説明"));
        }
    }

    @Test void standaloneImageRendersSavedRotationAndFlipAndRetainsAltAsBodyText() throws Exception {
        var image = new BufferedImage(60, 20, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 30, 20);
        g.setColor(Color.BLUE); g.fillRect(30, 0, 30, 20); g.dispose();
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); image.flush();
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("画像");
            var picture = sheet.createDrawingPatriarch().createPicture(
                    new XSSFClientAnchor(0, 0, 120 * 12700, 40 * 12700, 0, 0, 0, 0),
                    book.addPicture(bytes.toByteArray(), Workbook.PICTURE_TYPE_PNG));
            picture.getCTPicture().getNvPicPr().getCNvPr().setDescr("元の画像 [説明]");
            picture.getCTPicture().getSpPr().getXfrm().setRot(90 * 60000);
            picture.getCTPicture().getSpPr().getXfrm().setFlipH(true);
            var block = extractor.extract(sheet, workspace).getFirst();
            assertTrue(block.markdown().contains("元の画像 \\[説明\\]"));
            BufferedImage rendered = ImageIO.read(workspace.files().get("images/diagram-0001.png").toFile());
            try {
                assertTrue(rendered.getHeight() > rendered.getWidth() * 2);
                assertEquals(Color.BLUE.getRGB(), rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 4));
                assertEquals(Color.RED.getRGB(), rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() * 3 / 4));
            } finally { rendered.flush(); }
        }
    }

    @Test void geometryUsesRotatedPlacementBoundsWithoutStrokeOrCanvasPadding() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var shape = book.createSheet("座標").createDrawingPatriarch().createSimpleShape(
                    new XSSFClientAnchor(20 * 12700, 30 * 12700, 140 * 12700, 70 * 12700, 0, 0, 0, 0));
            shape.setShapeType(ShapeTypes.RECT);
            shape.getCTShape().getSpPr().getXfrm().setRot(90 * 60000);
            var properties = shape.getCTShape().getSpPr();
            (properties.isSetLn() ? properties.getLn() : properties.addNewLn()).setW(20 * 12700);
            var node = nodes(extractor.extract(book.getSheetAt(0), workspace).getFirst()).getFirst();
            assertEquals(60, ((Number) node.get("x")).doubleValue(), .001);
            assertEquals(-10, ((Number) node.get("y")).doubleValue(), .001);
            assertEquals(40, ((Number) node.get("width")).doubleValue(), .001);
            assertEquals(120, ((Number) node.get("height")).doubleValue(), .001);
        }
    }

    @Test void unresolvedGroupTransformDoesNotExposeItsFallbackCoordinates() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("不明");
            var group = sheet.createDrawingPatriarch().createGroup(anchor());
            group.setCoordinates(0, 0, 0, 0);
            var shape = group.createSimpleShape(new XSSFChildAnchor(0, 0, 100 * 12700, 40 * 12700));
            shape.setShapeType(ShapeTypes.RECT); shape.setText("本文");
            var block = extractor.extract(sheet, workspace).getFirst();
            for (String field : List.of("x", "y", "width", "height")) {
                assertNull(nodes(block).getFirst().get(field));
                assertTrue(new ObjectMapper().readTree(imageAlt(block.markdown())).get(field).isNull());
            }
            assertEquals(Integer.MAX_VALUE, block.firstRow());
            assertEquals(1, workspace.files().size());
        }
    }

    @Test void generatedAltRespectsMarkdownLimitBeforeRendering() throws Exception {
        var d = ConversionLimits.defaults();
        var limits = new ConversionLimits(d.maxInputBytes(), d.maxSections(), d.maxReadItems(), d.maxTableCells(),
                10, d.maxImages(), d.maxImageBytes(), d.maxOutputBytes(), d.maxShapes(), d.maxGroupDepth(), d.maxImagePixels());
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(limits)) {
            var sheet = book.createSheet("上限");
            sheet.createDrawingPatriarch().createSimpleShape(anchor()).setShapeType(ShapeTypes.RECT);
            var error = assertThrows(ConversionException.class, () -> extractor.extract(sheet, workspace));
            assertEquals("MARKDOWN_BYTES_LIMIT", error.code()); assertTrue(workspace.files().isEmpty());
        }
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> nodes(DrawingBlock block) {
        return (List<Map<String, Object>>) block.metadata().get("nodes");
    }
    private static String imageAlt(String markdown) {
        String line = markdown.lines().filter(value -> value.startsWith("![")).findFirst().orElseThrow();
        return line.substring(2, line.indexOf("](images/"));
    }
    private static XSSFClientAnchor anchor() { return new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 6, 12); }
    private static ConversionWorkspace workspace() { return new ConversionWorkspace(ConversionLimits.defaults()); }
}
