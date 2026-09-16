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

    static Content read(XSLFTextShape shape, IntConsumer count) {
        StringBuilder plain = new StringBuilder(), markdown = new StringBuilder();
        List<Link> links = new ArrayList<>();
        Map<Integer, Integer> numbers = new HashMap<>();
        Map<Integer, AutoNumberingScheme> schemes = new HashMap<>();
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            count.accept(1);
            StringBuilder text = new StringBuilder(), md = new StringBuilder();
            for (XSLFTextRun run : List.copyOf(paragraph.getTextRuns())) {
                String value = Objects.toString(run.getRawText(), "");
                count.accept(Math.max(1, value.length()));
                if (run.isStrikethrough() || paragraph.isHeaderOrFooter()) {
                    paragraph.removeTextRun(run);
                    continue;
                }
                value = value.replace('\u000b', '\n').replace("\r\n", "\n").replace('\r', '\n');
                text.append(value);
                String escaped = Markdown.escape(value);
                if (run.isBold()) escaped = Markdown.bold(escaped);
                XSLFHyperlink link = run.getHyperlink();
                if (link != null && Markdown.safeLink(link.getAddress())) {
                    links.add(new Link(value, link.getAddress()));
                    escaped = Markdown.link(escaped, link.getAddress());
                }
                md.append(escaped);
            }
            int level = Math.min(8, Math.max(0, paragraph.getIndentLevel()));
            AutoNumberingScheme scheme = paragraph.getAutoNumberingScheme();
            String prefix = "";
            if (scheme != null) {
                boolean explicitStart = paragraph.getXmlObject().isSetPPr()
                        && paragraph.getXmlObject().getPPr().isSetBuAutoNum()
                        && paragraph.getXmlObject().getPPr().getBuAutoNum().isSetStartAt();
                int first = Objects.requireNonNullElse(paragraph.getAutoNumberingStartAt(), 1);
                int n = explicitStart || schemes.get(level) != scheme ? first : numbers.getOrDefault(level, first);
                numbers.put(level, n == Integer.MAX_VALUE ? n : n + 1);
                schemes.put(level, scheme);
                numbers.keySet().removeIf(key -> key > level);
                // Escaped literal numbering keeps the saved numbering instead of Markdown renumbering.
                prefix = Markdown.escape(scheme.format(n)) + " ";
            } else if (paragraph.isBullet()) {
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
        XSLFHyperlink shapeLink = shape instanceof XSLFTableCell ? null : shape.getHyperlink();
        if (shapeLink != null && Markdown.safeLink(shapeLink.getAddress()))
            links.add(new Link(plain.isEmpty() ? "リンク" : plain.toString(), shapeLink.getAddress()));
        return new Content(plain.toString(), markdown.toString(), List.copyOf(links));
    }
    private PresentationText() { }
}
