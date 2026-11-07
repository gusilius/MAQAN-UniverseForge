package com.maqan.galacticnavigator.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Persisted workspace-only state. Navigator selection, camera, routes and catalogue
 * navigation deliberately do not belong here.
 */
public final class WorkspaceState {
    public static final int CURRENT_SCHEMA = 1;

    public enum PanelId {
        LIST("list"), INFO("info"), MAP("map");
        private final String key;
        PanelId(String key) { this.key = key; }
        public String key() { return key; }
        public static PanelId fromKey(String key) {
            if (key == null) return null;
            for (PanelId id : values()) if (id.key.equals(key.trim().toLowerCase(java.util.Locale.ROOT))) return id;
            return null;
        }
    }

    public record Snapshot(List<PanelId> order, boolean listVisible, boolean infoVisible,
                           int listWidth, int infoWidth, int schemaVersion) {
        public Snapshot {
            order = List.copyOf(order);
        }
    }

    private final Preferences store;
    private Snapshot state;
    private final boolean readOnlyFutureSchema;

    private WorkspaceState(Preferences store, Snapshot state, boolean readOnlyFutureSchema) {
        this.store = Objects.requireNonNull(store);
        this.state = state;
        this.readOnlyFutureSchema = readOnlyFutureSchema;
    }

    public static WorkspaceState load(Preferences store) {
        Objects.requireNonNull(store);
        int storedSchema = store.getInt("workspace.schema_version", 0);
        boolean futureSchema = storedSchema > CURRENT_SCHEMA;
        String rawOrder = store.get("workspace.panel_order", store.get("panel_order", "list,info,map"));
        List<PanelId> order = normalizeOrder(parseOrder(rawOrder));
        int listWidth = readInt(store, "workspace.list_width", "list_panel_width", 220);
        int infoWidth = readInt(store, "workspace.info_width", "info_panel_width", 280);
        boolean listVisible = store.getBoolean("workspace.list_visible", true);
        boolean infoVisible = store.getBoolean("workspace.info_visible", true);
        WorkspaceState result = new WorkspaceState(store, new Snapshot(order, listVisible, infoVisible,
                clamp(listWidth, 140, 340), clamp(infoWidth, 180, 420), CURRENT_SCHEMA), futureSchema);
        // Canonicalize/migrate once at load. Keep legacy keys synchronized for older builds.
        result.persist();
        return result;
    }

    public synchronized Snapshot snapshot() { return state; }
    public synchronized String[] panelOrderKeys() {
        return state.order().stream().map(PanelId::key).toArray(String[]::new);
    }
    public synchronized void setPanelOrder(String[] keys) {
        if (keys == null) return;
        List<PanelId> parsed = new ArrayList<>();
        for (String key : keys) {
            PanelId id = PanelId.fromKey(key);
            if (id == null || parsed.contains(id)) return;
            parsed.add(id);
        }
        if (parsed.size() != PanelId.values().length) return;
        state = new Snapshot(parsed, state.listVisible(), state.infoVisible(),
                state.listWidth(), state.infoWidth(), CURRENT_SCHEMA);
        persist();
    }
    public synchronized void setListVisible(boolean visible) {
        state = new Snapshot(state.order(), visible, state.infoVisible(),
                state.listWidth(), state.infoWidth(), CURRENT_SCHEMA);
        persist();
    }
    public synchronized void setInfoVisible(boolean visible) {
        state = new Snapshot(state.order(), state.listVisible(), visible,
                state.listWidth(), state.infoWidth(), CURRENT_SCHEMA);
        persist();
    }
    public synchronized void setListWidth(int width) {
        state = new Snapshot(state.order(), state.listVisible(), state.infoVisible(),
                clamp(width, 140, 340), state.infoWidth(), CURRENT_SCHEMA);
        persist();
    }
    public synchronized void setInfoWidth(int width) {
        state = new Snapshot(state.order(), state.listVisible(), state.infoVisible(),
                state.listWidth(), clamp(width, 180, 420), CURRENT_SCHEMA);
        persist();
    }

    private void persist() {
        // An older build must never downgrade or overwrite a newer workspace schema.
        if (readOnlyFutureSchema) return;
        String order = String.join(",", state.order().stream().map(PanelId::key).toList());
        store.putInt("workspace.schema_version", CURRENT_SCHEMA);
        store.put("workspace.panel_order", order);
        store.putBoolean("workspace.list_visible", state.listVisible());
        store.putBoolean("workspace.info_visible", state.infoVisible());
        store.putInt("workspace.list_width", state.listWidth());
        store.putInt("workspace.info_width", state.infoWidth());
        // Backward-compatible mirror for versions that still read the original keys.
        store.put("panel_order", order);
        store.putInt("list_panel_width", state.listWidth());
        store.putInt("info_panel_width", state.infoWidth());
    }

    static List<PanelId> normalizeOrder(List<PanelId> input) {
        List<PanelId> result = new ArrayList<>();
        if (input != null) for (PanelId id : input)
            if (id != null && !result.contains(id)) result.add(id);
        for (PanelId id : PanelId.values()) if (!result.contains(id)) result.add(id);
        return List.copyOf(result);
    }

    private static List<PanelId> parseOrder(String raw) {
        List<PanelId> parsed = new ArrayList<>();
        if (raw != null) for (String token : raw.split(",")) {
            PanelId id = PanelId.fromKey(token);
            if (id != null && !parsed.contains(id)) parsed.add(id);
        }
        return parsed;
    }
    private static int readInt(Preferences store, String currentKey, String legacyKey, int fallback) {
        String current = store.get(currentKey, null);
        if (current != null) {
            try { return Integer.parseInt(current.trim()); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return store.getInt(legacyKey, fallback);
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
