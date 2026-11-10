package com.maqan.galacticnavigator.navigation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NavigatorWorldForgeMapStoreTest {
    @AfterEach
    void resetStore() {
        NavigatorWorldForgeMapStore.clearForTests();
    }

    @Test
    void networkSnapshotPublishesThroughTheSameValidatedCache() {
        String json = """
                {
                  "schema_version": 1,
                  "world_id": "world-test-001",
                  "generated_at": "2026-11-11T10:00:00Z",
                  "territories": [],
                  "features": [],
                  "political_boundaries": [],
                  "terrain": []
                }
                """;

        assertTrue(NavigatorWorldForgeMapStore.acceptNetworkSnapshot(json));
        assertNotNull(NavigatorWorldForgeMapStore.current());
        assertEquals("world-test-001", NavigatorWorldForgeMapStore.current().world_id);
        assertEquals("network", NavigatorWorldForgeMapStore.snapshotSource());
        assertFalse(NavigatorWorldForgeMapStore.isStale());
        assertTrue(NavigatorWorldForgeMapStore.status().contains("network"));
    }

    @Test
    void rejectedNetworkSnapshotKeepsTheLastKnownGoodSnapshot() {
        String valid = """
                {"schema_version":1,"world_id":"world-test-002","generated_at":"2026-11-11T10:00:00Z",
                 "territories":[],"features":[],"political_boundaries":[],"terrain":[]}
                """;
        assertTrue(NavigatorWorldForgeMapStore.acceptNetworkSnapshot(valid));
        WorldForgeMapSnapshot accepted = NavigatorWorldForgeMapStore.current();

        assertFalse(NavigatorWorldForgeMapStore.acceptNetworkSnapshot(
                "{\"schema_version\":99,\"world_id\":\"bad\",\"territories\":[]}"));
        assertSame(accepted, NavigatorWorldForgeMapStore.current());
        assertTrue(NavigatorWorldForgeMapStore.isStale());
        assertEquals("network", NavigatorWorldForgeMapStore.snapshotSource());
        assertTrue(NavigatorWorldForgeMapStore.status().contains("last known-good"));
    }

    @Test
    void malformedGeometryNeverReplacesAValidNetworkSnapshot() {
        String valid = """
                {"schema_version":1,"world_id":"world-test-003","generated_at":"2026-11-11T10:00:00Z",
                 "territories":[],"features":[],"political_boundaries":[],"terrain":[]}
                """;
        assertTrue(NavigatorWorldForgeMapStore.acceptNetworkSnapshot(valid));
        WorldForgeMapSnapshot accepted = NavigatorWorldForgeMapStore.current();

        String malformed = """
                {"schema_version":1,"world_id":"world-test-003","generated_at":"2026-11-11T10:01:00Z",
                 "territories":[{"id":"province:bad","name":"Bad","dimension_id":"minecraft:overworld",
                 "rings":[[{"x":999999999,"y":64,"z":0},{"x":1,"y":64,"z":1},{"x":2,"y":64,"z":2}]]}],
                 "features":[],"political_boundaries":[],"terrain":[]}
                """;
        assertFalse(NavigatorWorldForgeMapStore.acceptNetworkSnapshot(malformed));
        assertSame(accepted, NavigatorWorldForgeMapStore.current());
        assertTrue(NavigatorWorldForgeMapStore.isStale());
    }
}
