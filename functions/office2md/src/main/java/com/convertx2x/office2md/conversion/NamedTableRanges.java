package com.convertx2x.office2md.conversion;

import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/** Applies unambiguous Excel defined-name boundaries after normal border detection and expansion. */
final class NamedTableRanges {
    record Defined(String name, CellRangeAddress range) { }
    record Part(TableExpansion.Table table, List<CellRangeAddress> headerSeeds,
                List<Defined> names, CellRangeAddress sourceRange) {
        boolean split() { return !same(table.range(), sourceRange); }
    }

    private NamedTableRanges() { }

    static List<Part> apply(Sheet sheet, List<TableExpansion.Table> tables, MergedRanges merges,
                            ConversionWorkspace workspace) {
        List<Defined> names = forSheet(sheet);
        List<Part> result = new ArrayList<>();
        for (TableExpansion.Table table : tables) result.addAll(parts(sheet, table, names, merges, workspace));
        result.sort(Comparator.comparingInt((Part part) -> part.table().range().getFirstRow())
                .thenComparingInt(part -> part.table().range().getFirstColumn()));
        return result;
    }

    private static List<Defined> forSheet(Sheet sheet) {
        Workbook workbook = sheet.getWorkbook();
        int sheetIndex = workbook.getSheetIndex(sheet);
        List<Defined> result = new ArrayList<>();
        for (Name name : workbook.getAllNames()) {
            String label = name.getNameName();
            if (label == null || label.isBlank() || label.toLowerCase(Locale.ROOT).startsWith("_xlnm.")
                    || name.isDeleted() || name.isHidden() || name.isFunctionName()) continue;
            String formula = name.getRefersToFormula();
            if (formula == null || formula.isBlank() || formula.length() > 512 || formula.indexOf('[') >= 0
                    || formula.indexOf(']') >= 0) continue;
            try {
                AreaReference[] areas = AreaReference.generateContiguous(workbook.getSpreadsheetVersion(), formula);
                if (areas.length != 1 || areas[0].isWholeColumnReference()) continue;
                CellReference first = areas[0].getFirstCell(), last = areas[0].getLastCell();
                if (first.getRow() < 0 || last.getRow() < first.getRow() || first.getCol() < 0
                        || last.getCol() < first.getCol() || !first.isRowAbsolute() || !first.isColAbsolute()
                        || !last.isRowAbsolute() || !last.isColAbsolute()) continue;
                String firstSheet = first.getSheetName(), lastSheet = last.getSheetName();
                if (firstSheet == null) {
                    if (lastSheet != null || name.getSheetIndex() != sheetIndex) continue;
                } else if (!firstSheet.equals(sheet.getSheetName())
                        || (lastSheet != null && !lastSheet.equals(firstSheet))) continue;
                result.add(new Defined(label, new CellRangeAddress(first.getRow(), last.getRow(),
                        first.getCol(), last.getCol())));
            } catch (RuntimeException ignored) {
                // Defined names may contain formulas, unions, external references, or broken refs.
                // Never evaluate them or use them as a reason to change a detected table.
            }
        }
        return result;
    }

    private static List<Part> parts(Sheet sheet, TableExpansion.Table table, List<Defined> all,
                                    MergedRanges merges, ConversionWorkspace workspace) {
        CellRangeAddress base = table.range();
        List<Defined> contained = all.stream().filter(name -> contains(base, name.range())).toList();
        List<Defined> exact = contained.stream().filter(name -> same(base, name.range())).toList();
        List<Defined> interior = contained.stream().filter(name -> !same(base, name.range()))
                .filter(name -> eligibleBand(sheet, table, name.range())).toList();
        if (interior.isEmpty()) return List.of(new Part(table, table.seeds(), exact, base));
        if (!exact.isEmpty()) return unsplit(sheet, table, exact, workspace);

        boolean columns = interior.stream().allMatch(name -> name.range().getFirstRow() == base.getFirstRow()
                && name.range().getLastRow() == base.getLastRow());
        boolean rows = interior.stream().allMatch(name -> name.range().getFirstColumn() == base.getFirstColumn()
                && name.range().getLastColumn() == base.getLastColumn());
        if (!columns && !rows) return unsplit(sheet, table, exact, workspace);
        boolean byColumns = columns;
        List<Defined> ordered = interior.stream().sorted(Comparator.comparingInt(name -> byColumns
                ? name.range().getFirstColumn() : name.range().getFirstRow())).toList();
        int end = byColumns ? base.getLastColumn() : base.getLastRow();
        int next = byColumns ? base.getFirstColumn() : base.getFirstRow();
        List<Part> parts = new ArrayList<>();
        for (Defined name : ordered) {
            int first = byColumns ? name.range().getFirstColumn() : name.range().getFirstRow();
            int last = byColumns ? name.range().getLastColumn() : name.range().getLastRow();
            if (first < next) return unsplit(sheet, table, exact, workspace);
            if (next < first) addPart(parts, table, band(base, next, first - 1, byColumns), List.of());
            addPart(parts, table, name.range(), List.of(name));
            next = last + 1;
        }
        if (next <= end) addPart(parts, table, band(base, next, end, byColumns), List.of());
        if (parts.size() < 2 || crossesBoundary(base, parts, merges.all())
                || crossesBoundary(base, parts, table.inferredMerges()))
            return unsplit(sheet, table, exact, workspace);
        workspace.info("NAMED_TABLE_SPLIT", sheet.getSheetName(), base.formatAsString(),
                "重ならない名前付き範囲に従い、検出済みの表を分割しました。");
        return parts;
    }

    private static List<Part> unsplit(Sheet sheet, TableExpansion.Table table, List<Defined> exact,
                                      ConversionWorkspace workspace) {
        workspace.info("NAMED_TABLE_SPLIT_SKIPPED", sheet.getSheetName(), table.range().formatAsString(),
                "名前付き範囲の重なり、形状、または結合セルが分割境界と競合するため、元の一表を維持しました。");
        return List.of(new Part(table, table.seeds(), exact, table.range()));
    }

    private static boolean eligibleBand(Sheet sheet, TableExpansion.Table table, CellRangeAddress range) {
        long visibleRows = 0;
        for (int row = range.getFirstRow(); row <= range.getLastRow(); row++) {
            Row physical = sheet.getRow(row);
            if (physical == null || !physical.getZeroHeight()) visibleRows++;
        }
        long visibleColumns = table.columns().stream().filter(column -> column >= range.getFirstColumn()
                && column <= range.getLastColumn()).count();
        if (visibleRows < 2 || visibleColumns < 2) return false;
        return table.values().entrySet().stream().anyMatch(entry -> inRange(range, entry.getKey())
                && !entry.getValue().isBlank())
                && table.seeds().stream().anyMatch(seed -> seed.intersects(range));
    }

    private static void addPart(List<Part> parts, TableExpansion.Table base, CellRangeAddress range,
                                List<Defined> names) {
        List<Integer> columns = base.columns().stream().filter(column -> column >= range.getFirstColumn()
                && column <= range.getLastColumn()).toList();
        if (columns.isEmpty()) return;
        Map<Long, String> values = new LinkedHashMap<>();
        for (var entry : base.values().entrySet()) if (inRange(range, entry.getKey())) values.put(entry.getKey(), entry.getValue());
        List<CellRangeAddress> headerSeeds = base.seeds().stream().filter(seed -> seed.intersects(range))
                .map(seed -> intersection(seed, range)).toList();
        List<CellRangeAddress> inferred = base.inferredMerges().stream().filter(range::intersects).toList();
        TableExpansion.Table sliced = new TableExpansion.Table(range, base.seeds(), columns, values, inferred);
        parts.add(new Part(sliced, headerSeeds, names, base.range()));
    }

    private static boolean crossesBoundary(CellRangeAddress base, List<Part> parts,
                                           List<CellRangeAddress> spans) {
        for (CellRangeAddress span : spans) {
            if (!span.intersects(base)) continue;
            int owners = 0;
            for (Part part : parts) if (contains(part.table().range(), span)) owners++;
            if (owners != 1) return true;
        }
        return false;
    }

    private static CellRangeAddress band(CellRangeAddress base, int first, int last, boolean columns) {
        return columns ? new CellRangeAddress(base.getFirstRow(), base.getLastRow(), first, last)
                : new CellRangeAddress(first, last, base.getFirstColumn(), base.getLastColumn());
    }
    private static CellRangeAddress intersection(CellRangeAddress left, CellRangeAddress right) {
        return new CellRangeAddress(Math.max(left.getFirstRow(), right.getFirstRow()),
                Math.min(left.getLastRow(), right.getLastRow()),
                Math.max(left.getFirstColumn(), right.getFirstColumn()),
                Math.min(left.getLastColumn(), right.getLastColumn()));
    }
    private static boolean inRange(CellRangeAddress range, long key) {
        return range.isInRange(BorderTables.row(key), BorderTables.col(key));
    }
    private static boolean contains(CellRangeAddress outer, CellRangeAddress inner) {
        return outer.getFirstRow() <= inner.getFirstRow() && outer.getLastRow() >= inner.getLastRow()
                && outer.getFirstColumn() <= inner.getFirstColumn() && outer.getLastColumn() >= inner.getLastColumn();
    }
    private static boolean same(CellRangeAddress left, CellRangeAddress right) {
        return left.getFirstRow() == right.getFirstRow() && left.getLastRow() == right.getLastRow()
                && left.getFirstColumn() == right.getFirstColumn() && left.getLastColumn() == right.getLastColumn();
    }
}
