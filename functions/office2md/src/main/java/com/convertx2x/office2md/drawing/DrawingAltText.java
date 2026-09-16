package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import java.awt.geom.Rectangle2D;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Shared descriptions of rendered Office shapes. Callers must remove hidden/deleted text first. */
public final class DrawingAltText {
    private DrawingAltText() { }

    /** Accepts DrawingML presets or POI enum names; unknown identifiers remain inspectable. */
    public static String typeName(String preset) {
        String original = singleLine(preset);
        String key = original.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "rect", "rectangle" -> "長方形";
            case "roundrect", "roundrectangle" -> "角丸長方形";
            case "ellipse", "oval" -> "楕円";
            case "line" -> "直線";
            case "straightconnector1" -> "直線コネクタ";
            case "bentconnector2", "bentconnector3", "bentconnector4", "bentconnector5" -> "折れ線コネクタ";
            case "curvedconnector2", "curvedconnector3", "curvedconnector4", "curvedconnector5" -> "曲線コネクタ";
            case "text", "textbox" -> "テキストボックス";
            case "picture", "image" -> "画像";
            case "group" -> "グループ";
            case "triangle" -> "三角形";
            case "rttriangle", "righttriangle" -> "直角三角形";
            case "diamond" -> "ひし形";
            case "parallelogram" -> "平行四辺形";
            case "trapezoid" -> "台形";
            case "nonisoscelestrapezoid" -> "不等辺台形";
            case "pentagon" -> "五角形";
            case "hexagon" -> "六角形";
            case "heptagon" -> "七角形";
            case "octagon" -> "八角形";
            case "decagon" -> "十角形";
            case "dodecagon" -> "十二角形";
            case "rightarrow", "arrow", "thickarrow" -> "右矢印";
            case "leftarrow" -> "左矢印";
            case "uparrow" -> "上矢印";
            case "downarrow" -> "下矢印";
            case "leftrightarrow" -> "左右矢印";
            case "updownarrow" -> "上下矢印";
            case "quadarrow" -> "四方向矢印";
            case "leftuparrow" -> "左上矢印";
            case "leftrightuparrow" -> "左右上矢印";
            case "bentarrow" -> "屈曲矢印";
            case "uturnarrow" -> "Uターン矢印";
            case "circulararrow" -> "環状矢印";
            case "leftcirculararrow" -> "左環状矢印";
            case "curvedrightarrow" -> "右カーブ矢印";
            case "curvedleftarrow" -> "左カーブ矢印";
            case "curveduparrow" -> "上カーブ矢印";
            case "curveddownarrow" -> "下カーブ矢印";
            case "chevron" -> "山形";
            case "homeplate" -> "ホームベース";
            case "star4", "star5", "star6", "star7", "star8", "star10", "star12", "star16", "star24", "star32" -> "星（" + key.substring(4) + "角）";
            case "heart" -> "ハート";
            case "cloud" -> "雲";
            case "sun" -> "太陽";
            case "moon" -> "月";
            case "smileyface" -> "スマイル";
            case "lightningbolt" -> "稲妻";
            case "plus" -> "十字";
            case "arc" -> "円弧";
            case "pie" -> "扇形";
            case "chord" -> "弦";
            case "donut" -> "ドーナツ";
            case "can", "cylinder" -> "円柱";
            case "cube" -> "立方体";
            case "bevel" -> "額縁";
            case "leftbracket" -> "左角かっこ";
            case "rightbracket" -> "右角かっこ";
            case "leftbrace" -> "左波かっこ";
            case "rightbrace" -> "右波かっこ";
            case "wedgerectcallout" -> "四角形吹き出し";
            case "wedgeroundrectcallout" -> "角丸四角形吹き出し";
            case "wedgeellipsecallout" -> "円形吹き出し";
            case "cloudcallout" -> "雲形吹き出し";
            case "flowchartprocess" -> "フローチャート・処理";
            case "flowchartdecision" -> "フローチャート・判断";
            case "flowchartinputoutput" -> "フローチャート・データ";
            case "flowchartdocument" -> "フローチャート・書類";
            case "flowchartterminator" -> "フローチャート・端子";
            case "flowchartpredefinedprocess" -> "フローチャート・定義済み処理";
            case "flowchartconnector" -> "フローチャート・結合子";
            case "custom", "custgeom", "freeform" -> "フリーフォーム";
            case "graphicframe" -> "グラフ・SmartArt等";
            case "" -> "図形（種類不明）";
            default -> "図形（" + original + "）";
        };
    }

    /** Returns unescaped text. DrawingML positive rotation is clockwise on the page. */
    public static String describe(String typeName, double clockwiseDegrees, String sanitizedText) {
        return describe(typeName, rotation(clockwiseDegrees), sanitizedText);
    }

    /** Use an explicit qualifier when reflections or group deformation prevent a single angle. */
    public static String describe(String typeName, String rotationDescription, String sanitizedText) {
        String type = singleLine(typeName), rotation = singleLine(rotationDescription), text = singleLine(sanitizedText);
        return (type.isEmpty() ? "図形" : type) + (rotation.isEmpty() ? "" : "、" + rotation)
                + (text.isEmpty() ? "" : "、" + text);
    }

    public static String rotation(double clockwiseDegrees) {
        if (!Double.isFinite(clockwiseDegrees)) return "回転角度不明";
        double normalized = ((clockwiseDegrees % 360) + 360) % 360;
        double rounded = Math.round(normalized * 1000d) / 1000d;
        if (rounded >= 360 || rounded == 0) rounded = 0;
        return "時計回り" + BigDecimal.valueOf(rounded).stripTrailingZeros().toPlainString() + "度";
    }

    /**
     * Unescaped location description. Bounds are in points after group/shape transforms,
     * excluding rendering padding and stroke thickness. An unknown location must be null,
     * never the fallback rectangle used to make an otherwise positionless shape drawable.
     */
    public static String geometry(String reference, Rectangle2D bounds) {
        String origin = singleLine(reference);
        String prefix = origin.isEmpty() ? "基準不明" : "基準=" + origin;
        if (bounds == null || !Double.isFinite(bounds.getX()) || !Double.isFinite(bounds.getY())
                || !Double.isFinite(bounds.getWidth()) || !Double.isFinite(bounds.getHeight())
                || bounds.getWidth() < 0 || bounds.getHeight() < 0)
            return prefix + "、位置・サイズ不明";
        return prefix + "、外接矩形 X=" + points(bounds.getX()) + "、Y=" + points(bounds.getY())
                + "、幅=" + points(bounds.getWidth()) + "、高さ=" + points(bounds.getHeight());
    }

    private static String points(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "pt";
    }

    /** Returns escaped content for the brackets in ![alt](path), never a complete image tag. */
    public static String imageAlt(List<String> descriptions) {
        return imageAlt(descriptions, Long.MAX_VALUE);
    }

    /** The caller's Markdown writer must still enforce its cumulative document/output limits. */
    public static String imageAlt(List<String> descriptions, long maxUtf8Bytes) {
        StringBuilder result = new StringBuilder();
        long bytes = 0;
        for (String description : descriptions) {
            String normalized = singleLine(description);
            if (normalized.isEmpty()) continue;
            String escaped = Markdown.escape(normalized);
            long required = escaped.getBytes(StandardCharsets.UTF_8).length + (result.isEmpty() ? 0L : 3L);
            if (required > maxUtf8Bytes - bytes)
                throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "図形の代替テキストがMarkdownの上限を超えました。");
            if (!result.isEmpty()) result.append(" / ");
            result.append(escaped); bytes += required;
        }
        if (result.isEmpty()) {
            if (maxUtf8Bytes < 6) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "図形の代替テキストがMarkdownの上限を超えました。");
            return "図形";
        }
        return result.toString();
    }

    /** Collapse line breaks/whitespace without converting source text into markup. */
    public static String singleLine(String source) {
        if (source == null || source.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        boolean space = false;
        for (int at = 0; at < source.length();) {
            int cp = source.codePointAt(at); at += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85) space = !text.isEmpty();
            else if (!Character.isISOControl(cp)) {
                if (space) text.append(' ');
                text.appendCodePoint(cp); space = false;
            }
        }
        return text.toString();
    }
}
