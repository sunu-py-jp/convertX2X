package com.convertx2x.excel2md.conversion;

import java.util.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Row interval tree avoids scanning every merged range for every physical cell. */
final class MergedRanges {
    private final Node root;
    private final List<CellRangeAddress> ranges;
    MergedRanges(List<CellRangeAddress> ranges) { this.ranges = ranges; root = build(ranges); }
    List<CellRangeAddress> all() { return ranges; }
    CellRangeAddress at(int row, int column) { return find(root, row, column); }
    boolean covered(int row, int column) {
        CellRangeAddress range = at(row, column);
        return range != null && (range.getFirstRow() != row || range.getFirstColumn() != column);
    }
    private static Node build(List<CellRangeAddress> input) {
        if (input.isEmpty()) return null;
        int[] starts = input.stream().mapToInt(CellRangeAddress::getFirstRow).sorted().toArray();
        int center = starts[starts.length / 2];
        List<CellRangeAddress> left = new ArrayList<>(), right = new ArrayList<>(), overlap = new ArrayList<>();
        for (CellRangeAddress range : input) {
            if (range.getLastRow() < center) left.add(range);
            else if (range.getFirstRow() > center) right.add(range);
            else overlap.add(range);
        }
        return new Node(center, overlap, build(left), build(right));
    }
    private static CellRangeAddress find(Node node, int row, int column) {
        if (node == null) return null;
        for (CellRangeAddress range : node.overlap()) if (range.isInRange(row, column)) return range;
        return row < node.center() ? find(node.left(), row, column) : row > node.center() ? find(node.right(), row, column) : null;
    }
    private record Node(int center, List<CellRangeAddress> overlap, Node left, Node right) { }
}
