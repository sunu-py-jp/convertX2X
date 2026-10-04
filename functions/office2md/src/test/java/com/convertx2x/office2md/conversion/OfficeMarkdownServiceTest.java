package com.convertx2x.office2md.conversion;

import com.convertx2x.office2md.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Map;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OfficeMarkdownServiceTest {
    private final OfficeMarkdownService service = new OfficeMarkdownService(ConversionLimits.defaults());

    @ParameterizedTest @ValueSource(strings={"xlsx", "docx", "pptx"})
    void optionalPdfRendersTheGeneratedMarkdownAndKeepsSourceArtifacts(String extension) throws Exception {
        try (ConversionResult result = service.convert(fixture(extension), "資料." + extension, OutputFormat.PDF)) {
            assertTrue(result.files().keySet().containsAll(java.util.Set.of("document.md", "document.pdf", "report.json")));
            byte[] pdf = result.pdfBytes();
            assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII));
            try (var document = Loader.loadPDF(pdf)) {
                assertTrue(new PDFTextStripper().getText(document).contains("保存した日本語"));
            }
            var report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
            assertEquals("document.pdf", report.path("pdfOutput").path("path").asText());
            assertTrue(report.path("pdfOutput").path("pageCount").asInt() > 0);
            assertEquals(pdf.length, report.path("pdfOutput").path("sizeBytes").asInt());
            assertEquals(result.warningCount(), report.path("warnings").size());
            assertTrue(result.zipBytes().length > pdf.length);
        }
    }

    @Test void pdfRendererWarningsAreIncludedInTheOfficeReport() throws Exception {
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            workspace.write("document.md", "# テスト".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            workspace.finishReport("sample.xlsx", "source-hash");
            workspace.addPdf("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 2,
                    new ObjectMapper().readTree("{\"warnings\":[{\"code\":\"IMAGE_UNSUPPORTED\",\"message\":\"画像を表示できません。\"},{\"code\":\"PDF_LAYOUT\",\"message\":\"配置を確認してください。\"}]}"));
            assertEquals(2, workspace.warningCount());
            var report = new ObjectMapper().readTree(workspace.files().get("report.json").toFile());
            assertEquals("PDF_IMAGE_UNSUPPORTED", report.path("warnings").get(0).path("code").asText());
            assertEquals("PDF_LAYOUT", report.path("warnings").get(1).path("code").asText());
            assertEquals(2, report.path("pdfOutput").path("pageCount").asInt());
            assertEquals(2, report.path("pdfOutput").path("warningCount").asInt());
        }
    }

    @Test void pdfUsesOfficeDrawingLineBreaksWithoutPrintingHtmlMarkup() throws Exception {
        byte[] input;
        try (var slides = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var table = slides.createSlide().createTable(2, 2);
            table.setAnchor(new java.awt.Rectangle(20, 20, 400, 140));
            table.getCell(0, 0).setText("受付\n承認");
            table.getCell(0, 1).setText("状態");
            table.getCell(1, 0).setText("東京");
            table.getCell(1, 1).setText("完了");
            slides.write(out);
            input = out.toByteArray();
        }
        try (ConversionResult result = service.convert(input, "flow.pptx", OutputFormat.PDF)) {
            String markdown = Files.readString(result.files().get("document.md"));
            assertTrue(markdown.contains("<br>"), markdown);
            try (var document = Loader.loadPDF(result.pdfBytes())) {
                String pdfText = new PDFTextStripper().getText(document);
                assertTrue(pdfText.contains("受付"));
                assertTrue(pdfText.contains("承認"));
                assertFalse(pdfText.contains("<br>"));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"xlsx", "docx", "pptx"})
    void routesAllOfficeFormatsAndPublishesGenericMetadata(String extension) throws Exception {
        byte[] input = fixture(extension);
        java.nio.file.Path directory;
        try (ConversionResult result = service.convert(input, "資料." + extension)) {
            directory = result.directory();
            String md = Files.readString(result.files().get("document.md"));
            assertTrue(md.contains("保存した日本語"));
            assertEquals(1, result.sectionCount());
            var report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
            assertEquals(1, report.path("specVersion").asInt());
            assertEquals(extension, report.path("source").path("format").asText());
            assertEquals(ConversionWorkspace.sha256(input), report.path("source").path("sha256").asText());
            assertEquals(switch(extension) {case "xlsx" -> "sheet"; case "pptx" -> "slide"; default -> "document";}, report.path("sectionKind").asText());
            assertTrue(result.zipBytes().length > 0);
        }
        assertFalse(Files.exists(directory));
    }

    @ParameterizedTest @ValueSource(strings={"doc", "ppt", "pdf", "docm", "pptm", "xlsm", "docb"})
    void unsupportedExtensionsAreNotEnabledByTheRename(String extension) throws Exception {
        var failure = assertThrows(ConversionException.class, () -> service.convert(fixture("docx"), "file." + extension));
        assertEquals(415, failure.statusCode());
    }

    @Test void rejectsMismatchedMainPartAndCanStillConvertNextDocument() throws Exception {
        assertThrows(ConversionException.class, () -> service.convert(fixture("xlsx"), "wrong.docx"));
        assertThrows(ConversionException.class, () -> service.convert(fixture("docx"), "wrong.pptx"));
        try (var result = service.convert(fixture("docx"), "valid.docx")) {
            assertTrue(Files.readString(result.files().get("document.md")).contains("保存した日本語"));
        }
    }

    @Test void genericLimitsTakePrecedenceAndLegacySettingsRemainAccepted() {
        var legacy = AppConfig.from(Map.of("CONVERSION_MAX_SHEETS", "12", "CONVERSION_MAX_READ_CELLS", "345"));
        assertEquals(12, legacy.limits().maxSections());
        assertEquals(345, legacy.limits().maxReadItems());
        var config = AppConfig.from(Map.of("CONVERSION_MAX_SECTIONS", "9", "CONVERSION_MAX_SHEETS", "12",
                "CONVERSION_MAX_READ_ITEMS", "234", "CONVERSION_MAX_READ_CELLS", "345"));
        assertEquals(9, config.limits().maxSections());
        assertEquals(234, config.limits().maxReadItems());
        assertEquals(0, AppConfig.from(Map.of("CONVERSION_MAX_SECTIONS", "0", "CONVERSION_MAX_SHEETS", "12")).limits().maxSections());
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_MAX_SECTIONS", "-1")));
    }

    @Test void moreThanFiftySheetsConvertWithDefaultLimits() throws Exception {
        byte[] input;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 51; i++) book.createSheet("Sheet " + i).createRow(0).createCell(0).setCellValue("Sheet content " + i);
            book.write(out); input = out.toByteArray();
        }
        try (var result = service.convert(input, "many-sheets.xlsx")) {
            assertEquals(51, result.sectionCount());
            assertTrue(Files.readString(result.files().get("document.md")).contains("Sheet content 51"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"CONVERSION_MAX_SECTIONS", "CONVERSION_MAX_READ_ITEMS", "CONVERSION_MAX_TABLE_CELLS", "CONVERSION_MAX_IMAGES", "CONVERSION_MAX_SHAPES", "CONVERSION_MAX_SHEETS", "CONVERSION_MAX_READ_CELLS"})
    void countLimitsAcceptZeroButRejectNegativeNumbers(String setting) {
        assertDoesNotThrow(() -> AppConfig.from(Map.of(setting, "0")));
        assertDoesNotThrow(() -> AppConfig.from(Map.of(setting, "1")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of(setting, "-1")));
    }

    @ParameterizedTest @ValueSource(strings={"CONVERSION_MAX_INPUT_BYTES", "CONVERSION_MAX_MARKDOWN_BYTES", "CONVERSION_MAX_IMAGE_BYTES", "CONVERSION_MAX_OUTPUT_BYTES", "CONVERSION_MAX_GROUP_DEPTH", "CONVERSION_MAX_IMAGE_PIXELS"})
    void memoryAndRecursionProtectionsRemainPositive(String setting) {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of(setting, "0")));
    }

    @Test void combinedCountsStayUnlimitedWhenEitherAssetSourceIsUnlimited() {
        assertEquals(0, AppConfig.from(Map.of("CONVERSION_MAX_IMAGES", "1")).limits().maxAssets());
        assertEquals(0, AppConfig.from(Map.of("CONVERSION_MAX_SHAPES", "1")).limits().maxAssets());
        assertEquals(3, AppConfig.from(Map.of("CONVERSION_MAX_IMAGES", "1", "CONVERSION_MAX_SHAPES", "2")).limits().maxAssets());
        assertDoesNotThrow(() -> ConversionLimits.defaults().checkTableCells(1_000_001));
    }

    private static byte[] fixture(String extension) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (extension) {
            case "xlsx" -> { try (var book = new XSSFWorkbook()) { book.createSheet("表").createRow(0).createCell(0).setCellValue("保存した日本語"); book.write(out); } }
            case "docx" -> { try (var doc = new XWPFDocument()) { doc.createParagraph().createRun().setText("保存した日本語"); doc.write(out); } }
            case "pptx" -> { try (var slides = new XMLSlideShow()) { var text = slides.createSlide().createTextBox(); text.setAnchor(new java.awt.Rectangle(20, 20, 300, 80)); text.setText("保存した日本語"); slides.write(out); } }
            default -> throw new IllegalArgumentException();
        }
        return out.toByteArray();
    }
}
