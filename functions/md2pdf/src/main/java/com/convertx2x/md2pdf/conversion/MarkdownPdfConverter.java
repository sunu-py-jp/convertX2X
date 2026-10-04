package com.convertx2x.md2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** CommonMark plus GFM tables, rendered without a browser or external executable. */
final class MarkdownPdfConverter {
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

    void convert(String markdown, NormalizedPdfWriter pdf, ConversionWorkspace workspace,
                 Function<String, BufferedImage> images) throws IOException {
        workspace.sectionIncluded();
        Node document = PARSER.parse(markdown);
        Renderer renderer = new Renderer(pdf, workspace, images);
        renderer.prepareLayout(document);
        renderer.blocks(document, 0, false);
    }

    private record ImagePart(String destination, String alt) { }
    private record BreakPart() { }
    private record TableData(List<List<String>> rows, List<ImagePart> images) { }
    private record Style(boolean strong, boolean emphasis, boolean code, String link) {
        static Style plain() { return new Style(false, false, false, null); }
        NormalizedPdfWriter.TextSpan span(String text) {
            return new NormalizedPdfWriter.TextSpan(text, strong, emphasis, code, link);
        }
    }

    private static final class Renderer {
        private final NormalizedPdfWriter pdf;
        private final ConversionWorkspace workspace;
        private final Function<String, BufferedImage> images;
        private final Map<TableBlock, TableData> tables = new IdentityHashMap<>();
        private boolean warnedHtml;
        private boolean warnedMermaid;

        Renderer(NormalizedPdfWriter pdf, ConversionWorkspace workspace, Function<String, BufferedImage> images) {
            this.pdf = pdf;
            this.workspace = workspace;
            this.images = images;
        }

        void prepareLayout(Node parent) throws IOException {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TableBlock table) {
                    List<List<String>> rows = new ArrayList<>();
                    List<ImagePart> deferredImages = new ArrayList<>();
                    collectRows(table, rows, deferredImages);
                    tables.put(table, new TableData(rows, deferredImages));
                    pdf.prepareTable(rows);
                } else prepareLayout(child);
            }
        }

        void blocks(Node parent, int depth, boolean quote) throws IOException {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) block(child, depth, quote);
        }

        private void block(Node node, int depth, boolean quote) throws IOException {
            if (node instanceof Heading heading) {
                renderInline(heading, depth, quote, heading.getLevel(), null);
            } else if (node instanceof Paragraph paragraph) {
                renderInline(paragraph, depth, quote, 0, null);
            } else if (node instanceof BlockQuote) {
                blocks(node, depth + (quote ? 1 : 0), true);
            } else if (node instanceof BulletList || node instanceof OrderedList) {
                list(node, depth, quote);
            } else if (node instanceof FencedCodeBlock code) {
                String language = code.getInfo() == null ? "" : code.getInfo().strip().split("\\s+", 2)[0];
                if (language.toLowerCase(Locale.ROOT).equals("mermaid") && !warnedMermaid) {
                    workspace.warning("MERMAID_AS_CODE", null, "Mermaidは図へ変換せず、コードとしてPDFに表示しました。");
                    warnedMermaid = true;
                }
                pdf.code(code.getLiteral(), depth, quote);
            } else if (node instanceof IndentedCodeBlock code) {
                pdf.code(code.getLiteral(), depth, quote);
            } else if (node instanceof ThematicBreak) {
                pdf.horizontalRule(depth);
            } else if (node instanceof HtmlBlock html) {
                if (isPageBreak(html.getLiteral())) pdf.pageBreak();
                else { warnHtml(); pdf.code(html.getLiteral(), depth, quote); }
            } else if (node instanceof TableBlock table) {
                table(table);
            } else {
                blocks(node, depth, quote);
            }
        }

        private void list(Node list, int depth, boolean quote) throws IOException {
            int number = list instanceof OrderedList ordered && ordered.getMarkerStartNumber() != null
                    ? ordered.getMarkerStartNumber() : 1;
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                String marker = list instanceof OrderedList ? number++ + ". " : "• ";
                Node first = item.getFirstChild();
                if (first instanceof Paragraph) renderInline(first, depth + 1, quote, 0, marker);
                else pdf.richParagraph(List.of(NormalizedPdfWriter.TextSpan.plain(marker)), depth + 1, quote);
                for (Node child = first; child != null; child = child.getNext()) {
                    if (child != first || !(first instanceof Paragraph)) block(child, depth + 1, quote);
                }
            }
        }

        private void renderInline(Node parent, int depth, boolean quote, int heading, String prefix) throws IOException {
            List<Object> parts = new ArrayList<>();
            if (prefix != null) parts.add(NormalizedPdfWriter.TextSpan.plain(prefix));
            inlines(parent, Style.plain(), parts);
            List<NormalizedPdfWriter.TextSpan> pending = new ArrayList<>();
            for (Object part : parts) {
                if (part instanceof NormalizedPdfWriter.TextSpan span) pending.add(span);
                else {
                    flush(pending, depth, quote, heading);
                    if (part instanceof ImagePart image) renderImage(image, depth, quote);
                    else if (part instanceof BreakPart) pdf.pageBreak();
                }
            }
            flush(pending, depth, quote, heading);
        }

        private void flush(List<NormalizedPdfWriter.TextSpan> pending, int depth, boolean quote, int heading) throws IOException {
            if (pending.isEmpty()) return;
            if (heading > 0) pdf.richHeading(pending, heading, depth, quote);
            else pdf.richParagraph(pending, depth, quote);
            pending.clear();
        }

        private void inlines(Node parent, Style style, List<Object> parts) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                if (node instanceof Text text) parts.add(style.span(text.getLiteral()));
                else if (node instanceof Code code) parts.add(new Style(style.strong(), style.emphasis(), true, style.link()).span(code.getLiteral()));
                else if (node instanceof SoftLineBreak) parts.add(style.span(" "));
                else if (node instanceof HardLineBreak) parts.add(style.span("\n"));
                else if (node instanceof StrongEmphasis) inlines(node, new Style(true, style.emphasis(), style.code(), style.link()), parts);
                else if (node instanceof Emphasis) inlines(node, new Style(style.strong(), true, style.code(), style.link()), parts);
                else if (node instanceof Link link) inlines(node, new Style(style.strong(), style.emphasis(), style.code(), link.getDestination()), parts);
                else if (node instanceof Image image) parts.add(new ImagePart(image.getDestination(), plainText(image)));
                else if (node instanceof HtmlInline html) {
                    if (isPageBreak(html.getLiteral())) parts.add(new BreakPart());
                    else if (isLineBreak(html.getLiteral())) parts.add(style.span("\n"));
                    else { warnHtml(); parts.add(style.span(html.getLiteral())); }
                } else inlines(node, style, parts);
            }
        }

        private void renderImage(ImagePart image, int depth, boolean quote) throws IOException {
            BufferedImage decoded = images.apply(image.destination());
            if (decoded != null) pdf.image(decoded, image.alt());
            else pdf.richParagraph(List.of(NormalizedPdfWriter.TextSpan.plain(
                    image.alt().isBlank() ? "[画像: " + image.destination() + "]" : image.alt())), depth, quote);
        }

        private void table(TableBlock table) throws IOException {
            TableData data = tables.get(table);
            List<ImagePart> deferredImages = data.images();
            pdf.table(null, data.rows());
            if (!deferredImages.isEmpty()) {
                workspace.warning("TABLE_IMAGES_AFTER", null, "表の画像はセル内に代替テキストを残し、表の後に表示しました。");
                for (ImagePart image : deferredImages) {
                    renderImage(image, 0, false);
                }
            }
        }

        private void collectRows(Node parent, List<List<String>> rows, List<ImagePart> imagesInTable) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                if (node instanceof TableRow) {
                    List<String> cells = new ArrayList<>();
                    for (Node cell = node.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (!(cell instanceof TableCell)) continue;
                        List<Object> parts = new ArrayList<>();
                        inlines(cell, Style.plain(), parts);
                        StringBuilder value = new StringBuilder();
                        for (Object part : parts) {
                            if (part instanceof NormalizedPdfWriter.TextSpan span) value.append(span.text());
                            else if (part instanceof ImagePart image) {
                                value.append(image.alt().isBlank() ? "[画像]" : image.alt());
                                imagesInTable.add(image);
                            } else if (part instanceof BreakPart) value.append('\n');
                        }
                        cells.add(value.toString());
                    }
                    rows.add(cells);
                } else collectRows(node, rows, imagesInTable);
            }
        }

        private String plainText(Node parent) {
            StringBuilder value = new StringBuilder();
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                if (node instanceof Text text) value.append(text.getLiteral());
                else if (node instanceof Code code) value.append(code.getLiteral());
                else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) value.append(' ');
                else value.append(plainText(node));
            }
            return value.toString();
        }

        private void warnHtml() {
            if (warnedHtml) return;
            workspace.warning("HTML_LITERAL", null, "HTML/CSSは実行せず、HTMLの記述を文字としてPDFに表示しました。");
            warnedHtml = true;
        }

        private boolean isPageBreak(String text) { return text != null && text.strip().equals("<!-- pagebreak -->"); }

        private boolean isLineBreak(String text) {
            return text != null && (text.equalsIgnoreCase("<br>")
                    || text.equalsIgnoreCase("<br/>") || text.equalsIgnoreCase("<br />"));
        }
    }
}
