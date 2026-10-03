package com.convertx2x.md2pdf.conversion;

/** Resource limits. Zero disables the optional page-count limit. */
public record ConversionLimits(long maxInputBytes, long maxOutputBytes, int maxPages, long maxImagePixels) {
    public ConversionLimits {
        if (maxInputBytes < 1 || maxOutputBytes < 1 || maxPages < 0 || maxImagePixels < 1)
            throw new IllegalArgumentException("Size and pixel limits must be positive; pages must be non-negative");
    }

    public static ConversionLimits defaults() {
        return new ConversionLimits(20L * 1024 * 1024, 100L * 1024 * 1024, 0, 20_000_000);
    }

    public static boolean exceeds(long value, long limit) { return limit > 0 && value > limit; }

}
