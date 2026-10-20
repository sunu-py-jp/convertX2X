import com.convertx2x.excel2img.conversion.ConversionException;
import com.convertx2x.excel2img.conversion.ConversionLimits;
import com.convertx2x.excel2img.conversion.ExcelImageService;
import com.convertx2x.excel2img.conversion.RenderOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

/** Synthetic, public sample. No business data, external images or formula recalculation. */
public class CreateRenderDemo {
    private static XSSFWorkbook book;
    private static CellStyle body, header, title, note;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the output directory");
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            book = workbook;
            body = style(11, false, null);
            header = style(11, true, new Color(223, 237, 247));
            title = style(20, true, new Color(20, 54, 80));
            ((XSSFFont) book.getFontAt(title.getFontIndex())).setColor(new XSSFColor(Color.WHITE, null));
            note = style(10, false, new Color(244, 247, 250));
            processSheet();
            printAreasSheet();
            longSheet();
            wideSheet();
            try (var stream = Files.newOutputStream(output.resolve("excel-layout-demo.xlsx"))) {
                book.write(stream);
            }
        }
        byte[] input = Files.readAllBytes(output.resolve("excel-layout-demo.xlsx"));
        var service = new ExcelImageService(ConversionLimits.defaults());
        var runs = new LinkedHashMap<String, Object>();
        for (String format : new String[] {"png", "pdf"}) {
            long started = System.nanoTime();
            try (var result = service.convert(input, "excel-layout-demo.xlsx", new RenderOptions(format, null, null))) {
                Path destination = output.resolve(format);
                Files.createDirectories(destination);
                for (var file : result.files().entrySet()) {
                    Path target = destination.resolve(file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.copy(file.getValue(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.write(output.resolve("excel-layout-" + format + ".zip"), result.zipBytes());
                runs.put(format, java.util.Map.of("pages", result.pageCount(), "warnings", result.warningCount(),
                        "elapsedMs", (System.nanoTime() - started) / 1_000_000));
            }
        }
        try (var unexpected = service.convert(input, "excel-layout-demo.xlsx",
                new RenderOptions("png", "横長マトリックス", null))) {
            throw new IllegalStateException("Wide matrix unexpectedly rendered");
        } catch (ConversionException expected) {
            if (!expected.code().equals("NO_RENDERABLE_RANGE")) throw expected;
            var error = new LinkedHashMap<String, Object>();
            error.put("code", expected.code());
            error.put("message", expected.getMessage());
            error.put("details", expected.details());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("wide-error.json").toFile(), error);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("run.json").toFile(), runs);
        System.out.println(new ObjectMapper().writeValueAsString(runs));
    }

    private static void processSheet() throws Exception {
        var sheet = sheet("画像付き業務フロー", 12, 26, 10, 24);
        merge(sheet, 0, 1, 0, 11, "受注受付 — 画面と業務フロー", title);
        merge(sheet, 3, 3, 0, 3, "受付一覧", header);
        String[][] rows = {
            {"ID", "担当", "状態", "件数"},
            {"R-101", "東京", "確認中", "12"},
            {"R-102", "大阪", "承認済", "8"},
            {"R-103", "福岡", "差戻し", "3"},
            {"R-104", "札幌", "確認中", "6"}
        };
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length; c++) {
                Cell cell = put(sheet, r + 4, c, rows[r][c], r == 0 ? header : body);
                CellStyle bordered = book.createCellStyle();
                bordered.cloneStyleFrom(cell.getCellStyle());
                bordered.setBorderBottom(BorderStyle.THIN);
                bordered.setBorderTop(BorderStyle.THIN);
                bordered.setBorderLeft(BorderStyle.THIN);
                bordered.setBorderRight(BorderStyle.THIN);
                cell.setCellStyle(bordered);
            }
        merge(sheet, 10, 12, 0, 3, "差戻し時は入力担当へ連絡。\n画面の赤い項目を修正します。", note);
        merge(sheet, 3, 3, 5, 11, "貼り付け画像：受注入力画面", header);
        var drawing = sheet.createDrawingPatriarch();
        drawing.createPicture(anchor(5, 6, 12, 14), book.addPicture(screenImage(), Workbook.PICTURE_TYPE_PNG));
        merge(sheet, 14, 14, 0, 11, "処理フロー（Excelの図形と矢印）", header);
        shape(drawing, 0, 17, 3, 20, ShapeTypes.ROUND_RECT, "受付\n入力担当", new Color(224, 240, 254));
        shape(drawing, 3, 18, 4, 19, ShapeTypes.RIGHT_ARROW, "", new Color(45, 105, 160));
        shape(drawing, 4, 16, 7, 21, ShapeTypes.DIAMOND, "承認\n確認", new Color(255, 238, 194));
        shape(drawing, 7, 18, 8, 19, ShapeTypes.RIGHT_ARROW, "", new Color(45, 105, 160));
        shape(drawing, 8, 17, 12, 20, ShapeTypes.ROUND_RECT, "登録完了\n後続処理へ", new Color(216, 241, 225));
        merge(sheet, 22, 24, 0, 11,
                "入力担当は一覧と画面を照合します。承認後、登録処理へ進みます。\n"
                + "セル・画像・図形を同じページ上で参照できるようにした架空の資料です。", note);
        book.setPrintArea(book.getSheetIndex(sheet), 0, 11, 0, 25);
    }

    private static void printAreasSheet() {
        var sheet = sheet("印刷範囲2つ", 6, 29, 13, 24);
        for (int start : new int[] {0, 17}) {
            merge(sheet, start, start + 1, 0, 5, start == 0 ? "月次集計 / 東地区" : "月次集計 / 西地区", title);
            String[] labels = {"拠点", "担当", "1月", "2月", "3月", "備考"};
            for (int c = 0; c < labels.length; c++) put(sheet, start + 3, c, labels[c], header);
            for (int r = 4; r < 10; r++) {
                put(sheet, start + r, 0, "拠点" + (r - 3), body);
                put(sheet, start + r, 1, "担当" + (r - 3), body);
                for (int c = 2; c < 5; c++) put(sheet, start + r, c, Integer.toString(r * 7 + c), body);
                put(sheet, start + r, 5, r == 5 ? "要確認" : "完了", note);
            }
        }
        book.setPrintArea(book.getSheetIndex(sheet), "$A$1:$F$12,$A$18:$F$29");
    }

    private static void longSheet() {
        var sheet = sheet("縦長一覧", 6, 125, 13, 20);
        sheet.setColumnWidth(0, 16 * 256);
        sheet.setColumnWidth(1, 17 * 256);
        sheet.setColumnWidth(4, 17 * 256);
        merge(sheet, 0, 1, 0, 5, "検査項目 / 縦方向の継続", title);
        String[] labels = {"番号", "対象", "担当", "状態", "日付", "注記"};
        for (int c = 0; c < labels.length; c++) put(sheet, 3, c, labels[c], header);
        for (int r = 4; r < 125; r++) {
            put(sheet, r, 0, String.format("CHK-%03d", r - 3), body);
            put(sheet, r, 1, "検査項目" + (r - 3), body);
            put(sheet, r, 2, "担当A", body);
            put(sheet, r, 3, r % 7 == 0 ? "確認待ち" : "完了", body);
            put(sheet, r, 4, "2026/10/20", body);
            put(sheet, r, 5, r % 7 == 0 ? "再確認" : "", note);
        }
    }

    private static void wideSheet() {
        var sheet = sheet("横長マトリックス", 42, 12, 12, 24);
        for (int c = 0; c < 42; c++) {
            put(sheet, 0, c, c == 0 ? "管理項目" : "機能" + c, header);
            for (int r = 1; r < 12; r++) put(sheet, r, c, c == 0 ? "項目" + r : "対応", body);
        }
    }

    private static XSSFSheet sheet(String name, int cols, int rows, int width, float height) {
        XSSFSheet sheet = book.createSheet(name);
        sheet.setDisplayGridlines(false);
        for (int c = 0; c < cols; c++) sheet.setColumnWidth(c, width * 256);
        for (int r = 0; r < rows; r++) sheet.createRow(r).setHeightInPoints(height);
        return sheet;
    }
    private static CellStyle style(int size, boolean bold, Color fill) {
        XSSFCellStyle style = book.createCellStyle();
        XSSFFont font = book.createFont();
        font.setFontName("BIZ UDPGothic"); font.setFontHeightInPoints((short) size); font.setBold(bold);
        style.setFont(font); style.setWrapText(true); style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (fill != null) { style.setFillForegroundColor(new XSSFColor(fill, null)); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); }
        return style;
    }
    private static Cell put(XSSFSheet sheet, int row, int col, String text, CellStyle style) {
        Cell cell = sheet.getRow(row).createCell(col); cell.setCellValue(text); cell.setCellStyle(style); return cell;
    }
    private static void merge(XSSFSheet sheet, int r1, int r2, int c1, int c2, String text, CellStyle style) {
        for (int r = r1; r <= r2; r++) for (int c = c1; c <= c2; c++) put(sheet, r, c, "", style);
        sheet.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
        sheet.getRow(r1).getCell(c1).setCellValue(text);
    }
    private static XSSFClientAnchor anchor(int c1, int r1, int c2, int r2) {
        return new XSSFClientAnchor(0, 0, 0, 0, c1, r1, c2, r2);
    }
    private static void shape(XSSFDrawing drawing, int c1, int r1, int c2, int r2, int type, String text, Color fill) {
        var shape = drawing.createSimpleShape(anchor(c1, r1, c2, r2));
        shape.setShapeType(type);
        shape.setFillColor(fill.getRed(), fill.getGreen(), fill.getBlue());
        shape.setLineStyleColor(52, 89, 116); shape.setLineWidth(1.2); shape.setText(text);
        shape.setVerticalAlignment(VerticalAlignment.CENTER);
        for (var p : shape.getTextParagraphs()) {
            p.setTextAlign(org.apache.poi.xssf.usermodel.TextAlign.CENTER);
            for (var run : p.getTextRuns()) { run.setFontSize(13); run.setFontFamily("BIZ UDPGothic", (byte) 0, (byte) 0, false); run.setFontColor(Color.BLACK); }
        }
    }
    private static byte[] screenImage() throws Exception {
        var image = new BufferedImage(720, 360, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        java.awt.Font font;
        try (var stream = CreateRenderDemo.class.getClassLoader().getResourceAsStream("fonts/bizud/BIZUDPGothic-Regular.ttf")) {
            font = stream == null ? new java.awt.Font("SansSerif", 0, 24) : java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, stream);
        }
        g.setColor(new Color(240, 245, 249)); g.fillRect(0, 0, 720, 360);
        g.setColor(new Color(24, 57, 83)); g.fillRect(0, 0, 720, 58);
        g.setFont(font.deriveFont(java.awt.Font.BOLD, 26)); g.setColor(Color.WHITE); g.drawString("受注入力システム", 26, 38);
        g.setFont(font.deriveFont(24f));
        String[] labels = {"受付番号", "取引先名", "納品希望日"};
        String[] values = {"R-103", "サンプル商事", "2026/11/01"};
        for (int i = 0; i < 3; i++) {
            int y = 82 + i * 64;
            g.setColor(new Color(45, 61, 73)); g.drawString(labels[i], 30, y + 32);
            g.setColor(Color.WHITE); g.fillRoundRect(230, y, 450, 48, 8, 8);
            g.setColor(new Color(175, 192, 204)); g.drawRoundRect(230, y, 450, 48, 8, 8);
            g.setColor(new Color(30, 49, 66)); g.drawString(values[i], 248, y + 32);
        }
        g.setColor(new Color(180, 45, 45)); g.drawString("入力内容を確認して登録してください", 30, 320);
        g.dispose();
        try (var bytes = new ByteArrayOutputStream()) { ImageIO.write(image, "png", bytes); return bytes.toByteArray(); }
    }
}
