package com.convertx2x.excel2img.conversion;

import com.convertx2x.excel2img.drawing.ExcelDrawingLayer;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;

/** Selects rectangles and tiles them in reading order without shrinking text below the permitted scale. */
final class RangePlanner {
    record Selection(CellRangeAddress cells, String source) {}
    record Page(double x, double y, double width, double height,
                int firstRow, int lastRow, int firstColumn, int lastColumn) {
        Rectangle2D.Double bounds() { return new Rectangle2D.Double(x, y, width, height); }
    }
    private record Slice(double start, double end, int first, int last) {}
    private record Span(double start, double end) {}
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
        double baseScale = policy.pixelsPerPoint();
        double fitScale = Math.min(baseScale, policy.maxWidthPixels() / width);
        double minimumFont = Double.POSITIVE_INFINITY;
        for (Row row : sheet) {
            if (row.getRowNum() < cells.getFirstRow() || row.getRowNum() > cells.getLastRow() || row.getZeroHeight()) continue;
            for (Cell cell : row) {
                if (cell.getColumnIndex() < cells.getFirstColumn() || cell.getColumnIndex() > cells.getLastColumn() || sheet.isColumnHidden(cell.getColumnIndex())) continue;
                if (!values.text(cell).isEmpty()) minimumFont = Math.min(minimumFont,
                        sheet.getWorkbook().getFontAt(cell.getCellStyle().getFontIndex()).getFontHeightInPoints());
            }
        }
        // Image text cannot be measured here. Limit image-only shrinking to 50% of the configured resolution.
        double readableScale = Double.isFinite(minimumFont)
                ? policy.minTextPixels() / Math.max(1, minimumFont) : baseScale / 2;
        double scale = Math.min(baseScale, Math.max(fitScale, readableScale));
        if (Double.isFinite(minimumFont) && minimumFont * baseScale + 1e-6 < policy.minTextPixels()) {
            scale = baseScale;
            workspace.warning("TEXT_BELOW_MINIMUM", sheet.getSheetName(),
                    "元の描画解像度でも最小文字サイズを下回る文字があります。拡大や追加の縮小をせず、設定された解像度で描画しました。");
        }
        if (scale < baseScale - .00001) workspace.warning("RANGE_SCALED", sheet.getSheetName(),
                "文字サイズまたは画像の縮小下限を保つ倍率で縮小しました。横幅を超える部分はページに分割します。");

        List<CellRangeAddress> merges = sheet.getMergedRegions().stream().filter(merge -> merge.intersects(cells)).toList();
        List<Slice> columns = slices(x, x + width, cells.getFirstColumn(), cells.getLastColumn(),
                policy.maxWidthPixels(), scale, false, geometry, merges, limits, workspace, sheet);
        List<Slice> rows = slices(y, y + height, cells.getFirstRow(), cells.getLastRow(),
                policy.maxHeightPixels(), scale, true, geometry, merges, limits, workspace, sheet);
        long pageCount = (long) columns.size() * rows.size();
        checkPageCount(pageCount, limits, workspace);
        if (columns.size() > 1) workspace.warning("HORIZONTAL_PAGINATION", sheet.getSheetName(),
                "文字や画像の縮小を抑え、列の境目を優先して横方向にも分割しました。ページ順は上段の左から右、その次の段の左から右です。");
        if (pageCount > Integer.MAX_VALUE)
            throw ConversionWorkspace.limit("PAGE_LIMIT", "扱える生成ページ数の上限を超えました。");
        List<Page> pages = new ArrayList<>((int) pageCount);
        for (Slice row : rows) {
            for (Slice column : columns) {
                double pageWidth = column.end - column.start, pageHeight = row.end - row.start;
                int pixelWidth = positiveCeil(pageWidth * scale), pixelHeight = positiveCeil(pageHeight * scale);
                if (pixelWidth > policy.maxWidthPixels() || pixelHeight > policy.maxHeightPixels() ||
                        (long) pixelWidth * pixelHeight > limits.maxImagePixels())
                    throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "生成画像の寸法または画素数が上限を超えました。");
                pages.add(new Page(column.start, row.start, pageWidth, pageHeight,
                        row.first, row.last, column.first, column.last));
            }
        }
        return new Plan(cells, new Rectangle2D.Double(x, y, width, height), scale, List.copyOf(pages), null, null);
    }

    /** Both axes are continuous. Oversized cells use adjacent clipped windows rather than losing their remainder. */
    private static List<Slice> slices(double start, double end, int first, int last, int maxPixels,
                                      double scale, boolean vertical, SheetLayout geometry,
                                      List<CellRangeAddress> merges, ConversionLimits limits,
                                      ConversionWorkspace workspace, Sheet sheet) {
        double capacity = maxPixels / scale;
        List<Span> allMergedSpans = mergeSpans(merges, geometry, vertical, start, end, Double.POSITIVE_INFINITY);
        List<Span> protectedSpans = mergeSpans(merges, geometry, vertical, start, end, capacity);
        List<Slice> result = new ArrayList<>();
        double current = start;
        while (current < end) {
            checkPageCount((long) result.size() + 1, limits, workspace);
            double next = (end - current) * scale <= maxPixels + 1e-8 ? end : Math.min(end, current + capacity);
            if (!(next > current))
                throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "指定された描画解像度ではページ境界を安全に計算できません。");
            // Rounding a very large sheet coordinate must not create an image one pixel beyond the limit.
            while ((next - current) * scale > maxPixels + 1e-8) {
                next = Math.nextDown(next);
                if (!(next > current))
                    throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "指定された描画解像度ではページ境界を安全に計算できません。");
            }
            if (next < end) {
                int index = indexAt(geometry, next, vertical);
                double natural = boundary(geometry, index, vertical);
                if (natural > current) next = natural;
                Span crossing = crossing(protectedSpans, next);
                // Overlapping merge projections form a protected block. If it cannot fit, no common
                // boundary can preserve all those merges; split continuously and report the approximation.
                if (crossing != null && crossing.end - crossing.start <= capacity && crossing.start > current)
                    next = crossing.start;
                if (crossing(allMergedSpans, next) != null)
                    workspace.warning("MERGE_SPLIT_ACROSS_PAGES", sheet.getSheetName(),
                            "ページ境界をまたぐ結合セルは、シート座標を保った連続ページに分割しました。");
                if (boundary(geometry, indexAt(geometry, next, vertical), vertical) < next) {
                    workspace.warning(vertical ? "ROW_SPLIT_ACROSS_PAGES" : "COLUMN_SPLIT_ACROSS_PAGES", sheet.getSheetName(),
                            vertical ? "ページより高い行は縦方向の連続ページに分割しました。"
                                    : "ページより広い列は横方向の連続ページに分割しました。");
                }
            }
            result.add(new Slice(current, next, Math.max(first, indexAt(geometry, current, vertical)),
                    Math.min(last, indexAt(geometry, Math.nextDown(next), vertical))));
            current = next;
        }
        return result;
    }

    /** Disjoint projected merge blocks let pagination avoid repeated scans or cyclic boundary adjustment. */
    private static List<Span> mergeSpans(List<CellRangeAddress> merges, SheetLayout geometry, boolean vertical,
                                        double selectedStart, double selectedEnd, double capacity) {
        List<Span> spans = new ArrayList<>();
        for (CellRangeAddress merge : merges) {
            double start = boundary(geometry, vertical ? merge.getFirstRow() : merge.getFirstColumn(), vertical);
            double end = boundary(geometry, (vertical ? merge.getLastRow() : merge.getLastColumn()) + 1, vertical);
            start = Math.max(selectedStart, start); end = Math.min(selectedEnd, end);
            // A title wider than one page must not prevent preserving smaller merged headers below it.
            if (end > start && end - start <= capacity) spans.add(new Span(start, end));
        }
        spans.sort(Comparator.comparingDouble(Span::start));
        List<Span> blocks = new ArrayList<>();
        for (Span span : spans) {
            if (blocks.isEmpty() || blocks.getLast().end <= span.start) blocks.add(span);
            else {
                Span previous = blocks.removeLast();
                blocks.add(new Span(previous.start, Math.max(previous.end, span.end)));
            }
        }
        return blocks;
    }
    private static Span crossing(List<Span> spans, double position) {
        int lo = 0, hi = spans.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid).start < position) lo = mid + 1; else hi = mid;
        }
        if (lo == 0) return null;
        Span span = spans.get(lo - 1);
        return span.end > position ? span : null;
    }
    private static int indexAt(SheetLayout geometry, double point, boolean vertical) {
        return vertical ? geometry.rowAt(point) : geometry.columnAt(point);
    }
    private static double boundary(SheetLayout geometry, int index, boolean vertical) {
        return vertical ? geometry.y(index) : geometry.x(index);
    }
    private static void checkPageCount(long count, ConversionLimits limits, ConversionWorkspace workspace) {
        if (ConversionLimits.exceeds(count + workspace.pageCount(), limits.maxPages()))
            throw ConversionWorkspace.limit("PAGE_LIMIT", "生成ページ数が上限を超えました。");
    }
    private static Plan skip(CellRangeAddress cells, String code, String message) {
        return new Plan(cells, null, 0, List.of(), code, message);
    }
    static int positiveCeil(double value) {
        if (!Double.isFinite(value) || value > Integer.MAX_VALUE) throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "画像寸法が上限を超えました。");
        return Math.max(1, (int) Math.ceil(value - 1e-8));
    }
}
