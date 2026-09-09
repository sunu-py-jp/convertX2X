package com.slide2image.conversion;

import org.apache.poi.sl.draw.DrawFactory;
import org.apache.poi.sl.draw.DrawTextFragment;
import org.apache.poi.sl.draw.DrawTextParagraph;
import org.apache.poi.sl.usermodel.TextParagraph;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Locale;

/** Repairs POI 5.5.1's FONT attribute after its font manager changes a text range's FAMILY. */
final class PptFontDrawFactory extends DrawFactory {
    @Override
    public DrawTextParagraph getDrawable(TextParagraph<?, ?, ?> paragraph) {
        return new MappedParagraph(paragraph);
    }

    private static final class MappedParagraph extends DrawTextParagraph {
        private MappedParagraph(TextParagraph<?, ?, ?> paragraph) {
            super(paragraph);
        }

        @Override
        protected void breakText(Graphics2D graphics) {
            super.breakText(graphics);
            if (lines.isEmpty()) {
                return;
            }
            // POI exposes the resolved ranges through its text fragments. Reuse those attributes
            // (including colors, highlights and superscripts) without reflection or document edits.
            AttributedString corrected = new AttributedString(rawText);
            int offset = 0;
            boolean changed = false;
            for (DrawTextFragment line : lines) {
                AttributedCharacterIterator text = line.getAttributedString().getIterator();
                boolean leadingNewline = offset == 0 && rawText.startsWith("\n");
                var trailingAttributes = new HashMap<AttributedCharacterIterator.Attribute, Object>();
                for (int index = text.getBeginIndex(); index < text.getEndIndex();) {
                    text.setIndex(index);
                    int end = text.getRunLimit();
                    var attributes = new HashMap<>(text.getAttributes());
                    Object family = attributes.get(TextAttribute.FAMILY);
                    Object font = attributes.get(TextAttribute.FONT);
                    if (family instanceof String name && font instanceof Font original) {
                        // POI's empty-paragraph path creates a font without consulting its font manager.
                        String mapped = BundledPptFontManager.instance().getMappedFont(graphics, () -> name).getTypeface();
                        if (!mapped.equalsIgnoreCase(original.getFontName(Locale.ROOT))) {
                            attributes.put(TextAttribute.FAMILY, mapped);
                            attributes.remove(TextAttribute.FONT);
                            attributes.put(TextAttribute.FONT, new Font(attributes));
                            changed = true;
                        }
                    }
                    corrected.addAttributes(attributes, offset + index - text.getBeginIndex(),
                            offset + end - text.getBeginIndex());
                    trailingAttributes = attributes;
                    index = end;
                }
                offset += text.getEndIndex() - text.getBeginIndex();
                // POI excludes a terminating newline from its fragment, then advances past it.
                // Its special first fragment for a leading newline already contains that character.
                if (!leadingNewline && offset < rawText.length() && rawText.charAt(offset) == '\n') {
                    corrected.addAttributes(trailingAttributes, offset, offset + 1);
                    offset++;
                }
            }
            if (changed) {
                reflow(graphics, corrected);
            }
        }

        private void reflow(Graphics2D graphics, AttributedString corrected) {
            // Measure again with the replacement font so wrapping and alignment use its actual width.
            // Unchanged paragraphs retain POI's original layout and drawing path.
            AttributedCharacterIterator text = corrected.getIterator();
            LineBreakMeasurer measurer = new LineBreakMeasurer(text, graphics.getFontRenderContext());
            AttributedString drawable = new AttributedString(rawText.replace('\n', ' ').replace('\r', ' '));
            for (int index = text.getBeginIndex(); index < text.getEndIndex();) {
                text.setIndex(index);
                int end = text.getRunLimit();
                drawable.addAttributes(text.getAttributes(), index, end);
                index = end;
            }
            lines.clear();
            while (measurer.getPosition() < text.getEndIndex()) {
                int start = measurer.getPosition();
                float width = (float) Math.max(1, getWrappingWidth(lines.isEmpty(), graphics) + 1);
                TextLayout layout;
                int end;
                if (start == 0 && rawText.startsWith("\n")) {
                    layout = measurer.nextLayout(width, 1, false);
                    end = 1;
                } else {
                    // Match POI's boundary handling: an initial newline represents an empty line,
                    // while a terminating newline is skipped without becoming part of its layout.
                    int newline = rawText.indexOf('\n', start + 1);
                    int limit = newline < 0 ? text.getEndIndex() : newline;
                    layout = measurer.nextLayout(width, limit, true);
                    if (layout == null) {
                        layout = measurer.nextLayout(width, limit, false);
                    }
                    if (layout == null) {
                        break;
                    }
                    end = measurer.getPosition();
                    if (end < rawText.length() && rawText.charAt(end) == '\n') {
                        measurer.setPosition(end + 1);
                    }
                    if (paragraph.getTextAlign() == TextParagraph.TextAlign.JUSTIFY
                            || paragraph.getTextAlign() == TextParagraph.TextAlign.JUSTIFY_LOW) {
                        layout = layout.getJustifiedLayout(width);
                    }
                }
                // Rendering uses spaces for the line terminators, as POI's text fragments do.
                lines.add(new DrawTextFragment(layout, new AttributedString(drawable.getIterator(), start, end)));
            }
        }
    }
}
