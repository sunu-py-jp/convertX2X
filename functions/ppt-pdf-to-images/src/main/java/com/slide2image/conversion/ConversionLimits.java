package com.slide2image.conversion;

/** Per-request limits, applied by both HTTP and background conversions. */
public record ConversionLimits(long maxInputBytes, int maxPages,
                               long maxPixelsPerPage, long maxOutputBytes) {
    public ConversionLimits {
        if (maxInputBytes <= 0 || maxPages <= 0 || maxPixelsPerPage <= 0 || maxOutputBytes <= 0
                || maxInputBytes > Integer.MAX_VALUE - 8L || maxOutputBytes > Integer.MAX_VALUE - 8L
                || maxPixelsPerPage > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Conversion limits must be positive and fit in Java arrays.");
        }
    }

    public static ConversionLimits defaults() {
        return new ConversionLimits(20L * 1024 * 1024, 50, 16_000_000, 100L * 1024 * 1024);
    }
}
