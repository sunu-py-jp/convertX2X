package com.convertx2x.excel2img.drawing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.ddf.EscherSpRecord;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.*;
import org.w3c.dom.Node;

/** Bounded, best-effort Java2D rendering of embedded Excel pictures and basic drawings. */
public final class ExcelDrawingLayer {
    public record Warning(String code, String message) { }
    private enum Kind { RECT, ROUND_RECT, ELLIPSE, DIAMOND, LINE, RIGHT, LEFT, UP, DOWN, BOTH, TEXT, PICTURE, UNSUPPORTED }
    private static final double EMU = 12700d;
    private final List<Item> items = new ArrayList<>();
    private final Sheet sheet;
    private final SheetGeometry coordinates;
    private final long maxSourceImagePixels;
    private final Consumer<Warning> warnings;
    private final Set<String> emittedWarnings = new HashSet<>();
    private boolean unsupported;

    private ExcelDrawingLayer(Sheet sheet, SheetGeometry coordinates, long limit, Consumer<Warning> warnings) {
        this.sheet = Objects.requireNonNull(sheet);
        this.coordinates = Objects.requireNonNull(coordinates);
        if (limit < 1) throw new IllegalArgumentException("maxSourceImagePixels must be positive");
        this.maxSourceImagePixels = limit;
        this.warnings = Objects.requireNonNull(warnings);
    }

    /** Retains picture references and placement metadata, never decoded image buffers. */
    public static ExcelDrawingLayer prepare(Sheet sheet, SheetGeometry coordinates,
                                           long maxSourceImagePixels, Consumer<Warning> warnings) {
        var layer = new ExcelDrawingLayer(sheet, coordinates, maxSourceImagePixels, warnings);
        try {
            var drawing = sheet.getDrawingPatriarch();
            if (drawing != null) for (Shape shape : drawing) layer.read(shape, new AffineTransform(), false, 0);
        } catch (RuntimeException ex) {
            layer.placeholder("DRAWING_UNREADABLE", "Drawing content could not be read", new Rectangle2D.Double(0, 0, 180, 50));
        }
        if (sheet instanceof HSSFSheet hssf) {
            try {
                if (HSSFChart.getSheetCharts(hssf).length > 0)
                    layer.placeholder("CHART_UNSUPPORTED", "XLS chart rendering is unsupported", new Rectangle2D.Double(0, 0, 180, 50));
            } catch (RuntimeException ex) {
                layer.warn("CHART_UNREADABLE", "XLS chart metadata could not be read", true);
            }
        }
        return layer;
    }

    public Rectangle2D bounds() {
        Rectangle2D result = null;
        for (Item item : items) result = result == null ? item.bounds() : result.createUnion(item.bounds());
        return result == null ? new Rectangle2D.Double() : result;
    }

    /** Includes unreadable or unsupported pictures discovered during draw(). */
    public boolean hasUnsupported() { return unsupported; }

    /**
     * Conservatively checks drawing coverage in sheet points, without decoding pictures.
     * Each node's transformed bounds are checked separately, preserving gaps between drawings.
     * Stroke/arrow padding also gives horizontal and vertical connectors nonzero coverage.
     * Transparent drawings count as coverage: callers can safely omit underlying PDF search text.
     */
    public boolean intersectsDrawing(Rectangle2D region) {
        if (region == null || region.isEmpty() || !Double.isFinite(region.getX()) || !Double.isFinite(region.getY())
                || !Double.isFinite(region.getWidth()) || !Double.isFinite(region.getHeight())) return false;
        for (Item item : items) if (item.bounds().intersects(region)) return true;
        return false;
    }

    /** The caller supplies its sheet-to-output transform and clip. Pictures decode one at a time. */
    public void draw(Graphics2D graphics) {
        for (Item item : items) {
            // Avoid allocating a source raster for drawings entirely outside this output tile.
            if (graphics.getClip() != null && !graphics.getClip().intersects(item.bounds())) continue;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.transform(item.transform);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                if (item.kind == Kind.PICTURE) {
                    try { drawPicture(g, item); }
                    catch (ImageLimitException ex) { fail(item, "IMAGE_SOURCE_PIXEL_LIMIT", "Embedded picture exceeds the source pixel limit"); drawPlaceholder(g, item); }
                    catch (IOException | RuntimeException ex) { fail(item, "IMAGE_UNREADABLE", "Embedded picture could not be decoded; external images are not fetched"); drawPlaceholder(g, item); }
                } else if (item.kind == Kind.UNSUPPORTED) drawPlaceholder(g, item);
                else {
                    java.awt.Shape shape = geometry(item);
                    if (item.kind != Kind.LINE && item.fill != null) { g.setColor(item.fill); g.fill(shape); }
                    if (item.line != null && item.lineWidth > 0) {
                        g.setColor(item.line);
                        g.setStroke(item.dashed
                                ? new BasicStroke(item.lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{4, 3}, 0)
                                : new BasicStroke(item.lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(shape);
                        if (item.kind == Kind.LINE) {
                            arrow(g, item.width, item.height, 0, 0, item.lineWidth, item.startArrow);
                            arrow(g, 0, 0, item.width, item.height, item.lineWidth, item.endArrow);
                        }
                    }
                    drawText(g, item);
                }
            } catch (RuntimeException ex) {
                fail(item, "DRAWING_RENDER_FAILED", "Drawing could not be rendered completely");
                drawPlaceholder(g, item);
            } finally { g.dispose(); }
        }
    }

    private void read(Shape shape, AffineTransform parent, boolean childShape, int depth) {
        if (shape instanceof HSSFComment) return;
        if (depth > 32 || items.size() >= 10000) {
            if (!emittedWarnings.contains("DRAWING_COMPLEXITY_LIMIT")) {
                placeholder("DRAWING_COMPLEXITY_LIMIT", "Drawing complexity limit reached", new Rectangle2D.Double(0, 0, 180, 50));
                emittedWarnings.add("DRAWING_COMPLEXITY_LIMIT");
            }
            return;
        }
        Item item = new Item();
        try {
            Node xml = xml(shape);
            if (hidden(shape, xml)) return;
            Rectangle2D anchor;
            try { anchor = !childShape && shape.getAnchor() instanceof ClientAnchor client ? anchor(client) : localAnchor(shape, xml); }
            catch (RuntimeException ex) { anchor = null; }
            if (anchor == null) {
                anchor = new Rectangle2D.Double(0, 0, 180, 50);
                warn("DRAWING_POSITION_UNKNOWN", "Drawing placement is unknown; a fallback position is used", true);
            }
            item.width = anchor.getWidth(); item.height = anchor.getHeight();
            item.transform = new AffineTransform(parent);
            item.transform.translate(anchor.getX(), anchor.getY());
            double rotation = shape instanceof HSSFShape hssf ? escher(hssf, EscherPropertyTypes.TRANSFORM__ROTATION, 0) / 65536d
                    : number(transform(xml), "rot", 0) / 60000;
            boolean flipH = flip(shape, xml, true), flipV = flip(shape, xml, false);
            item.transform.translate(item.width / 2, item.height / 2);
            item.transform.rotate(Math.toRadians(rotation));
            item.transform.scale(flipH ? -1 : 1, flipV ? -1 : 1);
            item.transform.translate(-item.width / 2, -item.height / 2);
            if (shape instanceof XSSFShapeGroup group) {
                Node xfrm = transform(xml), offset = child(xfrm, "chOff"), extent = child(xfrm, "chExt");
                double w = number(extent, "cx", 0) / EMU, h = number(extent, "cy", 0) / EMU;
                if (offset == null || w <= 0 || h <= 0) throw new IllegalArgumentException("Invalid group coordinates");
                item.transform.scale(item.width / w, item.height / h);
                item.transform.translate(-number(offset, "x", 0) / EMU, -number(offset, "y", 0) / EMU);
                for (XSSFShape member : group) read(member, item.transform, true, depth + 1);
                return;
            }
            if (shape instanceof HSSFShapeGroup group) {
                double w = group.getX2() - (double) group.getX1(), h = group.getY2() - (double) group.getY1();
                if (w <= 0 || h <= 0) throw new IllegalArgumentException("Invalid group coordinates");
                item.transform.scale(item.width / w, item.height / h);
                item.transform.translate(-group.getX1(), -group.getY1());
                for (HSSFShape member : group) read(member, item.transform, true, depth + 1);
                return;
            }
            if (shape instanceof Picture picture) readPicture(picture, xml, item);
            else readShape(shape, xml, item);
            if (shape instanceof HSSFShape && childShape) {
                double scale = Math.sqrt(Math.abs(parent.getDeterminant()));
                if (scale > 0) item.textScale = 1 / scale;
            }
            if ((flipH || flipV) && !item.text.isEmpty())
                warn("DRAWING_TEXT_APPROXIMATED", "Shape text follows the saved shape flip", false);
        } catch (RuntimeException ex) {
            item.kind = Kind.UNSUPPORTED;
            if (item.width <= 0 && item.height <= 0) { item.width = 180; item.height = 50; }
            fail(item, "DRAWING_UNREADABLE", "Drawing metadata could not be read");
        }
        items.add(item);
    }

    private void readPicture(Picture picture, Node xml, Item item) {
        item.picture = picture;
        var data = picture.getPictureData(); // Relationship lookup only: do not obtain or decode image bytes here.
        if (data == null) { fail(item, "IMAGE_UNREADABLE", "Embedded picture is missing; external pictures are not fetched"); return; }
        int type = data.getPictureType();
        if (type != Workbook.PICTURE_TYPE_PNG && type != Workbook.PICTURE_TYPE_JPEG && type != Workbook.PICTURE_TYPE_DIB
                && type != XSSFWorkbook.PICTURE_TYPE_GIF && type != XSSFWorkbook.PICTURE_TYPE_TIFF && type != XSSFWorkbook.PICTURE_TYPE_BMP) {
            fail(item, "IMAGE_FORMAT_UNSUPPORTED", "Embedded vector or unrecognized image format is unsupported"); return;
        }
        item.kind = Kind.PICTURE;
        if (picture instanceof HSSFPicture hssf) {
            if (hssf.getShapeType() != HSSFShapeTypes.PictureFrame
                    || hssf.getOptRecord().lookup(EscherPropertyTypes.GEOMETRY__VERTICES) != null
                    || hssf.getOptRecord().lookup(EscherPropertyTypes.GEOMETRY__SEGMENTINFO) != null
                    || hssf.getOptRecord().lookup(EscherPropertyTypes.BLIP__TRANSPARENTCOLOR) != null) {
                fail(item, "IMAGE_MASK_UNSUPPORTED", "Picture mask is unsupported; the original hidden area is not exposed"); return;
            }
            item.crop = new double[]{escher(hssf, EscherPropertyTypes.BLIP__CROPFROMLEFT, 0) / 65536d,
                    escher(hssf, EscherPropertyTypes.BLIP__CROPFROMTOP, 0) / 65536d,
                    escher(hssf, EscherPropertyTypes.BLIP__CROPFROMRIGHT, 0) / 65536d,
                    escher(hssf, EscherPropertyTypes.BLIP__CROPFROMBOTTOM, 0) / 65536d};
        } else {
            Node crop = child(child(xml, "blipFill"), "srcRect");
            try {
                item.crop = new double[]{cropFraction(crop, "l"), cropFraction(crop, "t"),
                        cropFraction(crop, "r"), cropFraction(crop, "b")};
            } catch (IllegalArgumentException ex) {
                fail(item, "IMAGE_CROP_UNSUPPORTED", "Picture crop cannot be reproduced safely; a placeholder is shown"); return;
            }
            Node properties = child(xml, "spPr");
            String preset = attr(child(properties, "prstGeom"), "prst");
            if (child(properties, "custGeom") != null || !preset.isEmpty() && !preset.equals("rect")) {
                fail(item, "IMAGE_MASK_UNSUPPORTED", "Picture mask is unsupported; the original hidden area is not exposed"); return;
            }
            Node fill = child(xml, "blipFill");
            if (child(fill, "tile") != null) warn("IMAGE_EFFECTS_SIMPLIFIED", "Tiled picture fill is fitted to its saved anchor", false);
            if (child(properties, "effectLst") != null || hasElementChildren(child(fill, "blip")))
                warn("IMAGE_EFFECTS_SIMPLIFIED", "Picture effects are simplified", false);
        }
        if (item.crop[0] < 0 || item.crop[1] < 0 || item.crop[2] < 0 || item.crop[3] < 0
                || item.crop[0] + item.crop[2] >= 1 || item.crop[1] + item.crop[3] >= 1)
            fail(item, "IMAGE_CROP_UNSUPPORTED", "Picture crop cannot be reproduced safely; a placeholder is shown");
    }

    private void readShape(Shape shape, Node xml, Item item) {
        if (shape instanceof XSSFSimpleShape simple && !(shape instanceof org.apache.poi.ss.usermodel.ObjectData)) {
            item.text = ShapeText.readXssf(xml, sheet.getWorkbook());
            Node properties = child(xml, "spPr");
            item.kind = shape instanceof XSSFTextBox ? Kind.TEXT : kind(attr(child(properties, "prstGeom"), "prst"));
            style(xml, item);
            Node body = child(child(xml, "txBody"), "bodyPr");
            item.left = number(body, "lIns", 91440) / EMU; item.right = number(body, "rIns", 91440) / EMU;
            item.top = number(body, "tIns", 45720) / EMU; item.bottom = number(body, "bIns", 45720) / EMU;
            item.vertical = switch (attr(body, "anchor")) { case "ctr" -> 2; case "b" -> 3; default -> 1; };
            String vertical = attr(body, "vert");
            if (!vertical.isEmpty() && !vertical.equals("horz") || number(body, "rot", 0) != 0)
                warn("DRAWING_TEXT_APPROXIMATED", "Vertical or independently rotated shape text is rendered horizontally", false);
        } else if (shape instanceof XSSFConnector) {
            String preset = attr(child(child(xml, "spPr"), "prstGeom"), "prst");
            item.kind = preset.equals("line") || preset.equals("straightConnector1") ? Kind.LINE : Kind.UNSUPPORTED;
            style(xml, item);
        } else if (shape instanceof HSSFSimpleShape simple && !(shape instanceof org.apache.poi.ss.usermodel.ObjectData)) {
            item.kind = hssfKind(simple.getShapeType());
            item.text = ShapeText.readHssf(simple, sheet.getWorkbook());
            item.fill = simple.isNoFill() ? null : hssfColor(simple.getFillColor());
            item.line = simple.getLineStyle() == HSSFShape.LINESTYLE_NONE ? null : hssfColor(simple.getLineStyleColor());
            item.lineWidth = (float) Math.max(0, Math.min(100, simple.getLineWidth() / EMU));
            item.dashed = simple.getLineStyle() != HSSFShape.LINESTYLE_SOLID;
            item.startArrow = hssfArrow(escher(simple, EscherPropertyTypes.LINESTYLE__LINESTARTARROWHEAD, 0));
            item.endArrow = hssfArrow(escher(simple, EscherPropertyTypes.LINESTYLE__LINEENDARROWHEAD, 0));
            item.left = escher(simple, EscherPropertyTypes.TEXT__TEXTLEFT, 91440) / EMU;
            item.right = escher(simple, EscherPropertyTypes.TEXT__TEXTRIGHT, 91440) / EMU;
            item.top = escher(simple, EscherPropertyTypes.TEXT__TEXTTOP, 45720) / EMU;
            item.bottom = escher(simple, EscherPropertyTypes.TEXT__TEXTBOTTOM, 45720) / EMU;
            if (simple instanceof HSSFTextbox box) { item.kind = Kind.TEXT; item.vertical = box.getVerticalAlignment(); }
            warn("XLS_DRAWING_APPROXIMATED", "XLS drawing style and effects are approximated", false);
        }
        if (item.kind == Kind.UNSUPPORTED)
            fail(item, shape instanceof XSSFGraphicFrame ? "GRAPHIC_FRAME_UNSUPPORTED" : "SHAPE_UNSUPPORTED",
                    shape instanceof XSSFGraphicFrame ? "Chart or SmartArt rendering is unsupported" : "Unsupported shape; available text is retained");
    }

    private void style(Node xml, Item item) {
        Node properties = child(xml, "spPr"), style = child(xml, "style");
        item.fill = child(properties, "noFill") != null ? null : color(child(properties, "solidFill"), color(child(style, "fillRef"), Color.WHITE, sheet.getWorkbook()), sheet.getWorkbook());
        Node line = child(properties, "ln");
        item.line = child(line, "noFill") != null ? null : color(child(line, "solidFill"), color(child(style, "lnRef"), Color.BLACK, sheet.getWorkbook()), sheet.getWorkbook());
        item.lineWidth = (float) Math.max(0, Math.min(100, number(line, "w", EMU) / EMU));
        item.startArrow = attr(child(line, "headEnd"), "type"); item.endArrow = attr(child(line, "tailEnd"), "type");
        String dash = attr(child(line, "prstDash"), "val"); item.dashed = !dash.isEmpty() && !dash.equals("solid");
        if (child(properties, "gradFill") != null || child(properties, "blipFill") != null || child(properties, "pattFill") != null
                || hasElementChildren(child(properties, "effectLst")) || child(properties, "effectDag") != null
                || child(properties, "scene3d") != null || child(properties, "sp3d") != null
                || hasElementChildren(child(child(properties, "prstGeom"), "avLst")))
            warn("DRAWING_EFFECTS_SIMPLIFIED", "Complex fill, shape adjustments and drawing effects are simplified", false);
    }

    private void drawPicture(Graphics2D g, Item item) throws IOException {
        byte[] bytes = item.picture.getPictureData().getData();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image encoding");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpeg", "jpg", "gif", "bmp", "tif", "tiff").contains(format)) throw new IOException("Unsupported raster encoding");
                reader.setInput(input, true, true);
                long width = reader.getWidth(0), height = reader.getHeight(0);
                // Check source dimensions before read() allocates its raster. Division avoids overflow.
                if (width < 1 || height < 1 || width > maxSourceImagePixels / height) throw new ImageLimitException();
                double[] crop = item.crop;
                int left = (int) Math.ceil(width * crop[0]), top = (int) Math.ceil(height * crop[1]);
                int right = (int) Math.floor(width * (1 - crop[2])), bottom = (int) Math.floor(height * (1 - crop[3]));
                if (right <= left || bottom <= top) throw new IOException("The visible crop contains no complete source pixels");
                var parameters = reader.getDefaultReadParam();
                // Decode only the visible source region. This also prevents interpolation sampling
                // pixels outside the crop at its boundary. Fractional crop edges round inward.
                parameters.setSourceRegion(new java.awt.Rectangle(left, top, right - left, bottom - top));
                BufferedImage image = reader.read(0, parameters);
                if (image == null) throw new IOException("Unreadable image");
                try {
                    g.clip(new Rectangle2D.Double(0, 0, item.width, item.height));
                    AffineTransform placement = AffineTransform.getScaleInstance(item.width / image.getWidth(), item.height / image.getHeight());
                    g.drawImage(image, placement, null);
                } finally { image.flush(); }
            } finally { reader.dispose(); }
        }
    }

    private void drawText(Graphics2D g, Item item) {
        if (item.text.isEmpty()) return;
        double scale = item.textScale;
        double left = Math.max(0, Math.min(item.width / 3, item.left * scale));
        double right = Math.max(0, Math.min(item.width / 3, item.right * scale));
        double top = Math.max(0, Math.min(item.height / 3, item.top * scale));
        double bottom = Math.max(0, Math.min(item.height / 3, item.bottom * scale));
        var box = new Rectangle2D.Double(left, top, Math.max(.1, item.width - left - right), Math.max(.1, item.height - top - bottom));
        if (ShapeText.draw(g, item.text, box, item.vertical, scale))
            warn("DRAWING_TEXT_FITTED", "Shape text was reduced to fit its saved bounds", false);
    }

    private void drawPlaceholder(Graphics2D g, Item item) {
        double w = Math.max(12, item.width), h = Math.max(12, item.height);
        g.setColor(new Color(255, 245, 235, 220)); g.fill(new Rectangle2D.Double(0, 0, w, h));
        g.setColor(new Color(160, 65, 20)); g.setStroke(new BasicStroke(1)); g.draw(new Rectangle2D.Double(0, 0, w, h));
        var text = new ArrayList<ShapeText.Paragraph>();
        text.add(ShapeText.plain("[" + item.placeholder + "]", 9)); text.addAll(item.text);
        ShapeText.draw(g, text, new Rectangle2D.Double(2, 2, Math.max(1, w - 4), Math.max(1, h - 4)), 1, 1);
    }

    private void placeholder(String code, String message, Rectangle2D bounds) {
        Item item = new Item(); item.width = bounds.getWidth(); item.height = bounds.getHeight();
        item.transform.translate(bounds.getX(), bounds.getY()); fail(item, code, message); items.add(item);
    }
    private void fail(Item item, String code, String message) { item.kind = Kind.UNSUPPORTED; item.placeholder = message; warn(code, message, true); }
    private void warn(String code, String message, boolean unsupported) {
        this.unsupported |= unsupported;
        if (emittedWarnings.add(code + "\n" + message)) warnings.accept(new Warning(code, message));
    }

    private Rectangle2D anchor(ClientAnchor anchor) {
        if (anchor instanceof XSSFClientAnchor xssf) {
            if (xssf.getPosition() != null && xssf.getSize() != null)
                return rectangle(value(xssf.getPosition().getX()) / EMU, value(xssf.getPosition().getY()) / EMU,
                        xssf.getSize().getCx() / EMU, xssf.getSize().getCy() / EMU);
            if (xssf.getFrom() != null && xssf.getSize() != null) {
                var from = xssf.getFrom();
                return rectangle(coordinates.x(from.getCol()) + value(from.getColOff()) / EMU,
                        coordinates.y(from.getRow()) + value(from.getRowOff()) / EMU,
                        xssf.getSize().getCx() / EMU, xssf.getSize().getCy() / EMU);
            }
        }
        int c1 = anchor.getCol1(), c2 = anchor.getCol2(), r1 = anchor.getRow1(), r2 = anchor.getRow2();
        boolean xssf = anchor instanceof XSSFClientAnchor;
        double x1 = coordinates.x(c1) + (xssf ? anchor.getDx1() / EMU : (coordinates.x(c1 + 1) - coordinates.x(c1)) * anchor.getDx1() / 1024d);
        double x2 = coordinates.x(c2) + (xssf ? anchor.getDx2() / EMU : (coordinates.x(c2 + 1) - coordinates.x(c2)) * anchor.getDx2() / 1024d);
        double y1 = coordinates.y(r1) + (xssf ? anchor.getDy1() / EMU : (coordinates.y(r1 + 1) - coordinates.y(r1)) * anchor.getDy1() / 256d);
        double y2 = coordinates.y(r2) + (xssf ? anchor.getDy2() / EMU : (coordinates.y(r2 + 1) - coordinates.y(r2)) * anchor.getDy2() / 256d);
        return rectangle(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    private static Rectangle2D localAnchor(Shape shape, Node xml) {
        if (shape instanceof HSSFShape && shape.getAnchor() != null) {
            var a = shape.getAnchor();
            return rectangle(Math.min(a.getDx1(), a.getDx2()), Math.min(a.getDy1(), a.getDy2()),
                    Math.abs(a.getDx2() - (double) a.getDx1()), Math.abs(a.getDy2() - (double) a.getDy1()));
        }
        Node xfrm = transform(xml), offset = child(xfrm, "off"), extent = child(xfrm, "ext");
        return offset == null || extent == null ? null : rectangle(number(offset, "x", 0) / EMU, number(offset, "y", 0) / EMU,
                number(extent, "cx", 0) / EMU, number(extent, "cy", 0) / EMU);
    }
    private static Rectangle2D rectangle(double x, double y, double w, double h) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h) || w < 0 || h < 0)
            throw new IllegalArgumentException("Invalid drawing bounds");
        return new Rectangle2D.Double(x, y, w, h);
    }
    private static java.awt.Shape geometry(Item item) {
        double w = item.width, h = item.height;
        return switch (item.kind) {
            case ROUND_RECT -> new RoundRectangle2D.Double(0, 0, w, h, Math.min(w, h) * .25, Math.min(w, h) * .25);
            case ELLIPSE -> new Ellipse2D.Double(0, 0, w, h);
            case LINE -> new Line2D.Double(0, 0, w, h);
            case DIAMOND -> polygon(new double[]{.5, 0, 1, .5, .5, 1, 0, .5}, w, h);
            case RIGHT, LEFT, UP, DOWN, BOTH -> blockArrow(item.kind, w, h);
            default -> new Rectangle2D.Double(0, 0, w, h);
        };
    }
    private static java.awt.Shape polygon(double[] points, double w, double h) {
        Path2D path = new Path2D.Double(); path.moveTo(points[0] * w, points[1] * h);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i] * w, points[i + 1] * h);
        path.closePath(); return path;
    }
    private static java.awt.Shape blockArrow(Kind kind, double w, double h) {
        if (kind == Kind.BOTH) return polygon(new double[]{0, .5, .25, 0, .25, .25, .75, .25, .75, 0, 1, .5, .75, 1, .75, .75, .25, .75, .25, 1}, w, h);
        var arrow = polygon(new double[]{0, .25, .6, .25, .6, 0, 1, .5, .6, 1, .6, .75, 0, .75}, 1, 1);
        AffineTransform t = AffineTransform.getScaleInstance(w, h);
        if (kind == Kind.LEFT) { t.translate(1, 0); t.scale(-1, 1); }
        if (kind == Kind.DOWN) { t.translate(1, 0); t.rotate(Math.PI / 2); }
        if (kind == Kind.UP) { t.translate(0, 1); t.rotate(-Math.PI / 2); }
        return t.createTransformedShape(arrow);
    }
    private static void arrow(Graphics2D g, double fromX, double fromY, double x, double y, float lineWidth, String type) {
        if (type == null || type.isBlank() || type.equals("none")) return;
        double angle = Math.atan2(y - fromY, x - fromX), size = Math.max(6, lineWidth * 4);
        Graphics2D local = (Graphics2D) g.create();
        try {
            local.translate(x, y); local.rotate(angle);
            if (type.equals("oval")) { local.fill(new Ellipse2D.Double(-size, -size / 2, size, size)); return; }
            if (type.equals("diamond")) { local.fill(polygon(new double[]{0, 0, -.5, -.4, -1, 0, -.5, .4}, size, size)); return; }
            Path2D head = new Path2D.Double(); head.moveTo(-size, -size * .4); head.lineTo(0, 0); head.lineTo(-size, size * .4);
            if (type.equals("arrow")) local.draw(head); else { head.closePath(); local.fill(head); }
        } finally { local.dispose(); }
    }
    private static String hssfArrow(int value) { return switch (value) { case 0 -> "none"; case 3 -> "diamond"; case 4 -> "oval"; case 5 -> "arrow"; default -> "triangle"; }; }
    private static Kind kind(String type) {
        return switch (type) {
            case "rect" -> Kind.RECT; case "roundRect" -> Kind.ROUND_RECT; case "ellipse" -> Kind.ELLIPSE; case "diamond" -> Kind.DIAMOND;
            case "line", "straightConnector1" -> Kind.LINE; case "rightArrow" -> Kind.RIGHT; case "leftArrow" -> Kind.LEFT;
            case "upArrow" -> Kind.UP; case "downArrow" -> Kind.DOWN; case "leftRightArrow" -> Kind.BOTH; default -> Kind.UNSUPPORTED;
        };
    }
    private static Kind hssfKind(int type) {
        return switch (type) {
            case HSSFShapeTypes.Rectangle -> Kind.RECT; case HSSFShapeTypes.RoundRectangle -> Kind.ROUND_RECT;
            case HSSFShapeTypes.Ellipse -> Kind.ELLIPSE; case HSSFShapeTypes.Diamond -> Kind.DIAMOND;
            case HSSFShapeTypes.Line, HSSFShapeTypes.StraightConnector1 -> Kind.LINE;
            case HSSFShapeTypes.Arrow, HSSFShapeTypes.ThickArrow -> Kind.RIGHT; case HSSFShapeTypes.LeftArrow -> Kind.LEFT;
            case HSSFShapeTypes.UpArrow -> Kind.UP; case HSSFShapeTypes.DownArrow -> Kind.DOWN;
            case HSSFShapeTypes.LeftRightArrow -> Kind.BOTH; case HSSFShapeTypes.TextBox -> Kind.TEXT; default -> Kind.UNSUPPORTED;
        };
    }
    private static Node xml(Shape shape) {
        if (shape instanceof XSSFPicture picture) return picture.getCTPicture().getDomNode();
        if (shape instanceof XSSFSimpleShape simple) return simple.getCTShape().getDomNode();
        if (shape instanceof XSSFConnector connector) return connector.getCTConnector().getDomNode();
        if (shape instanceof XSSFShapeGroup group) return group.getCTGroupShape().getDomNode();
        if (shape instanceof XSSFGraphicFrame frame) return frame.getCTGraphicalObjectFrame().getDomNode();
        return null;
    }
    static Node child(Node parent, String name) {
        if (parent != null) for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (name.equals(n.getLocalName())) return n;
        return null;
    }
    static String attr(Node node, String name) {
        if (node == null || node.getAttributes() == null) return "";
        Node value = node.getAttributes().getNamedItem(name); return value == null ? "" : value.getNodeValue();
    }
    static double number(Node node, String name, double fallback) {
        try { double v = Double.parseDouble(attr(node, name)); return Double.isFinite(v) ? v : fallback; }
        catch (NumberFormatException ex) { return fallback; }
    }
    private static double value(Object value) { return Double.parseDouble(value.toString()); }
    private static double cropFraction(Node crop, String name) {
        String text = attr(crop, name).trim();
        if (text.isEmpty()) return 0;
        double value = text.endsWith("%") ? Double.parseDouble(text.substring(0, text.length() - 1)) / 100
                : Double.parseDouble(text) / 100000;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid crop");
        return value;
    }
    private static boolean hasElementChildren(Node parent) {
        if (parent != null) for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeType() == Node.ELEMENT_NODE) return true;
        return false;
    }
    private static Node transform(Node xml) {
        Node properties = child(xml, "spPr"); if (properties == null) properties = child(xml, "grpSpPr");
        return properties == null ? child(xml, "xfrm") : child(properties, "xfrm");
    }
    private static Node nonVisual(Node xml) {
        for (String name : List.of("nvSpPr", "nvPicPr", "nvGrpSpPr", "nvCxnSpPr", "nvGraphicFramePr")) {
            Node result = child(child(xml, name), "cNvPr"); if (result != null) return result;
        }
        return null;
    }
    private static boolean hidden(Shape shape, Node xml) {
        String value = attr(nonVisual(xml), "hidden");
        return value.equals("1") || value.equals("true") || shape instanceof HSSFShape hssf && (escher(hssf, EscherPropertyTypes.GROUPSHAPE__HIDDEN, 0) & 2) != 0;
    }
    private static boolean flip(Shape shape, Node xml, boolean horizontal) {
        if (shape instanceof HSSFShape hssf) {
            EscherSpRecord record = hssfContainer(hssf).getChildById(EscherSpRecord.RECORD_ID);
            return record != null && (record.getFlags() & (horizontal ? EscherSpRecord.FLAG_FLIPHORIZ : EscherSpRecord.FLAG_FLIPVERT)) != 0;
        }
        String value = attr(transform(xml), horizontal ? "flipH" : "flipV"); return value.equals("true") || value.equals("1");
    }
    private static int escher(HSSFShape shape, EscherPropertyTypes type, int fallback) {
        EscherOptRecord options = shape.getOptRecord();
        if (options == null) options = hssfContainer(shape).getChildById(EscherOptRecord.RECORD_ID);
        EscherSimpleProperty property = options == null ? null : options.lookup(type);
        return property == null ? fallback : property.getPropertyValue();
    }
    private static EscherContainerRecord hssfContainer(HSSFShape shape) {
        List<HSSFShape> path = new ArrayList<>();
        for (HSSFShape current = shape; current != null; current = current.getParent()) path.addFirst(current);
        HSSFPatriarch patriarch = path.getFirst().getPatriarch();
        EscherContainerRecord container = (EscherContainerRecord) patriarch.getBoundAggregate().findFirstWithId(EscherContainerRecord.SPGR_CONTAINER);
        List<HSSFShape> siblings = patriarch.getChildren();
        for (HSSFShape current : path) {
            int index = siblings.indexOf(current); var records = container.getChildContainers();
            if (index < 0 || records.size() != siblings.size() + 1) throw new IllegalArgumentException("Inconsistent XLS drawing tree");
            container = records.get(index + 1);
            if (current instanceof HSSFShapeGroup group) siblings = group.getChildren();
        }
        EscherContainerRecord own = container.getChildById(EscherContainerRecord.SP_CONTAINER); return own == null ? container : own;
    }
    private static Color hssfColor(int color) { return new Color(color & 255, color >>> 8 & 255, color >>> 16 & 255); }
    static Color color(Node parent, Color fallback, Workbook workbook) {
        Node source = child(parent, "srgbClr"); Color value = fallback;
        try {
            if (source != null) value = new Color(Integer.parseInt(attr(source, "val"), 16));
            else if ((source = child(parent, "sysClr")) != null) value = new Color(Integer.parseInt(attr(source, "lastClr"), 16));
            else if ((source = child(parent, "schemeClr")) != null && workbook instanceof XSSFWorkbook xssf && xssf.getTheme() != null) {
                int index = switch (attr(source, "val")) {
                    case "lt1", "bg1" -> 0; case "dk1", "tx1" -> 1; case "lt2", "bg2" -> 2; case "dk2", "tx2" -> 3;
                    case "accent1" -> 4; case "accent2" -> 5; case "accent3" -> 6; case "accent4" -> 7;
                    case "accent5" -> 8; case "accent6" -> 9; case "hlink" -> 10; case "folHlink" -> 11; default -> -1;
                };
                XSSFColor theme = index < 0 ? null : xssf.getTheme().getThemeColor(index);
                if (theme != null && theme.getRGB() != null) { byte[] rgb = theme.getRGB(); value = new Color(rgb[0] & 255, rgb[1] & 255, rgb[2] & 255); }
            }
            if (value == null || source == null) return value;
            double mod = number(child(source, "lumMod"), "val", 100000) / 100000, off = number(child(source, "lumOff"), "val", 0) / 100000;
            double tint = number(child(source, "tint"), "val", 0) / 100000, shade = number(child(source, "shade"), "val", 100000) / 100000;
            return new Color(clamp((value.getRed() * mod + 255 * off) * shade * (1 - tint) + 255 * tint),
                    clamp((value.getGreen() * mod + 255 * off) * shade * (1 - tint) + 255 * tint),
                    clamp((value.getBlue() * mod + 255 * off) * shade * (1 - tint) + 255 * tint),
                    clamp(number(child(source, "alpha"), "val", 100000) / 100000 * 255));
        } catch (IllegalArgumentException ex) { return fallback; }
    }
    private static int clamp(double value) { return (int) Math.max(0, Math.min(255, Math.round(value))); }
    private static final class ImageLimitException extends IOException { }
    private static final class Item {
        Kind kind = Kind.UNSUPPORTED;
        double width, height, left = 7.2, right = 7.2, top = 3.6, bottom = 3.6, textScale = 1;
        int vertical = 1;
        AffineTransform transform = new AffineTransform();
        Color fill = Color.WHITE, line = Color.BLACK;
        float lineWidth = 1;
        boolean dashed;
        String startArrow = "none", endArrow = "none", placeholder = "Unsupported drawing";
        List<ShapeText.Paragraph> text = List.of();
        Picture picture;
        double[] crop = new double[4];
        Rectangle2D bounds() {
            double padding = kind == Kind.PICTURE ? 0 : Math.max(1, lineWidth / 2d);
            if (!startArrow.isBlank() && !startArrow.equals("none") || !endArrow.isBlank() && !endArrow.equals("none")) padding += Math.max(6, lineWidth * 4);
            return transform.createTransformedShape(new Rectangle2D.Double(-padding, -padding,
                    Math.max(kind == Kind.UNSUPPORTED ? 12 : 0, width) + 2 * padding,
                    Math.max(kind == Kind.UNSUPPORTED ? 12 : 0, height) + 2 * padding)).getBounds2D();
        }
    }
}
