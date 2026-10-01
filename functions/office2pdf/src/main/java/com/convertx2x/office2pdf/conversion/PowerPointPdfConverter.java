package com.convertx2x.office2pdf.conversion;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.xslf.usermodel.XSLFSlide;

/** Renders each visible slide as one PDF page using POI's Java2D renderer. */
final class PowerPointPdfConverter {
    void convert(byte[] input, NormalizedPdfWriter pdf, ConversionWorkspace workspace) throws IOException {
        try (SlideShow<?, ?> show = SlideShowFactory.create(new ByteArrayInputStream(input))) {
            Dimension page = show.getPageSize();
            double scale = 2d;
            long requested = Math.max(1, Math.round(page.getWidth() * scale))
                    * Math.max(1, Math.round(page.getHeight() * scale));
            if (requested > workspace.limits().maxImagePixels())
                scale *= Math.sqrt((double) workspace.limits().maxImagePixels() / requested);
            int width = Math.max(1, (int) Math.round(page.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(page.getHeight() * scale));
            int included = 0;
            for (Slide<?, ?> slide : show.getSlides()) {
                if (slide instanceof XSLFSlide modern && modern.isHidden()) continue;
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, width, height);
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.setRenderingHint(Drawable.FONT_HANDLER, BundledPptFontManager.instance());
                    graphics.scale(scale, scale);
                    slide.draw(graphics);
                    pdf.slide(image, (float) page.getWidth(), (float) page.getHeight());
                    workspace.sectionIncluded();
                    included++;
                } catch (RuntimeException failure) {
                    throw new ConversionException(422, "SLIDE_RENDER_FAILED", "PowerPointスライドを描画できませんでした。", failure);
                } finally {
                    graphics.dispose();
                    image.flush();
                }
            }
            if (included == 0) throw new ConversionException(422, "EMPTY_DOCUMENT", "表示対象のスライドがありません。");
        }
    }
}
