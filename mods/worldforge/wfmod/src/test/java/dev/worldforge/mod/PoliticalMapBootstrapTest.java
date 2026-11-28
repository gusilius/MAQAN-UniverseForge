package dev.worldforge.mod;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PoliticalMapBootstrapTest {
    @Test
    void generatedTitlesHaveSymmetricRasterDerivedNeighborsAtEveryNonRootTier() {
        int width = 32;
        int height = 32;
        boolean[] land = new boolean[width * height];
        Arrays.fill(land, true);

        TitleData data = PoliticalMapBootstrap.generate(width, height, 1.0, land);

        for (int tier = 0; tier < 3; tier++) {
            boolean foundNeighborPair = false;
            for (TitleData.Title title : data.allTitles()) {
                if (title.tier != tier || title.neighbors.length == 0) continue;
                for (int neighborId : title.neighbors) {
                    TitleData.Title neighbor = data.get(neighborId);
                    assertNotNull(neighbor, "neighbor id must resolve to a title");
                    assertEquals(tier, neighbor.tier, "neighbors must be on the same hierarchy tier");
                    assertTrue(Arrays.stream(neighbor.neighbors).anyMatch(id -> id == title.id),
                            "political adjacency must be symmetric");
                }
                foundNeighborPair = true;
            }
            assertTrue(foundNeighborPair, "generated tier " + tier + " should contain adjacent titles");
        }
    }

    @Test
    void generatedTitlesDoNotTreatLandSeparatedByWaterAsNeighbors() {
        int width = 16;
        int height = 8;
        boolean[] land = new boolean[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) land[z * width + x] = x < 6 || x > 9;
        }

        TitleData data = PoliticalMapBootstrap.generate(width, height, 1.0, land);
        TitleData.Title western = data.provinceAtPixelPublic(1, 1);
        TitleData.Title eastern = data.provinceAtPixelPublic(14, 1);

        assertNotNull(western);
        assertNotNull(eastern);
        assertFalse(Arrays.stream(western.neighbors).anyMatch(id -> id == eastern.id),
                "a water gap must not create a false province border");
    }
}
