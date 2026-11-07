package com.maqan.galacticnavigator.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldForgeMapGeometryTest {
    @Test
    void clipsPolygonToViewportWithoutChangingItsInteriorShape() {
        List<WorldForgeMapGeometry.Point> polygon = List.of(
                new WorldForgeMapGeometry.Point(-5, 2),
                new WorldForgeMapGeometry.Point(8, 2),
                new WorldForgeMapGeometry.Point(8, 8),
                new WorldForgeMapGeometry.Point(-5, 8));

        List<WorldForgeMapGeometry.Point> clipped =
                WorldForgeMapGeometry.clipPolygon(polygon, 0, 0, 10, 10);

        assertEquals(4, clipped.size());
        assertTrue(clipped.stream().allMatch(p ->
                p.x() >= 0 && p.x() <= 10 && p.y() >= 0 && p.y() <= 10));
        assertEquals(0.0, clipped.get(0).x(), 1.0e-9);
        assertEquals(8.0, clipped.get(1).x(), 1.0e-9);
    }

    @Test
    void scanlineFillProducesOnlyViewportBoundedSpansForConcavePolygon() {
        List<WorldForgeMapGeometry.Point> polygon = List.of(
                new WorldForgeMapGeometry.Point(1, 1),
                new WorldForgeMapGeometry.Point(9, 1),
                new WorldForgeMapGeometry.Point(9, 9),
                new WorldForgeMapGeometry.Point(6, 9),
                new WorldForgeMapGeometry.Point(6, 4),
                new WorldForgeMapGeometry.Point(4, 4),
                new WorldForgeMapGeometry.Point(4, 9),
                new WorldForgeMapGeometry.Point(1, 9));

        List<WorldForgeMapGeometry.Span> spans =
                WorldForgeMapGeometry.scanlineSpans(polygon, 0, 0, 10, 10);

        assertFalse(spans.isEmpty());
        assertTrue(spans.stream().allMatch(s ->
                s.y() >= 0 && s.y() < 10 && s.xStart() >= 0
                        && s.xEndExclusive() <= 10 && s.xEndExclusive() > s.xStart()));
        assertTrue(spans.stream().anyMatch(s -> s.y() == 5 && s.xStart() < 4));
        assertTrue(spans.stream().anyMatch(s -> s.y() == 5 && s.xStart() >= 6));
    }

    @Test
    void centroidUsesPolygonAreaRatherThanFirstVertex() {
        List<WorldForgeMapGeometry.Point> polygon = List.of(
                new WorldForgeMapGeometry.Point(0, 0),
                new WorldForgeMapGeometry.Point(8, 0),
                new WorldForgeMapGeometry.Point(8, 4),
                new WorldForgeMapGeometry.Point(0, 4));

        WorldForgeMapGeometry.Point center = WorldForgeMapGeometry.centroid(polygon);

        assertEquals(4.0, center.x(), 1.0e-9);
        assertEquals(2.0, center.y(), 1.0e-9);
    }

    @Test
    void labelPointStaysInsideConcaveTerritoryInsteadOfUsingOutsideCentroid() {
        List<WorldForgeMapGeometry.Point> uShape = List.of(
                new WorldForgeMapGeometry.Point(1, 1),
                new WorldForgeMapGeometry.Point(9, 1),
                new WorldForgeMapGeometry.Point(9, 9),
                new WorldForgeMapGeometry.Point(6, 9),
                new WorldForgeMapGeometry.Point(6, 4),
                new WorldForgeMapGeometry.Point(4, 4),
                new WorldForgeMapGeometry.Point(4, 9),
                new WorldForgeMapGeometry.Point(1, 9));

        WorldForgeMapGeometry.Point label =
                WorldForgeMapGeometry.interiorLabelPoint(uShape, 0, 0, 10, 10);

        assertTrue(isInsideOrOnBoundary(uShape, label),
                () -> "Label point must remain inside the concave polygon: " + label);
    }

    @Test
    void evenOddRingsPreserveIslandsAndHoles() {
        List<WorldForgeMapGeometry.Point> outer = List.of(
                new WorldForgeMapGeometry.Point(0,0), new WorldForgeMapGeometry.Point(10,0),
                new WorldForgeMapGeometry.Point(10,10), new WorldForgeMapGeometry.Point(0,10));
        List<WorldForgeMapGeometry.Point> hole = List.of(
                new WorldForgeMapGeometry.Point(3,3), new WorldForgeMapGeometry.Point(7,3),
                new WorldForgeMapGeometry.Point(7,7), new WorldForgeMapGeometry.Point(3,7));
        List<WorldForgeMapGeometry.Point> island = List.of(
                new WorldForgeMapGeometry.Point(4,4), new WorldForgeMapGeometry.Point(6,4),
                new WorldForgeMapGeometry.Point(6,6), new WorldForgeMapGeometry.Point(4,6));
        List<List<WorldForgeMapGeometry.Point>> rings = List.of(outer,hole,island);
        assertTrue(WorldForgeMapGeometry.containsPointRings(rings,1,1));
        assertFalse(WorldForgeMapGeometry.containsPointRings(rings,3.5,3.5));
        assertTrue(WorldForgeMapGeometry.containsPointRings(rings,5,5));
        assertTrue(WorldForgeMapGeometry.containsPointRings(rings,3,5));
        List<WorldForgeMapGeometry.Span> spans =
                WorldForgeMapGeometry.scanlineSpansRings(rings,0,0,10,10);
        assertTrue(spans.stream().anyMatch(s->s.y()==1&&s.xStart()<=1&&s.xEndExclusive()>=9));
        assertFalse(spans.stream().anyMatch(s->s.y()==3&&s.xStart()<=4&&s.xEndExclusive()>4));
        assertTrue(spans.stream().anyMatch(s->s.y()==4&&s.xStart()<=5&&s.xEndExclusive()>5));
    }

    private static boolean isInsideOrOnBoundary(
            List<WorldForgeMapGeometry.Point> polygon, WorldForgeMapGeometry.Point point) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            WorldForgeMapGeometry.Point a = polygon.get(j);
            WorldForgeMapGeometry.Point b = polygon.get(i);
            double cross = (point.x() - a.x()) * (b.y() - a.y())
                    - (point.y() - a.y()) * (b.x() - a.x());
            if (Math.abs(cross) < 1.0e-9
                    && point.x() >= Math.min(a.x(), b.x()) - 1.0e-9
                    && point.x() <= Math.max(a.x(), b.x()) + 1.0e-9
                    && point.y() >= Math.min(a.y(), b.y()) - 1.0e-9
                    && point.y() <= Math.max(a.y(), b.y()) + 1.0e-9) return true;
            if ((a.y() > point.y()) != (b.y() > point.y())
                    && point.x() < (b.x() - a.x()) * (point.y() - a.y())
                    / (b.y() - a.y()) + a.x()) inside = !inside;
        }
        return inside;
    }

    @Test
    void pointPickingSupportsConcaveTerritoriesAndTheirBoundaries() {
        List<WorldForgeMapGeometry.Point> uShape = List.of(
                new WorldForgeMapGeometry.Point(1, 1),
                new WorldForgeMapGeometry.Point(9, 1),
                new WorldForgeMapGeometry.Point(9, 9),
                new WorldForgeMapGeometry.Point(6, 9),
                new WorldForgeMapGeometry.Point(6, 4),
                new WorldForgeMapGeometry.Point(4, 4),
                new WorldForgeMapGeometry.Point(4, 9),
                new WorldForgeMapGeometry.Point(1, 9));

        assertTrue(WorldForgeMapGeometry.containsPoint(uShape, 5, 2));
        assertTrue(WorldForgeMapGeometry.containsPoint(uShape, 2, 7));
        assertTrue(WorldForgeMapGeometry.containsPoint(uShape, 5, 1));
        assertFalse(WorldForgeMapGeometry.containsPoint(uShape, 5, 7));
        assertFalse(WorldForgeMapGeometry.containsPoint(uShape, Double.NaN, 2));
        assertFalse(WorldForgeMapGeometry.containsPoint(List.of(), 2, 2));
    }

    @Test
    void degenerateAndOutsidePolygonsAreHandledSafely() {
        assertTrue(WorldForgeMapGeometry.clipPolygon(List.of(
                new WorldForgeMapGeometry.Point(-5, -5),
                new WorldForgeMapGeometry.Point(-2, -5),
                new WorldForgeMapGeometry.Point(-2, -2)), 0, 0, 10, 10).isEmpty());
        assertTrue(WorldForgeMapGeometry.scanlineSpans(List.of(
                new WorldForgeMapGeometry.Point(1, 1),
                new WorldForgeMapGeometry.Point(2, 2)), 0, 0, 10, 10).isEmpty());
        WorldForgeMapGeometry.Point center = WorldForgeMapGeometry.centroid(List.of(
                new WorldForgeMapGeometry.Point(2, 4),
                new WorldForgeMapGeometry.Point(4, 6)));
        assertEquals(3.0, center.x(), 1.0e-9);
        assertEquals(5.0, center.y(), 1.0e-9);
    }
}
