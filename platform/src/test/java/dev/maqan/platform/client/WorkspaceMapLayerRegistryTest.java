package dev.maqan.platform.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMapLayerRegistryTest {
    @AfterEach
    void clear() { WorkspaceMapLayerRegistry.clearForTests(); }

    @Test
    void providersCanRegisterToggleAndUnloadMapLayers() {
        var layer = new WorkspaceMapLayerRegistry.Layer("wf.borders", "Political borders",
                "wf_realms", 10, true, 0.8f, (graphics, x, y, width, height, opacity) -> {});
        WorkspaceMapLayerRegistry.register(layer);
        assertTrue(WorkspaceMapLayerRegistry.isVisible("wf.borders"));
        assertEquals(0.8f, WorkspaceMapLayerRegistry.opacity("wf.borders"));

        WorkspaceMapLayerRegistry.setVisible("wf.borders", false);
        WorkspaceMapLayerRegistry.setOpacity("wf.borders", 0.4f);
        assertFalse(WorkspaceMapLayerRegistry.isVisible("wf.borders"));
        assertEquals(0.4f, WorkspaceMapLayerRegistry.opacity("wf.borders"));

        WorkspaceMapLayerRegistry.unregisterProvider("wf_realms");
        assertTrue(WorkspaceMapLayerRegistry.layers().isEmpty());
    }

    @Test
    void validatesLayerOpacityAndRejectsCrossProviderIdCollision() {
        assertThrows(IllegalArgumentException.class, () ->
                new WorkspaceMapLayerRegistry.Layer("bad", "Bad", "provider", 0,
                        true, 2f, (g, x, y, w, h, opacity) -> {}));
        WorkspaceMapLayerRegistry.register(new WorkspaceMapLayerRegistry.Layer(
                "shared", "First", "provider-a", 0, true, 1f, (g, x, y, w, h, opacity) -> {}));
        assertThrows(IllegalArgumentException.class, () ->
                WorkspaceMapLayerRegistry.register(new WorkspaceMapLayerRegistry.Layer(
                        "shared", "Second", "provider-b", 0, true, 1f, (g, x, y, w, h, opacity) -> {})));
    }
}
