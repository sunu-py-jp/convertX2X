package com.convertx2x.office2md.images;

import static org.junit.jupiter.api.Assertions.*;

import com.convertx2x.office2md.conversion.ConversionLimits;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EmbeddedImagePreprocessorTest {
    @Test void cropRemovesHiddenPixelsBeforeOcrAndKeepsOriginalResolution() throws Exception {
        byte[] source = splitImage();
        var visible = EmbeddedImagePreprocessor.prepare(source, "png", "image/png",
                new EmbeddedImagePreprocessor.Crop(.5, 0, 0, 0, true),
                new AffineTransform(), 100, 50, ConversionLimits.defaults());
        assertTrue(visible.ocrEligible());
        assertTrue(visible.changed());
        assertEquals("png", visible.extension());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(visible.bytes()));
        try {
            assertEquals(50, image.getWidth());
            assertEquals(50, image.getHeight());
            for (int y = 0; y < image.getHeight(); y += 5)
                for (int x = 0; x < image.getWidth(); x += 5)
                    assertEquals(Color.BLUE.getRGB(), image.getRGB(x, y));
        } finally { image.flush(); }
    }

    @Test void rotationAndFlipAreAppliedWithoutUpscaling() throws Exception {
        byte[] source = splitImage();
        AffineTransform rotation = AffineTransform.getRotateInstance(Math.PI / 2);
        var visible = EmbeddedImagePreprocessor.prepare(source, "png", "image/png",
                EmbeddedImagePreprocessor.Crop.none(), rotation, 100, 50, ConversionLimits.defaults());
        assertTrue(visible.ocrEligible());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(visible.bytes()));
        try {
            assertEquals(50, image.getWidth());
            assertEquals(100, image.getHeight());
            assertEquals(Color.RED.getRGB(), image.getRGB(25, 25));
            assertEquals(Color.BLUE.getRGB(), image.getRGB(25, 75));
        } finally { image.flush(); }
        AffineTransform flip = AffineTransform.getScaleInstance(-1, 1);
        var mirrored = EmbeddedImagePreprocessor.prepare(source, "png", "image/png",
                EmbeddedImagePreprocessor.Crop.none(), flip, 100, 50, ConversionLimits.defaults());
        image = ImageIO.read(new ByteArrayInputStream(mirrored.bytes()));
        try {
            assertEquals(Color.BLUE.getRGB(), image.getRGB(25, 25));
            assertEquals(Color.RED.getRGB(), image.getRGB(75, 25));
        } finally { image.flush(); }
    }

    @Test void invalidCropOrTransformPreservesSourceButDisallowsOcr() throws Exception {
        byte[] source = splitImage();
        var invalidCrop = EmbeddedImagePreprocessor.prepare(source, "png", "image/png",
                new EmbeddedImagePreprocessor.Crop(.75, 0, .5, 0, true),
                new AffineTransform(), 100, 50, ConversionLimits.defaults());
        assertFalse(invalidCrop.ocrEligible());
        assertArrayEquals(source, invalidCrop.bytes());
        AffineTransform invalid = new AffineTransform(0, 0, 0, 0, 0, 0);
        var invalidTransform = EmbeddedImagePreprocessor.prepare(source, "png", "image/png",
                EmbeddedImagePreprocessor.Crop.none(), invalid, 100, 50, ConversionLimits.defaults());
        assertFalse(invalidTransform.ocrEligible());
    }

    private static byte[] splitImage() throws Exception {
        BufferedImage source = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 50; y++) for (int x = 0; x < 100; x++)
                source.setRGB(x, y, x < 50 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(source, "png", bytes));
            return bytes.toByteArray();
        } finally { source.flush(); }
    }
}
