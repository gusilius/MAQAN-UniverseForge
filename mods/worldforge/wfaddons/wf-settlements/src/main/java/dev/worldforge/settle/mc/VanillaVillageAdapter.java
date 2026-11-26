package dev.worldforge.settle.mc;

import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

/**
 * Adopts vanilla generated villages into the same settlement and Character systems.
 * Existing Minecraft structures are preserved; the adapter registers their location,
 * canonical villagers, employment and demographic count in UniverseForge.
 */
public final class VanillaVillageAdapter {
    private static final int RECONCILE_RADIUS = 64;
    private static final int MAX_INFRASTRUCTURE_CHUNKS_PER_TICK = 1;
    private static final Map<String, ArrayDeque<ChunkPos>> PENDING_INFRASTRUCTURE = new LinkedHashMap<>();
    private static final Set<String> PENDING_INFRASTRUCTURE_KEYS = new HashSet<>();
    private static final Set<Colony> DIRTY_INFRASTRUCTURE_COLONIES = new LinkedHashSet<>();
    private static final int INFRASTRUCTURE_SAVE_INTERVAL_TICKS = 100;
    private static long lastInfrastructureSaveTick;

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ChunkAccess chunk = event.getChunk();
        for (Map.Entry<net.minecraft.world.level.levelgen.structure.Structure, StructureStart> entry
                : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) continue;
            var typeKey = BuiltInRegistries.STRUCTURE_TYPE.getKey(entry.getKey().type());
            if (typeKey == null) continue;
            BoundingBox bounds = start.getBoundingBox();
            int x = (bounds.minX() + bounds.maxX()) / 2;
            int z = (bounds.minZ() + bounds.maxZ()) / 2;
            if (isSettlementAnchor(typeKey.getPath())) adopt(level, x, z, typeKey.toString());
            else adoptCataloguedStructure(level, x, z, typeKey.toString());
        }
        // Only chunks near an already-known vanilla village can contain adoptable
        // infrastructure. Synthetic/strategic colonies must not trigger this scan.
        if (hasNearbyVillage(level, chunk.getPos())) enqueueInfrastructureChunk(level, chunk.getPos());
    }

    /**
     * ChunkEvent.Load fires while the chunk is still being integrated into ServerChunkCache.
     * Infrastructure inspection calls height/block accessors that may request that same chunk
     * again and deadlock the server thread. Defer inspection until a later server tick and
     * process a bounded number of already-loaded chunks per tick.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int processed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            ArrayDeque<ChunkPos> queue = PENDING_INFRASTRUCTURE.get(dimension);
            if (queue == null) continue;
            while (processed < MAX_INFRASTRUCTURE_CHUNKS_PER_TICK && !queue.isEmpty()) {
                ChunkPos position = queue.removeFirst();
                PENDING_INFRASTRUCTURE_KEYS.remove(chunkKey(dimension, position));
                // A chunk may have unloaded between its load event and this tick. Drop
                // that stale work item; a later load event will enqueue it again.
                if (level.getChunkSource().getChunkNow(position.x, position.z) != null)
                    reconcileInfrastructureChunk(level, position);
                processed++;
            }
            if (queue.isEmpty()) PENDING_INFRASTRUCTURE.remove(dimension);
            if (processed >= MAX_INFRASTRUCTURE_CHUNKS_PER_TICK) break;
        }
        // Chunk reconciliation can discover many existing vanilla buildings. Never serialize
        // the entire settlement registry synchronously for each chunk: coalesce mutations
        // and flush at a bounded cadence on the server thread.
        long tick = server.getTickCount();
        if (!DIRTY_INFRASTRUCTURE_COLONIES.isEmpty()
                && tick - lastInfrastructureSaveTick >= INFRASTRUCTURE_SAVE_INTERVAL_TICKS) {
            if (SettleMod.saveDurably(server)) {
                DIRTY_INFRASTRUCTURE_COLONIES.clear();
                lastInfrastructureSaveTick = tick;
            } else {
                for (Colony colony : DIRTY_INFRASTRUCTURE_COLONIES) colony.actionPersistenceBlocked = true;
                SettleMod.LOGGER.error("wf_settle: deferred vanilla infrastructure batch could not be persisted; affected settlements frozen");
                DIRTY_INFRASTRUCTURE_COLONIES.clear();
                lastInfrastructureSaveTick = tick;
            }
        }
    }

    /** Namespace-independent settlement-anchor rules for vanilla and arbitrary structure mods. */
    private static boolean isSettlementAnchor(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (SettleMod.STRUCTURE_CATALOG.matchesStructureClass("settlement_anchor", value)) return true;
        // Backward-compatible fallback for older datapacks that have not yet loaded
        // the extended catalogue; namespaces are intentionally ignored.
        return value.contains("village") || value.contains("settlement") || value.contains("hamlet")
                || value.contains("town") || value.contains("city") || value.contains("colony")
                || value.contains("homestead") || value.contains("outpost") || value.contains("camp")
                || value.contains("tribal");
    }

    /** Existing modded houses, farms, halls, forts and workshops join the nearest canonical settlement. */
    private static void adoptCataloguedStructure(ServerLevel level, int x, int z, String structureType) {
        if (SettleMod.DEFS == null || !SettleMod.STRUCTURE_CATALOG.loaded()) return;
        String matchedClass = SettleMod.STRUCTURE_CATALOG.structureClasses().stream()
                .filter(classId -> SettleMod.STRUCTURE_CATALOG.matchesStructureClass(classId, structureType))
                .sorted().findFirst().orElse("");
        if (matchedClass.isBlank()) return;
        var definition = SettleMod.DEFS.buildings.values().stream()
                .filter(building -> matchedClass.equals(building.structureClass))
                .sorted(java.util.Comparator.comparing(building -> building.id))
                .findFirst().orElse(null);
        if (definition == null) return;
        String dimension = level.dimension().location().toString();
        Colony colony = SettleMod.COLONIES.values().stream()
                .filter(candidate -> candidate != null && candidate.s != null
                        && dimension.equals(candidate.dim))
                .filter(candidate -> {
                    long dx = (long) candidate.cx - x, dz = (long) candidate.cz - z;
                    return dx * dx + dz * dz <= 256L * 256L;
                })
                .min(java.util.Comparator.comparingLong(candidate -> {
                    long dx = (long) candidate.cx - x, dz = (long) candidate.cz - z;
                    return dx * dx + dz * dz;
                })).orElse(null);
        if (colony == null) return; // It remains in the durable world structure registry for later linkage.
        String request = "mc.structure." + Integer.toUnsignedString(
                (dimension + "|" + structureType + "|" + x + "|" + z).hashCode(), 36);
        try {
            colony.s.adoptExistingBuilding(request, definition.id, x - colony.cx, z - colony.cz);
            DIRTY_INFRASTRUCTURE_COLONIES.add(colony);
        } catch (RuntimeException failure) {
            SettleMod.LOGGER.debug("wf_settle: could not link mod structure {} to {}: {}",
                    structureType, colony.s.id, failure.getMessage());
        }
    }

    private static boolean isSettlementOperation(String operation) {
        return operation != null && (operation.startsWith("worldforge:structure:")
                || operation.startsWith("minecraft:village:"));
    }

    private static boolean hasNearbyVillage(ServerLevel level, ChunkPos position) {
        String dimension = level.dimension().location().toString();
        double centerX = position.getMinBlockX() + 8.0;
        double centerZ = position.getMinBlockZ() + 8.0;
        for (Colony colony : ColonyInterestIndex.nearbyAt(
                dimension, centerX, centerZ, RECONCILE_RADIUS + 12.0)) {
            if (colony != null && colony.genesisOperationId != null
                    && isSettlementOperation(colony.genesisOperationId)) return true;
        }
        return false;
    }

    private static void enqueueInfrastructureChunk(ServerLevel level, ChunkPos position) {
        String dimension = level.dimension().location().toString();
        if (!PENDING_INFRASTRUCTURE_KEYS.add(chunkKey(dimension, position))) return;
        PENDING_INFRASTRUCTURE.computeIfAbsent(dimension, ignored -> new ArrayDeque<>()).addLast(position);
    }

    private static String chunkKey(String dimension, ChunkPos position) {
        return dimension + "|" + position.x + "|" + position.z;
    }

    /** New villagers may arrive after the structure-start chunk; periodically reconcile the loaded area. */
    public static void reconcile(MinecraftServer server) {
        if (server == null || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return;
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || colony.genesisOperationId == null
                    || !colony.genesisOperationId.startsWith("minecraft:village:")) continue;
            ServerLevel level = colony.level(server);
            if (level != null) {
                reconcileResidents(level, colony);
                reconcileLoadedInfrastructure(level, colony);
            }
        }
    }

    /** Stable race-aware settlement name; existing authored colony names are never overwritten. */
    private static String generatedVillageName(MapData map, TitleData.Realm realm, String id,
                                               String dimension, int x, int z) {
        String race = "human";
        String culture = PeopleMod.NAMES.culture("northern");
        if (realm != null) {
            Object cultureValue = realm.props.get("culture");
            if (cultureValue instanceof String value && !value.isBlank())
                culture = PeopleMod.NAMES.culture(value.trim());
            Object racesValue = realm.props.get("races");
            if (racesValue instanceof String encoded) {
                double best = -1;
                for (String item : encoded.split(",")) {
                    String[] pair = item.trim().split(":", 2);
                    if (pair.length != 2) continue;
                    try {
                        double share = Double.parseDouble(pair[1].trim());
                        if (Double.isFinite(share) && share > best) {
                            best = share;
                            race = pair[0].trim();
                        }
                    } catch (NumberFormatException ignored) { }
                }
            } else if (racesValue instanceof Map<?, ?> shares) {
                double best = -1;
                for (Map.Entry<?, ?> entry : shares.entrySet()) {
                    if (!(entry.getValue() instanceof Number number)) continue;
                    double share = number.doubleValue();
                    if (Double.isFinite(share) && share > best) {
                        best = share;
                        race = String.valueOf(entry.getKey());
                    }
                }
            }
        }
        long seed = 0x574656494c4c4147L;
        seed = seed * 31 + (map == null ? 0 : map.name.hashCode());
        seed = seed * 31 + dimension.hashCode();
        seed = seed * 31 + x;
        seed = seed * 31 + z;
        try {
            return PeopleMod.NAMES.generate(race, culture, "settlements", new Random(seed));
        } catch (RuntimeException missingRacePool) {
            try {
                return PeopleMod.NAMES.generate("human", culture, "settlements", new Random(seed));
            } catch (RuntimeException missingCulturePool) {
                return "Поселение " + Integer.toUnsignedString(id.hashCode(), 36).toUpperCase(Locale.ROOT);
            }
        }
    }

    private static void adopt(ServerLevel level, int x, int z, String structureType) {
        if (SettleMod.DEFS == null || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return;
        CharacterRegistry registry = PeopleMod.registryFor(level);
        if (registry == null) return;

        String dimension = level.dimension().location().toString();
        String stableLocationId = java.util.UUID.nameUUIDFromBytes(
                (dimension + ":" + structureType + ":" + x + ":" + z)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
        String id = "world_structure_" + stableLocationId;
        String operation = "worldforge:structure:" + dimension + ":" + structureType + ":" + x + ":" + z;
        // If autonomous emergence already materialized this location, adopt that exact
        // canonical colony instead of registering a second settlement for the structure.
        // Prefer the exact stable village ID on repeat discovery; only then reconcile
        // an independently emerged colony at this physical location.
        Colony colony = SettleMod.colony(id);
        if (colony == null) colony = SettleMod.colonyByGenesisOperation(operation);
        if (colony == null) colony = SettleMod.nearbyEmergentSettlement(dimension, x, z, 64);
        boolean created = colony == null;
        boolean identityChanged = colony != null && !operation.equals(colony.genesisOperationId);
        // A physical village is a geographic observation, not evidence of pre-authored
        // sovereignty. Political ownership is assigned only by the shared emergence pass.
        String settlementName = generatedVillageName(null, null, id, dimension, x, z);
        try {
            if (colony == null) {
                colony = SettleMod.foundEmergentInMemory(level.getServer(), dimension, id,
                        settlementName, x, z, operation);
            } else {
                colony.genesisOperationId = operation;
            }
        } catch (RuntimeException failure) {
            SettleMod.LOGGER.warn("wf_settle: could not adopt vanilla village {} at {},{}: {}",
                    structureType, x, z, failure.getMessage());
            return;
        }

        if (created) {
            WorldForgeApi.postEvent("settlement",
                    "Vanilla village discovered and linked to the UniverseForge settlement registry: " + colony.s.name,
                    List.of(), WorldForgeApi.clock().worldMinute());
        }
        reconcileResidents(level, colony);
        // Infrastructure scans are queued by ChunkEvent.Load and drained on later ticks;
        // never inspect the chunk currently being integrated by ServerChunkCache.
        // Future chunks are adopted incrementally by the deferred chunk queue.
        if (created || identityChanged) {
            if (!SettleMod.saveDurably(level.getServer())) {
                // Preserve a retry marker in memory. A later chunk rediscovery resolves this
                // exact operation and retries the durable write instead of creating a duplicate.
                colony.genesisOperationId = "minecraft:village:pending:" + operation;
                SettleMod.LOGGER.error("wf_settle: discovered/reconciled vanilla village {} could not be durably saved; retry is pending", id);
            }
        }
    }

    private static void reconcileResidents(ServerLevel level, Colony colony) {
        if (colony == null || colony.s == null || !level.hasChunkAt(new BlockPos(colony.cx, 64, colony.cz))) return;
        CharacterRegistry registry = PeopleMod.registryFor(level);
        if (registry == null) return;

        AABB area = new AABB(colony.cx - RECONCILE_RADIUS, level.getMinBuildHeight(),
                colony.cz - RECONCILE_RADIUS, colony.cx + RECONCILE_RADIUS + 1,
                level.getMaxBuildHeight(), colony.cz + RECONCILE_RADIUS + 1);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, area, Villager::isAlive);
        Set<String> residentIds = new LinkedHashSet<>();
        for (String existingId : colony.residentCharacterIds) {
            try (var lease = registry.access().acquireGlobal(existingId)) {
                if (lease != null && lease.get() != null && lease.get().alive) residentIds.add(existingId);
            }
        }
        Set<String> changedIds = new LinkedHashSet<>();
        int year = WorldForgeApi.clock().year();
        for (Villager villager : villagers) {
            String uuid = villager.getUUID().toString();
            String globalId = registry.entityBindingOwner(uuid);
            boolean created = globalId == null || globalId.isBlank();
            if (created) globalId = registry.create(villager.getName().getString(), "", "", "m", year - 25).globalId;
            try (var lease = registry.access().acquireGlobal(globalId)) {
                if (lease == null) continue;
                Character person = lease.get();
                if (person == null || !person.alive) continue;
                boolean changed = created;
                if (!uuid.equals(person.props.get("minecraft.villager_uuid"))) {
                    person.props.put("minecraft.villager_uuid", uuid);
                    changed = true;
                }
                if (!(person.props.get("origin.race") instanceof String race) || race.isBlank()) {
                    person.props.put("origin.race", "human");
                    person.props.put("origin.source", "minecraft-village");
                    changed = true;
                }
                if (person.culture == null || person.culture.isBlank()) {
                    person.culture = PeopleMod.NAMES.culture("northern");
                    changed = true;
                }
                if (!colony.s.id.equals(person.props.get("population.location"))) {
                    person.props.put("population.location", colony.s.id);
                    changed = true;
                }
                String employer = "settlement:" + colony.s.id;
                if (!employer.equals(person.props.get("population.employer"))) {
                    person.props.put("population.employer", employer);
                    changed = true;
                }
                boolean bindingChanged = !uuid.equals(registry.entityBindingUuidForGlobal(globalId, "villager"));
                if (!registry.bindEntity(person.id, "villager", uuid)) continue;
                if (changed || bindingChanged) {
                    CharacterRegistry.recordCharacterMutation();
                    changedIds.add(globalId);
                }
                residentIds.add(globalId);
            }
        }

        // Persist every changed canonical resident before publishing the settlement projection.
        for (String changedId : changedIds) {
            try (var lease = registry.access().acquireGlobal(changedId)) {
                if (lease == null || !PeopleMod.persistCharacter(lease.get(), level.getServer())) {
                    SettleMod.LOGGER.error("wf_settle: canonical villager {} for {} could not be persisted; adoption will retry",
                            changedId, colony.s.id);
                    return;
                }
            }
        }

        if (!residentIds.equals(colony.residentCharacterIds)) {
            SettleMod.replaceCanonicalResidentsInMemory(colony.s.id, residentIds);
            colony.s.setCanonicalPopulation(residentIds.size());
            for (String id : residentIds) SettleMod.attachExistingCharacter(colony.s.id, id);
            if (!SettleMod.saveDurably(level.getServer()))
                SettleMod.LOGGER.error("wf_settle: adopted village {} could not be durably saved", colony.s.id);
        } else if (!colony.s.hasCanonicalPopulation()) {
            colony.s.setCanonicalPopulation(residentIds.size());
            SettleMod.saveDurably(level.getServer());
        }
    }


    private record ExistingBuilding(String definition, int x, int z) { }

    /** Visit only already loaded chunks around a village; never generate/load terrain from this adapter. */
    private static void reconcileLoadedInfrastructure(ServerLevel level, Colony colony) {
        int centerX = Math.floorDiv(colony.cx, 16), centerZ = Math.floorDiv(colony.cz, 16);
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                net.minecraft.world.level.chunk.LevelChunk loaded = level.getChunkSource().getChunkNow(x, z);
                if (loaded != null) reconcileInfrastructureChunk(level, loaded.getPos());
            }
        }
    }

    /** Each loaded chunk is reconciled once; this never forces an unloaded chunk to load. */
    private static void reconcileInfrastructureChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos) {
        int minX = chunkPos.getMinBlockX(), minZ = chunkPos.getMinBlockZ();
        String dimension = level.dimension().location().toString();
        // Restrict this block/heightmap scan to village colonies in the spatial
        // neighborhood; never walk the complete strategic settlement registry per chunk.
        for (Colony colony : ColonyInterestIndex.nearbyAt(
                dimension, minX + 8.0, minZ + 8.0, RECONCILE_RADIUS + 12.0)) {
            if (colony == null || colony.s == null || !dimension.equals(colony.dim)
                    || colony.genesisOperationId == null
                    || !colony.genesisOperationId.startsWith("minecraft:village:")
                    || Math.abs(minX + 8 - colony.cx) > RECONCILE_RADIUS
                    || Math.abs(minZ + 8 - colony.cz) > RECONCILE_RADIUS) continue;
            String chunkKey = chunkPos.x + ":" + chunkPos.z;
            if (colony.vanillaInfrastructureChunks.contains(chunkKey)) continue;
            adoptInfrastructureChunk(level, colony, minX, minZ, chunkKey);
        }
    }

    /** Existing blocks stay in place while completed economic buildings are registered. */
    private static void adoptInfrastructureChunk(ServerLevel level, Colony colony, int minX, int minZ, String chunkKey) {
        if (SettleMod.DEFS == null) return;
        Map<String, ExistingBuilding> candidates = new LinkedHashMap<>();
        Set<Long> bedDistricts = new LinkedHashSet<>();
        int farmland = 0;
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
            BlockPos probe = new BlockPos(x, level.getMinBuildHeight(), z);
            if (!level.hasChunkAt(probe)) return;
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.max(level.getMinBuildHeight(), surface - 4);
                 y <= Math.min(level.getMaxBuildHeight() - 1, surface + 8); y++) {
                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                if (state.getBlock() instanceof BedBlock) {
                    long district = ((long) Math.floorDiv(x - colony.cx, 12) << 32)
                            | (Math.floorDiv(z - colony.cz, 12) & 0xffffffffL);
                    bedDistricts.add(district);
                }
                if (state.is(Blocks.FARMLAND) || state.is(Blocks.WHEAT) || state.is(Blocks.CARROTS)
                        || state.is(Blocks.POTATOES) || state.is(Blocks.BEETROOTS)) farmland++;
                String def = null;
                if (state.is(Blocks.BELL)) def = "village_hall";
                else if (state.is(Blocks.BARREL) || state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) def = "village_granary";
                else if (state.is(Blocks.COMPOSTER)) def = "farm_district";
                else if (state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.ANVIL)
                        || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL)) def = "smithy";
                else if (state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.FURNACE)) def = "mining_district";
                else if (state.is(Blocks.SMOKER) || state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST)) def = "livestock_district";
                else if (state.is(Blocks.FLETCHING_TABLE) || state.is(Blocks.TARGET)) def = "hunting_lodge";
                else if (state.is(Blocks.LOOM) || state.is(Blocks.CARTOGRAPHY_TABLE) || state.is(Blocks.LECTERN)) def = "village_hall";
                if (def != null && SettleMod.DEFS.buildings.containsKey(def))
                    candidates.putIfAbsent(def + "@" + x + ":" + z, new ExistingBuilding(def, x, z));
            }
        }
        for (long district : bedDistricts) {
            int gx = (int) (district >> 32), gz = (int) district;
            int x = colony.cx + gx * 12 + 6, z = colony.cz + gz * 12 + 6;
            if (SettleMod.DEFS.buildings.containsKey("village_house"))
                candidates.putIfAbsent("village_house@" + x + ":" + z, new ExistingBuilding("village_house", x, z));
        }
        if (farmland >= 24 && SettleMod.DEFS.buildings.containsKey("farm_district"))
            candidates.putIfAbsent("farm_district@" + minX + ":" + minZ,
                    new ExistingBuilding("farm_district", minX + 8, minZ + 8));

        boolean changed = false;
        for (ExistingBuilding physical : candidates.values()) {
            String request = "mc.village.building." + physical.definition() + "." + physical.x() + "." + physical.z();
            int before = colony.s.buildings.size();
            try {
                colony.s.adoptExistingBuilding(request, physical.definition(),
                        physical.x() - colony.cx, physical.z() - colony.cz);
                changed |= colony.s.buildings.size() > before;
            } catch (RuntimeException failure) {
                SettleMod.LOGGER.debug("wf_settle: could not adopt existing {} in {}: {}",
                        physical.definition(), colony.s.id, failure.getMessage());
            }
        }
        colony.vanillaInfrastructureChunks.add(chunkKey);
        if (changed) WorldForgeApi.postEvent("settlement",
                "Existing Minecraft village facilities linked to shared housing, production and storage simulation",
                colony.realmId == null || colony.realmId.isBlank() ? List.of() : List.of(colony.realmId),
                WorldForgeApi.clock().worldMinute());
        DIRTY_INFRASTRUCTURE_COLONIES.add(colony);
    }

    @SubscribeEvent
    public void onVillagerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !(villager.level() instanceof ServerLevel level)) return;
        CharacterRegistry registry = PeopleMod.registryFor(level);
        if (registry == null) return;
        String uuid = villager.getUUID().toString();
        String globalId = registry.entityBindingOwner(uuid);
        if (globalId == null || globalId.isBlank()) return;
        try (var lease = registry.access().acquireGlobal(globalId)) {
            if (lease == null) return;
            Character person = lease.get();
            if (person == null) return;
            if (person.alive) PopulationSimulation.die(registry, person.id,
                    WorldForgeApi.clock().year(), "vanilla-villager-death");
            if (!PeopleMod.persistCharacter(person, level.getServer()))
                SettleMod.LOGGER.error("wf_settle: deceased vanilla villager {} could not be durably persisted", globalId);
        }
    }
}
