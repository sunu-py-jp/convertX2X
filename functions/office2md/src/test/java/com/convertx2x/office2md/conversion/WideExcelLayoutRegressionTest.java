package com.convertx2x.office2md.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A wide specification sheet has boxed metadata above a sparse, multiline main grid. */
class WideExcelLayoutRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anchorOnlyBorderOnMergedHeadingKeepsTheLowerWideGridAsOneTable() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("更新仕様");
            CellStyle grid = grid(workbook);
            CellStyle heading = workbook.createCellStyle();
            heading.cloneStyleFrom(grid);
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            CellStyle coveredHeading = workbook.createCellStyle();
            coveredHeading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            coveredHeading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());

            // Metadata is above the table, with a completely blank row between them.
            cell(sheet, 1, 2, "テーブルID", grid);
            cell(sheet, 1, 3, "ZATRA", grid);
            cell(sheet, 2, 2, "テーブル名称", grid);
            cell(sheet, 2, 3, "在庫受払トラン", grid);

            // C7:I7 are ordinary gray headers. J7:N7 is one merged gray caption.
            // In actual Excel files, only the merge's anchor may carry borders;
            // covered cells often retain just the fill. The lower grid is complete.
            String[] leftHeadings = {"No.", "項目ID", "項目名称", "主キー", "型", "桁", "備考"};
            for (int column = 2; column <= 8; column++)
                cell(sheet, 6, column, leftHeadings[column - 2], heading);
            cell(sheet, 6, 9, "月次仮締処理", heading);
            for (int column = 10; column <= 13; column++) cell(sheet, 6, column, "", coveredHeading);
            sheet.addMergedRegion(new CellRangeAddress(6, 6, 9, 13));

            for (int row = 7; row <= 11; row++) for (int column = 2; column <= 13; column++)
                cell(sheet, row, column, "", grid);
            cell(sheet, 7, 2, "1", grid);
            cell(sheet, 7, 3, "DATNO", grid);
            cell(sheet, 7, 4, "伝票管理番号", grid);
            cell(sheet, 7, 9, "更新条件：伝票番号", grid);
            cell(sheet, 11, 2, "5", grid);
            cell(sheet, 11, 3, "DKBSB", grid);
            cell(sheet, 11, 4, "伝票種別", grid);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            try (ConversionResult result = new ExcelMarkdownService(ConversionLimits.defaults())
                    .convert(bytes.toByteArray(), "merged-heading.xlsx")) {
                String markdown = Files.readString(result.files().get("document.md"));
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                List<JsonNode> tables = new ArrayList<>();
                for (JsonNode block : report.path("blocks")) if ("table".equals(block.path("type").asText()))
                    tables.add(block);
                JsonNode main = tables.stream().filter(block -> "C7:N12".equals(block.path("range").asText()))
                        .findFirst().orElse(null);
                assertNotNull(main, "The merged caption must not turn this grid into prose: " + report.path("blocks")
                        + "\n" + markdown);
                assertEquals("[7]", main.path("headerSourceRows").toString());
                assertTrue(markdown.contains("月次仮締処理"), markdown);
                assertTrue(markdown.contains("| 1 | DATNO | 伝票管理番号 |"), markdown);
                assertTrue(markdown.contains("| 5 | DKBSB | 伝票種別 |"), markdown);
            }
        }
    }

    @Test
    void boxedMetadataDoesNotSplitOrFlattenTheWideMainTable() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("ZATRA");
            CellStyle grid = grid(workbook);
            CellStyle heading = workbook.createCellStyle();
            heading.cloneStyleFrom(grid);
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());

            // A small, independently bordered metadata block is separated from the large table.
            for (int row = 1; row <= 2; row++) for (int column = 2; column <= 4; column++)
                cell(sheet, row, column, "", grid);
            cell(sheet, 1, 2, "テーブルID", grid);
            cell(sheet, 1, 3, "ZATRA", grid);
            cell(sheet, 2, 2, "テーブル名称", grid);
            cell(sheet, 2, 3, "在庫受払トラン", grid);
            cell(sheet, 4, 2, "テーブル更新仕様", workbook.createCellStyle());

            // C7:N12: gray heading, many sparsely populated body cells, line breaks and merges.
            for (int row = 6; row <= 11; row++) for (int column = 2; column <= 13; column++)
                cell(sheet, row, column, "", row == 6 ? heading : grid);
            String[] headings = {"No.", "項目ID", "項目名称", "主キー", "型", "桁", "備考",
                    "更新（初期化）\nCRUD: Update\n更新条件", "更新（品転元）\nCRUD: Update",
                    "更新（製品／半製品）\nCRUD: Update", "更新（製品／半製品・品転元）", "更新（月次）"};
            for (int column = 2; column <= 13; column++) cell(sheet, 6, column, headings[column - 2], heading);

            String[][] rows = {
                    {"1", "DATNO", "伝票管理番号", "1", "$", "16", "", "FP_SYSTEM", "", "", "", ""},
                    {"2", "DATKB", "削除区分", "", "$", "1", "", "", "FP_SYSTEM", "", "", ""},
                    {"3", "UKEHRDKB", "受払区分", "", "$", "1", "", "", "", "", "", ""},
                    {"4", "TNKB", "有商無対区分", "", "$", "1", "共通備考", "", "", "", "", ""},
                    {"5", "DKBSB", "伝票種別", "", "$", "3", "", "", "", "", "", ""}
            };
            for (int index = 0; index < rows.length; index++)
                for (int column = 2; column <= 13; column++)
                    cell(sheet, index + 7, column, rows[index][column - 2], grid);
            cell(sheet, 8, 13, "更新条件：伝票番号\nFP_SYSTEM", grid);
            sheet.addMergedRegion(new CellRangeAddress(9, 10, 8, 8));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            try (ConversionResult result = new ExcelMarkdownService(ConversionLimits.defaults())
                    .convert(bytes.toByteArray(), "wide-specification.xlsx")) {
                String markdown = Files.readString(result.files().get("document.md"));
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                List<JsonNode> tables = new ArrayList<>();
                for (JsonNode block : report.path("blocks")) if ("table".equals(block.path("type").asText()))
                    tables.add(block);
                JsonNode main = tables.stream().filter(block -> "C7:N12".equals(block.path("range").asText()))
                        .findFirst().orElse(null);
                assertNotNull(main, "Wide main table must remain a table: " + report.path("blocks"));
                assertEquals("[7]", main.path("headerSourceRows").toString());
                assertEquals("[7,8,9,10,11,12]", main.path("sourceRows").toString());
                assertTrue(markdown.contains("テーブルID"), markdown);
                assertTrue(markdown.contains("在庫受払トラン"), markdown);
                assertTrue(markdown.contains("テーブル更新仕様"), markdown);
                assertTrue(markdown.contains("更新（初期化）<br>CRUD: Update<br>更新条件"), markdown);
                assertTrue(markdown.contains("| 1 | DATNO | 伝票管理番号 |"), markdown);
                assertTrue(markdown.contains("| 5 | DKBSB | 伝票種別 |"), markdown);
                assertTrue(markdown.contains("更新条件：伝票番号<br>FP\\_SYSTEM"), markdown);
            }
        }
    }

    private static CellStyle grid(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static Cell cell(Sheet sheet, int rowIndex, int column, String value, CellStyle style) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        Cell cell = row.getCell(column);
        if (cell == null) cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
        return cell;
    }
}
