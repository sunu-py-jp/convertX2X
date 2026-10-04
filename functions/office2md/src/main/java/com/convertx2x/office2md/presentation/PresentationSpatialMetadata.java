package com.convertx2x.office2md.presentation;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Set;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;

/** Slide-relative location and visible bearing. Neither value implies a saved shape connection. */
final class PresentationSpatialMetadata {
    private static final String[] HORIZONTAL = {"left", "center", "right"};
    private static final String[] VERTICAL = {"top", "middle", "bottom"};
    private static final String[] BEARINGS = {"right-middle", "right-bottom", "center-bottom", "left-bottom",
            "left-middle", "left-top", "center-top", "right-top"};
    private static final Set<String> STRAIGHT_PRESETS = Set.of("line", "straightConnector1");

    private PresentationSpatialMetadata() { }

    static String positionOnSlide(Rectangle2D bounds, Rectangle2D slide) {
        if (bounds == null || slide == null || !finite(bounds.getCenterX()) || !finite(bounds.getCenterY())
                || !finite(slide.getX()) || !finite(slide.getY()) || !finite(slide.getWidth())
                || !finite(slide.getHeight()) || slide.getWidth() <= 0 || slide.getHeight() <= 0) return null;
        double x = (bounds.getCenterX() - slide.getX()) / slide.getWidth();
        double y = (bounds.getCenterY() - slide.getY()) / slide.getHeight();
        if (x < 0 || x >= 1 || y < 0 || y >= 1) return null;
        return HORIZONTAL[(int) (3 * x)] + "-" + VERTICAL[(int) (3 * y)];
    }

    static String japanesePosition(String value) {
        if (value == null) return "スライド上の位置は不明";
        return "スライド" + switch (value) {
            case "left-top" -> "左上";
            case "center-top" -> "上中央";
            case "right-top" -> "右上";
            case "left-middle" -> "左中央";
            case "center-middle" -> "中央";
            case "right-middle" -> "右中央";
            case "left-bottom" -> "左下";
            case "center-bottom" -> "下中央";
            case "right-bottom" -> "右下";
            default -> throw new IllegalArgumentException("Unknown slide position: " + value);
        };
    }

    /** Present only for a saved-unattached, one-way connector; null bearing means its path is ambiguous. */
    record VisualArrow(boolean applicable, String bearing) { }

    static VisualArrow visualArrow(XSLFConnectorShape connector, PresentationConnections.Edge edge,
                                   AffineTransform slideTransform) {
        CTConnector xml = (CTConnector) connector.getXmlObject();
        var nonVisual = xml.getNvCxnSpPr();
        var saved = nonVisual == null ? null : nonVisual.getCNvCxnSpPr();
        if (saved != null && (saved.getStCxn() != null || saved.getEndCxn() != null))
            return new VisualArrow(false, null);
        if (!edge.direction().equals("start-to-end") && !edge.direction().equals("end-to-start"))
            return new VisualArrow(false, null);

        var properties = xml.getSpPr();
        var preset = properties == null ? null : properties.getPrstGeom();
        if (preset == null || preset.getPrst() == null || !STRAIGHT_PRESETS.contains(preset.getPrst().toString()))
            return new VisualArrow(true, null);
        Rectangle2D anchor = connector.getAnchor();
        if (anchor == null || !finite(anchor.getX()) || !finite(anchor.getY())
                || !finite(anchor.getWidth()) || !finite(anchor.getHeight())) return new VisualArrow(true, null);
        Point2D start = slideTransform.transform(new Point2D.Double(anchor.getMinX(), anchor.getMinY()), null);
        Point2D end = slideTransform.transform(new Point2D.Double(anchor.getMaxX(), anchor.getMaxY()), null);
        double dx = end.getX() - start.getX(), dy = end.getY() - start.getY();
        if (edge.direction().equals("end-to-start")) { dx = -dx; dy = -dy; }
        if (!finite(dx) || !finite(dy) || Math.hypot(dx, dy) < 1e-9) return new VisualArrow(true, null);
        double angle = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
        int octant = ((int) Math.floor((angle + 22.5) / 45)) % BEARINGS.length;
        return new VisualArrow(true, BEARINGS[octant]);
    }

    static String japaneseBearing(String bearing) {
        if (bearing == null) return "矢印の先端が向く方向は不明";
        return "矢印の先端は" + switch (bearing) {
            case "right-middle" -> "右";
            case "right-bottom" -> "右下";
            case "center-bottom" -> "下";
            case "left-bottom" -> "左下";
            case "left-middle" -> "左";
            case "left-top" -> "左上";
            case "center-top" -> "上";
            case "right-top" -> "右上";
            default -> throw new IllegalArgumentException("Unknown arrow bearing: " + bearing);
        } + "を向く";
    }

    private static boolean finite(double value) { return Double.isFinite(value); }
}
