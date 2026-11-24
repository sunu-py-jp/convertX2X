package com.convertx2x.office2md.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.OfficeMarkdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFLine;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.sl.usermodel.LineDecoration.DecorationShape;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

class LegacyPowerPointMarkdownConverterTest {
    private final OfficeMarkdownService service = new OfficeMarkdownService(ConversionLimits.defaults());

    @Test void legacySlidesBecomePagedMarkdownWithTitleBodyAndListMarkers() throws Exception {
        byte[] input;
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFSlide first = show.createSlide();
            HSLFTextBox title = first.addTitle();
            title.setText("旧形式のタイトル");
            // POI's addTitle() stores the body placeholder id; PowerPoint stores the slide-title id for a title.
            title.getPlaceholderAtom().setPlaceholderId((byte) Placeholder.TITLE.nativeSlideId);
            HSLFTextBox body = first.createTextBox();
            body.setAnchor(new Rectangle(40, 120, 500, 200));
            body.setText("本文の一行目\n箇条書きの項目");
            body.getTextParagraphs().get(1).setBullet(true);
            body.appendText("太字の語", false).setBold(true);
            body.appendText("取消線の語", false).setStrikethrough(true);
            HSLFSlide second = show.createSlide();
            HSLFTextBox only = second.createTextBox();
            only.setAnchor(new Rectangle(40, 40, 400, 60));
            only.setText("二枚目の本文");
            input = bytes(show);
        }
        try (ConversionResult result = service.convert(input, "旧資料.ppt")) {
            assertEquals(2, result.sectionCount());
            String markdown = Files.readString(result.files().get("document.md"));
            assertTrue(markdown.startsWith("[page 1]\n\n# 旧形式のタイトル\n\n"), markdown);
            assertTrue(markdown.contains("本文の一行目"), markdown);
            assertTrue(markdown.contains("- 箇条書きの項目**太字の語**"), markdown);
            assertFalse(markdown.contains("取消線の語"), markdown);
            assertTrue(markdown.contains("[page 2]\n\n# スライド2\n\n二枚目の本文"), markdown);
            JsonNode report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
            assertEquals("ppt", report.path("source").path("format").asText());
            assertEquals("slide", report.path("sectionKind").asText());
            assertEquals("旧資料.ppt", report.path("source").path("filename").asText());
            assertEquals(0, report.path("warnings").size(), report.path("warnings").toString());
        }
    }

    @Test void legacyTableIsWrittenAsAMarkdownTable() throws Exception {
        byte[] input;
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFSlide slide = show.createSlide();
            HSLFTable table = slide.createTable(2, 2);
            table.setAnchor(new Rectangle(40, 60, 400, 80));
            table.getCell(0, 0).setText("項目"); table.getCell(0, 1).setText("値");
            table.getCell(1, 0).setText("件数"); table.getCell(1, 1).setText("42");
            input = bytes(show);
        }
        try (ConversionResult result = service.convert(input, "table.ppt")) {
            String markdown = Files.readString(result.files().get("document.md"));
            // The binary format stores no header-row mark, so all rows follow an empty Markdown header.
            assertTrue(markdown.contains("|  |  |\n| --- | --- |\n| 項目 | 値 |\n| 件数 | 42 |\n"), markdown);
            assertTrue(blocks(result, "table").size() == 1);
        }
    }

    @Test void legacyPictureIsSavedAndReferencedFromMarkdown() throws Exception {
        byte[] input;
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFSlide slide = show.createSlide();
            var picture = slide.createPicture(show.addPicture(png(Color.RED), PictureType.PNG));
            picture.setAnchor(new Rectangle(60, 60, 120, 60));
            input = bytes(show);
        }
        try (ConversionResult result = service.convert(input, "picture.ppt")) {
            String markdown = Files.readString(result.files().get("document.md"));
            List<String> images = result.files().keySet().stream().filter(name -> name.startsWith("images/")).toList();
            assertEquals(1, images.size(), result.files().keySet().toString());
            assertTrue(markdown.contains("](" + images.getFirst() + ")"), markdown);
        }
    }

    @Test void legacyLineBetweenShapesIsReportedAsAConnectionFromBoundaryContact() throws Exception {
        byte[] input;
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFSlide slide = show.createSlide();
            HSLFAutoShape from = slide.createAutoShape();
            from.setShapeType(ShapeType.RECT); from.setAnchor(new Rectangle(100, 100, 100, 50)); from.setText("受付");
            HSLFAutoShape to = slide.createAutoShape();
            to.setShapeType(ShapeType.RECT); to.setAnchor(new Rectangle(300, 100, 100, 50)); to.setText("審査");
            HSLFLine line = new HSLFLine();
            line.setAnchor(new Rectangle(200, 125, 100, 0));
            line.setLineTailDecoration(DecorationShape.TRIANGLE);
            slide.addShape(line);
            input = bytes(show);
        }
        try (ConversionResult result = service.convert(input, "flow.ppt")) {
            String markdown = Files.readString(result.files().get("document.md"));
            assertTrue(markdown.contains("図中の項目:"), markdown);
            assertTrue(markdown.contains("接続関係:"), markdown);
            assertTrue(markdown.matches("(?s).*shape-\\d+「受付」 → shape-\\d+「審査」.*"), markdown);
            JsonNode diagram = blocks(result, "diagram").getFirst();
            JsonNode edge = diagram.path("edges").get(0);
            assertEquals("resolved", edge.path("connectionResolutionStatus").asText(), edge.toString());
            assertEquals("start-to-end", edge.path("arrowheadDirectionAlongLine").asText());
            assertEquals("right-middle", edge.path("arrowheadPointsToward").asText());
            assertTrue(result.files().keySet().stream().anyMatch(name -> name.startsWith("images/diagram-")));
        }
    }

    @Test void hiddenLegacySlideIsNotConverted() throws Exception {
        byte[] input;
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFTextBox shown = show.createSlide().createTextBox();
            shown.setAnchor(new Rectangle(40, 40, 300, 60)); shown.setText("表示するスライド");
            HSLFSlide hidden = show.createSlide();
            hidden.setHidden(true);
            HSLFTextBox secret = hidden.createTextBox();
            secret.setAnchor(new Rectangle(40, 40, 300, 60)); secret.setText("非表示のスライド");
            input = bytes(show);
        }
        try (ConversionResult result = service.convert(input, "hidden.ppt")) {
            assertEquals(1, result.sectionCount());
            String markdown = Files.readString(result.files().get("document.md"));
            assertTrue(markdown.contains("表示するスライド"));
            assertFalse(markdown.contains("非表示のスライド"), markdown);
        }
    }

    @Test void pptExtensionAcceptsOnlyBinaryPowerPointAndPptxKeepsRejectingIt() throws Exception {
        byte[] modern;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            show.createSlide(); show.write(out); modern = out.toByteArray();
        }
        ConversionException renamed = assertThrows(ConversionException.class, () -> service.convert(modern, "renamed.ppt"));
        assertEquals(415, renamed.statusCode());
        assertEquals("UNSUPPORTED_DOCUMENT", renamed.code());

        byte[] workbook;
        try (HSSFWorkbook book = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.createSheet("sheet").createRow(0).createCell(0).setCellValue("not a presentation");
            book.write(out); workbook = out.toByteArray();
        }
        ConversionException other = assertThrows(ConversionException.class, () -> service.convert(workbook, "book.ppt"));
        assertEquals(415, other.statusCode());

        byte[] legacy;
        try (HSLFSlideShow show = new HSLFSlideShow()) {
            HSLFTextBox box = show.createSlide().createTextBox();
            box.setAnchor(new Rectangle(40, 40, 300, 60)); box.setText("旧形式");
            legacy = bytes(show);
        }
        // The PPTX reader is unchanged: binary content under a .pptx name is still refused.
        ConversionException mismatch = assertThrows(ConversionException.class, () -> service.convert(legacy, "legacy.pptx"));
        assertEquals(415, mismatch.statusCode());
        try (ConversionResult result = service.convert(legacy, "legacy.ppt")) {
            assertTrue(Files.readString(result.files().get("document.md")).contains("旧形式"));
        }
    }

    private static List<JsonNode> blocks(ConversionResult result, String type) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode block : new ObjectMapper().readTree(result.files().get("report.json").toFile()).path("blocks"))
            if (block.path("type").asText().equals(type)) found.add(block);
        return found;
    }

    private static byte[] bytes(HSLFSlideShow show) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        show.write(out);
        return out.toByteArray();
    }

    private static byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color); graphics.fillRect(0, 0, 40, 20); graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
