package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void liveEntityResolversAreProviderScopedReplaceableAndRemovable() {
        String provider = "live-resolver-test";
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger replacementCalls = new AtomicInteger();
        PlatformSelectionContext.unregisterLiveEntityResolver(provider);
        PlatformSelectionContext.publish(provider, "canonical-character-id",
                "CHARACTER", "Resolver Test", "test-world", "live body");

        PlatformSelectionContext.registerLiveEntityResolver(provider, id -> {
            assertEquals("canonical-character-id", id);
            firstCalls.incrementAndGet();
            return null;
        });
        assertTrue(PlatformSelectionContext.resolveLiveEntity(PlatformSelectionContext.current()) == null);
        assertEquals(1, firstCalls.get());

        PlatformSelectionContext.registerLiveEntityResolver(provider, id -> {
            replacementCalls.incrementAndGet();
            return null;
        });
        PlatformSelectionContext.resolveLiveEntity(PlatformSelectionContext.current());
        assertEquals(1, replacementCalls.get());
        assertEquals(1, firstCalls.get());

        PlatformSelectionContext.unregisterLiveEntityResolver(provider);
        PlatformSelectionContext.resolveLiveEntity(PlatformSelectionContext.current());
        assertEquals(1, replacementCalls.get());
        PlatformSelectionContext.clear(provider);
    }

    @Test
    void liveEntityResolverDoesNotLeakAcrossProviders() {
        String provider = "live-resolver-owner";
        AtomicInteger calls = new AtomicInteger();
        PlatformSelectionContext.registerLiveEntityResolver(provider, id -> {
            calls.incrementAndGet();
            return null;
        });
        PlatformSelectionContext.publish("another-provider", "same-id",
                "CHARACTER", "Other Provider", "test-world", "must not resolve");
        PlatformSelectionContext.resolveLiveEntity(PlatformSelectionContext.current());
        assertEquals(0, calls.get());
        PlatformSelectionContext.unregisterLiveEntityResolver(provider);
        PlatformSelectionContext.clear("another-provider");
    }

}
