package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.Markdown;
import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.xslf.usermodel.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Reads saved text only; sanitizes the in-memory drawing model before it can be rasterized. */
final class PresentationText {
    record Link(String label, String address) { }
    record Content(String plain, String markdown, List<Link> links) {
        static final Content EMPTY = new Content("", "", List.of());
    }

    /** One kept run of a paragraph; {@code link} is a safe address or null. */
    record Run(String text, boolean bold, String link) { }
    /** A saved paragraph. {@code startAt} is read only for numbered paragraphs and {@code bullet} only for the others. */
    record Paragraph(List<Run> runs, int indentLevel, AutoNumberingScheme scheme, boolean explicitStart,
                     Integer startAt, boolean bullet) { }

    static Content read(XSLFTextShape shape, IntConsumer count) {
        List<Paragraph> paragraphs = new ArrayList<>();
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            count.accept(1);
            List<Run> runs = new ArrayList<>();
            for (XSLFTextRun run : List.copyOf(paragraph.getTextRuns())) {
                String value = Objects.toString(run.getRawText(), "");
                count.accept(Math.max(1, value.length()));
                if (run.isStrikethrough() || paragraph.isHeaderOrFooter()) {
                    paragraph.removeTextRun(run);
                    continue;
                }
                XSLFHyperlink link = run.getHyperlink();
                runs.add(new Run(value, run.isBold(), link != null && Markdown.safeLink(link.getAddress()) ? link.getAddress() : null));
            }
            AutoNumberingScheme scheme = paragraph.getAutoNumberingScheme();
            boolean explicitStart = scheme != null && paragraph.getXmlObject().isSetPPr()
                    && paragraph.getXmlObject().getPPr().isSetBuAutoNum()
                    && paragraph.getXmlObject().getPPr().getBuAutoNum().isSetStartAt();
            paragraphs.add(new Paragraph(runs, paragraph.getIndentLevel(), scheme, explicitStart,
                    scheme != null ? paragraph.getAutoNumberingStartAt() : null, scheme == null && paragraph.isBullet()));
        }
        XSLFHyperlink shapeLink = shape instanceof XSLFTableCell ? null : shape.getHyperlink();
        return assemble(paragraphs, shapeLink != null && Markdown.safeLink(shapeLink.getAddress()) ? shapeLink.getAddress() : null);
    }

    /**
     * Markdown and plain text of saved paragraphs, with list markers and literal numbering.
     * Every presentation format reads its paragraphs into {@link Paragraph} and shares this assembly.
     * {@code shapeLink} is the safe address of a link on the whole shape, or null.
     */
    static Content assemble(List<Paragraph> paragraphs, String shapeLink) {
        StringBuilder plain = new StringBuilder(), markdown = new StringBuilder();
        List<Link> links = new ArrayList<>();
        Map<Integer, Integer> numbers = new HashMap<>();
        Map<Integer, AutoNumberingScheme> schemes = new HashMap<>();
        for (Paragraph paragraph : paragraphs) {
            StringBuilder text = new StringBuilder(), md = new StringBuilder();
            for (Run run : paragraph.runs()) {
                String value = run.text().replace('\u000b', '\n').replace("\r\n", "\n").replace('\r', '\n');
                text.append(value);
                String escaped = Markdown.escape(value);
                if (run.bold()) escaped = Markdown.bold(escaped);
                if (run.link() != null) {
                    links.add(new Link(value, run.link()));
                    escaped = Markdown.link(escaped, run.link());
                }
                md.append(escaped);
            }
            int level = Math.min(8, Math.max(0, paragraph.indentLevel()));
            AutoNumberingScheme scheme = paragraph.scheme();
            String prefix = "";
            if (scheme != null) {
                int first = Objects.requireNonNullElse(paragraph.startAt(), 1);
                int n = paragraph.explicitStart() || schemes.get(level) != scheme ? first : numbers.getOrDefault(level, first);
                numbers.put(level, n == Integer.MAX_VALUE ? n : n + 1);
                schemes.put(level, scheme);
                numbers.keySet().removeIf(key -> key > level);
                // Escaped literal numbering keeps the saved numbering instead of Markdown renumbering.
                prefix = Markdown.escape(scheme.format(n)) + " ";
            } else if (paragraph.bullet()) {
                prefix = "- ";
                numbers.remove(level);
                schemes.remove(level);
            } else {
                numbers.clear(); schemes.clear();
            }
            if (text.toString().isBlank()) continue;
            if (!plain.isEmpty()) plain.append('\n');
            plain.append(text);
            if (!markdown.isEmpty()) markdown.append('\n');
            markdown.append("  ".repeat(level)).append(prefix).append(md.toString().replace("\n", "  \n"));
        }
        if (shapeLink != null) links.add(new Link(plain.isEmpty() ? "リンク" : plain.toString(), shapeLink));
        return new Content(plain.toString(), markdown.toString(), List.copyOf(links));
    }
    private PresentationText() { }
}
