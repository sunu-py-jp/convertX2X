package com.convertx2x.md2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalizedPdfWriterTest {
    @Test void exactFitColumnTextDoesNotWrapBecauseOfScaledGlyphRounding() throws Exception {
        List<String> headers = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(column -> "項目%d / 判定条件".formatted(column)).toList();
        List<String> body = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(column -> "FIELD%02d".formatted(column)).toList();
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(List.of(headers, body));
            writer.table(null, List.of(headers, body));
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                String text = pageText(pdf, 1);
                for (String header : headers) assertTrue(text.contains(header), header + " wrapped: " + text);
                for (String value : body) assertTrue(text.contains(value), value + " wrapped: " + text);
            }
        }
    }

    @Test void exactFitBodyTextIsNotSplitWhenBodyDeterminesScaledColumnWidth() throws Exception {
        List<String> headers = java.util.Collections.nCopies(24, "ID");
        List<String> body = java.util.stream.IntStream.rangeClosed(1, 24)
                .mapToObj(column -> "FIELD%02d".formatted(column)).toList();
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(List.of(headers, body));
            writer.table(null, List.of(headers, body));
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                String text = pageText(pdf, 1);
                for (String value : body) assertTrue(text.contains(value), value + " wrapped: " + text);
            }
        }
    }

    @Test void wideTableKeepsEveryColumnOnOneLandscapePageAndScalesText() throws Exception {
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(table(20, 3));
            writer.table(null, table(20, 3));
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                assertEquals(1, pdf.getNumberOfPages());
                assertLandscape(pdf, 0);
                String text = pageText(pdf, 1).replaceAll("\\s+", "");
                for (int column = 1; column <= 20; column++) {
                    assertTrue(text.contains(header(column)), text);
                    for (int row = 1; row <= 3; row++) assertTrue(text.contains(cell(row, column)), text);
                }
                assertFalse(text.contains("列1–8"), "Column-slice labels must no longer be generated");
                List<TextPosition> positions = assertTextInsidePage(pdf, 1);
                assertTrue(positions.stream().anyMatch(position -> position.getFontSizeInPt() < 8f),
                        "Wide table text must scale together with its columns");
            }
        }
    }

    @Test void landscapeContinuationPagesRepeatAllHeadersWithoutDroppingBodyCells() throws Exception {
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(table(20, 140));
            writer.table(null, table(20, 140));
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                assertTrue(pdf.getNumberOfPages() > 1);
                StringBuilder all = new StringBuilder();
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                    assertLandscape(pdf, page - 1);
                    String text = pageText(pdf, page).replaceAll("\\s+", "");
                    for (int column = 1; column <= 20; column++) assertTrue(text.contains(header(column)), text);
                    all.append(text);
                    assertTextInsidePage(pdf, page);
                }
                for (int row = 1; row <= 140; row++) for (int column = 1; column <= 20; column++) {
                    String value = cell(row, column);
                    assertEquals(1, all.toString().split(value, -1).length - 1, value);
                }
            }
        }
    }

    @Test void veryManyColumnsStillFitWithinPageWithoutLosingAnyColumn() throws Exception {
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(table(120, 1));
            writer.table(null, table(120, 1));
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                assertEquals(1, pdf.getNumberOfPages());
                assertLandscape(pdf, 0);
                String text = pageText(pdf, 1).replaceAll("\\s+", "");
                for (int column = 1; column <= 120; column++) assertTrue(text.contains(cell(1, column)), text);
                assertTextInsidePage(pdf, 1);
            }
        }
    }

    @Test void cellWidthsSelectLandscapeAndSurroundingContentSharesTheSamePage() throws Exception {
        List<List<String>> wide = List.of(List.of("長い列見出し".repeat(5), "長い列見出し".repeat(5),
                "長い列見出し".repeat(5), "長い列見出し".repeat(5)), List.of("A", "B", "C", "D"));
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(wide);
            writer.paragraph("Before table", false);
            writer.table(null, wide);
            writer.richParagraph(List.of(new NormalizedPdfWriter.TextSpan("After table link", false, false, false,
                    "https://example.com/after")), 0, false);
            writer.horizontalRule(0);
            writer.image(new BufferedImage(1400, 100, BufferedImage.TYPE_INT_RGB), "Image after table");
            writer.table(null, List.of(List.of("Name", "Value"), List.of("Ordinary", "100")));
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                assertEquals(1, pdf.getNumberOfPages());
                assertLandscape(pdf, 0);
                String text = pageText(pdf, 1);
                assertTrue(text.contains("Before table"));
                assertTrue(text.contains("After table link"));
                assertTrue(text.contains("Image after table"));
                assertTrue(text.contains("Ordinary"));
                assertEquals(1, pdf.getPage(0).getAnnotations().size());
                PDRectangle link = pdf.getPage(0).getAnnotations().getFirst().getRectangle();
                assertTrue(link.getLowerLeftX() >= 36f);
                assertTrue(link.getUpperRightX() <= PDRectangle.A4.getHeight() - 36f);
                assertTrue(link.getLowerLeftY() >= 36f);
                assertTrue(link.getUpperRightY() <= PDRectangle.A4.getWidth() - 35f);
                assertTextInsidePage(pdf, 1);
            }
        }
    }

    @Test void ordinaryDocumentWithoutWideTablesKeepsPortraitPages() throws Exception {
        List<List<String>> rows = List.of(List.of("Name", "Value"), List.of("Ordinary", "100"));
        try (NormalizedPdfWriter writer = writer()) {
            writer.prepareTable(rows);
            writer.heading("Ordinary document", 1);
            writer.table(null, rows);
            try (PDDocument pdf = Loader.loadPDF(writer.finish())) {
                assertEquals(1, pdf.getNumberOfPages());
                assertEquals(PDRectangle.A4.getWidth(), pdf.getPage(0).getMediaBox().getWidth());
                assertEquals(PDRectangle.A4.getHeight(), pdf.getPage(0).getMediaBox().getHeight());
            }
        }
    }

    private static NormalizedPdfWriter writer() throws IOException {
        return new NormalizedPdfWriter(ConversionLimits.defaults(), warning -> fail(warning));
    }

    private static List<List<String>> table(int columns, int bodyRows) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(NormalizedPdfWriterTest::header).toList());
        for (int row = 1; row <= bodyRows; row++) {
            int number = row;
            rows.add(java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(column -> cell(number, column)).toList());
        }
        return rows;
    }

    private static String header(int column) { return "列見出し項目%03d".formatted(column); }
    private static String cell(int row, int column) { return "R%03dC%03d".formatted(row, column); }

    private static String pageText(PDDocument pdf, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(pdf);
    }

    private static void assertLandscape(PDDocument pdf, int page) {
        assertEquals(PDRectangle.A4.getHeight(), pdf.getPage(page).getMediaBox().getWidth());
        assertEquals(PDRectangle.A4.getWidth(), pdf.getPage(page).getMediaBox().getHeight());
    }

    private static List<TextPosition> assertTextInsidePage(PDDocument pdf, int page) throws IOException {
        List<TextPosition> positions = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override protected void processTextPosition(TextPosition position) {
                positions.add(position);
                super.processTextPosition(position);
            }
        };
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        stripper.getText(pdf);
        PDRectangle box = pdf.getPage(page - 1).getMediaBox();
        for (TextPosition position : positions) {
            assertTrue(position.getXDirAdj() >= 35.9f, position.toString());
            assertTrue(position.getXDirAdj() + position.getWidthDirAdj() <= box.getWidth() - 35.9f, position.toString());
            assertTrue(position.getYDirAdj() >= 35.9f, position.toString());
            assertTrue(position.getYDirAdj() <= box.getHeight() - 20f, position.toString());
        }
        return positions;
    }
}
