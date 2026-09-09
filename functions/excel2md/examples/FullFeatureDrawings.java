import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;
import org.openxmlformats.schemas.drawingml.x2006.main.STTextVerticalType;

/** Original, editable drawing fixtures; no downloaded images or rendered screenshots as source. */
public final class FullFeatureDrawings {
    private static final Color NAVY = new Color(20, 41, 65);
    private static final Color TEAL = new Color(0, 130, 140);
    private static final Color PALE = new Color(231, 246, 245);
    private static final Color BLUE = new Color(233, 241, 251);
    private static final int EMU = 12700;
    private static final String SANS = "Noto Sans CJK JP";
    private static final String SERIF = "Noto Serif CJK JP";
    private static Font regularFont, boldFont;

    private FullFeatureDrawings() { }

    public static void populate(XSSFWorkbook book) throws Exception {
        ensureTheme(book);
        int png = book.addPicture(originalImage("PNG", "日本語のオリジナル画像", "png", false), Workbook.PICTURE_TYPE_PNG);
        int jpeg = book.addPicture(originalImage("JPEG", "写真形式も元データで保存", "jpg", false), Workbook.PICTURE_TYPE_JPEG);
        int hidden = book.addPicture(originalImage("HIDDEN", "非表示の画像", "png", true), Workbook.PICTURE_TYPE_PNG);
        int cropped = book.addPicture(originalImage("CROP", "切り抜き前の原本画像", "png", false), Workbook.PICTURE_TYPE_PNG);
        int bitmap = book.addPicture(originalImage("BMP", "元ファイルの添付として保存", "bmp", false), Workbook.PICTURE_TYPE_DIB);
        images(book, png, jpeg, hidden);
        basicShapes(book);
        groupsAndConnections(book, png);
        unsupportedAndEffects(book, cropped, bitmap);
        imageOnly(book, png);
    }

    /** Case identifiers are intentionally independent of generated asset numbering. */
    public static List<String> coverage() {
        return List.of(
                "IMG01: 04_画像!A4:E10 PNG原本", "IMG02: 04_画像!G4:K10 JPEG原本",
                "IMG03: 04_画像!A14:E20,G14:K20 同じPNGの複数配置・成果物共有",
                "IMG04: 04_画像!A24:E29の罫線表 / B26:D29に重なるPNGは表直後",
                "IMG05: 04_画像!A35:E41 明示的非表示画像の除外",
                "IMG06: 04_画像!G35:K41 左上行非表示の画像除外",
                "IMG07: 04_画像!M4:Q10 左上列非表示の画像除外",
                "SHP01: 05_基本図形!A4:E10,G4:K10,M4:Q10 四角・角丸・楕円",
                "SHP02: 05_基本図形!A14:D21,F14:I21,K14:N21,P14:S21 上下左右矢印",
                "SHP03: 05_基本図形!A25:E25,G25:K25 直線・直線コネクター",
                "SHP04: 05_基本図形!A31:G38,I31:O38,A41:G48,I41:O48 日本語Sans/Serifの通常・太字",
                "SHP05: 05_基本図形!A51:H59 部分取消線除去・太字・図形内Webリンク",
                "SHP06: 05_基本図形!K52:P58 左右反転した矢印 / A63:G70 回転した図形",
                "SHP07: 05_基本図形!I63:O70 テーマ色による単色の塗り",
                "GRP01: 06_グループと接続!A4:N14 図形・線・PNGの明示グループ",
                "GRP02: 06_グループと接続!A18:N30 入れ子グループの拡大縮小・回転",
                "GRP03: 06_グループと接続!A35:F41,J35:O41 接続ID付きの未グループ図形",
                "GRP04: 06_グループと接続!A47:G55,C49:I57 重なった未グループ図形",
                "GRP05: 06_グループと接続!A63:J73 画像と背景図形の合成・重複配置なし",
                "UNS01: 07_未対応と加工!E4:Q15 実データ付き棒グラフは代替表示",
                "UNS02: 07_未対応と加工!A20:E28 星形は文字と代替表示",
                "UNS03: 07_未対応と加工!G20:L28 グラデーションを簡略化して警告",
                "UNS04: 07_未対応と加工!N20:Q30 縦書きを横書きへ簡略化",
                "UNS05: 07_未対応と加工!A35:G43 切り抜き・回転・反転したPNGを原本抽出",
                "UNS06: 07_未対応と加工!J35:P43 BMPは元画像ファイルへのリンク",
                "IMG08: 08_画像だけ!A3:J16 セル値がない画像専用シートを保持");
    }

    private static void images(XSSFWorkbook book, int png, int jpeg, int hidden) {
        XSSFSheet sheet = sheet(book, "04_画像", "04  貼り付け画像と配置");
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        label(sheet, 2, 0, "IMG01  PNG：元のバイト列と形式を保持");
        label(sheet, 2, 6, "IMG02  JPEG：元のバイト列と形式を保持");
        picture(drawing, anchor(0, 3, 4, 9), png, "PNGの日本語サンプル");
        picture(drawing, anchor(6, 3, 10, 9), jpeg, "JPEGの日本語サンプル");
        label(sheet, 12, 0, "IMG03  同じPNGを2か所に配置：ファイルは共有");
        picture(drawing, anchor(0, 13, 4, 19), png, "同一PNGの配置1");
        picture(drawing, anchor(6, 13, 10, 19), png, "同一PNGの配置2");
        label(sheet, 22, 0, "IMG04  罫線表に重なる画像：Markdownでは表の直後");
        grid(sheet, 23, 28, 0, 4);
        String[][] data = {{"項目", "状態", "担当", "期限", "備考"}, {"申請", "完了", "山田", "9/10", "確認済み"},
                {"確認", "進行中", "佐藤", "9/12", "画像が重なる範囲"}, {"決裁", "未着手", "鈴木", "9/15", "予定"},
                {"通知", "未着手", "高橋", "9/16", "メール"}, {"保管", "未着手", "田中", "9/18", "完了後"}};
        for (int row = 0; row < data.length; row++) for (int column = 0; column < data[row].length; column++)
            sheet.getRow(row + 23).getCell(column).setCellValue(data[row][column]);
        picture(drawing, anchor(1, 25, 3, 28), png, "罫線表に重なるPNG");
        label(sheet, 24, 7, "この本文より先に、表と画像が続いて出力されます。");
        label(sheet, 32, 0, "IMG05–07  明示非表示・非表示行・非表示列の画像は除外");
        picture(drawing, anchor(0, 34, 4, 40), hidden, "明示的に非表示の画像").getCTPicture().getNvPicPr().getCNvPr().setHidden(true);
        picture(drawing, anchor(6, 34, 10, 40), hidden, "非表示行をアンカーに持つ画像");
        sheet.createRow(34).setZeroHeight(true);
        picture(drawing, anchor(12, 3, 16, 9), hidden, "非表示列をアンカーに持つ画像");
        sheet.setColumnHidden(12, true);
    }

    private static void basicShapes(XSSFWorkbook book) {
        XSSFSheet sheet = sheet(book, "05_基本図形", "05  基本図形・日本語・文字書式");
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        label(sheet, 2, 0, "SHP01  四角"); label(sheet, 2, 6, "角丸四角"); label(sheet, 2, 12, "円・楕円");
        shape(drawing, anchor(0, 3, 4, 9), ShapeTypes.RECT, "四角\n受付内容", SANS, false, BLUE);
        shape(drawing, anchor(6, 3, 10, 9), ShapeTypes.ROUND_RECT, "角丸四角\n承認待ち", SANS, true, PALE);
        shape(drawing, anchor(12, 3, 16, 9), ShapeTypes.ELLIPSE, "楕円\n完了", SANS, false, BLUE);
        label(sheet, 12, 0, "SHP02  基本矢印：右・左・上・下");
        int[] types = {ShapeTypes.RIGHT_ARROW, ShapeTypes.LEFT_ARROW, ShapeTypes.UP_ARROW, ShapeTypes.DOWN_ARROW};
        String[] labels = {"右", "左", "上", "下"};
        for (int i = 0; i < types.length; i++) shape(drawing, anchor(i * 5, 13, i * 5 + 3, 20), types[i], labels[i], SANS, true, PALE);
        label(sheet, 23, 0, "SHP03  直線"); label(sheet, 23, 6, "直線コネクター：保存端点・矢印");
        XSSFSimpleShape line = shape(drawing, anchor(0, 24, 4, 24), ShapeTypes.LINE, "", SANS, false, PALE);
        line.setLineWidth(2);
        XSSFConnector connector = drawing.createConnector(anchor(6, 24, 10, 24));
        connector.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1); connector.setLineStyleColor(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue());
        connector.setLineWidth(2); connector.getCTConnector().getSpPr().getLn().addNewTailEnd().setType(STLineEndType.TRIANGLE);
        label(sheet, 29, 0, "SHP04  ゴシック：通常 / 太字");
        shape(drawing, anchor(0, 30, 6, 37), ShapeTypes.RECT, "日本語の表示確認\nひらがな あいうえお\nカタカナ アイウエオ", "ConvertX2X Missing Gothic", false, BLUE);
        shape(drawing, anchor(8, 30, 14, 37), ShapeTypes.RECT, "日本語の表示確認\n東京都・株式会社\n売上報告 ABC 123", "ConvertX2X Missing Gothic", true, PALE);
        label(sheet, 39, 0, "明朝：通常 / 太字");
        shape(drawing, anchor(0, 40, 6, 47), ShapeTypes.RECT, "明朝体の表示確認\nひらがな あいうえお\n東京都・株式会社", "ConvertX2X Missing Mincho", false, BLUE);
        shape(drawing, anchor(8, 40, 14, 47), ShapeTypes.RECT, "明朝体の表示確認\n日本語と English\n２０２６年９月", "ConvertX2X Missing Mincho", true, PALE);
        label(sheet, 49, 0, "SHP05  テキストボックス：部分取消線・太字・リンク");
        XSSFTextBox text = drawing.createTextbox(anchor(0, 50, 7, 58));
        text.setNoFill(false); text.setFillColor(PALE.getRed(), PALE.getGreen(), PALE.getBlue());
        if (text.getCTShape().getSpPr().isSetNoFill()) text.getCTShape().getSpPr().unsetNoFill();
        text.setLineStyleColor(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue()); text.clearText();
        XSSFTextParagraph paragraph = text.addNewTextParagraph();
        run(paragraph, "価格：", SANS, false);
        XSSFTextRun removed = run(paragraph, "SHAPE_SECRET_REMOVED", SANS, false); removed.setStrikethrough(true);
        run(paragraph, "1,200円（更新後）\n", SANS, true);
        XSSFTextRun link = run(paragraph, "Apache POIの公式サイト", SANS, false);
        var relationship = drawing.getPackagePart().addExternalRelationship("https://poi.apache.org/",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink");
        link.getXmlObject().getRPr().addNewHlinkClick().setId(relationship.getId());
        label(sheet, 50, 10, "SHP06  左右反転した右矢印");
        XSSFSimpleShape flipped = shape(drawing, anchor(10, 51, 15, 57), ShapeTypes.RIGHT_ARROW, "", SANS, true, PALE);
        flipped.getCTShape().getSpPr().getXfrm().setFlipH(true);
        label(sheet, 59, 0, "15度回転した角丸四角");
        XSSFSimpleShape rotated = shape(drawing, anchor(0, 62, 6, 69), ShapeTypes.ROUND_RECT, "回転した図形\n位置と文字を保持", SANS, true, BLUE);
        rotated.getCTShape().getSpPr().getXfrm().setRot(15 * 60000);
        label(sheet, 59, 8, "SHP07  テーマのaccent1を指定した塗り");
        XSSFSimpleShape themed = shape(drawing, anchor(8, 62, 14, 69), ShapeTypes.ROUND_RECT, "テーマ色\naccent1", SANS, false, BLUE);
        var fill = themed.getCTShape().getSpPr().getSolidFill(); fill.unsetSrgbClr();
        fill.addNewSchemeClr().setVal(STSchemeColorVal.ACCENT_1);
    }

    private static void groupsAndConnections(XSSFWorkbook book, int png) {
        XSSFSheet sheet = sheet(book, "06_グループと接続", "06  グループ・接続・重なり");
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        label(sheet, 2, 0, "GRP01  明示グループ：図形・線・画像を1枚に合成");
        XSSFShapeGroup group = drawing.createGroup(anchor(0, 3, 13, 13));
        group.setCoordinates(0, 0, 720 * EMU, 240 * EMU);
        groupShape(group, childAnchor(0, 30, 200, 190), ShapeTypes.ROUND_RECT, "グループ\n受付", SANS, true, BLUE);
        groupShape(group, childAnchor(250, 30, 430, 190), ShapeTypes.ELLIPSE, "確認\n完了", SANS, false, PALE);
        XSSFConnector line = group.createConnector(childAnchor(200, 110, 250, 110));
        line.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1); line.setLineWidth(2);
        line.getCTConnector().getSpPr().getLn().addNewTailEnd().setType(STLineEndType.TRIANGLE);
        XSSFPicture groupedPicture = group.createPicture(anchor(0, 0, 1, 1), png);
        var transform = groupedPicture.getCTPicture().getSpPr().getXfrm();
        transform.getOff().setX(480L * EMU); transform.getOff().setY(55L * EMU);
        transform.getExt().setCx(210L * EMU); transform.getExt().setCy(126L * EMU);
        groupedPicture.getCTPicture().getNvPicPr().getCNvPr().setDescr("明示グループに含まれるPNG");
        label(sheet, 16, 0, "GRP02  入れ子グループ：座標の拡大縮小と10度回転");
        XSSFShapeGroup parent = drawing.createGroup(anchor(0, 17, 13, 29));
        parent.setCoordinates(0, 0, 800 * EMU, 280 * EMU);
        XSSFShapeGroup nested = parent.createGroup(childAnchor(70, 30, 750, 240));
        nested.setCoordinates(0, 0, 500 * EMU, 200 * EMU);
        nested.getCTGroupShape().getGrpSpPr().getXfrm().setRot(10 * 60000);
        groupShape(nested, childAnchor(15, 20, 210, 165), ShapeTypes.RECT, "内側のグループ\n左の図形", SANS, true, BLUE);
        groupShape(nested, childAnchor(275, 20, 485, 165), ShapeTypes.ROUND_RECT, "内側のグループ\n右の図形", SERIF, false, PALE);
        label(sheet, 33, 0, "GRP03  未グループ：コネクターの接続先IDで1枚にまとめる");
        XSSFSimpleShape start = shape(drawing, anchor(0, 34, 5, 40), ShapeTypes.ROUND_RECT, "受付", SANS, true, BLUE);
        XSSFSimpleShape end = shape(drawing, anchor(9, 34, 14, 40), ShapeTypes.ROUND_RECT, "完了", SANS, true, PALE);
        XSSFConnector connection = drawing.createConnector(anchor(5, 37, 9, 37));
        connection.setShapeType(ShapeTypes.STRAIGHT_CONNECTOR_1); connection.setLineWidth(2);
        connection.getCTConnector().getSpPr().getLn().addNewTailEnd().setType(STLineEndType.TRIANGLE);
        var properties = connection.getCTConnector().getNvCxnSpPr().getCNvCxnSpPr();
        var from = properties.addNewStCxn(); from.setId(start.getShapeId()); from.setIdx(3);
        var to = properties.addNewEndCxn(); to.setId(end.getShapeId()); to.setIdx(1);
        label(sheet, 45, 0, "GRP04  未グループ：描画領域の重なりで1枚にまとめる");
        shape(drawing, anchor(0, 46, 6, 54), ShapeTypes.RECT, "背面の四角", SANS, false, BLUE);
        shape(drawing, anchor(2, 48, 8, 56), ShapeTypes.ELLIPSE, "前面の楕円", SANS, true, PALE);
        label(sheet, 61, 0, "GRP05  背景図形＋PNG：合成画像として一度だけ出力");
        shape(drawing, anchor(0, 62, 9, 72), ShapeTypes.ROUND_RECT, "", SANS, false, BLUE);
        picture(drawing, anchor(2, 64, 7, 70), png, "背景図形に重なるPNG");
    }

    private static void unsupportedAndEffects(XSSFWorkbook book, int cropped, int bitmap) {
        XSSFSheet sheet = sheet(book, "07_未対応と加工", "07  未対応要素と加工の通知");
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        label(sheet, 2, 0, "UNS01  実データの棒グラフ：描画は未対応、元のセル値は保持");
        String[] months = {"7月", "8月", "9月"}; Double[] counts = {12d, 18d, 25d};
        for (int i = 0; i < months.length; i++) {
            cell(sheet, i + 4, 0).setCellValue(months[i]); cell(sheet, i + 4, 1).setCellValue(counts[i]);
        }
        XSSFChart chart = drawing.createChart(anchor(4, 3, 16, 14));
        chart.setTitleText("月別の申請件数"); chart.setTitleOverlay(false);
        var category = chart.createCategoryAxis(AxisPosition.BOTTOM);
        var value = chart.createValueAxis(AxisPosition.LEFT);
        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, category, value);
        data.setBarDirection(BarDirection.COL);
        var series = data.addSeries(XDDFDataSourcesFactory.fromArray(months), XDDFDataSourcesFactory.fromArray(counts));
        series.setTitle("申請件数", null); chart.plot(data);
        label(sheet, 18, 0, "UNS02  星形：代替表示と文字を保持");
        shape(drawing, anchor(0, 19, 4, 27), ShapeTypes.STAR_5, "星形の文字", SANS, true, PALE);
        label(sheet, 18, 6, "UNS03  グラデーション：簡略化を警告");
        XSSFSimpleShape gradient = shape(drawing, anchor(6, 19, 11, 27), ShapeTypes.RECT, "グラデーション\n簡略化の通知を確認", SANS, false, BLUE);
        var properties = gradient.getCTShape().getSpPr(); properties.unsetSolidFill();
        var fill = properties.addNewGradFill(); var stops = fill.addNewGsLst();
        var first = stops.addNewGs(); first.setPos(0); first.addNewSrgbClr().setVal(rgb(NAVY));
        var last = stops.addNewGs(); last.setPos(100000); last.addNewSrgbClr().setVal(rgb(TEAL));
        var linear = fill.addNewLin(); linear.setAng(90 * 60000); linear.setScaled(true);
        label(sheet, 18, 13, "UNS04  縦書き：横書きへ簡略化");
        XSSFTextBox vertical = drawing.createTextbox(anchor(13, 19, 16, 29));
        vertical.clearText(); run(vertical.addNewTextParagraph(), "縦書きの日本語\n簡易描画の対象", SERIF, false);
        vertical.getCTShape().getTxBody().getBodyPr().setVert(STTextVerticalType.EA_VERT);
        label(sheet, 32, 0, "UNS05  切り抜き・回転・反転：切り抜き前の原本が保存される");
        XSSFPicture picture = picture(drawing, anchor(0, 34, 6, 42), cropped, "加工前の原本画像");
        var crop = picture.getCTPicture().getBlipFill().addNewSrcRect(); crop.setL(25000); crop.setR(25000);
        picture.getCTPicture().getSpPr().getXfrm().setRot(10 * 60000);
        picture.getCTPicture().getSpPr().getXfrm().setFlipH(true);
        label(sheet, 32, 9, "UNS06  BMP：元ファイルを添付、画像表示はしない");
        picture(drawing, anchor(9, 34, 15, 42), bitmap, "BMPのオリジナル画像");
    }

    private static void imageOnly(XSSFWorkbook book, int png) {
        // No cell values, including a title cell: this deliberately exercises image-only sheet retention.
        XSSFSheet sheet = book.createSheet("08_画像だけ");
        sheet.setDefaultRowHeightInPoints(24); sheet.setDisplayGridlines(false); sheet.createFreezePane(0, 1);
        for (int column = 0; column < 12; column++) sheet.setColumnWidth(column, 12 * 256);
        picture(sheet.createDrawingPatriarch(), anchor(0, 2, 9, 15), png, "セル値を持たないシートの画像");
    }

    private static XSSFSheet sheet(XSSFWorkbook book, String name, String title) {
        XSSFSheet sheet = book.createSheet(name);
        sheet.setDefaultRowHeightInPoints(24); sheet.setDisplayGridlines(false); sheet.createFreezePane(0, 1);
        for (int column = 0; column < 20; column++) sheet.setColumnWidth(column, 12 * 256);
        sheet.setZoom(80);
        var font = book.createFont(); font.setFontName(SANS); font.setBold(true); font.setFontHeightInPoints((short) 22);
        font.setColor(new XSSFColor(NAVY, null));
        var style = book.createCellStyle(); style.setFont(font);
        cell(sheet, 0, 0).setCellValue(title); cell(sheet, 0, 0).setCellStyle(style); sheet.getRow(0).setHeightInPoints(36);
        return sheet;
    }

    private static void label(XSSFSheet sheet, int row, int column, String text) {
        var book = sheet.getWorkbook(); var font = book.createFont(); font.setFontName(SANS); font.setBold(true);
        font.setFontHeightInPoints((short) 11); font.setColor(new XSSFColor(TEAL, null));
        var style = book.createCellStyle(); style.setFont(font);
        cell(sheet, row, column).setCellValue(text); cell(sheet, row, column).setCellStyle(style);
    }
    private static XSSFCell cell(XSSFSheet sheet, int row, int column) {
        XSSFRow existing = sheet.getRow(row); if (existing == null) existing = sheet.createRow(row);
        XSSFCell cell = existing.getCell(column); return cell == null ? existing.createCell(column) : cell;
    }
    private static void grid(XSSFSheet sheet, int firstRow, int lastRow, int firstColumn, int lastColumn) {
        var style = sheet.getWorkbook().createCellStyle();
        style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(new XSSFColor(BLUE, null)); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        for (int row = firstRow; row <= lastRow; row++) for (int column = firstColumn; column <= lastColumn; column++)
            cell(sheet, row, column).setCellStyle(style);
    }
    private static XSSFClientAnchor anchor(int column, int row, int lastColumn, int lastRow) {
        return new XSSFClientAnchor(0, 0, 0, 0, column, row, lastColumn, lastRow);
    }
    private static XSSFChildAnchor childAnchor(int x, int y, int right, int bottom) {
        return new XSSFChildAnchor(x * EMU, y * EMU, right * EMU, bottom * EMU);
    }
    private static XSSFPicture picture(XSSFDrawing drawing, XSSFClientAnchor anchor, int id, String description) {
        XSSFPicture picture = drawing.createPicture(anchor, id);
        picture.getCTPicture().getNvPicPr().getCNvPr().setDescr(description); return picture;
    }
    private static XSSFSimpleShape shape(XSSFDrawing drawing, XSSFClientAnchor anchor, int type,
                                        String text, String family, boolean bold, Color fill) {
        XSSFSimpleShape shape = drawing.createSimpleShape(anchor); style(shape, type, text, family, bold, fill); return shape;
    }
    private static XSSFSimpleShape groupShape(XSSFShapeGroup group, XSSFChildAnchor anchor, int type,
                                             String text, String family, boolean bold, Color fill) {
        XSSFSimpleShape shape = group.createSimpleShape(anchor); style(shape, type, text, family, bold, fill); return shape;
    }
    private static void style(XSSFSimpleShape shape, int type, String text, String family, boolean bold, Color fill) {
        shape.setShapeType(type); shape.setFillColor(fill.getRed(), fill.getGreen(), fill.getBlue());
        shape.setLineStyleColor(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue()); shape.setLineWidth(1.5);
        shape.clearText(); shape.setLeftInset(10); shape.setRightInset(8); shape.setTopInset(10); shape.setBottomInset(8);
        if (!text.isEmpty()) {
            var paragraph = shape.addNewTextParagraph();
            if (type == ShapeTypes.ELLIPSE) paragraph.setTextAlign(TextAlign.CENTER);
            run(paragraph, text, family, bold);
        }
    }
    private static XSSFTextRun run(XSSFTextParagraph paragraph, String text, String family, boolean bold) {
        XSSFTextRun run = paragraph.addNewTextRun(); run.setText(text); run.setFontFamily(family, (byte) 0, (byte) 0, false);
        run.getXmlObject().getRPr().addNewEa().setTypeface(family);
        run.setFontSize(16); run.setBold(bold); run.setFontColor(NAVY); return run;
    }
    private static byte[] rgb(Color color) { return new byte[]{(byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue()}; }

    private static byte[] originalImage(String badge, String title, String format, boolean hidden) throws Exception {
        BufferedImage image = new BufferedImage(720, 432, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(hidden ? new Color(255, 236, 215) : BLUE); graphics.fillRect(0, 0, 720, 432);
            graphics.setColor(NAVY); graphics.fillRect(0, 0, 720, 16);
            graphics.setFont(font(true, 24)); graphics.setColor(TEAL); graphics.drawString("convertX2X  /  " + badge, 36, 68);
            graphics.setFont(font(true, 32)); graphics.setColor(NAVY); graphics.drawString(title, 36, 130);
            graphics.setFont(font(false, 20)); graphics.drawString("日本語・ひらがな・カタカナ・English 123", 36, 172);
            for (int i = 0; i < 3; i++) {
                int x = 36 + i * 224;
                graphics.setColor(i == 1 ? PALE : Color.WHITE); graphics.fill(new RoundRectangle2D.Double(x, 216, 196, 136, 20, 20));
                graphics.setColor(TEAL); graphics.setFont(font(true, 34)); graphics.drawString("0" + (i + 1), x + 20, 262);
                graphics.setColor(NAVY); graphics.setFont(font(false, 24)); graphics.drawString(new String[]{"申請", "確認", "完了"}[i], x + 20, 314);
            }
            graphics.setFont(font(false, 16)); graphics.setColor(NAVY); graphics.drawString("Java2Dで作成した検証用オリジナル画像", 36, 399);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, bytes)) throw new IllegalStateException("Image encoder unavailable: " + format);
            return bytes.toByteArray();
        } finally { graphics.dispose(); image.flush(); }
    }
    private static synchronized Font font(boolean bold, float size) throws Exception {
        Font cached = bold ? boldFont : regularFont;
        if (cached != null) return cached.deriveFont(size);
        String resource = "/fonts/noto/NotoSansCJKjp-" + (bold ? "Bold" : "Regular") + ".otf";
        try (InputStream stream = FullFeatureDrawings.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Bundled font is missing: " + resource);
            Font loaded = Font.createFont(Font.TRUETYPE_FONT, stream);
            if (bold) boldFont = loaded; else regularFont = loaded;
            return loaded.deriveFont(size);
        }
    }

    private static void ensureTheme(XSSFWorkbook book) throws Exception {
        if (book.getTheme() != null) return;
        String fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
        String line = "<a:ln w=\"12700\">" + fill + "<a:prstDash val=\"solid\"/></a:ln>";
        String font = "<a:latin typeface=\"Noto Sans CJK JP\"/><a:ea typeface=\"Noto Sans CJK JP\"/><a:cs typeface=\"\"/>";
        String theme = """
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="convertX2X Sample">
                  <a:themeElements>
                    <a:clrScheme name="Navy and Teal">
                      <a:dk1><a:srgbClr val="142941"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
                      <a:dk2><a:srgbClr val="36526D"/></a:dk2><a:lt2><a:srgbClr val="E9F1FB"/></a:lt2>
                      <a:accent1><a:srgbClr val="B5E4DF"/></a:accent1><a:accent2><a:srgbClr val="00828C"/></a:accent2>
                      <a:accent3><a:srgbClr val="6B90BB"/></a:accent3><a:accent4><a:srgbClr val="B79DD4"/></a:accent4>
                      <a:accent5><a:srgbClr val="DEA45F"/></a:accent5><a:accent6><a:srgbClr val="C8717D"/></a:accent6>
                      <a:hlink><a:srgbClr val="006DB0"/></a:hlink><a:folHlink><a:srgbClr val="7353A0"/></a:folHlink>
                    </a:clrScheme>
                    <a:fontScheme name="Noto Japanese"><a:majorFont>%s</a:majorFont><a:minorFont>%s</a:minorFont></a:fontScheme>
                    <a:fmtScheme name="Simple"><a:fillStyleLst>%s</a:fillStyleLst><a:lnStyleLst>%s</a:lnStyleLst>
                      <a:effectStyleLst>%s</a:effectStyleLst><a:bgFillStyleLst>%s</a:bgFillStyleLst></a:fmtScheme>
                  </a:themeElements>
                </a:theme>
                """.formatted(font, font, fill.repeat(3), line.repeat(3),
                        "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3), fill.repeat(3));
        book.getStylesSource().ensureThemesTable();
        try (InputStream stream = new ByteArrayInputStream(theme.getBytes(StandardCharsets.UTF_8))) { book.getTheme().readFrom(stream); }
    }
}
