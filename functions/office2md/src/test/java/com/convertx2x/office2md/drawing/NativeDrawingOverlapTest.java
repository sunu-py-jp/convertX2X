package com.convertx2x.office2md.drawing;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;

class NativeDrawingOverlapTest {
    @Test void acceptsContainedAndPartialVisualsButNotBoundaryContact() {
        var picture = shape("rect", 0, 0, 100, 100, true);
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 20, 20, 30, 30, false)));
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 90, 90, 30, 30, false)));
        assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 100, 0, 30, 30, false)));
        assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 110, 0, 30, 30, false)));
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 20, 20, 30, 30, true)));
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("star5", 20, 20, 30, 30, false)));
        assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("textWave1", 20, 20, 30, 30, false)));
    }

    @Test void crossingZeroWidthAndZeroHeightLinesCountButBoundaryLinesDoNot() {
        var picture = shape("rect", 0, 0, 100, 100, true);
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("line", 50, -10, 0, 120, false)));
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("line", -10, 50, 120, 0, false)));
        assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("line", 100, -10, 0, 120, false)));
    }

    @Test void transformedPictureRectangleRatherThanBoundingBoxDeterminesOverlap() {
        AffineTransform transform = AffineTransform.getTranslateInstance(100, 100);
        transform.rotate(Math.PI / 4);
        var picture = shape("rect", transform, 100, 100, true);
        assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 31, 101, 5, 5, false)));
        assertTrue(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", 90, 160, 20, 20, false)));
    }

    @Test void malformedOrOverflowingGroupTransformsNeverEnterGeometryIntersection() {
        var picture = shape("rect", 0, 0, 100, 100, true);
        for (AffineTransform transform : new AffineTransform[]{
                AffineTransform.getTranslateInstance(Double.NaN, 0),
                AffineTransform.getTranslateInstance(Double.POSITIVE_INFINITY, 0),
                AffineTransform.getScaleInstance(Double.MAX_VALUE, 1),
                AffineTransform.getScaleInstance(0, 1)}) {
            assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("rect", transform, 100, 100, false)));
            assertFalse(NativeDrawingRenderer.pictureOverlapsShape(shape("rect", transform, 100, 100, true), picture));
            assertFalse(NativeDrawingRenderer.pictureOverlapsShape(picture, shape("line", transform, 100, 0, false)));
        }
        // Each input transform is finite/invertible, but their relative transform overflows.
        var tinyPicture = shape("rect", AffineTransform.getScaleInstance(1e-300, 1), 100, 100, true);
        var farLine = shape("line", AffineTransform.getTranslateInstance(1e100, 0), 100, 0, false);
        assertFalse(NativeDrawingRenderer.pictureOverlapsShape(tinyPicture, farLine));
    }

    private static NativeDrawingRenderer.Shape shape(String preset, double x, double y,
                                                     double width, double height, boolean picture) {
        return shape(preset, AffineTransform.getTranslateInstance(x, y), width, height, picture);
    }

    private static NativeDrawingRenderer.Shape shape(String preset, AffineTransform transform,
                                                     double width, double height, boolean picture) {
        return new NativeDrawingRenderer.Shape(preset, width, height, transform, null, Color.RED, 1,
                "", "Noto Sans CJK JP", 12, false, Color.BLACK, 1, 1,
                picture ? new byte[]{1} : null, "png", false, false);
    }
}
