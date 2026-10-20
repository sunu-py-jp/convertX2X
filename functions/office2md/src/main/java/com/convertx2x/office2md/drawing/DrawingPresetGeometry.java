package com.convertx2x.office2md.drawing;

import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.Guide;
import org.apache.poi.sl.draw.geom.PresetGeometries;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluates only POI's bundled DrawingML presets; document-supplied formula/XML code is never loaded. */
public final class DrawingPresetGeometry {
    private static final double EMU_PER_POINT = 12700d;
    private static final double MAX_COORDINATE = 100_000_000;
    private static final int MAX_COMMANDS = 4096;
    private static final Map<String, String> NAMES = names();

    public record Part(Shape shape, boolean filled, boolean stroked) { }
    /** The arrow tip and an adjacent point on its path, in the shape's untransformed coordinates. */
    public record Endpoint(Point2D tip, Point2D from) { }
    public record Geometry(List<Part> parts, Shape outline, Rectangle2D textBounds,
                           Endpoint start, Endpoint end) { }

    public static boolean supports(String preset) { return canonical(preset) != null; }

    public static boolean connector(String preset) {
        String name = canonical(preset);
        return name != null && (name.equals("line") || name.startsWith("straightConnector")
                || name.startsWith("bentConnector") || name.startsWith("curvedConnector"));
    }

    public static Geometry create(String preset, double width, double height) {
        return create(preset, width, height, Map.of());
    }

    /**
     * Returns null for unsupported/invalid geometry so callers can retain an explicit fallback.
     * Adjustments are numeric saved values only; formulas and arbitrary geometry are outside this API.
     */
    public static Geometry create(String preset, double width, double height, Map<String, Double> adjustments) {
        String name = canonical(preset);
        if (name == null || !finiteDimension(width) || !finiteDimension(height)
                || width == 0 && height == 0 || adjustments == null || adjustments.size() > 64) return null;
        Map<String, Guide> guides = new LinkedHashMap<>();
        for (var entry : adjustments.entrySet()) {
            Double value = entry.getValue();
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z][A-Za-z0-9]{0,31}")
                    || value == null || !Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) return null;
            Guide guide = new Guide();
            guide.setName(entry.getKey());
            guide.setFmla("val " + java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
            guides.put(entry.getKey(), guide);
        }
        try {
            var presetGeometry = PresetGeometries.getInstance().get(name);
            List<Part> parts = new ArrayList<>();
            Path2D.Double outline = new Path2D.Double();
            for (var path : presetGeometry) {
                if (parts.size() >= 64) return null;
                // Presets with a fixed path grid (e.g. flowchart symbols) use that grid;
                // all others are evaluated in EMU, matching POI's native slide renderer.
                double pathWidth = path.getW() < 0 ? Math.max(1, width * EMU_PER_POINT) : path.getW();
                double pathHeight = path.getH() < 0 ? Math.max(1, height * EMU_PER_POINT) : path.getH();
                if (pathWidth <= 0 || pathHeight <= 0) return null;
                Context context = new Context(presetGeometry,
                        new Rectangle2D.Double(0, 0, pathWidth, pathHeight), guides::get);
                AffineTransform scale = AffineTransform.getScaleInstance(width / pathWidth, height / pathHeight);
                Shape shape = scale.createTransformedShape(path.getPath(context));
                if (name.equals("round2DiagRect") || name.equals("round2SameRect"))
                    shape = omitZeroRadiusCornerSteps(shape);
                if (!safe(shape)) return null;
                parts.add(new Part(shape, path.isFilled(), path.isStroked()));
                outline.append(shape, false);
            }
            if (parts.isEmpty()) return null;
            Rectangle2D textBounds = new Rectangle2D.Double(0, 0, width, height);
            if (presetGeometry.getTextBounds() != null) {
                try {
                    Context context = new Context(presetGeometry,
                            new Rectangle2D.Double(0, 0, Math.max(1, width * EMU_PER_POINT),
                                    Math.max(1, height * EMU_PER_POINT)), guides::get);
                    Shape text = AffineTransform.getScaleInstance(1 / EMU_PER_POINT, 1 / EMU_PER_POINT)
                            .createTransformedShape(presetGeometry.getTextBounds().getPath(context));
                    if (safe(text)) textBounds = text.getBounds2D();
                } catch (RuntimeException unsupportedTextGuide) {
                    // POI's rtTriangle text rectangle references wd12, absent from BuiltInGuide.
                    // Keep its valid visible geometry and use the ordinary inset text rectangle.
                }
            }
            Endpoint[] endpoints = connector(name) ? endpoints(outline) : new Endpoint[]{null, null};
            return new Geometry(List.copyOf(parts), outline, textBounds, endpoints[0], endpoints[1]);
        } catch (RuntimeException unavailablePreset) {
            // Invalid saved adjustments must not turn a supported document into a conversion failure.
            return null;
        }
    }

    private static Shape omitZeroRadiusCornerSteps(Shape shape) {
        // POI 5.5.1 emits lineTo(NaN, NaN) for the square (zero-radius) corners
        // in these two trusted presets. The step has no displacement and can be omitted.
        Path2D.Double clean = new Path2D.Double();
        PathIterator iterator = shape.getPathIterator(null);
        double[] v = new double[6];
        while (!iterator.isDone()) {
            int kind = iterator.currentSegment(v);
            switch (kind) {
                case PathIterator.SEG_MOVETO -> clean.moveTo(v[0], v[1]);
                case PathIterator.SEG_LINETO -> {
                    if (!(Double.isNaN(v[0]) && Double.isNaN(v[1]))) clean.lineTo(v[0], v[1]);
                }
                case PathIterator.SEG_QUADTO -> clean.quadTo(v[0], v[1], v[2], v[3]);
                case PathIterator.SEG_CUBICTO -> clean.curveTo(v[0], v[1], v[2], v[3], v[4], v[5]);
                case PathIterator.SEG_CLOSE -> clean.closePath();
                default -> throw new IllegalArgumentException("Unknown path step");
            }
            iterator.next();
        }
        return clean;
    }

    private static Endpoint[] endpoints(Shape path) {
        Endpoint start = null, end = null;
        PathIterator iterator = path.getPathIterator(null, .05);
        double[] coordinates = new double[6];
        Point2D previous = null;
        int count = 0;
        while (!iterator.isDone() && count++ < MAX_COMMANDS) {
            int command = iterator.currentSegment(coordinates);
            Point2D point = new Point2D.Double(coordinates[0], coordinates[1]);
            if (command == PathIterator.SEG_MOVETO) previous = point;
            else if (command == PathIterator.SEG_LINETO) {
                if (previous != null && previous.distanceSq(point) > 1e-12) {
                    if (start == null) start = new Endpoint(previous, point);
                    end = new Endpoint(point, previous);
                }
                previous = point;
            }
            iterator.next();
        }
        return iterator.isDone() ? new Endpoint[]{start, end} : new Endpoint[]{null, null};
    }

    private static boolean safe(Shape shape) {
        PathIterator iterator = shape.getPathIterator(null);
        double[] coordinates = new double[6];
        int count = 0;
        while (!iterator.isDone()) {
            if (++count > MAX_COMMANDS) return false;
            int command = iterator.currentSegment(coordinates);
            int length = switch (command) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int i = 0; i < length; i++)
                if (!Double.isFinite(coordinates[i]) || Math.abs(coordinates[i]) > MAX_COORDINATE) return false;
            iterator.next();
        }
        return true;
    }

    private static boolean finiteDimension(double value) {
        return Double.isFinite(value) && value >= 0 && value <= MAX_COORDINATE;
    }

    private static String canonical(String preset) {
        return preset == null ? null : NAMES.get(preset.replace("_", "").toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> names() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String name : PresetGeometries.getInstance().keySet()) {
            // WordArt text-warp presets are deliberately outside the requested supported set.
            if (!name.startsWith("text")) names.put(name.toLowerCase(Locale.ROOT), name);
        }
        names.put("rectangle", "rect"); names.put("roundrectangle", "roundRect");
        names.put("oval", "ellipse"); names.put("arrow", "rightArrow");
        return Collections.unmodifiableMap(names);
    }

    private DrawingPresetGeometry() { }
}
