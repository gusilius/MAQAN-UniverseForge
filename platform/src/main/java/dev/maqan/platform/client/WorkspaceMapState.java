package dev.maqan.platform.client;

/**
 * Canonical, provider-neutral state for the active world map workspace.
 *
 * Renderers keep ownership of their data and drawing, while map type,
 * selected identity and viewport can be shared across political, geographic,
 * exploration and 3D presentations.
 */
public final class WorkspaceMapState {
    public enum ViewType {
        POLITICAL, GEOGRAPHIC, EXPLORATION, NAVIGATION, DIORAMA_3D
    }

    public record Selection(String providerId, String entityId, String entityType,
                            String displayName, String worldId, long revision) {}

    public record Viewport(String dimension, double centerX, double centerZ,
                           double zoom, double yaw, double pitch, long revision) {}

    private static volatile ViewType viewType = ViewType.POLITICAL;
    private static volatile Selection selection;
    private static volatile Viewport viewport = new Viewport("", 0, 0, 1, 0, 0, 0);
    private static long revision;

    private WorkspaceMapState() {}

    public static synchronized void setViewType(ViewType next) {
        if (next == null || next == viewType) return;
        viewType = next;
        revision++;
    }

    public static ViewType viewType() { return viewType; }

    public static synchronized void publishSelection(String providerId, String entityId,
                                                       String entityType, String displayName,
                                                       String worldId) {
        if (entityId == null || entityId.isBlank()) {
            clearSelection(providerId);
            return;
        }
        String provider = clean(providerId), id = entityId.trim();
        String type = clean(entityType), name = clean(displayName), world = clean(worldId);
        if (selection != null && selection.providerId().equals(provider)
                && selection.entityId().equals(id) && selection.entityType().equals(type)
                && selection.displayName().equals(name) && selection.worldId().equals(world)) return;
        selection = new Selection(provider, id, type, name, world, ++revision);
    }

    public static synchronized void clearSelection(String providerId) {
        if (selection == null) return;
        if (providerId == null || providerId.isBlank() || providerId.equals(selection.providerId())) {
            selection = null;
            revision++;
        }
    }

    public static Selection selection() { return selection; }

    public static synchronized void setViewport(String dimension, double centerX, double centerZ,
                                                 double zoom, double yaw, double pitch) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || !Double.isFinite(zoom) || zoom <= 0
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)) return;
        String dim = clean(dimension);
        Viewport current = viewport;
        if (current.dimension().equals(dim) && current.centerX() == centerX
                && current.centerZ() == centerZ && current.zoom() == zoom
                && current.yaw() == yaw && current.pitch() == pitch) return;
        viewport = new Viewport(dim, centerX, centerZ, zoom, yaw, pitch, ++revision);
    }

    public static Viewport viewport() { return viewport; }

    static synchronized void clearForTests() {
        viewType = ViewType.POLITICAL;
        selection = null;
        viewport = new Viewport("", 0, 0, 1, 0, 0, 0);
        revision = 0;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
