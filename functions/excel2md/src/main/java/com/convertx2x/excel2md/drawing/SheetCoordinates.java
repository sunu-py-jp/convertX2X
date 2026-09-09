package com.convertx2x.excel2md.drawing;

import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;

/** Converts the sparse worksheet coordinate system to points without walking empty rows. */
final class SheetCoordinates {
    private static final double EMU_PER_POINT = 12700;
    private final Sheet sheet;
    private final double[] columns;
    private final int[] changedRows;
    private final double[] rowDeltas;
    private final double defaultHeight;
    private final int maxRows;

    SheetCoordinates(Sheet sheet) {
        this.sheet = sheet;
        int maxColumns = sheet.getWorkbook().getSpreadsheetVersion().getMaxColumns();
        maxRows = sheet.getWorkbook().getSpreadsheetVersion().getMaxRows();
        columns = new double[maxColumns + 1];
        for (int i = 0; i < maxColumns; i++)
            columns[i + 1] = columns[i] + sheet.getColumnWidthInPixels(i) * 0.75;
        defaultHeight = Math.max(1, sheet.getDefaultRowHeightInPoints());
        changedRows = new int[sheet.getPhysicalNumberOfRows()];
        rowDeltas = new double[changedRows.length + 1];
        int index = 0;
        for (Row row : sheet) {
            changedRows[index] = row.getRowNum();
            rowDeltas[index + 1] = rowDeltas[index] + row.getHeightInPoints() - defaultHeight;
            index++;
        }
    }

    double x(int column) { return columns[Math.max(0, Math.min(column, columns.length - 1))]; }
    double y(int row) {
        row = Math.max(0, Math.min(row, maxRows));
        int index = Arrays.binarySearch(changedRows, row);
        if (index < 0) index = -index - 1;
        return row * defaultHeight + rowDeltas[index];
    }

    Rectangle2D anchor(ClientAnchor anchor) {
        if (anchor instanceof XSSFClientAnchor xssf) {
            // Avoid POI's synthesized getRow2()/getCol2() scans for absolute/one-cell anchors.
            if (xssf.getPosition() != null && xssf.getSize() != null) {
                var position = xssf.getPosition();
                var size = xssf.getSize();
                return rectangle(number(position.getX()) / EMU_PER_POINT,
                        number(position.getY()) / EMU_PER_POINT,
                        size.getCx() / EMU_PER_POINT, size.getCy() / EMU_PER_POINT);
            }
            if (xssf.getFrom() != null && xssf.getSize() != null) {
                var from = xssf.getFrom();
                return rectangle(x(from.getCol()) + number(from.getColOff()) / EMU_PER_POINT,
                        y(from.getRow()) + number(from.getRowOff()) / EMU_PER_POINT,
                        xssf.getSize().getCx() / EMU_PER_POINT, xssf.getSize().getCy() / EMU_PER_POINT);
            }
        }
        int c1 = anchor.getCol1(), c2 = anchor.getCol2();
        int r1 = anchor.getRow1(), r2 = anchor.getRow2();
        boolean xssf = anchor instanceof XSSFClientAnchor;
        double x1 = x(c1) + (xssf ? anchor.getDx1() / EMU_PER_POINT : (x(c1 + 1) - x(c1)) * anchor.getDx1() / 1024d);
        double x2 = x(c2) + (xssf ? anchor.getDx2() / EMU_PER_POINT : (x(c2 + 1) - x(c2)) * anchor.getDx2() / 1024d);
        double y1 = y(r1) + (xssf ? anchor.getDy1() / EMU_PER_POINT : (y(r1 + 1) - y(r1)) * anchor.getDy1() / 256d);
        double y2 = y(r2) + (xssf ? anchor.getDy2() / EMU_PER_POINT : (y(r2 + 1) - y(r2)) * anchor.getDy2() / 256d);
        return rectangle(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    int row(double point) {
        int low = 0, high = maxRows - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (y(middle) <= point) low = middle; else high = middle - 1;
        }
        return low;
    }

    int column(double point) {
        int found = Arrays.binarySearch(columns, Math.max(0, point));
        return Math.min(columns.length - 2, found >= 0 ? found : Math.max(0, -found - 2));
    }

    boolean hidden(Rectangle2D bounds) {
        int row = row(bounds.getY()), column = column(bounds.getX());
        Row existing = sheet.getRow(row);
        return sheet.isColumnHidden(column) || existing != null && existing.getZeroHeight();
    }

    static Rectangle2D rectangle(double x, double y, double width, double height) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                || width < 0 || height < 0) throw new IllegalArgumentException("Invalid drawing bounds");
        return new Rectangle2D.Double(x, y, width, height);
    }
    private static double number(Object value) { return Double.parseDouble(value.toString()); }
}
