package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.Markdown;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown inline text of Word 97-2003 runs. Fields are never executed: their instructions are dropped
 * and only the result saved in the file is kept, with a HYPERLINK field becoming a Markdown link.
 * One instance follows the document in reading order because a field may span runs and paragraphs.
 */
final class LegacyWordInline {
    private static final char FIELD_BEGIN = 0x13, FIELD_SEPARATOR = 0x14, FIELD_END = 0x15;
    /** The first argument of a HYPERLINK field is its address unless the field only has switches (\l bookmark). */
    private static final Pattern HYPERLINK = Pattern.compile("(?is)^\\s*HYPERLINK\\s+(?:\"([^\"]*)\"|([^\\\\\\s]\\S*))?.*$");

    private static final class Field {
        final StringBuilder instruction = new StringBuilder();
        final int paragraph;
        boolean result;
        int resultStart = -1;
        Field(int paragraph) { this.paragraph = paragraph; }
    }

    private final WordMarkdownWriter writer;
    private final Deque<Field> fields = new ArrayDeque<>();
    private StringBuilder out = new StringBuilder();
    private String range = "document";
    private int paragraph;

    LegacyWordInline(WordMarkdownWriter writer) { this.writer = writer; }

    void beginParagraph(String range) {
        this.range = range;
        out = new StringBuilder();
        paragraph++;
    }

    /** Whether text at the current position is displayed, i.e. not part of a field instruction. */
    boolean visible() {
        for (Field field : fields) if (!field.result) return false;
        return true;
    }

    /** Adds a block that is not run text (an image reference) at the current position. */
    void block(String markdown) { writer.append(out, "\n\n" + markdown + "\n\n"); }

    /**
     * Adds the raw text of one run. Struck-through and hidden runs contribute no text but still close
     * or open fields and break pages, so the structure around them stays intact.
     */
    void run(String raw, boolean bold, boolean struck, boolean hidden) {
        boolean removedText = read(raw, bold, struck || hidden, true);
        if (removedText) writer.notice(struck ? "STRIKETHROUGH_REMOVED" : "HIDDEN_TEXT_REMOVED", range,
                struck ? "取消線の文字を除外しました。" : "非表示の文字を除外しました。", true);
    }

    /**
     * Adds a run deleted by a tracked change. It is not part of the document: its text and page breaks
     * are ignored without a notice, and only its field marks are followed.
     */
    void deleted(String raw) { read(raw, false, true, false); }

    /** Returns whether displayed text was left out because the run is removed. */
    private boolean read(String raw, boolean bold, boolean removed, boolean breaksPages) {
        boolean removedText = false;
        StringBuilder segment = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (value == FIELD_BEGIN) {
                flush(segment, bold);
                fields.push(new Field(paragraph));
                writer.notice("FIELD_CACHED_RESULT", range, "フィールドは実行せず、保存済みの表示だけを保持しました。", true);
            } else if (value == FIELD_SEPARATOR) {
                flush(segment, bold);
                if (!fields.isEmpty()) { fields.peek().result = true; fields.peek().resultStart = out.length(); }
            } else if (value == FIELD_END) {
                flush(segment, bold);
                if (!fields.isEmpty()) close(fields.pop());
            } else if (!visible()) {
                if (!fields.peek().result) fields.peek().instruction.append(value);
            } else if (value == 0x0c && breaksPages) {
                flush(segment, bold);
                writer.append(out, WordMarkdownWriter.PAGE_BREAK);
            } else if (removed) {
                removedText |= value >= 0x20;
            } else if (value == 0x0b) segment.append('\n');
            else if (value == '\t') segment.append("    ");
            else if (value == 0x1e) segment.append('‑');
            else if (value >= 0x20 && value != 0x7f) segment.append(value);
            // Dropped: paragraph (0x0d) and cell (0x07) marks, optional hyphens (0x1f), and the
            // placeholders of pictures (0x01), drawings (0x08), notes (0x02) and comments (0x05).
        }
        flush(segment, bold);
        return removedText;
    }

    /** The Markdown of the paragraph; it may contain {@link WordMarkdownWriter#PAGE_BREAK}. */
    String finishParagraph() {
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private void flush(StringBuilder segment, boolean bold) {
        if (segment.isEmpty()) return;
        String text = Markdown.escape(segment.toString());
        writer.append(out, bold ? Markdown.bold(text) : text);
        segment.setLength(0);
    }

    private void close(Field field) {
        // A result that started in an earlier paragraph was already written as ordinary text.
        if (!field.result || field.paragraph != paragraph || field.resultStart < 0 || field.resultStart > out.length()) return;
        Matcher hyperlink = HYPERLINK.matcher(field.instruction);
        if (!hyperlink.matches()) return;
        String label = out.substring(field.resultStart);
        if (label.isBlank() || label.contains(WordMarkdownWriter.PAGE_BREAK)) return;
        String address = hyperlink.group(1) != null ? hyperlink.group(1) : hyperlink.group(2);
        if (address == null || !Markdown.safeLink(address)) {
            writer.notice("UNSUPPORTED_LINK", range, "内部・ファイルなどのリンク先を除外し、表示文字を保持しました。", false);
            return;
        }
        out.setLength(field.resultStart);
        writer.append(out, Markdown.link(label, address));
    }
}
