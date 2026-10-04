package com.convertx2x.office2md.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.convertx2x.office2md.conversion.ExcelMarkdownServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BorderTablesNeighboringFramesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void offsetMatrixBesideMetadataNeedsNoBlankSeparatorRow(boolean xlsx) throws Exception {
        // Move the caption independently of the lower table's B:H width. E3 only
        // touches the metadata diagonally; F3/G3 do not touch it at all.
        for (int startColumn : new int[] {4, 5, 6}) for (int gap : new int[] {0, 1})
            try (Workbook workbook = book(xlsx)) {
                Sheet sheet = example(workbook, startColumn, gap);
                try (ConversionResult result = convert(workbook)) {
                    JsonNode report = report(result);
                    List<JsonNode> tables = tables(report);
                    String mainRange = new CellRangeAddress(2 + gap, 5 + gap, 1, 7).formatAsString();
                    assertEquals(List.of("B1:D2", mainRange),
                            tables.stream().map(block -> block.path("range").asText()).toList(), report.toString());
                    assertFalse(hasWarning(report, "BORDER_NOT_TABLE"), report.toString());
                    assertEquals("[" + (3 + gap) + "," + (4 + gap) + "]",
                            tables.get(1).path("headerSourceRows").toString(), report.toString());
                    String md = Files.readString(result.files().get("document.md"));
                    assertTrue(md.contains("| テーブルID | DEMO |"), md);
                    assertTrue(md.contains("| 001 | 部品A | 分類A | 10 | 20 | 30 | 40 |"), md);
                    assertTrue(md.contains("| 002 | 部品B | 分類B | 11 | 21 | 31 | 41 |"), md);
                    assertTrue(md.contains("更新処理 / " + sheet.getRow(3 + gap).getCell(startColumn).getStringCellValue()), md);
                }
            }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void adjacentMetadataDoesNotChangeNamedRangeOrOpenCornerNotes(boolean xlsx) throws Exception {
        try (Workbook workbook = book(xlsx)) {
            Sheet sheet = example(workbook, 5, 0);
            cell(sheet, 2, 1, "更新時の注記");
            namedRange(workbook, sheet, "UpdateRules", "'更新仕様'!$B$3:$H$6", false);
            try (ConversionResult result = convert(workbook)) {
                String md = Files.readString(result.files().get("document.md"));
                assertTrue(md.contains("**UpdateRules**"), md);
                assertTrue(md.indexOf("更新時の注記") < md.indexOf("**UpdateRules**"), md);
                assertFalse(md.contains("| 更新時の注記 |"), md);
                assertEquals(List.of("B1:D2", "B3:H6"),
                        tables(report(result)).stream().map(block -> block.path("range").asText()).toList());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingMatrixBorderIsNotRepairedByUnrelatedMetadata(boolean xlsx) throws Exception {
        for (boolean captionTop : new boolean[] {true, false}) try (Workbook workbook = book(xlsx)) {
            Sheet sheet = example(workbook, 5, 0);
            // Either the merged caption itself or the closed lower grid is open.
            Cell damaged = sheet.getRow(captionTop ? 2 : 3).getCell(captionTop ? 5 : 1);
            CellStyle open = workbook.createCellStyle();
            open.cloneStyleFrom(damaged.getCellStyle());
            open.setBorderTop(BorderStyle.NONE);
            damaged.setCellStyle(open);
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = report(result);
                assertEquals(List.of("B1:D2"),
                        tables(report).stream().map(block -> block.path("range").asText()).toList(), report.toString());
                assertTrue(hasWarning(report, "BORDER_NOT_TABLE"), report.toString());
                assertTrue(Files.readString(result.files().get("document.md")).contains("部品A"));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void orphanHorizontalLineAboveOpenCornerStillPreventsMatrixInference(boolean xlsx) throws Exception {
        try (Workbook workbook = book(xlsx)) {
            Sheet sheet = example(workbook, 5, 0);
            removeMetadata(sheet);
            CellStyle bottomOnly = workbook.createCellStyle();
            bottomOnly.setBorderBottom(BorderStyle.THIN);
            cell(sheet, 1, 1, "").setCellStyle(bottomOnly);
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = report(result);
                assertTrue(tables(report).isEmpty(), report.toString());
                assertTrue(hasWarning(report, "BORDER_NOT_TABLE"), report.toString());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void verticalLineWithoutASeparateClosedCellStillCountsAsAProtrusion(boolean xlsx) throws Exception {
        for (boolean above : new boolean[] {true, false}) try (Workbook workbook = book(xlsx)) {
            Sheet sheet = workbook.createSheet("未完成の表");
            table(sheet, 2, 4, 1, 3);
            cell(sheet, 2, 1, "見出し");
            cell(sheet, 3, 1, "明細");
            CellStyle verticalOnly = workbook.createCellStyle();
            verticalOnly.setBorderLeft(BorderStyle.THIN);
            cell(sheet, above ? 1 : 5, 1, "").setCellStyle(verticalOnly);
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = report(result);
                assertTrue(tables(report).isEmpty(), report.toString());
                assertTrue(hasWarning(report, "BORDER_NOT_TABLE"), report.toString());
                assertTrue(Files.readString(result.files().get("document.md")).contains("明細"));
            }
        }
    }

    private static Sheet example(Workbook workbook, int startColumn, int gap) {
        Sheet sheet = workbook.createSheet("更新仕様");
        CellStyle border = grid(workbook);
        for (int row = 0; row < 2; row++) {
            cell(sheet, row, 1, row == 0 ? "テーブルID" : "テーブル名称").setCellStyle(border);
            cell(sheet, row, 2, row == 0 ? "DEMO" : "架空の仕様").setCellStyle(border);
            // Only the anchor stores the merged cell's perimeter.
            sheet.addMergedRegion(new CellRangeAddress(row, row, 2, 3));
        }
        int top = 2 + gap;
        cell(sheet, top, startColumn, "更新処理").setCellStyle(border);
        sheet.addMergedRegion(new CellRangeAddress(top, top, startColumn, 7));
        colorRow(sheet, top, startColumn, startColumn, IndexedColors.GREY_25_PERCENT.getIndex());
        table(sheet, top + 1, top + 3, 1, 7);
        String[][] values = {
                {"ID", "名称", "区分", "登録", "変更", "取消", "備考"},
                {"001", "部品A", "分類A", "10", "20", "30", "40"},
                {"002", "部品B", "分類B", "11", "21", "31", "41"}
        };
        for (int row = 0; row < values.length; row++)
            for (int column = 0; column < values[row].length; column++)
                cell(sheet, top + 1 + row, 1 + column, values[row][column]);
        colorRow(sheet, top + 1, 1, 7, IndexedColors.GREY_25_PERCENT.getIndex());
        colorRow(sheet, top + 2, 1, 1, IndexedColors.GREY_25_PERCENT.getIndex());
        return sheet;
    }

    private static void removeMetadata(Sheet sheet) {
        sheet.removeMergedRegions(List.of(0, 1));
        sheet.removeRow(sheet.getRow(0));
        sheet.removeRow(sheet.getRow(1));
    }

    private static Workbook book(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }
    private static ConversionResult convert(Workbook workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return new ExcelMarkdownService(ConversionLimits.defaults()).convert(output.toByteArray(),
                workbook instanceof XSSFWorkbook ? "example.xlsx" : "example.xls");
    }
    private static JsonNode report(ConversionResult result) throws Exception {
        return JSON.readTree(result.files().get("report.json").toFile());
    }
    private static List<JsonNode> tables(JsonNode report) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode block : report.path("blocks"))
            if ("table".equals(block.path("type").asText())) result.add(block);
        return result;
    }
    private static boolean hasWarning(JsonNode report, String code) {
        for (JsonNode warning : report.path("warnings"))
            if (code.equals(warning.path("code").asText())) return true;
        return false;
    }
}
