package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.*;
import com.convertx2x.office2md.drawing.ConnectorGeometry;
import com.convertx2x.office2md.drawing.DrawingAltText;
import com.convertx2x.office2md.images.EmbeddedImagePreprocessor;
import com.convertx2x.office2md.ocr.OcrClient;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.*;
import org.w3c.dom.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** RAG-oriented PPTX text and saved connections, with a slide preview. Never follows external content. */
public final class PowerPointMarkdownConverter {
    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
    private final ConversionLimits limits;

    public PowerPointMarkdownConverter(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public ConversionResult convert(byte[] input, String filename) {
        return convert(input, filename, ImageMode.IGNORE, OcrClient.disabled());
    }

    public ConversionResult convert(byte[] input, String filename, ImageMode imageMode, OcrClient ocrClient) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "入力ファイルが空です。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        if (FileMagic.valueOf(input) == FileMagic.OLE2) {
            try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(input))) {
                if (filesystem.getRoot().hasEntry("EncryptedPackage"))
                    throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化されたファイルは変換できません。");
            } catch (IOException failure) {
                throw new ConversionException(422, "INVALID_DOCUMENT", "ファイルを読み取れませんでした。", failure);
            }
            throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "PowerPointのPPTX形式を指定してください。");
        }
        ConversionWorkspace workspace = new ConversionWorkspace(limits, imageMode, ocrClient);
        try (OPCPackage archive = OPCPackage.open(new ByteArrayInputStream(input))) {
            if (archive.getPartsByContentType(CONTENT_TYPE).isEmpty())
                throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "PowerPointのPPTX形式を指定してください。");
            for (var part : archive.getParts()) {
                String contentType = part.getContentType().toLowerCase(Locale.ROOT);
                String partName = part.getPartName().getName().toLowerCase(Locale.ROOT);
                if (contentType.contains("vbaproject") || partName.endsWith("/vbaproject.bin"))
                    throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "マクロを含むPowerPointファイルは変換対象外です。");
            }
            try (XMLSlideShow presentation = new XMLSlideShow(archive)) {
                new Reader(workspace).read(presentation);
            }
            workspace.finishReport(safeFilename(filename), ConversionWorkspace.sha256(input));
            return new ConversionResult(workspace);
        } catch (Throwable failure) {
            try { workspace.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof ConversionException conversion) throw conversion;
            if (failure instanceof Error error) throw error;
            if (failure instanceof EncryptedDocumentException)
                throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化されたファイルは変換できません。", failure);
            throw new ConversionException(422, "INVALID_DOCUMENT", "PowerPointファイルを読み取り、変換できませんでした。", failure);
        }
    }

    private static String safeFilename(String filename) {
        String name = Objects.toString(filename, "presentation.pptx").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return name.isBlank() ? "presentation.pptx" : name.substring(0, Math.min(255, name.length()));
    }

    private final class Reader {
        private final ConversionWorkspace workspace;
        private final StringBuilder markdown = new StringBuilder();
        private long readItems, tableCells, markdownBytes;
        private String section;
        private int sequence;

        Reader(ConversionWorkspace workspace) { this.workspace = workspace; }

        void read(XMLSlideShow presentation) throws IOException {
            if (ConversionLimits.exceeds(presentation.getSlides().size(), limits.maxSections()))
                throw ConversionWorkspace.limit("SECTION_LIMIT", "スライド数が上限を超えました。");
            int ordinal = 0;
            for (XSLFSlide slide : presentation.getSlides()) {
                ordinal++;
                if (slide.isHidden()) continue;
                section = "スライド" + ordinal;
                workspace.sectionIncluded();
                sequence = 0;
                List<Entry> entries = new ArrayList<>();
                for (XSLFShape shape : List.copyOf(slide.getShapes())) {
                    Entry entry = inspect(shape, 0, new AffineTransform(), null);
                    if (entry != null) leaves(entry, entries);
                }
                List<Entry> visible = List.copyOf(entries);
                List<PresentationConnections.Edge> edges = PresentationConnections.extract(visible.stream()
                        .filter(entry -> entry.unsupported == null).map(entry -> entry.shape).toList());
                edges = inferMissingConnections(visible, edges);
                Set<String> endpoints = new HashSet<>();
                for (var edge : edges) {
                    if (edge.startId() != null) endpoints.add(edge.startId());
                    if (edge.endId() != null) endpoints.add(edge.endId());
                }
                List<Entry> nodes = visible.stream().filter(entry -> entry.unsupported == null
                        && !(entry.shape instanceof XSLFConnectorShape)
                        && (drawing(entry) || endpoints.contains(range(entry.shape)) || entry.groupKey != null))
                        .sorted(readingOrder()).toList();
                Entry title = entries.stream().filter(entry -> isTitle(entry.shape) && !entry.text.plain().isBlank())
                        .min(readingOrder()).orElse(null);
                append("[page " + ordinal + "]\n\n");
                append("# " + Markdown.escape(title == null ? section : DrawingAltText.singleLine(title.text.plain())) + "\n\n");
                workspace.block(Map.of("type", "heading", "section", section, "range", "slide-" + ordinal, "level", 1));
                if (title != null) entries.remove(title);
                entries.sort(readingOrder());
                Rectangle2D slideBounds = new Rectangle2D.Double(0, 0,
                        presentation.getPageSize().getWidth(), presentation.getPageSize().getHeight());
                for (Entry entry : entries) {
                    // Standalone pictures stay among surrounding text. Connected/grouped pictures
                    // remain with their diagram so its native text and relationships stay together.
                    if (entry.picture != null && entry.groupKey == null && !endpoints.contains(range(entry.shape)))
                        emitEmbeddedImage(entry, slideBounds);
                    else if (!drawing(entry) && !(entry.normalText && endpoints.contains(range(entry.shape)))) emit(entry);
                }
                if (!nodes.isEmpty() || !edges.isEmpty()) {
                    int componentOrdinal = 0;
                    for (DiagramComponent component : components(visible, nodes, edges))
                        emitDiagram(slide, component.entries(), component.nodes(), component.edges(), ordinal,
                                ++componentOrdinal, slideBounds);
                }
            }
            workspace.write("document.md", markdown.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void count(int amount) {
            readItems += amount;
            if (ConversionLimits.exceeds(readItems, limits.maxReadItems())) throw ConversionWorkspace.limit("READ_ITEMS_LIMIT", "文字・読み取り要素数が上限を超えました。");
        }

        private Entry inspect(XSLFShape shape, int depth, AffineTransform parent, String groupKey) throws IOException {
            workspace.shapeVisited(depth);
            if (hidden(shape) || excludedPlaceholder(shape)) return null;
            Rectangle2D anchor = anchor(shape);
            if (anchor == null) {
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), "位置を解釈できない図形を除外しました。");
                return null;
            }
            if (!finite(anchor)) throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "図形の座標が有効な範囲ではありません。");
            AffineTransform transform = shapeTransform(shape, parent, anchor);
            Entry entry = new Entry(shape, transform.createTransformedShape(anchor).getBounds2D(), sequence++,
                    new AffineTransform(parent), groupKey);
            stripExternalBlips(shape);
            if (shape instanceof XSLFTable table) {
                long cells = (long) table.getNumberOfRows() * table.getNumberOfColumns();
                tableCells += cells;
                limits.checkTableCells(tableCells);
                entry.table = table(table, entry);
            } else if (shape instanceof XSLFGroupShape group) {
                String nestedGroup = groupKey == null ? range(shape) : groupKey;
                Rectangle2D interior = group.getInteriorAnchor();
                if (!finite(interior) || interior.getWidth() <= 0 || interior.getHeight() <= 0) {
                    entry.unsupported = "グループの座標";
                    workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), "グループの内部座標を解釈できません。");
                    return entry;
                }
                transform.translate(anchor.getX(), anchor.getY());
                transform.scale(anchor.getWidth() / interior.getWidth(), anchor.getHeight() / interior.getHeight());
                transform.translate(-interior.getX(), -interior.getY());
                for (XSLFShape child : List.copyOf(group.getShapes())) {
                    Entry part = inspect(child, depth + 1, transform, nestedGroup);
                    if (part != null) entry.children.add(part);
                }
                if (entry.children.isEmpty()) return null;
            } else if (shape instanceof XSLFGraphicFrame frame) {
                entry.unsupported = shape instanceof XSLFObjectShape ? "埋め込みオブジェクト" : frame.hasChart() ? "グラフ" : frame.hasDiagram() ? "SmartArt" : "図表オブジェクト";
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), entry.unsupported + "は実行・再計算せず、位置に説明を残しました。");
            } else if (shape instanceof XSLFPictureShape picture) {
                workspace.imagePlacement();
                // isExternalLinkedPicture is checked before reading any picture relationship.
                if (picture.isExternalLinkedPicture() || picture.getPictureData() == null) {
                    entry.unsupported = "外部参照画像";
                    workspace.warning("EXTERNAL_IMAGE_SKIPPED", section, range(shape), "外部参照の画像は取得しません。");
                } else {
                    XSLFPictureData data = picture.getPictureData();
                    if (data.getPackagePart().getSize() > limits.maxImageBytes())
                        throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                    byte[] pictureBytes = workspace.retainImageBytes(data.getData());
                    if (pictureBytes.length > limits.maxImageBytes()) throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                    entry.picture = data;
                    entry.pictureType = data.getType();
                    entry.attachment = entry.pictureType != PictureType.PNG && entry.pictureType != PictureType.JPEG;
                    if (entry.attachment) workspace.warning("UNSUPPORTED_IMAGE_FORMAT", section, range(shape), "PNG・JPEG以外の画像は参考図に描画せず、個別の画像または原本添付として残します。");
                    else checkPicturePixels(pictureBytes);
                    String extension = entry.pictureType.extension.replace(".", "").toLowerCase(Locale.ROOT);
                    if (!extension.matches("[a-z0-9]{1,8}")) extension = "bin";
                    java.awt.Insets clipping = picture.getClipping();
                    EmbeddedImagePreprocessor.Crop crop = clipping == null ? EmbeddedImagePreprocessor.Crop.none()
                            : new EmbeddedImagePreprocessor.Crop(clipping.left / 100000d, clipping.top / 100000d,
                                    clipping.right / 100000d, clipping.bottom / 100000d, true);
                    boolean rectangular = EmbeddedImagePreprocessor.rectangularVisibility(
                            picture.getXmlObject().getDomNode());
                    entry.croppedPicture = crop.present() || !rectangular;
                    AffineTransform local = new AffineTransform(transform);
                    local.translate(anchor.getX(), anchor.getY());
                    String contentType = Objects.requireNonNullElse(entry.pictureType.contentType,
                            "application/octet-stream");
                    entry.visiblePicture = rectangular
                            ? EmbeddedImagePreprocessor.prepare(pictureBytes, extension, contentType,
                                    crop, local, anchor.getWidth(), anchor.getHeight(), limits)
                            : new EmbeddedImagePreprocessor.Prepared(pictureBytes, extension, contentType,
                                    false, false, "画像の非矩形マスク・透明化を安全に再現できません。");
                    entry.visiblePicture = entry.visiblePicture.withBytes(
                            workspace.retainImageBytes(entry.visiblePicture.bytes()));
                    if (entry.visiblePicture.skipReason() != null)
                        workspace.warning("IMAGE_VISIBLE_AREA_UNAVAILABLE", section, range(shape),
                                entry.visiblePicture.skipReason() + "元画像を保存しました。");
                    Element properties = ownProperties(shape);
                    if (properties != null) {
                        String description = properties.getAttribute("descr");
                        if (description.isBlank()) description = properties.getAttribute("title");
                        count(description.length()); entry.pictureDescription = description;
                    }
                }
            } else if (shape instanceof XSLFTextShape text) {
                entry.text = PresentationText.read(text, this::count);
                entry.normalText = depth == 0 && (shape instanceof XSLFTextBox || textPlaceholder(shape));
            } else if (!(shape instanceof XSLFConnectorShape) && !(shape instanceof XSLFSimpleShape)) {
                entry.unsupported = "未対応の図形";
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), "未対応の図形は描画せず説明を残しました。");
            }
            if (shape instanceof XSLFSimpleShape simple) {
                XSLFHyperlink link = simple.getHyperlink();
                if (link != null && Markdown.safeLink(link.getAddress())) entry.shapeLink = link.getAddress();
            }
            return entry;
        }

        private String table(XSLFTable table, Entry entry) {
            if (table.getNumberOfRows() == 0 || table.getNumberOfColumns() == 0) return "";
            StringBuilder out = new StringBuilder(), plain = new StringBuilder(); boolean merged = false;
            var properties = table.getCTTable().getTblPr();
            boolean header = properties != null && properties.isSetFirstRow() && properties.getFirstRow();
            if (!header) out.append('|').append("  |".repeat(table.getNumberOfColumns())).append('\n')
                    .append('|').append(" --- |".repeat(table.getNumberOfColumns())).append('\n');
            for (int r = 0; r < table.getNumberOfRows(); r++) {
                out.append('|');
                for (int c = 0; c < table.getNumberOfColumns(); c++) {
                    XSLFTableCell cell = table.getCell(r, c);
                    PresentationText.Content content = PresentationText.read(cell, this::count);
                    boolean covered = cell.isMerged();
                    // POI can paint stale text stored in covered cells; keep the preview consistent with Markdown.
                    if (covered) cell.clearText();
                    merged |= covered || cell.getGridSpan() > 1 || cell.getRowSpan() > 1;
                    if (!covered && !content.plain().isBlank()) {
                        if (!plain.isEmpty()) plain.append('\n');
                        plain.append(content.plain());
                    }
                    out.append(' ').append(covered ? "" : content.markdown().replace("  \n", "<br>").replace("\n", "<br>")).append(" |");
                }
                out.append('\n');
                if (r == 0 && header) out.append('|').append(" --- |".repeat(table.getNumberOfColumns())).append('\n');
            }
            if (merged) workspace.warning("TABLE_MERGE_FLATTENED", section, range(table), "Markdown表では結合の開始セルだけに内容を残します。");
            entry.text = new PresentationText.Content(plain.toString(), "", List.of());
            return out.toString();
        }

        private void emit(Entry first) throws IOException {
            String type;
            if (first.table != null) { append(first.table + "\n"); type = "table"; }
            else if (first.unsupported != null) {
                append("[" + Markdown.escape(first.unsupported + "：変換対象外、"
                        + DrawingAltText.geometry("スライド左上", first.bounds)) + "]\n\n"); type = "placeholder";
            } else if (first.normalText) {
                if (first.text.markdown().isBlank()) return;
                append(first.text.markdown() + "\n\n"); type = "paragraph";
            } else return;
            workspace.block(Map.of("type", type, "section", section, "range", range(first.shape)));
        }

        private void emitDiagram(XSLFSlide slide, List<Entry> visible, List<Entry> nodes, List<PresentationConnections.Edge> edges,
                                 int ordinal, int componentOrdinal, Rectangle2D slideBounds) throws IOException {
            Map<String, Entry> byId = new HashMap<>();
            Set<String> endpoints = new HashSet<>();
            for (var edge : edges) {
                if (edge.startId() != null) endpoints.add(edge.startId());
                if (edge.endId() != null) endpoints.add(edge.endId());
            }
            for (Entry node : nodes) byId.put(range(node.shape), node);
            List<Map<String, Object>> nodeMetadata = new ArrayList<>();
            boolean itemsStarted = false;
            for (Entry node : nodes) {
                String id = range(node.shape), plain = nodeText(node);
                Map<String, Object> metadata = PresentationImageMetadata.of(typeName(node), plain, node.bounds);
                String position = PresentationSpatialMetadata.positionOnSlide(node.bounds, slideBounds);
                metadata.put("positionOnSlide", position);
                metadata.put("id", id); nodeMetadata.add(metadata);
                if (!plain.isBlank() || endpoints.contains(id)) {
                    if (!itemsStarted) { append("図中の項目:\n\n"); itemsStarted = true; }
                    String content = node.picture != null || node.table != null ? Markdown.escape(plain) : node.text.markdown();
                    if (content.isBlank()) content = "文字なし";
                    append("- " + id + "（" + Markdown.escape(typeName(node)) + "、"
                            + PresentationSpatialMetadata.japanesePosition(position) + "）："
                            + content.replace("  \n", "<br>").replace("\n", "<br>") + "\n");
                }
                emitEmbeddedImage(node, slideBounds);
                if (node.shapeLink != null && !node.text.markdown().contains(node.shapeLink))
                    append("\n" + Markdown.link(Markdown.escape(plain.isBlank() ? "リンク" : DrawingAltText.singleLine(plain)), node.shapeLink) + "\n\n");
            }
            if (itemsStarted) append("\n");
            List<Entry> connectors = visible.stream()
                    .filter(entry -> entry.unsupported == null && entry.shape instanceof XSLFConnectorShape).toList();
            List<Map<String, Object>> edgeMetadata = new ArrayList<>();
            if (!edges.isEmpty()) {
                append("接続関係:\n\n");
                for (int i = 0; i < edges.size(); i++) {
                    var edge = edges.get(i);
                    Entry connectorEntry = i < connectors.size() ? connectors.get(i) : null;
                    String position = PresentationSpatialMetadata.positionOnSlide(
                            connectorEntry == null ? null : connectorEntry.bounds, slideBounds);
                    Map<String, Object> metadata = new LinkedHashMap<>(edge.metadata());
                    metadata.put("positionOnSlide", position);
                    PresentationSpatialMetadata.VisualArrow visualArrow = connectorEntry == null
                            ? new PresentationSpatialMetadata.VisualArrow(false, null)
                            : PresentationSpatialMetadata.visualArrow((XSLFConnectorShape) connectorEntry.shape, edge,
                                    shapeTransform(connectorEntry.shape, connectorEntry.parentTransform,
                                            anchor(connectorEntry.shape)));
                    if (visualArrow.applicable()) metadata.put("arrowheadPointsToward", visualArrow.bearing());
                    edgeMetadata.add(metadata);
                    String start = endpointLabel(edge.startId(), byId), end = endpointLabel(edge.endId(), byId);
                    String relation;
                    if (!edge.status().equals("resolved")) {
                        relation = "接続関係不明（" + PresentationConnections.reasonExplanation(edge.reason()) + "）";
                        workspace.warning("DIAGRAM_CONNECTION_UNRESOLVED", section, edge.id(),
                                "図形の接続関係を確定できません。" + PresentationConnections.reasonExplanation(edge.reason())
                                        + "。保存情報と線端の境界接触のどちらでも確定できません。");
                    } else relation = switch (edge.direction()) {
                        case "start-to-end" -> start + " → " + end;
                        case "end-to-start" -> end + " → " + start;
                        case "bidirectional" -> start + " ↔ " + end;
                        default -> start + " — " + end + "（向きなし）";
                    };
                    append("- " + edge.id() + "：" + relation + "。"
                            + PresentationSpatialMetadata.japanesePosition(position)
                            + (visualArrow.applicable() ? "。" + PresentationSpatialMetadata.japaneseBearing(visualArrow.bearing()) : "")
                            + "。\n");
                }
                append("\n");
            }
            boolean hasNonPictureLayer = visible.stream().anyMatch(entry -> entry.unsupported == null
                    && entry.picture == null);
            List<PresentationRenderer.Layer> layers = visible.stream()
                    .filter(entry -> entry.unsupported == null && !entry.attachment
                            && (entry.picture == null || hasNonPictureLayer && !entry.croppedPicture))
                    .sorted(Comparator.comparingInt(entry -> entry.order))
                    .map(entry -> new PresentationRenderer.Layer(entry.shape, entry.parentTransform)).toList();
            Map<String, Object> block = new LinkedHashMap<>();
            String blockRange = "slide-" + ordinal + "-diagram-" + componentOrdinal;
            block.put("type", "diagram"); block.put("section", section); block.put("range", blockRange);
            block.put("nodes", nodeMetadata); block.put("edges", edgeMetadata);
            if (!layers.isEmpty()) {
                var background = PresentationRenderer.background(slide);
                if (background.approximated()) workspace.warning("UNSUPPORTED_SLIDE_BACKGROUND", section, "slide-" + ordinal,
                        "参考画像の背景は単色のみ対応しています。画像・グラデーションなどの背景は取得せず白色に置き換えました。");
                String path = workspace.addAsset("diagram", PresentationRenderer.png(layers, slideBounds, background.color(), limits), "png", "image/png");
                Map<String, Object> metadata = PresentationImageMetadata.of(edges.isEmpty() ? "図形" : "接続図", "", slideBounds);
                append("![" + PresentationImageMetadata.imageAlt(metadata) + "](" + path + ")\n\n");
                block.put("path", path); block.put("metadata", metadata);
            }
            workspace.block(block);
        }

        private void emitEmbeddedImage(Entry entry, Rectangle2D slideBounds) {
            if (entry.picture == null || entry.visiblePicture == null || entry.embeddedImageEmitted) return;
            Map<String, Object> metadata = PresentationImageMetadata.of(typeName(entry), nodeText(entry), entry.bounds);
            metadata.put("positionOnSlide", PresentationSpatialMetadata.positionOnSlide(entry.bounds, slideBounds));
            append("\n" + workspace.embeddedImage(entry.visiblePicture.bytes(), entry.visiblePicture.extension(),
                    entry.visiblePicture.contentType(), metadata, section, range(entry.shape),
                    entry.visiblePicture.ocrEligible()) + "\n\n");
            entry.embeddedImageEmitted = true;
        }

        private List<PresentationConnections.Edge> inferMissingConnections(
                List<Entry> visible, List<PresentationConnections.Edge> edges) {
            Map<String, Rectangle2D> targets = new LinkedHashMap<>();
            Map<String, Entry> connectors = new HashMap<>();
            for (Entry entry : visible) if (entry.unsupported == null) {
                if (entry.shape instanceof XSLFConnectorShape) connectors.put(range(entry.shape), entry);
                else {
                    Rectangle2D entryAnchor = anchor(entry.shape);
                    if (entryAnchor != null && axisAligned(shapeTransform(entry.shape, entry.parentTransform, entryAnchor)))
                        targets.put(range(entry.shape), entry.bounds);
                }
            }
            List<PresentationConnections.Edge> resolved = new ArrayList<>();
            for (var edge : edges) {
                Entry entry = connectors.get(edge.id());
                if (entry == null || !"MISSING_ENDPOINT".equals(edge.reason())) {
                    resolved.add(edge); continue;
                }
                Rectangle2D connectorAnchor = anchor(entry.shape);
                AffineTransform transform = connectorAnchor == null ? null
                        : shapeTransform(entry.shape, entry.parentTransform, connectorAnchor);
                Point2D startPoint = transform == null ? null : transform.transform(
                        new Point2D.Double(connectorAnchor.getMinX(), connectorAnchor.getMinY()), null);
                Point2D endPoint = transform == null ? null : transform.transform(
                        new Point2D.Double(connectorAnchor.getMaxX(), connectorAnchor.getMaxY()), null);
                String startId = edge.startId() == null
                        ? ConnectorGeometry.uniqueBoundaryContact(startPoint, targets) : edge.startId();
                String endId = edge.endId() == null
                        ? ConnectorGeometry.uniqueBoundaryContact(endPoint, targets) : edge.endId();
                String reason = startId == null || endId == null ? "MISSING_ENDPOINT"
                        : "unknown".equals(edge.direction()) ? "UNKNOWN_ARROWHEAD" : "";
                resolved.add(new PresentationConnections.Edge(edge.id(), startId, endId,
                        edge.startArrow(), edge.endArrow(), edge.direction(),
                        reason.isEmpty() ? "resolved" : "unresolved", reason));
            }
            return List.copyOf(resolved);
        }

        private List<DiagramComponent> components(List<Entry> visible, List<Entry> nodes,
                                                   List<PresentationConnections.Edge> edges) {
            Map<String, Entry> candidates = new LinkedHashMap<>();
            for (Entry node : nodes) candidates.put(range(node.shape), node);
            for (Entry entry : visible) if (entry.unsupported == null && entry.shape instanceof XSLFConnectorShape)
                candidates.put(range(entry.shape), entry);
            Map<String, String> parents = new LinkedHashMap<>();
            Map<String, String> groupRoots = new HashMap<>();
            for (Entry entry : candidates.values()) {
                String id = range(entry.shape); parents.put(id, id);
                if (entry.groupKey != null) {
                    String first = groupRoots.putIfAbsent(entry.groupKey, id);
                    if (first != null) union(parents, id, first);
                }
            }
            for (var edge : edges) {
                if (!parents.containsKey(edge.id())) continue;
                if (edge.startId() != null && parents.containsKey(edge.startId())) union(parents, edge.id(), edge.startId());
                if (edge.endId() != null && parents.containsKey(edge.endId())) union(parents, edge.id(), edge.endId());
            }
            Map<String, List<Entry>> grouped = new LinkedHashMap<>();
            for (Entry entry : candidates.values()) grouped.computeIfAbsent(root(parents, range(entry.shape)), ignored -> new ArrayList<>()).add(entry);
            List<DiagramComponent> result = new ArrayList<>();
            List<Entry> unconnected = new ArrayList<>();
            for (List<Entry> entries : grouped.values()) {
                entries.sort(Comparator.comparingInt(entry -> entry.order));
                if (entries.size() == 1) unconnected.add(entries.getFirst());
                else result.add(component(entries, nodes, edges));
            }
            if (!unconnected.isEmpty()) result.add(component(unconnected, nodes, edges));
            result.sort(Comparator.comparingInt(component -> component.entries().getFirst().order));
            return result;
        }

        private DiagramComponent component(List<Entry> entries, List<Entry> nodes,
                                           List<PresentationConnections.Edge> edges) {
            List<Entry> ordered = entries.stream().sorted(Comparator.comparingInt(entry -> entry.order)).toList();
            Set<String> ids = new HashSet<>();
            for (Entry entry : ordered) ids.add(range(entry.shape));
            List<Entry> componentNodes = nodes.stream().filter(node -> ids.contains(range(node.shape)))
                    .sorted(readingOrder()).toList();
            List<PresentationConnections.Edge> componentEdges = edges.stream().filter(edge -> ids.contains(edge.id())).toList();
            return new DiagramComponent(ordered, componentNodes, componentEdges);
        }

        private String root(Map<String, String> parents, String id) {
            String parent = parents.get(id);
            if (parent == null || parent.equals(id)) return id;
            String found = root(parents, parent); parents.put(id, found); return found;
        }

        private void union(Map<String, String> parents, String left, String right) {
            String leftRoot = root(parents, left), rightRoot = root(parents, right);
            if (!leftRoot.equals(rightRoot)) parents.put(rightRoot, leftRoot);
        }

        private String endpointLabel(String id, Map<String, Entry> nodes) {
            Entry node = nodes.get(id);
            String text = node == null ? "" : DrawingAltText.singleLine(nodeText(node));
            return id + (text.isBlank() ? "（文字なし）" : "「" + Markdown.escape(text) + "」");
        }

        private void append(String value) {
            long bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > limits.maxMarkdownBytes() - markdownBytes) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
            markdownBytes += bytes; markdown.append(value);
        }

        private void checkPicturePixels(byte[] data) throws IOException {
            try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new ConversionException(422, "INVALID_IMAGE", "埋め込み画像を読み取れません。");
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > limits.maxImagePixels())
                        throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "埋め込み画像の画素数が上限を超えました。");
                } finally { reader.dispose(); }
            }
        }

        private void stripExternalBlips(XSLFShape shape) {
            // Removing an external r:link avoids allowing a renderer to choose it over an embedded image.
            // Standalone external pictures are reported using their saved relationship, before any I/O.
            if (shape instanceof XSLFPictureShape || shape instanceof XSLFGroupShape) return;
            NodeList nodes = shape.getXmlObject().getDomNode().getChildNodes();
            stripBlips(nodes, shape);
        }

        private void stripBlips(NodeList nodes, XSLFShape shape) {
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element element && "blip".equals(element.getLocalName())) {
                    String namespace = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
                    String link = element.getAttributeNS(namespace, "link");
                    if (!link.isBlank()) {
                        element.removeAttributeNS(namespace, "link");
                        workspace.warning("EXTERNAL_IMAGE_SKIPPED", section, range(shape), "外部参照の塗りつぶし画像は取得しません。");
                    }
                }
                stripBlips(node.getChildNodes(), shape);
            }
        }
    }

    private static final class Entry {
        final XSLFShape shape; final Rectangle2D bounds; final int order;
        final AffineTransform parentTransform;
        final String groupKey;
        final List<Entry> children = new ArrayList<>();
        PresentationText.Content text = PresentationText.Content.EMPTY;
        boolean normalText, attachment;
        boolean croppedPicture;
        String table, unsupported, shapeLink, pictureDescription = "";
        XSLFPictureData picture; PictureType pictureType;
        EmbeddedImagePreprocessor.Prepared visiblePicture;
        boolean embeddedImageEmitted;
        Entry(XSLFShape shape, Rectangle2D bounds, int order, AffineTransform parentTransform, String groupKey) {
            this.shape = shape; this.bounds = bounds; this.order = order;
            this.parentTransform = parentTransform;
            this.groupKey = groupKey;
        }
    }
    private record DiagramComponent(List<Entry> entries, List<Entry> nodes,
                                    List<PresentationConnections.Edge> edges) { }
    private static void leaves(Entry entry, List<Entry> target) {
        if (entry.children.isEmpty()) target.add(entry); else entry.children.forEach(child -> leaves(child, target));
    }
    private static boolean drawing(Entry entry) {
        return entry.unsupported == null && entry.table == null && !entry.normalText;
    }
    private static String nodeText(Entry entry) {
        return entry.picture != null ? entry.pictureDescription : entry.text.plain();
    }
    private static Comparator<Entry> readingOrder() {
        return Comparator.comparingDouble((Entry entry) -> entry.bounds.getY()).thenComparingDouble(entry -> entry.bounds.getX()).thenComparingInt(entry -> entry.order);
    }
    private static String range(XSLFShape shape) { return "shape-" + shape.getShapeId(); }
    private static Element ownProperties(XSLFShape shape) {
        Node node = shape.getXmlObject().getDomNode();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            if (child.getLocalName() != null && child.getLocalName().startsWith("nv")) return descendant(child, "cNvPr");
        return null;
    }
    private static Element descendant(Node node, String name) {
        if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Element found = descendant(child, name); if (found != null) return found;
        }
        return null;
    }
    private static boolean hidden(XSLFShape shape) {
        Element properties = ownProperties(shape);
        return properties != null && Set.of("1", "true").contains(properties.getAttribute("hidden"));
    }
    private static boolean excludedPlaceholder(XSLFShape shape) {
        Placeholder value = shape.getPlaceholder();
        return value == Placeholder.DATETIME || value == Placeholder.SLIDE_NUMBER || value == Placeholder.FOOTER || value == Placeholder.HEADER;
    }
    private static boolean isTitle(XSLFShape shape) {
        Placeholder value = shape.getPlaceholder();
        return value == Placeholder.TITLE || value == Placeholder.CENTERED_TITLE || value == Placeholder.VERTICAL_TEXT_TITLE;
    }
    private static boolean textPlaceholder(XSLFShape shape) {
        Placeholder value = shape.getPlaceholder();
        return isTitle(shape) || value == Placeholder.BODY || value == Placeholder.SUBTITLE || value == Placeholder.CONTENT || value == Placeholder.VERTICAL_TEXT_BODY;
    }
    private static String typeName(Entry entry) {
        if (entry.picture != null) return "画像";
        if (entry.table != null) return "表";
        if (entry.shape instanceof XSLFConnectorShape) return "接続線";
        if (entry.shape instanceof XSLFTextBox) return "テキストボックス";
        if (entry.shape instanceof XSLFFreeformShape) return DrawingAltText.typeName("freeform");
        if (entry.shape instanceof XSLFSimpleShape simple) {
            var type = simple.getShapeType();
            return DrawingAltText.typeName(type == null ? storedGeometry(simple) : type.getOoxmlName());
        }
        return "図形";
    }
    private static String storedGeometry(XSLFSimpleShape shape) {
        Node xml = shape.getXmlObject().getDomNode();
        for (Node properties = xml.getFirstChild(); properties != null; properties = properties.getNextSibling()) {
            if (!"spPr".equals(properties.getLocalName())) continue;
            for (Node geometry = properties.getFirstChild(); geometry != null; geometry = geometry.getNextSibling()) {
                if ("custGeom".equals(geometry.getLocalName())) return "custom";
                if ("prstGeom".equals(geometry.getLocalName()) && geometry instanceof Element preset)
                    return preset.getAttribute("prst");
            }
        }
        return "";
    }
    private static Rectangle2D anchor(XSLFShape shape) {
        if (shape instanceof XSLFSimpleShape simple) return simple.getAnchor();
        if (shape instanceof XSLFGroupShape group) return group.getAnchor();
        if (shape instanceof XSLFGraphicFrame frame) return frame.getAnchor();
        return null;
    }
    private static AffineTransform shapeTransform(XSLFShape shape, AffineTransform parent, Rectangle2D anchor) {
        AffineTransform transform = new AffineTransform(parent);
        transform.translate(anchor.getCenterX(), anchor.getCenterY());
        transform.rotate(Math.toRadians(rotation(shape)));
        transform.scale(flipH(shape) ? -1 : 1, flipV(shape) ? -1 : 1);
        transform.translate(-anchor.getCenterX(), -anchor.getCenterY());
        return transform;
    }
    private static boolean axisAligned(AffineTransform transform) {
        return Math.abs(transform.getShearX()) < 1e-9 && Math.abs(transform.getShearY()) < 1e-9;
    }
    private static boolean finite(Rectangle2D r) { return r != null && Double.isFinite(r.getX()) && Double.isFinite(r.getY()) && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight()) && r.getWidth() >= 0 && r.getHeight() >= 0; }
    private static double rotation(XSLFShape shape) {
        if (shape instanceof XSLFSimpleShape simple) return simple.getRotation();
        if (shape instanceof XSLFGroupShape group) return group.getRotation();
        if (shape instanceof XSLFGraphicFrame frame) return frame.getRotation();
        return 0;
    }
    private static boolean flipH(XSLFShape shape) { return shape instanceof XSLFSimpleShape s ? s.getFlipHorizontal() : shape instanceof XSLFGroupShape g && g.getFlipHorizontal(); }
    private static boolean flipV(XSLFShape shape) { return shape instanceof XSLFSimpleShape s ? s.getFlipVertical() : shape instanceof XSLFGroupShape g && g.getFlipVertical(); }
}
