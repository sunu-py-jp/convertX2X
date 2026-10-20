package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.images.EmbeddedImagePreprocessor;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Deliberately small scene model, independent of Excel's two shape implementations. */
final class DrawingScene {
    enum Kind { RECTANGLE, ROUND_RECTANGLE, ELLIPSE, LINE, RIGHT_ARROW, LEFT_ARROW, UP_ARROW, DOWN_ARROW, TEXT, PICTURE, PRESET, UNSUPPORTED }

    static final class Item {
        int order;
        long id;
        String groupKey;
        String graphId, sourceId, startId, endId;
        boolean connector;
        String startArrowType = "none", endArrowType = "none";
        Kind kind = Kind.UNSUPPORTED;
        String preset;
        DrawingPresetGeometry.Geometry presetGeometry;
        double width, height;
        AffineTransform transform = new AffineTransform();
        Color fill = Color.WHITE, line = Color.BLACK;
        float lineWidth = 1;
        boolean startArrow, endArrow, dashed;
        List<DrawingText.Run> text = List.of();
        DrawingText.Content textContent;
        int alignment = 1;
        double textScale = 1;
        byte[] picture;
        EmbeddedImagePreprocessor.Prepared visiblePicture;
        boolean croppedPicture;
        String extension = "bin", contentType = "application/octet-stream", alt = "画像";
        String typeName = "図形（種類不明）", altRotation = "時計回り0度";
        String placeholder = "未対応の図形";
        double leftInset = 7.2, topInset = 3.6, rightInset = 7.2, bottomInset = 3.6;
        boolean positionKnown = true;

        Rectangle2D bounds() {
            double padding = Math.max(1, lineWidth / 2d) + (startArrow || endArrow ? Math.max(6, lineWidth * 4) : 0);
            Rectangle2D content = new Rectangle2D.Double(0, 0, Math.max(0, width), Math.max(0, height));
            if (presetGeometry != null) content = content.createUnion(presetGeometry.outline().getBounds2D());
            return transform.createTransformedShape(new Rectangle2D.Double(content.getX() - padding, content.getY() - padding,
                    content.getWidth() + padding * 2, content.getHeight() + padding * 2)).getBounds2D();
        }
        Rectangle2D placementBounds() {
            return transform.createTransformedShape(new Rectangle2D.Double(0, 0, width, height)).getBounds2D();
        }
    }

    /** Only safely prepared visible pixels may enter a composite; masked originals stay attachments. */
    static boolean compositePicture(Item item) {
        return item.kind == Kind.PICTURE && item.positionKnown && item.visiblePicture != null
                && item.visiblePicture.skipReason() == null
                && (item.visiblePicture.extension().equals("png") || item.visiblePicture.extension().equals("jpg"))
                && item.width > 0 && item.height > 0;
    }

    /** Positive overlap associates a picture with a supported picture or native shape. */
    static boolean pictureOverlapsShape(Item picture, Item shape) {
        return compositePicture(picture) && (shape.kind != Kind.PICTURE || compositePicture(shape))
                && visualsOverlap(picture, shape);
    }

    /** Geometry-only check shared with Word; callers separately validate usable picture pixels. */
    static boolean pictureAreaOverlapsShape(Item picture, Item shape) {
        return visualsOverlap(picture, shape);
    }

    static boolean visualsOverlap(Item picture, Item shape) {
        // Nested group scales can overflow even when every saved anchor value was finite.
        // Reject them before Area/FlatteningPathIterator see NaN or infinite coordinates.
        if (!finiteGeometry(picture) || !finiteGeometry(shape)) return false;
        if (line(picture) && !line(shape)) return visualsOverlap(shape, picture);
        if (!picture.positionKnown || picture.width <= 0 || picture.height <= 0 || !shape.positionKnown
                || picture.kind == Kind.UNSUPPORTED || shape.kind == Kind.UNSUPPORTED) return false;
        if (line(shape)) {
            // Lines can have zero width or height. Test the segment against the open picture
            // rectangle in picture coordinates, so crossing lines count but an edge touch does not.
            try {
                AffineTransform toPicture = picture.transform.createInverse();
                toPicture.concatenate(shape.transform);
                if (!finiteTransform(toPicture)
                        || !finiteBounds(toPicture.createTransformedShape(geometry(shape)).getBounds2D())) return false;
                double inset = Math.min(1e-6, Math.min(picture.width, picture.height) / 4);
                Rectangle2D inside = new Rectangle2D.Double(inset, inset, picture.width - 2 * inset, picture.height - 2 * inset);
                var path = geometry(shape).getPathIterator(toPicture, .05);
                double[] points = new double[6];
                double x = 0, y = 0;
                int segments = 0;
                while (!path.isDone() && segments++ < 4096) {
                    int command = path.currentSegment(points);
                    if (command == java.awt.geom.PathIterator.SEG_LINETO && inside.intersectsLine(x, y, points[0], points[1])) {
                        if (picture.kind == Kind.PICTURE || picture.kind == Kind.RECTANGLE || picture.kind == Kind.TEXT) return true;
                        Area overlap = new Area(geometry(picture));
                        overlap.intersect(new Area(new BasicStroke(.00001f)
                                .createStrokedShape(new Line2D.Double(x, y, points[0], points[1]))));
                        if (!overlap.isEmpty()) return true;
                    }
                    x = points[0]; y = points[1]; path.next();
                }
                return false;
            } catch (NoninvertibleTransformException invalid) { return false; }
        }
        Rectangle2D pictureBounds = picture.transform.createTransformedShape(geometry(picture)).getBounds2D();
        Rectangle2D shapeBounds = shape.transform.createTransformedShape(geometry(shape)).getBounds2D();
        if (!pictureBounds.intersects(shapeBounds)) return false;
        Area overlap = new Area(picture.transform.createTransformedShape(geometry(picture)));
        overlap.intersect(new Area(shape.transform.createTransformedShape(geometry(shape))));
        return !overlap.isEmpty();
    }

    private static boolean finiteGeometry(Item item) {
        return Double.isFinite(item.width) && Double.isFinite(item.height) && item.width >= 0 && item.height >= 0
                && finiteTransform(item.transform)
                && finiteBounds(item.transform.createTransformedShape(geometry(item)).getBounds2D());
    }

    private static boolean finiteTransform(AffineTransform transform) {
        double[] matrix = new double[6];
        transform.getMatrix(matrix);
        for (double value : matrix) if (!Double.isFinite(value)) return false;
        double determinant = transform.getDeterminant();
        return Double.isFinite(determinant) && determinant != 0;
    }

    private static boolean finiteBounds(Rectangle2D bounds) {
        return Double.isFinite(bounds.getX()) && Double.isFinite(bounds.getY())
                && Double.isFinite(bounds.getWidth()) && Double.isFinite(bounds.getHeight())
                && Double.isFinite(bounds.getMaxX()) && Double.isFinite(bounds.getMaxY())
                && bounds.getWidth() >= 0 && bounds.getHeight() >= 0;
    }

    static byte[] render(List<Item> items, ConversionWorkspace workspace) {
        return render(items, workspace, null);
    }

    static byte[] render(List<Item> items, ConversionWorkspace workspace, SheetCellLayer cells) {
        Rectangle2D bounds = bounds(items);
        if (cells != null) bounds = bounds.createUnion(cells.bounds());
        final double scale = 96d / 72;
        double requestedWidth = Math.ceil((bounds.getWidth() + 6) * scale);
        double requestedHeight = Math.ceil((bounds.getHeight() + 6) * scale);
        if (!Double.isFinite(requestedWidth) || !Double.isFinite(requestedHeight)
                || requestedWidth > Integer.MAX_VALUE || requestedHeight > Integer.MAX_VALUE
                || requestedWidth * requestedHeight > workspace.limits().maxImagePixels())
            throw new ConversionException(413, "IMAGE_PIXEL_LIMIT", "The drawing canvas exceeds the configured pixel limit");
        int width = Math.max(1, (int) requestedWidth), height = Math.max(1, (int) requestedHeight);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.scale(scale, scale);
            graphics.translate(3 - bounds.getX(), 3 - bounds.getY());
            if (cells != null) cells.draw(graphics);
            for (Item item : items) draw(graphics, item, workspace);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] buffer, int offset, int length) {
                    check(length); super.write(buffer, offset, length);
                }
                @Override public synchronized void write(int value) { check(1); super.write(value); }
                private void check(int length) {
                    if (length > workspace.limits().maxImageBytes() - count)
                        throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "図形画像1件のサイズが上限を超えました。");
                }
            };
            if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder is unavailable");
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new ConversionException(422, "DRAWING_RENDER_FAILED", "A drawing image could not be encoded");
        } finally {
            graphics.dispose();
            image.flush();
        }
    }

    static Rectangle2D bounds(List<Item> items) {
        Rectangle2D result = null;
        for (Item item : items) {
            if (result == null) result = item.bounds(); else result = result.createUnion(item.bounds());
        }
        return result == null ? new Rectangle2D.Double(0, 0, 1, 1) : result;
    }

    private static void draw(Graphics2D parent, Item item, ConversionWorkspace workspace) throws IOException {
        Graphics2D graphics = (Graphics2D) parent.create();
        try {
            if (compositePicture(item)) {
                // Prepared pixels already include cropping, rotation and flips. Their canvas
                // covers the transformed placement rectangle, so applying item.transform again
                // would rotate/flip them twice and could expose cropped source pixels.
                BufferedImage picture = decode(item.visiblePicture.bytes(), workspace);
                try {
                    Rectangle2D bounds = item.placementBounds();
                    AffineTransform transform = AffineTransform.getTranslateInstance(bounds.getX(), bounds.getY());
                    transform.scale(bounds.getWidth() / picture.getWidth(), bounds.getHeight() / picture.getHeight());
                    graphics.drawImage(picture, transform, null);
                } finally { picture.flush(); }
                return;
            }
            graphics.transform(item.transform);
            if (item.kind == Kind.PICTURE && (item.extension.equals("png") || item.extension.equals("jpg"))) {
                BufferedImage picture = decode(item.picture, workspace);
                try {
                    AffineTransform transform = AffineTransform.getScaleInstance(item.width / picture.getWidth(), item.height / picture.getHeight());
                    graphics.drawImage(picture, transform, null);
                } finally { picture.flush(); }
                return;
            }
            Shape geometry = geometry(item);
            if (item.kind == Kind.PRESET && item.presetGeometry != null) {
                for (var part : item.presetGeometry.parts()) {
                    if (part.filled() && !line(item) && item.fill != null) {
                        graphics.setColor(item.fill); graphics.fill(part.shape());
                    }
                    if (part.stroked() && item.line != null && item.lineWidth > 0) {
                        graphics.setColor(item.line);
                        graphics.setStroke(stroke(item));
                        graphics.draw(part.shape());
                    }
                }
                if (line(item) && item.line != null && item.lineWidth > 0) {
                    graphics.setColor(item.line);
                    var start = item.presetGeometry.start(); var end = item.presetGeometry.end();
                    if (item.startArrow && start != null) arrowHead(graphics, start.from().getX(), start.from().getY(),
                            start.tip().getX(), start.tip().getY(), item.lineWidth);
                    if (item.endArrow && end != null) arrowHead(graphics, end.from().getX(), end.from().getY(),
                            end.tip().getX(), end.tip().getY(), item.lineWidth);
                }
            } else {
                if (item.kind != Kind.LINE && item.fill != null) {
                    graphics.setColor(item.fill);
                    graphics.fill(geometry);
                }
                if (item.line != null && item.lineWidth > 0) {
                    graphics.setColor(item.line);
                    graphics.setStroke(stroke(item));
                    graphics.draw(geometry);
                    if (item.kind == Kind.LINE) {
                        if (item.startArrow) arrowHead(graphics, item.width, item.height, 0, 0, item.lineWidth);
                        if (item.endArrow) arrowHead(graphics, 0, 0, item.width, item.height, item.lineWidth);
                    }
                }
            }
            // XLS child anchors use group coordinates; text and insets remain physical point sizes.
            double insetScale = item.textScale;
            Rectangle2D textArea = item.presetGeometry == null ? new Rectangle2D.Double(0, 0, item.width, item.height)
                    : item.presetGeometry.textBounds();
            Rectangle2D textBox = new Rectangle2D.Double(textArea.getX() + item.leftInset * insetScale,
                    textArea.getY() + item.topInset * insetScale,
                    Math.max(0.1, textArea.getWidth() - (item.leftInset + item.rightInset) * insetScale),
                    Math.max(0.1, textArea.getHeight() - (item.topInset + item.bottomInset) * insetScale));
            if (item.kind == Kind.UNSUPPORTED || item.kind == Kind.PICTURE) {
                graphics.setColor(new Color(160, 40, 40));
                List<DrawingText.Run> text = new ArrayList<>(item.text);
                text.add(0, new DrawingText.Run("[" + item.placeholder + "]\n", "Noto Sans CJK JP", 11, false, false, null));
                DrawingText.draw(graphics, text, textBox, item.alignment);
            } else if (item.textContent != null) {
                DrawingTextLayout.draw(graphics, item.textContent, textBox, item.textScale);
            } else {
                DrawingText.draw(graphics, item.text, textBox, item.alignment);
            }
        } finally { graphics.dispose(); }
    }

    private static Shape geometry(Item item) {
        if (item.presetGeometry != null) return item.presetGeometry.outline();
        double width = item.width, height = item.height;
        return switch (item.kind) {
            case ROUND_RECTANGLE -> new RoundRectangle2D.Double(0, 0, width, height, Math.min(width, height) * .25, Math.min(width, height) * .25);
            case ELLIPSE -> new Ellipse2D.Double(0, 0, width, height);
            case LINE -> new Line2D.Double(0, 0, width, height);
            case RIGHT_ARROW, LEFT_ARROW, UP_ARROW, DOWN_ARROW -> blockArrow(item.kind, width, height);
            default -> new Rectangle2D.Double(0, 0, width, height);
        };
    }

    private static boolean line(Item item) {
        return item.kind == Kind.LINE || item.kind == Kind.PRESET && DrawingPresetGeometry.connector(item.preset);
    }

    private static BasicStroke stroke(Item item) {
        return item.dashed ? new BasicStroke(item.lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{4, 3}, 0)
                : new BasicStroke(item.lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private static Shape blockArrow(Kind kind, double width, double height) {
        Path2D path = new Path2D.Double();
        path.moveTo(0, .25); path.lineTo(.6, .25); path.lineTo(.6, 0); path.lineTo(1, .5);
        path.lineTo(.6, 1); path.lineTo(.6, .75); path.lineTo(0, .75); path.closePath();
        AffineTransform transform = AffineTransform.getScaleInstance(width, height);
        if (kind == Kind.LEFT_ARROW) { transform.translate(1, 0); transform.scale(-1, 1); }
        if (kind == Kind.DOWN_ARROW) { transform.translate(1, 0); transform.rotate(Math.PI / 2); }
        if (kind == Kind.UP_ARROW) { transform.translate(0, 1); transform.rotate(-Math.PI / 2); }
        return transform.createTransformedShape(path);
    }

    private static void arrowHead(Graphics2D graphics, double fromX, double fromY, double x, double y, float lineWidth) {
        double angle = Math.atan2(y - fromY, x - fromX), size = Math.max(6, lineWidth * 4);
        Path2D head = new Path2D.Double();
        head.moveTo(x, y);
        head.lineTo(x - Math.cos(angle - .4) * size, y - Math.sin(angle - .4) * size);
        head.lineTo(x - Math.cos(angle + .4) * size, y - Math.sin(angle + .4) * size);
        head.closePath(); graphics.fill(head);
    }

    static void verifyPicture(byte[] bytes, ConversionWorkspace workspace) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unrecognized image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > workspace.limits().maxImagePixels())
                    throw new ConversionException(413, "IMAGE_PIXEL_LIMIT", "An image exceeds the configured pixel limit");
            } finally { reader.dispose(); }
        }
    }

    private static BufferedImage decode(byte[] bytes, ConversionWorkspace workspace) throws IOException {
        verifyPicture(bytes, workspace);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("Unreadable image");
        return image;
    }
}
