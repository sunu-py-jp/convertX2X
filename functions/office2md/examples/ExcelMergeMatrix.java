import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Editable Excel fixture covering header colors and explicit merged-cell layouts. */
public final class ExcelMergeMatrix {
    private record Case(String id, String sheet, String title, String category, String note,
            String[][] values, Set<Integer> filledRows, Map<Integer, Set<Integer>> filledCells,
            List<String> merges, Set<Integer> hiddenRows, Set<Integer> hiddenColumns,
            Set<String> borderlessCells) { }

    private ExcelMergeMatrix() { }

    public static void main(String[] args) throws Exception {
        Path destination = args.length == 0
                ? Path.of("docs/APIDocs/office2md/examples/excel-merge-matrix")
                : Path.of(args[0]);
        Files.createDirectories(destination);
        List<Case> cases = cases();
        List<Map<String, Object>> manifestCases = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.getProperties().getCoreProperties().setCreated(Optional.of(new Date(0)));
            Font plainFont = workbook.createFont();
            plainFont.setFontName("Noto Sans CJK JP");
            plainFont.setFontHeightInPoints((short) 11);
            Font filledFont = workbook.createFont();
            filledFont.setFontName("Noto Sans CJK JP");
            filledFont.setFontHeightInPoints((short) 11);
            filledFont.setBold(true);
            CellStyle plain = gridStyle(workbook, plainFont, null, BorderStyle.THIN);
            CellStyle filled = gridStyle(workbook, filledFont, IndexedColors.LIGHT_CORNFLOWER_BLUE,
                    BorderStyle.THIN);
            CellStyle plainNoBorder = gridStyle(workbook, plainFont, null, BorderStyle.NONE);
            CellStyle filledNoBorder = gridStyle(workbook, filledFont, IndexedColors.LIGHT_CORNFLOWER_BLUE,
                    BorderStyle.NONE);
            for (Case scenario : cases) {
                XSSFSheet sheet = workbook.createSheet(scenario.sheet());
                sheet.setDisplayGridlines(false);
                sheet.setDefaultRowHeightInPoints(26);
                int width = scenario.values()[0].length;
                for (int col = 0; col < width; col++) sheet.setColumnWidth(col, 25 * 256);
                for (int row = 0; row < scenario.values().length; row++) {
                    Row physical = sheet.createRow(row);
                    physical.setHeightInPoints(30);
                    for (int col = 0; col < width; col++) {
                        Cell cell = physical.createCell(col);
                        cell.setCellValue(scenario.values()[row][col]);
                        boolean directFill = scenario.filledRows().contains(row)
                                || scenario.filledCells().getOrDefault(row, Set.of()).contains(col);
                        String address = CellReference.convertNumToColString(col) + (row + 1);
                        boolean borderless = scenario.borderlessCells().contains(address);
                        cell.setCellStyle(borderless
                                ? (directFill ? filledNoBorder : plainNoBorder)
                                : (directFill ? filled : plain));
                    }
                }
                for (String range : scenario.merges()) sheet.addMergedRegion(CellRangeAddress.valueOf(range));
                for (int row : scenario.hiddenRows()) sheet.getRow(row).setZeroHeight(true);
                for (int col : scenario.hiddenColumns()) sheet.setColumnHidden(col, true);
                manifestCases.add(describe(sheet, scenario));
            }
            writeCanonicalWorkbook(workbook, destination.resolve("input.xlsx"));
        }
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1);
        manifest.put("description", "Excelの結合セル・直接塗り・複数行ヘッダーの入力例。変換後Markdownは実際のOffice2MD出力を参照してください。");
        manifest.put("input", "input.xlsx");
        manifest.put("cases", manifestCases);
        json.writeValue(destination.resolve("cases.json").toFile(), manifest);
        System.out.println("Generated " + cases.size() + " Excel merge/header cases in " + destination);
    }

    private static void writeCanonicalWorkbook(XSSFWorkbook workbook, Path destination) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        workbook.write(raw);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(raw.toByteArray()));
                ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
            ZipEntry member;
            while ((member = input.getNextEntry()) != null) {
                ZipEntry canonical = new ZipEntry(member.getName());
                canonical.setTime(0L);
                output.putNextEntry(canonical);
                input.transferTo(output);
                output.closeEntry();
                input.closeEntry();
            }
        }
    }

    private static CellStyle gridStyle(XSSFWorkbook workbook, Font font, IndexedColors fill,
            BorderStyle border) {
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setBorderTop(border);
        style.setBorderBottom(border);
        style.setBorderLeft(border);
        style.setBorderRight(border);
        style.setWrapText(true);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (fill != null) {
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setFillForegroundColor(fill.getIndex());
        }
        return style;
    }

    private static Map<String, Object> describe(XSSFSheet sheet, Case scenario) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", scenario.id());
        result.put("sheet", scenario.sheet());
        result.put("title", scenario.title());
        result.put("category", scenario.category());
        result.put("note", scenario.note());
        result.put("tableRange", "A1:" + CellReference.convertNumToColString(scenario.values()[0].length - 1)
                + scenario.values().length);
        result.put("mergedRanges", sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList());
        result.put("hiddenRows", scenario.hiddenRows().stream().sorted().map(row -> row + 1).toList());
        result.put("hiddenColumns", scenario.hiddenColumns().stream().sorted()
                .map(CellReference::convertNumToColString).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Row row : sheet) {
            LinkedHashMap<String, Object> rowInfo = new LinkedHashMap<>();
            rowInfo.put("number", row.getRowNum() + 1);
            rowInfo.put("hidden", row.getZeroHeight());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Cell cell : row) {
                LinkedHashMap<String, Object> cellInfo = new LinkedHashMap<>();
                cellInfo.put("address", cell.getAddress().formatAsString());
                cellInfo.put("value", cell.getStringCellValue());
                cellInfo.put("directFill", cell.getCellStyle().getFillPattern() != FillPatternType.NO_FILL);
                LinkedHashMap<String, Boolean> borders = new LinkedHashMap<>();
                borders.put("top", cell.getCellStyle().getBorderTop() != BorderStyle.NONE);
                borders.put("bottom", cell.getCellStyle().getBorderBottom() != BorderStyle.NONE);
                borders.put("left", cell.getCellStyle().getBorderLeft() != BorderStyle.NONE);
                borders.put("right", cell.getCellStyle().getBorderRight() != BorderStyle.NONE);
                cellInfo.put("directBorders", borders);
                CellRangeAddress merge = mergeAt(sheet, cell.getRowIndex(), cell.getColumnIndex());
                if (merge != null) {
                    cellInfo.put("mergedRange", merge.formatAsString());
                    cellInfo.put("mergeAnchor", merge.getFirstRow() == cell.getRowIndex()
                            && merge.getFirstColumn() == cell.getColumnIndex());
                }
                cells.add(cellInfo);
            }
            rowInfo.put("cells", cells);
            rows.add(rowInfo);
        }
        result.put("rows", rows);
        return result;
    }

    private static CellRangeAddress mergeAt(XSSFSheet sheet, int row, int column) {
        for (CellRangeAddress range : sheet.getMergedRegions())
            if (range.isInRange(row, column)) return range;
        return null;
    }

    private static Case scenario(String id, String sheet, String title, String category, String note,
            String[][] values, Set<Integer> filledRows, Map<Integer, Set<Integer>> filledCells,
            String... merges) {
        if (sheet.length() > 31) throw new IllegalArgumentException("Excelのシート名が長すぎます: " + sheet);
        int columns = values[0].length;
        if (Arrays.stream(values).anyMatch(row -> row.length != columns))
            throw new IllegalArgumentException("列数が揃っていません: " + sheet);
        return new Case(id, sheet, title, category, note, values, filledRows, filledCells,
                List.of(merges), Set.of(), Set.of(), Set.of());
    }

    private static Case withHiddenRows(Case scenario, int... zeroBasedRows) {
        Set<Integer> hidden = Arrays.stream(zeroBasedRows).boxed().collect(java.util.stream.Collectors.toSet());
        return new Case(scenario.id(), scenario.sheet(), scenario.title(), scenario.category(), scenario.note(),
                scenario.values(), scenario.filledRows(), scenario.filledCells(), scenario.merges(),
                hidden, scenario.hiddenColumns(), scenario.borderlessCells());
    }

    private static Case withoutBorders(Case scenario, String... addresses) {
        for (String address : addresses) {
            CellReference cell = new CellReference(address);
            if (cell.getRow() < 0 || cell.getRow() >= scenario.values().length
                    || cell.getCol() < 0 || cell.getCol() >= scenario.values()[0].length) {
                throw new IllegalArgumentException("罫線なしセルが入力範囲外です: " + address);
            }
        }
        return new Case(scenario.id(), scenario.sheet(), scenario.title(), scenario.category(), scenario.note(),
                scenario.values(), scenario.filledRows(), scenario.filledCells(), scenario.merges(),
                scenario.hiddenRows(), scenario.hiddenColumns(), Set.of(addresses));
    }

    private static List<Case> cases() {
        return List.of(
            scenario("C01", "01_単行_色あり_結合なし", "色付き1行ヘッダー・結合なし", "single-colored",
                "先頭行の直接塗りをヘッダーとする基本形。",
                new String[][] {{"商品", "数量", "単価"}, {"りんご", "2", "100"}, {"みかん", "3", "80"}},
                Set.of(0), Map.of()),
            scenario("C02", "02_複行_色あり_結合なし", "色付き2行ヘッダー・結合なし", "multi-colored",
                "連続する2行の見出しを列ごとに階層化する。",
                new String[][] {{"分類", "数量", "売上"}, {"商品", "2025年", "2025年"}, {"りんご", "2", "200"}},
                Set.of(0, 1), Map.of()),
            scenario("C03", "03_複行3段_色あり", "色付き3行ヘッダー", "multi-colored",
                "先頭から3行連続して直接塗りがある。",
                new String[][] {{"地域", "販売", "販売"}, {"都道府県", "2025年", "2024年"},
                    {"名称", "数量", "数量"}, {"東京", "12", "10"}},
                Set.of(0, 1, 2), Map.of()),
            scenario("C04", "04_全行_色なし", "色なし・結合なし", "no-colored-header",
                "先頭行が無色なら空ヘッダーを生成し、元の先頭行も明細として残す。",
                new String[][] {{"商品", "数量", "単価"}, {"りんご", "2", "100"}, {"みかん", "3", "80"}},
                Set.of(), Map.of()),
            scenario("C05", "05_先頭無色_次行色あり", "先頭無色・次行だけ色付き", "no-colored-header",
                "途中から色が付いてもヘッダー判定を再開しない。",
                new String[][] {{"先頭明細", "A", "1"}, {"色付き明細", "B", "2"}, {"末尾明細", "C", "3"}},
                Set.of(1), Map.of()),
            scenario("C06", "06_色_無色_色", "色付き→無色→色付き", "header-run-stops",
                "先頭からの連続色付き行だけがヘッダー。3行目の色は明細扱い。",
                new String[][] {{"商品", "数量", "単価"}, {"りんご", "2", "100"}, {"色付き明細", "3", "80"}},
                Set.of(0, 2), Map.of()),
            scenario("C07", "07_先頭1セルだけ色あり", "先頭の1セルだけ色付き", "single-cell-fill",
                "表の先頭行の検出範囲内に直接塗りが1セルでもあればヘッダー。",
                new String[][] {{"商品", "数量", "単価"}, {"りんご", "2", "100"}},
                Set.of(), Map.of(0, Set.of(0))),
            scenario("C08", "08_単行_上だけ横結合", "1行ヘッダーだけ横結合", "shared-header",
                "A1:B1の共有見出しを分かれた明細列へ引き継ぐ。",
                new String[][] {{"連絡先", "", "状態"}, {"03-1234", "a@example.com", "有効"},
                    {"06-5678", "b@example.com", "保留"}},
                Set.of(0), Map.of(), "A1:B1"),
            scenario("C09", "09_単行_全幅横結合", "1行ヘッダーが全列横結合", "full-width-header",
                "見出しのみA1:C1を結合。明細は3列に分かれる。",
                new String[][] {{"2025年度販売実績", "", ""}, {"東日本", "100", "20"}, {"西日本", "80", "15"}},
                Set.of(0), Map.of(), "A1:C1"),
            scenario("C10", "10_複行_上段横結合", "2行ヘッダー・上段だけ横結合", "multi-shared-header",
                "上段A1:B1の共有見出しと下段の個別見出しを合成する。",
                new String[][] {{"連絡先", "", "状態"}, {"電話", "メール", "区分"},
                    {"03-1234", "a@example.com", "有効"}},
                Set.of(0, 1), Map.of(), "A1:B1"),
            scenario("C11", "11_複行_縦横結合", "2行ヘッダー・縦結合と横結合", "vertical-header",
                "A1:A2の縦結合とB1:C1の横結合を同じ見出し内で扱う。",
                new String[][] {{"部門", "売上", ""}, {"", "2025年", "2024年"}, {"営業", "120", "95"}},
                Set.of(0, 1), Map.of(), "A1:A2", "B1:C1"),
            scenario("C12", "12_全行同幅_単行見出し", "全行で同じ幅の横結合・1行ヘッダー", "collapsed-logical-column",
                "A:Bを毎行同じ幅で結合し、論理的な1列として扱う。",
                new String[][] {{"項目", "", "状態"}, {"申請", "", "完了"}, {"確認", "", "進行中"}},
                Set.of(0), Map.of(), "A1:B1", "A2:B2", "A3:B3"),
            scenario("C13", "13_全行同幅_複行見出し", "全行で同じ幅の横結合・2行ヘッダー", "collapsed-logical-column",
                "A:Bを毎行結合し、2行の見出しを1列に集約する。",
                new String[][] {{"申請", "", "処理"}, {"項目", "", "状態"}, {"購入", "", "完了"}},
                Set.of(0, 1), Map.of(), "A1:B1", "A2:B2", "A3:B3"),
            scenario("C14", "14_明細だけ横結合", "明細の一部だけ横結合", "body-horizontal-merge",
                "先頭ヘッダーは独立列。2行目のA:Bだけ結合しても列数を維持する。",
                new String[][] {{"項目", "値", "状態"}, {"共通説明", "", "完了"}, {"個別", "20", "進行中"}},
                Set.of(0), Map.of(), "A2:B2"),
            scenario("C15", "15_明細だけ縦結合", "明細の縦結合", "body-vertical-merge",
                "A2:A3の値は2行分にまたがる。後続行の扱いを変換結果で確認する。",
                new String[][] {{"部門", "担当", "状態"}, {"営業", "佐藤", "進行中"}, {"", "鈴木", "完了"}},
                Set.of(0), Map.of(), "A2:A3"),
            scenario("C16", "16_複行_ずれた横結合", "複数行ヘッダーで横結合位置がずれる", "offset-header-merges",
                "1行目A:BとC:D、2行目B:Cが横結合。推測で見出しを増やさない。",
                new String[][] {{"顧客", "", "処理", ""}, {"個人", "共通項目", "", "状態"},
                    {"田中", "有効", "確認済み", "完了"}},
                Set.of(0, 1), Map.of(), "A1:B1", "C1:D1", "B2:C2"),
            scenario("C17", "17_全行色付き", "全行に直接塗りがある", "all-colored",
                "全行が連続した色付き見出しと判定され、明細は残らない。",
                new String[][] {{"商品", "数量", "単価"}, {"りんご", "2", "100"}, {"みかん", "3", "80"}},
                Set.of(0, 1, 2), Map.of()),
            scenario("C18", "18_無色_全行同幅横結合", "無色の先頭行・全行で同じ幅の横結合", "no-header-collapsed-column",
                "ヘッダーは空のまま、A:Bの論理列は全行で同じ幅なのでまとめる。",
                new String[][] {{"項目", "", "状態"}, {"申請", "", "完了"}, {"確認", "", "進行中"}},
                Set.of(), Map.of(), "A1:B1", "A2:B2", "A3:B3"),
            withHiddenRows(scenario("C19", "19_隠れた分割行", "非表示の分割行を挟む同幅結合", "hidden-row-collapsed-column",
                "2行目だけA/Bが分かれるが非表示。表示行ではA:Bの結合が揃う。",
                new String[][] {{"項目", "", "状態"}, {"非表示A", "非表示B", "非表示"},
                    {"申請", "", "完了"}, {"確認", "", "進行中"}},
                Set.of(0, 1), Map.of(), "A1:B1", "A3:B3", "A4:B4"), 1),
            scenario("C20", "20_隣接する同幅結合", "隣り合う2組の同幅結合", "adjacent-collapsed-columns",
                "毎行A:BとC:Dが結合されるので、2つの論理列にまとめる。",
                new String[][] {{"項目", "", "状態", ""}, {"申請", "", "完了", ""},
                    {"確認", "", "進行中", ""}},
                Set.of(0), Map.of(), "A1:B1", "C1:D1", "A2:B2", "C2:D2", "A3:B3", "C3:D3"),
            withoutBorders(scenario("C21", "21_左上無罫線のマトリックス", "左上だけ罫線のないマトリックス", "borderless-matrix-corner",
                "A1は空欄でセル自体に罫線がない。罫線付きの1月・2月を塗りなしでも1段の見出しにする。",
                new String[][] {{"", "1月", "2月"}, {"東京", "10", "12"}, {"大阪", "8", "9"}},
                Set.of(), Map.of()), "A1"),
            withoutBorders(scenario("C22", "22_通常表に続くマトリックス", "通常表に続く、角だけ罫線のないマトリックス", "attached-borderless-matrix",
                "A:Cの通常列とD:Fのマトリックスが横につながる。D1は空欄でセル自体に罫線がなく、全体を1表として扱う。",
                new String[][] {{"ID", "担当", "状態", "", "1月", "2月"},
                    {"1001", "田中", "完了", "東京", "10", "12"},
                    {"1002", "佐藤", "進行中", "大阪", "8", "9"}},
                Set.of(0), Map.of()), "D1"),
            withoutBorders(scenario("C23", "23_注記と3列結合の見出し", "無罫線の注記と3列幅の結合見出し", "borderless-notes-group-header",
                "A1・B1の文字は無罫線の注記。C1:E1の売上見出しは3列幅の明示結合で、下段の個別見出しと合わせる。",
                new String[][] {{"集計対象: 店舗", "単位: 千円", "売上", "", ""},
                    {"ID", "場所", "1月", "2月", "3月"},
                    {"001", "東京", "10", "12", "14"},
                    {"002", "大阪", "8", "9", "11"}},
                Set.of(), Map.of(), "C1:E1"), "A1", "B1"),
            withoutBorders(scenario("C24", "24_注記と縦横結合の3段見出し", "無罫線の注記と縦横結合を含む3段見出し", "borderless-notes-multi-level-matrix",
                "A1・B1の文字は無罫線の注記。売上はC1:D1だけの結合で、E1・F1は無罫線。A2:A3・B2:B3の縦結合と、月の横結合で3段を表す。上段結合の幅は固定規則ではなく入力の結合範囲に従う。",
                new String[][] {{"集計対象: 店舗", "単位: 千円", "売上", "", "", ""},
                    {"ID", "場所", "前半", "後半", "前半", "後半"},
                    {"", "", "1月", "", "2月", ""},
                    {"001", "東京", "10", "12", "10", "12"},
                    {"002", "大阪", "8", "9", "8", "9"}},
                Set.of(), Map.of(), "C1:D1", "A2:A3", "B2:B3", "C3:D3", "E3:F3"),
                "A1", "B1", "E1", "F1")
        );
    }
}
