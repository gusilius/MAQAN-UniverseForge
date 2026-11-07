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
