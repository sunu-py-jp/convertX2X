package com.convertx2x.office2md.conversion;

import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Adds same-row context to detected grids, without creating tables from plain text. */
final class TableExpansion {
    record Table(CellRangeAddress range, List<CellRangeAddress> seeds, List<Integer> columns,
                 Map<Long, String> values, List<CellRangeAddress> inferredMerges) { }
    private record Band(int firstRow, int lastRow, int left, int right) { }
    private record Rows(int first, int last) { }

    static List<Table> expand(Sheet sheet, List<CellRangeAddress> detected, BorderTables borders,
                             MergedRanges merges, NavigableMap<Long, String> values) {
        int lastColumn = sheet.getWorkbook().getSpreadsheetVersion().getLastColumnIndex();
        List<CellRangeAddress> seeds = detected.stream().filter(seed -> visibleSeed(sheet, seed)).toList();
        MergedRanges seedIndex = new MergedRanges(seeds);
        Map<Rows, List<CellRangeAddress>> barriersByRows = new HashMap<>();
        Map<Band, List<CellRangeAddress>> grouped = new LinkedHashMap<>();
        for (CellRangeAddress seed : seeds) {
            int left = 0, right = lastColumn;
            List<CellRangeAddress> barriers = barriersByRows.computeIfAbsent(new Rows(seed.getFirstRow(), seed.getLastRow()),
                    rows -> seedIndex.overlappingRows(rows.first(), rows.last()).stream().filter(other -> !sameRows(seed, other)).toList());
            for (CellRangeAddress other : barriers) {
                if (other.getLastColumn() < seed.getFirstColumn()) left = Math.max(left, other.getLastColumn() + 1);
                if (other.getFirstColumn() > seed.getLastColumn()) right = Math.min(right, other.getFirstColumn() - 1);
            }
            grouped.computeIfAbsent(new Band(seed.getFirstRow(), seed.getLastRow(), left, right), ignored -> new ArrayList<>()).add(seed);
        }
        List<List<CellRangeAddress>> groups = new ArrayList<>(grouped.values());
        List<CellRangeAddress> bases = groups.stream().map(TableExpansion::bounds).toList();
        MergedRanges baseIndex = new MergedRanges(bases);
        List<Table> result = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            List<CellRangeAddress> group = groups.get(index);
            CellRangeAddress base = bases.get(index);
            int left = 0, right = lastColumn;
            for (CellRangeAddress other : baseIndex.overlappingRows(base.getFirstRow(), base.getLastRow())) {
                // Different row bands remain separate. Divide their gap so a value cannot be copied twice.
                if (other.getLastColumn() < base.getFirstColumn()) left = Math.max(left, (other.getLastColumn() + base.getFirstColumn()) / 2 + 1);
                if (other.getFirstColumn() > base.getLastColumn()) right = Math.min(right, (base.getLastColumn() + other.getFirstColumn()) / 2);
            }
            Map<Long, String> owned = new LinkedHashMap<>();
            SortedSet<Integer> columns = new TreeSet<>();
            for (CellRangeAddress seed : group) addColumns(sheet, columns, seed.getFirstColumn(), seed.getLastColumn());
            List<CellRangeAddress> inferred = new ArrayList<>();
            Set<Long> visualCells = new HashSet<>();
            for (var entry : values.subMap(BorderTables.key(base.getFirstRow(), 0), true,
                    BorderTables.key(base.getLastRow(), lastColumn), true).entrySet()) {
                int row = BorderTables.row(entry.getKey()), column = BorderTables.col(entry.getKey());
                if (column < left || column > right) continue;
                CellRangeAddress merge = merges.at(row, column);
                if (merge != null && (merge.getFirstRow() < base.getFirstRow() || merge.getLastRow() > base.getLastRow()
                        || merge.getFirstColumn() < left || merge.getLastColumn() > right)) continue;
                owned.put(entry.getKey(), entry.getValue());
                columns.add(column);
                if (merge != null) addColumns(sheet, columns, merge.getFirstColumn(), merge.getLastColumn());
                if (merge != null || visualCells.contains(entry.getKey()) || group.stream().anyMatch(seed -> seed.isInRange(row, column))) continue;
                CellRangeAddress visual = visualSpan(sheet, borders, merges, row, column, left, right);
                if (visual == null || group.stream().anyMatch(visual::intersects)) continue;
                inferred.add(visual);
                for (int c = visual.getFirstColumn(); c <= visual.getLastColumn(); c++) visualCells.add(BorderTables.key(row, c));
                addColumns(sheet, columns, visual.getFirstColumn(), visual.getLastColumn());
            }
            if (columns.isEmpty()) continue;
            CellRangeAddress range = new CellRangeAddress(base.getFirstRow(), base.getLastRow(), columns.first(), columns.last());
            result.add(new Table(range, List.copyOf(group), List.copyOf(columns), owned, List.copyOf(inferred)));
        }
        result.sort(Comparator.comparingInt((Table table) -> table.range().getFirstRow()).thenComparingInt(table -> table.range().getFirstColumn()));
        return result;
    }

    /** Infer only enclosed horizontal spans; missing borders on plain notes do not merge their values. */
    private static CellRangeAddress visualSpan(Sheet sheet, BorderTables borders, MergedRanges merges,
                                               int row, int column, int min, int max) {
        int left = column, right = column;
        while (left > min && !borders.hasVertical(row, left)) {
            if (!horizontalCell(borders, row, left) || sheet.isColumnHidden(left - 1) || merges.at(row, left - 1) != null) return null;
            left--;
        }
        while (right < max && !borders.hasVertical(row, right + 1)) {
            if (!horizontalCell(borders, row, right) || sheet.isColumnHidden(right + 1) || merges.at(row, right + 1) != null) return null;
            right++;
        }
        if (left == right || !borders.hasVertical(row, left) || !borders.hasVertical(row, right + 1)) return null;
        for (int c = left; c <= right; c++) if (!horizontalCell(borders, row, c)) return null;
        return new CellRangeAddress(row, row, left, right);
    }
    private static boolean horizontalCell(BorderTables borders, int row, int column) {
        return borders.hasHorizontal(row, column) && borders.hasHorizontal(row + 1, column);
    }
    private static void addColumns(Sheet sheet, Set<Integer> columns, int first, int last) {
        for (int column = first; column <= last; column++) if (!sheet.isColumnHidden(column)) columns.add(column);
    }
    private static boolean visibleSeed(Sheet sheet, CellRangeAddress seed) {
        boolean visibleColumn = false;
        for (int column = seed.getFirstColumn(); column <= seed.getLastColumn(); column++)
            if (!sheet.isColumnHidden(column)) { visibleColumn = true; break; }
        if (!visibleColumn) return false;
        for (int row = seed.getFirstRow(); row <= seed.getLastRow(); row++)
            if (sheet.getRow(row) == null || !sheet.getRow(row).getZeroHeight()) return true;
        return false;
    }
    private static boolean sameRows(CellRangeAddress a, CellRangeAddress b) {
        return a.getFirstRow() == b.getFirstRow() && a.getLastRow() == b.getLastRow();
    }
    private static CellRangeAddress bounds(List<CellRangeAddress> ranges) {
        CellRangeAddress first = ranges.getFirst();
        return new CellRangeAddress(first.getFirstRow(), first.getLastRow(),
                ranges.stream().mapToInt(CellRangeAddress::getFirstColumn).min().orElseThrow(),
                ranges.stream().mapToInt(CellRangeAddress::getLastColumn).max().orElseThrow());
    }
}
