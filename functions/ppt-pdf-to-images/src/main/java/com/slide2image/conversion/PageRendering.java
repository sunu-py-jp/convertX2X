package com.slide2image.conversion;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Shared physical-size calculation and raster setup for all input formats. */
final class PageRendering {
    private final ConversionLimits limits;

    PageRendering(ConversionLimits limits) {
        this.limits = limits;
    }

    RenderSize dimensions(double width, double height, Integer outputWidth, double userUnit) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                || !Double.isFinite(userUnit) || userUnit <= 0) {
            throw new ConversionException(422, "INVALID_DOCUMENT", "The document has invalid page dimensions.");
        }
        // Slide dimensions and PDF default user units are 1/72 inch. Rasterize their physical size at 96 dpi.
        double scale = outputWidth == null ? 96.0 / 72.0 * userUnit : outputWidth / width;
        double scaledWidth = outputWidth == null ? width * scale : outputWidth;
        double scaledHeight = height * scale;
        if (!Double.isFinite(scaledWidth) || !Double.isFinite(scaledHeight)
                || scaledWidth > Integer.MAX_VALUE - 8L || scaledHeight > Integer.MAX_VALUE - 8L) {
            throw limit("PIXEL_LIMIT_EXCEEDED", "The requested image exceeds the configured pixel limit.");
        }
        long pixelWidth = Math.max(1, Math.round(scaledWidth));
        long outputHeight = Math.max(1, Math.round(scaledHeight));
        if (pixelWidth * outputHeight > limits.maxPixelsPerPage()) {
            throw limit("PIXEL_LIMIT_EXCEEDED", "The requested image exceeds the configured pixel limit.");
        }
        return new RenderSize((int) pixelWidth, (int) outputHeight, scale);
    }

    record RenderSize(int width, int height, double scale) { }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setBackground(Color.WHITE);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return graphics;
    }

    private static ConversionException limit(String code, String message) {
        return new ConversionException(413, code, message);
    }
}
