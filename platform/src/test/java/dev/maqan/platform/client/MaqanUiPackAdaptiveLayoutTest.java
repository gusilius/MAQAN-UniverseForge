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
    void desktopKeepsBothSideWindowsAndAnchorsTopAndBottom() {
        var result = MaqanUiPackAdaptiveLayout.calculate(1920, 1080, REFERENCE);
        assertFalse(result.compact());
        assertNotNull(result.get("lp"));
        assertNotNull(result.get("rp"));
        assertEquals(0, result.get("top").x());
        assertEquals(1920, result.get("top").width());
        assertEquals(1080, result.get("mm").bottom());
        assertEquals(1080, result.get("bot").bottom());
    }

    @Test
    void compactLayoutReplacesSideWindowsWithDrawerSpace() {
        var result = MaqanUiPackAdaptiveLayout.calculate(900, 700, REFERENCE);
        assertTrue(result.compact());
        assertNull(result.get("lp"));
        assertNull(result.get("rp"));
        assertNull(result.get("cor"));
        assertNotNull(result.get("mm"));
        assertNotNull(result.get("bot"));
        assertTrue(result.get("mm").right() <= result.get("bot").x());
        assertEquals(900, result.get("bot").right());
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
