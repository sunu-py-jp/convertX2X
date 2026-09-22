package com.convertx2x.office2md.drawing;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.ExcelMarkdownService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;
import static org.junit.jupiter.api.Assertions.*;

class ExcelDiagramGraphTest {
    private final DrawingExtractor extractor = new DrawingExtractor();

    @Test void storedDirectionsAndCycleRemainMachineReadableInReportAfterWorkbookRoundTrip() throws Exception {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("業務フロー");
            sheet.createRow(0).createCell(0).setCellValue("フローの前の説明");
            sheet.createRow(30).createCell(0).setCellValue("フローの後の説明");
            var drawing = sheet.createDrawingPatriarch();
            var start = shape(drawing, 1, 3, "受付"); var end = shape(drawing, 7, 3, "承認");
            connector(drawing, start.getShapeId(), end.getShapeId(), "oval", "triangle");
            connector(drawing, start.getShapeId(), end.getShapeId(), "triangle", "diamond");
            connector(drawing, start.getShapeId(), end.getShapeId(), "triangle", "triangle");
            connector(drawing, start.getShapeId(), end.getShapeId(), "diamond", "oval");
            var bytes = new ByteArrayOutputStream(); book.write(bytes);
            try (var result = new ExcelMarkdownService(ConversionLimits.defaults()).convert(bytes.toByteArray(), "workflow.xlsx")) {
                String markdown = Files.readString(result.files().get("document.md"));
                var report = new ObjectMapper().readTree(Files.readString(result.files().get("report.json")));
                var block = report.get("blocks").findValues("nodes");
                assertEquals(1, block.size()); assertEquals(2, block.getFirst().size());
                var edges = report.get("blocks").findValues("edges").getFirst();
                assertEquals(List.of("start-to-end", "end-to-start", "bidirectional", "undirected"),
                        java.util.stream.StreamSupport.stream(edges.spliterator(), false).map(edge -> edge.get("direction").asText()).toList());
                assertEquals("shape-" + start.getShapeId(), edges.get(0).get("fromId").asText());
                assertEquals("shape-" + start.getShapeId(), edges.get(1).get("toId").asText());
                for (var edge : edges) {
                    assertEquals("resolved", edge.path("status").asText()); assertEquals("", edge.path("reason").asText());
                }
                assertEquals("oval", edges.get(0).path("startArrow").asText());
                assertEquals("diamond", edges.get(1).path("endArrow").asText());
                assertEquals("diamond", edges.get(3).path("startArrow").asText());
                assertEquals("oval", edges.get(3).path("endArrow").asText());
                assertFalse(edges.get(3).has("fromId")); assertFalse(edges.get(3).has("toId"));
                assertTrue(markdown.contains("受付」 →")); assertTrue(markdown.contains("承認」 →")); assertTrue(markdown.contains(" ↔ "));
                assertTrue(markdown.contains("（向きなし）")); assertFalse(markdown.contains("接続関係不明"));
                assertFalse(report.path("warnings").toString().contains("DIAGRAM_CONNECTION_UNRESOLVED"));
                assertEquals(1, markdown.lines().filter(line -> line.startsWith("![")).count());
                assertTrue(markdown.indexOf("フローの前の説明") < markdown.indexOf("図中の項目"));
                assertTrue(markdown.indexOf("images/diagram-") < markdown.indexOf("フローの後の説明"));
            }
        }
    }

    @Test void looseConnectorDoesNotInferEndpointsOrConsumeNearbyLabel() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var drawing = book.createSheet("不明").createDrawingPatriarch();
            shape(drawing, 1, 3, "受付"); shape(drawing, 7, 3, "承認");
            connector(drawing, null, null, "oval", "diamond");
            var blocks = extractor.extract(book.getSheetAt(0), workspace);
            assertEquals(3, blocks.size()); assertEquals(3, workspace.files().size());
            var connector = blocks.stream().filter(block -> !edges(block).isEmpty()).findFirst().orElseThrow();
            var edge = edges(connector).getFirst();
            assertEquals("unresolved", edge.get("status")); assertEquals("MISSING_ENDPOINT", edge.get("reason"));
            assertEquals("undirected", edge.get("direction"));
            assertEquals("oval", edge.get("startArrow")); assertEquals("diamond", edge.get("endArrow"));
            assertFalse(edge.containsKey("startId")); assertFalse(edge.containsKey("endId"));
            assertTrue(connector.markdown().contains("接続関係不明"));
            assertFalse(connector.markdown().contains("受付")); assertFalse(connector.markdown().contains("承認"));
        }
    }

    @Test void hiddenTargetAndDuplicateNativeIdsCannotResolve() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var drawing = book.createSheet("曖昧").createDrawingPatriarch();
            var first = shape(drawing, 1, 3, "一つめ"); var duplicate = shape(drawing, 7, 3, "二つめ");
            duplicate.getCTShape().getNvSpPr().getCNvPr().setId(first.getShapeId());
            var hidden = shape(drawing, 1, 20, "HIDDEN_GRAPH_SECRET");
            hidden.getCTShape().getNvSpPr().getCNvPr().setHidden(true);
            connector(drawing, first.getShapeId(), hidden.getShapeId(), "none", "triangle");
            var blocks = extractor.extract(book.getSheetAt(0), workspace);
            var nodes = blocks.stream().flatMap(block -> nodes(block).stream()).toList();
            assertEquals(2, nodes.stream().map(node -> node.get("id")).distinct().count());
            var edge = blocks.stream().flatMap(block -> edges(block).stream()).findFirst().orElseThrow();
            assertEquals("AMBIGUOUS_TARGET", edge.get("reason"));
            assertFalse(edge.containsKey("startId")); assertFalse(edge.containsKey("endId"));
            assertFalse(blocks.toString().contains("HIDDEN_GRAPH_SECRET"));
        }
    }

    @Test void explicitGroupAndSavedOutsideConnectionFormSinglePreview() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var sheet = book.createSheet("グループ間"); var drawing = sheet.createDrawingPatriarch();
            var group = drawing.createGroup(anchor(0, 3, 4, 12)); group.setCoordinates(0, 0, 100 * 12700, 100 * 12700);
            var a = group.createSimpleShape(new XSSFChildAnchor(0, 0, 40 * 12700, 40 * 12700)); a.setShapeType(ShapeTypes.RECT); a.setText("組織内受付"); a.getCTShape().getNvSpPr().getCNvPr().setId(101);
            var b = group.createSimpleShape(new XSSFChildAnchor(60 * 12700, 60 * 12700, 100 * 12700, 100 * 12700)); b.setShapeType(ShapeTypes.RECT); b.setText("独立した注記"); b.getCTShape().getNvSpPr().getCNvPr().setId(102);
            var outside = shape(drawing, 7, 3, "組織外承認");
            connector(drawing, a.getShapeId(), outside.getShapeId(), "none", "triangle");
            var blocks = extractor.extract(sheet, workspace);
            assertEquals(1, blocks.size()); assertEquals(1, workspace.files().size());
            assertEquals(3, nodes(blocks.getFirst()).size());
            assertEquals(1, edges(blocks.getFirst()).size());
            assertTrue(blocks.getFirst().markdown().contains("独立した注記"));
        }
    }

    @Test void nativeBoldAndSafeLinkSurviveWithoutFetchingExternalContent() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var drawing = book.createSheet("文字").createDrawingPatriarch(); var shape = shape(drawing, 1, 3, ""); shape.clearText();
            var paragraph = shape.addNewTextParagraph();
            var strike = paragraph.addNewTextRun(); strike.setText("STRIKE_GRAPH_SECRET"); strike.setStrikethrough(true);
            var link = paragraph.addNewTextRun(); link.setText("承認マニュアル"); link.setBold(true);
            String address = "http://127.0.0.1:" + server.getAddress().getPort() + "/manual";
            var rel = drawing.getPackagePart().addExternalRelationship(address,
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink");
            link.getXmlObject().getRPr().addNewHlinkClick().setId(rel.getId());
            var block = extractor.extract(book.getSheetAt(0), workspace).getFirst();
            assertTrue(block.markdown().contains("[**承認マニュアル**](" + address + ")"), block.markdown());
            assertEquals("承認マニュアル", nodes(block).getFirst().get("text"));
            assertFalse(block.toString().contains("STRIKE_GRAPH_SECRET")); assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void connectorOwnedNativeTextAndCoordinatesRemainInBodyAndEdgeMetadata() throws Exception {
        try (var book = new XSSFWorkbook(); var workspace = workspace()) {
            var drawing = book.createSheet("線の文字").createDrawingPatriarch();
            var line = shape(drawing, 1, 3, ""); line.setShapeType(ShapeTypes.LINE); line.clearText();
            var paragraph = line.addNewTextParagraph();
            var removed = paragraph.addNewTextRun(); removed.setText("REMOVED_CONNECTOR_TEXT"); removed.setStrikethrough(true);
            var visible = paragraph.addNewTextRun(); visible.setText("条件付き承認"); visible.setBold(true);
            var block = extractor.extract(book.getSheetAt(0), workspace).getFirst();
            assertTrue(block.markdown().contains("の文字：**条件付き承認**"));
            var edge = edges(block).getFirst();
            assertEquals("条件付き承認", edge.get("text")); assertNotNull(edge.get("x"));
            assertEquals("unresolved", edge.get("status"));
            assertFalse(block.toString().contains("REMOVED_CONNECTOR_TEXT"));
        }
    }

    @Test void legacyXlsLineIsUnresolvedInsteadOfInventingConnection() throws Exception {
        try (var book = new HSSFWorkbook(); var workspace = workspace()) {
            var drawing = book.createSheet("旧形式").createDrawingPatriarch();
            var line = drawing.createSimpleShape(new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 3, (short) 7, 3));
            line.setShapeType(HSSFShapeTypes.Line);
            var block = extractor.extract(book.getSheetAt(0), workspace).getFirst();
            assertEquals("unresolved", edges(block).getFirst().get("status"));
            assertTrue(block.markdown().contains("接続関係不明"));
            workspace.finishReport("legacy.xls", "hash");
            assertTrue(Files.readString(workspace.files().get("report.json")).contains("XLS_CONNECTION_UNSUPPORTED"));
        }
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> nodes(DrawingBlock block) { return (List<Map<String, Object>>) block.metadata().get("nodes"); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> edges(DrawingBlock block) { return (List<Map<String, Object>>) block.metadata().get("edges"); }
    private static XSSFSimpleShape shape(XSSFDrawing drawing, int col, int row, String text) {
        var shape = drawing.createSimpleShape(anchor(col, row, col + 2, row + 4)); shape.setShapeType(ShapeTypes.RECT); shape.setText(text); return shape;
    }
    private static XSSFConnector connector(XSSFDrawing drawing, Number start, Number end, String head, String tail) {
        var shape = drawing.createConnector(anchor(3, 5, 7, 5)); shape.setShapeType(ShapeTypes.LINE);
        shape.getCTConnector().getNvCxnSpPr().getCNvPr().setId(1000 + drawing.getShapes().size());
        var properties = shape.getCTConnector().getNvCxnSpPr().getCNvCxnSpPr();
        if (start != null) properties.addNewStCxn().setId(start.longValue()); if (end != null) properties.addNewEndCxn().setId(end.longValue());
        var style = shape.getCTConnector().getSpPr(); var line = style.isSetLn() ? style.getLn() : style.addNewLn();
        line.addNewHeadEnd().setType(STLineEndType.Enum.forString(head)); line.addNewTailEnd().setType(STLineEndType.Enum.forString(tail));
        return shape;
    }
    private static XSSFClientAnchor anchor(int col, int row, int endCol, int endRow) { return new XSSFClientAnchor(0, 0, 0, 0, col, row, endCol, endRow); }
    private static ConversionWorkspace workspace() { return new ConversionWorkspace(ConversionLimits.defaults()); }
}
