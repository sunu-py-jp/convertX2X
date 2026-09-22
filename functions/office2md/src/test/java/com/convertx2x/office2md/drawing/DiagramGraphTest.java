package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagramGraphTest {
    private static DiagramGraph.Vertex vertex(String id, String source, String text) {
        return new DiagramGraph.Vertex(id, source, "長方形", text, text,
                DiagramMetadata.of("長方形", text, new Rectangle2D.Double(12, 34, 100, 50)));
    }

    @Test void directionsUseSavedMarkersRatherThanNodePositionOrNames() {
        var nodes = List.of(vertex("a", "1", "承認"), vertex("b", "2", "承認"));
        var edges = DiagramGraph.resolve(nodes, List.of(
                new DiagramGraph.Connection("line-1", "1", "2", "none", "triangle"),
                new DiagramGraph.Connection("line-2", "1", "2", "arrow", "none"),
                new DiagramGraph.Connection("line-3", "1", "2", "stealth", "triangle"),
                new DiagramGraph.Connection("line-4", "1", "2", "none", "none")));
        assertEquals(List.of("start-to-end", "end-to-start", "bidirectional", "undirected"),
                edges.stream().map(DiagramGraph.Edge::direction).toList());
        assertTrue(edges.stream().allMatch(edge -> edge.status().equals("resolved")));
        assertEquals("b", edges.get(1).metadata().get("fromId"));
        assertFalse(edges.get(2).metadata().containsKey("fromId"));
    }

    @Test void ovalAndDiamondRetainTheirMarkersWithoutAddingArrowDirectionsOrWarnings() throws Exception {
        var nodes = List.of(vertex("a", "1", "開始"), vertex("b", "2", "確認"));
        record Case(String start, String end, String direction) { }
        var cases = List.of(
                new Case("oval", "none", "undirected"),
                new Case("none", "oval", "undirected"),
                new Case("diamond", "none", "undirected"),
                new Case("none", "diamond", "undirected"),
                new Case("oval", "oval", "undirected"),
                new Case("diamond", "diamond", "undirected"),
                new Case("oval", "diamond", "undirected"),
                new Case("diamond", "oval", "undirected"),
                new Case("oval", "triangle", "start-to-end"),
                new Case("diamond", "stealth", "start-to-end"),
                new Case("diamond", "arrow", "start-to-end"),
                new Case("triangle", "diamond", "end-to-start"),
                new Case("stealth", "oval", "end-to-start"),
                new Case("arrow", "oval", "end-to-start"));
        for (var test : cases) {
            var edges = DiagramGraph.resolve(nodes, List.of(
                    new DiagramGraph.Connection("line", "1", "2", test.start(), test.end())));
            var edge = edges.getFirst();
            assertEquals(test.direction(), edge.direction(), test.toString());
            assertEquals("resolved", edge.status()); assertEquals("", edge.reason());
            var json = new ObjectMapper().valueToTree(edge.metadata());
            assertEquals(test.start(), json.path("startArrow").asText());
            assertEquals(test.end(), json.path("endArrow").asText());
            assertEquals("a", json.path("startId").asText()); assertEquals("b", json.path("endId").asText());
            String relation;
            if (test.direction().equals("undirected")) {
                assertFalse(json.has("fromId")); assertFalse(json.has("toId"));
                relation = "a「開始」 — b「確認」（向きなし）";
            } else if (test.direction().equals("start-to-end")) {
                assertEquals("a", json.path("fromId").asText()); assertEquals("b", json.path("toId").asText());
                relation = "a「開始」 → b「確認」";
            } else {
                assertEquals("b", json.path("fromId").asText()); assertEquals("a", json.path("toId").asText());
                relation = "b「確認」 → a「開始」";
            }
            try (var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
                String markdown = DiagramGraph.markdown(nodes, edges, workspace, "対象", "図-1");
                assertTrue(markdown.contains(relation), markdown);
                assertFalse(markdown.contains("接続関係不明")); assertEquals(0, workspace.warningCount());
            }
        }
    }

    @Test void missingHiddenDuplicateAndUnknownMarkersStayUnresolved() throws Exception {
        var nodes = List.of(vertex("a", "1", "開始"), vertex("b", "2", "確認"), vertex("c", "2", "同一ID"));
        var edges = DiagramGraph.resolve(nodes, List.of(
                new DiagramGraph.Connection("missing", null, "1", "diamond", "oval"),
                new DiagramGraph.Connection("hidden", "99", "1", "none", "triangle"),
                new DiagramGraph.Connection("duplicate", "2", "1", "none", "triangle"),
                new DiagramGraph.Connection("unknown-start", "1", "1", "future-decoration", "oval"),
                new DiagramGraph.Connection("unknown-end", "1", "1", "diamond", "future-decoration")));
        assertEquals(List.of("MISSING_ENDPOINT", "TARGET_UNAVAILABLE", "AMBIGUOUS_TARGET", "UNKNOWN_ARROWHEAD", "UNKNOWN_ARROWHEAD"),
                edges.stream().map(DiagramGraph.Edge::reason).toList());
        assertTrue(edges.stream().allMatch(edge -> edge.status().equals("unresolved")));
        assertEquals("undirected", edges.get(0).direction());
        assertEquals("diamond", edges.get(0).startArrow()); assertEquals("oval", edges.get(0).endArrow());
        assertEquals("unknown", edges.get(3).direction()); assertEquals("unknown", edges.get(4).direction());
        assertEquals("future-decoration", edges.get(3).metadata().get("startArrow"));
        assertEquals("future-decoration", edges.get(4).metadata().get("endArrow"));
        assertTrue(edges.stream().noneMatch(edge -> edge.metadata().containsKey("fromId")));
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            String markdown = DiagramGraph.markdown(nodes, edges, workspace, "対象", "図-1");
            assertEquals(5, markdown.split("接続関係不明", -1).length - 1);
            assertEquals(5, workspace.warningCount());
            assertFalse(markdown.contains(" → "));
        }
    }

    @Test void bodyRetainsLabelsAndFormattingAndReportRetainsPlainText() throws Exception {
        var a = new DiagramGraph.Vertex("shape-1", "1", "長方形", "申請\n内容", "**申請**\n[内容](https://example.com)",
                DiagramMetadata.of("長方形", "申請\n内容", null));
        var empty = vertex("shape-2", "2", "");
        var edges = DiagramGraph.resolve(List.of(a, empty), List.of(
                new DiagramGraph.Connection("connector-1", "1", "2", "none", "arrow")));
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            String markdown = DiagramGraph.markdown(List.of(a, empty), edges, workspace, "シート", "A1:H8");
            assertTrue(markdown.contains("**申請**<br>[内容](https://example.com)"));
            assertTrue(markdown.contains("shape-1「申請 内容」 → shape-2（文字なし）"));
            assertTrue(markdown.contains("shape-2（長方形）：文字なし"));
            workspace.block(a.metadata()); workspace.finishReport("sample.xlsx", "test");
            var json = new ObjectMapper().readTree(Files.readString(workspace.files().get("report.json")));
            assertEquals("申請\n内容", json.path("blocks").get(0).path("text").asText());
            assertTrue(json.path("blocks").get(0).path("x").isNull());
        }
    }

    @Test void metadataRoundTripsMarkdownPunctuationAndDoesNotInventUnknownCoordinates() throws Exception {
        String text = "[説明] \"引用\" \\パス\n**強調** <script>& `code`";
        var metadata = DiagramMetadata.of("図形", text, null);
        String alt = DiagramMetadata.imageAlt(metadata);
        var json = new ObjectMapper().readTree(alt);
        assertEquals(text, json.path("text").asText());
        assertTrue(json.path("x").isNull());
        assertTrue(json.path("width").isNull());
        assertFalse(alt.contains("[説明]"));
        assertFalse(alt.contains("<script>"));
        assertEquals(6, json.size());
        assertEquals("1.235", DiagramMetadata.of("図", "", new Rectangle2D.Double(1.2349, 0, 10, 20)).get("x").toString());
    }
}
