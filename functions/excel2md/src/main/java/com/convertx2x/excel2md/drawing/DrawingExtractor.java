package com.convertx2x.excel2md.drawing;

import com.convertx2x.excel2md.conversion.ConversionException;
import com.convertx2x.excel2md.conversion.ConversionWorkspace;
import com.convertx2x.excel2md.conversion.Markdown;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherSpRecord;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;
import org.w3c.dom.Node;
import static com.convertx2x.excel2md.drawing.DrawingScene.Kind;

/** Extracts embedded images and renders an explicitly bounded subset of Excel drawing shapes. */
public final class DrawingExtractor {
    public List<DrawingBlock> extract(Sheet sheet, ConversionWorkspace workspace) {
        int index = sheet.getWorkbook().getSheetIndex(sheet);
        if (sheet.getWorkbook().isSheetHidden(index) || sheet.getWorkbook().isSheetVeryHidden(index)) return List.of();
        return new Reader(sheet, workspace).extract();
    }

    private static final class Reader {
        private final Sheet sheet;
        private final ConversionWorkspace workspace;
        private final SheetCoordinates coordinates;
        private final List<List<DrawingScene.Item>> roots = new ArrayList<>();
        private int sequence;

        Reader(Sheet sheet, ConversionWorkspace workspace) {
            this.sheet = sheet;
            this.workspace = workspace;
            coordinates = new SheetCoordinates(sheet);
        }

        List<DrawingBlock> extract() {
            var drawing = sheet.getDrawingPatriarch();
            if (drawing != null) for (Shape shape : drawing) {
                List<DrawingScene.Item> items = new ArrayList<>();
                read(shape, new AffineTransform(), false, true, false, 1, items);
                if (!items.isEmpty()) roots.add(items);
            }
            List<DrawingBlock> blocks = new ArrayList<>();
            for (List<DrawingScene.Item> component : components()) {
                component.sort(Comparator.comparingInt(item -> item.order));
                String markdown;
                if (component.size() == 1 && component.getFirst().kind == Kind.PICTURE) {
                    markdown = pictureMarkdown(component.getFirst());
                } else if (component.stream().allMatch(item -> item.kind == Kind.UNSUPPORTED)) {
                    StringBuilder text = new StringBuilder();
                    for (var item : component) {
                        if (!text.isEmpty()) text.append("\n\n");
                        text.append("[" + item.placeholder + "]");
                        String content = DrawingText.plainText(item.text);
                        if (!content.isBlank()) text.append(" ").append(Markdown.escape(content));
                    }
                    markdown = text.toString();
                } else {
                    String path = workspace.addAsset("diagram", DrawingScene.render(component, workspace), "png", "image/png");
                    markdown = "![図形](" + path + ")";
                    for (var item : component) {
                        if (item.kind == Kind.PICTURE && !inline(item)) markdown += "\n\n" + pictureMarkdown(item);
                        if (item.kind == Kind.UNSUPPORTED) {
                            markdown += "\n\n[" + item.placeholder + "]";
                            String content = DrawingText.plainText(item.text);
                            if (!content.isBlank()) markdown += " " + Markdown.escape(content);
                        }
                    }
                    info("DRAWING_SIMPLIFIED", component, "基本図形を簡易描画しました。セル内容は画像に含みません。図形ID: "
                            + component.stream().map(item -> Long.toString(item.id)).toList());
                }
                Set<String> links = new LinkedHashSet<>();
                for (var item : component) for (var run : item.text) {
                    if (run.hyperlink() != null && !run.text().isBlank() && Markdown.safeLink(run.hyperlink()))
                        links.add(Markdown.link(Markdown.escape(run.text()), run.hyperlink()));
                }
                if (!links.isEmpty()) markdown += "\n\n" + String.join("\n\n", links);
                blocks.add(block(component, markdown));
            }
            // BIFF chart records can exist independently of an Escher shape tree.
            if (sheet instanceof HSSFSheet hssf && HSSFChart.getSheetCharts(hssf).length > 0) {
                workspace.warning("CHART_UNSUPPORTED", sheet.getSheetName(), null, "グラフの描画は未対応です。保存されたセル値は別途出力します。");
                blocks.add(new DrawingBlock(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, "[グラフの描画は未対応です]"));
            }
            return blocks;
        }

        private void read(Shape shape, AffineTransform parent, boolean child, boolean knownPosition,
                          boolean parentFlipped, int depth, List<DrawingScene.Item> output) {
            workspace.shapeVisited(depth);
            if (shape instanceof HSSFComment) return;
            Node xml = xml(shape);
            if (hidden(shape, xml)) {
                workspace.info("HIDDEN_DRAWING", sheet.getSheetName(), null, "明示的に非表示の描画オブジェクトを除外しました。");
                return;
            }
            Rectangle2D anchor;
            boolean known = knownPosition;
            try {
                anchor = !child && shape.getAnchor() instanceof ClientAnchor client
                        ? coordinates.anchor(client) : anchor(shape, xml, child);
                if (anchor == null) {
                    known = false;
                    anchor = new Rectangle2D.Double(0, 0, 240, 90);
                    workspace.warning("DRAWING_POSITION_UNKNOWN", sheet.getSheetName(), null, "描画位置を取得できないためシート末尾に配置します。");
                }
            } catch (RuntimeException ex) {
                if (ex instanceof ConversionException conversion) throw conversion;
                known = false;
                anchor = new Rectangle2D.Double(0, 0, 240, 90);
                workspace.warning("DRAWING_POSITION_UNKNOWN", sheet.getSheetName(), null, "描画位置を解決できないためシート末尾に配置します。");
            }
            Rectangle2D sheetBounds = parent.createTransformedShape(anchor).getBounds2D();
            if (known && coordinates.hidden(sheetBounds)) {
                workspace.info("HIDDEN_DRAWING_ANCHOR", sheet.getSheetName(), range(sheetBounds), "左上アンカーの行または列が非表示の描画オブジェクトを除外しました。");
                return;
            }
            AffineTransform transform = new AffineTransform(parent);
            transform.translate(anchor.getX(), anchor.getY());
            double rotation = rotation(shape, xml);
            boolean flipH = flip(shape, xml, true), flipV = flip(shape, xml, false);
            boolean textFlipped = parentFlipped || flipH || flipV;
            transform.translate(anchor.getWidth() / 2, anchor.getHeight() / 2);
            transform.rotate(Math.toRadians(rotation));
            transform.scale(flipH ? -1 : 1, flipV ? -1 : 1);
            transform.translate(-anchor.getWidth() / 2, -anchor.getHeight() / 2);
            if (shape instanceof XSSFShapeGroup group) {
                Node xfrm = child(child(xml, "grpSpPr"), "xfrm");
                Node offset = child(xfrm, "chOff"), extent = child(xfrm, "chExt");
                double width = attribute(extent, "cx", anchor.getWidth() * 12700) / 12700;
                double height = attribute(extent, "cy", anchor.getHeight() * 12700) / 12700;
                if (width <= 0 || height <= 0) {
                    workspace.warning("GROUP_TRANSFORM_INVALID", sheet.getSheetName(), range(sheetBounds), "グループの座標変換を解決できません。");
                    width = Math.max(1, anchor.getWidth()); height = Math.max(1, anchor.getHeight());
                }
                transform.scale(anchor.getWidth() / width, anchor.getHeight() / height);
                transform.translate(-attribute(offset, "x", 0) / 12700, -attribute(offset, "y", 0) / 12700);
                for (XSSFShape member : group) read(member, transform, true, known, textFlipped, depth + 1, output);
                return;
            }
            if (shape instanceof HSSFShapeGroup group) {
                double width = group.getX2() - (double) group.getX1(), height = group.getY2() - (double) group.getY1();
                if (width <= 0 || height <= 0) {
                    workspace.warning("GROUP_TRANSFORM_INVALID", sheet.getSheetName(), range(sheetBounds), "グループの座標変換を解決できません。");
                    width = height = 1;
                }
                transform.scale(anchor.getWidth() / width, anchor.getHeight() / height);
                transform.translate(-group.getX1(), -group.getY1());
                for (HSSFShape member : group) read(member, transform, true, known, textFlipped, depth + 1, output);
                return;
            }
            DrawingScene.Item item = new DrawingScene.Item();
            item.order = ++sequence;
            item.id = identifier(shape, xml, item.order);
            item.transform = transform;
            item.width = anchor.getWidth(); item.height = anchor.getHeight();
            item.positionKnown = known;
            if (shape instanceof Picture picture) {
                workspace.imagePlacement();
                readPicture(picture, xml, item);
            } else {
                readShape(shape, xml, item, textFlipped);
                if (shape instanceof HSSFShape && child) {
                    double scale = Math.sqrt(Math.abs(parent.getDeterminant()));
                    if (scale > 0) item.textScale = 1 / scale;
                }
            }
            // A zero-width/height line is valid, but an unrenderable box still needs a visible placeholder.
            if (item.kind == Kind.UNSUPPORTED && (item.width < 1 || item.height < 1)) {
                item.width = Math.max(120, item.width); item.height = Math.max(40, item.height);
            }
            output.add(item);
        }

        private void readPicture(Picture picture, Node xml, DrawingScene.Item item) {
            try {
                var data = picture.getPictureData();
                if (data == null) throw new IllegalArgumentException("Missing embedded picture");
                item.picture = data.getData();
                if (item.picture.length > workspace.limits().maxImageBytes())
                    throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                String[] format = sniff(item.picture);
                item.extension = format[0]; item.contentType = format[1]; item.kind = Kind.PICTURE;
                Node properties = child(child(xml, "nvPicPr"), "cNvPr");
                String description = attr(properties, "descr");
                if (!description.isBlank()) item.alt = description;
                if (inline(item)) DrawingScene.verifyPicture(item.picture, workspace);
                else warning("IMAGE_FORMAT_ATTACHMENT", item, "この画像形式は元ファイルの添付として保持します。");
                if (picture instanceof XSSFPicture) {
                    Node blipFill = child(xml, "blipFill");
                    Node blip = child(blipFill, "blip");
                    if (child(blipFill, "srcRect") != null || rotation((Shape) picture, xml) != 0
                            || flip((Shape) picture, xml, true) || flip((Shape) picture, xml, false)
                            || child(child(xml, "spPr"), "effectLst") != null || hasElementChildren(blip))
                        warning("IMAGE_EFFECTS_IGNORED", item, "元画像を抽出します。切り抜き前の領域を含み、切り抜き・画像効果は反映しません。図形内への合成時だけ配置の回転・反転を反映します。");
                } else if (picture instanceof HSSFPicture hssf) {
                    if (hssf.getRotationDegree() != 0 || hssf.isFlipHorizontal() || hssf.isFlipVertical()
                            || escher(hssf, EscherPropertyTypes.BLIP__CROPFROMTOP, 0) != 0
                            || escher(hssf, EscherPropertyTypes.BLIP__CROPFROMBOTTOM, 0) != 0
                            || escher(hssf, EscherPropertyTypes.BLIP__CROPFROMLEFT, 0) != 0
                            || escher(hssf, EscherPropertyTypes.BLIP__CROPFROMRIGHT, 0) != 0)
                        warning("IMAGE_EFFECTS_IGNORED", item, "元画像には切り抜き前の領域を含みます。単独画像の回転・反転は反映しません。");
                }
            } catch (IOException | RuntimeException ex) {
                if (ex instanceof ConversionException conversion) throw conversion;
                item.kind = Kind.UNSUPPORTED;
                item.placeholder = "画像を抽出できませんでした";
                item.picture = null;
                warning("IMAGE_UNREADABLE", item, "埋め込み画像を読み取れません。外部画像は取得しません。");
            }
        }

        private void readShape(Shape shape, Node xml, DrawingScene.Item item, boolean textFlipped) {
            if (shape instanceof org.apache.poi.ss.usermodel.SimpleShape simple && !(shape instanceof org.apache.poi.ss.usermodel.ObjectData)) {
                item.textContent = DrawingText.readContent(simple, sheet.getWorkbook());
                item.text = flatten(item.textContent);
                if (!DrawingText.plainText(item.text).isBlank()) {
                    if (textFlipped) {
                        var content = item.textContent;
                        List<String> reasons = new ArrayList<>(content.approximations());
                        reasons.add("図形やグループの反転に文字も追随させます。文字だけを正立に保つ処理は未対応です。");
                        item.textContent = new DrawingText.Content(content.paragraphs(), content.verticalAlignment(),
                                content.wrap(), content.fontScale(), content.lineSpacingReduction(), content.shrinkToFit(),
                                content.rotation(), content.verticalMode(), List.copyOf(reasons));
                    }
                    for (String reason : new LinkedHashSet<>(item.textContent.approximations()))
                        warning("DRAWING_TEXT_APPROXIMATED", item, reason);
                }
            }
            if (shape instanceof XSSFGraphicFrame) {
                item.placeholder = "グラフ・SmartArtなどの描画は未対応です";
                warning("GRAPHIC_FRAME_UNSUPPORTED", item, "グラフ・SmartArtなどの描画は未対応です。");
                return;
            }
            if (shape instanceof XSSFSimpleShape simple && !(shape instanceof org.apache.poi.ss.usermodel.ObjectData)) {
                Node properties = child(xml, "spPr");
                String geometry = attr(child(properties, "prstGeom"), "prst");
                item.kind = kind(geometry);
                if (shape instanceof XSSFTextBox) item.kind = Kind.TEXT;
                style(xml, properties, item);
                Node body = child(child(xml, "txBody"), "bodyPr");
                // POI 5.5.1 uses 3.6 pt for absent left/right values; DrawingML defaults to 7.2 pt.
                if (!attr(body, "lIns").isEmpty()) item.leftInset = simple.getLeftInset();
                if (!attr(body, "rIns").isEmpty()) item.rightInset = simple.getRightInset();
                if (!attr(body, "tIns").isEmpty()) item.topInset = simple.getTopInset();
                if (!attr(body, "bIns").isEmpty()) item.bottomInset = simple.getBottomInset();
                if (!simple.getTextParagraphs().isEmpty()) {
                    Node paragraph = child(child(xml, "txBody"), "p");
                    String alignment = attr(child(paragraph, "pPr"), "algn");
                    item.alignment = "ctr".equals(alignment) ? 2 : "r".equals(alignment) ? 3 : 1;
                }
                if (child(child(properties, "prstGeom"), "avLst") != null
                        && hasElementChildren(child(child(properties, "prstGeom"), "avLst")))
                    warning("SHAPE_ADJUSTMENTS_IGNORED", item, "図形の調整ハンドルは標準の形に簡略化します。");
            } else if (shape instanceof XSSFConnector) {
                Node properties = child(xml, "spPr");
                String geometry = attr(child(properties, "prstGeom"), "prst");
                item.kind = "line".equals(geometry) || "straightConnector1".equals(geometry) ? Kind.LINE : Kind.UNSUPPORTED;
                style(xml, properties, item);
                Node connectorProperties = child(child(xml, "nvCxnSpPr"), "cNvCxnSpPr");
                for (String name : List.of("stCxn", "endCxn")) {
                    Node connection = child(connectorProperties, name);
                    if (connection != null) item.connections.add((long) attribute(connection, "id", -1));
                }
                if (item.connections.isEmpty()) warning("CONNECTOR_ENDPOINTS_UNKNOWN", item, "接続先を取得できない線は、重なりだけを根拠に他の図形とまとめます。");
            } else if (shape instanceof HSSFSimpleShape simple && !(shape instanceof org.apache.poi.ss.usermodel.ObjectData)) {
                item.kind = hssfKind(simple.getShapeType());
                item.fill = simple.isNoFill() ? null : hssfColor(simple.getFillColor());
                item.line = simple.getLineStyle() == HSSFShape.LINESTYLE_NONE ? null : hssfColor(simple.getLineStyleColor());
                item.lineWidth = (float) Math.max(0, simple.getLineWidth() / 12700d);
                item.dashed = simple.getLineStyle() != HSSFShape.LINESTYLE_SOLID;
                item.startArrow = escher(simple, EscherPropertyTypes.LINESTYLE__LINESTARTARROWHEAD, 0) != 0;
                item.endArrow = escher(simple, EscherPropertyTypes.LINESTYLE__LINEENDARROWHEAD, 0) != 0;
                item.leftInset = escher(simple, EscherPropertyTypes.TEXT__TEXTLEFT, 91440) / 12700d;
                item.rightInset = escher(simple, EscherPropertyTypes.TEXT__TEXTRIGHT, 91440) / 12700d;
                item.topInset = escher(simple, EscherPropertyTypes.TEXT__TEXTTOP, 45720) / 12700d;
                item.bottomInset = escher(simple, EscherPropertyTypes.TEXT__TEXTBOTTOM, 45720) / 12700d;
                if (simple instanceof HSSFTextbox textbox) {
                    item.kind = Kind.TEXT; item.alignment = textbox.getHorizontalAlignment();
                }
                if (escher(simple, EscherPropertyTypes.FILL__FILLTYPE, 0) != 0)
                    warning("SHAPE_FILL_SIMPLIFIED", item, "複雑な塗りつぶしを単色に簡略化しました。");
                if (item.kind == Kind.LINE) warning("CONNECTOR_ENDPOINTS_UNKNOWN", item, "XLSの線は保存位置で描画し、重なりだけを根拠に他の図形とまとめます。");
                warning("XLS_DRAWING_APPROXIMATED", item, "XLS図形を簡易描画します。テーマ・効果・図形リンクは再現しません。");
            }
            if (item.kind == Kind.UNSUPPORTED) warning("SHAPE_UNSUPPORTED", item, "この種類の図形は未対応です。取得できた取消線除去後の文字を残します。");
        }

        private List<DrawingText.Run> flatten(DrawingText.Content content) {
            List<DrawingText.Run> runs = new ArrayList<>();
            boolean first = true;
            for (var paragraph : content.paragraphs()) {
                if (!first) runs.add(new DrawingText.Run("\n", "Noto Sans CJK JP", 12, false, false, null));
                first = false;
                runs.addAll(paragraph.runs());
            }
            return List.copyOf(runs);
        }

        private void style(Node xml, Node properties, DrawingScene.Item item) {
            Node style = child(xml, "style");
            Color defaultFill = color(child(style, "fillRef"), Color.WHITE);
            Color defaultLine = color(child(style, "lnRef"), Color.BLACK);
            item.fill = child(properties, "noFill") != null ? null : color(child(properties, "solidFill"), defaultFill);
            Node line = child(properties, "ln");
            item.line = child(line, "noFill") != null ? null : color(child(line, "solidFill"), defaultLine);
            item.lineWidth = (float) Math.max(0, attribute(line, "w", 12700) / 12700);
            item.startArrow = !Set.of("", "none").contains(attr(child(line, "headEnd"), "type"));
            item.endArrow = !Set.of("", "none").contains(attr(child(line, "tailEnd"), "type"));
            String dash = attr(child(line, "prstDash"), "val");
            item.dashed = !dash.isEmpty() && !dash.equals("solid");
            if (child(properties, "gradFill") != null || child(properties, "blipFill") != null
                    || child(properties, "pattFill") != null || child(properties, "effectLst") != null
                    || child(properties, "effectDag") != null || child(properties, "scene3d") != null
                    || child(properties, "sp3d") != null || child(properties, "custGeom") != null)
                warning("DRAWING_EFFECTS_SIMPLIFIED", item, "複雑な塗り・効果・自由形状は簡略化または代替表示します。");
        }

        private Color color(Node parent, Color fallback) {
            if (parent == null) return fallback;
            Node rgb = child(parent, "srgbClr"), scheme = child(parent, "schemeClr"), system = child(parent, "sysClr");
            Color value = fallback;
            Node source = rgb != null ? rgb : scheme != null ? scheme : system;
            try {
                if (rgb != null) value = new Color(Integer.parseInt(attr(rgb, "val"), 16));
                else if (system != null && !attr(system, "lastClr").isEmpty()) value = new Color(Integer.parseInt(attr(system, "lastClr"), 16));
                else if (scheme != null && sheet.getWorkbook() instanceof XSSFWorkbook workbook && workbook.getTheme() != null) {
                    int index = switch (attr(scheme, "val")) {
                        case "lt1", "bg1" -> 0; case "dk1", "tx1" -> 1; case "lt2", "bg2" -> 2; case "dk2", "tx2" -> 3;
                        case "accent1" -> 4; case "accent2" -> 5; case "accent3" -> 6; case "accent4" -> 7;
                        case "accent5" -> 8; case "accent6" -> 9; case "hlink" -> 10; case "folHlink" -> 11; default -> -1;
                    };
                    XSSFColor theme = index >= 0 ? workbook.getTheme().getThemeColor(index) : null;
                    if (theme != null && theme.getRGB() != null) {
                        byte[] bytes = theme.getRGB(); value = new Color(bytes[0] & 255, bytes[1] & 255, bytes[2] & 255);
                    }
                }
                if (value != null && source != null) {
                    double mod = attribute(child(source, "lumMod"), "val", 100000) / 100000;
                    double off = attribute(child(source, "lumOff"), "val", 0) / 100000;
                    double tint = attribute(child(source, "tint"), "val", 0) / 100000;
                    double shade = attribute(child(source, "shade"), "val", 100000) / 100000;
                    int alpha = clamp(attribute(child(source, "alpha"), "val", 100000) / 100000 * 255);
                    value = new Color(clamp((value.getRed() * mod + 255 * off) * shade * (1 - tint) + 255 * tint),
                            clamp((value.getGreen() * mod + 255 * off) * shade * (1 - tint) + 255 * tint),
                            clamp((value.getBlue() * mod + 255 * off) * shade * (1 - tint) + 255 * tint), alpha);
                }
            } catch (IllegalArgumentException ignored) { return fallback; }
            return value;
        }

        private List<List<DrawingScene.Item>> components() {
            int[] parents = new int[roots.size()];
            Map<Long, Integer> identifiers = new HashMap<>();
            List<Rectangle2D> bounds = new ArrayList<>();
            for (int i = 0; i < roots.size(); i++) {
                parents[i] = i; bounds.add(DrawingScene.bounds(roots.get(i)));
                for (var item : roots.get(i)) identifiers.putIfAbsent(item.id, i);
            }
            for (int i = 0; i < roots.size(); i++) {
                for (int j = i + 1; j < roots.size(); j++) {
                    if (roots.get(i).stream().allMatch(item -> item.positionKnown)
                            && roots.get(j).stream().allMatch(item -> item.positionKnown)
                            && bounds.get(i).intersects(bounds.get(j))) union(parents, i, j);
                }
                for (var item : roots.get(i)) for (long target : item.connections) {
                    Integer other = identifiers.get(target);
                    if (other != null) union(parents, i, other);
                    else warning("CONNECTOR_TARGET_MISSING", item, "接続先が対象範囲にないコネクターを保存位置で描画します。");
                }
            }
            Map<Integer, List<DrawingScene.Item>> result = new LinkedHashMap<>();
            for (int i = 0; i < roots.size(); i++) result.computeIfAbsent(find(parents, i), ignored -> new ArrayList<>()).addAll(roots.get(i));
            return new ArrayList<>(result.values());
        }

        private String pictureMarkdown(DrawingScene.Item item) {
            String path = workspace.addAsset("image", item.picture, item.extension, item.contentType);
            String label = Markdown.escape(item.alt.replace('\n', ' ').replace('\r', ' '));
            return inline(item) ? "![" + label + "](" + path + ")" : "[" + label + "（元画像）](" + path + ")";
        }
        private DrawingBlock block(List<DrawingScene.Item> items, String markdown) {
            if (items.stream().anyMatch(item -> !item.positionKnown))
                return new DrawingBlock(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, markdown);
            Rectangle2D bounds = null;
            for (var item : items) bounds = bounds == null ? item.placementBounds() : bounds.createUnion(item.placementBounds());
            return new DrawingBlock(coordinates.row(bounds.getMinY()), coordinates.column(bounds.getMinX()),
                    coordinates.row(bounds.getMaxY()), coordinates.column(bounds.getMaxX()), markdown);
        }
        private String range(Rectangle2D bounds) {
            String first = new CellReference(coordinates.row(bounds.getMinY()), coordinates.column(bounds.getMinX())).formatAsString();
            String last = new CellReference(coordinates.row(bounds.getMaxY()), coordinates.column(bounds.getMaxX())).formatAsString();
            return first.equals(last) ? first : first + ":" + last;
        }
        private void warning(String code, DrawingScene.Item item, String message) {
            workspace.warning(code, sheet.getSheetName(), item.positionKnown ? range(item.placementBounds()) : null, message);
        }
        private void info(String code, List<DrawingScene.Item> items, String message) {
            workspace.info(code, sheet.getSheetName(), items.stream().allMatch(item -> item.positionKnown) ? range(DrawingScene.bounds(items)) : null, message);
        }
    }

    private static boolean inline(DrawingScene.Item item) { return item.extension.equals("png") || item.extension.equals("jpg"); }
    private static int find(int[] parents, int value) {
        while (parents[value] != value) { parents[value] = parents[parents[value]]; value = parents[value]; }
        return value;
    }
    private static void union(int[] parents, int a, int b) { parents[find(parents, b)] = find(parents, a); }
    private static int clamp(double value) { return (int) Math.max(0, Math.min(255, Math.round(value))); }

    private static Node xml(Shape shape) {
        if (shape instanceof XSSFPicture picture) return picture.getCTPicture().getDomNode();
        if (shape instanceof XSSFSimpleShape simple) return simple.getCTShape().getDomNode();
        if (shape instanceof XSSFConnector connector) return connector.getCTConnector().getDomNode();
        if (shape instanceof XSSFShapeGroup group) return group.getCTGroupShape().getDomNode();
        if (shape instanceof XSSFGraphicFrame frame) return frame.getCTGraphicalObjectFrame().getDomNode();
        return null;
    }
    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (name.equals(node.getLocalName())) return node;
        return null;
    }
    private static boolean hasElementChildren(Node parent) {
        if (parent == null) return false;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node.getNodeType() == Node.ELEMENT_NODE) return true;
        return false;
    }
    private static String attr(Node node, String name) {
        if (node == null || node.getAttributes() == null) return "";
        Node value = node.getAttributes().getNamedItem(name);
        return value == null ? "" : value.getNodeValue();
    }
    private static double attribute(Node node, String name, double fallback) {
        String value = attr(node, name);
        if (value.isEmpty()) return fallback;
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static Node transform(Node xml) {
        Node properties = child(xml, "spPr");
        if (properties == null) properties = child(xml, "grpSpPr");
        return properties == null ? child(xml, "xfrm") : child(properties, "xfrm");
    }
    private static Rectangle2D anchor(Shape shape, Node xml, boolean childShape) {
        if (shape instanceof HSSFShape && shape.getAnchor() != null) {
            var anchor = shape.getAnchor();
            return SheetCoordinates.rectangle(Math.min(anchor.getDx1(), anchor.getDx2()), Math.min(anchor.getDy1(), anchor.getDy2()),
                    Math.abs(anchor.getDx2() - (double) anchor.getDx1()), Math.abs(anchor.getDy2() - (double) anchor.getDy1()));
        }
        Node xfrm = transform(xml), offset = child(xfrm, "off"), extent = child(xfrm, "ext");
        if (offset == null || extent == null) return null;
        return SheetCoordinates.rectangle(attribute(offset, "x", 0) / 12700, attribute(offset, "y", 0) / 12700,
                attribute(extent, "cx", 0) / 12700, attribute(extent, "cy", 0) / 12700);
    }
    private static double rotation(Shape shape, Node xml) {
        return shape instanceof HSSFShape hssf ? escher(hssf, EscherPropertyTypes.TRANSFORM__ROTATION, 0) / 65536d
                : attribute(transform(xml), "rot", 0) / 60000;
    }
    private static boolean flip(Shape shape, Node xml, boolean horizontal) {
        if (shape instanceof HSSFShape hssf) {
            EscherSpRecord record = hssfContainer(hssf).getChildById(EscherSpRecord.RECORD_ID);
            return record != null && (record.getFlags() & (horizontal ? EscherSpRecord.FLAG_FLIPHORIZ : EscherSpRecord.FLAG_FLIPVERT)) != 0;
        }
        String value = attr(transform(xml), horizontal ? "flipH" : "flipV");
        return value.equals("1") || value.equals("true");
    }
    private static Node nonVisual(Node xml) {
        for (String name : List.of("nvSpPr", "nvPicPr", "nvGrpSpPr", "nvCxnSpPr", "nvGraphicFramePr")) {
            Node properties = child(child(xml, name), "cNvPr");
            if (properties != null) return properties;
        }
        return null;
    }
    private static boolean hidden(Shape shape, Node xml) {
        String value = attr(nonVisual(xml), "hidden");
        if (value.equals("true") || value.equals("1")) return true;
        if (shape instanceof HSSFShape hssf) {
            int flags = escher(hssf, EscherPropertyTypes.GROUPSHAPE__HIDDEN, 0);
            return (flags & 2) != 0;
        }
        return false;
    }
    private static long identifier(Shape shape, Node xml, int fallback) {
        if (shape instanceof HSSFSimpleShape hssf) return hssf.getShapeId();
        return (long) attribute(nonVisual(xml), "id", -fallback);
    }
    private static int escher(HSSFShape shape, EscherPropertyTypes type, int fallback) {
        EscherOptRecord options = shape.getOptRecord();
        if (options == null) options = hssfContainer(shape).getChildById(EscherOptRecord.RECORD_ID);
        if (options == null) return fallback;
        EscherSimpleProperty property = options.lookup(type);
        return property == null ? fallback : property.getPropertyValue();
    }
    private static EscherContainerRecord hssfContainer(HSSFShape shape) {
        // HSSFShape's container is protected and its flip accessors do not handle groups.
        // Follow the same child order as the public shape tree through the public aggregate.
        List<HSSFShape> path = new ArrayList<>();
        for (HSSFShape current = shape; current != null; current = current.getParent()) path.addFirst(current);
        HSSFPatriarch patriarch = path.getFirst().getPatriarch();
        EscherContainerRecord container = (EscherContainerRecord) patriarch.getBoundAggregate()
                .findFirstWithId(EscherContainerRecord.SPGR_CONTAINER);
        List<HSSFShape> siblings = patriarch.getChildren();
        for (HSSFShape current : path) {
            int index = siblings.indexOf(current);
            List<EscherContainerRecord> records = container.getChildContainers();
            if (index < 0 || records.size() != siblings.size() + 1)
                throw new IllegalArgumentException("Inconsistent XLS drawing tree");
            container = records.get(index + 1); // First container describes the parent group itself.
            if (current instanceof HSSFShapeGroup group) siblings = group.getChildren();
        }
        EscherContainerRecord ownShape = container.getChildById(EscherContainerRecord.SP_CONTAINER);
        return ownShape == null ? container : ownShape;
    }
    private static Color hssfColor(int color) {
        return new Color(color & 255, color >>> 8 & 255, color >>> 16 & 255);
    }
    private static Kind kind(String type) {
        return switch (type) {
            case "rect" -> Kind.RECTANGLE; case "roundRect" -> Kind.ROUND_RECTANGLE; case "ellipse" -> Kind.ELLIPSE;
            case "line", "straightConnector1" -> Kind.LINE; case "rightArrow" -> Kind.RIGHT_ARROW;
            case "leftArrow" -> Kind.LEFT_ARROW; case "upArrow" -> Kind.UP_ARROW; case "downArrow" -> Kind.DOWN_ARROW;
            default -> Kind.UNSUPPORTED;
        };
    }
    private static Kind hssfKind(int type) {
        return switch (type) {
            case HSSFShapeTypes.Rectangle -> Kind.RECTANGLE; case HSSFShapeTypes.RoundRectangle -> Kind.ROUND_RECTANGLE;
            case HSSFShapeTypes.Ellipse -> Kind.ELLIPSE; case HSSFShapeTypes.Line, HSSFShapeTypes.StraightConnector1 -> Kind.LINE;
            case HSSFShapeTypes.Arrow, HSSFShapeTypes.ThickArrow -> Kind.RIGHT_ARROW; case HSSFShapeTypes.LeftArrow -> Kind.LEFT_ARROW;
            case HSSFShapeTypes.UpArrow -> Kind.UP_ARROW; case HSSFShapeTypes.DownArrow -> Kind.DOWN_ARROW;
            case HSSFShapeTypes.TextBox -> Kind.TEXT; default -> Kind.UNSUPPORTED;
        };
    }
    private static String[] sniff(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return new String[]{"png", "image/png"};
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return new String[]{"jpg", "image/jpeg"};
        if (bytes.length >= 44 && bytes[0] == 1 && bytes[1] == 0 && bytes[40] == 0x20 && bytes[41] == 'E' && bytes[42] == 'M' && bytes[43] == 'F') return new String[]{"emf", "image/emf"};
        if (bytes.length >= 4 && bytes[0] == (byte) 0xd7 && bytes[1] == (byte) 0xcd && bytes[2] == (byte) 0xc6 && bytes[3] == (byte) 0x9a) return new String[]{"wmf", "image/wmf"};
        if (bytes.length >= 40 && bytes[0] == 40 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0) return new String[]{"dib", "image/bmp"};
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') return new String[]{"bmp", "image/bmp"};
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return new String[]{"gif", "image/gif"};
        return new String[]{"bin", "application/octet-stream"};
    }
}
