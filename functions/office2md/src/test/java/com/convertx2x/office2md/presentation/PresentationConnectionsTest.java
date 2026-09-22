package com.convertx2x.office2md.presentation;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.sl.usermodel.LineDecoration.DecorationShape;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PresentationConnectionsTest {
    @Test void directionsFollowSavedArrowheadsRatherThanGeometry() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), end = box(slide);
            // Reverse positions and flips cannot reverse the connector's saved endpoint IDs.
            start.setAnchor(new Rectangle2D.Double(300, 30, 100, 60));
            end.setAnchor(new Rectangle2D.Double(10, 200, 100, 60));
            record Case(DecorationShape head, DecorationShape tail, String direction) { }
            for (Case test : List.of(
                    new Case(DecorationShape.NONE, DecorationShape.TRIANGLE, "start-to-end"),
                    new Case(DecorationShape.STEALTH, DecorationShape.NONE, "end-to-start"),
                    new Case(DecorationShape.ARROW, DecorationShape.TRIANGLE, "bidirectional"),
                    new Case(DecorationShape.NONE, DecorationShape.NONE, "undirected"))) {
                XSLFConnectorShape line = connect(slide.createConnector(), start, end);
                line.setFlipHorizontal(true); line.setRotation(90);
                line.setLineHeadDecoration(test.head()); line.setLineTailDecoration(test.tail());
                var edge = only(start, end, line);
                assertEquals("shape-" + line.getShapeId(), edge.id());
                assertEquals(id(start), edge.startId()); assertEquals(id(end), edge.endId());
                assertEquals(test.direction(), edge.direction());
                assertEquals("resolved", edge.status()); assertEquals("", edge.reason());
                assertEquals(test.head().name().toLowerCase(java.util.Locale.ROOT), edge.startArrow());
                assertEquals(test.tail().name().toLowerCase(java.util.Locale.ROOT), edge.endArrow());
                if (test.direction().equals("start-to-end")) {
                    assertEquals(id(start), edge.metadata().get("fromId"));
                    assertEquals(id(end), edge.metadata().get("toId"));
                } else if (test.direction().equals("end-to-start")) {
                    assertEquals(id(end), edge.metadata().get("fromId"));
                    assertEquals(id(start), edge.metadata().get("toId"));
                } else {
                    assertFalse(edge.metadata().containsKey("fromId"));
                    assertFalse(edge.metadata().containsKey("toId"));
                }
            }
        }
    }

    @Test void missingEndpointsAreNotGuessedFromTouchingGeometry() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), end = box(slide);
            start.setAnchor(new Rectangle2D.Double(0, 0, 100, 100));
            end.setAnchor(new Rectangle2D.Double(200, 0, 100, 100));
            XSLFConnectorShape line = slide.createConnector();
            line.setAnchor(new Rectangle2D.Double(100, 50, 100, 0));
            line.setLineTailDecoration(DecorationShape.TRIANGLE);
            var edge = only(start, end, line);
            assertEquals("unresolved", edge.status()); assertEquals("MISSING_ENDPOINT", edge.reason());
            assertNull(edge.startId()); assertNull(edge.endId());
            assertFalse(edge.metadata().containsKey("startId")); assertFalse(edge.metadata().containsKey("endId"));
            assertFalse(edge.metadata().containsKey("fromId")); assertFalse(edge.metadata().containsValue(null));
            var st = xml(line).getNvCxnSpPr().getCNvCxnSpPr().addNewStCxn();
            st.setId(start.getShapeId()); st.setIdx(0);
            edge = only(start, end, line);
            assertEquals(id(start), edge.startId()); assertNull(edge.endId());
            assertEquals("MISSING_ENDPOINT", edge.reason());
        }
    }

    @Test void staleAndExcludedTargetsRemainUnavailable() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), hidden = box(slide);
            ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
            XSLFConnectorShape line = connect(slide.createConnector(), start, hidden);
            line.setLineTailDecoration(DecorationShape.ARROW);
            // Hidden/excluded shapes are removed by the converter before this helper is called.
            var edge = only(start, line);
            assertEquals("TARGET_UNAVAILABLE", edge.reason());
            assertEquals(id(start), edge.startId()); assertNull(edge.endId());
            xml(line).getNvCxnSpPr().getCNvCxnSpPr().getEndCxn().setId(9999);
            edge = only(start, line);
            assertEquals("TARGET_UNAVAILABLE", edge.reason());
            assertNull(edge.endId());
            assertFalse(edge.metadata().containsKey("fromId"));
            assertTrue(PresentationConnections.extract(List.of(start)).isEmpty(), "An excluded connector cannot create an edge");
        }
    }

    @Test void duplicateTargetIdsAreAmbiguousRatherThanArbitrarilySelected() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), end = box(slide), duplicate = box(slide);
            ((CTShape) duplicate.getXmlObject()).getNvSpPr().getCNvPr().setId(end.getShapeId());
            XSLFConnectorShape line = connect(slide.createConnector(), start, end);
            var edge = only(start, end, duplicate, line);
            assertEquals("AMBIGUOUS_TARGET", edge.reason());
            assertEquals(id(start), edge.startId()); assertNull(edge.endId());
            assertEquals("unresolved", edge.status());
            assertEquals("resolved", only(start, end, line).status(), "Only supplied leaves participate in ID resolution");
        }
    }

    @Test void ornamentalAndUnknownMarkersDoNotBecomeSemanticArrows() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), end = box(slide);
            XSLFConnectorShape line = connect(slide.createConnector(), start, end);
            line.setLineTailDecoration(DecorationShape.TRIANGLE);
            for (DecorationShape ornament : List.of(DecorationShape.OVAL, DecorationShape.DIAMOND)) {
                line.setLineHeadDecoration(ornament);
                var edge = only(start, end, line);
                assertEquals("unknown", edge.direction()); assertEquals("UNKNOWN_ARROWHEAD", edge.reason());
                assertEquals("unresolved", edge.status());
                assertEquals(id(start), edge.startId()); assertEquals(id(end), edge.endId());
                assertFalse(edge.metadata().containsKey("fromId"));
            }
            ((Element) xml(line).getSpPr().getLn().getHeadEnd().getDomNode()).setAttribute("type", "future-decoration");
            var edge = only(start, end, line);
            assertEquals("future-decoration", edge.startArrow());
            assertEquals("UNKNOWN_ARROWHEAD", edge.reason());
        }
    }

    @Test void cyclesAndSelfLoopsAreRecordedWithoutInventingAnExecutionOrder() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape first = box(slide), second = box(slide);
            XSLFConnectorShape forward = connect(slide.createConnector(), first, second);
            XSLFConnectorShape back = connect(slide.createConnector(), second, first);
            XSLFConnectorShape self = connect(slide.createConnector(), second, second);
            for (XSLFConnectorShape line : List.of(forward, back, self)) line.setLineTailDecoration(DecorationShape.TRIANGLE);
            var edges = PresentationConnections.extract(List.of(first, second, forward, back, self));
            assertEquals(3, edges.size());
            assertTrue(edges.stream().allMatch(edge -> edge.status().equals("resolved")));
            assertEquals(id(first), edges.get(0).metadata().get("fromId"));
            assertEquals(id(first), edges.get(1).metadata().get("toId"));
            assertEquals(edges.get(2).startId(), edges.get(2).endId());
            assertEquals(edges, PresentationConnections.extract(List.of(first, second, forward, back, self)));
        }
    }

    @Test void groupLeafReferencesSurvivePptxRoundTripAndUseSlideScopedIds() throws Exception {
        byte[] bytes;
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFGroupShape group = slide.createGroup();
            group.setAnchor(new Rectangle2D.Double(100, 100, 400, 200));
            group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 200, 100));
            XSLFAutoShape grouped = group.createAutoShape(); grouped.setShapeType(ShapeType.RECT);
            XSLFAutoShape outside = box(slide);
            XSLFConnectorShape connector = connect(group.createConnector(), grouped, outside);
            connector.setLineTailDecoration(DecorationShape.STEALTH);
            var out = new ByteArrayOutputStream(); deck.write(out); bytes = out.toByteArray();
        }
        try (XMLSlideShow reopened = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            XSLFSlide slide = reopened.getSlides().getFirst();
            XSLFGroupShape group = (XSLFGroupShape) slide.getShapes().getFirst();
            List<XSLFShape> leaves = new ArrayList<>(group.getShapes());
            XSLFShape outside = slide.getShapes().get(1); leaves.add(outside);
            var edge = PresentationConnections.extract(leaves).getFirst();
            assertEquals("resolved", edge.status()); assertEquals("start-to-end", edge.direction());
            assertEquals(id(group.getShapes().getFirst()), edge.startId()); assertEquals(id(outside), edge.endId());
            assertFalse(edge.startId().equals(id(group)), "The group is not substituted for its referenced leaf");
        }
    }

    @Test void onlyConnectorsAreEdgesAndOnlyNonconnectorLeavesAreTargets() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), simpleLine = box(slide);
            simpleLine.setShapeType(ShapeType.LINE); simpleLine.setLineTailDecoration(DecorationShape.TRIANGLE);
            assertTrue(PresentationConnections.extract(List.of(start, simpleLine)).isEmpty());
            XSLFGroupShape group = slide.createGroup();
            XSLFConnectorShape target = slide.createConnector();
            XSLFConnectorShape line = connect(slide.createConnector(), start, target);
            assertEquals("TARGET_UNAVAILABLE", only(start, target, line).reason());
            xml(line).getNvCxnSpPr().getCNvCxnSpPr().getEndCxn().setId(group.getShapeId());
            assertEquals("TARGET_UNAVAILABLE", only(start, group, line).reason());
        }
    }

    @Test void absentArrowPropertiesMeanUndirectedAndMalformedEndpointStaysUnresolved() throws Exception {
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide slide = deck.createSlide();
            XSLFAutoShape start = box(slide), end = box(slide);
            XSLFConnectorShape line = connect(slide.createConnector(), start, end);
            var edge = only(start, end, line);
            assertEquals("none", edge.startArrow()); assertEquals("none", edge.endArrow());
            assertEquals("undirected", edge.direction()); assertEquals("resolved", edge.status());
            ((Element) xml(line).getNvCxnSpPr().getCNvCxnSpPr().getEndCxn().getDomNode()).setAttribute("id", "not-an-id");
            edge = only(start, end, line);
            assertNull(edge.endId()); assertEquals("MISSING_ENDPOINT", edge.reason());
        }
    }

    private static XSLFAutoShape box(XSLFSlide slide) {
        XSLFAutoShape shape = slide.createAutoShape(); shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(0, 0, 100, 60)); return shape;
    }

    private static XSLFConnectorShape connect(XSLFConnectorShape line, XSLFShape start, XSLFShape end) {
        var properties = xml(line).getNvCxnSpPr().getCNvCxnSpPr();
        var from = properties.addNewStCxn(); from.setId(start.getShapeId()); from.setIdx(0);
        var to = properties.addNewEndCxn(); to.setId(end.getShapeId()); to.setIdx(0);
        line.setAnchor(new Rectangle2D.Double(100, 30, 100, 0)); return line;
    }

    private static PresentationConnections.Edge only(XSLFShape... shapes) {
        return PresentationConnections.extract(List.of(shapes)).getLast();
    }

    private static CTConnector xml(XSLFConnectorShape line) { return (CTConnector) line.getXmlObject(); }
    private static String id(XSLFShape shape) { return "shape-" + shape.getShapeId(); }
}
