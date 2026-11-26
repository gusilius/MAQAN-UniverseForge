package dev.worldforge.mod;

import java.util.List;
import java.util.Map;

/** Stable planet-body profile, independent of Minecraft dimension identity. */
public final class PlanetWorldProfile {
    public final String format, planetId, generationMode, sourceImageSha256, settlementProfile, displayName;
    public final long seed;
    public final int settlementLevel, populationDensityPct, wildernessSharePct;
    public final int widthPx, heightPx, seaLevel, minY, maxY;
    public final double blocksPerPixel;

    private PlanetWorldProfile(String format, String planetId, long seed, String generationMode,
            String sourceImageSha256, int settlementLevel, String settlementProfile,
            int populationDensityPct, int wildernessSharePct, int widthPx, int heightPx,
            double blocksPerPixel, int seaLevel, int minY, int maxY, String displayName) {
        this.format=format; this.planetId=planetId; this.seed=seed; this.generationMode=generationMode;
        this.sourceImageSha256=sourceImageSha256; this.settlementLevel=settlementLevel;
        this.settlementProfile=settlementProfile; this.populationDensityPct=populationDensityPct;
        this.wildernessSharePct=wildernessSharePct; this.widthPx=widthPx; this.heightPx=heightPx;
        this.blocksPerPixel=blocksPerPixel; this.seaLevel=seaLevel; this.minY=minY; this.maxY=maxY;
        this.displayName=displayName;
    }

    @SuppressWarnings("unchecked")
    public static PlanetWorldProfile parse(byte[] json) {
        if (json == null || json.length == 0) return null;
        Object parsed=Json.parse(new String(json, java.nio.charset.StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?,?> raw)) throw new IllegalArgumentException("planet profile must be an object");
        Map<String,Object> root=(Map<String,Object>)raw;
        String format=string(root,"format","maqan-planet-world-profile/1");
        if (!"maqan-planet-world-profile/1".equals(format)) throw new IllegalArgumentException("Unsupported planet profile format: "+format);
        String mode=string(root,"generation_mode","procedural");
        if (!"image".equals(mode) && !"procedural".equals(mode)) throw new IllegalArgumentException("Invalid planet generation mode: "+mode);
        List<Object> size=root.get("size_px") instanceof List<?> list ? (List<Object>)list : List.of();
        int defaultSize="procedural".equals(mode)?16384:0;
        int width=size.size()<2?defaultSize:number(size.get(0),defaultSize).intValue();
        int height=size.size()<2?defaultSize:number(size.get(1),defaultSize).intValue();
        int level=number(root.get("settlement_level"),4).intValue();
        double bpp=number(root.get("blocks_per_pixel"),16).doubleValue();
        Map<String,Object> density=root.get("settlement_profile") instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
        Map<String,Object> surface=root.get("surface") instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
        int population=percent(density.get("population_density"),38);
        int wilderness=percent(density.get("wilderness_share"),25);
        int sea=number(surface.get("sea_level"),number(root.get("sea_level"),63)).intValue();
        int minY=number(surface.get("min_y"),number(root.get("min_y"),-64)).intValue();
        int maxY=number(surface.get("max_y"),number(root.get("max_y"),320)).intValue();
        String id=string(root,"planet_id","").trim();
        if ("procedural".equals(mode) && id.isBlank())
            throw new IllegalArgumentException("planet_id is required and must be stable");
        if (level<1 || level>7 || width<("procedural".equals(mode)?256:1)
                || height<("procedural".equals(mode)?256:1) || !Double.isFinite(bpp)
                || bpp<=0 || bpp>4096 || minY>=sea || sea>=maxY || maxY-minY>4096
                || (double)width*bpp>59_999_968.0 || (double)height*bpp>59_999_968.0)
            throw new IllegalArgumentException("Invalid planet surface dimensions or vertical range");
        return new PlanetWorldProfile(format,id,number(root.get("seed"),0).longValue(),mode,
                string(root,"source_image_sha256",null),level,string(density,"key",""),
                population,wilderness,width,height,bpp,sea,minY,maxY,string(root,"display_name",id));
    }

    private static int percent(Object value,int fallback) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) return fallback;
        double raw=n.doubleValue();
        return Math.max(0,Math.min(100,(int)Math.round(raw<=1.0?raw*100.0:raw)));
    }
    private static String string(Map<String,Object> map,String key,String fallback) {
        Object value=map.get(key); return value instanceof String ? (String)value : fallback;
    }
    private static Number number(Object value,Number fallback) { return value instanceof Number ? (Number)value : fallback; }
}
