package dev.worldforge.mod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Пакет карты мира: biomes.png, height16.png, rivers.png и meta.json (создаёт инструмент worldforge).
 * Не зависит от Minecraft, поэтому его можно проверять отдельно.
 * Карта центрирована в точке мира (0, 0); за краем карты продолжается ближайший пиксель (обычно океан).
 */
public final class MapData {
    /** Stable map/planet resource ID referenced by worldforge:image. */
    public final String name;
    /** Human-readable planet name from meta.json display_name; never used as an identifier. */
    public final String displayName;
    public final int w, h;
    public final double bpp;                 // блоков на пиксель
    public final int seaLevel, minY, maxY;
    public final String[] keys;              // ключ биома по индексу (например "desert")
    public final int[] biomeRgb;             // цвет биома из meta.json (для рисования карты)
    public final String[][] candidates;      // ID биомов Minecraft по приоритету
    public final double[] spawnNorm;         // точка спавна в долях картинки или null
    public final RegionData regions;         // политическая карта (может быть null)
    public final byte[] regionMap;           // 128x128 байт для карты-предмета (может быть null)
    public volatile TitleData titles;        // иерархия титулов и государства; создаётся из рельефа, если пакет их не содержит
    /** Atlas/WorldForge seven-level settlement and planet-scale profile, if supplied. */
    public final PlanetWorldProfile planetProfile;
    public final Map<String, byte[]> mapItems; // карты-предметы по уровням и "realms"
    /** Named settlement anchors authored by image-worldgen in world-blueprint.json. */
    public record SettlementSeed(String id, String type, String name, String realmId, int provinceId,
                                 int pixelX, int pixelZ, int blockX, int blockZ) {}
    public final List<SettlementSeed> settlementSeeds;

    private static final String[] FILES = {"meta.json", "biomes.png", "height16.png", "rivers.png", "wilderness.png", "regions.png", "regions.json", "regionmap.bin", "provinces.png", "titles.json", "realms.json", "world-blueprint.json", "generation-profile.json"};

    // Core terrain rasters are decoded on demand into a bounded LRU of 256x256 tiles.
    private final RasterTileLayer height;
    private final RasterTileLayer biome;
    /** 255 marks land intentionally left wild by the selected occupancy profile. */
    private final RasterTileLayer wilderness;
    private final RasterTileLayer river;     // 0 суша, 160 озеро, 255 река (может быть null)
    private final Noise warp = new Noise(0x5EED1234L);

    private MapData(String name, String displayName, int w, int h, double bpp, int sea, int minY, int maxY, String[] keys, int[] biomeRgb,
                    String[][] cand, double[] spawn, RasterTileLayer height, RasterTileLayer biome, RasterTileLayer river,
                    RegionData regions, byte[] regionMap, TitleData titles, RasterTileLayer wilderness,
                    PlanetWorldProfile planetProfile, Map<String, byte[]> mapItems,
                    List<SettlementSeed> settlementSeeds) {
        this.name = name;
        this.displayName = displayName == null || displayName.isBlank() ? name : displayName;
        this.w = w;
        this.h = h;
        this.bpp = bpp;
        this.seaLevel = sea;
        this.minY = minY;
        this.maxY = maxY;
        this.keys = keys;
        this.biomeRgb = biomeRgb;
        this.candidates = cand;
        this.spawnNorm = spawn;
        this.height = height;
        this.biome = biome;
        this.river = river;
        this.regions = regions;
        this.regionMap = regionMap;
        this.titles = titles;
        this.wilderness = wilderness;
        this.planetProfile = planetProfile;
        this.mapItems = mapItems;
        this.settlementSeeds = List.copyOf(settlementSeeds);
    }

    // ------------------------------------------------------------------ загрузка

    /** Источник файлов пакета: папка, zip или ресурсы датапака. Возвращает null, если файла нет. */
    public interface Source {
        byte[] read(String file) throws IOException;
    }

    /** Загрузка из config/worldforge/maps: <имя>.worldmap (zip) или папка <имя>/ (можно с вложенной out/). */
    /** Procedural planet using the same MapData/TerrainSampler/chunk generator path as image worlds. */
    public static MapData procedural(String sourceId, PlanetWorldProfile profile) {
        if (profile == null || !"procedural".equals(profile.generationMode))
            throw new IllegalArgumentException("A procedural planet profile is required");
        ProceduralPlanetTerrain terrain = new ProceduralPlanetTerrain(profile);
        int w=profile.widthPx,h=profile.heightPx;
        String[] keys={"deep_ocean","shallow_sea","beach","desert","mountains",
                "forest","plains","snowy_mountains","taiga","savanna","glacier"};
        String[][] candidates={
                {"minecraft:deep_ocean","minecraft:ocean"},{"minecraft:ocean","minecraft:beach"},
                {"minecraft:beach"},{"minecraft:desert"},{"minecraft:windswept_hills","minecraft:mountains"},
                {"minecraft:forest"},{"minecraft:plains"},{"minecraft:snowy_slopes","minecraft:jagged_peaks"},
                {"minecraft:taiga"},{"minecraft:savanna"},{"minecraft:ice_spikes"}};
        int[] rgb={0x244B78,0x397DAA,0xD8C58B,0xD8B66A,0x817D78,
                0x397348,0x8DB360,0xD8E5EA,0x587B67,0xB7A34A,0xDDEEFF};
        RasterTileLayer height=RasterTileLayer.procedural(w,h,"procedural-height16",terrain::heightSample);
        RasterTileLayer biome=RasterTileLayer.procedural(w,h,"procedural-biomes",terrain::biomeSample);
        RasterTileLayer river=RasterTileLayer.procedural(w,h,"procedural-rivers",terrain::riverSample);
        RasterTileLayer wilderness=RasterTileLayer.procedural(w,h,"procedural-wilderness",terrain::wildernessSample);
        int[] spawnPixel=terrain.findSpawn();
        double[] spawn={spawnPixel[0]/(double)w,spawnPixel[1]/(double)h};
        return new MapData(sourceId,profile.displayName,w,h,profile.blocksPerPixel,
                profile.seaLevel,profile.minY,profile.maxY,keys,rgb,candidates,spawn,
                height,biome,river,null,null,null,wilderness,profile,Map.of(),List.of());
    }

    public static MapData load(String name, Path mapsDir) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        Path zip = mapsDir.resolve(name + ".worldmap");
        Path dir = mapsDir.resolve(name);
        if (Files.isDirectory(dir.resolve("out"))) dir = dir.resolve("out"); // можно указать папку проекта worldforge
        if (Files.isRegularFile(zip)) {
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) files.put(e.getName(), zin.readAllBytes());
            }
        } else if (Files.isDirectory(dir)) {
            for (String n : FILES) {
                Path p = dir.resolve(n);
                if (Files.isRegularFile(p)) files.put(n, Files.readAllBytes(p));
            }
            try (var ls = Files.list(dir)) {
                for (Path p : (Iterable<Path>) ls::iterator) {
                    String fn = p.getFileName().toString();
                    if (fn.startsWith("mapitem_") && fn.endsWith(".bin")) files.put(fn, Files.readAllBytes(p));
                }
            }
        } else {
            throw new FileNotFoundException("Карта '" + name + "' не найдена: ожидается " + zip + " или папка " + dir);
        }
        return load(name, files::get);
    }

    /** Запасная карта: плоская равнина 64x64 (по 16 блоков на пиксель). Нужна, чтобы отсутствие файла карты не роняло сервер. */
    public static MapData fallback(String name) {
        try {
            int n = 64;
            java.awt.image.BufferedImage hi = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_USHORT_GRAY);
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) hi.getRaster().setSample(x, z, 0, 22870);
            ByteArrayOutputStream ho = new ByteArrayOutputStream(), bo = new ByteArrayOutputStream();
            ImageIO.write(hi, "png", ho);
            ImageIO.write(bi, "png", bo);
            String meta = "{\"size_px\":[64,64],\"blocks_per_pixel\":16,\"sea_level\":63,\"min_y\":-64,\"max_y\":320,"
                    + "\"biomes\":[{\"index\":0,\"key\":\"plains\",\"color\":\"#8db360\",\"minecraft_id\":\"minecraft:plains\"}],"
                    + "\"spawn\":[0.5,0.5]}";
            Map<String, byte[]> f = new HashMap<>();
            f.put("meta.json", meta.getBytes(StandardCharsets.UTF_8));
            f.put("height16.png", ho.toByteArray());
            f.put("biomes.png", bo.toByteArray());
            return load(name, f::get);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MapData load(String name, Source src) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        for (String n : FILES) {
            byte[] data = src.read(n);
            if (data != null) files.put(n, data);
        }
        for (String n : new String[]{"meta.json", "biomes.png", "height16.png"}) {
            if (!files.containsKey(n)) throw new FileNotFoundException("В карте '" + name + "' нет файла " + n);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) Json.parse(new String(files.get("meta.json"), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<Object> size = (List<Object>) meta.get("size_px");
        int w = ((Number) size.get(0)).intValue(), h = ((Number) size.get(1)).intValue();
        double bpp = ((Number) meta.get("blocks_per_pixel")).doubleValue();
        int sea = ((Number) meta.get("sea_level")).intValue();
        int minY = ((Number) meta.get("min_y")).intValue(), maxY = ((Number) meta.get("max_y")).intValue();
        String displayName = meta.get("display_name") instanceof String value && !value.isBlank() ? value : name;
        Object configuredPlanetId = meta.get("planet_id");
        // planet_id is a celestial-body identity, independent of a surface/dimension resource key.

        @SuppressWarnings("unchecked")
        List<Object> bl = (List<Object>) meta.get("biomes");
        String[] keys = new String[bl.size()];
        String[][] cand = new String[bl.size()][];
        int[] rgb = new int[bl.size()];
        for (int i = 0; i < bl.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) bl.get(i);
            int idx = ((Number) b.get("index")).intValue();
            keys[idx] = (String) b.get("key");
            rgb[idx] = b.get("color") instanceof String ? Integer.parseInt(((String) b.get("color")).substring(1), 16) : 0x808080;
            @SuppressWarnings("unchecked")
            List<Object> c = (List<Object>) b.get("candidates");
            if (c == null) {
                cand[idx] = new String[]{(String) b.get("minecraft_id")};
            } else {
                cand[idx] = new String[c.size()];
                for (int j = 0; j < c.size(); j++) cand[idx][j] = (String) c.get(j);
            }
        }
        double[] spawn = null;
        if (meta.get("spawn") instanceof List) {
            List<?> sp = (List<?>) meta.get("spawn");
            spawn = new double[]{((Number) sp.get(0)).doubleValue(), ((Number) sp.get(1)).doubleValue()};
        }

        RasterTileLayer height = RasterTileLayer.load(files.get("height16.png"), w, h, "height16.png");
        RasterTileLayer biome = RasterTileLayer.load(files.get("biomes.png"), w, h, "biomes.png");
        RasterTileLayer wilderness = files.containsKey("wilderness.png")
                ? RasterTileLayer.load(files.get("wilderness.png"), w, h, "wilderness.png") : null;
        RasterTileLayer river = files.containsKey("rivers.png")
                ? RasterTileLayer.load(files.get("rivers.png"), w, h, "rivers.png") : null;
        RegionData regions = null;
        byte[] regionMap = null;
        if (files.containsKey("regions.png") && files.containsKey("regions.json")) {
            RasterTileLayer labels = RasterTileLayer.load(files.get("regions.png"), w, h, "regions.png");
            regions = new RegionData(new String(files.get("regions.json"), StandardCharsets.UTF_8), labels, w, h);
        }
        byte[] rm = files.get("regionmap.bin");
        if (rm != null && rm.length == 16384) regionMap = rm;
        TitleData titles = null;
        Map<String, byte[]> mapItems = new HashMap<>();
        if (files.containsKey("provinces.png") && files.containsKey("titles.json")) {
            Raster pr = decode(files.get("provinces.png"), w, h, "provinces.png");
            short[] lab = new short[w * h];
            for (int z = 0; z < h; z++) for (int x = 0; x < w; x++) lab[z * w + x] = (short) pr.getSample(x, z, 0);
            byte[] rj = files.get("realms.json");
            titles = new TitleData(new String(files.get("titles.json"), StandardCharsets.UTF_8),
                    rj == null ? null : new String(rj, StandardCharsets.UTF_8), lab, w, h);
            List<String> names = new java.util.ArrayList<>();
            for (TitleData.Tier t : titles.tiers) names.add(t.key);
            names.add("realms");
            for (String n : names) {
                byte[] b = src.read("mapitem_" + n + ".bin");
                if (b != null && b.length == 16384) mapItems.put(n, b);
            }
        }
        List<SettlementSeed> settlementSeeds = new java.util.ArrayList<>();
        byte[] blueprintBytes = files.get("world-blueprint.json");
        if (blueprintBytes != null) {
            Object parsedBlueprint = Json.parse(new String(blueprintBytes, StandardCharsets.UTF_8));
            if (!(parsedBlueprint instanceof Map<?, ?> blueprint)
                    || !"maqan-world-blueprint/2".equals(String.valueOf(blueprint.get("format"))))
                throw new IOException("Некорректный world-blueprint.json в карте '" + name + "'");
            if (blueprint.get("settlements") instanceof List<?> rows) {
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> item) || !(item.get("block") instanceof List<?> block)
                            || block.size() < 2 || !(item.get("pixel") instanceof List<?> pixel) || pixel.size() < 2
                            || !(item.get("province_id") instanceof Number) || !(block.get(0) instanceof Number)
                            || !(block.get(1) instanceof Number) || !(pixel.get(0) instanceof Number)
                            || !(pixel.get(1) instanceof Number))
                        throw new IOException("Некорректная запись поселения в world-blueprint.json карты '" + name + "'");
                    String id = String.valueOf(item.get("id"));
                    if (id.isBlank() || !ids.add(id)) throw new IOException("Пустой или повторяющийся ID поселения: " + id);
                    settlementSeeds.add(new SettlementSeed(id, String.valueOf(item.get("type")),
                            String.valueOf(item.get("name")), String.valueOf(item.get("realm_id")),
                            ((Number) item.get("province_id")).intValue(),
                            ((Number) pixel.get(0)).intValue(), ((Number) pixel.get(1)).intValue(),
                            ((Number) block.get(0)).intValue(), ((Number) block.get(1)).intValue()));
                }
            }
        }
        PlanetWorldProfile planetProfile = PlanetWorldProfile.parse(files.get("generation-profile.json"));
        if (planetProfile != null) {
            if (planetProfile.widthPx != w || planetProfile.heightPx != h
                    || Math.abs(planetProfile.blocksPerPixel - bpp) > 0.0001)
                throw new IOException("generation-profile.json dimensions/scale do not match meta.json for map '" + name + "'");
        }
        return new MapData(name, displayName, w, h, bpp, sea, minY, maxY, keys, rgb, cand, spawn,
                height, biome, river, regions, regionMap, titles, wilderness, planetProfile, mapItems, settlementSeeds);
    }

    private static Raster decode(byte[] png, int w, int h, String what) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("Не удалось прочитать " + what);
        if (img.getWidth() != w || img.getHeight() != h) {
            throw new IOException(what + ": размер " + img.getWidth() + "x" + img.getHeight() + ", а в meta.json " + w + "x" + h);
        }
        return img.getRaster();
    }

    // ------------------------------------------------------------------ выборка

    private int clampX(int x) {
        return x < 0 ? 0 : (x >= w ? w - 1 : x);
    }

    private int clampZ(int z) {
        return z < 0 ? 0 : (z >= h ? h - 1 : z);
    }

    private double rawY(int x, int z) {
        int v = height.sample(clampX(x), clampZ(z));
        return minY + v / 65535.0 * (maxY - minY);
    }

    /** Высота поверхности (блоки Y) в точке мира с билинейной интерполяцией. */
    public double yAt(double bx, double bz) {
        double px = bx / bpp + w * 0.5 - 0.5, pz = bz / bpp + h * 0.5 - 0.5;
        int x0 = (int) Math.floor(px), z0 = (int) Math.floor(pz);
        double fx = px - x0, fz = pz - z0;
        double a = rawY(x0, z0), b = rawY(x0 + 1, z0), c = rawY(x0, z0 + 1), d = rawY(x0 + 1, z0 + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private long pixelIndex(double bx, double bz) {
        double wx = warp.noise2(bx * 0.045, bz * 0.045) * bpp * 1.4;
        double wz = warp.noise2(bx * 0.045 + 100, bz * 0.045 - 100) * bpp * 1.4;
        int px = clampX((int) Math.floor((bx + wx) / bpp + w * 0.5));
        int pz = clampZ((int) Math.floor((bz + wz) / bpp + h * 0.5));
        return (long) pz * w + px;
    }

    private int sampleIndex(RasterTileLayer layer, long index) {
        return layer.sample((int) (index % w), (int) (index / w));
    }

    /** True only when the source package actually supplied rivers.png. */
    /** Build deterministic starter titles/realms once when a terrain package has no political layer. */
    public synchronized boolean ensurePoliticalData() {
        if (titles != null) return false;
        titles = PoliticalMapBootstrap.generate(this);
        PoliticalMapRenderer.regenerateAll(this);
        return true;
    }

    public boolean hasRiverLayer() { return river != null; }

    /** Whether this pixel is intentionally wild and excluded from initial civilization materialization. */
    public boolean isWildernessPx(int x, int z) {
        if (wilderness == null) return false;
        return wilderness.sample(clampX(x), clampZ(z)) >= 128;
    }

    public boolean hasWildernessLayer() { return wilderness != null; }

    /** True only when the package contains actual sovereignty, not neutral province/region geography. */
    public boolean hasPoliticalLayers() {
        return titles != null && (titles.realms.values().stream()
                .anyMatch(realm -> realm != null && realm.id != null && !realm.id.startsWith("emergent-"))
                || titles.allTitles().stream().anyMatch(title -> title != null && title.tier >= 2));
    }

    /** Индекс биома в точке мира; границы слегка «дрожат», чтобы не было квадратов по пикселям. */
    public int biomeIndexAt(double bx, double bz) {
        int i = sampleIndex(biome, pixelIndex(bx, bz));
        return i < keys.length ? i : 0;
    }

    /** 0 суша, 160 озеро, 255 река. */
    public int riverAt(double bx, double bz) {
        return river == null ? 0 : sampleIndex(river, pixelIndex(bx, bz));
    }

    /** Уровень воды реки в блоках Y или -1, если здесь нет реки. */
    public int riverY(double bx, double bz) {
        if (river == null) return -1;
        long i = pixelIndex(bx, bz);
        int x = (int) (i % w), z = (int) (i / w);
        if (river.sample(x, z) == 0) return -1;
        // Water level is the local terrain minimum in a 7x7 window. Compute it only
        // for river pixels actually queried by generated chunks, not for every map pixel.
        int minimum = 65535;
        for (int dz = -3; dz <= 3; dz++) {
            int zz = clampZ(z + dz);
            for (int dx = -3; dx <= 3; dx++)
                minimum = Math.min(minimum, height.sample(clampX(x + dx), zz));
        }
        return (int) Math.floor(minY + minimum / 65535.0 * (maxY - minY));
    }

    /** Номер региона в точке мира (0 = нет региона или регионы не заданы). */
    public int regionAt(double bx, double bz) {
        if (regions == null) return 0;
        return regions.labelAt((int) Math.floor(bx / bpp + w * 0.5), (int) Math.floor(bz / bpp + h * 0.5));
    }

    /** Провинция в точке мира (null, если титулов нет или это море). */
    public TitleData.Title provinceAt(double bx, double bz) {
        if (titles == null) return null;
        return titles.provinceAtPixel((int) Math.floor(bx / bpp + w * 0.5), (int) Math.floor(bz / bpp + h * 0.5));
    }

    /** Сырая высота пикселя 0..65535. */
    public int rawHeightPx(int x, int z) {
        return height.sample(clampX(x), clampZ(z));
    }

    public int biomeAtPx(int x, int z) {
        int i = biome.sample(clampX(x), clampZ(z));
        return i < keys.length ? i : 0;
    }

    /** 0 суша, 160 озеро, 255 река. */
    public int riverAtPx(int x, int z) {
        return river == null ? 0 : river.sample(clampX(x), clampZ(z));
    }

    public String biomeKey(int idx) {
        return keys[idx];
    }

    // ------------------------------------------------------------------ спавн

    private static boolean isSpawnable(String key) {
        switch (key) {
            case "deep_ocean": case "ocean": case "shallow_sea": case "frozen_ocean": case "river":
            case "glacier": case "mountains": case "snowy_mountains": case "peaks": case "volcanic_wasteland":
            case "wasteland": case "desert": case "badlands":
                return false;
            default:
                return true;
        }
    }

    /** Блок (x, z) для спавна: из meta.json, иначе ближайшая к центру подходящая суша. */
    public int[] findSpawn() {
        if (spawnNorm != null) {
            return new int[]{(int) Math.round((spawnNorm[0] * w - w * 0.5) * bpp), (int) Math.round((spawnNorm[1] * h - h * 0.5) * bpp)};
        }
        long best = Long.MAX_VALUE;
        int bx = 0, bz = 0;
        for (int z = 0; z < h; z += 2) {
            for (int x = 0; x < w; x += 2) {
                int biomeIndex = biome.sample(x, z);
                if (biomeIndex >= keys.length || !isSpawnable(keys[biomeIndex])) continue;
                if (minY + height.sample(x, z) / 65535.0 * (maxY - minY) < seaLevel + 3) continue;
                long dx = x - w / 2, dz = z - h / 2, d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    bx = x;
                    bz = z;
                }
            }
        }
        return new int[]{(int) Math.round((bx + 0.5 - w * 0.5) * bpp), (int) Math.round((bz + 0.5 - h * 0.5) * bpp)};
    }
}
