package dev.maqan.platform.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMapStateTest {
    @AfterEach
    void reset() {
        WorkspaceMapState.clearForTests();
        PlatformSelectionContext.clear(null);
    }

    @Test
    void viewTypeAndSelectionAreSharedIndependently() {
        WorkspaceMapState.setViewType(WorkspaceMapState.ViewType.GEOGRAPHIC);
        PlatformSelectionContext.publish("atlas", "province-7", "province",
                "Northern March", "minecraft:overworld", "realm=North");

        assertEquals(WorkspaceMapState.ViewType.GEOGRAPHIC, WorkspaceMapState.viewType());
        assertNotNull(WorkspaceMapState.selection());
        assertEquals("province-7", WorkspaceMapState.selection().entityId());
        assertEquals("atlas", WorkspaceMapState.selection().providerId());

        WorkspaceMapState.setViewType(WorkspaceMapState.ViewType.DIORAMA_3D);
        assertEquals("province-7", WorkspaceMapState.selection().entityId());
    }

    @Test
    void viewportRejectsInvalidNumbersAndTracksDimension() {
        WorkspaceMapState.setViewport("minecraft:overworld", 120.5, -48.25, 2.0, 90, 45);
        var saved = WorkspaceMapState.viewport();
        assertEquals("minecraft:overworld", saved.dimension());
        assertEquals(120.5, saved.centerX());
        assertEquals(-48.25, saved.centerZ());
        assertEquals(2.0, saved.zoom());

        WorkspaceMapState.setViewport("minecraft:the_nether", Double.NaN, 0, 1, 0, 0);
        assertEquals(saved, WorkspaceMapState.viewport());
    }

    @Test
    void clearingProviderSelectionDoesNotClearAnotherProvider() {
        PlatformSelectionContext.publish("atlas", "poi-1", "poi", "Outpost", "overworld", "");
        PlatformSelectionContext.clear("worldforge");
        assertEquals("poi-1", WorkspaceMapState.selection().entityId());

        PlatformSelectionContext.clear("atlas");
        assertNull(WorkspaceMapState.selection());
    }
}
