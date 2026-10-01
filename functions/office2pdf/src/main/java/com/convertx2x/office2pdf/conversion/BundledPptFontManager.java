package com.convertx2x.office2pdf.conversion;

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
import org.apache.poi.common.usermodel.fonts.FontInfo;
import org.apache.poi.sl.draw.DrawFontManagerDefault;

/** Uses installed fonts when available and bundled BIZ UDPGothic otherwise. */
final class BundledPptFontManager extends DrawFontManagerDefault {
    private final Set<String> available = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private BundledPptFontManager() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        try {
            for (String resource : new String[] {
                    "/fonts/bizud/BIZUDPGothic-Regular.ttf",
                    "/fonts/bizud/BIZUDPGothic-Bold.ttf"}) {
                try (InputStream stream = BundledPptFontManager.class.getResourceAsStream(resource)) {
                    if (stream == null) throw new IOException("Bundled font is missing: " + resource);
                    environment.registerFont(Font.createFont(Font.TRUETYPE_FONT, stream));
                }
            }
        } catch (IOException | FontFormatException failure) {
            throw new IllegalStateException("Cannot load bundled Japanese fonts", failure);
        }
        available.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.ROOT)));
        available.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.JAPANESE)));
    }

    static BundledPptFontManager instance() { return Holder.INSTANCE; }
    private static final class Holder { private static final BundledPptFontManager INSTANCE = new BundledPptFontManager(); }

    @Override public FontInfo getMappedFont(Graphics2D graphics, FontInfo original) {
        if (original == null || original.getTypeface() == null || available.contains(original.getTypeface())) return original;
        return replacement(original);
    }

    @Override public FontInfo getFallbackFont(Graphics2D graphics, FontInfo original) { return replacement(original); }

    private static FontInfo replacement(FontInfo original) {
        String name = original == null ? "" : original.getTypeface();
        String replacement = "BIZ UDPGothic";
        return () -> replacement;
    }
}
