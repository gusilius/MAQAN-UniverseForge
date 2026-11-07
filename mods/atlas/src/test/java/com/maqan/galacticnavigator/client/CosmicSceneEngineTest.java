package com.maqan.galacticnavigator.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CosmicSceneEngineTest {
    @Test
    void sceneBodyOwnsAnImmutableCopyOfSourceData() {
        JsonObject source = new JsonObject();
        source.addProperty("name", "Aster");
        CosmicSceneEngine.SceneBody body = new CosmicSceneEngine.SceneBody(
                "aster", "Aster", "planet", "primary",
                new CosmicSceneEngine.Vector3(12, 0, -4),
                CosmicSceneEngine.PlacementSource.DATAPACK, source);

        source.addProperty("name", "mutated");
        assertEquals("Aster", body.sourceData().get("name").getAsString());

        JsonObject returned = body.sourceData();
        returned.addProperty("name", "also-mutated");
        assertEquals("Aster", body.sourceData().get("name").getAsString());
    }

    @Test
    void snapshotsDefensivelyCopyHierarchyCollections() {
        CosmicSceneEngine.SceneSystem system = new CosmicSceneEngine.SceneSystem(
                "sol", "Sol", "milky-way", "Milky Way", "local", "Local",
                CosmicSceneEngine.Vector3.ZERO, CosmicSceneEngine.PlacementSource.SCHEMATIC,
                List.of(), new JsonObject());
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "universe", "Universe", 1, 0,
                List.of(), List.of(), List.of(system), 0, 0, 1, 0);

        assertEquals(1, snapshot.systems().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.systems().add(system));
    }

    @Test
    void placementVectorsRejectNonFiniteCoordinates() {
        assertTrue(new CosmicSceneEngine.Vector3(1, 2, 3).finite());
        assertFalse(new CosmicSceneEngine.Vector3(Double.NaN, 0, 0).finite());
        assertFalse(new CosmicSceneEngine.Vector3(0, Double.POSITIVE_INFINITY, 0).finite());
    }
}
