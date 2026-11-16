package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaqanUiPackLayoutTransformTest {
    @Test
    void referenceViewportScalesToFitWithoutDistortion() {
        int referenceWidth = 1368;
        int referenceHeight = 760;
        int screenWidth = 1920;
        int screenHeight = 1080;
        double scale = Math.min(screenWidth / (double) referenceWidth,
                screenHeight / (double) referenceHeight);
        int offsetX = (int) Math.round((screenWidth - referenceWidth * scale) / 2.0);
        int offsetY = (int) Math.round((screenHeight - referenceHeight * scale) / 2.0);

        var transform = new MaqanUiPackLayout.ViewportTransform(scale, offsetX, offsetY);
        assertEquals(0, offsetX);
        assertEquals(7, offsetY);
        assertEquals(1920, transform.screenWidth(referenceWidth));
        assertEquals(1067, transform.screenHeight(referenceHeight));
    }

    @Test
    void pointerCoordinatesRoundTripIntoReferenceSpace() {
        var transform = new MaqanUiPackLayout.ViewportTransform(0.75, 20, 12);
        assertEquals(400.0, transform.referenceX(transform.screenX(400)), 1.0);
        assertEquals(250.0, transform.referenceY(transform.screenY(250)), 1.0);
    }

    @Test
    void hitTestingUsesTheSameViewportTransformAsRendering() {
        var transform = new MaqanUiPackLayout.ViewportTransform(0.5, 10, 20);
        int[] minimap = {0, 534, 292, 760};

        assertTrue(transform.contains(20, 300, minimap));
        assertFalse(transform.contains(200, 300, minimap));
        assertFalse(transform.contains(20, 100, minimap));
    }
}
