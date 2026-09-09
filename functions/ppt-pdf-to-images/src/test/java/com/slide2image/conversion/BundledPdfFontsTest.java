package com.slide2image.conversion;

import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class BundledPdfFontsTest {
    private static final String MISSING_FONT = "MissingJapaneseGothic-TestFont";
    private static final String JAPANESE = "日本語あいうえおガギグゲゴ東京都①②③￥１２３ABC123";
    private static final CIDFontMapping MISSING = new CIDFontMapping(null, null, true);
    private static final FontMapping<TrueTypeFont> SIMPLE_TTF = new FontMapping<>(null, true);
    private static final FontMapping<FontBoxFont> SIMPLE_FONT = new FontMapping<>(null, true);
    private static final BundledPdfFonts FALLBACK = new BundledPdfFonts(delegate(MISSING));

    @Test
    void selectsAllFourJapaneseStylesAndUsesUnicodeMapping() throws Exception {
        assertBundled("MS-Gothic", "NotoSansCJKjp-Regular");
        assertBundled("Meiryo-Bold", "NotoSansCJKjp-Bold");
        assertBundled("HeiseiMin-W3", "NotoSerifCJKjp-Regular");
        assertBundled("YuMincho-Bold", "NotoSerifCJKjp-Bold");
        assertBundled("ＭＳ Ｐ明朝", "NotoSerifCJKjp-Regular");

        PDFontDescriptor descriptor = descriptor("Meiryo");
        descriptor.setSerif(true);
        descriptor.setFontWeight(700);
        assertEquals("NotoSerifCJKjp-Bold", FALLBACK.getCIDFont("Meiryo", descriptor, null)
                .getTrueTypeFont().getName());
    }

    @Test
    void preservesExactMappingsSimpleFontsAndOtherCharacterCollections() throws Exception {
        CIDFontMapping exact = new CIDFontMapping(null, bundled(false, false), false);
        BundledPdfFonts mapper = new BundledPdfFonts(delegate(exact));
        assertSame(exact, mapper.getCIDFont("Meiryo", null, null));
        assertSame(SIMPLE_TTF, mapper.getTrueTypeFont("Meiryo", null));
        assertSame(SIMPLE_FONT, mapper.getFontBoxFont("Symbol", null));
        assertSame(MISSING, FALLBACK.getCIDFont("Helvetica", null, null));
        for (String collection : new String[]{"GB1", "CNS1", "Korea1"}) {
            assertSame(MISSING, FALLBACK.getCIDFont("Meiryo", null, collection(collection)));
        }
        assertSame(MISSING, FALLBACK.getCIDFont("UnidentifiedFont", null, collection("Identity")));
    }

    @Test
    void installsOneWrapperUnderConcurrentInitialization() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<FontMapper>>();
            for (int i = 0; i < 12; i++) {
                tasks.add(workers.submit(() -> {
                    BundledPdfFonts.ensureInstalled();
                    return FontMappers.instance();
                }));
            }
            FontMapper first = tasks.getFirst().get();
            assertInstanceOf(BundledPdfFonts.class, first);
            for (var task : tasks) {
                assertSame(first, task.get());
            }
        }
    }

    @Test
    void rendersUnembeddedJapan1CffAndTrueTypeFontsWithCorrectJapaneseGlyphs() throws Exception {
        BundledPdfFonts.ensureInstalled();
        for (COSName subtype : new COSName[]{COSName.CID_FONT_TYPE0, COSName.CID_FONT_TYPE2}) {
            byte[] bytes = japanesePdf(subtype, false);
            try (PDDocument document = Loader.loadPDF(bytes)) {
                PDType0Font font = onlyFont(document);
                assertFalse(font.isEmbedded());
                assertTrue(new PDFTextStripper().getText(document).contains(JAPANESE));
                for (int codePoint : JAPANESE.codePoints().toArray()) {
                    assertTrue(font.hasGlyph(codePoint), "Missing U+" + Integer.toHexString(codePoint));
                    assertPathEquals(bundled(false, false).getPath(glyphName(codePoint)), font.getPath(codePoint));
                }
            }
            assertRenderedContent(bytes);
        }
    }

    @Test
    void retainsOriginalEmbeddedJapaneseCffOutlinesDespiteDifferentDeclaredStyle() throws Exception {
        BundledPdfFonts.ensureInstalled();
        byte[] bytes = japanesePdf(COSName.CID_FONT_TYPE0, true);
        OpenTypeFont original = bundled(true, true);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDType0Font font = onlyFont(document);
            assertTrue(font.isEmbedded());
            assertFalse(font.isDamaged());
            assertEquals(MISSING_FONT, font.getName());
            assertTrue(new PDFTextStripper().getText(document).contains(JAPANESE));
            for (int codePoint : JAPANESE.codePoints().toArray()) {
                int cid = embeddedCid(original, codePoint);
                assertPathEquals(original.getPath(glyphName(codePoint)), font.getPath(cid));
            }
            // The embedded face really differs from the missing-font default chosen by the name.
            assertNotEquals(original.getPath(glyphName('日')).getBounds2D(),
                    bundled(false, false).getPath(glyphName('日')).getBounds2D());
        }
        assertRenderedContent(bytes);
    }

    private static void assertBundled(String name, String expected) throws Exception {
        CIDFontMapping mapping = FALLBACK.getCIDFont(name, null, null);
        assertTrue(mapping.isFallback());
        assertFalse(mapping.isCIDFont(), "Noto Identity CIDs must not be interpreted as Adobe-Japan1 CIDs");
        assertEquals(expected, mapping.getTrueTypeFont().getName());
    }

    private static OpenTypeFont bundled(boolean serif, boolean bold) {
        return (OpenTypeFont) FALLBACK.getCIDFont((serif ? "YuMincho" : "Meiryo")
                + (bold ? "-Bold" : ""), null, null).getTrueTypeFont();
    }

    private static FontMapper delegate(CIDFontMapping cidMapping) {
        return new FontMapper() {
            @Override
            public FontMapping<TrueTypeFont> getTrueTypeFont(String name, PDFontDescriptor descriptor) {
                return SIMPLE_TTF;
            }

            @Override
            public FontMapping<FontBoxFont> getFontBoxFont(String name, PDFontDescriptor descriptor) {
                return SIMPLE_FONT;
            }

            @Override
            public CIDFontMapping getCIDFont(String name, PDFontDescriptor descriptor, PDCIDSystemInfo info) {
                return cidMapping;
            }
        };
    }

    private static PDCIDSystemInfo collection(String ordering) throws Exception {
        try (PDDocument document = new PDDocument()) {
            return new PDType0Font(fontDictionary(document, COSName.CID_FONT_TYPE0, ordering, null, Map.of()))
                    .getDescendantFont().getCIDSystemInfo();
        }
    }

    private static byte[] japanesePdf(COSName subtype, boolean embedded) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            OpenTypeFont original = embedded ? bundled(true, true) : null;
            Map<Integer, Integer> toUnicode = new LinkedHashMap<>();
            StringBuilder encoded = new StringBuilder();
            for (int codePoint : JAPANESE.codePoints().toArray()) {
                int code = embedded ? embeddedCid(original, codePoint) : codePoint;
                encoded.append(String.format(Locale.ROOT, "%04X", code));
                toUnicode.put(code, codePoint);
            }
            COSDictionary dictionary = fontDictionary(document, subtype,
                    embedded ? "Identity" : "Japan1", original, toUnicode);
            PDType0Font font = new PDType0Font(dictionary);
            PDPage page = new PDPage(new PDRectangle(1000, 100));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 24);
                content.newLineAtOffset(20, 45);
                content.appendRawCommands("<" + encoded + "> Tj\n");
                content.endText();
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static COSDictionary fontDictionary(PDDocument document, COSName subtype, String ordering,
                                               OpenTypeFont embedded, Map<Integer, Integer> toUnicode) throws Exception {
        PDFontDescriptor descriptor = descriptor(MISSING_FONT);
        if (embedded != null) {
            PDStream program = stream(document, embedded.getCFF().getFont().getData());
            program.getCOSObject().setName(COSName.SUBTYPE, "CIDFontType0C");
            descriptor.setFontFile3(program);
        }
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, ordering);
        info.setInt(COSName.SUPPLEMENT, "Japan1".equals(ordering) ? 6 : 0);
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, subtype);
        cid.setName(COSName.BASE_FONT, MISSING_FONT);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, descriptor);
        cid.setInt(COSName.DW, 1000);
        COSArray descendants = new COSArray();
        descendants.add(cid);
        COSDictionary parent = new COSDictionary();
        parent.setItem(COSName.TYPE, COSName.FONT);
        parent.setItem(COSName.SUBTYPE, COSName.TYPE0);
        parent.setName(COSName.BASE_FONT, MISSING_FONT);
        parent.setName(COSName.ENCODING, embedded != null || !"Japan1".equals(ordering)
                ? "Identity-H" : "UniJIS-UTF16-H");
        parent.setItem(COSName.DESCENDANT_FONTS, descendants);
        StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /TestJapaneseUCS def\n/CMapType 2 def\n"
                + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        cmap.append(toUnicode.size()).append(" beginbfchar\n");
        toUnicode.forEach((code, unicode) -> cmap.append(String.format(Locale.ROOT, "<%04X> <%04X>\n", code, unicode)));
        cmap.append("endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        parent.setItem(COSName.TO_UNICODE, stream(document, cmap.toString().getBytes(StandardCharsets.US_ASCII)));
        return parent;
    }

    private static PDFontDescriptor descriptor(String name) {
        PDFontDescriptor descriptor = new PDFontDescriptor(new COSDictionary());
        descriptor.setFontName(name);
        descriptor.setFlags(4);
        descriptor.setAscent(880);
        descriptor.setDescent(-120);
        descriptor.setCapHeight(700);
        descriptor.setStemV(80);
        descriptor.setFontBoundingBox(new PDRectangle(-1000, -1000, 3000, 3000));
        return descriptor;
    }

    private static PDStream stream(PDDocument document, byte[] data) throws Exception {
        return new PDStream(document, new ByteArrayInputStream(data), COSName.FLATE_DECODE);
    }

    private static int embeddedCid(OpenTypeFont font, int unicode) throws Exception {
        CFFFont cff = font.getCFF().getFont();
        return cff.getCharset().getCIDForGID(font.getUnicodeCmapLookup().getGlyphId(unicode));
    }

    private static PDType0Font onlyFont(PDDocument document) throws Exception {
        var resources = document.getPage(0).getResources();
        return (PDType0Font) resources.getFont(resources.getFontNames().iterator().next());
    }

    private static String glyphName(int unicode) {
        return String.format(Locale.ROOT, "uni%04X", unicode);
    }

    private static void assertPathEquals(GeneralPath expected, GeneralPath actual) {
        PathIterator a = expected.getPathIterator(null);
        PathIterator b = actual.getPathIterator(null);
        float[] pa = new float[6];
        float[] pb = new float[6];
        while (!a.isDone()) {
            assertFalse(b.isDone());
            assertEquals(a.currentSegment(pa), b.currentSegment(pb));
            assertArrayEquals(pa, pb, 0.001f);
            a.next();
            b.next();
        }
        assertTrue(b.isDone());
    }

    private static void assertRenderedContent(byte[] pdf) throws Exception {
        ConversionResult result = new ConversionService(ConversionLimits.defaults()).convert(pdf, "japanese.pdf",
                new ConversionOptions(1000, "png", 1));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertEquals(1000, image.getWidth());
        assertEquals(100, image.getHeight());
        int darkPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) < 0x808080) {
                    darkPixels++;
                }
            }
        }
        assertTrue(darkPixels > 1000, "Japanese glyphs should be visible in the converted image");
        image.flush();
    }
}
