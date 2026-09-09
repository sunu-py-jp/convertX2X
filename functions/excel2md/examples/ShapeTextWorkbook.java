import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;

/** Editable native Excel shapes for visual comparison. No screenshots or raster source images. */
public final class ShapeTextWorkbook {
    private static final String SANS = "Noto Sans CJK JP", SERIF = "Noto Serif CJK JP";
    private static final Color NAVY = new Color(0x19324D), WHITE = Color.WHITE;
    private static final Color PALE = new Color(0xE9F5F2), BLUE = new Color(0x2473C5), RED = new Color(0xC73949);
    private final XSSFWorkbook book = new XSSFWorkbook();
    private final List<String> cases = new ArrayList<>();
    private final XSSFCellStyle label = style(11, true, NAVY, new Color(0xEFF3F7));
    private final XSSFCellStyle title = style(19, true, WHITE, NAVY);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ShapeTextWorkbook output.xlsx");
        ShapeTextWorkbook fixture = new ShapeTextWorkbook();
        try (XSSFWorkbook workbook = fixture.book) {
            fixture.theme();
            fixture.colorsAndFonts(); fixture.alignment(); fixture.paragraphs();
            fixture.wrapping(); fixture.decoration(); fixture.rotation();
            workbook.getProperties().getCoreProperties().setTitle("excel2md 図形文字の比較テスト");
            workbook.getProperties().getCoreProperties().setDescription("Original editable shapes with explicit text formatting and visual case labels.");
            workbook.setActiveSheet(0);
            Path output = Path.of(args[0]).toAbsolutePath();
            Files.createDirectories(output.getParent());
            try (OutputStream stream = Files.newOutputStream(output)) { workbook.write(stream); }
            String filename = output.getFileName().toString();
            String stem = filename.endsWith(".xlsx") ? filename.substring(0, filename.length() - 5) : filename;
            Files.writeString(output.resolveSibling(stem + ".md"), fixture.documentation(), StandardCharsets.UTF_8);
            System.out.println(output + " (" + Files.size(output) + " bytes, " + workbook.getNumberOfSheets()
                    + " sheets, " + fixture.cases.size() + " native shapes)");
        }
    }

    private XSSFCellStyle style(int size, boolean bold, Color foreground, Color background) {
        XSSFFont font = book.createFont(); font.setFontName(SANS); font.setFontHeightInPoints((short) size);
        font.setBold(bold); font.setColor(new XSSFColor(foreground, null));
        XSSFCellStyle style = book.createCellStyle(); style.setFont(font); style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(new XSSFColor(background, null)); style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
    private XSSFSheet sheet(String name, String heading) {
        XSSFSheet sheet = book.createSheet(name); sheet.setDefaultRowHeightInPoints(20);
        for (int column = 0; column < 18; column++) sheet.setColumnWidth(column, 9 * 256);
        cell(sheet, 0, 0, heading, title); sheet.getRow(0).setHeightInPoints(34);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 16));
        cell(sheet, 1, 0, "枠は編集可能なExcel図形です。セルの設定説明と図形文字を見比べます。", label);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 16));
        sheet.createFreezePane(0, 2); return sheet;
    }
    private void cell(XSSFSheet sheet, int row, int column, String text, XSSFCellStyle style) {
        XSSFRow target = sheet.getRow(row); if (target == null) target = sheet.createRow(row);
        XSSFCell cell = target.createCell(column); cell.setCellStyle(style); cell.setCellValue(text);
    }
    private XSSFSimpleShape box(XSSFSheet sheet, int index, String id, String description) {
        return boxAt(sheet, 4 + index / 2 * 14, index % 2 * 9, 7, 8, id, description);
    }
    private XSSFSimpleShape boxAt(XSSFSheet sheet, int row, int column, int width, int height, String id, String description) {
        return boxAt(sheet, row, column, width, height, id, description, 1);
    }
    private XSSFSimpleShape boxAt(XSSFSheet sheet, int row, int column, int width, int height, String id, String description, int leadingRows) {
        cell(sheet, row, column, id + "  " + description, label); sheet.getRow(row).setHeightInPoints(36);
        sheet.addMergedRegion(new CellRangeAddress(row, row, column, column + width - 1));
        XSSFDrawing drawing = sheet.getDrawingPatriarch(); if (drawing == null) drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0, column, row + leadingRows, column + width, row + leadingRows + height);
        XSSFSimpleShape shape = drawing.createSimpleShape(anchor);
        shape.setShapeType(ShapeTypes.RECT); fill(shape, WHITE); shape.setLineStyleColor(108, 137, 153); shape.setLineWidth(1);
        shape.setVerticalAlignment(VerticalAlignment.TOP); shape.setWordWrap(true); shape.setTextAutofit(TextAutofit.NONE);
        shape.setTextHorizontalOverflow(TextHorizontalOverflow.CLIP); shape.setTextVerticalOverflow(TextVerticalOverflow.CLIP);
        insets(shape, 9, 9, 9, 9); shape.clearText();
        shape.getCTShape().getNvSpPr().getCNvPr().setName(id);
        shape.getCTShape().getNvSpPr().getCNvPr().setDescr(id + ": " + description);
        String range = new CellReference(row + leadingRows, column).formatAsString() + ":"
                + new CellReference(row + leadingRows + height, column + width).formatAsString();
        cases.add("| " + id + " | " + sheet.getSheetName() + "!" + range + " | " + description + " |");
        return shape;
    }
    private static void fill(XSSFSimpleShape shape, Color color) {
        var properties = shape.getCTShape().getSpPr();
        if (properties.isSetNoFill()) properties.unsetNoFill();
        shape.setFillColor(color.getRed(), color.getGreen(), color.getBlue());
    }
    private static void insets(XSSFSimpleShape shape, double left, double top, double right, double bottom) {
        shape.setLeftInset(left); shape.setTopInset(top); shape.setRightInset(right); shape.setBottomInset(bottom);
    }
    private static XSSFTextParagraph paragraph(XSSFSimpleShape shape, TextAlign alignment) {
        XSSFTextParagraph paragraph = shape.addNewTextParagraph(); paragraph.setTextAlign(alignment);
        paragraph.setLineSpacing(100); paragraph.setSpaceBefore(0); paragraph.setSpaceAfter(0); return paragraph;
    }
    private static XSSFTextRun run(XSSFTextParagraph paragraph, String text, String family, double points, boolean bold, Color color) {
        XSSFTextRun run = paragraph.addNewTextRun(); run.setText(text); run.setFont(family);
        run.setFontSize(points); run.setBold(bold); run.setFontColor(color);
        var properties = run.getXmlObject().getRPr();
        if (properties.isSetEa()) properties.getEa().setTypeface(family); else properties.addNewEa().setTypeface(family);
        return run;
    }
    private static XSSFTextRun text(XSSFSimpleShape shape, String text, String family, double points, boolean bold, Color color) {
        return run(paragraph(shape, TextAlign.LEFT), text, family, points, bold, color);
    }
    private static void themeColor(XSSFTextRun run, STSchemeColorVal.Enum color) {
        var properties = run.getXmlObject().getRPr();
        if (properties.isSetSolidFill()) properties.unsetSolidFill();
        properties.addNewSolidFill().addNewSchemeClr().setVal(color);
    }

    private void colorsAndFonts() {
        XSSFSheet sheet = sheet("01_色と書体", "01  図形内の文字色・日本語フォント・大きさ");
        XSSFSimpleShape shape = box(sheet, 0, "COL01", "白背景／紺 #19324D／Sans 16pt 通常");
        text(shape, "COL01 日本語の文字\nひらがな・カタカナ\nEnglish ABC 123", SANS, 16, false, NAVY);
        shape = box(sheet, 1, "COL02", "濃紺 #19324D 背景／白 #FFFFFF／Sans 24pt 太字"); fill(shape, NAVY);
        text(shape, "COL02 白文字の確認\n申請・確認・完了", SANS, 24, true, WHITE);
        shape = box(sheet, 2, "COL03", "白背景／紺／Serif 16pt 通常");
        text(shape, "COL03 明朝体の文字\n東京都・株式会社\nEnglish ABC 123", SERIF, 16, false, NAVY);
        shape = box(sheet, 3, "COL04", "淡緑背景／紺／Serif 24pt 太字"); fill(shape, PALE);
        text(shape, "COL04 明朝体の太字\n日本語と English", SERIF, 24, true, NAVY);
        shape = box(sheet, 4, "COL05", "1段落にRGB赤 #C73949 16pt と青 #2473C5 24pt太字");
        XSSFTextParagraph paragraph = paragraph(shape, TextAlign.LEFT);
        run(paragraph, "赤い文字 ", SANS, 16, false, RED); run(paragraph, "青い太字", SANS, 24, true, BLUE);
        paragraph.addLineBreak(); run(paragraph, "基準線と色を確認", SANS, 16, false, NAVY);
        shape = box(sheet, 5, "COL06", "テーマ文字色 accent1=#008A7B／accent2=#A44377、各20pt");
        paragraph = paragraph(shape, TextAlign.LEFT);
        themeColor(run(paragraph, "accent1 緑の日本語", SANS, 20, true, NAVY), STSchemeColorVal.ACCENT_1);
        paragraph.addLineBreak(); themeColor(run(paragraph, "accent2 紫の日本語", SANS, 20, false, NAVY), STSchemeColorVal.ACCENT_2);
    }
    private void alignment() {
        XSSFSheet sheet = sheet("02_上下左右配置", "02  左・中央・右 × 上・中央・下（同じ枠・同じ文字サイズ）");
        TextAlign[] horizontal = {TextAlign.LEFT, TextAlign.CENTER, TextAlign.RIGHT};
        VerticalAlignment[] vertical = {VerticalAlignment.TOP, VerticalAlignment.CENTER, VerticalAlignment.BOTTOM};
        String[] h = {"左", "中央", "右"}, v = {"上", "中央", "下"};
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            String id = "POS" + (y * 3 + x + 1);
            XSSFSimpleShape shape = boxAt(sheet, 4 + y * 13, x * 6, 4, 8, id, h[x] + " × " + v[y] + "／16pt／余白9pt");
            fill(shape, y == 1 ? PALE : WHITE); shape.setVerticalAlignment(vertical[y]);
            run(paragraph(shape, horizontal[x]), h[x] + " × " + v[y] + "\n日本語 16pt", SANS, 16, false, NAVY);
        }
    }
    private void paragraphs() {
        XSSFSheet sheet = sheet("03_段落と余白", "03  段落ごとの配置・図形内余白・行と段落の間隔");
        XSSFSimpleShape shape = box(sheet, 0, "PAR01", "3段落を左→中央→右／上配置／各16pt");
        run(paragraph(shape, TextAlign.LEFT), "左揃えの段落", SANS, 16, true, RED);
        run(paragraph(shape, TextAlign.CENTER), "中央揃えの段落", SANS, 16, true, NAVY);
        run(paragraph(shape, TextAlign.RIGHT), "右揃えの段落", SANS, 16, true, BLUE);
        shape = box(sheet, 1, "PAR02", "1段落内の明示改行 a:br／中央揃え・縦中央／16pt");
        shape.setVerticalAlignment(VerticalAlignment.CENTER);
        XSSFTextParagraph paragraph = paragraph(shape, TextAlign.CENTER);
        run(paragraph, "段落内の1行目", SANS, 16, false, NAVY); paragraph.addLineBreak();
        run(paragraph, "段落内の2行目", SANS, 16, true, BLUE);
        shape = box(sheet, 2, "PAR03", "図形の内余白 L/T/R/B=0/0/0/0pt／左上"); insets(shape, 0, 0, 0, 0);
        text(shape, "余白 0pt\n枠の左上から始まる文字", SANS, 16, false, NAVY);
        shape = box(sheet, 3, "PAR04", "図形の内余白 L/T/R/B=24/18/12/8pt／左上"); insets(shape, 24, 18, 12, 8);
        text(shape, "左24pt・上18pt\n余白の差を確認", SANS, 16, false, NAVY);
        shape = box(sheet, 4, "PAR05", "16pt／行間150%／2段落目の前12pt・後18pt");
        paragraph = paragraph(shape, TextAlign.LEFT); paragraph.setLineSpacing(150);
        run(paragraph, "行間150%の1行目\n行間150%の2行目", SANS, 16, false, NAVY);
        paragraph = paragraph(shape, TextAlign.LEFT); paragraph.setSpaceBefore(-12); paragraph.setSpaceAfter(-18);
        run(paragraph, "前12pt・後18ptの段落", SANS, 16, true, BLUE);
        run(paragraph(shape, TextAlign.LEFT), "次の段落", SANS, 16, false, NAVY);
        shape = box(sheet, 5, "PAR06", "16pt／固定行間30pt／左マージン18pt＋先頭字下げ12pt");
        paragraph = paragraph(shape, TextAlign.LEFT); paragraph.setLineSpacing(-30); paragraph.setLeftMargin(18); paragraph.setIndent(12);
        run(paragraph, "先頭行を字下げ\n2行目は左マージン\n3行目も30ptの行間", SANS, 16, false, NAVY);
    }
    private void wrapping() {
        XSSFSheet sheet = sheet("04_折返しと縮小", "04  折返し・枠外クリップ・保存済み自動縮小設定");
        String longText = "日本語の長い文章を同じ枠で比較します。東京から大阪へ、申請と確認を進めます。English ABC 123. ";
        XSSFSimpleShape shape = box(sheet, 0, "WRP01", "折返しあり／18pt／枠外clip／縮小なし");
        text(shape, longText.repeat(2), SANS, 18, false, NAVY);
        shape = box(sheet, 1, "WRP02", "折返しなし／18pt／横はみ出しclip／縮小なし"); shape.setWordWrap(false);
        text(shape, longText, SANS, 18, false, NAVY);
        shape = box(sheet, 2, "WRP03", "折返しあり／24pt太字／縮小なし（WRP04と同じ本文）");
        String shrinkText = "保存倍率を比べます。大きな日本語の文章を枠内に収めます。\n申請、確認、承認、通知、完了。";
        text(shape, shrinkText, SANS, 24, true, NAVY);
        shape = box(sheet, 3, "WRP04", "normAutofit fontScale=60%・lnSpcReduction=20%／元24pt太字");
        shape.setTextAutofit(TextAutofit.NORMAL);
        var fit = shape.getCTShape().getTxBody().getBodyPr().getNormAutofit(); fit.setFontScale(60000); fit.setLnSpcReduction(20000);
        text(shape, shrinkText, SANS, 24, true, NAVY);
        shape = box(sheet, 4, "WRP05", "normAutofit・倍率未保存／元24pt／長い本文の自動縮小");
        shape.setTextAutofit(TextAutofit.NORMAL); text(shape, longText.repeat(4), SANS, 24, false, NAVY);
        shape = box(sheet, 5, "WRP06", "明示改行＋折返し／18pt／右・下配置");
        shape.setVerticalAlignment(VerticalAlignment.BOTTOM);
        XSSFTextParagraph paragraph = paragraph(shape, TextAlign.RIGHT);
        run(paragraph, "明示改行の前", SANS, 18, false, NAVY); paragraph.addLineBreak();
        run(paragraph, "折返しを含む日本語の行です。東京・大阪・名古屋の担当者に通知します。", SANS, 18, true, BLUE);
    }
    private void decoration() {
        XSSFSheet sheet = sheet("05_装飾と字間", "05  下線・文字間隔・部分書式・取消線の削除");
        XSSFSimpleShape shape = box(sheet, 0, "DEC01", "青の下線20pt＋紺の通常16pt／段落内で書式を変更");
        XSSFTextParagraph paragraph = paragraph(shape, TextAlign.LEFT);
        run(paragraph, "下線のある日本語", SANS, 20, false, BLUE).setUnderline(true);
        paragraph.addLineBreak(); run(paragraph, "この行には下線なし", SANS, 16, false, NAVY);
        String text = "字間 ABC 123 日本語";
        shape = box(sheet, 1, "DEC02", "字間0pt／18pt／DEC03・04と比較"); text(shape, text, SANS, 18, false, NAVY).setCharacterSpacing(0);
        shape = box(sheet, 2, "DEC03", "字間+3pt／18pt／文字間を広げる"); text(shape, text, SANS, 18, false, NAVY).setCharacterSpacing(3);
        shape = box(sheet, 3, "DEC04", "字間-0.5pt／18pt／文字間を狭める"); text(shape, text, SANS, 18, false, NAVY).setCharacterSpacing(-0.5);
        shape = box(sheet, 4, "DEC05", "18pt／部分取消線の旧価格を変換時に削除／新価格は24pt太字");
        paragraph = paragraph(shape, TextAlign.LEFT);
        run(paragraph, "価格：", SANS, 18, false, NAVY);
        run(paragraph, "旧価格2,000円", SANS, 18, false, RED).setStrikethrough(true);
        run(paragraph, "1,200円", SANS, 24, true, BLUE);
        paragraph.addLineBreak(); run(paragraph, "Excelでは取消線／変換では文字を除外", SANS, 12, false, NAVY);
        shape = box(sheet, 5, "DEC06", "Sans 16pt＋24pt太字＋Serif 16pt／1行の基準線を比較");
        paragraph = paragraph(shape, TextAlign.LEFT);
        run(paragraph, "通常16pt ", SANS, 16, false, NAVY); run(paragraph, "太字24pt", SANS, 24, true, RED);
        run(paragraph, " 明朝16pt", SERIF, 16, false, BLUE);
    }
    private void rotation() {
        XSSFSheet sheet = sheet("06_文字と図形の回転", "06  図形の回転と文字本体の回転を区別する");
        // Leave room around rotated outlines so they do not obscure the descriptive cells in Excel.
        XSSFSimpleShape shape = boxAt(sheet, 4, 0, 7, 8, "ROT01", "図形+15°／文字本体0°／中央・中央／20pt", 3); fill(shape, PALE);
        shape.getCTShape().getSpPr().getXfrm().setRot(15 * 60000); shape.setVerticalAlignment(VerticalAlignment.CENTER);
        run(paragraph(shape, TextAlign.CENTER), "図形と一緒に回転\n日本語 20pt", SANS, 20, true, NAVY);
        shape = boxAt(sheet, 4, 9, 7, 8, "ROT02", "図形0°／bodyPr.rot=+30°／中央・中央／20pt", 3);
        shape.getCTShape().getTxBody().getBodyPr().setRot(30 * 60000); shape.setVerticalAlignment(VerticalAlignment.CENTER);
        run(paragraph(shape, TextAlign.CENTER), "文字だけ30度\n枠は水平", SANS, 20, true, BLUE);
        shape = boxAt(sheet, 22, 0, 7, 8, "ROT03", "図形+15°＋文字本体-15°／中央・中央／20pt", 3); fill(shape, PALE);
        shape.getCTShape().getSpPr().getXfrm().setRot(15 * 60000);
        shape.getCTShape().getTxBody().getBodyPr().setRot(-15 * 60000); shape.setVerticalAlignment(VerticalAlignment.CENTER);
        run(paragraph(shape, TextAlign.CENTER), "文字の最終角度0度\n2つの回転を合成", SANS, 20, true, NAVY);
        shape = boxAt(sheet, 22, 9, 7, 8, "ROT04", "図形0°／bodyPr.rot=+90°／中央・中央／16pt", 3);
        shape.getCTShape().getTxBody().getBodyPr().setRot(90 * 60000); shape.setVerticalAlignment(VerticalAlignment.CENTER);
        run(paragraph(shape, TextAlign.CENTER), "日本語とABC\n文字90度", SANS, 16, false, NAVY);
    }
    private void theme() throws Exception {
        String fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
        String line = "<a:ln w=\"12700\">" + fill + "<a:prstDash val=\"solid\"/></a:ln>";
        String font = "<a:latin typeface=\"" + SANS + "\"/><a:ea typeface=\"" + SANS + "\"/><a:cs typeface=\"\"/>";
        String xml = """
                <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Shape Text Comparison">
                  <a:themeElements><a:clrScheme name="Explicit test colors">
                    <a:dk1><a:srgbClr val="19324D"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
                    <a:dk2><a:srgbClr val="36526D"/></a:dk2><a:lt2><a:srgbClr val="E9F5F2"/></a:lt2>
                    <a:accent1><a:srgbClr val="008A7B"/></a:accent1><a:accent2><a:srgbClr val="A44377"/></a:accent2>
                    <a:accent3><a:srgbClr val="2473C5"/></a:accent3><a:accent4><a:srgbClr val="C73949"/></a:accent4>
                    <a:accent5><a:srgbClr val="B77E22"/></a:accent5><a:accent6><a:srgbClr val="6866A3"/></a:accent6>
                    <a:hlink><a:srgbClr val="2473C5"/></a:hlink><a:folHlink><a:srgbClr val="A44377"/></a:folHlink>
                  </a:clrScheme><a:fontScheme name="Noto Japanese"><a:majorFont>%s</a:majorFont><a:minorFont>%s</a:minorFont></a:fontScheme>
                  <a:fmtScheme name="Simple"><a:fillStyleLst>%s</a:fillStyleLst><a:lnStyleLst>%s</a:lnStyleLst>
                    <a:effectStyleLst>%s</a:effectStyleLst><a:bgFillStyleLst>%s</a:bgFillStyleLst></a:fmtScheme>
                  </a:themeElements>
                </a:theme>
                """.formatted(font, font, fill.repeat(3), line.repeat(3), "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3), fill.repeat(3));
        book.getStylesSource().ensureThemesTable();
        try (var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) { book.getTheme().readFrom(stream); }
    }
    private String documentation() {
        return """
                # 図形内の文字の比較サンプル

                `shape-text.xlsx` は6シート・37個の編集可能なネイティブExcel図形です。画像を貼り付けて見た目を作ったものではありません。各図形の直前に設定値を記したセルを置き、図形名にもケースIDを付けています。

                元のExcelを開いて図形の設定と表示を確認し、同じファイルを変換した画像と比較します。Excel側で指定のNoto Sans CJK JP / Noto Serif CJK JPが利用できない場合、Excel自身もフォントを置き換えるため、同じフォントが使える環境で比較してください。

                基本設定は白背景・紺色文字、内余白9pt、左上配置、折返しあり、枠外clip、自動縮小なしです。表の記載があるケースだけ変更しています。文字サイズ・内余白・字間・段落間隔の単位はptです。テーマ色はExcel内でaccent1=`#008A7B`、accent2=`#A44377`に定義しています。

                取消線部分は元のExcelでは取り消した文字として見えますが、変換では既存ルールによりその文字自体を削除します。自動縮小・回転などはアプリ間で組版差が生じうるため、このファイルは設定を明示した比較用入力であり、Excelとの完全一致を保証するものではありません。

                | ID | 図形の範囲 | 指定した設定・比較点 |
                | --- | --- | --- |
                """ + String.join("\n", cases) + """


                `examples/ShapeTextWorkbook.java` から再生成できます。モジュールをビルドした後、`functions/excel2md` で実行します。

                ```sh
                javac -cp 'target/azure-functions/excel2md-local/lib/*' -d /tmp/excel2md-shape-fixture examples/ShapeTextWorkbook.java
                java -cp '/tmp/excel2md-shape-fixture:target/azure-functions/excel2md-local/lib/*' ShapeTextWorkbook samples/shape-text.xlsx
                ```

                生成器はExcelとこの設定一覧を同時に更新します。実行にExcelや外部画像の取得は不要です。図形の実際の見た目の確認にはExcelで開くか、変換後のPNG・Playgroundを使ってください。
                """;
    }
}
