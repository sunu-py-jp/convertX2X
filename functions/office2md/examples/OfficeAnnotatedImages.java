import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.DocumentDocument;

/** Editable native annotations on the same self-authored fictional screen in Word and PowerPoint. */
public final class OfficeAnnotatedImages {
    private static final Color NAVY = new Color(0x19324D);
    private static final Color RED = new Color(0xCD324A);
    private static final Color GREEN = new Color(0x157D70);
    private static final String FONT = "Noto Sans CJK JP";
    private static final int EMU = 12700;
    private static final String NS = """
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            """;

    public static void main(String[] args) throws Exception {
        var options = args.length >= 2 ? java.util.Arrays.asList(args).subList(2, args.length) : java.util.List.<String>of();
        if (args.length < 2 || options.stream().anyMatch(option -> !java.util.Set.of(
                "--convert", "--format=word", "--format=powerpoint").contains(option))
                || options.contains("--format=word") && options.contains("--format=powerpoint"))
            throw new IllegalArgumentException(
                    "Usage: OfficeAnnotatedImages examples-directory source-image.png [--convert] [--format=word|powerpoint]");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        byte[] source = Files.readAllBytes(Path.of(args[1]));
        Path word = root.resolve("word-annotated-images");
        Path powerpoint = root.resolve("powerpoint-annotated-images");
        boolean includeWord = !options.contains("--format=powerpoint");
        boolean includePowerPoint = !options.contains("--format=word");
        for (Path directory : new Path[]{word, powerpoint}) {
            if (directory.equals(word) && !includeWord || directory.equals(powerpoint) && !includePowerPoint) continue;
            Files.createDirectories(directory);
            Files.write(directory.resolve("source-image.png"), source);
        }
        if (includeWord) createWord(word.resolve("input.docx"), source);
        if (includePowerPoint) createPowerPoint(powerpoint.resolve("input.pptx"), source);
        if (options.contains("--convert")) {
            if (includeWord) convert(word, "input.docx");
            if (includePowerPoint) convert(powerpoint, "input.pptx");
        }
    }

    private static void createPowerPoint(Path output, byte[] source) throws Exception {
        try (var deck = new XMLSlideShow()) {
            deck.setPageSize(new Dimension(960, 620));
            var slide = deck.createSlide();
            var title = slide.createTextBox();
            title.setPlaceholder(Placeholder.TITLE);
            title.setAnchor(new Rectangle2D.Double(32, 18, 890, 48));
            title.setText("PowerPoint / 画面画像と編集可能な注釈").setFontSize(28.0);
            format(title, NAVY, 28, true);
            var intro = slide.createTextBox();
            intro.setAnchor(new Rectangle2D.Double(32, 76, 880, 40));
            intro.setText("赤枠は画像内、注釈は右下へはみ出します。緑のメモは画像から離しています。");
            format(intro, NAVY, 16, false);
            var picture = slide.createPicture(deck.addPicture(source, PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(45, 135, 576, 360));
            ((CTPicture) picture.getXmlObject()).getNvPicPr().getCNvPr()
                    .setDescr("架空の申請一覧画面。赤枠と注釈は別の編集可能な図形。");
            rectangle(slide, 62, 390, 360, 78, RED, null, "");
            var annotation = slide.createTextBox();
            annotation.setAnchor(new Rectangle2D.Double(530, 450, 260, 112));
            annotation.setFillColor(new Color(0xFFF0F3));
            annotation.setLineColor(RED);
            annotation.setLineWidth(3);
            annotation.setVerticalAlignment(VerticalAlignment.MIDDLE);
            annotation.setText("送信前に\n状態を確認");
            format(annotation, RED, 18, true);
            for (var paragraph : annotation.getTextParagraphs())
                paragraph.setTextAlign(TextParagraph.TextAlign.CENTER);
            rectangle(slide, 710, 190, 205, 98, GREEN, new Color(0xEAF8F3),
                    "画像と離れたメモ\n単独の図形として保持");
            createPowerPointPictureLayers(deck, source);
            createPowerPointTableLayers(deck, source);
            deck.getProperties().getCoreProperties().setTitle("office2md PowerPoint 画像と注釈の合成");
            try (var stream = Files.newOutputStream(output)) { deck.write(stream); }
        }
        System.out.println(output);
    }

    private static void createPowerPointPictureLayers(XMLSlideShow deck, byte[] source) {
        var slide = deck.createSlide();
        pptTitle(slide, "PowerPoint / 画像の上に画像を重ねる",
                "画面全体に拡大画像を重ねています。画像だけの重なりも1枚へまとめます。");
        var data = deck.addPicture(source, PictureData.PictureType.PNG);
        var screen = slide.createPicture(data);
        screen.setAnchor(new Rectangle2D.Double(45, 145, 576, 360));
        ((CTPicture) screen.getXmlObject()).getNvPicPr().getCNvPr().setDescr("架空の申請一覧画面");
        var zoom = slide.createPicture(data);
        zoom.setAnchor(new Rectangle2D.Double(430, 400, 480, 90));
        // Zoom into the status/button strip. Keep the original media as a shared embedded image.
        var crop = ((CTPicture) zoom.getXmlObject()).getBlipFill().addNewSrcRect();
        crop.setL(0); crop.setT(70000); crop.setR(0); crop.setB(10000);
        ((CTPicture) zoom.getXmlObject()).getNvPicPr().getCNvPr().setDescr("状態欄と送信ボタンの拡大画像");
    }

    private static void createPowerPointTableLayers(XMLSlideShow deck, byte[] source) {
        var slide = deck.createSlide();
        pptTitle(slide, "PowerPoint / 表に重なる画像・図形・文字",
                "表はMarkdownの表として残し、重なりを保った見た目も1枚のPNGにします。");
        var table = slide.createTable(4, 3);
        for (int column = 0; column < 3; column++) table.setColumnWidth(column, 200);
        for (int row = 0; row < 4; row++) table.setRowHeight(row, 68);
        table.setAnchor(new Rectangle2D.Double(45, 165, 600, 272));
        table.getCTTable().getTblPr().setFirstRow(true);
        String[][] contents = {
                {"確認項目", "担当", "状態"},
                {"申請内容", "営業", "確認済み"},
                {"承認条件", "管理", "再確認"},
                {"送信前確認", "担当者", "未着手"}
        };
        for (int row = 0; row < 4; row++) for (int column = 0; column < 3; column++) {
            var cell = table.getCell(row, column);
            cell.setText(contents[row][column]);
            cell.setFillColor(row == 0 ? NAVY : row % 2 == 0 ? new Color(0xEAF2F7) : Color.WHITE);
            cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
            format(cell, row == 0 ? Color.WHITE : NAVY, 20, row == 0);
        }
        rectangle(slide, 445, 300, 195, 67, RED, null, "");
        var screen = slide.createPicture(deck.addPicture(source, PictureData.PictureType.PNG));
        screen.setAnchor(new Rectangle2D.Double(590, 390, 315, 197));
        ((CTPicture) screen.getXmlObject()).getNvPicPr().getCNvPr().setDescr("表の右下に重なる補足画面");
        var note = slide.createTextBox();
        note.setAnchor(new Rectangle2D.Double(590, 285, 300, 66));
        note.setText("承認条件を再確認");
        note.setFillColor(new Color(0xFFF0F3)); note.setLineColor(RED);
        note.setVerticalAlignment(VerticalAlignment.MIDDLE);
        format(note, RED, 20, true);
    }

    private static void pptTitle(XSLFSlide slide, String titleText, String introText) {
        var title = slide.createTextBox(); title.setPlaceholder(Placeholder.TITLE);
        title.setAnchor(new Rectangle2D.Double(32, 18, 900, 48)); title.setText(titleText);
        format(title, NAVY, 28, true);
        var intro = slide.createTextBox(); intro.setAnchor(new Rectangle2D.Double(32, 76, 895, 50));
        intro.setText(introText); format(intro, NAVY, 16, false);
    }

    private static void rectangle(XSLFSlide slide, double x, double y, double width, double height,
                                  Color line, Color fill, String text) {
        var shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(x, y, width, height));
        shape.setFillColor(fill);
        shape.setLineColor(line);
        shape.setLineWidth(3);
        shape.setVerticalAlignment(VerticalAlignment.MIDDLE);
        shape.setLeftInset(10);
        shape.setRightInset(10);
        shape.setText(text);
        format(shape, line, 18, true);
        for (var paragraph : shape.getTextParagraphs())
            paragraph.setTextAlign(TextParagraph.TextAlign.CENTER);
    }

    private static void format(XSLFTextShape shape, Color color, double size, boolean bold) {
        for (var paragraph : shape.getTextParagraphs()) {
            for (var run : paragraph.getTextRuns()) {
                run.setFontFamily(FONT);
                run.setFontColor(color);
                run.setFontSize(size);
                run.setBold(bold);
            }
        }
    }

    private static void createWord(Path output, byte[] source) throws Exception {
        try (var document = new XWPFDocument()) {
            String relation = document.addPictureData(source, XWPFDocument.PICTURE_TYPE_PNG);
            String drawings =
                    anchor(1, 1, 54, 155, 384, 240, picture(relation, 384, 240), true)
                    + anchor(2, 2, 66, 326, 240, 50,
                            shape(2, 240, 50, "CD324A", null, ""), false)
                    + anchor(3, 3, 365, 365, 170, 78,
                            shape(3, 170, 78, "CD324A", "FFF0F3", "送信前に\n状態を確認"), false)
                    + anchor(4, 4, 54, 485, 230, 62,
                            shape(4, 230, 62, "157D70", "EAF8F3", "画像と離れたメモ\n単独の図形として保持"), false);
            String body = paragraph("Word / 画面画像と編集可能な注釈", true)
                    + paragraph("同じ段落のページ基準で配置した画像・赤枠・注釈です。図形は画像に焼き込まず、Wordで編集できます。", false)
                    + "<w:p>" + drawings + "</w:p>"
                    + paragraph("赤枠と右下の注釈は画面画像へ重なります。緑のメモは画像から離れた場所にあります。", false)
                    + wordPictureLayers(relation)
                    + wordTableLayers(relation)
                    + "<w:sectPr><w:pgSz w:w='11906' w:h='16838'/>"
                    + "<w:pgMar w:top='720' w:right='720' w:bottom='720' w:left='720'/></w:sectPr>";
            String xml = "<w:document " + NS + "><w:body>" + body + "</w:body></w:document>";
            document.getDocument().setBody(DocumentDocument.Factory.parse(xml).getDocument().getBody());
            document.getProperties().getCoreProperties().setTitle("office2md Word 画像と注釈の合成");
            try (var stream = Files.newOutputStream(output)) { document.write(stream); }
        }
        System.out.println(output);
    }

    private static String wordPictureLayers(String relation) {
        return "<w:p><w:r><w:br w:type='page'/></w:r></w:p>"
                + paragraph("Word / 画像の上に画像を重ねる", true)
                + paragraph("同じ画面を縮小して右下へ重ねています。画像同士の重なりも1枚にまとめます。", false)
                + "<w:p>" + anchor(11, 11, 54, 160, 384, 240, picture(relation, 384, 240, "架空の申請一覧画面。"), true)
                + anchor(12, 12, 280, 310, 235, 147, picture(relation, 235, 147, "同じ画面を縮小した画像。"), true) + "</w:p>";
    }

    private static String wordTableLayers(String relation) {
        String overlays = anchor(21, 21, 330, 240, 210, 131, picture(relation, 210, 131, "表に重なる補足画面。"), true)
                + anchor(22, 22, 350, 185, 170, 60,
                        shape(22, 170, 60, "CD324A", "FFF0F3", "確認対象を強調"), false);
        overlays = overlays.replace("layoutInCell='1'", "layoutInCell='0'");
        String[] sides = {"top", "bottom", "left", "right", "insideH", "insideV"};
        StringBuilder borders = new StringBuilder();
        for (String side : sides) borders.append("<w:").append(side)
                .append(" w:val='single' w:sz='8' w:color='19324D'/>");
        String[][] values = {{"項目", "担当", "状態"}, {"申請内容", "営業", "確認済み"}, {"承認条件", "管理", "再確認"}};
        StringBuilder rows = new StringBuilder();
        for (int row = 0; row < values.length; row++) {
            rows.append("<w:tr><w:trPr><w:trHeight w:val='1000' w:hRule='exact'/>")
                    .append(row == 0 ? "<w:tblHeader/>" : "").append("</w:trPr>");
            for (int column = 0; column < values[row].length; column++) {
                rows.append("<w:tc><w:tcPr><w:tcW w:w='2400' w:type='dxa'/>")
                        .append("<w:shd w:fill='").append(row == 0 ? "DDEAF2" : "FFFFFF")
                        .append("'/><w:vAlign w:val='center'/></w:tcPr><w:p><w:pPr><w:jc w:val='center'/></w:pPr>")
                        .append("<w:r><w:rPr><w:rFonts w:ascii='").append(FONT)
                        .append("' w:eastAsia='").append(FONT).append("'/><w:sz w:val='24'/>")
                        .append(row == 0 ? "<w:b/>" : "").append("</w:rPr><w:t>").append(values[row][column])
                        .append("</w:t></w:r>").append(row == 0 && column == 0 ? overlays : "").append("</w:p></w:tc>");
            }
            rows.append("</w:tr>");
        }
        return "<w:p><w:r><w:br w:type='page'/></w:r></w:p>"
                + paragraph("Word / 固定位置の表と画像・図形", true)
                + paragraph("固定列幅・固定行高・保存位置を持つ浮動表です。表の文字はMarkdownにも保持します。", false)
                + "<w:tbl><w:tblPr><w:tblW w:w='7200' w:type='dxa'/><w:tblLayout w:type='fixed'/>"
                + "<w:tblpPr w:horzAnchor='page' w:vertAnchor='page' w:tblpX='1000' w:tblpY='3400'/>"
                + "<w:tblBorders>" + borders + "</w:tblBorders></w:tblPr><w:tblGrid>"
                + "<w:gridCol w:w='2400'/><w:gridCol w:w='2400'/><w:gridCol w:w='2400'/></w:tblGrid>"
                + rows + "</w:tbl>";
    }

    private static String paragraph(String text, boolean title) {
        return "<w:p>" + (title ? "<w:pPr><w:outlineLvl w:val='0'/></w:pPr>" : "")
                + "<w:r><w:rPr><w:rFonts w:ascii='" + FONT + "' w:eastAsia='" + FONT
                + "'/><w:sz w:val='" + (title ? "34" : "22") + "'/>"
                + (title ? "<w:b/>" : "") + "</w:rPr><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static String anchor(int id, int z, int x, int y, int width, int height,
                                 String element, boolean picture) {
        String uri = picture ? "http://schemas.openxmlformats.org/drawingml/2006/picture"
                : "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
        return "<w:r><w:drawing><wp:anchor distT='0' distB='0' distL='0' distR='0'"
                + " simplePos='0' relativeHeight='" + z
                + "' behindDoc='0' locked='0' layoutInCell='1' allowOverlap='1'>"
                + "<wp:simplePos x='0' y='0'/><wp:positionH relativeFrom='page'><wp:posOffset>"
                + x * EMU + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom='page'><wp:posOffset>" + y * EMU
                + "</wp:posOffset></wp:positionV><wp:extent cx='" + width * EMU
                + "' cy='" + height * EMU + "'/><wp:wrapNone/>"
                + "<wp:docPr id='" + id + "' name='drawing-" + id + "'/>"
                + "<wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri='" + uri + "'>"
                + element + "</a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    private static String picture(String relation, int width, int height) {
        return picture(relation, width, height, "架空の申請一覧画面。赤枠と注釈は別の編集可能な図形。");
    }

    private static String picture(String relation, int width, int height, String description) {
        return "<pic:pic><pic:nvPicPr><pic:cNvPr id='1' name='fictional-screen'"
                + " descr='" + description + "'/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed='" + relation
                + "'/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr>" + transform(width, height)
                + "<a:prstGeom prst='rect'><a:avLst/></a:prstGeom></pic:spPr></pic:pic>";
    }

    private static String shape(int id, int width, int height, String line, String fill, String text) {
        StringBuilder paragraphs = new StringBuilder();
        for (String value : text.split("\n", -1))
            paragraphs.append("<w:p><w:pPr><w:jc w:val='center'/></w:pPr><w:r><w:rPr>")
                    .append("<w:rFonts w:ascii='").append(FONT).append("' w:eastAsia='").append(FONT)
                    .append("'/><w:b/><w:sz w:val='24'/><w:color w:val='").append(line)
                    .append("'/></w:rPr><w:t>").append(value).append("</w:t></w:r></w:p>");
        return "<wps:wsp><wps:cNvPr id='" + id + "' name='annotation-" + id
                + "'/><wps:cNvSpPr" + (id == 3 ? " txBox='1'" : "") + "/><wps:spPr>" + transform(width, height)
                + "<a:prstGeom prst='rect'><a:avLst/></a:prstGeom>"
                + (fill == null ? "<a:noFill/>" : "<a:solidFill><a:srgbClr val='" + fill + "'/></a:solidFill>")
                + "<a:ln w='38100'><a:solidFill><a:srgbClr val='" + line
                + "'/></a:solidFill></a:ln></wps:spPr><wps:txbx><w:txbxContent>"
                + paragraphs + "</w:txbxContent></wps:txbx><wps:bodyPr anchor='ctr'"
                + " lIns='91440' rIns='91440' tIns='45720' bIns='45720'/></wps:wsp>";
    }

    private static String transform(int width, int height) {
        return "<a:xfrm><a:off x='0' y='0'/><a:ext cx='" + width * EMU + "' cy='"
                + height * EMU + "'/></a:xfrm>";
    }

    private static void convert(Path directory, String filename) throws Exception {
        byte[] input = Files.readAllBytes(directory.resolve(filename));
        var service = new com.convertx2x.office2md.conversion.OfficeMarkdownService(
                com.convertx2x.office2md.conversion.ConversionLimits.defaults());
        long started = System.nanoTime();
        try (var result = service.convert(input, filename,
                com.convertx2x.office2md.conversion.ImageMode.IGNORE)) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            Path output = directory.resolve("result");
            Files.createDirectories(output);
            var artifacts = new java.util.ArrayList<Map<String, Object>>();
            for (var entry : result.files().entrySet()) {
                Path target = output.resolve(entry.getKey()).normalize();
                if (!target.startsWith(output)) throw new IllegalStateException("Unsafe artifact path");
                Files.createDirectories(target.getParent());
                Files.copy(entry.getValue(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                artifacts.add(Map.of("path", "result/" + entry.getKey(),
                        "sizeBytes", Files.size(target), "sha256", sha256(Files.readAllBytes(target))));
            }
            byte[] zip = result.zipBytes();
            Files.write(directory.resolve("result.zip"), zip);
            var record = new LinkedHashMap<String, Object>();
            record.put("completedAt", java.time.Instant.now().toString());
            record.put("execution", "OfficeMarkdownService shared by HTTP and Queue");
            record.put("imageMode", "ignore");
            record.put("input", Map.of("path", filename, "sizeBytes", input.length, "sha256", sha256(input)));
            record.put("elapsedMs", elapsed);
            record.put("sectionCount", result.sectionCount());
            record.put("warningCount", result.warningCount());
            record.put("zip", Map.of("path", "result.zip", "sizeBytes", zip.length, "sha256", sha256(zip)));
            record.put("artifacts", artifacts);
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve("run.json").toFile(), record);
            System.out.printf("%s: sections=%d warnings=%d files=%d elapsed=%d ms%n",
                    filename, result.sectionCount(), result.warningCount(), artifacts.size(), elapsed);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
