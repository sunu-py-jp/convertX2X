package com.convertx2x.excel2md.conversion;

public record ConversionLimits(long maxInputBytes, int maxSheets, int maxReadCells,
        int maxTableCells, long maxMarkdownBytes, int maxImages, long maxImageBytes,
        long maxOutputBytes, int maxShapes, int maxGroupDepth, long maxImagePixels) {
    public ConversionLimits {
        if (maxInputBytes < 1 || maxSheets < 1 || maxReadCells < 1 || maxTableCells < 1
                || maxMarkdownBytes < 1 || maxImages < 1 || maxImageBytes < 1
                || maxOutputBytes < 1 || maxShapes < 1 || maxGroupDepth < 1 || maxImagePixels < 1)
            throw new IllegalArgumentException("Conversion limits must be positive");
    }

    public static ConversionLimits defaults() {
        return new ConversionLimits(20L * 1024 * 1024, 50, 200_000, 1_000_000,
                20L * 1024 * 1024, 200, 20L * 1024 * 1024, 100L * 1024 * 1024,
                1000, 16, 20_000_000);
    }
}
