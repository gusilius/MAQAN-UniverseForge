package dev.worldforge.settle.mc;

import dev.worldforge.settle.Worker;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.people.Character;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Тела рабочих: по одному WFWorker на Worker, пока рядом (64 блока) есть игрок; когда игроки ушли (80 блоков) - тела убираются. */
public final class Bodies {
    private Bodies() {}

    public static final double SPAWN = SimulationLod.WORKER_SPAWN_RADIUS, DESPAWN = SimulationLod.WORKER_RETAIN_RADIUS;
    /** "поселение#рабочий" -> UUID живого тела. Тело, которого здесь нет, само исчезает (см. WFWorker.tick). */
    public static final Map<String, UUID> LIVE = new HashMap<>();

    private record ColonyRuntime(Colony colony, ServerLevel level, boolean retain, boolean spawn) {}

    public static String key(String colony, int worker) { return colony + "#" + worker; }

    private static boolean hasLoadedVanillaVillagerBody(Worker worker, ServerLevel level) {
        if (worker == null || worker.person == null || worker.person.isBlank()) return false;
        Character person = PeopleMod.authority().getGlobal(worker.person);
        if (person == null) return false;
        Object raw = person.props.get("population.entity_bindings");
        if (!(raw instanceof Map<?, ?> bindings) || !(bindings.get("villager") instanceof String uuid)) return false;
        try {
            Entity body = level.getEntity(UUID.fromString(uuid));
            return body instanceof net.minecraft.world.entity.npc.Villager villager && villager.isAlive();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static void decrement(Map<String, Integer> counts, String colonyId) {
        int current = counts.getOrDefault(colonyId, 0);
        if (current <= 1) counts.remove(colonyId);
        else counts.put(colonyId, current - 1);
    }

    public static void tick(MinecraftServer server) {
        int spawnBudget = dev.worldforge.mod.MaqanPerformanceConfig.maxWorkerSpawnsPerPass();
        if (spawnBudget <= 0) return;

        /*
         * Build the active colony view once. The previous implementation scanned
         * the entire LIVE map once per colony, making body maintenance O(C * L)
         * for C colonies and L materialized bodies.
         */
        List<ColonyRuntime> activeColonies = new ArrayList<>(SettleMod.COLONIES.size());
        Map<String, ColonyRuntime> byColonyId = new HashMap<>(Math.max(16, SettleMod.COLONIES.size() * 2));
        for (Colony colony : SettleMod.COLONIES.values()) {
            ServerLevel level = colony.level(server);
            if (level == null) continue;
            String id = colony.s.id;
            String dimension = level.dimension().location().toString();
            ColonyRuntime runtime = new ColonyRuntime(colony, level,
                    SimulationLod.shouldRetainWorkers(dimension, colony.cx, colony.cz),
                    SimulationLod.shouldSpawnWorkers(dimension, colony.cx, colony.cz));
            activeColonies.add(runtime);
            byColonyId.put(id, runtime);
        }

        // One pass over all tracked bodies: validate ownership and count survivors.
        Map<String, Integer> bodyCounts = new HashMap<>();
        Iterator<Map.Entry<String, UUID>> live = LIVE.entrySet().iterator();
        while (live.hasNext()) {
            Map.Entry<String, UUID> entry = live.next();
            String liveKey = entry.getKey();
            int separator = liveKey.lastIndexOf('#');
            if (separator <= 0 || separator == liveKey.length() - 1) continue;
            String colonyId = liveKey.substring(0, separator);
            ColonyRuntime runtime = byColonyId.get(colonyId);
            // Preserve records for dimensions that are not currently available, as before.
            if (runtime == null) continue;

            bodyCounts.merge(colonyId, 1, Integer::sum);
            boolean workerExists = false;
            try {
                workerExists = runtime.colony().s.worker(
                        Integer.parseInt(liveKey.substring(separator + 1))) != null;
            } catch (NumberFormatException ignored) {
                // Invalid legacy keys are stale and are removed below.
            }

            if (!runtime.retain() || !workerExists) {
                Entity body = runtime.level().getEntity(entry.getValue());
                if (body != null) {
                    body.discard();
                    dev.worldforge.mod.MaqanPerformanceTelemetry.workerDespawned();
                }
                live.remove();
                decrement(bodyCounts, colonyId);
            }
        }

        // Spawn in the same colony iteration order, using O(1) per-colony counts.
        int spawned = 0;
        int colonyBodyLimit = dev.worldforge.mod.MaqanPerformanceConfig.maxActiveWorkerBodiesPerColony();
        for (ColonyRuntime runtime : activeColonies) {
            if (!runtime.spawn() || spawned >= spawnBudget) continue;
            Colony colony = runtime.colony();
            ServerLevel level = runtime.level();
            String colonyId = colony.s.id;
            int colonyBodyCount = bodyCounts.getOrDefault(colonyId, 0);
            if (colonyBodyCount >= colonyBodyLimit) continue;

            for (Worker worker : colony.s.workers) {
                if (spawned >= spawnBudget || colonyBodyCount >= colonyBodyLimit) break;
                String liveKey = key(colonyId, worker.id);
                UUID trackedUuid = LIVE.get(liveKey);
                Entity body = trackedUuid == null ? null : level.getEntity(trackedUuid);
                if (hasLoadedVanillaVillagerBody(worker, level)) {
                    if (body != null) body.discard();
                    if (LIVE.remove(liveKey) != null) {
                        decrement(bodyCounts, colonyId);
                        colonyBodyCount--;
                    }
                    continue;
                }
                if (body != null && body.isAlive()) continue;

                int x = colony.cx + level.random.nextInt(9) - 4;
                int z = colony.cz + level.random.nextInt(9) - 4;
                if (!level.hasChunkAt(new BlockPos(x, 64, z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                WFWorker entity = SettleMod.WORKER.get().create(level);
                if (entity == null) continue;
                entity.colonyId = colonyId;
                entity.workerId = worker.id;
                entity.moveTo(x + 0.5, y, z + 0.5, level.random.nextFloat() * 360f, 0f);
                boolean alreadyTracked = trackedUuid != null;
                LIVE.put(liveKey, entity.getUUID());
                entity.refreshName();
                if (level.addFreshEntity(entity)) {
                    dev.worldforge.mod.MaqanPerformanceTelemetry.workerSpawned();
                    spawned++;
                    if (!alreadyTracked) {
                        bodyCounts.merge(colonyId, 1, Integer::sum);
                        colonyBodyCount++;
                    }
                } else {
                    if (LIVE.remove(liveKey) != null) {
                        decrement(bodyCounts, colonyId);
                        colonyBodyCount--;
                    }
                }
            }
        }
    }
}
