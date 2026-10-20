import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Generates only fictional XLSX input fixtures. Images are deterministic Java2D test graphics. */
public class CreateAdditionalRenderSamples {
    private static final Color INK = new Color(27, 47, 65);
    private static final Color NAVY = new Color(22, 53, 78);
    private static final Color BLUE = new Color(38, 115, 164);
    private static final Color TEAL = new Color(26, 131, 123);
    private static final Color PALE = new Color(235, 244, 249);
    private static final Color RULE = new Color(194, 208, 216);
    private static final Font IMAGE_REGULAR = font("BIZUDPGothic-Regular.ttf");
    private static final Font IMAGE_BOLD = font("BIZUDPGothic-Bold.ttf");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the inputs output directory");
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        wideTable(output.resolve("wide-table.xlsx"));
        embeddedImages(output.resolve("embedded-images.xlsx"));
        wideWithImages(output.resolve("wide-with-images.xlsx"));
    }

    private static final class Styles {
        final CellStyle title, section, header, body, alternate, number, alternateNumber, note, label;
        Styles(XSSFWorkbook book) {
            title = style(book, 20, true, NAVY, Color.WHITE, false);
            section = style(book, 12, true, BLUE, Color.WHITE, false);
            header = style(book, 11, true, new Color(215, 234, 245), INK, true);
            body = style(book, 11, false, Color.WHITE, INK, true);
            alternate = style(book, 11, false, new Color(245, 249, 251), INK, true);
            number = style(book, 11, false, Color.WHITE, INK, true);
            alternateNumber = style(book, 11, false, new Color(245, 249, 251), INK, true);
            number.setAlignment(HorizontalAlignment.RIGHT); alternateNumber.setAlignment(HorizontalAlignment.RIGHT);
            note = style(book, 11, false, PALE, INK, false);
            label = style(book, 11, true, new Color(227, 241, 236), INK, false);
        }
    }

    private static void wideTable(Path file) throws Exception {
        try (var book = new XSSFWorkbook()) {
            Styles styles = new Styles(book);
            var sheet = sheet(book, "24列の横長一覧", 24, 29, 14, 24);
            for (int c = 0; c < 3; c++) sheet.setColumnWidth(c, 16 * 256);
            sheet.getRow(0).setHeightInPoints(25); sheet.getRow(1).setHeightInPoints(25);
            sheet.getRow(2).setHeightInPoints(28); sheet.getRow(3).setHeightInPoints(26);
            sheet.getRow(4).setHeightInPoints(28); sheet.getRow(25).setHeightInPoints(12);
            for (int r = 26; r < 29; r++) sheet.getRow(r).setHeightInPoints(22);
            merge(sheet, 0, 1, 0, 23, "地域別の月次処理一覧 / 24列を横幅に収めるサンプル", styles.title);
            merge(sheet, 2, 2, 0, 23, "架空の検証データ  |  20拠点 × 7か月  |  各セル11pt以上  |  横方向の分割なし", styles.note);
            merge(sheet, 3, 3, 0, 2, "基本情報", styles.section);
            String[] months = {"04月", "05月", "06月", "07月", "08月", "09月", "10月"};
            for (int m = 0; m < months.length; m++) merge(sheet, 3, 3, 3 + m * 3, 5 + m * 3, months[m], styles.section);
            String[] labels = {"拠点ID", "拠点", "担当"};
            for (int c = 0; c < 3; c++) put(sheet, 4, c, labels[c], styles.header);
            for (int c = 3; c < 24; c++) put(sheet, 4, c, new String[]{"受付", "処理", "完了"}[(c - 3) % 3], styles.header);
            for (int r = 5; r < 25; r++) {
                boolean odd = (r & 1) == 1;
                CellStyle text = odd ? styles.body : styles.alternate;
                CellStyle num = odd ? styles.number : styles.alternateNumber;
                put(sheet, r, 0, String.format("SITE-%02d", r - 4), text);
                put(sheet, r, 1, "モデル拠点" + (r - 4), text);
                put(sheet, r, 2, "担当" + (char) ('A' + (r - 5) % 6), text);
                for (int c = 3; c < 24; c++) number(sheet, r, c, 110 + (r - 5) * 7 + (c / 3) * 13 - (c % 3) * 4, num);
            }
            merge(sheet, 26, 28, 0, 23, "確認ポイント：月ごとの結合見出し、交互の行色、罫線、右寄せの数値を同じ横長ページで確認できます。\n"
                    + "次の「48列・可読性不足」シートは意図的に広く、既定の最小文字サイズを満たさずスキップされる比較用です。", styles.note);
            book.setPrintArea(0, 0, 23, 0, 28);
            oversize(book, styles, "48列・可読性不足", 48, 14, false);
            save(book, file);
            describe(sheet, "A1:X29", 0);
            describe(book.getSheetAt(1), "A1:AV29", 0);
        }
    }

    private static void embeddedImages(Path file) throws Exception {
        try (var book = new XSSFWorkbook()) {
            Styles styles = new Styles(book);
            var sheet = sheet(book, "画像3種と記録", 15, 38, 11, 24);
            merge(sheet, 0, 1, 0, 14, "検証記録 / 3種類の貼り付け画像", styles.title);
            merge(sheet, 2, 2, 0, 14, "操作画面・現場の合成模式図・グラフをセルの記録と一緒に配置した架空資料です。", styles.note);
            merge(sheet, 4, 4, 0, 6, "① 操作画面 / PNG", styles.section);
            merge(sheet, 4, 4, 8, 14, "② 現場イメージ / 合成模式図", styles.section);
            var drawing = sheet.createDrawingPatriarch();
            picture(book, drawing, screen(1000, 650), 0, 5, 7, 16);
            XSSFPicture scene = picture(book, drawing, facility(1000, 650), 8, 5, 15, 16);
            var crop = scene.getCTPicture().getBlipFill().addNewSrcRect();
            crop.setL(5000); crop.setR(5000); crop.setT(3000); crop.setB(3000);
            pair(sheet, 17, 0, 2, 3, 6, "記録番号", "UI-204", styles);
            pair(sheet, 18, 0, 2, 3, 6, "判定", "確認済み", styles);
            merge(sheet, 19, 20, 0, 6, "受付番号・状態・実行ボタンを画像で確認。\n画面の内容はすべて架空です。", styles.note);
            pair(sheet, 17, 8, 10, 11, 14, "設備番号", "RACK-B", styles);
            pair(sheet, 18, 8, 10, 11, 14, "判定", "異常なし", styles);
            merge(sheet, 19, 20, 8, 14, "合成画像をアンカーに合わせて配置。\n周囲を左右5%・上下3%トリミング。", styles.note);
            merge(sheet, 22, 22, 0, 9, "③ 月次処理件数 / グラフ画像", styles.section);
            merge(sheet, 22, 22, 11, 14, "画像の隣のセル", styles.section);
            picture(book, drawing, chart(1100, 500), 0, 23, 10, 34);
            pair(sheet, 24, 11, 12, 13, 14, "対象", "架空7拠点", styles);
            pair(sheet, 26, 11, 12, 13, 14, "集計", "7か月", styles);
            pair(sheet, 28, 11, 12, 13, 14, "単位", "件", styles);
            merge(sheet, 30, 33, 11, 14, "棒と折れ線の位置、色、ラベルを画像のまま比較します。", styles.note);
            merge(sheet, 36, 37, 0, 14, "画像は外部から取得せず、この生成スクリプトが作成したテスト用PNGです。\n"
                    + "このシートには印刷範囲を設定せず、セルと画像の使用範囲が自動選択されることも確認します。", styles.note);
            save(book, file);
            describe(sheet, "A1:O38", 3);
        }
    }

    private static void wideWithImages(Path file) throws Exception {
        try (var book = new XSSFWorkbook()) {
            Styles styles = new Styles(book);
            var sheet = sheet(book, "横長一覧と画像", 27, 38, 12, 24);
            merge(sheet, 0, 1, 0, 26, "月次確認資料 / 横長一覧と画面・グラフ", styles.title);
            merge(sheet, 2, 2, 0, 26, "左：16列の明細  |  右：2枚のPNG  |  下：確認メモ  |  全体27列・11pt以上", styles.note);
            merge(sheet, 4, 4, 0, 2, "基本情報", styles.section);
            merge(sheet, 4, 4, 3, 6, "受付状況", styles.section);
            merge(sheet, 4, 4, 7, 10, "処理状況", styles.section);
            merge(sheet, 4, 4, 11, 15, "確認事項", styles.section);
            String[] columns = {"ID", "拠点", "担当", "受付", "保留", "新規", "再開", "処理", "完了", "差戻", "期限", "品質", "安全", "添付", "確認", "状態"};
            for (int c = 0; c < columns.length; c++) put(sheet, 5, c, columns[c], styles.header);
            for (int r = 6; r < 26; r++) {
                CellStyle text = (r & 1) == 0 ? styles.body : styles.alternate;
                CellStyle num = (r & 1) == 0 ? styles.number : styles.alternateNumber;
                put(sheet, r, 0, String.format("W-%02d", r - 5), text);
                put(sheet, r, 1, "拠点" + (r - 5), text);
                put(sheet, r, 2, "担当" + (char) ('A' + r % 5), text);
                for (int c = 3; c < 10; c++) number(sheet, r, c, 20 + (r * 3 + c * 7) % 80, num);
                put(sheet, r, 10, "10/31", text);
                for (int c = 11; c < 15; c++) put(sheet, r, c, c == 13 ? "2枚" : "適合", text);
                put(sheet, r, 15, r % 5 == 0 ? "確認待" : "完了", text);
            }
            merge(sheet, 4, 4, 17, 26, "貼り付け画像① / 確認画面", styles.section);
            merge(sheet, 21, 21, 17, 26, "貼り付け画像② / 月次推移", styles.section);
            var drawing = sheet.createDrawingPatriarch();
            picture(book, drawing, screen(1200, 640), 17, 5, 27, 19);
            picture(book, drawing, chart(1100, 500), 17, 22, 27, 34);
            merge(sheet, 19, 20, 17, 26, "画面と左の明細を照合：受付番号 DEMO-204 / 状態 承認待ち", styles.note);
            merge(sheet, 28, 28, 0, 15, "確認メモ", styles.section);
            merge(sheet, 29, 33, 0, 15, "・画像は表の右側のセル座標に固定しています。\n"
                    + "・図や文字を読むため、横方向は分割せず可読性の条件内で縮小します。\n"
                    + "・数値と画面は架空の内容です。業務データや実在システムの画面は含みません。", styles.note);
            merge(sheet, 36, 37, 0, 26, "このシートは約1,700ptの横幅で既定の描画条件に収まる想定です。\n"
                    + "次の52列シートは幅が大きすぎるためスキップされ、変換結果のreport.jsonに理由が記録される比較用です。", styles.note);
            book.setPrintArea(0, 0, 26, 0, 37);
            oversize(book, styles, "52列・可読性不足", 52, 12, true);
            save(book, file);
            describe(sheet, "A1:AA38", 2);
            describe(book.getSheetAt(1), "A1:AZ29", 1);
        }
    }

    private static void oversize(XSSFWorkbook book, Styles styles, String name, int columns, int width, boolean image) throws Exception {
        var sheet = sheet(book, name, columns, 29, width, 24);
        merge(sheet, 0, 1, 0, columns - 1, "比較用 / " + columns + "列は横幅を縮小すると11ptの文字が読みにくくなる", styles.title);
        merge(sheet, 2, 2, 0, columns - 1, "このシートは既定設定で WIDTH_UNREADABLE としてスキップされる想定です。", styles.note);
        for (int c = 0; c < columns; c++) {
            put(sheet, 4, c, c == 0 ? "項目" : "列" + (c + 1), styles.header);
            for (int r = 5; r < 25; r++) put(sheet, r, c, c == 0 ? "検査" + (r - 4) : Integer.toString(100 + r * 2 + c),
                    (r & 1) == 0 ? styles.body : styles.alternate);
        }
        if (image) picture(book, sheet.createDrawingPatriarch(), screen(1200, 640), columns - 10, 6, columns, 20);
        merge(sheet, 26, 28, 0, columns - 1, "確認ポイント：無理に小さな画像にせず、この範囲をスキップして他の読めるシートは出力します。", styles.note);
        book.setPrintArea(book.getSheetIndex(sheet), 0, columns - 1, 0, 28);
    }

    private static XSSFSheet sheet(XSSFWorkbook book, String name, int columns, int rows, int width, int height) {
        XSSFSheet sheet = book.createSheet(name); sheet.setDisplayGridlines(false);
        for (int c = 0; c < columns; c++) sheet.setColumnWidth(c, width * 256);
        for (int r = 0; r < rows; r++) sheet.createRow(r).setHeightInPoints(height);
        return sheet;
    }
    private static CellStyle style(XSSFWorkbook book, int points, boolean bold, Color fill, Color ink, boolean border) {
        var style = book.createCellStyle(); var font = book.createFont();
        font.setFontName("BIZ UDPGothic"); font.setFontHeightInPoints((short) points); font.setBold(bold);
        font.setColor(new XSSFColor(ink, null)); style.setFont(font);
        style.setWrapText(true); style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(new XSSFColor(fill, null)); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        if (border) {
            style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
            var color = new XSSFColor(RULE, null);
            style.setTopBorderColor(color); style.setBottomBorderColor(color); style.setLeftBorderColor(color); style.setRightBorderColor(color);
        }
        return style;
    }
    private static void put(XSSFSheet sheet, int row, int column, String value, CellStyle style) {
        var cell = sheet.getRow(row).createCell(column); cell.setCellValue(value); cell.setCellStyle(style);
    }
    private static void number(XSSFSheet sheet, int row, int column, int value, CellStyle style) {
        var cell = sheet.getRow(row).createCell(column); cell.setCellValue(value); cell.setCellStyle(style);
    }
    private static void merge(XSSFSheet sheet, int r1, int r2, int c1, int c2, String value, CellStyle style) {
        for (int r = r1; r <= r2; r++) for (int c = c1; c <= c2; c++) put(sheet, r, c, "", style);
        if (r1 != r2 || c1 != c2) sheet.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
        sheet.getRow(r1).getCell(c1).setCellValue(value);
    }
    private static void pair(XSSFSheet sheet, int row, int labelStart, int labelEnd, int valueStart, int valueEnd,
                             String label, String value, Styles styles) {
        merge(sheet, row, row, labelStart, labelEnd, label, styles.label);
        merge(sheet, row, row, valueStart, valueEnd, value, styles.note);
    }
    private static XSSFPicture picture(XSSFWorkbook book, org.apache.poi.xssf.usermodel.XSSFDrawing drawing,
                                       byte[] bytes, int c1, int r1, int c2, int r2) {
        var anchor = new XSSFClientAnchor(0, 0, 0, 0, c1, r1, c2, r2);
        return drawing.createPicture(anchor, book.addPicture(bytes, Workbook.PICTURE_TYPE_PNG));
    }
    private static void save(XSSFWorkbook book, Path file) throws Exception {
        try (var output = Files.newOutputStream(file)) { book.write(output); }
        System.out.println(file + " (" + Files.size(file) + " bytes)");
    }
    private static void describe(XSSFSheet sheet, String range, int pictures) {
        CellRangeAddress a = CellRangeAddress.valueOf(range);
        double width = 0, height = 0;
        for (int c = a.getFirstColumn(); c <= a.getLastColumn(); c++) width += sheet.getColumnWidthInPixels(c) * .75;
        for (int r = a.getFirstRow(); r <= a.getLastRow(); r++) height += sheet.getRow(r).getHeightInPoints();
        double scale = Math.min(2, 2048 / width);
        System.out.printf(java.util.Locale.ROOT, "  %s: %s, %.3f x %.3f pt, %d PNGs, scale %.6f, min text %.3f px%n",
                sheet.getSheetName(), range, width, height, pictures, scale, 11 * scale);
    }

    private static Font font(String name) {
        try (var input = CreateAdditionalRenderSamples.class.getClassLoader().getResourceAsStream("fonts/bizud/" + name)) {
            if (input == null) throw new IllegalStateException("Bundled BIZ font resource is required");
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (Exception failure) { throw new IllegalStateException("Could not load bundled font", failure); }
    }
    private static BufferedImage canvas(int width, int height) { return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); }
    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(image.getWidth() / 1000.0, image.getHeight() / 600.0);
        return g;
    }
    private static void label(Graphics2D g, String text, int x, int y, int size, boolean bold, Color color) {
        g.setFont((bold ? IMAGE_BOLD : IMAGE_REGULAR).deriveFont((float) size)); g.setColor(color); g.drawString(text, x, y);
    }
    private static byte[] png(BufferedImage image) throws Exception {
        try (var output = new ByteArrayOutputStream()) { ImageIO.write(image, "png", output); return output.toByteArray(); }
        finally { image.flush(); }
    }
    private static byte[] screen(int width, int height) throws Exception {
        BufferedImage image = canvas(width, height); Graphics2D g = graphics(image);
        g.setColor(new Color(239, 245, 249)); g.fillRect(0, 0, 1000, 600);
        g.setColor(NAVY); g.fillRect(0, 0, 1000, 76);
        label(g, "受付確認 / 架空の検証画面", 30, 49, 30, true, Color.WHITE);
        g.setColor(new Color(221, 232, 240)); g.fillRect(0, 76, 200, 524);
        label(g, "メニュー", 24, 124, 25, true, INK);
        label(g, "受付一覧", 24, 181, 24, false, INK); label(g, "画像確認", 24, 235, 24, false, INK);
        g.setColor(Color.WHITE); g.fillRoundRect(224, 104, 744, 440, 14, 14);
        label(g, "受付情報", 252, 151, 29, true, INK);
        String[] names = {"受付番号", "対象拠点", "状態"}; String[] values = {"DEMO-204", "モデル拠点 07", "承認待ち"};
        for (int i = 0; i < 3; i++) {
            int y = 187 + i * 76;
            label(g, names[i], 252, y + 33, 25, false, INK);
            g.setColor(new Color(245, 248, 251)); g.fillRoundRect(428, y, 492, 55, 8, 8);
            g.setColor(RULE); g.drawRoundRect(428, y, 492, 55, 8, 8);
            label(g, values[i], 448, y + 35, 27, i == 2, i == 2 ? new Color(159, 102, 20) : INK);
        }
        g.setColor(TEAL); g.fillRoundRect(658, 447, 263, 62, 12, 12);
        label(g, "内容を確認する", 685, 488, 27, true, Color.WHITE);
        label(g, "TEST DATA — 実在の業務システムではありません", 228, 579, 23, false, new Color(80, 101, 115));
        g.dispose(); return png(image);
    }
    private static byte[] facility(int width, int height) throws Exception {
        BufferedImage image = canvas(width, height); Graphics2D g = graphics(image);
        g.setPaint(new GradientPaint(0, 0, new Color(216, 232, 241), 0, 410, new Color(175, 201, 214)));
        g.fillRect(0, 0, 1000, 600);
        g.setColor(new Color(113, 136, 148)); g.fillRect(0, 430, 1000, 170);
        g.setColor(new Color(160, 180, 191));
        for (int x = -500; x < 1500; x += 170) g.drawLine(500, 360, x, 600);
        for (int y = 455; y < 600; y += 44) g.drawLine(0, y, 1000, y);
        g.setColor(new Color(241, 247, 250)); g.fillRoundRect(75, 65, 850, 330, 18, 18);
        label(g, "MODEL FACILITY / 合成模式図", 105, 112, 29, true, INK);
        for (int i = 0; i < 3; i++) {
            int x = 120 + i * 254;
            g.setColor(new Color(35, 61, 76)); g.fillRoundRect(x + 8, 152, 194, 304, 12, 12);
            g.setPaint(new GradientPaint(x, 0, new Color(56, 86, 104), x + 190, 0, new Color(30, 51, 65)));
            g.fillRoundRect(x, 140, 194, 304, 12, 12);
            for (int j = 0; j < 5; j++) {
                g.setColor(new Color(74, 106, 124)); g.fillRoundRect(x + 16, 163 + j * 47, 160, 34, 4, 4);
                g.setColor(j == 3 && i == 1 ? new Color(255, 197, 70) : new Color(107, 222, 164));
                g.fillOval(x + 145, 175 + j * 47, 10, 10);
            }
            label(g, "RACK-" + (char) ('A' + i), x + 27, 428, 23, true, Color.WHITE);
        }
        g.setColor(new Color(244, 250, 248)); g.fillRoundRect(170, 496, 660, 61, 16, 16);
        label(g, "点検結果：異常なし / すべて架空の設備", 193, 536, 25, true, TEAL);
        g.dispose(); return png(image);
    }
    private static byte[] chart(int width, int height) throws Exception {
        BufferedImage image = canvas(width, height); Graphics2D g = graphics(image);
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1000, 600);
        label(g, "月次受付と完了件数 / テスト用グラフ", 35, 53, 30, true, INK);
        label(g, "架空データ・単位：件", 35, 91, 23, false, new Color(90, 111, 125));
        int[] a = {108, 124, 142, 136, 160, 171, 188}; int[] b = {96, 117, 132, 130, 150, 163, 179};
        g.setStroke(new BasicStroke(1));
        for (int tick = 0; tick <= 200; tick += 50) {
            int y = 485 - tick * 340 / 200;
            g.setColor(new Color(220, 230, 236)); g.drawLine(90, y, 940, y);
            label(g, Integer.toString(tick), 36, y + 8, 21, false, new Color(87, 109, 124));
        }
        var line = new Path2D.Double();
        for (int i = 0; i < 7; i++) {
            int x = 116 + i * 116; int y = 485 - a[i] * 340 / 200;
            g.setColor(BLUE); g.fillRoundRect(x, y, 64, 485 - y, 6, 6);
            label(g, Integer.toString(a[i]), x + 3, y - 13, 22, true, BLUE);
            label(g, String.format("%02d月", i + 4), x - 4, 524, 24, false, INK);
            double ly = 485 - b[i] * 340.0 / 200;
            if (i == 0) line.moveTo(x + 32, ly); else line.lineTo(x + 32, ly);
        }
        g.setColor(new Color(233, 146, 45)); g.setStroke(new BasicStroke(5)); g.draw(line);
        for (int i = 0; i < 7; i++) g.fillOval(142 + i * 116, 479 - b[i] * 340 / 200, 12, 12);
        g.setColor(BLUE); g.fillRect(330, 560, 30, 17); label(g, "受付", 372, 577, 23, false, INK);
        g.setColor(new Color(233, 146, 45)); g.fillRect(512, 567, 32, 5); label(g, "完了", 556, 577, 23, false, INK);
        g.dispose(); return png(image);
    }
}
