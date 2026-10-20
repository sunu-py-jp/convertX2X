package com.convertx2x.office2md.drawing;

import java.awt.geom.PathIterator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DrawingPresetGeometryTest {
    @Test void everyAdvertisedBundledPresetHasFiniteDefaultGeometry() {
        for (String preset : org.apache.poi.sl.draw.geom.PresetGeometries.getInstance().keySet())
            if (DrawingPresetGeometry.supports(preset))
                assertNotNull(DrawingPresetGeometry.create(preset, 160, 90), preset);
    }

    @Test void diamondAndFlowchartDecisionKeepTheirRealOutlineInsteadOfBoundingRectangle() {
        for (String preset : new String[]{"diamond", "flowChartDecision"}) {
            var geometry = DrawingPresetGeometry.create(preset, 120, 80);
            assertNotNull(geometry, preset);
            assertTrue(geometry.outline().contains(60, 40), preset);
            assertFalse(geometry.outline().contains(5, 5), "Empty corner of " + preset);
            assertEquals(120, geometry.outline().getBounds2D().getWidth(), 1e-6);
            assertEquals(80, geometry.outline().getBounds2D().getHeight(), 1e-6);
            assertNull(geometry.start()); assertNull(geometry.end());
        }
    }

    @Test void flowchartAndOrdinaryPresetFamiliesAreAvailableOffline() {
        for (String preset : new String[]{"hexagon", "triangle", "parallelogram", "chevron",
                "flowChartProcess", "flowChartInputOutput", "flowChartDocument",
                "flowChartMagneticDisk", "flowChartTerminator", "flowChartPredefinedProcess",
                "cloud", "star5", "wedgeRoundRectCallout", "bracePair", "arc", "circularArrow"}) {
            assertTrue(DrawingPresetGeometry.supports(preset), preset);
            var geometry = DrawingPresetGeometry.create(preset, 140, 90);
            assertNotNull(geometry, preset);
            assertFalse(geometry.parts().isEmpty(), preset);
            assertTrue(geometry.parts().stream().anyMatch(part -> part.filled() || part.stroked()), preset);
        }
    }

    @Test void bentConnectorHasAnElbowAndHorizontalEndpointsRatherThanADiagonalLine() {
        var geometry = DrawingPresetGeometry.create("bentConnector3", 100, 80);
        assertNotNull(geometry); assertTrue(DrawingPresetGeometry.connector("bentConnector3"));
        assertFalse(geometry.parts().getFirst().filled());
        assertEquals(0, geometry.start().tip().getX(), 1e-6);
        assertEquals(0, geometry.start().tip().getY(), 1e-6);
        assertEquals(0, geometry.start().from().getY(), 1e-6);
        assertEquals(100, geometry.end().tip().getX(), 1e-6);
        assertEquals(80, geometry.end().tip().getY(), 1e-6);
        assertEquals(80, geometry.end().from().getY(), 1e-6);
        assertTrue(commandCount(geometry, PathIterator.SEG_LINETO) >= 3);
    }

    @Test void curvedConnectorKeepsCurveCommandsAndArrowTangents() {
        var geometry = DrawingPresetGeometry.create("curvedConnector3", 120, 90);
        assertNotNull(geometry); assertTrue(DrawingPresetGeometry.connector("curvedConnector3"));
        assertTrue(commandCount(geometry, PathIterator.SEG_CUBICTO) > 0);
        assertEquals(0, geometry.start().tip().getX(), 1e-6);
        assertEquals(0, geometry.start().tip().getY(), 1e-6);
        assertEquals(120, geometry.end().tip().getX(), 1e-6);
        assertEquals(90, geometry.end().tip().getY(), 1e-6);
        assertTrue(geometry.start().from().getX() > geometry.start().tip().getX());
        assertTrue(geometry.end().from().getX() < geometry.end().tip().getX());
    }

    @Test void numericAdjustmentsMoveSavedConnectorElbow() {
        var geometry = DrawingPresetGeometry.create("bentConnector3", 100, 80, Map.of("adj1", 75000d));
        assertNotNull(geometry);
        PathIterator iterator = geometry.outline().getPathIterator(null);
        double[] coords = new double[6]; iterator.next();
        assertEquals(PathIterator.SEG_LINETO, iterator.currentSegment(coords));
        assertEquals(75, coords[0], 1e-6);
    }

    @Test void zeroHeightAndZeroWidthStraightConnectorsRetainEndpoints() {
        var horizontal = DrawingPresetGeometry.create("line", 120, 0);
        var vertical = DrawingPresetGeometry.create("straightConnector1", 0, 80);
        assertNotNull(horizontal); assertNotNull(vertical);
        assertEquals(120, horizontal.end().tip().getX(), 1e-6);
        assertEquals(0, horizontal.end().tip().getY(), 1e-6);
        assertEquals(0, vertical.end().tip().getX(), 1e-6);
        assertEquals(80, vertical.end().tip().getY(), 1e-6);
    }

    @Test void invalidOrUnrequestedGeometryDoesNotRunArbitraryFormulaCode() {
        assertFalse(DrawingPresetGeometry.supports("textWave1"));
        assertFalse(DrawingPresetGeometry.supports("custom"));
        assertNull(DrawingPresetGeometry.create("unknown", 100, 50));
        assertNull(DrawingPresetGeometry.create("diamond", Double.NaN, 50));
        assertNull(DrawingPresetGeometry.create("diamond", 100, Double.POSITIVE_INFINITY));
        assertNull(DrawingPresetGeometry.create("diamond", -1, 50));
        assertNull(DrawingPresetGeometry.create("diamond", 0, 0));
        assertNull(DrawingPresetGeometry.create("bentConnector3", 100, 50, Map.of("adj1", Double.NaN)));
        assertNull(DrawingPresetGeometry.create("bentConnector3", 100, 50, Map.of("adj1", 1e20)));
    }

    private static long commandCount(DrawingPresetGeometry.Geometry geometry, int target) {
        var iterator = geometry.outline().getPathIterator(null);
        double[] coords = new double[6]; long count = 0;
        while (!iterator.isDone()) { if (iterator.currentSegment(coords) == target) count++; iterator.next(); }
        return count;
    }
}
