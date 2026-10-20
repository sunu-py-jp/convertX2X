package com.convertx2x.office2md.word;

import static com.convertx2x.office2md.word.WordXml.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Node;

/**
 * Saved geometry of a floating, fixed-layout table. No Word pagination or auto-fit is
 * inferred: missing coordinates, automatic row heights and saved page breaks reject it.
 */
final class WordTableLayout {
    record Layout(double x, double y, String reference, List<Cell> cells, Node properties) { }
    static final class Cell {
        final Node source;
        final double x, y, width;
        double height;
        Cell(Node source, double x, double y, double width, double height) {
            this.source = source; this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    private WordTableLayout() { }

    static Layout read(Node table) {
        Node properties = child(table, "tblPr"), position = child(properties, "tblpPr");
        String horizontal = attr(position, "horzAnchor"), vertical = attr(position, "vertAnchor");
        if (!List.of("page", "margin").contains(horizontal) || !List.of("page", "margin").contains(vertical)
                || !attr(position, "tblpXSpec").isEmpty() || !attr(position, "tblpYSpec").isEmpty()
                || !"fixed".equals(attr(child(properties, "tblLayout"), "type"))
                || "never".equals(val(child(properties, "tblOverlap"))) || hasPageBreak(table)) return null;
        Double x = points(attr(position, "tblpX")), y = points(attr(position, "tblpY"));
        if (x == null || y == null) return null;
        List<Double> columns = new ArrayList<>();
        columns.add(0d);
        for (Node column : children(child(table, "tblGrid"))) {
            if (!is(column, "gridCol")) continue;
            Double width = points(attr(column, "w"));
            if (width == null || width <= 0 || width + columns.getLast() > 100_000_000) return null;
            columns.add(columns.getLast() + width);
        }
        if (columns.size() < 2) return null;
        List<Node> rows = new ArrayList<>();
        collect(table, "tr", rows);
        List<Cell> cells = new ArrayList<>();
        Map<Integer, Merged> verticalMerges = new HashMap<>();
        double rowY = 0;
        for (Node row : rows) {
            Node rowProperties = child(row, "trPr");
            if (on(child(rowProperties, "del"))) continue;
            Node heightNode = child(rowProperties, "trHeight");
            Double height = points(val(heightNode));
            if (height == null || height <= 0 || !"exact".equals(attr(heightNode, "hRule"))
                    || rowY + height > 100_000_000) return null;
            int column = integer(val(child(rowProperties, "gridBefore")), 0);
            if (column < 0 || column >= columns.size()) return null;
            List<Node> sourceCells = new ArrayList<>();
            collect(row, "tc", sourceCells);
            Map<Integer, Merged> nextMerges = new HashMap<>();
            for (Node source : sourceCells) {
                Node cellProperties = child(source, "tcPr");
                if (child(cellProperties, "cellDel") != null) continue;
                // Legacy horizontal merge has no reliable gridSpan for every producer.
                if (child(cellProperties, "hMerge") != null) return null;
                int span = integer(val(child(cellProperties, "gridSpan")), 1);
                if (span < 1 || column + (long) span >= columns.size()) return null;
                Node merge = child(cellProperties, "vMerge");
                if (merge != null && !"restart".equals(val(merge))) {
                    Merged previous = verticalMerges.get(column);
                    if (previous == null || previous.span != span) return null;
                    previous.cell.height += height;
                    nextMerges.put(column, previous);
                } else {
                    Cell cell = new Cell(source, columns.get(column), rowY,
                            columns.get(column + span) - columns.get(column), height);
                    cells.add(cell);
                    if (merge != null) nextMerges.put(column, new Merged(cell, span));
                }
                column += span;
            }
            int after = integer(val(child(rowProperties, "gridAfter")), 0);
            if (after < 0 || column + (long) after != columns.size() - 1) return null;
            verticalMerges = nextMerges;
            rowY += height;
        }
        if (cells.isEmpty()) return null;
        return new Layout(x, y, horizontal + "/" + vertical, cells, properties);
    }

    private record Merged(Cell cell, int span) { }
    private static Double points(String raw) {
        try {
            double value = Double.parseDouble(raw) / 20;
            return Double.isFinite(value) && Math.abs(value) <= 100_000_000 ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }
    private static void collect(Node node, String target, List<Node> result) {
        for (Node value : children(node)) {
            if (omitted(value)) continue;
            if (is(value, target)) result.add(value);
            else if (is(value, "ins") || is(value, "moveTo") || is(value, "sdt") || is(value, "sdtContent"))
                collect(value, target, result);
        }
    }
    private static boolean hasPageBreak(Node node) {
        if (omitted(node)) return false;
        if (is(node, "lastRenderedPageBreak") || is(node, "br") && "page".equals(attr(node, "type"))
                || is(node, "pageBreakBefore") && on(node)) return true;
        for (Node value : children(node)) if (hasPageBreak(value)) return true;
        return false;
    }
}
