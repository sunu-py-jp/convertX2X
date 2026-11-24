package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.Markdown;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;
import org.apache.poi.ddf.AbstractEscherOptRecord;
import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFConnectorShape;
import org.apache.poi.hslf.usermodel.HSLFFill;
import org.apache.poi.hslf.usermodel.HSLFFreeformShape;
import org.apache.poi.hslf.usermodel.HSLFHyperlink;
import org.apache.poi.hslf.usermodel.HSLFLine;
import org.apache.poi.hslf.usermodel.HSLFObjectShape;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSimpleShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTableCell;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.sl.usermodel.AutoNumberingScheme;
import org.apache.poi.sl.usermodel.LineDecoration.DecorationShape;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.PictureShape;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.SimpleShape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;

/**
 * PowerPoint 97-2003 (.ppt): reads what the binary format stores in its drawing records.
 * The format's saved connector attachments are not available through POI, so connectors are always
 * reported without endpoints and {@link SlideMarkdownReader} fills them from boundary contact.
 */
final class BinarySlideDialect implements SlideDialect {
    /** fHidden in the group-shape boolean properties. */
    private static final int HIDDEN_FLAG = 0x2;
    /** msofillSolid, the fill type of a single color. */
    private static final int SOLID_FILL = 0;

    @Override public boolean hidden(Slide<?, ?> slide) { return ((HSLFSlide) slide).isHidden(); }

    @Override public boolean hidden(Shape<?, ?> shape) {
        return (((HSLFShape) shape).getEscherProperty(EscherPropertyTypes.GROUPSHAPE__FLAGS, 0) & HIDDEN_FLAG) != 0;
    }

    @Override public Placeholder placeholder(Shape<?, ?> shape) {
        return shape instanceof HSLFSimpleShape simple ? simple.getPlaceholder() : null;
    }

    @Override public Rectangle2D anchor(Shape<?, ?> shape) { return ((HSLFShape) shape).getAnchor(); }

    @Override public double rotation(Shape<?, ?> shape) { return ((HSLFShape) shape).getRotation(); }

    @Override public boolean flipHorizontal(Shape<?, ?> shape) { return ((HSLFShape) shape).getFlipHorizontal(); }

    @Override public boolean flipVertical(Shape<?, ?> shape) { return ((HSLFShape) shape).getFlipVertical(); }

    @Override public void sanitize(Shape<?, ?> shape, Runnable externalFillRemoved) {
        // Fills use pictures stored in the file. A linked file name is never opened by POI, so nothing is removed.
    }

    @Override public String unsupportedFrame(Shape<?, ?> shape) {
        // Charts and other embedded documents of this format are OLE objects.
        return shape instanceof HSLFObjectShape ? "埋め込みオブジェクト" : null;
    }

    @Override public boolean connector(Shape<?, ?> shape) {
        if (shape instanceof HSLFConnectorShape || shape instanceof HSLFLine) return true;
        ShapeType type = shape instanceof HSLFSimpleShape simple ? simple.getShapeType() : null;
        return type == ShapeType.LINE || type != null && type.name().contains("CONNECTOR");
    }

    @Override public boolean textBox(Shape<?, ?> shape) {
        return shape instanceof HSLFTextBox && !(shape instanceof HSLFTableCell);
    }

    @Override public String geometryName(SimpleShape<?, ?> shape) {
        if (shape instanceof HSLFFreeformShape) return "freeform";
        ShapeType type = shape.getShapeType();
        return type == null ? "" : Objects.toString(type.getOoxmlName(), "");
    }

    /** The format has no header-row mark, so every row is body content under an empty Markdown header. */
    @Override public boolean headerRow(TableShape<?, ?> table) { return false; }

    @Override public void clearText(TableCell<?, ?> cell) { ((HSLFTableCell) cell).setText(""); }

    @Override public PresentationText.Content text(TextShape<?, ?> shape, IntConsumer count) {
        HSLFTextShape text = (HSLFTextShape) shape;
        List<PresentationText.Paragraph> paragraphs = new ArrayList<>();
        for (HSLFTextParagraph paragraph : text.getTextParagraphs()) {
            count.accept(1);
            List<PresentationText.Run> runs = new ArrayList<>();
            List<HSLFTextRun> saved = List.copyOf(paragraph.getTextRuns());
            for (int index = 0; index < saved.size(); index++) {
                HSLFTextRun run = saved.get(index);
                String value = Objects.toString(run.getRawText(), "");
                count.accept(Math.max(1, value.length()));
                if (run.isStrikethrough() || paragraph.isHeaderOrFooter()) {
                    // Emptied in the in-memory model too, so the preview matches the Markdown.
                    run.setText("");
                    continue;
                }
                // The paragraph mark is stored as the last character of the paragraph's text.
                if (index == saved.size() - 1 && value.endsWith("\r")) value = value.substring(0, value.length() - 1);
                HSLFHyperlink link = run.getHyperlink();
                runs.add(new PresentationText.Run(value, run.isBold(), safeAddress(link)));
            }
            AutoNumberingScheme scheme = paragraph.getAutoNumberingScheme();
            // The start value is a property of the whole list here, never a restart at one paragraph.
            paragraphs.add(new PresentationText.Paragraph(runs, paragraph.getIndentLevel(), scheme, false,
                    scheme != null ? paragraph.getAutoNumberingStartAt() : null, scheme == null && paragraph.isBullet()));
        }
        return PresentationText.assemble(paragraphs, shape instanceof HSLFTableCell ? null : safeAddress(text.getHyperlink()));
    }

    private static String safeAddress(HSLFHyperlink link) {
        String address = link == null ? null : link.getAddress();
        return address != null && Markdown.safeLink(address) ? address : null;
    }

    /** Linked pictures have no stored data and are reported by the shared reader when their data is absent. */
    @Override public boolean externalPicture(PictureShape<?, ?> picture) { return false; }

    @Override public long storedPictureBytes(PictureData data) { return ((HSLFPictureData) data).getRawData().length; }

    /** A picture frame of this format is always shown as its rectangle; cropping is read separately. */
    @Override public boolean rectangularPicture(PictureShape<?, ?> picture) { return true; }

    @Override public String description(Shape<?, ?> shape) {
        AbstractEscherOptRecord options = ((HSLFShape) shape).getEscherOptRecord();
        EscherComplexProperty property = options == null ? null
                : HSLFShape.getEscherProperty(options, EscherPropertyTypes.GROUPSHAPE__DESCRIPTION);
        if (property == null || property.getComplexData() == null) return "";
        String value = new String(property.getComplexData(), StandardCharsets.UTF_16LE);
        int terminator = value.indexOf('\0');
        return terminator < 0 ? value : value.substring(0, terminator);
    }

    @Override public List<PresentationConnections.Edge> connections(List<Shape<?, ?>> visibleLeaves) {
        List<PresentationConnections.Edge> edges = new ArrayList<>();
        for (Shape<?, ?> shape : visibleLeaves) {
            if (!connector(shape) || !(shape instanceof HSLFSimpleShape line)) continue;
            edges.add(PresentationConnections.unattached("shape-" + shape.getShapeId(),
                    marker(line.getLineHeadDecoration()), marker(line.getLineTailDecoration())));
        }
        return List.copyOf(edges);
    }

    /** The head decorates the first point of the line and the tail its last point, as in DrawingML. */
    private static String marker(DecorationShape decoration) {
        return decoration == null ? "none" : decoration.name().toLowerCase(Locale.ROOT);
    }

    @Override public PresentationSpatialMetadata.VisualArrow visualArrow(Shape<?, ?> connector,
            PresentationConnections.Edge edge, AffineTransform slideTransform) {
        ShapeType type = connector instanceof HSLFSimpleShape simple ? simple.getShapeType() : null;
        boolean straight = type == ShapeType.LINE || type == ShapeType.STRAIGHT_CONNECTOR_1;
        return PresentationSpatialMetadata.visualArrow(((HSLFShape) connector).getAnchor(), straight, edge, slideTransform);
    }

    @Override public PresentationRenderer.Background background(Slide<?, ?> slide) {
        try {
            // Only the stored fill type and color are read; a picture or texture fill is never loaded.
            var background = ((HSLFSlide) slide).getBackground();
            HSLFFill fill = background == null ? null : background.getFill();
            if (fill == null) return new PresentationRenderer.Background(Color.WHITE, false);
            if (fill.getFillType() != SOLID_FILL) return new PresentationRenderer.Background(Color.WHITE, true);
            Color color = fill.getForegroundColor();
            return new PresentationRenderer.Background(color == null ? Color.WHITE : color, color == null);
        } catch (RuntimeException unsupportedFill) {
            return new PresentationRenderer.Background(Color.WHITE, true);
        }
    }
}
