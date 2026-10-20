package com.convertx2x.excel2img.drawing;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.poi.hssf.usermodel.HSSFSimpleShape;
import org.apache.poi.hssf.usermodel.HSSFTextbox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.w3c.dom.Node;
import static com.convertx2x.excel2img.drawing.ExcelDrawingLayer.*;

/** Rich drawing text retained as displayed, including strike-through characters. */
final class ShapeText {
    private static final String FALLBACK = "BIZ UDPGothic";
    record Run(String text, String family, float size, boolean bold, boolean italic, boolean strike, boolean underline, Color color) { }
    record Paragraph(List<Run> runs, int alignment) { }
    private record Line(TextLayout layout, int alignment) { }
    private ShapeText() { }
    static Paragraph plain(String text, float size) { return new Paragraph(List.of(new Run(text, FALLBACK, size, false, false, false, false, Color.BLACK)), 1); }

    static List<Paragraph> readXssf(Node xml, Workbook workbook) {
        List<Paragraph> result = new ArrayList<>();
        Node body = child(xml, "txBody"), list = child(body, "lstStyle");
        Color defaultColor = color(child(child(xml, "style"), "fontRef"), Color.BLACK, workbook);
        if (body == null) return List.of();
        for (Node p = body.getFirstChild(); p != null; p = p.getNextSibling()) {
            if (!"p".equals(p.getLocalName())) continue;
            Node pp = child(p, "pPr");
            int level = (int) Math.max(0, Math.min(8, number(pp, "lvl", 0))) + 1;
            List<Node> defaults = new ArrayList<>();
            add(defaults, child(pp, "defRPr")); add(defaults, child(child(list, "lvl" + level + "pPr"), "defRPr")); add(defaults, child(child(list, "defPPr"), "defRPr"));
            List<Run> runs = new ArrayList<>();
            Node bullet = child(pp, "buChar");
            if (bullet != null) runs.add(plain(attr(bullet, "char") + " ", 12).runs().getFirst());
            for (Node r = p.getFirstChild(); r != null; r = r.getNextSibling()) {
                String name = r.getLocalName();
                if (!"r".equals(name) && !"br".equals(name) && !"fld".equals(name)) continue;
                String text = "br".equals(name) ? "\n" : text(child(r, "t"));
                if (text.isEmpty()) continue;
                List<Node> properties = new ArrayList<>(); add(properties, child(r, "rPr")); properties.addAll(defaults);
                String family = inheritedChildAttr(properties, "ea", "typeface");
                if (family.isBlank()) family = inheritedChildAttr(properties, "latin", "typeface");
                if (family.isBlank() || family.startsWith("+")) family = FALLBACK;
                float size = (float) parse(inherited(properties, "sz"), 1200) / 100;
                String strike = inherited(properties, "strike"), underline = inherited(properties, "u");
                runs.add(new Run(text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' '), family,
                        Math.max(1, Math.min(400, size)), truth(inherited(properties, "b")), truth(inherited(properties, "i")),
                        !strike.isEmpty() && !strike.equals("noStrike"), !underline.isEmpty() && !underline.equals("none"),
                        color(inheritedChild(properties, "solidFill"), defaultColor, workbook)));
            }
            String alignment = attr(pp, "algn");
            result.add(new Paragraph(List.copyOf(runs), alignment.equals("ctr") ? 2 : alignment.equals("r") ? 3 : 1));
        }
        return List.copyOf(result);
    }

    static List<Paragraph> readHssf(HSSFSimpleShape shape, Workbook workbook) {
        var rich = shape.getString();
        if (rich == null || rich.length() == 0) return List.of();
        List<Run> runs = new ArrayList<>();
        String text = rich.getString();
        int start = 0;
        while (start < text.length()) {
            short fontIndex = rich.getFontAtIndex(start);
            int end = start + 1;
            while (end < text.length() && rich.getFontAtIndex(end) == fontIndex) end++;
            var font = workbook.getFontAt(Math.max(0, fontIndex));
            Color color = Color.BLACK;
            if (workbook instanceof HSSFWorkbook hssf) {
                var indexed = hssf.getCustomPalette().getColor(font.getColor());
                if (indexed != null) { short[] rgb = indexed.getTriplet(); color = new Color(rgb[0], rgb[1], rgb[2]); }
            }
            runs.add(new Run(text.substring(start, end).replace('\t', ' '), font.getFontName(),
                    Math.max(1, font.getFontHeightInPoints()), font.getBold(), font.getItalic(), font.getStrikeout(),
                    font.getUnderline() != org.apache.poi.ss.usermodel.Font.U_NONE, color));
            start = end;
        }
        return List.of(new Paragraph(List.copyOf(runs), shape instanceof HSSFTextbox box ? box.getHorizontalAlignment() : 1));
    }

    /** Returns true if fitting was necessary. All characters remain in the laid-out content. */
    static boolean draw(Graphics2D graphics, List<Paragraph> paragraphs, Rectangle2D box, int vertical, double scale) {
        if (paragraphs.isEmpty()) return false;
        Set<String> fonts = Set.copyOf(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        List<Line> lines = layout(graphics, paragraphs, box.getWidth(), scale, fonts);
        double height = height(lines), factor = 1;
        for (int i = 0; i < 8 && height > box.getHeight() && height > 0; i++) {
            factor *= Math.max(.05, Math.min(.95, box.getHeight() / height));
            lines = layout(graphics, paragraphs, box.getWidth(), scale * factor, fonts);
            height = height(lines);
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(box);
            double y = box.getY() + (vertical == 2 ? Math.max(0, box.getHeight() - height) / 2 : vertical == 3 ? Math.max(0, box.getHeight() - height) : 0);
            for (Line line : lines) {
                TextLayout layout = line.layout();
                double x = box.getX() + (line.alignment() == 2 ? Math.max(0, box.getWidth() - layout.getAdvance()) / 2
                        : line.alignment() == 3 ? Math.max(0, box.getWidth() - layout.getAdvance()) : 0);
                y += layout.getAscent(); layout.draw(g, (float) x, (float) y); y += layout.getDescent() + layout.getLeading();
            }
        } finally { g.dispose(); }
        return factor < .999;
    }
    private static List<Line> layout(Graphics2D graphics, List<Paragraph> paragraphs, double width, double scale, Set<String> fonts) {
        List<Line> result = new ArrayList<>();
        for (Paragraph p : paragraphs) {
            StringBuilder text = new StringBuilder(); for (Run run : p.runs()) text.append(run.text());
            if (text.isEmpty()) text.append(' ');
            AttributedString attributed = new AttributedString(text.toString());
            attributed.addAttribute(TextAttribute.FONT, new Font(FALLBACK, Font.PLAIN, 1).deriveFont((float) Math.max(.01, 12 * scale)));
            int position = 0;
            for (Run run : p.runs()) {
                if (run.text().isEmpty()) continue;
                String family = fonts.contains(run.family()) ? run.family() : FALLBACK;
                Font font = new Font(family, (run.bold() ? Font.BOLD : 0) | (run.italic() ? Font.ITALIC : 0), 1)
                        .deriveFont((float) Math.max(.01, run.size() * scale));
                if (font.canDisplayUpTo(run.text()) >= 0)
                    font = new Font(FALLBACK, font.getStyle(), 1).deriveFont(font.getSize2D());
                int end = position + run.text().length();
                attributed.addAttribute(TextAttribute.FONT, font, position, end);
                attributed.addAttribute(TextAttribute.FOREGROUND, run.color(), position, end);
                if (run.strike()) attributed.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, position, end);
                if (run.underline()) attributed.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, position, end);
                position = end;
            }
            var measurer = new LineBreakMeasurer(attributed.getIterator(), graphics.getFontRenderContext());
            while (measurer.getPosition() < text.length()) {
                int start = measurer.getPosition(), newline = text.indexOf("\n", start);
                int end = newline < 0 ? text.length() : newline;
                if (start == end) {
                    result.add(new Line(new TextLayout(" ", new Font(FALLBACK, Font.PLAIN, 1).deriveFont((float) Math.max(.01, 12 * scale)), graphics.getFontRenderContext()), p.alignment()));
                    measurer.setPosition(start + 1); continue;
                }
                TextLayout line = measurer.nextLayout((float) Math.max(.01, width), end, false);
                if (line == null) break;
                result.add(new Line(line, p.alignment()));
                if (measurer.getPosition() == newline) measurer.setPosition(newline + 1);
            }
        }
        return result;
    }
    private static double height(List<Line> lines) { return lines.stream().mapToDouble(l -> l.layout().getAscent() + l.layout().getDescent() + l.layout().getLeading()).sum(); }
    private static String text(Node node) {
        if (node == null) return "";
        StringBuilder result = new StringBuilder();
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) result.append(n.getNodeValue());
        return result.toString();
    }
    private static void add(List<Node> list, Node node) { if (node != null) list.add(node); }
    private static boolean truth(String value) { return value.equals("1") || value.equals("true"); }
    private static double parse(String value, double fallback) { try { return Double.parseDouble(value); } catch (NumberFormatException ex) { return fallback; } }
    private static String inherited(List<Node> list, String name) { for (Node n : list) { String v = attr(n, name); if (!v.isEmpty()) return v; } return ""; }
    private static Node inheritedChild(List<Node> list, String name) { for (Node n : list) { Node c = child(n, name); if (c != null) return c; } return null; }
    private static String inheritedChildAttr(List<Node> list, String name, String attribute) { return attr(inheritedChild(list, name), attribute); }
}
