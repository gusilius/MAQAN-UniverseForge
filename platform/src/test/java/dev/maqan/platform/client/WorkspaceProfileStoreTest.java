package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceProfileStoreTest {
    @Test
    void savesLoadsListsAndDeletesNamedProfiles() throws Exception {
        Preferences node = Preferences.userRoot().node("maqan-tests/" + UUID.randomUUID());
        try {
            WorkspaceProfileStore store = new WorkspaceProfileStore(node);
            WorkspaceDockTree.Node tree = new WorkspaceDockTree.Panel("map");
            tree = WorkspaceDockTree.dock(tree, "map", "inspector",
                    WorkspaceDockTree.Side.RIGHT, 300);
            tree = WorkspaceDockTree.group(tree, "inspector", "character");

            store.save("Strategy", tree);
            assertEquals(tree, store.load("Strategy"));
            assertEquals(List.of("Strategy"), store.list());
            assertEquals("Strategy", store.activeProfile());

            store.delete("Strategy");
            assertTrue(store.list().isEmpty());
            assertEquals("", store.activeProfile());
        } finally {
            node.removeNode();
            node.flush();
        }
    }

    @Test
    void rejectsBlankNamesAndCorruptOrUnsupportedProfiles() throws Exception {
        Preferences node = Preferences.userRoot().node("maqan-tests/" + UUID.randomUUID());
        try {
            WorkspaceProfileStore store = new WorkspaceProfileStore(node);
            assertThrows(IllegalArgumentException.class,
                    () -> store.save(" ", new WorkspaceDockTree.Panel("map")));
            Preferences profile = node.node("profile-corrupt");
            profile.putInt("schema", 1);
            profile.put("tree", "not-base64");
            profile.put("displayName", "Corrupt");
            assertThrows(IllegalStateException.class, () -> store.load("Corrupt"));
        } finally {
            node.removeNode();
            node.flush();
        }
    }
}
