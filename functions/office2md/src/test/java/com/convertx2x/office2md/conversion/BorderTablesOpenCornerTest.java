package com.convertx2x.office2md.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Set;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class BorderTablesOpenCornerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void contiguousBlankTopCornerWithMergedGroupHeaderFormsOneTable(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = example(workbook);
            try (ConversionResult result = convert(workbook)) {
                String markdown = Files.readString(result.files().get("document.md"));
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                JsonNode blocks = report.path("blocks");
                assertEquals(1, blocks.size(), blocks.toString());
                assertEquals("table", blocks.get(0).path("type").asText());
                assertEquals("A1:D4", blocks.get(0).path("range").asText());
                assertEquals("fill-color", blocks.get(0).path("header").asText());
                assertEquals("[1,2]", blocks.get(0).path("headerSourceRows").toString());
                assertTrue(markdown.contains("| ID | 場所 | 売上 / 1月 | 売上 / 2月 |\n"
                        + "| --- | --- | --- | --- |\n"
                        + "| 001 | 東京 | 10 | 12 |\n"
                        + "| 002 | 大阪 | 8 | 9 |"), markdown);
                assertTrue(hasInfoAt(report, "A1"), report.toString());
                assertTrue(hasInfoAt(report, "B1"), report.toString());
                assertFalse(hasInfoAt(report, "C1"), report.toString());
                assertEquals(1, sheet.getMergedRegions().size());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unborderedTextInsideTopCornerIsAcceptedAsANoteCoordinate(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = example(workbook);
            sheet.getRow(0).getCell(1).setCellValue("注記");
            try (ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
                BorderTables borders = new BorderTables(sheet, new MergedRanges(sheet.getMergedRegions()), workspace);
                assertEquals("A1:D4", borders.detect().getFirst().formatAsString());
                assertEquals(Set.of(BorderTables.key(0, 0), BorderTables.key(0, 1)), borders.openTopCells());
            }
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                boolean fullTable = false;
                for (JsonNode block : report.path("blocks"))
                    if ("table".equals(block.path("type").asText()) && "A1:D4".equals(block.path("range").asText()))
                        fullTable = true;
                assertTrue(fullTable, report.toString());
                assertTrue(hasInfoAt(report, "A1"), report.toString());
                assertTrue(hasDiagnosticAt(report, "TABLE_OPEN_TOP_NOTE", "B1"), report.toString());
                assertTrue(Files.readString(result.files().get("document.md")).contains("注記"));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void formulaInsideOpenTopRunIsRejected(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = example(workbook);
            sheet.getRow(0).getCell(1).setCellFormula("1+1");
            try (ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
                BorderTables borders = new BorderTables(sheet, new MergedRanges(sheet.getMergedRegions()), workspace);
                for (CellRangeAddress range : borders.detect())
                    assertNotEquals("A1:D4", range.formatAsString());
                assertTrue(borders.openTopCells().isEmpty());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void contiguousBlankRunAfterOrdinaryColumnsAlsoStaysInOneTable(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("担当別月次");
            CellStyle border = grid(workbook);
            for (int row = 0; row < 3; row++) {
                Row physical = sheet.createRow(row);
                for (int col = 0; col < 6; col++) physical.createCell(col).setCellStyle(border);
            }
            Row top = sheet.getRow(0);
            top.getCell(0).setCellValue("ID");
            top.getCell(1).setCellValue("場所");
            CellStyle borderless = workbook.createCellStyle();
            top.getCell(2).setCellStyle(borderless);
            top.getCell(3).setCellStyle(borderless);
            top.getCell(4).setCellValue("売上");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 4, 5));
            CellStyle colored = workbook.createCellStyle();
            colored.cloneStyleFrom(border);
            colored.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            colored.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            top.getCell(4).setCellStyle(colored);
            String[][] details = {
                    {"001", "東京", "東", "本店", "10", "12"},
                    {"002", "大阪", "西", "支店", "8", "9"}
            };
            for (int row = 1; row <= 2; row++)
                for (int col = 0; col < 6; col++) sheet.getRow(row).getCell(col).setCellValue(details[row - 1][col]);
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                JsonNode blocks = report.path("blocks");
                assertEquals(1, blocks.size(), blocks.toString());
                assertEquals("A1:F3", blocks.get(0).path("range").asText());
                assertEquals("[1]", blocks.get(0).path("headerSourceRows").toString());
                assertTrue(Files.readString(result.files().get("document.md"))
                        .contains("| 001 | 東京 | 東 | 本店 | 10 | 12 |"));
                assertTrue(hasInfoAt(report, "C1"), report.toString());
                assertTrue(hasInfoAt(report, "D1"), report.toString());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void openSuffixAfterTopHeaderDoesNotBecomeInferredTableCell(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = example(workbook);
            sheet.removeMergedRegion(0);
            Row top = sheet.getRow(0);
            top.removeCell(top.getCell(2));
            top.removeCell(top.getCell(3));
            CellStyle border = grid(workbook);
            top.createCell(0).setCellValue("ID");
            top.createCell(1).setCellValue("場所");
            top.getCell(0).setCellStyle(border);
            top.getCell(1).setCellStyle(border);
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                assertNoFullTable(report);
                assertFalse(hasInfoAt(report, "C1"), report.toString());
                assertFalse(hasInfoAt(report, "D1"), report.toString());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void blankTopRunOverOneLargeMergedCellIsNotInventedAsMatrix(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = example(workbook);
            sheet.addMergedRegion(new CellRangeAddress(1, 3, 0, 3));
            try (ConversionResult result = convert(workbook)) {
                JsonNode report = JSON.readTree(result.files().get("report.json").toFile());
                assertNoFullTable(report);
                assertFalse(hasInfoAt(report, "A1"), report.toString());
                assertFalse(hasInfoAt(report, "B1"), report.toString());
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void twoTallMergedCellsWithoutLowerRowDivisionDoNotAnchorAnOpenTopMatrix(boolean xlsx) throws Exception {
        try (Workbook workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
            Sheet sheet = example(workbook);
            sheet.addMergedRegion(new CellRangeAddress(1, 3, 0, 1));
            sheet.addMergedRegion(new CellRangeAddress(1, 3, 2, 3));
            try (ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
                BorderTables borders = new BorderTables(sheet, new MergedRanges(sheet.getMergedRegions()), workspace);
                for (CellRangeAddress range : borders.detect())
                    assertNotEquals("A1:D4", range.formatAsString());
                assertTrue(borders.openTopCells().isEmpty());
            }
        }
    }

    private static Sheet example(Workbook workbook) {
        Sheet sheet = workbook.createSheet("月次売上");
        CellStyle border = grid(workbook);
        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++) {
                Row physical = sheet.getRow(row);
                if (physical == null) physical = sheet.createRow(row);
                physical.createCell(col).setCellStyle(border);
            }
        Row top = sheet.getRow(0);
        // These are actual blank Excel cells, rather than absent records.
        CellStyle borderless = workbook.createCellStyle();
        top.getCell(0).setCellStyle(borderless);
        top.getCell(1).setCellStyle(borderless);
        top.getCell(2).setCellValue("売上");
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 2, 3));
        String[][] values = {
                {"ID", "場所", "1月", "2月"},
                {"001", "東京", "10", "12"},
                {"002", "大阪", "8", "9"}
        };
        for (int row = 1; row <= 3; row++)
            for (int col = 0; col < 4; col++) sheet.getRow(row).getCell(col).setCellValue(values[row - 1][col]);
        CellStyle colored = workbook.createCellStyle();
        colored.cloneStyleFrom(border);
        colored.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        colored.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        for (int col = 2; col < 4; col++) top.getCell(col).setCellStyle(colored);
        for (int col = 0; col < 4; col++) sheet.getRow(1).getCell(col).setCellStyle(colored);
        return sheet;
    }

    private static CellStyle grid(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static ConversionResult convert(Workbook workbook) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return new ExcelMarkdownService(ConversionLimits.defaults()).convert(output.toByteArray(),
                workbook instanceof XSSFWorkbook ? "example.xlsx" : "example.xls");
    }

    private static boolean hasInfoAt(JsonNode report, String address) {
        return hasDiagnosticAt(report, "TABLE_OPEN_TOP_CELL", address);
    }

    private static boolean hasDiagnosticAt(JsonNode report, String code, String address) {
        for (JsonNode info : report.path("information"))
            if (code.equals(info.path("code").asText()) && address.equals(info.path("range").asText()))
                return true;
        return false;
    }

    private static void assertNoFullTable(JsonNode report) {
        for (JsonNode block : report.path("blocks"))
            if ("table".equals(block.path("type").asText()))
                assertNotEquals("A1:D4", block.path("range").asText(), report.toString());
    }
}
