package com.convertx2x.excel2img.conversion;

import com.convertx2x.excel2img.drawing.ExcelDrawingLayer;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;

/** Selects explicit/print/occupied rectangles and creates vertical windows without horizontal slicing. */
final class RangePlanner {
    record Selection(CellRangeAddress cells, String source) {}
    record Page(double y, double height, int firstRow, int lastRow) {}
    record Plan(CellRangeAddress cells, Rectangle2D.Double bounds, double scale, List<Page> pages,
                String skipReason, String skipMessage) {}
    static List<Selection> selections(Sheet sheet, RenderOptions options, SheetLayout geometry,
                                      ExcelDrawingLayer drawings, ConversionWorkspace workspace, long maxCells) {
        if (options.range() != null) return List.of(new Selection(CellRangeAddress.valueOf(options.range()), "explicit"));
        String print = sheet.getWorkbook().getPrintArea(sheet.getWorkbook().getSheetIndex(sheet));
        if (print != null && !print.isBlank()) {
            try {
                List<Selection> result = new ArrayList<>();
                for (AreaReference area : AreaReference.generateContiguous(sheet.getWorkbook().getSpreadsheetVersion(), print)) {
                    var first = area.getFirstCell(); var last = area.getLastCell();
                    CellRangeAddress cells = new CellRangeAddress(first.getRow(), last.getRow(), first.getCol(), last.getCol());
                    cells.validate(sheet.getWorkbook().getSpreadsheetVersion());
                    result.add(new Selection(cells, "printArea"));
                }
                if (!result.isEmpty()) return result;
            } catch (IllegalArgumentException failure) {
                workspace.warning("PRINT_AREA_INVALID", sheet.getSheetName(), "印刷範囲を読み取れないため、使用範囲を描画しました。");
            }
        }
        int minRow = Integer.MAX_VALUE, minCol = Integer.MAX_VALUE, maxRow = -1, maxCol = -1;
        long visited = 0;
        for (Row row : sheet) {
            if (row.getZeroHeight()) continue;
            for (Cell cell : row) {
                if (++visited > maxCells) throw new ScanLimit();
                if (sheet.isColumnHidden(cell.getColumnIndex())) continue;
                if (cell.getCellType() != CellType.BLANK || cell.getCellStyle().getIndex() != 0) {
                    minRow = Math.min(minRow, row.getRowNum()); maxRow = Math.max(maxRow, row.getRowNum());
                    minCol = Math.min(minCol, cell.getColumnIndex()); maxCol = Math.max(maxCol, cell.getColumnIndex());
                }
            }
        }
        for (CellRangeAddress merge : sheet.getMergedRegions()) {
            if (maxRow >= 0 && merge.getFirstRow() <= maxRow && merge.getLastRow() >= minRow &&
                    merge.getFirstColumn() <= maxCol && merge.getLastColumn() >= minCol) {
                minRow = Math.min(minRow, merge.getFirstRow()); maxRow = Math.max(maxRow, merge.getLastRow());
                minCol = Math.min(minCol, merge.getFirstColumn()); maxCol = Math.max(maxCol, merge.getLastColumn());
            }
        }
        Rectangle2D bounds = drawings.bounds();
        if (bounds != null && bounds.getWidth() > 0 && bounds.getHeight() > 0) {
            minRow = Math.min(minRow, geometry.rowAt(bounds.getMinY()));
            maxRow = Math.max(maxRow, geometry.rowAt(Math.max(bounds.getMinY(), Math.nextDown(bounds.getMaxY()))));
            minCol = Math.min(minCol, geometry.columnAt(bounds.getMinX()));
            maxCol = Math.max(maxCol, geometry.columnAt(Math.max(bounds.getMinX(), Math.nextDown(bounds.getMaxX()))));
        }
        if (maxRow < 0) return List.of();
        return List.of(new Selection(new CellRangeAddress(minRow, maxRow, minCol, maxCol), "occupied"));
    }
    static final class ScanLimit extends RuntimeException {}

    static Plan plan(Sheet sheet, CellRangeAddress cells, SheetLayout geometry, RenderPolicy policy,
                     ConversionLimits limits, CellValues values, ConversionWorkspace workspace) {
        try { cells.validate(sheet.getWorkbook().getSpreadsheetVersion()); }
        catch (IllegalArgumentException invalid) { return skip(cells, "RANGE_OUT_OF_BOUNDS", "指定範囲がこのファイル形式の上限を超えています。"); }
        long count = ((long) cells.getLastRow() - cells.getFirstRow() + 1) *
                ((long) cells.getLastColumn() - cells.getFirstColumn() + 1);
        if (count > policy.maxCells()) return skip(cells, "CELL_LIMIT", "範囲のセル数が描画上限を超えています。");
        double x = geometry.x(cells.getFirstColumn()), y = geometry.y(cells.getFirstRow());
        double width = geometry.x(cells.getLastColumn() + 1) - x;
        double height = geometry.y(cells.getLastRow() + 1) - y;
        if (width <= 0 || height <= 0) return skip(cells, "EMPTY_VISIBLE_RANGE", "範囲に表示される行または列がありません。");
        double scale = Math.min(policy.pixelsPerPoint(), policy.maxWidthPixels() / width);
        double minimumFont = Double.POSITIVE_INFINITY;
        for (Row row : sheet) {
            if (row.getRowNum() < cells.getFirstRow() || row.getRowNum() > cells.getLastRow() || row.getZeroHeight()) continue;
            for (Cell cell : row) {
                if (cell.getColumnIndex() < cells.getFirstColumn() || cell.getColumnIndex() > cells.getLastColumn() || sheet.isColumnHidden(cell.getColumnIndex())) continue;
                if (!values.text(cell).isEmpty()) minimumFont = Math.min(minimumFont,
                        sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndex()).getFontHeightInPoints());
            }
        }
        if (Double.isFinite(minimumFont) && minimumFont * scale + 1e-6 < policy.minTextPixels())
            return skip(cells, "WIDTH_UNREADABLE", "幅を収めると文字が最小可読サイズを下回るため、この範囲をスキップしました。");
        if (scale < policy.pixelsPerPoint() - .00001) workspace.warning("RANGE_SCALED", sheet.getSheetName(),
                "横幅を上限に収めるため、文字の最小サイズを維持できる範囲で縮小しました。");
        int widthPixels = positiveCeil(width * scale);
        double pageHeight = policy.maxHeightPixels() / scale;
        if ((long) widthPixels * Math.min(policy.maxHeightPixels(), positiveCeil(height * scale)) > limits.maxImagePixels())
            throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "生成画像の画素数が上限を超えました。");
        List<Page> pages = new ArrayList<>();
        double end = y + height;
        double current = y;
        while (current < end - .00001) {
            if (ConversionLimits.exceeds(pages.size() + 1L, limits.maxPages()))
                throw ConversionWorkspace.limit("PAGE_LIMIT", "生成ページ数が上限を超えました。");
            double next = Math.min(end, current + pageHeight);
            if (next < end) {
                int row = geometry.rowAt(next);
                double rowBoundary = geometry.y(row);
                if (rowBoundary > current + .00001) next = rowBoundary;
                for (CellRangeAddress merge : sheet.getMergedRegions()) {
                    if (merge.getLastColumn() < cells.getFirstColumn() || merge.getFirstColumn() > cells.getLastColumn()) continue;
                    double top = geometry.y(merge.getFirstRow()), bottom = geometry.y(merge.getLastRow() + 1);
                    if (top < next && bottom > next && top > current + .00001) next = top;
                }
                if (next <= current + .00001) next = Math.min(end, current + pageHeight);
                for (CellRangeAddress merge : sheet.getMergedRegions()) {
                    if (geometry.y(merge.getFirstRow()) < next && geometry.y(merge.getLastRow() + 1) > next)
                        workspace.warning("MERGE_SPLIT_ACROSS_PAGES", sheet.getSheetName(), "ページより高い結合セルは縦方向の連続ページに分割しました。");
                }
                if (geometry.y(geometry.rowAt(next)) < next - .00001)
                    workspace.warning("ROW_SPLIT_ACROSS_PAGES", sheet.getSheetName(), "ページより高い行は縦方向の連続ページに分割しました。");
            }
            pages.add(new Page(current, next - current, Math.max(cells.getFirstRow(), geometry.rowAt(current)),
                    Math.min(cells.getLastRow(), geometry.rowAt(Math.nextDown(next)))));
            current = next;
        }
        return new Plan(cells, new Rectangle2D.Double(x, y, width, height), scale, pages, null, null);
    }
    private static Plan skip(CellRangeAddress cells, String code, String message) {
        return new Plan(cells, null, 0, List.of(), code, message);
    }
    static int positiveCeil(double value) {
        if (!Double.isFinite(value) || value > Integer.MAX_VALUE) throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "画像寸法が上限を超えました。");
        return Math.max(1, (int) Math.ceil(value - 1e-8));
    }
}
