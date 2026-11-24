package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Format-neutral list numbering of a Word document: the counters of every list and the displayed
 * prefix of a list paragraph. Each Word reader resolves its own list definition into {@link Definition}.
 */
final class WordListNumbers {
    /**
     * The nine levels of one list. {@code formats} use the WordprocessingML names ("decimal", "bullet",
     * "lowerRoman", ...; "" when a level is not defined), {@code texts} use %1-%9 for the level numbers,
     * and {@code restarts[i]} is the level after which level i restarts (0 never restarts).
     */
    record Definition(String[] formats, String[] texts, int[] starts, int[] restarts) { }

    private final ConversionWorkspace workspace;
    private final Map<String, int[]> counters = new HashMap<>();

    WordListNumbers(ConversionWorkspace workspace) { this.workspace = workspace; }

    /** Advances list {@code id} at {@code level} (0-8) and returns the indented Markdown prefix of the paragraph. */
    String prefix(String id, int level, Definition definition, String section, String range) {
        int[] starts = definition.starts();
        int[] values = counters.computeIfAbsent(id, ignored -> { int[] a = new int[9]; Arrays.fill(a, Integer.MIN_VALUE); return a; });
        if (values[level] == Integer.MAX_VALUE) values[level] = starts[level];
        else values[level] = values[level] == Integer.MIN_VALUE ? starts[level] : values[level] + 1;
        for (int i = level + 1; i < 9; i++) {
            int restart = definition.restarts()[i];
            if (restart != 0 && level < restart) values[i] = Integer.MIN_VALUE;
        }
        String format = definition.formats()[level];
        if ("bullet".equals(format)) return "  ".repeat(level) + "- ";
        if ("none".equals(format)) return "";
        String text = definition.texts()[level];
        if (text.isEmpty()) text = "%" + (level + 1) + ".";
        for (int i = 0; i < 9; i++) {
            int value = values[i] == Integer.MIN_VALUE ? starts[i] : values[i];
            String fmt = definition.formats()[i];
            text = text.replace("%" + (i + 1), format(value, fmt, section, range));
        }
        // Escaping the displayed prefix prevents a Markdown renderer from renumbering it.
        return "  ".repeat(level) + Markdown.escape(text + " ");
    }

    private String format(int value, String format, String section, String range) {
        return switch (format) {
            case "", "decimal", "bullet", "none" -> Integer.toString(value);
            case "decimalZero" -> value < 10 && value >= 0 ? "0" + value : Integer.toString(value);
            case "lowerLetter", "upperLetter" -> letters(value, "lowerLetter".equals(format));
            case "lowerRoman", "upperRoman" -> roman(value, "lowerRoman".equals(format));
            default -> {
                workspace.warning("NUMBERING_APPROXIMATED", section, range, "この番号書式は十進数に置き換えました。");
                yield Integer.toString(value);
            }
        };
    }

    private static String letters(int value, boolean lower) {
        if (value < 1) return Integer.toString(value);
        StringBuilder out = new StringBuilder();
        while (value > 0) { value--; out.append((char) ((lower ? 'a' : 'A') + value % 26)); value /= 26; }
        return out.reverse().toString();
    }

    private static String roman(int value, boolean lower) {
        if (value < 1 || value > 3999) return Integer.toString(value);
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) while (value >= values[i]) { out.append(symbols[i]); value -= values[i]; }
        return lower ? out.toString().toLowerCase(java.util.Locale.ROOT) : out.toString();
    }
}
