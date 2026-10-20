package com.convertx2x.excel2img.conversion;

import java.util.Map;

/** Geometry and readability limits; values are applied before allocating a page bitmap. */
public record RenderPolicy(int maxWidthPixels, int maxHeightPixels, double minTextPixels,
                           double pixelsPerPoint, long maxCells, long maxSourceImagePixels) {
    public RenderPolicy {
        if (maxWidthPixels <= 0 || maxHeightPixels <= 0 ||
                !Double.isFinite(minTextPixels) || minTextPixels <= 0 ||
                !Double.isFinite(pixelsPerPoint) || pixelsPerPoint <= 0 ||
                maxCells <= 0 || maxSourceImagePixels <= 0)
            throw new IllegalArgumentException("Render policy dimensions, readability, resolution and limits must be finite and positive");
    }
    public static RenderPolicy defaults() { return new RenderPolicy(2048, 2800, 12, 2, 200_000, 20_000_000); }
    public static RenderPolicy from(Map<String, String> environment) {
        RenderPolicy d = defaults();
        return new RenderPolicy(integer(environment, "MAX_WIDTH_PIXELS", d.maxWidthPixels),
                integer(environment, "MAX_HEIGHT_PIXELS", d.maxHeightPixels),
                decimal(environment, "MIN_TEXT_PIXELS", d.minTextPixels),
                decimal(environment, "PIXELS_PER_POINT", d.pixelsPerPoint),
                number(environment, "MAX_CELLS", d.maxCells),
                number(environment, "MAX_SOURCE_IMAGE_PIXELS", d.maxSourceImagePixels));
    }
    private static String value(Map<String, String> env, String suffix) {
        return env == null ? null : env.get("CONVERSION_RENDER_" + suffix);
    }
    private static long number(Map<String, String> env, String key, long fallback) {
        String v = value(env, key); return v == null || v.isBlank() ? fallback : Long.parseLong(v);
    }
    private static int integer(Map<String, String> env, String key, int fallback) {
        return Math.toIntExact(number(env, key, fallback));
    }
    private static double decimal(Map<String, String> env, String key, double fallback) {
        String v = value(env, key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v);
    }
}
