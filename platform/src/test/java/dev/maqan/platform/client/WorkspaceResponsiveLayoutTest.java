package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceResponsiveLayoutTest {
    @Test
    void desktopKeepsDockTreeInControl() {
        assertFalse(WorkspaceResponsiveLayout.isCompact(1920, 1080));
        assertFalse(WorkspaceResponsiveLayout.isCompact(1366, 768));
        assertFalse(WorkspaceResponsiveLayout.calculate(1366, 768, "navigation").compact());
        assertTrue(WorkspaceResponsiveLayout.calculate(1366, 768, "navigation").rectangles().isEmpty());
    }

    @Test
    void compactViewportKeepsMapFullCanvasAndShowsOnlySelectedDrawer() {
        for (int[] size : new int[][]{{800, 600}, {640, 480}, {1024, 540}, {720, 900}}) {
            var result = WorkspaceResponsiveLayout.calculate(size[0], size[1], "navigation");
            assertTrue(result.compact());
            var map = result.rectangles().get("content");
            var drawer = result.rectangles().get("navigation");
            assertNotNull(map);
            assertNotNull(drawer);
            assertEquals(size[0] - 44, map.width());
            assertEquals(size[1] - 35, map.height());
            assertTrue(drawer.x() >= 0 && drawer.y() >= 0);
            assertTrue(drawer.x() + drawer.width() <= size[0]);
            assertTrue(drawer.y() + drawer.height() <= size[1]);
            assertFalse(result.rectangles().containsKey("inspector"));
            assertFalse(result.rectangles().containsKey("timeline"));
        }
    }

    @Test
    void compactWorkspaceReservesPersistentBottomDock() {
        int width = 720, height = 900, reservedBottom = 176;
        int dockTop = height - reservedBottom;
        var canvasOnly = WorkspaceResponsiveLayout.calculate(width, height, null, reservedBottom);
        var canvas = canvasOnly.rectangles().get("content");
        assertEquals(dockTop, canvas.y() + canvas.height());

        for (String id : new String[]{"navigation", "inspector", "tools", "timeline"}) {
            var result = WorkspaceResponsiveLayout.calculate(width, height, id, reservedBottom);
            var drawer = result.rectangles().get(id);
            assertNotNull(drawer);
            assertTrue(drawer.y() + drawer.height() <= dockTop,
                    id + " drawer must not overlap the persistent bottom dock");
            assertTrue(drawer.x() + drawer.width() <= width);
        }
    }

    @Test
    void timelineDrawerStaysInsideVeryShortViewports() {
        var result = WorkspaceResponsiveLayout.calculate(480, 320, "timeline");
        var drawer = result.rectangles().get("timeline");
        assertNotNull(drawer);
        assertTrue(drawer.width() > 0 && drawer.height() > 0);
        assertTrue(drawer.x() + drawer.width() <= 480);
        assertTrue(drawer.y() + drawer.height() <= 320);
    }

    @Test
    void drawersRemainClampedEvenForPathologicalTinyDimensions() {
        for (int[] size : new int[][]{{1, 1}, {32, 24}, {96, 64}}) {
            for (String id : new String[]{"navigation", "inspector", "tools", "timeline"}) {
                var result = WorkspaceResponsiveLayout.calculate(size[0], size[1], id);
                var drawer = result.rectangles().get(id);
                assertNotNull(drawer);
                assertTrue(drawer.x() >= 0 && drawer.y() >= 0);
                assertTrue(drawer.x() + drawer.width() <= size[0]);
                assertTrue(drawer.y() + drawer.height() <= size[1]);
            }
        }
    }

    @Test
    void noDrawerLeavesOnlyTheUnobstructedCanvas() {
        var result = WorkspaceResponsiveLayout.calculate(640, 480, null);
        assertEquals(1, result.rectangles().size());
        assertTrue(result.rectangles().containsKey("content"));
    }
}
