package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PowerPointMarkdownConverterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void visibleSlidesTitlesReadingOrderNumberingAndBorderlessMergedTables() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            text(slide, "下段の本文", 20, 360, 300, 45);
            XSLFTextBox title = text(slide, "表示タイトル", 20, 5, 600, 40);
            title.setPlaceholder(Placeholder.TITLE);
            XSLFTextBox body = text(slide, "先頭の本文", 20, 60, 250, 35);
            XSLFTextRun struck = body.getTextParagraphs().getFirst().addNewTextRun();
            struck.setText("STRIKE_SECRET"); struck.setStrikethrough(true);
            XSLFTextBox hidden = text(slide, "HIDDEN_SHAPE_SECRET", 20, 110, 200, 30);
            ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
            XSLFTextBox footer = text(slide, "FOOTER_SECRET", 20, 460, 200, 30);
            footer.setPlaceholder(Placeholder.FOOTER);
            deck.getNotesSlide(slide).createTextBox().setText("NOTES_SECRET");
            slide.getSlideMaster().createTextBox().setText("MASTER_SECRET");

            XSLFTextBox list = slide.createTextBox(); list.setAnchor(new Rectangle2D.Double(340, 60, 280, 120));
            XSLFTextParagraph first = list.getTextParagraphs().getFirst();
            first.addNewTextRun().setText("元の番号"); first.setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 4);
            XSLFTextParagraph deleted = list.addNewTextParagraph();
            deleted.setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 5);
            XSLFTextRun gone = deleted.addNewTextRun(); gone.setText("DELETED_LIST_SECRET"); gone.setStrikethrough(true);
            XSLFTextParagraph third = list.addNewTextParagraph();
            third.setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 6); third.addNewTextRun().setText("後続の番号");
            XSLFTextParagraph bullet = list.addNewTextParagraph(); bullet.setBullet(true); bullet.addNewTextRun().setText("箇条書き");

            XSLFTable table = slide.createTable(3, 2); table.setAnchor(new Rectangle2D.Double(20, 210, 600, 100));
            table.getCTTable().getTblPr().setFirstRow(true);
            table.getCell(0, 0).setText("項目"); table.getCell(0, 1).setText("値");
            table.getCell(1, 0).setText("結合アンカー"); table.getCell(1, 1).setText("MERGED_CONTINUATION_SECRET"); table.mergeCells(1, 1, 0, 1);
            table.getCell(2, 0).setText("通常行"); table.getCell(2, 1).setText("123");
            for (var row : table.getRows()) for (var cell : row.getCells()) for (var edge : TableCell.BorderEdge.values()) cell.removeBorder(edge);
            deck.createSlide().setHidden(true);
            deck.getSlides().get(1).createTextBox().setText("HIDDEN_SLIDE_SECRET");
            text(deck.createSlide(), "タイトルなしの本文", 20, 50, 450, 40);
            byte[] input = bytes(deck);
            Path directory;
            try (ConversionResult result = convert(input)) {
                directory = result.directory();
                String md = md(result), report = Files.readString(result.files().get("report.json"));
                assertEquals(2, result.sectionCount());
                assertTrue(md.startsWith("[page 1]\n\n# 表示タイトル\n\n"));
                assertTrue(md.contains("[page 3]\n\n# スライド3\n"));
                assertEquals(2, md.lines().filter(line -> line.matches("\\[page \\d+]")).count());
                assertEquals(2, md.lines().filter(line -> line.startsWith("# ")).count());
                assertTrue(md.indexOf("先頭の本文") < md.indexOf("下段の本文"));
                assertTrue(md.contains("4\\. 元の番号")); assertTrue(md.contains("6\\. 後続の番号"));
                assertTrue(md.contains("- 箇条書き")); assertTrue(md.contains("| 項目 | 値 |"));
                assertTrue(md.contains("| 結合アンカー |  |"));
                for (String forbidden : List.of("STRIKE_SECRET", "HIDDEN_SHAPE_SECRET", "FOOTER_SECRET", "NOTES_SECRET", "MASTER_SECRET", "DELETED_LIST_SECRET", "MERGED_CONTINUATION_SECRET", "HIDDEN_SLIDE_SECRET")) {
                    assertFalse(md.contains(forbidden), forbidden); assertFalse(report.contains(forbidden), forbidden);
                }
                JsonNode json = JSON.readTree(report);
                assertEquals("slide", json.path("sectionKind").asText());
                assertEquals(ConversionWorkspace.sha256(input), json.path("source").path("sha256").asText());
                assertEquals(0, json.path("assets").size());
                assertTrue(report.contains("TABLE_MERGE_FLATTENED"));
            }
            assertFalse(Files.exists(directory), "The successful result owns and deletes its workspace");
        }
    }

    @Test void connectedAndOverlappingShapesKeepSearchableTextAndOneSlidePreview() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape rectangle = box(slide, "図内の処理", 40, 80, 180, 100);
            rectangle.setRotation(15);
            XSLFTextRun run = rectangle.getTextParagraphs().getFirst().getTextRuns().getFirst();
            run.createHyperlink().setAddress("https://example.invalid/details");
            text(slide, "重なる説明", 70, 90, 100, 35);
            XSLFAutoShape second = box(slide, "図内の結果", 360, 80, 180, 100);
            XSLFConnectorShape connector = slide.createConnector();
            connector.setAnchor(new Rectangle2D.Double(220, 130, 140, 0));
            var properties = ((org.openxmlformats.schemas.presentationml.x2006.main.CTConnector) connector.getXmlObject()).getNvCxnSpPr().getCNvCxnSpPr();
            properties.addNewStCxn().setId(rectangle.getShapeId()); properties.getStCxn().setIdx(0);
            properties.addNewEndCxn().setId(second.getShapeId()); properties.getEndCxn().setIdx(0);
            connector.setLineColor(Color.BLACK); connector.setLineWidth(2);
            try (ConversionResult result = convert(bytes(deck))) {
                String md = md(result);
                List<String> images = md.lines().filter(line -> line.startsWith("![")).toList();
                assertEquals(1, images.size(), "The complete slide provides one optional visual reference");
                JsonNode firstMetadata = imageMetadata(images.get(0));
                assertEquals("図", firstMetadata.path("type").asText());
                assertEquals("", firstMetadata.path("text").asText());
                assertImageMetadata(result, images);
                String searchable = withoutImages(md);
                assertTrue(searchable.contains("図内の処理")); assertTrue(searchable.contains("図内の結果"));
                assertTrue(searchable.contains("https://example.invalid/details"));
                assertTrue(md.lines().anyMatch(line -> line.equals("重なる説明")), "An ordinary overlapping textbox remains Markdown");
                assertTrue(md.indexOf("\n重なる説明\n") < md.indexOf(images.getFirst()), "The preview follows the searchable slide content");
                assertEquals("長方形", nodeByText(result, "図内の処理").path("type").asText());
                assertEquals(2, diagram(result).path("nodes").size());
                assertEquals(1, diagram(result).path("edges").size());
                JsonNode assets = report(result).path("assets"); assertEquals(1, assets.size());
                assertEquals("image/png", assets.get(0).path("contentType").asText());
                BufferedImage image = ImageIO.read(result.files().get(assets.get(0).path("path").asText()).toFile());
                try {
                    assertSlideCanvas(image);
                    assertEquals(Color.BLACK.getRGB(), image.getRGB(px(290), px(130)), "A zero-height horizontal connector must survive in the preview");
                } finally { image.flush(); }
            }
        }
    }

    @Test void groupLeavesKeepTransformedBoundsAndParentTransformInWholeSlidePreview() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFGroupShape group = slide.createGroup();
            group.setAnchor(new Rectangle2D.Double(100, 200, 400, 200));
            group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 200, 100)); group.setRotation(90);
            XSLFAutoShape rectangle = group.createAutoShape(); rectangle.setShapeType(ShapeType.RECT);
            rectangle.setAnchor(new Rectangle2D.Double(10, 20, 40, 20)); rectangle.setFillColor(Color.BLUE);
            XSLFTextBox groupedText = group.createTextBox(); groupedText.setAnchor(new Rectangle2D.Double(110, 20, 60, 30));
            groupedText.setText("図の文字").setFontSize(10.0);
            text(slide, "図の間にある本文", 20, 240, 300, 30);
            byte[] input = bytes(deck), untouched = input.clone();
            try (ConversionResult result = convert(input)) {
                String md = md(result);
                List<String> images = md.lines().filter(line -> line.startsWith("![")).toList();
                assertEquals(1, images.size());
                JsonNode firstNode = diagram(result).path("nodes").get(0);
                assertEquals("長方形", firstNode.path("type").asText());
                assertBounds(firstNode, 320, 120, 40, 80);
                assertImageMetadata(result, images);
                assertEquals("テキストボックス", nodeByText(result, "図の文字").path("type").asText());
                assertTrue(withoutImages(md).contains("図の文字"));
                assertTrue(md.indexOf("\n図の間にある本文\n") < md.indexOf(images.getFirst()));
                String imagePath = report(result).path("assets").get(0).path("path").asText();
                BufferedImage image = ImageIO.read(result.files().get(imagePath).toFile());
                try {
                    assertSlideCanvas(image);
                    assertEquals(Color.BLUE.getRGB(), image.getRGB(px(340), px(160)), "The transformed leaf must retain its slide position");
                    assertNotEquals(Color.BLUE.getRGB(), image.getRGB(px(130), px(250)), "The untransformed leaf position must stay empty");
                } finally { image.flush(); }
            }
            assertArrayEquals(untouched, input, "Flattening groups must not mutate the saved source bytes");
        }
    }

    @Test void unrotatedPictureStillRendersParentGroupRotationAndScale() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFGroupShape group = deck.createSlide().createGroup();
            group.setAnchor(new Rectangle2D.Double(200, 100, 240, 160));
            group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 120, 80)); group.setRotation(90);
            byte[] original = png();
            XSLFPictureShape picture = group.createPicture(deck.addPicture(original, PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(10, 10, 60, 20));
            assertEquals(0, picture.getRotation());
            try (ConversionResult result = convert(bytes(deck))) {
                String md = md(result);
                List<String> images = imageLines(md);
                JsonNode node = diagram(result).path("nodes").get(0);
                assertEquals("画像", node.path("type").asText());
                assertBounds(node, 340, 80, 40, 120);
                assertImageMetadata(result, images);
                String path = report(result).path("assets").get(0).path("path").asText();
                assertTrue(path.startsWith("images/diagram-"), "A parent's transform must prevent unmodified image extraction");
                BufferedImage image = ImageIO.read(result.files().get(path).toFile());
                try {
                    assertSlideCanvas(image);
                    assertEquals(Color.BLUE.getRGB(), image.getRGB(px(360), px(140)));
                    assertNotEquals(Color.BLUE.getRGB(), image.getRGB(px(250), px(140)));
                } finally { image.flush(); }
                assertFalse(Arrays.equals(original, Files.readAllBytes(result.files().get(path))));
            }
        }
    }

    @Test void tableHeaderRequiresExplicitFirstRowTrueAndAllOtherRowsRemainData() throws Exception {
        for (Boolean firstRow : new Boolean[]{null, false, true}) {
            try (XMLSlideShow deck = deck()) {
                XSLFTable table = deck.createSlide().createTable(2, 2);
                table.setAnchor(new Rectangle2D.Double(20, 20, 400, 120));
                var properties = table.getCTTable().getTblPr();
                if (firstRow == null) { if (properties.isSetFirstRow()) properties.unsetFirstRow(); }
                else properties.setFirstRow(firstRow);
                table.getCell(0, 0).setText("先頭の値"); table.getCell(0, 1).setText("先頭の別値");
                table.getCell(1, 0).setText("二行目の値"); table.getCell(1, 1).setText("二行目の別値");
                try (ConversionResult result = convert(bytes(deck))) {
                    List<String> rows = md(result).lines().filter(line -> line.startsWith("|")).toList();
                    if (Boolean.TRUE.equals(firstRow)) {
                        assertEquals(List.of("| 先頭の値 | 先頭の別値 |", "| --- | --- |", "| 二行目の値 | 二行目の別値 |"), rows);
                    } else {
                        assertEquals(List.of("|  |  |", "| --- | --- |", "| 先頭の値 | 先頭の別値 |", "| 二行目の値 | 二行目の別値 |"), rows,
                                "An absent/false firstRow must not promote the first data row into a header");
                    }
                }
            }
        }
    }

    @Test void nestedUniformGroupRotationIsRenderedWithoutRotationMetadata() throws Exception {
        try (XMLSlideShow deck = transformedGroupDeck(false, false); ConversionResult result = convert(bytes(deck))) {
            List<String> images = imageLines(md(result));
            JsonNode node = nodeByText(result, "図内テキスト");
            assertEquals("長方形", node.path("type").asText());
            assertFalse(node.has("rotation"));
            assertTrue(withoutImages(md(result)).contains("図内テキスト"));
            assertImageMetadata(result, images);
            assertEquals(1, report(result).path("assets").size());
        }
    }

    @Test void reflectedOrNonuniformGroupsUseTheSameMetadataContract() throws Exception {
        for (boolean reflection : new boolean[]{false, true}) {
            try (XMLSlideShow deck = transformedGroupDeck(reflection, !reflection); ConversionResult result = convert(bytes(deck))) {
                List<String> images = imageLines(md(result));
                assertEquals("図内テキスト", nodeByText(result, "図内テキスト").path("text").asText());
                assertImageMetadata(result, images);
                assertEquals(1, report(result).path("assets").size());
            }
        }
    }

    @Test void hiddenAndStruckGroupTextIsAbsentFromBothPngAndAlt() throws Exception {
        byte[] sanitized, withDeleted;
        try (XMLSlideShow a = groupDeck(false); XMLSlideShow b = groupDeck(true)) { sanitized = bytes(a); withDeleted = bytes(b); }
        try (ConversionResult clean = convert(sanitized); ConversionResult removed = convert(withDeleted)) {
            assertEquals(md(clean), md(removed));
            assertFalse(md(removed).contains("SECRET"));
            assertEquals(1, report(removed).path("assets").size());
            for (JsonNode asset : report(removed).path("assets")) {
                String path = asset.path("path").asText();
                assertArrayEquals(Files.readAllBytes(clean.files().get(path)), Files.readAllBytes(removed.files().get(path)), "Deleted glyphs must also disappear from the complete slide preview");
            }
            assertTrue(md(removed).contains("長方形")); assertTrue(md(removed).contains("楕円"));
        }
    }

    @Test void wholeSlidePreviewAlsoRemovesHiddenAndStruckOrdinaryTextAndTableCells() throws Exception {
        try (XMLSlideShow a = previewSanitizationDeck(false); XMLSlideShow b = previewSanitizationDeck(true);
             ConversionResult clean = convert(bytes(a)); ConversionResult removed = convert(bytes(b))) {
            assertEquals(md(clean), md(removed));
            assertFalse(report(removed).toString().contains("SECRET"));
            String path = diagram(removed).path("path").asText();
            assertArrayEquals(Files.readAllBytes(clean.files().get(path)), Files.readAllBytes(removed.files().get(path)),
                    "The whole-slide preview must retain the same omission policy as text extraction");
        }
    }

    @Test void slideAndInheritedSolidBackgroundsKeepWhiteTextReadable() throws Exception {
        Color dark = new Color(18, 36, 64);
        for (int source = 0; source < 3; source++) {
            try (XMLSlideShow deck = deck()) {
                XSLFSlide slide = deck.createSlide();
                var common = switch (source) {
                    case 0 -> slide.getXmlObject().getCSld();
                    case 1 -> slide.getSlideLayout().getXmlObject().getCSld();
                    default -> slide.getSlideMaster().getXmlObject().getCSld();
                };
                if (common.isSetBg()) common.unsetBg();
                common.addNewBg().addNewBgPr().addNewSolidFill().addNewSrgbClr().setVal(new byte[]{18, 36, 64});
                XSLFAutoShape shape = box(slide, "白い文字", 40, 80, 200, 80);
                shape.setFillColor(null); shape.setLineColor(null);
                shape.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontColor(Color.WHITE);
                try (ConversionResult result = convert(bytes(deck))) {
                    BufferedImage image = ImageIO.read(result.files().get(diagram(result).path("path").asText()).toFile());
                    try {
                        assertEquals(dark.getRGB(), image.getRGB(px(600), px(400)), "Slide/layout/master solid background must be retained");
                        long whiteGlyphs = 0;
                        for (int y = px(80); y < px(160); y++) for (int x = px(40); x < px(240); x++)
                            if (image.getRGB(x, y) == Color.WHITE.getRGB()) whiteGlyphs++;
                        assertTrue(whiteGlyphs > 100, "White text must remain visible over the dark background");
                    } finally { image.flush(); }
                    assertFalse(report(result).toString().contains("UNSUPPORTED_SLIDE_BACKGROUND"));
                }
            }
        }
    }

    @Test void solidThemeBackgroundReferenceIsResolvedWithoutRenderingMasterShapes() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            var reference = slide.getXmlObject().getCSld().addNewBg().addNewBgRef();
            reference.setIdx(1001);
            reference.addNewSchemeClr().setVal(org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal.DK_1);
            box(slide, "確認", 40, 80, 180, 80);
            XSLFAutoShape masterShape = slide.getSlideMaster().createAutoShape();
            masterShape.setAnchor(new Rectangle2D.Double(500, 350, 180, 120)); masterShape.setFillColor(Color.RED);
            try (ConversionResult result = convert(bytes(deck))) {
                BufferedImage image = ImageIO.read(result.files().get(diagram(result).path("path").asText()).toFile());
                try {
                    assertEquals(Color.BLACK.getRGB(), image.getRGB(px(600), px(400)), "Resolve the theme's solid color without painting master shapes");
                } finally { image.flush(); }
                assertFalse(report(result).toString().contains("UNSUPPORTED_SLIDE_BACKGROUND"));
            }
        }
    }

    @Test void directAndThemePictureBackgroundsUseWhiteWithoutExternalRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.start();
        try {
            for (boolean theme : new boolean[]{false, true}) {
                try (XMLSlideShow deck = deck()) {
                    XSLFSlide slide = deck.createSlide(); box(slide, "安全な本文", 40, 80, 200, 80);
                    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/background.png";
                    var part = theme ? slide.getTheme().getPackagePart() : slide.getPackagePart();
                    var relationship = part.addRelationship(java.net.URI.create(url), TargetMode.EXTERNAL,
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image");
                    var background = slide.getXmlObject().getCSld().addNewBg();
                    if (theme) {
                        var reference = background.addNewBgRef(); reference.setIdx(1001);
                        reference.addNewSchemeClr().setVal(org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal.DK_1);
                        var styles = org.openxmlformats.schemas.drawingml.x2006.main.CTBackgroundFillStyleList.Factory.newInstance();
                        styles.addNewBlipFill().addNewBlip().setLink(relationship.getId());
                        slide.getTheme().getXmlObject().getThemeElements().getFmtScheme().setBgFillStyleLst(styles);
                    } else background.addNewBgPr().addNewBlipFill().addNewBlip().setLink(relationship.getId());
                    try (ConversionResult result = convert(bytes(deck))) {
                        BufferedImage image = ImageIO.read(result.files().get(diagram(result).path("path").asText()).toFile());
                        try { assertEquals(Color.WHITE.getRGB(), image.getRGB(px(600), px(400))); }
                        finally { image.flush(); }
                        assertTrue(report(result).toString().contains("UNSUPPORTED_SLIDE_BACKGROUND"));
                        assertFalse(report(result).toString().contains(url)); assertFalse(md(result).contains(url));
                    }
                }
            }
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void embeddedPicturesAreRenderedAndExternalPicturesNeverFetched() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
        server.start();
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide(); byte[] png = png();
            XSLFPictureShape embedded = slide.createPicture(deck.addPicture(png, PictureData.PictureType.PNG));
            embedded.setAnchor(new Rectangle2D.Double(20, 20, 80, 60));
            ((CTPicture) embedded.getXmlObject()).getNvPicPr().getCNvPr().setDescr("保存済みロゴ");
            XSLFPictureShape external = slide.createPicture(deck.addPicture(png, PictureData.PictureType.PNG));
            external.setAnchor(new Rectangle2D.Double(200, 20, 80, 60));
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/must-not-fetch.png";
            var relationship = slide.getPackagePart().addRelationship(java.net.URI.create(url), TargetMode.EXTERNAL,
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image");
            var blip = ((CTPicture) external.getXmlObject()).getBlipFill().getBlip();
            blip.unsetEmbed(); blip.setLink(relationship.getId());
            try (ConversionResult result = convert(bytes(deck))) {
                JsonNode json = report(result); String md = md(result);
                assertEquals(1, json.path("assets").size());
                BufferedImage preview = ImageIO.read(result.files().get(json.path("assets").get(0).path("path").asText()).toFile());
                try {
                    assertSlideCanvas(preview);
                    assertEquals(Color.BLUE.getRGB(), preview.getRGB(px(50), px(50)));
                    assertNotEquals(Color.BLUE.getRGB(), preview.getRGB(px(230), px(50)), "Skipped external pictures must not appear in the preview");
                } finally { preview.flush(); }
                assertTrue(md.contains("保存済みロゴ")); assertTrue(md.contains("外部参照画像"));
                assertFalse(md.contains(url)); assertTrue(json.toString().contains("EXTERNAL_IMAGE_SKIPPED"));
            }
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void renderingAndTextLimitsAreEnforcedBeforeLargeOutput() throws Exception {
        ConversionLimits defaults = ConversionLimits.defaults();
        try (XMLSlideShow deck = deck()) {
            deck.setPageSize(new Dimension(100_000, 100_000));
            XSLFSlide slide = deck.createSlide(); box(slide, "大きな図", 0, 0, 100_000, 100_000);
            ConversionException failure = assertThrows(ConversionException.class, () -> convert(bytes(deck)));
            assertEquals("IMAGE_PIXELS_LIMIT", failure.code()); assertEquals(413, failure.statusCode());
        }
        try (XMLSlideShow deck = deck()) {
            text(deck.createSlide(), "文字数の上限を検証するための本文", 20, 20, 500, 40);
            ConversionLimits tiny = new ConversionLimits(defaults.maxInputBytes(), 50, 5, defaults.maxTableCells(), defaults.maxMarkdownBytes(), defaults.maxImages(), defaults.maxImageBytes(), defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
            assertEquals("READ_ITEMS_LIMIT", assertThrows(ConversionException.class, () -> new PowerPointMarkdownConverter(tiny).convert(bytes(deck), "sample.pptx")).code());
        }
        try (XMLSlideShow deck = deck()) {
            deck.createSlide(); deck.createSlide();
            ConversionLimits tiny = new ConversionLimits(defaults.maxInputBytes(), 1, defaults.maxReadItems(), defaults.maxTableCells(), defaults.maxMarkdownBytes(), defaults.maxImages(), defaults.maxImageBytes(), defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
            assertEquals("SECTION_LIMIT", assertThrows(ConversionException.class, () -> new PowerPointMarkdownConverter(tiny).convert(bytes(deck), "sample.pptx")).code());
        }
    }

    @Test void defaultLimitsAllowMoreSlidesShapesPicturesAndTextThanTheFormerCaps() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide first = deck.createSlide();
            for (int i = 1; i <= 1001; i++) text(first, "Text box " + i, 20, 20, 300, 30);
            XSLFPictureData picture = deck.addPicture(png(), PictureData.PictureType.PNG);
            for (int i = 0; i < 201; i++) first.createPicture(picture).setAnchor(new Rectangle2D.Double(20, 80, 40, 30));
            // Varied text keeps the fixture below POI's compression-ratio protection.
            var random = new Random(42);
            StringBuilder largeText = new StringBuilder();
            for (int i = 0; i < 201_000; i++) largeText.append((char) ('a' + random.nextInt(26)));
            for (int i = 2; i <= 51; i++) text(deck.createSlide(), "Slide " + i, 20, 20, 300, 30);
            text(deck.getSlides().getLast(), largeText.toString(), 20, 80, 300, 30);
            byte[] input = bytes(deck);
            Path fixture = Path.of("target/fixtures/many-slides.pptx");
            Files.createDirectories(fixture.getParent());
            Files.write(fixture, input);
            try (ConversionResult result = convert(input)) {
                assertEquals(51, result.sectionCount());
                String markdown = md(result);
                assertTrue(markdown.contains("Text box 1001"));
                assertTrue(markdown.contains("Slide 51"));
                assertTrue(markdown.contains(largeText));
                assertEquals(1, markdown.lines().filter(line -> line.startsWith("![")).count());
                assertEquals(201, diagram(result).path("nodes").size(), "Every graphical placement stays in the graph even when picture bytes repeat");
                assertEquals(1, report(result).path("assets").size(), "The 201 picture placements share one slide preview");
            }
        }
    }

    @Test void rotatedAndCroppedPicturesUseTheirSavedAppearance() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFPictureShape picture = slide.createPicture(deck.addPicture(png(), PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(80, 80, 160, 120)); picture.setRotation(90);
            ((CTPicture) picture.getXmlObject()).getBlipFill().addNewSrcRect().setL(25000);
            try (ConversionResult result = convert(bytes(deck))) {
                JsonNode asset = report(result).path("assets").get(0);
                assertTrue(asset.path("path").asText().startsWith("images/diagram-"));
                assertImageMetadata(result, imageLines(md(result)));
                BufferedImage output = ImageIO.read(result.files().get(asset.path("path").asText()).toFile());
                try {
                    assertSlideCanvas(output);
                    JsonNode node = diagram(result).path("nodes").get(0);
                    assertBounds(node, 100, 60, 120, 160);
                    assertEquals(Color.BLUE.getRGB(), output.getRGB(px(160), px(65)));
                    assertNotEquals(Color.BLUE.getRGB(), output.getRGB(px(85), px(140)), "Rotated pixels must leave the unrotated footprint");
                } finally { output.flush(); }
            }
        }
    }

    @Test void clockwisePictureRotationIsBakedIntoPixelsWithoutRotationText() throws Exception {
        try (XMLSlideShow deck = deck()) {
            byte[] original = twoColorPng();
            XSLFPictureShape picture = deck.createSlide().createPicture(deck.addPicture(original, PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(80, 80, 160, 80));
            picture.setRotation(90);
            try (ConversionResult result = convert(bytes(deck))) {
                List<String> images = imageLines(md(result));
                assertImageMetadata(result, images);
                assertBounds(diagram(result).path("nodes").get(0), 120, 40, 80, 160);
                String path = report(result).path("assets").get(0).path("path").asText();
                assertFalse(Arrays.equals(original, Files.readAllBytes(result.files().get(path))));
                BufferedImage output = ImageIO.read(result.files().get(path).toFile());
                try {
                    assertSlideCanvas(output);
                    assertEquals(Color.RED.getRGB(), output.getRGB(px(160), px(80)),
                            "The red left half must become the top half after clockwise rotation");
                    assertEquals(Color.BLUE.getRGB(), output.getRGB(px(160), px(160)),
                            "The blue right half must become the bottom half, not a reflected image");
                } finally { output.flush(); }
            }
        }
    }

    @Test void reportNodesPreserveSpecialCharactersAndNewlinesAndMarkdownRemainsSearchable() throws Exception {
        String description = "引用\"日本語\" [表示](https://example.invalid/a)\n2行目: C:\\temp\\a.png & <script> *強調* _下線_ `code` {json}";
        // Picture descriptions are XML attributes; use a single line to avoid XML whitespace normalization.
        String pictureDescription = description.replace('\n', ' ');
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape shape = box(slide, description, 40.123456, 80.987654, 280.567891, 120.123456);
            XSLFTextRun removed = shape.getTextParagraphs().getLast().addNewTextRun();
            removed.setText("STRIKE_SECRET"); removed.setStrikethrough(true);
            XSLFPictureShape picture = slide.createPicture(deck.addPicture(png(), PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(40, 300, 80, 60));
            ((CTPicture) picture.getXmlObject()).getNvPicPr().getCNvPr().setDescr(pictureDescription);
            try (ConversionResult result = convert(bytes(deck))) {
                List<String> images = imageLines(md(result));
                assertEquals(1, images.size());
                assertImageMetadata(result, images);
                for (JsonNode metadata : diagram(result).path("nodes")) {
                    String expectedText = metadata.path("type").asText().equals("画像") ? pictureDescription : description;
                    assertEquals(expectedText, metadata.path("text").asText(), "JSON must round-trip text without Markdown escaping artifacts");
                    assertNodeFields(metadata);
                }
                String searchable = withoutImages(md(result));
                assertTrue(searchable.contains("日本語")); assertTrue(searchable.contains("2行目"));
                assertFalse(searchable.contains("STRIKE_SECRET")); assertFalse(searchable.contains("<script>"));
                assertBounds(nodeByText(result, description), 40.123, 80.988, 280.568, 120.123);
                assertFalse(report(result).toString().contains("STRIKE_SECRET"));
            }
        }
    }

    @Test void chartIsAPlaceholderAndIsNeverRenderedOrRecalculated() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            var chart = deck.createChart();
            // The chart has no plot data or workbook: conversion must not ask a chart renderer to resolve them.
            // Unlike shape.setAnchor, addChart takes its rectangle in EMUs.
            slide.addChart(chart, new Rectangle2D.Double(50 * 12700, 50 * 12700, 400 * 12700, 250 * 12700));
            try (ConversionResult result = convert(bytes(deck))) {
                assertTrue(md(result).contains("グラフ：変換対象外"));
                assertTrue(md(result).contains("基準=スライド左上、外接矩形 X=50pt、Y=50pt、幅=400pt、高さ=250pt"), md(result));
                assertTrue(report(result).toString().contains("UNSUPPORTED_DRAWING"));
                assertEquals(0, report(result).path("assets").size());
            }
        }
    }

    @Test void storedEndpointsAndArrowheadsProduceDirectionalSearchableConnections() throws Exception {
        var none = LineDecoration.DecorationShape.NONE;
        var triangle = LineDecoration.DecorationShape.TRIANGLE;
        var combinations = List.of(
                List.of(none, triangle), List.of(triangle, none),
                List.of(triangle, triangle), List.of(none, none));
        var directions = List.of("start-to-end", "end-to-start", "bidirectional", "undirected");
        for (int i = 0; i < combinations.size(); i++) {
            try (XMLSlideShow deck = deck()) {
                XSLFSlide slide = deck.createSlide();
                XSLFAutoShape start = box(slide, "申請", 40, 80, 180, 80);
                start.getTextParagraphs().getFirst().getTextRuns().getFirst().setBold(true);
                XSLFAutoShape end = box(slide, "確認", 360, 80, 180, 80);
                XSLFConnectorShape connector = connected(slide, start, end);
                connector.setLineHeadDecoration(combinations.get(i).get(0));
                connector.setLineTailDecoration(combinations.get(i).get(1));
                try (ConversionResult result = convert(bytes(deck))) {
                    JsonNode graph = diagram(result), edge = graph.path("edges").get(0);
                    assertEquals("shape-" + connector.getShapeId(), edge.path("id").asText());
                    assertEquals("shape-" + start.getShapeId(), edge.path("startId").asText());
                    assertEquals("shape-" + end.getShapeId(), edge.path("endId").asText());
                    assertEquals("resolved", edge.path("status").asText());
                    assertEquals(directions.get(i), edge.path("direction").asText());
                    assertEquals(combinations.get(i).get(0) == none ? "none" : "triangle", edge.path("startArrow").asText());
                    assertEquals(combinations.get(i).get(1) == none ? "none" : "triangle", edge.path("endArrow").asText());
                    String body = withoutImages(md(result));
                    assertTrue(body.contains("図中の項目：")); assertTrue(body.contains("接続関係"));
                    assertTrue(body.contains("**申請**"), "Inline bold must remain available to a normal Markdown reader");
                    assertTrue(body.contains("確認"));
                    assertTrue(body.contains("shape-" + start.getShapeId()));
                    assertTrue(body.contains("shape-" + end.getShapeId()));
                    assertEquals(1, imageLines(md(result)).size());
                }
            }
        }
    }

    @Test void nearbyCaptionsAndUnattachedConnectorsNeverInventRelationships() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide, "確認", 40, 80, 180, 80);
            XSLFAutoShape end = box(slide, "承認済み", 360, 80, 180, 80);
            connected(slide, start, end).setLineTailDecoration(LineDecoration.DecorationShape.TRIANGLE);
            text(slide, "承認なら進む", 220, 80, 140, 35);
            XSLFConnectorShape unattached = slide.createConnector();
            unattached.setAnchor(new Rectangle2D.Double(220, 170, 140, 0));
            unattached.setLineTailDecoration(LineDecoration.DecorationShape.TRIANGLE);
            try (ConversionResult result = convert(bytes(deck))) {
                JsonNode graph = diagram(result);
                assertEquals(2, graph.path("nodes").size(), "An adjacent caption remains ordinary text, not a fabricated graph node");
                assertEquals(2, graph.path("edges").size());
                JsonNode unknown = edgeById(result, "shape-" + unattached.getShapeId());
                assertEquals("unresolved", unknown.path("status").asText());
                assertTrue(unknown.path("startId").isMissingNode()); assertTrue(unknown.path("endId").isMissingNode());
                assertFalse(unknown.path("reason").asText().isBlank());
                for (JsonNode edge : graph.path("edges")) {
                    assertFalse(edge.has("label"));
                    assertFalse(edge.toString().contains("承認なら進む"), "Proximity alone is never evidence for a branch label");
                }
                String body = withoutImages(md(result));
                assertTrue(body.lines().anyMatch(line -> line.equals("承認なら進む")));
                assertTrue(body.contains("shape-" + unattached.getShapeId()));
                assertTrue(body.contains("不明") || body.contains("未確定"));
            }
        }
    }

    @Test void connectorTargetsIncludeTextboxesAndTablesWithoutLosingNativeMarkdown() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFTextBox title = text(slide, "判断の根拠", 20, 10, 600, 40); title.setPlaceholder(Placeholder.TITLE);
            XSLFTextBox body = text(slide, "担当者が確認", 20, 90, 200, 40);
            XSLFTable table = slide.createTable(2, 2); table.setAnchor(new Rectangle2D.Double(340, 180, 300, 100));
            table.getCTTable().getTblPr().setFirstRow(true);
            table.getCell(0, 0).setText("項目"); table.getCell(0, 1).setText("判定");
            table.getCell(1, 0).setText("金額"); table.getCell(1, 1).setText("100万円");
            connected(slide, title, body); connected(slide, body, table);
            try (ConversionResult result = convert(bytes(deck))) {
                String bodyMd = withoutImages(md(result));
                assertTrue(bodyMd.contains("# 判断の根拠"));
                assertTrue(bodyMd.contains("担当者が確認"), "A connector-target textbox must remain readable Markdown text");
                assertTrue(bodyMd.contains("| 金額 | 100万円 |"));
                JsonNode graph = diagram(result);
                assertEquals(3, graph.path("nodes").size());
                for (JsonNode edge : graph.path("edges")) assertEquals("resolved", edge.path("status").asText());
                assertEquals("shape-" + title.getShapeId(), nodeByText(result, "判断の根拠").path("id").asText());
                assertEquals("shape-" + body.getShapeId(), nodeByText(result, "担当者が確認").path("id").asText());
                assertFalse(nodeById(result, "shape-" + table.getShapeId()).path("text").asText().isBlank(), "The target table must supply text for relationship labels");
            }
        }
    }

    @Test void duplicateCaptionsKeepDistinctIdsAndHiddenEndpointsStayUnresolved() throws Exception {
        try (XMLSlideShow deck = deck()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape first = box(slide, "確認", 40, 80, 180, 80);
            XSLFAutoShape second = box(slide, "確認", 360, 80, 180, 80);
            XSLFAutoShape hidden = box(slide, "HIDDEN_SECRET", 360, 260, 180, 80);
            ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
            connected(slide, first, second);
            XSLFConnectorShape toHidden = connected(slide, second, hidden);
            try (ConversionResult result = convert(bytes(deck))) {
                JsonNode graph = diagram(result);
                assertEquals(2, graph.path("nodes").size());
                assertNotEquals(graph.path("nodes").get(0).path("id"), graph.path("nodes").get(1).path("id"));
                String body = withoutImages(md(result));
                assertTrue(body.contains("shape-" + first.getShapeId())); assertTrue(body.contains("shape-" + second.getShapeId()));
                assertEquals("unresolved", edgeById(result, "shape-" + toHidden.getShapeId()).path("status").asText());
                assertFalse(body.contains("HIDDEN_SECRET")); assertFalse(report(result).toString().contains("HIDDEN_SECRET"));
            }
        }
    }

    @Test void diagramTextStillHonorsMarkdownAndImageOutputLimits() throws Exception {
        ConversionLimits defaults = ConversionLimits.defaults();
        try (XMLSlideShow deck = deck()) {
            box(deck.createSlide(), "この文字列も通常のMarkdownとして上限に含める", 40, 80, 280, 120);
            ConversionLimits textLimit = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(), defaults.maxReadItems(), defaults.maxTableCells(), 100,
                    defaults.maxImages(), defaults.maxImageBytes(), defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
            assertEquals("MARKDOWN_BYTES_LIMIT", assertThrows(ConversionException.class,
                    () -> new PowerPointMarkdownConverter(textLimit).convert(bytes(deck), "sample.pptx")).code());
            ConversionLimits pixelLimit = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(), defaults.maxReadItems(), defaults.maxTableCells(), defaults.maxMarkdownBytes(),
                    defaults.maxImages(), defaults.maxImageBytes(), defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), 500_000);
            assertEquals("IMAGE_PIXELS_LIMIT", assertThrows(ConversionException.class,
                    () -> new PowerPointMarkdownConverter(pixelLimit).convert(bytes(deck), "sample.pptx")).code());
        }
    }

    @Test void misleadingPptxFilenameDoesNotAcceptSpreadsheetContents() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet().createRow(0).createCell(0).setCellValue("not a presentation"); workbook.write(out);
            ConversionException failure = assertThrows(ConversionException.class, () -> convert(out.toByteArray()));
            assertEquals(415, failure.statusCode()); assertEquals("UNSUPPORTED_DOCUMENT", failure.code());
        }
    }

    @Test void standardPptxMainPartDoesNotHideAnAdditionalVbaProject() throws Exception {
        try (XMLSlideShow deck = deck()) {
            text(deck.createSlide(), "通常の本文", 20, 20, 300, 40);
            var name = org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/ppt/vbaProject.bin");
            var macro = deck.getPackage().createPart(name, "application/vnd.ms-office.vbaProject");
            try (var output = macro.getOutputStream()) { output.write(new byte[]{0, 1, 2, 3}); }
            ConversionException failure = assertThrows(ConversionException.class, () -> convert(bytes(deck)));
            assertEquals(415, failure.statusCode()); assertEquals("UNSUPPORTED_DOCUMENT", failure.code());
        }
    }

    @Test void encryptedOoxmlEnvelopeIsDetectedWithoutAttemptingDecryption() throws Exception {
        try (var filesystem = new org.apache.poi.poifs.filesystem.POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            filesystem.createDocument(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}), "EncryptedPackage");
            filesystem.writeFilesystem(out);
            ConversionException failure = assertThrows(ConversionException.class, () -> convert(out.toByteArray()));
            assertEquals("ENCRYPTED_DOCUMENT", failure.code());
        }
    }

    private static XMLSlideShow groupDeck(boolean deleted) {
        XMLSlideShow deck = deck(); XSLFSlide slide = deck.createSlide();
        XSLFGroupShape group = slide.createGroup();
        group.setAnchor(new Rectangle2D.Double(40, 60, 400, 200)); group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 400, 200));
        XSLFAutoShape first = group.createAutoShape(); first.setShapeType(ShapeType.RECT);
        first.setAnchor(new Rectangle2D.Double(0, 0, 180, 120)); first.setFillColor(new Color(235, 240, 255)); first.setText("公開テキスト").setFontSize(20.0);
        XSLFAutoShape second = group.createAutoShape(); second.setShapeType(ShapeType.ELLIPSE);
        second.setAnchor(new Rectangle2D.Double(220, 0, 160, 120)); second.setText("結果").setFontSize(20.0);
        if (deleted) {
            XSLFTextRun run = first.getTextParagraphs().getFirst().addNewTextRun(); run.setText("STRIKE_SECRET"); run.setStrikethrough(true);
            XSLFTextBox hidden = group.createTextBox(); hidden.setText("HIDDEN_SECRET"); hidden.setAnchor(new Rectangle2D.Double(0, 130, 300, 50));
            ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
        }
        return deck;
    }
    private static XMLSlideShow previewSanitizationDeck(boolean deleted) {
        XMLSlideShow deck = deck(); XSLFSlide slide = deck.createSlide();
        box(slide, "処理", 40, 380, 180, 80);
        XSLFTextBox title = text(slide, "公開タイトル", 20, 20, 600, 40); title.setPlaceholder(Placeholder.TITLE);
        XSLFTextBox body = text(slide, "公開本文", 20, 90, 600, 40);
        XSLFTable table = slide.createTable(2, 2); table.setAnchor(new Rectangle2D.Double(40, 180, 400, 100));
        table.getCTTable().getTblPr().setFirstRow(true);
        table.getCell(0, 0).setText("項目"); table.getCell(0, 1).setText("値");
        table.getCell(1, 0).setText("公開セル"); table.mergeCells(1, 1, 0, 1);
        if (deleted) {
            for (XSLFTextShape shape : List.of(title, body, table.getCell(0, 1))) {
                XSLFTextRun removed = shape.getTextParagraphs().getFirst().addNewTextRun();
                removed.setText("STRIKE_SECRET"); removed.setStrikethrough(true);
            }
            table.getCell(1, 1).setText("MERGED_CONTINUATION_SECRET");
            XSLFTextBox hidden = text(slide, "HIDDEN_SECRET", 20, 130, 500, 40);
            ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
            text(slide, "FOOTER_SECRET", 20, 490, 500, 40).setPlaceholder(Placeholder.FOOTER);
            deck.getNotesSlide(slide).createTextBox().setText("NOTES_SECRET");
            slide.getSlideMaster().createTextBox().setText("MASTER_SECRET");
        }
        return deck;
    }
    private static XMLSlideShow transformedGroupDeck(boolean reflection, boolean nonuniform) {
        XMLSlideShow deck = deck();
        XSLFGroupShape outer = deck.createSlide().createGroup();
        outer.setAnchor(new Rectangle2D.Double(100, 100, nonuniform ? 400 : 300, 300));
        outer.setInteriorAnchor(new Rectangle2D.Double(0, 0, 300, 300));
        outer.setRotation(30); outer.setFlipHorizontal(reflection);
        XSLFGroupShape inner = outer.createGroup();
        // EMU rounding produces slightly unequal scales; equivalent uniform transforms still qualify.
        inner.setAnchor(new Rectangle2D.Double(50, 50, 133.3333333, 100));
        inner.setInteriorAnchor(new Rectangle2D.Double(0, 0, 160, 120)); inner.setRotation(20);
        XSLFAutoShape leaf = inner.createAutoShape(); leaf.setShapeType(ShapeType.RECT);
        leaf.setAnchor(new Rectangle2D.Double(20, 20, 120, 70)); leaf.setRotation(15);
        leaf.setFillColor(new Color(230, 235, 250)); leaf.setText("図内テキスト").setFontSize(16.0);
        return deck;
    }
    private static XMLSlideShow deck() { XMLSlideShow result = new XMLSlideShow(); result.setPageSize(new Dimension(720, 540)); return result; }
    private static XSLFTextBox text(XSLFSlide slide, String text, double x, double y, double width, double height) {
        XSLFTextBox box = slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(x, y, width, height)); box.setText(text).setFontSize(20.0); return box;
    }
    private static XSLFAutoShape box(XSLFSlide slide, String text, double x, double y, double width, double height) {
        XSLFAutoShape box = slide.createAutoShape(); box.setShapeType(ShapeType.RECT); box.setAnchor(new Rectangle2D.Double(x, y, width, height)); box.setFillColor(new Color(220, 230, 255)); box.setText(text).setFontSize(20.0); return box;
    }
    private static byte[] bytes(XMLSlideShow deck) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); deck.write(out); return out.toByteArray(); }
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.BLUE); graphics.fillRect(0, 0, 16, 12); graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); image.flush(); return out.toByteArray();
    }
    private static byte[] twoColorPng() throws Exception {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED); graphics.fillRect(0, 0, 8, 12);
        graphics.setColor(Color.BLUE); graphics.fillRect(8, 0, 8, 12); graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); image.flush(); return out.toByteArray();
    }
    private static List<String> imageLines(String markdown) { return markdown.lines().filter(line -> line.startsWith("![")).toList(); }
    private static String withoutImages(String markdown) {
        return String.join("\n", markdown.lines().filter(line -> !line.startsWith("![")).toList());
    }
    private static JsonNode diagram(ConversionResult result) throws Exception {
        for (JsonNode block : report(result).path("blocks")) if (block.path("type").asText().equals("diagram")) return block;
        fail("Expected a diagram report block"); return null;
    }
    private static JsonNode nodeByText(ConversionResult result, String text) throws Exception {
        for (JsonNode node : diagram(result).path("nodes")) if (node.path("text").asText().equals(text)) return node;
        fail("Expected diagram node with text: " + text); return null;
    }
    private static JsonNode nodeById(ConversionResult result, String id) throws Exception {
        for (JsonNode node : diagram(result).path("nodes")) if (node.path("id").asText().equals(id)) return node;
        fail("Expected diagram node with id: " + id); return null;
    }
    private static JsonNode edgeById(ConversionResult result, String id) throws Exception {
        for (JsonNode edge : diagram(result).path("edges")) if (edge.path("id").asText().equals(id)) return edge;
        fail("Expected diagram edge with id: " + id); return null;
    }
    private static XSLFConnectorShape connected(XSLFSlide slide, XSLFShape start, XSLFShape end) {
        XSLFConnectorShape connector = slide.createConnector();
        connector.setAnchor(new Rectangle2D.Double(220, 120, 140, 0));
        connector.setLineColor(Color.BLACK); connector.setLineWidth(2);
        var properties = ((org.openxmlformats.schemas.presentationml.x2006.main.CTConnector) connector.getXmlObject()).getNvCxnSpPr().getCNvCxnSpPr();
        properties.addNewStCxn().setId(start.getShapeId()); properties.getStCxn().setIdx(0);
        properties.addNewEndCxn().setId(end.getShapeId()); properties.getEndCxn().setIdx(0);
        return connector;
    }
    private static void assertNodeFields(JsonNode node) {
        Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "type", "text", "x", "y", "width", "height"), fields);
        assertTrue(node.path("id").asText().matches("shape-\\d+"));
        for (String coordinate : List.of("x", "y", "width", "height")) assertTrue(node.path(coordinate).isNumber());
    }
    private static void assertSlideCanvas(BufferedImage image) {
        assertEquals(976, image.getWidth()); assertEquals(736, image.getHeight());
    }
    private static int px(double points) { return (int) Math.round((points + 6) * 96 / 72); }
    private static JsonNode imageMetadata(String image) throws Exception {
        int closing = image.indexOf("](");
        assertTrue(closing > 2, "Expected an image reference with inline JSON alt text: " + image);
        JsonNode metadata = JSON.readTree(image.substring(2, closing));
        assertTrue(metadata.isObject());
        return metadata;
    }
    private static void assertBounds(JsonNode metadata, double x, double y, double width, double height) {
        assertEquals(x, metadata.path("x").asDouble(), 0.000001);
        assertEquals(y, metadata.path("y").asDouble(), 0.000001);
        assertEquals(width, metadata.path("width").asDouble(), 0.000001);
        assertEquals(height, metadata.path("height").asDouble(), 0.000001);
    }
    private static void assertImageMetadata(ConversionResult result, List<String> images) throws Exception {
        JsonNode blocks = report(result).path("blocks");
        int withMetadata = 0;
        for (JsonNode block : blocks) if (block.has("metadata")) withMetadata++;
        assertEquals(images.size(), withMetadata);
        for (String image : images) {
            JsonNode metadata = imageMetadata(image);
            Set<String> fields = new HashSet<>(); metadata.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("type", "text", "x", "y", "width", "height"), fields);
            for (String coordinate : List.of("x", "y", "width", "height")) {
                JsonNode number = metadata.path(coordinate);
                assertTrue(number.isNumber(), coordinate + " must remain a JSON number");
                assertTrue(number.decimalValue().stripTrailingZeros().scale() <= 3, coordinate + " must be rounded to at most three decimal places");
            }
            assertFalse(image.contains("時計回り")); assertFalse(image.contains("保存値")); assertFalse(metadata.has("rotation"));
            String path = image.substring(image.indexOf("](") + 2, image.length() - 1);
            boolean found = false;
            for (JsonNode block : blocks) {
                if (path.equals(block.path("path").asText()) && metadata.equals(block.path("metadata"))) found = true;
            }
            assertTrue(found, "report.json must contain the same metadata object for " + path);
        }
    }
    private static ConversionResult convert(byte[] bytes) { return new PowerPointMarkdownConverter(ConversionLimits.defaults()).convert(bytes, "sample.pptx"); }
    private static String md(ConversionResult result) throws Exception { return Files.readString(result.files().get("document.md")); }
    private static JsonNode report(ConversionResult result) throws Exception { return JSON.readTree(Files.readAllBytes(result.files().get("report.json"))); }
}
