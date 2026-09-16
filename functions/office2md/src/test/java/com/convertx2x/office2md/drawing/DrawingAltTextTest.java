package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionException;
import java.awt.geom.Rectangle2D;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrawingAltTextTest {
    @Test void geometryDeclaresReferenceUnitsAndBoundsWithoutTurningUnknownIntoZero() {
        assertEquals("基準=シート左上、外接矩形 X=-20.125pt、Y=100pt、幅=120pt、高さ=0pt",
                DrawingAltText.geometry("シート左上", new Rectangle2D.Double(-20.1251, 100.00000001, 120, -0.0)));
        assertEquals("基準=スライド左上、位置・サイズ不明", DrawingAltText.geometry("スライド左上", null));
        assertEquals("基準不明、位置・サイズ不明", DrawingAltText.geometry("", new Rectangle2D.Double(Double.NaN, 0, 10, 20)));
        assertEquals("基準=本文内、位置・サイズ不明", DrawingAltText.geometry("本文内", new Rectangle2D.Double(0, 0, -1, 20)));
    }

    @Test void transformedGeometryUsesTheBoundingBoxInsteadOfUnrotatedDimensions() {
        var transform = java.awt.geom.AffineTransform.getTranslateInstance(20, 100);
        transform.rotate(Math.toRadians(90));
        var bounds = transform.createTransformedShape(new Rectangle2D.Double(0, 0, 120, 40)).getBounds2D();
        assertEquals("基準=スライド左上、外接矩形 X=-20pt、Y=100pt、幅=40pt、高さ=120pt",
                DrawingAltText.geometry("スライド左上", bounds));
    }

    @Test void presetsAndPoiNamesUseJapaneseAndUnknownPresetsKeepTheirIdentifier() {
        assertEquals("長方形", DrawingAltText.typeName("RECT"));
        assertEquals("角丸長方形", DrawingAltText.typeName("ROUND_RECTANGLE"));
        assertEquals("右矢印", DrawingAltText.typeName("rightArrow"));
        assertEquals("左右矢印", DrawingAltText.typeName("LEFT_RIGHT_ARROW"));
        assertEquals("星（5角）", DrawingAltText.typeName("star5"));
        assertEquals("図形（newPreset42）", DrawingAltText.typeName("newPreset42"));
    }

    @Test void clockwiseAnglesNormalizeNegativeFullTurnsAndFloatingPointNoise() {
        assertEquals("長方形、時計回り330度、確定", DrawingAltText.describe("長方形", -30, "確定"));
        assertEquals("時計回り0度", DrawingAltText.rotation(720));
        assertEquals("時計回り0度", DrawingAltText.rotation(-0.0000001));
        assertEquals("時計回り30度", DrawingAltText.rotation(29.999999999));
        assertEquals("時計回り12.345度", DrawingAltText.rotation(12.345));
        assertEquals("回転角度不明", DrawingAltText.rotation(Double.NaN));
    }

    @Test void descriptionsStayOneLineAndEscapeImageHtmlAndTableDelimitersExactlyOnce() {
        String description = DrawingAltText.describe("長方形", 0,
                "  承認\r\n\t![外部](https://example.invalid/x) | <script>\u2028次の行\u0000 ");
        String alt = DrawingAltText.imageAlt(List.of(description, "画像 [原文]"));
        assertEquals("長方形、時計回り0度、承認 \\!\\[外部\\](https://example.invalid/x) \\| &lt;script&gt; 次の行 / 画像 \\[原文\\]", alt);
        assertFalse(alt.contains("\n"));
        assertFalse(alt.contains("\r"));
    }

    @Test void boundedAltChecksEscapedUtf8BytesAndDoesNotSilentlyTruncate() {
        assertEquals("あ", DrawingAltText.imageAlt(List.of("あ"), 3));
        assertEquals("\\[", DrawingAltText.imageAlt(List.of("["), 2));
        var utf8 = assertThrows(ConversionException.class, () -> DrawingAltText.imageAlt(List.of("あ"), 2));
        assertEquals("MARKDOWN_BYTES_LIMIT", utf8.code());
        assertThrows(ConversionException.class, () -> DrawingAltText.imageAlt(List.of("["), 1));
        assertThrows(ConversionException.class, () -> DrawingAltText.imageAlt(List.of("a", "b"), 4));
        assertEquals("図形", DrawingAltText.imageAlt(List.of(), 6));
    }
}
