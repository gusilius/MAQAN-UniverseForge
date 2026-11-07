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
