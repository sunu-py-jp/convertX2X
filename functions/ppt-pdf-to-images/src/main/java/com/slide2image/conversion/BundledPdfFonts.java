package com.slide2image.conversion;

import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Adds Japanese CID fallback while preserving PDFBox's embedded and installed font selection. */
final class BundledPdfFonts implements FontMapper {
    private static boolean installed;
    private final FontMapper delegate;
    private final OpenTypeFont[] fonts = new OpenTypeFont[4];

    BundledPdfFonts(FontMapper delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static synchronized void ensureInstalled() {
        if (!installed) {
            synchronized (FontMappers.class) {
                FontMapper existing = FontMappers.instance();
                if (!(existing instanceof BundledPdfFonts)) {
                    FontMappers.set(new BundledPdfFonts(existing));
                }
                installed = true;
            }
        }
    }

    @Override
    public FontMapping<TrueTypeFont> getTrueTypeFont(String baseFont, PDFontDescriptor descriptor) {
        // Simple fonts do not identify a Japanese character collection. Keep their normal mapping.
        return delegate.getTrueTypeFont(baseFont, descriptor);
    }

    @Override
    public FontMapping<FontBoxFont> getFontBoxFont(String baseFont, PDFontDescriptor descriptor) {
        return delegate.getFontBoxFont(baseFont, descriptor);
    }

    @Override
    public CIDFontMapping getCIDFont(String baseFont, PDFontDescriptor descriptor, PDCIDSystemInfo info) {
        CIDFontMapping existing = delegate.getCIDFont(baseFont, descriptor, info);
        if (!existing.isFallback() || hasEmbeddedFont(descriptor) || !isJapanese(baseFont, descriptor, info)) {
            return existing;
        }
        String family = descriptor == null ? "" : descriptor.getFontFamily();
        boolean serif = BundledFonts.isSerif(baseFont) || BundledFonts.isSerif(family)
                || (descriptor != null && descriptor.isSerif());
        boolean bold = BundledFonts.isBold(baseFont) || BundledFonts.isBold(family)
                || (descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600));

        // Noto's CFF uses Adobe-Identity, not Adobe-Japan1 CID numbering. Returning it in the
        // Unicode/TrueType slot makes PDFBox translate the PDF's CMap/ToUnicode to Unicode first.
        // Returning it in the CID slot would draw unrelated glyphs for Japan1 CID numbers.
        return new CIDFontMapping(null, font(serif, bold), true);
    }

    private static boolean hasEmbeddedFont(PDFontDescriptor descriptor) {
        return descriptor != null && (descriptor.getFontFile() != null || descriptor.getFontFile2() != null
                || descriptor.getFontFile3() != null);
    }

    private static boolean isJapanese(String baseFont, PDFontDescriptor descriptor, PDCIDSystemInfo info) {
        if (info != null) {
            if ("Adobe".equals(info.getRegistry()) && "Japan1".equals(info.getOrdering())) {
                return true;
            }
            // Explicit Chinese/Korean or other collections must keep PDFBox's own fallback.
            if (!"Identity".equals(info.getOrdering())) {
                return false;
            }
        }
        return japaneseName(baseFont) || (descriptor != null
                && (japaneseName(descriptor.getFontName()) || japaneseName(descriptor.getFontFamily())));
    }

    private static boolean japaneseName(String value) {
        if (value == null) {
            return false;
        }
        String name = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s,_+\\-]", "");
        return name.contains("msgothic") || name.contains("mspgothic") || name.contains("msuigothic")
                || name.contains("msmincho") || name.contains("mspmincho") || name.contains("meiryo")
                || name.contains("yugothic") || name.contains("yumincho") || name.contains("hiragino")
                || (name.contains("hira") && (name.contains("kaku") || name.contains("min") || name.contains("maru")))
                || name.contains("kozgo") || name.contains("kozmin") || name.contains("heisei")
                || name.contains("notosanscjkjp") || name.contains("notoserifcjkjp")
                || name.contains("notosansjp") || name.contains("notoserifjp") || name.contains("ipaex")
                || name.contains("ipagothic") || name.contains("ipamincho")
                || name.contains("メイリオ") || name.contains("游ゴシック") || name.contains("游明朝")
                || name.contains("ヒラギノ") || name.contains("msゴシック") || name.contains("ms明朝")
                || name.contains("mspゴシック") || name.contains("msp明朝");
    }

    private synchronized OpenTypeFont font(boolean serif, boolean bold) {
        int index = (serif ? 2 : 0) + (bold ? 1 : 0);
        if (fonts[index] == null) {
            try (InputStream input = BundledFonts.open(serif, bold)) {
                RandomAccessReadBuffer data = new RandomAccessReadBuffer(input);
                try {
                    OpenTypeFont font = new OTFParser().parse(data);
                    // Load the outline/cmap tables once before sharing this process-lifetime font.
                    font.getCFF();
                    font.getUnicodeCmapLookup();
                    fonts[index] = font;
                } catch (IOException | RuntimeException failure) {
                    data.close();
                    throw failure;
                }
            } catch (IOException failure) {
                throw new ConversionException(500, "FONT_CONFIGURATION_ERROR",
                        "A bundled Japanese PDF font could not be loaded.", failure);
            }
        }
        return fonts[index];
    }
}
