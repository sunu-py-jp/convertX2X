package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcelDrawingAltTextTest {
    private final DrawingExtractor extractor = new DrawingExtractor();

    @Test void xlsxAltHasPresetRotationAndOnlyVisibleUnstruckText() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("図形");
            var drawing = sheet.createDrawingPatriarch();
            var arrow = drawing.createSimpleShape(anchor());
            arrow.setShapeType(ShapeTypes.RIGHT_ARROW);
            arrow.getCTShape().getSpPr().getXfrm().setRot(-30 * 60000);
            arrow.clearText();
            var paragraph = arrow.addNewTextParagraph();
            var deleted = paragraph.addNewTextRun(); deleted.setText("削除秘密"); deleted.setStrikethrough(true);
            paragraph.addNewTextRun().setText("承認\n[本文] <b>");
            var hidden = drawing.createSimpleShape(anchor());
            hidden.setShapeType(ShapeTypes.RECT); hidden.setText("非表示秘密");
            hidden.getCTShape().getNvSpPr().getCNvPr().setHidden(true);
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size());
            assertTrue(blocks.getFirst().markdown().startsWith("![右矢印、時計回り330度、承認 \\[本文\\] &lt;b&gt;、基準=シート左上、外接矩形 X="));
            assertTrue(blocks.getFirst().markdown().endsWith("](images/diagram-0001.png)"));
            assertFalse(blocks.toString().contains("秘密"));
            assertEquals(1, workspace.files().size());
        }
    }

    @Test void xlsAltUsesShapeKindSavedClockwiseRotationAndStrikethroughFiltering() throws Exception {
        try (var book = new HSSFWorkbook(); var workspace = workspace()) {
            var shape = book.createSheet("図形").createDrawingPatriarch()
                    .createSimpleShape(new HSSFClientAnchor(0, 0, 0, 0, (short) 0, 0, (short) 5, 10));
            shape.setShapeType(HSSFShapeTypes.Ellipse); shape.setRotationDegree((short) 45);
            var text = new HSSFRichTextString("旧価格新価格");
            var strike = book.createFont(); strike.setStrikeout(true); text.applyFont(0, 3, strike);
            shape.setString(text);
            String markdown = extractor.extract(book.getSheetAt(0), workspace).getFirst().markdown();
            assertTrue(markdown.startsWith("![楕円、時計回り45度、新価格、基準=シート左上、外接矩形 X="));
            assertTrue(markdown.endsWith("](images/diagram-0001.png)"));
            assertFalse(markdown.contains("旧価格"));
        }
    }

    @Test void uniformGroupRotationIsAppliedToSeparateChildrenAndPreservesSourceOrder() throws Exception {
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
            assertEquals(2, blocks.size());
            assertEquals(2, workspace.files().size());
            assertTrue(blocks.getFirst().markdown().startsWith("![長方形、時計回り120度、受付、基準=シート左上、外接矩形 X="));
            assertTrue(blocks.getLast().markdown().startsWith("![楕円、時計回り90度、完了、基準=シート左上、外接矩形 X="));
            assertFalse(blocks.getFirst().markdown().contains("完了"));
            assertFalse(blocks.getLast().markdown().contains("受付"));
            assertFalse(blocks.toString().contains("非表示秘密"));
            assertTrue(blocks.getFirst().firstRow() < blocks.getLast().firstRow(), "Group rotation moves the left child above the right child");
        }
    }

    @Test void reflectedOrNonuniformGroupAnglesAreExplicitlySavedValues() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("変形");
            var group = sheet.createDrawingPatriarch().createGroup(anchor());
            group.setCoordinates(0, 0, 100 * 12700, 100 * 12700);
            group.getCTGroupShape().getGrpSpPr().getXfrm().setFlipH(true);
            var shape = group.createSimpleShape(new XSSFChildAnchor(0, 0, 100 * 12700, 100 * 12700));
            shape.setShapeType(ShapeTypes.RECT); shape.setText("説明");
            shape.getCTShape().getSpPr().getXfrm().setRot(15 * 60000);
            String markdown = extractor.extract(sheet, workspace).getFirst().markdown();
            assertTrue(markdown.contains("時計回り15度（図形の保存値。反転・グループ変形後の角度は未算出）"));
        }
    }

    @Test void standaloneImageKeepsOriginalAlternativeTextAndBytes() throws Exception {
        var image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); image.flush();
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("画像");
            var picture = sheet.createDrawingPatriarch().createPicture(anchor(), book.addPicture(bytes.toByteArray(), Workbook.PICTURE_TYPE_PNG));
            picture.getCTPicture().getNvPicPr().getCNvPr().setDescr("元の画像 [説明]");
            String markdown = extractor.extract(sheet, workspace).getFirst().markdown();
            assertTrue(markdown.startsWith("![画像、時計回り0度、元の画像 \\[説明\\]、基準=シート左上、外接矩形 X="));
            assertTrue(markdown.endsWith("](images/image-0001.png)"));
            assertArrayEquals(bytes.toByteArray(), Files.readAllBytes(workspace.files().get("images/image-0001.png")));
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
            String markdown = extractor.extract(book.getSheetAt(0), workspace).getFirst().markdown();
            // The 120 × 40 pt rectangle rotates around (80, 50), independent of its 20 pt stroke.
            assertTrue(markdown.contains("基準=シート左上、外接矩形 X=60pt、Y=\\-10pt、幅=40pt、高さ=120pt"), markdown);
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
            assertTrue(block.markdown().contains("基準=シート左上、位置・サイズ不明"));
            assertFalse(block.markdown().contains("外接矩形"));
            assertFalse(block.markdown().contains("X="));
            assertEquals(Integer.MAX_VALUE, block.firstRow());
            assertEquals(1, workspace.files().size(), "Fallback rendering remains available without claiming a position");
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
            assertEquals("MARKDOWN_BYTES_LIMIT", error.code());
            assertTrue(workspace.files().isEmpty());
        }
    }

    private static XSSFClientAnchor anchor() { return new XSSFClientAnchor(0, 0, 0, 0, 0, 0, 6, 12); }
    private static ConversionWorkspace workspace() { return new ConversionWorkspace(ConversionLimits.defaults()); }
}
