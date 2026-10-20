package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionWorkspace;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adapts sanitized Office shape data to the existing bounded Java2D renderer. */
public final class NativeDrawingRenderer {
    private NativeDrawingRenderer() { }

    public record Shape(String preset, double width, double height, AffineTransform transform,
            Color fill, Color line, float lineWidth, String text, String fontFamily, float fontSize,
            boolean bold, Color textColor, int horizontalAlignment, int verticalAlignment,
            byte[] picture, String extension, boolean startArrow, boolean endArrow) { }

    public static boolean supports(String preset) { return kind(preset) != DrawingScene.Kind.UNSUPPORTED; }

    public static void verifyPicture(byte[] bytes, ConversionWorkspace workspace) throws IOException {
        DrawingScene.verifyPicture(bytes, workspace);
    }

    /** Positive overlap of a picture placement with another supported visual layer. */
    public static boolean pictureOverlapsShape(Shape picture, Shape shape) {
        return picture.picture() != null && shapesOverlap(picture, shape);
    }

    /** Geometry only: no semantic connector relationship is inferred from overlap. */
    public static boolean shapesOverlap(Shape first, Shape second) {
        return DrawingScene.visualsOverlap(item(first), item(second));
    }

    /** Shape text must already exclude deleted, struck-through, and hidden content. */
    public static byte[] render(List<Shape> shapes, ConversionWorkspace workspace) {
        List<DrawingScene.Item> items = new ArrayList<>();
        for (Shape shape : shapes) items.add(item(shape));
        return DrawingScene.render(items, workspace);
    }

    private static DrawingScene.Item item(Shape shape) {
        DrawingScene.Item item = new DrawingScene.Item();
        item.kind = shape.picture() == null ? kind(shape.preset()) : DrawingScene.Kind.PICTURE;
        item.width = shape.width(); item.height = shape.height();
        item.preset = shape.preset();
        if (item.kind == DrawingScene.Kind.PRESET) {
            item.presetGeometry = DrawingPresetGeometry.create(item.preset, item.width, item.height);
            if (item.presetGeometry == null) item.kind = DrawingScene.Kind.UNSUPPORTED;
        }
        item.transform = new AffineTransform(shape.transform());
        item.startArrow = shape.startArrow(); item.endArrow = shape.endArrow();
        item.fill = shape.fill(); item.line = shape.line(); item.lineWidth = shape.lineWidth();
        item.picture = shape.picture(); item.extension = shape.extension() == null ? "png" : shape.extension();
        String text = shape.text() == null ? "" : shape.text();
        var run = new DrawingText.Run(text, shape.fontFamily() == null ? "Noto Sans CJK JP" : shape.fontFamily(),
                shape.fontSize(), shape.bold(), false, null, shape.textColor(), false, 0);
        item.text = List.of(run);
        var paragraph = new DrawingText.Paragraph(item.text, shape.horizontalAlignment(), 0, 0, 0, 100, true, 0, 0);
        item.textContent = new DrawingText.Content(List.of(paragraph), shape.verticalAlignment(), true,
                1, 0, false, 0, "horz", List.of());
        return item;
    }

    private static DrawingScene.Kind kind(String preset) {
        String key = preset == null ? "" : preset.replace("_", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "rect", "rectangle" -> DrawingScene.Kind.RECTANGLE;
            case "roundrect", "roundrectangle" -> DrawingScene.Kind.ROUND_RECTANGLE;
            case "ellipse", "oval" -> DrawingScene.Kind.ELLIPSE;
            case "line", "straightconnector1" -> DrawingScene.Kind.LINE;
            case "rightarrow", "arrow" -> DrawingScene.Kind.RIGHT_ARROW;
            case "leftarrow" -> DrawingScene.Kind.LEFT_ARROW;
            case "uparrow" -> DrawingScene.Kind.UP_ARROW;
            case "downarrow" -> DrawingScene.Kind.DOWN_ARROW;
            case "textbox", "text" -> DrawingScene.Kind.TEXT;
            default -> DrawingPresetGeometry.supports(preset) ? DrawingScene.Kind.PRESET : DrawingScene.Kind.UNSUPPORTED;
        };
    }
}
