package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.*;
import com.convertx2x.office2md.drawing.DrawingAltText;
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
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** PPTX extraction and individual-shape rendering. This class never evaluates or follows external content. */
public final class PowerPointMarkdownConverter {
    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
    private final ConversionLimits limits;

    public PowerPointMarkdownConverter(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public ConversionResult convert(byte[] input, String filename) {
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
        ConversionWorkspace workspace = new ConversionWorkspace(limits);
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
                    Entry entry = inspect(shape, 0, new AffineTransform(), false);
                    if (entry != null) leaves(entry, entries);
                }
                Entry title = entries.stream().filter(entry -> isTitle(entry.shape) && !entry.text.plain().isBlank())
                        .min(readingOrder()).orElse(null);
                append("[page " + ordinal + "]\n\n");
                append("# " + Markdown.escape(title == null ? section : DrawingAltText.singleLine(title.text.plain())) + "\n\n");
                workspace.block(Map.of("type", "heading", "section", section, "range", "slide-" + ordinal, "level", 1));
                if (title != null) entries.remove(title);
                entries.sort(readingOrder());
                for (Entry entry : entries) emit(entry);
            }
            workspace.write("document.md", markdown.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void count(int amount) {
            readItems += amount;
            if (ConversionLimits.exceeds(readItems, limits.maxReadItems())) throw ConversionWorkspace.limit("READ_ITEMS_LIMIT", "文字・読み取り要素数が上限を超えました。");
        }

        private Entry inspect(XSLFShape shape, int depth, AffineTransform parent, boolean parentFlipped) throws IOException {
            workspace.shapeVisited(depth);
            if (hidden(shape) || excludedPlaceholder(shape)) return null;
            Rectangle2D anchor = anchor(shape);
            if (anchor == null) {
                workspace.warning("UNSUPPORTED_DRAWING", section, range(shape), "位置を解釈できない図形を除外しました。");
                return null;
            }
            if (!finite(anchor)) throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "図形の座標が有効な範囲ではありません。");
            AffineTransform transform = new AffineTransform(parent);
            transform.translate(anchor.getCenterX(), anchor.getCenterY());
            transform.rotate(Math.toRadians(rotation(shape)));
            transform.scale(flipH(shape) ? -1 : 1, flipV(shape) ? -1 : 1);
            transform.translate(-anchor.getCenterX(), -anchor.getCenterY());
            Entry entry = new Entry(shape, transform.createTransformedShape(anchor).getBounds2D(), sequence++,
                    new AffineTransform(transform), new AffineTransform(parent), depth > 0,
                    parentFlipped || flipH(shape) || flipV(shape));
            stripExternalBlips(shape);
            if (shape instanceof XSLFTable table) {
                long cells = (long) table.getNumberOfRows() * table.getNumberOfColumns();
                tableCells += cells;
                limits.checkTableCells(tableCells);
                entry.table = table(table);
            } else if (shape instanceof XSLFGroupShape group) {
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
                    Entry part = inspect(child, depth + 1, transform, entry.flipped);
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
                    byte[] pictureBytes = data.getData();
                    if (pictureBytes.length > limits.maxImageBytes()) throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
                    entry.picture = data;
                    entry.pictureType = data.getType();
                    entry.attachment = entry.pictureType != PictureType.PNG && entry.pictureType != PictureType.JPEG;
                    if (entry.attachment) workspace.warning("UNSUPPORTED_IMAGE_FORMAT", section, range(shape), "PNG・JPEG以外の画像は元のバイナリを添付し、描画しません。");
                    else checkPicturePixels(pictureBytes);
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

        private String table(XSLFTable table) {
            if (table.getNumberOfRows() == 0 || table.getNumberOfColumns() == 0) return "";
            StringBuilder out = new StringBuilder(); boolean merged = false;
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
                    merged |= covered || cell.getGridSpan() > 1 || cell.getRowSpan() > 1;
                    out.append(' ').append(covered ? "" : content.markdown().replace("  \n", "<br>").replace("\n", "<br>")).append(" |");
                }
                out.append('\n');
                if (r == 0 && header) out.append('|').append(" --- |".repeat(table.getNumberOfColumns())).append('\n');
            }
            if (merged) workspace.warning("TABLE_MERGE_FLATTENED", section, range(table), "Markdown表では結合の開始セルだけに内容を残します。");
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
            } else {
                String description = DrawingAltText.describe(typeName(first), rotationDescription(first),
                        first.picture != null ? first.pictureDescription : first.text.plain())
                        + "、" + DrawingAltText.geometry("スライド左上", first.bounds);
                String alt = DrawingAltText.imageAlt(List.of(description), limits.maxMarkdownBytes());
                String path;
                if (first.picture != null && (first.attachment || (!first.grouped && unchangedPicture(first.shape)))) {
                    String extension = first.pictureType.extension.replace(".", "").toLowerCase(Locale.ROOT);
                    if (!extension.matches("[a-z0-9]{1,8}")) extension = "bin";
                    String mime = first.pictureType.contentType;
                    path = workspace.addAsset("image", first.picture.getData(), extension, mime == null ? "application/octet-stream" : mime);
                    append((first.attachment ? "[" : "![") + alt + "](" + path + ")\n\n");
                    type = first.attachment ? "attachment" : "image";
                } else {
                    byte[] png = PresentationRenderer.png(first.shape, first.bounds, first.parentTransform, limits);
                    path = workspace.addAsset("diagram", png, "png", "image/png");
                    append("![" + alt + "](" + path + ")\n\n"); type = "diagram";
                }
                LinkedHashSet<PresentationText.Link> links = new LinkedHashSet<>();
                links.addAll(first.text.links());
                if (first.shapeLink != null) links.add(new PresentationText.Link(first.text.plain().isBlank() ? "リンク" : first.text.plain(), first.shapeLink));
                for (PresentationText.Link link : links)
                    append(Markdown.link(Markdown.escape(DrawingAltText.singleLine(link.label())), link.address()) + "\n\n");
                workspace.block(Map.of("type", type, "section", section, "range", range(first.shape), "path", path));
                return;
            }
            workspace.block(Map.of("type", type, "section", section, "range", range(first.shape)));
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
        final AffineTransform transform, parentTransform; final boolean grouped, flipped;
        final List<Entry> children = new ArrayList<>();
        PresentationText.Content text = PresentationText.Content.EMPTY;
        boolean normalText, attachment;
        String table, unsupported, shapeLink, pictureDescription = "";
        XSLFPictureData picture; PictureType pictureType;
        Entry(XSLFShape shape, Rectangle2D bounds, int order, AffineTransform transform,
              AffineTransform parentTransform, boolean grouped, boolean flipped) {
            this.shape = shape; this.bounds = bounds; this.order = order; this.transform = transform;
            this.parentTransform = parentTransform; this.grouped = grouped; this.flipped = flipped;
        }
    }
    private static void leaves(Entry entry, List<Entry> target) {
        if (entry.children.isEmpty()) target.add(entry); else entry.children.forEach(child -> leaves(child, target));
    }
    private static String rotationDescription(Entry entry) {
        AffineTransform transform = entry.transform;
        double x = Math.hypot(transform.getScaleX(), transform.getShearY());
        double y = Math.hypot(transform.getShearX(), transform.getScaleY());
        double dot = transform.getScaleX() * transform.getShearX() + transform.getShearY() * transform.getScaleY();
        if (!entry.flipped && x > 0 && y > 0 && transform.getDeterminant() > 0
                && Math.abs(x - y) <= Math.max(x, y) * 1e-6 && Math.abs(dot) <= x * y * 1e-6)
            return DrawingAltText.rotation(Math.toDegrees(Math.atan2(transform.getShearY(), transform.getScaleX())));
        return DrawingAltText.rotation(rotation(entry.shape)) + "（図形の保存値。反転・グループ変形後の角度は未算出）";
    }
    private static Comparator<Entry> readingOrder() {
        return Comparator.comparingDouble((Entry entry) -> entry.bounds.getY()).thenComparingDouble(entry -> entry.bounds.getX()).thenComparingInt(entry -> entry.order);
    }
    private static boolean unchangedPicture(XSLFShape shape) {
        if (rotation(shape) != 0 || flipH(shape) || flipV(shape)) return false;
        Element crop = descendant(shape.getXmlObject().getDomNode(), "srcRect");
        if (crop == null) return true;
        for (String side : List.of("l", "t", "r", "b")) {
            String value = crop.getAttribute(side);
            if (!value.isBlank() && !value.equals("0")) return false;
        }
        return true;
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
        if (entry.shape instanceof XSLFConnectorShape) return "接続線";
        if (entry.shape instanceof XSLFTextBox) return "テキストボックス";
        if (entry.shape instanceof XSLFSimpleShape simple) return DrawingAltText.typeName(simple.getShapeType().getOoxmlName());
        return "図形";
    }
    private static Rectangle2D anchor(XSLFShape shape) {
        if (shape instanceof XSLFSimpleShape simple) return simple.getAnchor();
        if (shape instanceof XSLFGroupShape group) return group.getAnchor();
        if (shape instanceof XSLFGraphicFrame frame) return frame.getAnchor();
        return null;
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
