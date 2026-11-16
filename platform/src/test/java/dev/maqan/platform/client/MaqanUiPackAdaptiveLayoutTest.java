package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaqanUiPackAdaptiveLayoutTest {
    private static final Map<String, int[]> REFERENCE = Map.of(
            "top", new int[]{0, 0, 1368, 62},
            "lcol", new int[]{0, 46, 58, 480},
            "lp", new int[]{58, 60, 336, 522},
            "rp", new int[]{1028, 60, 1328, 522},
            "rcol", new int[]{1326, 46, 1368, 524},
            "mm", new int[]{0, 534, 292, 760},
            "bot", new int[]{304, 584, 1150, 760},
            "cor", new int[]{0, 0, 136, 94}
    );

    @Test
    void referenceViewportPreservesTheImportedComposition() {
        var result = MaqanUiPackAdaptiveLayout.calculate(1368, 760, REFERENCE);
        assertEquals(new MaqanUiPackAdaptiveLayout.Rect(0, 0, 1368, 62), result.get("top"));
        assertEquals(new MaqanUiPackAdaptiveLayout.Rect(58, 60, 278, 462), result.get("lp"));
        assertEquals(new MaqanUiPackAdaptiveLayout.Rect(1028, 60, 300, 462), result.get("rp"));
        assertEquals(new MaqanUiPackAdaptiveLayout.Rect(0, 534, 292, 226), result.get("mm"));
        assertEquals(new MaqanUiPackAdaptiveLayout.Rect(304, 584, 846, 176), result.get("bot"));
    }

    @Test
    void desktopKeepsBothSideWindowsAndAnchorsTopAndBottom() {
        var result = MaqanUiPackAdaptiveLayout.calculate(1920, 1080, REFERENCE);
        assertFalse(result.compact());
        assertNotNull(result.get("lp"));
        assertNotNull(result.get("rp"));
        assertEquals(0, result.get("top").x());
        assertEquals(1920, result.get("top").width());
        assertEquals(1080, result.get("mm").bottom());
        assertEquals(1080, result.get("bot").bottom());
        assertTrue(result.get("lp").bottom() < result.get("mm").y());
        assertTrue(result.get("rp").bottom() < result.get("mm").y());
        for (var rect : result.rectangles().values()) {
            assertTrue(rect.x() >= 0 && rect.y() >= 0);
            assertTrue(rect.right() <= 1920 && rect.bottom() <= 1080);
        }
        assertEquals(292.0 / 226.0,
                result.get("mm").width() / (double) result.get("mm").height(), 0.02);
        assertEquals(846.0 / 176.0,
                result.get("bot").width() / (double) result.get("bot").height(), 0.03);
    }

    @Test
    void desktopContentViewportFitsBetweenSideWindowsAndAboveBottomDock() {
        var layout = MaqanUiPackAdaptiveLayout.calculate(1368, 760, REFERENCE);
        var viewport = MaqanUiPackAdaptiveLayout.contentViewport(layout, 8);
        assertNotNull(viewport);
        assertEquals(344, viewport.x());
        assertEquals(70, viewport.y());
        assertEquals(668, viewport.width());
        assertEquals(448, viewport.height());
        assertTrue(viewport.x() >= layout.get("lp").right());
        assertTrue(viewport.right() <= layout.get("rp").x());
        assertTrue(viewport.bottom() <= layout.get("mm").y());
        assertTrue(viewport.bottom() <= layout.get("bot").y());
    }

    @Test
    void compactLayoutHasNoDesktopContentViewport() {
        var layout = MaqanUiPackAdaptiveLayout.calculate(800, 700, REFERENCE);
        assertNull(MaqanUiPackAdaptiveLayout.contentViewport(layout, 8));
    }

    @Test
    void compactLayoutReplacesSideWindowsWithDrawerSpace() {
        var result = MaqanUiPackAdaptiveLayout.calculate(800, 700, REFERENCE);
        assertTrue(result.compact());
        assertNull(result.get("lp"));
        assertNull(result.get("rp"));
        assertNull(result.get("cor"));
        assertNotNull(result.get("mm"));
        assertNotNull(result.get("bot"));
        assertTrue(result.get("mm").right() <= result.get("bot").x());
        assertEquals(800, result.get("bot").right());
    }

    @Test
    void narrowPortraitViewportKeepsMinimapAndConsoleInsideScreen() {
        var result = MaqanUiPackAdaptiveLayout.calculate(390, 844, REFERENCE);
        var minimap = result.get("mm");
        var console = result.get("bot");
        assertTrue(result.compact());
        assertNotNull(minimap);
        assertNotNull(console);
        assertTrue(minimap.x() >= 0 && minimap.y() >= 0);
        assertTrue(minimap.right() <= 390 && minimap.bottom() <= 844);
        assertTrue(console.x() >= minimap.right());
        assertTrue(console.right() <= 390 && console.bottom() <= 844);
        assertEquals(292.0 / 226.0, minimap.width() / (double) minimap.height(), 0.02);
        assertEquals(846.0 / 176.0, console.width() / (double) console.height(), 0.08);
    }

    @Test
    void verySmallViewportDoesNotProduceNegativeOrOffscreenRectangles() {
        var result = MaqanUiPackAdaptiveLayout.calculate(320, 240, REFERENCE);
        for (var rect : result.rectangles().values()) {
            assertTrue(rect.x() >= 0 && rect.y() >= 0);
            assertTrue(rect.width() >= 0 && rect.height() >= 0);
            assertTrue(rect.right() <= 320);
            assertTrue(rect.bottom() <= 240);
        }
    }
}
