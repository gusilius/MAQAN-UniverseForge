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
        if (map.hasPoliticalLayers() || map.titles == null || !map.titles.realms.isEmpty())
            throw new AssertionError("Image package must contain geography only, never pre-authored political entities");
        String[] expectedTiers = {"province", "region"};
        if (map.titles.tiers.size() != expectedTiers.length)
            throw new AssertionError("Expected only neutral geographic tiers: province and region");
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
        int provinces = 0, regions = 0;
        for (TitleData.Title title : map.titles.allTitles()) {
            if (title.tier == 0) {
                provinces++;
                TitleData.Title parent = map.titles.get(title.parent);
                if (parent == null || parent.tier != 1) throw new AssertionError("Province is not inside a region: " + title.key);
            } else if (title.tier == 1) {
                regions++;
                if (title.parent != 0) throw new AssertionError("Geographic region must not be nested under a state: " + title.key);
            } else {
                throw new AssertionError("Unexpected pre-authored political title tier: " + title.tier);
            }
        }
        if (provinces == 0 || regions == 0)
            throw new AssertionError("Geographic hierarchy is incomplete: provinces=" + provinces + ", regions=" + regions);
        if (map.settlementSeeds.isEmpty())
            throw new AssertionError("Actual image blueprint has no geographic settlement-site candidates");
        java.util.Set<String> settlementIds = new java.util.HashSet<>();
        for (MapData.SettlementSeed settlement : map.settlementSeeds) {
            if (settlement.id().isBlank() || !settlementIds.add(settlement.id()))
                throw new AssertionError("Settlement IDs must be non-empty and unique: " + settlement.id());
            if (!settlement.name().isBlank() || !settlement.realmId().isBlank() || !"site".equals(settlement.type()))
                throw new AssertionError("Image package must provide unnamed, unowned site candidates: " + settlement.id());
            TitleData.Title province = map.titles.get(settlement.provinceId());
            if (province == null || province.tier != 0)
                throw new AssertionError("Settlement is not linked to a real province: " + settlement.id());
            if (map.titles.realmOf(province) != null)
                throw new AssertionError("A geographic province unexpectedly has sovereign ownership: " + settlement.id());
        }
        System.out.println("Actual map unnamed settlement-site candidates: " + map.settlementSeeds.size()
                + ", unique IDs: " + settlementIds.size());
        TerrainSampler sampler = new TerrainSampler(map, 1.0f);
        int[] spawn = sampler.findSpawn();
        TerrainSampler.Col column = sampler.sample(spawn[0], spawn[2]);
        if (column.top < map.minY || column.top > map.maxY) throw new AssertionError("Spawn terrain height is outside world bounds");
        System.out.println("IMAGE_WORLD_MAP_COMPATIBILITY=PASS");
        System.out.println("MAP_SIZE=" + map.w + "x" + map.h);
        System.out.println("GEOGRAPHIC_PROVINCE_PIXELS=" + provinceSamples);
        System.out.println("TITLE_TIERS=" + map.titles.tiers.size());
        System.out.println("GEOGRAPHIC_HIERARCHY=PASS provinces=" + provinces + " regions=" + regions + " pre_authored_realms=0");
        System.out.println("SPAWN=" + spawn[0] + "," + spawn[1] + "," + spawn[2]);
    }
}
