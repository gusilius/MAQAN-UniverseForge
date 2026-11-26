package dev.worldforge.worldsim.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.army.Army;
import dev.worldforge.army.ArmyEngine;
import dev.worldforge.army.mc.ArmyMod;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.settle.TradeNetwork;
import dev.worldforge.settle.mc.Colony;
import dev.worldforge.settle.mc.SettleMod;
import dev.worldforge.troops.mc.TroopsMod;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in Stage A acceptance driver. It observes and exercises the production settlement,
 * treasury, trade-network, army recruitment and field-supply services in one loaded Minecraft
 * save. It never fabricates a second population or substitutes test-only economy counters.
 *
 * Run only on a disposable copy of a real world:
 *   -Dwf.living.acceptance.enabled=true
 *   /wfworldsim acceptance-a start [duration_ticks]
 */
public final class LivingWorldAcceptanceLab {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String REPORT_NAME = "living-world-stage-a.json";
    private static volatile Run active;

    private static final class Candidate {
        final Colony source, destination;
        final String resource, dutyRealm;
        Candidate(Colony source, Colony destination, String resource, String dutyRealm) {
            this.source = source; this.destination = destination;
            this.resource = resource; this.dutyRealm = dutyRealm;
        }
    }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final Colony source, destination;
        final String resource, realm, worldId, mapId;
        final int durationTicks;
        final long startedNanos = System.nanoTime();
        final long[] tickNanos;
        final long gcCountStart = gcMetric(true), gcMillisStart = gcMetric(false);
        final long heapStart = heapUsed();
        final long treasurySequenceStart;
        final long sourceProductionSequenceStart;
        final double sourceStockStart, destinationStockStart, destinationSilverStart;
        final double sourcePriceStart, destinationPriceStart;
        final double realmBalanceStart;
        final int sourceWorkersStart, destinationWorkersStart;
        final int registryPopulationStart, registryMaterializedStart;
        final int physicalWorkersStart;
        final int routeId;
        final boolean routeCreated;
        final ArmyEngine armyEngine;
        final Army army;
        final long startedWorldDay;
        int tickCount, tickCursor, tickSamples;
        long maxTickNanos;
        long peakHeapBytes = heapStart;
        long peakCommittedBytes = Runtime.getRuntime().totalMemory();
        double peakCpuPercent;
        int walQueueHighWater;
        int physicalWorkersPeak = -1;
        boolean finished;
        String failure = "";
        double deliveredRations;
        double consumedRations;
        boolean supplyCheckpoint;
        boolean treasuryCheckpoint;
        boolean settlementCheckpoint;
        boolean armyCheckpoint;
        long routeCarriedStart;
        long taxCreditsStart;
        long taxDebitsStart;
        long tradeVolumeStart;
        long routeShipmentsAtStart;

        Run(MinecraftServer server, ServerLevel level, Candidate candidate, int durationTicks,
            int routeId, boolean routeCreated, ArmyEngine armyEngine, Army army) {
            this.server = server; this.level = level;
            this.source = candidate.source; this.destination = candidate.destination;
            this.resource = candidate.resource; this.realm = candidate.source.realmId;
            this.worldId = String.valueOf(WorldForgeMod.worldId());
            this.mapId = Objects.requireNonNull(EditorManager.dataFor(level)).name;
            this.durationTicks = durationTicks; this.routeId = routeId; this.routeCreated = routeCreated;
            this.armyEngine = armyEngine; this.army = army;
            this.tickNanos = new long[Math.min(Math.max(durationTicks, 200), 24_000)];
            this.treasurySequenceStart = WorldForgeMod.treasury().nextSequence();
            this.sourceStockStart = source.s.amount(resource);
            this.destinationStockStart = destination.s.amount(resource);
            this.destinationSilverStart = destination.s.amount("silver");
            this.sourcePriceStart = source.s.price(resource);
            this.destinationPriceStart = destination.s.price(resource);
            this.realmBalanceStart = armyEngine.gold(realm);
            this.sourceWorkersStart = source.s.workers.size();
            this.destinationWorkersStart = destination.s.workers.size();
            CharacterRegistry registry = PeopleMod.registryFor(level);
            this.registryPopulationStart = registry == null ? 0 : registry.size();
            this.registryMaterializedStart = registry == null ? 0 : registry.materializedSize();
            this.physicalWorkersStart = physicalWorkerCount(level);
            this.startedWorldDay = Math.max(0, WorldForgeApi.clock().dayNumber());
            TradeNetwork.Route route = SettleMod.NETWORK.routes.get(routeId);
            this.routeCarriedStart = route == null ? 0 : (long) route.carried;
            this.routeShipmentsAtStart = SettleMod.NETWORK.shipments.values().stream()
                    .filter(s -> s.routeId == routeId).count();
            String sourceSilver = source.s.ledgerAccountId("silver");
            String realmAccount = "realm:" + realm;
            this.taxCreditsStart = WorldForgeMod.treasury().entriesForAccount(realmAccount).stream()
                    .filter(e -> e.credit() && e.kind() == TreasuryLedger.Kind.TAX).count();
            this.taxDebitsStart = WorldForgeMod.treasury().entriesForAccount(sourceSilver).stream()
                    .filter(e -> !e.credit() && e.kind() == TreasuryLedger.Kind.TAX).count();
            this.tradeVolumeStart = (long) SettleMod.NETWORK.shipments.size();
        }
    }

    public LivingWorldAcceptanceLab() { }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfworldsim")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("acceptance-a")
                        .then(Commands.literal("start")
                                .executes(context -> start(context.getSource().getServer(), context.getSource(), 6000))
                                .then(Commands.argument("duration_ticks", IntegerArgumentType.integer(1200, 24000))
                                        .executes(context -> start(context.getSource().getServer(), context.getSource(),
                                                IntegerArgumentType.getInteger(context, "duration_ticks")))))
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))));
    }

    private static int start(MinecraftServer server, net.minecraft.commands.CommandSourceStack source, int duration) {
        if (!Boolean.getBoolean("wf.living.acceptance.enabled")) {
            source.sendFailure(Component.literal("Stage A is disabled. Start a disposable test server with -Dwf.living.acceptance.enabled=true."));
            return 0;
        }
        if (active != null && !active.finished) {
            source.sendFailure(Component.literal("Integrated living-world Stage A is already running."));
            return 0;
        }
        if (WorldForgeMod.worldId() == null || !PeopleMod.identityPartitionsReady()) {
            source.sendFailure(Component.literal("INCONCLUSIVE: canonical world identity / Character partitions are not ready."));
            return 0;
        }
        if (SettleMod.actionsBlocked() || ArmyMod.actionsBlocked()) {
            source.sendFailure(Component.literal("FAIL: settlement or army persistence is blocked; no acceptance mutations were attempted."));
            return 0;
        }

        Candidate candidate = chooseCandidate(server);
        if (candidate == null) {
            source.sendFailure(Component.literal("INCONCLUSIVE: need two same-dimension settlements with staffed markets, a price spread, export stock and buyer silver."));
            return 0;
        }
        ServerLevel level = candidate.source.level(server);
        MapData map = level == null ? null : EditorManager.dataFor(level);
        if (level == null || map == null || map.titles == null || !map.titles.realms.containsKey(candidate.source.realmId)) {
            source.sendFailure(Component.literal("INCONCLUSIVE: selected settlement has no loaded canonical political map/realm."));
            return 0;
        }

        ArmyEngine engine;
        Army army;
        try {
            engine = ArmyMod.armies(map);
            army = engine.recruitWithPersistence(
                    "living-acceptance-a:" + WorldForgeMod.worldId() + ":" + WorldForgeApi.clock().dayNumber(),
                    "hire", candidate.source.realmId, "archer", 1);
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("FAIL: production ArmyEngine recruitment/persistence rejected Stage A: " + error.getMessage()));
            LOGGER.error("LIVING_WORLD_STAGE_A recruitment failed", error);
            return 0;
        }

        int routeId = -1;
        boolean routeCreated = false;
        try {
            TradeNetwork.Route existing = SettleMod.NETWORK.routes.values().stream()
                    .filter(r -> r.from.equals(candidate.source.s.id) && r.to.equals(candidate.destination.s.id)
                            && r.res.equals(candidate.resource)).findFirst().orElse(null);
            if (existing == null) {
                Map<String, dev.worldforge.settle.Settlement> settlements = new LinkedHashMap<>();
                SettleMod.COLONIES.values().forEach(c -> { if (c != null && c.s != null) settlements.put(c.s.id, c.s); });
                TradeNetwork.Route route = SettleMod.NETWORK.add(settlements, candidate.source.s.id,
                        candidate.destination.s.id, candidate.resource, 1.0);
                routeId = route.id;
                routeCreated = true;
            } else routeId = existing.id;
            SettleMod.NETWORK.setDuty(routeId, candidate.destination.realmId, 0.05);
            if (!SettleMod.saveDurably(server) || !WorldForgeMod.saveTreasuryNow(server))
                throw new IllegalStateException("trade route/treasury checkpoint failed");
        } catch (RuntimeException error) {
            source.sendFailure(Component.literal("FAIL: production settlement trade route could not be committed: " + error.getMessage()));
            LOGGER.error("LIVING_WORLD_STAGE_A route setup failed", error);
            return 0;
        }

        Run run = new Run(server, level, candidate, duration, routeId, routeCreated, engine, army);
        active = run;
        LOGGER.warn("LIVING_WORLD_STAGE_A status=STARTED world={} source={} destination={} resource={} realm={} army={} route={} durationTicks={} commit={}",
                run.worldId, run.source.s.id, run.destination.s.id, run.resource, run.realm, army.id, routeId,
                duration, System.getProperty("wf.acceptance.commit", "runtime-unknown"));
        source.sendSuccess(() -> Component.literal("Stage A started in this Minecraft save: route=" + routeId
                + ", recruited army=" + army.id + ", duration=" + duration + " ticks. Run /wfworldsim acceptance-a status."), true);
        return 1;
    }

    private static Candidate chooseCandidate(MinecraftServer server) {
        List<Colony> colonies = SettleMod.COLONIES.values().stream()
                .filter(c -> c != null && c.s != null && c.realmId != null && !c.realmId.isBlank()
                        && c.level(server) != null && c.s.tradePerMin() > 0)
                .sorted(Comparator.comparing(c -> c.s.id)).toList();
        for (Colony source : colonies) for (Colony destination : colonies) {
            if (source == destination || !source.dim.equals(destination.dim)
                    || !Objects.equals(source.realmId, destination.realmId)) continue;
            for (String resource : List.of("wood", "food", "stone", "grain")) {
                double sourceStock = source.s.amount(resource), destinationStock = destination.s.amount(resource);
                double sourcePrice = source.s.price(resource), destinationPrice = destination.s.price(resource);
                if (source.s.basePrice(resource) <= 0 || sourceStock < 30 || sourcePrice <= 0
                        || destinationPrice < sourcePrice * TradeNetwork.MIN_SPREAD
                        || destination.s.capacity(resource) - destinationStock < 2
                        || destination.s.amount("silver") < 10) continue;
                return new Candidate(source, destination, resource, destination.realmId);
            }
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || run.finished || run.server != event.getServer()) return;
        if (event.phase == TickEvent.Phase.START) {
            run.tickStartNanos = System.nanoTime();
            return;
        }
        long elapsed = Math.max(0L, System.nanoTime() - run.tickStartNanos);
        run.tickNanos[run.tickCursor] = elapsed;
        run.tickCursor = (run.tickCursor + 1) % run.tickNanos.length;
        run.tickSamples = Math.min(run.tickSamples + 1, run.tickNanos.length);
        run.tickCount++;
        run.maxTickNanos = Math.max(run.maxTickNanos, elapsed);
        run.peakHeapBytes = Math.max(run.peakHeapBytes, heapUsed());
        run.peakCommittedBytes = Math.max(run.peakCommittedBytes, Runtime.getRuntime().totalMemory());
        run.peakCpuPercent = Math.max(run.peakCpuPercent, processCpuPercent());
        CharacterRegistry registry = PeopleMod.registryFor(run.level);
        if (registry != null) run.walQueueHighWater = Math.max(run.walQueueHighWater, PeopleMod.characterWalQueueDepth(registry));
        run.physicalWorkersPeak = Math.max(run.physicalWorkersPeak, physicalWorkerCount(run.level));
        if (run.tickCount >= run.durationTicks) finish(run);
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        Run run = active;
        if (run == null) {
            source.sendSuccess(() -> Component.literal("No Stage A run in this server process."), false);
            return 1;
        }
        TradeNetwork.Route route = SettleMod.NETWORK.routes.get(run.routeId);
        String routeStatus = route == null ? "missing" : route.status;
        source.sendSuccess(() -> Component.literal("Stage A: ticks=" + run.tickCount + "/" + run.durationTicks
                + ", route=" + routeStatus + ", carried=" + (route == null ? 0 : route.carried)
                + ", army=" + run.army.id + ", physicalWorkers=" + physicalWorkerCount(run.level)
                + ", WALQueueHighWater=" + run.walQueueHighWater + ", finished=" + run.finished
                + ", failure=" + run.failure), false);
        return 1;
    }

    private static void finish(Run run) {
        if (run.finished) return;
        run.finished = true;
        try {
            run.settlementCheckpoint = SettleMod.saveDurably(run.server);
            run.treasuryCheckpoint = WorldForgeMod.saveTreasuryNow(run.server);
            run.armyCheckpoint = ArmyMod.persistNow(run.server);
            String supplyAccount = TroopsMod.acceptanceSupplyAccountId(run.realm, run.army.id,
                    run.mapId, run.level.dimension().location().toString());
            String supplyOwner = TroopsMod.acceptanceSupplyOwnerId(run.realm, run.army.id, run.mapId);
            double delivered = 0;
            double consumed = 0;
            if (run.source.s.amount("food") > 1.0) {
                delivered = SettleMod.transferFoodToArmy(run.level, run.realm, supplyAccount, supplyOwner,
                        1.0, run.army.location, run.army.location, "living-acceptance-a-rations:" + run.worldId);
                TreasuryLedger ledger = WorldForgeMod.treasury();
                double available = ledger.balance(supplyAccount);
                consumed = Math.min(1.0, available);
                if (consumed > 1e-9 && ledger.withdrawOnce(supplyAccount, consumed,
                        TreasuryLedger.Kind.CONSUMPTION, "field-rations",
                        "living-acceptance-a-consumption:" + run.worldId,
                        WorldForgeApi.clock().worldMinute(), "Stage A integrated army field ration")) {
                    run.treasuryCheckpoint = WorldForgeMod.saveTreasuryNow(run.server) && run.treasuryCheckpoint;
                    run.supplyCheckpoint = TroopsMod.advanceAcceptanceSupply(run.realm, run.army.id,
                            run.mapId, run.level.dimension().location().toString(), 1, 1.0,
                            available, consumed, run.startedWorldDay);
                }
            }
            run.deliveredRations = delivered;
            run.consumedRations = consumed;
        } catch (RuntimeException error) {
            run.failure = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            LOGGER.error("LIVING_WORLD_STAGE_A finalization failed", error);
        }

        TradeNetwork.Route route = SettleMod.NETWORK.routes.get(run.routeId);
        long carried = route == null ? 0 : (long) Math.max(0, route.carried - run.routeCarriedStart);
        long newShipments = SettleMod.NETWORK.shipments.values().stream()
                .filter(s -> s.routeId == run.routeId).count() - run.routeShipmentsAtStart;
        long deliveredShipments = SettleMod.NETWORK.shipments.values().stream()
                .filter(s -> s.routeId == run.routeId && "доставлен".equals(s.status)).count();
        TreasuryLedger ledger = WorldForgeMod.treasury();
        String sourceStockAccount = run.source.s.ledgerAccountId(run.resource);
        String realmAccount = "realm:" + run.realm;
        long productionReceipts = ledger.entriesForAccount(sourceStockAccount).stream()
                .filter(e -> e.sequence() >= run.treasurySequenceStart && e.kind() == TreasuryLedger.Kind.PRODUCTION && e.credit()).count();
        long dutyCredits = ledger.entriesForAccount(realmAccount).stream()
                .filter(e -> e.sequence() >= run.treasurySequenceStart && e.kind() == TreasuryLedger.Kind.DUTY && e.credit()).count();
        long taxCredits = ledger.entriesForAccount(realmAccount).stream()
                .filter(e -> e.sequence() >= run.treasurySequenceStart && e.kind() == TreasuryLedger.Kind.TAX && e.credit()).count();
        long taxDebits = ledger.entriesForAccount(run.source.s.ledgerAccountId("silver")).stream()
                .filter(e -> e.sequence() >= run.treasurySequenceStart && e.kind() == TreasuryLedger.Kind.TAX && !e.credit()).count();

        List<Long> sorted = new ArrayList<>();
        for (int i=0;i<run.tickSamples;i++) sorted.add(run.tickNanos[i]);
        sorted.sort(Long::compare);
        double p50 = percentileMs(sorted, .50), p95 = percentileMs(sorted, .95);
        double p99 = percentileMs(sorted, .99), max = run.maxTickNanos / 1_000_000.0;
        double elapsedSeconds = Math.max(.001, (System.nanoTime() - run.startedNanos) / 1_000_000_000.0);
        double tps = Math.min(20.0, run.tickCount / elapsedSeconds);
        CharacterRegistry registry = PeopleMod.registryFor(run.level);
        int population = registry == null ? 0 : registry.size();
        int materialized = registry == null ? 0 : registry.materializedSize();
        int physicalWorkers = physicalWorkerCount(run.level);
        long gcCount = Math.max(0, gcMetric(true) - run.gcCountStart);
        long gcMillis = Math.max(0, gcMetric(false) - run.gcMillisStart);

        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("stage", "A");
        report.put("status", run.failure.isBlank() ? "INCONCLUSIVE" : "FAIL");
        report.put("verdict_reason", run.failure.isBlank()
                ? "Server-side causal receipts were collected. Client frame-time and restart/recovery proof are separate required evidence and have not been collected by this dedicated-server run."
                : run.failure);
        report.put("commit", System.getProperty("wf.acceptance.commit", "runtime-unknown"));
        report.put("minecraft_version", SharedConstants.getCurrentVersion().getName());
        report.put("forge_version", ModList.get().getModContainerById("forge").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown"));
        report.put("java_version", System.getProperty("java.version", "unknown"));
        report.put("world_id", run.worldId);
        report.put("world_path", run.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        report.put("seed", run.server.getWorldData().worldGenOptions().seed());
        report.put("hardware_profile", System.getProperty("os.name") + " / " + Runtime.getRuntime().availableProcessors()
                + " CPUs / maxHeap=" + Runtime.getRuntime().maxMemory());
        report.put("duration_seconds", elapsedSeconds);
        report.put("tick_count", run.tickCount);
        report.put("source_settlement", run.source.s.id);
        report.put("destination_settlement", run.destination.s.id);
        report.put("resource", run.resource);
        report.put("realm_id", run.realm);
        report.put("trade_route_id", run.routeId);
        report.put("trade_route_carried_delta", carried);
        report.put("trade_shipments_created_delta", newShipments);
        report.put("trade_shipments_delivered_total", deliveredShipments);
        report.put("source_resource_stock_before", run.sourceStockStart);
        report.put("source_resource_stock_after", run.source.s.amount(run.resource));
        report.put("destination_resource_stock_before", run.destinationStockStart);
        report.put("destination_resource_stock_after", run.destination.s.amount(run.resource));
        report.put("source_price_before", run.sourcePriceStart);
        report.put("destination_price_before", run.destinationPriceStart);
        report.put("source_production_receipts", productionReceipts);
        report.put("customs_duty_credit_receipts", dutyCredits);
        report.put("crown_tax_credit_receipts", taxCredits);
        report.put("settlement_tax_debit_receipts", taxDebits);
        report.put("realm_balance_before", run.realmBalanceStart);
        report.put("realm_balance_after", ledger.balance(realmAccount));
        report.put("army_id", run.army.id);
        report.put("army_recruited_units", new LinkedHashMap<>(run.army.units));
        report.put("army_rations_transferred", run.deliveredRations);
        report.put("army_rations_consumed", run.consumedRations);
        report.put("army_supply_checkpoint", run.supplyCheckpoint);
        report.put("settlement_durable_checkpoint", run.settlementCheckpoint);
        report.put("treasury_durable_checkpoint", run.treasuryCheckpoint);
        report.put("army_durable_checkpoint", run.armyCheckpoint);
        report.put("character_population", population);
        report.put("character_materialized", materialized);
        report.put("physical_worker_entities_start", run.physicalWorkersStart);
        report.put("physical_worker_entities_peak", run.physicalWorkersPeak);
        report.put("physical_worker_entities_end", physicalWorkers);
        report.put("wal_queue_depth_high_water", run.walQueueHighWater);
        report.put("heap_used_bytes_start", run.heapStart);
        report.put("heap_used_bytes_max", run.peakHeapBytes);
        report.put("heap_committed_bytes_max", run.peakCommittedBytes);
        report.put("gc_collections_delta", gcCount);
        report.put("gc_collection_ms_delta", gcMillis);
        report.put("cpu_percent_max", run.peakCpuPercent);
        report.put("server_tps", tps);
        report.put("server_mspt_p50", p50);
        report.put("server_mspt_p95", p95);
        report.put("server_mspt_p99", p99);
        report.put("server_mspt_max", max);
        report.put("client_fps", "NOT_MEASURED_DEDICATED_SERVER");
        report.put("restart_recovery", "NOT_MEASURED");
        report.put("subsystem_receipts", Map.of(
                "population", population > 0,
                "settlements", run.source.s.workers.size() > 0 && run.destination.s.workers.size() > 0,
                "economy", carried > 0 || newShipments > 0,
                "politics", run.realm != null && !run.realm.isBlank(),
                "army_rts", run.army.men() > 0,
                "field_supply", run.supplyCheckpoint));
        report.put("causal_chain", Map.of(
                "production_to_market", productionReceipts > 0 && carried > 0,
                "market_to_treasury_politics", dutyCredits > 0 || taxCredits > 0,
                "politics_to_recruitment", run.army.men() > 0,
                "recruitment_to_supply", run.deliveredRations > 0 && run.consumedRations > 0,
                "supply_to_combat", false,
                "combat_to_households_territory", false,
                "world_state_to_restart_recovery", false));
        report.put("missing_evidence", List.of(
                "real Minecraft client FPS/frame-time capture",
                "NPC physical unload and rematerialization with UUID/inventory/family verification",
                "combat-to-household/territory causal receipts",
                "shutdown/restart/WAL replay and world-state hash equality",
                "process working-set/private bytes and full GC pause histogram"));
        writeReport(run, report);
        LOGGER.warn("LIVING_WORLD_STAGE_A status={} ticks={} TPS={} MSPT_p95={} MSPT_p99={} heapPeak={} productionReceipts={} tradeCarried={} dutyCredits={} taxCredits={} army={} supplyConsumed={} report={}",
                report.get("status"), run.tickCount, tps, p95, p99, run.peakHeapBytes,
                productionReceipts, carried, dutyCredits, taxCredits, run.army.id, run.consumedRations,
                reportPath(run.server));
        if (run.routeCreated && !hasActiveShipments(run.routeId)) {
            try {
                SettleMod.NETWORK.remove(run.routeId);
                SettleMod.saveDurably(run.server);
            } catch (RuntimeException ignored) { }
        }
    }

    private static boolean hasActiveShipments(int routeId) {
        return SettleMod.NETWORK.shipments.values().stream().anyMatch(s -> s.routeId == routeId
                && ("в пути".equals(s.status) || "ожидает места".equals(s.status) || "ожидает расчёта".equals(s.status)));
    }

    private static void writeReport(Run run, Map<String,Object> report) {
        try {
            Path path = reportPath(run.server);
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, dev.worldforge.mod.Json.write(report), StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            LOGGER.error("LIVING_WORLD_STAGE_A report write failed", error);
        }
    }

    private static Path reportPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("acceptance").resolve(REPORT_NAME);
    }

    private static int physicalWorkerCount(ServerLevel level) {
        int count = 0;
        for (Entity entity : level.getAllEntities())
            if (entity.getClass().getSimpleName().equals("WFWorker") && entity.isAlive()) count++;
        return count;
    }

    private static long heapUsed() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long gcMetric(boolean count) {
        long result = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = count ? bean.getCollectionCount() : bean.getCollectionTime();
            if (value > 0) result += value;
        }
        return result;
    }

    private static double processCpuPercent() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            return load < 0 ? 0 : load * 100.0;
        }
        return 0;
    }

    private static double percentileMs(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, Math.min(sorted.size() - 1, (int)Math.ceil(q * sorted.size()) - 1));
        return sorted.get(index) / 1_000_000.0;
    }
}
