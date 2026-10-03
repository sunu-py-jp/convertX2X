package com.convertx2x.md2pdf.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownPdfServiceTest {
    private final MarkdownPdfService service = new MarkdownPdfService(ConversionLimits.defaults());

    @Test void rendersJapaneseHeadingsListsQuotesCodeAndTablesAsSearchableText() throws Exception {
        String markdown = """
                # 月次報告

                日本語の **重要な本文** と `inlineCode`。

                ## 実施項目

                - 集計作業
                  - 子項目
                - 確認作業

                1. 最初の手順
                2. 次の手順

                > 引用した説明です。

                ```java
                int total = 42;
                ```

                | 項目 | 値 |
                | :--- | ---: |
                | 東京 | 12345 |
                | 大阪 | 67890 |
                """;
        try (ConversionResult result = service.convert(utf8(markdown), "月次報告.md");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf);
            for (String expected : new String[]{"月次報告", "重要な本文", "inlineCode", "実施項目", "集計作業", "子項目",
                    "確認作業", "最初の手順", "次の手順", "引用した説明です。", "int total = 42;", "項目", "東京", "12345", "大阪", "67890"}) {
                assertTrue(text.contains(expected), expected + " absent from PDF: " + text);
            }
            assertEquals(1, result.sectionCount());
            assertEquals(pdf.getNumberOfPages(), result.pageCount());
            JsonNode report = report(result);
            assertEquals("md", report.path("source").path("format").asText());
            assertEquals("document", report.path("sectionKind").asText());
            assertEquals(1, report.path("sectionCount").asInt());
            assertEquals(ConversionWorkspace.sha256(utf8(markdown)), report.path("source").path("sha256").asText());
            assertEquals(result.pdfBytes().length, report.path("output").path("sizeBytes").asLong());
            assertEquals(0, result.warningCount());
        }
    }

    @Test void markdownExtensionAndUtf8BomAreAccepted() throws Exception {
        try (ConversionResult result = service.convert(utf8("\uFEFF# 日本語\n\n本文です。"), "input.MARKDOWN");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("日本語"));
            assertEquals("markdown", report(result).path("source").path("format").asText());
            assertEquals(0, result.warningCount());
        }
    }

    @Test void explicitPageBreakStartsFollowingTextOnNextPage() throws Exception {
        try (ConversionResult result = service.convert(utf8("# 第一章\n\n一枚目の本文\n\n<!-- pagebreak -->\n\n# 第二章\n\n二枚目の本文"), "pages.md");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(2, pdf.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1); stripper.setEndPage(1);
            String first = stripper.getText(pdf);
            assertTrue(first.contains("第一章"));
            assertFalse(first.contains("第二章"));
            stripper.setStartPage(2); stripper.setEndPage(2);
            assertTrue(stripper.getText(pdf).contains("第二章"));
            assertEquals(0, result.warningCount());
        }
    }

    @Test void optionalPageLimitIsEnforcedWhileDefaultAllowsMultiplePages() {
        MarkdownPdfService limited = new MarkdownPdfService(new ConversionLimits(1_000_000, 10_000_000, 1, 20_000_000));
        assertEquals("PAGE_LIMIT", assertThrows(ConversionException.class,
                () -> limited.convert(utf8("first\n\n<!-- pagebreak -->\n\nsecond"), "pages.md")).code());
    }

    @Test void base64PngIsEmbeddedWithItsAltText() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(png());
        try (ConversionResult result = service.convert(utf8("# 画像\n\n![日本語の図](data:image/png;base64," + encoded + ")"), "image.md");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(1, imageCount(pdf));
            assertTrue(new PDFTextStripper().getText(pdf).contains("日本語の図"));
            assertEquals(0, result.warningCount());
        }
    }

    @Test void zipResolvesImagesRelativeToTheSelectedMarkdownDirectory() throws Exception {
        byte[] input = zip(Map.of("docs/report.md", utf8("# 添付画像\n\n![画像の説明](images/chart.png)"),
                "docs/images/chart.png", png()));
        try (ConversionResult result = service.convert(input, "bundle.zip");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(1, imageCount(pdf));
            assertTrue(new PDFTextStripper().getText(pdf).contains("画像の説明"));
            assertEquals("zip", report(result).path("source").path("format").asText());
            assertEquals(0, result.warningCount());
        }
    }

    @Test void zipPrefersRootDocumentOverAdditionalMarkdownFiles() throws Exception {
        byte[] input = zip(Map.of("document.md", utf8("# SelectedDocument"),
                "README.md", utf8("UnselectedDocument"), "docs/note.markdown", utf8("UnselectedNestedDocument")));
        try (ConversionResult result = service.convert(input, "bundle.zip");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("SelectedDocument"));
            assertFalse(text.contains("UnselectedDocument"));
            assertFalse(text.contains("UnselectedNestedDocument"));
        }
    }

    @Test void rejectsInvalidUtf8UnsupportedExtensionAndEmptyInput() {
        assertEquals("INVALID_ENCODING", assertThrows(ConversionException.class,
                () -> service.convert(new byte[]{(byte) 0xc3, 0x28}, "invalid.md")).code());
        assertEquals("UNSUPPORTED_FORMAT", assertThrows(ConversionException.class,
                () -> service.convert(utf8("# heading"), "input.docx")).code());
        assertEquals("EMPTY_INPUT", assertThrows(ConversionException.class,
                () -> service.convert(new byte[0], "empty.md")).code());
    }

    @Test void rejectsMalformedZipMissingMarkdownAndAmbiguousMarkdown() throws Exception {
        assertClientFailure(() -> service.convert(utf8("not a ZIP archive"), "broken.zip"));
        byte[] missing = zip(Map.of("image.png", png()));
        assertClientFailure(() -> service.convert(missing, "missing.zip"));
        byte[] ambiguous = zip(Map.of("first.md", utf8("one"), "nested/second.markdown", utf8("two")));
        assertClientFailure(() -> service.convert(ambiguous, "ambiguous.zip"));
    }

    @Test void rejectsUnsafeArchivePathsInsteadOfWritingOutsideWorkspace() throws Exception {
        for (String path : new String[]{"../escape.png", "/absolute.png", "C:/escape.png", "nested/../../escape.png"}) {
            byte[] archive = zip(Map.of("document.md", utf8("# 文書"), path, png()));
            assertClientFailure(() -> service.convert(archive, "unsafe.zip"));
        }
    }

    @Test void validateRejectsCompressedExpansionAndInvalidUtf8InsideZip() throws Exception {
        byte[] compressed = zip(Map.of("document.md", utf8("text ".repeat(2_000))));
        assertTrue(compressed.length < 1_024);
        MarkdownPdfService limited = new MarkdownPdfService(new ConversionLimits(1_024, 1_000_000, 0, 20_000_000));
        assertEquals("INPUT_BYTES_LIMIT", assertThrows(ConversionException.class,
                () -> limited.validate(compressed, "compressed.zip")).code());
        byte[] invalidUtf8 = zip(Map.of("document.md", new byte[]{(byte) 0xc3, 0x28}));
        assertEquals("INVALID_ENCODING", assertThrows(ConversionException.class,
                () -> service.validate(invalidUtf8, "invalid.zip")).code());
    }

    @Test void remoteAndMissingImagesKeepCaptionsAndProduceWarnings() throws Exception {
        try (ConversionResult result = service.convert(utf8("![遠隔の図](https://images.invalid/never-fetch.png)\n\n![不足の図](missing.png)"), "images.md");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("遠隔の図"));
            assertTrue(text.contains("不足の図"));
            assertEquals(0, imageCount(pdf));
            assertTrue(result.warningCount() >= 2);
        }
    }

    @Test void localFileAndZipEscapingImageReferencesAreNotRead() throws Exception {
        String markdown = "![ローカル画像](file:///tmp/private.png)\n\n![外部画像](../../outside.png)\n\n![絶対パス画像](/tmp/private.png)";
        byte[] input = zip(Map.of("docs/document.md", utf8(markdown)));
        try (ConversionResult result = service.convert(input, "references.zip");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("ローカル画像"));
            assertTrue(text.contains("外部画像"));
            assertTrue(text.contains("絶対パス画像"));
            assertEquals(0, imageCount(pdf));
            assertEquals(3, result.warningCount());
        }
    }

    @Test void htmlAndMermaidArePreservedAsTextWithWarnings() throws Exception {
        String input = "<div>HTML本文</div>\n\n```mermaid\ngraph LR\nA-->B\n```";
        try (ConversionResult result = service.convert(utf8(input), "special.md");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("<div>HTML本文</div>"), text);
            assertTrue(text.contains("graph LR"), text);
            assertTrue(text.contains("A-->B"), text);
            assertTrue(result.warningCount() >= 2);
        }
    }

    @Test void longTableCellFlowsAcrossPagesWithoutLosingItsEnd() throws Exception {
        String longCell = "長い説明を省略せず保持する。".repeat(1_200) + "末尾確認END";
        try (ConversionResult result = service.convert(utf8("| 内容 | 値 |\n| --- | --- |\n| " + longCell + " | ENDVALUE |\n"), "long-table.md");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", "");
            assertTrue(pdf.getNumberOfPages() > 1);
            assertTrue(text.contains("末尾確認END"), "The last part of the long cell must remain in the PDF");
            assertTrue(text.contains("ENDVALUE"));
            assertEquals(1_200, text.codePoints().filter(character -> character == '長').count());
            assertEquals(0, result.warningCount());
        }
    }

    @Test void inputAndOutputByteLimitsAreEnforced() {
        MarkdownPdfService smallInput = new MarkdownPdfService(new ConversionLimits(8, 1_000_000, 0, 20_000_000));
        assertEquals("INPUT_BYTES_LIMIT", assertThrows(ConversionException.class,
                () -> smallInput.convert(utf8("123456789"), "input.md")).code());
        MarkdownPdfService smallOutput = new MarkdownPdfService(new ConversionLimits(1_000_000, 64, 0, 20_000_000));
        assertEquals("OUTPUT_BYTES_LIMIT", assertThrows(ConversionException.class,
                () -> smallOutput.convert(utf8("# 日本語の本文"), "input.md")).code());
    }

    @Test void closingResultRemovesItsTemporaryWorkspace() {
        ConversionResult result = service.convert(utf8("# 文書"), "input.md");
        Path directory = result.directory();
        assertTrue(Files.exists(directory));
        result.close();
        assertFalse(Files.exists(directory));
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static JsonNode report(ConversionResult result) throws Exception {
        return new ObjectMapper().readTree(Files.readAllBytes(result.files().get("report.json")));
    }

    private static void assertClientFailure(org.junit.jupiter.api.function.Executable action) {
        ConversionException failure = assertThrows(ConversionException.class, action);
        assertTrue(failure.statusCode() >= 400 && failure.statusCode() < 500, failure.code());
    }

    private static int imageCount(PDDocument document) throws Exception {
        int count = 0;
        for (var page : document.getPages()) {
            for (var name : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(name) instanceof PDImageXObject) count++;
            }
        }
        return count;
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, x < 12 ? Color.BLUE.getRGB() : Color.GREEN.getRGB());
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        }
    }
}
