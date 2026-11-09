package dev.maqan.platform.client;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provider-owned overlays composited over the active Atlas/WorldForge map viewport. */
public final class WorkspaceMapLayerRegistry {
    public record Layer(String id, String label, String providerId, int order,
                        boolean defaultVisible, float defaultOpacity, Renderer renderer) {
        public Layer {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Layer id is required");
            if (label == null || label.isBlank()) throw new IllegalArgumentException("Layer label is required");
            if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Provider id is required");
            if (!Float.isFinite(defaultOpacity) || defaultOpacity < 0f || defaultOpacity > 1f)
                throw new IllegalArgumentException("Layer opacity must be between 0 and 1");
            if (renderer == null) throw new IllegalArgumentException("Layer renderer is required");
        }
    }

    @FunctionalInterface
    public interface Renderer {
        void render(GuiGraphics graphics, int x, int y, int width, int height, float opacity);
    }

    private static final Map<String, Layer> LAYERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> VISIBILITY = new ConcurrentHashMap<>();
    private static final Map<String, Float> OPACITY = new ConcurrentHashMap<>();
    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userNodeForPackage(WorkspaceMapLayerRegistry.class).node("map-layers");

    private WorkspaceMapLayerRegistry() {}

    public static void register(Layer layer) {
        if (layer == null) throw new IllegalArgumentException("Layer is required");
        Layer previous = LAYERS.putIfAbsent(layer.id(), layer);
        if (previous != null && !previous.providerId().equals(layer.providerId()))
            throw new IllegalArgumentException("Map layer id belongs to another provider: " + layer.id());
        if (previous != null) LAYERS.put(layer.id(), layer);
        VISIBILITY.putIfAbsent(layer.id(), PREFS.getBoolean("visible." + layer.id(), layer.defaultVisible()));
        OPACITY.putIfAbsent(layer.id(), PREFS.getFloat("opacity." + layer.id(), layer.defaultOpacity()));
    }

    public static void unregister(String layerId) {
        LAYERS.remove(layerId);
        VISIBILITY.remove(layerId);
        OPACITY.remove(layerId);
    }

    public static void unregisterProvider(String providerId) {
        LAYERS.values().stream().filter(layer -> layer.providerId().equals(providerId))
                .map(Layer::id).toList().forEach(WorkspaceMapLayerRegistry::unregister);
    }

    public static List<Layer> layers() {
        return LAYERS.values().stream().sorted(Comparator.comparingInt(Layer::order)
                .thenComparing(Layer::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public static boolean isVisible(String layerId) {
        Layer layer = LAYERS.get(layerId);
        return layer != null && VISIBILITY.getOrDefault(layerId, layer.defaultVisible());
    }

    public static void setVisible(String layerId, boolean visible) {
        if (!LAYERS.containsKey(layerId)) throw new IllegalArgumentException("Unknown map layer: " + layerId);
        VISIBILITY.put(layerId, visible);
        try { PREFS.putBoolean("visible." + layerId, visible); PREFS.flush(); }
        catch (java.util.prefs.BackingStoreException error) {
            WorkspaceRuntime.publish(WorkspaceRuntime.Severity.WARNING, "platform", "map-layer",
                    "Could not save map layer visibility", layerId, "");
        }
    }

    public static float opacity(String layerId) {
        Layer layer = LAYERS.get(layerId);
        return layer == null ? 0f : OPACITY.getOrDefault(layerId, layer.defaultOpacity());
    }

    public static void setOpacity(String layerId, float opacity) {
        if (!LAYERS.containsKey(layerId)) throw new IllegalArgumentException("Unknown map layer: " + layerId);
        if (!Float.isFinite(opacity) || opacity < 0f || opacity > 1f)
            throw new IllegalArgumentException("Layer opacity must be between 0 and 1");
        OPACITY.put(layerId, opacity);
        try { PREFS.putFloat("opacity." + layerId, opacity); PREFS.flush(); }
        catch (java.util.prefs.BackingStoreException error) {
            WorkspaceRuntime.publish(WorkspaceRuntime.Severity.WARNING, "platform", "map-layer",
                    "Could not save map layer opacity", layerId, "");
        }
    }

    public static void render(GuiGraphics graphics, int x, int y, int width, int height) {
        for (Layer layer : layers()) {
            if (!isVisible(layer.id())) continue;
            try {
                layer.renderer().render(graphics, x, y, width, height, opacity(layer.id()));
            } catch (RuntimeException error) {
                VISIBILITY.put(layer.id(), false);
                WorkspaceRuntime.publish(WorkspaceRuntime.Severity.WARNING,
                        layer.providerId(), "map-layer",
                        "Map layer failed: " + layer.label(), layer.id(), "");
            }
        }
    }

    static void clearForTests() {
        LAYERS.clear();
        VISIBILITY.clear();
        OPACITY.clear();
    }
}
