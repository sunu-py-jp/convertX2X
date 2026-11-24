package com.convertx2x.office2pdf.conversion;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Visible text of a Word 97-2003 text stream. Field instructions are never executed or shown; only the
 * result saved in the file is kept. One instance follows a document in reading order because a field may
 * span several runs and paragraphs.
 */
final class LegacyWordText {
    /** Page and section breaks are returned as this character so the caller can start a new page. */
    static final char PAGE_BREAK = '\f';
    private static final char FIELD_BEGIN = 0x13, FIELD_SEPARATOR = 0x14, FIELD_END = 0x15;
    /** One entry per open field: false while reading its instruction, true while reading its saved result. */
    private final Deque<Boolean> fields = new ArrayDeque<>();

    boolean insideInstruction() { return fields.contains(false); }

    String visible(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (value == FIELD_BEGIN) { fields.push(false); continue; }
            if (value == FIELD_SEPARATOR) { if (!fields.isEmpty()) { fields.pop(); fields.push(true); } continue; }
            if (value == FIELD_END) { if (!fields.isEmpty()) fields.pop(); continue; }
            if (insideInstruction()) continue;
            switch (value) {
                case 0x0b -> out.append('\n');            // line break inside a paragraph
                case 0x1e -> out.append('‑');        // non-breaking hyphen
                case '\t', PAGE_BREAK -> out.append(value);
                default -> { if (value >= 0x20 && value != 0x7f) out.append(value); }
                // Dropped: paragraph (0x0d) and cell (0x07) marks, optional hyphen (0x1f), and the
                // placeholders for pictures (0x01), drawings (0x08), notes (0x02) and comments (0x05).
            }
        }
        return out.toString();
    }
}
