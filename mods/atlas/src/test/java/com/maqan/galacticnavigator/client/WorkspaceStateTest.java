package com.maqan.galacticnavigator.client;

import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStateTest {
    private Preferences node() {
        return Preferences.userRoot().node("maqan-tests/" + java.util.UUID.randomUUID());
    }

    @Test
    void migratesLegacyWidthsAndRepairsCorruptOrder() throws Exception {
        Preferences prefs = node();
        try {
            prefs.put("panel_order", "info,info,unknown");
            prefs.putInt("list_panel_width", 999);
            prefs.putInt("info_panel_width", 120);
            WorkspaceState state = WorkspaceState.load(prefs);
            assertEquals(java.util.List.of(WorkspaceState.PanelId.INFO,
                    WorkspaceState.PanelId.LIST, WorkspaceState.PanelId.MAP), state.snapshot().order());
            assertEquals(340, state.snapshot().listWidth());
            assertEquals(180, state.snapshot().infoWidth());
            assertEquals("info,list,map", prefs.get("workspace.panel_order", ""));
            assertEquals(340, prefs.getInt("list_panel_width", 0));
            assertEquals(1, prefs.getInt("workspace.schema_version", 0));
        } finally {
            prefs.removeNode();
        }
    }

    @Test
    void invalidOrderUpdateDoesNotDestroyCurrentOrder() throws Exception {
        Preferences prefs = node();
        try {
            WorkspaceState state = WorkspaceState.load(prefs);
            var before = state.snapshot();
            state.setPanelOrder(new String[]{"list", "list", "map"});
            assertEquals(before.order(), state.snapshot().order());
            state.setPanelOrder(new String[]{"map", "list", "info"});
            assertEquals(java.util.List.of(WorkspaceState.PanelId.MAP,
                    WorkspaceState.PanelId.LIST, WorkspaceState.PanelId.INFO), state.snapshot().order());
        } finally {
            prefs.removeNode();
        }
    }

    @Test
    void javaFallbackHonorsVisibilityOrderAndWidthBounds() throws Exception {
        Preferences prefs = node();
        try {
            WorkspaceState state = WorkspaceState.load(prefs);
            state.setPanelOrder(new String[]{"info", "map", "list"});
            int[] layout = WorkspaceLayout.compute(1600, 900, state.snapshot());
            assertNotNull(layout);
            // Golden contract vector shared with Rust layout.rs:
            // 1600x900, order info/map/list, widths 220/280.
            assertArrayEquals(new int[]{94, 864, 1368, 1588, 12, 292, 300, 1360}, layout);

            state.setListWidth(300);
            state.setInfoWidth(380);
            layout = WorkspaceLayout.compute(1600, 900, state.snapshot());
            assertEquals(380, layout[5] - layout[4]);
            assertEquals(300, layout[3] - layout[2]);
            assertTrue(layout[4] < layout[6]);
            assertTrue(layout[6] < layout[2]);

            state.setListVisible(false);
            layout = WorkspaceLayout.compute(1600, 900, state.snapshot());
            assertEquals(0, layout[2]);
            assertEquals(0, layout[3]);
            assertTrue(layout[7] > layout[6]);
        } finally {
            prefs.removeNode();
        }
    }

    @Test
    void doesNotDowngradePreferencesFromANewerSchema() throws Exception {
        Preferences prefs = node();
        try {
            prefs.putInt("workspace.schema_version", WorkspaceState.CURRENT_SCHEMA + 4);
            prefs.put("workspace.panel_order", "map,info,list");
            WorkspaceState state = WorkspaceState.load(prefs);
            state.setPanelOrder(new String[]{"list", "map", "info"});
            assertEquals(WorkspaceState.CURRENT_SCHEMA + 4,
                    prefs.getInt("workspace.schema_version", 0));
            assertEquals("map,info,list", prefs.get("workspace.panel_order", ""));
        } finally {
            prefs.removeNode();
        }
    }

    @Test
    void rejectsInvalidViewportAndKeepsMapOnNarrowScreen() throws Exception {
        Preferences prefs = node();
        try {
            WorkspaceState state = WorkspaceState.load(prefs);
            assertNull(WorkspaceLayout.compute(0, 900, state.snapshot()));
            int[] layout = WorkspaceLayout.compute(480, 720, state.snapshot());
            assertNotNull(layout);
            assertEquals(0, layout[2]);
            assertEquals(0, layout[3]);
            assertEquals(0, layout[4]);
            assertEquals(0, layout[5]);
            assertTrue(layout[7] > layout[6]);
        } finally {
            prefs.removeNode();
        }
    }
}
