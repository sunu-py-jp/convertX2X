package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.images.EmbeddedImagePreprocessor;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.PictureShape;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.SimpleShape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFFreeformShape;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** PPTX: reads the values POI does not expose in its format-neutral model from the saved DrawingML. */
final class OoxmlSlideDialect implements SlideDialect {
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Override public boolean hidden(Slide<?, ?> slide) { return ((XSLFSlide) slide).isHidden(); }

    @Override public boolean hidden(Shape<?, ?> shape) {
        Element properties = ownProperties((XSLFShape) shape);
        return properties != null && Set.of("1", "true").contains(properties.getAttribute("hidden"));
    }

    @Override public Placeholder placeholder(Shape<?, ?> shape) { return ((XSLFShape) shape).getPlaceholder(); }

    @Override public Rectangle2D anchor(Shape<?, ?> shape) {
        if (shape instanceof XSLFSimpleShape simple) return simple.getAnchor();
        if (shape instanceof XSLFGroupShape group) return group.getAnchor();
        if (shape instanceof XSLFGraphicFrame frame) return frame.getAnchor();
        return null;
    }

    @Override public double rotation(Shape<?, ?> shape) {
        if (shape instanceof XSLFSimpleShape simple) return simple.getRotation();
        if (shape instanceof XSLFGroupShape group) return group.getRotation();
        if (shape instanceof XSLFGraphicFrame frame) return frame.getRotation();
        return 0;
    }

    @Override public boolean flipHorizontal(Shape<?, ?> shape) {
        return shape instanceof XSLFSimpleShape s ? s.getFlipHorizontal() : shape instanceof XSLFGroupShape g && g.getFlipHorizontal();
    }

    @Override public boolean flipVertical(Shape<?, ?> shape) {
        return shape instanceof XSLFSimpleShape s ? s.getFlipVertical() : shape instanceof XSLFGroupShape g && g.getFlipVertical();
    }

    @Override public void sanitize(Shape<?, ?> shape, Runnable externalFillRemoved) {
        // Removing an external r:link avoids allowing a renderer to choose it over an embedded image.
        // Standalone external pictures are reported using their saved relationship, before any I/O.
        if (shape instanceof XSLFPictureShape || shape instanceof XSLFGroupShape) return;
        stripBlips(((XSLFShape) shape).getXmlObject().getDomNode().getChildNodes(), externalFillRemoved);
    }

    private static void stripBlips(NodeList nodes, Runnable externalFillRemoved) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && "blip".equals(element.getLocalName())) {
                String link = element.getAttributeNS(RELATIONSHIPS, "link");
                if (!link.isBlank()) {
                    element.removeAttributeNS(RELATIONSHIPS, "link");
                    externalFillRemoved.run();
                }
            }
            stripBlips(node.getChildNodes(), externalFillRemoved);
        }
    }

    @Override public String unsupportedFrame(Shape<?, ?> shape) {
        if (!(shape instanceof XSLFGraphicFrame frame)) return null;
        return shape instanceof XSLFObjectShape ? "埋め込みオブジェクト" : frame.hasChart() ? "グラフ" : frame.hasDiagram() ? "SmartArt" : "図表オブジェクト";
    }

    @Override public boolean connector(Shape<?, ?> shape) { return shape instanceof XSLFConnectorShape; }

    @Override public boolean textBox(Shape<?, ?> shape) { return shape instanceof XSLFTextBox; }

    @Override public String geometryName(SimpleShape<?, ?> shape) {
        if (shape instanceof XSLFFreeformShape) return "freeform";
        var type = shape.getShapeType();
        return type == null ? storedGeometry((XSLFSimpleShape) shape) : type.getOoxmlName();
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

    @Override public boolean headerRow(TableShape<?, ?> table) {
        var properties = ((XSLFTable) table).getCTTable().getTblPr();
        return properties != null && properties.isSetFirstRow() && properties.getFirstRow();
    }

    @Override public void clearText(TableCell<?, ?> cell) { ((XSLFTableCell) cell).clearText(); }

    @Override public PresentationText.Content text(TextShape<?, ?> shape, IntConsumer count) {
        return PresentationText.read((XSLFTextShape) shape, count);
    }

    @Override public boolean externalPicture(PictureShape<?, ?> picture) {
        // isExternalLinkedPicture is checked before reading any picture relationship.
        return ((XSLFPictureShape) picture).isExternalLinkedPicture();
    }

    @Override public long storedPictureBytes(PictureData data) { return ((XSLFPictureData) data).getPackagePart().getSize(); }

    @Override public boolean rectangularPicture(PictureShape<?, ?> picture) {
        return EmbeddedImagePreprocessor.rectangularVisibility(((XSLFPictureShape) picture).getXmlObject().getDomNode());
    }

    @Override public String description(Shape<?, ?> shape) {
        Element properties = ownProperties((XSLFShape) shape);
        if (properties == null) return null;
        String description = properties.getAttribute("descr");
        return description.isBlank() ? properties.getAttribute("title") : description;
    }

    @Override public List<PresentationConnections.Edge> connections(List<Shape<?, ?>> visibleLeaves) {
        return PresentationConnections.extract(visibleLeaves.stream().map(shape -> (XSLFShape) shape).toList());
    }

    @Override public PresentationSpatialMetadata.VisualArrow visualArrow(Shape<?, ?> connector,
            PresentationConnections.Edge edge, AffineTransform slideTransform) {
        return PresentationSpatialMetadata.visualArrow((XSLFConnectorShape) connector, edge, slideTransform);
    }

    @Override public PresentationRenderer.Background background(Slide<?, ?> slide) {
        return PresentationRenderer.background((XSLFSlide) slide);
    }

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
}
