package com.convertx2x.office2md.word;

import static com.convertx2x.office2md.word.WordXml.*;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.w3c.dom.Node;

final class WordNumbering {
    private final XWPFNumbering numbering;
    private final WordStyles styles;
    private final ConversionWorkspace workspace;
    private final Map<String, int[]> counters = new HashMap<>();
    WordNumbering(XWPFDocument document, WordStyles styles, ConversionWorkspace workspace) {
        numbering = document.getNumbering(); this.styles = styles; this.workspace = workspace;
    }
    String prefix(Node paragraph, String section, String range) {
        String id = "";
        int level = 0;
        for (Node props : styles.paragraphProperties(paragraph)) {
            Node num = child(props, "numPr");
            if (child(num, "numId") != null) id = val(child(num, "numId"));
            if (child(num, "ilvl") != null) level = integer(val(child(num, "ilvl")), 0);
        }
        if (id.isEmpty() || "0".equals(id)) return "";
        level = Math.clamp(level, 0, 8);
        Node[] levels = new Node[9];
        int[] starts = new int[9];
        Arrays.fill(starts, 1);
        try {
            var num = numbering == null ? null : numbering.getNum(new BigInteger(id));
            var abs = num == null ? null : numbering.getAbstractNum(num.getCTNum().getAbstractNumId().getVal());
            if (abs == null) throw new IllegalArgumentException();
            for (Node n : children(abs.getCTAbstractNum().getDomNode())) if (is(n, "lvl")) {
                int i = integer(attr(n, "ilvl"), -1);
                if (i >= 0 && i < 9) { levels[i] = n; starts[i] = integer(val(child(n, "start")), 1); }
            }
            for (Node n : children(num.getCTNum().getDomNode())) if (is(n, "lvlOverride")) {
                int i = integer(attr(n, "ilvl"), -1);
                if (i >= 0 && i < 9) {
                    if (child(n, "lvl") != null) levels[i] = child(n, "lvl");
                    if (child(n, "startOverride") != null) starts[i] = integer(val(child(n, "startOverride")), 1);
                }
            }
        } catch (RuntimeException e) {
            workspace.warning("NUMBERING_APPROXIMATED", section, range, "番号定義を解決できないため段落の箇条書きとして保持しました。");
            return "  ".repeat(level) + "- ";
        }
        int[] values = counters.computeIfAbsent(id, ignored -> { int[] a = new int[9]; Arrays.fill(a, Integer.MIN_VALUE); return a; });
        if (values[level] == Integer.MAX_VALUE) values[level] = starts[level];
        else values[level] = values[level] == Integer.MIN_VALUE ? starts[level] : values[level] + 1;
        for (int i = level + 1; i < 9; i++) {
            int restart = integer(val(child(levels[i], "lvlRestart")), i);
            if (restart != 0 && level < restart) values[i] = Integer.MIN_VALUE;
        }
        String format = val(child(levels[level], "numFmt"));
        if ("bullet".equals(format)) return "  ".repeat(level) + "- ";
        if ("none".equals(format)) return "";
        String text = val(child(levels[level], "lvlText"));
        if (text.isEmpty()) text = "%" + (level + 1) + ".";
        for (int i = 0; i < 9; i++) {
            int value = values[i] == Integer.MIN_VALUE ? starts[i] : values[i];
            String fmt = val(child(levels[i], "numFmt"));
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
