package com.convertx2x.excel2md.conversion;

import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Conservative closed-grid detection. Border color/style do not affect connectivity. */
final class BorderTables {
    private final Set<Long> horizontal = new HashSet<>(), vertical = new HashSet<>();
    private final Set<Long> candidates = new HashSet<>();
    private final Sheet sheet;
    private final MergedRanges merges;
    private final ConversionWorkspace workspace;
    BorderTables(Sheet sheet, MergedRanges merges, ConversionWorkspace workspace) {
        this.sheet = sheet; this.merges = merges; this.workspace = workspace;
    }
    static long key(int row, int col) { return ((long) row << 32) | (col & 0xffffffffL); }
    static int row(long key) { return (int) (key >>> 32); }
    static int col(long key) { return (int) key; }

    List<CellRangeAddress> detect() {
        for (Row row : sheet) for (Cell cell : row) {
            int r = cell.getRowIndex(), c = cell.getColumnIndex();
            CellStyle style = cell.getCellStyle();
            if (style.getBorderTop() != BorderStyle.NONE) horizontal(r, c);
            if (style.getBorderBottom() != BorderStyle.NONE) horizontal(r + 1, c);
            if (style.getBorderLeft() != BorderStyle.NONE) vertical(r, c);
            if (style.getBorderRight() != BorderStyle.NONE) vertical(r, c + 1);
        }
        Map<Long, Integer> occupied = new HashMap<>();
        List<CellRangeAddress> units = new ArrayList<>();
        Set<Long> examined = new HashSet<>();
        long expanded = 0;
        for (long point : candidates.stream().sorted().toList()) {
            int row = row(point), column = col(point);
            if (row < 0 || column < 0) continue;
            CellRangeAddress merge = merges.at(row, column);
            CellRangeAddress unit = merge != null ? merge : new CellRangeAddress(row, row, column, column);
            long origin = key(unit.getFirstRow(), unit.getFirstColumn());
            if (!examined.add(origin)) continue;
            long area = area(unit);
            if (area > workspace.limits().maxTableCells()) continue;
            if (!closed(unit)) continue;
            if (expanded + area > workspace.limits().maxTableCells())
                throw ConversionWorkspace.limit("TABLE_CELLS_LIMIT", "罫線表の展開セル数が上限を超えました。");
            expanded += area;
            int index = units.size(); units.add(unit);
            for (int r = unit.getFirstRow(); r <= unit.getLastRow(); r++)
                for (int c = unit.getFirstColumn(); c <= unit.getLastColumn(); c++) occupied.put(key(r, c), index);
        }
        int[] parent = new int[units.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (var entry : occupied.entrySet()) {
            int r = row(entry.getKey()), c = col(entry.getKey());
            Integer right = occupied.get(key(r, c + 1)), below = occupied.get(key(r + 1, c));
            if (right != null) union(parent, entry.getValue(), right);
            if (below != null) union(parent, entry.getValue(), below);
        }
        Map<Integer, List<CellRangeAddress>> groups = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) groups.computeIfAbsent(root(parent, i), ignored -> new ArrayList<>()).add(units.get(i));
        List<CellRangeAddress> result = new ArrayList<>();
        boolean rejected = false;
        for (List<CellRangeAddress> group : groups.values()) {
            if (group.size() < 2) continue;
            CellRangeAddress bounds = bounds(group);
            long cells = group.stream().mapToLong(BorderTables::area).sum();
            if (area(bounds) != cells || !closed(bounds) || protruding(bounds)) { rejected = true; continue; }
            result.add(bounds);
        }
        result.sort(Comparator.comparingInt(CellRangeAddress::getFirstRow).thenComparingInt(CellRangeAddress::getFirstColumn));
        if ((!candidates.isEmpty() && result.isEmpty()) || rejected)
            workspace.warning("BORDER_NOT_TABLE", sheet.getSheetName(), borderRange(),
                    "囲み枠または不完全な罫線は表として確定できないため、本文として保持します。");
        return result;
    }
    private void horizontal(int r, int c) {
        horizontal.add(key(r, c)); candidates.add(key(r, c));
        if (r > 0) candidates.add(key(r - 1, c));
    }
    private void vertical(int r, int c) {
        vertical.add(key(r, c)); candidates.add(key(r, c));
        if (c > 0) candidates.add(key(r, c - 1));
    }
    private boolean closed(CellRangeAddress range) {
        for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++)
            if (!horizontal.contains(key(range.getFirstRow(), c)) || !horizontal.contains(key(range.getLastRow() + 1, c))) return false;
        for (int r = range.getFirstRow(); r <= range.getLastRow(); r++)
            if (!vertical.contains(key(r, range.getFirstColumn())) || !vertical.contains(key(r, range.getLastColumn() + 1))) return false;
        return true;
    }
    /** A partial grid attached to this rectangle makes its extent ambiguous. */
    private boolean protruding(CellRangeAddress range) {
        for (int c = range.getFirstColumn(); c <= range.getLastColumn() + 1; c++) {
            if (range.getFirstRow() > 0 && vertical.contains(key(range.getFirstRow() - 1, c))) return true;
            if (vertical.contains(key(range.getLastRow() + 1, c))) return true;
        }
        for (int r = range.getFirstRow(); r <= range.getLastRow() + 1; r++) {
            if (range.getFirstColumn() > 0 && horizontal.contains(key(r, range.getFirstColumn() - 1))) return true;
            if (horizontal.contains(key(r, range.getLastColumn() + 1))) return true;
        }
        return false;
    }
    private String borderRange() {
        int r1 = Integer.MAX_VALUE, r2 = 0, c1 = Integer.MAX_VALUE, c2 = 0;
        for (long point : candidates) {
            r1 = Math.min(r1, row(point)); r2 = Math.max(r2, row(point));
            c1 = Math.min(c1, col(point)); c2 = Math.max(c2, col(point));
        }
        return candidates.isEmpty() ? null : new CellRangeAddress(r1, r2, c1, c2).formatAsString();
    }
    private static CellRangeAddress bounds(List<CellRangeAddress> group) {
        return new CellRangeAddress(group.stream().mapToInt(CellRangeAddress::getFirstRow).min().orElseThrow(),
                group.stream().mapToInt(CellRangeAddress::getLastRow).max().orElseThrow(),
                group.stream().mapToInt(CellRangeAddress::getFirstColumn).min().orElseThrow(),
                group.stream().mapToInt(CellRangeAddress::getLastColumn).max().orElseThrow());
    }
    static long area(CellRangeAddress range) {
        return (range.getLastRow() - (long) range.getFirstRow() + 1) * (range.getLastColumn() - (long) range.getFirstColumn() + 1);
    }
    private static int root(int[] parents, int x) {
        while (parents[x] != x) { parents[x] = parents[parents[x]]; x = parents[x]; }
        return x;
    }
    private static void union(int[] parents, int x, int y) { parents[root(parents, x)] = root(parents, y); }
}
