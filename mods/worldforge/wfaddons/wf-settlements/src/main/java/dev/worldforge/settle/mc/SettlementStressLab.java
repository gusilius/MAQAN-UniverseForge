package dev.worldforge.settle.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.worldforge.mod.Json;
import dev.worldforge.settle.Worker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.authlib.GameProfile;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraft.world.level.LevelResource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dedicated-server acceptance driver for contour 04.
 *
 * It creates many strategic-only settlements plus one real, player-adjacent
 * settlement. A protocol bot supplies a real ServerPlayer so the ordinary
 * Bodies/SimulationLod/WFWorker code is exercised without test-only entity
 * spawning. The same server tick is measured from START to END.
 */
public final class SettlementStressLab {
    private static final String PREFIX = "wfsettle_stress_";
    private static final String BOT_NAME = "MAQANStressBot";
    private static final int BATCH_SIZE = 16;
    private static final int TEST_TICKS = 420;

    private final MinecraftServer server;
    private final UUID playerId;
    private final ServerPlayer fakePlayer;
    private final ServerLevel level;
    private final double originX, originY, originZ;
    private final Colony local;
    private final int targetRemoteColonies;
    private final List<Colony> remote = new ArrayList<>();
    private final List<Double> tickMillis = new ArrayList<>();
    private long tickStartedNanos;
    private long runStartedNanos;
    private int created;
    private int elapsedTicks;
    private int nearBodies = -1;
    private int farBodies = -1;
    private int returnBodies = -1;
    private int navigationSamples;
    private int capturedTerrainCells;
    private boolean roundTripVerified;
    private boolean durableSnapshotVerified;
    private int canonicalWorkerLinks;
    private boolean teleportedFar;
    private boolean teleportedBack;
    private boolean failed;
    private String failure = "";

    private SettlementStressLab(MinecraftServer server, ServerPlayer player, int targetRemoteColonies) {
        this.server = server;
        this.playerId = player.getUUID();
        this.fakePlayer = player;
        this.level = player.serverLevel();
        this.originX = player.getX();
        this.originY = player.getY();
        this.originZ = player.getZ();
        this.targetRemoteColonies = targetRemoteColonies;
        String dimension = level.dimension().location().toString();
        this.local = SettleMod.foundEmergentInMemory(server, dimension,
                PREFIX + "local", "MAQAN Stress Local",
                player.blockPosition().getX(), player.blockPosition().getZ(),
                "wf-settlement-stress:local");
        local.s.add("food", 10000);
        local.s.add("wood", 10000);
        local.s.add("stone", 10000);
        prepareLocalWorksite();
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wfsettle-stress")
                .then(Commands.literal("start")
                        .then(Commands.argument("remote_colonies", IntegerArgumentType.integer(1, 2000))
                                .executes(SettlementStressLab::startCommand))));
    }

    private static int startCommand(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!Boolean.getBoolean("wf.settlement.stress.enabled")) {
            source.sendFailure(Component.literal("Contour 04 stress driver is disabled outside acceptance mode"));
            return 0;
        }
        ServerPlayer player;
        try {
            UUID botId = UUID.nameUUIDFromBytes((source.getServer().getWorldData().getLevelName()
                    + ":" + BOT_NAME).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            player = FakePlayerFactory.get(source.getLevel(), new GameProfile(botId, BOT_NAME));
            if (!source.getServer().getPlayerList().getPlayers().contains(player))
                source.getServer().getPlayerList().getPlayers().add(player);
            if (!player.isAddedToWorld()) source.getLevel().addNewPlayer(player);
            player.teleportTo(source.getLevel(), source.getPosition().x, source.getPosition().y,
                    source.getPosition().z, Set.of(), source.getRotation().y, source.getRotation().x);
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("Could not attach server-side test player: " + error));
            return 0;
        }
        if (SettleMod.DEFS == null) {
            source.sendFailure(Component.literal("Settlement definitions are not ready"));
            return 0;
        }
        try {
            SettlementStressLab lab = new SettlementStressLab(source.getServer(), player,
                    IntegerArgumentType.getInteger(context, "remote_colonies"));
            MinecraftForge.EVENT_BUS.register(lab);
            source.sendSuccess(() -> Component.literal("WF_SETTLEMENT_STRESS_STARTED remote=" + lab.targetRemoteColonies
                    + " local_workers=" + lab.local.s.workers.size()), true);
            return 1;
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("Contour 04 setup failed: " + error));
            return 0;
        }
    }

    private void prepareLocalWorksite() {
        int centerX = (int) Math.floor(originX);
        int centerZ = (int) Math.floor(originZ);
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        BlockPos probe = new BlockPos(centerX, level.getMinBuildHeight(), centerZ);
        if (!level.hasChunkAt(probe))
            throw new IllegalStateException("The bot spawn chunk is not loaded; refusing to force-load a test chunk");

        // A small, deterministic work pad in the already-loaded player chunk.
        // No remote chunk is requested by the acceptance driver.
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                int x = centerX + dx, z = centerZ + dz;
                if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z))) continue;
                level.setBlock(new BlockPos(x, baseY - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++)
                    level.setBlock(new BlockPos(x, baseY + dy, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }

        for (int index = 0; index < 4; index++) {
            Worker worker = local.s.addWorker("Stress lumberjack " + (index + 1));
            worker.profession = "lumberjack";
            int treeX = centerX + 4 + index * 2;
            int treeZ = centerZ + 6;
            for (int dy = 0; dy < 3; dy++)
                level.setBlock(new BlockPos(treeX, baseY + dy, treeZ),
                        Blocks.OAK_LOG.defaultBlockState(), 3);
            Colony.WorkOrder order = new Colony.WorkOrder();
            order.mode = "wood";
            order.x = treeX;
            order.z = treeZ;
            order.radius = 4;
            order.halfX = 4;
            order.halfZ = 4;
            order.overlay = 0;
            local.workOrders.put(worker.id, order);
        }
        capturedTerrainCells = local.captureTerrain(level, -16, -16, 16, 16);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartedNanos = System.nanoTime();
            return;
        }
        if (event.phase != TickEvent.Phase.END || failed) return;

        if (runStartedNanos == 0) {
            createRemoteBatch();
            if (created >= targetRemoteColonies) finishSetup();
            return;
        }

        long now = System.nanoTime();
        tickMillis.add((now - tickStartedNanos) / 1_000_000.0);
        elapsedTicks++;
        navigationSamples += countNavigatingLocalWorkers();

        if (elapsedTicks == 100) nearBodies = countLocalBodies();
        if (elapsedTicks == 140) teleportPlayer(true);
        if (elapsedTicks == 220) farBodies = countLocalBodies();
        if (elapsedTicks == 240) teleportPlayer(false);
        if (elapsedTicks == 360) returnBodies = countLocalBodies();
        if (elapsedTicks >= TEST_TICKS) finish();
    }

    private void createRemoteBatch() {
        int end = Math.min(targetRemoteColonies, created + BATCH_SIZE);
        String dimension = level.dimension().location().toString();
        while (created < end) {
            int index = created++;
            String id = PREFIX + "remote_" + index;
            Colony colony = SettleMod.foundEmergentInMemory(server, dimension, id,
                    "MAQAN Stress Remote " + index,
                    8192 + index * 128, 8192,
                    "wf-settlement-stress:remote:" + index);
            colony.s.setCanonicalPopulation(100 + (index % 900));
            remote.add(colony);
        }
        if (created % 128 == 0 || created == targetRemoteColonies)
            SettleMod.LOGGER.info("WF_STRESS contour=04_settlements setup={}/{}",
                    created, targetRemoteColonies);
    }

    private void finishSetup() {
        if (runStartedNanos != 0) return;
        if (local.workOrders.size() != local.s.workers.size())
            throw new IllegalStateException("Every local worker must own exactly one persisted work-area order");
        Colony restored = Colony.fromMap(local.toMap(), SettleMod.DEFS);
        roundTripVerified = restored.workOrders.size() == local.workOrders.size()
                && restored.s.workers.size() == local.s.workers.size()
                && restored.map.cells().size() == local.map.cells().size();
        if (!roundTripVerified)
            throw new IllegalStateException("Settlement worker/work-area/map round-trip failed");
        var canonicalPeople = dev.worldforge.people.mc.PeopleMod.registryFor(level);
        if (canonicalPeople != null) {
            for (Worker worker : local.s.workers) {
                if (worker.person != null && !worker.person.isBlank()
                        && canonicalPeople.getByGlobalId(worker.person) != null) canonicalWorkerLinks++;
            }
        }
        durableSnapshotVerified = SettleMod.saveDurably(server);
        if (!durableSnapshotVerified)
            throw new IllegalStateException("The settlement snapshot could not be durably committed");
        runStartedNanos = System.nanoTime();
        SettleMod.LOGGER.info("WF_STRESS contour=04_settlements phase=measuring colonies={} workers={} work_orders={} terrain_cells={}",
                SettleMod.COLONIES.size(), local.s.workers.size(), local.workOrders.size(), capturedTerrainCells);
    }

    private void teleportPlayer(boolean far) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            failed = true;
            failure = "The protocol bot disconnected during physical LOD transition";
            finish();
            return;
        }
        if (far) {
            player.teleportTo(level, 12000, 100, 12000, Set.of(), player.getYRot(), player.getXRot());
            teleportedFar = true;
        } else {
            player.teleportTo(level, originX, originY, originZ, Set.of(), player.getYRot(), player.getXRot());
            teleportedBack = true;
        }
    }

    private int countLocalBodies() {
        int count = 0;
        String prefix = local.s.id + "#";
        for (Map.Entry<String, UUID> entry : Bodies.LIVE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            for (ServerLevel candidate : server.getAllLevels()) {
                var entity = candidate.getEntity(entry.getValue());
                if (entity instanceof WFWorker && entity.isAlive()) { count++; break; }
            }
        }
        return count;
    }

    private int countNavigatingLocalWorkers() {
        int count = 0;
        String prefix = local.s.id + "#";
        for (Map.Entry<String, UUID> entry : Bodies.LIVE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            var entity = level.getEntity(entry.getValue());
            if (entity instanceof WFWorker worker && worker.isAlive() && !worker.getNavigation().isDone()) count++;
        }
        return count;
    }

    private int remoteChunksLoaded() {
        int count = 0;
        for (Colony colony : remote) {
            if (level.hasChunkAt(new BlockPos(colony.cx, level.getMinBuildHeight(), colony.cz))) count++;
        }
        return count;
    }

    private void finish() {
        MinecraftForge.EVENT_BUS.unregister(this);
        if (fakePlayer.isAddedToWorld()) {
            server.getPlayerList().getPlayers().remove(fakePlayer);
            fakePlayer.serverLevel().removePlayerImmediately(fakePlayer,
                    net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
        List<Double> sorted = new ArrayList<>(tickMillis);
        sorted.sort(Comparator.naturalOrder());
        double p50 = percentile(sorted, 0.50);
        double p95 = percentile(sorted, 0.95);
        double p99 = percentile(sorted, 0.99);
        double max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
        double elapsedSeconds = runStartedNanos == 0 ? 0 : (System.nanoTime() - runStartedNanos) / 1_000_000_000.0;
        double tps = elapsedSeconds <= 0 ? 0 : elapsedTicks / elapsedSeconds;
        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        int remoteLoaded = remoteChunksLoaded();
        boolean lodVerified = nearBodies >= 4 && farBodies == 0 && returnBodies >= 4
                && teleportedFar && teleportedBack && navigationSamples > 0;
        boolean performanceVerified = p95 <= 50.0 && p99 <= 100.0 && max <= 1000.0 && tps >= 15.0;
        boolean statusPass = !failed && created == targetRemoteColonies && roundTripVerified
                && durableSnapshotVerified && canonicalWorkerLinks == local.s.workers.size()
                && remoteLoaded == 0 && lodVerified && performanceVerified;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("status", statusPass ? "PASS" : "FAIL");
        report.put("runtime_kind", "minecraft_forge_dedicated_server_settlement_stress");
        report.put("dedicated_server", true);
        report.put("required_mods_loaded", net.minecraftforge.fml.ModList.get().isLoaded("wf_settle")
                && net.minecraftforge.fml.ModList.get().isLoaded("wf_people"));
        report.put("created_remote_colonies", created);
        report.put("total_colonies", targetRemoteColonies + 1);
        report.put("local_workers", local.s.workers.size());
        report.put("local_work_orders", local.workOrders.size());
        report.put("terrain_cells_captured_from_loaded_world", capturedTerrainCells);
        report.put("work_order_save_load_round_trip", roundTripVerified);
        report.put("durable_settlement_snapshot", durableSnapshotVerified);
        report.put("canonical_worker_character_links", canonicalWorkerLinks);
        report.put("canonical_worker_identity_verified", canonicalWorkerLinks == local.s.workers.size());
        report.put("physical_lod_verified", lodVerified);
        report.put("near_player_worker_bodies", nearBodies);
        report.put("far_player_worker_bodies", farBodies);
        report.put("return_player_worker_bodies", returnBodies);
        report.put("worker_navigation_active_samples", navigationSamples);
        report.put("remote_settlement_chunks_loaded", remoteLoaded);
        report.put("measured_server_ticks", elapsedTicks);
        report.put("elapsed_seconds", elapsedSeconds);
        report.put("server_tps", tps);
        report.put("server_tick_ms_p50", p50);
        report.put("server_tick_ms_p95", p95);
        report.put("server_tick_ms_p99", p99);
        report.put("server_tick_ms_max", max);
        report.put("heap_used_bytes", heapUsed);
        report.put("performance_thresholds", Map.of(
                "server_tick_ms_p95_max", 50.0,
                "server_tick_ms_p99_max", 100.0,
                "server_tick_ms_max_max", 1000.0,
                "server_tps_min", 15.0));
        if (!failure.isBlank()) report.put("failure", failure);

        try {
            Path output = server.getWorldPath(LevelResource.ROOT)
                    .resolve("worldforge").resolve("stresslab").resolve("contour-04-settlements.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, Json.write(report));
            SettleMod.LOGGER.info("WF_STRESS contour=04_settlements status={} colonies={} workers={} nearBodies={} farBodies={} returnBodies={} tickP95ms={} tickP99ms={} tickMaxMs={} tps={} heapBytes={} remoteChunksLoaded={}",
                    statusPass ? "PASS" : "FAIL", targetRemoteColonies + 1, local.s.workers.size(),
                    nearBodies, farBodies, returnBodies, p95, p99, max, tps, heapUsed, remoteLoaded);
            SettleMod.LOGGER.info("WF_STRESS contour=04_settlements report={}", output);
        } catch (Exception error) {
            SettleMod.LOGGER.error("WF_STRESS contour=04_settlements report write failed", error);
        }
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, Math.min(sorted.size() - 1,
                (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }
}
