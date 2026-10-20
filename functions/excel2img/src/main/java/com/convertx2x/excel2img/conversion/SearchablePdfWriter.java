package com.convertx2x.excel2img.conversion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;

/** Uses the exact PNG rendering plus measured invisible Unicode text. PDF streams spill to disk. */
final class SearchablePdfWriter implements AutoCloseable {
    private final PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache());
    private final PDType0Font regular;
    private final PDType0Font bold;
    private final ConversionLimits limits;
    private final Consumer<String> warning;
    private long imageBytes;
    SearchablePdfWriter(ConversionLimits limits, Consumer<String> warning) throws IOException {
        this.limits = limits; this.warning = warning;
        try (InputStream a = resource("/fonts/bizud/BIZUDPGothic-Regular.ttf");
             InputStream b = resource("/fonts/bizud/BIZUDPGothic-Bold.ttf")) {
            regular = PDType0Font.load(document, a, true);
            bold = PDType0Font.load(document, b, true);
        }
    }
    void page(BufferedImage image, List<CellPainter.TextLine> lines, RangePlanner.Plan plan,
              RangePlanner.Page source, double pixelsPerPoint) throws IOException {
        float width = (float) (image.getWidth() / pixelsPerPoint);
        float height = (float) (image.getHeight() / pixelsPerPoint);
        double factor = plan.scale() / pixelsPerPoint;
        PDPage page = new PDPage(new PDRectangle(width, height));
        document.addPage(page);
        var picture = LosslessFactory.createFromImage(document, image);
        imageBytes += picture.getCOSObject().getLength();
        if (imageBytes > limits.maxOutputBytes()) throw ConversionWorkspace.limit("OUTPUT_BYTES_LIMIT", "PDF画像の合計サイズが上限を超えました。");
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.drawImage(picture, 0, 0, width, height);
            stream.beginText();
            stream.setRenderingMode(RenderingMode.NEITHER);
            for (CellPainter.TextLine line : lines) {
                PDType0Font font = line.bold() ? bold : regular;
                String safe = glyphSafe(font, line.text());
                if (safe.isBlank()) continue;
                float size = (float) (line.fontSize() * factor);
                float nativeWidth = font.getStringWidth(safe) / 1000f * size;
                if (nativeWidth <= 0) continue;
                stream.setFont(font, size);
                stream.setTextMatrix(new Matrix((float) (line.width() * factor / nativeWidth), 0, 0, 1,
                        (float) ((line.x() - plan.bounds().getX()) * factor),
                        height - (float) ((line.baseline() - source.y()) * factor)));
                stream.showText(safe);
            }
            stream.endText();
        }
    }
    private String glyphSafe(PDType0Font font, String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String value = new String(Character.toChars(cp));
            try { font.getStringWidth(value); result.append(value); }
            catch (IOException | IllegalArgumentException unsupported) {
                result.append('\u25a1');
                warning.accept("PDF検索用文字層で同梱フォントにない文字を □ に置き換えました。画像には描画結果が残ります。");
            }
        });
        return result.toString();
    }
    void finish(OutputStream output) throws IOException { document.save(output); }
    private static InputStream resource(String name) throws IOException {
        InputStream stream = SearchablePdfWriter.class.getResourceAsStream(name);
        if (stream == null) throw new IOException("Missing bundled font");
        return stream;
    }
    @Override public void close() throws IOException { document.close(); }
}
