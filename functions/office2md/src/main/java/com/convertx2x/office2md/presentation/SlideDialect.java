package com.convertx2x.office2md.presentation;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.List;
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

/**
 * What a presentation file format stores in its own way. {@link SlideMarkdownReader} holds the shared
 * reading order, Markdown, diagram and preview logic and asks only these questions of a format, so a
 * format is supported by adding an implementation and never by changing another format's reads.
 */
interface SlideDialect {
    boolean hidden(Slide<?, ?> slide);

    boolean hidden(Shape<?, ?> shape);

    /** The placeholder role of a shape, or null. */
    Placeholder placeholder(Shape<?, ?> shape);

    /** The position of a shape in its parent's coordinates, or null when the format gives none. */
    Rectangle2D anchor(Shape<?, ?> shape);

    double rotation(Shape<?, ?> shape);

    boolean flipHorizontal(Shape<?, ?> shape);

    boolean flipVertical(Shape<?, ?> shape);

    /**
     * Removes references a renderer must never follow from the in-memory shape.
     * {@code externalFillRemoved} runs once for each removed external picture fill.
     */
    void sanitize(Shape<?, ?> shape, Runnable externalFillRemoved);

    /** The kind of an object this converter does not read (chart, SmartArt, embedded object), or null. */
    String unsupportedFrame(Shape<?, ?> shape);

    boolean connector(Shape<?, ?> shape);

    boolean textBox(Shape<?, ?> shape);

    /** The DrawingML preset name of a shape's geometry, "freeform", "custom", or "" when unknown. */
    String geometryName(SimpleShape<?, ?> shape);

    /** Whether the first row of the table is marked as its header. */
    boolean headerRow(TableShape<?, ?> table);

    /** Removes the text of a covered (merged-away) cell from the in-memory model before it is drawn. */
    void clearText(TableCell<?, ?> cell);

    /** Saved text of a shape; {@code count} receives the number of read items for the conversion limits. */
    PresentationText.Content text(TextShape<?, ?> shape, IntConsumer count);

    /** Whether the picture is a link to a file outside the document. Checked before any picture data is read. */
    boolean externalPicture(PictureShape<?, ?> picture);

    /** The stored size of a picture, known without expanding it. */
    long storedPictureBytes(PictureData data);

    /** Whether the visible area of the picture is its plain rectangle (no mask or non-rectangular geometry). */
    boolean rectangularPicture(PictureShape<?, ?> picture);

    /** The alternative text of a shape ("" when it has none), or null when the format stores no such property. */
    String description(Shape<?, ?> shape);

    /** Saved connector relationships among the visible leaves of one slide. Absent endpoints may be inferred later. */
    List<PresentationConnections.Edge> connections(List<Shape<?, ?>> visibleLeaves);

    PresentationSpatialMetadata.VisualArrow visualArrow(Shape<?, ?> connector, PresentationConnections.Edge edge,
                                                        AffineTransform slideTransform);

    /** The slide background as a solid color, without loading picture fills. */
    PresentationRenderer.Background background(Slide<?, ?> slide);
}
