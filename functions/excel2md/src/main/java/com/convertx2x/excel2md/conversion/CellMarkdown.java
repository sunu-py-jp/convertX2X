package com.convertx2x.excel2md.conversion;

import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/** Display values only: no formula evaluator, external workbook or network access. */
final class CellMarkdown {
    private final Workbook workbook;
    private final ConversionWorkspace workspace;
    private final DataFormatter formatter = new DataFormatter(Locale.JAPAN, true);

    CellMarkdown(Workbook workbook, ConversionWorkspace workspace) {
        this.workbook = workbook;
        this.workspace = workspace;
        // Security invariant: read saved results without a FormulaEvaluator, for every
        // formula (including unknown, nested and external-data functions). Never retry
        // a missing cache by evaluating a formula or resolving an external workbook.
        formatter.setUseCachedValuesForFormulaCells(true);
    }

    String format(Cell cell) {
        if (cell == null) return "";
        Font base = workbook.getFontAt(cell.getCellStyle().getFontIndex());
        String sheet = cell.getSheet().getSheetName(), coordinate = cell.getAddress().formatAsString();
        boolean formula = cell.getCellType() == CellType.FORMULA;
        boolean missingCache = formula && cell instanceof XSSFCell x && (!x.getCTCell().isSetV()
                || (x.getCachedFormulaResultType() == CellType.NUMERIC && x.getCTCell().getV().isEmpty()));
        RichTextString rich = null;
        if (cell.getCellType() == CellType.STRING) rich = cell.getRichStringCellValue();
        String raw = rich == null ? "" : rich.getString();
        String display;
        try { display = missingCache ? "=" + cell.getCellFormula() : formatter.formatCellValue(cell); }
        catch (RuntimeException e) {
            // A malformed format must not leak the unfiltered value through diagnostics.
            workspace.warning("CELL_FORMAT_UNSUPPORTED", sheet, coordinate, "セルの表示形式を適用できませんでした。");
            display = rich != null ? raw : "[表示形式を読み取れないセル]";
        }
        if (display == null) display = "";

        List<Run> runs = rich == null ? List.of(new Run(display, base.getBold(), base.getStrikeout())) : runs(rich, base);
        int removed = runs.stream().filter(Run::strike).mapToInt(r -> r.text().length()).sum();
        // Strikethrough on numeric/formula values is resolved before formula/link fallbacks.
        if (removed > 0 || (rich == null && base.getStrikeout())) {
            workspace.info("STRIKETHROUGH_REMOVED", sheet, coordinate, removed + "文字を除外しました。");
            if (runs.stream().filter(r -> !r.strike()).allMatch(r -> r.text().isEmpty())) return "";
        }
        if (rich != null && !display.equals(raw)) {
            if (removed > 0) workspace.warning("PARTIAL_FORMAT_FALLBACK", sheet, coordinate,
                    "部分取消線を除外するため、文字列の表示形式を適用せず出力しました。");
            else {
                if (rich.numFormattingRuns() > 0) workspace.warning("PARTIAL_FORMAT_FALLBACK", sheet, coordinate,
                        "表示形式で文字列が変わるため、セル全体の文字装飾を適用しました。");
                runs = List.of(new Run(display, base.getBold(), base.getStrikeout()));
            }
        }

        String address = cell.getHyperlink() == null ? null : cell.getHyperlink().getAddress();
        if (formula) {
            String expression = cell.getCellFormula();
            if (expression.matches("(?is)\\s*(?:_xlfn\\.)?IMAGE\\s*\\(.*")) {
                workspace.warning("CELL_IMAGE_UNSUPPORTED", sheet, coordinate, "IMAGE関数の画像は取得しません。");
                if (removed == 0) runs = List.of(new Run("[セル内画像: 未対応]", base.getBold(), false));
            } else if (isHyperlink(expression)) {
                LiteralHyperlink literal = parseHyperlink(expression);
                if (literal != null) {
                    address = literal.address();
                    if (missingCache && removed == 0) {
                        String label = literal.label() == null ? literal.address() : literal.label();
                        runs = List.of(new Run(label, base.getBold(), base.getStrikeout()));
                    }
                } else workspace.warning("DYNAMIC_HYPERLINK_UNSUPPORTED", sheet, coordinate,
                        "数式のリンク先を定数として読み取れないため、リンク化していません。");
            }
            if (missingCache && removed == 0) workspace.warning("FORMULA_CACHE_MISSING", sheet, coordinate,
                    "数式の保存済み計算結果がありません。再計算せず、数式または確定できるリンク表示を残しました。");
        }

        String rendered = render(runs);
        if (address != null && !address.isBlank()) {
            if (removed > 0 && rendered.isEmpty()) return "";
            if (Markdown.safeLink(address)) {
                if (rendered.isEmpty()) rendered = Markdown.escape(address);
                rendered = Markdown.link(rendered, address);
            } else workspace.warning("LINK_UNSUPPORTED", sheet, coordinate,
                    "Web・メール以外または不正なリンクを表示文字だけで保持しました。");
        }
        if (cell instanceof XSSFCell x && x.getCTCell().isSetVm()) {
            workspace.warning("CELL_IMAGE_UNSUPPORTED", sheet, coordinate, "セル内画像などの値メタデータは未対応です。");
            if (rendered.isEmpty() && removed == 0) rendered = "[セル内オブジェクト: 未対応]";
        }
        return rendered;
    }

    private List<Run> runs(RichTextString rich, Font base) {
        String text = rich.getString();
        List<Run> result = new ArrayList<>();
        int position = 0;
        for (int i = 0; i < rich.numFormattingRuns(); i++) {
            int start = Math.max(0, Math.min(text.length(), rich.getIndexOfFormattingRun(i)));
            int end = i + 1 < rich.numFormattingRuns() ? rich.getIndexOfFormattingRun(i + 1) : text.length();
            end = Math.max(start, Math.min(text.length(), end));
            if (start > position) result.add(new Run(text.substring(position, start), base.getBold(), base.getStrikeout()));
            boolean bold = base.getBold(), strike = base.getStrikeout();
            if (rich instanceof XSSFRichTextString x) {
                XSSFFont font = x.getFontOfFormattingRun(i);
                if (font != null) {
                    // An explicit run font replaces the cell font. In a run font,
                    // absent <b>/<strike> mean false (POI also writes false this way).
                    // A run without rPr returns null and inherits the cell font.
                    bold = font.getBold(); strike = font.getStrikeout();
                }
            } else if (rich instanceof HSSFRichTextString h) {
                short index = h.getFontOfFormattingRun(i);
                if (index != HSSFRichTextString.NO_FONT) {
                    Font font = workbook.getFontAt(index);
                    bold = font.getBold(); strike = font.getStrikeout();
                }
            }
            result.add(new Run(text.substring(start, end), bold, strike));
            position = end;
        }
        if (position < text.length()) result.add(new Run(text.substring(position), base.getBold(), base.getStrikeout()));
        return result;
    }

    private static String render(List<Run> runs) {
        StringBuilder result = new StringBuilder(), span = new StringBuilder();
        Boolean bold = null;
        for (Run run : runs) {
            if (run.strike() || run.text().isEmpty()) continue;
            if (bold != null && bold != run.bold()) {
                String escaped = Markdown.escape(span.toString());
                result.append(bold ? Markdown.bold(escaped) : escaped); span.setLength(0);
            }
            bold = run.bold(); span.append(run.text());
        }
        if (bold != null) {
            String escaped = Markdown.escape(span.toString());
            result.append(bold ? Markdown.bold(escaped) : escaped);
        }
        return result.toString();
    }
    private record Run(String text, boolean bold, boolean strike) { }
    private record LiteralHyperlink(String address, String label) { }

    private static boolean isHyperlink(String formula) {
        return formula.matches("(?is)\\s*(?:_xlfn\\.)?HYPERLINK\\s*\\(.*");
    }
    /** Only the destination is required to be a literal; dynamic labels use the saved display value. */
    private static LiteralHyperlink parseHyperlink(String formula) {
        if (!isHyperlink(formula)) return null;
        int[] offset = { formula.indexOf('(') + 1 };
        String address = stringLiteral(formula, offset);
        if (address == null) return null;
        whitespace(formula, offset);
        String label = null;
        if (offset[0] < formula.length() && formula.charAt(offset[0]) == ',') {
            offset[0]++;
            int labelStart = offset[0];
            label = stringLiteral(formula, offset);
            whitespace(formula, offset);
            if (label == null || offset[0] >= formula.length() || formula.charAt(offset[0]) != ')') {
                label = null; offset[0] = labelStart;
                if (!skipLabelExpression(formula, offset)) return null;
            }
        }
        whitespace(formula, offset);
        if (offset[0] >= formula.length() || formula.charAt(offset[0]++) != ')') return null;
        whitespace(formula, offset);
        return offset[0] == formula.length() ? new LiteralHyperlink(address, label) : null;
    }
    private static boolean skipLabelExpression(String expression, int[] offset) {
        int start = offset[0], depth = 0;
        boolean quoted = false;
        for (; offset[0] < expression.length(); offset[0]++) {
            char c = expression.charAt(offset[0]);
            if (c == '"') {
                if (quoted && offset[0] + 1 < expression.length() && expression.charAt(offset[0] + 1) == '"') offset[0]++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') depth++;
                else if (c == ')') {
                    if (depth == 0) return !expression.substring(start, offset[0]).isBlank();
                    depth--;
                } else if (c == ',' && depth == 0) return false;
            }
        }
        return false;
    }
    private static void whitespace(String text, int[] offset) {
        while (offset[0] < text.length() && Character.isWhitespace(text.charAt(offset[0]))) offset[0]++;
    }
    private static String stringLiteral(String text, int[] offset) {
        whitespace(text, offset);
        if (offset[0] >= text.length() || text.charAt(offset[0]++) != '"') return null;
        StringBuilder out = new StringBuilder();
        while (offset[0] < text.length()) {
            char c = text.charAt(offset[0]++);
            if (c == '"') {
                if (offset[0] < text.length() && text.charAt(offset[0]) == '"') { offset[0]++; out.append('"'); }
                else return out.toString();
            } else out.append(c);
        }
        return null;
    }
}
