package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.drawing.DiagramMetadata;
import java.awt.geom.Rectangle2D;
import java.util.Map;

/** PowerPoint uses the shared JSON image metadata contract with slide-local coordinates. */
final class PresentationImageMetadata {
    private PresentationImageMetadata() { }
    static Map<String, Object> of(String type, String text, Rectangle2D bounds) {
        return DiagramMetadata.of(type, text, bounds);
    }
    static String imageAlt(Map<String, Object> metadata) {
        return DiagramMetadata.imageAlt(metadata);
    }
}
