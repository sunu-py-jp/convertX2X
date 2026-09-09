package com.convertx2x.excel2md.drawing;

import java.awt.Color;
import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFSimpleShape;
import org.apache.poi.hssf.usermodel.HSSFTextbox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFSimpleShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBody;
import org.openxmlformats.schemas.drawingml.x2006.main.STFontCollectionIndex;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;
import static org.junit.jupiter.api.Assertions.*;

class DrawingTextPropertiesTest {
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    @Test void readsSavedParagraphAndRunPropertiesWithInheritanceAfterRoundTrip() throws Exception {
        try (var source = new XSSFWorkbook()) {
            shape(source, """
                    <a:bodyPr anchor="b" wrap="none" rot="1800000">
                      <a:normAutofit fontScale="75000" lnSpcReduction="20000"/>
                    </a:bodyPr>
                    <a:lstStyle>
                      <a:defPPr><a:defRPr sz="1800" b="1" u="sng"><a:latin typeface="Noto Serif CJK JP"/><a:ea typeface="Noto Serif CJK JP"/><a:solidFill><a:srgbClr val="204080"/></a:solidFill></a:defRPr></a:defPPr>
                    </a:lstStyle>
                    <a:p><a:pPr algn="ctr" marL="152400" marR="76200" indent="-38100"><a:lnSpc><a:spcPct val="125000"/></a:lnSpc><a:spcBef><a:spcPts val="300"/></a:spcBef><a:spcAft><a:spcPts val="500"/></a:spcAft></a:pPr>
                      <a:r><a:rPr i="1" spc="150"><a:solidFill><a:srgbClr val="204080"><a:alpha val="50000"/></a:srgbClr></a:solidFill></a:rPr><a:t>日本語</a:t></a:r>
                      <a:r><a:rPr sz="1400" b="0" u="none"><a:solidFill><a:srgbClr val="008040"/></a:solidFill></a:rPr><a:t>override</a:t></a:r>
                    </a:p>
                    <a:p><a:pPr algn="r"><a:lnSpc><a:spcPts val="2200"/></a:lnSpc></a:pPr><a:r><a:t>二段目</a:t></a:r></a:p>
                    """);
            try (XSSFWorkbook book = roundTrip(source)) {
                var shape = (XSSFSimpleShape) book.getSheetAt(0).getDrawingPatriarch().getShapes().getFirst();
                var content = DrawingText.readContent(shape, book);
                assertEquals(3, content.verticalAlignment()); assertFalse(content.wrap());
                assertEquals(.75, content.fontScale(), 1e-9); assertEquals(.2, content.lineSpacingReduction(), 1e-9);
                assertTrue(content.shrinkToFit()); assertEquals(30, content.rotation(), 1e-9);
                assertEquals(2, content.paragraphs().size());
                var first = content.paragraphs().getFirst();
                assertEquals(2, first.alignment()); assertEquals(12, first.marginLeft(), 1e-9);
                assertEquals(6, first.marginRight(), 1e-9); assertEquals(-3, first.indent(), 1e-9);
                assertEquals(125, first.lineSpacing(), 1e-9); assertTrue(first.percentageSpacing());
                assertEquals(3, first.spaceBefore(), 1e-9); assertEquals(5, first.spaceAfter(), 1e-9);
                var inherited = first.runs().getFirst();
                assertEquals("Noto Serif CJK JP", inherited.fontFamily()); assertEquals(18, inherited.fontSize());
                assertTrue(inherited.bold()); assertTrue(inherited.italic()); assertTrue(inherited.underline());
                assertEquals(1.5f, inherited.characterSpacing()); assertEquals(new Color(32, 64, 128, 128), inherited.color());
                var override = first.runs().get(1);
                assertFalse(override.bold()); assertFalse(override.italic()); assertFalse(override.underline());
                assertEquals(14, override.fontSize()); assertEquals(new Color(0, 128, 64), override.color());
                var second = content.paragraphs().get(1);
                assertEquals(3, second.alignment()); assertFalse(second.percentageSpacing());
                assertEquals(22, second.lineSpacing(), 1e-9); assertEquals(new Color(32, 64, 128), second.runs().getFirst().color());
                assertEquals("日本語override\n二段目", DrawingText.plainText(DrawingText.read(shape, book)));
                assertTrue(content.approximations().isEmpty(), content.approximations().toString());
            }
        }
    }

    @Test void resolvesThemeFontReferencePlaceholderColorAndAlphaWithoutWarnings() throws Exception {
        try (var book = new XSSFWorkbook()) {
            installTheme(book);
            var shape = shape(book, """
                    <a:bodyPr/><a:lstStyle/>
                    <a:p>
                      <a:r><a:t>継承</a:t></a:r>
                      <a:r><a:rPr><a:solidFill><a:schemeClr val="phClr"><a:alpha val="50000"/></a:schemeClr></a:solidFill></a:rPr><a:t>半透明</a:t></a:r>
                      <a:r><a:rPr><a:solidFill><a:schemeClr val="accent2"/></a:solidFill></a:rPr><a:t>別テーマ</a:t></a:r>
                      <a:r><a:rPr><a:noFill/></a:rPr><a:t>透明</a:t></a:r>
                    </a:p>
                    """);
            var style = shape.getCTShape().isSetStyle() ? shape.getCTShape().getStyle() : shape.getCTShape().addNewStyle();
            var ref = style.getFontRef() != null ? style.getFontRef() : style.addNewFontRef();
            ref.setIdx(STFontCollectionIndex.MINOR);
            ref.addNewSchemeClr().setVal(STSchemeColorVal.ACCENT_1);
            var content = DrawingText.readContent(shape, book);
            var runs = content.paragraphs().getFirst().runs();
            assertEquals(new Color(18, 52, 86), runs.get(0).color());
            assertEquals(new Color(18, 52, 86, 128), runs.get(1).color());
            assertEquals(new Color(171, 205, 239), runs.get(2).color());
            assertEquals(0, runs.get(3).color().getAlpha());
            assertEquals("Noto Serif CJK JP", runs.get(0).fontFamily());
            assertTrue(content.approximations().isEmpty(), content.approximations().toString());
        }
    }

    @Test void removesInheritedStrikeBeforeReadingTextLinkOrUnsupportedEffects() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var shape = shape(book, """
                    <a:bodyPr/><a:lstStyle/>
                    <a:p><a:pPr><a:defRPr strike="sngStrike"/></a:pPr>
                      <a:r><a:rPr><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:effectLst><a:glow rad="12000"/></a:effectLst><a:hlinkClick r:id="missing"/></a:rPr><a:t>REMOVED_DRAWING_TEXT</a:t></a:r>
                      <a:r><a:rPr strike="noStrike"><a:hlinkClick r:id="visible"/></a:rPr><a:t>残す</a:t></a:r>
                    </a:p>
                    """);
            shape.getDrawing().getPackagePart().addExternalRelationship("https://poi.apache.org/",
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", "visible");
            var content = DrawingText.readContent(shape, book);
            assertEquals("残す", DrawingText.plainText(DrawingText.flatten(content)));
            assertEquals("https://poi.apache.org/", content.paragraphs().getFirst().runs().getFirst().hyperlink());
            assertFalse(content.toString().contains("REMOVED_DRAWING_TEXT"));
            assertTrue(content.approximations().isEmpty(), "A removed run's unsupported effects must not be read");
        }
    }

    @Test void preservesLineBreaksAndEmptyParagraphFontMetrics() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var shape = shape(book, """
                    <a:bodyPr/><a:lstStyle/>
                    <a:p><a:r><a:t>A</a:t></a:r><a:br/><a:r><a:t>B</a:t></a:r></a:p>
                    <a:p><a:endParaRPr sz="2400"/></a:p>
                    <a:p><a:r><a:t>C</a:t></a:r></a:p>
                    """);
            var content = DrawingText.readContent(shape, book);
            assertEquals(3, content.paragraphs().size());
            assertEquals("A\nB\n\nC", DrawingText.plainText(DrawingText.flatten(content)));
            assertEquals(24, content.paragraphs().get(1).runs().getFirst().fontSize());
            assertTrue(content.approximations().isEmpty());
        }
    }

    @Test void readsXlsPaletteUnderlineAndTextboxAlignmentAfterRoundTrip() throws Exception {
        try (var source = new HSSFWorkbook()) {
            var shape = source.createSheet().createDrawingPatriarch().createTextbox(new HSSFClientAnchor(0,0,0,0,(short)0,0,(short)8,10));
            source.getCustomPalette().setColorAtIndex((short) 0x28, (byte) 40, (byte) 80, (byte) 120);
            var visible = source.createFont(); visible.setFontName("Noto Serif CJK JP"); visible.setFontHeightInPoints((short) 16);
            visible.setColor((short)0x28); visible.setBold(true); visible.setItalic(true); visible.setUnderline(org.apache.poi.ss.usermodel.Font.U_SINGLE);
            var removed = source.createFont(); removed.setStrikeout(true);
            var text = new HSSFRichTextString("削除残す\n二段目"); text.applyFont(0,2,removed); text.applyFont(2,text.length(),visible);
            shape.setString(text); shape.setHorizontalAlignment(HSSFTextbox.HORIZONTAL_ALIGNMENT_CENTERED);
            shape.setVerticalAlignment(HSSFTextbox.VERTICAL_ALIGNMENT_BOTTOM); shape.setWrapText(HSSFSimpleShape.WRAP_NONE);
            var bytes = new ByteArrayOutputStream(); source.write(bytes);
            try (var book = (HSSFWorkbook) WorkbookFactory.create(new ByteArrayInputStream(bytes.toByteArray()))) {
                var restored = (HSSFTextbox) book.getSheetAt(0).getDrawingPatriarch().getChildren().getFirst();
                var content = DrawingText.readContent(restored,book);
                assertEquals("残す\n二段目",DrawingText.plainText(DrawingText.flatten(content)));
                assertEquals(3,content.verticalAlignment()); assertFalse(content.wrap());
                assertEquals(2,content.paragraphs().size()); assertEquals(2,content.paragraphs().getFirst().alignment());
                var run=content.paragraphs().getFirst().runs().getFirst();
                assertEquals(new Color(40,80,120),run.color()); assertTrue(run.bold()); assertTrue(run.italic()); assertTrue(run.underline());
                assertEquals(16,run.fontSize()); assertNull(run.hyperlink()); assertTrue(content.approximations().isEmpty());
            }
        }
    }

    @Test void reportsOnlyUnsupportedTextFeaturesAndConvertsPercentageParagraphSpacing() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var shape = shape(book, """
                    <a:bodyPr vert="eaVert" upright="1"><a:spAutoFit/></a:bodyPr><a:lstStyle/>
                    <a:p><a:pPr><a:spcBef><a:spcPct val="150000"/></a:spcBef><a:buChar char="•"/></a:pPr><a:r><a:rPr sz="2000"/><a:t>縦書き</a:t></a:r></a:p>
                    """);
            var content=DrawingText.readContent(shape,book);
            assertEquals("eaVert",content.verticalMode()); assertEquals(30,content.paragraphs().getFirst().spaceBefore(),1e-9);
            assertEquals(5,content.approximations().size());
            assertTrue(content.approximations().stream().anyMatch(s->s.contains("箇条書き")));
            assertTrue(content.approximations().stream().anyMatch(s->s.contains("縦書き")));
            var supported = shape(book,"<a:bodyPr vert=\"vert270\"/><a:lstStyle/><a:p><a:pPr><a:spcBef><a:spcPct val=\"0\"/></a:spcBef><a:spcAft><a:spcPct val=\"0\"/></a:spcAft></a:pPr><a:r><a:t>回転</a:t></a:r></a:p>");
            assertTrue(DrawingText.readContent(supported,book).approximations().isEmpty());
        }
    }

    @Test void appliesLinearTintShadeAndAlphaTransforms() throws Exception {
        try (var book=new XSSFWorkbook()) {
            var shape=shape(book,"""
                    <a:bodyPr/><a:lstStyle/><a:p>
                      <a:r><a:rPr><a:solidFill><a:srgbClr val="000000"><a:tint val="25000"/></a:srgbClr></a:solidFill></a:rPr><a:t>tint</a:t></a:r>
                      <a:r><a:rPr><a:solidFill><a:srgbClr val="FFFFFF"><a:shade val="50000"/><a:alpha val="80000"/><a:alphaMod val="50000"/></a:srgbClr></a:solidFill></a:rPr><a:t>shade</a:t></a:r>
                    </a:p>
                    """);
            var content=DrawingText.readContent(shape,book);
            var runs=content.paragraphs().getFirst().runs();
            assertEquals(new Color(225,225,225),runs.getFirst().color());
            assertEquals(new Color(188,188,188,102),runs.get(1).color());
            assertTrue(content.approximations().isEmpty());
        }
    }

    @Test void logicalLatinFontIsKeptWhenItCanDisplayTheActualRun() {
        // Logical fonts are installed by Java; a Linux host may not have Japanese fallback for them.
        String family = "Monospaced";
        var font = DrawingText.font(new DrawingText.Run("Latin ABC 123", family, 17, false, false, null));
        assertEquals(new Font(family, Font.PLAIN, 17).getFamily(), font.getFamily());
        assertEquals(17, font.getSize2D());
    }

    private static XSSFSimpleShape shape(XSSFWorkbook book,String content) throws Exception {
        var sheet=book.getNumberOfSheets()==0?book.createSheet():book.getSheetAt(0);
        var drawing=sheet.getDrawingPatriarch()==null?sheet.createDrawingPatriarch():sheet.getDrawingPatriarch();
        var shape=drawing.createSimpleShape(new XSSFClientAnchor(0,0,0,0,0,0,8,10));
        shape.getCTShape().setTxBody(CTTextBody.Factory.parse("<xml-fragment xmlns:a=\""+DRAWING_NS+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"+content+"</xml-fragment>"));
        return shape;
    }

    private static XSSFWorkbook roundTrip(XSSFWorkbook source) throws Exception {
        var bytes=new ByteArrayOutputStream();source.write(bytes);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void installTheme(XSSFWorkbook book) throws Exception {
        book.getStylesSource().ensureThemesTable();
        String theme="""
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="test">
                  <a:themeElements><a:clrScheme name="test"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
                    <a:dk2><a:srgbClr val="444444"/></a:dk2><a:lt2><a:srgbClr val="EEEEEE"/></a:lt2>
                    <a:accent1><a:srgbClr val="123456"/></a:accent1><a:accent2><a:srgbClr val="ABCDEF"/></a:accent2></a:clrScheme>
                    <a:fontScheme name="test"><a:majorFont><a:latin typeface="Noto Sans CJK JP"/><a:ea typeface="Noto Sans CJK JP"/><a:cs typeface=""/></a:majorFont>
                      <a:minorFont><a:latin typeface="Noto Serif CJK JP"/><a:ea typeface=""/><a:cs typeface=""/><a:font script="Jpan" typeface="Noto Serif CJK JP"/></a:minorFont></a:fontScheme>
                    <a:fmtScheme name="test"/></a:themeElements>
                </a:theme>
                """;
        book.getTheme().readFrom(new ByteArrayInputStream(theme.getBytes(StandardCharsets.UTF_8)));
    }
}
