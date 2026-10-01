package com.convertx2x.office2pdf.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class OfficePdfServiceTest {
    private final OfficePdfService service = new OfficePdfService(ConversionLimits.defaults());

    @Test void excelBecomesSearchablePdfWithOneSectionPerVisibleSheet() throws Exception {
        byte[] input;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("売上");
            sheet.createRow(0).createCell(0).setCellValue("地域");
            sheet.getRow(0).createCell(1).setCellValue("金額");
            sheet.createRow(1).createCell(0).setCellValue("東京");
            sheet.getRow(1).createCell(1).setCellValue(12345);
            workbook.createSheet("非表示"); workbook.setSheetHidden(1, true);
            workbook.write(bytes); input = bytes.toByteArray();
        }
        try (ConversionResult result = service.convert(input, "sales.xlsx");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(1, result.sectionCount());
            assertTrue(pdf.getNumberOfPages() >= 1);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("売上"), text);
            assertTrue(text.contains("東京"), text);
            assertTrue(text.contains("12345"), text);
            String report = Files.readString(result.files().get("report.json"));
            assertTrue(report.contains("\"pageCount\""));
            assertTrue(report.contains("NORMALIZED_PDF"));
        }
    }

    @Test void wordParagraphAndTableRemainSearchable() throws Exception {
        byte[] input;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var heading = word.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("月次報告");
            word.createParagraph().createRun().setText("Document Intelligenceで抽出する本文です。");
            var table = word.createTable(2, 2);
            table.getRow(0).getCell(0).setText("項目"); table.getRow(0).getCell(1).setText("値");
            table.getRow(1).getCell(0).setText("件数"); table.getRow(1).getCell(1).setText("42");
            word.write(bytes); input = bytes.toByteArray();
        }
        try (ConversionResult result = service.convert(input, "report.docx");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("月次報告"), text);
            assertTrue(text.contains("Document Intelligence"), text);
            assertTrue(text.contains("件数"), text);
        }
    }

    @Test void powerpointRendersOnePdfPagePerSlide() throws Exception {
        byte[] input;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            show.setPageSize(new Dimension(720, 405));
            var slide = show.createSlide();
            var text = slide.createTextBox(); text.setAnchor(new Rectangle2D.Double(80, 100, 560, 100));
            text.setText("日本語スライド");
            show.write(bytes); input = bytes.toByteArray();
        }
        try (ConversionResult result = service.convert(input, "slides.pptx");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(1, result.sectionCount());
            assertEquals(1, pdf.getNumberOfPages());
            assertTrue(result.pdfBytes().length > 1_000);
        }
    }

    @Test void validatesFormatAndOptionalPageLimit() throws Exception {
        ConversionException unsupported = assertThrows(ConversionException.class,
                () -> service.convert(new byte[]{1, 2, 3}, "file.pdf"));
        assertEquals("UNSUPPORTED_FORMAT", unsupported.code());

        byte[] input;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("one");
            var second = word.createParagraph(); second.setPageBreak(true); second.createRun().setText("two");
            word.write(bytes); input = bytes.toByteArray();
        }
        OfficePdfService limited = new OfficePdfService(new ConversionLimits(10_000_000, 20_000_000, 1, 20_000_000));
        ConversionException failure = assertThrows(ConversionException.class, () -> limited.convert(input, "two.docx"));
        assertEquals("PAGE_LIMIT", failure.code(), String.valueOf(failure.getCause()));
    }
}
