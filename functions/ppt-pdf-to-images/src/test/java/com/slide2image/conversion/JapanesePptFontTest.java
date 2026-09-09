package com.slide2image.conversion;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.slide2image.conversion.JapanesePptFontVerification.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JapanesePptFontTest {
    private final ConversionService service = new ConversionService(ConversionLimits.defaults());

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt"})
    void missingJapaneseFontsMatchBundledSansAndSerifAtBothWeights(String format) throws Exception {
        List<int[]> rendered = new ArrayList<>();
        for (FontCase font : CASES) {
            try (RenderPair pair = compare(service, format, font)) {
                Font installed = new Font(font.referenceFamily(), font.bold() ? Font.BOLD : Font.PLAIN, 24);
                assertEquals(font.referenceFamily(), installed.getFamily(Locale.ROOT), "Noto must be registered");
                assertEquals(-1, installed.canDisplayUpTo(String.join("", LINES)), "Noto must contain the sample glyphs");
                assertTrue(inkPixels(pair.actual()) > 1_000, font.name() + " must contain rendered Japanese text");
                assertArrayEquals(pixels(pair.expected()), pixels(pair.actual()),
                        format + " " + font.name() + " must match the explicit Noto reference");
                rendered.add(pixels(pair.actual()));
            }
        }
        // Equality with a reference alone could miss a manager that always chooses the same face/weight.
        assertFalse(Arrays.equals(rendered.get(0), rendered.get(1)), "Sans bold must differ from regular");
        assertFalse(Arrays.equals(rendered.get(2), rendered.get(3)), "Serif bold must differ from regular");
        assertFalse(Arrays.equals(rendered.get(0), rendered.get(2)), "Serif must differ from sans");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt"})
    void preservesAnInstalledFontInsteadOfReplacingItWithNoto(String format) throws Exception {
        var installed = installedLatinFamily(false);
        assumeTrue(installed.isPresent(), "This runtime has no non-Noto physical Latin font");
        byte[] input = document(format, installed.orElseThrow(), false, List.of("Original font: iii WWW 0123"));
        BufferedImage original = drawWithDefaultPoi(input);
        BufferedImage converted = convert(service, input, format);
        try {
            assertTrue(inkPixels(original) > 100);
            assertArrayEquals(pixels(original), pixels(converted),
                    "Existing physical font must retain the same rendering: " + installed.orElseThrow());
        } finally {
            original.flush();
            converted.flush();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt"})
    void usesBundledJapaneseGlyphsWhenAnInstalledLatinFontCannotDisplayThem(String format) throws Exception {
        var installed = installedLatinFamily(true);
        assumeTrue(installed.isPresent(), "This runtime has no physical Latin font lacking the Japanese sample");
        String family = installed.orElseThrow();
        assertNotEquals(-1, new Font(family, Font.PLAIN, 24).canDisplayUpTo(JAPANESE_ONLY));
        byte[] missingGlyphs = document(format, family, false, List.of(JAPANESE_ONLY));
        byte[] explicitNoto = document(format, SANS, false, List.of(JAPANESE_ONLY));
        BufferedImage converted = convert(service, missingGlyphs, format);
        BufferedImage reference = convert(service, explicitNoto, format);
        try {
            assertTrue(inkPixels(converted) > 100);
            assertArrayEquals(pixels(reference), pixels(converted),
                    "Missing Japanese glyphs in " + family + " must use the bundled Japanese font");
        } finally {
            converted.flush();
            reference.flush();
        }
    }

    @ParameterizedTest
    @CsvSource({"pptx,wrap", "ppt,wrap", "pptx,soft-breaks", "ppt,soft-breaks", "pptx,paragraphs", "ppt,paragraphs",
            "pptx,mixed-groups", "ppt,mixed-groups", "pptx,group-table", "ppt,group-table"})
    void preservesWrappingBreaksAndRunFormattingWithReplacementFonts(String format, String scenario) throws Exception {
        try (RenderPair pair = compareLayout(service, format, scenario)) {
            assertTrue(inkPixels(pair.actual()) > 100, "The fixture must draw text");
            assertArrayEquals(pixels(pair.expected()), pixels(pair.actual()),
                    format + " " + scenario + " must match unmodified POI with explicit Noto fonts");
            if (scenario.equals("wrap") || scenario.equals("mixed-groups")) {
                assertTrue(inkLineBands(pair.expected()) >= 3, "The one-paragraph fixture must actually wrap");
            }
        }
    }

    private static int inkLineBands(BufferedImage image) {
        int bands = 0;
        boolean previousHasInk = false;
        for (int y = 0; y < image.getHeight(); y++) {
            boolean hasInk = false;
            for (int x = 0; x < image.getWidth(); x++) {
                hasInk |= (image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff;
            }
            if (hasInk && !previousHasInk) bands++;
            previousHasInk = hasInk;
        }
        return bands;
    }
}
