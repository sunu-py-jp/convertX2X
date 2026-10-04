package com.convertx2x.office2md.conversion;

import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Grid seeds for horizontal table expansion. Border color/style do not affect connectivity. */
final class BorderTables {
    private final Set<Long> horizontal = new HashSet<>(), vertical = new HashSet<>();
    private final Set<Long> candidates = new HashSet<>();
    private final Set<Long> openTop = new HashSet<>();
    private final Sheet sheet;
    private final MergedRanges merges;
    private final ConversionWorkspace workspace;
    BorderTables(Sheet sheet, MergedRanges merges, ConversionWorkspace workspace) {
        this.sheet = sheet; this.merges = merges; this.workspace = workspace;
    }
    static long key(int row, int col) { return ((long) row << 32) | (col & 0xffffffffL); }
    static int row(long key) { return (int) (key >>> 32); }
    static int col(long key) { return (int) key; }
    boolean hasHorizontal(int row, int column) { return horizontal.contains(key(row, column)); }
    boolean hasVertical(int row, int column) { return vertical.contains(key(row, column)); }
    Set<Long> openTopCells() { return Set.copyOf(openTop); }

    List<CellRangeAddress> detect() {
        horizontal.clear();
        vertical.clear();
        candidates.clear();
        openTop.clear();
        for (Row row : sheet) for (Cell cell : row) {
            int r = cell.getRowIndex(), c = cell.getColumnIndex();
            CellStyle style = cell.getCellStyle();
            if (style.getBorderTop() != BorderStyle.NONE) horizontal(r, c);
            if (style.getBorderBottom() != BorderStyle.NONE) horizontal(r + 1, c);
            if (style.getBorderLeft() != BorderStyle.NONE) vertical(r, c);
            if (style.getBorderRight() != BorderStyle.NONE) vertical(r, c + 1);
        }
        for (CellRangeAddress merge : merges.all()) {
            if (ConversionLimits.exceeds(area(merge), workspace.limits().maxTableCells())) continue;
            Row row = sheet.getRow(merge.getFirstRow());
            Cell anchor = row == null ? null : row.getCell(merge.getFirstColumn());
            if (anchor == null) continue;
            CellStyle style = anchor.getCellStyle();
            // Excel can draw a merged cell's perimeter from its anchor style even
            // when covered cells do not store their own border segments.
            for (int column = merge.getFirstColumn(); column <= merge.getLastColumn(); column++) {
                if (style.getBorderTop() != BorderStyle.NONE) horizontal(merge.getFirstRow(), column);
                if (style.getBorderBottom() != BorderStyle.NONE) horizontal(merge.getLastRow() + 1, column);
            }
            for (int coveredRow = merge.getFirstRow(); coveredRow <= merge.getLastRow(); coveredRow++) {
                if (style.getBorderLeft() != BorderStyle.NONE) vertical(coveredRow, merge.getFirstColumn());
                if (style.getBorderRight() != BorderStyle.NONE) vertical(coveredRow, merge.getLastColumn() + 1);
            }
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
            if (ConversionLimits.exceeds(area, workspace.limits().maxTableCells())) continue;
            if (!closed(unit)) continue;
            workspace.limits().checkTableCells(expanded + area);
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
        ClosedGrid grid = new ClosedGrid(units, occupied, parent);
        List<CellRangeAddress> result = new ArrayList<>();
        boolean rejected = false;
        for (var component : groups.entrySet()) {
            int componentId = component.getKey();
            List<CellRangeAddress> group = component.getValue();
            if (group.size() < 2) continue;
            CellRangeAddress bounds = bounds(group);
            long cells = group.stream().mapToLong(BorderTables::area).sum();
            if (area(bounds) == cells && closed(bounds) && !protrudingVertically(bounds, grid, componentId)) result.add(bounds);
            else if (openTopRowGap(bounds, grid, componentId) && !protrudingVertically(bounds, grid, componentId)) {
                // A matrix may leave its top corner unbordered, even when it starts
                // after ordinary columns in the same table. The closed, horizontally
                // divided lower grid fixes its position without guessing from text.
                workspace.limits().checkTableCells(area(bounds));
                result.add(bounds);
                for (int column = bounds.getFirstColumn(); column <= bounds.getLastColumn(); column++) {
                    int top = bounds.getFirstRow();
                    long coordinate = key(top, column);
                    if (occupied.containsKey(coordinate)) continue;
                    openTop.add(coordinate);
                    Row first = sheet.getRow(top);
                    Cell cell = first == null ? null : first.getCell(column);
                    boolean note = cell != null && cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank();
                    workspace.info(note ? "TABLE_OPEN_TOP_NOTE" : "TABLE_OPEN_TOP_CELL", sheet.getSheetName(),
                            new CellRangeAddress(top, top, column, column).formatAsString(),
                            note ? "罫線のない上段の文字列を表外の注記として保持しました。"
                                    : "罫線のない上段の空欄を、外周が閉じ横に分割された下段に基づいて表へ含めました。");
                }
            }
            else {
                // A closed outer rectangle may contain a regular full-height grid beside a
                // visually merged span. Never recover a seed from a generally open outer border.
                if (closed(bounds) && !protrudingVertically(bounds, grid, componentId)) result.addAll(fullHeightSeeds(group, bounds));
                rejected = true;
            }
        }
        result.sort(Comparator.comparingInt(CellRangeAddress::getFirstRow).thenComparingInt(CellRangeAddress::getFirstColumn));
        if ((!candidates.isEmpty() && result.isEmpty()) || rejected)
            workspace.warning("BORDER_NOT_TABLE", sheet.getSheetName(), borderRange(),
                    "罫線だけでは表として確定できない部分があります。検出した表の左右の値は表に取り込み、それ以外は本文として保持します。");
        return result;
    }
    private List<CellRangeAddress> fullHeightSeeds(List<CellRangeAddress> units, CellRangeAddress bounds) {
        Map<Integer, Long> coverage = new TreeMap<>();
        for (CellRangeAddress unit : units)
            for (int column = unit.getFirstColumn(); column <= unit.getLastColumn(); column++)
                coverage.merge(column, unit.getLastRow() - (long) unit.getFirstRow() + 1, Long::sum);
        long height = bounds.getLastRow() - (long) bounds.getFirstRow() + 1;
        List<CellRangeAddress> seeds = new ArrayList<>();
        int first = -1, last = -1;
        for (var entry : coverage.entrySet()) {
            int column = entry.getKey();
            if (entry.getValue() != height || (last >= 0 && column != last + 1)) {
                addSeed(seeds, units, bounds, first, last);
                first = last = -1;
            }
            if (entry.getValue() == height) {
                if (first < 0) first = column;
                last = column;
            }
        }
        addSeed(seeds, units, bounds, first, last);
        return seeds;
    }
    private void addSeed(List<CellRangeAddress> seeds, List<CellRangeAddress> units, CellRangeAddress bounds, int first, int last) {
        if (first < 0) return;
        CellRangeAddress seed = new CellRangeAddress(bounds.getFirstRow(), bounds.getLastRow(), first, last);
        long count = 0;
        for (CellRangeAddress unit : units) if (seed.intersects(unit)) {
            if (unit.getFirstColumn() < first || unit.getLastColumn() > last) return;
            count++;
        }
        if (count >= 2 && closed(seed)) seeds.add(seed);
    }
    /** Accept unbordered text or blank top-row runs only when a closed, divided lower grid anchors them. */
    private boolean openTopRowGap(CellRangeAddress bounds, ClosedGrid grid, int componentId) {
        Map<Long, Integer> occupied = grid.occupied();
        int top = bounds.getFirstRow(), bottom = bounds.getLastRow();
        int left = bounds.getFirstColumn(), right = bounds.getLastColumn();
        if (top == bottom || left == right || !closed(new CellRangeAddress(top + 1, bottom, left, right))) return false;
        boolean lowerHasColumnDivision = false;
        for (int row = top + 1; row <= bottom; row++) {
            for (int column = left; column <= right; column++)
                if (!occupied.containsKey(key(row, column))) return false;
            if (!occupied.get(key(row, left)).equals(occupied.get(key(row, right))))
                lowerHasColumnDivision = true;
        }
        if (!lowerHasColumnDivision) return false;
        boolean lowerHasRowDivision = false;
        for (int row = top + 1; row < bottom && !lowerHasRowDivision; row++)
            for (int column = left; column <= right; column++)
                if (!occupied.get(key(row, column)).equals(occupied.get(key(row + 1, column)))) {
                    lowerHasRowDivision = true;
                    break;
                }
        if (!lowerHasRowDivision) return false;
        boolean missing = false, covered = false, openRun = false;
        Row first = sheet.getRow(top);
        for (int column = left; column <= right; column++) {
            if (occupied.containsKey(key(top, column))) {
                covered = true;
                openRun = false;
                continue;
            }
            missing = openRun = true;
            Cell cell = first == null ? null : first.getCell(column);
            // The bottom of a separate closed cell directly above is not this
            // borderless corner's top edge. Its own cell style must still be blank.
            if ((horizontal.contains(key(top, column)) && !grid.foreignBottomEdge(top, column, componentId))
                    || merges.at(top, column) != null
                    || !unborderedTextOrBlank(cell)) return false;
        }
        // A trailing gap is ambiguous unless the top row contains an explicit,
        // nonblank merged group caption. Its width comes from the workbook; the
        // lower closed grid anchors any unbordered columns outside that caption.
        return missing && covered && (!openRun || hasTopGroupCaption(bounds));
    }
    private boolean hasTopGroupCaption(CellRangeAddress bounds) {
        int top = bounds.getFirstRow();
        for (CellRangeAddress merge : merges.all()) {
            if (merge.getFirstRow() != top || merge.getLastRow() != top
                    || merge.getFirstColumn() < bounds.getFirstColumn()
                    || merge.getLastColumn() > bounds.getLastColumn()
                    || merge.getFirstColumn() == merge.getLastColumn()
                    || !closed(merge)) continue;
            Row row = sheet.getRow(top);
            Cell caption = row == null ? null : row.getCell(merge.getFirstColumn());
            if (caption != null && caption.getCellType() == CellType.STRING
                    && !caption.getStringCellValue().isBlank()) return true;
        }
        return false;
    }
    private static boolean unborderedTextOrBlank(Cell cell) {
        if (cell == null) return true;
        // Formula cells are deliberately excluded: their value could depend on an
        // external source, so the inferred note must never require evaluation.
        if (cell.getCellType() != CellType.BLANK && cell.getCellType() != CellType.STRING) return false;
        CellStyle style = cell.getCellStyle();
        return style.getBorderTop() == BorderStyle.NONE && style.getBorderBottom() == BorderStyle.NONE
                && style.getBorderLeft() == BorderStyle.NONE && style.getBorderRight() == BorderStyle.NONE;
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
    /** Horizontal attachments belong to expansion; do not extend a seed above or below its rows. */
    private boolean protrudingVertically(CellRangeAddress range, ClosedGrid grid, int componentId) {
        for (int c = range.getFirstColumn(); c <= range.getLastColumn() + 1; c++) {
            if (range.getFirstRow() > 0 && unexplainedVertical(range.getFirstRow() - 1, c, grid, componentId)) return true;
            if (unexplainedVertical(range.getLastRow() + 1, c, grid, componentId)) return true;
        }
        return false;
    }
    private boolean unexplainedVertical(int row, int column, ClosedGrid grid, int componentId) {
        return vertical.contains(key(row, column)) && !grid.foreignVerticalEdge(row, column, componentId);
    }
    /** Only another connected group of closed cells can account for a neighboring edge. */
    private record ClosedGrid(List<CellRangeAddress> units, Map<Long, Integer> occupied, int[] parents) {
        private CellRangeAddress foreignUnit(int row, int column, int componentId) {
            Integer unit = occupied.get(key(row, column));
            return unit == null || root(parents, unit) == componentId ? null : units.get(unit);
        }
        boolean foreignBottomEdge(int row, int column, int componentId) {
            CellRangeAddress above = foreignUnit(row - 1, column, componentId);
            return above != null && above.getLastRow() == row - 1;
        }
        boolean foreignVerticalEdge(int row, int column, int componentId) {
            CellRangeAddress right = foreignUnit(row, column, componentId);
            // A merged anchor may also store a right border inside its own span.
            // Those covered edges belong to that foreign closed unit as well.
            if (right != null) return true;
            CellRangeAddress left = foreignUnit(row, column - 1, componentId);
            return left != null;
        }
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
