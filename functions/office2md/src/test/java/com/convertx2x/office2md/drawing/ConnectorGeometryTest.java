package com.convertx2x.office2md.drawing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConnectorGeometryTest {
    @Test void resolvesOnlyOneExactBoundaryContact() {
        Map<String, Rectangle2D> targets = Map.of(
                "left", new Rectangle2D.Double(10, 10, 100, 60),
                "right", new Rectangle2D.Double(200, 10, 100, 60));

        assertEquals("left", ConnectorGeometry.uniqueBoundaryContact(new Point2D.Double(110, 40), targets));
        assertNull(ConnectorGeometry.uniqueBoundaryContact(new Point2D.Double(100, 40), targets),
                "A line endpoint inside a shape is not a snapped connection");
        assertNull(ConnectorGeometry.uniqueBoundaryContact(new Point2D.Double(115, 40), targets),
                "Nearby geometry is not enough to infer a connection");
    }

    @Test void overlappingBoundariesRemainAmbiguous() {
        Map<String, Rectangle2D> targets = new LinkedHashMap<>();
        targets.put("first", new Rectangle2D.Double(10, 10, 100, 60));
        targets.put("second", new Rectangle2D.Double(110, 20, 100, 40));
        assertNull(ConnectorGeometry.uniqueBoundaryContact(new Point2D.Double(110, 40), targets));
    }
}
