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
import dev.worldforge.mod.api.WorldForgeApi;
import net.minecraftforge.event.level.ChunkEvent;
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
import java.util.List;
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

    private record RegionAddress(String dimension, int x, int z) { }
    private record WorldBounds(int minRegionX, int minRegionZ, int maxRegionX, int maxRegionZ) {
        int width() { return (maxRegionX - minRegionX + 1) * REGION_BLOCKS; }
        int height() { return (maxRegionZ - minRegionZ + 1) * REGION_BLOCKS; }
    }
    private static RegionAddress regionAddress(String id) {
        if (id == null || !id.startsWith("vanilla:")) return null;
        int last = id.lastIndexOf(':'), previous = last < 0 ? -1 : id.lastIndexOf(':', last - 1);
        if (previous <= 7 || last <= previous) return null;
        try {
            return new RegionAddress(id.substring(8, previous),
                    Integer.parseInt(id.substring(previous + 1, last)),
                    Integer.parseInt(id.substring(last + 1)));
        } catch (NumberFormatException invalid) { return null; }
    }
    private static WorldBounds bounds(String dimension) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Region region : REGIONS.values()) {
            RegionAddress address = regionAddress(region.id);
            if (address == null || !dimension.equals(address.dimension) || region.cells.isEmpty()) continue;
            minX = Math.min(minX, address.x); minZ = Math.min(minZ, address.z);
            maxX = Math.max(maxX, address.x); maxZ = Math.max(maxZ, address.z);
        }
        return minX == Integer.MAX_VALUE ? null : new WorldBounds(minX, minZ, maxX, maxZ);
    }

    /** Explicit political causes allowed to change already established sovereignty. */
    public enum PoliticalTransferCause {
        WAR, ANNEXATION, VASSALAGE, TREATY, GRANT, DISSOLUTION
    }

    /** A canonical settled community used to construct persistent political borders. */
    public static final class PoliticalSeed {
        public final int x, z;
        public final String realmId;
        public PoliticalSeed(int x, int z, String realmId) {
            this.x = x; this.z = z; this.realmId = realmId == null ? "" : realmId;
        }
    }

    public VanillaWorldGeography() { }

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
                // Discovery time is immutable: revisiting or regenerating an already
                // observed cell may refresh terrain evidence but never rewinds/ages it.
                if (previous != null) {
                    sample.add("discovered_year", previous.has("discovered_year")
                            ? previous.get("discovered_year").deepCopy()
                            : new com.google.gson.JsonPrimitive(0));
                } else {
                    sample.addProperty("discovered_year", Math.max(0L, WorldForgeApi.clock().dayNumber() / 365L));
                }
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
        boolean waterBiome = biomePath.contains("ocean") || biomePath.endsWith(":river")
                || biomePath.endsWith(":frozen_river");
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

    public static synchronized java.util.List<String> simulationMapIds() {
        return REGIONS.values().stream().filter(region -> !region.cells.isEmpty())
                .map(region -> regionAddress(region.id)).filter(java.util.Objects::nonNull)
                .map(RegionAddress::dimension).distinct().sorted()
                .map(dimension -> "vanilla-world:" + dimension).toList();
    }
    public static synchronized int widthForMapId(String mapId) {
        WorldBounds world = mapId != null && mapId.startsWith("vanilla-world:")
                ? bounds(mapId.substring("vanilla-world:".length())) : null;
        return world == null ? REGION_BLOCKS : world.width();
    }
    public static synchronized int heightForMapId(String mapId) {
        WorldBounds world = mapId != null && mapId.startsWith("vanilla-world:")
                ? bounds(mapId.substring("vanilla-world:".length())) : null;
        return world == null ? REGION_BLOCKS : world.height();
    }

    /** Diagnostic counters used by runtime acceptance tests and the in-game status command. */
    public static synchronized int sampledCellCount() {
        return REGIONS.values().stream().mapToInt(region -> region.cells.size()).sum();
    }

    public static synchronized int capturedRegionCount() {
        return (int) REGIONS.values().stream().filter(region -> !region.cells.isEmpty()).count();
    }

    public static synchronized int politicallyOwnedCellCount() {
        return (int) REGIONS.values().stream()
                .flatMap(region -> region.cells.values().stream())
                .filter(cell -> cell.has("owner_realm_id")
                        && !cell.get("owner_realm_id").getAsString().isBlank())
                .count();
    }

    public static synchronized int pendingChunkCount() {
        return PENDING.values().stream().mapToInt(java.util.ArrayDeque::size).sum();
    }

    /**
     * Builds a durable Voronoi-style political raster from real settled communities.
     * Every land sample is assigned to its nearest canonical polity seed; water and
     * remote frontier cells remain neutral. The same owner IDs feed Rust snapshots,
     * save data and later map/UI projections.
     */
    public static synchronized int applyPoliticalOwnership(
            Map<String, List<PoliticalSeed>> seedsByWorld) {
        int owned = 0;
        boolean changed = false;
        long maxDistanceSquared = 512L * 512L;
        for (Region region : REGIONS.values()) {
            RegionAddress address = regionAddress(region.id);
            if (address == null) continue;
            List<PoliticalSeed> seeds = seedsByWorld == null ? List.of()
                    : seedsByWorld.getOrDefault("vanilla-world:" + address.dimension, List.of());
            for (JsonObject cell : region.cells.values()) {
                String previous = cell.has("owner_realm_id") ? cell.get("owner_realm_id").getAsString() : "";
                // Existing sovereignty is immutable during ordinary simulation ticks.
                // Newly observed land can be claimed once; an existing border is never
                // recomputed from moving settlement centres or a changing seed list.
                String owner = previous;
                boolean land = cell.has("land") && cell.get("land").getAsBoolean();
                if (!seeds.isEmpty() && !land) {
                    // One-time sanitation of legacy invalid water ownership.
                    owner = "";
                } else if (land && previous.isBlank() && !seeds.isEmpty()) {
                    int x = address.x * REGION_BLOCKS + cell.get("x").getAsInt();
                    int z = address.z * REGION_BLOCKS + cell.get("z").getAsInt();
                    long bestDistance = Long.MAX_VALUE;
                    for (PoliticalSeed seed : seeds) {
                        if (seed.realmId.isBlank()) continue;
                        long dx = (long) x - seed.x, dz = (long) z - seed.z;
                        long distance = dx * dx + dz * dz;
                        if (distance < bestDistance) { bestDistance = distance; owner = seed.realmId; }
                    }
                    if (bestDistance > maxDistanceSquared) owner = "";
                }
                if (!previous.equals(owner)) { cell.addProperty("owner_realm_id", owner); changed = true; }
                if (!owner.isBlank()) owned++;
            }
        }
        if (changed) dirty = true;
        return owned;
    }

    /**
     * Atomically transfers already-owned vanilla geography after a durable political event.
     * Ordinary simulation and chunk discovery must use applyPoliticalOwnership(), never this API.
     * The event ID is written into every changed cell, making replay idempotent.
     */
    public static synchronized int transferOwnership(String mapId, Set<Long> cellKeys,
            String expectedOwner, String newOwner, PoliticalTransferCause cause, String eventId) {
        if (mapId == null || mapId.isBlank() || cellKeys == null || cellKeys.isEmpty()
                || expectedOwner == null || expectedOwner.isBlank()
                || newOwner == null || newOwner.isBlank() || expectedOwner.equals(newOwner)
                || cause == null || eventId == null || eventId.isBlank())
            throw new IllegalArgumentException("A complete political transfer event is required");
        Region region = REGIONS.get(mapId);
        if (region == null) throw new IllegalArgumentException("Unknown geography region: " + mapId);
        List<JsonObject> targets = new ArrayList<>();
        boolean allAlreadyApplied = true;
        for (Long key : cellKeys) {
            if (key == null) throw new IllegalArgumentException("Null geography cell key");
            JsonObject cell = region.cells.get(key);
            if (cell == null) throw new IllegalArgumentException("Unknown geography cell: " + key);
            String owner = cell.has("owner_realm_id") ? cell.get("owner_realm_id").getAsString() : "";
            String previousEvent = cell.has("ownership_event_id")
                    ? cell.get("ownership_event_id").getAsString() : "";
            if (newOwner.equals(owner) && eventId.equals(previousEvent)) {
                targets.add(cell);
                continue;
            }
            allAlreadyApplied = false;
            if (!expectedOwner.equals(owner))
                throw new IllegalStateException("Political transfer conflict at cell " + key
                        + ": expected " + expectedOwner + " but found " + owner);
            if (!cell.has("land") || !cell.get("land").getAsBoolean())
                throw new IllegalStateException("Political transfer cannot claim water cell " + key);
            targets.add(cell);
        }
        if (allAlreadyApplied) return 0;
        for (JsonObject cell : targets) {
            cell.addProperty("owner_realm_id", newOwner);
            cell.addProperty("ownership_event_id", eventId);
            cell.addProperty("ownership_event_cause", cause.name().toLowerCase(java.util.Locale.ROOT));
        }
        dirty = true;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            throw new IllegalStateException("Cannot durably commit political transfer without an active server");
        persist(server);
        return targets.size();
    }

    public static synchronized String biomeAt(String mapId, int x, int z) {
        Region region = REGIONS.get(mapId);
        if (region == null || x < 0 || z < 0 || x >= REGION_BLOCKS || z >= REGION_BLOCKS) return "";
        JsonObject cell = region.cells.get((long) z * REGION_BLOCKS + x);
        return cell == null || !cell.has("biome") ? "" : cell.get("biome").getAsString();
    }

    public static synchronized JsonArray snapshot() {
        JsonArray result = new JsonArray();
        for (String mapId : simulationMapIds()) {
            String dimension = dimensionFromMapId(mapId);
            WorldBounds world = bounds(dimension);
            if (world == null) continue;
            JsonObject map = new JsonObject();
            map.addProperty("map_id", mapId);
            map.addProperty("width_px", world.width());
            map.addProperty("height_px", world.height());
            map.addProperty("blocks_per_pixel_milli", 1000);
            map.addProperty("sample_stride", SAMPLE_STEP);
            JsonArray cells = new JsonArray();
            boolean political = false;
            for (Region region : REGIONS.values()) {
                RegionAddress address = regionAddress(region.id);
                if (address == null || !dimension.equals(address.dimension)) continue;
                for (JsonObject original : region.cells.values()) {
                    JsonObject cell = original.deepCopy();
                    cell.addProperty("x", (address.x - world.minRegionX) * REGION_BLOCKS + original.get("x").getAsInt());
                    cell.addProperty("z", (address.z - world.minRegionZ) * REGION_BLOCKS + original.get("z").getAsInt());
                    if (cell.has("owner_realm_id") && !cell.get("owner_realm_id").getAsString().isBlank()) political = true;
                    cells.add(cell);
                }
            }
            map.addProperty("political_layers_present", political);
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
        if (mapId == null) return "";
        if (mapId.startsWith("vanilla-world:")) return mapId.substring("vanilla-world:".length());
        RegionAddress address = regionAddress(mapId);
        return address == null ? "" : address.dimension;
    }

    public static synchronized int[] blockPosition(String mapId, int localX, int localZ) {
        if (mapId == null) return new int[]{0, 0};
        if (mapId.startsWith("vanilla-world:")) {
            WorldBounds world = bounds(dimensionFromMapId(mapId));
            return world == null ? new int[]{localX, localZ}
                    : new int[]{world.minRegionX * REGION_BLOCKS + localX,
                        world.minRegionZ * REGION_BLOCKS + localZ};
        }
        RegionAddress address = regionAddress(mapId);
        return address == null ? new int[]{0, 0}
                : new int[]{address.x * REGION_BLOCKS + localX, address.z * REGION_BLOCKS + localZ};
    }

    public static synchronized String biomeAtWorldPosition(String mapId, int worldX, int worldZ) {
        String dimension = dimensionFromMapId(mapId);
        if (dimension.isBlank()) return "";
        int regionX = Math.floorDiv(worldX, REGION_BLOCKS), regionZ = Math.floorDiv(worldZ, REGION_BLOCKS);
        Region region = REGIONS.get(mapId(dimension, regionX, regionZ));
        if (region == null) return "";
        int localX = Math.floorMod(worldX, REGION_BLOCKS), localZ = Math.floorMod(worldZ, REGION_BLOCKS);
        JsonObject cell = region.cells.get((long) localZ * REGION_BLOCKS + localX);
        return cell == null || !cell.has("biome") ? "" : cell.get("biome").getAsString();
    }

    private static String mapId(String dimension, int regionX, int regionZ) {
        return "vanilla:" + dimension + ":" + regionX + ":" + regionZ;
    }

    private static Path dataFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("worldforge").resolve("wf_world_geography.json");
    }
}
