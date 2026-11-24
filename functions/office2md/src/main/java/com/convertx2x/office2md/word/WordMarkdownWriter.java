package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionWorkspace;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Format-neutral Markdown output of a Word document: page markers, blocks with their line ranges,
 * tables, size limits and de-duplicated notices. Each Word reader walks its own document model and
 * writes through this class, so every Word format produces the same document structure.
 */
final class WordMarkdownWriter {
    /** XML 1.0 cannot contain this noncharacter, so source text cannot collide with it. */
    static final String PAGE_BREAK = "﷐";
    private final ConversionWorkspace workspace;
    private final String section;
    private final StringBuilder output = new StringBuilder();
    private final Set<String> noticeKeys = new HashSet<>();
    private long tableCells;
    private int outputLine = 1;
    private int pageNumber = 1;
    private boolean documentContent;

    WordMarkdownWriter(ConversionWorkspace workspace, String section) {
        this.workspace = workspace;
        this.section = section;
    }

    /** The main document text. Notes are collected in their own buffers and appended by the reader. */
    StringBuilder output() { return output; }

    void begin() {
        append(output, "[page 1]\n\n");
        outputLine = 3;
    }

    /** Writes one block. {@link #PAGE_BREAK} inside the text starts a new page in the main document. */
    void emit(StringBuilder destination, String text, String type, String range, boolean note) {
        if (!note && text.contains(PAGE_BREAK)) {
            String[] pages = text.split(PAGE_BREAK, -1);
            for (int index = 0; index < pages.length; index++) {
                if (index > 0) nextPage(destination);
                if (!pages[index].isBlank()) emit(destination, pages[index], type, range, false);
            }
            return;
        }
        int line = outputLine;
        append(destination, text.stripTrailing() + "\n\n");
        if (!note) {
            int lines = (int) text.stripTrailing().chars().filter(c -> c == '\n').count();
            workspace.block(Map.of("type", type, "section", section, "range", range,
                    "markdownStartLine", line, "markdownEndLine", line + lines));
            outputLine += lines + 2;
            documentContent = true;
        }
    }

    private void nextPage(StringBuilder destination) {
        // A break-before marker on the first paragraph still starts page 1.
        if (!documentContent && pageNumber == 1) return;
        pageNumber++;
        append(destination, "[page " + pageNumber + "]\n\n");
        outputLine += 2;
    }

    /** Checks the table-cell limit for a table that is still being read. */
    void checkTableWidth(long width, int rows, boolean header) {
        workspace.limits().checkTableCells(tableCells + width * (rows + (header ? 0L : 1L)));
    }

    /** A Markdown table of already rendered cells; without a header row the header line is left empty. */
    String table(List<List<String>> values, int width, boolean header) {
        tableCells += (long) width * (values.size() + (header ? 0 : 1));
        workspace.limits().checkTableCells(tableCells);
        StringBuilder out = new StringBuilder();
        int start = 0;
        if (header) { tableRow(out, values.getFirst(), width); start = 1; }
        else tableRow(out, List.of(), width);
        append(out, "|" + " --- |".repeat(width) + "\n");
        for (int i = start; i < values.size(); i++) tableRow(out, values.get(i), width);
        return out.toString().stripTrailing();
    }

    private void tableRow(StringBuilder out, List<String> cells, int width) {
        append(out, "|");
        for (int i = 0; i < width; i++) append(out, " " + (i < cells.size() ? cells.get(i) : "") + " |");
        append(out, "\n");
    }

    void append(StringBuilder target, String value) {
        if ((long) target.length() + value.length() > workspace.limits().maxMarkdownBytes())
            throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdown のサイズが上限を超えています。");
        target.append(value);
    }

    /** Reports a notice once per code and range. */
    void notice(String code, String range, String message, boolean info) {
        if (!noticeKeys.add(code + "|" + range)) return;
        if (info) workspace.info(code, section, range, message);
        else workspace.warning(code, section, range, message);
    }
}
