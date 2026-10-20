package com.convertx2x.excel2img.conversion;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

/** Draws cell geometry, styles and text directly onto Java2D, retaining measured search text. */
final class CellPainter {
    record TextLine(String text, double x, double baseline, double width, float fontSize, boolean bold) {}
    private static final Font REGULAR = bundled("/fonts/bizud/BIZUDPGothic-Regular.ttf");
    private static final Font BOLD = bundled("/fonts/bizud/BIZUDPGothic-Bold.ttf");
    private final Sheet sheet;
    private final SheetLayout geometry;
    private final CellValues values;
    private final ConversionWorkspace workspace;
    private final CellRangeAddress selected;
    private final Map<Long, CellRangeAddress> merged = new HashMap<>();
    private final List<CellRangeAddress> merges = new ArrayList<>();
    private final Map<Integer, Font> fonts = new HashMap<>();

    CellPainter(Sheet sheet, SheetLayout geometry, CellValues values, ConversionWorkspace workspace,
                CellRangeAddress selected, long maxCells) {
        this.sheet = sheet; this.geometry = geometry; this.values = values;
        this.workspace = workspace; this.selected = selected;
        long indexed = 0;
        for (CellRangeAddress merge : sheet.getMergedRegions()) {
            if (!merge.intersects(selected)) continue;
            merges.add(merge);
            for (int r = Math.max(merge.getFirstRow(), selected.getFirstRow()); r <= Math.min(merge.getLastRow(), selected.getLastRow()); r++) {
                for (int c = Math.max(merge.getFirstColumn(), selected.getFirstColumn()); c <= Math.min(merge.getLastColumn(), selected.getLastColumn()); c++) {
                    if (++indexed > maxCells && indexed - maxCells > maxCells)
                        throw ConversionWorkspace.limit("CELL_LIMIT", "結合セルの処理量が上限を超えました。");
                    merged.put(key(r, c), merge);
                }
            }
        }
    }
    List<TextLine> draw(Graphics2D g, RangePlanner.Page page, Rectangle2D pageBounds) {
        List<TextLine> text = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        for (int r = page.firstRow(); r <= page.lastRow(); r++) {
            if (geometry.y(r + 1) <= geometry.y(r)) continue;
            Row row = sheet.getRow(r);
            for (int c = selected.getFirstColumn(); c <= selected.getLastColumn(); c++) {
                if (geometry.x(c + 1) <= geometry.x(c) || merged.containsKey(key(r, c))) continue;
                Cell cell = row == null ? null : row.getCell(c);
                CellStyle style = cell != null ? cell.getCellStyle() : row != null && row.getRowStyle() != null
                        ? row.getRowStyle() : sheet.getColumnStyle(c);
                if (cell == null && style == null) continue;
                boxes.add(new Box(cell, style, rectangle(r, r, c, c), false));
            }
        }
        for (CellRangeAddress merge : merges) {
            Rectangle2D bounds = rectangle(merge.getFirstRow(), merge.getLastRow(), merge.getFirstColumn(), merge.getLastColumn());
            if (!bounds.intersects(pageBounds)) continue;
            Row row = sheet.getRow(merge.getFirstRow());
            Cell cell = row == null ? null : row.getCell(merge.getFirstColumn());
            CellStyle style = cell == null ? null : cell.getCellStyle();
            boxes.add(new Box(cell, style, bounds, true));
        }
        for (Box box : boxes) fill(g, box);
        for (Box box : boxes) border(g, box);
        for (Box box : boxes) {
            if (box.cell == null || box.style == null) continue;
            String value = values.text(box.cell);
            if (value.isEmpty()) continue;
            drawText(g, box, value, pageBounds, text);
        }
        return text;
    }
    private record Box(Cell cell, CellStyle style, Rectangle2D bounds, boolean merged) {}
    private Rectangle2D rectangle(int r1, int r2, int c1, int c2) {
        return new Rectangle2D.Double(geometry.x(c1), geometry.y(r1), geometry.x(c2 + 1) - geometry.x(c1), geometry.y(r2 + 1) - geometry.y(r1));
    }
    private void fill(Graphics2D g, Box box) {
        if (box.style == null || box.style.getFillPattern() == FillPatternType.NO_FILL) return;
        g.setColor(color(box.style.getFillForegroundColorColor(), box.style.getFillForegroundColor(), Color.WHITE));
        g.fill(box.bounds);
        if (box.style.getFillPattern() != FillPatternType.SOLID_FOREGROUND)
            workspace.warning("FILL_PATTERN_APPROXIMATED", sheet.getSheetName(), "パターン塗りつぶしを単色で近似しました。");
    }
    private void border(Graphics2D g, Box box) {
        if (box.style == null) return;
        CellStyle style = box.style;
        Rectangle2D b = box.bounds;
        side(g, style, 0, style.getBorderTop(), style.getTopBorderColor(), b.getMinX(), b.getMinY(), b.getMaxX(), b.getMinY());
        side(g, style, 1, style.getBorderBottom(), style.getBottomBorderColor(), b.getMinX(), b.getMaxY(), b.getMaxX(), b.getMaxY());
        side(g, style, 2, style.getBorderLeft(), style.getLeftBorderColor(), b.getMinX(), b.getMinY(), b.getMinX(), b.getMaxY());
        side(g, style, 3, style.getBorderRight(), style.getRightBorderColor(), b.getMaxX(), b.getMinY(), b.getMaxX(), b.getMaxY());
        // Excel can store the visible outer border on the last cell of a merged range.
        if (box.merged) {
            CellRangeAddress merge = merged.get(key(Math.max(selected.getFirstRow(), box.cell == null ? 0 : box.cell.getRowIndex()),
                    Math.max(selected.getFirstColumn(), box.cell == null ? 0 : box.cell.getColumnIndex())));
            if (merge != null) {
                Cell bottom = cell(merge.getLastRow(), merge.getFirstColumn());
                Cell right = cell(merge.getFirstRow(), merge.getLastColumn());
                if (bottom != null) side(g, bottom.getCellStyle(), 1, bottom.getCellStyle().getBorderBottom(), bottom.getCellStyle().getBottomBorderColor(), b.getMinX(), b.getMaxY(), b.getMaxX(), b.getMaxY());
                if (right != null) side(g, right.getCellStyle(), 3, right.getCellStyle().getBorderRight(), right.getCellStyle().getRightBorderColor(), b.getMaxX(), b.getMinY(), b.getMaxX(), b.getMaxY());
            }
        }
    }
    private void side(Graphics2D g, CellStyle style, int side, BorderStyle border, short indexed,
                      double x1, double y1, double x2, double y2) {
        if (border == BorderStyle.NONE) return;
        org.apache.poi.ss.usermodel.Color source = null;
        if (style instanceof XSSFCellStyle x) source = switch (side) {
            case 0 -> x.getTopBorderXSSFColor(); case 1 -> x.getBottomBorderXSSFColor();
            case 2 -> x.getLeftBorderXSSFColor(); default -> x.getRightBorderXSSFColor();
        };
        g.setColor(color(source, indexed, Color.BLACK));
        float width = switch (border) {
            case HAIR -> .3f;
            case MEDIUM, MEDIUM_DASHED, MEDIUM_DASH_DOT, MEDIUM_DASH_DOT_DOT -> 1.4f;
            case THICK -> 2.2f;
            default -> .65f;
        };
        float[] dash = switch (border) {
            case DOTTED -> new float[]{1, 2};
            case DASHED, MEDIUM_DASHED -> new float[]{4, 3};
            case DASH_DOT, MEDIUM_DASH_DOT -> new float[]{4, 2, 1, 2};
            case DASH_DOT_DOT, MEDIUM_DASH_DOT_DOT -> new float[]{4, 2, 1, 2, 1, 2};
            default -> null;
        };
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0));
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
        if (border == BorderStyle.DOUBLE) {
            boolean horizontal = Math.abs(y1 - y2) < .0001;
            g.draw(new java.awt.geom.Line2D.Double(x1 + (horizontal ? 0 : 1.5), y1 + (horizontal ? 1.5 : 0),
                    x2 + (horizontal ? 0 : 1.5), y2 + (horizontal ? 1.5 : 0)));
        }
    }
    private void drawText(Graphics2D g, Box box, String value, Rectangle2D pageBounds, List<TextLine> captured) {
        CellStyle style = box.style;
        org.apache.poi.ss.usermodel.Font excelFont = sheet.getWorkbook().getFontAt(style.getFontIndex());
        Font font = fonts.computeIfAbsent(style.getFontIndex(), ignored -> {
            if (!excelFont.getFontName().equalsIgnoreCase("BIZ UDPGothic")) workspace.warning("FONT_SUBSTITUTED", sheet.getSheetName(),
                    "セルのフォントは同梱の BIZ UDPゴシックで代替しました。文字幅は原本と異なる場合があります。");
            Font base = excelFont.getBold() ? BOLD : REGULAR;
            return base.deriveFont(excelFont.getItalic() ? Font.ITALIC : Font.PLAIN, Math.max(1f, excelFont.getFontHeightInPoints()));
        });
        Rectangle2D b = box.bounds;
        double indent = Math.max(0, style.getIndention()) * font.getSize2D() * .75;
        double available = Math.max(.1, b.getWidth() - 4 - indent);
        if (style.getShrinkToFit()) workspace.warning("SHRINK_TO_FIT_IGNORED", sheet.getSheetName(),
                "縮小して全体を表示する設定は、可読性を保つため適用していません。");
        short rotation = style.getRotation();
        if (rotation != 0) workspace.warning("TEXT_ROTATION_APPROXIMATED", sheet.getSheetName(), "回転文字は横書きで近似しました。");
        if (box.cell.getCellType() == CellType.STRING && box.cell.getRichStringCellValue().numFormattingRuns() > 0)
            workspace.warning("RICH_TEXT_APPROXIMATED", sheet.getSheetName(), "セル内の部分書式はセル全体の書式で近似しました。");
        g.setFont(font);
        org.apache.poi.ss.usermodel.Color fontColor = excelFont instanceof XSSFFont x ? x.getXSSFColor() : null;
        g.setColor(color(fontColor, excelFont.getColor(), Color.BLACK));
        FontRenderContext context = g.getFontRenderContext();
        List<Line> lines = lines(value, font, context, available, style.getWrapText());
        double lineHeight = font.getLineMetrics("Ag", context).getHeight();
        double ascent = font.getLineMetrics("Ag", context).getAscent();
        double blockHeight = lines.size() * lineHeight;
        VerticalAlignment vertical = style.getVerticalAlignment();
        double top = switch (vertical) {
            case TOP -> b.getY() + 1;
            case CENTER -> b.getY() + (b.getHeight() - blockHeight) / 2;
            default -> b.getMaxY() - blockHeight - 1;
        };
        top = Math.max(b.getY() + 1, top);
        HorizontalAlignment alignment = style.getAlignment();
        boolean numeric = box.cell.getCellType() == CellType.NUMERIC || box.cell.getCellType() == CellType.FORMULA && box.cell.getCachedFormulaResultType() == CellType.NUMERIC;
        boolean right = alignment == HorizontalAlignment.RIGHT || alignment == HorizontalAlignment.GENERAL && numeric;
        boolean center = alignment == HorizontalAlignment.CENTER || alignment == HorizontalAlignment.CENTER_SELECTION;
        Rectangle2D clip = new Rectangle2D.Double(b.getX() + 1, b.getY(), Math.max(0, b.getWidth() - 2), b.getHeight());
        if (!style.getWrapText() && !box.merged && !right && !center && !numeric) {
            int c = box.cell.getColumnIndex() + 1;
            double rightEdge = b.getMaxX();
            double wanted = lines.stream().mapToDouble(Line::width).max().orElse(0) + 4;
            while (c <= selected.getLastColumn() && rightEdge - b.getX() < wanted) {
                Cell next = cell(box.cell.getRowIndex(), c);
                if (merged.containsKey(key(box.cell.getRowIndex(), c)) || next != null &&
                        (!values.text(next).isEmpty() || next.getCellStyle().getFillPattern() != FillPatternType.NO_FILL)) break;
                rightEdge = geometry.x(++c);
            }
            clip.setRect(clip.getX(), clip.getY(), rightEdge - clip.getX() - 1, clip.getHeight());
        }
        Rectangle2D visible = clip.createIntersection(pageBounds);
        Shape previous = g.getClip();
        g.clip(clip);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            double left = right ? b.getMaxX() - 2 - line.width : center ? b.getCenterX() - line.width / 2 : b.getX() + 2 + indent;
            double baseline = top + i * lineHeight + ascent;
            if (!line.text.isEmpty()) g.drawString(line.text, (float) left, (float) baseline);
            if (excelFont.getStrikeout()) {
                g.setStroke(new BasicStroke(Math.max(.5f, font.getSize2D() / 16)));
                g.draw(new java.awt.geom.Line2D.Double(left, baseline - ascent * .35, left + line.width, baseline - ascent * .35));
            }
            if (excelFont.getUnderline() != org.apache.poi.ss.usermodel.Font.U_NONE) {
                g.setStroke(new BasicStroke(.55f));
                g.draw(new java.awt.geom.Line2D.Double(left, baseline + 1, left + line.width, baseline + 1));
            }
            if (baseline >= visible.getMinY() && baseline <= visible.getMaxY() && !line.text.isEmpty()) {
                int start = visible.getMinX() > left ? prefixAtWidth(line.text, font, context, visible.getMinX() - left) : 0;
                int end = visible.getMaxX() + .5 < left + line.width
                        ? prefixAtWidth(line.text, font, context, visible.getMaxX() + .5 - left) : line.text.length();
                double offset;
                if (start < end) {
                    offset = font.getStringBounds(line.text.substring(0, start), context).getWidth();
                    String actual = line.text.substring(start, end);
                    captured.add(new TextLine(actual, left + offset, baseline, font.getStringBounds(actual, context).getWidth(), font.getSize2D(), excelFont.getBold()));
                }
            }
            if (left < clip.getMinX() - 1 || left + line.width > clip.getMaxX() + 1 || baseline - ascent < b.getY() - 1 || baseline > b.getMaxY() + 1)
                workspace.warning("CELL_TEXT_CLIPPED", sheet.getSheetName(), "元のセル寸法に収まらない文字はセル境界でクリップしました。行列サイズは変更していません。");
        }
        g.setClip(previous);
    }
    private record Line(String text, double width) {}
    /** Binary prefix measurement avoids quadratic work on Excel's 32K-character cells. */
    private static int prefixAtWidth(String text, Font font, FontRenderContext context, double width) {
        if (width <= 0) return 0;
        int[] offsets = new int[text.codePointCount(0, text.length()) + 1];
        for (int i = 1; i < offsets.length; i++)
            offsets[i] = offsets[i - 1] + Character.charCount(text.codePointAt(offsets[i - 1]));
        int lo = 0, hi = offsets.length;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (font.getStringBounds(text, 0, offsets[mid], context).getWidth() <= width) lo = mid;
            else hi = mid;
        }
        return offsets[lo];
    }
    private static List<Line> lines(String value, Font font, FontRenderContext context, double width, boolean wrap) {
        List<Line> lines = new ArrayList<>();
        for (String logical : value.split("\n", -1)) {
            if (!wrap || logical.isEmpty()) { lines.add(new Line(logical, font.getStringBounds(logical, context).getWidth())); continue; }
            AttributedString attributed = new AttributedString(logical);
            attributed.addAttribute(TextAttribute.FONT, font);
            LineBreakMeasurer breaker = new LineBreakMeasurer(attributed.getIterator(), context);
            while (breaker.getPosition() < logical.length()) {
                int start = breaker.getPosition();
                TextLayout measured = breaker.nextLayout((float) Math.max(.1, width));
                lines.add(new Line(logical.substring(start, breaker.getPosition()), measured.getAdvance()));
            }
        }
        return lines;
    }
    private Cell cell(int row, int column) { Row r = sheet.getRow(row); return r == null ? null : r.getCell(column); }
    private Color color(org.apache.poi.ss.usermodel.Color source, short indexed, Color fallback) {
        if (source instanceof XSSFColor x) {
            byte[] rgb = x.getRGBWithTint();
            if (rgb != null && rgb.length >= 3) return new Color(rgb[0] & 255, rgb[1] & 255, rgb[2] & 255);
        }
        if (sheet.getWorkbook() instanceof HSSFWorkbook h) {
            org.apache.poi.hssf.util.HSSFColor c = h.getCustomPalette().getColor(indexed);
            if (c != null) { short[] rgb = c.getTriplet(); return new Color(rgb[0], rgb[1], rgb[2]); }
        }
        if (indexed == IndexedColors.AUTOMATIC.getIndex()) return fallback;
        org.apache.poi.hssf.util.HSSFColor c = org.apache.poi.hssf.util.HSSFColor.getIndexHash().get((int) indexed);
        if (c != null) { short[] rgb = c.getTriplet(); return new Color(rgb[0], rgb[1], rgb[2]); }
        return fallback;
    }
    private static long key(int row, int col) { return ((long) row << 32) | (col & 0xffffffffL); }
    private static Font bundled(String name) {
        try (InputStream input = CellPainter.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing bundled font");
            Font font = Font.createFont(Font.TRUETYPE_FONT, input);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (IOException | java.awt.FontFormatException failure) { throw new IllegalStateException("Unable to load bundled font", failure); }
    }
}
