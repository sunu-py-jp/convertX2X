package com.convertx2x.office2md.word;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.ImageMode;
import com.convertx2x.office2md.conversion.OfficeMarkdownService;
import com.convertx2x.office2md.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.HWPFList;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class LegacyWordMarkdownConverterTest {
    private final OfficeMarkdownService service = new OfficeMarkdownService(ConversionLimits.defaults());

    @Test void legacyWordBecomesPagedMarkdownWithTheSameStructureAsDocx() throws Exception {
        byte[] input = fixture();
        try (ConversionResult result = service.convert(input, "報告.doc")) {
            assertEquals(1, result.sectionCount());
            String markdown = Files.readString(result.files().get("document.md"));
            assertEquals("""
                    [page 1]

                    **月次報告**

                    Document Intelligenceで抽出する**本文**です。

                    一行目
                    改行後の二行目

                    |  |  |
                    | --- | --- |
                    | 項目 | 値 |
                    | 件数 | 42 |
                    | 合計 | 1,234 |

                    表の後の段落

                    [page 2]

                    二ページ目の本文

                    """, markdown);
            JsonNode report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
            assertEquals("doc", report.path("source").path("format").asText());
            assertEquals("document", report.path("sectionKind").asText());
            assertEquals(ConversionWorkspace.sha256(input), report.path("source").path("sha256").asText());
            assertEquals(List.of("STRIKETHROUGH_REMOVED"), codes(report, "information"));
            assertEquals(List.of(), codes(report, "warnings"));

            List<String> types = new ArrayList<>();
            for (JsonNode block : report.path("blocks")) types.add(block.path("type").asText() + "@" + block.path("markdownStartLine").asInt());
            assertEquals(List.of("paragraph@3", "paragraph@5", "paragraph@7", "table@10", "paragraph@16", "paragraph@20"), types);
        }
    }

    @Test void docExtensionAcceptsOnlyWordBinaryDocumentsAndDocxKeepsRejectingThem() throws Exception {
        byte[] docx;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("renamed");
            word.write(out); docx = out.toByteArray();
        }
        ConversionException renamed = assertThrows(ConversionException.class, () -> service.convert(docx, "renamed.doc"));
        assertEquals(415, renamed.statusCode());
        assertEquals("UNSUPPORTED_FORMAT", renamed.code());

        byte[] workbook;
        try (HSSFWorkbook book = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("sheet").createRow(0).createCell(0).setCellValue("not word");
            book.write(out); workbook = out.toByteArray();
        }
        assertEquals(415, assertThrows(ConversionException.class, () -> service.convert(workbook, "book.doc")).statusCode());

        // The DOCX reader is unchanged: binary content under a .docx name is still refused.
        assertEquals(415, assertThrows(ConversionException.class, () -> service.convert(fixture(), "legacy.docx")).statusCode());
        try (ConversionResult result = service.convert(fixture(), "legacy.doc")) {
            assertTrue(Files.readString(result.files().get("document.md")).contains("二ページ目の本文"));
        }
    }

    @Test void limitsApplyToLegacyWordLikeDocx() throws Exception {
        ConversionLimits defaults = ConversionLimits.defaults();
        var tiny = new LegacyWordMarkdownConverter(new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(),
                defaults.maxReadItems(), defaults.maxTableCells(), 40, defaults.maxImages(), defaults.maxImageBytes(),
                defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels()));
        ConversionException failure = assertThrows(ConversionException.class,
                () -> tiny.convert(fixture(), "report.doc", ImageMode.IGNORE, OcrClient.disabled()));
        assertEquals("MARKDOWN_BYTES_LIMIT", failure.code());
    }

    @Test void headingLevelUsesTheStyleNameThenTheOutlineLevel() {
        assertEquals(1, LegacyWordMarkdownConverter.headingLevel("heading 1", 9));
        assertEquals(2, LegacyWordMarkdownConverter.headingLevel("見出し 2", 9));
        assertEquals(6, LegacyWordMarkdownConverter.headingLevel("Heading 6", 9));
        // Word stores outline levels 0-8 for headings and 9 for body text; Markdown has six levels.
        assertEquals(1, LegacyWordMarkdownConverter.headingLevel("Normal", 0));
        assertEquals(3, LegacyWordMarkdownConverter.headingLevel("社内見出し", 2));
        assertEquals(0, LegacyWordMarkdownConverter.headingLevel("Normal", 6));
        assertEquals(0, LegacyWordMarkdownConverter.headingLevel("Normal", 9));
        assertEquals(0, LegacyWordMarkdownConverter.headingLevel(null, -1));
    }

    @Test void binaryListDefinitionsUseTheSharedNumbering() throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(fixture()))) {
            var numbered = LegacyWordMarkdownConverter.definition(new HWPFList(true, document.getStyleSheet()));
            assertEquals("decimal", numbered.formats()[0]);
            assertTrue(numbered.texts()[0].contains("%1"), numbered.texts()[0]);
            assertEquals(1, numbered.starts()[0]);
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8}, numbered.restarts());
            var bullets = LegacyWordMarkdownConverter.definition(new HWPFList(false, document.getStyleSheet()));
            assertEquals("bullet", bullets.formats()[0]);

            ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.IGNORE, OcrClient.disabled());
            try {
                WordListNumbers lists = new WordListNumbers(workspace);
                String first = lists.prefix("1", 0, numbered, "document", "paragraph:1");
                String second = lists.prefix("1", 0, numbered, "document", "paragraph:2");
                assertTrue(first.startsWith("1"), first);
                assertTrue(second.startsWith("2"), second);
                assertEquals("- ", lists.prefix("2", 0, bullets, "document", "paragraph:3"));
                assertEquals("  - ", lists.prefix("2", 1, bullets, "document", "paragraph:4"));
            } finally { workspace.close(); }
        }
        assertEquals("upperRoman", LegacyWordMarkdownConverter.numberFormat(1));
        assertEquals("lowerLetter", LegacyWordMarkdownConverter.numberFormat(4));
        assertEquals("none", LegacyWordMarkdownConverter.numberFormat(255));
    }

    private static List<String> codes(JsonNode report, String kind) {
        List<String> codes = new ArrayList<>();
        for (JsonNode entry : report.path(kind)) codes.add(entry.path("code").asText());
        return codes;
    }

    private static byte[] fixture() throws Exception {
        try (InputStream stream = LegacyWordMarkdownConverterTest.class.getResourceAsStream("/legacy/word-sample.doc")) {
            return stream.readAllBytes();
        }
    }
}
