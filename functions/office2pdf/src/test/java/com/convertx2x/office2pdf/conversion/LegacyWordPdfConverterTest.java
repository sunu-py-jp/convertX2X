package com.convertx2x.office2pdf.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class LegacyWordPdfConverterTest {
    private final OfficePdfService service = new OfficePdfService(ConversionLimits.defaults());

    @Test void legacyWordParagraphsTableAndPageBreakRemainSearchable() throws Exception {
        byte[] input = fixture();
        try (ConversionResult result = service.convert(input, "report.doc");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(1, result.sectionCount());
            String text = new PDFTextStripper().getText(pdf);
            for (String expected : new String[] {"月次報告", "Document Intelligence", "本文", "一行目", "改行後の二行目",
                    "Word 表", "項目", "件数", "42", "1,234", "表の後の段落", "二ページ目の本文"})
                assertTrue(text.contains(expected), expected + " in " + text);
            assertFalse(text.contains("\u0007"), "cell marks are not text");

            // The explicit page break starts a new page: the last page holds only the text after it.
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(pdf.getNumberOfPages());
            String lastPage = last.getText(pdf);
            assertTrue(pdf.getNumberOfPages() >= 2);
            assertTrue(lastPage.contains("二ページ目の本文"), lastPage);
            assertFalse(lastPage.contains("表の後の段落"), lastPage);

            var report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
            assertEquals("doc", report.path("source").path("format").asText());
            assertEquals("document", report.path("sectionKind").asText());
        }
    }

    @Test void docExtensionAcceptsOnlyWordBinaryDocuments() throws Exception {
        byte[] docx;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("renamed");
            word.write(bytes); docx = bytes.toByteArray();
        }
        ConversionException renamed = assertThrows(ConversionException.class, () -> service.convert(docx, "renamed.doc"));
        assertEquals(415, renamed.statusCode());
        assertEquals("UNSUPPORTED_FORMAT", renamed.code());

        byte[] workbook;
        try (HSSFWorkbook book = new HSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            book.createSheet("sheet").createRow(0).createCell(0).setCellValue("not word");
            book.write(bytes); workbook = bytes.toByteArray();
        }
        ConversionException other = assertThrows(ConversionException.class, () -> service.convert(workbook, "book.doc"));
        assertEquals(415, other.statusCode());
        assertEquals("UNSUPPORTED_FORMAT", other.code());

        // A rejected input leaves the shared conversion slot usable.
        try (ConversionResult result = service.convert(fixture(), "after.doc")) {
            assertEquals(1, result.sectionCount());
        }
    }

    @Test void docxInputIsNotAffectedByTheLegacyReader() throws Exception {
        byte[] docx;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var heading = word.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("新形式の見出し");
            word.write(bytes); docx = bytes.toByteArray();
        }
        try (ConversionResult result = service.convert(docx, "modern.docx");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("新形式の見出し"));
            var report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
            assertEquals("docx", report.path("source").path("format").asText());
        }
    }

    @Test void headingLevelUsesTheStyleNameThenTheOutlineLevel() {
        assertEquals(1, LegacyWordPdfConverter.headingLevel("heading 1", 9));
        assertEquals(2, LegacyWordPdfConverter.headingLevel("見出し 2", 9));
        assertEquals(3, LegacyWordPdfConverter.headingLevel("heading 6", 9));
        // Word stores the outline level 0-8 for headings; 9 is body text.
        assertEquals(1, LegacyWordPdfConverter.headingLevel("Normal", 0));
        assertEquals(2, LegacyWordPdfConverter.headingLevel("社内見出し", 1));
        assertEquals(3, LegacyWordPdfConverter.headingLevel(null, 5));
        assertEquals(0, LegacyWordPdfConverter.headingLevel("Normal", 9));
        assertEquals(0, LegacyWordPdfConverter.headingLevel(null, -1));
    }

    private static byte[] fixture() throws Exception {
        try (InputStream stream = LegacyWordPdfConverterTest.class.getResourceAsStream("/legacy/word-sample.doc")) {
            return stream.readAllBytes();
        }
    }
}
