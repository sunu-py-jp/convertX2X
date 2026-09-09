package com.convertx2x.excel2md.drawing;

import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFSimpleShape;
import org.apache.poi.hssf.usermodel.HSSFTextbox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.SimpleShape;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSimpleShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.main.ThemeDocument;
import org.w3c.dom.Node;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Sanitizes drawing text before it can reach rendering, Markdown, or diagnostics. */
final class DrawingText {
    private static final Run DEFAULT = new Run("", "Noto Sans CJK JP", 12, false, false, null);

    /** Sizes and character spacing are points; color retains the saved alpha channel. */
    record Run(String text, String fontFamily, float fontSize, boolean bold, boolean italic,
               String hyperlink, Color color, boolean underline, float characterSpacing) {
        Run { if (color == null) color = Color.BLACK; }
        Run(String text, String fontFamily, float fontSize, boolean bold, boolean italic, String hyperlink) {
            this(text, fontFamily, fontSize, bold, italic, hyperlink, Color.BLACK, false, 0);
        }
    }

    record Paragraph(List<Run> runs, int alignment, double marginLeft, double marginRight, double indent,
                     double lineSpacing, boolean percentageSpacing, double spaceBefore, double spaceAfter) {
        Paragraph { runs = List.copyOf(runs); }
    }

    record Content(List<Paragraph> paragraphs, int verticalAlignment, boolean wrap, double fontScale,
                   double lineSpacingReduction, boolean shrinkToFit, double rotation, String verticalMode,
                   List<String> approximations) {
        Content { paragraphs = List.copyOf(paragraphs); approximations = List.copyOf(approximations); }
    }

    private DrawingText() { }

    static List<Run> read(SimpleShape shape, Workbook workbook) {
        return flatten(readContent(shape, workbook));
    }

    static List<Run> flatten(Content content) {
        List<Run> runs = new ArrayList<>();
        boolean first = true;
        for (Paragraph paragraph : content.paragraphs()) {
            if (!first) runs.add(withText(DEFAULT, "\n"));
            first = false;
            runs.addAll(paragraph.runs());
        }
        return List.copyOf(runs);
    }

    static Content readContent(SimpleShape shape, Workbook workbook) {
        if (shape instanceof XSSFSimpleShape xssf) return readXssf(xssf, workbook);
        if (shape instanceof HSSFSimpleShape hssf) return readHssf(hssf, workbook);
        return new Content(List.of(), 1, true, 1, 0, false, 0, "horz", List.of());
    }

    private static Content readXssf(XSSFSimpleShape shape, Workbook workbook) {
        var body = shape.getCTShape().getTxBody();
        if (body == null) return new Content(List.of(), 1, true, 1, 0, false, 0, "horz", List.of());
        Set<String> approximations = new LinkedHashSet<>();
        Node xml = shape.getCTShape().getDomNode();
        Node bodyNode = body.getDomNode();
        Node bodyProperties = child(bodyNode, "bodyPr");
        Node list = child(bodyNode, "lstStyle");
        Node fontReference = child(child(xml, "style"), "fontRef");
        ColorContext colors = new ColorContext(workbook, approximations);
        Color fontColor = colors.resolve(fontReference, Color.BLACK, Color.BLACK);
        List<Paragraph> paragraphs = new ArrayList<>();
        for (Node paragraph : children(bodyNode, "p")) {
            List<Node> paragraphProperties = new ArrayList<>();
            Node direct = child(paragraph, "pPr");
            add(paragraphProperties, direct);
            int level = (int) number(attribute(direct, "lvl"), 0);
            add(paragraphProperties, child(list, "lvl" + (Math.max(0, Math.min(8, level)) + 1) + "pPr"));
            add(paragraphProperties, child(list, "defPPr"));
            List<Node> defaults = new ArrayList<>();
            for (Node property : paragraphProperties) add(defaults, child(property, "defRPr"));
            List<Run> runs = new ArrayList<>();
            for (Node source = paragraph.getFirstChild(); source != null; source = source.getNextSibling()) {
                if (!Set.of("r", "br", "fld").contains(local(source))) continue;
                List<Node> properties = new ArrayList<>();
                add(properties, child(source, "rPr"));
                properties.addAll(defaults);
                String strike = inheritedAttribute(properties, "strike");
                if (!strike.isEmpty() && !strike.equals("noStrike")) continue;
                // Do not read a removed run's text, hyperlink, or optional effects.
                String value = local(source).equals("br") ? "\n" : nodeText(child(source, "t"));
                if (value.isEmpty()) continue;
                Run run = xssfRun(value.replace("\r\n", "\n").replace('\r', '\n'), properties,
                        shape, colors, fontReference, fontColor, approximations);
                runs.add(run);
                if (local(source).equals("fld")) approximations.add("文字フィールドは保存済み表示を使い、再計算しません。");
            }
            if (runs.isEmpty()) {
                // Preserve a real empty paragraph's font metrics without retaining removed run properties.
                List<Node> properties = new ArrayList<>();
                add(properties, child(paragraph, "endParaRPr"));
                properties.addAll(defaults);
                runs.add(xssfRun("", properties, shape, colors, fontReference, fontColor, approximations));
            }
            String align = inheritedAttribute(paragraphProperties, "algn");
            int alignment = switch (align) { case "ctr" -> 2; case "r" -> 3;
                case "just", "justLow", "dist", "thaiDist" -> 4; default -> 1; };
            if (Set.of("justLow", "dist", "thaiDist").contains(align))
                approximations.add("特殊な均等割り付けは通常の両端揃えに近似します。");
            Node line = inheritedChild(paragraphProperties, "lnSpc");
            Node linePercent = child(line, "spcPct");
            boolean percentage = linePercent != null || line == null;
            double spacing = percentage ? percent(attribute(linePercent, "val"), 1) * 100
                    : number(attribute(child(line, "spcPts"), "val"), 0) / 100;
            double fontSize = runs.stream().mapToDouble(Run::fontSize).max().orElse(12);
            double before = paragraphSpacing(inheritedChild(paragraphProperties, "spcBef"), fontSize, approximations);
            double after = paragraphSpacing(inheritedChild(paragraphProperties, "spcAft"), fontSize, approximations);
            Node bullet = inheritedChoice(paragraphProperties, "buNone", "buAutoNum", "buChar", "buBlip");
            if (bullet != null && !local(bullet).equals("buNone"))
                approximations.add("箇条書きの記号・自動番号は描画せず、本文と段落余白を保持します。");
            if (inheritedChild(paragraphProperties, "tabLst") != null)
                approximations.add("独自のタブ位置は再現せず、通常の空白として描画します。");
            paragraphs.add(new Paragraph(runs, alignment,
                    number(inheritedAttribute(paragraphProperties, "marL"), 0) / 12700,
                    number(inheritedAttribute(paragraphProperties, "marR"), 0) / 12700,
                    number(inheritedAttribute(paragraphProperties, "indent"), 0) / 12700,
                    Math.max(0, spacing), percentage, before, after));
        }
        int vertical = switch (attribute(bodyProperties, "anchor")) {
            case "ctr" -> 2; case "b" -> 3; case "just" -> 4; case "dist" -> 5; default -> 1;
        };
        String verticalMode = attribute(bodyProperties, "vert");
        if (verticalMode.isEmpty()) verticalMode = "horz";
        if (!Set.of("horz", "vert270").contains(verticalMode)) approximations.add("縦書き・縦方向の文字組みは横書きに近似します。");
        if (truth(attribute(bodyProperties, "upright"))) approximations.add("回転した図形の文字だけを正立に保つ指定は再現しません。");
        if (child(bodyProperties, "spAutoFit") != null) approximations.add("文字に合わせた図形サイズの自動拡張は行わず、保存された枠を使います。");
        if (number(attribute(bodyProperties, "numCol"), 1) > 1) approximations.add("図形内の複数段組みは一段の文字として描画します。");
        if (truth(attribute(bodyProperties, "anchorCtr"))) approximations.add("文字全体の中央アンカーは段落の配置で近似します。");
        Node warp = child(bodyProperties, "prstTxWarp");
        if ((warp != null && !attribute(warp, "prst").equals("textNoShape")) || child(bodyProperties, "scene3d") != null
                || child(bodyProperties, "sp3d") != null)
            approximations.add("ワードアート・立体文字の変形や効果は再現しません。");
        Node autoFit = child(bodyProperties, "normAutofit");
        return new Content(paragraphs, vertical, !attribute(bodyProperties, "wrap").equals("none"),
                Math.max(.01, Math.min(1, percent(attribute(autoFit, "fontScale"), 1))),
                Math.max(0, Math.min(1, percent(attribute(autoFit, "lnSpcReduction"), 0))),
                autoFit != null, number(attribute(bodyProperties, "rot"), 0) / 60000,
                verticalMode, List.copyOf(approximations));
    }

    private static Run xssfRun(String text, List<Node> properties, XSSFSimpleShape shape,
                               ColorContext colors, Node fontReference, Color fontColor,
                               Set<String> approximations) {
        boolean eastAsian = text.codePoints().anyMatch(DrawingText::isEastAsian);
        Node family = inheritedChild(properties, eastAsian ? "ea" : "latin");
        if (family == null || attribute(family, "typeface").isBlank()) family = inheritedChild(properties, "latin");
        String font = attribute(family, "typeface");
        font = colors.fontFamily(font, attribute(fontReference, "idx"), eastAsian);
        Node fill = inheritedChoice(properties, "noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill");
        Color color = fontColor;
        if (fill != null) {
            if (local(fill).equals("noFill")) color = new Color(0, 0, 0, 0);
            else if (local(fill).equals("solidFill")) color = colors.resolve(fill, fontColor, fontColor);
            else approximations.add("文字の複雑な塗りつぶしは単色に近似します。");
        }
        String underline = inheritedAttribute(properties, "u");
        if (!Set.of("", "none", "sng").contains(underline)) approximations.add("特殊な下線は一本の下線に近似します。");
        if (inheritedChild(properties, "ln") != null || inheritedChild(properties, "effectLst") != null
                || inheritedChild(properties, "effectDag") != null || inheritedChild(properties, "highlight") != null)
            approximations.add("文字の輪郭・影・光彩・強調効果は再現しません。");
        if (number(inheritedAttribute(properties, "baseline"), 0) != 0)
            approximations.add("上付き・下付き文字の位置は通常のベースラインに近似します。");
        String caps = inheritedAttribute(properties, "cap");
        if (!Set.of("", "none").contains(caps)) approximations.add("大文字・小型大文字の書式変換は行わず、保存された文字を使います。");
        if (text.indexOf('\t') >= 0) approximations.add("タブは通常の空白として描画します。");
        return new Run(text, font, validSize((float) (number(inheritedAttribute(properties, "sz"), 1200) / 100)),
                truth(inheritedAttribute(properties, "b")), truth(inheritedAttribute(properties, "i")),
                text.isEmpty() ? null : hyperlink(shape, inheritedChild(properties, "hlinkClick")), color,
                !underline.isEmpty() && !underline.equals("none"),
                (float) (pointValue(inheritedAttribute(properties, "spc"), 0) / 100));
    }

    private static double paragraphSpacing(Node spacing, double fontSize, Set<String> approximations) {
        Node points = child(spacing, "spcPts");
        if (points != null) return Math.max(0, number(attribute(points, "val"), 0) / 100);
        Node percentage = child(spacing, "spcPct");
        if (percentage == null) return 0;
        double pointsFromPercentage = Math.max(0, percent(attribute(percentage, "val"), 0) * fontSize);
        if (pointsFromPercentage > 0)
            approximations.add("割合指定の段落間隔は保存フォントサイズを基準に近似します。");
        return pointsFromPercentage;
    }

    private static String hyperlink(XSSFSimpleShape shape, Node properties) {
        if (properties == null || shape.getDrawing() == null) return null;
        Node idAttribute = properties.getAttributes().getNamedItemNS(
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
        String id = idAttribute == null ? "" : idAttribute.getNodeValue();
        if (id.isBlank()) return null;
        var relation = shape.getDrawing().getPackagePart().getRelationship(id);
        if (relation == null || relation.getTargetMode() != TargetMode.EXTERNAL) return null;
        URI uri = relation.getTargetURI();
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!Set.of("https", "http", "mailto").contains(scheme)) return null;
        if (!scheme.equals("mailto") && (uri.getHost() == null || uri.getHost().isBlank())) return null;
        String value = uri.toString();
        return value.codePoints().anyMatch(Character::isISOControl) ? null : value;
    }

    private static Content readHssf(HSSFSimpleShape shape, Workbook workbook) {
        HSSFRichTextString rich = shape.getString();
        List<Run> runs = new ArrayList<>();
        Set<String> approximations = new LinkedHashSet<>();
        int start = 0;
        while (rich != null && start < rich.length()) {
            short index = rich.getFontAtIndex(start);
            int end = start + 1;
            while (end < rich.length() && rich.getFontAtIndex(end) == index) end++;
            org.apache.poi.ss.usermodel.Font source = workbook.getFontAt(index < 0 ? 0 : index);
            if (!source.getStrikeout()) {
                String text = rich.getString().substring(start, end).replace("\r\n", "\n").replace('\r', '\n');
                Color color = Color.BLACK;
                if (workbook instanceof HSSFWorkbook hssf) {
                    var palette = hssf.getCustomPalette().getColor(source.getColor());
                    if (palette != null) {
                        short[] rgb = palette.getTriplet();
                        color = new Color(rgb[0], rgb[1], rgb[2]);
                    }
                }
                if (source.getUnderline() != org.apache.poi.ss.usermodel.Font.U_NONE
                        && source.getUnderline() != org.apache.poi.ss.usermodel.Font.U_SINGLE)
                    approximations.add("特殊な下線は一本の下線に近似します。");
                if (source.getTypeOffset() != org.apache.poi.ss.usermodel.Font.SS_NONE)
                    approximations.add("上付き・下付き文字の位置は通常のベースラインに近似します。");
                runs.add(new Run(text, source.getFontName(), validSize(source.getFontHeight() / 20f),
                        source.getBold(), source.getItalic(), null, color,
                        source.getUnderline() != org.apache.poi.ss.usermodel.Font.U_NONE, 0));
            }
            start = end;
        }
        int alignment = shape instanceof HSSFTextbox textbox ? textbox.getHorizontalAlignment() : 1;
        int vertical = shape instanceof HSSFTextbox textbox ? textbox.getVerticalAlignment() : 1;
        if (alignment > 4) {
            alignment = 4;
            approximations.add("特殊な均等割り付けは通常の両端揃えに近似します。");
        }
        List<Paragraph> paragraphs = new ArrayList<>();
        List<Run> paragraph = new ArrayList<>();
        for (Run run : runs) {
            String[] pieces = run.text().split("\n", -1);
            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) {
                    paragraphs.add(new Paragraph(paragraph, Math.max(1, alignment), 0, 0, 0, 100, true, 0, 0));
                    paragraph = new ArrayList<>();
                }
                paragraph.add(withText(run, pieces[i]));
            }
        }
        if (!paragraph.isEmpty()) paragraphs.add(new Paragraph(paragraph, Math.max(1, alignment), 0, 0, 0, 100, true, 0, 0));
        return new Content(paragraphs, Math.max(1, Math.min(5, vertical)), shape.getWrapText() != HSSFSimpleShape.WRAP_NONE,
                1, 0, false, 0, "horz", List.copyOf(approximations));
    }

    private static Run withText(Run run, String text) {
        return new Run(text, run.fontFamily(), run.fontSize(), run.bold(), run.italic(), run.hyperlink(),
                run.color(), run.underline(), run.characterSpacing());
    }

    static String plainText(List<Run> runs) {
        StringBuilder text = new StringBuilder();
        for (Run run : runs) text.append(run.text());
        return text.toString();
    }

    private static final class ColorContext {
        private final Workbook workbook;
        private final Set<String> approximations;
        private Node theme;
        private boolean themeRead;
        ColorContext(Workbook workbook, Set<String> approximations) {
            this.workbook = workbook; this.approximations = approximations;
        }

        Color resolve(Node parent, Color fallback, Color placeholder) {
            if (parent == null) return fallback;
            Node source = null;
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
                if (Set.of("srgbClr", "schemeClr", "sysClr", "scrgbClr", "prstClr", "hslClr").contains(local(node))) { source = node; break; }
            if (source == null) return fallback;
            Color value;
            try {
                value = switch (local(source)) {
                    case "srgbClr" -> new Color(Integer.parseInt(attribute(source, "val"), 16));
                    case "sysClr" -> new Color(Integer.parseInt(attribute(source, "lastClr"), 16));
                    case "schemeClr" -> scheme(attribute(source, "val"), fallback, placeholder);
                    case "scrgbClr" -> new Color(channel(linearToSrgb(percent(attribute(source, "r"), 0))),
                            channel(linearToSrgb(percent(attribute(source, "g"), 0))), channel(linearToSrgb(percent(attribute(source, "b"), 0))));
                    default -> { approximations.add("特殊な文字色は既定の色に近似します。"); yield fallback; }
                };
            } catch (IllegalArgumentException ignored) {
                approximations.add("解決できない文字色は既定の色を使います。"); return fallback;
            }
            double r = value.getRed() / 255d, g = value.getGreen() / 255d, b = value.getBlue() / 255d, alpha = value.getAlpha() / 255d;
            for (Node transform = source.getFirstChild(); transform != null; transform = transform.getNextSibling()) {
                if (transform.getNodeType() != Node.ELEMENT_NODE) continue;
                double amount = percent(attribute(transform, "val"), 0);
                switch (local(transform)) {
                    case "alpha" -> alpha = amount;
                    case "alphaMod" -> alpha *= amount;
                    case "alphaOff" -> alpha += amount;
                    case "tint" -> { r = linearToSrgb(1 - (1 - srgbToLinear(r)) * amount);
                        g = linearToSrgb(1 - (1 - srgbToLinear(g)) * amount); b = linearToSrgb(1 - (1 - srgbToLinear(b)) * amount); }
                    case "shade" -> { r = linearToSrgb(srgbToLinear(r) * amount); g = linearToSrgb(srgbToLinear(g) * amount); b = linearToSrgb(srgbToLinear(b) * amount); }
                    case "red" -> r = amount; case "green" -> g = amount; case "blue" -> b = amount;
                    case "redMod" -> r *= amount; case "greenMod" -> g *= amount; case "blueMod" -> b *= amount;
                    case "redOff" -> r += amount; case "greenOff" -> g += amount; case "blueOff" -> b += amount;
                    case "lumMod", "lumOff", "satMod", "satOff", "hueMod", "hueOff" -> {
                        double[] hsl = rgbToHsl(r, g, b);
                        switch (local(transform)) {
                            case "lumMod" -> hsl[2] *= amount; case "lumOff" -> hsl[2] += amount;
                            case "satMod" -> hsl[1] *= amount; case "satOff" -> hsl[1] += amount;
                            case "hueMod" -> hsl[0] *= amount;
                            case "hueOff" -> hsl[0] += number(attribute(transform, "val"), 0) / 21600000;
                        }
                        double[] rgb = hslToRgb(hsl); r = rgb[0]; g = rgb[1]; b = rgb[2];
                    }
                    default -> approximations.add("文字色の特殊な変換は省略します。");
                }
            }
            return new Color(channel(r), channel(g), channel(b), channel(alpha));
        }

        private Color scheme(String name, Color fallback, Color placeholder) {
            if (name.equals("phClr")) return placeholder;
            int index = switch (name) {
                case "lt1", "bg1" -> 0; case "dk1", "tx1" -> 1; case "lt2", "bg2" -> 2; case "dk2", "tx2" -> 3;
                case "accent1" -> 4; case "accent2" -> 5; case "accent3" -> 6; case "accent4" -> 7;
                case "accent5" -> 8; case "accent6" -> 9; case "hlink" -> 10; case "folHlink" -> 11; default -> -1;
            };
            if (index >= 0 && workbook instanceof XSSFWorkbook xssf && xssf.getTheme() != null) {
                var color = xssf.getTheme().getThemeColor(index);
                if (color != null && color.getRGB() != null) {
                    byte[] rgb = color.getRGB();
                    return new Color(rgb[0] & 255, rgb[1] & 255, rgb[2] & 255);
                }
            }
            if (name.equals("dk1") || name.equals("tx1")) return Color.BLACK;
            if (name.equals("lt1") || name.equals("bg1")) return Color.WHITE;
            approximations.add("解決できないテーマ文字色は既定の色を使います。");
            return fallback;
        }

        String fontFamily(String requested, String fontRef, boolean eastAsian) {
            if (!requested.isEmpty() && !requested.startsWith("+mj-") && !requested.startsWith("+mn-")) return requested;
            String collection = requested.startsWith("+mj-") || (requested.isEmpty() && fontRef.equals("major")) ? "majorFont" : "minorFont";
            boolean useEastAsian = requested.endsWith("-ea") || (requested.isEmpty() && eastAsian);
            Node fontCollection = child(child(child(theme(), "themeElements"), "fontScheme"), collection);
            String family = attribute(child(fontCollection, useEastAsian ? "ea" : "latin"), "typeface");
            if (family.isBlank() && useEastAsian) {
                for (Node font : children(fontCollection, "font")) {
                    if (attribute(font, "script").equals("Jpan")) { family = attribute(font, "typeface"); break; }
                }
            }
            return family.isBlank() ? DEFAULT.fontFamily() : family;
        }

        private Node theme() {
            if (!themeRead) {
                themeRead = true;
                if (workbook instanceof XSSFWorkbook xssf && xssf.getTheme() != null) {
                    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        xssf.getTheme().writeTo(bytes);
                        theme = ThemeDocument.Factory.parse(new java.io.ByteArrayInputStream(bytes.toByteArray())).getTheme().getDomNode();
                    } catch (Exception ignored) { /* Font rendering will use the bundled fallback. */ }
                }
            }
            return theme;
        }
    }

    private static Node child(Node parent, String localName) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node.getNodeType() == Node.ELEMENT_NODE && local(node).equals(localName)) return node;
        return null;
    }

    private static List<Node> children(Node parent, String name) {
        List<Node> result = new ArrayList<>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node.getNodeType() == Node.ELEMENT_NODE && local(node).equals(name)) result.add(node);
        return result;
    }

    private static String local(Node node) { return node == null || node.getLocalName() == null ? "" : node.getLocalName(); }
    private static String attribute(Node node, String name) {
        if (node == null || node.getAttributes() == null) return "";
        Node value = node.getAttributes().getNamedItem(name);
        return value == null ? "" : value.getNodeValue();
    }
    private static String nodeText(Node node) {
        StringBuilder value = new StringBuilder();
        if (node != null) for (Node content = node.getFirstChild(); content != null; content = content.getNextSibling())
            if (content.getNodeType() == Node.TEXT_NODE || content.getNodeType() == Node.CDATA_SECTION_NODE) value.append(content.getNodeValue());
        return value.toString();
    }
    private static void add(List<Node> list, Node node) { if (node != null) list.add(node); }
    private static String inheritedAttribute(List<Node> properties, String name) {
        for (Node property : properties) {
            String value = attribute(property, name);
            if (!value.isEmpty()) return value;
        }
        return "";
    }
    private static Node inheritedChild(List<Node> properties, String name) { return inheritedChoice(properties, name); }
    private static Node inheritedChoice(List<Node> properties, String... names) {
        for (Node property : properties) for (String name : names) {
            Node value = child(property, name); if (value != null) return value;
        }
        return null;
    }
    private static boolean truth(String value) { return value.equals("true") || value.equals("1"); }
    private static double number(String value, double fallback) {
        try { double number = Double.parseDouble(value); return Double.isFinite(number) ? number : fallback; }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static double percent(String value, double fallback) {
        return value.endsWith("%") ? number(value.substring(0, value.length() - 1), fallback * 100) / 100
                : number(value, fallback * 100000) / 100000;
    }
    private static double pointValue(String value, double fallback) {
        return value.endsWith("pt") ? number(value.substring(0, value.length() - 2), fallback / 100) * 100 : number(value, fallback);
    }
    private static int channel(double value) { return (int) Math.round(Math.max(0, Math.min(1, value)) * 255); }
    private static double srgbToLinear(double value) { return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4); }
    private static double linearToSrgb(double value) { return value <= .0031308 ? 12.92 * value : 1.055 * Math.pow(value, 1 / 2.4) - .055; }
    private static double[] rgbToHsl(double r, double g, double b) {
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), delta = max - min;
        double light = (max + min) / 2, saturation = delta == 0 ? 0 : delta / (1 - Math.abs(2 * light - 1));
        double hue = delta == 0 ? 0 : max == r ? ((g - b) / delta) % 6 : max == g ? (b - r) / delta + 2 : (r - g) / delta + 4;
        return new double[]{(hue / 6 + 1) % 1, saturation, light};
    }
    private static double[] hslToRgb(double[] hsl) {
        double hue = (hsl[0] % 1 + 1) % 1 * 6, s = Math.max(0, Math.min(1, hsl[1])), l = Math.max(0, Math.min(1, hsl[2]));
        double c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs(hue % 2 - 1)), m = l - c / 2;
        double[] rgb = hue < 1 ? new double[]{c,x,0} : hue < 2 ? new double[]{x,c,0} : hue < 3 ? new double[]{0,c,x}
                : hue < 4 ? new double[]{0,x,c} : hue < 5 ? new double[]{x,0,c} : new double[]{c,0,x};
        return new double[]{rgb[0]+m,rgb[1]+m,rgb[2]+m};
    }

    static Font font(Run run) {
        String family = run.fontFamily() == null ? "" : run.fontFamily();
        String lower = family.toLowerCase(Locale.ROOT);
        boolean bold = run.bold() || lower.contains("bold") || lower.contains("demi");
        int style = (bold ? Font.BOLD : Font.PLAIN) | (run.italic() ? Font.ITALIC : Font.PLAIN);
        if (Fonts.AVAILABLE.contains(family) && !lower.startsWith("noto ")) {
            Font installed = new Font(family, style, 12).deriveFont(validSize(run.fontSize()));
            if (installed.canDisplayUpTo(run.text()) == -1) return installed;
        }
        boolean serif = lower.contains("mincho") || lower.contains("明朝") || lower.contains("times")
                || lower.contains("simsun") || lower.contains("song") || lower.contains("heiseimin")
                || lower.contains("kozmin") || lower.contains("hiramin") || lower.contains("ryumin")
                || (lower.contains("serif") && !lower.contains("sans"));
        Font bundled = Fonts.BUNDLED[(serif ? 2 : 0) + (bold ? 1 : 0)];
        return bundled.deriveFont(style, validSize(run.fontSize()));
    }

    /** Alignment values: 1 left, 2 center, 3 right. Coordinates and sizes are in points. */
    static void draw(Graphics2D graphics, List<Run> runs, Rectangle2D box, int horizontalAlignment) {
        if (runs.isEmpty() || box.getWidth() <= 0 || box.getHeight() <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(box);
            String text = plainText(runs);
            if (text.isEmpty()) return;
            AttributedString attributed = new AttributedString(text);
            int offset = 0;
            for (Run run : runs) {
                int end = offset + run.text().length();
                if (end > offset) attributed.addAttribute(TextAttribute.FONT, font(run), offset, end);
                offset = end;
            }
            float y = (float) box.getY();
            int begin = 0;
            while (begin <= text.length() && y < box.getMaxY()) {
                int end = text.indexOf('\n', begin);
                if (end < 0) end = text.length();
                if (begin == end) {
                    y += font(DEFAULT).getLineMetrics(" ", g.getFontRenderContext()).getHeight();
                } else {
                    LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(null, begin, end),
                            g.getFontRenderContext());
                    while (measurer.getPosition() < end && y < box.getMaxY()) {
                        TextLayout line = measurer.nextLayout((float) box.getWidth());
                        if (line == null) break;
                        y += line.getAscent();
                        float x = (float) box.getX();
                        if (horizontalAlignment == 2) x += ((float) box.getWidth() - line.getAdvance()) / 2;
                        if (horizontalAlignment == 3) x += (float) box.getWidth() - line.getAdvance();
                        line.draw(g, x, y);
                        y += line.getDescent() + line.getLeading();
                    }
                }
                if (end == text.length()) break;
                begin = end + 1;
            }
        } finally {
            g.dispose();
        }
    }

    private static float validSize(float size) {
        return Float.isFinite(size) && size > 0 ? Math.min(size, 4000) : 12;
    }

    private static boolean isEastAsian(int cp) {
        return switch (Character.UnicodeScript.of(cp)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> true;
            default -> false;
        };
    }

    private static final class Fonts {
        private static final Font[] BUNDLED = load();
        private static final Set<String> AVAILABLE = available();

        private static Font[] load() {
            Font[] fonts = new Font[4];
            for (int i = 0; i < fonts.length; i++) {
                String path = "/fonts/noto/Noto" + (i >= 2 ? "Serif" : "Sans")
                        + "CJKjp-" + (i % 2 == 1 ? "Bold" : "Regular") + ".otf";
                try (InputStream stream = DrawingText.class.getResourceAsStream(path)) {
                    if (stream == null) throw new IllegalStateException("Missing bundled drawing font: " + path);
                    fonts[i] = Font.createFont(Font.TRUETYPE_FONT, stream);
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot load bundled drawing font: " + path, failure);
                }
            }
            return fonts;
        }

        private static Set<String> available() {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            var environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            names.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.ROOT)));
            names.addAll(Arrays.asList(environment.getAvailableFontFamilyNames(Locale.JAPANESE)));
            for (Font font : environment.getAllFonts()) {
                names.add(font.getFontName(Locale.ROOT));
                names.add(font.getFontName(Locale.JAPANESE));
                names.add(font.getPSName());
            }
            return names;
        }
    }
}
