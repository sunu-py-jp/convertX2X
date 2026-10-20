package com.convertx2x.office2md.images;

import static org.junit.jupiter.api.Assertions.*;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EmbeddedVectorImagesTest {
    private static final String SVG_START = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"50\" viewBox=\"0 0 100 50\">";

    @Test void svgRendersLocalReferencesWithTransparentBackground() throws Exception {
        var result = prepareSvg(SVG_START + """
                <defs><rect id="mark" width="35" height="25" fill="#ff0000"/></defs>
                <use xlink:href="#mark" x="10" y="10"/>
                </svg>
                """);
        assertTrue(result.ocrEligible(), result.skipReason());
        assertEquals("png", result.extension());
        assertTrue(result.changed());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        try {
            assertEquals(Color.RED.getRGB(), image.getRGB(40, 40));
            assertEquals(0, image.getRGB(180, 80) >>> 24);
        } finally { image.flush(); }
    }

    @Test void svgCropAndQuarterTurnAreAppliedOnce() throws Exception {
        String svg = SVG_START + "<rect width=\"50\" height=\"50\" fill=\"red\"/>"
                + "<rect x=\"50\" width=\"50\" height=\"50\" fill=\"blue\"/></svg>";
        var result = EmbeddedImagePreprocessor.prepare(svg.getBytes(StandardCharsets.UTF_8),
                "svg", "image/svg+xml", new EmbeddedImagePreprocessor.Crop(.5, 0, 0, 0, true),
                AffineTransform.getRotateInstance(Math.PI / 2), 100, 50, ConversionLimits.defaults());
        assertTrue(result.ocrEligible(), result.skipReason());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        try {
            assertEquals(Color.BLUE.getRGB(), image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
            for (int y = 2; y < image.getHeight() - 2; y += 5)
                for (int x = 2; x < image.getWidth() - 2; x += 5)
                    assertEquals(Color.BLUE.getRGB(), image.getRGB(x, y));
        } finally { image.flush(); }
    }

    @Test void gifBmpAndTiffNormalizeBeforeCompositing() throws Exception {
        for (String format : new String[]{"gif", "bmp", "dib", "tiff"}) {
            BufferedImage input = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
            try {
                for (int y = 0; y < 20; y++) for (int x = 0; x < 40; x++) input.setRGB(x, y, Color.GREEN.getRGB());
                ByteArrayOutputStream source = new ByteArrayOutputStream();
                assertTrue(ImageIO.write(input, format.equals("dib") ? "bmp" : format, source), format);
                byte[] bytes = source.toByteArray();
                if (format.equals("dib")) bytes = java.util.Arrays.copyOfRange(bytes, 14, bytes.length);
                var result = EmbeddedImagePreprocessor.prepare(bytes, format, "image/" + format,
                        EmbeddedImagePreprocessor.Crop.none(), new AffineTransform(), 100, 50, ConversionLimits.defaults());
                assertTrue(result.ocrEligible(), format + ": " + result.skipReason());
                assertEquals("png", result.extension());
                BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.bytes()));
                try {
                    assertEquals(40, output.getWidth());
                    assertEquals(20, output.getHeight());
                    assertEquals(Color.GREEN.getRGB(), output.getRGB(10, 10));
                } finally { output.flush(); }
            } finally { input.flush(); }
        }
    }

    @Test void svgAllowsOnlyValidatedInlineRasterImages() throws Exception {
        BufferedImage raster = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        var graphics = raster.createGraphics();
        graphics.setColor(Color.GREEN); graphics.fillRect(0, 0, 10, 10); graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(raster, "png", encoded); raster.flush();
        String uri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(encoded.toByteArray());
        String svg = SVG_START + "<image x=\"0\" y=\"0\" width=\"100\" height=\"50\" "
                + "preserveAspectRatio=\"none\" xlink:href=\"" + uri + "\"/></svg>";
        var result = prepareSvg(svg);
        assertTrue(result.ocrEligible(), result.skipReason());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        try { assertEquals(Color.GREEN.getRGB(), image.getRGB(100, 50)); }
        finally { image.flush(); }
        String nestedSvg = "data:image/svg+xml;base64," + java.util.Base64.getEncoder()
                .encodeToString((SVG_START + "</svg>").getBytes(StandardCharsets.UTF_8));
        assertFalse(prepareSvg(SVG_START + "<image xlink:href=\"" + nestedSvg + "\"/></svg>").ocrEligible());
    }

    @Test void vectorCanvasLimitIsCheckedBeforeRendering() {
        var defaults = ConversionLimits.defaults();
        var limits = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(), defaults.maxReadItems(),
                defaults.maxTableCells(), defaults.maxMarkdownBytes(), defaults.maxImages(), defaults.maxImageBytes(),
                defaults.maxOutputBytes(), defaults.maxShapes(), defaults.maxGroupDepth(), 500);
        var failure = assertThrows(ConversionException.class, () -> EmbeddedImagePreprocessor.prepare(
                (SVG_START + "</svg>").getBytes(StandardCharsets.UTF_8), "svg", "image/svg+xml",
                EmbeddedImagePreprocessor.Crop.none(), new AffineTransform(), 100, 50, limits));
        assertTrue(failure.getMessage().contains("画素数"));
    }

    @Test void scriptExternalResourcesDtdAndCssNeverFetchOrBecomeOcrInputs() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            String[] unsafe = {
                    SVG_START + "<image xlink:href=\"" + remote + "\"/></svg>",
                    SVG_START + "<use xlink:href=\"" + remote + "#object\"/></svg>",
                    SVG_START + "<style>@import url('" + remote + "');</style></svg>",
                    SVG_START + "<rect style=\"fill:url(" + remote + ")\"/></svg>",
                    SVG_START + "<rect style=\"fill:u\\72l(" + remote + ")\"/></svg>",
                    SVG_START + "<script>throw 'must not run';</script></svg>",
                    SVG_START + "<rect onclick=\"throw 'must not run'\"/></svg>",
                    SVG_START + "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x</div></foreignObject></svg>",
                    "<!DOCTYPE svg SYSTEM \"" + remote + "\">" + SVG_START + "</svg>",
                    "<?xml-stylesheet type=\"text/css\" href=\"" + remote + "\"?>" + SVG_START + "</svg>",
                    SVG_START + "<image xlink:href=\"file:///etc/passwd\"/></svg>"
            };
            for (String svg : unsafe) {
                var result = prepareSvg(svg);
                assertFalse(result.ocrEligible(), svg);
                assertNotNull(result.skipReason());
                assertArrayEquals(svg.getBytes(StandardCharsets.UTF_8), result.bytes());
            }
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }

    @Test void cyclicAndExponentiallyExpandedUseAreRejected() {
        var cycle = prepareSvg(SVG_START + """
                <defs><g id="one"><use xlink:href="#two"/></g><g id="two"><use xlink:href="#one"/></g></defs>
                <use xlink:href="#one"/></svg>
                """);
        assertFalse(cycle.ocrEligible());
        var svg = new StringBuilder(SVG_START).append("<defs><g id=\"u0\"><rect width=\"2\" height=\"2\"/></g>");
        for (int i = 1; i < 20; i++) svg.append("<g id=\"u").append(i).append("\"><use xlink:href=\"#u")
                .append(i - 1).append("\"/><use xlink:href=\"#u").append(i - 1).append("\"/></g>");
        svg.append("</defs><use xlink:href=\"#u19\"/></svg>");
        assertFalse(prepareSvg(svg.toString()).ocrEligible());
    }

    @Test void plainWmfAndEmfBecomeVisiblePng() throws Exception {
        for (byte[] source : new byte[][]{wmf(), emf()}) {
            String extension = source[0] == 1 ? "emf" : "wmf";
            var result = EmbeddedImagePreprocessor.prepare(source, extension, "image/" + extension,
                    EmbeddedImagePreprocessor.Crop.none(), new AffineTransform(), 100, 50, ConversionLimits.defaults());
            assertTrue(result.ocrEligible(), extension + ": " + result.skipReason());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.bytes()));
            try { assertEquals(Color.RED.getRGB(), image.getRGB(100, 50), extension); }
            finally { image.flush(); }
        }
    }

    private static EmbeddedImagePreprocessor.Prepared prepareSvg(String svg) {
        return EmbeddedImagePreprocessor.prepare(svg.getBytes(StandardCharsets.UTF_8), "svg", "image/svg+xml",
                EmbeddedImagePreprocessor.Crop.none(), new AffineTransform(), 100, 50, ConversionLimits.defaults());
    }

    /** A tiny generated red rectangle: no third-party test document or drawing data. */
    private static byte[] wmf() {
        ByteBuffer bytes = ByteBuffer.allocate(108).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x9ac6cdd7).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .putShort((short) 100).putShort((short) 50).putShort((short) 1440).putInt(0);
        short checksum = 0;
        for (int i = 0; i < 20; i += 2) checksum ^= bytes.getShort(i);
        bytes.putShort(checksum);
        bytes.putShort((short) 1).putShort((short) 9).putShort((short) 0x300)
                .putInt(40).putShort((short) 1).putInt(7).putShort((short) 0);
        bytes.putInt(5).putShort((short) 0x020b).putShort((short) 0).putShort((short) 0);
        bytes.putInt(5).putShort((short) 0x020c).putShort((short) 50).putShort((short) 100);
        bytes.putInt(7).putShort((short) 0x02fc).putShort((short) 0).putInt(0x000000ff).putShort((short) 0);
        bytes.putInt(4).putShort((short) 0x012d).putShort((short) 0);
        bytes.putInt(7).putShort((short) 0x041b).putShort((short) 50).putShort((short) 100).putInt(0);
        bytes.putInt(3).putShort((short) 0);
        return java.util.Arrays.copyOf(bytes.array(), bytes.position());
    }

    private static byte[] emf() {
        ByteBuffer bytes = ByteBuffer.allocate(168).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(1).putInt(88);
        bytes.putInt(0).putInt(0).putInt(100).putInt(50);
        bytes.putInt(0).putInt(0).putInt(2646).putInt(1323);
        bytes.putInt(0x464d4520).putInt(0x10000).putInt(168).putInt(5);
        bytes.putShort((short) 2).putShort((short) 0).putInt(0).putInt(0).putInt(0);
        bytes.putInt(100).putInt(50).putInt(26).putInt(13);
        bytes.putInt(39).putInt(24).putInt(1).putInt(0).putInt(0xff).putInt(0);
        bytes.putInt(37).putInt(12).putInt(1);
        bytes.putInt(43).putInt(24).putInt(0).putInt(0).putInt(100).putInt(50);
        bytes.putInt(14).putInt(20).putInt(0).putInt(0).putInt(20);
        return bytes.array();
    }
}
