package com.convertx2x.office2md.images;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.imageio.ImageIO;
import org.apache.batik.anim.dom.SVGDOMImplementation;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.util.ParsedURL;
import org.apache.poi.common.usermodel.GenericRecord;
import org.apache.poi.hemf.record.emfplus.HemfPlusImage.EmfPlusImage;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.record.HwmfBitmap16;
import org.apache.poi.hwmf.record.HwmfBitmapDib;
import org.apache.poi.hwmf.record.HwmfFill.WmfStretchDib;
import org.apache.poi.hwmf.usermodel.HwmfPicture;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Static, offline vector decoding. Active/external SVG content is rejected rather than fetched. */
final class VectorImageRasterizer {
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final int MAX_NODES = 20_000;
    private static final int MAX_DEPTH = 64;
    private static final Set<String> SVG_ELEMENTS = Set.of("svg", "g", "defs", "title", "desc", "metadata",
            "rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "text", "tspan", "textPath",
            "use", "symbol", "linearGradient", "radialGradient", "stop", "clipPath", "style", "image", "marker", "switch");
    private static final Pattern URL = Pattern.compile("(?i)url\\s*\\(([^)]*)\\)");
    private static final Pattern LENGTH = Pattern.compile("([+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(px|pt|pc|in|cm|mm)?");

    private VectorImageRasterizer() { }

    static boolean recognizes(byte[] bytes, String extension, String contentType) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return Set.of("svg", "emf", "wmf").contains(ext) || type.contains("svg")
                || type.endsWith("/emf") || type.endsWith("/x-emf")
                || type.endsWith("/wmf") || type.endsWith("/x-wmf")
                || isEmf(bytes) || isWmf(bytes);
    }

    static BufferedImage rasterize(byte[] bytes, String extension, String contentType,
                                   double width, double height, ConversionLimits limits) throws IOException {
        int pixelWidth = pixels(width, limits), pixelHeight = pixels(height, limits);
        checkPixels(pixelWidth, pixelHeight, limits);
        try {
            if (isEmf(bytes)) {
                HemfPicture picture = new HemfPicture(new ByteArrayInputStream(bytes));
                inspectMetafile(picture, limits);
                return paintMetafile(pixelWidth, pixelHeight, g -> picture.draw(g,
                        new Rectangle2D.Double(0, 0, pixelWidth, pixelHeight)));
            }
            if (isWmf(bytes)) {
                HwmfPicture picture = new HwmfPicture(new ByteArrayInputStream(bytes));
                inspectMetafile(picture, limits);
                return paintMetafile(pixelWidth, pixelHeight, g -> picture.draw(g,
                        new Rectangle2D.Double(0, 0, pixelWidth, pixelHeight)));
            }
            if ("svg".equalsIgnoreCase(extension) || contentType != null && contentType.contains("svg"))
                return svg(bytes, pixelWidth, pixelHeight, limits);
            throw new IOException("Vector format does not match its signature");
        } catch (TranscoderException | SAXException | javax.xml.parsers.ParserConfigurationException failure) {
            throw new IOException("Static vector image cannot be decoded safely", failure);
        }
    }

    private static BufferedImage svg(byte[] bytes, int width, int height, ConversionLimits limits)
            throws IOException, SAXException, javax.xml.parsers.ParserConfigurationException, TranscoderException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        });
        Document source = builder.parse(new ByteArrayInputStream(bytes));
        if (!SVG.equals(source.getDocumentElement().getNamespaceURI())
                || !"svg".equals(source.getDocumentElement().getLocalName()))
            throw new IOException("Expected an SVG document");
        Set<String> inlineImages = validateSvg(source, limits);
        double[] intrinsic = intrinsicViewport(source.getDocumentElement(), width, height);
        double scale = Math.min(width / intrinsic[0], height / intrinsic[1]);
        int rasterWidth = Math.max(1, (int) Math.round(intrinsic[0] * scale));
        int rasterHeight = Math.max(1, (int) Math.round(intrinsic[1] * scale));
        checkPixels(rasterWidth, rasterHeight, limits);
        Document document = SVGDOMImplementation.getDOMImplementation().createDocument(SVG, "svg", null);
        document.replaceChild(document.importNode(source.getDocumentElement(), true), document.getDocumentElement());
        // An explicit safe base enables internal #fragment references without an application filesystem URI.
        document.setDocumentURI("https://offline.invalid/image.svg");
        StaticTranscoder transcoder = new StaticTranscoder(limits, inlineImages);
        transcoder.setErrorHandler(new org.apache.batik.transcoder.ErrorHandler() {
            @Override public void warning(TranscoderException failure) throws TranscoderException { throw failure; }
            @Override public void error(TranscoderException failure) throws TranscoderException { throw failure; }
            @Override public void fatalError(TranscoderException failure) throws TranscoderException { throw failure; }
        });
        transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) rasterWidth);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) rasterHeight);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_MAX_WIDTH, (float) rasterWidth);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_MAX_HEIGHT, (float) rasterHeight);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_EXECUTE_ONLOAD, false);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
        transcoder.addTranscodingHint(ImageTranscoder.KEY_DEFAULT_FONT_FAMILY, "Noto Sans CJK JP");
        transcoder.transcode(new TranscoderInput(document), new TranscoderOutput());
        if (transcoder.image == null) throw new IOException("SVG produced no image");
        return transcoder.image;
    }

    /** Office placement stretches a picture independently of its SVG viewport aspect ratio. */
    private static double[] intrinsicViewport(Element root, int fallbackWidth, int fallbackHeight) throws IOException {
        double width = svgLength(root.getAttribute("width")), height = svgLength(root.getAttribute("height"));
        String viewBox = root.getAttribute("viewBox").strip();
        double viewWidth = Double.NaN, viewHeight = Double.NaN;
        if (!viewBox.isEmpty()) {
            String[] parts = viewBox.split("[,\\s]+");
            if (parts.length != 4) throw new IOException("Malformed SVG viewBox");
            try { viewWidth = Double.parseDouble(parts[2]); viewHeight = Double.parseDouble(parts[3]); }
            catch (NumberFormatException failure) { throw new IOException("Malformed SVG viewBox", failure); }
        }
        boolean viewKnown = Double.isFinite(viewWidth) && Double.isFinite(viewHeight) && viewWidth > 0 && viewHeight > 0;
        if (!Double.isFinite(width) && !Double.isFinite(height)) {
            width = viewKnown ? viewWidth : fallbackWidth;
            height = viewKnown ? viewHeight : fallbackHeight;
        } else if (!Double.isFinite(width)) width = viewKnown ? height * viewWidth / viewHeight : fallbackWidth;
        else if (!Double.isFinite(height)) height = viewKnown ? width * viewHeight / viewWidth : fallbackHeight;
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            throw new IOException("Invalid SVG viewport");
        // Resolve percentage/omitted dimensions against the intrinsic viewport, not the placement.
        root.setAttribute("width", Double.toString(width));
        root.setAttribute("height", Double.toString(height));
        return new double[]{width, height};
    }

    private static double svgLength(String text) {
        Matcher length = LENGTH.matcher(text.strip());
        if (!length.matches()) return Double.NaN;
        double number = Double.parseDouble(length.group(1));
        String unit = length.group(2);
        return number * (unit == null ? 1 : switch (unit) {
            case "pt" -> 96d / 72; case "pc" -> 16d; case "in" -> 96d;
            case "cm" -> 96d / 2.54; case "mm" -> 96d / 25.4; default -> 1d;
        });
    }

    private record Visit(Node node, int depth) { }

    private static Set<String> validateSvg(Document document, ConversionLimits limits) throws IOException {
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(document, 0));
        Map<String, Element> ids = new HashMap<>();
        Set<String> inlineImages = new HashSet<>();
        long inlinePixels = 0;
        int count = 0;
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            if (++count > MAX_NODES || visit.depth > MAX_DEPTH)
                throw new IOException("SVG structure exceeds its safe complexity limit");
            Node node = visit.node;
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                    || node.getNodeType() == Node.DOCUMENT_TYPE_NODE || node.getNodeType() == Node.ENTITY_REFERENCE_NODE)
                throw new IOException("Active XML content is unsupported");
            if (node instanceof Element element) {
                if (!SVG.equals(element.getNamespaceURI()) || !SVG_ELEMENTS.contains(element.getLocalName()))
                    throw new IOException("SVG contains an unsupported active or resource-loading element");
                if ("metadata".equals(element.getLocalName())) {
                    element.getParentNode().removeChild(element);
                    continue; // Non-rendered metadata may use arbitrary namespaces; never pass it to Batik.
                }
                if ("image".equals(element.getLocalName())) {
                    String href = element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
                    if (href.isEmpty()) href = element.getAttribute("href");
                    InlineRaster raster = inlineRaster(href, limits);
                    inlinePixels += raster.pixels;
                    if (inlinePixels > limits.maxImagePixels())
                        throw ConversionWorkspace.limit("IMAGE_PIXEL_LIMIT", "SVG内の画像の合計画素数が上限を超えました。");
                    inlineImages.add(raster.uri);
                    element.removeAttribute("href");
                    element.setAttributeNS("http://www.w3.org/1999/xlink", "xlink:href", raster.uri);
                }
                String id = element.getAttribute("id");
                if (!id.isEmpty() && ids.putIfAbsent(id, element) != null) throw new IOException("Duplicate SVG id");
                for (int i = 0; i < element.getAttributes().getLength(); i++) {
                    Node attribute = element.getAttributes().item(i);
                    if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) continue;
                    String name = attribute.getLocalName();
                    String value = attribute.getNodeValue();
                    if (name == null) name = attribute.getNodeName();
                    boolean inlineImage = "href".equals(name) && "image".equals(element.getLocalName())
                            && inlineImages.contains(value);
                    if ((!inlineImage && value.length() > 1_000_000) || name.toLowerCase(Locale.ROOT).startsWith("on")
                            || "base".equals(name) || "filter".equals(name) || "mask".equals(name))
                        throw new IOException("Unsafe SVG attribute");
                    if ("href".equals(name) && !inlineImage && !value.isEmpty()
                            && !value.matches("#[A-Za-z_][A-Za-z0-9_.:-]*"))
                        throw new IOException("SVG external references are unsupported");
                    if (!inlineImage) validateCss(value);
                }
                if ("style".equals(element.getLocalName())) validateCss(element.getTextContent());
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
                pending.push(new Visit(child, visit.depth + 1));
        }
        // Bound expanded <use> trees, including cycles and repeated exponential references.
        int[] budget = {MAX_NODES};
        inspectUseTree(document.getDocumentElement(), ids, new HashSet<>(), 0, budget);
        return inlineImages;
    }

    private record InlineRaster(String uri, long pixels) { }
    private static InlineRaster inlineRaster(String uri, ConversionLimits limits) throws IOException {
        int comma = uri.indexOf(',');
        if (comma < 0 || !uri.substring(0, comma).matches("data:image/(png|jpeg|gif|bmp|tiff);base64"))
            throw new IOException("Only inline raster SVG images are supported");
        if (uri.length() - comma - 1L > (limits.maxImageBytes() + 2) / 3 * 4)
            throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "SVG内の画像のサイズが上限を超えました。");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(uri.substring(comma + 1)); }
        catch (IllegalArgumentException failure) { throw new IOException("Malformed inline SVG image", failure); }
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null || !ImageIO.getImageReaders(stream).hasNext())
                throw new IOException("Inline SVG image must contain raster bytes");
        }
        // ImageIO determines the actual format and validates dimensions before any full decoding.
        var prepared = EmbeddedImagePreprocessor.prepare(bytes, "bin", "application/octet-stream",
                EmbeddedImagePreprocessor.Crop.none(), new java.awt.geom.AffineTransform(), 1, 1, limits);
        if (!prepared.ocrEligible()) throw new IOException("Unsafe inline SVG image");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(prepared.bytes()))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Missing inline image decoder");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                return new InlineRaster("data:" + prepared.contentType() + ";base64,"
                        + Base64.getEncoder().encodeToString(prepared.bytes()), pixels);
            } finally { reader.dispose(); }
        }
    }

    private static void validateCss(String value) throws IOException {
        // CSS escaping/imports can otherwise bypass a superficial URL check.
        if (value.indexOf('\\') >= 0 || value.indexOf('@') >= 0)
            throw new IOException("SVG CSS imports and escapes are unsupported");
        Matcher matcher = URL.matcher(value);
        while (matcher.find()) {
            String target = matcher.group(1).strip().replace("\"", "").replace("'", "");
            if (!target.matches("#[A-Za-z_][A-Za-z0-9_.:-]*"))
                throw new IOException("SVG external CSS references are unsupported");
        }
    }

    private static void inspectUseTree(Element element, Map<String, Element> ids, Set<Element> active,
                                       int depth, int[] budget) throws IOException {
        if (--budget[0] < 0 || depth > MAX_DEPTH || !active.add(element))
            throw new IOException("SVG references exceed their safe complexity limit");
        if (!"image".equals(element.getLocalName())) {
            String href = element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            if (href.isEmpty()) href = element.getAttribute("href");
            Element target = href.startsWith("#") ? ids.get(href.substring(1)) : null;
            if (target != null) inspectUseTree(target, ids, active, depth + 1, budget);
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                Matcher urls = URL.matcher(element.getAttributes().item(i).getNodeValue());
                while (urls.find()) {
                    String id = urls.group(1).strip().replace("\"", "").replace("'", "");
                    Element resource = id.startsWith("#") ? ids.get(id.substring(1)) : null;
                    if (resource != null) inspectUseTree(resource, ids, active, depth + 1, budget);
                }
            }
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element childElement) inspectUseTree(childElement, ids, active, depth + 1, budget);
        active.remove(element);
    }

    private static final class StaticTranscoder extends ImageTranscoder {
        private final ConversionLimits limits;
        private final Set<String> inlineImages;
        private BufferedImage image;
        StaticTranscoder(ConversionLimits limits, Set<String> inlineImages) {
            this.limits = limits;
            this.inlineImages = inlineImages;
        }
        @Override protected UserAgent createUserAgent() {
            return new SVGAbstractTranscoderUserAgent() {
                @Override public void checkLoadScript(String type, ParsedURL script, ParsedURL document) {
                    throw new SecurityException("SVG scripting is disabled");
                }
                @Override public void checkLoadExternalResource(ParsedURL resource, ParsedURL document) {
                    if (inlineImages != null && inlineImages.contains(resource.toString())) return;
                    throw new SecurityException("SVG external resources are disabled");
                }
            };
        }
        @Override public BufferedImage createImage(int width, int height) {
            checkPixels(width, height, limits);
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }
        @Override public void writeImage(BufferedImage image, TranscoderOutput output) { this.image = image; }
    }

    private interface Painter { void paint(Graphics2D graphics); }
    private static BufferedImage paintMetafile(int width, int height, Painter painter) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            painter.paint(graphics);
            return image;
        } catch (RuntimeException failure) {
            image.flush();
            throw failure;
        } finally { graphics.dispose(); }
    }

    private static void inspectMetafile(GenericRecord record, ConversionLimits limits) throws IOException {
        inspectRecord(record, limits, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }

    private static void inspectRecord(Object value, ConversionLimits limits, Set<Object> seen, int depth) throws IOException {
        if (value == null || !seen.add(value)) return;
        if (seen.size() > MAX_NODES || depth > MAX_DEPTH)
            throw new IOException("Metafile exceeds its safe complexity limit");
        if (value instanceof HwmfBitmapDib || value instanceof HwmfBitmap16) {
            Map<String, Supplier<?>> props = ((GenericRecord) value).getGenericProperties();
            long width = Math.abs(((Number) props.get("width").get()).longValue());
            long height = Math.abs(((Number) props.get("height").get()).longValue());
            if (width != 0 || height != 0) checkPixels(width, height, limits);
            return; // Never evaluate the generic "image" supplier: it decodes/allocates the bitmap.
        }
        // EMF+ can embed another metafile and concatenate image records. Do not decode unchecked recursion.
        if (value instanceof EmfPlusImage) throw new IOException("Embedded EMF+ image records are unsupported");
        if (value instanceof WmfStretchDib image) inspectRecord(image.getBitmap(), limits, seen, depth + 1);
        if (value instanceof GenericRecord generic) {
            Map<String, Supplier<?>> properties = generic.getGenericProperties();
            if (properties != null) for (Map.Entry<String, Supplier<?>> entry : properties.entrySet()) {
                if (!Set.of("image", "bmpData", "rawData", "imageData").contains(entry.getKey()))
                    inspectRecord(entry.getValue().get(), limits, seen, depth + 1);
            }
            var children = generic.getGenericChildren();
            if (children != null) for (GenericRecord child : children) inspectRecord(child, limits, seen, depth + 1);
        } else if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (Set.of("image", "bmpData", "rawData", "imageData").contains(entry.getKey())) continue;
                Object child = entry.getValue();
                inspectRecord(child instanceof Supplier<?> supplier ? supplier.get() : child, limits, seen, depth + 1);
            }
        } else if (value instanceof Iterable<?> values)
            for (Object child : values) inspectRecord(child, limits, seen, depth + 1);
    }

    private static int pixels(double points, ConversionLimits limits) {
        double pixels = Math.ceil(points * 2);
        if (!Double.isFinite(pixels) || pixels < 1 || pixels > Integer.MAX_VALUE || pixels > limits.maxImagePixels())
            throw ConversionWorkspace.limit("IMAGE_PIXEL_LIMIT", "ベクター画像の画素数が上限を超えました。");
        return (int) pixels;
    }

    private static void checkPixels(long width, long height, ConversionLimits limits) {
        if (width <= 0 || height <= 0 || width > limits.maxImagePixels() / height)
            throw ConversionWorkspace.limit("IMAGE_PIXEL_LIMIT", "画像の画素数が上限を超えました。");
    }

    private static boolean isEmf(byte[] bytes) {
        return bytes.length >= 44 && bytes[0] == 1 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0
                && bytes[40] == 0x20 && bytes[41] == 'E' && bytes[42] == 'M' && bytes[43] == 'F';
    }
    private static boolean isWmf(byte[] bytes) {
        return bytes.length >= 18 && ((bytes[0] == (byte) 0xd7 && bytes[1] == (byte) 0xcd
                && bytes[2] == (byte) 0xc6 && bytes[3] == (byte) 0x9a)
                || ((bytes[0] == 1 || bytes[0] == 2) && bytes[1] == 0 && bytes[2] == 9 && bytes[3] == 0));
    }
}
