package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.CellMarkdown;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.MergedRanges;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/** Worksheet content behind overlapping drawings. Text remains independently available as Markdown. */
final class SheetCellLayer {
    /** A visual preview must not allocate millions of cell boxes for a sparse source range. */
    private static final int MAX_PREVIEW_CELLS = 10_000;
    record Region(String id, CellRangeAddress range, Rectangle2D bounds) {
        boolean overlaps(DrawingScene.Item item) {
            DrawingScene.Item rectangle = new DrawingScene.Item();
            rectangle.kind = DrawingScene.Kind.RECTANGLE;
            rectangle.width = bounds.getWidth(); rectangle.height = bounds.getHeight();
            rectangle.transform = AffineTransform.getTranslateInstance(bounds.getX(), bounds.getY());
            return DrawingScene.visualsOverlap(rectangle, item);
        }
    }

    private final Sheet sheet;
    private final SheetCoordinates coordinates;
    private final ConversionWorkspace workspace;
    private final List<Region> regions;
    private final MergedRanges merges;
    private final CellMarkdown values;

    SheetCellLayer(Sheet sheet, SheetCoordinates coordinates, ConversionWorkspace workspace, List<Region> regions) {
        this.sheet = sheet; this.coordinates = coordinates; this.workspace = workspace; this.regions = regions;
        this.merges = new MergedRanges(sheet.getMergedRegions());
        this.values = new CellMarkdown(sheet.getWorkbook(), workspace);
        long cells = 0;
        for (Region region : regions) {
            cells += (region.range().getLastRow() - (long) region.range().getFirstRow() + 1)
                    * (region.range().getLastColumn() - (long) region.range().getFirstColumn() + 1);
            workspace.limits().checkTableCells(cells);
        }
    }

    static List<Region> touchingRegions(Sheet sheet, SheetCoordinates coordinates,
                                       List<CellRangeAddress> tables, List<DrawingScene.Item> items,
                                       ConversionWorkspace workspace) {
        List<DrawingScene.Item> visible = items.stream().filter(item -> item.positionKnown
                && item.kind != DrawingScene.Kind.UNSUPPORTED
                && (item.kind != DrawingScene.Kind.PICTURE || DrawingScene.compositePicture(item))).toList();
        if (visible.isEmpty()) return List.of();
        DrawingIndex index = new DrawingIndex(visible);
        Map<DrawingScene.Item, Rectangle2D> previews = new IdentityHashMap<>();
        for (DrawingScene.Item item : visible) previews.put(item, item.bounds());
        Map<String, Region> found = new LinkedHashMap<>();
        MergedRanges merges = new MergedRanges(sheet.getMergedRegions());
        MergedRanges tableIndex = new MergedRanges(tables);
        Set<String> skipped = new HashSet<>();
        for (CellRangeAddress table : tables) {
            Region region = region(table, coordinates);
            List<DrawingScene.Item> touching = index.query(region.bounds()).stream().filter(region::overlaps).toList();
            if (touching.isEmpty()) continue;
            if (addBounded(found, region, touching, previews, workspace)) continue;
            workspace.warning("TABLE_COMPOSITE_LOCAL_RANGE", sheet.getSheetName(), table.formatAsString(),
                    "表全体を画像へ含めると描画上限を超えるため、重なるセルの周辺だけを合成します。Markdownには表全体を保持します。");
            for (DrawingScene.Item item : touching) {
                Rectangle2D overlap = region.bounds().createIntersection(item.bounds());
                int firstRow = Math.max(table.getFirstRow(), coordinates.row(overlap.getMinY()));
                int lastRow = Math.min(table.getLastRow(), coordinates.row(Math.nextDown(overlap.getMaxY())));
                int firstColumn = Math.max(table.getFirstColumn(), coordinates.column(overlap.getMinX()));
                int lastColumn = Math.min(table.getLastColumn(), coordinates.column(Math.nextDown(overlap.getMaxX())));
                if ((lastRow - (long) firstRow + 1) * (lastColumn - (long) firstColumn + 1) > MAX_PREVIEW_CELLS) {
                    skipped(workspace, sheet, table, skipped);
                    continue;
                }
                for (int r = firstRow; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row != null && row.getZeroHeight()) continue;
                    for (int c = firstColumn; c <= lastColumn; c++) {
                        if (sheet.isColumnHidden(c)) continue;
                        CellRangeAddress span = merges.at(r, c);
                        if (span == null) span = new CellRangeAddress(r, r, c, c);
                        addLocal(found, span, coordinates, index, previews, workspace, sheet, skipped);
                    }
                }
            }
        }
        // A merged cell's anchor row can be far above the drawings. Check merged rectangles
        // explicitly before pruning ordinary rows by their actual vertical drawing intervals.
        for (CellRangeAddress merge : sheet.getMergedRegions()) {
            if (tableIndex.at(merge.getFirstRow(), merge.getFirstColumn()) != null) continue;
            Row row = sheet.getRow(merge.getFirstRow());
            Cell cell = row == null ? null : row.getCell(merge.getFirstColumn());
            if (row != null && row.getZeroHeight() || sheet.isColumnHidden(merge.getFirstColumn())
                    || cell == null || !hasContent(cell)) continue;
            addLocal(found, merge, coordinates, index, previews, workspace, sheet, skipped);
        }
        for (Row row : sheet) {
            if (row.getZeroHeight()) continue;
            double y = coordinates.y(row.getRowNum()), bottom = coordinates.y(row.getRowNum() + 1);
            if (!index.intersectsVertical(y, bottom)) continue;
            for (Cell cell : row) {
                if (sheet.isColumnHidden(cell.getColumnIndex()) || !hasContent(cell)) continue;
                if (tableIndex.at(cell.getRowIndex(), cell.getColumnIndex()) != null) continue;
                if (merges.at(cell.getRowIndex(), cell.getColumnIndex()) != null) continue;
                CellRangeAddress span = new CellRangeAddress(cell.getRowIndex(), cell.getRowIndex(),
                        cell.getColumnIndex(), cell.getColumnIndex());
                addLocal(found, span, coordinates, index, previews, workspace, sheet, skipped);
            }
        }
        return List.copyOf(found.values());
    }

    private static Region region(CellRangeAddress range, SheetCoordinates coordinates) {
        return new Region("cells-" + range.formatAsString(), range, rectangle(coordinates, range));
    }

    private static void addLocal(Map<String, Region> found, CellRangeAddress range, SheetCoordinates coordinates,
                                 DrawingIndex index, Map<DrawingScene.Item, Rectangle2D> previews,
                                 ConversionWorkspace workspace, Sheet sheet, Set<String> skipped) {
        String id = "cells-" + range.formatAsString();
        if (found.containsKey(id)) return;
        Row first = sheet.getRow(range.getFirstRow());
        if (first != null && first.getZeroHeight() || sheet.isColumnHidden(range.getFirstColumn())) return;
        Region region = region(range, coordinates);
        List<DrawingScene.Item> touching = index.query(region.bounds()).stream().filter(region::overlaps).toList();
        if (!touching.isEmpty() && !addBounded(found, region, touching, previews, workspace))
            skipped(workspace, sheet, range, skipped);
    }

    private static boolean addBounded(Map<String, Region> found, Region region, List<DrawingScene.Item> touching,
                                      Map<DrawingScene.Item, Rectangle2D> previews, ConversionWorkspace workspace) {
        if ((region.range().getLastRow() - (long) region.range().getFirstRow() + 1)
                * (region.range().getLastColumn() - (long) region.range().getFirstColumn() + 1) > MAX_PREVIEW_CELLS)
            return false;
        Rectangle2D combined = region.bounds();
        for (DrawingScene.Item item : touching) combined = combined.createUnion(previews.get(item));
        double width = Math.ceil((combined.getWidth() + 6) * 96d / 72);
        double height = Math.ceil((combined.getHeight() + 6) * 96d / 72);
        if (!Double.isFinite(width) || !Double.isFinite(height) || width > Integer.MAX_VALUE
                || height > Integer.MAX_VALUE || width * height > workspace.limits().maxImagePixels()) return false;
        found.putIfAbsent(region.id(), region);
        for (DrawingScene.Item item : touching) previews.put(item, combined);
        return true;
    }

    /** Check the final connected component, including transitive cell/table associations. */
    static boolean fitsPreview(List<Region> regions, List<DrawingScene.Item> items, ConversionWorkspace workspace) {
        long cells = 0;
        Rectangle2D bounds = null;
        for (Region region : regions) {
            cells += (region.range().getLastRow() - (long) region.range().getFirstRow() + 1)
                    * (region.range().getLastColumn() - (long) region.range().getFirstColumn() + 1);
            if (cells > MAX_PREVIEW_CELLS) return false;
            bounds = bounds == null ? region.bounds() : bounds.createUnion(region.bounds());
        }
        for (DrawingScene.Item item : items)
            if (item.kind != DrawingScene.Kind.UNSUPPORTED
                    && (item.kind != DrawingScene.Kind.PICTURE || DrawingScene.compositePicture(item)))
                bounds = bounds == null ? item.bounds() : bounds.createUnion(item.bounds());
        if (bounds == null) return true;
        double width = Math.ceil((bounds.getWidth() + 6) * 96d / 72);
        double height = Math.ceil((bounds.getHeight() + 6) * 96d / 72);
        return Double.isFinite(width) && Double.isFinite(height) && width <= Integer.MAX_VALUE
                && height <= Integer.MAX_VALUE && width * height <= workspace.limits().maxImagePixels();
    }

    private static void skipped(ConversionWorkspace workspace, Sheet sheet, CellRangeAddress range, Set<String> skipped) {
        if (skipped.add(range.formatAsString()))
            workspace.warning("CELL_COMPOSITE_RANGE_SKIPPED", sheet.getSheetName(), range.formatAsString(),
                    "このセル範囲は合成画像の描画上限を超えるため、文字・表と画像・図形を個別に保持しました。");
    }

    /** Balanced vertical interval tree. Horizontal bounds are checked before exact geometry. */
    private static final class DrawingIndex {
        private record Entry(DrawingScene.Item item, Rectangle2D bounds) { }
        private static final class Node {
            final Entry entry;
            final Node left, right;
            final double bottom;
            Node(Entry entry, Node left, Node right) {
                this.entry = entry; this.left = left; this.right = right;
                bottom = Math.max(entry.bounds().getMaxY(), Math.max(left == null ? -Double.MAX_VALUE : left.bottom,
                        right == null ? -Double.MAX_VALUE : right.bottom));
            }
        }
        private final Node root;
        DrawingIndex(List<DrawingScene.Item> items) {
            List<Entry> entries = items.stream().map(item -> new Entry(item, item.bounds()))
                    .sorted(Comparator.comparingDouble(entry -> entry.bounds().getMinY())).toList();
            root = build(entries, 0, entries.size());
        }
        private static Node build(List<Entry> entries, int first, int end) {
            if (first >= end) return null;
            int middle = (first + end) >>> 1;
            return new Node(entries.get(middle), build(entries, first, middle), build(entries, middle + 1, end));
        }
        boolean intersectsVertical(double top, double bottom) { return intersects(root, top, bottom); }
        private static boolean intersects(Node node, double top, double bottom) {
            if (node == null || node.bottom < top) return false;
            if (intersects(node.left, top, bottom)) return true;
            Rectangle2D bounds = node.entry.bounds();
            if (bounds.getMinY() > bottom) return false;
            return bounds.getMaxY() >= top || intersects(node.right, top, bottom);
        }
        List<DrawingScene.Item> query(Rectangle2D bounds) {
            List<DrawingScene.Item> result = new ArrayList<>();
            query(root, bounds, result);
            return result;
        }
        private static void query(Node node, Rectangle2D bounds, List<DrawingScene.Item> result) {
            if (node == null || node.bottom < bounds.getMinY()) return;
            query(node.left, bounds, result);
            Rectangle2D candidate = node.entry.bounds();
            if (candidate.getMinY() > bounds.getMaxY()) return;
            if (candidate.getMaxY() >= bounds.getMinY() && candidate.getMaxX() >= bounds.getMinX()
                    && candidate.getMinX() <= bounds.getMaxX()) result.add(node.entry.item());
            query(node.right, bounds, result);
        }
    }

    private static boolean hasContent(Cell cell) {
        CellStyle s = cell.getCellStyle();
        return cell.getCellType() != CellType.BLANK || s.getFillPattern() != FillPatternType.NO_FILL
                || s.getBorderTop() != BorderStyle.NONE || s.getBorderBottom() != BorderStyle.NONE
                || s.getBorderLeft() != BorderStyle.NONE || s.getBorderRight() != BorderStyle.NONE;
    }

    Rectangle2D bounds() {
        Rectangle2D result = null;
        for (Region region : regions) result = result == null ? region.bounds() : result.createUnion(region.bounds());
        return result == null ? new Rectangle2D.Double() : result;
    }

    private record Box(Cell cell, CellStyle style, CellRangeAddress range, Rectangle2D bounds) { }

    void draw(Graphics2D graphics) {
        List<Box> boxes = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (Region region : regions) {
            CellRangeAddress range = region.range();
            for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getZeroHeight()) continue;
                for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                    if (sheet.isColumnHidden(c)) continue;
                    CellRangeAddress span = merges.at(r, c);
                    if (span == null) span = new CellRangeAddress(r, r, c, c);
                    if (!visited.add(span.formatAsString())) continue;
                    Row sourceRow = sheet.getRow(span.getFirstRow());
                    if (sourceRow != null && sourceRow.getZeroHeight() || sheet.isColumnHidden(span.getFirstColumn())) continue;
                    Cell cell = cell(span.getFirstRow(), span.getFirstColumn());
                    CellStyle style = cell != null ? cell.getCellStyle()
                            : sourceRow != null && sourceRow.getRowStyle() != null ? sourceRow.getRowStyle() : sheet.getColumnStyle(c);
                    boxes.add(new Box(cell, style, span, rectangle(coordinates, span)));
                }
            }
        }
        for (Box box : boxes) {
            CellStyle style = box.style();
            Color fill = style == null || style.getFillPattern() == FillPatternType.NO_FILL ? Color.WHITE
                    : color(style.getFillForegroundColorColor(), style.getFillForegroundColor(), Color.WHITE);
            graphics.setColor(fill);
            graphics.fill(box.bounds());
        }
        for (Box box : boxes) borders(graphics, box);
        for (Box box : boxes) text(graphics, box);
    }

    private void borders(Graphics2D g, Box box) {
        CellStyle style = box.style();
        if (style == null) return;
        Rectangle2D b = box.bounds();
        side(g, style, 0, b.getMinX(), b.getMinY(), b.getMaxX(), b.getMinY());
        side(g, style, 1, b.getMinX(), b.getMaxY(), b.getMaxX(), b.getMaxY());
        side(g, style, 2, b.getMinX(), b.getMinY(), b.getMinX(), b.getMaxY());
        side(g, style, 3, b.getMaxX(), b.getMinY(), b.getMaxX(), b.getMaxY());
        // Merged ranges can save bottom/right borders in their final physical cells.
        Cell bottom = cell(box.range().getLastRow(), box.range().getFirstColumn());
        Cell right = cell(box.range().getFirstRow(), box.range().getLastColumn());
        if (bottom != null) side(g, bottom.getCellStyle(), 1, b.getMinX(), b.getMaxY(), b.getMaxX(), b.getMaxY());
        if (right != null) side(g, right.getCellStyle(), 3, b.getMaxX(), b.getMinY(), b.getMaxX(), b.getMaxY());
    }

    private void side(Graphics2D g, CellStyle s, int side, double x1, double y1, double x2, double y2) {
        BorderStyle border = switch (side) {
            case 0 -> s.getBorderTop(); case 1 -> s.getBorderBottom(); case 2 -> s.getBorderLeft(); default -> s.getBorderRight();
        };
        if (border == BorderStyle.NONE) return;
        short indexed = switch (side) {
            case 0 -> s.getTopBorderColor(); case 1 -> s.getBottomBorderColor(); case 2 -> s.getLeftBorderColor(); default -> s.getRightBorderColor();
        };
        org.apache.poi.ss.usermodel.Color source = s instanceof XSSFCellStyle xs ? switch (side) {
            case 0 -> xs.getTopBorderXSSFColor(); case 1 -> xs.getBottomBorderXSSFColor();
            case 2 -> xs.getLeftBorderXSSFColor(); default -> xs.getRightBorderXSSFColor();
        } : null;
        g.setColor(color(source, indexed, Color.BLACK));
        float width = switch (border) {
            case HAIR -> .3f; case THICK -> 2.2f;
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT -> 1.4f; default -> .65f;
        };
        float[] dash = switch (border) {
            case DOTTED -> new float[]{1, 2};
            case DASHED, MEDIUM_DASHED -> new float[]{4, 3};
            case DASH_DOT, MEDIUM_DASH_DOT -> new float[]{4, 2, 1, 2};
            case DASH_DOT_DOT, MEDIUM_DASH_DOT_DOT -> new float[]{4, 2, 1, 2, 1, 2}; default -> null;
        };
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0));
        g.draw(new Line2D.Double(x1, y1, x2, y2));
        if (border == BorderStyle.DOUBLE) {
            double dx = y1 == y2 ? 0 : 1.5, dy = y1 == y2 ? 1.5 : 0;
            g.draw(new Line2D.Double(x1 + dx, y1 + dy, x2 + dx, y2 + dy));
        }
    }

    private void text(Graphics2D g, Box box) {
        if (box.cell() == null || box.style() == null) return;
        String text = values.text(box.cell());
        if (text.isEmpty()) return;
        CellStyle s = box.style();
        org.apache.poi.ss.usermodel.Font font = sheet.getWorkbook().getFontAt(s.getFontIndex());
        Color color = color(font instanceof XSSFFont x ? x.getXSSFColor() : null, font.getColor(), Color.BLACK);
        boolean numeric = box.cell().getCellType() == CellType.NUMERIC || box.cell().getCellType() == CellType.FORMULA
                && box.cell().getCachedFormulaResultType() == CellType.NUMERIC;
        int horizontal = switch (s.getAlignment()) {
            case CENTER, CENTER_SELECTION -> 2; case RIGHT -> 3; case GENERAL -> numeric ? 3 : 1; default -> 1;
        };
        int vertical = switch (s.getVerticalAlignment()) { case CENTER -> 2; case BOTTOM -> 3; default -> 1; };
        if (s.getRotation() == 255) text = text.codePoints().mapToObj(cp -> new String(Character.toChars(cp)))
                .collect(java.util.stream.Collectors.joining("\n"));
        List<DrawingText.Paragraph> paragraphs = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            var run = new DrawingText.Run(line, font.getFontName(), Math.max(1, font.getFontHeightInPoints()),
                    font.getBold(), font.getItalic(), null, color, font.getUnderline() != 0, 0);
            paragraphs.add(new DrawingText.Paragraph(List.of(run), horizontal, 0, 0, 0, 100, true, 0, 0));
        }
        var content = new DrawingText.Content(paragraphs, vertical, s.getWrapText(), 1, 0, s.getShrinkToFit(),
                s.getRotation() == 255 ? 0 : -s.getRotation(), "horz", List.of());
        double indent = s.getIndention() * font.getFontHeightInPoints() * .75;
        Rectangle2D b = box.bounds();
        DrawingTextLayout.draw(g, content, new Rectangle2D.Double(b.getX() + 2 + indent, b.getY() + 1,
                Math.max(.1, b.getWidth() - 4 - indent), Math.max(.1, b.getHeight() - 2)), 1);
    }

    private Color color(org.apache.poi.ss.usermodel.Color source, short indexed, Color fallback) {
        if (source instanceof XSSFColor x) {
            byte[] rgb = x.getRGBWithTint();
            if (rgb != null && rgb.length >= 3) return new Color(rgb[0] & 255, rgb[1] & 255, rgb[2] & 255);
        }
        if (sheet.getWorkbook() instanceof HSSFWorkbook hssf) {
            var palette = hssf.getCustomPalette().getColor(indexed);
            if (palette != null) { short[] rgb = palette.getTriplet(); return new Color(rgb[0], rgb[1], rgb[2]); }
        }
        try {
            byte[] rgb = new org.apache.poi.xssf.usermodel.DefaultIndexedColorMap().getRGB(indexed);
            if (rgb != null) return new Color(rgb[0] & 255, rgb[1] & 255, rgb[2] & 255);
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private Cell cell(int row, int column) { Row r = sheet.getRow(row); return r == null ? null : r.getCell(column); }
    private static Rectangle2D rectangle(SheetCoordinates c, CellRangeAddress r) {
        return new Rectangle2D.Double(c.x(r.getFirstColumn()), c.y(r.getFirstRow()),
                c.x(r.getLastColumn() + 1) - c.x(r.getFirstColumn()), c.y(r.getLastRow() + 1) - c.y(r.getFirstRow()));
    }
}
