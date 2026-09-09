package com.convertx2x.excel2md.drawing;

import com.convertx2x.excel2md.conversion.ConversionException;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lays out saved DrawingML text properties in the shape's local point coordinates. */
final class DrawingTextLayout {
    private record Line(TextLayout text, double x, double baseline, double advance) { }
    private record Layout(List<Line> lines, double height, double width) { }
    private static final DrawingText.Run DEFAULT = new DrawingText.Run("", "Noto Sans CJK JP", 12, false, false, null);

    private DrawingTextLayout() { }

    static void draw(Graphics2D graphics, DrawingText.Content content, Rectangle2D box, double textScale) {
        if (content == null || content.paragraphs().isEmpty() || box.getWidth() <= 0 || box.getHeight() <= 0) return;
        Budget budget = new Budget(content);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(box);
            double rotation = finite(content.rotation(), 0);
            if ("vert270".equals(content.verticalMode())) rotation += 270;
            // True East Asian vertical composition requires a different glyph layout engine;
            // the reader reports that fallback. A 270-degree horizontal frame is supported.
            double normalized = Math.abs(rotation % 180);
            boolean quarterTurn = Math.abs(normalized - 90) < .001;
            double width = quarterTurn ? box.getHeight() : box.getWidth();
            double height = quarterTurn ? box.getWidth() : box.getHeight();
            g.translate(box.getCenterX(), box.getCenterY());
            g.rotate(Math.toRadians(rotation));
            g.translate(-width / 2, -height / 2);
            double scale = positive(textScale, 1) * positive(content.fontScale(), 1);
            Layout layout = layout(content, width, scale, positive(textScale, 1), g.getFontRenderContext(), budget);
            if (content.shrinkToFit() && !fits(layout, width, height)) {
                // Saved fontScale is the upper bound. Reduce only when the saved geometry
                // still overflows with the fonts available on this host.
                double low = scale / 100, high = scale;
                Layout smallest = layout(content, width, low, positive(textScale, 1), g.getFontRenderContext(), budget);
                if (fits(smallest, width, height)) {
                    layout = smallest;
                    for (int i = 0; i < 12; i++) {
                        double candidate = (low + high) / 2;
                        Layout measured = layout(content, width, candidate, positive(textScale, 1), g.getFontRenderContext(), budget);
                        if (fits(measured, width, height)) { low = candidate; layout = measured; }
                        else high = candidate;
                    }
                } else layout = smallest;
            }
            double extra = Math.max(0, height - layout.height());
            double yOffset = switch (content.verticalAlignment()) {
                case 2 -> extra / 2;
                case 3 -> extra;
                case 5 -> layout.lines().isEmpty() ? 0 : extra / (layout.lines().size() * 2);
                default -> 0;
            };
            double between = switch (content.verticalAlignment()) {
                case 4 -> layout.lines().size() > 1 ? extra / (layout.lines().size() - 1) : 0;
                case 5 -> layout.lines().isEmpty() ? 0 : extra / layout.lines().size();
                default -> 0;
            };
            for (int i = 0; i < layout.lines().size(); i++) {
                Line line = layout.lines().get(i);
                if (line.text() != null) line.text().draw(g, (float) line.x(), (float) (line.baseline() + yOffset + i * between));
            }
        } finally { g.dispose(); }
    }

    private static boolean fits(Layout layout, double width, double height) {
        return layout.height() <= height + .01 && layout.width() <= width + .01;
    }

    private static Layout layout(DrawingText.Content content, double width, double fontScale, double pointScale, FontRenderContext context, Budget budget) {
        List<Line> lines = new ArrayList<>();
        double y = 0, widest = 0;
        double reduction = Math.max(0, Math.min(.99, finite(content.lineSpacingReduction(), 0)));
        for (DrawingText.Paragraph paragraph : content.paragraphs()) {
            String text = DrawingText.plainText(paragraph.runs()).replace('\t', ' ');
            AttributedString styled = attributes(paragraph.runs(), text, fontScale);
            y += Math.max(0, finite(paragraph.spaceBefore(), 0)) * pointScale;
            double paragraphBottom = y;
            double left = finite(paragraph.marginLeft(), 0) * pointScale, right = finite(paragraph.marginRight(), 0) * pointScale;
            boolean firstLine = true;
            int begin = 0;
            while (begin <= text.length()) {
                int end = text.indexOf('\n', begin);
                if (end < 0) end = text.length();
                double indent = firstLine ? finite(paragraph.indent(), 0) * pointScale : 0;
                if (end == begin) {
                    Font font = scaledFont(paragraph.runs().isEmpty() ? DEFAULT : paragraph.runs().getFirst(), fontScale);
                    var metrics = font.getLineMetrics(" ", context);
                    double step = lineStep(paragraph, metrics.getHeight(), reduction, pointScale);
                    budget.line();
                    lines.add(new Line(null, left + indent, y + metrics.getAscent(), 0));
                    paragraphBottom = Math.max(paragraphBottom, y + metrics.getHeight());
                    y += step; firstLine = false;
                } else if (!content.wrap()) {
                    TextLayout line = new TextLayout(styled.getIterator(null, begin, end), context);
                    double available = Math.max(.1, width - left - right - indent);
                    double x = alignedX(paragraph.alignment(), line, left + indent, available);
                    budget.line();
                    lines.add(new Line(line, x, y + line.getAscent(), line.getAdvance()));
                    paragraphBottom = Math.max(paragraphBottom, y + line.getAscent() + line.getDescent() + line.getLeading());
                    widest = Math.max(widest, left + indent + line.getAdvance() + right);
                    y += lineStep(paragraph, line.getAscent() + line.getDescent() + line.getLeading(), reduction, pointScale);
                    firstLine = false;
                } else {
                    LineBreakMeasurer measurer = new LineBreakMeasurer(styled.getIterator(null, begin, end), context);
                    while (measurer.getPosition() < end) {
                        indent = firstLine ? finite(paragraph.indent(), 0) * pointScale : 0;
                        double available = Math.max(.1, width - left - right - indent);
                        TextLayout line = measurer.nextLayout((float) Math.min(available, Float.MAX_VALUE));
                        if (line == null) break;
                        if (paragraph.alignment() == 4 && measurer.getPosition() < end && line.getAdvance() < available)
                            line = line.getJustifiedLayout((float) available);
                        double x = alignedX(paragraph.alignment(), line, left + indent, available);
                        budget.line();
                        lines.add(new Line(line, x, y + line.getAscent(), line.getAdvance()));
                        paragraphBottom = Math.max(paragraphBottom, y + line.getAscent() + line.getDescent() + line.getLeading());
                        widest = Math.max(widest, left + indent + line.getAdvance() + right);
                        y += lineStep(paragraph, line.getAscent() + line.getDescent() + line.getLeading(), reduction, pointScale);
                        firstLine = false;
                    }
                }
                if (end == text.length()) break;
                begin = end + 1;
            }
            y = paragraphBottom + Math.max(0, finite(paragraph.spaceAfter(), 0)) * pointScale;
        }
        return new Layout(lines, y, widest);
    }

    private static double alignedX(int alignment, TextLayout line, double left, double available) {
        return switch (alignment) {
            case 2 -> left + (available - line.getAdvance()) / 2;
            case 3 -> left + available - line.getAdvance();
            default -> left;
        };
    }

    private static double lineStep(DrawingText.Paragraph paragraph, double natural, double reduction, double pointScale) {
        double requested = positive(paragraph.lineSpacing(), paragraph.percentageSpacing() ? 100 : natural);
        return Math.max(.01, paragraph.percentageSpacing()
                ? natural * Math.max(.01, requested / 100 - reduction) : requested * pointScale);
    }

    private static AttributedString attributes(List<DrawingText.Run> runs, String text, double scale) {
        AttributedString attributed = new AttributedString(text);
        int offset = 0;
        for (DrawingText.Run run : runs) {
            int end = offset + run.text().length();
            if (end > offset) {
                attributed.addAttribute(TextAttribute.FONT, scaledFont(run, scale), offset, end);
                if (run.color() != null) attributed.addAttribute(TextAttribute.FOREGROUND, run.color(), offset, end);
                if (run.underline()) attributed.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, offset, end);
            }
            offset = end;
        }
        return attributed;
    }

    private static Font scaledFont(DrawingText.Run run, double scale) {
        Font font = DrawingText.font(run);
        font = font.deriveFont((float) Math.max(.01, Math.min(100_000, font.getSize2D() * scale)));
        if (run.characterSpacing() != 0 && Float.isFinite(run.characterSpacing()))
            font = font.deriveFont(Map.of(TextAttribute.TRACKING, (float) (run.characterSpacing() / positive(run.fontSize(), 12))));
        return font;
    }
    /** Bounds work as well as retained layouts, including all autofit measurement passes. */
    private static final class Budget {
        private int remainingLines = 20_000;
        Budget(DrawingText.Content content) {
            long characters = 0, runs = 0;
            if (content.paragraphs().size() > 10_000) throw limit();
            for (DrawingText.Paragraph paragraph : content.paragraphs()) {
                runs += paragraph.runs().size();
                for (DrawingText.Run run : paragraph.runs()) characters += run.text().length();
            }
            if (characters > 200_000 || runs > 20_000) throw limit();
        }
        void line() { if (--remainingLines < 0) throw limit(); }
        private static ConversionException limit() {
            return new ConversionException(413, "DRAWING_TEXT_LIMIT", "The drawing text exceeds the layout work limit.");
        }
    }
    private static double finite(double value, double fallback) { return Double.isFinite(value) ? value : fallback; }
    private static double positive(double value, double fallback) { return Double.isFinite(value) && value > 0 ? value : fallback; }
}
