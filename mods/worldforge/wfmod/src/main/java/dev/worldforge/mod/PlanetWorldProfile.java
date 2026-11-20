package dev.worldforge.mod;

import java.util.List;
import java.util.Map;

/** Versioned Atlas-to-WorldForge generation profile embedded in a .worldmap package. */
public final class PlanetWorldProfile {
    public final String format;
    public final String planetId;
    public final long seed;
    public final String generationMode;
    public final String sourceImageSha256;
    public final int settlementLevel;
    public final String settlementProfile;
    public final int populationDensityPct;
    public final int wildernessSharePct;
    public final int widthPx;
    public final int heightPx;
    public final double blocksPerPixel;

    private PlanetWorldProfile(String format, String planetId, long seed, String generationMode,
                               String sourceImageSha256, int settlementLevel, String settlementProfile,
                               int populationDensityPct, int wildernessSharePct,
                               int widthPx, int heightPx, double blocksPerPixel) {
        this.format = format;
        this.planetId = planetId;
        this.seed = seed;
        this.generationMode = generationMode;
        this.sourceImageSha256 = sourceImageSha256;
        this.settlementLevel = settlementLevel;
        this.settlementProfile = settlementProfile;
        this.populationDensityPct = populationDensityPct;
        this.wildernessSharePct = wildernessSharePct;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.blocksPerPixel = blocksPerPixel;
    }

    @SuppressWarnings("unchecked")
    public static PlanetWorldProfile parse(byte[] json) {
        if (json == null || json.length == 0) return null;
        Object parsed = Json.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw))
            throw new IllegalArgumentException("generation-profile.json must be an object");
        Map<String, Object> root = (Map<String, Object>) raw;
        String format = string(root, "format", "maqan-planet-world-profile/1");
        if (!"maqan-planet-world-profile/1".equals(format))
            throw new IllegalArgumentException("Unsupported planet profile format: " + format);
        String mode = string(root, "generation_mode", "procedural");
        if (!"image".equals(mode) && !"procedural".equals(mode))
            throw new IllegalArgumentException("Invalid planet generation mode: " + mode);
        List<Object> size = root.get("size_px") instanceof List<?> list ? (List<Object>) list : List.of();
        int width = size.size() < 2 ? 0 : number(size.get(0), 0).intValue();
        int height = size.size() < 2 ? 0 : number(size.get(1), 0).intValue();
        int level = number(root.get("settlement_level"), 4).intValue();
        double bpp = number(root.get("blocks_per_pixel"), 16).doubleValue();
        Map<String, Object> density = root.get("settlement_profile") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        int populationDensity = percent(density.get("population_density"), 38);
        int wildernessShare = percent(density.get("wilderness_share"), 0);
        if (level < 1 || level > 7) throw new IllegalArgumentException("Settlement level must be 1..7");
        if (width < 1 || height < 1 || !Double.isFinite(bpp) || bpp <= 0)
            throw new IllegalArgumentException("Invalid planet profile dimensions");
        return new PlanetWorldProfile(format, string(root, "planet_id", ""), number(root.get("seed"), 0).longValue(),
                mode, string(root, "source_image_sha256", null), level,
                string(density, "key", ""), populationDensity, wildernessShare, width, height, bpp);
    }

    private static int percent(Object value, int fallback) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return fallback;
        return Math.max(0, Math.min(100, (int) Math.round(number.doubleValue() * 100.0)));
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static Number number(Object value, Number fallback) {
        return value instanceof Number ? (Number) value : fallback;
    }
}
