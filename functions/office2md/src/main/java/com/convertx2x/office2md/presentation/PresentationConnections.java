package com.convertx2x.office2md.presentation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTConnection;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineEndProperties;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;
import org.w3c.dom.Element;

/** Extracts only saved connector relationships; geometry and nearby labels never imply an edge. */
final class PresentationConnections {
    private static final Set<String> ARROWS = Set.of("triangle", "stealth", "arrow");
    private static final Set<String> NON_DIRECTIONAL_MARKERS = Set.of("none", "oval", "diamond");

    private PresentationConnections() { }

    /**
     * The caller supplies the already filtered, visible leaves of ONE slide, including group leaves.
     * Targets not in that set are intentionally unavailable, even if present elsewhere in the file.
     */
    static List<Edge> extract(List<XSLFShape> shapes) {
        Map<Long, List<XSLFShape>> targets = new HashMap<>();
        for (XSLFShape shape : shapes) {
            if (!(shape instanceof XSLFConnectorShape) && !(shape instanceof XSLFGroupShape)) {
                targets.computeIfAbsent(Integer.toUnsignedLong(shape.getShapeId()), ignored -> new ArrayList<>()).add(shape);
            }
        }

        List<Edge> edges = new ArrayList<>();
        for (XSLFShape shape : shapes) {
            if (!(shape instanceof XSLFConnectorShape connector)) continue;
            CTConnector xml = (CTConnector) connector.getXmlObject();
            var nonVisual = xml.getNvCxnSpPr();
            var properties = nonVisual == null ? null : nonVisual.getCNvCxnSpPr();
            Endpoint start = endpoint(properties == null ? null : properties.getStCxn(), targets);
            Endpoint end = endpoint(properties == null ? null : properties.getEndCxn(), targets);
            var line = xml.getSpPr() == null ? null : xml.getSpPr().getLn();
            // DrawingML headEnd decorates stCxn (the first point); tailEnd decorates endCxn.
            // Oval/diamond ornaments are preserved but are not interpreted as semantic arrows.
            String startArrow = marker(line == null ? null : line.getHeadEnd());
            String endArrow = marker(line == null ? null : line.getTailEnd());
            String direction = direction(startArrow, endArrow);
            String reason = !start.reason().isEmpty() ? start.reason()
                    : !end.reason().isEmpty() ? end.reason()
                    : direction.equals("unknown") ? "UNKNOWN_ARROWHEAD" : "";
            edges.add(new Edge(id(shape), start.id(), end.id(), startArrow, endArrow,
                    direction, reason.isEmpty() ? "resolved" : "unresolved", reason));
        }
        return List.copyOf(edges);
    }

    private static Endpoint endpoint(CTConnection connection, Map<Long, List<XSLFShape>> targets) {
        if (connection == null || connection.xgetId() == null) return new Endpoint(null, "MISSING_ENDPOINT");
        long targetId;
        try {
            targetId = Long.parseLong(((Element) connection.getDomNode()).getAttribute("id"));
        } catch (NumberFormatException malformed) {
            return new Endpoint(null, "MISSING_ENDPOINT");
        }
        List<XSLFShape> matches = targets.get(targetId);
        if (matches == null || matches.isEmpty()) return new Endpoint(null, "TARGET_UNAVAILABLE");
        if (matches.size() != 1) return new Endpoint(null, "AMBIGUOUS_TARGET");
        return new Endpoint(id(matches.getFirst()), "");
    }

    private static String marker(CTLineEndProperties end) {
        if (end == null) return "none";
        Element element = (Element) end.getDomNode();
        return element.hasAttribute("type") ? element.getAttribute("type") : "none";
    }

    private static String direction(String start, String end) {
        if ((!NON_DIRECTIONAL_MARKERS.contains(start) && !ARROWS.contains(start))
                || (!NON_DIRECTIONAL_MARKERS.contains(end) && !ARROWS.contains(end))) return "unknown";
        if (ARROWS.contains(start)) return ARROWS.contains(end) ? "bidirectional" : "end-to-start";
        return ARROWS.contains(end) ? "start-to-end" : "undirected";
    }

    private static String id(XSLFShape shape) { return "shape-" + shape.getShapeId(); }

    private record Endpoint(String id, String reason) { }

    record Edge(String id, String startId, String endId, String startArrow, String endArrow,
                String direction, String status, String reason) {
        Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", id);
            if (startId != null) metadata.put("startId", startId);
            if (endId != null) metadata.put("endId", endId);
            metadata.put("startArrow", startArrow);
            metadata.put("endArrow", endArrow);
            metadata.put("direction", direction);
            metadata.put("status", status);
            metadata.put("reason", reason);
            if (status.equals("resolved") && direction.equals("start-to-end")) {
                metadata.put("fromId", startId);
                metadata.put("toId", endId);
            } else if (status.equals("resolved") && direction.equals("end-to-start")) {
                metadata.put("fromId", endId);
                metadata.put("toId", startId);
            }
            return metadata;
        }
    }
}
