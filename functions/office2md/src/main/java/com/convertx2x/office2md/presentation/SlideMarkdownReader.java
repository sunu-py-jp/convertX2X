package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import com.convertx2x.office2md.drawing.ConnectorGeometry;
import com.convertx2x.office2md.drawing.DrawingAltText;
import com.convertx2x.office2md.images.EmbeddedImagePreprocessor;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.PictureShape;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.SimpleShape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RAG-oriented slide text, saved connections and previews, shared by every presentation format.
 * It reads POI's format-neutral slide model; everything a file format stores in its own way is asked
 * through {@link SlideDialect}. Never follows external content.
 */
final class SlideMarkdownReader {
    private final ConversionLimits limits;
    private final SlideDialect dialect;

    SlideMarkdownReader(ConversionLimits limits, SlideDialect dialect) {
        this.limits = Objects.requireNonNull(limits);
        this.dialect = Objects.requireNonNull(dialect);
    }

    static void checkInput(byte[] input, ConversionLimits limits) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "入力ファイルが空です。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
    }

    /** The base name recorded in the report; {@code fallback} is used when the request names no file. */
    static String safeFilename(String filename, String fallback) {
        String name = Objects.toString(filename, fallback).replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return name.isBlank() ? fallback : name.substring(0, Math.min(255, name.length()));
    }

    /** Releases the workspace of a failed conversion and gives the stable client error for the failure. */
    static ConversionException failed(Throwable failure, ConversionWorkspace workspace) {
        try { workspace.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        if (failure instanceof ConversionException conversion) return conversion;
        if (failure instanceof Error error) throw error;
        if (failure instanceof EncryptedDocumentException)
            return new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化されたファイルは変換できません。", failure);
        return new ConversionException(422, "INVALID_DOCUMENT", "PowerPointファイルを読み取り、変換できませんでした。", failure);
    }

    /** Writes document.md and the report blocks of every visible slide into the workspace. */
    void read(SlideShow<?, ?> presentation, ConversionWorkspace workspace) throws IOException {
        new Reader(workspace).read(presentation);
    }

    private final class Reader {
        private final ConversionWorkspace workspace;
        private final StringBuilder markdown = new StringBuilder();
        private long readItems, tableCells, markdownBytes;
        private String section;
        private int sequence;

        Reader(ConversionWorkspace workspace) { this.workspace = workspace; }

        void read(SlideShow<?, ?> presentation) throws IOException {
            if (ConversionLimits.exceeds(presentation.getSlides().size(), limits.maxSections()))
                throw ConversionWorkspace.limit("SECTION_LIMIT", "スライド数が上限を超えました。");
            int ordinal = 0;
            for (Slide<?, ?> slide : presentation.getSlides()) {
                ordinal++;
                if (dialect.hidden(slide)) continue;
                section = "スライド" + ordinal;
                workspace.sectionIncluded();
                sequence = 0;
                List<Entry> entries = new ArrayList<>();
                for (Shape<?, ?> shape : List.copyOf(slide.getShapes())) {
                    Entry entry = inspect(shape, 0, new AffineTransform(), null);
                    if (entry != null) leaves(entry, entries);
                }
                List<Entry> visible = List.copyOf(entries);
                List<PresentationConnections.Edge> edges = dialect.connections(visible.stream()
                        .filter(entry -> entry.unsupported == null).<Shape<?, ?>>map(entry -> entry.shape).toList());
                edges = inferMissingConnections(visible, edges);
                Set<String> endpoints = new HashSet<>();
                for (var edge : edges) {
                    if (edge.startId() != null) endpoints.add(edge.startId());
                    if (edge.endId() != null) endpoints.add(edge.endId());
                }
                List<Entry> nodes = visible.stream().filter(entry -> entry.unsupported == null
                        && !(dialect.connector(entry.shape))
                        && (drawing(entry) || endpoints.contains(range(entry.shape)) || entry.groupKey != null))
                        .sorted(readingOrder()).toList();
                List<DiagramComponent> diagramComponents = components(visible, nodes, edges);
                for (DiagramComponent component : diagramComponents) if (component.composite())
                    for (Entry entry : component.entries()) if (compositePicture(entry)) entry.compositeImage = true;
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
                if (!diagramComponents.isEmpty()) {
                    int componentOrdinal = 0;
                    for (DiagramComponent component : diagramComponents)
                        emitDiagram(slide, component.entries(), component.nodes(), component.edges(), ordinal,
                                ++componentOrdinal, slideBounds, component.composite());
                }
            }
            workspace.write("document.md", markdown.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void count(int amount) {
            readItems += amount;
            if (ConversionLimits.exceeds(readItems, limits.maxReadItems())) throw ConversionWorkspace.limit("READ_ITEMS_LIMIT", "文字・読み取り要素数が上限を超えました。");
        }

        private Entry inspect(Shape<?, ?> shape, int depth, AffineTransform parent, String groupKey) throws IOException {
            workspace.shapeVisited(depth);
            if (dialect.hidden(shape) || excludedPlaceholder(shape)) return null;
            Rectangle2D anchor = anchor(shape);
            if (anchor == null) {
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), "位置を解釈できない図形を除外しました。");
                return null;
            }
            if (!finite(anchor)) throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "図形の座標が有効な範囲ではありません。");
            AffineTransform transform = shapeTransform(shape, parent, anchor);
            Entry entry = new Entry(shape, transform.createTransformedShape(anchor).getBounds2D(), sequence++,
                    new AffineTransform(parent), groupKey);
            dialect.sanitize(shape, () -> workspace.warning("EXTERNAL_IMAGE_SKIPPED", section, range(shape),
                    "外部参照の塗りつぶし画像は取得しません。"));
            if (shape instanceof TableShape<?, ?> table) {
                long cells = (long) table.getNumberOfRows() * table.getNumberOfColumns();
                tableCells += cells;
                limits.checkTableCells(tableCells);
                entry.table = table(table, entry);
            } else if (shape instanceof GroupShape<?, ?> group) {
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
                for (Shape<?, ?> child : List.copyOf(group.getShapes())) {
                    Entry part = inspect(child, depth + 1, transform, nestedGroup);
                    if (part != null) entry.children.add(part);
                }
                if (entry.children.isEmpty()) return null;
            } else if (dialect.unsupportedFrame(shape) != null) {
                entry.unsupported = dialect.unsupportedFrame(shape);
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), entry.unsupported + "は実行・再計算せず、位置に説明を残しました。");
            } else if (shape instanceof PictureShape<?, ?> picture) {
                workspace.imagePlacement();
                // isExternalLinkedPicture is checked before reading any picture relationship.
                if (dialect.externalPicture(picture) || picture.getPictureData() == null) {
                    entry.unsupported = "外部参照画像";
                    workspace.warning("EXTERNAL_IMAGE_SKIPPED", section, range(shape), "外部参照の画像は取得しません。");
                } else {
                    PictureData data = picture.getPictureData();
                    if (dialect.storedPictureBytes(data) > limits.maxImageBytes())
                        throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                    byte[] pictureBytes = workspace.retainImageBytes(data.getData());
                    if (pictureBytes.length > limits.maxImageBytes()) throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                    entry.picture = data;
                    entry.pictureType = data.getType();
                    if (entry.pictureType == PictureType.PNG || entry.pictureType == PictureType.JPEG)
                        checkPicturePixels(pictureBytes);
                    String extension = entry.pictureType.extension.replace(".", "").toLowerCase(Locale.ROOT);
                    if (!extension.matches("[a-z0-9]{1,8}")) extension = "bin";
                    java.awt.Insets clipping = picture.getClipping();
                    EmbeddedImagePreprocessor.Crop crop = clipping == null ? EmbeddedImagePreprocessor.Crop.none()
                            : new EmbeddedImagePreprocessor.Crop(clipping.left / 100000d, clipping.top / 100000d,
                                    clipping.right / 100000d, clipping.bottom / 100000d, true);
                    boolean rectangular = dialect.rectangularPicture(picture);
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
                    // The preparation step safely normalizes supported raster/vector formats.
                    // Eligibility follows those decoded pixels, not the original file extension.
                    entry.attachment = entry.visiblePicture.skipReason() != null
                            || !Set.of("image/png", "image/jpeg").contains(entry.visiblePicture.contentType());
                    if (!entry.attachment) checkPicturePixels(entry.visiblePicture.bytes());
                    if (entry.visiblePicture.skipReason() != null)
                        workspace.warning("IMAGE_VISIBLE_AREA_UNAVAILABLE", section, range(shape),
                                entry.visiblePicture.skipReason() + "元画像を保存しました。");
                    String description = dialect.description(shape);
                    if (description != null) {
                        count(description.length()); entry.pictureDescription = description;
                    }
                }
            } else if (shape instanceof TextShape<?, ?> text) {
                entry.text = dialect.text(text, this::count);
                entry.normalText = depth == 0 && (dialect.textBox(shape) || textPlaceholder(shape));
            } else if (!(dialect.connector(shape)) && !(shape instanceof SimpleShape<?, ?>)) {
                entry.unsupported = "未対応の図形";
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), "未対応の図形は描画せず説明を残しました。");
            }
            if (shape instanceof SimpleShape<?, ?> simple) {
                var link = simple.getHyperlink();
                if (link != null && Markdown.safeLink(link.getAddress())) entry.shapeLink = link.getAddress();
            }
            return entry;
        }

        private String table(TableShape<?, ?> table, Entry entry) {
            if (table.getNumberOfRows() == 0 || table.getNumberOfColumns() == 0) return "";
            StringBuilder out = new StringBuilder(), plain = new StringBuilder(); boolean merged = false;
            boolean header = dialect.headerRow(table);
            if (!header) out.append('|').append("  |".repeat(table.getNumberOfColumns())).append('\n')
                    .append('|').append(" --- |".repeat(table.getNumberOfColumns())).append('\n');
            for (int r = 0; r < table.getNumberOfRows(); r++) {
                out.append('|');
                for (int c = 0; c < table.getNumberOfColumns(); c++) {
                    TableCell<?, ?> cell = table.getCell(r, c);
                    // A grid position without a stored cell is covered by a merged neighbour.
                    PresentationText.Content content = cell == null ? PresentationText.Content.EMPTY
                            : dialect.text(cell, this::count);
                    boolean covered = cell == null || cell.isMerged();
                    // POI can paint stale text stored in covered cells; keep the preview consistent with Markdown.
                    if (covered && cell != null) dialect.clearText(cell);
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

        private void emitDiagram(Slide<?, ?> slide, List<Entry> visible, List<Entry> nodes, List<PresentationConnections.Edge> edges,
                                 int ordinal, int componentOrdinal, Rectangle2D slideBounds, boolean composite) throws IOException {
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
                    .filter(entry -> entry.unsupported == null && dialect.connector(entry.shape)).toList();
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
                            : dialect.visualArrow(connectorEntry.shape, edge,
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
            List<Entry> rendered = visible.stream()
                    .filter(entry -> entry.unsupported == null && !entry.attachment
                            && (entry.picture == null || composite && compositePicture(entry)))
                    .sorted(Comparator.comparingInt(entry -> entry.order)).toList();
            List<PresentationRenderer.Layer> layers = rendered.stream()
                    .map(entry -> entry.picture == null
                            ? new PresentationRenderer.Layer(entry.shape, entry.parentTransform)
                            : PresentationRenderer.Layer.picture(entry.shape, entry.visiblePicture.bytes(), entry.bounds)).toList();
            Map<String, Object> block = new LinkedHashMap<>();
            String blockRange = "slide-" + ordinal + "-diagram-" + componentOrdinal;
            block.put("type", "diagram"); block.put("section", section); block.put("range", blockRange);
            block.put("nodes", nodeMetadata); block.put("edges", edgeMetadata);
            if (!layers.isEmpty() && composite) {
                Rectangle2D bounds = unionBounds(rendered);
                Map<String, Object> metadata = PresentationImageMetadata.of("図", "", bounds);
                List<String> sourceIds = rendered.stream().map(entry -> range(entry.shape)).toList();
                metadata.put("sourceIds", sourceIds);
                metadata.put("positionOnSlide", PresentationSpatialMetadata.positionOnSlide(bounds, slideBounds));
                List<ConversionWorkspace.CompositeImageSource> sources = new ArrayList<>();
                for (Entry entry : rendered) if (entry.picture != null) {
                    Map<String, Object> source = PresentationImageMetadata.of("画像", nodeText(entry), entry.bounds);
                    source.put("id", range(entry.shape));
                    source.put("positionOnSlide", PresentationSpatialMetadata.positionOnSlide(entry.bounds, slideBounds));
                    sources.add(new ConversionWorkspace.CompositeImageSource(entry.visiblePicture.bytes(),
                            entry.visiblePicture.extension(), entry.visiblePicture.contentType(), source,
                            entry.visiblePicture.ocrEligible()));
                }
                append(workspace.compositeImage(PresentationRenderer.png(layers, bounds, limits),
                        metadata, sources, section, blockRange) + "\n\n");
                block.put("composite", true); block.put("sourceIds", sourceIds); block.put("metadata", metadata);
            } else if (!layers.isEmpty()) {
                var background = dialect.background(slide);
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
            if (entry.picture == null || entry.visiblePicture == null || entry.embeddedImageEmitted || entry.compositeImage) return;
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
                if (dialect.connector(entry.shape)) connectors.put(range(entry.shape), entry);
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
            for (Entry entry : visible) if (entry.unsupported == null && dialect.connector(entry.shape))
                candidates.put(range(entry.shape), entry);
            // Images and tables are visual bases. Their overlapping pictures, native drawings,
            // and textboxes share one preview while editable text/table content remains Markdown.
            // These overlap edges never become semantic graph relationships.
            List<String[]> overlaps = new ArrayList<>();
            for (Entry base : visible) if (compositeBase(base)) {
                for (Entry shape : visible) if (visualOverlaps(base, shape)) {
                    candidates.putIfAbsent(range(base.shape), base);
                    candidates.putIfAbsent(range(shape.shape), shape);
                    overlaps.add(new String[] { range(base.shape), range(shape.shape) });
                }
            }
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
            for (String[] pair : overlaps) union(parents, pair[0], pair[1]);
            Set<String> overlappingIds = new HashSet<>();
            for (String[] pair : overlaps) {
                overlappingIds.add(pair[0]); overlappingIds.add(pair[1]);
            }
            Map<String, List<Entry>> grouped = new LinkedHashMap<>();
            for (Entry entry : candidates.values()) grouped.computeIfAbsent(root(parents, range(entry.shape)), ignored -> new ArrayList<>()).add(entry);
            List<DiagramComponent> result = new ArrayList<>();
            List<Entry> unconnected = new ArrayList<>();
            for (List<Entry> entries : grouped.values()) {
                entries.sort(Comparator.comparingInt(entry -> entry.order));
                if (entries.size() == 1) unconnected.add(entries.getFirst());
                else result.add(component(entries, nodes, edges,
                        entries.stream().anyMatch(SlideMarkdownReader::compositePicture)
                                || entries.stream().anyMatch(entry -> entry.table != null
                                    && overlappingIds.contains(range(entry.shape)))));
            }
            if (!unconnected.isEmpty()) result.add(component(unconnected, nodes, edges, false));
            result.sort(Comparator.comparingInt(component -> component.entries().getFirst().order));
            return result;
        }

        private DiagramComponent component(List<Entry> entries, List<Entry> nodes,
                                           List<PresentationConnections.Edge> edges, boolean composite) {
            List<Entry> ordered = entries.stream().sorted(Comparator.comparingInt(entry -> entry.order)).toList();
            Set<String> ids = new HashSet<>();
            for (Entry entry : ordered) ids.add(range(entry.shape));
            List<Entry> componentNodes = nodes.stream().filter(node -> ids.contains(range(node.shape)))
                    .sorted(readingOrder()).toList();
            List<PresentationConnections.Edge> componentEdges = edges.stream().filter(edge -> ids.contains(edge.id())).toList();
            return new DiagramComponent(ordered, componentNodes, componentEdges, composite);
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
    }

    private static final class Entry {
        final Shape<?, ?> shape; final Rectangle2D bounds; final int order;
        final AffineTransform parentTransform;
        final String groupKey;
        final List<Entry> children = new ArrayList<>();
        PresentationText.Content text = PresentationText.Content.EMPTY;
        boolean normalText, attachment;
        boolean croppedPicture;
        String table, unsupported, shapeLink, pictureDescription = "";
        PictureData picture; PictureType pictureType;
        EmbeddedImagePreprocessor.Prepared visiblePicture;
        boolean embeddedImageEmitted, compositeImage;
        Entry(Shape<?, ?> shape, Rectangle2D bounds, int order, AffineTransform parentTransform, String groupKey) {
            this.shape = shape; this.bounds = bounds; this.order = order;
            this.parentTransform = parentTransform;
            this.groupKey = groupKey;
        }
    }
    private record DiagramComponent(List<Entry> entries, List<Entry> nodes,
                                    List<PresentationConnections.Edge> edges, boolean composite) { }
    private static boolean compositePicture(Entry entry) {
        return entry.picture != null && !entry.attachment && entry.visiblePicture != null
                && entry.visiblePicture.skipReason() == null && entry.bounds.getWidth() > 0 && entry.bounds.getHeight() > 0;
    }
    private static boolean compositeBase(Entry entry) {
        return entry.unsupported == null && (entry.table != null || compositePicture(entry));
    }
    private boolean visualOverlaps(Entry base, Entry shape) {
        if (base == shape || shape.unsupported != null) return false;
        Rectangle2D baseAnchor = anchor(base.shape), shapeAnchor = anchor(shape.shape);
        if (baseAnchor == null || shapeAnchor == null) return false;
        AffineTransform baseTransform = shapeTransform(base.shape, base.parentTransform, baseAnchor);
        AffineTransform targetTransform = shapeTransform(shape.shape, shape.parentTransform, shapeAnchor);
        if (compositeBase(shape))
            return PresentationOverlap.intersects(baseAnchor, baseTransform, shapeAnchor, targetTransform);
        // Unavailable picture geometry must remain a separate attachment, not a native POI draw.
        if (shape.picture != null || !(shape.shape instanceof SimpleShape<?, ?> simple)) return false;
        return PresentationOverlap.intersects(baseAnchor, baseTransform, simple, targetTransform);
    }
    private static Rectangle2D unionBounds(List<Entry> entries) {
        Rectangle2D bounds = null;
        for (Entry entry : entries) bounds = bounds == null ? (Rectangle2D) entry.bounds.clone() : bounds.createUnion(entry.bounds);
        return bounds;
    }
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
    private static String range(Shape<?, ?> shape) { return "shape-" + shape.getShapeId(); }
    private boolean excludedPlaceholder(Shape<?, ?> shape) {
        Placeholder value = dialect.placeholder(shape);
        return value == Placeholder.DATETIME || value == Placeholder.SLIDE_NUMBER || value == Placeholder.FOOTER || value == Placeholder.HEADER;
    }
    private boolean isTitle(Shape<?, ?> shape) {
        Placeholder value = dialect.placeholder(shape);
        return value == Placeholder.TITLE || value == Placeholder.CENTERED_TITLE || value == Placeholder.VERTICAL_TEXT_TITLE;
    }
    private boolean textPlaceholder(Shape<?, ?> shape) {
        Placeholder value = dialect.placeholder(shape);
        return isTitle(shape) || value == Placeholder.BODY || value == Placeholder.SUBTITLE || value == Placeholder.CONTENT || value == Placeholder.VERTICAL_TEXT_BODY;
    }
    private String typeName(Entry entry) {
        if (entry.picture != null) return "画像";
        if (entry.table != null) return "表";
        if (dialect.connector(entry.shape)) return "接続線";
        if (dialect.textBox(entry.shape)) return "テキストボックス";
        if (entry.shape instanceof SimpleShape<?, ?> simple) return DrawingAltText.typeName(dialect.geometryName(simple));
        return "図形";
    }
    private Rectangle2D anchor(Shape<?, ?> shape) { return dialect.anchor(shape); }
    private AffineTransform shapeTransform(Shape<?, ?> shape, AffineTransform parent, Rectangle2D anchor) {
        AffineTransform transform = new AffineTransform(parent);
        transform.translate(anchor.getCenterX(), anchor.getCenterY());
        transform.rotate(Math.toRadians(dialect.rotation(shape)));
        transform.scale(dialect.flipHorizontal(shape) ? -1 : 1, dialect.flipVertical(shape) ? -1 : 1);
        transform.translate(-anchor.getCenterX(), -anchor.getCenterY());
        return transform;
    }
    private static boolean axisAligned(AffineTransform transform) {
        return Math.abs(transform.getShearX()) < 1e-9 && Math.abs(transform.getShearY()) < 1e-9;
    }
    private static boolean finite(Rectangle2D r) { return r != null && Double.isFinite(r.getX()) && Double.isFinite(r.getY()) && Double.isFinite(r.getWidth()) && Double.isFinite(r.getHeight()) && r.getWidth() >= 0 && r.getHeight() >= 0; }
}
