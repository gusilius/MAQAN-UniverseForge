package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSelectionContextTest {
    @Test
    void selectionChangesAndClearsRemoveStaleGlobalSearchEntries() {
        PlatformSelectionContext.clear("selection-context-test");
        PlatformSelectionContext.publish("selection-context-test", "entity-old",
                "TEST", "UniqueOldWorkspaceSelection", "test-world", "old selection");
        assertEquals(1, WorkspaceRuntime.search("UniqueOldWorkspaceSelection", 10).size());

        PlatformSelectionContext.publish("selection-context-test", "entity-new",
                "TEST", "UniqueNewWorkspaceSelection", "test-world", "new selection");
        assertTrue(WorkspaceRuntime.search("UniqueOldWorkspaceSelection", 10).isEmpty());
        assertEquals(1, WorkspaceRuntime.search("UniqueNewWorkspaceSelection", 10).size());

        PlatformSelectionContext.clear("selection-context-test");
        assertTrue(WorkspaceRuntime.search("UniqueNewWorkspaceSelection", 10).isEmpty());
    }
}
