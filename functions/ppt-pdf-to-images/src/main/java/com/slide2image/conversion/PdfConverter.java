package com.slide2image.conversion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** PDF input detection and rendering; encoding and transport remain shared by the application. */
final class PdfConverter implements InputConverter {
    private final PageRendering rendering;

    PdfConverter(ConversionLimits limits) {
        rendering = new PageRendering(limits);
    }

    @Override
    public boolean supports(byte[] input) {
        if (input == null) {
            return false;
        }
        // PDF permits leading bytes before its header; PDFBox supports headers in the first 1024 bytes.
        int bound = Math.min(input.length - 4, 1024);
        for (int index = 0; index < bound; index++) {
            if (input[index] == '%' && input[index + 1] == 'P' && input[index + 2] == 'D'
                    && input[index + 3] == 'F' && input[index + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    @Override
    public <T> T convert(byte[] input, ConversionOptions options, InputConverter.PageEncoder<T> encoder) throws Exception {
        // The PDF stays open while the shared encoder consumes its pages synchronously.
        try (PDDocument document = Loader.loadPDF(input)) {
            if (document.isEncrypted()) {
                throw encrypted(null);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            return encoder.encode(document.getNumberOfPages(), index -> {
                PDPage page = document.getPage(index);
                double width = page.getCropBox().getWidth();
                double height = page.getCropBox().getHeight();
                if (page.getRotation() == 90 || page.getRotation() == 270) {
                    double originalWidth = width;
                    width = height;
                    height = originalWidth;
                }
                PageRendering.RenderSize size = rendering.dimensions(width, height, options.width(), page.getUserUnit());
                BufferedImage image = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = PageRendering.graphics(image);
                try {
                    float scale = (float) size.scale();
                    renderer.renderPageToGraphics(index, graphics, scale, scale, RenderDestination.EXPORT);
                    return image;
                } catch (IOException | RuntimeException failure) {
                    image.flush();
                    throw failure;
                } finally {
                    graphics.dispose();
                }
            });
        } catch (InvalidPasswordException failure) {
            throw encrypted(failure);
        }
    }

    private static ConversionException encrypted(Throwable cause) {
        return new ConversionException(422, "ENCRYPTED_DOCUMENT", "Encrypted documents are not supported.", cause);
    }
}
