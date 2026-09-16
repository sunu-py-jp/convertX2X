package com.convertx2x.office2md.word;

import static com.convertx2x.office2md.word.WordXml.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.w3c.dom.Node;

/** Resolves current styles only: revision snapshots in *PrChange are never read. */
final class WordStyles {
    private static final Pattern HEADING = Pattern.compile("(?iu)^(?:heading|見出し)\\s*([1-6])$");
    private final Map<String, Node> styles = new HashMap<>();
    private Node defaults;
    private String defaultParagraph = "";
    record RunStyle(boolean bold, boolean strike, boolean hidden) { }

    WordStyles(XWPFDocument document) {
        if (document.getStyles() == null) return;
        Node root = document.getStyles().getCtStyles().getDomNode();
        defaults = child(root, "docDefaults");
        for (Node style : children(root)) if (is(style, "style")) {
            styles.put(attr(style, "styleId"), style);
            if ("paragraph".equals(attr(style, "type")) && "1".equals(attr(style, "default")))
                defaultParagraph = attr(style, "styleId");
        }
    }
    List<Node> paragraphProperties(Node paragraph) {
        List<Node> result = new ArrayList<>();
        result.add(child(child(defaults, "pPrDefault"), "pPr"));
        Node direct = child(paragraph, "pPr");
        String id = val(child(direct, "pStyle"));
        for (Node style : chain(id.isEmpty() ? defaultParagraph : id)) result.add(child(style, "pPr"));
        result.add(direct);
        return result;
    }
    RunStyle run(Node paragraph, Node run) {
        boolean[] values = new boolean[5];
        apply(values, child(child(defaults, "rPrDefault"), "rPr"));
        Node pPr = child(paragraph, "pPr");
        String id = val(child(pPr, "pStyle"));
        for (Node style : chain(id.isEmpty() ? defaultParagraph : id)) apply(values, child(style, "rPr"));
        Node rPr = child(run, "rPr");
        for (Node style : chain(val(child(rPr, "rStyle")))) apply(values, child(style, "rPr"));
        apply(values, rPr);
        return new RunStyle(values[0], values[1] || values[2], values[3] || values[4]);
    }
    private void apply(boolean[] values, Node props) {
        if (props == null) return;
        if (child(props, "b") != null) values[0] = on(child(props, "b"));
        String[] names = {"strike", "dstrike", "vanish", "webHidden"};
        for (int i = 0; i < names.length; i++)
            if (child(props, names[i]) != null) values[i + 1] = on(child(props, names[i]));
    }
    int heading(Node paragraph) {
        Integer outline = null;
        for (Node props : paragraphProperties(paragraph)) {
            Node level = child(props, "outlineLvl");
            if (level != null) outline = integer(val(level), 9);
        }
        if (outline != null) return outline >= 0 && outline < 6 ? outline + 1 : 0;
        String id = val(child(child(paragraph, "pPr"), "pStyle"));
        List<Node> chain = chain(id);
        java.util.Collections.reverse(chain);
        for (Node style : chain) {
            int level = headingName(val(child(style, "name")));
            if (level == 0) level = headingName(attr(style, "styleId"));
            if (level != 0) return level;
        }
        return headingName(id);
    }
    private static int headingName(String name) {
        Matcher m = HEADING.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }
    private List<Node> chain(String id) {
        List<Node> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (!id.isEmpty() && seen.add(id) && result.size() < 64) {
            Node style = styles.get(id);
            if (style == null) break;
            result.add(style);
            id = val(child(style, "basedOn"));
        }
        java.util.Collections.reverse(result);
        return result;
    }
}
