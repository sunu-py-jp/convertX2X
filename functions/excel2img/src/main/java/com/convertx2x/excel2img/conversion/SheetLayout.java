package com.convertx2x.excel2img.conversion;

import com.convertx2x.excel2img.drawing.SheetGeometry;
import java.util.Arrays;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/** Lazy cumulative dimensions in points. Hidden cells take no physical space. */
final class SheetLayout implements SheetGeometry {
    private final Sheet sheet;
    private final int maxRows;
    private final int maxColumns;
    private double[] columns = new double[64];
    private double[] rows = new double[256];
    private int columnsComputed;
    private int rowsComputed;
    SheetLayout(Sheet sheet) {
        this.sheet = sheet;
        maxRows = sheet.getWorkbook().getSpreadsheetVersion().getMaxRows();
        maxColumns = sheet.getWorkbook().getSpreadsheetVersion().getMaxColumns();
    }
    @Override public double x(int column) {
        if (column < 0 || column > maxColumns) throw new IllegalArgumentException("Invalid column boundary");
        if (column >= columns.length) columns = Arrays.copyOf(columns, capacity(columns.length, column, maxColumns));
        while (columnsComputed < column) {
            int c = columnsComputed++;
            columns[c + 1] = columns[c] + (sheet.isColumnHidden(c) ? 0 : sheet.getColumnWidthInPixels(c) * .75);
        }
        return columns[column];
    }
    @Override public double y(int row) {
        if (row < 0 || row > maxRows) throw new IllegalArgumentException("Invalid row boundary");
        if (row >= rows.length) rows = Arrays.copyOf(rows, capacity(rows.length, row, maxRows));
        while (rowsComputed < row) {
            int r = rowsComputed++;
            Row source = sheet.getRow(r);
            rows[r + 1] = rows[r] + (source == null ? sheet.getDefaultRowHeightInPoints()
                    : source.getZeroHeight() ? 0 : Math.max(0, source.getHeightInPoints()));
        }
        return rows[row];
    }
    int columnAt(double point) { return locate(point, false); }
    int rowAt(double point) { return locate(point, true); }
    private int locate(double point, boolean vertical) {
        int max = vertical ? maxRows : maxColumns;
        int hi = 1;
        while (hi < max && boundary(hi, vertical) <= point) hi = Math.min(max, hi * 2);
        int lo = 0;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (boundary(mid, vertical) <= point) lo = mid; else hi = mid;
        }
        return Math.min(max - 1, lo);
    }
    private double boundary(int index, boolean vertical) { return vertical ? y(index) : x(index); }
    private static int capacity(int previous, int requested, int max) {
        return (int) Math.min(max + 1L, Math.max(requested + 1L, previous * 2L));
    }
}
