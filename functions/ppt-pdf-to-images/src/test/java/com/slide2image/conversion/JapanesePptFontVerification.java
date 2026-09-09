package com.slide2image.conversion;

import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextRun;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Self-contained PPT/PPTX fixtures and an optional Linux/manual PNG verification entry point.
 * Run from the module directory after test compilation and application packaging:
 * <pre>
 * java -Djava.awt.headless=true \
 *   -cp 'target/test-classes:target/classes:target/azure-functions/slide2image-local/lib/*' \
 *   com.slide2image.conversion.JapanesePptFontVerification /tmp/japanese-ppt-fonts
 * </pre>
 * No desktop application, installed Japanese font, or JUnit runtime is required by this entry point.
 */
public final class JapanesePptFontVerification {
    static final String SANS = "Noto Sans CJK JP";
    static final String SERIF = "Noto Serif CJK JP";
    static final String JAPANESE_ONLY = "日本語の表示確認東京都株式会社";
    static final List<String> LINES = List.of(
            "日本語の表示確認",
            "あいうえお がぎぐげご ／ アイウエオ",
            "東京都・株式会社・￥１２３，４５６",
            "Mixed 日本語 ABC 123");
    static final List<FontCase> CASES = List.of(
            new FontCase("gothic-regular", "ConvertX2X Missing Gothic", SANS, false),
            new FontCase("gothic-bold", "ConvertX2X Missing Gothic", SANS, true),
            new FontCase("mincho-regular", "ConvertX2X Missing Mincho", SERIF, false),
            new FontCase("mincho-bold", "ConvertX2X Missing Mincho", SERIF, true));
    static final List<String> LAYOUT_CASES = List.of("wrap", "soft-breaks", "paragraphs", "mixed-groups", "group-table");
    private static final int SOURCE_WIDTH = 720;
    private static final int SOURCE_HEIGHT = 270;
    static final int OUTPUT_WIDTH = 960;
    static final int OUTPUT_HEIGHT = 360;

    private JapanesePptFontVerification() { }

    record FontCase(String name, String missingFamily, String referenceFamily, boolean bold) { }

    record RenderPair(byte[] requestedDocument, byte[] referenceDocument,
                      BufferedImage actual, BufferedImage expected) implements AutoCloseable {
        @Override
        public void close() {
            actual.flush();
            expected.flush();
        }
    }

    static RenderPair compare(ConversionService service, String format, FontCase font) throws Exception {
        require(!font.missingFamily().equals(new Font(font.missingFamily(), Font.PLAIN, 24).getFamily(Locale.ROOT)),
                "The deliberately missing test font is unexpectedly installed: " + font.missingFamily());
        byte[] requested = document(format, font.missingFamily(), font.bold(), LINES);
        byte[] reference = document(format, font.referenceFamily(), font.bold(), LINES);
        return new RenderPair(requested, reference,
                convert(service, requested, format), convert(service, reference, format));
    }

    static byte[] document(String format, String family, boolean bold, List<String> lines) throws Exception {
        try (SlideShow<?, ?> slides = switch (format) {
            case "pptx" -> new XMLSlideShow();
            case "ppt" -> new HSLFSlideShow();
            default -> throw new IllegalArgumentException("Unsupported fixture format: " + format);
        }; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.setPageSize(new Dimension(SOURCE_WIDTH, SOURCE_HEIGHT));
            var slide = slides.createSlide();
            slide.setFollowMasterObjects(false);
            for (int index = 0; index < lines.size(); index++) {
                var text = slide.createTextBox();
                text.setAnchor(new Rectangle2D.Double(24, 24 + index * 60, 672, 54));
                text.setInsets(new Insets2D(0, 0, 0, 0));
                text.setVerticalAlignment(VerticalAlignment.MIDDLE);
                text.setWordWrap(false);
                var run = text.setText(lines.get(index));
                run.setFontFamily(family);
                run.setFontFamily(family, FontGroup.LATIN);
                run.setFontFamily(family, FontGroup.EAST_ASIAN);
                run.setFontFamily(family, FontGroup.COMPLEX_SCRIPT);
                run.setFontSize(index == 0 ? 36.0 : 24.0);
                run.setFontColor(new Color(0x172A46));
                run.setBold(bold);
                for (var paragraph : text.getTextParagraphs()) {
                    paragraph.setTextAlign(TextParagraph.TextAlign.LEFT);
                    paragraph.setSpaceBefore(0.0);
                    paragraph.setSpaceAfter(0.0);
                }
            }
            slides.write(output);
            return output.toByteArray();
        }
    }

    static RenderPair compareLayout(ConversionService service, String format, String scenario) throws Exception {
        byte[] requested = layoutDocument(format, false, scenario);
        byte[] reference = layoutDocument(format, true, scenario);
        BufferedImage actual = convert(service, requested, format);
        // The reference bypasses our drawing factory entirely, so a layout bug cannot affect both sides.
        return new RenderPair(requested, reference, actual, drawWithDefaultPoi(reference));
    }

    private static byte[] layoutDocument(String format, boolean reference, String scenario) throws Exception {
        String family = reference ? SANS : "ConvertX2X Missing Gothic";
        try (SlideShow<?, ?> slides = "pptx".equals(format) ? new XMLSlideShow() : new HSLFSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slides.setPageSize(new Dimension(SOURCE_WIDTH, SOURCE_HEIGHT));
            var slide = slides.createSlide();
            slide.setFollowMasterObjects(false);
            if ("group-table".equals(scenario)) {
                var group = slide.createGroup();
                group.setAnchor(new Rectangle2D.Double(24, 20, 320, 200));
                group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 320, 200));
                var text = group.createTextBox();
                prepare(text, new Rectangle2D.Double(0, 0, 320, 160));
                style(text.setText("グループの日本語 ABC"), family, true, false, false, false);
                var table = slide.createTable(2, 1);
                table.setAnchor(new Rectangle2D.Double(380, 20, 310, 180));
                table.setColumnWidth(0, 310);
                for (int row = 0; row < 2; row++) {
                    table.setRowHeight(row, 85);
                    var cell = table.getCell(row, 0);
                    cell.setInsets(new Insets2D(6, 6, 6, 6));
                    style(cell.setText(row == 0 ? "表の日本語 123" : "明細 株式会社 ABC"), family,
                            row == 0, false, row == 1, false);
                }
            } else {
                var text = slide.createTextBox();
                double width = "wrap".equals(scenario) || "mixed-groups".equals(scenario) ? 260 : 672;
                prepare(text, new Rectangle2D.Double(24, 12, width, 246));
                switch (scenario) {
                    case "wrap" -> {
                        style(text.setText("日本語 ABC 123 東京都 株式会社 "), family, false, false, false, false);
                        style(text.appendText("太字 Bold アイウエオ ", false), family, true, false, false, false);
                        style(text.appendText("斜体 italic がぎぐげご ", false), family, false, true, true, false);
                        style(text.appendText("2", false), family, false, false, false, true);
                        style(text.appendText(" 最後の日本語", false), family, false, false, false, false);
                        text.getTextParagraphs().get(0).setTextAlign(TextParagraph.TextAlign.CENTER);
                        if (text.getTextParagraphs().size() != 1) throw new IllegalStateException("Wrap fixture must use one paragraph");
                    }
                    case "soft-breaks" -> {
                        style(text.setText(""), family, false, false, false, false);
                        lineBreak(text, family);
                        style(text.appendText("先頭 First 日本語", false), family, false, false, false, false);
                        lineBreak(text, family);
                        lineBreak(text, family);
                        style(text.appendText("空行の後 Second", false), family, true, false, false, false);
                        lineBreak(text, family);
                        style(text.appendText("最後 Third ", false), family, false, true, true, false);
                        style(text.appendText("2", false), family, false, false, false, true);
                        text.getTextParagraphs().get(0).setTextAlign(TextParagraph.TextAlign.RIGHT);
                        if (text.getTextParagraphs().size() != 1) throw new IllegalStateException("Soft breaks must remain in one paragraph");
                    }
                    case "paragraphs" -> {
                        text.setText("第一段落 ABC\n\n第三段落 日本語\n最後の行 123");
                        int index = 0;
                        for (var paragraph : text.getTextParagraphs()) {
                            paragraph.setTextAlign(index % 2 == 0 ? TextParagraph.TextAlign.CENTER : TextParagraph.TextAlign.RIGHT);
                            for (var run : paragraph.getTextRuns()) {
                                style(run, family, index >= 2, index == 3, index == 3, false);
                            }
                            index++;
                        }
                        if (text.getTextParagraphs().size() != 4) throw new IllegalStateException("Expected four paragraphs including a blank one");
                    }
                    case "mixed-groups" -> {
                        String[] parts = {"日本語", " ABC 123 ", "追加の日本語", " Latin words 456 ", "東京都株式会社"};
                        String mixed = String.join("", parts);
                        if (reference) {
                            int start = 0;
                            for (var range : FontGroup.getFontGroupRanges(mixed)) {
                                String segment = mixed.substring(start, start + range.getLength());
                                var run = start == 0 ? text.setText(segment) : text.appendText(segment, false);
                                style(run, range.getFontGroup() == FontGroup.EAST_ASIAN ? SANS : "Monospaced",
                                        false, false, false, false);
                                start += range.getLength();
                            }
                        } else {
                            var run = text.setText(mixed);
                            style(run, family, false, false, false, false);
                            run.setFontFamily("Monospaced", FontGroup.LATIN);
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown layout fixture: " + scenario);
                }
            }
            slides.write(output);
            return output.toByteArray();
        }
    }

    private static void prepare(TextShape<?, ?> text, Rectangle2D anchor) {
        text.setAnchor(anchor);
        text.setInsets(new Insets2D(0, 0, 0, 0));
        text.setVerticalAlignment(VerticalAlignment.TOP);
        text.setWordWrap(true);
    }

    private static void style(TextRun run, String family, boolean bold, boolean italic,
                              boolean underline, boolean superscript) {
        run.setFontFamily(family);
        run.setFontFamily(family, FontGroup.LATIN);
        run.setFontFamily(family, FontGroup.EAST_ASIAN);
        run.setFontFamily(family, FontGroup.COMPLEX_SCRIPT);
        run.setFontSize(22.0);
        run.setFontColor(bold ? new Color(0x6847B6) : new Color(0x172A46));
        run.setBold(bold);
        run.setItalic(italic);
        run.setUnderlined(underline);
        if (run instanceof XSLFTextRun xslf) xslf.setSuperscript(superscript);
        if (run instanceof HSLFTextRun hslf) hslf.setSuperscript(superscript ? 30 : 0);
        run.getParagraph().setSpaceBefore(0.0);
        run.getParagraph().setSpaceAfter(0.0);
    }

    private static void lineBreak(TextShape<?, ?> text, String family) {
        TextRun run = text.getTextParagraphs().get(0) instanceof XSLFTextParagraph paragraph
                ? paragraph.addLineBreak() : text.appendText("\u000b", false);
        style(run, family, false, false, false, false);
    }

    static BufferedImage convert(ConversionService service, byte[] document, String format) throws Exception {
        ConversionResult result = service.convert(document, "japanese." + format,
                new ConversionOptions(OUTPUT_WIDTH, "png", 1));
        require("image/png".equals(result.contentType()) && result.pageCount() == 1,
                "Expected one PNG image");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        require(image != null && image.getWidth() == OUTPUT_WIDTH && image.getHeight() == OUTPUT_HEIGHT,
                "Unexpected PNG dimensions");
        return image;
    }

    /** Draw with unmodified POI to detect replacement of an already available font. */
    static BufferedImage drawWithDefaultPoi(byte[] document) throws Exception {
        try (var input = new ByteArrayInputStream(document); var slides = SlideShowFactory.create(input)) {
            BufferedImage image = new BufferedImage(OUTPUT_WIDTH, OUTPUT_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = PageRendering.graphics(image);
            try {
                double scale = OUTPUT_WIDTH / (double) SOURCE_WIDTH;
                graphics.scale(scale, scale);
                slides.getSlides().get(0).draw(graphics);
                return image;
            } finally {
                graphics.dispose();
            }
        }
    }

    /** Prefer fonts available on typical Linux/macOS hosts; never silently substitute a missing family. */
    static Optional<String> installedLatinFamily(boolean withoutJapaneseGlyphs) {
        Set<String> available = new LinkedHashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
        List<String> candidates = new ArrayList<>(List.of("DejaVu Sans", "Liberation Sans", "Arial", "Helvetica"));
        candidates.addAll(available);
        Set<String> logical = Set.of("dialog", "dialoginput", "serif", "sansserif", "monospaced");
        for (String family : candidates) {
            String lower = family.toLowerCase(Locale.ROOT);
            if (!available.contains(family) || lower.contains("noto") || logical.contains(lower)) {
                continue;
            }
            Font font = new Font(family, Font.PLAIN, 24);
            if (font.canDisplayUpTo("Original font: iii WWW 0123") == -1
                    && (!withoutJapaneseGlyphs || font.canDisplayUpTo(JAPANESE_ONLY) != -1)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }

    static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    static long inkPixels(BufferedImage image) {
        return Arrays.stream(pixels(image)).filter(pixel -> (pixel & 0x00ffffff) != 0x00ffffff).count();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: JapanesePptFontVerification <output-directory>");
        }
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        ConversionService service = new ConversionService(ConversionLimits.defaults());
        for (String format : List.of("pptx", "ppt")) {
            for (FontCase font : CASES) {
                try (RenderPair pair = compare(service, format, font)) {
                    String stem = format + "-" + font.name();
                    Files.write(output.resolve(stem + "." + format), pair.requestedDocument());
                    Files.write(output.resolve(stem + "-reference." + format), pair.referenceDocument());
                    ImageIO.write(pair.actual(), "png", output.resolve(stem + ".png").toFile());
                    ImageIO.write(pair.expected(), "png", output.resolve(stem + "-reference.png").toFile());
                    require(Arrays.equals(pixels(pair.actual()), pixels(pair.expected())),
                            format + " " + font.name() + " differs from explicit Noto reference");
                    require(inkPixels(pair.actual()) > 1_000, format + " " + font.name() + " is blank");
                    Font awt = new Font(font.referenceFamily(), font.bold() ? Font.BOLD : Font.PLAIN, 24);
                    require(font.referenceFamily().equals(awt.getFamily(Locale.ROOT))
                                    && awt.canDisplayUpTo(String.join("", LINES)) == -1,
                            "Bundled font was not registered or does not contain the Japanese sample");
                    System.out.println("PASS " + stem + " matches " + awt.getFontName(Locale.ROOT));
                }
            }
            verifyInstalledFonts(service, format, output);
            for (String scenario : LAYOUT_CASES) {
                try (RenderPair pair = compareLayout(service, format, scenario)) {
                    String stem = format + "-layout-" + scenario;
                    Files.write(output.resolve(stem + "." + format), pair.requestedDocument());
                    Files.write(output.resolve(stem + "-reference." + format), pair.referenceDocument());
                    ImageIO.write(pair.actual(), "png", output.resolve(stem + ".png").toFile());
                    ImageIO.write(pair.expected(), "png", output.resolve(stem + "-reference.png").toFile());
                    require(Arrays.equals(pixels(pair.actual()), pixels(pair.expected())),
                            stem + " differs from unmodified POI with explicit Noto fonts");
                    System.out.println("PASS " + stem + " matches unmodified POI with explicit Noto fonts");
                }
            }
        }
        System.out.println("PNG comparisons and editable fixtures: " + output);
        System.out.println("Verified only on this runtime: " + System.getProperty("os.name")
                + " / Java " + System.getProperty("java.version"));
    }

    private static void verifyInstalledFonts(ConversionService service, String format, Path output) throws Exception {
        var installed = installedLatinFamily(false);
        if (installed.isPresent()) {
            byte[] input = document(format, installed.orElseThrow(), false, List.of("Original font: iii WWW 0123"));
            BufferedImage expected = drawWithDefaultPoi(input);
            BufferedImage actual = convert(service, input, format);
            try {
                require(inkPixels(actual) > 100 && Arrays.equals(pixels(expected), pixels(actual)),
                        "Installed font changed: " + installed.orElseThrow());
                ImageIO.write(actual, "png", output.resolve(format + "-installed-font.png").toFile());
                System.out.println("PASS " + format + " preserves installed font " + installed.orElseThrow());
            } finally {
                expected.flush();
                actual.flush();
            }
        } else {
            System.out.println("SKIP " + format + " installed font comparison: no non-Noto physical Latin font");
        }
        var withoutJapanese = installedLatinFamily(true);
        if (withoutJapanese.isPresent()) {
            byte[] input = document(format, withoutJapanese.orElseThrow(), false, List.of(JAPANESE_ONLY));
            byte[] reference = document(format, SANS, false, List.of(JAPANESE_ONLY));
            BufferedImage actual = convert(service, input, format);
            BufferedImage expected = convert(service, reference, format);
            try {
                require(inkPixels(actual) > 100 && Arrays.equals(pixels(expected), pixels(actual)),
                        "Missing Japanese glyphs did not fall back from " + withoutJapanese.orElseThrow());
                ImageIO.write(actual, "png", output.resolve(format + "-missing-glyphs.png").toFile());
                System.out.println("PASS " + format + " fills Japanese glyphs missing from " + withoutJapanese.orElseThrow());
            } finally {
                actual.flush();
                expected.flush();
            }
        } else {
            System.out.println("SKIP " + format + " missing-glyph comparison: no suitable physical Latin font");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
