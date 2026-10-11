package com.convertx2x.office2pdf.conversion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.junit.jupiter.api.Test;

class PowerPointSearchablePdfTest {
    private static final Dimension PAGE = new Dimension(720, 405);
    private static final String FONT = "BIZ UDPGothic";
    private final OfficePdfService service = new OfficePdfService(ConversionLimits.defaults());

    @Test void japaneseRunsTablesAndTransformedGroupsAreSearchableOnTheirOwnPages() throws Exception {
        byte[] input = presentation();
        try (ConversionResult result = service.convert(input, "searchable-slides.pptx");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(2, result.sectionCount());
            assertEquals(2, pdf.getNumberOfPages());
            String first = pageText(pdf, 1);
            String second = pageText(pdf, 2);
            for (String expected : List.of("四半期売上報告", "Document Intelligence", "中央配置の本文", "下端配置の注記")) {
                assertTrue(first.contains(expected), first);
                assertEquals(1, occurrences(first, expected), "Measurement must not duplicate " + expected);
                assertFalse(second.contains(expected), second);
            }
            for (String expected : List.of("部門別の実績", "営業部", "開発部", "120", "98", "拡大回転グループ")) {
                assertTrue(second.contains(expected), second);
                assertEquals(1, occurrences(second, expected), "Text must occur once: " + expected);
                assertFalse(first.contains(expected), first);
            }
            assertFalse((first + second).contains("非表示の機密メモ"));

            PositionStripper positions = new PositionStripper();
            positions.getText(pdf);
            assertFalse(positions.positions.isEmpty());
            for (TextPosition position : positions.positions) {
                assertTrue(position.getX() >= 0 && position.getX() <= PAGE.width,
                        () -> "Text outside slide horizontally: " + position);
                assertTrue(position.getY() >= 0 && position.getY() <= PAGE.height,
                        () -> "Text outside slide vertically: " + position);
                assertTrue(position.getFont().isEmbedded(), "Searchable Unicode font must be embedded");
            }
            assertOriginWithin(positions.positions, "中", new Rectangle2D.Double(48, 195, 620, 40));
            assertOriginWithin(positions.positions, "下", new Rectangle2D.Double(48, 335, 620, 27));
            assertOriginWithin(positions.positions, "拡", new Rectangle2D.Double(430, 170, 250, 110));
            if (Boolean.getBoolean("searchablePptDemo")) {
                Path directory = Path.of("target", "searchable-ppt-demo");
                Files.createDirectories(directory);
                Files.write(directory.resolve("searchable-slides.pptx"), input);
                Files.write(directory.resolve("searchable-slides.pdf"), result.pdfBytes());
                Files.writeString(directory.resolve("extracted-text.txt"), first + "\n--- Page 2 ---\n" + second);
                PDFRenderer renderer = new PDFRenderer(pdf);
                for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                    ImageIO.write(renderer.renderImage(index, 1.5f), "png",
                            directory.resolve("page-" + (index + 1) + ".png").toFile());
                }
            }
        }
    }

    @Test void invisibleTextLeavesTheOriginalPoiSlideRenderingPixelIdentical() throws Exception {
        byte[] input = presentation();
        try (ConversionResult result = service.convert(input, "visual-fidelity.pptx");
             PDDocument actual = Loader.loadPDF(result.pdfBytes());
             XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(input));
             PDDocument expected = new PDDocument()) {
            for (XSLFSlide slide : show.getSlides()) {
                if (slide.isHidden()) continue;
                BufferedImage image = renderWithOriginalPoi(slide);
                PDPage page = new PDPage(new PDRectangle(PAGE.width, PAGE.height));
                expected.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(expected, page)) {
                    stream.drawImage(LosslessFactory.createFromImage(expected, image), 0, 0, PAGE.width, PAGE.height);
                } finally {
                    image.flush();
                }
            }
            PDFRenderer actualRenderer = new PDFRenderer(actual);
            PDFRenderer expectedRenderer = new PDFRenderer(expected);
            for (int page = 0; page < actual.getNumberOfPages(); page++) {
                BufferedImage expectedImage = expectedRenderer.renderImage(page, 2f);
                BufferedImage actualImage = actualRenderer.renderImage(page, 2f);
                try {
                    assertEquals(expectedImage.getWidth(), actualImage.getWidth());
                    assertEquals(expectedImage.getHeight(), actualImage.getHeight());
                    for (int row = 0; row < expectedImage.getHeight(); row++) {
                        assertArrayEquals(expectedImage.getRGB(0, row, expectedImage.getWidth(), 1, null, 0, expectedImage.getWidth()),
                                actualImage.getRGB(0, row, actualImage.getWidth(), 1, null, 0, actualImage.getWidth()),
                                "Slide " + (page + 1) + ", image row " + row);
                    }
                } finally {
                    expectedImage.flush();
                    actualImage.flush();
                }
            }
        }
    }

    @Test void legacyPptAlsoPreservesSearchableJapaneseText() throws Exception {
        byte[] input;
        try (HSLFSlideShow show = new HSLFSlideShow(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            show.setPageSize(PAGE);
            var slide = show.createSlide();
            HSLFTextBox box = new HSLFTextBox();
            slide.addShape(box);
            box.setAnchor(new Rectangle2D.Double(70, 100, 580, 130));
            box.setVerticalAlignment(VerticalAlignment.MIDDLE);
            var run = box.setText("旧形式でも日本語を検索");
            run.setFontFamily(FONT);
            run.setFontSize(28d);
            show.write(bytes);
            input = bytes.toByteArray();
        }
        try (ConversionResult result = service.convert(input, "legacy.ppt");
             PDDocument pdf = Loader.loadPDF(result.pdfBytes())) {
            assertEquals(1, result.sectionCount());
            assertEquals(1, pdf.getNumberOfPages());
            String text = pageText(pdf, 1);
            assertTrue(text.contains("旧形式でも日本語を検索"), text);
            assertEquals(1, occurrences(text, "旧形式でも日本語を検索"), text);
            PositionStripper positions = new PositionStripper();
            positions.getText(pdf);
            assertFalse(positions.positions.isEmpty());
        }
    }

    private static byte[] presentation() throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            show.setPageSize(PAGE);
            XSLFSlide first = show.createSlide();
            XSLFTextBox title = textBox(first, "", 42, 32, 640, 65, 32);
            title.clearText();
            var paragraph = title.addNewTextParagraph();
            addRun(paragraph.addNewTextRun(), "四半期", 32, false, new Color(24, 48, 78));
            addRun(paragraph.addNewTextRun(), "売上", 32, true, new Color(18, 112, 159));
            addRun(paragraph.addNewTextRun(), "報告", 32, false, new Color(24, 48, 78));
            textBox(first, "Document Intelligence で全文検索", 44, 98, 630, 42, 20);
            XSLFTextBox middle = textBox(first, "中央配置の本文", 48, 163, 620, 90, 25);
            middle.setFillColor(new Color(235, 245, 251));
            middle.setVerticalAlignment(VerticalAlignment.MIDDLE);
            XSLFTextBox bottom = textBox(first, "下端配置の注記", 48, 280, 620, 82, 19);
            bottom.setLineColor(new Color(170, 190, 205));
            bottom.setVerticalAlignment(VerticalAlignment.BOTTOM);

            XSLFSlide hidden = show.createSlide();
            textBox(hidden, "非表示の機密メモ", 50, 50, 600, 100, 28);
            hidden.setHidden(true);

            XSLFSlide second = show.createSlide();
            textBox(second, "部門別の実績", 42, 32, 640, 65, 32);
            var table = second.createTable(3, 2);
            table.setAnchor(new Rectangle2D.Double(48, 127, 340, 156));
            table.setColumnWidth(0, 210);
            table.setColumnWidth(1, 130);
            String[][] values = {{"部門", "達成率"}, {"営業部", "120"}, {"開発部", "98"}};
            for (int row = 0; row < values.length; row++) {
                table.setRowHeight(row, 52);
                for (int column = 0; column < values[row].length; column++) {
                    var cell = table.getCell(row, column);
                    addRun(cell.setText(values[row][column]), values[row][column], 21, row == 0, new Color(24, 48, 78));
                    cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
                    cell.setFillColor(row == 0 ? new Color(222, 237, 247) : new Color(248, 251, 253));
                }
            }
            var group = second.createGroup();
            group.setAnchor(new Rectangle2D.Double(438, 141, 225, 155));
            group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 300, 150));
            group.setRotation(-8);
            var groupedText = group.createTextBox();
            groupedText.setAnchor(new Rectangle2D.Double(12, 40, 275, 65));
            groupedText.setRotation(12);
            groupedText.setFillColor(new Color(233, 245, 233));
            groupedText.setVerticalAlignment(VerticalAlignment.MIDDLE);
            addRun(groupedText.setText("拡大回転グループ"), "拡大回転グループ", 22, true, new Color(27, 105, 64));
            textBox(second, "図表の見た目を維持した検索可能な PDF", 48, 328, 630, 36, 17);
            show.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static XSLFTextBox textBox(XSLFSlide slide, String text, double x, double y, double width, double height, double fontSize) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, width, height));
        addRun(box.setText(text), text, fontSize, false, new Color(24, 48, 78));
        return box;
    }

    private static void addRun(XSLFTextRun run, String text, double fontSize, boolean bold, Color color) {
        run.setText(text);
        run.setFontFamily(FONT);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontColor(color);
    }

    private static String pageText(PDDocument pdf, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        // POI measures centered/bottom text before painting. Do not let PDFBox hide accidental duplicate overlays.
        stripper.setSuppressDuplicateOverlappingText(false);
        return stripper.getText(pdf);
    }

    private static int occurrences(String text, String expected) {
        return (text.length() - text.replace(expected, "").length()) / expected.length();
    }

    private static void assertOriginWithin(List<TextPosition> positions, String character, Rectangle2D bounds) {
        TextPosition position = positions.stream().filter(value -> character.equals(value.getUnicode()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing positioned character: " + character));
        assertTrue(bounds.contains(position.getX(), position.getY()),
                () -> character + " at " + position.getX() + ", " + position.getY() + " must be inside " + bounds);
    }

    private static BufferedImage renderWithOriginalPoi(Slide<?, ?> slide) {
        BufferedImage image = new BufferedImage(PAGE.width * 2, PAGE.height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(Drawable.FONT_HANDLER, BundledPptFontManager.instance());
            graphics.scale(2d, 2d);
            slide.draw(graphics);
            return image;
        } finally {
            graphics.dispose();
        }
    }

    private static final class PositionStripper extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        @Override protected void processTextPosition(TextPosition position) {
            assertEquals(RenderingMode.NEITHER, getGraphicsState().getTextState().getRenderingMode(),
                    "The searchable layer must not paint or clip any glyphs");
            positions.add(position);
            super.processTextPosition(position);
        }
    }
}
