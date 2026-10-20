package com.convertx2x.office2md.presentation;

import org.apache.poi.sl.draw.DrawSimpleShape;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.sl.draw.geom.Outline;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.Collection;

/** Positive overlap of stored geometry; mere boundary contact does not imply an annotation. */
final class PresentationOverlap {
    private static final double EPSILON = 1e-6;

    static boolean intersects(Rectangle2D left, AffineTransform leftTransform,
                              Rectangle2D right, AffineTransform rightTransform) {
        if (left == null || right == null || left.getWidth() <= 0 || left.getHeight() <= 0
                || right.getWidth() <= 0 || right.getHeight() <= 0
                || !finite(leftTransform) || !finite(rightTransform)) return false;
        Shape leftArea = leftTransform.createTransformedShape(left);
        Shape rightArea = rightTransform.createTransformedShape(right);
        if (!finite(leftArea.getBounds2D()) || !finite(rightArea.getBounds2D())) return false;
        Area intersection = new Area(leftArea);
        intersection.intersect(new Area(rightArea));
        Rectangle2D bounds = intersection.getBounds2D();
        return !intersection.isEmpty() && bounds.getWidth() > EPSILON && bounds.getHeight() > EPSILON;
    }

    static boolean intersects(Rectangle2D picture, AffineTransform pictureTransform,
                              XSLFSimpleShape shape, AffineTransform shapeTransform) {
        if (picture == null || picture.getWidth() <= 0 || picture.getHeight() <= 0
                || !finite(pictureTransform) || !finite(shapeTransform)) return false;
        Rectangle2D outer = pictureTransform.createTransformedShape(picture).getBounds2D();
        Rectangle2D candidate = shapeTransform.createTransformedShape(shape.getAnchor()).getBounds2D();
        if (!finite(outer) || !finite(candidate)) return false;
        if (candidate.getMaxX() < outer.getMinX() || candidate.getMinX() > outer.getMaxX()
                || candidate.getMaxY() < outer.getMinY() || candidate.getMinY() > outer.getMaxY()) return false;
        BufferedImage canvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(Drawable.GROUP_TRANSFORM, new AffineTransform());
            AffineTransform toPicture = pictureTransform.createInverse();
            toPicture.concatenate(shapeTransform);
            if (!finite(toPicture)) return false;
            Rectangle2D interior = new Rectangle2D.Double(picture.getX() + EPSILON, picture.getY() + EPSILON,
                    Math.max(0, picture.getWidth() - 2 * EPSILON), Math.max(0, picture.getHeight() - 2 * EPSILON));
            for (Outline outline : new Geometry(shape).outlines(graphics)) {
                Shape local = toPicture.createTransformedShape(outline.getOutline());
                if (shape instanceof XSLFConnectorShape || new Area(local).isEmpty()) {
                    if (lineIntersects(local, interior)) return true;
                } else {
                    Area intersection = new Area(local);
                    intersection.intersect(new Area(interior));
                    if (!intersection.isEmpty()) return true;
                }
            }
            return false;
        } catch (NoninvertibleTransformException | RuntimeException unavailableGeometry) {
            // Uncertain geometry stays separate rather than attaching unrelated objects.
            return false;
        } finally { graphics.dispose(); canvas.flush(); }
    }

    private static boolean finite(AffineTransform transform) {
        if (transform == null) return false;
        double[] matrix = new double[6];
        transform.getMatrix(matrix);
        for (double value : matrix) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static boolean finite(Rectangle2D bounds) {
        return Double.isFinite(bounds.getX()) && Double.isFinite(bounds.getY())
                && Double.isFinite(bounds.getWidth()) && Double.isFinite(bounds.getHeight());
    }

    private static boolean lineIntersects(Shape line, Rectangle2D interior) {
        PathIterator iterator = line.getPathIterator(null, .05);
        double[] coordinates = new double[6];
        double x = 0, y = 0, firstX = 0, firstY = 0;
        while (!iterator.isDone()) {
            int kind = iterator.currentSegment(coordinates);
            if (kind == PathIterator.SEG_MOVETO) { x = firstX = coordinates[0]; y = firstY = coordinates[1]; }
            else if (kind == PathIterator.SEG_LINETO) {
                if (interior.intersectsLine(x, y, coordinates[0], coordinates[1])) return true;
                x = coordinates[0]; y = coordinates[1];
            } else if (kind == PathIterator.SEG_CLOSE && interior.intersectsLine(x, y, firstX, firstY)) return true;
            iterator.next();
        }
        return false;
    }

    private static final class Geometry extends DrawSimpleShape {
        Geometry(XSLFSimpleShape shape) { super(shape); }
        Collection<Outline> outlines(Graphics2D graphics) { return computeOutlines(graphics); }
    }
    private PresentationOverlap() { }
}
