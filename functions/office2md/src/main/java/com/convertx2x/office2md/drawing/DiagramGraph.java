package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.Markdown;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saved diagram relationships only. The caller supplies visible, sanitized vertices in one ID scope. */
public final class DiagramGraph {
    private static final Set<String> ARROWS = Set.of("triangle", "stealth", "arrow");
    private static final Set<String> NON_DIRECTIONAL_MARKERS = Set.of("none", "oval", "diamond");
    private DiagramGraph() { }

    public record Vertex(String id, String sourceId, String type, String text, String markdown,
                         Map<String, Object> details) {
        public Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>(details);
            result.put("id", id); result.put("type", type); result.put("text", text);
            return result;
        }
    }

    /** Endpoint IDs are raw saved identifiers, not output IDs. Null means no saved endpoint. */
    public record Connection(String id, String startId, String endId, String startArrow, String endArrow) { }

    public record Edge(String id, String startId, String endId, String startArrow, String endArrow,
                       String direction, String status, String reason) {
        public Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            if (startId != null) result.put("startId", startId);
            if (endId != null) result.put("endId", endId);
            result.put("startArrow", startArrow); result.put("endArrow", endArrow);
            result.put("direction", direction); result.put("status", status); result.put("reason", reason);
            if (status.equals("resolved") && direction.equals("start-to-end")) {
                result.put("fromId", startId); result.put("toId", endId);
            } else if (status.equals("resolved") && direction.equals("end-to-start")) {
                result.put("fromId", endId); result.put("toId", startId);
            }
            return result;
        }
    }

    public static List<Edge> resolve(List<Vertex> vertices, List<Connection> connections) {
        Map<String, List<Vertex>> targets = new HashMap<>();
        for (Vertex vertex : vertices) if (vertex.sourceId() != null && !vertex.sourceId().isBlank())
            targets.computeIfAbsent(vertex.sourceId(), ignored -> new ArrayList<>()).add(vertex);
        List<Edge> result = new ArrayList<>();
        for (Connection connection : connections) {
            Endpoint start = endpoint(connection.startId(), targets), end = endpoint(connection.endId(), targets);
            String startArrow = marker(connection.startArrow()), endArrow = marker(connection.endArrow());
            String direction = direction(startArrow, endArrow);
            String reason = !start.reason().isEmpty() ? start.reason() : !end.reason().isEmpty() ? end.reason()
                    : direction.equals("unknown") ? "UNKNOWN_ARROWHEAD" : "";
            result.add(new Edge(connection.id(), start.id(), end.id(), startArrow, endArrow, direction,
                    reason.isEmpty() ? "resolved" : "unresolved", reason));
        }
        return List.copyOf(result);
    }

    private record Endpoint(String id, String reason) { }
    private static Endpoint endpoint(String sourceId, Map<String, List<Vertex>> targets) {
        if (sourceId == null || sourceId.isBlank()) return new Endpoint(null, "MISSING_ENDPOINT");
        List<Vertex> matches = targets.get(sourceId);
        if (matches == null || matches.isEmpty()) return new Endpoint(null, "TARGET_UNAVAILABLE");
        if (matches.size() != 1) return new Endpoint(null, "AMBIGUOUS_TARGET");
        return new Endpoint(matches.getFirst().id(), "");
    }

    private static String marker(String marker) { return marker == null || marker.isBlank() ? "none" : marker; }
    private static String direction(String start, String end) {
        // Known ornaments contribute no direction, but their original types remain in the metadata.
        if ((!NON_DIRECTIONAL_MARKERS.contains(start) && !ARROWS.contains(start))
                || (!NON_DIRECTIONAL_MARKERS.contains(end) && !ARROWS.contains(end)))
            return "unknown";
        if (ARROWS.contains(start)) return ARROWS.contains(end) ? "bidirectional" : "end-to-start";
        return ARROWS.contains(end) ? "start-to-end" : "undirected";
    }

    /** Vertex markdown must already be escaped/sanitized by the format-specific text extractor. */
    public static String markdown(List<Vertex> vertices, List<Edge> edges, ConversionWorkspace workspace,
                                  String section, String range) {
        Map<String, Vertex> byId = new HashMap<>();
        Set<String> referenced = new HashSet<>();
        for (Vertex vertex : vertices) byId.put(vertex.id(), vertex);
        for (Edge edge : edges) {
            if (edge.startId() != null) referenced.add(edge.startId());
            if (edge.endId() != null) referenced.add(edge.endId());
        }
        Writer result = new Writer(workspace.limits().maxMarkdownBytes());
        boolean started = false;
        for (Vertex vertex : vertices) if (!vertex.text().isBlank() || referenced.contains(vertex.id())) {
            if (!started) { result.append("図中の項目：\n\n"); started = true; }
            String text = vertex.markdown().isBlank() ? "文字なし" : vertex.markdown();
            result.append("- " + identifier(vertex.id()) + "（" + Markdown.escape(vertex.type()) + "）："
                    + text.replace("  \n", "<br>").replace("\n", "<br>") + "\n");
        }
        if (started) result.append("\n");
        if (!edges.isEmpty()) {
            result.append("接続関係（保存情報）：\n\n");
            for (Edge edge : edges) {
                String relation;
                if (!edge.status().equals("resolved")) {
                    relation = "接続関係不明（" + reason(edge.reason()) + "）";
                    workspace.warning("DIAGRAM_CONNECTION_UNRESOLVED", section, range,
                            "図形の接続関係を確定できません。" + reason(edge.reason()) + "。配置からは推測しません。図形ID: " + edge.id());
                } else {
                    String start = label(edge.startId(), byId), end = label(edge.endId(), byId);
                    relation = switch (edge.direction()) {
                        case "start-to-end" -> start + " → " + end;
                        case "end-to-start" -> end + " → " + start;
                        case "bidirectional" -> start + " ↔ " + end;
                        default -> start + " — " + end + "（向きなし）";
                    };
                }
                result.append("- " + identifier(edge.id()) + "：" + relation + "\n");
            }
            result.append("\n");
        } else if (vertices.size() > 1) {
            result.append("図形間の接続情報はありません。配置から順序や関係を推測していません。\n\n");
        }
        return result.text.toString();
    }

    private static String label(String id, Map<String, Vertex> vertices) {
        Vertex vertex = vertices.get(id);
        String text = vertex == null ? "" : DrawingAltText.singleLine(vertex.text());
        return identifier(id) + (text.isBlank() ? "（文字なし）" : "「" + Markdown.escape(text) + "」");
    }
    // IDs are inline labels; a hyphen cannot start a list here and should remain easy to match to JSON.
    private static String identifier(String id) { return Markdown.escape(id).replace("\\-", "-"); }
    private static String reason(String code) {
        return switch (code) {
            case "MISSING_ENDPOINT" -> "接続先の保存情報がありません";
            case "TARGET_UNAVAILABLE" -> "接続先が出力対象にありません";
            case "AMBIGUOUS_TARGET" -> "接続先IDが重複しています";
            case "UNKNOWN_ARROWHEAD" -> "矢印の向きを確定できません";
            default -> "保存情報を解決できません";
        };
    }

    private static final class Writer {
        final StringBuilder text = new StringBuilder();
        final long limit;
        long bytes;
        Writer(long limit) { this.limit = limit; }
        void append(String value) {
            long count = value.getBytes(StandardCharsets.UTF_8).length;
            if (count > limit - bytes) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "図形の本文がMarkdownの上限を超えました。");
            text.append(value); bytes += count;
        }
    }
}
