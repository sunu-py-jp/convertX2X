package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import com.convertx2x.office2md.drawing.DrawingAltText;
import com.convertx2x.office2md.drawing.DiagramMetadata;
import com.convertx2x.office2md.drawing.DiagramGraph;
import com.convertx2x.office2md.drawing.NativeDrawingRenderer;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reads embedded images and a bounded subset of DrawingML/VML. Never follows external relationships. */
final class WordDrawings {
    private static final String VML = "urn:schemas-microsoft-com:vml";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private final ConversionWorkspace workspace;
    private int drawingNumber;
    private record Frame(AffineTransform transform, boolean known, boolean sizeKnown) { }

    WordDrawings(ConversionWorkspace workspace) { this.workspace = workspace; }

    String render(Node source, PackagePart sourcePart, String section, String range,
                  Function<Node, String> visibleText) {
        return new Reader(sourcePart, section, range, visibleText).render(source);
    }

    private static final class Item {
        String preset = "rect", text = "", alt = "画像", extension = "bin", contentType = "application/octet-stream";
        double width = 240, height = 120, rotation;
        AffineTransform transform = new AffineTransform();
        Color fill = Color.WHITE, line = Color.BLACK, textColor = Color.BLACK;
        float lineWidth = 1, fontSize = 12;
        String font = "Noto Sans CJK JP";
        boolean bold, flipped, geometryKnown, rotationKnown = true;
        Double savedWidth, savedHeight;
        String reference, sourceId, startId, endId;
        String startArrow = "none", endArrow = "none";
        boolean connector, renderable = true;
        String unsupportedMessage;
        int horizontal = 1, vertical = 1;
        byte[] picture;
        boolean inlineImage() { return extension.equals("png") || extension.equals("jpg"); }
        NativeDrawingRenderer.Shape shape() {
            return new NativeDrawingRenderer.Shape(connector ? "line" : preset, width, height, transform, fill, line, lineWidth,
                    text, font, fontSize, bold, textColor, horizontal, vertical, picture, extension,
                    directional(startArrow), directional(endArrow));
        }
    }

    private static final class Scope {
        final List<Object> outputs = new ArrayList<>();
    }

    private final class Reader {
        private final PackagePart part;
        private final String section, range;
        private final Function<Node, String> visibleText;
        private final List<Object> outputs = new ArrayList<>();
        private boolean handled;
        private Scope activeScope;

        Reader(PackagePart part, String section, String range, Function<Node, String> visibleText) {
            this.part = part; this.section = section; this.range = range; this.visibleText = visibleText;
        }

        String render(Node source) {
            if (local(source).equals("object")) {
                workspace.shapeVisited(1);
                unsupported("OLE_UNSUPPORTED", "埋め込みオブジェクトは展開しません。", source);
            } else {
                Node extent = find(source, "extent");
                double width = number(attr(extent, "cx"), 240 * 12700) / 12700;
                double height = number(attr(extent, "cy"), 120 * 12700) / 12700;
                boolean sizeKnown = emu(attr(extent, "cx")) != null && emu(attr(extent, "cy")) != null;
                read(source, new Frame(new AffineTransform(), true, sizeKnown), width, height, false, 1, 0);
                if (!handled && outputs.isEmpty())
                    unsupported("DRAWING_UNSUPPORTED", "このWord描画形式は未対応です。", source);
            }
            List<String> result = new ArrayList<>();
            for (Object output : outputs) {
                if (output instanceof String text) result.add(text);
                else result.add(renderScope((Scope) output));
            }
            return String.join("\n\n", result);
        }

        private String renderScope(Scope scope) {
            List<Item> items = scope.outputs.stream().filter(Item.class::isInstance).map(Item.class::cast).toList();
            if (items.isEmpty()) return String.join("\n\n", scope.outputs.stream().map(Object::toString).toList());
            String diagramRange = range + "/drawing:" + (++drawingNumber);
            List<DiagramGraph.Vertex> vertices = new ArrayList<>();
            List<DiagramGraph.Connection> connections = new ArrayList<>();
            List<NativeDrawingRenderer.Shape> shapes = new ArrayList<>();
            List<String> attachments = new ArrayList<>();
            List<Map<String, Object>> attachmentMetadata = new ArrayList<>();
            List<String> connectorText = new ArrayList<>();
            Map<String, Map<String, Object>> connectorMetadata = new LinkedHashMap<>();
            Rectangle2D union = null;
            boolean geometryKnown = true;
            int ordinal = 0;
            for (Item item : items) {
                String id = "shape-" + (++ordinal);
                Rectangle2D bounds = item.geometryKnown ? item.transform.createTransformedShape(
                        new Rectangle2D.Double(0, 0, item.width, item.height)).getBounds2D() : null;
                if (item.renderable && (item.picture == null || item.inlineImage())) {
                    geometryKnown &= bounds != null;
                    if (bounds != null) union = union == null ? bounds : union.createUnion(bounds);
                }
                String type = item.picture == null ? DrawingAltText.typeName(item.preset) : "画像";
                String text = item.picture == null ? item.text : item.alt.equals("画像") ? "" : item.alt;
                Map<String, Object> metadata = DiagramMetadata.of(type, text, bounds);
                if (item.connector) {
                    connections.add(new DiagramGraph.Connection(id, item.startId, item.endId, item.startArrow, item.endArrow));
                    connectorMetadata.put(id, metadata);
                    if (!text.isBlank()) connectorText.add("接続線 " + Markdown.escape(id) + " の文字：" + Markdown.escape(text).replace("\n", "<br>"));
                } else vertices.add(new DiagramGraph.Vertex(id, item.sourceId, type, text, Markdown.escape(text), metadata));
                if (item.picture != null && !item.inlineImage()) {
                    String path = workspace.addAsset("image", item.picture, item.extension, item.contentType);
                    attachments.add("[" + DiagramMetadata.imageAlt(metadata) + "](" + path + ")");
                    attachmentMetadata.add(Map.of("id", id, "path", path, "metadata", metadata));
                } else if (item.renderable) shapes.add(item.shape());
            }
            var edges = DiagramGraph.resolve(vertices, connections);
            List<String> output = new ArrayList<>();
            String body = DiagramGraph.markdown(vertices, edges, workspace, section, diagramRange);
            if (!body.isBlank()) output.add(body);
            output.addAll(connectorText);
            for (Item item : items) if (item.unsupportedMessage != null) output.add("[" + Markdown.escape(item.unsupportedMessage) + "]");
            for (Object entry : scope.outputs) if (entry instanceof String text) output.add(text);
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "diagram"); block.put("section", section); block.put("range", diagramRange);
            block.put("placement", items.get(0).reference);
            block.put("nodes", vertices.stream().map(DiagramGraph.Vertex::metadata).toList());
            if (!attachmentMetadata.isEmpty()) block.put("attachments", attachmentMetadata);
            block.put("edges", edges.stream().map(edge -> {
                Map<String, Object> metadata = new LinkedHashMap<>(edge.metadata());
                metadata.putAll(connectorMetadata.get(edge.id()));
                return metadata;
            }).toList());
            if (!shapes.isEmpty()) {
                String path = workspace.addAsset("diagram", NativeDrawingRenderer.render(shapes, workspace), "png", "image/png");
                Map<String, Object> metadata = items.size() == 1
                        ? DiagramMetadata.of(items.get(0).picture == null ? DrawingAltText.typeName(items.get(0).preset) : "画像",
                                items.get(0).picture == null ? items.get(0).text : items.get(0).alt, geometryKnown ? union : null)
                        : DiagramMetadata.of("図", "", geometryKnown ? union : null);
                output.add("![" + DiagramMetadata.imageAlt(metadata) + "](" + path + ")");
                block.put("path", path); block.put("metadata", metadata);
                workspace.info("DRAWING_SIMPLIFIED", section, diagramRange,
                        "Wordの図形・保存された接続を文字として保持し、同じ描画領域を確認用画像にまとめました。ページ全体の配置は再現しません。");
            }
            output.addAll(attachments);
            workspace.block(block);
            return String.join("\n\n", output);
        }

        private void read(Node node, Frame parent, double width, double height,
                          boolean flipped, int groupDepth, int xmlDepth) {
            if (node == null) return;
            if (xmlDepth > 128) throw ConversionWorkspace.limit("GROUP_DEPTH_LIMIT", "描画XMLの階層が上限を超えました。");
            String name = local(node);
            if (WordXml.omitted(node) || name.endsWith("PrChange") || name.equals("shapetype")) return;
            if (hidden(node)) {
                handled = true;
                workspace.info("HIDDEN_DRAWING", section, range, "非表示の描画オブジェクトを除外しました。"); return;
            }
            if (activeScope == null && (WP.equals(node.getNamespaceURI()) && List.of("inline", "anchor").contains(name)
                    || List.of("wpc", "wgp", "grpSp").contains(name)
                    || VML.equals(node.getNamespaceURI()) && name.equals("group"))) {
                Scope scope = new Scope(); outputs.add(scope); activeScope = scope;
                try { read(node, parent, width, height, flipped, groupDepth, xmlDepth); }
                finally { activeScope = null; }
                return;
            }
            if (name.equals("AlternateContent")) {
                Node selected = null;
                for (Node candidate : children(node)) {
                    if (local(candidate).equals("Choice") && (find(candidate, "wsp") != null || find(candidate, "pic") != null || find(candidate, "wgp") != null || find(candidate, "wpc") != null)) {
                        selected = candidate; break;
                    }
                    if (local(candidate).equals("Fallback")) selected = candidate;
                }
                if (selected != null) read(selected, parent, width, height, flipped, groupDepth, xmlDepth + 1);
                else unsupported("DRAWING_UNSUPPORTED", "互換描画の形式は未対応です。", node);
                return;
            }
            if (name.equals("object")) {
                workspace.shapeVisited(groupDepth);
                unsupported("OLE_UNSUPPORTED", "埋め込みオブジェクトは展開しません。", node);
                return;
            }
            if (name.equals("wgp") || name.equals("grpSp") || (VML.equals(node.getNamespaceURI()) && name.equals("group"))) {
                handled = true;
                workspace.shapeVisited(groupDepth);
                if (VML.equals(node.getNamespaceURI())) readVmlGroup(node, parent, width, height, flipped, groupDepth, xmlDepth);
                else readGroup(node, parent, width, height, flipped, groupDepth, xmlDepth);
                return;
            }
            if (name.equals("pic")) {
                handled = true;
                workspace.shapeVisited(groupDepth);
                Item item = drawingGeometry(node, parent, width, height, flipped);
                Node blip = find(node, "blip");
                if (image(node, blip, item)) add(item, node);
                return;
            }
            if (name.equals("wsp") || name.equals("sp") || name.equals("cxnSp")) {
                handled = true;
                workspace.shapeVisited(groupDepth);
                Item item = drawingGeometry(node, parent, width, height, flipped);
                Node props = child(node, "spPr"), preset = child(props, "prstGeom");
                item.preset = attr(preset, "prst");
                Node textbox = find(node, "txbxContent");
                if (item.preset.isEmpty() && textbox != null) item.preset = "textBox";
                if (item.preset.isEmpty()) item.preset = "custGeom";
                item.text = visible(node);
                item.fill = color(child(props, "solidFill"), Color.WHITE);
                if (child(props, "noFill") != null) item.fill = null;
                Node line = child(props, "ln");
                item.line = child(line, "noFill") == null ? color(child(line, "solidFill"), Color.BLACK) : null;
                item.lineWidth = (float) Math.min(100, number(attr(line, "w"), 12700) / 12700);
                Node connection = find(node, "cNvCnPr");
                if (connection == null) connection = find(node, "cNvCxnSpPr");
                item.connector = name.equals("cxnSp") || connection != null
                        || item.preset.equals("line") || item.preset.startsWith("straightConnector")
                        || item.preset.startsWith("bentConnector") || item.preset.startsWith("curvedConnector");
                item.startId = attr(child(connection, "stCxn"), "id");
                item.endId = attr(child(connection, "endCxn"), "id");
                item.startArrow = arrow(child(line, "headEnd")); item.endArrow = arrow(child(line, "tailEnd"));
                textStyle(node, item);
                if (NativeDrawingRenderer.supports(item.preset) || item.connector) {
                    if (item.connector && !NativeDrawingRenderer.supports(item.preset))
                        workspace.warning("DRAWING_SIMPLIFIED", section, range,
                                "接続線の保存された接続先を保持します。折れ線・曲線の経路は直線で近似します。");
                    add(item, node); textApproximation(item);
                } else unsupportedShape(item, node, "この種類のWord図形は未対応です（" + DrawingAltText.typeName(item.preset) + "）。文字と接続情報は保持します。");
                return;
            }
            if (VML.equals(node.getNamespaceURI()) && List.of("shape", "rect", "roundrect", "oval", "line").contains(name)) {
                handled = true;
                workspace.shapeVisited(groupDepth);
                Item item = vmlGeometry(node, parent, width, height, flipped);
                Node image = child(node, "imagedata");
                if (image != null) {
                    if (image(node, image, item)) add(item, node);
                    String text = visible(node);
                    if (!text.isBlank()) output(Markdown.escape(text));
                    return;
                }
                item.preset = switch (name) {
                    case "rect" -> "rect"; case "roundrect" -> "roundRect"; case "oval" -> "ellipse"; case "line" -> "line";
                    default -> vmlPreset(attr(node, "type"), find(node, "txbxContent") != null);
                };
                item.text = visible(node);
                item.fill = flag(attr(node, "filled"), true) ? parseColor(attr(node, "fillcolor"), Color.WHITE) : null;
                item.line = flag(attr(node, "stroked"), true) ? parseColor(attr(node, "strokecolor"), Color.BLACK) : null;
                item.lineWidth = (float) Math.min(100, points(attr(node, "strokeweight"), 1));
                item.connector = item.preset.equals("line");
                Node stroke = child(node, "stroke");
                item.startArrow = vmlArrow(attr(stroke, "startarrow")); item.endArrow = vmlArrow(attr(stroke, "endarrow"));
                textStyle(node, item);
                if (NativeDrawingRenderer.supports(item.preset)) { add(item, node); textApproximation(item); }
                else unsupportedShape(item, node, "この種類のVML図形は未対応です（" + DrawingAltText.typeName(item.preset) + "）。文字は保持します。");
                return;
            }
            if (List.of("chart", "relIds", "OLEObject").contains(name)) {
                workspace.shapeVisited(groupDepth);
                unsupported(name.equals("chart") ? "CHART_UNSUPPORTED" : "DRAWING_UNSUPPORTED", "グラフ・SmartArt・埋め込みオブジェクトは描画しません。", node);
                return;
            }
            for (Node child : children(node)) read(child, parent, width, height, flipped, groupDepth, xmlDepth + 1);
        }

        private void readGroup(Node group, Frame parent, double width, double height,
                               boolean flipped, int groupDepth, int xmlDepth) {
            Node props = child(group, "grpSpPr"), xfrm = child(props, "xfrm");
            Item box = geometry(xfrm, parent, width, height, flipped);
            Node offset = child(xfrm, "chOff"), extent = child(xfrm, "chExt");
            double innerWidth = number(attr(extent, "cx"), box.width * 12700) / 12700;
            double innerHeight = number(attr(extent, "cy"), box.height * 12700) / 12700;
            if (innerWidth <= 0 || innerHeight <= 0) {
                unsupported("GROUP_TRANSFORM_INVALID", "グループの座標を解決できません。", group); return;
            }
            box.transform.scale(box.width / innerWidth, box.height / innerHeight);
            box.transform.translate(-number(attr(offset, "x"), 0) / 12700, -number(attr(offset, "y"), 0) / 12700);
            for (Node child : children(group)) if (!local(child).equals("grpSpPr"))
                read(child, new Frame(box.transform, box.geometryKnown
                        && emu(attr(offset, "x")) != null && emu(attr(offset, "y")) != null
                        && emu(attr(extent, "cx")) != null && emu(attr(extent, "cy")) != null, true),
                        innerWidth, innerHeight, box.flipped, groupDepth + 1, xmlDepth + 1);
        }

        private void readVmlGroup(Node group, Frame parent, double width, double height,
                                  boolean flipped, int groupDepth, int xmlDepth) {
            Item box = vmlGeometry(group, parent, width, height, flipped);
            double[] size = pair(attr(group, "coordsize"), box.width, box.height), origin = pair(attr(group, "coordorigin"), 0, 0);
            if (size[0] <= 0 || size[1] <= 0) {
                unsupported("GROUP_TRANSFORM_INVALID", "VMLグループの座標を解決できません。", group); return;
            }
            box.transform.scale(box.width / size[0], box.height / size[1]);
            box.transform.translate(-origin[0], -origin[1]);
            for (Node child : children(group)) read(child, new Frame(box.transform, box.geometryKnown && validPair(attr(group, "coordsize"))
                    && (attr(group, "coordorigin").isBlank() || validPair(attr(group, "coordorigin"))), true),
                    size[0], size[1], box.flipped, groupDepth + 1, xmlDepth + 1);
        }

        private boolean image(Node node, Node blip, Item item) {
            workspace.imagePlacement();
            String id = rel(blip, "embed");
            if (id.isEmpty()) id = rel(blip, "id");
            if (!rel(blip, "link").isEmpty() || !attr(blip, "src").isEmpty() || id.isEmpty() || part == null) {
                unsupported("EXTERNAL_IMAGE_UNSUPPORTED", "外部画像やリンク画像は取得しません。", null); return false;
            }
            try {
                var relationship = part.getRelationship(id);
                if (relationship == null || relationship.getTargetMode() != TargetMode.INTERNAL) {
                    unsupported("EXTERNAL_IMAGE_UNSUPPORTED", "外部画像やリンク画像は取得しません。", null); return false;
                }
                PackagePart imagePart = part.getRelatedPart(relationship);
                long limit = Math.min(workspace.limits().maxImageBytes(), Integer.MAX_VALUE - 1L);
                try (var input = imagePart.getInputStream()) { item.picture = input.readNBytes((int) limit + 1); }
                if (item.picture.length > limit) throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                String[] format = imageFormat(item.picture, imagePart.getContentType());
                item.extension = format[0]; item.contentType = format[1];
                Node properties = find(node, "cNvPr");
                String alt = attr(properties, "descr");
                if (alt.isBlank()) alt = attr(node, "alt");
                if (alt.isBlank()) {
                    for (Node p = node.getParentNode(); p != null; p = p.getParentNode()) {
                        Node docPr = child(p, "docPr");
                        if (docPr != null) { alt = attr(docPr, "descr"); break; }
                    }
                }
                if (!alt.isBlank()) item.alt = alt;
                if (item.inlineImage()) NativeDrawingRenderer.verifyPicture(item.picture, workspace);
                else workspace.warning("IMAGE_FORMAT_ATTACHMENT", section, range, "この画像形式は元ファイルの添付として保持します。");
                if (find(node, "srcRect") != null)
                    workspace.warning("IMAGE_EFFECTS_IGNORED", section, range, "画像の切り抜きは未対応です。回転・反転・グループ変換はPNG/JPEGの確認用画像に反映します。");
                return true;
            } catch (Exception error) {
                if (error instanceof ConversionException conversion) throw conversion;
                unsupported("IMAGE_UNREADABLE", "埋め込み画像を読み取れませんでした。", null); return false;
            }
        }

        private void unsupportedShape(Item item, Node node, String message) {
            item.renderable = false; item.unsupportedMessage = message;
            workspace.warning("SHAPE_UNSUPPORTED", section, range, message);
            add(item, node);
        }

        private void output(String text) {
            if (activeScope == null) outputs.add(text); else activeScope.outputs.add(text);
        }
        private void add(Item item, Node source) {
            item.reference = placement(source);
            Node properties = child(source, "cNvPr");
            if (properties == null) {
                for (String container : List.of("nvSpPr", "nvPicPr", "nvCxnSpPr")) {
                    properties = child(child(source, container), "cNvPr");
                    if (properties != null) break;
                }
            }
            item.sourceId = VML.equals(source.getNamespaceURI()) ? attr(source, "id") : attr(properties, "id");
            if (item.sourceId.isBlank()) {
                for (Node ancestor = source.getParentNode(); ancestor != null; ancestor = ancestor.getParentNode()) {
                    if (List.of("wpc", "wgp", "grpSp", "group").contains(local(ancestor))) break;
                    if (WP.equals(ancestor.getNamespaceURI()) && List.of("inline", "anchor").contains(local(ancestor))) {
                        item.sourceId = attr(child(ancestor, "docPr"), "id"); break;
                    }
                }
            }
            if (activeScope == null) { Scope scope = new Scope(); scope.outputs.add(item); outputs.add(scope); }
            else activeScope.outputs.add(item);
        }
        private String placement(Node source) {
            for (Node n = source; n != null; n = n.getParentNode()) {
                if (WP.equals(n.getNamespaceURI()) && local(n).equals("inline"))
                    return "本文内、" + range + "、ローカル座標（ページ位置未算出）";
                if (WP.equals(n.getNamespaceURI()) && local(n).equals("anchor"))
                    return "浮動図形、" + range + "、ローカル座標（ページ位置未算出）、保存配置: " + anchorPlacement(n);
            }
            if (VML.equals(source.getNamespaceURI())) {
                Node outer = source;
                for (Node n = source.getParentNode(); n != null; n = n.getParentNode())
                    if (VML.equals(n.getNamespaceURI()) && local(n).equals("group")) outer = n;
                Map<String, String> saved = style(outer);
                return "VML、" + range + "、ローカル座標（ページ位置未算出）、保存配置: position="
                        + token(saved.get("position"), List.of("absolute", "relative", "static"))
                        + "、水平基準=" + reference(saved.get("mso-position-horizontal-relative"))
                        + "、垂直基準=" + reference(saved.get("mso-position-vertical-relative"))
                        + vmlOffset(saved, "left") + vmlOffset(saved, "top")
                        + vmlAlignment(saved, "horizontal") + vmlAlignment(saved, "vertical");
            }
            return "本文内、" + range + "、ローカル座標（本文の配置指定不明・ページ位置未算出）";
        }
        private String anchorPlacement(Node anchor) {
            if (flag(attr(anchor, "simplePos"), false)) {
                Node simple = child(anchor, "simplePos");
                Double x = emu(attr(simple, "x")), y = emu(attr(simple, "y"));
                return "simplePos X=" + (x == null ? "未算出" : pt(x)) + "、Y=" + (y == null ? "未算出" : pt(y));
            }
            return axis(child(anchor, "positionH"), "水平") + "、" + axis(child(anchor, "positionV"), "垂直");
        }
        private String axis(Node position, String name) {
            String result = name + "基準=" + reference(attr(position, "relativeFrom"));
            Node offset = child(position, "posOffset");
            if (offset != null) {
                Double value = emu(WordXml.text(offset));
                return result + "、保存オフセット=" + (value == null ? "未算出（無効値）" : pt(value));
            }
            Node align = child(position, "align");
            if (align != null) return result + "、保存align=" + token(WordXml.text(align), ALIGNMENTS) + "（オフセット未算出）";
            for (Node n : children(position)) if (local(n).contains("posOffsetPct")) {
                Double value = finite(WordXml.text(n), 1d / 1000);
                return result + "、保存割合=" + (value == null ? "不明" : decimal(value) + "%") + "（オフセット未算出）";
            }
            return result + "、保存オフセット未指定・未算出";
        }
        private String vmlOffset(Map<String, String> saved, String name) {
            String key = saved.containsKey(name) ? name : "margin-" + name;
            String raw = saved.get(key);
            if (raw == null) return "、" + name + "未指定";
            Double value = measuredPoints(raw);
            return "、保存" + key + "=" + (value == null ? "未算出" : pt(value));
        }
        private String vmlAlignment(Map<String, String> saved, String axis) {
            String value = saved.get("mso-position-" + axis);
            return value == null ? "" : "、保存" + axis + "=" + token(value, ALIGNMENTS) + "（基準枠の組版位置は未算出）";
        }
        private String visible(Node node) {
            if (node == null) return "";
            String text = visibleText.apply(node);
            return text == null ? "" : text;
        }
        private void unsupported(String code, String message, Node node) {
            workspace.warning(code, section, range, message);
            String text = visible(node);
            output("[" + Markdown.escape(message) + "]" + (text.isBlank() ? "" : " " + Markdown.escape(text)));
        }
        private void textApproximation(Item item) {
            if (!item.text.isBlank()) workspace.warning("DRAWING_TEXT_APPROXIMATED", section, range,
                    "Word図形内の文字は取消線・非表示を除去して簡易描画します。段落ごとの書式・余白・文字回転は近似です。");
        }
    }

    private static Item drawingGeometry(Node node, Frame parent, double width, double height, boolean flipped) {
        return geometry(child(child(node, "spPr"), "xfrm"), parent, width, height, flipped);
    }
    private static Item geometry(Node xfrm, Frame parent, double width, double height, boolean flipped) {
        Item item = new Item();
        Node offset = child(xfrm, "off"), extent = child(xfrm, "ext");
        item.width = number(attr(extent, "cx"), width * 12700) / 12700;
        item.height = number(attr(extent, "cy"), height * 12700) / 12700;
        item.rotation = number(attr(xfrm, "rot"), 0) / 60000;
        item.savedWidth = extent != null ? emu(attr(extent, "cx")) : parent.sizeKnown() ? width : null;
        item.savedHeight = extent != null ? emu(attr(extent, "cy")) : parent.sizeKnown() ? height : null;
        item.rotationKnown = attr(xfrm, "rot").isBlank() || finite(attr(xfrm, "rot"), 1d / 60000) != null;
        item.geometryKnown = parent.known() && emu(attr(offset, "x")) != null && emu(attr(offset, "y")) != null
                && item.savedWidth != null && item.savedHeight != null && item.rotationKnown;
        boolean flipH = flag(attr(xfrm, "flipH"), false), flipV = flag(attr(xfrm, "flipV"), false);
        item.flipped = flipped || flipH || flipV;
        item.transform = transformed(parent.transform(), number(attr(offset, "x"), 0) / 12700,
                number(attr(offset, "y"), 0) / 12700, item.width, item.height, item.rotation, flipH, flipV);
        return item;
    }
    private static Item vmlGeometry(Node node, Frame parent, double width, double height, boolean flipped) {
        Map<String, String> style = style(node);
        Item item = new Item();
        item.width = points(style.get("width"), width); item.height = points(style.get("height"), height);
        item.savedWidth = measuredPoints(style.get("width")); item.savedHeight = measuredPoints(style.get("height"));
        boolean knownPosition = measuredPoints(style.getOrDefault("left", style.get("margin-left"))) != null
                && measuredPoints(style.getOrDefault("top", style.get("margin-top"))) != null;
        if (style.containsKey("mso-position-horizontal") && !style.get("mso-position-horizontal").equals("absolute")
                || style.containsKey("mso-position-vertical") && !style.get("mso-position-vertical").equals("absolute"))
            knownPosition = false;
        double left = points(style.getOrDefault("left", style.get("margin-left")), 0), top = points(style.getOrDefault("top", style.get("margin-top")), 0);
        boolean lineFlipH = false, lineFlipV = false;
        if (local(node).equals("line")) {
            double[] from = pair(attr(node, "from"), left, top), to = pair(attr(node, "to"), left + item.width, top + item.height);
            left = Math.min(from[0], to[0]); top = Math.min(from[1], to[1]);
            item.width = Math.abs(to[0] - from[0]); item.height = Math.abs(to[1] - from[1]);
            lineFlipH = to[0] < from[0]; lineFlipV = to[1] < from[1];
            if (validPair(attr(node, "from")) && validPair(attr(node, "to"))) {
                knownPosition = true; item.savedWidth = item.width; item.savedHeight = item.height;
            }
        }
        item.rotation = number(style.get("rotation"), 0);
        item.rotationKnown = !style.containsKey("rotation") || finite(style.get("rotation"), 1) != null;
        item.geometryKnown = parent.known() && knownPosition && item.savedWidth != null && item.savedHeight != null && item.rotationKnown;
        String flip = style.getOrDefault("flip", "");
        boolean flipH = flip.contains("x") ^ lineFlipH, flipV = flip.contains("y") ^ lineFlipV;
        item.flipped = flipped || flipH || flipV;
        item.transform = transformed(parent.transform(), left, top, item.width, item.height, item.rotation, flipH, flipV);
        return item;
    }
    private static AffineTransform transformed(AffineTransform parent, double x, double y, double width, double height,
                                               double rotation, boolean flipH, boolean flipV) {
        if (width < 0 || height < 0) throw new ConversionException(422, "DRAWING_GEOMETRY_INVALID", "図形の大きさが不正です。");
        for (double value : new double[]{x, y, width, height, rotation})
            if (!Double.isFinite(value) || Math.abs(value) > 100_000_000)
                throw ConversionWorkspace.limit("IMAGE_PIXEL_LIMIT", "図形の座標または大きさが上限を超えています。");
        AffineTransform transform = new AffineTransform(parent);
        transform.translate(x + width / 2, y + height / 2); transform.rotate(Math.toRadians(rotation));
        transform.scale(flipH ? -1 : 1, flipV ? -1 : 1); transform.translate(-width / 2, -height / 2);
        return transform;
    }
    private static String arrow(Node node) { return node == null ? "none" : attr(node, "type").isBlank() ? "none" : attr(node, "type"); }
    private static String vmlArrow(String value) {
        return switch (value) { case "", "none" -> "none"; case "block" -> "triangle"; case "classic" -> "stealth"; case "open" -> "arrow"; default -> value; };
    }
    private static boolean directional(String type) { return List.of("triangle", "stealth", "arrow").contains(type); }
    private static void textStyle(Node shape, Item item) {
        Node textbox = find(shape, "txbxContent"), paragraph = find(textbox, "p"), props = child(paragraph, "pPr");
        String align = wval(child(props, "jc"));
        item.horizontal = align.equals("center") ? 2 : align.equals("right") ? 3 : 1;
        Node body = child(shape, "bodyPr");
        if (body == null) body = child(child(shape, "txBody"), "bodyPr");
        String anchor = attr(body, "anchor"); item.vertical = anchor.equals("ctr") ? 2 : anchor.equals("b") ? 3 : 1;
        Node runProps = find(textbox, "rPr");
        item.bold = WordXml.on(child(runProps, "b"));
        item.fontSize = (float) Math.max(1, Math.min(400, number(wval(child(runProps, "sz")), 24) / 2));
        item.textColor = parseColor(wval(child(runProps, "color")), Color.BLACK);
        Node fonts = child(runProps, "rFonts");
        String font = wattr(fonts, "eastAsia"); if (font.isEmpty()) font = wattr(fonts, "ascii");
        if (!font.isBlank()) item.font = font;
    }
    private static String vmlPreset(String type, boolean textbox) {
        return switch (type.replace("#", "")) {
            case "_x0000_t1" -> "rect"; case "_x0000_t2" -> "roundRect"; case "_x0000_t3" -> "ellipse";
            case "_x0000_t20", "_x0000_t32" -> "line"; case "_x0000_t202" -> "textBox";
            default -> type.isEmpty() && textbox ? "textBox" : type;
        };
    }
    private static boolean hidden(Node node) {
        Map<String, String> style = style(node);
        if (style.getOrDefault("visibility", "").equalsIgnoreCase("hidden") || style.getOrDefault("display", "").equalsIgnoreCase("none")) return true;
        if (flag(attr(node, "hidden"), false)) return true;
        Node properties = child(node, "docPr");
        if (properties == null) properties = child(child(node, "nvPicPr"), "cNvPr");
        if (properties == null) properties = child(child(node, "nvSpPr"), "cNvPr");
        if (properties == null) properties = child(child(node, "nvGrpSpPr"), "cNvPr");
        if (properties == null) properties = child(node, "cNvPr");
        return flag(attr(properties, "hidden"), false);
    }
    private static Map<String, String> style(Node node) {
        Map<String, String> style = new LinkedHashMap<>();
        for (String entry : attr(node, "style").split(";")) {
            int colon = entry.indexOf(':');
            if (colon > 0) style.put(entry.substring(0, colon).trim().toLowerCase(Locale.ROOT), entry.substring(colon + 1).trim());
        }
        return style;
    }
    private static Color color(Node fill, Color fallback) {
        Node rgb = child(fill, "srgbClr"); return rgb == null ? fallback : parseColor(attr(rgb, "val"), fallback);
    }
    private static Color parseColor(String raw, Color fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            if (hex.matches("[a-f0-9]{6}")) return new Color(Integer.parseInt(hex, 16));
            if (hex.matches("[a-f0-9]{3}")) return new Color(Integer.parseInt(""+hex.charAt(0)+hex.charAt(0)+hex.charAt(1)+hex.charAt(1)+hex.charAt(2)+hex.charAt(2),16));
            return switch(value) { case "white" -> Color.WHITE; case "black" -> Color.BLACK; case "red" -> Color.RED; case "blue" -> Color.BLUE; case "green" -> Color.GREEN; default -> fallback; };
        } catch (IllegalArgumentException ignored) { return fallback; }
    }
    private static String[] imageFormat(byte[] bytes, String type) {
        if (bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return new String[]{"png","image/png"};
        if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return new String[]{"jpg","image/jpeg"};
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') return new String[]{"bmp","image/bmp"};
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return new String[]{"gif","image/gif"};
        return switch(type) { case "image/x-emf", "image/emf" -> new String[]{"emf","image/emf"}; case "image/x-wmf", "image/wmf" -> new String[]{"wmf","image/wmf"}; default -> new String[]{"bin","application/octet-stream"}; };
    }
    private static double[] pair(String value, double x, double y) {
        if (value == null) return new double[]{x,y};
        String[] parts = value.trim().split("[, ]+");
        return parts.length == 2 ? new double[]{points(parts[0],x),points(parts[1],y)} : new double[]{x,y};
    }
    private static boolean validPair(String value) {
        if (value == null || value.isBlank()) return false;
        String[] values = value.trim().split("[, ]+");
        return values.length == 2 && measuredPoints(values[0]) != null && measuredPoints(values[1]) != null;
    }
    private static final List<String> ALIGNMENTS = List.of("left", "right", "center", "inside", "outside", "top", "bottom", "absolute");
    private static String reference(String value) {
        return token(value, List.of("page", "margin", "column", "character", "paragraph", "line", "text", "char",
                "leftMargin", "rightMargin", "topMargin", "bottomMargin", "insideMargin", "outsideMargin"));
    }
    private static String token(String value, List<String> allowed) {
        return value != null && allowed.contains(value.trim()) ? value.trim() : "不明";
    }
    private static String decimal(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    private static String pt(double value) { return decimal(value) + "pt"; }
    private static Double emu(String raw) { return finite(raw, 1d / 12700); }
    private static Double finite(String raw, double scale) {
        try {
            double value = Double.parseDouble(raw) * scale;
            return Double.isFinite(value) && Math.abs(value) <= 100_000_000 ? value : null;
        } catch (NullPointerException | NumberFormatException ignored) { return null; }
    }
    private static Double measuredPoints(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        double scale = 1;
        if (value.endsWith("pt")) value = value.substring(0, value.length() - 2);
        else if (value.endsWith("px")) { value = value.substring(0, value.length() - 2); scale = .75; }
        else if (value.endsWith("in")) { value = value.substring(0, value.length() - 2); scale = 72; }
        else if (value.endsWith("cm")) { value = value.substring(0, value.length() - 2); scale = 72 / 2.54; }
        else if (value.endsWith("mm")) { value = value.substring(0, value.length() - 2); scale = 72 / 25.4; }
        return finite(value, scale);
    }
    private static double points(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v=value.trim().toLowerCase(Locale.ROOT); double scale=1;
        if (v.endsWith("pt")) v=v.substring(0,v.length()-2);
        else if (v.endsWith("px")) {v=v.substring(0,v.length()-2);scale=.75;}
        else if (v.endsWith("in")) {v=v.substring(0,v.length()-2);scale=72;}
        else if (v.endsWith("cm")) {v=v.substring(0,v.length()-2);scale=72/2.54;}
        else if (v.endsWith("mm")) {v=v.substring(0,v.length()-2);scale=72/25.4;}
        return number(v,fallback/scale)*scale;
    }
    private static double number(String value, double fallback) {
        try { double parsed=Double.parseDouble(value); return Double.isFinite(parsed) ? parsed : fallback; }
        catch (NullPointerException | NumberFormatException ignored) { return fallback; }
    }
    private static boolean flag(String value, boolean fallback) {
        return value == null || value.isBlank() ? fallback : List.of("true","1","t","on").contains(value.toLowerCase(Locale.ROOT));
    }
    private static String attr(Node node, String name) { return node instanceof Element e ? e.getAttribute(name) : ""; }
    private static String rel(Node node, String name) { return node instanceof Element e ? e.getAttributeNS(REL,name) : ""; }
    private static String wattr(Node node, String name) { return node instanceof Element e ? e.getAttributeNS(WordXml.W,name) : ""; }
    private static String wval(Node node) { return wattr(node,"val"); }
    private static String local(Node node) { return node == null ? "" : node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }
    private static List<Node> children(Node node) { return WordXml.children(node); }
    private static Node child(Node node, String name) {
        for (Node candidate : children(node)) if (local(candidate).equals(name)) return candidate;
        return null;
    }
    private static Node find(Node node, String name) {
        if (node == null) return null;
        List<Node> pending = new ArrayList<>(); pending.add(node);
        for (int i=0;i<pending.size();i++) {
            Node current=pending.get(i);
            if (local(current).equals(name)) return current;
            if (!WordXml.omitted(current) && !local(current).endsWith("PrChange")) pending.addAll(children(current));
        }
        return null;
    }
}
