import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;

/** Self-authored screen image and editable annotations; no external images or real business data. */
public final class ExcelAnnotatedImages {
    private static final Color NAVY = new Color(0x19324D);
    private static final Color RED = new Color(0xCD324A);
    private static final Color BLUE = new Color(0x2468B3);
    private static final Color TEAL = new Color(0x157D70);
    private static final String FONT = "Noto Sans CJK JP";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || args.length == 2 && !args[1].equals("--convert"))
            throw new IllegalArgumentException("Usage: ExcelAnnotatedImages output-directory [--convert]");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        byte[] source = screen();
        Files.write(directory.resolve("source-image.png"), source);
        Path file = directory.resolve("input.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("画像と注釈の比較");
            sheet.setDisplayGridlines(false);
            for (int column = 0; column < 31; column++) sheet.setColumnWidth(column, 9 * 256);
            for (int row = 0; row < 35; row++) sheet.createRow(row).setHeightInPoints(20);
            var title = cellStyle(book, 19, true, Color.WHITE, NAVY);
            var label = cellStyle(book, 12, true, NAVY, new Color(0xE9EFF5));
            var note = cellStyle(book, 11, false, NAVY, Color.WHITE);
            mergedText(sheet, 0, 1, 0, 30, "画像と注釈の比較 / 同じ画面に異なる図形を重ねた例", title);
            mergedText(sheet, 2, 2, 0, 30, "画面は同じPNGを2か所へ配置。赤枠・青枠・注釈はExcelで編集できる図形です。", note);
            mergedText(sheet, 3, 3, 1, 13, "① 赤枠 ＋ 画像の外にはみ出す注釈", label);
            mergedText(sheet, 3, 3, 16, 30, "② 同じ元画像 ＋ 別の青い注釈", label);

            var drawing = sheet.createDrawingPatriarch();
            int pictureIndex = book.addPicture(source, Workbook.PICTURE_TYPE_PNG);
            var first = drawing.createPicture(anchor(1, 5, 11, 20), pictureIndex);
            pictureName(first, "screen-A", "架空の申請一覧画面。元画像はscreen-Bと同一。");
            var second = drawing.createPicture(anchor(16, 5, 26, 20), pictureIndex);
            pictureName(second, "screen-B", "架空の申請一覧画面。元画像はscreen-Aと同一。");

            var redFrame = rectangle(drawing, "red-frame", 1, 15, 8, 19, RED, null, "");
            var redNote = rectangle(drawing, "red-note", 9, 18, 14, 24, RED, new Color(0xFFF0F3),
                    "送信前に\n状態を確認");
            connector(drawing, redFrame, redNote, 8, 17, 9, 21);

            rectangle(drawing, "blue-frame", 16, 7, 24, 9, BLUE, null, "");
            rectangle(drawing, "blue-note", 24, 7, 29, 12, BLUE, new Color(0xEDF5FF),
                    "受付番号を確認\n別の注釈を保持");
            rectangle(drawing, "touch-only", 26, 16, 31, 19, TEAL, new Color(0xEAF8F3),
                    "接触だけの図形\n画像とは別に保持");
            rectangle(drawing, "separate-note", 1, 28, 9, 32, TEAL, new Color(0xEAF8F3),
                    "画像と離れたメモ\n単独の図形として保持");

            mergedText(sheet, 25, 26, 1, 13,
                    "赤枠は画面内に重なります。注釈は画面の右下へはみ出し、赤枠との保存済み接続を持ちます。", note);
            mergedText(sheet, 22, 24, 16, 30,
                    "青い注釈は元画像の右へはみ出します。緑の図形は画像右端に接するだけで、重なる面積はありません。", note);
            mergedText(sheet, 33, 34, 1, 30,
                    "全て架空の内容です。同じ元画像でも注釈が異なれば別の合成結果になり、図形文字と確認できた接続はMarkdownに残ります。", note);
            book.setPrintArea(0, 0, 30, 0, 34);
            extendedExamples(book, pictureIndex, label, note);
            book.getProperties().getCoreProperties().setTitle("office2md 画像に重なる図形の合成例");
            book.getProperties().getCoreProperties().setDescription("Original fictional screen with native, editable Excel annotations.");
            try (var output = Files.newOutputStream(file)) {
                book.write(output);
            }
        }
        try (var stream = Files.newInputStream(file); var saved = new XSSFWorkbook(stream)) {
            var shapes = saved.getSheetAt(0).getDrawingPatriarch().getShapes();
            long pictures = shapes.stream().filter(XSSFPicture.class::isInstance).count();
            if (saved.getAllPictures().size() != 1 || pictures != 2 || shapes.size() != 9 || saved.getNumberOfSheets() != 3)
                throw new IllegalStateException("Expected one shared image, two placements, six shapes and one connector");
            long connectors = shapes.stream().filter(XSSFConnector.class::isInstance).count();
            if (connectors != 1) throw new IllegalStateException("Expected one saved connector");
            System.out.printf("%s%nbytes=%d sheets=3 mediaParts=1 firstSheetPicturePlacements=%d firstSheetDrawings=%d%n",
                    file, Files.size(file), pictures, shapes.size());
        }
        if (args.length == 2) convert(directory);
    }

    private static void extendedExamples(XSSFWorkbook book, int pictureIndex, XSSFCellStyle label, XSSFCellStyle note) {
        var pictures = book.createSheet("画像同士の重なり");
        for (int c = 0; c < 18; c++) pictures.setColumnWidth(c, 10 * 256);
        for (int r = 0; r < 30; r++) pictures.createRow(r).setHeightInPoints(22);
        mergedText(pictures, 0, 1, 0, 17, "画面に別の画面を重ねる / 画像だけでも1枚に合成", label);
        mergedText(pictures, 2, 3, 0, 17, "画像の重なり順と位置を保持。図形がなくても、重なる画像は同じ出力画像へまとめます。", note);
        var layers = pictures.createDrawingPatriarch();
        pictureName(layers.createPicture(anchor(1, 5, 12, 20), pictureIndex), "background-screen", "背面の画面");
        pictureName(layers.createPicture(anchor(8, 13, 17, 26), pictureIndex), "foreground-screen", "手前に重ねた画面");

        var table = book.createSheet("表と画像と注釈");
        for (int c = 0; c < 10; c++) table.setColumnWidth(c, 16 * 256);
        for (int r = 0; r < 14; r++) table.createRow(r).setHeightInPoints(36);
        mergedText(table, 0, 1, 0, 9, "表の文字も画像も注釈も保持", label);
        var head = cellStyle(book, 12, true, NAVY, new Color(0xDCEAF7));
        var body = cellStyle(book, 11, false, NAVY, Color.WHITE);
        for (var style : new XSSFCellStyle[]{head, body}) {
            style.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            style.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            style.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            style.setWrapText(true);
        }
        String[][] rows = {
                {"対象", "担当", "確認ポイント", "画面 / 状態", "備考"},
                {"申請一覧", "業務担当", "受付番号と状態", "", "画面上の赤枠を確認"},
                {"承認一覧", "管理担当", "承認待ちの表示", "", "操作手順を添付"},
                {"保存結果", "開発担当", "処理履歴", "正常", "Markdownにも表を保持"}
        };
        for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length; c++) {
            var cell = table.getRow(r + 3).createCell(c);
            cell.setCellValue(rows[r][c]); cell.setCellStyle(r == 0 ? head : body);
        }
        var drawings = table.createDrawingPatriarch();
        pictureName(drawings.createPicture(anchor(3, 4, 5, 6), pictureIndex), "table-screen", "表に重なる画面");
        rectangle(drawings, "table-red-frame", 2, 4, 5, 6, RED, null, "");
        rectangle(drawings, "table-note", 4, 5, 7, 8, BLUE, new Color(0xEDF5FF), "画面と確認箇所を\nまとめて確認");
        mergedText(table, 10, 11, 0, 9, "表はMarkdownにも残ります。合成画像内の表や図形文字をOCRへ重複送信しません。", note);
    }

    private static void convert(Path directory) throws Exception {
        byte[] input = Files.readAllBytes(directory.resolve("input.xlsx"));
        var service = new com.convertx2x.office2md.conversion.OfficeMarkdownService(
                com.convertx2x.office2md.conversion.ConversionLimits.defaults());
        long started = System.nanoTime();
        try (var result = service.convert(input, "input.xlsx",
                com.convertx2x.office2md.conversion.ImageMode.IGNORE)) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            Path output = directory.resolve("result");
            Files.createDirectories(output);
            var artifacts = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (var entry : result.files().entrySet()) {
                Path target = output.resolve(entry.getKey()).normalize();
                if (!target.startsWith(output)) throw new IllegalStateException("Unsafe artifact path");
                Files.createDirectories(target.getParent());
                Files.copy(entry.getValue(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                artifacts.add(java.util.Map.of("path", "result/" + entry.getKey(),
                        "sizeBytes", Files.size(target), "sha256", sha256(Files.readAllBytes(target))));
            }
            byte[] archive = result.zipBytes();
            Files.write(directory.resolve("result.zip"), archive);
            var record = new java.util.LinkedHashMap<String, Object>();
            record.put("completedAt", java.time.Instant.now().toString());
            record.put("execution", "OfficeMarkdownService shared by HTTP and Queue");
            record.put("imageMode", "ignore");
            record.put("input", java.util.Map.of("path", "input.xlsx", "sizeBytes", input.length, "sha256", sha256(input)));
            record.put("elapsedMs", elapsedMs);
            record.put("sectionCount", result.sectionCount());
            record.put("warningCount", result.warningCount());
            record.put("zip", java.util.Map.of("path", "result.zip", "sizeBytes", archive.length, "sha256", sha256(archive)));
            record.put("artifacts", artifacts);
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve("run.json").toFile(), record);
            System.out.printf("Converted: sections=%d warnings=%d files=%d ZIP=%d bytes elapsed=%d ms%n",
                    result.sectionCount(), result.warningCount(), artifacts.size(), archive.length, elapsedMs);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static XSSFClientAnchor anchor(int col1, int row1, int col2, int row2) {
        return new XSSFClientAnchor(0, 0, 0, 0, col1, row1, col2, row2);
    }

    private static void pictureName(XSSFPicture picture, String name, String description) {
        var properties = picture.getCTPicture().getNvPicPr().getCNvPr();
        properties.setName(name);
        properties.setDescr(description);
    }

    private static XSSFSimpleShape rectangle(XSSFDrawing drawing, String name, int col1, int row1,
                                             int col2, int row2, Color line, Color fill, String text) {
        var shape = drawing.createSimpleShape(anchor(col1, row1, col2, row2));
        shape.setShapeType(ShapeTypes.RECT);
        shape.setLineStyleColor(line.getRed(), line.getGreen(), line.getBlue());
        shape.setLineWidth(3);
        if (fill == null) shape.setNoFill(true);
        else shape.setFillColor(fill.getRed(), fill.getGreen(), fill.getBlue());
        shape.getCTShape().getNvSpPr().getCNvPr().setName(name);
        shape.setVerticalAlignment(VerticalAlignment.CENTER);
        shape.setWordWrap(true);
        shape.setTextAutofit(TextAutofit.NONE);
        shape.setLeftInset(8);
        shape.setRightInset(8);
        shape.clearText();
        if (!text.isEmpty()) {
            var paragraph = shape.addNewTextParagraph();
            paragraph.setTextAlign(TextAlign.CENTER);
            var run = paragraph.addNewTextRun();
            run.setText(text);
            run.setFont(FONT);
            run.setFontSize(14);
            run.setBold(true);
            run.setFontColor(line);
            run.getXmlObject().getRPr().addNewEa().setTypeface(FONT);
        }
        return shape;
    }

    private static void connector(XSSFDrawing drawing, XSSFSimpleShape from, XSSFSimpleShape to,
                                  int col1, int row1, int col2, int row2) {
        var line = drawing.createConnector(anchor(col1, row1, col2, row2));
        line.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1);
        line.setLineStyleColor(RED.getRed(), RED.getGreen(), RED.getBlue());
        line.setLineWidth(2);
        line.getCTConnector().getNvCxnSpPr().getCNvPr().setName("saved-connection");
        var endpoints = line.getCTConnector().getNvCxnSpPr().getCNvCxnSpPr();
        endpoints.addNewStCxn().setId(from.getCTShape().getNvSpPr().getCNvPr().getId());
        endpoints.getStCxn().setIdx(3);
        endpoints.addNewEndCxn().setId(to.getCTShape().getNvSpPr().getCNvPr().getId());
        endpoints.getEndCxn().setIdx(1);
        line.getCTConnector().getSpPr().getLn().addNewTailEnd().setType(STLineEndType.TRIANGLE);
    }

    private static XSSFCellStyle cellStyle(XSSFWorkbook book, int points, boolean bold, Color color, Color fill) {
        var font = book.createFont();
        font.setFontName(FONT);
        font.setFontHeightInPoints((short) points);
        font.setBold(bold);
        font.setColor(new XSSFColor(color, null));
        var style = book.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(fill, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static void mergedText(XSSFSheet sheet, int row1, int row2, int col1, int col2,
                                   String text, XSSFCellStyle style) {
        for (int row = row1; row <= row2; row++) {
            for (int column = col1; column <= col2; column++) {
                var cell = sheet.getRow(row).createCell(column);
                cell.setCellStyle(style);
            }
        }
        sheet.getRow(row1).getCell(col1).setCellValue(text);
        sheet.addMergedRegion(new CellRangeAddress(row1, row2, col1, col2));
    }

    private static Font font(boolean bold, float size) throws Exception {
        String path = "/fonts/noto/NotoSansCJKjp-" + (bold ? "Bold" : "Regular") + ".otf";
        try (var stream = ExcelAnnotatedImages.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Bundled font is missing: " + path);
            return Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(size);
        }
    }

    private static byte[] screen() throws Exception {
        var image = new BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(new Color(0xF0F4F8)); graphics.fillRect(0, 0, 960, 600);
            graphics.setColor(NAVY); graphics.fillRect(0, 0, 960, 80);
            graphics.setFont(font(true, 30)); graphics.setColor(Color.WHITE);
            graphics.drawString("申請一覧 / 検証用デモ", 36, 52);
            graphics.setFont(font(false, 25)); graphics.setColor(NAVY);
            graphics.drawString("受付番号 DEMO-204", 40, 133);
            graphics.setColor(Color.WHITE); graphics.fillRoundRect(36, 166, 888, 230, 14, 14);
            String[][] values = {{"対象部署", "架空営業所"}, {"申請内容", "備品購入"}, {"金額", "48,000 円"}};
            for (int index = 0; index < values.length; index++) {
                int y = 218 + index * 65;
                graphics.setFont(font(false, 25)); graphics.setColor(new Color(0x52667A));
                graphics.drawString(values[index][0], 65, y);
                graphics.setFont(font(true, 26)); graphics.setColor(NAVY);
                graphics.drawString(values[index][1], 280, y);
            }
            graphics.setColor(new Color(0xE2F2EC)); graphics.fillRoundRect(36, 432, 570, 110, 14, 14);
            graphics.setFont(font(true, 27)); graphics.setColor(TEAL);
            graphics.drawString("状態：確認待ち", 63, 496);
            graphics.setColor(BLUE); graphics.fillRoundRect(650, 455, 274, 66, 12, 12);
            graphics.setColor(Color.WHITE); graphics.setFont(font(true, 25));
            graphics.drawString("申請を送信", 718, 498);
            graphics.setFont(font(false, 16)); graphics.setColor(new Color(0x607386));
            graphics.drawString("この画面・数値・名称はすべて架空です。", 40, 578);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
