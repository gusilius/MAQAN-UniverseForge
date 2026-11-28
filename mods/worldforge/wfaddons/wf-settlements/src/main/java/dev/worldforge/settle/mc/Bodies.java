package dev.worldforge.settle.mc;

import dev.worldforge.settle.Worker;
import dev.worldforge.mod.api.SimulationLod;
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
import java.util.concurrent.ConcurrentHashMap;

/** Тела рабочих: по одному WFWorker на Worker, пока рядом (64 блока) есть игрок; когда игроки ушли (80 блоков) - тела убираются. */
public final class Bodies {
    private Bodies() {}

    public static final double SPAWN = SimulationLod.WORKER_SPAWN_RADIUS, DESPAWN = SimulationLod.WORKER_RETAIN_RADIUS;
    /** "поселение#рабочий" -> UUID живого тела. Тело, которого здесь нет, само исчезает (см. WFWorker.tick). */
    public static final Map<String, UUID> LIVE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LIVE_BODY_COUNTS = new HashMap<>();
    private static final int LIVE_RECONCILE_BUDGET_PER_PASS = 512;
    /** Weakly-consistent iterator survives map mutations between bounded server passes. */
    private static Iterator<Map.Entry<String, UUID>> liveReconciliationCursor;
    /** Per-colony worker scan cursor; avoids rescanning the same leading workers every pass. */
    private static final Map<String, Integer> WORKER_SCAN_CURSORS = new HashMap<>();

    public static String key(String colony, int worker) { return colony + "#" + worker; }
    static void clearRuntimeIndexes() {
        WORKER_SCAN_CURSORS.clear();
        clearLive();
    }

    static void clearLive() {
        LIVE.clear();
        LIVE_BODY_COUNTS.clear();
        liveReconciliationCursor = null;
    }

    private static String colonyIdFromLiveKey(String liveKey) {
        if (liveKey == null) return null;
        int separator = liveKey.lastIndexOf('#');
        return separator <= 0 ? null : liveKey.substring(0, separator);
    }

    private static void trackLive(String liveKey, UUID uuid) {
        UUID previous = LIVE.put(liveKey, uuid);
        if (previous == null) {
            String colonyId = colonyIdFromLiveKey(liveKey);
            if (colonyId != null) LIVE_BODY_COUNTS.merge(colonyId, 1, Integer::sum);
        }
    }

    private static void untrackLive(String liveKey, UUID uuid) {
        if (!LIVE.remove(liveKey, uuid)) return;
        String colonyId = colonyIdFromLiveKey(liveKey);
        if (colonyId != null) decrement(LIVE_BODY_COUNTS, colonyId);
    }
    static void forgetColony(String colonyId) {
        if (colonyId != null) WORKER_SCAN_CURSORS.remove(colonyId);
    }

    private static boolean hasLoadedVanillaVillagerBody(Worker worker, ServerLevel level) {
        if (worker == null || worker.person == null || worker.person.isBlank()) return false;
        dev.worldforge.people.CharacterRegistry registry = PeopleMod.authority().registryForGlobal(worker.person);
        if (registry == null) return false;
        String uuid = registry.entityBindingUuidForGlobal(worker.person, "villager");
        if (uuid == null || uuid.isBlank()) return false;
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

    /**
     * Maintains only the physical neighborhood. Existing bodies are reconciled
     * through LIVE (O(materialized bodies)); new bodies are considered only for
     * colonies returned by the spatial interest index (O(nearby colonies)).
     * The global colony registry is never traversed by this hot path.
     */
    public static void tick(MinecraftServer server) {
        int spawnBudget = Math.min(2,
                Math.max(0, dev.worldforge.mod.MaqanPerformanceConfig.maxWorkerSpawnsPerPass()));
        if (server == null) return;
        // A zero spawn budget disables creation only. Existing bodies must still be reconciled
        // and despawned when their colony leaves the retention radius or loses its worker.

        List<Colony> nearby = ColonyInterestIndex.nearby(server, DESPAWN);
        Map<String, Colony> nearbyById = new HashMap<>(Math.max(16, nearby.size() * 2));
        for (Colony colony : nearby) {
            if (colony != null && colony.s != null && colony.s.id != null)
                nearbyById.put(colony.s.id, colony);
        }

        // Reconcile a bounded slice of materialized entities. ConcurrentHashMap's
        // weakly-consistent iterator remains valid while workers spawn/despawn between passes.
        if (liveReconciliationCursor == null) liveReconciliationCursor = LIVE.entrySet().iterator();
        int reconciled = 0;
        while (reconciled < LIVE_RECONCILE_BUDGET_PER_PASS && liveReconciliationCursor.hasNext()) {
            Map.Entry<String, UUID> entry = liveReconciliationCursor.next();
            reconciled++;
            String liveKey = entry.getKey();
            UUID trackedUuid = entry.getValue();
            if (!trackedUuid.equals(LIVE.get(liveKey))) continue;

            int separator = liveKey.lastIndexOf('#');
            if (separator <= 0 || separator == liveKey.length() - 1) {
                untrackLive(liveKey, trackedUuid);
                continue;
            }
            String colonyId = liveKey.substring(0, separator);
            Colony colony = SettleMod.COLONIES.get(colonyId);
            if (colony == null || colony.s == null) {
                untrackLive(liveKey, trackedUuid);
                continue;
            }

            ServerLevel colonyLevel = colony.level(server);
            // An unloaded dimension is not an instruction to destroy its durable body binding.
            if (colonyLevel == null) continue;

            boolean discoveredVillage = colony.genesisOperationId != null
                    && colony.genesisOperationId.startsWith("minecraft:village:");
            boolean generatedSettlement = colony.genesisOperationId != null
                    && colony.genesisOperationId.startsWith("worldsim:settlement:")
                    && colony.s.count("village_hall", true) > 0;
            boolean playerFoundedSettlement = colony.genesisOperationId != null
                    && colony.genesisOperationId.startsWith("player:found:")
                    && colony.owner != null && !colony.owner.isBlank();
            boolean physicalSettlementReady = discoveredVillage || generatedSettlement || playerFoundedSettlement;
            boolean retained = physicalSettlementReady && nearbyById.containsKey(colonyId)
                    && SimulationLod.shouldRetainWorkers(colony.dim, colony.cx, colony.cz);
            int workerId;
            try {
                workerId = Integer.parseInt(liveKey.substring(separator + 1));
            } catch (NumberFormatException invalidKey) {
                workerId = -1;
            }
            boolean workerExists = workerId >= 0 && colony.s.worker(workerId) != null;
            Entity body = colonyLevel.getEntity(trackedUuid);

            if (!retained || !workerExists || (body != null && !body.isAlive())) {
                if (body != null) {
                    body.discard();
                    dev.worldforge.mod.MaqanPerformanceTelemetry.workerDespawned();
                }
                untrackLive(liveKey, trackedUuid);
                continue;
            }
            if (body == null) {
                // Worker bodies are intentionally ephemeral and are rematerialized when the
                // relevant loaded neighborhood is active again.
                untrackLive(liveKey, trackedUuid);
            }
        }
        if (!liveReconciliationCursor.hasNext()) liveReconciliationCursor = null;

        int spawned = 0;
        int colonyBodyLimit = Math.min(2,
                Math.max(0, dev.worldforge.mod.MaqanPerformanceConfig.maxActiveWorkerBodiesPerColony()));
        // Bound candidate inspection separately from successful spawns. Otherwise a nearby
        // colony with thousands of already-materialized workers forces a full worker-list scan
        // every pass even though the spawn budget is exhausted by existing bodies.
        int scanBudget = (int)Math.min(4096L, Math.max(256L, (long)spawnBudget * 16L));
        int perColonyScan = Math.max(1, (scanBudget + Math.max(1, nearby.size()) - 1)
                / Math.max(1, nearby.size()));
        for (Colony colony : nearby) {
            if (spawned >= spawnBudget || scanBudget <= 0) break;
            if (colony == null || colony.s == null || colony.s.id == null) continue;
            String colonyId = colony.s.id;
            // Physical workers are allowed only after a real vanilla village was adopted
            // or the generated settlement's civic hall is fully built. The canonical
            // Character/Worker remains independent of this nearby LOD representation.
            boolean discoveredVillage = colony.genesisOperationId != null
                    && colony.genesisOperationId.startsWith("minecraft:village:");
            boolean generatedSettlement = colony.genesisOperationId != null
                    && colony.genesisOperationId.startsWith("worldsim:settlement:")
                    && colony.s.count("village_hall", true) > 0;
            boolean playerFoundedSettlement = colony.genesisOperationId != null
                    && colony.genesisOperationId.startsWith("player:found:")
                    && colony.owner != null && !colony.owner.isBlank();
            if (!discoveredVillage && !generatedSettlement && !playerFoundedSettlement) continue;
            ServerLevel level = colony.level(server);
            if (level == null || !SimulationLod.shouldSpawnWorkers(
                    colony.dim, colony.cx, colony.cz)) continue;

            List<Worker> workers = colony.s.workers;
            int workerCount = workers.size();
            if (workerCount == 0) {
                WORKER_SCAN_CURSORS.remove(colonyId);
                continue;
            }
            int colonyBodyCount = LIVE_BODY_COUNTS.getOrDefault(colonyId, 0);
            if (colonyBodyCount >= colonyBodyLimit) continue;

            int inspect = Math.min(Math.min(workerCount, perColonyScan), scanBudget);
            int cursor = Math.floorMod(WORKER_SCAN_CURSORS.getOrDefault(colonyId, 0), workerCount);
            int inspected = 0;
            for (int offset = 0; offset < inspect && spawned < spawnBudget
                    && colonyBodyCount < colonyBodyLimit; offset++) {
                Worker worker = workers.get((cursor + offset) % workerCount);
                inspected++;
                if (worker == null) continue;
                String liveKey = key(colonyId, worker.id);
                UUID trackedUuid = LIVE.get(liveKey);
                Entity body = trackedUuid == null ? null : level.getEntity(trackedUuid);
                if (body != null && body.isAlive()) continue;

                if (hasLoadedVanillaVillagerBody(worker, level)) {
                    if (trackedUuid != null) untrackLive(liveKey, trackedUuid);
                    continue;
                }

                // Materialize at the worker's persisted settlement-local position.
                // A never-moved worker gets a stable home-ring slot derived from its ID;
                // player coordinates and per-attempt randomness never affect placement.
                double localX = worker.x, localZ = worker.z;
                if (Math.abs(localX) < 0.001 && Math.abs(localZ) < 0.001) {
                    double angle = worker.id * 2.399963229728653;
                    double radius = 3.0 + Math.sqrt(Math.floorMod(worker.id, 16)) * 0.65;
                    localX = Math.cos(angle) * radius;
                    localZ = Math.sin(angle) * radius;
                }
                int x = colony.cx + (int)Math.floor(localX);
                int z = colony.cz + (int)Math.floor(localZ);
                if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                WFWorker entity = SettleMod.WORKER.get().create(level);
                if (entity == null) continue;
                entity.colonyId = colonyId;
                entity.workerId = worker.id;
                entity.moveTo(x + 0.5, y, z + 0.5, level.random.nextFloat() * 360f, 0f);
                entity.refreshName();
                // A physical worker is an active representation of its canonical Character.
                // Hold a scoped lease until this entity is removed so eviction cannot invalidate
                // equipment, dialogue, work and social operations while the player is nearby.
                if (!entity.retainCanonicalCharacter()) continue;

                if (level.addFreshEntity(entity)) {
                    trackLive(liveKey, entity.getUUID());
                    dev.worldforge.mod.MaqanPerformanceTelemetry.workerSpawned();
                    spawned++;
                    colonyBodyCount++;
                } else {
                    entity.releaseCanonicalCharacter();
                }
            }
            if (inspected > 0)
                WORKER_SCAN_CURSORS.put(colonyId, (cursor + inspected) % workerCount);
            scanBudget -= inspected;
        }
    }
}
