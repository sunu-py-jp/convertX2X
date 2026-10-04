package com.convertx2x.office2md.images;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * Produces the visible pixels of an embedded Office picture without rendering neighboring shapes.
 * The source resolution is the ceiling: placement transforms may rotate or downsample, but never
 * create additional detail. A crop that cannot be applied is never sent to OCR.
 */
public final class EmbeddedImagePreprocessor {
    private EmbeddedImagePreprocessor() { }

    public record Crop(double left, double top, double right, double bottom, boolean present) {
        public static Crop none() { return new Crop(0, 0, 0, 0, false); }

        /** DrawingML srcRect percentages are stored in 1/1000 percent (100000 = 100%). */
        public static Crop drawingMl(Node source) {
            if (source == null) return none();
            return new Crop(percent(source, "l"), percent(source, "t"),
                    percent(source, "r"), percent(source, "b"), true);
        }

        /** BIFF crop values are signed 16.16 fractions of the original picture. */
        public static Crop biff(int left, int top, int right, int bottom) {
            boolean present = left != 0 || top != 0 || right != 0 || bottom != 0;
            return new Crop(left / 65536d, top / 65536d, right / 65536d, bottom / 65536d, present);
        }

        private boolean valid() {
            return Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(right) && Double.isFinite(bottom)
                    && left >= 0 && top >= 0 && right >= 0 && bottom >= 0
                    && left + right < 1 && top + bottom < 1;
        }
    }

    public record Prepared(byte[] bytes, String extension, String contentType, boolean ocrEligible,
                           boolean changed, String skipReason) {
        public Prepared withBytes(byte[] shared) {
            return new Prepared(shared, extension, contentType, ocrEligible, changed, skipReason);
        }
    }

    /** Unknown picture masks and alpha effects can hide source text; never OCR beneath them. */
    public static boolean rectangularVisibility(Node picture) {
        Node shape = child(picture, "spPr"), fill = child(picture, "blipFill");
        if (shape == null || fill == null || child(shape, "custGeom") != null) return false;
        Node preset = child(shape, "prstGeom");
        if (preset == null || preset.getAttributes() == null || preset.getAttributes().getNamedItem("prst") == null
                || !"rect".equals(preset.getAttributes().getNamedItem("prst").getNodeValue())) return false;
        if (hasElementChildren(child(shape, "effectLst")) || child(shape, "effectDag") != null
                || child(shape, "scene3d") != null || child(shape, "sp3d") != null
                || child(fill, "tile") != null) return false;
        Node blip = child(fill, "blip");
        if (blip == null || !harmlessBlipChildren(blip)) return false;
        Node fillRect = child(child(fill, "stretch"), "fillRect");
        if (fillRect != null && fillRect.getAttributes() != null)
            for (int i = 0; i < fillRect.getAttributes().getLength(); i++) {
                Node attribute = fillRect.getAttributes().item(i);
                if ("http://www.w3.org/2000/xmlns/".equals(attribute.getNamespaceURI())) continue;
                String name = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
                if (!"l".equals(name) && !"t".equals(name) && !"r".equals(name) && !"b".equals(name)) return false;
                String value = attribute.getNodeValue();
                if (!"0".equals(value) && !"0%".equals(value)) return false;
            }
        return true;
    }

    public static Prepared prepare(byte[] bytes, String extension, String contentType, Crop crop,
                                   AffineTransform placement, double width, double height, ConversionLimits limits) {
        if (bytes.length > limits.maxImageBytes())
            throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
        if (crop == null) crop = Crop.none();
        if (!crop.valid()) return skipped(bytes, extension, contentType, "画像の切り抜き範囲を解釈できません。");
        if (placement == null || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            return skipped(bytes, extension, contentType, "画像の表示方向を解釈できません。");
        double[] matrix = new double[6];
        placement.getMatrix(matrix);
        for (double element : matrix)
            if (!Double.isFinite(element))
                return skipped(bytes, extension, contentType, "画像の表示方向を解釈できません。");
        if (!Double.isFinite(placement.getDeterminant()) || Math.abs(placement.getDeterminant()) < 1e-12)
            return skipped(bytes, extension, contentType, "画像の表示方向を解釈できません。");

        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) return skipped(bytes, extension, contentType, "画像形式を復号できません。");
            Iterator<ImageReader> candidates = ImageIO.getImageReaders(stream);
            if (!candidates.hasNext()) return skipped(bytes, extension, contentType, "画像形式を復号できません。");
            ImageReader reader = candidates.next();
            try {
                reader.setInput(stream, true, true);
                int sourceWidth = reader.getWidth(0), sourceHeight = reader.getHeight(0);
                if (sourceWidth <= 0 || sourceHeight <= 0 || sourceWidth > limits.maxImagePixels() / sourceHeight)
                    throw ConversionWorkspace.limit("IMAGE_PIXEL_LIMIT", "画像の画素数が上限を超えました。");
                int x1 = (int) Math.ceil(crop.left() * sourceWidth);
                int y1 = (int) Math.ceil(crop.top() * sourceHeight);
                int x2 = (int) Math.floor((1 - crop.right()) * sourceWidth);
                int y2 = (int) Math.floor((1 - crop.bottom()) * sourceHeight);
                if (x1 < 0 || y1 < 0 || x2 > sourceWidth || y2 > sourceHeight || x1 >= x2 || y1 >= y2)
                    return skipped(bytes, extension, contentType, "画像の切り抜き後に表示可能な画素がありません。");

                boolean cropped = x1 != 0 || y1 != 0 || x2 != sourceWidth || y2 != sourceHeight;
                boolean reoriented = Math.abs(placement.getShearX()) > 1e-8
                        || Math.abs(placement.getShearY()) > 1e-8
                        || placement.getScaleX() < 0 || placement.getScaleY() < 0;
                boolean nativePng = isPng(bytes), nativeJpeg = isJpeg(bytes);
                if (!cropped && !reoriented && (nativePng || nativeJpeg))
                    return new Prepared(bytes, nativePng ? "png" : "jpg", nativePng ? "image/png" : "image/jpeg", true, false, null);

                BufferedImage source = reader.read(0);
                if (source == null) return skipped(bytes, extension, contentType, "画像を復号できません。");
                BufferedImage visible = source;
                try {
                    if (cropped) visible = source.getSubimage(x1, y1, x2 - x1, y2 - y1);
                    byte[] png = reoriented ? transformedPng(visible, placement, width, height, limits)
                            : encodePng(visible, limits);
                    return new Prepared(png, "png", "image/png", true, true, null);
                } finally {
                    source.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException failure) {
            return skipped(bytes, extension, contentType, "画像の可視領域を安全に生成できません。");
        }
    }

    private static byte[] transformedPng(BufferedImage source, AffineTransform placement,
                                         double width, double height, ConversionLimits limits) throws IOException {
        AffineTransform pixelToPoint = new AffineTransform(placement);
        pixelToPoint.scale(width / source.getWidth(), height / source.getHeight());
        double axisX = Math.hypot(pixelToPoint.getScaleX(), pixelToPoint.getShearY());
        double axisY = Math.hypot(pixelToPoint.getShearX(), pixelToPoint.getScaleY());
        if (!Double.isFinite(axisX) || !Double.isFinite(axisY) || axisX <= 0 || axisY <= 0)
            throw new IllegalArgumentException("Invalid image placement transform");
        // Each source pixel maps to at most one destination pixel unit along its mapped axis.
        double pixelsPerPoint = 1 / Math.max(axisX, axisY);
        Rectangle2D bounds = pixelToPoint.createTransformedShape(
                new Rectangle2D.Double(0, 0, source.getWidth(), source.getHeight())).getBounds2D();
        double rawWidth = Math.ceil(bounds.getWidth() * pixelsPerPoint);
        double rawHeight = Math.ceil(bounds.getHeight() * pixelsPerPoint);
        if (!Double.isFinite(rawWidth) || !Double.isFinite(rawHeight)
                || rawWidth < 1 || rawHeight < 1 || rawWidth > Integer.MAX_VALUE || rawHeight > Integer.MAX_VALUE
                || rawWidth > limits.maxImagePixels() / rawHeight)
            throw ConversionWorkspace.limit("IMAGE_PIXEL_LIMIT", "可視画像の画素数が上限を超えました。");
        BufferedImage target = new BufferedImage((int) rawWidth, (int) rawHeight, BufferedImage.TYPE_INT_ARGB);
        try {
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                AffineTransform toTarget = new AffineTransform();
                toTarget.scale(pixelsPerPoint, pixelsPerPoint);
                toTarget.translate(-bounds.getX(), -bounds.getY());
                toTarget.concatenate(pixelToPoint);
                graphics.drawImage(source, toTarget, null);
            } finally { graphics.dispose(); }
            return encodePng(target, limits);
        } finally { target.flush(); }
    }

    private static byte[] encodePng(BufferedImage image, ConversionLimits limits) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] buffer, int offset, int length) {
                check(length); super.write(buffer, offset, length);
            }
            @Override public synchronized void write(int value) { check(1); super.write(value); }
            private void check(int length) {
                if (length > limits.maxImageBytes() - count)
                    throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "可視画像のサイズが上限を超えました。");
            }
        };
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder is unavailable");
        return bytes.toByteArray();
    }

    private static Prepared skipped(byte[] bytes, String extension, String contentType, String reason) {
        return new Prepared(bytes, extension, contentType, false, false, reason);
    }

    private static double percent(Node node, String name) {
        if (node.getAttributes() == null || node.getAttributes().getNamedItem(name) == null) return 0;
        String value = node.getAttributes().getNamedItem(name).getNodeValue();
        try {
            return value.endsWith("%") ? Double.parseDouble(value.substring(0, value.length() - 1)) / 100
                    : Double.parseDouble(value) / 100000;
        } catch (NumberFormatException failure) { return Double.NaN; }
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
    }
    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
    }

    private static Node child(Node parent, String localName) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) return node;
        return null;
    }

    private static boolean hasElementChildren(Node parent) {
        if (parent == null) return false;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node.getNodeType() == Node.ELEMENT_NODE) return true;
        return false;
    }

    private static boolean harmlessBlipChildren(Node blip) {
        for (Node node = blip.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!"extLst".equals(node.getLocalName())) return false;
            for (Node extension = node.getFirstChild(); extension != null; extension = extension.getNextSibling()) {
                if (extension.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!"ext".equals(extension.getLocalName())) return false;
                boolean localDpi = false;
                for (Node feature = extension.getFirstChild(); feature != null; feature = feature.getNextSibling()) {
                    if (feature.getNodeType() != Node.ELEMENT_NODE) continue;
                    if (!"useLocalDpi".equals(feature.getLocalName()) || localDpi) return false;
                    localDpi = true;
                }
                if (!localDpi) return false;
            }
        }
        return true;
    }
}
