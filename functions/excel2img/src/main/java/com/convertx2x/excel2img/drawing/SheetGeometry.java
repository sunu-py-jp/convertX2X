package com.convertx2x.excel2img.drawing;

/** Cumulative cell boundaries in points; hidden rows and columns have zero extent. */
public interface SheetGeometry {
    double x(int column);
    double y(int row);
}
