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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adopts vanilla generated villages into the same settlement and Character systems.
 * Existing Minecraft structures are preserved; the adapter registers their location,
 * canonical villagers, employment and demographic count in UniverseForge.
 */
public final class VanillaVillageAdapter {
    private static final int RECONCILE_RADIUS = 64;

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ChunkAccess chunk = event.getChunk();
        for (Map.Entry<net.minecraft.world.level.levelgen.structure.Structure, StructureStart> entry
                : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) continue;
            var typeKey = BuiltInRegistries.STRUCTURE_TYPE.getKey(entry.getKey().type());
            if (typeKey == null || !typeKey.getPath().contains("village")) continue;
            BoundingBox bounds = start.getBoundingBox();
            int x = (bounds.minX() + bounds.maxX()) / 2;
            int z = (bounds.minZ() + bounds.maxZ()) / 2;
            adopt(level, x, z, typeKey.toString());
        }
        reconcileInfrastructureChunk(level, chunk.getPos());
    }

    /** New villagers may arrive after the structure-start chunk; periodically reconcile the loaded area. */
    public static void reconcile(MinecraftServer server) {
        if (server == null || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return;
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || colony.genesisOperationId == null
                    || !colony.genesisOperationId.startsWith("minecraft:village:")) continue;
            ServerLevel level = colony.level(server);
            if (level != null) reconcileResidents(level, colony);
        }
    }

    private static void adopt(ServerLevel level, int x, int z, String structureType) {
        if (SettleMod.DEFS == null || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return;
        MapData map = EditorManager.dataFor(level);
        if (map == null) return;
        CharacterRegistry registry = PeopleMod.registry(map);
        if (registry == null) return;

        String dimension = level.dimension().location().toString();
        String id = "vanilla_village_" + Integer.toHexString(dimension.hashCode()) + "_" + x + "_" + z;
        String operation = "minecraft:village:" + dimension + ":" + x + ":" + z;
        boolean created = !SettleMod.COLONIES.containsKey(id);
        Colony colony;
        try {
            colony = SettleMod.foundEmergentInMemory(level.getServer(), dimension, id,
                    "Village " + x + ", " + z, x, z, operation);
        } catch (RuntimeException failure) {
            SettleMod.LOGGER.warn("wf_settle: could not adopt vanilla village {} at {},{}: {}",
                    structureType, x, z, failure.getMessage());
            return;
        }

        TitleData.Title province = map.titles == null ? null : map.provinceAt(x, z);
        TitleData.Realm realm = province == null || map.titles == null ? null : map.titles.realmOf(province);
        if (realm != null) colony.realmId = realm.id;
        if (created) {
            WorldForgeApi.postEvent("settlement",
                    "Vanilla village discovered and linked to the UniverseForge settlement registry: " + colony.s.name,
                    realm == null ? List.of() : List.of(realm.id), WorldForgeApi.clock().worldMinute());
        }
        reconcileResidents(level, colony);
        // Physical infrastructure is adopted incrementally as nearby chunks load.
        if (created && !SettleMod.saveDurably(level.getServer()))
            SettleMod.LOGGER.error("wf_settle: discovered vanilla village {} could not be durably saved", id);
    }

    private static void reconcileResidents(ServerLevel level, Colony colony) {
        if (colony == null || colony.s == null || !level.hasChunkAt(new BlockPos(colony.cx, 64, colony.cz))) return;
        MapData map = EditorManager.dataFor(level);
        CharacterRegistry registry = PeopleMod.registry(map);
        if (registry == null) return;

        AABB area = new AABB(colony.cx - RECONCILE_RADIUS, level.getMinBuildHeight(),
                colony.cz - RECONCILE_RADIUS, colony.cx + RECONCILE_RADIUS + 1,
                level.getMaxBuildHeight(), colony.cz + RECONCILE_RADIUS + 1);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, area, Villager::isAlive);
        Map<String, Character> byEntity = new LinkedHashMap<>();
        for (Character person : registry.all()) {
            Object raw = person.props.get("population.entity_bindings");
            if (raw instanceof Map<?, ?> bindings) {
                Object uuid = bindings.get("villager");
                if (uuid instanceof String value) byEntity.put(value, person);
            }
            Object legacy = person.props.get("minecraft.villager_uuid");
            if (legacy instanceof String value) byEntity.putIfAbsent(value, person);
        }

        Set<String> residentIds = new LinkedHashSet<>();
        for (String existingId : colony.residentCharacterIds) {
            Character existing = PeopleMod.authority().getGlobal(existingId);
            if (existing != null && existing.alive) residentIds.add(existingId);
        }
        List<Character> changed = new ArrayList<>();
        int year = WorldForgeApi.clock().year();
        for (Villager villager : villagers) {
            String uuid = villager.getUUID().toString();
            Character person = byEntity.get(uuid);
            if (person == null) {
                person = registry.create(villager.getName().getString(), "", "", "m", year - 25);
                person.props.put("minecraft.villager_uuid", uuid);
                person.props.put("population.location", colony.s.id);
                person.props.put("population.employer", "settlement:" + colony.s.id);
                if (!registry.bindEntity(person.id, "villager", uuid)) continue;
                changed.add(person);
                byEntity.put(uuid, person);
            }
            person.props.put("population.location", colony.s.id);
            person.props.put("population.employer", "settlement:" + colony.s.id);
            residentIds.add(person.globalId);
        }

        if (!residentIds.equals(colony.residentCharacterIds)) {
            SettleMod.replaceCanonicalResidentsInMemory(colony.s.id, residentIds);
            colony.s.setCanonicalPopulation(residentIds.size());
            for (String id : residentIds) SettleMod.attachExistingCharacter(colony.s.id, id);
            if (!changed.isEmpty() && !PeopleMod.persistCharacter(changed.get(0), level.getServer())) {
                SettleMod.LOGGER.error("wf_settle: canonical villagers for {} could not be persisted; village adoption will retry",
                        colony.s.id);
                return;
            }
            if (!SettleMod.saveDurably(level.getServer()))
                SettleMod.LOGGER.error("wf_settle: adopted village {} could not be durably saved", colony.s.id);
        } else if (!colony.s.hasCanonicalPopulation()) {
            colony.s.setCanonicalPopulation(residentIds.size());
            SettleMod.saveDurably(level.getServer());
        }
    }


    private record ExistingBuilding(String definition, int x, int z) { }

    /** Each loaded chunk is reconciled once; this never forces an unloaded chunk to load. */
    private static void reconcileInfrastructureChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunkPos) {
        int minX = chunkPos.getMinBlockX(), minZ = chunkPos.getMinBlockZ();
        String dimension = level.dimension().location().toString();
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || colony.s == null || !dimension.equals(colony.dim)
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
                if (state.is(Blocks.BED)) {
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
        if (!SettleMod.saveDurably(level.getServer())) {
            colony.actionPersistenceBlocked = true;
            SettleMod.LOGGER.error("wf_settle: infrastructure adoption for {} could not be persisted; settlement frozen for recovery",
                    colony.s.id);
        }
    }

    @SubscribeEvent
    public void onVillagerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !(villager.level() instanceof ServerLevel level)) return;
        CharacterRegistry registry = PeopleMod.registryFor(level);
        if (registry == null) return;
        String uuid = villager.getUUID().toString();
        for (Character person : registry.all()) {
            Object raw = person.props.get("population.entity_bindings");
            if (!(raw instanceof Map<?, ?> bindings) || !uuid.equals(bindings.get("villager"))) continue;
            if (person.alive) PopulationSimulation.die(registry, person.id,
                    WorldForgeApi.clock().year(), "vanilla-villager-death");
            PeopleMod.persistCharacter(person, level.getServer());
            break;
        }
    }
}
