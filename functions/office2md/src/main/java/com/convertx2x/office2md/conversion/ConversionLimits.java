package com.convertx2x.office2md.conversion;

public record ConversionLimits(long maxInputBytes, int maxSections, int maxReadItems,
        int maxTableCells, long maxMarkdownBytes, int maxImages, long maxImageBytes,
        long maxOutputBytes, int maxShapes, int maxGroupDepth, long maxImagePixels) {
    public ConversionLimits {
        if (maxInputBytes < 1 || maxSections < 0 || maxReadItems < 0 || maxTableCells < 0
                || maxMarkdownBytes < 1 || maxImages < 0 || maxImageBytes < 1
                || maxOutputBytes < 1 || maxShapes < 0 || maxGroupDepth < 1 || maxImagePixels < 1)
            throw new IllegalArgumentException("Count limits must be non-negative; size and depth limits must be positive");
    }

    /** Zero disables an optional content-count limit. */
    public static boolean exceeds(long count, long limit) { return limit > 0 && count > limit; }

    /** A combined limit is unbounded if either source can produce an unlimited number of assets. */
    public long maxAssets() { return maxImages == 0 || maxShapes == 0 ? 0 : (long) maxImages + maxShapes; }

    public long maxReportEntries() {
        return maxReadItems == 0 || maxShapes == 0 || maxImages == 0 || maxSections == 0
                ? 0 : (long) maxReadItems + maxShapes + maxImages + maxSections;
    }

    /** Reject table expansion that cannot fit even empty Markdown cells before allocating them. */
    public void checkTableCells(long cells) {
        if (exceeds(cells, maxTableCells))
            throw ConversionWorkspace.limit("TABLE_CELLS_LIMIT", "表のセル数が上限を超えました。");
        // Each generated Markdown cell needs at least three bytes: a separator and two spaces.
        if (cells > Math.min(maxMarkdownBytes, maxOutputBytes) / 3)
            throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "表を展開すると出力容量の上限を超えます。");
    }

    public static ConversionLimits defaults() {
        return new ConversionLimits(20L * 1024 * 1024, 0, 0, 0,
                20L * 1024 * 1024, 0, 20L * 1024 * 1024, 100L * 1024 * 1024,
                0, 16, 20_000_000);
    }
}
