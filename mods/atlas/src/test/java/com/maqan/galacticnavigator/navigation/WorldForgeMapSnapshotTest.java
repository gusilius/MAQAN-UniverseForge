package com.maqan.galacticnavigator.navigation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldForgeMapSnapshotTest {
    @Test
    void indexesTerritoriesAndFeaturesByExactDimension() {
        WorldForgeMapSnapshot snapshot = new WorldForgeMapSnapshot();

        WorldForgeMapSnapshot.Territory overworld = new WorldForgeMapSnapshot.Territory();
        overworld.id = "realm:north";
        overworld.dimension_id = "minecraft:overworld";
        WorldForgeMapSnapshot.Territory nether = new WorldForgeMapSnapshot.Territory();
        nether.id = "realm:ash";
        nether.dimension_id = "minecraft:the_nether";
        snapshot.territories = List.of(overworld, nether);

        WorldForgeMapSnapshot.Feature city = new WorldForgeMapSnapshot.Feature();
        city.id = "city:capital";
        city.dimension_id = "minecraft:overworld";
        WorldForgeMapSnapshot.Feature village = new WorldForgeMapSnapshot.Feature();
        village.id = "settlement:ford";
        village.dimension_id = "minecraft:the_nether";
        snapshot.features = List.of(city, village);

        snapshot.rebuildIndexes();

        assertEquals(List.of(overworld), snapshot.territoriesInDimension("minecraft:overworld"));
        assertEquals(List.of(nether), snapshot.territoriesInDimension("minecraft:the_nether"));
        assertEquals(List.of(city), snapshot.featuresInDimension("minecraft:overworld"));
        assertEquals(List.of(village), snapshot.featuresInDimension("minecraft:the_nether"));
        assertTrue(snapshot.territoriesInDimension("minecraft:end").isEmpty());
        assertTrue(snapshot.featuresInDimension("").isEmpty());
    }

    @Test
    void indexesPoliticalBoundariesSeparatelyFromProvinceTerritories() {
        WorldForgeMapSnapshot snapshot = new WorldForgeMapSnapshot();
        WorldForgeMapSnapshot.Territory province = new WorldForgeMapSnapshot.Territory();
        province.id = "province:north";
        province.dimension_id = "minecraft:overworld";
        snapshot.territories = List.of(province);

        WorldForgeMapSnapshot.PoliticalBoundary realm = new WorldForgeMapSnapshot.PoliticalBoundary();
        realm.id = "realm:north";
        realm.realm_id = "north";
        realm.name = "Northern Realm";
        realm.dimension_id = "minecraft:overworld";
        snapshot.political_boundaries = List.of(realm);
        snapshot.features = List.of();
        snapshot.rebuildIndexes();

        assertEquals(List.of(province), snapshot.territoriesInDimension("minecraft:overworld"));
        assertEquals(List.of(realm), snapshot.boundariesInDimension("minecraft:overworld"));
        assertTrue(snapshot.boundariesInDimension("minecraft:the_nether").isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.boundariesInDimension("minecraft:overworld").clear());
    }

    @Test
    void indexesTerrainRastersAndKeepsFeatureGeometryInSnapshot() {
        WorldForgeMapSnapshot snapshot = new WorldForgeMapSnapshot();
        WorldForgeMapSnapshot.Terrain terrain = new WorldForgeMapSnapshot.Terrain();
        terrain.dimension_id = "minecraft:overworld";
        terrain.width = 2;
        terrain.height = 1;
        terrain.step_pixels = 1;
        terrain.blocks_per_pixel = 2;
        terrain.origin_x = -2;
        terrain.origin_z = -1;
        terrain.heights = new int[]{64, 92};
        terrain.biome_indices = new int[]{0, 1};
        terrain.river_values = new int[]{0, 255};
        terrain.biome_palette = new String[]{"plains", "river"};
        terrain.biome_colors = new int[]{0x779955, 0x3366AA};
        snapshot.terrain = List.of(terrain);

        WorldForgeMapSnapshot.Feature road = new WorldForgeMapSnapshot.Feature();
        road.id = "road:main";
        road.dimension_id = "minecraft:overworld";
        road.path.add(new WorldForgeMapSnapshot.Vertex());
        road.path.get(0).x = 10;
        road.path.get(0).z = 20;
        snapshot.features = List.of(road);
        snapshot.rebuildIndexes();

        assertSame(terrain, snapshot.terrainInDimension("minecraft:overworld"));
        assertNull(snapshot.terrainInDimension("minecraft:the_nether"));
        assertEquals(-1.0, terrain.sampleX(0));
        assertEquals(1.0, terrain.sampleX(1));
        assertEquals(road, snapshot.featuresInDimension("minecraft:overworld").get(0));
        assertEquals(10.0, road.path.get(0).x);
    }

    @Test
    void dimensionIndexesAreImmutableAndIgnoreUnboundRecords() {
        WorldForgeMapSnapshot snapshot = new WorldForgeMapSnapshot();
        WorldForgeMapSnapshot.Territory unbound = new WorldForgeMapSnapshot.Territory();
        unbound.id = "realm:unbound";
        snapshot.territories = List.of(unbound);
        snapshot.features = List.of();
        snapshot.rebuildIndexes();

        assertTrue(snapshot.territoriesInDimension("minecraft:overworld").isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.territoriesInDimension("minecraft:overworld").add(unbound));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.featuresInDimension("minecraft:overworld").add(
                        new WorldForgeMapSnapshot.Feature()));
    }
}
