package com.slide2image.conversion;

import org.apache.poi.common.usermodel.fonts.FontInfo;
import org.apache.poi.common.usermodel.fonts.FontFamily;
import org.apache.poi.sl.draw.DrawFontManagerDefault;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Keeps installed fonts and supplies Japanese glyphs independently of the host OS font set. */
final class BundledPptFontManager extends DrawFontManagerDefault {
    private final Set<String> available = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private BundledPptFontManager() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        try {
            for (boolean serif : new boolean[]{false, true}) {
                for (boolean bold : new boolean[]{false, true}) {
                    try (InputStream stream = BundledFonts.open(serif, bold)) {
                        environment.registerFont(Font.createFont(Font.TRUETYPE_FONT, stream));
                    }
                }
            }
        } catch (IOException | FontFormatException failure) {
            throw new IllegalStateException("Cannot load the bundled Japanese fonts.", failure);
        }
        available.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.ROOT)));
        available.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.JAPANESE)));
        for (Font font : environment.getAllFonts()) {
            available.add(font.getFontName(Locale.ROOT));
            available.add(font.getFontName(Locale.JAPANESE));
            available.add(font.getPSName());
        }
    }

    static BundledPptFontManager instance() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final BundledPptFontManager INSTANCE = new BundledPptFontManager();
    }

    @Override
    public FontInfo getMappedFont(Graphics2D graphics, FontInfo original) {
        if (original == null || original.getTypeface() == null) {
            // Let POI resolve the paragraph's default typeface first.
            return null;
        }
        return available.contains(original.getTypeface()) ? original : replacement(original);
    }

    @Override
    public FontInfo getFallbackFont(Graphics2D graphics, FontInfo original) {
        // POI applies this only to characters the mapped font cannot display.
        return replacement(original);
    }

    private static FontInfo replacement(FontInfo original) {
        String name = original == null ? null : original.getTypeface();
        boolean serif = BundledFonts.isSerif(name)
                || (original != null && original.getFamily() == FontFamily.FF_ROMAN);
        String replacement = BundledFonts.family(serif) + (BundledFonts.isBold(name) ? " Bold" : "");
        return () -> replacement;
    }
}
