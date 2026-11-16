package dev.maqan.platform.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceViewStateTest {
    @AfterEach
    void reset() {
        WorkspaceViewState.setCameraMode(WorkspaceViewState.CameraMode.FIRST_PERSON);
        WorkspaceViewState.setMapMode(WorkspaceViewState.MapMode.LOCAL_MINIMAP);
    }

    @Test
    void changingCameraDoesNotChangeMapMode() {
        WorkspaceViewState.setMapMode(WorkspaceViewState.MapMode.POLITICAL_MAP);
        WorkspaceViewState.setCameraMode(WorkspaceViewState.CameraMode.RTS);
        assertEquals(WorkspaceViewState.MapMode.POLITICAL_MAP, WorkspaceViewState.mapMode());
        assertEquals(WorkspaceViewState.CameraMode.RTS, WorkspaceViewState.cameraMode());
    }

    @Test
    void changingMapDoesNotChangeCameraMode() {
        WorkspaceViewState.setCameraMode(WorkspaceViewState.CameraMode.FREE);
        WorkspaceViewState.setMapMode(WorkspaceViewState.MapMode.WORLD_MAP);
        assertEquals(WorkspaceViewState.CameraMode.FREE, WorkspaceViewState.cameraMode());
        assertEquals(WorkspaceViewState.MapMode.WORLD_MAP, WorkspaceViewState.mapMode());
    }

    @Test
    void cameraAndMapCyclesAreIndependent() {
        assertEquals(WorkspaceViewState.CameraMode.FREE, WorkspaceViewState.cycleCameraMode());
        assertEquals(WorkspaceViewState.MapMode.WORLD_MAP, WorkspaceViewState.cycleMapMode());
        assertEquals(WorkspaceViewState.CameraMode.RTS, WorkspaceViewState.cameraMode());
        assertEquals(WorkspaceViewState.MapMode.POLITICAL_MAP, WorkspaceViewState.mapMode());
    }
}
