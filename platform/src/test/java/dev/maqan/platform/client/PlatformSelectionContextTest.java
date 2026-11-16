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
    void liveEntityResolversComposeAcrossProvidersAndAreIdempotent() {
        String provider = "live-resolver-test";
        AtomicInteger troopCalls = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        PlatformSelectionContext.unregisterLiveEntityResolver(provider);
        PlatformSelectionContext.publish(provider, "canonical-character-id",
                "CHARACTER", "Resolver Test", "test-world", "live body");

        java.util.function.Function<String, net.minecraft.world.entity.Entity> troopResolver = id -> {
            assertEquals("canonical-character-id", id);
            troopCalls.incrementAndGet();
            return null;
        };
        java.util.function.Function<String, net.minecraft.world.entity.Entity> workerResolver = id -> {
            workerCalls.incrementAndGet();
            return null;
        };
        PlatformSelectionContext.registerLiveEntityResolver(provider, troopResolver);
        PlatformSelectionContext.registerLiveEntityResolver(provider, troopResolver);
        PlatformSelectionContext.registerLiveEntityResolver(provider, workerResolver);
        assertTrue(PlatformSelectionContext.resolveLiveEntity(PlatformSelectionContext.current()) == null);
        assertEquals(1, troopCalls.get(), "same provider resolver must not be registered twice");
        assertEquals(1, workerCalls.get(), "a second module resolver must be tried");

        PlatformSelectionContext.unregisterLiveEntityResolver(provider);
        PlatformSelectionContext.resolveLiveEntity(PlatformSelectionContext.current());
        assertEquals(1, troopCalls.get());
        assertEquals(1, workerCalls.get());
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
