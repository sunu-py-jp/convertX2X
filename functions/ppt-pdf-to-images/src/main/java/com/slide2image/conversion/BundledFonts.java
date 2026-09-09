package com.slide2image.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Shared resource names and conservative family hints for the bundled Japanese fonts. */
final class BundledFonts {
    private BundledFonts() { }

    static String family(boolean serif) {
        return serif ? "Noto Serif CJK JP" : "Noto Sans CJK JP";
    }

    static InputStream open(boolean serif, boolean bold) throws IOException {
        String resource = "/fonts/noto/Noto" + (serif ? "Serif" : "Sans")
                + "CJKjp-" + (bold ? "Bold" : "Regular") + ".otf";
        InputStream stream = BundledFonts.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Missing bundled font resource: " + resource);
        }
        return stream;
    }

    static boolean isSerif(String name) {
        String normalized = normalize(name);
        return normalized.contains("mincho") || normalized.contains("明朝")
                || normalized.contains("heiseimin") || normalized.contains("kozmin")
                || normalized.contains("hiramin")
                || normalized.contains("ryumin") || normalized.contains("times")
                || normalized.contains("simsun") || normalized.contains("song")
                || (normalized.contains("serif") && !normalized.contains("sans"));
    }

    static boolean isBold(String name) {
        String normalized = normalize(name);
        return normalized.contains("bold") || normalized.contains("demi") || normalized.contains("太字");
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
