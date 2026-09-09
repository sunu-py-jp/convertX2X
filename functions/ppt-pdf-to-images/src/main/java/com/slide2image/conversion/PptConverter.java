package com.slide2image.conversion;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Opens PowerPoint containers and renders their slides while their resources remain available. */
final class PptConverter implements InputConverter {
    private static final String PPTX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
    private final PageRendering rendering;

    PptConverter(ConversionLimits limits) {
        rendering = new PageRendering(limits);
    }

    @Override
    public boolean supports(byte[] input) {
        if (input == null || input.length == 0) {
            return false;
        }
        FileMagic magic = FileMagic.valueOf(input);
        return magic == FileMagic.OOXML || magic == FileMagic.OLE2;
    }

    @Override
    public <T> T convert(byte[] input, ConversionOptions options, PageEncoder<T> encoder) throws Exception {
        try {
            // File signatures select a container; its contents must still identify a presentation.
            FileMagic magic = FileMagic.valueOf(input);
            if (magic == FileMagic.OOXML) {
                try (OPCPackage archive = OPCPackage.open(new ByteArrayInputStream(input))) {
                    if (archive.getPartsByContentType(PPTX_CONTENT_TYPE).isEmpty()) {
                        throw unsupported();
                    }
                    try (XMLSlideShow slides = new XMLSlideShow(archive)) {
                        return convertSlides(slides, options, encoder);
                    }
                }
            }
            if (magic == FileMagic.OLE2) {
                try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(input))) {
                    if (filesystem.getRoot().hasEntry("EncryptedPackage")) {
                        throw encrypted(null);
                    }
                    if (!filesystem.getRoot().hasEntry("PowerPoint Document")) {
                        throw unsupported();
                    }
                    try (HSLFSlideShow slides = new HSLFSlideShow(filesystem)) {
                        return convertSlides(slides, options, encoder);
                    }
                }
            }
            throw unsupported();
        } catch (EncryptedDocumentException failure) {
            throw encrypted(failure);
        }
    }

    private <T> T convertSlides(SlideShow<?, ?> slides, ConversionOptions options,
                               PageEncoder<T> encoder) throws IOException {
        Dimension points = slides.getPageSize();
        PageRendering.RenderSize size = rendering.dimensions(points.getWidth(), points.getHeight(), options.width(), 1);
        return encoder.encode(slides.getSlides().size(), index -> {
            BufferedImage image = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = PageRendering.graphics(image);
            try {
                graphics.setRenderingHint(Drawable.FONT_HANDLER, BundledPptFontManager.instance());
                graphics.setRenderingHint(Drawable.DRAW_FACTORY, new PptFontDrawFactory());
                graphics.scale(size.scale(), size.scale());
                slides.getSlides().get(index).draw(graphics);
                return image;
            } catch (RuntimeException failure) {
                image.flush();
                throw failure;
            } finally {
                graphics.dispose();
            }
        });
    }

    private static ConversionException unsupported() {
        return new ConversionException(415, "UNSUPPORTED_DOCUMENT", "Only PowerPoint (PPT/PPTX) and PDF are supported.");
    }

    private static ConversionException encrypted(Throwable cause) {
        return new ConversionException(422, "ENCRYPTED_DOCUMENT", "Encrypted documents are not supported.", cause);
    }
}
