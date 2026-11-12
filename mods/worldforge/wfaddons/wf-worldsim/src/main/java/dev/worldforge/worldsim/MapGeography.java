package dev.worldforge.worldsim;

import dev.worldforge.mod.MapData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic geographic evidence extracted from a WorldForge map package.
 *
 * This class deliberately never reads TitleData, RegionData, province labels,
 * realms, rulers or settlement metadata. Climate and fertility are explicitly
 * biome-derived proxies because the current .worldmap format has no separate
 * climate or resource raster. It does not create people, settlements or states.
 */
public final class MapGeography {
    private static final int MAX_SAMPLES = 4096;

    private MapGeography() { }

    public static Map<String, Object> describe(MapData map) {
        if (map == null) throw new IllegalArgumentException("map is required");
        int area = Math.multiplyExact(map.w, map.h);
        int stride = Math.max(1, (int) Math.ceil(Math.sqrt(area / (double) MAX_SAMPLES)));
        List<Map<String, Object>> cells = new ArrayList<>();
        int land = 0, rivers = 0, viable = 0;

        for (int z = 0; z < map.h; z += stride) {
            for (int x = 0; x < map.w; x += stride) {
                int biomeIndex = map.biomeAtPx(x, z);
                String biome = map.biomeKey(biomeIndex);
                int rawHeight = map.rawHeightPx(x, z);
                int elevation = (int) Math.round(map.minY + rawHeight / 65535.0 * (map.maxY - map.minY));
                int river = map.riverAtPx(x, z);
                boolean isLand = elevation >= map.seaLevel + 1 && !isWaterBiome(biome);
                int slope = localRelief(map, x, z, stride);
                int suitability = suitability(biome, elevation, map.seaLevel, slope, river);
                if (isLand) land++;
                if (river > 0) rivers++;
                if (isLand && suitability >= 55) viable++;

                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("x", x);
                cell.put("z", z);
                cell.put("biome", biome);
                cell.put("elevation", elevation);
                cell.put("river", river);
                cell.put("land", isLand);
                cell.put("suitability", suitability);
                cell.put("climate_proxy", climateProxy(biome));
                cells.add(cell);
            }
        }

        List<String> layers = new ArrayList<>(List.of("meta.json", "biomes.png", "height16.png"));
        if (map.hasRiverLayer()) layers.add("rivers.png");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("map_id", map.name);
        result.put("width_px", map.w);
        result.put("height_px", map.h);
        result.put("blocks_per_pixel_milli", Math.max(1L, Math.round(map.bpp * 1000.0)));
        result.put("political_layers_present", map.hasPoliticalLayers());
        result.put("political_layers_ignored", true);
        result.put("sample_stride", stride);
        result.put("sampled_cells", cells.size());
        result.put("land_samples", land);
        result.put("river_samples", rivers);
        result.put("viable_site_samples", viable);
        result.put("source_layers", layers);
        result.put("missing_layers", List.of("climate raster", "resource raster"));
        result.put("cells", cells);
        return result;
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
