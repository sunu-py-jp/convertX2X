package com.convertx2x.excel2md.drawing;

/** Original sheet coordinates are zero based. Unknown positions sort at the end. */
public record DrawingBlock(int firstRow, int firstColumn, int lastRow, int lastColumn,
                           String markdown) { }
