package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaqanCameraRigTest {
    @Test
    void presetsKeepFreeAndRtsCameraGeometryDistinct() {
        MaqanCameraRig rig = new MaqanCameraRig();
        rig.start(10, 20, 0, MaqanCameraRig.Preset.FREE);
        double freePitch = rig.pitch();
        double freeDistance = rig.distance();

        rig.setPreset(MaqanCameraRig.Preset.RTS);

        assertNotEquals(freePitch, rig.pitch());
        assertNotEquals(freeDistance, rig.distance());
        assertEquals(10, rig.focusX());
        assertEquals(20, rig.focusZ());
    }

    @Test
    void panMovesFocusInCameraRelativeDirections() {
        MaqanCameraRig rig = new MaqanCameraRig();
        rig.start(0, 0, 0, MaqanCameraRig.Preset.RTS);
        rig.pan(1, 0, 1, false);
        assertTrue(rig.focusZ() > 0);
        double before = rig.focusX();
        rig.pan(0, 1, 1, false);
        assertTrue(rig.focusX() < before);
    }

    @Test
    void rotationAndZoomAreClamped() {
        MaqanCameraRig rig = new MaqanCameraRig();
        rig.start(0, 0, 0, MaqanCameraRig.Preset.RTS);
        rig.rotate(45, 1000);
        assertEquals(MaqanCameraRig.MAX_PITCH, rig.pitch());
        rig.rotate(0, -2000);
        assertEquals(MaqanCameraRig.MIN_PITCH, rig.pitch());
        rig.zoomBy(1000);
        assertEquals(MaqanCameraRig.MIN_DISTANCE, rig.distance());
        rig.zoomBy(-2000);
        assertEquals(MaqanCameraRig.MAX_DISTANCE, rig.distance());
    }

    @Test
    void focusCannotLeaveLoadedPlayerArea() {
        MaqanCameraRig rig = new MaqanCameraRig();
        rig.start(0, 0, 0, MaqanCameraRig.Preset.RTS);
        rig.focusOn(1000, 0);
        rig.clampTo(0, 0);
        assertEquals(88.0, rig.focusX(), 0.0001);
        assertEquals(0.0, rig.focusZ(), 0.0001);
    }

    @Test
    void cameraPositionLooksFromAboveAndBehindTheFocus() {
        MaqanCameraRig rig = new MaqanCameraRig();
        rig.start(0, 0, 0, MaqanCameraRig.Preset.RTS);
        double[] position = rig.position(64);
        assertEquals(0.0, position[0], 0.0001);
        assertTrue(position[1] > 64);
        assertTrue(position[2] < 0);
    }
}
