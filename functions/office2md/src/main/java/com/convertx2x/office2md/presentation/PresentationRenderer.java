package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.xslf.usermodel.XSLFShape;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Draws one leaf with its complete parent transform, without masters, notes or neighboring shapes. */
final class PresentationRenderer {
    static byte[] png(XSLFShape shape, Rectangle2D bounds, AffineTransform parent, ConversionLimits limits) throws IOException {
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
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(Drawable.FONT_HANDLER, BundledPptFontManager.instance());
            PptFontDrawFactory factory = new PptFontDrawFactory();
            g.setRenderingHint(Drawable.DRAW_FACTORY, factory);
            g.scale(scale, scale);
            g.translate(padding - bounds.getX(), padding - bounds.getY());
            g.transform(parent);
            g.setRenderingHint(Drawable.GROUP_TRANSFORM, new AffineTransform());
            // DrawFactory.drawShape skips zero-height/width anchors, which would erase horizontal/vertical connectors.
            Drawable drawable = factory.getDrawable(shape);
            drawable.applyTransform(g);
            drawable.draw(g);
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
