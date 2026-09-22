package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.openxmlformats.schemas.presentationml.x2006.main.CTBackground;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Draws sanitized leaves with their parent transforms, without masters, notes or external content. */
final class PresentationRenderer {
    record Layer(XSLFShape shape, AffineTransform parent) { }
    record Background(Color color, boolean approximated) { }

    /** Inspect the stored fill before asking POI to resolve colors; never instantiate a texture paint. */
    static Background background(XSLFSlide slide) {
        try {
            // getBackground only selects the slide/layout/master XML; it does not load picture fills.
            var background = slide.getBackground();
            if (background == null) return new Background(Color.WHITE, false);
            CTBackground xml = (CTBackground) background.getXmlObject();
            if (xml.isSetBgPr()) {
                var properties = xml.getBgPr();
                if (properties.isSetNoFill()) return new Background(Color.WHITE, false);
                if (!properties.isSetSolidFill() || properties.isSetBlipFill() || properties.isSetGradFill()
                        || properties.isSetPattFill() || properties.isSetGrpFill())
                    return new Background(Color.WHITE, true);
            } else if (xml.isSetBgRef()) {
                long index = xml.getBgRef().getIdx();
                if (index == 0 || index == 1000) return new Background(Color.WHITE, false);
                var theme = slide.getTheme();
                if (theme == null || theme.getXmlObject().getThemeElements() == null)
                    return new Background(Color.WHITE, true);
                var scheme = theme.getXmlObject().getThemeElements().getFmtScheme();
                if (scheme == null) return new Background(Color.WHITE, true);
                XmlObject styles = index >= 1001 ? scheme.getBgFillStyleLst() : scheme.getFillStyleLst();
                long position = index >= 1001 ? index - 1001 : index - 1;
                if (styles == null || position < 0 || position > Integer.MAX_VALUE)
                    return new Background(Color.WHITE, true);
                try (var cursor = styles.newCursor()) {
                    if (!cursor.toChild((int) position) || !(cursor.getObject() instanceof CTSolidColorFillProperties))
                        return new Background(Color.WHITE, true);
                }
            } else return new Background(Color.WHITE, false);
            // Both direct and theme-referenced fills have now been proven to contain only a solid color.
            Color color = background.getFillColor();
            return new Background(color == null ? Color.WHITE : color, color == null);
        } catch (RuntimeException unsupportedColor) {
            return new Background(Color.WHITE, true);
        }
    }

    static byte[] png(XSLFShape shape, Rectangle2D bounds, AffineTransform parent, ConversionLimits limits) throws IOException {
        return png(List.of(new Layer(shape, parent)), bounds, limits);
    }

    static byte[] png(List<Layer> layers, Rectangle2D bounds, ConversionLimits limits) throws IOException {
        return png(layers, bounds, null, limits);
    }

    static byte[] png(List<Layer> layers, Rectangle2D bounds, Color background, ConversionLimits limits) throws IOException {
        double scale = 96.0 / 72.0, padding = 6;
        double rawWidth = Math.ceil((bounds.getWidth() + padding * 2) * scale);
        double rawHeight = Math.ceil((bounds.getHeight() + padding * 2) * scale);
        if (!Double.isFinite(rawWidth) || !Double.isFinite(rawHeight) || rawWidth < 1 || rawHeight < 1
                || rawWidth > Integer.MAX_VALUE || rawHeight > Integer.MAX_VALUE
                || rawWidth * rawHeight > limits.maxImagePixels())
            throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "図の描画画素数が上限を超えました。");
        BufferedImage image = new BufferedImage((int) rawWidth, (int) rawHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            if (background != null) {
                g.setColor(Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.setColor(background); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(Drawable.FONT_HANDLER, BundledPptFontManager.instance());
            PptFontDrawFactory factory = new PptFontDrawFactory();
            g.setRenderingHint(Drawable.DRAW_FACTORY, factory);
            g.scale(scale, scale);
            g.translate(padding - bounds.getX(), padding - bounds.getY());
            for (Layer layer : layers) {
                Graphics2D item = (Graphics2D) g.create();
                try {
                    item.transform(layer.parent());
                    item.setRenderingHint(Drawable.GROUP_TRANSFORM, new AffineTransform());
                    // DrawFactory.drawShape skips zero-height/width anchors, erasing horizontal/vertical connectors.
                    Drawable drawable = factory.getDrawable(layer.shape());
                    drawable.applyTransform(item);
                    drawable.draw(item);
                } finally { item.dispose(); }
            }
            LimitedBytes output = new LimitedBytes(limits.maxImageBytes());
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
            return output.toByteArray();
        } finally { g.dispose(); image.flush(); }
    }

    private static final class LimitedBytes extends ByteArrayOutputStream {
        private final long max;
        LimitedBytes(long max) { this.max = max; }
        private void check(int size) {
            if (size > max - count) throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
        }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) { check(length); super.write(bytes, offset, length); }
    }
    private PresentationRenderer() { }
}
