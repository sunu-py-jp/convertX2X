package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parseable JSON in both Markdown source and the rendered image alt attribute. */
public final class DiagramMetadata {
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactoryBuilder()
            .characterEscapes(new MarkdownStringEscapes()).build());

    private DiagramMetadata() { }

    /** Coordinates are points in the owning sheet or drawing scope; unknown values stay null. */
    public static Map<String, Object> of(String type, String text, Rectangle2D bounds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("text", text == null ? "" : text);
        boolean known = bounds != null && Double.isFinite(bounds.getX()) && Double.isFinite(bounds.getY())
                && Double.isFinite(bounds.getWidth()) && Double.isFinite(bounds.getHeight())
                && bounds.getWidth() >= 0 && bounds.getHeight() >= 0;
        result.put("x", known ? coordinate(bounds.getX()) : null);
        result.put("y", known ? coordinate(bounds.getY()) : null);
        result.put("width", known ? coordinate(bounds.getWidth()) : null);
        result.put("height", known ? coordinate(bounds.getHeight()) : null);
        return result;
    }

    public static String imageAlt(Map<String, Object> metadata) {
        try { return JSON.writeValueAsString(metadata); }
        catch (IOException error) { throw ConversionWorkspace.io(error); }
    }

    private static BigDecimal coordinate(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }

    private static final class MarkdownStringEscapes extends CharacterEscapes {
        private final int[] escapes = standardAsciiEscapesForJSON();
        private final SerializableString[] sequences = new SerializableString[128];

        MarkdownStringEscapes() {
            for (char c : "\"\\`*_[]<>!&|~".toCharArray()) {
                escapes[c] = ESCAPE_CUSTOM;
                sequences[c] = new SerializedString(String.format("\\u%04X", (int) c));
            }
        }
        @Override public int[] getEscapeCodesForAscii() { return escapes; }
        @Override public SerializableString getEscapeSequence(int c) {
            return c >= 0 && c < sequences.length ? sequences[c] : null;
        }
    }
}
