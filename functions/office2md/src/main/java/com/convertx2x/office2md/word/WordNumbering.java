package com.convertx2x.office2md.word;

import static com.convertx2x.office2md.word.WordXml.*;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import java.math.BigInteger;
import java.util.Arrays;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.w3c.dom.Node;

final class WordNumbering {
    private final XWPFNumbering numbering;
    private final WordStyles styles;
    private final ConversionWorkspace workspace;
    private final WordListNumbers lists;
    WordNumbering(XWPFDocument document, WordStyles styles, ConversionWorkspace workspace) {
        numbering = document.getNumbering(); this.styles = styles; this.workspace = workspace;
        lists = new WordListNumbers(workspace);
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
        String[] formats = new String[9], texts = new String[9];
        int[] restarts = new int[9];
        for (int i = 0; i < 9; i++) {
            formats[i] = val(child(levels[i], "numFmt"));
            texts[i] = val(child(levels[i], "lvlText"));
            restarts[i] = integer(val(child(levels[i], "lvlRestart")), i);
        }
        return lists.prefix(id, level, new WordListNumbers.Definition(formats, texts, starts, restarts), section, range);
    }
}
