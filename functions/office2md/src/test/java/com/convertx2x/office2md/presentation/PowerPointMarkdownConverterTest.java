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
                assertTrue(md.startsWith("# 表示タイトル\n\n"));
                assertTrue(md.contains("# スライド3\n"));
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

    @Test void connectedAndOverlappingShapesStayIndividualAndInterleaveWithBodyText() throws Exception {
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
                assertEquals(3, images.size());
                assertTrue(images.get(0).contains("図内の処理")); assertFalse(images.get(0).contains("図内の結果"));
                assertTrue(images.get(1).contains("図内の結果")); assertFalse(images.get(1).contains("図内の処理"));
                assertTrue(images.get(2).contains("接続線"));
                assertTrue(images.stream().noneMatch(line -> line.contains(" / ")), "Every image describes exactly one leaf");
                assertTrue(md.contains("長方形")); assertTrue(md.contains("時計回り15度"));
                assertTrue(md.contains("基準=スライド左上、外接矩形"));
                assertTrue(md.indexOf("https://example.invalid/details") > md.indexOf("](images/diagram-"));
                assertTrue(md.lines().anyMatch(line -> line.equals("重なる説明")), "An ordinary overlapping textbox remains Markdown");
                assertTrue(md.indexOf(images.get(1)) < md.indexOf("\n重なる説明\n"));
                assertTrue(md.indexOf("\n重なる説明\n") < md.indexOf(images.get(2)), "Body and images follow their individual Y/X positions");
                JsonNode assets = report(result).path("assets"); assertEquals(3, assets.size());
                assertEquals("image/png", assets.get(0).path("contentType").asText());
                BufferedImage image = ImageIO.read(result.files().get(assets.get(0).path("path").asText()).toFile());
                assertTrue(image.getWidth() < 350); assertTrue(image.getHeight() > 100); image.flush();
                BufferedImage line = ImageIO.read(result.files().get(assets.get(2).path("path").asText()).toFile());
                assertTrue(visiblePixels(line) > 150, "A zero-height horizontal connector must not become an empty PNG"); line.flush();
            }
        }
    }

    @Test void individualGroupLeafUsesTransformedBoundsAndParentTransformForPixels() throws Exception {
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
                assertEquals(2, images.size());
                assertTrue(images.get(0).contains("長方形、時計回り90度"));
                assertTrue(images.get(0).contains("外接矩形 X=320pt、Y=120pt、幅=40pt、高さ=80pt"), images.get(0));
                assertTrue(images.get(1).contains("テキストボックス")); assertTrue(images.get(1).contains("図の文字"));
                assertFalse(md.lines().anyMatch(line -> line.equals("図の文字")));
                assertTrue(md.indexOf(images.get(0)) < md.indexOf("\n図の間にある本文\n"));
                assertTrue(md.indexOf("\n図の間にある本文\n") < md.indexOf(images.get(1)));
                String imagePath = report(result).path("assets").get(0).path("path").asText();
                BufferedImage image = ImageIO.read(result.files().get(imagePath).toFile());
                assertEquals(70, image.getWidth()); assertEquals(123, image.getHeight());
                assertTrue(visiblePixels(image) > 5000, "The group transform must place the leaf inside its individual canvas"); image.flush();
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
                assertTrue(md.contains("画像、時計回り90度"));
                assertTrue(md.contains("外接矩形 X=340pt、Y=80pt、幅=40pt、高さ=120pt"), md);
                String path = report(result).path("assets").get(0).path("path").asText();
                assertTrue(path.startsWith("images/diagram-"), "A parent's transform must prevent unmodified image extraction");
                BufferedImage image = ImageIO.read(result.files().get(path).toFile());
                assertEquals(70, image.getWidth()); assertEquals(176, image.getHeight());
                assertTrue(visiblePixels(image) > 5000); image.flush();
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

    @Test void nestedUniformGroupRotationIsComposedForEachLeafDescription() throws Exception {
        try (XMLSlideShow deck = transformedGroupDeck(false, false); ConversionResult result = convert(bytes(deck))) {
            String md = md(result);
            assertTrue(md.contains("長方形、時計回り65度、図内テキスト"), md);
            assertFalse(md.contains("保存値"));
            assertEquals(1, report(result).path("assets").size());
        }
    }

    @Test void reflectedOrNonuniformGroupsQualifySavedLeafRotation() throws Exception {
        for (boolean reflection : new boolean[]{false, true}) {
            try (XMLSlideShow deck = transformedGroupDeck(reflection, !reflection); ConversionResult result = convert(bytes(deck))) {
                String md = md(result);
                assertTrue(md.contains("時計回り15度（図形の保存値。反転・グループ変形後の角度は未算出）"), md);
                assertTrue(md.contains("図内テキスト"));
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
            assertEquals(2, report(removed).path("assets").size());
            for (JsonNode asset : report(removed).path("assets")) {
                String path = asset.path("path").asText();
                assertArrayEquals(Files.readAllBytes(clean.files().get(path)), Files.readAllBytes(removed.files().get(path)), "Deleted glyphs must also disappear from every individual shape PNG");
            }
            assertTrue(md(removed).contains("長方形")); assertTrue(md(removed).contains("楕円"));
        }
    }

    @Test void embeddedPicturesArePreservedAndExternalPicturesNeverFetched() throws Exception {
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
                assertArrayEquals(png, Files.readAllBytes(result.files().get(json.path("assets").get(0).path("path").asText())));
                assertTrue(md.contains("保存済みロゴ")); assertTrue(md.contains("外部参照画像"));
                assertFalse(md.contains(url)); assertTrue(json.toString().contains("EXTERNAL_IMAGE_SKIPPED"));
            }
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void renderingAndTextLimitsAreEnforcedBeforeLargeOutput() throws Exception {
        ConversionLimits defaults = ConversionLimits.defaults();
        try (XMLSlideShow deck = deck()) {
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
                assertEquals(201, markdown.lines().filter(line -> line.startsWith("![")).count());
                assertEquals(1, report(result).path("assets").size(), "Repeated images share one output file");
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
                assertTrue(md(result).contains("時計回り90度"));
                BufferedImage output = ImageIO.read(result.files().get(asset.path("path").asText()).toFile());
                assertTrue(output.getHeight() > output.getWidth(), "A 90 degree rotation must change the visible image orientation");
                assertTrue(output.getWidth() > 100); output.flush();
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
    private static long visiblePixels(BufferedImage image) {
        long pixels = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if ((image.getRGB(x, y) >>> 24) > 128) pixels++;
        return pixels;
    }
    private static ConversionResult convert(byte[] bytes) { return new PowerPointMarkdownConverter(ConversionLimits.defaults()).convert(bytes, "sample.pptx"); }
    private static String md(ConversionResult result) throws Exception { return Files.readString(result.files().get("document.md")); }
    private static JsonNode report(ConversionResult result) throws Exception { return JSON.readTree(Files.readAllBytes(result.files().get("report.json"))); }
}
