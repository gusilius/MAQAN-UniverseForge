package dev.worldforge.mod;

import java.util.List;
import java.util.Map;

/** Optional, versioned Atlas-to-WorldForge profile embedded in a .worldmap package. */
public final class PlanetWorldProfile {
    public final String format;
    public final String planetId;
    public final long seed;
    public final String generationMode;
    public final String sourceImageSha256;
    public final int settlementLevel;
    public final String settlementProfile;
    public final int widthPx;
    public final int heightPx;
    public final double blocksPerPixel;

    private PlanetWorldProfile(String format, String planetId, long seed, String generationMode,
                               String sourceImageSha256, int settlementLevel, String settlementProfile,
                               int widthPx, int heightPx, double blocksPerPixel) {
        this.format = format;
        this.planetId = planetId;
        this.seed = seed;
        this.generationMode = generationMode;
        this.sourceImageSha256 = sourceImageSha256;
        this.settlementLevel = settlementLevel;
        this.settlementProfile = settlementProfile;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.blocksPerPixel = blocksPerPixel;
    }

    @SuppressWarnings("unchecked")
    public static PlanetWorldProfile parse(byte[] json) {
        if (json == null || json.length == 0) return null;
        Object parsed = Json.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("generation-profile.json must be an object");
        Map<String, Object> root = (Map<String, Object>) parsed;
        String format = string(root, "format", "maqan-planet-world-profile/1");
        if (!"maqan-planet-world-profile/1".equals(format))
            throw new IllegalArgumentException("Unsupported planet profile format: " + format);
        String mode = string(root, "generation_mode", "procedural");
        if (!"image".equals(mode) && !"procedural".equals(mode))
            throw new IllegalArgumentException("Invalid planet generation mode: " + mode);
        List<Object> size = (List<Object>) root.get("size_px");
        int width = size == null || size.size() < 2 ? 0 : number(size.get(0), 0).intValue();
        int height = size == null || size.size() < 2 ? 0 : number(size.get(1), 0).intValue();
        int level = number(root.get("settlement_level"), 4).intValue();
        double bpp = number(root.get("blocks_per_pixel"), 16).doubleValue();
        if (level < 1 || level > 7) throw new IllegalArgumentException("Settlement level must be 1..7");
        if (width < 1 || height < 1 || bpp <= 0) throw new IllegalArgumentException("Invalid planet profile dimensions");
        return new PlanetWorldProfile(format, string(root, "planet_id", ""),
                number(root.get("seed"), 0).longValue(), mode, string(root, "source_image_sha256", null),
                level, string((Map<String, Object>) root.get("settlement_profile"), "key", ""),
                width, height, bpp);
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static Number number(Object value, Number fallback) {
        return value instanceof Number ? (Number) value : fallback;
    }
}
