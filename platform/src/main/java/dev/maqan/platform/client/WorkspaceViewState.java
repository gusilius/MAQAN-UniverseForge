package dev.maqan.platform.client;

/**
 * Shared presentation state. Camera and map are independent axes: changing
 * either one must not clear the provider selection or reset the other axis.
 */
public final class WorkspaceViewState {
    public enum CameraMode {
        FIRST_PERSON("First person", "◉"),
        FREE("Free camera", "◇"),
        ISOMETRIC("Isometric camera", "◈"),
        RTS("RTS camera", "⌖");

        private final String label;
        private final String icon;
        CameraMode(String label, String icon) { this.label = label; this.icon = icon; }
        public String label() { return label; }
        public String icon() { return icon; }
    }

    public enum MapMode {
        LOCAL_MINIMAP("Local map"),
        WORLD_MAP("World map"),
        POLITICAL_MAP("Political map");

        private final String label;
        MapMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static CameraMode cameraMode = CameraMode.FIRST_PERSON;
    private static MapMode mapMode = MapMode.LOCAL_MINIMAP;

    private WorkspaceViewState() {}

    public static CameraMode cameraMode() { return cameraMode; }
    public static MapMode mapMode() { return mapMode; }

    public static CameraMode setCameraMode(CameraMode mode) {
        if (mode != null) cameraMode = mode;
        return cameraMode;
    }

    public static CameraMode cycleCameraMode() {
        CameraMode[] values = CameraMode.values();
        cameraMode = values[(cameraMode.ordinal() + 1) % values.length];
        return cameraMode;
    }

    public static MapMode setMapMode(MapMode mode) {
        if (mode != null) mapMode = mode;
        return mapMode;
    }

    public static MapMode cycleMapMode() {
        MapMode[] values = MapMode.values();
        mapMode = values[(mapMode.ordinal() + 1) % values.length];
        return mapMode;
    }
}
