package com.convertx2x.office2md.conversion;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDefinedName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ExcelMarkdownServiceTest {
    private static final ConversionLimits DEFAULTS = ConversionLimits.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Workbook book(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }
    private static byte[] bytes(Workbook book) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); book.write(out); return out.toByteArray();
    }
    private static ConversionResult convert(Workbook book) throws IOException { return new ExcelMarkdownService(DEFAULTS).convert(bytes(book), book instanceof XSSFWorkbook ? "資料.xlsx" : "資料.xls"); }
    private static String markdown(ConversionResult result) throws IOException { return Files.readString(result.files().get("document.md")); }
    private static JsonNode report(ConversionResult result) throws IOException { return JSON.readTree(result.files().get("report.json").toFile()); }
    static Cell cell(Sheet sheet, int row, int column, String value) {
        Row r = sheet.getRow(row); if (r == null) r = sheet.createRow(row);
        Cell c = r.getCell(column); if (c == null) c = r.createCell(column);
        c.setCellValue(value); return c;
    }
    static CellStyle grid(Workbook book) {
        CellStyle s = book.createCellStyle(); s.setBorderTop(BorderStyle.THIN); s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN); s.setBorderRight(BorderStyle.THIN); return s;
    }
    static void table(Sheet sheet, int r1, int r2, int c1, int c2) {
        CellStyle style = grid(sheet.getWorkbook());
        for (int r = r1; r <= r2; r++) for (int c = c1; c <= c2; c++) {
            Row row = sheet.getRow(r); if (row == null) row = sheet.createRow(r);
            Cell cell = row.getCell(c); if (cell == null) cell = row.createCell(c); cell.setCellStyle(style);
        }
    }
    static void colorRow(Sheet sheet, int row, int firstColumn, int lastColumn, short color) {
        for (int column = firstColumn; column <= lastColumn; column++) {
            Cell cell = sheet.getRow(row).getCell(column);
            CellStyle style = sheet.getWorkbook().createCellStyle();
            style.cloneStyleFrom(cell.getCellStyle());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setFillForegroundColor(color);
            cell.setCellStyle(style);
        }
    }
    static Name namedRange(Workbook book, Sheet sheet, String name, String formula, boolean sheetScoped) {
        Name defined = book.createName();
        if (sheetScoped) defined.setSheetIndex(book.getSheetIndex(sheet));
        defined.setNameName(name);
        defined.setRefersToFormula(formula);
        return defined;
    }
    static CTDefinedName xmlName(Name name) throws Exception {
        var method = XSSFName.class.getDeclaredMethod("getCTName");
        method.setAccessible(true);
        return (CTDefinedName) method.invoke(name);
    }
    static List<JsonNode> tableBlocks(ConversionResult result) throws IOException {
        List<JsonNode> tables = new ArrayList<>();
        for (JsonNode block : report(result).path("blocks"))
            if ("table".equals(block.path("type").asText())) tables.add(block);
        return tables;
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void sheetHeadingsAndPlainRowsHaveNoInventedStructure(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("売上"); cell(sheet,0,0,"大きなタイトル"); cell(sheet,0,4,"同じ行");
            cell(sheet,1,0,"2行目\nセル内改行"); cell(sheet,4,0,"次の段落");
            book.createSheet("空白"); Sheet second = book.createSheet("補足"); cell(second,0,0,"内容");
            try (ConversionResult result = convert(book)) {
                assertEquals("# [売上] シート\n\n大きなタイトル　同じ行  \n2行目  \nセル内改行\n\n次の段落\n\n# [補足] シート\n\n内容\n", markdown(result));
                assertEquals(2, result.sectionCount()); assertTrue(report(result).toString().contains("EMPTY_SHEET_OMITTED"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void bordersKeepEveryOriginalRowAndBlankCells(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("一覧"); table(sheet,1,4,1,3);
            cell(sheet,1,1,"商品"); cell(sheet,1,3,"価格"); cell(sheet,2,1,"りんご"); cell(sheet,4,3,"末尾");
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("|  |  |  |\n| --- | --- | --- |\n| 商品 |  | 価格 |\n| りんご |  |  |\n|  |  |  |\n|  |  | 末尾 |"));
                JsonNode block = report(result).path("blocks").get(0); assertEquals("empty-generated", block.path("header").asText());
                assertEquals(List.of(), JSON.convertValue(block.path("headerSourceRows"), List.class));
                assertEquals(List.of(2,3,4,5), JSON.convertValue(block.path("sourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void contrastingCellFillMarksTheFirstBorderedRowAsHeader(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("入力項目"); table(sheet, 3, 5, 1, 3);
            colorRow(sheet, 3, 1, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            cell(sheet,2,1,"表の前の説明");
            cell(sheet,3,1,"No."); cell(sheet,3,2,"項目名"); cell(sheet,3,3,"入力制御");
            cell(sheet,4,1,"1"); cell(sheet,4,2,"受注番号"); cell(sheet,4,3,"必須");
            cell(sheet,5,1,"2"); cell(sheet,5,2,"得意先"); cell(sheet,5,3,"任意");
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("| No. | 項目名 | 入力制御 |\n| --- | --- | --- |\n| 1 | 受注番号 | 必須 |\n| 2 | 得意先 | 任意 |"));
                assertEquals(1, md.split("No\\.", -1).length - 1);
                assertEquals("fill-color", report(result).path("blocks").get(1).path("header").asText());
                assertEquals(List.of(4), JSON.convertValue(report(result).path("blocks").get(1).path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void partialFillOnTheSecondRowStartsTheDetails(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("一部だけ色付きの明細"); table(sheet, 0, 3, 0, 1);
            colorRow(sheet, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            cell(sheet,0,0,"列名"); cell(sheet,0,1,"説明");
            cell(sheet,1,0,"ID"); cell(sheet,1,1,"名称");
            cell(sheet,2,0,"A"); cell(sheet,2,1,"値");
            cell(sheet,3,0,"B"); cell(sheet,3,1,"次の値");
            colorRow(sheet, 2, 0, 1, IndexedColors.LIGHT_BLUE.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("| 列名 | 説明 |\n| --- | --- |\n| ID | 名称 |\n| A | 値 |\n| B | 次の値 |"));
                JsonNode block = report(result).path("blocks").get(0);
                assertEquals("fill-color", block.path("header").asText());
                assertEquals(List.of(1), JSON.convertValue(block.path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void whiteFillOnTheRestOfTheSecondRowDoesNotExtendTheHeader(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("白い背景の明細"); table(sheet, 0, 2, 0, 2);
            cell(sheet, 0, 0, "No."); cell(sheet, 0, 1, "種別"); cell(sheet, 0, 2, "項目名");
            cell(sheet, 1, 0, "1"); cell(sheet, 1, 1, "ヘッダ"); cell(sheet, 1, 2, "伝票番号");
            cell(sheet, 2, 0, "2"); cell(sheet, 2, 1, "ヘッダ"); cell(sheet, 2, 2, "受注日付");
            colorRow(sheet, 0, 0, 2, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 1, 2, IndexedColors.WHITE.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("| No. | 種別 | 項目名 |\n| --- | --- | --- |\n| 1 | ヘッダ | 伝票番号 |\n| 2 | ヘッダ | 受注日付 |"));
                assertEquals(List.of(1), JSON.convertValue(tableBlocks(result).getFirst().path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void laterColoredRowsRemainDetailsWhenTheFirstRowHasNoFill(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("途中から色付き"); table(sheet, 0, 2, 0, 1);
            cell(sheet,0,0,"先頭明細"); cell(sheet,0,1,"A");
            cell(sheet,1,0,"色付き明細"); cell(sheet,1,1,"B");
            cell(sheet,2,0,"末尾明細"); cell(sheet,2,1,"C");
            colorRow(sheet, 1, 0, 1, IndexedColors.YELLOW.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("|  |  |\n| --- | --- |\n| 先頭明細 | A |\n| 色付き明細 | B |\n| 末尾明細 | C |"));
                JsonNode block = report(result).path("blocks").get(0);
                assertEquals("empty-generated", block.path("header").asText());
                assertEquals(List.of(), JSON.convertValue(block.path("headerSourceRows"), List.class));
                assertEquals(List.of(1, 2, 3), JSON.convertValue(block.path("sourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void coloredHeaderRunStopsAtTheFirstUncoloredDetail(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("途中で終了"); table(sheet, 0, 3, 0, 1);
            cell(sheet,0,0,"基本"); cell(sheet,0,1,"説明");
            cell(sheet,1,0,"ID"); cell(sheet,1,1,"名称");
            cell(sheet,2,0,"1"); cell(sheet,2,1,"りんご");
            cell(sheet,3,0,"2"); cell(sheet,3,1,"みかん");
            colorRow(sheet, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 0, 1, IndexedColors.LIGHT_BLUE.getIndex());
            colorRow(sheet, 3, 0, 1, IndexedColors.YELLOW.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("| 基本 / ID | 説明 / 名称 |\n| --- | --- |\n| 1 | りんご |\n| 2 | みかん |"));
                assertEquals(List.of(1, 2), JSON.convertValue(report(result).path("blocks").get(0).path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void hiddenColoredRowDoesNotAppearInCompositeHeader(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("非表示見出し"); table(sheet, 0, 3, 0, 1);
            cell(sheet,0,0,"分類"); cell(sheet,0,1,"分類");
            cell(sheet,1,0,"秘密"); cell(sheet,1,1,"秘密");
            cell(sheet,2,0,"ID"); cell(sheet,2,1,"名称");
            cell(sheet,3,0,"1"); cell(sheet,3,1,"りんご");
            colorRow(sheet, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 0, 1, IndexedColors.YELLOW.getIndex());
            colorRow(sheet, 2, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            sheet.getRow(1).setZeroHeight(true);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("| 分類 / ID | 分類 / 名称 |\n| --- | --- |\n| 1 | りんご |"));
                assertFalse(md.contains("秘密"));
                JsonNode block = report(result).path("blocks").get(0);
                assertEquals(List.of(1, 3), JSON.convertValue(block.path("headerSourceRows"), List.class));
                assertEquals(List.of(1, 3, 4), JSON.convertValue(block.path("sourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void outerBoxAndPartialGridFallbackToTextWithoutLosingValues(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("枠"); cell(sheet,0,0,"タイトル").setCellStyle(grid(book));
            table(sheet,3,4,0,1); cell(sheet,3,0,"A"); cell(sheet,3,1,"B"); cell(sheet,4,0,"C"); cell(sheet,4,1,"D");
            CellStyle incomplete = book.createCellStyle(); incomplete.cloneStyleFrom(sheet.getRow(4).getCell(1).getCellStyle());
            incomplete.setBorderRight(BorderStyle.NONE); sheet.getRow(4).getCell(1).setCellStyle(incomplete);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result); assertFalse(md.contains("| ---"));
                assertTrue(md.contains("タイトル")); assertTrue(md.contains("A　B")); assertTrue(md.contains("C　D"));
                assertTrue(report(result).toString().contains("BORDER_NOT_TABLE"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void neighborBordersAndSeparateGridsAreDetected(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("表");
            for (int r=0;r<2;r++) for(int c=0;c<2;c++) {
                Cell cell = cell(sheet,r,c,"値"+r+c); CellStyle style = book.createCellStyle();
                style.setBorderBottom(BorderStyle.DOUBLE); style.setBorderRight(BorderStyle.DASHED);
                if(r==0) style.setBorderTop(BorderStyle.THIN); if(c==0) style.setBorderLeft(BorderStyle.THICK); cell.setCellStyle(style);
            }
            table(sheet,4,5,3,4); cell(sheet,4,3,"別表");
            try(ConversionResult result=convert(book)) { assertEquals(2, report(result).path("blocks").size()); assertEquals(2, markdown(result).split("\\| --- \\| --- \\|",-1).length-1); }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void matrixWithUnborderedTopLeftCellRemainsOneTable(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("月別実績"); table(sheet, 0, 2, 0, 2);
            // A1 has neither a value nor a border. B1:C1 label the columns; A2:A3 label the rows.
            sheet.getRow(0).removeCell(sheet.getRow(0).getCell(0));
            cell(sheet, 0, 1, "1月"); cell(sheet, 0, 2, "2月");
            cell(sheet, 1, 0, "東京"); cell(sheet, 1, 1, "10"); cell(sheet, 1, 2, "12");
            cell(sheet, 2, 0, "大阪"); cell(sheet, 2, 1, "8"); cell(sheet, 2, 2, "9");
            colorRow(sheet, 0, 1, 2, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("|  | 1月 | 2月 |\n| --- | --- | --- |\n| 東京 | 10 | 12 |\n| 大阪 | 8 | 9 |"), md);
                JsonNode blocks = report(result).path("blocks");
                assertEquals(1, blocks.size(), blocks.toString());
                assertEquals("table", blocks.get(0).path("type").asText());
                assertEquals("A1:C3", blocks.get(0).path("range").asText());
                assertEquals("fill-color", blocks.get(0).path("header").asText());
                assertEquals(List.of(1), JSON.convertValue(blocks.get(0).path("headerSourceRows"), List.class));
                assertTrue(report(result).toString().contains("\"code\":\"TABLE_OPEN_TOP_CELL\""));
                assertTrue(report(result).toString().contains("\"range\":\"A1\""));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void borderedColumnsAndAttachedMatrixWithMissingCornerStayOneTable(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("担当別月次"); table(sheet, 0, 2, 0, 5);
            // A:C are a complete grid. D:F continue it as a matrix, except that D1 is borderless.
            sheet.getRow(0).removeCell(sheet.getRow(0).getCell(3));
            cell(sheet, 0, 0, "ID"); cell(sheet, 0, 1, "部門"); cell(sheet, 0, 2, "担当");
            cell(sheet, 0, 4, "1月"); cell(sheet, 0, 5, "2月");
            cell(sheet, 1, 0, "01"); cell(sheet, 1, 1, "営業"); cell(sheet, 1, 2, "佐藤");
            cell(sheet, 1, 3, "東京"); cell(sheet, 1, 4, "10"); cell(sheet, 1, 5, "12");
            cell(sheet, 2, 0, "02"); cell(sheet, 2, 1, "営業"); cell(sheet, 2, 2, "鈴木");
            cell(sheet, 2, 3, "大阪"); cell(sheet, 2, 4, "8"); cell(sheet, 2, 5, "9");
            colorRow(sheet, 0, 0, 2, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 0, 4, 5, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("| ID | 部門 | 担当 |  | 1月 | 2月 |\n| --- | --- | --- | --- | --- | --- |\n"
                        + "| 01 | 営業 | 佐藤 | 東京 | 10 | 12 |\n| 02 | 営業 | 鈴木 | 大阪 | 8 | 9 |"), md);
                JsonNode blocks = report(result).path("blocks");
                assertEquals(1, blocks.size(), blocks.toString());
                assertEquals("table", blocks.get(0).path("type").asText());
                assertEquals("A1:F3", blocks.get(0).path("range").asText());
                assertEquals(List.of(1, 2, 3, 4, 5, 6), JSON.convertValue(blocks.get(0).path("sourceColumns"), List.class));
                assertTrue(report(result).toString().contains("\"range\":\"D1\""));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void exactWorkbookAndSheetScopedNamesLabelTablesWithoutChangingTheirCells(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet first = book.createSheet("年間集計"); table(first, 0, 1, 0, 1);
            cell(first, 0, 0, "項目"); cell(first, 0, 1, "金額");
            cell(first, 1, 0, "売上"); cell(first, 1, 1, "100");
            colorRow(first, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, first, "Annual_Sales", "'年間集計'!$A$1:$B$2", false);
            Sheet second = book.createSheet("部署別"); table(second, 0, 1, 0, 1);
            cell(second, 0, 0, "部署"); cell(second, 0, 1, "担当");
            cell(second, 1, 0, "営業"); cell(second, 1, 1, "佐藤");
            colorRow(second, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, second, "Department_Table", "$A$1:$B$2", true);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("**Annual\\_Sales**\n\n| 項目 | 金額 |\n| --- | --- |\n| 売上 | 100 |"), md);
                assertTrue(md.contains("**Department\\_Table**\n\n| 部署 | 担当 |\n| --- | --- |\n| 営業 | 佐藤 |"), md);
                List<JsonNode> tables = tableBlocks(result);
                assertEquals(2, tables.size());
                assertEquals("A1:B2", tables.get(0).path("range").asText());
                assertEquals("A1:B2", tables.get(1).path("range").asText());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void adjacentFullHeightNamedBandsSplitOneGridIntoTwoTables(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("左右"); table(sheet, 0, 2, 0, 3);
            String[][] cells = {{"左ID", "左値", "右ID", "右値"}, {"L1", "左甲", "R1", "右甲"}, {"L2", "左乙", "R2", "右乙"}};
            for (int row = 0; row < cells.length; row++)
                for (int column = 0; column < cells[row].length; column++) cell(sheet, row, column, cells[row][column]);
            colorRow(sheet, 0, 0, 3, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, sheet, "Left_Band", "'左右'!$A$1:$B$3", false);
            namedRange(book, sheet, "Right_Band", "'左右'!$C$1:$D$3", false);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("**Left\\_Band**\n\n| 左ID | 左値 |\n| --- | --- |\n| L1 | 左甲 |\n| L2 | 左乙 |"), md);
                assertTrue(md.contains("**Right\\_Band**\n\n| 右ID | 右値 |\n| --- | --- |\n| R1 | 右甲 |\n| R2 | 右乙 |"), md);
                assertEquals(1, md.split("左甲", -1).length - 1, md);
                assertEquals(1, md.split("右甲", -1).length - 1, md);
                List<JsonNode> tables = tableBlocks(result);
                assertEquals(List.of("A1:B3", "C1:D3"), tables.stream().map(t -> t.path("range").asText()).toList());
                assertEquals(List.of(1, 2), JSON.convertValue(tables.get(0).path("sourceColumns"), List.class));
                assertEquals(List.of(3, 4), JSON.convertValue(tables.get(1).path("sourceColumns"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void stackedFullWidthNamedBandsGetIndependentHeaders(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("上下"); table(sheet, 0, 3, 0, 1);
            cell(sheet, 0, 0, "上項目"); cell(sheet, 0, 1, "上値");
            cell(sheet, 1, 0, "上明細"); cell(sheet, 1, 1, "甲");
            cell(sheet, 2, 0, "下項目"); cell(sheet, 2, 1, "下値");
            cell(sheet, 3, 0, "下明細"); cell(sheet, 3, 1, "乙");
            colorRow(sheet, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 2, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, sheet, "Top_Band", "$A$1:$B$2", true);
            namedRange(book, sheet, "Bottom_Band", "$A$3:$B$4", true);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("**Top\\_Band**\n\n| 上項目 | 上値 |\n| --- | --- |\n| 上明細 | 甲 |"), md);
                assertTrue(md.contains("**Bottom\\_Band**\n\n| 下項目 | 下値 |\n| --- | --- |\n| 下明細 | 乙 |"), md);
                List<JsonNode> tables = tableBlocks(result);
                assertEquals(List.of("A1:B2", "A3:B4"), tables.stream().map(t -> t.path("range").asText()).toList());
                assertEquals(List.of(1), JSON.convertValue(tables.get(0).path("headerSourceRows"), List.class));
                assertEquals(List.of(3), JSON.convertValue(tables.get(1).path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void namedCornerlessMatrixKeepsItsBlankCornerAndSingleTable(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("月別"); table(sheet, 0, 2, 0, 2);
            sheet.getRow(0).removeCell(sheet.getRow(0).getCell(0));
            cell(sheet, 0, 0, "集計注記");
            cell(sheet, 0, 1, "1月"); cell(sheet, 0, 2, "2月");
            cell(sheet, 1, 0, "東京"); cell(sheet, 1, 1, "10"); cell(sheet, 1, 2, "12");
            cell(sheet, 2, 0, "大阪"); cell(sheet, 2, 1, "8"); cell(sheet, 2, 2, "9");
            colorRow(sheet, 0, 1, 2, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, sheet, "Monthly_Matrix", "'月別'!$A$1:$C$3", false);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("集計注記\n\n**Monthly\\_Matrix**\n\n|  | 1月 | 2月 |\n| --- | --- | --- |\n| 東京 | 10 | 12 |\n| 大阪 | 8 | 9 |"), md);
                assertEquals(List.of("A1:C3"), tableBlocks(result).stream().map(t -> t.path("range").asText()).toList());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void dynamicAndOverlappingNamesDoNotSplitOrDuplicateTheOriginalGrid(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("入力"); table(sheet, 0, 2, 0, 3);
            cell(sheet, 0, 0, "A見出し"); cell(sheet, 0, 1, "B見出し");
            cell(sheet, 0, 2, "C見出し"); cell(sheet, 0, 3, "D見出し");
            cell(sheet, 1, 0, "左端甲"); cell(sheet, 1, 3, "右端甲");
            cell(sheet, 2, 0, "左端乙"); cell(sheet, 2, 3, "右端乙");
            colorRow(sheet, 0, 0, 3, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, sheet, "Dynamic_Range", "OFFSET('入力'!$A$1,0,0,3,4)", false);
            namedRange(book, sheet, "Left_Overlap", "'入力'!$A$1:$C$3", false);
            namedRange(book, sheet, "Right_Overlap", "'入力'!$B$1:$D$3", false);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertEquals(List.of("A1:D3"), tableBlocks(result).stream().map(t -> t.path("range").asText()).toList());
                assertFalse(md.contains("**Dynamic\\_Range**"), md);
                for (String value : List.of("左端甲", "右端甲", "左端乙", "右端乙"))
                    assertEquals(1, md.split(value, -1).length - 1, md);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void mergeCrossingNamedBandBoundaryKeepsOneTableAndSharedValue(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("結合またぎ"); table(sheet, 0, 2, 0, 3);
            cell(sheet, 0, 0, "左ID"); cell(sheet, 0, 1, "左値");
            cell(sheet, 0, 2, "右ID"); cell(sheet, 0, 3, "右値");
            cell(sheet, 1, 0, "L1"); cell(sheet, 1, 1, "共有結合値"); cell(sheet, 1, 3, "R1");
            cell(sheet, 2, 0, "L2"); cell(sheet, 2, 1, "左乙");
            cell(sheet, 2, 2, "R2"); cell(sheet, 2, 3, "右乙");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 1, 2));
            colorRow(sheet, 0, 0, 3, IndexedColors.GREY_25_PERCENT.getIndex());
            namedRange(book, sheet, "Left_Band", "'結合またぎ'!$A$1:$B$3", false);
            namedRange(book, sheet, "Right_Band", "'結合またぎ'!$C$1:$D$3", false);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertEquals(List.of("A1:D3"), tableBlocks(result).stream().map(t -> t.path("range").asText()).toList());
                assertEquals(1, md.split("\\| L1 \\| 共有結合値 \\|", -1).length - 1, md);
                assertTrue(md.contains("左乙") && md.contains("右乙"), md);
            }
        }
    }
    @Test void hiddenMultiAreaAndExternalNamesAreNotRenderedAsTableLabels() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            XSSFSheet sheet = book.createSheet("名前の安全性");
            table(sheet, 0, 1, 0, 1); table(sheet, 4, 5, 0, 1); table(sheet, 8, 9, 0, 1);
            cell(sheet, 0, 0, "隠し表"); cell(sheet, 1, 0, "隠し明細");
            cell(sheet, 4, 0, "複数領域表"); cell(sheet, 5, 0, "複数明細");
            cell(sheet, 8, 0, "外部表"); cell(sheet, 9, 0, "外部明細");
            xmlName(namedRange(book, sheet, "Hidden_Name", "'名前の安全性'!$A$1:$B$2", false)).setHidden(true);
            xmlName(namedRange(book, sheet, "Multi_Area", "'名前の安全性'!$A$5:$B$6", false))
                    .setStringValue("'名前の安全性'!$A$5:$B$6,'名前の安全性'!$A$9:$B$10");
            xmlName(namedRange(book, sheet, "External_Name", "'名前の安全性'!$A$9:$B$10", false))
                    .setStringValue("[1]External!$A$9:$B$10");
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertEquals(List.of("A1:B2", "A5:B6", "A9:B10"),
                        tableBlocks(result).stream().map(t -> t.path("range").asText()).toList());
                for (String name : List.of("Hidden\\_Name", "Multi\\_Area", "External\\_Name"))
                    assertFalse(md.contains(name), md);
                for (String value : List.of("隠し明細", "複数明細", "外部明細")) assertTrue(md.contains(value), md);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void nonemptyUnborderedTopCellBecomesANoteBeforeTheInferredTable(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("注記付き格子"); table(sheet, 0, 2, 0, 2);
            sheet.getRow(0).removeCell(sheet.getRow(0).getCell(0));
            cell(sheet, 0, 0, "注記"); cell(sheet, 0, 1, "1月"); cell(sheet, 0, 2, "2月");
            cell(sheet, 1, 0, "東京"); cell(sheet, 1, 1, "10"); cell(sheet, 1, 2, "12");
            cell(sheet, 2, 0, "大阪"); cell(sheet, 2, 1, "8"); cell(sheet, 2, 2, "9");
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertEquals(1, tableBlocks(result).size());
                assertTrue(md.indexOf("注記") < md.indexOf("| --- |"), md);
                assertTrue(md.contains("|  | 1月 | 2月 |\n| --- | --- | --- |\n| 東京 | 10 | 12 |"), md);
                assertFalse(md.contains("| 注記 |"), md);
                assertEquals("matrix-grid", tableBlocks(result).getFirst().path("header").asText());
                assertEquals("[1]", tableBlocks(result).getFirst().path("headerSourceRows").toString());
                assertTrue(report(result).toString().contains("TABLE_OPEN_TOP_NOTE"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void threeLevelMatrixHeaderFollowsMergeGeometryWithVariableTopCaptionWidth(boolean xlsx) throws Exception {
        for (int captionWidth : new int[] {2, 3, 4}) try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("売上マトリックス"); table(sheet, 0, 4, 0, 5);
            CellStyle noBorder = book.createCellStyle();
            for (int column = 0; column < 2; column++) sheet.getRow(0).getCell(column).setCellStyle(noBorder);
            for (int column = 2 + captionWidth; column < 6; column++) sheet.getRow(0).getCell(column).setCellStyle(noBorder);
            cell(sheet, 0, 0, "集計上の注記");
            cell(sheet, 0, 2, "売上");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 2, 1 + captionWidth));
            cell(sheet, 1, 0, "ID"); cell(sheet, 1, 1, "場所");
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 1, 1));
            for (int column = 2; column < 6; column++)
                cell(sheet, 1, column, column % 2 == 0 ? "前半" : "後半");
            cell(sheet, 2, 2, "1月"); cell(sheet, 2, 4, "2月");
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 2, 3));
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 4, 5));
            String[][] details = {{"001", "東京", "10", "12", "10", "12"},
                    {"002", "大阪", "8", "9", "8", "9"}};
            for (int row = 3; row < 5; row++)
                for (int column = 0; column < 6; column++)
                    cell(sheet, row, column, details[row - 3][column]);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertEquals(1, tableBlocks(result).size(), report(result).toString());
                JsonNode block = tableBlocks(result).getFirst();
                assertEquals("A1:F5", block.path("range").asText());
                assertEquals("matrix-grid", block.path("header").asText());
                assertEquals("[1,2,3]", block.path("headerSourceRows").toString());
                assertTrue(md.indexOf("集計上の注記") < md.indexOf("| --- |"), md);
                assertTrue(md.contains("| ID | 場所 | 売上 / 前半 / 1月 | 売上 / 後半 / 1月 |"), md);
                assertTrue(md.contains("| 001 | 東京 | 10 | 12 | 10 | 12 |"), md);
                assertTrue(md.contains("| 002 | 大阪 | 8 | 9 | 8 | 9 |"), md);
                assertFalse(md.contains("| 集計上の注記 |"), md);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void blankCornerWithItsOwnBottomAndSideBorderIsNotAnOpenMatrixCell(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("角に罫線あり"); table(sheet, 0, 2, 0, 2);
            // A1 has no text, but its own style explicitly draws the bottom and right edges.
            CellStyle partialCorner = book.createCellStyle();
            partialCorner.setBorderBottom(BorderStyle.THIN);
            partialCorner.setBorderRight(BorderStyle.THIN);
            sheet.getRow(0).getCell(0).setCellStyle(partialCorner);
            cell(sheet, 0, 1, "1月"); cell(sheet, 0, 2, "2月");
            cell(sheet, 1, 0, "東京"); cell(sheet, 1, 1, "10"); cell(sheet, 1, 2, "12");
            cell(sheet, 2, 0, "大阪"); cell(sheet, 2, 1, "8"); cell(sheet, 2, 2, "9");
            try (ConversionResult result = convert(book)) {
                JsonNode details = report(result);
                for (JsonNode block : details.path("blocks"))
                    if ("table".equals(block.path("type").asText()))
                        assertNotEquals("A1:C3", block.path("range").asText(), details.toString());
                assertFalse(details.toString().contains("TABLE_OPEN_TOP_CELL"), details.toString());
                assertTrue(markdown(result).contains("東京"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void singleMergedLowerCellDoesNotImplyAFullMatrixGrid(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("下段は一個の結合セル"); table(sheet, 0, 2, 0, 1);
            sheet.getRow(0).removeCell(sheet.getRow(0).getCell(0));
            cell(sheet, 0, 1, "右上セル");
            cell(sheet, 1, 0, "大きな結合セル");
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 1));
            try (ConversionResult result = convert(book)) {
                JsonNode details = report(result);
                for (JsonNode block : details.path("blocks"))
                    if ("table".equals(block.path("type").asText()))
                        assertNotEquals("A1:B3", block.path("range").asText(), details.toString());
                assertFalse(details.toString().contains("TABLE_OPEN_TOP_CELL"), details.toString());
                String md = markdown(result);
                assertTrue(md.contains("右上セル"), md);
                assertTrue(md.contains("大きな結合セル"), md);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void openBorderElsewhereAndSurroundingProseDoNotJoinCornerlessMatrix(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("説明と実績");
            cell(sheet, 0, 0, "月別実績の説明");
            table(sheet, 2, 4, 0, 2);
            sheet.getRow(2).removeCell(sheet.getRow(2).getCell(0));
            cell(sheet, 2, 1, "1月"); cell(sheet, 2, 2, "2月");
            cell(sheet, 3, 0, "東京"); cell(sheet, 3, 1, "10"); cell(sheet, 3, 2, "12");
            cell(sheet, 4, 0, "大阪"); cell(sheet, 4, 1, "8"); cell(sheet, 4, 2, "9");
            colorRow(sheet, 2, 1, 2, IndexedColors.GREY_25_PERCENT.getIndex());
            cell(sheet, 6, 0, "末尾の注記");
            table(sheet, 8, 9, 4, 5);
            cell(sheet, 8, 4, "未完成"); cell(sheet, 8, 5, "枠");
            CellStyle open = book.createCellStyle();
            open.cloneStyleFrom(sheet.getRow(9).getCell(5).getCellStyle());
            open.setBorderRight(BorderStyle.NONE);
            sheet.getRow(9).getCell(5).setCellStyle(open);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertEquals(1, md.split("\\| --- \\| --- \\| --- \\|", -1).length - 1, md);
                assertTrue(md.contains("月別実績の説明"), md);
                assertTrue(md.contains("末尾の注記"), md);
                assertTrue(md.contains("未完成"), md);
                JsonNode blocks = report(result).path("blocks");
                long tables = 0;
                for (JsonNode block : blocks) if ("table".equals(block.path("type").asText())) tables++;
                assertEquals(1, tables, blocks.toString());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void strikeWinsOverBoldLinksAndFormulaFallbacks(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("文字"); Font strike=book.createFont();strike.setBold(true);strike.setStrikeout(true);
            CellStyle style=book.createCellStyle();style.setFont(strike);
            Cell a=cell(sheet,0,0,"削除秘密");a.setCellStyle(style);
            Hyperlink link=book.getCreationHelper().createHyperlink(HyperlinkType.URL);link.setAddress("https://secret.example/hidden");a.setHyperlink(link);
            Cell b=cell(sheet,1,0,"");b.setCellFormula("HYPERLINK(\"https://secret.example/formula\",\"秘密\")");b.setCellStyle(style);
            Cell c=cell(sheet,2,0,"");RichTextString rich=book.getCreationHelper().createRichTextString("旧価格新価格"); rich.applyFont(0,3,strike);c.setCellValue(rich);
            try(ConversionResult result=convert(book)) {
                String md=markdown(result);assertEquals("# [文字] シート\n\n新価格\n",md);
                String report=report(result).toString(); assertTrue(report.contains("STRIKETHROUGH_REMOVED"));
                for(String forbidden:List.of("削除秘密","旧価格","secret.example","HYPERLINK")) {assertFalse(md.contains(forbidden));assertFalse(report.contains(forbidden));}
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void richTextBoldAndExplicitNormalOverrideCellBold(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("書式"); Font bold=book.createFont();bold.setBold(true);Font normal=book.createFont();normal.setBold(false);normal.setStrikeout(false);
            CellStyle style=book.createCellStyle();style.setFont(bold);
            Cell cell=cell(sheet,0,0,"");cell.setCellStyle(style);
            RichTextString rich=book.getCreationHelper().createRichTextString("太字通常太字");rich.applyFont(2,4,normal);cell.setCellValue(rich);
            try(ConversionResult result=convert(book)){assertEquals("# [書式] シート\n\n**太字**通常**太字**\n",markdown(result));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void safeLinksEscapeMarkdownAndUnsafeHtmlRemainsText(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("リンク"); Cell a=cell(sheet,0,0,"[詳細]|<script>alert(1)</script>");
            Hyperlink link=book.getCreationHelper().createHyperlink(HyperlinkType.URL);link.setAddress("https://example.com/a(b)?q=x%20y");a.setHyperlink(link);
            Cell b=cell(sheet,2,0,"危険リンク");Hyperlink unsafe=book.getCreationHelper().createHyperlink(HyperlinkType.URL);unsafe.setAddress("javascript:alert(1)");b.setHyperlink(unsafe);
            Cell empty=cell(sheet,4,0,"");Hyperlink email=book.getCreationHelper().createHyperlink(HyperlinkType.EMAIL);email.setAddress("mailto:test@example.com");empty.setHyperlink(email);
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("\\[詳細\\]\\|&lt;script&gt;"));assertTrue(md.contains("https://example.com/a%28b%29?q=x%20y"));assertFalse(md.contains("javascript:"));assertTrue(md.contains("[mailto:test@example.com](mailto:test@example.com)"));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void numericFormatsUseSavedValuesAndNeverEvaluate(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("数値");Cell number=cell(sheet,0,0,"");number.setCellValue(12);
            CellStyle leading=book.createCellStyle();leading.setDataFormat(book.createDataFormat().getFormat("00000"));number.setCellStyle(leading);
            Cell formula=cell(sheet,1,0,"");formula.setCellFormula("A1*2");formula.setCellValue(999);
            Cell percentage=cell(sheet,2,0,"");percentage.setCellValue(.125);CellStyle percent=book.createCellStyle();percent.setDataFormat(book.createDataFormat().getFormat("0.0%"));percentage.setCellStyle(percent);
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("00012"));assertTrue(md.contains("999"));assertFalse(md.contains("24"));assertTrue(md.contains("12.5%"));}
        }
    }
    @Test void missingFormulaCacheAndConstantHyperlinkHaveExplicitFallback() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()) {
            XSSFSheet sheet=book.createSheet("数式");XSSFCell formula=(XSSFCell)cell(sheet,0,0,"");formula.setCellFormula("1+2");formula.getCTCell().unsetV();
            XSSFCell link=(XSSFCell)cell(sheet,2,0,"");link.setCellFormula("HYPERLINK(\"https://example.com\",\"表示\")");link.getCTCell().unsetV();
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("=1\\+2"));assertTrue(md.contains("[表示](https://example.com)"));assertTrue(report(result).toString().contains("FORMULA_CACHE_MISSING"));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void hiddenCellsAndMergedAnchorAreNeverCopied(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("表示");table(sheet,0,3,0,2);cell(sheet,0,0,"表示値");cell(sheet,1,0,"非表示行の秘密");sheet.getRow(1).setZeroHeight(true);
            cell(sheet,2,1,"非表示列の秘密");sheet.setColumnHidden(1,true);
            Sheet hidden=book.createSheet("隠しシート");cell(hidden,0,0,"シート秘密");book.setSheetHidden(1,true);
            Sheet merge=book.createSheet("結合");cell(merge,0,0,"アンカー秘密");cell(merge,0,1,"結合内部秘密");merge.addMergedRegion(new CellRangeAddress(0,0,0,1));merge.setColumnHidden(0,true);cell(merge,2,0,"これも非表示");cell(merge,2,1,"表示本文");
            try(ConversionResult result=convert(book)){String md=markdown(result);assertFalse(md.contains("秘密"));assertTrue(md.contains("表示値"));assertTrue(md.contains("表示本文"));JsonNode b=report(result).path("blocks").get(0);assertEquals(List.of(1,3,4),JSON.convertValue(b.path("sourceRows"),List.class));assertEquals(List.of(1,3),JSON.convertValue(b.path("sourceColumns"),List.class));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void mergedColoredGroupHeaderPropagatesToEachMarkdownColumn(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("結合表");table(sheet,0,2,0,1);
            cell(sheet,0,0,"基本情報");cell(sheet,1,0,"ID");cell(sheet,1,1,"名称");
            cell(sheet,2,0,"1");cell(sheet,2,1,"りんご");
            sheet.addMergedRegion(new CellRangeAddress(0,0,0,1));
            CellStyle left=book.createCellStyle();left.cloneStyleFrom(grid(book));left.setBorderRight(BorderStyle.NONE);sheet.getRow(0).getCell(0).setCellStyle(left);
            CellStyle right=book.createCellStyle();right.cloneStyleFrom(grid(book));right.setBorderLeft(BorderStyle.NONE);sheet.getRow(0).getCell(1).setCellStyle(right);
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            try(ConversionResult result=convert(book)){
                assertTrue(markdown(result).contains("| 基本情報 / ID | 基本情報 / 名称 |\n| --- | --- |\n| 1 | りんご |"));
                assertEquals(List.of(1, 2), JSON.convertValue(report(result).path("blocks").get(0).path("headerSourceRows"), List.class));
                assertTrue(report(result).toString().contains("TABLE_MERGE_FLATTENED"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void stableExplicitMergeAcrossHeaderAndDetailsBecomesOneLogicalColumn(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("同幅結合"); table(sheet, 0, 2, 0, 3);
            cell(sheet, 0, 0, "項目"); cell(sheet, 0, 2, "分類"); cell(sheet, 0, 3, "状態");
            cell(sheet, 1, 0, "りんご"); cell(sheet, 1, 2, "果物"); cell(sheet, 1, 3, "有効");
            cell(sheet, 2, 0, "みかん"); cell(sheet, 2, 2, "柑橘"); cell(sheet, 2, 3, "無効");
            for (int row = 0; row <= 2; row++) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, 1));
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("| 項目 | 分類 | 状態 |\n| --- | --- | --- |\n| りんご | 果物 | 有効 |\n| みかん | 柑橘 | 無効 |"), md);
                JsonNode block = report(result).path("blocks").get(0);
                assertEquals(List.of(1), JSON.convertValue(block.path("headerSourceRows"), List.class));
                assertEquals(List.of(1, 2, 3, 4), JSON.convertValue(block.path("sourceColumns"), List.class));
                JsonNode spans = block.path("sourceColumnSpans");
                assertEquals(3, spans.size());
                assertEquals(1, spans.get(0).path("first").asInt());
                assertEquals(2, spans.get(0).path("last").asInt());
                assertEquals(3, spans.get(1).path("first").asInt());
                assertEquals(3, spans.get(1).path("last").asInt());
                assertEquals(4, spans.get(2).path("first").asInt());
                assertEquals(4, spans.get(2).path("last").asInt());
                assertTrue(report(result).toString().contains("TABLE_MERGED_COLUMNS_COLLAPSED"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void stableExplicitMergeAlsoCollapsesConsecutiveColoredHeaderRows(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("複数段の同幅結合"); table(sheet, 0, 3, 0, 2);
            cell(sheet, 0, 0, "基本情報"); cell(sheet, 0, 2, "分類");
            cell(sheet, 1, 0, "商品"); cell(sheet, 1, 2, "種類");
            cell(sheet, 2, 0, "りんご"); cell(sheet, 2, 2, "果物");
            cell(sheet, 3, 0, "みかん"); cell(sheet, 3, 2, "柑橘");
            for (int row = 0; row <= 3; row++) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, 1));
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            colorRow(sheet, 1, 0, 0, IndexedColors.LIGHT_BLUE.getIndex());
            colorRow(sheet, 1, 2, 2, IndexedColors.LIGHT_BLUE.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("| 基本情報 / 商品 | 分類 / 種類 |\n| --- | --- |\n| りんご | 果物 |\n| みかん | 柑橘 |"));
                assertEquals(List.of(1, 2), JSON.convertValue(report(result).path("blocks").get(0).path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void adjacentStableMergeGroupsRemainSeparateLogicalColumns(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("並ぶ同幅結合"); table(sheet, 0, 2, 0, 3);
            cell(sheet, 0, 0, "項目"); cell(sheet, 0, 2, "説明");
            cell(sheet, 1, 0, "A"); cell(sheet, 1, 2, "最初");
            cell(sheet, 2, 0, "B"); cell(sheet, 2, 2, "次");
            for (int row = 0; row <= 2; row++) {
                sheet.addMergedRegion(new CellRangeAddress(row, row, 0, 1));
                sheet.addMergedRegion(new CellRangeAddress(row, row, 2, 3));
            }
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("| 項目 | 説明 |\n| --- | --- |\n| A | 最初 |\n| B | 次 |"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void uncoloredStableMergeStillKeepsTheFirstRowAsDetail(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("無色の同幅結合"); table(sheet, 0, 1, 0, 2);
            cell(sheet, 0, 0, "先頭明細"); cell(sheet, 0, 2, "A");
            cell(sheet, 1, 0, "次の明細"); cell(sheet, 1, 2, "B");
            for (int row = 0; row <= 1; row++) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, 1));
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("|  |  |\n| --- | --- |\n| 先頭明細 | A |\n| 次の明細 | B |"));
                assertEquals("empty-generated", report(result).path("blocks").get(0).path("header").asText());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void oneRowMergedHeaderOverSplitDetailsKeepsColumnsAndDisambiguatesTheirLabels(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("見出しだけ結合"); table(sheet, 0, 2, 0, 2);
            cell(sheet, 0, 0, "連絡先"); cell(sheet, 0, 2, "状態");
            cell(sheet, 1, 0, "03-1234-5678"); cell(sheet, 1, 1, "a@example.com"); cell(sheet, 1, 2, "有効");
            cell(sheet, 2, 0, "06-1234-5678"); cell(sheet, 2, 1, "b@example.com"); cell(sheet, 2, 2, "無効");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("| 連絡先（A列） | 連絡先（B列） | 状態 |\n| --- | --- | --- |\n| 03\\-1234\\-5678 | a@example.com | 有効 |\n| 06\\-1234\\-5678 | b@example.com | 無効 |"), markdown(result));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void splitDetailPreventsCollapseEvenWhenOtherRowsShareTheHeaderMerge(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("一部だけ分割"); table(sheet, 0, 2, 0, 2);
            cell(sheet, 0, 0, "項目"); cell(sheet, 0, 2, "状態");
            cell(sheet, 1, 0, "共通値"); cell(sheet, 1, 2, "確認中");
            cell(sheet, 2, 0, "個別A"); cell(sheet, 2, 1, "個別B"); cell(sheet, 2, 2, "完了");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("結合セル A2:B2「共通値」は明細行の複数列に共通です。\n\n"
                        + "| 項目（A列） | 項目（B列） | 状態 |\n| --- | --- | --- |\n| 共通値 |  | 確認中 |\n| 個別A | 個別B | 完了 |"), md);
                assertEquals(1, md.split("\\| 共通値 \\|", -1).length - 1);
                JsonNode note = report(result).path("blocks").get(0);
                assertEquals("body-merge", note.path("noteKind").asText());
                assertEquals(List.of("A2:B2"), JSON.convertValue(note.path("mergedRanges"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void verticalDetailMergeIsExplainedWithoutRepeatingTheSharedCell(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("縦結合の明細"); table(sheet, 0, 2, 0, 1);
            cell(sheet, 0, 0, "部門"); cell(sheet, 0, 1, "担当者");
            cell(sheet, 1, 0, "営業"); cell(sheet, 1, 1, "佐藤");
            cell(sheet, 2, 1, "鈴木");
            sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            colorRow(sheet, 0, 0, 1, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("結合セル A2:A3「営業」は複数の明細行に共通です。\n\n"
                        + "| 部門 | 担当者 |\n| --- | --- |\n| 営業 | 佐藤 |\n|  | 鈴木 |"), md);
                assertEquals(1, md.split("\\| 営業 \\|", -1).length - 1);
                JsonNode note = report(result).path("blocks").get(0);
                assertEquals("body-merge", note.path("noteKind").asText());
                assertEquals(List.of("A2:A3"), JSON.convertValue(note.path("mergedRanges"), List.class));
                JsonNode tableBlock = report(result).path("blocks").get(1);
                assertEquals("table", tableBlock.path("type").asText());
                assertEquals(List.of(1), JSON.convertValue(tableBlock.path("headerSourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void hiddenSplitRowDoesNotPreventCollapseAcrossVisibleRows(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("非表示行を除く同幅結合"); table(sheet, 0, 2, 0, 2);
            cell(sheet, 0, 0, "項目"); cell(sheet, 0, 2, "分類");
            cell(sheet, 1, 0, "非表示の値A"); cell(sheet, 1, 1, "非表示の値B");
            cell(sheet, 2, 0, "りんご"); cell(sheet, 2, 2, "果物");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, 1));
            sheet.getRow(1).setZeroHeight(true);
            colorRow(sheet, 0, 0, 0, IndexedColors.GREY_25_PERCENT.getIndex());
            try (ConversionResult result = convert(book)) {
                String md = markdown(result);
                assertTrue(md.contains("| 項目 | 分類 |\n| --- | --- |\n| りんご | 果物 |"));
                assertFalse(md.contains("非表示の値"));
                assertEquals(List.of(1, 3), JSON.convertValue(report(result).path("blocks").get(0).path("sourceRows"), List.class));
            }
        }
    }
    @Test void nativeTableWithoutDirectFillStartsWithAnEmptyGeneratedHeader() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()) {
            XSSFSheet sheet=book.createSheet("実テーブル");cell(sheet,0,0,"項目");cell(sheet,0,1,"値");cell(sheet,1,0,"A");cell(sheet,1,1,"B");table(sheet,0,1,0,1);
            sheet.createTable(new AreaReference("A1:B2",book.getSpreadsheetVersion()));
            XSSFSheet second=book.createSheet("スタイルのみ");cell(second,0,0,"項目");cell(second,0,1,"値");cell(second,1,0,"C");cell(second,1,1,"D");second.createTable(new AreaReference("A1:B2",book.getSpreadsheetVersion()));
            try(ConversionResult result=convert(book)){
                String md=markdown(result);
                assertTrue(md.contains("|  |  |\n| --- | --- |\n| 項目 | 値 |\n| A | B |"));
                assertTrue(md.contains("# [スタイルのみ] シート\n\n項目　値  \nC　D"));
                assertEquals("empty-generated",report(result).path("blocks").get(0).path("header").asText());
                assertEquals(List.of(), JSON.convertValue(report(result).path("blocks").get(0).path("headerSourceRows"), List.class));
            }
        }
    }
    @Test void outputZipContainsOnlyArtifactsAndTemporaryFilesAreDeleted() throws Exception {
        Path directory;
        try(Workbook book=new XSSFWorkbook()){cell(book.createSheet("表"),0,0,"内容");try(ConversionResult result=convert(book)){
            directory=result.directory();Set<String> entries=new HashSet<>();
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(result.zipBytes()))){for(ZipEntry e;(e=zip.getNextEntry())!=null;)entries.add(e.getName());}
            assertEquals(Set.of("document.md","report.json"),entries);assertFalse(Files.exists(directory.resolve("source.workbook")));
        }}assertFalse(Files.exists(directory));
    }
    @Test void inputAndCellAndOutputLimitsFailClearly() throws Exception {
        assertEquals("EMPTY_INPUT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(DEFAULTS).convert(new byte[0],"x.xlsx")).code());
        assertEquals(415,assertThrows(ConversionException.class,()->new ExcelMarkdownService(DEFAULTS).convert(new byte[]{1,2,3},"x.xlsx")).statusCode());
        try(Workbook book=new XSSFWorkbook()) {
            cell(book.createSheet("表"),0,0,"本文");cell(book.getSheetAt(0),1,0,"次");byte[] bytes=bytes(book);
            ConversionLimits small=new ConversionLimits(DEFAULTS.maxInputBytes(),50,1,100,100,10,1000,1000,10,10,1000);
            assertEquals("READ_CELLS_LIMIT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(small).convert(bytes,"x.xlsx")).code());
            ConversionLimits tiny=new ConversionLimits(DEFAULTS.maxInputBytes(),50,100,100,2,10,1000,1000,10,10,1000);
            assertEquals("MARKDOWN_BYTES_LIMIT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(tiny).convert(bytes,"x.xlsx")).code());
        }
    }
    @Test void macroContentIsRejectedEvenWithXlsxFilename() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook(XSSFWorkbookType.XLSM)){cell(book.createSheet("表"),0,0,"内容");byte[] bytes=bytes(book);assertEquals("UNSUPPORTED_FORMAT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(DEFAULTS).convert(bytes,"pretend.xlsx")).code());}
    }
    @Test void imageFormulaIsNotFetchedOrExposedAsRemoteMarkdownImage() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()){Cell cell=cell(book.createSheet("画像"),0,0,"");cell.setCellFormula("_xlfn.IMAGE(\"https://private.example/pic.png\")");try(ConversionResult result=convert(book)){String md=markdown(result);assertFalse(md.contains("private.example"));assertTrue(md.contains("未対応"));assertTrue(report(result).toString().contains("CELL_IMAGE_UNSUPPORTED"));}}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void pictureOnlySheetIsKeptAndOverlappingPictureFollowsWholeTable(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);image.flush();
            int picture=book.addPicture(png.toByteArray(),Workbook.PICTURE_TYPE_PNG);
            Sheet sheet=book.createSheet("配置");table(sheet,0,9,0,1);cell(sheet,0,0,"表の先頭");cell(sheet,9,1,"表の末尾");cell(sheet,1,4,"表外本文");
            ClientAnchor anchor=book.getCreationHelper().createClientAnchor();anchor.setRow1(4);anchor.setRow2(6);anchor.setCol1(0);anchor.setCol2(1);
            sheet.createDrawingPatriarch().createPicture(anchor,picture);
            Sheet imageOnly=book.createSheet("画像のみ");ClientAnchor other=book.getCreationHelper().createClientAnchor();other.setRow1(1);other.setRow2(3);other.setCol1(0);other.setCol2(2);imageOnly.createDrawingPatriarch().createPicture(other,picture);
            try(ConversionResult result=convert(book)) {
                String md=markdown(result);assertEquals(2,result.sectionCount());assertTrue(md.indexOf("表の末尾")<md.indexOf("!["));
                assertTrue(md.contains("|  |  | 表外本文 |"));assertTrue(md.indexOf("表外本文")<md.indexOf("!["));
                assertTrue(md.contains("# [画像のみ] シート"));assertEquals(1,report(result).path("assets").size());
                assertEquals(2, md.split("images/image-0001.png", -1).length - 1);
            }
        }
    }
    @Test void hyperlinkWithDynamicLabelKeepsLiteralDestinationAndCachedText() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()) {
            Cell link=cell(book.createSheet("リンク"),0,0,"");link.setCellFormula("HYPERLINK(\"https://example.com/a\",B1)");link.setCellValue("保存済み表示");
            try(ConversionResult result=convert(book)){assertTrue(markdown(result).contains("[保存済み表示](https://example.com/a)"));}
        }
    }
}
