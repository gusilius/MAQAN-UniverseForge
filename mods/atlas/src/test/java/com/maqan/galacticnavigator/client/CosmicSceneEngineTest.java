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
    void sceneSnapshotIndexesSystemsBodiesAndSpatialGroups() {
        CosmicSceneEngine.SceneBody earth = new CosmicSceneEngine.SceneBody(
                "earth", "Earth", "planet", "sol",
                new CosmicSceneEngine.Vector3(1, 0, 0),
                CosmicSceneEngine.PlacementSource.DATAPACK, new JsonObject());
        CosmicSceneEngine.SceneSystem sol = new CosmicSceneEngine.SceneSystem(
                "sol", "Sol", "milky-way", "Milky Way", "local", "Local",
                new CosmicSceneEngine.Vector3(10, 0, 0),
                CosmicSceneEngine.PlacementSource.DATAPACK, List.of(earth), new JsonObject());
        CosmicSceneEngine.SceneSystem alpha = new CosmicSceneEngine.SceneSystem(
                "alpha", "Alpha", "milky-way", "Milky Way", "local", "Local",
                new CosmicSceneEngine.Vector3(14, 0, 0),
                CosmicSceneEngine.PlacementSource.DATAPACK, List.of(), new JsonObject());
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "universe", "Universe", 2, 0,
                List.of(), List.of(), List.of(sol, alpha), 1, 2, 0, 0);

        assertSame(sol, snapshot.system("sol").orElseThrow());
        assertSame(earth, snapshot.body("sol", "earth").orElseThrow());
        assertTrue(snapshot.body("alpha", "earth").isEmpty());
        assertEquals(List.of(sol, alpha), snapshot.systemsInGalaxy("milky-way"));
        assertEquals(List.of(sol, alpha), snapshot.systemsInSector("local"));
        assertEquals(List.of(sol), snapshot.systemsWithin(
                new CosmicSceneEngine.Vector3(10, 0, 0), 2.5));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.systemIndex().put("beta", alpha));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.bodyIndex().get("sol").put("mars", earth));
    }

    @Test
    void spatialQueriesRejectInvalidBounds() {
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "universe", "Universe", 1, 0, List.of(), List.of(), List.of(),
                0, 0, 0, 0);
        assertTrue(snapshot.systemsWithin(null, 10).isEmpty());
        assertTrue(snapshot.systemsWithin(CosmicSceneEngine.Vector3.ZERO, Double.NaN).isEmpty());
        assertTrue(snapshot.systemsWithin(CosmicSceneEngine.Vector3.ZERO, -1).isEmpty());
    }

    @Test
    void spatialHashFindsBoundaryNeighboursAndExcludesUnplacedSystems() {
        CosmicSceneEngine.SceneSystem left = new CosmicSceneEngine.SceneSystem(
                "left", "Left", "g", "Galaxy", "s", "Sector",
                new CosmicSceneEngine.Vector3(255.5, 0, 0),
                CosmicSceneEngine.PlacementSource.DATAPACK, List.of(), new JsonObject());
        CosmicSceneEngine.SceneSystem right = new CosmicSceneEngine.SceneSystem(
                "right", "Right", "g", "Galaxy", "s", "Sector",
                new CosmicSceneEngine.Vector3(256.5, 0, 0),
                CosmicSceneEngine.PlacementSource.DATAPACK, List.of(), new JsonObject());
        CosmicSceneEngine.SceneSystem unplaced = new CosmicSceneEngine.SceneSystem(
                "unplaced", "Unplaced", "g", "Galaxy", "s", "Sector",
                CosmicSceneEngine.Vector3.ZERO,
                CosmicSceneEngine.PlacementSource.UNPLACED, List.of(), new JsonObject());
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "u", "Universe", 1, 0, List.of(), List.of(),
                List.of(left, right, unplaced), 0, 2, 0, 1);

        assertEquals(List.of(left, right), snapshot.systemsWithin(
                new CosmicSceneEngine.Vector3(256, 0, 0), 1));
        assertTrue(snapshot.systemsWithin(CosmicSceneEngine.Vector3.ZERO, 0).isEmpty());
    }

    @Test
    void spatialHashLargeRadiusFallsBackWithoutLosingResults() {
        CosmicSceneEngine.SceneSystem distant = new CosmicSceneEngine.SceneSystem(
                "distant", "Distant", "g", "Galaxy", "s", "Sector",
                new CosmicSceneEngine.Vector3(1_000_000, -2_000_000, 3_000_000),
                CosmicSceneEngine.PlacementSource.DATAPACK, List.of(), new JsonObject());
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "u", "Universe", 1, 0, List.of(), List.of(),
                List.of(distant), 0, 1, 0, 0);

        assertEquals(List.of(distant), snapshot.systemsWithin(
                CosmicSceneEngine.Vector3.ZERO, 10_000_000));
    }

    @Test
    void hierarchyResolverBuildsRootsParentsAndChildren() {
        CosmicSceneEngine.SceneBody star = new CosmicSceneEngine.SceneBody(
                "star", "Star", "star", "",
                CosmicSceneEngine.Vector3.ZERO, CosmicSceneEngine.PlacementSource.DATAPACK,
                new JsonObject());
        CosmicSceneEngine.SceneBody planet = new CosmicSceneEngine.SceneBody(
                "planet", "Planet", "planet", "star",
                new CosmicSceneEngine.Vector3(10, 0, 0), CosmicSceneEngine.PlacementSource.DATAPACK,
                new JsonObject());
        CosmicSceneEngine.SceneBody moon = new CosmicSceneEngine.SceneBody(
                "moon", "Moon", "moon", "PLANET",
                new CosmicSceneEngine.Vector3(12, 0, 0), CosmicSceneEngine.PlacementSource.DATAPACK,
                new JsonObject());
        CosmicSceneEngine.SceneSystem system = new CosmicSceneEngine.SceneSystem(
                "sol", "Sol", "g", "Galaxy", "s", "Sector",
                CosmicSceneEngine.Vector3.ZERO, CosmicSceneEngine.PlacementSource.DATAPACK,
                List.of(star, planet, moon), new JsonObject());
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "u", "Universe", 1, 0, List.of(), List.of(), List.of(system),
                3, 1, 0, 0);

        assertEquals(List.of(star), snapshot.rootBodies("sol"));
        assertSame(star, snapshot.parentBody("sol", "planet").orElseThrow());
        assertSame(planet, snapshot.parentBody("sol", "moon").orElseThrow());
        assertEquals(List.of(planet), snapshot.childBodies("sol", "star"));
        assertEquals(List.of(moon), snapshot.childBodies("sol", "planet"));
        assertEquals("sol::moon", CosmicSceneEngine.SceneSnapshot.bodyKey("sol", "moon"));
        assertFalse(snapshot.hasUnresolvedParent("sol", "moon"));
        assertFalse(snapshot.isInParentCycle("sol", "moon"));
    }

    @Test
    void hierarchyResolverReportsMissingParentsAndCyclesWithoutDroppingBodies() {
        CosmicSceneEngine.SceneBody a = new CosmicSceneEngine.SceneBody(
                "a", "A", "planet", "b", CosmicSceneEngine.Vector3.ZERO,
                CosmicSceneEngine.PlacementSource.DATAPACK, new JsonObject());
        CosmicSceneEngine.SceneBody b = new CosmicSceneEngine.SceneBody(
                "b", "B", "moon", "a", CosmicSceneEngine.Vector3.ZERO,
                CosmicSceneEngine.PlacementSource.DATAPACK, new JsonObject());
        CosmicSceneEngine.SceneBody orphan = new CosmicSceneEngine.SceneBody(
                "orphan", "Orphan", "moon", "missing", CosmicSceneEngine.Vector3.ZERO,
                CosmicSceneEngine.PlacementSource.DATAPACK, new JsonObject());
        CosmicSceneEngine.SceneSystem system = new CosmicSceneEngine.SceneSystem(
                "sol", "Sol", "g", "Galaxy", "s", "Sector",
                CosmicSceneEngine.Vector3.ZERO, CosmicSceneEngine.PlacementSource.DATAPACK,
                List.of(a, b, orphan), new JsonObject());
        CosmicSceneEngine.SceneSnapshot snapshot = new CosmicSceneEngine.SceneSnapshot(
                "u", "Universe", 1, 0, List.of(), List.of(), List.of(system),
                3, 1, 0, 0);

        assertTrue(snapshot.isInParentCycle("sol", "a"));
        assertTrue(snapshot.isInParentCycle("sol", "b"));
        assertTrue(snapshot.hasUnresolvedParent("sol", "orphan"));
        assertEquals(3, snapshot.system("sol").orElseThrow().bodies().size());
    }

    @Test
    void placementVectorsRejectNonFiniteCoordinates() {
        assertTrue(new CosmicSceneEngine.Vector3(1, 2, 3).finite());
        assertFalse(new CosmicSceneEngine.Vector3(Double.NaN, 0, 0).finite());
        assertFalse(new CosmicSceneEngine.Vector3(0, Double.POSITIVE_INFINITY, 0).finite());
    }
}
