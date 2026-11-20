package dev.worldforge.worldsim;

import dev.worldforge.mod.MapData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic natural-geography evidence extracted from a WorldForge map package.
 * Political/title layers are deliberately not read: an image map is a terrain source,
 * not a pre-existing sovereignty source. Climate and fertility are biome-derived
 * proxies because .worldmap has no separate climate or resource raster.
 */
public final class MapGeography {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_SAMPLE_BUDGET = 16_384;
    private static final int MIN_SAMPLE_BUDGET = 4_096;
    private static final int MAX_SAMPLE_BUDGET = 65_536;
    private static final String SAMPLE_BUDGET_PROPERTY = "worldforge.worldsim.map_sample_budget";
    /**
     * Raster inspection can take seconds on a continental image. It must never run on the
     * Minecraft server thread. A single daemon worker and a tiny bounded queue prevent
     * background CPU/memory spikes when several maps are loaded together.
     */
    private static final ThreadPoolExecutor GEOLOGY_WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "WorldForge-Geography");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<MapData, CompletableFuture<JsonObject>> PENDING =
            new WeakHashMap<>();

    private MapGeography() { }

    /**
     * Schedules expensive image sampling once and returns only completed immutable data.
     * A null result means the simulation frame should wait; it never blocks the server tick.
     */
    public static JsonObject snapshotIfReady(MapData map) {
        if (map == null) return null;
        CompletableFuture<JsonObject> future;
        synchronized (PENDING) {
            future = PENDING.get(map);
            if (future == null) {
                try {
                    future = CompletableFuture.supplyAsync(
                            () -> GSON.toJsonTree(describe(map)).getAsJsonObject(), GEOLOGY_WORKER);
                    PENDING.put(map, future);
                } catch (java.util.concurrent.RejectedExecutionException saturated) {
                    return null;
                }
            }
        }
        if (!future.isDone()) return null;
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException failure) {
            synchronized (PENDING) { PENDING.remove(map, future); }
            throw new IllegalStateException("Unable to prepare geography snapshot for map " + map.name,
                    failure.getCause() == null ? failure : failure.getCause());
        }
    }

    /**
     * Image-backed worlds may be much larger than the original prototype maps.
     * The budget is configurable per server; it bounds snapshot size, not map extent.
     */
    private static int sampleBudget() {
        String configured = System.getProperty(SAMPLE_BUDGET_PROPERTY);
        if (configured == null || configured.isBlank()) return DEFAULT_SAMPLE_BUDGET;
        try {
            return Math.max(MIN_SAMPLE_BUDGET,
                    Math.min(MAX_SAMPLE_BUDGET, Integer.parseInt(configured.trim())));
        } catch (NumberFormatException ignored) {
            return DEFAULT_SAMPLE_BUDGET;
        }
    }

    public static Map<String, Object> describe(MapData map) {
        if (map == null) throw new IllegalArgumentException("map is required");
        long area = Math.multiplyExact((long) map.w, (long) map.h);
        int budget = sampleBudget();
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt(area / (double) budget)));
        Set<Long> foundingPixels = new HashSet<>();
        for (MapData.SettlementSeed seed : map.settlementSeeds) {
            if (seed.pixelX() >= 0 && seed.pixelX() < map.w && seed.pixelZ() >= 0 && seed.pixelZ() < map.h)
                foundingPixels.add(coordinateKey(seed.pixelX(), seed.pixelZ()));
        }

        List<Map<String, Object>> cells = new ArrayList<>();
        int land = 0, rivers = 0, viable = 0, wildernessLand = 0, inhabitedLand = 0, foundingSites = 0;
        for (int z = 0; z < map.h; z += stride) {
            for (int x = 0; x < map.w; x += stride) {
                boolean foundingSite = foundingPixels.remove(coordinateKey(x, z));
                Map<String, Object> cell = geographyCell(map, x, z, stride, foundingSite);
                cells.add(cell);
                boolean isLand = Boolean.TRUE.equals(cell.get("land"));
                boolean wilderness = Boolean.TRUE.equals(cell.get("wilderness"));
                int river = ((Number) cell.get("river")).intValue();
                int suitability = ((Number) cell.get("suitability")).intValue();
                if (isLand) land++;
                if (wilderness) wildernessLand++;
                if (isLand && !wilderness) inhabitedLand++;
                if (isLand && !wilderness && suitability >= 55) viable++;
                if (river > 0) rivers++;
                if (foundingSite) foundingSites++;
            }
        }

        // Settlement anchors authored by image-worldgen are exact geographic opportunities.
        // Add off-grid anchors to the same immutable native snapshot so the simulation can
        // found them deterministically instead of silently dropping world-blueprint.json.
        for (MapData.SettlementSeed seed : map.settlementSeeds) {
            long key = coordinateKey(seed.pixelX(), seed.pixelZ());
            if (!foundingPixels.remove(key)) continue;
            Map<String, Object> cell = geographyCell(map, seed.pixelX(), seed.pixelZ(), stride, true);
            if (!Boolean.TRUE.equals(cell.get("land")) || Boolean.TRUE.equals(cell.get("wilderness"))) continue;
            cells.add(cell);
            land++;
            inhabitedLand++;
            if (((Number) cell.get("suitability")).intValue() >= 55) viable++;
            if (((Number) cell.get("river")).intValue() > 0) rivers++;
            foundingSites++;
        }

        List<String> layers = new ArrayList<>(List.of("meta.json", "biomes.png", "height16.png"));
        if (map.hasRiverLayer()) layers.add("rivers.png");
        if (map.hasWildernessLayer()) layers.add("wilderness.png");
        if (map.planetProfile != null) layers.add("generation-profile.json");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("map_id", map.name);
        result.put("width_px", map.w);
        result.put("height_px", map.h);
        result.put("blocks_per_pixel_milli", Math.max(1L, Math.round(map.bpp * 1000.0)));
        result.put("political_layers_present", map.hasPoliticalLayers());
        result.put("political_layers_ignored", true);
        result.put("political_ownership_included", false);
        result.put("settlement_level", map.planetProfile == null ? 4 : map.planetProfile.settlementLevel);
        result.put("settlement_profile", map.planetProfile == null ? "legacy-default" : map.planetProfile.settlementProfile);
        result.put("population_density_pct", map.planetProfile == null ? 38 : map.planetProfile.populationDensityPct);
        result.put("wilderness_share_pct", map.planetProfile == null ? 0 : map.planetProfile.wildernessSharePct);
        result.put("wilderness_samples", wildernessLand);
        result.put("inhabited_land_samples", inhabitedLand);
        result.put("founding_site_samples", foundingSites);
        result.put("sample_stride", stride);
        result.put("sample_budget", budget);
        result.put("world_area_pixels", area);
        result.put("sampled_cells", cells.size());
        result.put("land_samples", land);
        result.put("river_samples", rivers);
        result.put("viable_site_samples", viable);
        result.put("source_layers", layers);
        result.put("missing_layers", List.of("climate raster", "resource raster"));
        result.put("cells", cells);
        return result;
    }

    private static long coordinateKey(int x, int z) {
        return ((long) z << 32) | (x & 0xffff_ffffL);
    }

    private static Map<String, Object> geographyCell(
            MapData map, int x, int z, int stride, boolean foundingSite) {
        int biomeIndex = map.biomeAtPx(x, z);
        String biome = map.biomeKey(biomeIndex);
        int rawHeight = map.rawHeightPx(x, z);
        int elevation = (int) Math.round(map.minY + rawHeight / 65535.0 * (map.maxY - map.minY));
        int river = map.riverAtPx(x, z);
        boolean isLand = elevation >= map.seaLevel + 1 && !isWaterBiome(biome);
        boolean wilderness = isLand && map.isWildernessPx(x, z);
        int slope = localRelief(map, x, z, stride);
        int suitability = suitability(biome, elevation, map.seaLevel, slope, river);
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("x", x);
        cell.put("z", z);
        cell.put("biome", biome);
        cell.put("elevation", elevation);
        cell.put("river", river);
        cell.put("land", isLand);
        cell.put("wilderness", wilderness);
        cell.put("founding_site", foundingSite);
        cell.put("suitability", suitability);
        cell.put("owner_realm_id", "");
        cell.put("climate_proxy", climateProxy(biome));
        return cell;
    }

    private static int localRelief(MapData map, int x, int z, int stride) {
        int center = elevation(map, x, z);
        int east = elevation(map, Math.min(map.w - 1, x + stride), z);
        int south = elevation(map, x, Math.min(map.h - 1, z + stride));
        return Math.max(Math.abs(center - east), Math.abs(center - south));
    }

    private static int elevation(MapData map, int x, int z) {
        return (int) Math.round(map.minY + map.rawHeightPx(x, z) / 65535.0 * (map.maxY - map.minY));
    }

    private static int suitability(String biome, int elevation, int seaLevel, int slope, int river) {
        String key = biome == null ? "" : biome.toLowerCase(java.util.Locale.ROOT);
        int score;
        if (key.contains("ocean") || key.contains("sea") || key.equals("river") || key.contains("deep_water")) score = 0;
        else if (key.contains("desert") || key.contains("badlands") || key.contains("wasteland")) score = 18;
        else if (key.contains("mountain") || key.contains("peaks") || key.contains("glacier")) score = 24;
        else if (key.contains("swamp") || key.contains("marsh") || key.contains("wetland")) score = 52;
        else if (key.contains("forest") || key.contains("wood")) score = 70;
        else if (key.contains("plains") || key.contains("grassland") || key.contains("meadow")) score = 82;
        else if (key.contains("savanna") || key.contains("steppe")) score = 62;
        else if (key.contains("tundra") || key.contains("snow")) score = 36;
        else score = 55;

        if (elevation >= seaLevel + 2 && elevation <= seaLevel + 48) score += 8;
        else if (elevation > seaLevel + 110) score -= 18;
        if (river == 255) score += 12;
        else if (river == 160) score += 7;
        if (slope <= 3) score += 8;
        else if (slope >= 24) score -= 20;
        return Math.max(0, Math.min(100, score));
    }

    private static String climateProxy(String biome) {
        String key = biome == null ? "" : biome.toLowerCase(java.util.Locale.ROOT);
        if (key.contains("desert") || key.contains("badlands") || key.contains("savanna")) return "arid-biome-proxy";
        if (key.contains("snow") || key.contains("frozen") || key.contains("glacier") || key.contains("tundra")) return "cold-biome-proxy";
        if (key.contains("swamp") || key.contains("marsh") || key.contains("jungle")) return "humid-biome-proxy";
        return "temperate-biome-proxy";
    }

    private static boolean isWaterBiome(String biome) {
        String key = biome == null ? "" : biome.toLowerCase(java.util.Locale.ROOT);
        return key.contains("ocean") || key.contains("sea") || key.equals("river") || key.contains("deep_water");
    }
}
