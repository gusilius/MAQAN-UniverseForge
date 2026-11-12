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
