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
    void persistsAndAppliesUserDefinedLayerCompositingOrder() {
        WorkspaceMapLayerRegistry.register(new WorkspaceMapLayerRegistry.Layer(
                "test.base", "Base", "test", 0, true, 1f, (g, x, y, w, h, opacity) -> {}));
        WorkspaceMapLayerRegistry.register(new WorkspaceMapLayerRegistry.Layer(
                "test.armies", "Armies", "test", 0, true, 1f, (g, x, y, w, h, opacity) -> {}));
        WorkspaceMapLayerRegistry.register(new WorkspaceMapLayerRegistry.Layer(
                "test.labels", "Labels", "test", 0, true, 1f, (g, x, y, w, h, opacity) -> {}));

        assertEquals("test.armies", WorkspaceMapLayerRegistry.layers().get(0).id());
        WorkspaceMapLayerRegistry.move("test.labels", -1);

        assertEquals(java.util.List.of("test.labels", "test.armies", "test.base"),
                WorkspaceMapLayerRegistry.layers().stream().map(WorkspaceMapLayerRegistry.Layer::id).toList());
        assertEquals(0, WorkspaceMapLayerRegistry.order("test.labels"));
        assertEquals(10, WorkspaceMapLayerRegistry.order("test.armies"));
        assertEquals(20, WorkspaceMapLayerRegistry.order("test.base"));
    }

    @Test
    void rejectsOrderingUnknownLayers() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceMapLayerRegistry.setOrder("missing", 1));
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
