package dev.worldforge.worldsim.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * Durable, observation-only geography for ordinary Minecraft world generation.
 *
 * Samples are captured exclusively from chunks that Minecraft has already loaded.
 * A fixed 16x16-chunk region is the same geographic unit for every dimension and
 * remains address-stable as additional chunks are explored. The cache is an input
 * adapter for the shared WorldSim emergence engine, never a second world model.
 */
public final class VanillaWorldGeography {
    private static final Gson GSON = new Gson();
    private static final int REGION_CHUNKS = 16;
    private static final int REGION_BLOCKS = REGION_CHUNKS * 16;
    private static final int SAMPLE_STEP = 4;
    private static final int SCHEMA = 1;
    private static final Map<String, Region> REGIONS = new TreeMap<>();
    private static final Map<String, ArrayDeque<ChunkPos>> PENDING = new TreeMap<>();
    private static final Set<String> PENDING_KEYS = new HashSet<>();
    private static final int MAX_CAPTURE_CHUNKS_PER_TICK = 8;
    private static boolean loaded;
    private static boolean dirty;
    private static long lastPersistGameTime = Long.MIN_VALUE;
    private static MinecraftServer activeServer;

    private static final class Region {
        final String id;
        final TreeMap<Long, JsonObject> cells = new TreeMap<>();
        Region(String id) { this.id = id; }
    }

    private VanillaWorldGeography() { }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        load(level.getServer());
        enqueue(level, event.getChunk().getPos());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int processed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            ArrayDeque<ChunkPos> queue = PENDING.get(dimension);
            if (queue == null) continue;
            while (processed < MAX_CAPTURE_CHUNKS_PER_TICK && !queue.isEmpty()) {
                ChunkPos position = queue.removeFirst();
                PENDING_KEYS.remove(dimension + "|" + position.x + "|" + position.z);
                ChunkAccess loadedChunk = level.getChunkSource().getChunkNow(position.x, position.z);
                if (loadedChunk != null) capture(level, loadedChunk);
                processed++;
            }
            if (queue.isEmpty()) PENDING.remove(dimension);
            if (processed >= MAX_CAPTURE_CHUNKS_PER_TICK) break;
        }
        flushIfDue(server);
    }

    private static synchronized void enqueue(ServerLevel level, ChunkPos position) {
        String dimension = level.dimension().location().toString();
        String key = dimension + "|" + position.x + "|" + position.z;
        if (!PENDING_KEYS.add(key)) return;
        PENDING.computeIfAbsent(dimension, ignored -> new ArrayDeque<>()).addLast(position);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) { load(event.getServer()); }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) { persist(event.getServer()); }

    /** Called from ChunkEvent.Load; never asks the server for an unloaded chunk. */
    public static synchronized void capture(ServerLevel level, ChunkAccess chunk) {
        if (level == null || chunk == null) return;
        load(level.getServer());
        String dimension = level.dimension().location().toString();
        ChunkPos pos = chunk.getPos();
        int regionX = Math.floorDiv(pos.x, REGION_CHUNKS);
        int regionZ = Math.floorDiv(pos.z, REGION_CHUNKS);
        String regionId = mapId(dimension, regionX, regionZ);
        Region region = REGIONS.computeIfAbsent(regionId, Region::new);
        int localChunkX = Math.floorMod(pos.x, REGION_CHUNKS) * 16;
        int localChunkZ = Math.floorMod(pos.z, REGION_CHUNKS) * 16;
        boolean changed = false;

        for (int localZ = 2; localZ < 16; localZ += SAMPLE_STEP) {
            for (int localX = 2; localX < 16; localX += SAMPLE_STEP) {
                int x = localChunkX + localX;
                int z = localChunkZ + localZ;
                long key = (long) z * REGION_BLOCKS + x;
                JsonObject sample = sample(level, chunk, localX, localZ, x, z);
                JsonObject previous = region.cells.get(key);
                if (previous == null || !previous.equals(sample)) {
                    region.cells.put(key, sample);
                    changed = true;
                }
            }
        }
        if (changed) dirty = true;
    }

    private static JsonObject sample(ServerLevel level, ChunkAccess chunk,
                                     int localX, int localZ, int regionX, int regionZ) {
        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
        int y = Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, surfaceY - 1));
        BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + localX, y,
                chunk.getPos().getMinBlockZ() + localZ);
        BlockState surface = chunk.getBlockState(pos);
        Optional<ResourceLocation> biomeKey = level.getBiome(pos).unwrapKey().map(key -> key.location());
        String biome = biomeKey.map(ResourceLocation::toString).orElse("minecraft:plains");
        String biomePath = biome.toLowerCase(java.util.Locale.ROOT);
        boolean riverBiome = biomePath.endsWith(":river") || biomePath.endsWith(":frozen_river");
        boolean waterBiome = biomePath.contains("ocean") || biomePath.contains("deep_")
                || biomePath.contains("river") || biomePath.contains("beach");
        boolean land = surface.getFluidState().isEmpty() && !waterBiome;
        int eastY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, Math.min(15, localX + SAMPLE_STEP), localZ);
        int southY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, Math.min(15, localZ + SAMPLE_STEP));
        int slope = Math.max(Math.abs(surfaceY - eastY), Math.abs(surfaceY - southY));
        int suitability = suitability(biomePath, y, level.getSeaLevel(), slope);
        JsonObject cell = new JsonObject();
        cell.addProperty("x", regionX);
        cell.addProperty("z", regionZ);
        cell.addProperty("biome", biome);
        cell.addProperty("elevation", y);
        cell.addProperty("river", riverBiome ? 255 : 0);
        cell.addProperty("land", land);
        cell.addProperty("suitability", suitability);
        cell.addProperty("owner_realm_id", "");
        return cell;
    }

    private static int suitability(String biome, int elevation, int seaLevel, int slope) {
        int score;
        if (biome.contains("ocean") || biome.contains("sea") || biome.contains("river")
                || biome.contains("deep_water")) score = 0;
        else if (biome.contains("desert") || biome.contains("badlands")) score = 18;
        else if (biome.contains("mountain") || biome.contains("peaks") || biome.contains("glacier")) score = 24;
        else if (biome.contains("swamp") || biome.contains("marsh") || biome.contains("wetland")) score = 52;
        else if (biome.contains("forest") || biome.contains("wood")) score = 70;
        else if (biome.contains("plains") || biome.contains("grassland") || biome.contains("meadow")) score = 82;
        else if (biome.contains("savanna") || biome.contains("steppe")) score = 62;
        else if (biome.contains("tundra") || biome.contains("snow")) score = 36;
        else score = 55;
        if (elevation >= seaLevel + 2 && elevation <= seaLevel + 48) score += 8;
        else if (elevation > seaLevel + 110) score -= 18;
        if (slope <= 3) score += 8;
        else if (slope >= 24) score -= 20;
        return Math.max(0, Math.min(100, score));
    }

    /** Stable lightweight index used by Java materialization; avoids cloning terrain cells every server tick. */
    public static synchronized java.util.List<String> regionIds() {
        return REGIONS.values().stream().filter(region -> !region.cells.isEmpty())
                .map(region -> region.id).toList();
    }

    public static synchronized String biomeAt(String mapId, int x, int z) {
        Region region = REGIONS.get(mapId);
        if (region == null || x < 0 || z < 0 || x >= REGION_BLOCKS || z >= REGION_BLOCKS) return "";
        JsonObject cell = region.cells.get((long) z * REGION_BLOCKS + x);
        return cell == null || !cell.has("biome") ? "" : cell.get("biome").getAsString();
    }

    public static synchronized JsonArray snapshot() {
        JsonArray result = new JsonArray();
        for (Region region : REGIONS.values()) {
            if (region.cells.isEmpty()) continue;
            JsonObject map = new JsonObject();
            map.addProperty("map_id", region.id);
            map.addProperty("width_px", REGION_BLOCKS);
            map.addProperty("height_px", REGION_BLOCKS);
            map.addProperty("blocks_per_pixel_milli", 1000);
            map.addProperty("political_layers_present", false);
            map.addProperty("sample_stride", SAMPLE_STEP);
            JsonArray cells = new JsonArray();
            region.cells.values().forEach(cells::add);
            map.add("cells", cells);
            result.add(map);
        }
        return result;
    }

    public static synchronized void load(MinecraftServer server) {
        if (server == null) return;
        if (activeServer != server) {
            REGIONS.clear();
            PENDING.clear(); PENDING_KEYS.clear();
            dirty = false;
            loaded = false;
            lastPersistGameTime = Long.MIN_VALUE;
            activeServer = server;
        }
        if (loaded) return;
        loaded = true;
        Path file = dataFile(server);
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || !root.has("schema_version") || root.get("schema_version").getAsInt() != SCHEMA
                    || !root.has("regions") || !root.get("regions").isJsonArray())
                throw new IOException("unsupported vanilla geography cache schema");
            for (var item : root.getAsJsonArray("regions")) {
                if (!item.isJsonObject()) continue;
                JsonObject regionJson = item.getAsJsonObject();
                if (!regionJson.has("map_id") || !regionJson.has("cells") || !regionJson.get("cells").isJsonArray()) continue;
                String id = regionJson.get("map_id").getAsString();
                if (!id.startsWith("vanilla:")) continue;
                Region region = new Region(id);
                for (var cellElement : regionJson.getAsJsonArray("cells")) {
                    if (!cellElement.isJsonObject()) continue;
                    JsonObject cell = cellElement.getAsJsonObject();
                    if (!cell.has("x") || !cell.has("z")) continue;
                    int x = cell.get("x").getAsInt(), z = cell.get("z").getAsInt();
                    if (x < 0 || z < 0 || x >= REGION_BLOCKS || z >= REGION_BLOCKS) continue;
                    region.cells.put((long) z * REGION_BLOCKS + x, cell.deepCopy());
                }
                REGIONS.put(id, region);
            }
        } catch (Exception error) {
            // Fail closed: a corrupt cache must not silently be overwritten by partial observations.
            throw new IllegalStateException("Unable to load canonical vanilla geography cache " + file, error);
        }
    }

    public static synchronized void flushIfDue(MinecraftServer server) {
        if (server == null || !dirty) return;
        long now = server.overworld().getGameTime();
        if (lastPersistGameTime != Long.MIN_VALUE && now - lastPersistGameTime < 200) return;
        persist(server);
    }

    public static synchronized void persist(MinecraftServer server) {
        if (server == null || !dirty) return;
        Path file = dataFile(server);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", SCHEMA);
            JsonArray regions = new JsonArray();
            for (Region region : REGIONS.values()) {
                JsonObject item = new JsonObject();
                item.addProperty("map_id", region.id);
                JsonArray cells = new JsonArray();
                region.cells.values().forEach(cells::add);
                item.add("cells", cells);
                regions.add(item);
            }
            root.add("regions", regions);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            lastPersistGameTime = server.overworld().getGameTime();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to persist canonical vanilla geography cache " + file, error);
        }
    }

    public static String dimensionFromMapId(String mapId) {
        if (mapId == null || !mapId.startsWith("vanilla:")) return "";
        int last = mapId.lastIndexOf(':');
        int previous = last < 0 ? -1 : mapId.lastIndexOf(':', last - 1);
        return previous > 7 ? mapId.substring(8, previous) : "";
    }

    public static int[] blockPosition(String mapId, int localX, int localZ) {
        if (mapId == null || !mapId.startsWith("vanilla:")) return new int[]{0, 0};
        int last = mapId.lastIndexOf(':');
        int previous = last < 0 ? -1 : mapId.lastIndexOf(':', last - 1);
        if (previous < 8 || last <= previous) return new int[]{0, 0};
        int regionX = Integer.parseInt(mapId.substring(previous + 1, last));
        int regionZ = Integer.parseInt(mapId.substring(last + 1));
        return new int[]{regionX * REGION_BLOCKS + localX,
                regionZ * REGION_BLOCKS + localZ};
    }

    private static String mapId(String dimension, int regionX, int regionZ) {
        return "vanilla:" + dimension + ":" + regionX + ":" + regionZ;
    }

    private static Path dataFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("worldforge").resolve("wf_world_geography.json");
    }
}
