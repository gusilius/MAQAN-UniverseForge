package dev.worldforge.mod;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Verifies that the image-generated archive is consumable by the production WorldForge MapData loader. */
public final class ImageWorldMapCompatibilityTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: ImageWorldMapCompatibilityTest MAPS_DIR MAP_ID");
        Path maps = Paths.get(args[0]);
        MapData map = MapData.load(args[1], maps);
        if (map.w < 32 || map.h < 32) throw new AssertionError("Map dimensions are unexpectedly small");
        if (!map.hasPoliticalLayers() || map.titles == null) throw new AssertionError("Political title layers were not loaded");
        String[] expectedTiers = {"province", "region", "state"};
        if (map.titles.tiers.size() != expectedTiers.length)
            throw new AssertionError("Expected administrative tiers: province, region and state");
        for (int i = 0; i < expectedTiers.length; i++) {
            if (!expectedTiers[i].equals(map.titles.tiers.get(i).key))
                throw new AssertionError("Unexpected title tier at index " + i + ": " + map.titles.tiers.get(i).key);
        }
        int provinceSamples = 0;
        for (int z = 0; z < map.h; z++) {
            for (int x = 0; x < map.w; x++) {
                if (map.provinceAt((x + 0.5) * map.bpp - map.w * map.bpp / 2.0,
                                   (z + 0.5) * map.bpp - map.h * map.bpp / 2.0) != null) {
                    provinceSamples++;
                }
            }
        }
        if (provinceSamples == 0) throw new AssertionError("No provinces are queryable through MapData");
        int provinces = 0, regions = 0, states = 0;
        for (TitleData.Title title : map.titles.allTitles()) {
            if (title.tier == 0) {
                provinces++;
                TitleData.Title parent = map.titles.get(title.parent);
                if (parent == null || parent.tier != 1) throw new AssertionError("Province is not inside a region: " + title.key);
            } else if (title.tier == 1) {
                regions++;
                TitleData.Title parent = map.titles.get(title.parent);
                if (parent == null || parent.tier != 2) throw new AssertionError("Region is not inside a state: " + title.key);
            } else if (title.tier == 2) {
                states++;
                if (title.parent != 0) throw new AssertionError("State must be a sovereign root: " + title.key);
            }
        }
        if (provinces == 0 || regions == 0 || states == 0 || regions < states)
            throw new AssertionError("Administrative honeycomb is incomplete: provinces=" + provinces + ", regions=" + regions + ", states=" + states);
        TerrainSampler sampler = new TerrainSampler(map, 1.0f);
        int[] spawn = sampler.findSpawn();
        TerrainSampler.Col column = sampler.sample(spawn[0], spawn[2]);
        if (column.top < map.minY || column.top > map.maxY) throw new AssertionError("Spawn terrain height is outside world bounds");
        System.out.println("IMAGE_WORLD_MAP_COMPATIBILITY=PASS");
        System.out.println("MAP_SIZE=" + map.w + "x" + map.h);
        System.out.println("POLITICAL_PROVINCE_PIXELS=" + provinceSamples);
        System.out.println("TITLE_TIERS=" + map.titles.tiers.size());
        System.out.println("ADMIN_HONEYCOMB=PASS provinces=" + provinces + " regions=" + regions + " states=" + states);
        System.out.println("SPAWN=" + spawn[0] + "," + spawn[1] + "," + spawn[2]);
    }
}
