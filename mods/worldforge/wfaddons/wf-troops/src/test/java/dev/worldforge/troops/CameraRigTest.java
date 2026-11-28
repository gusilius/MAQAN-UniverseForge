package dev.worldforge.troops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraRigTest {
    @Test
    void snapshotRestoresCompleteCameraStateWithoutTransition() {
        CameraRig rig = new CameraRig();
        CameraRig.Snapshot saved = new CameraRig.Snapshot(
                CameraRig.View.STRATEGIC, 128.5, -72.25, 725.0, 61.5, 52.0, 140.0);

        assertTrue(rig.restore(saved));
        CameraRig.Snapshot restored = rig.snapshot();
        assertEquals(CameraRig.View.STRATEGIC, restored.view());
        assertEquals(128.5, restored.fx());
        assertEquals(-72.25, restored.fz());
        assertEquals(5.0, restored.yaw());
        assertEquals(61.5, restored.pitch());
        assertEquals(52.0, restored.zoom());
        assertEquals(140.0, restored.leash());
    }

    @Test
    void restoreRejectsInvalidOrUnsafeCameraValuesWithoutMutatingRig() {
        CameraRig rig = new CameraRig();
        rig.start(10, 20, 30, CameraRig.View.SKY);
        CameraRig.Snapshot before = rig.snapshot();

        assertFalse(rig.restore(new CameraRig.Snapshot(
                CameraRig.View.FREE, Double.NaN, 0, 0, 30, 30, 90)));
        assertFalse(rig.restore(new CameraRig.Snapshot(
                CameraRig.View.FREE, 0, 0, 0, 89, 30, 90)));
        assertFalse(rig.restore(new CameraRig.Snapshot(
                CameraRig.View.FREE, 0, 0, 0, 30, 30, 4)));

        assertEquals(before, rig.snapshot());
    }

    @Test
    void restoredStateCanContinueUsingNormalCameraConstraints() {
        CameraRig rig = new CameraRig();
        assertTrue(rig.restore(new CameraRig.Snapshot(
                CameraRig.View.TACTICS, 500, -500, 180, 55, 24, 64)));

        rig.clamp(0, 0);
        assertEquals(64.0, Math.hypot(rig.fx, rig.fz), 1.0e-9);
        assertEquals(0.0, CameraRig.keepAboveTerrain(10, 12) - 14.25, 1.0e-9);
    }

    @Test
    void cameraPresetsTransitionSmoothlyAcrossAllFiveViews() {
        CameraRig rig = new CameraRig();
        rig.start(0, 0, 0, CameraRig.View.SKY);
        rig.cycle();
        assertEquals(CameraRig.View.TACTICS, rig.view);
        assertNotEquals(CameraRig.View.TACTICS.pitch, rig.pitch, 1.0e-6);
        for (int i = 0; i < 120; i++) rig.updateTransition(1.0 / 60.0);
        assertEquals(CameraRig.View.TACTICS.pitch, rig.pitch, 0.03);
        rig.cycle();
        assertEquals(CameraRig.View.FREE, rig.view);
        for (int i = 0; i < 120; i++) rig.updateTransition(1.0 / 60.0);
        rig.cycle();
        assertEquals(CameraRig.View.TOP_DOWN, rig.view);
        for (int i = 0; i < 120; i++) rig.updateTransition(1.0 / 60.0);
        rig.cycle();
        assertEquals(CameraRig.View.STRATEGIC, rig.view);
    }
    @Test
    void cameraHeightIsRelativeToPlayerAndIndependentForEachView() {
        CameraRig rig = new CameraRig();
        rig.start(100, 200, 0, CameraRig.View.SKY);
        rig.setHeightFromUser(72);
        double[] first = rig.positionRelativeToPlayer(64);
        assertEquals(136.0, first[1], 1.0e-9);

        rig.transitionTo(CameraRig.View.TACTICS);
        rig.setHeightFromUser(40);
        double[] second = rig.positionRelativeToPlayer(-20);
        assertEquals(20.0, second[1], 1.0e-9);

        rig.transitionTo(CameraRig.View.SKY);
        assertEquals(72.0, rig.heightAbovePlayer, 1.0e-9);
        assertEquals(136.0, rig.positionRelativeToPlayer(64)[1], 1.0e-9);
    }

    @Test
    void eachCameraHeightLockOnlyLocksItsOwnView() {
        CameraRig rig = new CameraRig();
        rig.start(0, 0, 0, CameraRig.View.SKY);
        rig.setHeightFromUser(80);
        rig.setHeightLocked(true);
        rig.adjustHeight(16);
        assertEquals(80.0, rig.heightAbovePlayer, 1.0e-9);

        rig.transitionTo(CameraRig.View.TOP_DOWN);
        rig.setHeightFromUser(120);
        assertEquals(120.0, rig.heightAbovePlayer, 1.0e-9);

        rig.transitionTo(CameraRig.View.SKY);
        assertEquals(80.0, rig.heightAbovePlayer, 1.0e-9);
        assertTrue(rig.heightLockedFor(CameraRig.View.SKY));
        assertFalse(rig.heightLockedFor(CameraRig.View.TOP_DOWN));
    }

    @Test
    void playerRelativeCameraHeightDoesNotDependOnFocusTerrain() {
        CameraRig rig = new CameraRig();
        rig.start(10, 20, 0, CameraRig.View.SKY);
        rig.setHeightFromUser(56);
        rig.focusOn(900, -1200);
        assertEquals(156.0, rig.positionRelativeToPlayer(100)[1], 1.0e-9);
        assertEquals(-44.0, rig.positionRelativeToPlayer(-100)[1], 1.0e-9);
    }

}
