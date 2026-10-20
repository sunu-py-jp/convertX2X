package com.convertx2x.office2md.word;

import static com.convertx2x.office2md.word.WordCompositeImagesTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.ImageMode;
import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WordFixedTableCompositionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void fixedFloatingTableAndOverlaidPictureTextboxComposeAndRetainSemanticTable() throws Exception {
        String visuals = floating(picture(140, 70), 1, 100, 40, 140, 70)
                + floating(shape("2", "textBox", 80, 50, "確認箇所", true), 2, 200, 90, 80, 50);
        List<byte[]> calls = new ArrayList<>();
        OcrClient ocr = new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] bytes, String type) {
                calls.add(bytes); return new OcrResult("画面内の情報");
            }
        };
        try (var result = new WordMarkdownConverter(ConversionLimits.defaults()).convert(
                document(table(visuals)), "fixed.docx", ImageMode.OCR, ocr)) {
            JsonNode report = report(result), composite = blocks(report, "compositeImage").getFirst();
            String markdown = Files.readString(result.files().get("document.md"));
            assertEquals(1, blocks(report, "compositeImage").size());
            assertEquals(0, blocks(report, "embeddedImage").size());
            assertEquals(1, report.path("assets").size());
            assertEquals(1, composite.path("sourceImages").size());
            assertEquals(1, calls.size(), "Only the original embedded picture goes to OCR");
            assertTrue(composite.path("metadata").path("includesTable").asBoolean());
            assertTrue(markdown.contains("| 項目 | 値 |"));
            assertTrue(markdown.contains("| 番号 | 123 |"));
            assertTrue(markdown.contains("確認箇所"));
            assertTrue(markdown.indexOf("| 番号 |") < markdown.indexOf("images/image-"));
            assertFalse(markdown.contains("\uFDD1"));
            assertFalse(markdown.contains("<br><br><br><br>"));
            BufferedImage image = ImageIO.read(result.files().get(composite.path("path").asText()).toFile());
            assertTrue(image.getWidth() > 240);
            assertEquals(0, (image.getRGB(image.getWidth() - 5, 5) >>> 24) & 255,
                    "Expanded area above the protruding annotation remains transparent");
            image.flush();
        }
    }

    @Test void tableAndShapeWithoutPicturesProduceOneCompositeWithoutOcr() throws Exception {
        String visual = floating(shape("2", "textBox", 80, 40, "修正箇所", true), 2, 120, 40, 80, 40);
        try (var result = convert(table(visual))) {
            JsonNode composite = blocks(report(result), "compositeImage").getFirst();
            assertEquals(0, composite.path("sourceImages").size());
            assertTrue(composite.path("metadata").path("includesTable").asBoolean());
            assertEquals(1, report(result).path("assets").size());
            assertTrue(Files.readString(result.files().get("document.md")).contains("| 項目 | 値 |"));
        }
    }

    @Test void nonOverlappingVisualsAndAutomaticTablesAreKeptSeparate() throws Exception {
        String outside = floating(picture(100, 60), 1, 400, 300, 100, 60);
        String overlapping = floating(picture(100, 60), 1, 40, 40, 100, 60);
        for (String body : List.of(table(outside),
                table(overlapping).replace("w:hRule='exact'", "w:hRule='atLeast'"),
                table(overlapping).replace("w:tblpX='400'", "w:tblpXSpec='center'"),
                table(overlapping).replace("layoutInCell='0'", "layoutInCell='1'"),
                table(overlapping).replace("w:type='fixed'", "w:type='autofit'"))) {
            try (var result = convert(body)) {
                assertEquals(0, blocks(report(result), "compositeImage").size());
                assertEquals(1, blocks(report(result), "embeddedImage").size());
                assertTrue(Files.readString(result.files().get("document.md")).contains("| 番号 | 123 |"));
            }
        }
    }

    @Test void horizontalAndVerticalMergedCellsUseTheirSavedFullRectangles() throws Exception {
        String visual = floating(shape("3", "textBox", 50, 40, "合計", true), 3, 170, 70, 50, 40);
        String body = table(visual).replace(
                cell("項目" + "", "<w:shd w:fill='DDEEFF'/>", visual) + cell("値", "<w:shd w:fill='DDEEFF'/>", ""),
                cell("結合ヘッダ", "<w:gridSpan w:val='2'/><w:vMerge w:val='restart'/><w:shd w:fill='DDEEFF'/>", visual))
                .replace(cell("番号", "", "") + cell("123", "", ""),
                        cell("", "<w:gridSpan w:val='2'/><w:vMerge/>", ""));
        try (var result = convert(body)) {
            JsonNode composite = blocks(report(result), "compositeImage").getFirst();
            assertTrue(composite.path("metadata").path("width").asDouble() >= 180);
            assertTrue(composite.path("metadata").path("height").asDouble() >= 70);
            assertTrue(Files.readString(result.files().get("document.md")).contains("結合ヘッダ"));
        }
    }

    private static String table(String visuals) {
        return "<w:tbl><w:tblPr><w:tblpPr w:horzAnchor='page' w:vertAnchor='page' w:tblpX='400' w:tblpY='600'/>"
                + "<w:tblLayout w:type='fixed'/><w:tblBorders>"
                + "<w:top w:val='single'/><w:bottom w:val='single'/><w:left w:val='single'/><w:right w:val='single'/>"
                + "<w:insideH w:val='single'/><w:insideV w:val='single'/></w:tblBorders></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w='1800'/><w:gridCol w:w='1800'/></w:tblGrid>"
                + "<w:tr><w:trPr><w:tblHeader/><w:trHeight w:val='700' w:hRule='exact'/></w:trPr>"
                + cell("項目", "<w:shd w:fill='DDEEFF'/>", visuals) + cell("値", "<w:shd w:fill='DDEEFF'/>", "")
                + "</w:tr><w:tr><w:trPr><w:trHeight w:val='700' w:hRule='exact'/></w:trPr>"
                + cell("番号", "", "") + cell("123", "", "") + "</w:tr></w:tbl>";
    }
    private static String cell(String value, String props, String visuals) {
        return "<w:tc><w:tcPr>" + props + "</w:tcPr>" + paragraph(text(value) + visuals) + "</w:tc>";
    }
    private static String floating(String content, int id, int x, int y, int width, int height) {
        return anchor(content, id, x, y, width, height, id).replace("<wp:anchor ", "<wp:anchor layoutInCell='0' ");
    }
    private static ConversionResult convert(String body) throws Exception {
        return new WordMarkdownConverter(ConversionLimits.defaults()).convert(document(body), "fixed.docx");
    }
    private static JsonNode report(ConversionResult result) throws Exception {
        return JSON.readTree(Files.readString(result.files().get("report.json")));
    }
    private static List<JsonNode> blocks(JsonNode report, String type) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode block : report.path("blocks")) if (type.equals(block.path("type").asText())) result.add(block);
        return result;
    }
}
