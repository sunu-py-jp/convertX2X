package com.convertx2x.office2md.drawing;

import java.util.Map;

/** Original sheet coordinates are zero based. Unknown positions sort at the end. */
public record DrawingBlock(int firstRow, int firstColumn, int lastRow, int lastColumn,
                           String markdown, Map<String, Object> metadata) {
    public DrawingBlock(int firstRow, int firstColumn, int lastRow, int lastColumn, String markdown) {
        this(firstRow, firstColumn, lastRow, lastColumn, markdown, Map.of());
    }
}
