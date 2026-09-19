package com.convertx2x.office2md.conversion;

import static com.convertx2x.office2md.conversion.ExcelMarkdownServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TableExpansionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Workbook workbook(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }
    private static byte[] bytes(Workbook book) throws Exception {
        try (var out = new ByteArrayOutputStream()) { book.write(out); return out.toByteArray(); }
    }
    private static ConversionResult convert(Workbook book) throws Exception {
        return new ExcelMarkdownService(ConversionLimits.defaults()).convert(bytes(book), book instanceof XSSFWorkbook ? "tables.xlsx" : "tables.xls");
    }
    private static String md(ConversionResult result) throws Exception { return Files.readString(result.files().get("document.md")); }
    private static List<JsonNode> tables(ConversionResult result) throws Exception {
        List<JsonNode> tables = new ArrayList<>();
        for (JsonNode block : JSON.readTree(result.files().get("report.json").toFile()).path("blocks"))
            if (block.path("type").asText().equals("table")) tables.add(block);
        return tables;
    }
    private static void missingDivider(Sheet sheet, int row, int leftColumn) {
        CellStyle left = sheet.getWorkbook().createCellStyle();
        left.cloneStyleFrom(sheet.getRow(row).getCell(leftColumn).getCellStyle());
        left.setBorderRight(BorderStyle.NONE); sheet.getRow(row).getCell(leftColumn).setCellStyle(left);
        CellStyle right = sheet.getWorkbook().createCellStyle();
        right.cloneStyleFrom(sheet.getRow(row).getCell(leftColumn + 1).getCellStyle());
        right.setBorderLeft(BorderStyle.NONE); sheet.getRow(row).getCell(leftColumn + 1).setCellStyle(right);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void addsBothSidesAcrossEmptyColumnsWithoutTakingRowsAboveOrBelow(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("補足付き表");
            table(sheet, 1, 3, 1, 2);
            cell(sheet, 0, 5, "上の本文"); cell(sheet, 4, 0, "下の本文");
            cell(sheet, 1, 0, "分類"); cell(sheet, 1, 1, "商品"); cell(sheet, 1, 2, "金額"); cell(sheet, 1, 5, "備考");
            cell(sheet, 2, 0, "果物"); cell(sheet, 2, 1, "りんご"); cell(sheet, 2, 2, "100円"); cell(sheet, 2, 5, "特売");
            cell(sheet, 3, 1, "みかん"); cell(sheet, 3, 5, "在庫あり");
            try (var result = convert(book)) {
                String markdown = md(result);
                assertTrue(markdown.contains("| 分類 | 商品 | 金額 | 備考 |"));
                assertTrue(markdown.contains("| 果物 | りんご | 100円 | 特売 |"));
                assertTrue(markdown.contains("|  | みかん |  | 在庫あり |"));
                assertEquals(1, markdown.split("特売", -1).length - 1);
                assertTrue(markdown.indexOf("上の本文") < markdown.indexOf("| ---"));
                assertTrue(markdown.indexOf("下の本文") > markdown.indexOf("在庫あり"));
                JsonNode table = tables(result).getFirst();
                assertEquals("A2:F4", table.path("range").asText());
                assertEquals(List.of("B2:C4"), JSON.convertValue(table.path("detectedRanges"), List.class));
                assertEquals(List.of(1, 2, 3, 6), JSON.convertValue(table.path("sourceColumns"), List.class));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void combinesAlignedTablesButKeepsBlankColumnsInsideTheirOriginalGrids(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("横並び"); table(sheet, 0, 2, 0, 2); table(sheet, 0, 2, 5, 6);
            cell(sheet, 0, 0, "商品"); cell(sheet, 0, 2, "金額"); cell(sheet, 0, 5, "担当"); cell(sheet, 0, 6, "備考");
            cell(sheet, 1, 0, "りんご"); cell(sheet, 1, 2, "100円"); cell(sheet, 1, 5, "田中"); cell(sheet, 1, 6, "特売");
            try (var result = convert(book)) {
                assertEquals(1, tables(result).size());
                assertTrue(md(result).contains("| りんご |  | 100円 | 田中 | 特売 |"));
                assertEquals(List.of("A1:C3", "F1:G3"), JSON.convertValue(tables(result).getFirst().path("detectedRanges"), List.class));
                assertEquals(List.of(1, 2, 3, 6, 7), JSON.convertValue(tables(result).getFirst().path("sourceColumns"), List.class));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void completeGridRemainsTheSeedWhenOnlyOneSideRowHasAnUnmergedVisualSpan(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("見た目の結合"); table(sheet, 0, 2, 0, 3);
            cell(sheet, 0, 0, "商品"); cell(sheet, 0, 1, "金額"); cell(sheet, 0, 2, "説明"); cell(sheet, 0, 3, "詳細");
            cell(sheet, 1, 0, "りんご"); cell(sheet, 1, 1, "100円"); cell(sheet, 1, 2, "期間限定"); cell(sheet, 1, 3, "特売品");
            cell(sheet, 2, 0, "みかん"); cell(sheet, 2, 2, "通常"); cell(sheet, 2, 3, "在庫あり");
            missingDivider(sheet, 1, 2);
            assertEquals(0, sheet.getNumMergedRegions());
            byte[] input = bytes(book);
            Path fixture = Path.of("target/fixtures/table-expansion." + (xlsx ? "xlsx" : "xls"));
            Files.createDirectories(fixture.getParent()); Files.write(fixture, input);
            try (var result = new ExcelMarkdownService(ConversionLimits.defaults()).convert(input, fixture.getFileName().toString())) {
                assertEquals(1, tables(result).size());
                assertTrue(md(result).contains("| りんご | 100円 | 期間限定　特売品 |  |"));
                assertTrue(md(result).contains("| みかん |  | 通常 | 在庫あり |"));
                assertEquals(List.of("A1:B3"), JSON.convertValue(tables(result).getFirst().path("detectedRanges"), List.class));
                assertEquals(List.of("C2:D2"), JSON.convertValue(tables(result).getFirst().path("inferredMergedRanges"), List.class));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void acceptsAnAttachedSideWithNoInteriorVerticalDividers(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("横の区画"); table(sheet, 0, 1, 0, 3);
            missingDivider(sheet, 0, 2); missingDivider(sheet, 1, 2);
            cell(sheet, 0, 0, "主表"); cell(sheet, 0, 2, "補足"); cell(sheet, 1, 0, "商品"); cell(sheet, 1, 3, "右寄りの値");
            try (var result = convert(book)) {
                assertTrue(md(result).contains("| 商品 |  | 右寄りの値 |  |"));
                assertEquals(2, tables(result).getFirst().path("inferredMergedRanges").size());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void plainDataNeverCreatesATableAndUnborderedNotesRemainSeparateColumns(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet plain = book.createSheet("本文"); cell(plain, 0, 0, "単独本文"); cell(plain, 0, 3, "独立補足");
            cell(plain, 1, 0, "続き"); cell(plain, 1, 3, "末尾");
            Sheet sheet = book.createSheet("表"); table(sheet, 0, 1, 0, 1);
            cell(sheet, 0, 0, "主表"); cell(sheet, 1, 2, "補足A"); cell(sheet, 1, 3, "補足B");
            try (var result = convert(book)) {
                assertEquals(1, tables(result).size());
                assertTrue(md(result).contains("単独本文　独立補足  \n続き　末尾"));
                assertTrue(md(result).contains("|  |  | 補足A | 補足B |"));
                assertTrue(tables(result).getFirst().path("inferredMergedRanges").isEmpty());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void infersTheLeftSideSpanWithoutChangingTheCoreGrid(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("左側"); table(sheet, 0, 1, 0, 3);
            missingDivider(sheet, 0, 0); missingDivider(sheet, 1, 0);
            cell(sheet, 0, 0, "左の分類"); cell(sheet, 0, 1, "左の補足"); cell(sheet, 0, 2, "商品"); cell(sheet, 0, 3, "金額");
            try (var result = convert(book)) {
                assertTrue(md(result).contains("| 左の分類　左の補足 |  | 商品 | 金額 |"));
                assertEquals(List.of("C1:D2"), JSON.convertValue(tables(result).getFirst().path("detectedRanges"), List.class));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void doesNotJoinAlignedSeedsThroughAnotherTableWithDifferentRows(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("別表をまたがない");
            table(sheet, 1, 3, 0, 1); table(sheet, 0, 4, 4, 5); table(sheet, 1, 3, 8, 9);
            cell(sheet, 1, 0, "左表"); cell(sheet, 0, 4, "中央の別表"); cell(sheet, 1, 8, "右表");
            try (var result = convert(book)) {
                assertEquals(3, tables(result).size());
                for (JsonNode table : tables(result)) assertEquals(1, table.path("detectedRanges").size());
                for (String value : List.of("左表", "中央の別表", "右表")) assertEquals(1, md(result).split(value, -1).length - 1);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void differentRowRangesRemainSeparateAndSideValuesAreNotDuplicated(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("段違い"); table(sheet, 0, 2, 0, 1); table(sheet, 1, 3, 5, 6);
            cell(sheet, 0, 0, "左表"); cell(sheet, 1, 5, "右表"); cell(sheet, 1, 2, "左の補足"); cell(sheet, 2, 4, "右の補足");
            try (var result = convert(book)) {
                assertEquals(2, tables(result).size());
                assertEquals(1, md(result).split("左の補足", -1).length - 1);
                assertEquals(1, md(result).split("右の補足", -1).length - 1);
                assertEquals("A1:C3", tables(result).get(0).path("range").asText());
                assertEquals("E2:G4", tables(result).get(1).path("range").asText());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void hiddenAndStruckSideValuesStayExcludedAndRealMergesKeepTheirAnchor(boolean xlsx) throws Exception {
        try (Workbook book = workbook(xlsx)) {
            Sheet sheet = book.createSheet("除外"); table(sheet, 0, 2, 0, 1); cell(sheet, 0, 0, "主表");
            cell(sheet, 1, 3, "隠し列の秘密"); sheet.setColumnHidden(3, true);
            cell(sheet, 2, 4, "隠し行の秘密"); sheet.getRow(2).setZeroHeight(true);
            Cell strike = cell(sheet, 0, 4, "取消線の秘密"); Font font = book.createFont(); font.setStrikeout(true);
            CellStyle style = book.createCellStyle(); style.setFont(font); strike.setCellStyle(style);
            cell(sheet, 1, 5, "結合アンカー"); cell(sheet, 1, 6, "結合内部の秘密"); sheet.addMergedRegion(new CellRangeAddress(1, 1, 5, 6));
            try (var result = convert(book)) {
                assertFalse(md(result).contains("秘密"));
                assertTrue(md(result).contains("|  |  | 結合アンカー |  |"));
                assertEquals(List.of(1, 2, 6, 7), JSON.convertValue(tables(result).getFirst().path("sourceColumns"), List.class));
            }
        }
    }

    @Test void nativeHeaderSurvivesExpansionAndExpandedCellsStillRespectConfiguredLimits() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("テーブル"); table(sheet, 0, 1, 0, 1);
            cell(sheet, 0, 0, "商品"); cell(sheet, 0, 1, "価格"); cell(sheet, 1, 0, "りんご");
            sheet.createTable(new AreaReference("A1:B2", book.getSpreadsheetVersion()));
            cell(sheet, 0, 3, "備考"); cell(sheet, 1, 3, "特売");
            try (var result = convert(book)) {
                assertTrue(md(result).contains("| 商品 | 価格 | 備考 |\n| --- | --- | --- |\n| りんご |  | 特売 |"));
                assertEquals("excel-table", tables(result).getFirst().path("header").asText());
            }
            var limits = com.convertx2x.office2md.AppConfig.from(java.util.Map.of("CONVERSION_MAX_TABLE_CELLS", "4")).limits();
            assertEquals("TABLE_CELLS_LIMIT", assertThrows(ConversionException.class,
                    () -> new ExcelMarkdownService(limits).convert(bytes(book), "tables.xlsx")).code());
        }
    }
}
