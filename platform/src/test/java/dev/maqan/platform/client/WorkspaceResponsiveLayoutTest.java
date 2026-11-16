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
    void timelineDrawerStaysInsideVeryShortViewports() {
        var result = WorkspaceResponsiveLayout.calculate(480, 320, "timeline");
        var drawer = result.rectangles().get("timeline");
        assertNotNull(drawer);
        assertTrue(drawer.width() > 0 && drawer.height() > 0);
        assertTrue(drawer.x() + drawer.width() <= 480);
        assertTrue(drawer.y() + drawer.height() <= 320);
    }

    @Test
    void noDrawerLeavesOnlyTheUnobstructedCanvas() {
        var result = WorkspaceResponsiveLayout.calculate(640, 480, null);
        assertEquals(1, result.rectangles().size());
        assertTrue(result.rectangles().containsKey("content"));
    }
}
