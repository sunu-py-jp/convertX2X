package com.convertx2x.office2md.presentation;

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

/** Metadata that is valid JSON both in Markdown source and in the rendered image's alt attribute. */
final class PresentationImageMetadata {
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactoryBuilder()
            .characterEscapes(new MarkdownStringEscapes()).build());

    private PresentationImageMetadata() { }

    static Map<String, Object> of(String type, String text, Rectangle2D bounds) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", type);
        metadata.put("text", text);
        metadata.put("x", coordinate(bounds.getX()));
        metadata.put("y", coordinate(bounds.getY()));
        metadata.put("width", coordinate(bounds.getWidth()));
        metadata.put("height", coordinate(bounds.getHeight()));
        return metadata;
    }

    static String imageAlt(Map<String, Object> metadata) throws IOException {
        // Do not apply Markdown.escape: its backslash/entity escapes would invalidate the raw JSON.
        return JSON.writeValueAsString(metadata);
    }

    private static BigDecimal coordinate(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }

    private static final class MarkdownStringEscapes extends CharacterEscapes {
        private final int[] escapes = standardAsciiEscapesForJSON();
        private final SerializableString[] sequences = new SerializableString[128];

        MarkdownStringEscapes() {
            // Escape string CONTENT, not JSON punctuation. Unicode escapes survive Markdown parsing;
            // ordinary JSON escapes such as backslash-quote and backslash-backslash do not.
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
