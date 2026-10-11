package com.convertx2x.office2pdf.conversion;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.DrawTextFragment;

/** Records POI's actual line layout, including table, group and rotation transforms. */
final class SlideTextCapture extends DrawFactory {
    record Glyph(String text, double x, float width, float fontSize) { }
    record Line(AffineTransform baselineToPixels, List<Glyph> glyphs) { }

    private final Graphics2D slideGraphics;
    private final List<Line> lines = new ArrayList<>();

    SlideTextCapture(Graphics2D slideGraphics) { this.slideGraphics = slideGraphics; }

    List<Line> lines() { return List.copyOf(lines); }

    @Override public DrawTextFragment getTextFragment(TextLayout layout, AttributedString text) {
        return new DrawTextFragment(layout, text) {
            private double x;
            private double y;

            @Override public void setPosition(double x, double y) {
                super.setPosition(x, y);
                this.x = x;
                this.y = y;
            }

            @Override public void draw(Graphics2D graphics) {
                super.draw(graphics);
                // POI copies our factory into temporary graphics when measuring text height.
                // Recording that pass would duplicate center/bottom-aligned text at the origin.
                if (graphics != slideGraphics || text == null) return;
                String value = getString();
                if (value == null || value.isBlank()) return;
                AffineTransform baseline = graphics.getTransform();
                baseline.translate(x, y + layout.getAscent());
                lines.add(new Line(baseline, glyphs(value, text, layout)));
            }
        };
    }

    private static List<Glyph> glyphs(String value, AttributedString attributed, TextLayout layout) {
        List<Glyph> result = new ArrayList<>();
        AttributedCharacterIterator attributes = attributed.getIterator();
        // Keep surrogate pairs and combining characters together, and preserve logical order.
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(value);
        for (int start = characters.first(), end = characters.next(); end != BreakIterator.DONE;
             start = end, end = characters.next()) {
            String cluster = value.substring(start, end);
            if (cluster.codePoints().allMatch(Character::isISOControl)) continue;
            float leading = layout.getCaretInfo(TextHitInfo.leading(start))[0];
            float trailing = layout.getCaretInfo(TextHitInfo.trailing(end - 1))[0];
            float width = Math.abs(trailing - leading);
            if (!Float.isFinite(width) || width <= 0) continue;
            attributes.setIndex(attributes.getBeginIndex() + start);
            Object font = attributes.getAttribute(TextAttribute.FONT);
            Object size = attributes.getAttribute(TextAttribute.SIZE);
            float fontSize = font instanceof Font awtFont ? awtFont.getSize2D()
                    : size instanceof Number number ? number.floatValue() : layout.getAscent();
            if (!Float.isFinite(fontSize) || fontSize <= 0) fontSize = Math.max(1, layout.getAscent());
            result.add(new Glyph(cluster, Math.min(leading, trailing), width, fontSize));
        }
        return List.copyOf(result);
    }
}
