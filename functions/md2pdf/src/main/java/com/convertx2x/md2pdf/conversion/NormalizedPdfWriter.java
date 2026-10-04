package com.convertx2x.md2pdf.conversion;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.util.Matrix;

/** Pure-Java flow layout. Text and table cells remain searchable across page breaks. */
final class NormalizedPdfWriter implements AutoCloseable {
    private static final float MARGIN = 36f;
    private static final float FOOTER = 18f;
    private static final float BODY_SIZE = 10f;
    private static final float LINE_HEIGHT = 14f;
    private static final int TABLE_COLUMNS_PER_SLICE = 8;
    private static final Color INK = new Color(30, 49, 40);
    private final PDDocument document = new PDDocument();
    private final ConversionLimits limits;
    private final Consumer<String> warning;
    private final PDType0Font regular;
    private final PDType0Font bold;
    private PDPage page;
    private PDPageContentStream stream;
    private float y;
    private int pageCount;

    record TextSpan(String text, boolean strong, boolean emphasis, boolean code, String link) {
        static TextSpan plain(String text) { return new TextSpan(text, false, false, false, null); }
    }
    private record Glyph(String text, TextSpan style, float width) { }

    NormalizedPdfWriter(ConversionLimits limits, Consumer<String> warning) throws IOException {
        this.limits = Objects.requireNonNull(limits);
        this.warning = Objects.requireNonNull(warning);
        try (InputStream normal = resource("/fonts/bizud/BIZUDPGothic-Regular.ttf");
             InputStream strong = resource("/fonts/bizud/BIZUDPGothic-Bold.ttf")) {
            regular = PDType0Font.load(document, normal, true);
            bold = PDType0Font.load(document, strong, true);
        }
    }

    int pageCount() { return pageCount; }

    void heading(String text, int level) throws IOException {
        richHeading(List.of(TextSpan.plain(text)), level, 0, false);
    }

    void richHeading(List<TextSpan> spans, int level, int depth, boolean quote) throws IOException {
        float fontSize = switch (Math.max(1, Math.min(6, level))) {
            case 1 -> 20f; case 2 -> 17f; case 3 -> 14f; case 4 -> 12f; default -> 11f;
        };
        ensureSpace(fontSize * 2.4f);
        y -= fontSize * .3f;
        List<TextSpan> strong = spans.stream().map(s -> new TextSpan(s.text(), true, s.emphasis(), s.code(), s.link())).toList();
        flow(strong, fontSize, fontSize * 1.45f, depth, quote);
        y -= 5f;
    }

    void paragraph(String text, boolean strong) throws IOException {
        richParagraph(List.of(new TextSpan(text, strong, false, false, null)), 0, false);
    }

    void richParagraph(List<TextSpan> spans, int depth, boolean quote) throws IOException {
        flow(spans, BODY_SIZE, LINE_HEIGHT, depth, quote);
        y -= 5f;
    }

    private void flow(List<TextSpan> spans, float fontSize, float lineHeight, int depth, boolean quote) throws IOException {
        float inset = indent(depth) + (quote ? 14f : 0f);
        float maximum = contentWidth() - inset;
        List<Glyph> line = new ArrayList<>();
        float width = 0;
        boolean any = false;
        for (TextSpan span : spans) {
            PDType0Font font = span.strong() ? bold : regular;
            String text = glyphSafe(font, normalize(span.text()));
            for (int offset = 0; offset < text.length();) {
                int point = text.codePointAt(offset);
                offset += Character.charCount(point);
                if (point == '\n') {
                    drawFlowLine(line, fontSize, lineHeight, inset, quote);
                    line.clear();
                    width = 0;
                    any = true;
                    continue;
                }
                String character = new String(Character.toChars(point));
                float advance = textWidth(font, fontSize, character);
                if (!line.isEmpty() && width + advance > maximum) {
                    drawFlowLine(line, fontSize, lineHeight, inset, quote);
                    line.clear();
                    width = 0;
                }
                line.add(new Glyph(character, span, advance));
                width += advance;
                any = true;
            }
        }
        if (!line.isEmpty()) drawFlowLine(line, fontSize, lineHeight, inset, quote);
        else if (!any) { ensureSpace(lineHeight); y -= lineHeight / 2f; }
    }

    private void drawFlowLine(List<Glyph> line, float fontSize, float lineHeight, float inset, boolean quote) throws IOException {
        ensureSpace(lineHeight);
        if (quote) {
            stream.setStrokingColor(new Color(170, 180, 173));
            stream.setLineWidth(2f);
            stream.moveTo(MARGIN + inset - 9f, y + fontSize);
            stream.lineTo(MARGIN + inset - 9f, y - lineHeight + fontSize);
            stream.stroke();
        }
        float x = MARGIN + inset;
        for (int start = 0; start < line.size();) {
            TextSpan style = line.get(start).style();
            StringBuilder value = new StringBuilder();
            float width = 0;
            int end = start;
            while (end < line.size() && line.get(end).style().equals(style)) {
                value.append(line.get(end).text());
                width += line.get(end).width();
                end++;
            }
            if (style.code()) {
                stream.setNonStrokingColor(new Color(241, 243, 244));
                stream.addRect(x, y - 2f, width, fontSize + 3f);
                stream.fill();
            }
            textLine(value.toString(), x, y, style.strong() ? bold : regular, fontSize,
                    style.link() == null ? Color.BLACK : new Color(26, 91, 159), style.emphasis());
            if (safeLink(style.link()) && width > 0) addLink(style.link(), x, y - 2f, width, fontSize + 3f);
            x += width;
            start = end;
        }
        y -= lineHeight;
    }

    void code(String text, int depth, boolean quote) throws IOException {
        float inset = indent(depth) + (quote ? 14f : 0f);
        // Expand tabs to four-column stops while preserving spaces and every logical line.
        String expanded = expandTabs(normalize(text));
        if (expanded.endsWith("\n")) expanded = expanded.substring(0, expanded.length() - 1);
        for (String logical : expanded.split("\n", -1)) {
            for (String line : wrap(logical, regular, 9f, contentWidth() - inset - 12f)) {
                ensureSpace(13f);
                stream.setNonStrokingColor(new Color(241, 243, 244));
                stream.addRect(MARGIN + inset, y - 3f, contentWidth() - inset, 13f);
                stream.fill();
                textLine(line, MARGIN + inset + 6f, y, regular, 9f, INK, false);
                y -= 13f;
            }
        }
        y -= 7f;
    }

    void horizontalRule(int depth) throws IOException {
        ensureSpace(18f);
        stream.setStrokingColor(new Color(170, 180, 173));
        stream.setLineWidth(.7f);
        stream.moveTo(MARGIN + indent(depth), y - 5f);
        stream.lineTo(PDRectangle.A4.getWidth() - MARGIN, y - 5f);
        stream.stroke();
        y -= 18f;
    }

    /** Defer creation so a trailing page-break marker does not add an empty PDF page. */
    void pageBreak() throws IOException { closePage(); }

    void table(String title, List<List<String>> rows) throws IOException {
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) return;
        if (title != null && !title.isBlank()) heading(title, 2);
        for (int start = 0; start < columns; start += TABLE_COLUMNS_PER_SLICE) {
            int end = Math.min(columns, start + TABLE_COLUMNS_PER_SLICE);
            if (start > 0) pageBreak();
            if (columns > TABLE_COLUMNS_PER_SLICE) paragraph("列 " + (start + 1) + "–" + end, true);
            drawTableSlice(rows, start, end);
            // Leave enough room for the ascent of a heading immediately after the table.
            y -= 20f;
        }
    }

    private void drawTableSlice(List<List<String>> rows, int start, int end) throws IOException {
        ensureSpace(25f);
        float cellWidth = contentWidth() / (end - start);
        List<List<String>> header = cellLinesFor(rows.getFirst(), start, end, cellWidth, true);
        int headerLines = maximumLines(header);
        // Repeat a reasonably sized header. Large headers themselves flow across pages once.
        boolean repeatHeader = headerLines <= 8;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<List<String>> cells = rowIndex == 0 ? header : cellLinesFor(rows.get(rowIndex), start, end, cellWidth, false);
            int count = maximumLines(cells);
            int consumed = 0;
            while (consumed < count) {
                int available = (int) ((y - MARGIN - FOOTER - 8f) / 11f);
                if (stream == null || available < 1) {
                    newPage();
                    if (rowIndex > 0 && repeatHeader) drawTableRows(header, 0, headerLines, cellWidth, true);
                    available = (int) ((y - MARGIN - FOOTER - 8f) / 11f);
                }
                int chunk = Math.min(count - consumed, Math.max(1, available));
                drawTableRows(cells, consumed, chunk, cellWidth, rowIndex == 0);
                consumed += chunk;
                if (consumed < count) {
                    newPage();
                    if (rowIndex > 0 && repeatHeader) drawTableRows(header, 0, headerLines, cellWidth, true);
                }
            }
        }
    }

    private List<List<String>> cellLinesFor(List<String> row, int start, int end, float cellWidth, boolean header) throws IOException {
        List<List<String>> result = new ArrayList<>();
        for (int column = start; column < end; column++) {
            String value = column < row.size() ? row.get(column) : "";
            result.add(wrap(value, header ? bold : regular, 8f, cellWidth - 8f));
        }
        return result;
    }

    private int maximumLines(List<List<String>> cells) { return cells.stream().mapToInt(List::size).max().orElse(1); }

    private void drawTableRows(List<List<String>> cells, int from, int count, float cellWidth, boolean header) throws IOException {
        float height = 8f + count * 11f;
        float x = MARGIN;
        for (List<String> lines : cells) {
            if (header) {
                stream.setNonStrokingColor(new Color(239, 244, 240));
                stream.addRect(x, y - height, cellWidth, height);
                stream.fill();
            }
            stream.setStrokingColor(new Color(170, 180, 173));
            stream.setLineWidth(.5f);
            stream.addRect(x, y - height, cellWidth, height);
            stream.stroke();
            float baseline = y - 12f;
            for (int index = from; index < Math.min(lines.size(), from + count); index++) {
                textLine(lines.get(index), x + 4f, baseline, header ? bold : regular, 8f, Color.BLACK, false);
                baseline -= 11f;
            }
            x += cellWidth;
        }
        y -= height;
    }

    void image(BufferedImage image, String caption) throws IOException {
        if (image == null) return;
        if ((long) image.getWidth() * image.getHeight() > limits.maxImagePixels()) {
            warning.accept("画素数が大きすぎる画像をPDFへ配置できませんでした。");
            if (caption != null && !caption.isBlank()) paragraph(caption, false);
            return;
        }
        float maximumHeight = PDRectangle.A4.getHeight() - MARGIN * 2f - FOOTER - BODY_SIZE;
        float scale = Math.min(1f, Math.min(contentWidth() / image.getWidth(), maximumHeight / image.getHeight()));
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        ensureSpace(drawHeight + 10f);
        stream.drawImage(LosslessFactory.createFromImage(document, image), MARGIN, y - drawHeight, drawWidth, drawHeight);
        y -= drawHeight + 10f;
        if (caption != null && !caption.isBlank()) paragraph(caption, false);
    }

    byte[] finish() throws IOException {
        if (pageCount == 0) newPage();
        closePage();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.save(bytes);
            if (bytes.size() > limits.maxOutputBytes())
                throw ConversionWorkspace.limit("OUTPUT_BYTES_LIMIT", "PDFのサイズが上限を超えました。");
            return bytes.toByteArray();
        }
    }

    private void ensureSpace(float required) throws IOException {
        if (stream == null || y - required < MARGIN + FOOTER) newPage();
    }

    private void newPage() throws IOException {
        closePage();
        if (ConversionLimits.exceeds(pageCount + 1, limits.maxPages()))
            throw ConversionWorkspace.limit("PAGE_LIMIT", "生成されたPDFのページ数が上限を超えました。");
        page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        pageCount++;
        y = PDRectangle.A4.getHeight() - MARGIN - BODY_SIZE;
    }

    private void closePage() throws IOException {
        if (stream == null) return;
        String number = "[page " + pageCount + "]";
        textLine(number, (PDRectangle.A4.getWidth() - textWidth(regular, 8f, number)) / 2f, 24f,
                regular, 8f, new Color(100, 100, 100), false);
        stream.close();
        stream = null;
    }

    private float contentWidth() { return PDRectangle.A4.getWidth() - MARGIN * 2f; }
    private float indent(int depth) { return Math.min(Math.max(0, depth) * 16f, contentWidth() * .4f); }

    private void textLine(String value, float x, float baseline, PDType0Font font, float fontSize, Color color, boolean italic) throws IOException {
        stream.beginText();
        stream.setNonStrokingColor(color);
        stream.setFont(font, fontSize);
        stream.setTextMatrix(new Matrix(1, 0, italic ? .2f : 0, 1, x, baseline));
        stream.showText(glyphSafe(font, value));
        stream.endText();
    }

    private void addLink(String destination, float x, float baseline, float width, float height) throws IOException {
        PDAnnotationLink annotation = new PDAnnotationLink();
        annotation.setRectangle(new PDRectangle(x, baseline, width, height));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        annotation.setBorderStyle(border);
        PDActionURI action = new PDActionURI();
        action.setURI(destination);
        annotation.setAction(action);
        page.getAnnotations().add(annotation);
    }

    private static boolean safeLink(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:");
    }

    private List<String> wrap(String value, PDType0Font font, float fontSize, float maximum) throws IOException {
        String safe = glyphSafe(font, normalize(value));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        float width = 0;
        for (int offset = 0; offset < safe.length();) {
            int point = safe.codePointAt(offset);
            offset += Character.charCount(point);
            if (point == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                width = 0;
                continue;
            }
            String character = new String(Character.toChars(point));
            float advance = textWidth(font, fontSize, character);
            if (!line.isEmpty() && width + advance > maximum) {
                lines.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            line.append(character);
            width += advance;
        }
        lines.add(line.toString());
        return lines;
    }

    private static float textWidth(PDType0Font font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        text.replace("\r\n", "\n").replace('\r', '\n').codePoints().forEach(point -> {
            if (point == '\n' || point == '\t' || !Character.isISOControl(point)) result.appendCodePoint(point);
        });
        return result.toString().replace('\u00a0', ' ');
    }

    private static String expandTabs(String text) {
        StringBuilder result = new StringBuilder();
        int column = 0;
        for (int point : text.codePoints().toArray()) {
            if (point == '\t') {
                int count = 4 - column % 4;
                result.append(" ".repeat(count));
                column += count;
            } else {
                result.appendCodePoint(point);
                column = point == '\n' ? 0 : column + 1;
            }
        }
        return result.toString();
    }

    private static String glyphSafe(PDType0Font font, String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            // Line breaks are layout instructions, not missing font glyphs.
            if (point == '\n') { result.append('\n'); return; }
            String character = point == '\t' ? "    " : new String(Character.toChars(point));
            try { font.getStringWidth(character); result.append(character); }
            catch (IOException | IllegalArgumentException failure) { result.append('\u25a1'); }
        });
        return result.toString();
    }

    private static InputStream resource(String name) throws IOException {
        InputStream stream = NormalizedPdfWriter.class.getResourceAsStream(name);
        if (stream == null) throw new IOException("Missing bundled font resource: " + name);
        return stream;
    }

    @Override public void close() throws IOException {
        closePage();
        document.close();
    }
}
