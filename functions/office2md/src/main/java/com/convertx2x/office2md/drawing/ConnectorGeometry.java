package com.convertx2x.office2md.drawing;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Map;

/** Conservative fallback for connectors whose saved endpoint relationship is absent. */
public final class ConnectorGeometry {
    private static final double CONTACT_TOLERANCE_POINTS = 1.5;

    private ConnectorGeometry() { }

    /**
     * Returns the only shape boundary touched by {@code point}. A point inside a shape, near a
     * shape, or touching more than one shape is deliberately not treated as a connection.
     */
    public static String uniqueBoundaryContact(Point2D point, Map<String, Rectangle2D> targets) {
        if (point == null || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) return null;
        String match = null;
        for (var candidate : targets.entrySet()) {
            Rectangle2D bounds = candidate.getValue();
            if (bounds == null || !finite(bounds) || !touchesBoundary(point, bounds)) continue;
            if (match != null) return null;
            match = candidate.getKey();
        }
        return match;
    }

    private static boolean touchesBoundary(Point2D point, Rectangle2D bounds) {
        double x = point.getX(), y = point.getY(), t = CONTACT_TOLERANCE_POINTS;
        boolean withinX = x >= bounds.getMinX() - t && x <= bounds.getMaxX() + t;
        boolean withinY = y >= bounds.getMinY() - t && y <= bounds.getMaxY() + t;
        if (!withinX || !withinY) return false;
        return Math.abs(x - bounds.getMinX()) <= t || Math.abs(x - bounds.getMaxX()) <= t
                || Math.abs(y - bounds.getMinY()) <= t || Math.abs(y - bounds.getMaxY()) <= t;
    }

    private static boolean finite(Rectangle2D bounds) {
        return Double.isFinite(bounds.getX()) && Double.isFinite(bounds.getY())
                && Double.isFinite(bounds.getWidth()) && Double.isFinite(bounds.getHeight())
                && bounds.getWidth() >= 0 && bounds.getHeight() >= 0;
    }
}
