import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;

/** Reproducible, original fixture for a real HTTP/Queue conversion. No formula evaluation. */
public final class FullFeatureWorkbook {
    private final XSSFWorkbook book = new XSSFWorkbook();
    private final XSSFCellStyle normal = style(false, false);
    private final XSSFCellStyle bold = style(true, false);
    private final XSSFCellStyle strike = style(false, true);
    private final XSSFCellStyle title = style(true, false);
    private final XSSFCellStyle header = style(true, false);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: FullFeatureWorkbook output.xlsx");
        FullFeatureWorkbook fixture = new FullFeatureWorkbook();
        try (XSSFWorkbook book = fixture.book) {
            fixture.title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            fixture.title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            XSSFFont titleFont = book.createFont();
            titleFont.setFontName("Noto Sans CJK JP"); titleFont.setBold(true); titleFont.setFontHeightInPoints((short)20);
            titleFont.setColor(IndexedColors.WHITE.getIndex()); fixture.title.setFont(titleFont);
            fixture.header.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            fixture.header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            fixture.textSheet(); fixture.tableSheet(); fixture.valueSheet();
            FullFeatureDrawings.populate(book);
            fixture.hiddenAndEmptySheets();
            book.getProperties().getCoreProperties().setTitle("excel2md 全機能テスト");
            book.getProperties().getCoreProperties().setDescription("Original reproducible fixture. Formula caches intentionally stored; no recalculation during conversion.");
            book.setActiveSheet(0);
            Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output.getParent());
            try (OutputStream out = Files.newOutputStream(output)) { book.write(out); }
            System.out.println(output + " (" + Files.size(output) + " bytes, " + book.getNumberOfSheets() + " sheets)");
        }
    }

    private XSSFCellStyle style(boolean isBold, boolean isStrike) {
        XSSFFont font = book.createFont(); font.setFontName("Noto Sans CJK JP");
        font.setFontHeightInPoints((short)11); font.setBold(isBold); font.setStrikeout(isStrike);
        XSSFCellStyle result = book.createCellStyle(); result.setFont(font); result.setVerticalAlignment(VerticalAlignment.CENTER);
        result.setWrapText(true); return result;
    }
    private XSSFSheet sheet(String name, String heading, String subtitle) {
        XSSFSheet s = book.createSheet(name); s.setDefaultRowHeightInPoints(25);
        for (int c=0; c<12; c++) s.setColumnWidth(c, 18*256);
        cell(s, 1, 1, heading).setCellStyle(title); s.getRow(0).setHeightInPoints(42);
        s.addMergedRegion(new CellRangeAddress(0,0,0,8));
        cell(s, 2, 1, subtitle); s.addMergedRegion(new CellRangeAddress(1,1,0,8));
        s.createFreezePane(0,2); return s;
    }
    private XSSFCell cell(XSSFSheet s, int row, int col, String value) {
        XSSFRow r = s.getRow(row-1); if (r == null) r = s.createRow(row-1);
        XSSFCell c = r.getCell(col-1); if (c == null) c = r.createCell(col-1);
        c.setCellStyle(normal); c.setCellValue(value); return c;
    }
    private void link(XSSFCell c, HyperlinkType type, String address) {
        Hyperlink link = book.getCreationHelper().createHyperlink(type); link.setAddress(address); c.setHyperlink(link);
    }
    private void rich(XSSFCell c, String value, String part, boolean isBold, boolean isStrike) {
        XSSFRichTextString rich = new XSSFRichTextString(value);
        XSSFFont font = book.createFont(); font.setFontName("Noto Sans CJK JP");
        font.setBold(isBold); font.setStrikeout(isStrike);
        int start = value.indexOf(part); rich.applyFont(start, start+part.length(), font); c.setCellValue(rich);
    }
    private void textSheet() {
        XSSFSheet s = sheet("01_文章と書式", "文章と書式の変換テスト", "見出しはシート名のH1だけ。文章・部分書式・リンク・除外対象を確認します。");
        cell(s,4,1,"日本語の文章です。漢字・ひらがな・カタカナ・全角記号をそのまま残します。");
        cell(s,5,1,"同じ段落の次の行です。");
        cell(s,7,1,"空行の後は別の段落です。");
        cell(s,9,1,"左のセル"); cell(s,9,4,"右のセル：罫線がないため表にはしません。");
        cell(s,11,1,"セル全体の太字").setCellStyle(bold);
        rich(cell(s,12,1,""), "通常の文字と部分太字の文字", "部分太字", true, false);
        XSSFCell inherited = cell(s,13,1,""); inherited.setCellStyle(bold);
        rich(inherited, "太字の中に通常文字を明示します", "通常文字", false, false);
        rich(cell(s,15,1,""), "担当：DELETE_PARTIAL_TOKEN山田 花子", "DELETE_PARTIAL_TOKEN", false, true);
        cell(s,16,1,"DELETE_CELL_TOKEN").setCellStyle(strike);
        XSSFCell removedLink = cell(s,17,1,"DELETE_LINK_TOKEN"); removedLink.setCellStyle(strike);
        link(removedLink, HyperlinkType.URL, "https://example.invalid/DELETE_URL_TOKEN");
        XSSFCell removedFormula = cell(s,18,1,""); removedFormula.setCellStyle(strike);
        removedFormula.setCellFormula("HYPERLINK(\"https://example.invalid/DELETE_FORMULA_URL_TOKEN\",\"DELETE_FORMULA_LABEL_TOKEN\")");
        removedFormula.setCellValue("DELETE_FORMULA_CACHE_TOKEN");
        XSSFCell https = cell(s,20,1,"Apache POI 公式サイト"); link(https, HyperlinkType.URL,"https://poi.apache.org/");
        XSSFCell http = cell(s,21,1,"HTTPリンク"); link(http, HyperlinkType.URL,"http://example.com/guide");
        XSSFCell mail = cell(s,22,1,"メール窓口"); link(mail, HyperlinkType.EMAIL,"mailto:help@example.com");
        XSSFCell boldLink = cell(s,23,1,"太字のリンク"); boldLink.setCellStyle(bold); link(boldLink,HyperlinkType.URL,"https://example.com/bold");
        XSSFCell blank = cell(s,24,1,""); link(blank,HyperlinkType.URL,"https://example.com/blank-label");
        XSSFCell internal = cell(s,26,1,"内部リンクは表示文字のみ"); link(internal,HyperlinkType.DOCUMENT,"'02_罫線の表'!A1");
        XSSFCell file = cell(s,27,1,"ファイルリンクは表示文字のみ"); link(file,HyperlinkType.FILE,"file:///not-fetched/manual.pdf");
        XSSFCell unsafe = cell(s,28,1,"不正なリンクは表示文字のみ"); link(unsafe,HyperlinkType.URL,"javascript:alert(1)");
        cell(s,30,1,"# 見出し風の文字、*強調風*、[リンク風](https://example.invalid/)、縦棒 | を文字として保持");
        cell(s,31,1,"<script>window.__fixtureInjected = true</script> を文字として保持");
        cell(s,32,1,"1. 番号付きリスト風の文字");
        cell(s,33,1,"セル内の1行目\nセル内の2行目"); s.getRow(32).setHeightInPoints(42);
        cell(s,35,1,"結合セルの文章は左上だけを出力します。"); s.addMergedRegion(new CellRangeAddress(34,34,0,4));
        cell(s,37,1,"HIDDEN_ROW_TOKEN"); s.getRow(36).setZeroHeight(true);
        cell(s,38,12,"HIDDEN_COLUMN_TOKEN"); s.setColumnHidden(11,true);
        cell(s,40,1,"取消線、非表示行・列・シートの文字は出力に含めません。");
    }
    private void grid(XSSFSheet s, int r1, int c1, String[][] values, boolean oneSided) {
        for (int r=0;r<values.length;r++) for(int c=0;c<values[r].length;c++) {
            XSSFCell v = cell(s,r1+r,c1+c,values[r][c]);
            XSSFCellStyle st = book.createCellStyle(); st.cloneStyleFrom(r==0 ? header : normal);
            st.setBorderBottom(BorderStyle.THIN); st.setBorderRight(BorderStyle.THIN);
            if (!oneSided || r==0) st.setBorderTop(BorderStyle.THIN);
            if (!oneSided || c==0) st.setBorderLeft(BorderStyle.THIN);
            v.setCellStyle(st);
        }
    }
    private XSSFCell at(XSSFSheet s,int r,int c) { return s.getRow(r-1).getCell(c-1); }
    private void tableSheet() {
        XSSFSheet s = sheet("02_罫線の表", "罫線から表を検出", "閉じた格子だけを表にします。元の先頭行、空の行・列、結合セルも確認します。");
        grid(s,4,1,new String[][]{{"項目","数量","","備考"},{"売上","12","","日本語の備考"},{"","","",""},{"予算","0","","上限 | 下限"},{"取消線","","","残る説明"},{"リンク","","",""}},false);
        at(s,8,2).setCellValue("DELETE_TABLE_TOKEN");
        XSSFCellStyle deleted = book.createCellStyle(); deleted.cloneStyleFrom(at(s,8,2).getCellStyle()); deleted.setFont(book.getFontAt(strike.getFontIndex())); at(s,8,2).setCellStyle(deleted);
        rich(at(s,5,4),"通常と重要の混在", "重要",true,false);
        at(s,9,4).setCellValue("表のリンク\n2行目"); link(at(s,9,4),HyperlinkType.URL,"https://example.com/table");
        grid(s,4,6,new String[][]{{"別の表","値","状態"},{"東日本","100","OK"},{"西日本","200","OK"},{"合計","300","完了"}},false);
        grid(s,13,1,new String[][]{{"片側罫線","値","備考"},{"左上","1","共有境界を片側だけ設定"},{"中段","2",""},{"最終行","3","閉じた格子"}},true);
        grid(s,13,6,new String[][]{{"可視の項目","値"},{"非表示の行","HIDDEN_TABLE_ROW_TOKEN"},{"表示される行","42"},{"末尾","43"}},false); s.getRow(13).setZeroHeight(true);
        grid(s,20,1,new String[][]{{"結合した見出し","",""},{"A項目","10","完了"},{"B項目","20","確認中"},{"C項目","30","完了"}},false);
        s.addMergedRegion(new CellRangeAddress(19,19,0,2));
        for(int c=1;c<=3;c++) {
            XSSFCell v=at(s,20,c); XSSFCellStyle st=book.createCellStyle(); st.cloneStyleFrom(v.getCellStyle());
            if(c>1) st.setBorderLeft(BorderStyle.NONE); if(c<3) st.setBorderRight(BorderStyle.NONE); v.setCellStyle(st);
        }
        grid(s,27,1,new String[][]{{"商品名","個数","単位"},{"りんご","3","個"},{"みかん","5","個"},{"バナナ","2","本"}},false);
        XSSFTable nativeTable=s.createTable(new AreaReference("A27:C30",book.getSpreadsheetVersion())); nativeTable.setName("BorderedInventory"); nativeTable.setDisplayName("BorderedInventory");
        String[][] styleOnly={{"スタイルだけの表","値","備考"},{"データA","10","直接罫線なし"},{"データB","20","文章として出力"},{"データC","30",""}};
        for(int r=0;r<styleOnly.length;r++) for(int c=0;c<3;c++) cell(s,27+r,6+c,styleOnly[r][c]);
        XSSFTable styled=s.createTable(new AreaReference("F27:H30",book.getSpreadsheetVersion())); styled.setName("StyledOnlyInventory"); styled.setDisplayName("StyledOnlyInventory"); styled.setStyleName("TableStyleMedium2");
        grid(s,34,1,new String[][]{{"単一セルの囲みは文章"}},false);
        grid(s,34,6,new String[][]{{"閉じていない罫線","値","備考"},{"途中の行","10","文章として保持"},{"最終の行","20","右下の外周が欠ける"}},false);
        XSSFCell corner=at(s,36,8); XSSFCellStyle open=book.createCellStyle();open.cloneStyleFrom(corner.getCellStyle());open.setBorderBottom(BorderStyle.NONE);open.setBorderRight(BorderStyle.NONE);corner.setCellStyle(open);
        XSSFCell underline=cell(s,38,1,"下線だけでは表にしません");XSSFCellStyle under=book.createCellStyle();under.cloneStyleFrom(normal);under.setBorderBottom(BorderStyle.THIN);underline.setCellStyle(under);
        grid(s,40,1,new String[][]{{"","",""},{"","",""}},false);
        cell(s,44,1,"条件付き書式の見た目は再評価せず、通常の保存値と直接書式を使います。");
        XSSFCell condition=cell(s,45,1,"");condition.setCellValue(100);
        SheetConditionalFormatting cf=s.getSheetConditionalFormatting();ConditionalFormattingRule rule=cf.createConditionalFormattingRule(ComparisonOperator.GT,"0");
        rule.createFontFormatting().setFontStyle(false,true);cf.addConditionalFormatting(new CellRangeAddress[]{CellRangeAddress.valueOf("A45")},rule);
    }
    private XSSFCell value(XSSFSheet s,int row,String label,double number,String format) {
        cell(s,row,1,label); XSSFCell v=cell(s,row,3,"");v.setCellValue(number);
        XSSFCellStyle st=book.createCellStyle();st.cloneStyleFrom(normal);st.setDataFormat(book.createDataFormat().getFormat(format));v.setCellStyle(st);return v;
    }
    private void valueSheet() {
        XSSFSheet s=sheet("03_表示値と数式","表示値と数式キャッシュ","保存済みの表示値を使用します。数式を再計算せず、外部のデータを取得しません。");
        value(s,4,"先頭のゼロ",42,"00000"); value(s,5,"桁区切り",12345.67,"#,##0.00");
        value(s,6,"通貨",12800,"\"¥\"#,##0"); value(s,7,"パーセント",.125,"0.0%");
        XSSFCell date=value(s,8,"日付",0,"yyyy/mm/dd");date.setCellValue(LocalDateTime.of(2026,9,9,0,0));
        XSSFCell time=value(s,9,"時刻",0,"hh:mm");time.setCellValue(LocalDateTime.of(2026,9,9,13,45));
        value(s,10,"負数の表示",-1234,"#,##0;(#,##0)"); value(s,11,"指数表記",1230000,"0.00E+00");
        cell(s,12,1,"真偽値");cell(s,12,3,"").setCellValue(true);
        cell(s,13,1,"エラー値");cell(s,13,3,"").setCellErrorValue(FormulaError.DIV0.getCode());
        value(s,14,"ゼロも残す",0,"0");cell(s,15,1,"空セルは空のまま");cell(s,15,3,"");
        cell(s,17,1,"保存キャッシュ999（数式は1+2）");XSSFCell cached=cell(s,17,3,"");cached.setCellFormula("1+2");cached.setCellValue(999);
        XSSFCell formatted=value(s,18,"数式の通貨表示",12345,"\"¥\"#,##0");formatted.setCellFormula("10000+2345");formatted.setCellValue(12345);
        cell(s,20,1,"キャッシュなしの数式");XSSFCell missing=cell(s,20,3,"");missing.setCellFormula("1+2");missing.getCTCell().unsetV();
        cell(s,22,1,"リンク先と表示が文字列リテラル");XSSFCell literal=cell(s,22,3,"");literal.setCellFormula("HYPERLINK(\"https://poi.apache.org/spreadsheet/\",\"数式の公式リンク\")");literal.getCTCell().unsetV();
        cell(s,23,1,"リンク先は固定、表示は保存値");XSSFCell dynamicLabel=cell(s,23,3,"");dynamicLabel.setCellFormula("HYPERLINK(\"https://example.com/cached\",A4)");dynamicLabel.setCellValue("保存されたリンク表示");
        cell(s,24,1,"リンク先が動的ならリンク化しない");XSSFCell dynamic=cell(s,24,3,"");dynamic.setCellFormula("HYPERLINK(A4,\"動的なリンク表示\")");dynamic.setCellValue("動的なリンク表示");
        cell(s,26,1,"IMAGE関数は取得しない");XSSFCell image=cell(s,26,3,"");image.setCellFormula("_xlfn.IMAGE(\"https://example.invalid/not-fetched.png\")");image.getCTCell().unsetV();
        cell(s,28,1,"取消線がある数値も除外");XSSFCell removed=cell(s,28,3,"");removed.setCellValue(987654321);removed.setCellStyle(strike);
    }
    private void hiddenAndEmptySheets() {
        XSSFSheet hidden=book.createSheet("非表示シート");cell(hidden,1,1,"HIDDEN_SHEET_TOKEN");book.setSheetVisibility(book.getSheetIndex(hidden),SheetVisibility.HIDDEN);
        XSSFSheet veryHidden=book.createSheet("VeryHiddenシート");cell(veryHidden,1,1,"VERY_HIDDEN_SHEET_TOKEN");book.setSheetVisibility(book.getSheetIndex(veryHidden),SheetVisibility.VERY_HIDDEN);
        book.createSheet("空シート");XSSFSheet deleted=book.createSheet("取消線のみ");cell(deleted,1,1,"DELETED_SHEET_TOKEN").setCellStyle(strike);
        XSSFSheet emptyBorders=book.createSheet("罫線のみ");grid(emptyBorders,1,1,new String[][]{{"",""},{"",""}},false);
    }
}
