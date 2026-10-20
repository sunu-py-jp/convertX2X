package com.convertx2x.excel2img.conversion;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFCell;

/** Cached display values only. No FormulaEvaluator, external-link resolution, or macro execution. */
final class CellValues {
    private final DataFormatter formatter = new DataFormatter(Locale.JAPAN);
    private final Map<Cell, String> values = new IdentityHashMap<>();
    private final ConversionWorkspace workspace;
    CellValues(ConversionWorkspace workspace) {
        this.workspace = workspace;
        formatter.setUseCachedValuesForFormulaCells(true);
    }
    String text(Cell cell) {
        if (cell == null) return "";
        return values.computeIfAbsent(cell, this::format);
    }
    private String format(Cell cell) {
        if (cell.getCellType() == CellType.FORMULA) {
            boolean missing = cell instanceof XSSFCell x && (!x.getCTCell().isSetV() ||
                    x.getCTCell().getV().isEmpty() && cell.getCachedFormulaResultType() != CellType.STRING);
            if (missing || cell.getCachedFormulaResultType() == CellType._NONE || cell.getCachedFormulaResultType() == CellType.BLANK) {
                workspace.warning("FORMULA_CACHE_MISSING", cell.getSheet().getSheetName(),
                        "数式の保存済み計算結果がないセルは #UNCALCULATED と表示しました。再計算は行っていません。");
                return "#UNCALCULATED";
            }
            workspace.warning("FORMULA_CACHED_VALUE", cell.getSheet().getSheetName(),
                    "数式は保存済み計算結果を使用しています。再計算や外部参照の取得は行っていません。");
        }
        String value;
        try { value = formatter.formatCellValue(cell); }
        catch (RuntimeException failure) {
            workspace.warning("CELL_VALUE_UNAVAILABLE", cell.getSheet().getSheetName(), "表示できないセル値を #VALUE! と表示しました。");
            value = "#VALUE!";
        }
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> { if (cp == '\n' || cp == '\r' || cp == '\t' || !Character.isISOControl(cp)) safe.appendCodePoint(cp == '\t' ? ' ' : cp); });
        return safe.toString().replace("\r\n", "\n").replace('\r', '\n');
    }
}
