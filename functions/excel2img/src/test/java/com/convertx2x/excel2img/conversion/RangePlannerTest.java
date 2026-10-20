package com.convertx2x.excel2img.conversion;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Rectangle2D;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class RangePlannerTest {
    private static final ConversionLimits DEFAULTS = ConversionLimits.defaults();
    private static final double EPS = 1e-6;

    private static Sheet sheet(XSSFWorkbook book, int columns, int rows, int columnWidth, int rowHeight, int fontSize) {
        Sheet sheet = book.createSheet("Layout");
        var font = book.createFont(); font.setFontName("BIZ UDPGothic"); font.setFontHeightInPoints((short) fontSize);
        var style = book.createCellStyle(); style.setFont(font);
        for (int c = 0; c < columns; c++) sheet.setColumnWidth(c, columnWidth * 256);
        for (int r = 0; r < rows; r++) {
            var row = sheet.createRow(r); row.setHeightInPoints(rowHeight);
            for (int c = 0; c < columns; c++) {
                var cell = row.createCell(c); cell.setCellValue("R" + r + "C" + c); cell.setCellStyle(style);
            }
        }
        return sheet;
    }
    private static RangePlanner.Plan plan(Sheet sheet, CellRangeAddress cells, RenderPolicy policy, ConversionWorkspace workspace) {
        return RangePlanner.plan(sheet, cells, new SheetLayout(sheet), policy, workspace.limits(), new CellValues(workspace), workspace);
    }
    private static RenderPolicy policy(int width, int height) { return new RenderPolicy(width, height, 12, 2, 200_000, 20_000_000); }
    private static boolean warning(ConversionWorkspace workspace, String code) {
        return workspace.warnings().stream().anyMatch(item -> code.equals(item.get("code")));
    }
    private static void assertCoverage(RangePlanner.Plan plan, RenderPolicy policy) {
        assertNull(plan.skipReason()); assertFalse(plan.pages().isEmpty());
        Rectangle2D bounds = plan.bounds();
        double y = bounds.getY(); int cursor = 0;
        while (cursor < plan.pages().size()) {
            RangePlanner.Page first = plan.pages().get(cursor);
            assertEquals(y, first.y(), EPS);
            double rowHeight = first.height(), x = bounds.getX();
            do {
                RangePlanner.Page page = plan.pages().get(cursor++);
                assertEquals(x, page.x(), EPS); assertEquals(y, page.y(), EPS);
                assertEquals(rowHeight, page.height(), EPS);
                assertTrue(page.width() > 0); assertTrue(page.height() > 0);
                assertTrue(RangePlanner.positiveCeil(page.width() * plan.scale()) <= policy.maxWidthPixels());
                assertTrue(RangePlanner.positiveCeil(page.height() * plan.scale()) <= policy.maxHeightPixels());
                assertTrue(page.firstRow() <= page.lastRow()); assertTrue(page.firstColumn() <= page.lastColumn());
                assertTrue(page.firstRow() >= plan.cells().getFirstRow()); assertTrue(page.lastRow() <= plan.cells().getLastRow());
                assertTrue(page.firstColumn() >= plan.cells().getFirstColumn()); assertTrue(page.lastColumn() <= plan.cells().getLastColumn());
                assertEquals(new Rectangle2D.Double(page.x(), page.y(), page.width(), page.height()), page.bounds());
                x += page.width();
            } while (cursor < plan.pages().size() && Math.abs(plan.pages().get(cursor).y() - y) < EPS);
            assertEquals(bounds.getMaxX(), x, EPS);
            y += rowHeight;
        }
        assertEquals(bounds.getMaxY(), y, EPS);
    }

    @Test void readableFitKeepsExistingUniformShrinkAndSingleHorizontalPage() {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 24, 4, 14, 24, 11);
            RenderPolicy policy = RenderPolicy.defaults();
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 3, 0, 23), policy, workspace);
            assertEquals(policy.maxWidthPixels() / plan.bounds().width, plan.scale(), EPS);
            assertEquals(1, plan.pages().size()); assertTrue(plan.scale() * 11 >= 12);
            assertTrue(warning(workspace, "RANGE_SCALED")); assertFalse(warning(workspace, "HORIZONTAL_PAGINATION"));
            assertCoverage(plan, policy);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    @Test void wideRangeShrinksOnlyToReadableScaleThenSplitsAcrossColumns() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 48, 4, 14, 24, 11);
            RenderPolicy policy = RenderPolicy.defaults();
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 3, 0, 47), policy, workspace);
            assertEquals(12.0 / 11, plan.scale(), EPS);
            assertEquals(2, plan.pages().size());
            assertEquals(0, plan.pages().getFirst().firstColumn()); assertEquals(47, plan.pages().getLast().lastColumn());
            assertEquals(plan.pages().getFirst().lastColumn() + 1, plan.pages().getLast().firstColumn());
            assertTrue(warning(workspace, "HORIZONTAL_PAGINATION"));
            assertCoverage(plan, policy);
        }
    }

    @Test void pagesReadLeftToRightWithinEachBandAndCoverEntireSource() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 8, 6, 20, 40, 11);
            RenderPolicy policy = policy(200, 100);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 5, 0, 7), policy, workspace);
            assertEquals(24, plan.pages().size());
            assertEquals(0, plan.pages().get(0).firstColumn()); assertEquals(7, plan.pages().get(7).firstColumn());
            assertEquals(0, plan.pages().get(8).firstColumn()); assertEquals(2, plan.pages().get(8).firstRow());
            assertCoverage(plan, policy);
        }
    }

    @Test void fittingMergeMovesColumnBreakBeforeItsStart() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 6, 2, 10, 30, 11);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 1, 3));
            RenderPolicy policy = policy(180, 200);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 1, 0, 5), policy, workspace);
            assertEquals(3, plan.pages().size());
            assertEquals(0, plan.pages().get(0).lastColumn());
            assertEquals(1, plan.pages().get(1).firstColumn()); assertEquals(3, plan.pages().get(1).lastColumn());
            assertFalse(warning(workspace, "MERGE_SPLIT_ACROSS_PAGES"));
            assertCoverage(plan, policy);
        }
    }

    @Test void oversizedTitleDoesNotPreventPreservingSmallerMergedHeaders() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 6, 3, 10, 30, 11);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 1, 3));
            RenderPolicy policy = policy(180, 200);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 2, 0, 5), policy, workspace);
            assertEquals(0, plan.pages().getFirst().lastColumn());
            assertEquals(1, plan.pages().get(1).firstColumn()); assertEquals(3, plan.pages().get(1).lastColumn());
            assertTrue(warning(workspace, "MERGE_SPLIT_ACROSS_PAGES"));
            assertCoverage(plan, policy);
        }
    }

    @Test void fittingVerticalMergeMovesRowBreakBeforeItsStart() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 2, 6, 10, 30, 11);
            sheet.addMergedRegion(new CellRangeAddress(1, 3, 0, 0));
            RenderPolicy policy = policy(500, 200);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 5, 0, 1), policy, workspace);
            assertEquals(3, plan.pages().size());
            assertEquals(0, plan.pages().get(0).lastRow());
            assertEquals(1, plan.pages().get(1).firstRow()); assertEquals(3, plan.pages().get(1).lastRow());
            assertFalse(warning(workspace, "MERGE_SPLIT_ACROSS_PAGES"));
            assertCoverage(plan, policy);
        }
    }

    @Test void oversizedSingleCellIsCoveredByContinuousWindowsOnBothAxes() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 1, 1, 80, 350, 11);
            RenderPolicy policy = policy(120, 100);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 0, 0, 0), policy, workspace);
            assertTrue(plan.pages().size() > 4);
            assertTrue(plan.pages().stream().allMatch(page -> page.firstColumn() == 0 && page.lastColumn() == 0 && page.firstRow() == 0 && page.lastRow() == 0));
            assertTrue(warning(workspace, "COLUMN_SPLIT_ACROSS_PAGES"));
            assertTrue(warning(workspace, "ROW_SPLIT_ACROSS_PAGES"));
            assertCoverage(plan, policy);
        }
    }

    @Test void oversizedMergeHasContinuousCoverageOnBothAxes() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 6, 4, 10, 60, 11);
            sheet.addMergedRegion(new CellRangeAddress(0, 3, 0, 5));
            RenderPolicy policy = policy(200, 120);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 3, 0, 5), policy, workspace);
            assertEquals(8, plan.pages().size());
            assertTrue(warning(workspace, "MERGE_SPLIT_ACROSS_PAGES"));
            assertCoverage(plan, policy);
        }
    }

    @Test void alreadySmallTextUsesBaseResolutionWithoutUpscalingOrRejecting() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 10, 2, 10, 30, 5);
            RenderPolicy policy = policy(400, 200);
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 1, 0, 9), policy, workspace);
            assertEquals(2, plan.scale()); assertTrue(plan.pages().size() > 1);
            assertTrue(warning(workspace, "TEXT_BELOW_MINIMUM"));
            assertFalse(warning(workspace, "RANGE_SCALED"));
            assertCoverage(plan, policy);
        }
    }

    @Test void noCellTextLimitsShrinkingToHalfOfBaseResolution() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 48, 3, 12, 30, 11);
            for (var row : sheet) for (var cell : row) cell.setBlank();
            RenderPolicy policy = RenderPolicy.defaults();
            RangePlanner.Plan plan = plan(sheet, new CellRangeAddress(0, 2, 0, 47), policy, workspace);
            assertEquals(1, plan.scale()); assertEquals(2, plan.pages().size());
            assertCoverage(plan, policy);
        }
    }

    @Test void selectedOffsetAndHiddenDimensionsRemainInWorldCoordinates() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = new ConversionWorkspace(DEFAULTS)) {
            Sheet sheet = sheet(book, 10, 10, 20, 30, 11);
            sheet.setColumnHidden(3, true); sheet.getRow(3).setZeroHeight(true);
            RenderPolicy policy = policy(250, 150);
            CellRangeAddress selected = new CellRangeAddress(2, 8, 2, 8);
            RangePlanner.Plan plan = plan(sheet, selected, policy, workspace);
            SheetLayout geometry = new SheetLayout(sheet);
            assertEquals(geometry.x(2), plan.bounds().x); assertEquals(geometry.y(2), plan.bounds().y);
            assertTrue(plan.pages().size() > 1);
            assertCoverage(plan, policy);
        }
    }

    @Test void pagePixelAndCellLimitsApplyToTheNewTiling() throws Exception {
        try (var book = new XSSFWorkbook()) {
            Sheet sheet = sheet(book, 8, 6, 20, 40, 11);
            CellRangeAddress cells = new CellRangeAddress(0, 5, 0, 7);
            try (var workspace = new ConversionWorkspace(new ConversionLimits(1_000_000, 1_000_000, 6, 20_000_000))) {
                assertEquals("PAGE_LIMIT", assertThrows(ConversionException.class,
                        () -> plan(sheet, cells, policy(200, 100), workspace)).code());
            }
            try (var workspace = new ConversionWorkspace(new ConversionLimits(1_000_000, 1_000_000, 50, 100))) {
                assertEquals("IMAGE_PIXELS_LIMIT", assertThrows(ConversionException.class,
                        () -> plan(sheet, cells, policy(200, 100), workspace)).code());
            }
            try (var workspace = new ConversionWorkspace(DEFAULTS)) {
                RenderPolicy tiny = new RenderPolicy(200, 100, 12, 2, 10, 20_000_000);
                RangePlanner.Plan plan = plan(sheet, cells, tiny, workspace);
                assertEquals("CELL_LIMIT", plan.skipReason()); assertEquals(List.of(), plan.pages());
            }
        }
    }

    @Test void pageLimitIncludesPagesAlreadyProducedByAnotherRange() throws Exception {
        try (var book = new XSSFWorkbook();
             var workspace = new ConversionWorkspace(new ConversionLimits(1_000_000, 1_000_000, 2, 20_000_000))) {
            Sheet sheet = sheet(book, 4, 1, 20, 30, 11);
            workspace.pagesGenerated(1);
            assertEquals("PAGE_LIMIT", assertThrows(ConversionException.class,
                    () -> plan(sheet, new CellRangeAddress(0, 0, 0, 3), policy(250, 200), workspace)).code());
        }
    }
}
