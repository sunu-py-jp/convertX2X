package com.convertx2x.office2pdf.conversion;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Small pure-Java paginator optimized for searchable text and simple table recognition. */
final class NormalizedPdfWriter implements AutoCloseable {
    private static final float MARGIN = 36f;
    private static final float FOOTER = 18f;
    private static final float BODY_SIZE = 10f;
    private static final float LINE_HEIGHT = 14f;
    private static final int TABLE_COLUMNS_PER_SLICE = 8;
    private static final PDRectangle A4_LANDSCAPE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private final PDDocument document = new PDDocument();
    private final ConversionLimits limits;
    private final Consumer<String> warning;
    private final PDType0Font regular;
    private final PDType0Font bold;
    private PDPage page;
    private PDPageContentStream stream;
    private PDRectangle size;
    private float y;
    private int pageCount;

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
        float fontSize = switch (Math.max(1, Math.min(3, level))) { case 1 -> 18f; case 2 -> 14f; default -> 12f; };
        ensureSpace(fontSize * 2.2f, PDRectangle.A4);
        y -= fontSize * .35f;
        for (String line : wrap(text, bold, fontSize, contentWidth())) {
            ensureSpace(fontSize * 1.45f, PDRectangle.A4);
            textLine(line, MARGIN, y, bold, fontSize, new Color(30, 49, 40));
            y -= fontSize * 1.45f;
        }
        y -= 4f;
    }

    void paragraph(String text, boolean strong) throws IOException {
        String normalized = normalize(text);
        ensureSpace(LINE_HEIGHT, PDRectangle.A4);
        if (normalized.isBlank()) { y -= LINE_HEIGHT / 2f; return; }
        PDType0Font font = strong ? bold : regular;
        for (String logical : normalized.split("\\R", -1)) {
            if (logical.isBlank()) { y -= LINE_HEIGHT / 2f; continue; }
            for (String line : wrap(logical, font, BODY_SIZE, contentWidth())) {
                ensureSpace(LINE_HEIGHT, PDRectangle.A4);
                textLine(line, MARGIN, y, font, BODY_SIZE, Color.BLACK);
                y -= LINE_HEIGHT;
            }
        }
        y -= 5f;
    }

    void pageBreak() throws IOException { newPage(PDRectangle.A4); }

    void table(String title, List<List<String>> rows) throws IOException {
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) return;
        for (int start = 0; start < columns; start += TABLE_COLUMNS_PER_SLICE) {
            int end = Math.min(columns, start + TABLE_COLUMNS_PER_SLICE);
            newPage(A4_LANDSCAPE);
            heading(title + (columns > TABLE_COLUMNS_PER_SLICE
                    ? "（列 " + (start + 1) + "–" + end + "）" : ""), 2);
            drawTableSlice(rows, start, end);
        }
    }

    private void drawTableSlice(List<List<String>> rows, int start, int end) throws IOException {
        float cellWidth = contentWidth() / (end - start);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<List<String>> cellLines = new ArrayList<>();
            int lineCount = 1;
            for (int column = start; column < end; column++) {
                String value = column < rows.get(rowIndex).size() ? rows.get(rowIndex).get(column) : "";
                List<String> lines = wrap(value, rowIndex == 0 ? bold : regular, 8f, cellWidth - 8f);
                if (lines.size() > 6) {
                    lines = new ArrayList<>(lines.subList(0, 6));
                    lines.set(5, ellipsis(lines.get(5)));
                    warning.accept("表の長いセルをPDF上で省略しました。値全体は元のOfficeファイルに残っています。");
                }
                cellLines.add(lines);
                lineCount = Math.max(lineCount, lines.size());
            }
            float rowHeight = Math.max(18f, 8f + lineCount * 10f);
            if (y - rowHeight < MARGIN + FOOTER) {
                newPage(A4_LANDSCAPE);
                if (rowIndex > 1) drawTableRow(cellLinesFor(rows.getFirst(), start, end, cellWidth), start, end, cellWidth, 0, true);
            }
            drawTableRow(cellLines, start, end, cellWidth, rowHeight, rowIndex == 0);
        }
    }

    private List<List<String>> cellLinesFor(List<String> row, int start, int end, float cellWidth) throws IOException {
        List<List<String>> result = new ArrayList<>();
        for (int column = start; column < end; column++) {
            String value = column < row.size() ? row.get(column) : "";
            List<String> lines = wrap(value, bold, 8f, cellWidth - 8f);
            result.add(lines.size() > 6 ? lines.subList(0, 6) : lines);
        }
        return result;
    }

    private void drawTableRow(List<List<String>> cells, int start, int end, float cellWidth,
                              float requestedHeight, boolean header) throws IOException {
        int count = cells.stream().mapToInt(List::size).max().orElse(1);
        float height = requestedHeight > 0 ? requestedHeight : Math.max(18f, 8f + count * 10f);
        float x = MARGIN;
        for (int index = 0; index < end - start; index++) {
            if (header) {
                stream.setNonStrokingColor(new Color(239, 244, 240));
                stream.addRect(x, y - height, cellWidth, height);
                stream.fill();
            }
            stream.setStrokingColor(new Color(170, 180, 173));
            stream.setLineWidth(.5f);
            stream.addRect(x, y - height, cellWidth, height);
            stream.stroke();
            float lineY = y - 11f;
            for (String line : cells.get(index)) {
                textLine(line, x + 4f, lineY, header ? bold : regular, 8f, Color.BLACK);
                lineY -= 10f;
            }
            x += cellWidth;
        }
        y -= height;
    }

    void image(BufferedImage image, String caption) throws IOException {
        if (image == null) return;
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels > limits.maxImagePixels()) {
            warning.accept("画素数が大きすぎる画像をPDFへ配置できませんでした。");
            return;
        }
        if (caption != null && !caption.isBlank()) paragraph(caption, true);
        ensureSpace(100f, PDRectangle.A4);
        float width = contentWidth();
        float available = y - MARGIN - FOOTER;
        float scale = Math.min(width / image.getWidth(), available / image.getHeight());
        if (scale <= 0 || image.getHeight() * scale < 48f) {
            newPage(PDRectangle.A4);
            available = y - MARGIN - FOOTER;
            scale = Math.min(contentWidth() / image.getWidth(), available / image.getHeight());
        }
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        PDImageXObject object = LosslessFactory.createFromImage(document, image);
        stream.drawImage(object, MARGIN, y - drawHeight, drawWidth, drawHeight);
        y -= drawHeight + 10f;
    }

    void slide(BufferedImage image, float widthPoints, float heightPoints) throws IOException {
        closePage();
        size = new PDRectangle(Math.max(1, widthPoints), Math.max(1, heightPoints));
        page = new PDPage(size);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        pageCount++;
        checkPageLimit();
        PDImageXObject object = LosslessFactory.createFromImage(document, image);
        stream.drawImage(object, 0, 0, size.getWidth(), size.getHeight());
        y = 0;
    }

    byte[] finish() throws IOException {
        if (pageCount == 0) newPage(PDRectangle.A4);
        closePage();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.save(bytes);
            if (bytes.size() > limits.maxOutputBytes())
                throw ConversionWorkspace.limit("OUTPUT_BYTES_LIMIT", "PDFのサイズが上限を超えました。");
            return bytes.toByteArray();
        }
    }

    private void ensureSpace(float required, PDRectangle preferred) throws IOException {
        if (stream == null || y - required < MARGIN + FOOTER) newPage(preferred);
    }

    private void newPage(PDRectangle pageSize) throws IOException {
        closePage();
        size = pageSize;
        page = new PDPage(pageSize);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        pageCount++;
        checkPageLimit();
        y = size.getHeight() - MARGIN;
    }

    private void checkPageLimit() {
        if (ConversionLimits.exceeds(pageCount, limits.maxPages()))
            throw ConversionWorkspace.limit("PAGE_LIMIT", "生成されたPDFのページ数が上限を超えました。");
    }

    private void closePage() throws IOException {
        if (stream == null) return;
        stream.close();
        stream = null;
    }

    private float contentWidth() { return size.getWidth() - MARGIN * 2f; }

    private void textLine(String value, float x, float baseline, PDType0Font font, float fontSize, Color color) throws IOException {
        String safe = glyphSafe(font, normalize(value));
        stream.beginText();
        stream.setNonStrokingColor(color);
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(x, baseline);
        stream.showText(safe);
        stream.endText();
    }

    private List<String> wrap(String value, PDType0Font font, float fontSize, float maximum) throws IOException {
        String safe = glyphSafe(font, normalize(value));
        if (safe.isEmpty()) return List.of("");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < safe.length();) {
            int codePoint = safe.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            if (codePoint == '\n') {
                lines.add(line.toString());
                line.setLength(0);
            } else {
                String candidate = line + character;
                if (line.length() > 0 && textWidth(font, fontSize, candidate) > maximum) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(character);
            }
            offset += Character.charCount(codePoint);
        }
        if (line.length() > 0 || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static float textWidth(PDType0Font font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || !Character.isISOControl(codePoint))
                result.appendCodePoint(codePoint == '\t' ? ' ' : codePoint);
        });
        return result.toString().replace('\u00a0', ' ');
    }

    private static String glyphSafe(PDType0Font font, String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            try {
                // PDFont.hasGlyph(int) accepts an encoded character code, not a Unicode code point.
                // Measuring the Unicode string exercises the font's actual Unicode encoder.
                font.getStringWidth(character);
                result.append(character);
            } catch (IOException | IllegalArgumentException failure) {
                result.append('\u25a1');
            }
        });
        return result.toString();
    }

    private static String ellipsis(String line) {
        if (line == null || line.isEmpty()) return "…";
        return line.substring(0, line.offsetByCodePoints(0, Math.max(0, line.codePointCount(0, line.length()) - 1))) + "…";
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
