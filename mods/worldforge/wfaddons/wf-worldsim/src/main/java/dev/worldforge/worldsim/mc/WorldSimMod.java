package dev.worldforge.worldsim.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.MapChunkGenerator;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.Diplomacy;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.worldsim.HistoricalChronicle;
import dev.worldforge.worldsim.MapGeography;
import dev.worldforge.worldsim.WorldSimComputeScheduler;
import dev.worldforge.court.CourtSim;
import dev.worldforge.court.mc.CourtMod;
import dev.worldforge.court.Marriages;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.worldsim.EmergentPopulationIndex;
import dev.worldforge.origins.Origins;
import dev.worldforge.origins.mc.OriginsMod;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.realms.RealmDefs;
import dev.worldforge.realms.mc.RealmsMod;
import dev.worldforge.war.WarEngine;
import dev.worldforge.army.ArmyEngine;
import dev.worldforge.army.UnitType;
import dev.worldforge.army.mc.ArmyMod;
import dev.worldforge.war.mc.WarMod;
import dev.worldforge.settle.mc.Colony;
import dev.worldforge.settle.Worker;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.mc.SettleMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * World-level autonomous decision coordinator. It observes the existing addon
 * state, asks the Rust engine for proposals, validates each proposal against
 * authoritative Java services, and records the resulting event journal.
 */
@Mod(WorldSimMod.MOD_ID)
public final class WorldSimMod {
    public static final String MOD_ID = "wf_worldsim";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final WorldSimProfiler PROFILER = new WorldSimProfiler();
    private static WorldSimComputeScheduler COMPUTE_SCHEDULER = new WorldSimComputeScheduler();
    private static long lifecycleEpoch;
    private static long pendingComputeRevision = Long.MIN_VALUE;
    private static final Gson GSON = new Gson();
    private static final int DECISION_INTERVAL_DAYS = 30;
    private static long lastDecisionDay = Long.MIN_VALUE;
    private static long lodTicks;
    private static final Map<String, Long> lastProjectDay = new HashMap<>();
    /** Avoid repeating unchanged world-census lines on every native compute frame. */
    private static final Map<String, String> LAST_GENESIS_DIAGNOSTIC_FINGERPRINT = new HashMap<>();
    private static final Deque<String> journal = new ArrayDeque<>();
    private static JsonObject emergenceState;
    private static JsonObject emergenceBindings = new JsonObject();
    private static boolean emergentRealmsRehydrated;
    private static final HistoricalChronicle HISTORICAL_CHRONICLE = new HistoricalChronicle();
    private static final int PEOPLE_MATERIALIZATION_BUDGET_PER_TICK = 256;
    private static WorldSimComputeScheduler.Result pendingApplyResult;
    private static JsonObject pendingCandidateEmergenceState;
    private static EmergentPeopleProgress emergentPeopleProgress;

    private static final class EmergentBandProgress {
        final List<dev.worldforge.people.Character> residents;
        final EmergentParents parents;
        int originCursor;
        boolean settlementReconciled;
        EmergentBandProgress(List<dev.worldforge.people.Character> residents, int year) {
            this.residents = residents;
            this.residents.sort(Comparator.comparingInt(person -> person.birthYear));
            this.parents = EmergentParents.from(this.residents, year);
        }
    }

    private static final class EmergentMapProgress {
        final EmergentPopulationIndex index;
        final Map<String, EmergentBandProgress> bands = new HashMap<>();
        EmergentMapProgress(CharacterRegistry registry) {
            this.index = new EmergentPopulationIndex(registry.all());
        }
    }

    /** A thin adapter over either an authored image map or observed vanilla geography.
     * Both sources feed the same emergence state, Character registry and settlement services. */
    private static final class EmergenceSource {
        final String id;
        final MapData map;
        final CharacterRegistry registry;
        final int width, height;
        final double blocksPerPixel;
        final long seed;
        final String dimension;

        private EmergenceSource(String id, MapData map, CharacterRegistry registry,
                                int width, int height, double blocksPerPixel, long seed, String dimension) {
            this.id = id; this.map = map; this.registry = registry; this.width = width; this.height = height;
            this.blocksPerPixel = blocksPerPixel; this.seed = seed; this.dimension = dimension;
        }
        static EmergenceSource authored(MapData map) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            String dimension = mapDimension(server, map);
            return new EmergenceSource(map.name, map, PeopleMod.registry(map), map.w, map.h, map.bpp,
                    PeopleMod.seed(map), dimension == null ? "" : dimension);
        }
        static EmergenceSource vanilla(String id, CharacterRegistry registry) {
            String dimension = VanillaWorldGeography.dimensionFromMapId(id);
            long seed = WorldForgeMod.worldId() == null ? id.hashCode()
                    : WorldForgeMod.worldId().toString().hashCode() ^ id.hashCode();
            return new EmergenceSource(id, null, registry,
                    VanillaWorldGeography.widthForMapId(id),
                    VanillaWorldGeography.heightForMapId(id), 1.0, seed, dimension);
        }
        int[] blockPosition(int x, int z) {
            if (map == null) return VanillaWorldGeography.blockPosition(id, x, z);
            return new int[] {
                    (int) Math.max(-29_999_000L, Math.min(29_999_000L, Math.round((x - map.w / 2.0) * Math.max(0.001, map.bpp)))),
                    (int) Math.max(-29_999_000L, Math.min(29_999_000L, Math.round((z - map.h / 2.0) * Math.max(0.001, map.bpp))))
            };
        }
        String settlementDimension(MinecraftServer server) {
            return dimension.isBlank() && map != null ? mapDimension(server, map) : dimension;
        }
    }

    private static List<EmergenceSource> emergenceSources() {
        List<EmergenceSource> sources = new ArrayList<>();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Map<String, String> registeredMapSources = new HashMap<>();
        // Enumerate the actual loaded ServerLevels, not only maps already touched by a
        // generated chunk or command. This bootstraps a map planet's strategic geography
        // from its complete .worldmap package even before a player visits that dimension.
        if (server != null) for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator generator)) continue;
            MapData map;
            try { map = generator.data(); }
            catch (RuntimeException unavailable) {
                LOGGER.error("wf_worldsim: map geography source unavailable for dimension {}: {}",
                        level.dimension().location(), unavailable.toString());
                continue;
            }
            if (map == null) continue;
            String dimension = level.dimension().location().toString();
            String previousDimension = registeredMapSources.putIfAbsent(map.name, dimension);
            if (previousDimension != null) {
                if (!previousDimension.equals(dimension))
                    LOGGER.error("wf_worldsim: map package '{}' is mounted in both {} and {}; map-scoped identity cannot represent two independent planets. Use a unique map ID per dimension until PlanetId scoping is enabled.",
                            map.name, previousDimension, dimension);
                continue;
            }
            CharacterRegistry registry = PeopleMod.registry(map);
            if (registry != null) sources.add(EmergenceSource.authored(map));
        }
        {
            if (server != null) for (String id : VanillaWorldGeography.simulationMapIds()) {
                String dimension = VanillaWorldGeography.dimensionFromMapId(id);
                for (ServerLevel level : server.getAllLevels()) {
                    if (!dimension.equals(level.dimension().location().toString())) continue;
                    CharacterRegistry dimensionRegistry = PeopleMod.registryFor(level);
                    if (dimensionRegistry != null)
                        sources.add(EmergenceSource.vanilla(id, dimensionRegistry));
                    break;
                }
            }
        }
        sources.sort(Comparator.comparing(source -> source.id));
        return sources;
    }

    private static final class EmergentPeopleProgress {
        final JsonObject state;
        final int year;
        final Map<String, EmergentMapProgress> maps = new HashMap<>();
        EmergentPeopleProgress(JsonObject state, int year) { this.state = state; this.year = year; }
    }


    /**
     * Mirrors confirmed domain facts into MAQAN's presentation timeline.
     * The EventLog and HistoricalChronicle remain canonical; the Platform journal
     * is only a bounded UI projection and is optional on dedicated servers.
     */
    private static void publishPlatformTimelineEvent(dev.worldforge.mod.api.EventLog.Event event) {
        if (event == null || event.text == null || event.text.isBlank()) return;
        try {
            Class<?> runtime = Class.forName("dev.maqan.platform.client.WorkspaceRuntime");
            Class<?> severity = Class.forName("dev.maqan.platform.client.WorkspaceRuntime$Severity");
            Object info = java.lang.Enum.valueOf((Class) severity, "INFO");
            String entityId = event.realms == null || event.realms.isEmpty() ? "" : event.realms.get(0);
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            String worldId = server == null ? "" : server.overworld().dimension().location().toString();
            runtime.getMethod("publish", severity, String.class, String.class,
                    String.class, String.class, String.class)
                    .invoke(null, info, "worldforge", event.category == null ? "world" : event.category.id(),
                            event.text, entityId, worldId);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // MAQAN Platform is optional; the authoritative event and book are unaffected.
        }
    }

    public WorldSimMod() {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new VanillaWorldGeography());
        WorldForgeApi.events().addListener(item -> {
            recordChronicleEvent(item);
            publishPlatformTimelineEvent(item);
        });
    }

    private static void recordChronicleEvent(dev.worldforge.mod.api.EventLog.Event item) {
        if (item == null) return;
        // EventLog.time is the authoritative occurrence time. Listener delivery may
        // happen later (or after a batched simulation step), so never date history
        // from the current wall/game clock when the event carries its own timestamp.
        long eventDay = Math.max(0L, item.time / 1440L);
        int eventYear = WorldForgeApi.clock().startYear
                + (int) (eventDay / HistoricalChronicle.DAYS_PER_YEAR);
        HISTORICAL_CHRONICLE.record(item, eventDay, eventYear);
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        lifecycleEpoch++;
        LAST_GENESIS_DIAGNOSTIC_FINGERPRINT.clear();
        COMPUTE_SCHEDULER.close();
        COMPUTE_SCHEDULER = new WorldSimComputeScheduler();
        pendingComputeRevision = Long.MIN_VALUE;
        pendingApplyResult = null;
        pendingCandidateEmergenceState = null;
        emergentPeopleProgress = null;
        lastDecisionDay = Long.MIN_VALUE;
        lodTicks = 0;
        emergenceState = null;
        emergenceBindings = new JsonObject();
        emergentRealmsRehydrated = false;
        HISTORICAL_CHRONICLE.clear();
        HISTORICAL_CHRONICLE.setStartingYear(WorldForgeApi.clock().startYear);
        publishObservers(event.getServer());
        loadState(event.getServer());
        VanillaWorldGeography.load(event.getServer());
        // Backfill the book from the persisted canonical journal. This also migrates
        // worlds saved before the chronicle projection existed; seq-based identities
        // make replay safe when the saved book already contains some of these events.
        for (var item : WorldForgeApi.events().all()) recordChronicleEvent(item);
        LOGGER.info("wf_worldsim: autonomous coordinator started; native Rust core={}, initial LOD observers={}",
                NativeWorldSimulation.load() ? "available" : "unavailable",
                serverObserverCount(event.getServer()));
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        lifecycleEpoch++;
        COMPUTE_SCHEDULER.close();
        pendingComputeRevision = Long.MIN_VALUE;
        pendingApplyResult = null;
        pendingCandidateEmergenceState = null;
        emergentPeopleProgress = null;
        PROFILER.setGauge("worldsim.compute.busy", 0);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            VanillaWorldGeography.persist(server);
            saveState(server);
        }
        lastDecisionDay = Long.MIN_VALUE;
        lodTicks = 0;
        emergentRealmsRehydrated = false;
        SimulationLod.clear();
        lastProjectDay.clear();
        journal.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try (WorldSimProfiler.Scope ignored = PROFILER.begin("worldsim.server_tick_handler")) {
            processServerTick(event);
        }
    }

    private void processServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (++lodTicks % 20 == 0) publishObservers(server);
        if (!emergentRealmsRehydrated && emergenceState != null) {
            syncEmergentPolities(server, emergenceState, emergenceYear(emergenceState, WorldForgeApi.clock().year()));
            emergentRealmsRehydrated = true;
        }

        String worldId = worldIdentity(server);
        if (pendingApplyResult == null) {
            WorldSimComputeScheduler.Result completed = null;
            try {
                completed = COMPUTE_SCHEDULER.poll();
            } catch (RuntimeException error) {
                pendingComputeRevision = Long.MIN_VALUE;
                PROFILER.setGauge("worldsim.compute.busy", 0);
                PROFILER.setGauge("worldsim.compute.failures", 1);
                LOGGER.error("wf_worldsim: native compute failed; canonical state was not advanced", error);
            }
            if (completed != null) {
                PROFILER.record("worldsim.compute.queue_wait", completed.queueWaitNanos());
                PROFILER.record("worldsim.compute.total", completed.computeNanos());
                WorldSimComputeScheduler.Ticket ticket = completed.ticket();
                if (!ticket.matches(worldId, "worldsim.compute.cycle", pendingComputeRevision, lifecycleEpoch,
                        NativeWorldSimulation.SNAPSHOT_SCHEMA_VERSION, NativeWorldSimulation.NATIVE_ABI_VERSION)) {
                    pendingComputeRevision = Long.MIN_VALUE;
                    PROFILER.setGauge("worldsim.compute.busy", 0);
                    PROFILER.setGauge("worldsim.compute.stale_results", 1);
                    LOGGER.warn("wf_worldsim: discarded stale compute result world={} revision={} epoch={}",
                            ticket.worldId(), ticket.revision(), ticket.lifecycleEpoch());
                } else {
                    pendingApplyResult = completed;
                    pendingCandidateEmergenceState = null;
                    emergentPeopleProgress = null;
                }
            }
        }
        if (pendingApplyResult != null) {
            try {
                if (!applyComputeResult(server, pendingApplyResult)) {
                    PROFILER.setGauge("worldsim.compute.busy", 1);
                    return; // Continue the same authoritative frame on the next server tick.
                }
                pendingApplyResult = null;
                pendingCandidateEmergenceState = null;
                emergentPeopleProgress = null;
                pendingComputeRevision = Long.MIN_VALUE;
            } catch (Exception error) {
                // Keep the frame and its staged native state. A transient save/materialization
                // failure must not discard the only result or start a competing compute cycle.
                PROFILER.setGauge("worldsim.compute.busy", 1);
                LOGGER.error("wf_worldsim: authoritative frame remains pending at day {}; retrying next server tick",
                        pendingComputeRevision, error);
                return;
            }
        }

        long day = WorldForgeApi.clock().dayNumber();
        // The first world frame is a creation-time operation, not a scenario and not
        // something that waits thirty in-game days. Do not submit an empty snapshot:
        // ordinary vanilla dimensions become eligible as soon as real chunks have
        // been observed by VanillaWorldGeography.
        if (lastDecisionDay != Long.MIN_VALUE
                && day - lastDecisionDay < DECISION_INTERVAL_DAYS) return;
        if (!NativeWorldSimulation.load()) return;
        if (COMPUTE_SCHEDULER.busy()) {
            PROFILER.setGauge("worldsim.compute.busy", 1);
            return;
        }

        try {
            long phaseStarted = System.nanoTime();
            JsonObject snapshot = snapshot(server, day);
            if (!hasObservedGeography(snapshot)) {
                PROFILER.setGauge("worldsim.genesis.waiting_for_geography", 1);
                return;
            }
            PROFILER.setGauge("worldsim.genesis.waiting_for_geography", 0);
            byte[] input = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            JsonObject emergenceRequest = new JsonObject();
            emergenceRequest.addProperty("schema_version", NativeWorldSimulation.SNAPSHOT_SCHEMA_VERSION);
            emergenceRequest.add("snapshot", snapshot);
            if (emergenceState != null) emergenceRequest.add("state", emergenceState.deepCopy());
            byte[] emergenceInput = GSON.toJson(emergenceRequest).getBytes(StandardCharsets.UTF_8);
            PROFILER.record("worldsim.snapshot_and_json", System.nanoTime() - phaseStarted);

            WorldSimComputeScheduler.Ticket ticket =
                    new WorldSimComputeScheduler.Ticket(worldId, "worldsim.compute.cycle", day, lifecycleEpoch,
                            NativeWorldSimulation.SNAPSHOT_SCHEMA_VERSION, NativeWorldSimulation.NATIVE_ABI_VERSION);
            boolean submitted = COMPUTE_SCHEDULER.submit(ticket, input, emergenceInput, (snapshotBytes, emergenceBytes) -> {
                long nativeStarted = System.nanoTime();
                byte[] emergenceFrame = NativeWorldSimulation.advanceEmergence(emergenceBytes);
                PROFILER.record("worldsim.native_emergence", System.nanoTime() - nativeStarted);
                nativeStarted = System.nanoTime();
                byte[] decisionFrame = NativeWorldSimulation.decide(snapshotBytes);
                PROFILER.record("worldsim.native_decide", System.nanoTime() - nativeStarted);
                return new WorldSimComputeScheduler.Output(emergenceFrame, decisionFrame);
            });
            if (submitted) {
                pendingComputeRevision = day;
                PROFILER.setGauge("worldsim.compute.busy", 1);
                PROFILER.setGauge("worldsim.compute.pending_day", day);
            } else {
                PROFILER.setGauge("worldsim.compute.backpressure", 1);
            }
        } catch (RuntimeException error) {
            LOGGER.error("wf_worldsim: unable to submit native compute at day {}", day, error);
        }
    }

    /** Applies a completed immutable native frame on the authoritative Minecraft server thread only. */
    private boolean applyComputeResult(MinecraftServer server, WorldSimComputeScheduler.Result result) {
        long day = result.ticket().revision();
        JsonObject snapshot = GSON.fromJson(new String(result.snapshot(), StandardCharsets.UTF_8), JsonObject.class);
        JsonObject emergenceFrame = GSON.fromJson(new String(result.emergenceFrame(), StandardCharsets.UTF_8), JsonObject.class);
        JsonObject frame = GSON.fromJson(new String(result.decisionFrame(), StandardCharsets.UTF_8), JsonObject.class);
        requireNativeSuccess(emergenceFrame, "emergence");
        requireNativeSuccess(frame, "decision");
        if (snapshot == null || emergenceFrame == null || !emergenceFrame.has("state")
                || !emergenceFrame.get("state").isJsonObject()) {
            throw new IllegalStateException("Native emergence engine returned an invalid state frame");
        }
        if (frame == null || !frame.has("proposals") || !frame.get("proposals").isJsonArray()) {
            throw new IllegalStateException("Native decision engine returned an invalid proposal frame");
        }

        // Keep the last committed native state as the retry base until every apply
        // stage below completes. Retain the same candidate object across tick-sized batches.
        if (pendingCandidateEmergenceState == null)
            pendingCandidateEmergenceState = emergenceFrame.getAsJsonObject("state").deepCopy();
        JsonObject candidateEmergenceState = pendingCandidateEmergenceState;
        long phaseStarted = System.nanoTime();
        int materializationYear = emergenceYear(candidateEmergenceState, WorldForgeApi.clock().year());
        boolean peopleComplete = syncEmergentPeople(server, candidateEmergenceState, materializationYear);
        PROFILER.record("worldsim.sync_emergent_people", System.nanoTime() - phaseStarted);
        if (!peopleComplete) return false;
        phaseStarted = System.nanoTime();
        syncEmergentPolities(server, candidateEmergenceState, materializationYear);
        PROFILER.record("worldsim.sync_emergent_polities", System.nanoTime() - phaseStarted);
        logWorldGenesisDiagnostics(server, candidateEmergenceState);

        if (emergenceFrame.has("events") && emergenceFrame.get("events").isJsonArray()) {
            for (var eventElement : emergenceFrame.getAsJsonArray("events")) {
                if (!eventElement.isJsonObject()) continue;
                JsonObject emergenceEvent = eventElement.getAsJsonObject();
                String kind = emergenceEvent.has("kind") ? emergenceEvent.get("kind").getAsString() : "world";
                String subject = emergenceEvent.has("text") ? emergenceEvent.get("text").getAsString() : "Emergent world event";
                String eventCategory = switch (kind) {
                    case "settlement_founded" -> "settlement";
                    case "founding_ruler", "dynastic_succession", "population_origin" -> "people";
                    case "state_founded", "state_dissolved", "settlement_absorbed" -> "diplomacy";
                    default -> "system";
                };
                long eventDay = Math.max(0L, emergenceEvent.has("year")
                        ? emergenceEvent.get("year").getAsLong() * 365L : day);
                List<String> affected = kind.equals("state_founded") || kind.equals("state_dissolved")
                        ? List.of("emergent-" + (emergenceEvent.has("subject_id")
                                ? emergenceEvent.get("subject_id").getAsString() : "unknown")) : List.of();
                String subjectId = emergenceEvent.has("subject_id")
                        ? emergenceEvent.get("subject_id").getAsString() : "";
                List<String> eventScopes = emergencePlanetScope(candidateEmergenceState, subjectId);
                WorldForgeApi.postEventScoped(eventCategory, "[" + kind + "] " + subject,
                        affected, eventScopes, eventDay * 1440L);
            }
        }

        int accepted = 0, rejected = 0;
        phaseStarted = System.nanoTime();
        for (var element : frame.getAsJsonArray("proposals")) {
            if (!element.isJsonObject()) continue;
            JsonObject proposal = element.getAsJsonObject();
            JsonObject kind = proposal.has("kind") && proposal.get("kind").isJsonObject()
                    ? proposal.getAsJsonObject("kind") : null;
            if (kind == null) continue;
            String proposalId = proposal.get("id").getAsString();
            try {
                String requestId = "worldsim:" + proposalId;
                String payload = GSON.toJson(kind);
                long worldMinute = Math.max(0L, day * 1440L);
                var actionService = WorldForgeApi.actionExecution();
                var priorTask = actionService.task(requestId);
                if (priorTask != null && priorTask.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED
                        && priorTask.message().startsWith("Interrupted by server restart")) {
                    var reconciliation = reconcileInterrupted(server, requestId, kind);
                    priorTask = actionService.reconcileInterrupted(requestId, reconciliation,
                            reconciliation == dev.worldforge.mod.api.ActionExecutionService.Reconciliation.APPLIED
                                    ? "Authoritative domain receipt confirms the effect"
                                    : "Authoritative domain state confirms safe retry",
                            worldMinute, WorldForgeMod.actionExecutionFile(server));
                }
                var task = actionService.execute(
                        new dev.worldforge.mod.api.ActionExecutionService.Request(
                                requestId, "worldsim.apply", "worldsim", "world",
                                List.of(payload), worldMinute),
                        request -> null,
                        (request, attempt) -> {
                            JsonObject decoded = GSON.fromJson(request.args().get(0), JsonObject.class);
                            boolean applied = decoded != null && apply(server, request.requestId(), decoded, day);
                            return applied
                                    ? dev.worldforge.mod.api.ActionResult.ok("Autonomous proposal applied")
                                    : dev.worldforge.mod.api.ActionResult.fail("Proposal rejected by authoritative domain API");
                        },
                        null, worldMinute, WorldForgeMod.actionExecutionFile(server));
                boolean applied = task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED;
                if (applied) accepted++; else rejected++;
                recordEvent(day, proposalId, kind, applied);
            } catch (RuntimeException error) {
                rejected++;
                LOGGER.warn("wf_worldsim: proposal {} rejected: {}", proposalId, error.toString());
                recordEvent(day, proposalId, kind, false);
            }
        }
        PROFILER.record("worldsim.apply_proposals", System.nanoTime() - phaseStarted);
        PROFILER.setGauge("worldsim.proposals.accepted", accepted);
        PROFILER.setGauge("worldsim.proposals.rejected", rejected);
        // Commit the native emergence cursor only after materialization, event publication,
        // and proposal processing have completed. A failed apply retries from prior state.
        emergenceState = candidateEmergenceState.deepCopy();
        PROFILER.setGauge("worldsim.compute.busy", 0);
        lastDecisionDay = day;
        phaseStarted = System.nanoTime();
        saveState(server);
        PROFILER.record("worldsim.save_state", System.nanoTime() - phaseStarted);
        LOGGER.info("wf_worldsim: day={} realms={} settlements={} proposals={} applied={} rejected={} compute=async",
                day, RealmsMod.realms().size(), SettleMod.COLONIES.size(),
                frame.getAsJsonArray("proposals").size(), accepted, rejected);
        return true;
    }

    /** Per-dimension runtime evidence; diagnostics are projections of canonical mod state. */
    private static void logWorldGenesisDiagnostics(MinecraftServer server, JsonObject state) {
        if (server == null || state == null || !state.has("bands") || !state.get("bands").isJsonArray()
                || !state.has("polities") || !state.get("polities").isJsonArray()) return;
        for (EmergenceSource source : emergenceSources()) {
            long bands = state.getAsJsonArray("bands").asList().stream()
                    .filter(JsonObject.class::isInstance).map(JsonObject.class::cast)
                    .filter(band -> source.id.equals(band.has("map_id") ? band.get("map_id").getAsString() : ""))
                    .count();
            long settled = state.getAsJsonArray("bands").asList().stream()
                    .filter(JsonObject.class::isInstance).map(JsonObject.class::cast)
                    .filter(band -> source.id.equals(band.has("map_id") ? band.get("map_id").getAsString() : "")
                            && band.has("settled") && band.get("settled").getAsBoolean()).count();
            List<TitleData.Realm> realms = RealmsMod.emergentRealms().stream()
                    .filter(realm -> source.id.equals(String.valueOf(realm.props.getOrDefault("emergence.map_id", ""))))
                    .toList();
            Set<String> raceIds = new TreeSet<>();
            if (source.registry != null) for (dev.worldforge.people.Character person : source.registry.all()) {
                Object race = person.props.get("origin.race");
                if (person.alive && race != null && !String.valueOf(race).isBlank())
                    raceIds.add(String.valueOf(race));
            }
            List<Colony> sourceColonies = SettleMod.COLONIES.values().stream()
                    .filter(colony -> colony != null && colony.s != null && source.dimension.equals(colony.dim))
                    .sorted(Comparator.comparing(colony -> colony.s.id)).toList();
            List<Colony> activeColonies = sourceColonies.stream()
                    .filter(colony -> colony.s.population() > 0 || !colony.residentCharacterIds.isEmpty()
                            || !colony.s.buildings.isEmpty() || !colony.s.workers.isEmpty()).toList();
            long settlements = activeColonies.size();
            Map<String, Long> settlementTypeCounts = activeColonies.stream()
                    .map(colony -> colony.s.typeId == null || colony.s.typeId.isBlank() ? "unclassified" : colony.s.typeId)
                    .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                            java.util.stream.Collectors.counting()));
            Map<String, Long> blueprintSiteTypeCounts = sourceColonies.stream()
                    .filter(colony -> colony.blueprintSettlementId != null && !colony.blueprintSettlementId.isBlank())
                    .map(colony -> colony.settlementType == null || colony.settlementType.isBlank()
                            ? "unspecified" : colony.settlementType)
                    .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                            java.util.stream.Collectors.counting()));
            long emergentSettlements = activeColonies.stream()
                    .filter(colony -> colony.genesisOperationId != null
                            && colony.genesisOperationId.startsWith("worldsim:settlement:")
                            && (colony.blueprintSettlementId == null || colony.blueprintSettlementId.isBlank()))
                    .count();
            long mapAnchors = sourceColonies.stream()
                    .filter(colony -> colony.blueprintSettlementId != null && !colony.blueprintSettlementId.isBlank())
                    .count();
            long emptyBlueprintAnchors = sourceColonies.stream()
                    .filter(colony -> colony.blueprintSettlementId != null && !colony.blueprintSettlementId.isBlank())
                    .filter(colony -> colony.s.population() <= 0 && colony.residentCharacterIds.isEmpty()
                            && colony.s.buildings.isEmpty() && colony.s.workers.isEmpty()).count();
            Map<String, Long> duplicateCenters = activeColonies.stream()
                    .collect(java.util.stream.Collectors.groupingBy(colony -> colony.cx + ":" + colony.cz,
                            java.util.stream.Collectors.counting()));
            long duplicateSettlementCenters = duplicateCenters.values().stream()
                    .mapToLong(count -> Math.max(0, count - 1)).sum();
            final long overlapRadius = 128L;
            final long overlapRadiusSq = overlapRadius * overlapRadius;
            Map<String, List<Colony>> overlapBuckets = new HashMap<>();
            long nearbySettlementConflicts = 0;
            for (Colony colony : activeColonies) {
                int bucketX = Math.floorDiv(colony.cx, (int) overlapRadius);
                int bucketZ = Math.floorDiv(colony.cz, (int) overlapRadius);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    for (Colony other : overlapBuckets.getOrDefault((bucketX + dx) + ":" + (bucketZ + dz), List.of())) {
                        long deltaX = (long) colony.cx - other.cx, deltaZ = (long) colony.cz - other.cz;
                        if (deltaX * deltaX + deltaZ * deltaZ < overlapRadiusSq) nearbySettlementConflicts++;
                    }
                }
                overlapBuckets.computeIfAbsent(bucketX + ":" + bucketZ, ignored -> new ArrayList<>()).add(colony);
            }
            long livingCharacters = source.registry == null ? 0 : source.registry.all().stream()
                    .filter(person -> person.alive).count();
            long namedCharacters = source.registry == null ? 0 : source.registry.all().stream()
                    .filter(person -> person.alive && person.name != null && !person.name.isBlank()).count();
            long namedPolities = realms.stream()
                    .filter(realm -> realm.name != null && !realm.name.isBlank()).count();
            long namedTitles = RealmsMod.POLITICS.titles().stream()
                    .filter(title -> realms.stream().anyMatch(realm -> realm.id.equals(title.realmId)))
                    .filter(title -> title.name != null && !title.name.isBlank()).count();
            long namedSettlements = activeColonies.stream()
                    .filter(colony -> colony.s.name != null && !colony.s.name.isBlank()).count();
            long courtOffices = source.registry == null ? 0 : source.registry.all().stream()
                    .filter(person -> person.alive)
                    .flatMap(person -> person.posts.stream())
                    .filter(post -> post.place != null && post.place.startsWith("realm:"))
                    .count();
            long rulers = realms.stream().filter(realm -> {
                Object id = realm.props.get("emergence.ruler_global_id");
                return id != null && PeopleMod.authority().getGlobal(String.valueOf(id)) != null;
            }).count();
            long governments = realms.stream().filter(realm -> {
                var political = RealmsMod.REALMS.state(realm.id);
                return political.gov != null && !political.gov.isBlank();
            }).count();
            Map<String, Long> governmentTypes = realms.stream()
                    .map(realm -> RealmsMod.REALMS.state(realm.id).gov)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                            java.util.stream.Collectors.counting()));
            List<dev.worldforge.realms.Statecraft.VassalContract> vassalContracts = realms.stream()
                    .map(realm -> RealmsMod.POLITICS.vassalage(realm.id))
                    .filter(Objects::nonNull).toList();
            Map<String, Long> subjectTypes = vassalContracts.stream()
                    .collect(java.util.stream.Collectors.groupingBy(contract -> contract.subjectType.name(),
                            TreeMap::new, java.util.stream.Collectors.counting()));
            Map<String, Long> titleRanks = RealmsMod.POLITICS.titles().stream()
                    .filter(title -> realms.stream().anyMatch(realm -> realm.id.equals(title.realmId)))
                    .collect(java.util.stream.Collectors.groupingBy(title -> title.rank.name(), TreeMap::new,
                            java.util.stream.Collectors.counting()));
            long titles = RealmsMod.POLITICS.titles().stream()
                    .filter(title -> realms.stream().anyMatch(realm -> realm.id.equals(title.realmId))).count();
            long dynasties = RealmsMod.POLITICS.dynasties().stream()
                    .filter(dynasty -> dynasty != null && dynasty.name != null && !dynasty.name.isBlank()
                            && realms.stream().anyMatch(realm -> Objects.equals(
                                    String.valueOf(realm.props.getOrDefault("emergence.dynasty_id", "")), dynasty.houseId)))
                    .count();
            long geographyCells = source.map == null
                    ? VanillaWorldGeography.sampledCellCount() : (long) source.map.w * source.map.h;
            String fingerprint = geographyCells + "|" + bands + "|" + settled + "|" + sourceColonies.size()
                    + "|" + settlements + "|" + emergentSettlements + "|" + mapAnchors + "|"
                    + emptyBlueprintAnchors + "|" + duplicateSettlementCenters + "|" + nearbySettlementConflicts
                    + "|" + livingCharacters + "|" + namedCharacters + "|" + namedSettlements + "|"
                    + courtOffices + "|" + realms.size() + "|" + namedPolities + "|" + rulers + "|"
                    + dynasties + "|" + titles + "|" + namedTitles + "|" + governments + "|"
                    + raceIds + "|" + settlementTypeCounts + "|" + blueprintSiteTypeCounts + "|"
                    + governmentTypes + "|" + vassalContracts.size() + "|" + subjectTypes + "|" + titleRanks;
            String previousFingerprint = LAST_GENESIS_DIAGNOSTIC_FINGERPRINT.put(source.id, fingerprint);
            if (!fingerprint.equals(previousFingerprint)) {
                LOGGER.info("MAQAN_WORLD_GENESIS source={} dimension={} geography_cells={} communities={} settled={} settlements={} colony_records={} emergent_settlements={} blueprint_sites={} empty_blueprint_sites={} active_settlements={} living_characters={} named_characters={} named_settlements={} court_offices={} polities={} named_polities={} canonical_rulers={} dynasties={} titles={} named_titles={} governments={} race_kinds={} settlement_types={} blueprint_site_types={} government_types={} duplicate_centers={} nearby_center_conflicts={} vassal_contracts={} subject_types={} title_ranks={}",
                        source.id, source.dimension, geographyCells, bands, settled, settlements, sourceColonies.size(),
                        emergentSettlements, mapAnchors, emptyBlueprintAnchors, activeColonies.size(),
                        livingCharacters, namedCharacters, namedSettlements, courtOffices, realms.size(),
                        namedPolities, rulers, dynasties, titles, namedTitles, governments, raceIds.size(),
                        GSON.toJson(settlementTypeCounts), GSON.toJson(blueprintSiteTypeCounts),
                        GSON.toJson(governmentTypes), duplicateSettlementCenters, nearbySettlementConflicts,
                        vassalContracts.size(), GSON.toJson(subjectTypes), GSON.toJson(titleRanks));
            }
            if (source.map == null) {
                int cells = 0, landCells = 0, viable36 = 0, viable48 = 0, maxSuitability = 0;
                Map<String, Integer> biomeCounts = new TreeMap<>();
                for (var geographyElement : VanillaWorldGeography.snapshot()) {
                    if (!geographyElement.isJsonObject()) continue;
                    JsonObject geographyMap = geographyElement.getAsJsonObject();
                    if (!source.id.equals(geographyMap.has("map_id") ? geographyMap.get("map_id").getAsString() : "")) continue;
                    if (!geographyMap.has("cells") || !geographyMap.get("cells").isJsonArray()) continue;
                    for (var cellElement : geographyMap.getAsJsonArray("cells")) {
                        if (!cellElement.isJsonObject()) continue;
                        JsonObject cell = cellElement.getAsJsonObject();
                        cells++;
                        boolean land = cell.has("land") && cell.get("land").getAsBoolean();
                        int suitability = cell.has("suitability") ? cell.get("suitability").getAsInt() : 0;
                        if (land) landCells++;
                        if (land && suitability >= 36) viable36++;
                        if (land && suitability >= 48) viable48++;
                        maxSuitability = Math.max(maxSuitability, suitability);
                        String biome = cell.has("biome") ? cell.get("biome").getAsString() : "unknown";
                        biomeCounts.merge(biome, 1, Integer::sum);
                    }
                }
                String geographyFingerprint = cells + "|" + landCells + "|" + viable36 + "|" + viable48
                        + "|" + maxSuitability + "|" + biomeCounts;
                String previousGeographyFingerprint = LAST_GENESIS_DIAGNOSTIC_FINGERPRINT.put(
                        "vanilla-geography:" + source.id, geographyFingerprint);
                if (!geographyFingerprint.equals(previousGeographyFingerprint))
                    LOGGER.info("MAQAN_VANILLA_GEOGRAPHY source={} cells={} land={} viable36={} viable48={} max_suitability={} biomes={}",
                            source.id, cells, landCells, viable36, viable48, maxSuitability, GSON.toJson(biomeCounts));
            }
        }
    }

    private static void requireNativeSuccess(JsonObject frame, String stage) {
        if (frame == null) throw new IllegalStateException("Native " + stage + " frame is null");
        if (frame.has("status") && !frame.get("status").isJsonNull()) {
            String status = frame.get("status").getAsString();
            if (!"ok".equalsIgnoreCase(status)) {
                String detail = frame.has("error") ? frame.get("error").getAsString() : "unspecified native error";
                throw new IllegalStateException("Native " + stage + " rejected request: " + status + ": " + detail);
            }
        }
    }

    private static int emergenceYear(JsonObject state, int fallback) {
        if (state == null || !state.has("last_year") || state.get("last_year").isJsonNull()) return fallback;
        long year = state.get("last_year").getAsLong();
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, year));
    }

    private static String worldIdentity(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("wfworldsim")
                .then(Commands.literal("status").executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "WorldSim: native=" + NativeWorldSimulation.load()
                                    + ", dimensions=" + java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false).count()
                                    + ", realms=" + RealmsMod.realms().size()
                                    + ", settlements=" + SettleMod.COLONIES.size()
                                    + ", proposal_records=" + journal.size()
                                    + ", world_events=" + WorldForgeApi.events().size()
                                    + ", chronicle_records=" + HISTORICAL_CHRONICLE.size()
                                    + ", compute_busy=" + COMPUTE_SCHEDULER.busy()
                                    + ", compute_pending_day=" + (pendingComputeRevision == Long.MIN_VALUE ? "-" : pendingComputeRevision)
                                    + ", compute_epoch=" + lifecycleEpoch
                                    + ", day=" + WorldForgeApi.clock().dayNumber()), false);
                    return 1;
                }))
                .then(Commands.literal("geography").executes(context -> {
                    int regions = VanillaWorldGeography.capturedRegionCount();
                    int samples = VanillaWorldGeography.sampledCellCount();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Vanilla geography: regions=" + regions + ", samples=" + samples
                                    + ", pending_chunks=" + VanillaWorldGeography.pendingChunkCount()
                                    + ", cache=worldforge/wf_world_geography.json"), false);
                    return regions > 0 && samples > 0 ? 1 : 0;
                }))
                .then(Commands.literal("history")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20)).executes(context -> {
                                int count = Math.min(IntegerArgumentType.getInteger(context, "count"), WorldForgeApi.events().size());
                            var recent = WorldForgeApi.events().recent(count);
                            for (var item : recent) {
                                String line = "День " + Math.max(0L, item.time / 1440L) + ": "
                                        + item.category.label + " — " + item.text;
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            return recent.size();
                        })))
                .then(Commands.literal("chronicle")
                        .executes(context -> sendChronicle(context.getSource(), HISTORICAL_CHRONICLE.book(12)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50)).executes(context -> {
                            int count = IntegerArgumentType.getInteger(context, "count");
                            return sendChronicle(context.getSource(), HISTORICAL_CHRONICLE.book(count));
                        }))
                        .then(chronicleScopeCommand("planet"))
                        .then(chronicleScopeCommand("realm"))
                        .then(chronicleScopeCommand("dynasty"))
                        .then(chronicleScopeCommand("settlement"))
                        .then(chronicleScopeCommand("character"))));
    }

    private static int sendChronicle(CommandSourceStack source, List<String> pages) {
        for (String page : pages) source.sendSuccess(() -> Component.literal(page), false);
        return pages.size();
    }

    private static List<String> scopedChronicle(String type, String id, int count) {
        return switch (type) {
            case "planet" -> HISTORICAL_CHRONICLE.bookForPlanet(id, count);
            case "realm" -> HISTORICAL_CHRONICLE.bookForRealm(id, count);
            case "dynasty" -> HISTORICAL_CHRONICLE.bookForDynasty(id, count);
            case "settlement" -> HISTORICAL_CHRONICLE.bookForSettlement(id, count);
            case "character" -> HISTORICAL_CHRONICLE.bookForCharacter(id, count);
            default -> List.of("Неизвестный тип летописи: " + type);
        };
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            chronicleScopeCommand(String type) {
        return Commands.literal(type)
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(context -> sendChronicle(context.getSource(),
                                scopedChronicle(type, StringArgumentType.getString(context, "id"), 12)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(context -> sendChronicle(context.getSource(),
                                        scopedChronicle(type, StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "count"))))));
    }

    /** Returns the already registered physical dimension whose generator uses this exact map package. */
    private static String mapDimension(MinecraftServer server, MapData map) {
        if (server == null || map == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof MapChunkGenerator generator
                    && generator.data() != null && map.name.equals(generator.data().name))
                return level.dimension().location().toString();
        }
        return null;
    }

    private static int serverObserverCount(MinecraftServer server) {
        int count = 0;
        for (var level : server.getAllLevels())
            for (var player : level.players()) if (!player.isSpectator()) count++;
        return count;
    }

    /** One shared observer snapshot drives NPC, worker, settlement and future ambient LOD. */
    private static void publishObservers(MinecraftServer server) {
        List<SimulationLod.Observer> observers = new ArrayList<>();
        for (var level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            for (var player : level.players()) {
                if (player.isSpectator()) continue;
                observers.add(new SimulationLod.Observer(dimension, player.getX(), player.getZ()));
            }
        }
        SimulationLod.publish(observers);
    }

    /** A newly created world is bootstrapped only from terrain actually supplied by its generators. */
    private static boolean hasObservedGeography(JsonObject snapshot) {
        if (snapshot == null || !snapshot.has("geography") || !snapshot.get("geography").isJsonArray()) return false;
        for (var element : snapshot.getAsJsonArray("geography")) {
            if (!element.isJsonObject()) continue;
            JsonObject map = element.getAsJsonObject();
            if (map.has("cells") && map.get("cells").isJsonArray() && !map.getAsJsonArray("cells").isEmpty())
                return true;
        }
        return false;
    }

    private static JsonObject snapshot(MinecraftServer server, long day) {
        JsonObject root = new JsonObject();
        var canonicalWorldId = WorldForgeMod.worldId();
        if (canonicalWorldId == null)
            throw new IllegalStateException("Canonical WorldId is unavailable; refusing autonomous simulation without stable world identity");
        root.addProperty("schema_version", NativeWorldSimulation.SNAPSHOT_SCHEMA_VERSION);
        root.addProperty("world_id", canonicalWorldId.toString());
        root.addProperty("seed", server.getWorldData().worldGenOptions().seed() & Long.MAX_VALUE);
        root.addProperty("day", day);
        JsonArray realms = new JsonArray(), borders = new JsonArray(), settlements = new JsonArray(), characters = new JsonArray(), workforce = new JsonArray();
        JsonArray politicalTitles = new JsonArray();
        JsonArray governmentDefinitions = new JsonArray(), realmGovernments = new JsonArray();
        JsonArray capabilities = new JsonArray(), catalogEntries = new JsonArray();
        JsonArray diplomaticRelations = new JsonArray(), vassalContracts = new JsonArray(), wars = new JsonArray();
        JsonArray armies = new JsonArray(), socialRelations = new JsonArray(), namedEntities = new JsonArray();
        JsonArray geography = new JsonArray();
        List<EmergenceSource> sources = emergenceSources();
        // Geography is sampled independently of political overlays. A map with no titles/realms
        // is still a valid source of terrain, biome and river evidence for a future map-first world.
        for (EmergenceSource source : sources) {
            if (source.map != null) geography.add(GSON.toJsonTree(MapGeography.describe(source.map)));
        }
        // Every loaded vanilla-like dimension contributes its own observed geography.
        for (var vanillaRegion : VanillaWorldGeography.snapshot()) geography.add(vanillaRegion);
        // WorldSim must never consume sovereignty or political borders from an image map.
        // Both image and vanilla sources contribute only canonical emergent realms created
        // by the shared geography pipeline; this also prevents map realms being counted twice.
        Set<String> threatenedRealms = new HashSet<>();
        Map<String, Double> actualArmyPower = new HashMap<>();
        // wf-army owns its rosters and unit definitions. WorldSim reads every loaded map
        // through the module API; it never estimates an army from civilian population.
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null || map.titles == null) continue;
            ArmyEngine engine = ArmyMod.armies(map);
            for (var army : engine.armies.values()) {
                double power = Math.max(0.0, army.power(engine.types));
                actualArmyPower.merge(army.realm, power, Double::sum);
                JsonObject formation = new JsonObject();
                formation.addProperty("id", map.name + ":" + army.id);
                formation.addProperty("realm_id", army.realm);
                formation.addProperty("name", army.name == null ? "" : army.name);
                formation.addProperty("commander_id", army.commander == null ? "" : army.commander);
                formation.addProperty("martial", army.martial);
                formation.addProperty("men", Math.max(0, army.men()));
                formation.addProperty("morale", finite(army.morale));
                formation.addProperty("supply", finite(army.supply));
                formation.addProperty("location_title_id", army.location > 0 && map.titles.get(army.location) != null
                        ? dev.worldforge.realms.Statecraft.mapTitleId(map.name, map.titles.get(army.location).key) : "");
                formation.add("unit_counts", GSON.toJsonTree(new TreeMap<>(army.units)));
                formation.addProperty("power", finite(power));
                addNamedEntity(namedEntities, map.name + ":" + army.id, "army", army.name, "", army.realm);
                if (RealmsMod.realm(army.realm) != null) armies.add(formation);
            }
            for (TitleData.Realm realm : map.titles.realms.values()) {
                JsonObject economy = new JsonObject();
                economy.addProperty("manpower_pool", Math.max(0, engine.pool(realm.id)));
                economy.addProperty("gold", finite(engine.gold(realm.id)));
                economy.addProperty("capital_title_id", engine.capital(realm.id));
                addCatalogEntry(catalogEntries, "wf_army", "realm_economy", realm.id, realm.name, economy);
            }
            for (var unit : engine.types.values()) {
                JsonObject definition = new JsonObject();
                definition.addProperty("name", unit.name);
                definition.addProperty("attack", finite(unit.attack));
                definition.addProperty("defense", finite(unit.defense));
                definition.addProperty("speed", finite(unit.speed));
                definition.addProperty("siege", finite(unit.siege));
                definition.addProperty("cost", finite(unit.cost));
                definition.addProperty("upkeep", finite(unit.upkeep));
                definition.addProperty("levy", unit.levy);
                definition.add("counters", GSON.toJsonTree(unit.counters));
                addCatalogEntry(catalogEntries, "wf_army", "unit_types", unit.id, unit.name, definition);
            }
        }
        for (TitleData.Realm realm : RealmsMod.realms()) {
            addNamedEntity(namedEntities, realm.id, "realm", realm.name, "", realm.id);
            JsonObject item = new JsonObject();
            item.addProperty("id", realm.id);
            long population = (long) number(realm.props.get("population"), 0);
            item.addProperty("population", Math.max(0L, population));
            item.addProperty("treasury", fixed(RealmsMod.POLITICS.treasury(realm.id).balance));
            // Population-backed mobilization potential is strategic capacity, not a spawned army.
            item.addProperty("army_strength", Math.max(0L, Math.round(actualArmyPower.getOrDefault(realm.id, 0.0))));
            item.addProperty("stability", (int) number(realm.props.get("stability"), 50));
            item.addProperty("prestige", (int) number(realm.props.get("prestige"), 0));
            item.addProperty("expansion_pressure", (int) Math.max(0, Math.min(100, 20 + population / 10)));
            long lastWarDay = 0;
            boolean atWar = false;
            for (TitleData.Realm realm : RealmsMod.realms()) {
            var treasury = RealmsMod.POLITICS.treasury(realm.id);
            JsonObject balance = new JsonObject();
            balance.addProperty("balance", finite(treasury.balance)); balance.addProperty("tax_rate", finite(treasury.taxRate));
            balance.addProperty("last_income", finite(treasury.lastIncome)); balance.addProperty("last_expenses", finite(treasury.lastExpenses));
            balance.addProperty("tax_days", treasury.taxDays);
            addCatalogEntry(catalogEntries, "wf_realms", "realm_treasuries", realm.id, realm.name, balance);
            addCatalogEntry(catalogEntries, "wf_realms", "inheritance_laws", realm.id, realm.name,
                    GSON.toJsonTree(Map.of("law", RealmsMod.POLITICS.inheritanceLaw(realm.id).name())));
        }
        for (var plot : RealmsMod.POLITICS.plots()) {
            JsonObject plotData = new JsonObject();
            plotData.addProperty("id", plot.id); plotData.addProperty("realm_id", plot.realmId);
            plotData.addProperty("sponsor_character_id", plot.sponsorCharacterId);
            plotData.addProperty("target_character_id", plot.targetCharacterId);
            plotData.addProperty("kind", plot.kind.name()); plotData.addProperty("status", plot.status.name());
            plotData.addProperty("progress", finite(plot.progress)); plotData.addProperty("discovered", plot.discovered);
            plotData.add("backers", GSON.toJsonTree(new TreeSet<>(plot.backers)));
            addCatalogEntry(catalogEntries, "wf_realms", "intrigue_plots", plot.id, plot.kind.name(), plotData);
            addNamedEntity(namedEntities, plot.id, "intrigue_plot", plot.kind.name(), "", plot.realmId);
        }
        for (var war : RealmsMod.POLITICS.wars()) {
                if (!war.attackerRealm.equals(realm.id) && !war.defenderRealm.equals(realm.id)) continue;
                lastWarDay = Math.max(lastWarDay, war.startDay);
                if (war.status == dev.worldforge.realms.Statecraft.WarStatus.ACTIVE) {
                    atWar = true;
                    threatenedRealms.add(realm.id);
                }
            }
            item.addProperty("at_war", atWar);
            item.addProperty("last_war_day", lastWarDay);
            realms.add(item);
        }
        // Export the complete authoritative Statecraft graph, not just at_war flags.
        for (var relation : RealmsMod.POLITICS.relations()) {
            JsonObject row = new JsonObject();
            row.addProperty("realm_a", relation.realmA); row.addProperty("realm_b", relation.realmB);
            row.addProperty("opinion", relation.opinion);
            JsonArray treaties = new JsonArray();
            relation.treaties.stream().map(Enum::name).sorted().forEach(treaties::add);
            row.add("treaties", treaties); row.addProperty("truce_until_day", relation.truceUntilDay);
            diplomaticRelations.add(row);
        }
        Set<String> exportedVassals = new HashSet<>();
        for (TitleData.Realm realm : RealmsMod.realms()) for (var contract : RealmsMod.POLITICS.vassalsOf(realm.id)) {
            if (!exportedVassals.add(contract.vassalRealm)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("vassal_realm", contract.vassalRealm); row.addProperty("liege_realm", contract.liegeRealm);
            row.addProperty("subject_type", contract.subjectType.name()); row.addProperty("tax_share", finite(contract.taxShare));
            row.addProperty("levy_share", finite(contract.levyShare)); row.addProperty("loyalty", contract.loyalty);
            row.addProperty("autonomy", contract.autonomy); row.addProperty("protected_by_liege", contract.protectedByLiege);
            vassalContracts.add(row);
        }
        for (var war : RealmsMod.POLITICS.wars()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", war.id); row.addProperty("attacker_realm", war.attackerRealm);
            row.addProperty("defender_realm", war.defenderRealm); row.addProperty("casus_belli", war.casusBelli);
            row.addProperty("declared_by_character_id", war.declaredByCharacterId); row.addProperty("war_score", war.warScore);
            row.addProperty("start_day", war.startDay); row.addProperty("status", war.status.name());
            row.addProperty("outcome", war.outcome == null ? "" : war.outcome);
            wars.add(row);
        }
        for (var government : RealmsMod.DEFS.levels)
            addCatalogEntry(catalogEntries, "wf_realms", "government_levels", government.id(), government.name(), GSON.toJsonTree(government));
        for (var terrain : RealmsMod.DEFS.terrains.values())
            addCatalogEntry(catalogEntries, "wf_realms", "government_terrains", terrain.id(), terrain.name(), GSON.toJsonTree(terrain));
        for (var branch : RealmsMod.DEFS.branches.values())
            addCatalogEntry(catalogEntries, "wf_realms", "doctrine_branches", branch.id(), branch.name(), GSON.toJsonTree(branch));
        for (var law : RealmsMod.DEFS.laws.values())
            addCatalogEntry(catalogEntries, "wf_realms", "laws", law.id(), law.name(), GSON.toJsonTree(law));
        // Shared pair set prevents exact province-adjacency borders from being
        // duplicated by the coarser emergence-neighbour projection below.
        // Export the complete data-pack government catalogue and each realm's
        // selected government, development level, doctrines and laws.
        for (var government : RealmsMod.DEFS.govs.values()) {
            JsonObject definition = new JsonObject();
            definition.addProperty("id", government.id());
            definition.addProperty("name", government.name());
            definition.addProperty("category", government.category());
            definition.addProperty("succession", government.succession());
            definition.addProperty("min_level", government.minLevel());
            definition.add("req_races", GSON.toJsonTree(government.reqRaces()));
            definition.add("forbid_races", GSON.toJsonTree(government.forbidRaces()));
            definition.add("req_flags", GSON.toJsonTree(government.reqFlags()));
            definition.add("req_terrain", GSON.toJsonTree(government.reqTerrain()));
            definition.add("aff_races", GSON.toJsonTree(government.affRaces()));
            definition.add("aff_terrain", GSON.toJsonTree(government.affTerrain()));
            definition.add("mods", GSON.toJsonTree(government.mods()));
            definition.addProperty("doctrine_slots", government.doctrineSlots());
            definition.addProperty("law_slots", government.lawSlots());
            governmentDefinitions.add(definition);
        }
        for (TitleData.Realm realm : RealmsMod.realms()) {
            var policy = RealmsMod.REALMS.state(realm.id);
            JsonObject selection = new JsonObject();
            selection.addProperty("realm_id", realm.id);
            selection.addProperty("government_id", policy.gov == null ? "" : policy.gov);
            int levelIndex = RealmsMod.DEFS.levels.isEmpty() ? -1 : RealmsMod.REALMS.level(realm.id);
            String levelId = levelIndex >= 0 && levelIndex < RealmsMod.DEFS.levels.size()
                    ? RealmsMod.DEFS.levels.get(levelIndex).id() : "";
            selection.addProperty("level_id", levelId);
            JsonArray doctrines = new JsonArray();
            for (String doctrine : new TreeSet<>(policy.nodes)) doctrines.add(doctrine);
            selection.add("doctrine_ids", doctrines);
            selection.add("laws", GSON.toJsonTree(new TreeMap<>(policy.laws)));
            realmGovernments.add(selection);
        }

        // Export the complete authored-map title graph to Rust. This includes every
        // tier, neutral title, parent edge, real liege, holder, claim and province border.
        // The native engine must reason over the same canonical title registry as Java.
        Set<String> emittedPoliticalTitleIds = new HashSet<>();
        Set<String> emittedMapBorders = new TreeSet<>();
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null || map.titles == null) continue;
            for (TitleData.Title sourceTitle : map.titles.allTitles()) {
                String titleId = dev.worldforge.realms.Statecraft.mapTitleId(map.name, sourceTitle.key);
                var political = RealmsMod.POLITICS.title(titleId);
                String realmId = political == null ? "unclaimed:" + map.name : political.realmId;
                JsonObject title = new JsonObject();
                title.addProperty("id", titleId);
                title.addProperty("map_id", map.name);
                title.addProperty("key", sourceTitle.key);
                title.addProperty("name", sourceTitle.name == null ? "" : sourceTitle.name);
                title.addProperty("realm_id", realmId);
                title.addProperty("tier_index", Math.max(0, sourceTitle.tier));
                title.addProperty("tier_key", sourceTitle.tier >= 0 && sourceTitle.tier < map.titles.tiers.size()
                        ? map.titles.tiers.get(sourceTitle.tier).key : "tier_" + sourceTitle.tier);
                String parentId = sourceTitle.parent > 0 && map.titles.get(sourceTitle.parent) != null
                        ? dev.worldforge.realms.Statecraft.mapTitleId(map.name,
                                map.titles.get(sourceTitle.parent).key) : "";
                title.addProperty("parent_title_id", parentId);
                title.addProperty("liege_title_id", political == null ? "" : political.liegeTitleId);
                title.addProperty("holder_character_id", political == null ? "" : political.holderCharacterId);
                title.addProperty("area_blocks", sourceTitle.areaBlocks);
                JsonArray claims = new JsonArray();
                if (political != null) for (String claimant : new TreeSet<>(political.claims)) claims.add(claimant);
                title.add("claims", claims);
                JsonArray neighbors = new JsonArray();
                if (sourceTitle.neighbors != null) {
                    for (int neighborId : Arrays.stream(sourceTitle.neighbors).sorted().toArray()) {
                        TitleData.Title neighbor = map.titles.get(neighborId);
                        if (neighbor == null) continue;
                        String neighborTitleId = dev.worldforge.realms.Statecraft.mapTitleId(map.name, neighbor.key);
                        neighbors.add(neighborTitleId);
                        if (sourceTitle.tier == 0 && political != null) {
                            var otherPolitical = RealmsMod.POLITICS.title(neighborTitleId);
                            if (otherPolitical == null || otherPolitical.realmId.equals(political.realmId)
                                    || otherPolitical.realmId.startsWith("unclaimed:")) continue;
                            String a = political.realmId.compareTo(otherPolitical.realmId) < 0
                                    ? political.realmId : otherPolitical.realmId;
                            String b = political.realmId.compareTo(otherPolitical.realmId) < 0
                                    ? otherPolitical.realmId : political.realmId;
                            if (!emittedMapBorders.add(a + "|" + b)) continue;
                            JsonObject border = new JsonObject();
                            border.addProperty("a", a); border.addProperty("b", b);
                            border.addProperty("contested_claim", !political.claims.isEmpty()
                                    || !otherPolitical.claims.isEmpty());
                            border.addProperty("strategic_value", (int) Math.max(1,
                                    Math.min(100, (sourceTitle.areaPct + neighbor.areaPct) * 2)));
                            border.addProperty("distance_cost", 0);
                            borders.add(border);
                        }
                    }
                }
                title.add("neighbors", neighbors);
                if (emittedPoliticalTitleIds.add(titleId)) {
                    politicalTitles.add(title);
                    addNamedEntity(namedEntities, titleId, "title", sourceTitle.name, parentId, realmId);
                }
            }
        }
        // Include Statecraft-native titles too (e.g. generated duchies/kingdoms
        // above an image-map's native province/region/state raster).
        for (var political : RealmsMod.POLITICS.titles()) {
            if (!emittedPoliticalTitleIds.add(political.id)) continue;
            TitleData.Realm owner = RealmsMod.realm(political.realmId);
            Object mapValue = owner == null ? null : owner.props.get("emergence.map_id");
            JsonObject title = new JsonObject();
            title.addProperty("id", political.id);
            title.addProperty("map_id", mapValue == null ? "statecraft" : String.valueOf(mapValue));
            title.addProperty("key", political.id);
            title.addProperty("name", political.name == null ? political.id : political.name);
            title.addProperty("realm_id", political.realmId);
            title.addProperty("tier_index", political.sourceTierIndex >= 0
                    ? political.sourceTierIndex : political.rank.ordinal());
            title.addProperty("tier_key", political.sourceTierKey == null || political.sourceTierKey.isBlank()
                    ? political.rank.name().toLowerCase(Locale.ROOT) : political.sourceTierKey);
            String parent = political.sourceParentTitleId == null || political.sourceParentTitleId.isBlank()
                    ? political.liegeTitleId : political.sourceParentTitleId;
            title.addProperty("parent_title_id", parent == null ? "" : parent);
            title.addProperty("liege_title_id", political.liegeTitleId == null ? "" : political.liegeTitleId);
            title.addProperty("holder_character_id", political.holderCharacterId == null ? "" : political.holderCharacterId);
            title.addProperty("area_blocks", 0L);
            JsonArray claims = new JsonArray();
            for (String claimant : new TreeSet<>(political.claims)) claims.add(claimant);
            title.add("claims", claims);
            title.add("neighbors", new JsonArray());
            politicalTitles.add(title);
            addNamedEntity(namedEntities, political.id, "title", political.name, parent, political.realmId);
        }
        for (var dynasty : RealmsMod.POLITICS.dynasties()) {
            addNamedEntity(namedEntities, dynasty.houseId, "dynasty", dynasty.name, "", "");
            addNamedEntity(namedEntities, dynasty.houseId, "house_or_dynasty", dynasty.name, "", "");
            JsonObject dynastyData = new JsonObject();
            dynastyData.addProperty("founder_character_id", dynasty.founderCharacterId);
            dynastyData.addProperty("prestige", dynasty.prestige);
            dynastyData.add("member_character_ids", GSON.toJsonTree(new TreeSet<>(dynasty.members)));
            addCatalogEntry(catalogEntries, "wf_realms", "dynasties", dynasty.houseId, dynasty.name, dynastyData);
        }
        for (TitleData.Realm realm : RealmsMod.realms()) {
            Object raw = realm.props.get("emergence.neighbors");
            if (raw == null) continue;
            for (String neighbor : String.valueOf(raw).split(",")) {
                if (neighbor.isBlank() || RealmsMod.realm(neighbor) == null) continue;
                String a = realm.id.compareTo(neighbor) < 0 ? realm.id : neighbor;
                String b = realm.id.compareTo(neighbor) < 0 ? neighbor : realm.id;
                if (!emittedMapBorders.add(a + "|" + b)) continue;
                JsonObject border = new JsonObject();
                border.addProperty("a", a); border.addProperty("b", b);
                border.addProperty("contested_claim", false);
                border.addProperty("strategic_value", 60);
                border.addProperty("distance_cost", 0);
                borders.add(border);
            }
        }
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || colony.s == null) continue;
            var s = colony.s;
            String settlementRealm = SettleMod.realmOf(colony);
            if (settlementRealm == null || settlementRealm.isBlank()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", s.id);
            item.addProperty("realm_id", settlementRealm);
            int residentPopulation = s.population();
            item.addProperty("population", residentPopulation);
            item.addProperty("food", fixed(s.amount("food")));
            item.addProperty("food_capacity", fixed(s.capacity("food")));
            item.addProperty("wood", fixed(s.amount("wood")));
            item.addProperty("stone", fixed(s.amount("stone")));
            item.addProperty("housing", s.housing());
            item.addProperty("housing_used", residentPopulation);
            item.addProperty("farms", s.count("farm", false));
            item.addProperty("foresters", s.count("lumber_camp", false));
            item.addProperty("quarries", s.count("quarry", false));
            item.addProperty("workshops", s.count("market", false) + s.count("sawmill", false) + s.count("smithy", false));
            boolean staffedBarracks = false;
            for (Building building : s.buildings) if ("barracks".equals(building.def.id)
                    && building.state == Building.State.DONE && !building.paused
                    && s.assigned(building.uid).stream().anyMatch(worker -> worker.lifeAction.isBlank() && "trainer".equals(worker.workRole()))) {
                staffedBarracks = true; break;
            }
            item.addProperty("barracks", staffedBarracks ? 1 : 0);
            item.addProperty("watch_posts", s.count("watch_post", true));
            item.addProperty("threatened", threatenedRealms.contains(settlementRealm));
            int roadSegments = s == null || colony.map == null ? 0 : colony.map.roads.values().stream()
                    .mapToInt(road -> Math.max(0, road.builtSegments)).sum();
            item.addProperty("roads", roadSegments);
            addNamedEntity(namedEntities, s.id, "settlement", s.name, "", settlementRealm);
            // Export the actual infrastructure graph, not merely a road count.
            if (colony.map != null) {
                for (var road : colony.map.roads.values()) {
                    JsonObject roadData = new JsonObject();
                    roadData.addProperty("road_id", road.id); roadData.addProperty("surface", road.surface);
                    roadData.addProperty("built_segments", Math.max(0, road.builtSegments));
                    JsonArray nodes = new JsonArray();
                    for (var node : road.path) {
                        JsonObject point = new JsonObject();
                        point.addProperty("x", node.gx()); point.addProperty("z", node.gz());
                        nodes.add(point);
                    }
                    roadData.add("path", nodes);
                    addCatalogEntry(catalogEntries, "wf_settle", "road_network",
                            s.id + ":road:" + road.id, "Road " + road.id, roadData);
                }
                for (var plan : colony.map.plans.values())
                    addCatalogEntry(catalogEntries, "wf_settle", "construction_plans",
                            s.id + ":plan:" + plan.buildingUid, plan.buildingId, GSON.toJsonTree(plan));
                for (var warehouse : colony.map.warehouses.values())
                    addCatalogEntry(catalogEntries, "wf_settle", "warehouses",
                            s.id + ":warehouse:" + warehouse.id, warehouse.id, GSON.toJsonTree(warehouse));
                for (var job : colony.map.logistics.values())
                    addCatalogEntry(catalogEntries, "wf_settle", "logistics_jobs",
                            s.id + ":logistics:" + job.id, job.resource, GSON.toJsonTree(job));
            }
            JsonObject settlementIdentity = new JsonObject();
            settlementIdentity.addProperty("type_id", colony.settlementType == null ? "" : colony.settlementType);
            settlementIdentity.addProperty("source_map_id", colony.sourceMapId == null ? "" : colony.sourceMapId);
            settlementIdentity.addProperty("blueprint_settlement_id", colony.blueprintSettlementId == null ? "" : colony.blueprintSettlementId);
            settlementIdentity.addProperty("race_id", colony.identityRace == null ? "" : colony.identityRace);
            settlementIdentity.addProperty("culture_id", colony.identityCulture == null ? "" : colony.identityCulture);
            settlementIdentity.addProperty("government_id", colony.governmentId == null ? "" : colony.governmentId);
            Map<String, Integer> buildingCounts = new TreeMap<>(), completedBuildingCounts = new TreeMap<>();
            for (Building building : s.buildings) {
                if (building == null || building.def == null || building.def.id == null) continue;
                buildingCounts.merge(building.def.id, 1, Integer::sum);
                if (building.state == Building.State.DONE)
                    completedBuildingCounts.merge(building.def.id, 1, Integer::sum);
            }
            Set<String> trainerBuildings = new TreeSet<>();
            Map<String, Integer> trainerCounts = new TreeMap<>();
            for (Building building : s.buildings) {
                if (building == null || building.def == null || building.state != Building.State.DONE || building.paused) continue;
                int trainers = (int) s.assigned(building.uid).stream().filter(worker -> worker.lifeAction.isBlank()
                        && "trainer".equals(worker.workRole())).count();
                if (trainers > 0) {
                    trainerBuildings.add(building.def.id);
                    trainerCounts.put(building.def.id, trainers);
                }
            }
            settlementIdentity.add("trainer_buildings", GSON.toJsonTree(trainerBuildings));
            settlementIdentity.add("trainer_counts", GSON.toJsonTree(trainerCounts));
            settlementIdentity.add("building_counts", GSON.toJsonTree(buildingCounts));
            settlementIdentity.add("completed_building_counts", GSON.toJsonTree(completedBuildingCounts));
            settlementIdentity.add("resource_stock", GSON.toJsonTree(new TreeMap<>(s.stock)));
            addCatalogEntry(catalogEntries, "wf_settle", "settlement_instances", s.id, s.name, settlementIdentity);
            item.addProperty("local_pressure", Math.max(0, residentPopulation - s.housing()));
            item.addProperty("last_project_day", lastProjectDay.getOrDefault(s.id, Math.max(0, day - 365)));
            settlements.add(item);
            int farmers = 0, lumberjacks = 0, miners = 0, builders = 0, soldiers = 0, unemployed = 0;
            for (Worker worker : s.workers) {
                switch (worker.permanentProfession()) {
                    case "farmer" -> farmers++;
                    case "lumberjack" -> lumberjacks++;
                    case "miner" -> miners++;
                    case "builder" -> builders++;
                    case "guard", "trainer" -> soldiers++;
                    case "laborer", "general_worker" -> { if (worker.building == 0 && worker.lifeAction.isBlank()) unemployed++; }
                }
            }
            JsonObject w = new JsonObject();
            w.addProperty("settlement_id", s.id);
            w.addProperty("farmers", farmers); w.addProperty("lumberjacks", lumberjacks);
            w.addProperty("miners", miners); w.addProperty("builders", builders);
            w.addProperty("soldiers", soldiers); w.addProperty("unemployed", unemployed);
            w.addProperty("food_need", fixed(s.flow.perMinute(s.time, "food", false)));
            w.addProperty("food_output", fixed(s.flow.perMinute(s.time, "food", true)));
            w.addProperty("wood_need", fixed(s.flow.perMinute(s.time, "wood", false)));
            w.addProperty("wood_output", fixed(s.flow.perMinute(s.time, "wood", true)));
            w.addProperty("stone_need", fixed(s.flow.perMinute(s.time, "stone", false)));
            w.addProperty("stone_output", fixed(s.flow.perMinute(s.time, "stone", true)));
            workforce.add(w);
        }
        addCatalogEntry(catalogEntries, "wf_people", "name_pools", "all_pools", "Cultural and racial naming pools",
                GSON.toJsonTree(PeopleMod.NAMES.catalogSnapshot()));
        JsonObject relationsSnapshot = exportOptionalModuleSnapshot("wf_relations",
                "dev.worldforge.relations.mc.RelationsMc", "worldSnapshotJson");
        if (relationsSnapshot != null) {
            root.add("relations_runtime", relationsSnapshot);
            addCatalogEntry(catalogEntries, "wf_relations", "runtime_state", "relationships",
                    "Canonical social bonds, requests and persistent history", relationsSnapshot);
        }
        JsonObject tradeSnapshot = exportOptionalModuleSnapshot("wf_trade",
                "dev.worldforge.trade.mc.TradeMod", "worldSnapshotJson");
        if (tradeSnapshot != null) {
            root.add("trade_runtime", tradeSnapshot);
            JsonObject goods = tradeSnapshot.has("goods") ? tradeSnapshot.getAsJsonObject("goods") : new JsonObject();
            for (var entry : goods.entrySet()) {
                String goodId = entry.getKey();
                String goodName = entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("name")
                        ? entry.getValue().getAsJsonObject().get("name").getAsString() : goodId;
                addCatalogEntry(catalogEntries, "wf_trade", "goods", goodId, goodName, entry.getValue());
                addNamedEntity(namedEntities, goodId, "trade_good", goodName, "", "");
            }
            JsonObject markets = tradeSnapshot.has("markets") ? tradeSnapshot.getAsJsonObject("markets") : new JsonObject();
            for (var entry : markets.entrySet())
                addCatalogEntry(catalogEntries, "wf_trade", "markets", entry.getKey(), entry.getKey(), entry.getValue());
            JsonArray caravans = tradeSnapshot.has("caravans") ? tradeSnapshot.getAsJsonArray("caravans") : new JsonArray();
            for (var element : caravans) if (element.isJsonObject()) {
                JsonObject caravan = element.getAsJsonObject();
                String id = caravan.get("id").getAsString();
                addCatalogEntry(catalogEntries, "wf_trade", "caravans", id, "Caravan " + id, caravan);
                addNamedEntity(namedEntities, id, "caravan", "Caravan " + id, "", caravan.has("owner") ? caravan.get("owner").getAsString() : "");
            }
            JsonArray ambushes = tradeSnapshot.has("ambushes") ? tradeSnapshot.getAsJsonArray("ambushes") : new JsonArray();
            for (var element : ambushes) if (element.isJsonObject()) {
                JsonObject ambush = element.getAsJsonObject();
                String id = ambush.get("id").getAsString();
                addCatalogEntry(catalogEntries, "wf_trade", "ambushes", id, "Ambush " + id, ambush);
            }
            addCatalogEntry(catalogEntries, "wf_trade", "runtime_state", "trade",
                    "Markets, caravans, ambushes and trade shocks", tradeSnapshot);
        }
        JsonObject troopSnapshot = exportOptionalModuleSnapshot("wf_troops",
                "dev.worldforge.troops.mc.TroopsMod", "worldSnapshotJson");
        if (troopSnapshot != null) {
            root.add("military_runtime", troopSnapshot);
            JsonObject unitDefinitions = troopSnapshot.has("unit_definitions")
                    ? troopSnapshot.getAsJsonObject("unit_definitions") : new JsonObject();
            for (var entry : unitDefinitions.entrySet()) {
                String unitId = entry.getKey();
                String unitName = entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("name")
                        ? entry.getValue().getAsJsonObject().get("name").getAsString() : unitId;
                addCatalogEntry(catalogEntries, "wf_troops", "physical_unit_types", unitId, unitName, entry.getValue());
                addNamedEntity(namedEntities, unitId, "troop_type", unitName, "", "");
            }
            JsonArray liveTroops = troopSnapshot.has("live_troops") ? troopSnapshot.getAsJsonArray("live_troops") : new JsonArray();
            for (var element : liveTroops) if (element.isJsonObject()) {
                JsonObject troop = element.getAsJsonObject();
                String entityId = troop.has("entity_id") ? troop.get("entity_id").getAsString() : "";
                String unitId = troop.has("unit_id") ? troop.get("unit_id").getAsString() : "";
                String unitName = unitDefinitions.has(unitId) && unitDefinitions.getAsJsonObject(unitId).has("name")
                        ? unitDefinitions.getAsJsonObject(unitId).get("name").getAsString() : unitId;
                addNamedEntity(namedEntities, entityId, "physical_troop", unitName, "", troop.has("realm_id") ? troop.get("realm_id").getAsString() : "");
            }
            JsonArray squadRows = troopSnapshot.has("squads") ? troopSnapshot.getAsJsonArray("squads") : new JsonArray();
            for (var element : squadRows) if (element.isJsonObject()) {
                JsonObject squad = element.getAsJsonObject();
                addNamedEntity(namedEntities, squad.get("id").getAsString(), "squad",
                        squad.get("name").getAsString(), "", squad.has("realm") ? squad.get("realm").getAsString() : "");
            }
            addCatalogEntry(catalogEntries, "wf_troops", "runtime_state", "military",
                    "Physical military state and durable ledgers", troopSnapshot);
        }
        // CourtSim remains the owner of schemes, regencies, factions, pending events,
        // council opinions and court news; WorldSim reads its complete persisted state.
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null || map.titles == null) continue;
            CourtSim court = CourtMod.court(map);
            if (court != null) {
                JsonElement courtState = com.google.gson.JsonParser.parseString(court.toJson());
                addCatalogEntry(catalogEntries, "wf_court", "court_states", map.name, map.name, courtState);
            }
        }
        // wf-war owns active campaigns, casus belli, occupation, peace terms and truces.
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null || map.titles == null) continue;
            WarEngine warEngine = WarMod.engine(map);
            for (var cb : warEngine.cbs.values())
                addCatalogEntry(catalogEntries, "wf_war", "casus_belli", cb.id, cb.name, GSON.toJsonTree(cb));
            for (var war : warEngine.wars.values()) {
                JsonObject warData = new JsonObject();
                warData.addProperty("id", war.id); warData.addProperty("attacker", war.attacker);
                warData.addProperty("defender", war.defender); warData.addProperty("casus_belli", war.cb);
                warData.addProperty("score", finite(war.score)); warData.addProperty("start_day", war.startDay);
                warData.addProperty("end_day", war.endDay); warData.addProperty("active", war.active);
                warData.addProperty("result", war.result);
                warData.addProperty("winner", war.winner == null ? "" : war.winner.name());
                warData.addProperty("offer_from", war.offerFrom == null ? "" : war.offerFrom.name());
                warData.addProperty("offer_white", war.offerWhite);
                warData.add("attacker_members", GSON.toJsonTree(war.members(dev.worldforge.war.War.Side.ATTACKER)));
                warData.add("defender_members", GSON.toJsonTree(war.members(dev.worldforge.war.War.Side.DEFENDER)));
                warData.add("sides", GSON.toJsonTree(war.sides));
                warData.addProperty("winner", war.winner == null ? "" : war.winner.name());
                warData.addProperty("peace_offer_from", war.offerFrom == null ? "" : war.offerFrom.name());
                warData.addProperty("peace_offer_white", war.offerWhite);
                warData.add("goals", GSON.toJsonTree(war.goals)); warData.add("occupied", GSON.toJsonTree(war.occupied));
                warData.add("log", GSON.toJsonTree(war.log));
                addCatalogEntry(catalogEntries, "wf_war", "war_instances",
                        map.name + ":" + war.id, "War " + war.id, warData);
                addNamedEntity(namedEntities, map.name + ":" + war.id, "war", "War " + war.id, "", war.attacker);
            }
            addCatalogEntry(catalogEntries, "wf_war", "truces", map.name, map.name,
                    GSON.toJsonTree(warEngine.truces));
        }
        if (SettleMod.DEFS != null) {
            for (var building : SettleMod.DEFS.buildings.values()) {
                addCatalogEntry(catalogEntries, "wf_settle", "buildings", building.id, building.name, GSON.toJsonTree(building));
                addNamedEntity(namedEntities, building.id, "building_definition", building.name, "", "");
            }
            for (var profession : SettleMod.DEFS.professions.values())
                addCatalogEntry(catalogEntries, "wf_settle", "building_professions", profession.id, profession.name, GSON.toJsonTree(profession));
            for (var resource : SettleMod.DEFS.resourceNames.entrySet())
                addCatalogEntry(catalogEntries, "wf_settle", "resources", resource.getKey(), resource.getValue(),
                        GSON.toJsonTree(Map.of("name", resource.getValue(),
                                "base_price", SettleMod.DEFS.prices.getOrDefault(resource.getKey(), 0.0))));
        }
        for (var type : SettleMod.TYPES.types.values()) {
            addCatalogEntry(catalogEntries, "wf_settle", "settlement_types", type.id(), type.name(), GSON.toJsonTree(type));
            addNamedEntity(namedEntities, type.id(), "settlement_type", type.name(), "", "");
        }
        // Export all loaded profession definitions and all configured race/class/origin
        // definitions from their owning modules, preserving their complete JSON shape.
        for (var profession : PeopleMod.PROFESSIONS.all())
            addCatalogEntry(catalogEntries, "wf_people", "professions", profession.id, profession.name, GSON.toJsonTree(profession));
        for (var race : OriginsMod.ORIGINS.defs.races.values()) {
            addCatalogEntry(catalogEntries, "wf_origins", "races", race.id(), race.name(), GSON.toJsonTree(race));
            addNamedEntity(namedEntities, race.id(), "race", race.name(), race.parent(), "");
        }
        for (var cls : OriginsMod.ORIGINS.defs.classes.values()) {
            addCatalogEntry(catalogEntries, "wf_origins", "classes", cls.id(), cls.name(), GSON.toJsonTree(cls));
            addNamedEntity(namedEntities, cls.id(), "character_class", cls.name(), "", "");
        }
        for (var profession : OriginsMod.DEFS.professions.values())
            addCatalogEntry(catalogEntries, "wf_origins", "origin_professions", profession.id(), profession.name(), GSON.toJsonTree(profession));
        for (var synergy : OriginsMod.DEFS.synergies.values())
            addCatalogEntry(catalogEntries, "wf_origins", "synergies", synergy.id(), synergy.name(), GSON.toJsonTree(synergy));
        // Resolve band -> polity from the last committed emergence frame. Residents from
        // either geography source then enter the same Character snapshot by canonical UUID.
        Map<String, String> polityByBand = new HashMap<>();
        if (emergenceState != null && emergenceState.has("polities") && emergenceState.get("polities").isJsonArray()) {
            for (var polityElement : emergenceState.getAsJsonArray("polities")) {
                if (!polityElement.isJsonObject()) continue;
                JsonObject polity = polityElement.getAsJsonObject();
                if (!polity.has("id") || !polity.has("settlement_ids") || !polity.get("settlement_ids").isJsonArray()) continue;
                String polityId = polity.get("id").getAsString();
                for (var bandElement : polity.getAsJsonArray("settlement_ids"))
                    if (bandElement.isJsonPrimitive()) polityByBand.put(bandElement.getAsString(), polityId);
            }
        }
        // Persist the band -> polity projection onto each canonical resident. Downstream
        // relations, reputation, court and economy addons must not independently guess
        // which state a person belongs to from a physical chunk or legacy local ID.
        for (EmergenceSource source : sources) {
            if (source.registry == null) continue;
            for (dev.worldforge.people.Character person : source.registry.all()) {
                if (person == null || !person.alive) continue;
                Object rawBand = person.props.get("emergence.band_id");
                String polityId = rawBand == null ? "" : polityByBand.getOrDefault(String.valueOf(rawBand), "");
                if (polityId.isBlank()) {
                    person.props.remove("emergence.polity_id");
                    person.props.remove("emergence.realm_id");
                } else {
                    person.props.put("emergence.polity_id", polityId);
                    person.props.put("emergence.realm_id", "emergent-" + polityId);
                }
            }
        }
        Set<String> emittedCharacterIds = new HashSet<>();
        Set<CharacterRegistry> snapshotRegistries = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EmergenceSource source : sources) {
            CharacterRegistry registry = source.registry;
            if (registry == null || !snapshotRegistries.add(registry)) continue;
            for (dev.worldforge.people.Character person : registry.all()) {
                if (person == null || person.globalId == null || person.globalId.isBlank()) continue;
                String realmId = "";
                // Realm office holders (rulers, stewards, marshals, councillors) are canonical
                // Characters too; include them without admitting authored map-court characters.
                for (dev.worldforge.people.Character.Post post : person.posts) {
                    if (post.place == null || !post.place.startsWith("realm:")) continue;
                    String candidate = post.place.substring("realm:".length());
                    if (RealmsMod.realm(candidate) != null) {
                        realmId = candidate;
                        break;
                    }
                }
                if (realmId.isBlank() && source.map != null && source.map.titles != null) {
                    for (dev.worldforge.people.Character.Post post : person.posts) {
                        if (post.place == null || !post.place.startsWith("title:")) continue;
                        try {
                            int localTitleId = Integer.parseInt(post.place.substring("title:".length()));
                            TitleData.Title mapTitle = source.map.titles.get(localTitleId);
                            if (mapTitle == null) continue;
                            var politicalTitle = RealmsMod.POLITICS.title(
                                    dev.worldforge.realms.Statecraft.mapTitleId(source.map.name, mapTitle.key));
                            if (politicalTitle != null && RealmsMod.realm(politicalTitle.realmId) != null) {
                                realmId = politicalTitle.realmId;
                                break;
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
                if (realmId.isBlank()) {
                    Object rawPolity = person.props.get("emergence.polity_id");
                    String polityId = rawPolity == null ? "" : String.valueOf(rawPolity).trim();
                    if (polityId.isBlank()) {
                        Object rawBand = person.props.get("emergence.band_id");
                        if (rawBand != null) polityId = polityByBand.getOrDefault(String.valueOf(rawBand), "");
                    }
                    if (!polityId.isBlank()) realmId = "emergent-" + polityId;
                }
                if (realmId.isBlank() || RealmsMod.realm(realmId) == null) {
                    realmId = "";
                    boolean hasAutonomousLifeState = person.props.keySet().stream()
                            .anyMatch(key -> key.startsWith("population.") || key.startsWith("life.")
                                    || key.startsWith("origin.profession"));
                    // Authored court templates without a living population record are not
                    // world inhabitants. Vanilla and tracked unaffiliated NPCs are included.
                    if (source.map != null && !hasAutonomousLifeState) continue;
                }
                if (!emittedCharacterIds.add(person.globalId)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("id", person.globalId);
                item.addProperty("realm_id", realmId);
                item.addProperty("map_id", source.id);
                item.addProperty("age", Math.max(0, WorldForgeApi.clock().year() - person.birthYear));
                item.addProperty("alive", person.alive);
                String spouseGlobalId = canonicalFamilyId(source.registry,
                        person.props.get("family.spouse_global_id"), person.spouse);
                item.addProperty("married", !spouseGlobalId.isBlank());
                item.addProperty("gender", "m".equals(person.gender) ? 0 : 1);
                item.addProperty("fertility", (int) number(person.props.get("fertility"), 50));
                item.addProperty("prestige", (int) number(person.props.get("prestige"), 0));
                item.addProperty("opinion", (int) number(person.props.get("opinion"), 0));
                item.addProperty("house_id", person.house == null ? "" : person.house);
                item.addProperty("display_name", person.name == null ? "" : person.name);
                addNamedEntity(namedEntities, person.globalId, "character", person.name, "", realmId);
                Origins.Build origin = OriginsMod.ORIGINS.peek("n:" + person.globalId);
                item.addProperty("race_id", origin != null && origin.race != null ? origin.race
                        : String.valueOf(person.props.getOrDefault("origin.race", "")));
                item.addProperty("culture_id", person.culture == null ? "" : person.culture);
                item.addProperty("class_id", origin != null && origin.cls != null ? origin.cls
                        : String.valueOf(person.props.getOrDefault("origin.class", "")));
                String canonicalProfession = PopulationSimulation.profession(person);
                item.addProperty("profession_id", !canonicalProfession.isBlank() ? canonicalProfession
                        : origin != null && !origin.prof.isEmpty() ? origin.prof.keySet().iterator().next()
                        : String.valueOf(person.props.getOrDefault("origin.profession", "")));
                JsonArray traits = new JsonArray();
                for (String trait : new TreeSet<>(person.traits)) traits.add(trait);
                item.add("trait_ids", traits);
                item.addProperty("father_id", canonicalFamilyId(source.registry,
                        person.props.get("family.father_global_id"), person.father));
                item.addProperty("mother_id", canonicalFamilyId(source.registry,
                        person.props.get("family.mother_global_id"), person.mother));
                item.addProperty("spouse_id", spouseGlobalId);
                JsonArray children = new JsonArray();
                Set<String> childGlobalIds = new TreeSet<>();
                Object globalChildren = person.props.get("family.child_global_ids");
                if (globalChildren instanceof Collection<?> ids)
                    for (Object childId : ids) if (childId instanceof String id && !id.isBlank())
                        childGlobalIds.add(canonicalFamilyId(source.registry, id, id));
                for (String childId : person.children) {
                    String globalChild = canonicalFamilyId(source.registry, null, childId);
                    if (!globalChild.isBlank()) childGlobalIds.add(globalChild);
                }
                childGlobalIds.forEach(children::add);
                item.add("child_ids", children);
                JsonArray roles = new JsonArray(), heldTitles = new JsonArray();
                Set<String> roleIds = new TreeSet<>(), titleIds = new TreeSet<>();
                for (dev.worldforge.people.Character.Post post : person.posts) {
                    roleIds.add(post.role);
                    if (post.place != null && post.place.startsWith("title:")
                            && source.map != null && source.map.titles != null) {
                        try {
                            TitleData.Title mapTitle = source.map.titles.get(
                                    Integer.parseInt(post.place.substring("title:".length())));
                            if (mapTitle != null) titleIds.add(
                                    dev.worldforge.realms.Statecraft.mapTitleId(source.map.name, mapTitle.key));
                        } catch (NumberFormatException ignored) { }
                    }
                }
                roleIds.forEach(roles::add);
                titleIds.forEach(heldTitles::add);
                item.add("role_ids", roles);
                item.add("title_ids", heldTitles);
                JsonObject skills = new JsonObject(), needs = new JsonObject();
                for (var skill : PopulationSimulation.skillRanksSnapshot(person).entrySet())
                    skills.addProperty(skill.getKey(), skill.getValue());
                for (var need : PopulationSimulation.Need.values())
                    needs.addProperty(need.name().toLowerCase(Locale.ROOT), PopulationSimulation.need(person, need));
                JsonArray duties = new JsonArray();
                for (String duty : new TreeSet<>(PopulationSimulation.duties(person))) duties.add(duty);
                item.add("duty_ids", duties);
                item.addProperty("life_stage", PopulationSimulation.lifeStage(person, WorldForgeApi.clock().year()).name());
                item.addProperty("last_intent", PopulationSimulation.lastIntent(person).name());
                item.add("profession_history", GSON.toJsonTree(PopulationSimulation.professionHistory(person)));
                JsonObject lifeAi = new JsonObject();
                for (String key : List.of("population.ai.last_action", "population.ai.last_outcome",
                        "population.ai.last_reason", "population.ai.pending_action",
                        "population.ai.pending_minute", "population.ai.next_think_minute",
                        "population.ai.pending_token", "population.ai.failure_cooldowns",
                        "population.ai.last_plan")) {
                    Object value = person.props.get(key);
                    if (value != null) lifeAi.add(key.substring("population.ai.".length()), GSON.toJsonTree(value));
                }
                item.add("life_ai", lifeAi);
                for (var property : person.props.entrySet()) {
                    if (!(property.getValue() instanceof Number value)) continue;
                    String key = property.getKey();
                    if (key.startsWith("skill."))
                        skills.addProperty(key.substring("skill.".length()), value.intValue());
                    else if (key.startsWith("needs."))
                        needs.addProperty(key.substring("needs.".length()), value.intValue());
                }
                item.add("skills", skills);
                item.add("needs", needs);
                characters.add(item);
            }
        }
        // Canonical JSON ordering is part of the native simulation contract:
        // identical authoritative state must yield identical Rust input bytes.
        sortObjects(realms, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(politicalTitles, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(governmentDefinitions, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(realmGovernments, Comparator.comparing(item -> item.get("realm_id").getAsString()));
        sortObjects(borders, Comparator.comparing((JsonObject item) -> item.get("a").getAsString())
                .thenComparing(item -> item.get("b").getAsString()));
        sortObjects(settlements, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(characters, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(workforce, Comparator.comparing(item -> item.get("settlement_id").getAsString()));
        // wf-relations is optional. Resolve its public domain API reflectively so the
        // WorldSim class remains loadable when that addon is not installed.
        int exportedSocialRelations = exportSocialRelations(socialRelations, emittedCharacterIds);
        // Capabilities distinguish absent optional modules from present-but-empty catalogs.
        addCapability(capabilities, "worldforge", 1, net.minecraftforge.fml.ModList.get().isLoaded("worldforge"), 0, sources.size());
        addCapability(capabilities, "wf_worldsim", 1, true, 0, 1);
        addCapability(capabilities, "wf_realms", 1, true,
                governmentDefinitions.size() + catalogEntryCount(catalogEntries, "wf_realms"), RealmsMod.realms().size());
        addCapability(capabilities, "wf_settle", 1, true,
                catalogEntryCount(catalogEntries, "wf_settle"), SettleMod.COLONIES.size());
        addCapability(capabilities, "wf_army", 1, true,
                catalogEntryCount(catalogEntries, "wf_army"), armies.size());
        addCapability(capabilities, "wf_troops", 1, net.minecraftforge.fml.ModList.get().isLoaded("wf_troops"),
                catalogEntryCount(catalogEntries, "wf_troops"),
                troopSnapshot != null && troopSnapshot.has("live_troops") ? troopSnapshot.getAsJsonArray("live_troops").size() : 0);
        addCapability(capabilities, "wf_people", 1, true,
                catalogEntryCount(catalogEntries, "wf_people"), characters.size());
        addCapability(capabilities, "wf_relations", 1, net.minecraftforge.fml.ModList.get().isLoaded("wf_relations"),
                catalogEntryCount(catalogEntries, "wf_relations"), exportedSocialRelations);
        addCapability(capabilities, "wf_origins", 1, true,
                catalogEntryCount(catalogEntries, "wf_origins"), characters.size());
        addCapability(capabilities, "wf_court", 1, net.minecraftforge.fml.ModList.get().isLoaded("wf_court"),
                catalogEntryCount(catalogEntries, "wf_court"), 0);
        addCapability(capabilities, "wf_war", 1, net.minecraftforge.fml.ModList.get().isLoaded("wf_war"),
                catalogEntryCount(catalogEntries, "wf_war"), wars.size());
        addCapability(capabilities, "wf_trade", 1, net.minecraftforge.fml.ModList.get().isLoaded("wf_trade"),
                catalogEntryCount(catalogEntries, "wf_trade"),
                tradeSnapshot != null && tradeSnapshot.has("caravans") ? tradeSnapshot.getAsJsonArray("caravans").size() : 0);
        for (String optionalModule : List.of("wf_rts", "wf_genesis", "wf_progress", "wf_reputation"))
            addCapability(capabilities, optionalModule, 1, net.minecraftforge.fml.ModList.get().isLoaded(optionalModule), 0, 0);
        sortObjects(catalogEntries, Comparator.comparing((JsonObject item) -> item.get("module_id").getAsString())
                .thenComparing(item -> item.get("catalog_id").getAsString()).thenComparing(item -> item.get("entry_id").getAsString()));
        sortObjects(diplomaticRelations, Comparator.comparing((JsonObject item) -> item.get("realm_a").getAsString())
                .thenComparing(item -> item.get("realm_b").getAsString()));
        sortObjects(vassalContracts, Comparator.comparing(item -> item.get("vassal_realm").getAsString()));
        sortObjects(wars, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(armies, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(socialRelations, Comparator.comparing((JsonObject item) -> item.get("character_a").getAsString())
                .thenComparing(item -> item.get("character_b").getAsString()));
        sortObjects(namedEntities, Comparator.comparing((JsonObject item) -> item.get("kind").getAsString())
                .thenComparing(item -> item.get("id").getAsString()));
        root.add("capabilities", capabilities); root.add("catalog_entries", catalogEntries);
        root.add("diplomatic_relations", diplomaticRelations); root.add("vassal_contracts", vassalContracts);
        root.add("wars", wars); root.add("armies", armies); root.add("social_relations", socialRelations);
        root.add("named_entities", namedEntities);
        root.add("geography", geography);
        root.add("realms", realms); root.add("titles", politicalTitles);
        root.add("government_definitions", governmentDefinitions); root.add("realm_governments", realmGovernments);
        root.add("borders", borders); root.add("settlements", settlements);
        root.add("characters", characters); root.add("workforce", workforce);
        return root;
    }


    private static String troopRecruitmentBuilding(String armyUnitId) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_troops")) return "barracks";
        try {
            Class<?> owner = Class.forName("dev.worldforge.troops.mc.TroopsMod");
            Object definitions = owner.getField("DEFS").get(null);
            @SuppressWarnings("unchecked")
            Map<String, String> armyTypes = (Map<String, String>) definitions.getClass().getMethod("armyTypes").invoke(definitions);
            String physicalType = armyTypes.getOrDefault(armyUnitId, "");
            if (physicalType.isBlank()) return "barracks";
            Object building = definitions.getClass().getMethod("recruitmentBuilding", String.class)
                    .invoke(definitions, physicalType);
            return building instanceof String value && !value.isBlank() ? value : "barracks";
        } catch (ReflectiveOperationException | LinkageError error) {
            LOGGER.warn("wf_worldsim: could not resolve wf-troops recruitment building for {}: {}", armyUnitId, error.toString());
            return "barracks";
        }
    }

    private static JsonObject exportOptionalModuleSnapshot(String moduleId, String className, String methodName) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded(moduleId)) return null;
        try {
            Class<?> owner = Class.forName(className);
            Object raw = owner.getMethod(methodName).invoke(null);
            if (!(raw instanceof String json)) return null;
            JsonElement parsed = com.google.gson.JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalStateException("Module snapshot must be a JSON object: " + moduleId);
            return parsed.getAsJsonObject();
        } catch (ReflectiveOperationException | LinkageError | com.google.gson.JsonParseException error) {
            LOGGER.warn("wf_worldsim: optional module {} snapshot unavailable: {}", moduleId, error.toString());
            return null;
        }
    }

    private static String canonicalFamilyId(CharacterRegistry registry, Object globalValue, String localValue) {
        String candidate = globalValue instanceof String value && !value.isBlank()
                ? value.trim() : localValue == null ? "" : localValue.trim();
        if (candidate.isBlank()) return "";
        dev.worldforge.people.Character character = PeopleMod.authority().getGlobal(candidate);
        if (character == null && registry != null) character = registry.get(candidate);
        if (character != null) return character.globalId;
        try { return java.util.UUID.fromString(candidate).toString(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static int exportSocialRelations(JsonArray target, Set<String> snapshotCharacterIds) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_relations")) return 0;
        int exported = 0;
        try {
            Class<?> bridge = Class.forName("dev.worldforge.relations.mc.RelationsMc");
            Object relations = bridge.getField("REL").get(null);
            java.lang.reflect.Method keys = relations.getClass().getMethod("keys");
            java.lang.reflect.Method peek = relations.getClass().getMethod("peek", String.class, String.class);
            for (Object rawKey : (Collection<?>) keys.invoke(relations)) {
                String key = String.valueOf(rawKey);
                int separator = key.indexOf('|');
                if (separator <= 0 || separator == key.length() - 1) continue;
                String first = key.substring(0, separator), second = key.substring(separator + 1);
                if (!snapshotCharacterIds.contains(first) || !snapshotCharacterIds.contains(second)) continue;
                Object bond = peek.invoke(relations, first, second);
                if (bond == null) continue;
                Class<?> type = bond.getClass();
                JsonObject row = new JsonObject();
                row.addProperty("character_a", first); row.addProperty("character_b", second);
                row.addProperty("relationship", type.getField("affinity").getInt(bond));
                row.addProperty("love", type.getField("love").getInt(bond));
                row.addProperty("married", type.getField("married").getBoolean(bond));
                row.addProperty("dating", type.getField("dating").getBoolean(bond));
                row.addProperty("employed", type.getField("employed").getBoolean(bond));
                row.addProperty("last_contact_day", Math.max(0, type.getField("lastContact").getInt(bond)));
                target.add(row); exported++;
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            LOGGER.warn("wf_worldsim: wf-relations is installed but its social snapshot API could not be read: {}", error.toString());
        }
        return exported;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static int catalogEntryCount(JsonArray entries, String moduleId) {
        int count = 0;
        for (var element : entries) if (element.isJsonObject()
                && moduleId.equals(element.getAsJsonObject().get("module_id").getAsString())) count++;
        return count;
    }

    private static void addCapability(JsonArray target, String moduleId, int schemaVersion,
                                      boolean available, int catalogCount, int entityCount) {
        JsonObject item = new JsonObject();
        item.addProperty("module_id", moduleId); item.addProperty("schema_version", schemaVersion);
        item.addProperty("available", available); item.addProperty("catalog_count", Math.max(0, catalogCount));
        item.addProperty("entity_count", Math.max(0, entityCount)); target.add(item);
    }

    private static void addCatalogEntry(JsonArray target, String moduleId, String catalogId,
                                        String entryId, String name, com.google.gson.JsonElement payload) {
        if (entryId == null || entryId.isBlank()) return;
        for (var existing : target) if (existing.isJsonObject()) {
            JsonObject row = existing.getAsJsonObject();
            if (moduleId.equals(row.get("module_id").getAsString())
                    && catalogId.equals(row.get("catalog_id").getAsString())
                    && entryId.equals(row.get("entry_id").getAsString())) return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("module_id", moduleId); item.addProperty("catalog_id", catalogId);
        item.addProperty("entry_id", entryId); item.addProperty("name", name == null ? "" : name);
        item.add("payload", payload == null ? new JsonObject() : payload.deepCopy());
        target.add(item);
    }

    private static void addNamedEntity(JsonArray target, String id, String kind, String name,
                                       String parentId, String ownerId) {
        if (id == null || id.isBlank()) return;
        for (var existing : target) if (existing.isJsonObject()) {
            JsonObject row = existing.getAsJsonObject();
            if (kind.equals(row.get("kind").getAsString()) && id.equals(row.get("id").getAsString())) return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("id", id); item.addProperty("kind", kind);
        item.addProperty("name", name == null ? "" : name);
        item.addProperty("parent_id", parentId == null ? "" : parentId);
        item.addProperty("owner_id", ownerId == null ? "" : ownerId);
        target.add(item);
    }

    /** Resolve the planet scope from the canonical polity/band that emitted an emergence fact. */
    private static List<String> emergencePlanetScope(JsonObject state, String subjectId) {
        if (state == null || subjectId == null || subjectId.isBlank()) return List.of();
        Set<String> scopes = new LinkedHashSet<>();
        if (state.has("polities") && state.get("polities").isJsonArray()) {
            for (var item : state.getAsJsonArray("polities")) if (item.isJsonObject()) {
                JsonObject polity = item.getAsJsonObject();
                boolean matches = subjectId.equals(polity.get("id").getAsString());
                if (!matches && polity.has("settlement_ids") && polity.get("settlement_ids").isJsonArray())
                    for (var band : polity.getAsJsonArray("settlement_ids"))
                        if (band.isJsonPrimitive() && subjectId.equals(band.getAsString())) { matches = true; break; }
                if (!matches) continue;
                String mapId = polity.has("map_id") ? polity.get("map_id").getAsString() : "";
                if (!mapId.isBlank()) scopes.add("planet:" + mapId);
                String dynastyId = polity.has("dynasty_id") ? polity.get("dynasty_id").getAsString() : "";
                if (!dynastyId.isBlank()) scopes.add("dynasty:" + dynastyId);
                String rulerId = polity.has("ruler_global_id") && !polity.get("ruler_global_id").isJsonNull()
                        ? polity.get("ruler_global_id").getAsString() : "";
                if (!rulerId.isBlank()) scopes.add("character:" + rulerId);
                if (state.has("bands") && state.get("bands").isJsonArray()) {
                    for (var bandItem : state.getAsJsonArray("bands")) if (bandItem.isJsonObject()) {
                        JsonObject band = bandItem.getAsJsonObject();
                        if (!subjectId.equals(band.get("id").getAsString())) continue;
                        String settlementId = settlementIdForBand(band);
                        if (settlementId != null && !settlementId.isBlank())
                            scopes.add("settlement:" + settlementId);
                    }
                }
                return List.copyOf(scopes);
            }
        }
        if (state.has("bands") && state.get("bands").isJsonArray()) {
            for (var item : state.getAsJsonArray("bands")) if (item.isJsonObject()) {
                JsonObject band = item.getAsJsonObject();
                if (!subjectId.equals(band.get("id").getAsString())) continue;
                String mapId = band.has("map_id") ? band.get("map_id").getAsString() : "";
                if (!mapId.isBlank()) scopes.add("planet:" + mapId);
                String settlementId = settlementIdForBand(band);
                if (settlementId != null && !settlementId.isBlank())
                    scopes.add("settlement:" + settlementId);
                return List.copyOf(scopes);
            }
        }
        return List.of();
    }

    /**
     * Materializes canonical people incrementally. The native frame remains pending until every
     * resident and Origins record is reconciled; a large cohort can never monopolize one server tick.
     */
    private static boolean syncEmergentPeople(MinecraftServer server, JsonObject state, int year) {
        if (server == null || state == null || !state.has("bands") || !state.get("bands").isJsonArray()) return true;
        if (emergentPeopleProgress == null || emergentPeopleProgress.state != state || emergentPeopleProgress.year != year)
            emergentPeopleProgress = new EmergentPeopleProgress(state, year);

        int budget = PEOPLE_MATERIALIZATION_BUDGET_PER_TICK;
        boolean complete = true;
        boolean settlementBindingsChanged = false;
        Map<String, String> realmByBand = new HashMap<>();
        Map<String, JsonObject> polityById = new HashMap<>();
        if (state.has("polities") && state.get("polities").isJsonArray()) {
            for (var item : state.getAsJsonArray("polities")) if (item.isJsonObject()) {
                JsonObject polity = item.getAsJsonObject();
                String polityId = polity.get("id").getAsString();
                polityById.put(polityId, polity);
                for (var band : polity.getAsJsonArray("settlement_ids"))
                    realmByBand.put(band.getAsString(), "emergent-" + polityId);
            }
        }
        for (EmergenceSource source : emergenceSources()) {
            CharacterRegistry registry = source.registry;
            if (registry == null) { complete = false; continue; }
            // Establish a provisional, deterministic racial composition before the
            // first Origins assignment. Previously every new resident used global race
            // weights because the realm was only registered after people materialized.
            for (var entry : polityById.entrySet()) {
                JsonObject polity = entry.getValue();
                if (!source.id.equals(polity.get("map_id").getAsString())) continue;
                String realmId = "emergent-" + entry.getKey();
                if (RealmsMod.realm(realmId) != null) continue;
                Map<String, Double> raceProfile = emergentFoundingRaceProfile(source.seed, entry.getKey());
                RealmsMod.registerEmergentRealm(realmId,
                        polity.get("name").getAsString(), source.id, "", polity.get("population").getAsLong(),
                        polity.get("stability").getAsInt(), polity.getAsJsonArray("settlement_ids").size(),
                        List.of(), "", raceProfile);
            }
            // Every dimension/map owns an independent materialization cursor and
            // population index, even when several vanilla dimensions share the same
            // server-level registry implementation. A single "__vanilla_world__"
            // cursor silently routed later dimensions through the first dimension's index.
            String progressPartition = source.id;
            EmergentMapProgress sourceProgress = emergentPeopleProgress.maps.computeIfAbsent(
                    progressPartition, ignored -> new EmergentMapProgress(registry));
            boolean hasSettledBands = false;
            for (var element : state.getAsJsonArray("bands")) {
                if (!element.isJsonObject()) continue;
                JsonObject band = element.getAsJsonObject();
                if (!band.has("map_id") || !source.id.equals(band.get("map_id").getAsString())) continue;
                String bandId = band.get("id").getAsString();
                int target = (int) Math.max(0L, Math.min(100_000L, band.get("population").getAsLong()));
                EmergentBandProgress bandProgress = sourceProgress.bands.computeIfAbsent(bandId,
                        ignored -> new EmergentBandProgress(sourceProgress.index.livingResidents(bandId), year));
                List<dev.worldforge.people.Character> residents = bandProgress.residents;

                while (bandProgress.originCursor < residents.size() && budget > 0) {
                    int ordinal = bandProgress.originCursor++;
                    ensureEmergentOrigin(residents.get(ordinal), source, year, ordinal, realmByBand.getOrDefault(bandId, ""));
                    budget--;
                }
                while (residents.size() < target && budget > 0) {
                    int ordinal = residents.size();
                    dev.worldforge.people.Character created = createEmergentResident(
                            registry, source, bandId, year, ordinal, bandProgress.parents);
                    if (created == null) break;
                    sourceProgress.index.add(created);
                    ensureEmergentOrigin(created, source, year, ordinal, realmByBand.getOrDefault(bandId, ""));
                    bandProgress.originCursor = residents.size();
                    budget--;
                }

                boolean bandComplete = residents.size() >= target
                        && bandProgress.originCursor >= residents.size();
                band.addProperty("materialized_population", residents.size());
                band.addProperty("population_target_gap", target - residents.size());
                if (!bandComplete) {
                    complete = false;
                    if (budget == 0) break;
                    continue;
                }
                if (!band.get("settled").getAsBoolean()) {
                    bandProgress.settlementReconciled = true;
                    continue;
                }
                if (bandProgress.settlementReconciled) continue;

                String settlementId = settlementIdForBand(band);
                try {
                    int[] position = source.blockPosition(band.get("x").getAsInt(), band.get("z").getAsInt());
                    int x = position[0], z = position[1];
                    String display = "";
                    String settlementDimension = source.settlementDimension(server);
                    if (settlementDimension == null || settlementDimension.isBlank())
                        throw new IllegalStateException("No loaded physical dimension for emergence source " + source.id);
                    Colony colony = SettleMod.colony(settlementId);
                    if (colony == null) {
                        // Reuse an authored image-map settlement anchor when the emergent
                        // population site falls nearby. Vanilla worlds instead adopt real
                        // generated villages. Both paths preserve one canonical Colony ID.
                        if (source.map != null) {
                            int anchorRadius = (int) Math.max(64, Math.min(512,
                                    Math.round(96.0 * Math.max(0.001, source.blocksPerPixel))));
                            colony = SettleMod.nearbyBlueprintSettlement(settlementDimension,
                                    source.map.name, x, z, anchorRadius, emergenceBoundSettlementIds());
                        } else {
                            colony = SettleMod.nearbyVanillaVillage(settlementDimension, x, z, 64,
                                    emergenceBoundSettlementIds());
                        }
                        if (colony != null) {
                            settlementId = colony.s.id;
                            bindPhysicalSettlement(bandId, settlementId);
                            settlementBindingsChanged = true;
                        } else {
                            settlementId = "emg_" + bandId;
                            display = emergentSettlementName(source, bandId, residents);
                            colony = SettleMod.foundEmergentInMemory(server,
                                    settlementDimension, settlementId, display, x, z, "worldsim:settlement:" + bandId);
                            bindPhysicalSettlement(bandId, settlementId);
                            settlementBindingsChanged = true;
                        }
                    }
                    if (colony != null && colony.sourceMapId.isBlank() && colony.s != null) {
                        String currentSettlementName = colony.s.name == null ? "" : colony.s.name.trim();
                        String normalizedSettlementName = currentSettlementName.replaceAll("\\s+", " ")
                                .toLowerCase(Locale.ROOT);
                        Colony adoptedColony = colony;
                        boolean duplicateSettlementName = SettleMod.COLONIES.values().stream()
                                .anyMatch(other -> other != null && other != adoptedColony && other.s != null
                                        && other.s.name != null
                                        && other.s.name.trim().replaceAll("\\s+", " ")
                                                .equalsIgnoreCase(normalizedSettlementName));
                        if (currentSettlementName.isBlank() || currentSettlementName.equals(colony.s.id)
                                || duplicateSettlementName) {
                            colony.s.name = emergentSettlementName(source, bandId, residents);
                        }
                    }
                    List<String> residentIds = residents.stream().filter(person -> person.alive)
                            .map(person -> person.globalId).toList();
                    if (!SettleMod.replaceCanonicalResidentsInMemory(colony.s.id, residentIds))
                        throw new IllegalStateException("Canonical residents could not be attached to settlement " + settlementId);
                    colony.s.setCanonicalPopulation(residentIds.size());
                    // Classify only after canonical residents are attached. This avoids
                    // zero-population camp placeholders and uses local terrain plus the
                    // actual race mix of this settlement's residents.
                    colony.s.types = SettleMod.TYPES;
                    colony.s.refreshType(residentIds.size(), emergentSettlementTerrain(source, band),
                            emergentRaceShares(residents.stream().filter(person -> person.alive).toList()));
                    for (Worker worker : new ArrayList<>(colony.s.workers)) {
                        if (worker.person == null || worker.person.isBlank() || residentIds.contains(worker.person)) continue;
                        dev.worldforge.people.Character former = PeopleMod.authority().getGlobal(worker.person);
                        if (former == null) continue;
                        String reason = !former.alive ? "death:emergence" : "transfer:emergence";
                        colony.s.removeWorkerByPerson(worker.person, reason);
                    }
                    for (String residentId : residentIds) {
                        dev.worldforge.people.Character resident = sourceProgress.index.byGlobalId(residentId);
                        if (resident == null) continue;
                        boolean workingAge = PopulationSimulation.age(resident, year) >= 18
                                && PopulationSimulation.canWork(resident, year);
                        if (!workingAge) {
                            colony.s.removeWorkerByPerson(residentId, "transfer:underage");
                            continue;
                        }
                        if (!SettleMod.attachExistingCharacter(colony.s.id, residentId))
                            throw new IllegalStateException("Canonical resident " + residentId
                                    + " could not be attached to settlement " + settlementId);
                    }
                    bandProgress.settlementReconciled = true;
                    hasSettledBands = true;
                } catch (IllegalStateException deferred) {
                    LOGGER.warn("wf_worldsim: settlement materialization deferred for band {}: {}", bandId, deferred.getMessage());
                    complete = false;
                }
                if (budget == 0) break;
            }

            List<dev.worldforge.people.Character> emergentResidents = sourceProgress.index.emergentResidents();
            boolean peopleDurable = emergentResidents.isEmpty()
                    || PeopleMod.persistCharacter(emergentResidents.get(0), server);
            if (!peopleDurable) {
                LOGGER.error("wf_worldsim: canonical residents for source {} could not be durably saved; materialization remains pending", source.id);
                return false;
            }
            if (hasSettledBands && !SettleMod.saveDurably(server)) {
                LOGGER.error("wf_worldsim: settlement/Worker batch for source {} could not be durably saved; materialization remains pending", source.id);
                return false;
            }
            if (settlementBindingsChanged && !saveState(server)) {
                LOGGER.error("wf_worldsim: physical settlement identity bindings for source {} could not be durably saved", source.id);
                return false;
            }
            if (budget == 0) complete = false;
        }
        if (complete) emergentPeopleProgress = null;
        return complete;
    }

    private static String settlementBindingKey(String bandId) { return "settlement:" + bandId; }

    private static String settlementIdForBand(JsonObject band) {
        String bandId = band != null && band.has("id") ? band.get("id").getAsString() : "unknown";
        String key = settlementBindingKey(bandId);
        if (emergenceBindings.has(key) && emergenceBindings.get(key).isJsonPrimitive()) {
            String bound = emergenceBindings.get(key).getAsString();
            if (!bound.isBlank()) return bound;
        }
        return "emg_" + bandId;
    }

    private static Set<String> emergenceBoundSettlementIds() {
        Set<String> result = new HashSet<>();
        for (var entry : emergenceBindings.entrySet()) {
            if (!entry.getKey().startsWith("settlement:") || !entry.getValue().isJsonPrimitive()) continue;
            String id = entry.getValue().getAsString();
            if (!id.isBlank()) result.add(id);
        }
        return result;
    }

    private static void bindPhysicalSettlement(String bandId, String settlementId) {
        if (bandId == null || bandId.isBlank() || settlementId == null || settlementId.isBlank()) return;
        emergenceBindings.addProperty(settlementBindingKey(bandId), settlementId);
    }

    private static String settlementIdForBand(JsonArray bands, String bandId) {
        if (bands != null) for (var item : bands) {
            if (!item.isJsonObject()) continue;
            JsonObject band = item.getAsJsonObject();
            if (bandId.equals(band.has("id") ? band.get("id").getAsString() : "")) return settlementIdForBand(band);
        }
        return "emg_" + bandId;
    }

    private static String emergentBiomeSummary(Set<String> bandIds, JsonArray bands, EmergenceSource source) {
        if (source.map != null) return emergentBiomeSummary(bandIds, bands, source.map);
        Map<String, Integer> counts = new TreeMap<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (!source.id.equals(band.get("map_id").getAsString()) || !bandIds.contains(band.get("id").getAsString())) continue;
            int[] worldPosition = source.blockPosition(
                    band.get("x").getAsInt(), band.get("z").getAsInt());
            String biome = VanillaWorldGeography.biomeAtWorldPosition(source.id,
                    worldPosition[0], worldPosition[1]);
            counts.merge(biome.isBlank() ? "unknown" : biome, 1, Integer::sum);
        }
        return counts.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** Keep a comparable number of CK3-scale territorial tiers as the observed map grows. */
    private static int emergentProvinceScale(EmergenceSource source) {
        int span = Math.max(128, Math.min(source.width, source.height));
        int quarter = Math.max(128, span / 4);
        return Math.max(128, Math.min(512, Integer.highestOneBit(quarter)));
    }

    private static int emergentProvinceCount(Set<String> bandIds, JsonArray bands, EmergenceSource source) {
        if (bandIds == null || bandIds.isEmpty() || bands == null) return 0;
        Set<String> cells = new HashSet<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (!source.id.equals(band.get("map_id").getAsString()) || !bandIds.contains(band.get("id").getAsString())) continue;
            int[] p = source.blockPosition(band.get("x").getAsInt(), band.get("z").getAsInt());
            int provinceBlocks = emergentProvinceScale(source);
            cells.add(Math.floorDiv(p[0], provinceBlocks) + ":" + Math.floorDiv(p[1], provinceBlocks));
        }
        return cells.size();
    }

    private static int emergentRegionCount(Set<String> bandIds, JsonArray bands, EmergenceSource source) {
        if (bandIds == null || bandIds.isEmpty() || bands == null) return 0;
        Set<String> cells = new HashSet<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (!source.id.equals(band.get("map_id").getAsString()) || !bandIds.contains(band.get("id").getAsString())) continue;
            int[] p = source.blockPosition(band.get("x").getAsInt(), band.get("z").getAsInt());
            int regionBlocks = emergentProvinceScale(source) * 2;
            cells.add(Math.floorDiv(p[0], regionBlocks) + ":" + Math.floorDiv(p[1], regionBlocks));
        }
        return cells.size();
    }

    /** Neighbours come from physical proximity, never imported political borders. */
    private static Set<String> emergentNeighbors(JsonObject polity, JsonArray polities, JsonArray bands, EmergenceSource source) {
        Set<String> own = new HashSet<>();
        if (polity.has("settlement_ids") && polity.get("settlement_ids").isJsonArray())
            for (var id : polity.getAsJsonArray("settlement_ids")) own.add(id.getAsString());
        Map<String, JsonObject> samples = new HashMap<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (source.id.equals(band.get("map_id").getAsString())) samples.put(band.get("id").getAsString(), band);
        }
        double radiusBlocks = source.map == null ? 64.0
                : Math.max(64.0, Math.min(source.width, source.height) * source.blocksPerPixel / 4.0);
        double maxDistanceSq = radiusBlocks * radiusBlocks;
        Set<String> neighbors = new TreeSet<>();
        for (var item : polities) {
            if (!item.isJsonObject()) continue;
            JsonObject other = item.getAsJsonObject();
            String otherId = other.get("id").getAsString();
            if (otherId.equals(polity.get("id").getAsString()) || !source.id.equals(other.get("map_id").getAsString())) continue;
            boolean adjacent = false;
            for (String firstId : own) {
                JsonObject first = samples.get(firstId);
                if (first == null) continue;
                int[] a = source.blockPosition(first.get("x").getAsInt(), first.get("z").getAsInt());
                for (var secondId : other.getAsJsonArray("settlement_ids")) {
                    JsonObject second = samples.get(secondId.getAsString());
                    if (second == null) continue;
                    int[] b = source.blockPosition(second.get("x").getAsInt(), second.get("z").getAsInt());
                    double dx = a[0] - b[0], dz = a[1] - b[1];
                    if (dx * dx + dz * dz <= maxDistanceSq) { adjacent = true; break; }
                }
                if (adjacent) break;
            }
            if (adjacent) neighbors.add("emergent-" + otherId);
        }
        return neighbors;
    }

    private static String emergentBiomeSummary(Set<String> bandIds, JsonArray bands, MapData map) {
        Map<String, Integer> counts = new TreeMap<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (!map.name.equals(band.get("map_id").getAsString()) || !bandIds.contains(band.get("id").getAsString())) continue;
            int x = Math.max(0, Math.min(map.w - 1, band.get("x").getAsInt()));
            int z = Math.max(0, Math.min(map.h - 1, band.get("z").getAsInt()));
            String biome = map.biomeKey(map.biomeAtPx(x, z));
            counts.merge(biome == null || biome.isBlank() ? "unknown" : biome, 1, Integer::sum);
        }
        return counts.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** Unique settlement naming uses only race/culture pools supplied by wf-people datapacks. */
    private static String emergentSettlementName(EmergenceSource source, String bandId,
            List<dev.worldforge.people.Character> residents) {
        Map<String, Long> races = residents.stream()
                .map(person -> String.valueOf(person.props.getOrDefault("origin.race", "")))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> cultures = residents.stream()
                .map(person -> person.culture == null ? "" : person.culture)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                        java.util.stream.Collectors.counting()));
        String race = races.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse("");
        String culture = cultures.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse("");
        Set<String> reserved = SettleMod.COLONIES.values().stream()
                .filter(colony -> colony != null && colony.s != null && colony.s.name != null && !colony.s.name.isBlank())
                .map(colony -> colony.s.name)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        return PeopleMod.NAMES.generateUnique(race, culture, "settlements",
                new Random(source.seed ^ bandId.hashCode()), reserved);
    }

    /** Local terrain identity for settlement founding; do not copy a realm-wide biome mix to every town. */
    private static Map<String, Double> emergentSettlementTerrain(EmergenceSource source, JsonObject band) {
        if (source == null || band == null || !band.has("x") || !band.has("z")) return Map.of();
        String biome = "";
        int x = band.get("x").getAsInt(), z = band.get("z").getAsInt();
        try {
            if (source.map != null) {
                biome = source.map.biomeKey(source.map.biomeAtPx(x, z));
            } else {
                int[] position = source.blockPosition(x, z);
                biome = VanillaWorldGeography.biomeAtWorldPosition(source.id, position[0], position[1]);
            }
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
        String key = biome == null ? "" : biome.toLowerCase(Locale.ROOT);
        if (key.contains("beach") || key.contains("coast")) return Map.of("coast", 1.0);
        if (key.contains("mountain") || key.contains("peak") || key.contains("highlands")) return Map.of("mountains", 1.0);
        if (key.contains("desert") || key.contains("badlands") || key.contains("wasteland")) return Map.of("desert", 1.0);
        if (key.contains("swamp") || key.contains("marsh")) return Map.of("swamp", 1.0);
        if (key.contains("snow") || key.contains("frozen") || key.contains("tundra")) return Map.of("tundra", 1.0);
        if (key.contains("forest") || key.contains("taiga") || key.contains("jungle") || key.contains("wood")) return Map.of("forest", 1.0);
        if (key.isBlank()) return Map.of();
        return Map.of("plains", 1.0);
    }

    /** Stable realm-level race profile used only to seed its first canonical residents. */
    private static Map<String, Double> emergentFoundingRaceProfile(long worldSeed, String polityId) {
        List<dev.worldforge.origins.OriginDefs.Race> roots = OriginsMod.DEFS.races.values().stream()
                .filter(race -> race.parent() == null || race.parent().isBlank())
                .sorted(Comparator.comparing(dev.worldforge.origins.OriginDefs.Race::id)).toList();
        if (roots.isEmpty()) return Map.of();
        int total = roots.stream().mapToInt(race -> Math.max(1, race.weight())).sum();
        Random random = new Random(worldSeed ^ ((long) polityId.hashCode() * 0x9E3779B97F4A7C15L));
        int pick = random.nextInt(total);
        var dominant = roots.get(0);
        for (var race : roots) {
            pick -= Math.max(1, race.weight());
            if (pick < 0) { dominant = race; break; }
        }
        Map<String, Double> profile = new LinkedHashMap<>();
        profile.put(dominant.id(), 0.70);
        int remainder = Math.max(1, total - Math.max(1, dominant.weight()));
        for (var race : roots) if (!race.id().equals(dominant.id()))
            profile.put(race.id(), 0.30 * Math.max(1, race.weight()) / remainder);
        return profile;
    }

    /** Race composition is sampled from the polity's canonical living Characters, not copied into a second population registry. */
    private static Map<String, Double> emergentRaceShares(
            List<dev.worldforge.people.Character> members) {
        Map<String, Double> counts = new TreeMap<>();
        for (dev.worldforge.people.Character person : members) {
            Object rawRace = person.props.get("origin.race");
            String race = rawRace == null ? "" : String.valueOf(rawRace).trim();
            if (race.isEmpty()) continue;
            // Count every subrace toward its full ancestry. Government and settlement
            // requirements target base races (elf/dwarf/orc), while names and traits
            // still retain the precise subrace on the canonical Character.
            for (var ancestor : OriginsMod.ORIGINS.definitionSnapshot().raceChain(race))
                counts.merge(ancestor.id(), 1.0, Double::sum);
        }
        double total = counts.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return Map.of();
        counts.replaceAll((race, count) -> count / total);
        return counts;
    }

    /**
     * Bind an emergent sovereign to the ordinary realm-ruler office used by Courts,
     * CourtSim, councils, succession and court events. The emergence-specific post
     * remains a provenance marker; both offices point to the same Character UUID.
     */
    private static boolean ensureEmergentCourtOffice(CharacterRegistry registry,
            dev.worldforge.people.Character ruler, String realmId, int year) {
        var role = PeopleMod.ROLES.ruler();
        if (role == null) {
            LOGGER.error("wf_worldsim: no canonical ruler role is defined; court integration deferred for {}", realmId);
            return false;
        }
        String place = dev.worldforge.people.Courts.realmPlace(realmId);
        if (!ruler.has(role.id, place)) registry.appoint(ruler.id, role.id, place, year);
        return ruler.has(role.id, place);
    }

    /** Maps the existing Realms development ladder onto the canonical Statecraft title hierarchy. */
    private static dev.worldforge.realms.Statecraft.Rank emergentTitleRank(String realmId) {
        int level = RealmsMod.REALMS.level(realmId);
        return switch (Math.max(0, Math.min(4, level))) {
            case 0 -> dev.worldforge.realms.Statecraft.Rank.BARONY;
            case 1 -> dev.worldforge.realms.Statecraft.Rank.COUNTY;
            case 2 -> dev.worldforge.realms.Statecraft.Rank.DUCHY;
            case 3 -> dev.worldforge.realms.Statecraft.Rank.KINGDOM;
            default -> dev.worldforge.realms.Statecraft.Rank.EMPIRE;
        };
    }

    private static final class EmergentTerritorySite {
        final String bandId, settlementId, name;
        final int x, z;
        EmergentTerritorySite(String bandId, String settlementId, String name, int x, int z) {
            this.bandId = bandId; this.settlementId = settlementId; this.name = name;
            this.x = x; this.z = z;
        }
    }

    private static String emergentTerritoryTitleId(String tier, String polityId, String key) {
        String identity = polityId + "|" + tier + "|" + key;
        String hash = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return "emg-" + tier + "-" + hash;
    }

    private static dev.worldforge.realms.Statecraft.Title ensureEmergentTerritoryTitle(
            String id, String realmId, dev.worldforge.realms.Statecraft.Rank rank, String name) {
        var title = RealmsMod.POLITICS.title(id);
        if (title == null) {
            title = RealmsMod.POLITICS.createTitle(id, realmId, rank, name);
        } else {
            if (!realmId.equals(title.realmId))
                throw new IllegalStateException("territorial title " + id + " is already owned by " + title.realmId);
            title.name = name;
            if (title.rank != rank && !RealmsMod.POLITICS.setTitleRank(id, rank))
                LOGGER.warn("wf_worldsim: deferred territorial title rank update for {}: {}", id,
                        RealmsMod.POLITICS.lastError);
        }
        return title;
    }

    /** Allocates stable, world-unique territorial names from wf-people datapacks. */
    private static String uniqueTerritorialName(String titleId, String category, String bandId,
            EmergentPopulationIndex populationIndex, String identity) {
        var existing = RealmsMod.POLITICS.title(titleId);
        Set<String> reserved = new HashSet<>();
        RealmsMod.POLITICS.titles().stream()
                .filter(title -> title != null && !title.id.equals(titleId))
                .map(title -> title.name).filter(name -> name != null && !name.isBlank())
                .forEach(reserved::add);
        SettleMod.COLONIES.values().stream()
                .filter(colony -> colony != null && colony.s != null && colony.s.name != null
                        && !colony.s.name.isBlank())
                .map(colony -> colony.s.name).forEach(reserved::add);
        if (existing != null && existing.name != null && !existing.name.isBlank()) {
            String normalized = existing.name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            boolean available = reserved.stream().noneMatch(value -> value.trim()
                    .replaceAll("\\s+", " ").equalsIgnoreCase(normalized));
            if (available) return existing.name;
        }
        List<dev.worldforge.people.Character> residents = bandId == null || bandId.isBlank()
                ? List.of() : populationIndex.livingResidents(bandId);
        Map<String, Long> races = residents.stream()
                .map(person -> String.valueOf(person.props.getOrDefault("origin.race", "")))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> cultures = residents.stream()
                .map(person -> person.culture == null ? "" : person.culture)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new,
                        java.util.stream.Collectors.counting()));
        String race = races.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse("");
        String culture = cultures.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey).orElse("");
        return PeopleMod.NAMES.generateUnique(race, culture, category,
                new Random(Objects.hash(identity, titleId, category)), reserved);
    }

    private static void linkEmergentTerritoryTitle(String childId, String liegeId) {
        var child = RealmsMod.POLITICS.title(childId);
        if (child == null || childId.equals(liegeId)) return;
        if (!liegeId.equals(child.liegeTitleId)
                && !RealmsMod.POLITICS.setTitleLiege(childId, liegeId))
            LOGGER.warn("wf_worldsim: could not link territorial title {} -> {}: {}",
                    childId, liegeId, RealmsMod.POLITICS.lastError);
    }

    private static void grantEmergentTerritoryHolder(String titleId, String bandId,
            EmergentPopulationIndex populationIndex, int year, String fallbackHolder) {
        var existing = RealmsMod.POLITICS.title(titleId);
        if (existing != null && existing.holderCharacterId != null
                && !existing.holderCharacterId.isBlank()) {
            var currentHolder = PeopleMod.authority().getGlobal(existing.holderCharacterId);
            if (currentHolder != null && currentHolder.alive) return;
        }
        Set<String> alreadyHoldingRealmTitles = RealmsMod.POLITICS.titles().stream()
                .filter(title -> title != null && existing != null
                        && existing.realmId.equals(title.realmId) && !title.id.equals(titleId))
                .map(title -> title.holderCharacterId)
                .filter(holder -> holder != null && !holder.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        var residents = populationIndex.livingResidents(bandId).stream()
                .filter(person -> person.alive && PopulationSimulation.age(person, year) >= 18)
                // Prefer a distinct local notable for each county/duchy/kingdom.
                // Reuse an existing holder only when the local adult population is exhausted.
                .sorted(Comparator.comparing((dev.worldforge.people.Character person) ->
                                alreadyHoldingRealmTitles.contains(person.globalId))
                        .thenComparingInt(person -> person.birthYear)
                        .thenComparing(person -> person.globalId)).toList();
        String holder = residents.isEmpty() ? fallbackHolder : residents.get(0).globalId;
        if (holder != null && !holder.isBlank()
                && !RealmsMod.POLITICS.grantTitle(titleId, holder))
            LOGGER.warn("wf_worldsim: could not grant territorial title {} to Character {}: {}",
                    titleId, holder, RealmsMod.POLITICS.lastError);
    }

    /**
     * Materializes the settlement-derived barony/county/duchy/kingdom hierarchy
     * in the existing Statecraft registry. Coordinates are taken from real
     * geography and physical settlements; IDs are stable and reconciliation is
     * idempotent across every simulation tick and save reload.
     */
    private static Set<String> reconcileEmergentTerritorialTitles(
            String polityId, String realmId, String crownTitleId, String polityName,
            Set<String> bandIds, JsonArray bands, EmergenceSource source,
            EmergentPopulationIndex populationIndex, int year, String rulerGlobalId) {
        Set<String> titleIds = new TreeSet<>();
        titleIds.add(crownTitleId);
        Map<String, JsonObject> bandById = new HashMap<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (source.id.equals(band.get("map_id").getAsString()))
                bandById.put(band.get("id").getAsString(), band);
        }
        List<EmergentTerritorySite> sites = new ArrayList<>();
        for (String bandId : new TreeSet<>(bandIds)) {
            JsonObject band = bandById.get(bandId);
            if (band == null) continue;
            String settlementId = settlementIdForBand(bands, bandId);
            Colony colony = SettleMod.colony(settlementId);
            if (colony == null || colony.s == null) continue;
            int[] position = source.blockPosition(band.get("x").getAsInt(), band.get("z").getAsInt());
            String name = colony.s.name == null || colony.s.name.isBlank()
                    ? settlementId : colony.s.name.trim();
            sites.add(new EmergentTerritorySite(bandId, settlementId, name, position[0], position[1]));
        }
        // Image-derived maps already contain the canonical province/region/state raster.
        // Import and bind that hierarchy below instead of creating a duplicate grid of titles.
        if (source.map != null && source.map.titles != null) return titleIds;
        if (sites.size() <= 1) {
            var crown = RealmsMod.POLITICS.title(crownTitleId);
            if (crown != null) {
                var base = emergentTitleRank(realmId);
                if (crown.rank.ordinal() < base.ordinal())
                    RealmsMod.POLITICS.setTitleRank(crownTitleId, base);
            }
            return titleIds;
        }

        int provinceBlocks = emergentProvinceScale(source);
        int regionBlocks = provinceBlocks * 2;
        int kingdomBlocks = regionBlocks * 4;
        Map<String, List<EmergentTerritorySite>> provinceGroups = new TreeMap<>();
        for (EmergentTerritorySite site : sites) {
            String provinceKey = Math.floorDiv(site.x, provinceBlocks) + ":" + Math.floorDiv(site.z, provinceBlocks);
            provinceGroups.computeIfAbsent(provinceKey, ignored -> new ArrayList<>()).add(site);
        }

        Map<String, EmergentTerritorySite> countyByProvince = new TreeMap<>();
        for (var entry : provinceGroups.entrySet()) {
            List<EmergentTerritorySite> members = entry.getValue();
            members.sort(Comparator.comparing(site -> site.name));
            EmergentTerritorySite capital = members.get(0);
            String countyId = emergentTerritoryTitleId("county", polityId, entry.getKey());
            String countyName = uniqueTerritorialName(countyId, "provinces", capital.bandId,
                    populationIndex, polityId);
            ensureEmergentTerritoryTitle(countyId, realmId,
                    dev.worldforge.realms.Statecraft.Rank.COUNTY, countyName);
            grantEmergentTerritoryHolder(countyId, capital.bandId, populationIndex, year, rulerGlobalId);
            countyByProvince.put(entry.getKey(), capital);
            titleIds.add(countyId);
            for (EmergentTerritorySite site : members) {
                String baronyId = emergentTerritoryTitleId("barony", polityId, site.bandId);
                ensureEmergentTerritoryTitle(baronyId, realmId,
                        dev.worldforge.realms.Statecraft.Rank.BARONY, site.name);
                grantEmergentTerritoryHolder(baronyId, site.bandId, populationIndex, year, rulerGlobalId);
                linkEmergentTerritoryTitle(baronyId, countyId);
                titleIds.add(baronyId);
            }
        }

        var crown = RealmsMod.POLITICS.title(crownTitleId);
        if (crown == null) return titleIds;
        if (provinceGroups.size() == 1) {
            var required = dev.worldforge.realms.Statecraft.Rank.DUCHY;
            var base = emergentTitleRank(realmId);
            RealmsMod.POLITICS.setTitleRank(crownTitleId,
                    base.ordinal() > required.ordinal() ? base : required);
            linkEmergentTerritoryTitle(emergentTerritoryTitleId("county", polityId,
                    provinceGroups.keySet().iterator().next()), crownTitleId);
            return titleIds;
        }

        Map<String, List<String>> countiesByRegion = new TreeMap<>();
        for (String provinceKey : provinceGroups.keySet()) {
            String[] coordinates = provinceKey.split(":", 2);
            int provinceX = Integer.parseInt(coordinates[0]);
            int provinceZ = Integer.parseInt(coordinates[1]);
            int centerX = provinceX * provinceBlocks + provinceBlocks / 2;
            int centerZ = provinceZ * provinceBlocks + provinceBlocks / 2;
            String regionKey = Math.floorDiv(centerX, regionBlocks) + ":" + Math.floorDiv(centerZ, regionBlocks);
            countiesByRegion.computeIfAbsent(regionKey, ignored -> new ArrayList<>())
                    .add(emergentTerritoryTitleId("county", polityId, provinceKey));
        }

        Map<String, List<String>> duchiesByKingdom = new TreeMap<>();
        Map<String, String> duchyCapitalBandById = new HashMap<>();
        for (var entry : countiesByRegion.entrySet()) {
            String duchyId = emergentTerritoryTitleId("duchy", polityId, entry.getKey());
            EmergentTerritorySite capital = countyByProvince.get(
                    provinceGroups.keySet().stream().filter(key -> {
                        String[] coordinates = key.split(":", 2);
                        int x = Integer.parseInt(coordinates[0]) * provinceBlocks + provinceBlocks / 2;
                        int z = Integer.parseInt(coordinates[1]) * provinceBlocks + provinceBlocks / 2;
                        return entry.getKey().equals(Math.floorDiv(x, regionBlocks) + ":"
                                + Math.floorDiv(z, regionBlocks));
                    }).findFirst().orElse(provinceGroups.keySet().iterator().next()));
            String duchyName = uniqueTerritorialName(duchyId, "regions", capital.bandId,
                    populationIndex, polityId);
            ensureEmergentTerritoryTitle(duchyId, realmId,
                    dev.worldforge.realms.Statecraft.Rank.DUCHY, duchyName);
            grantEmergentTerritoryHolder(duchyId, capital.bandId, populationIndex, year, rulerGlobalId);
            duchyCapitalBandById.put(duchyId, capital.bandId);
            for (String countyId : entry.getValue()) linkEmergentTerritoryTitle(countyId, duchyId);
            titleIds.add(duchyId);
            String[] region = entry.getKey().split(":", 2);
            int regionX = Integer.parseInt(region[0]), regionZ = Integer.parseInt(region[1]);
            int centerX = regionX * regionBlocks + regionBlocks / 2;
            int centerZ = regionZ * regionBlocks + regionBlocks / 2;
            String kingdomKey = Math.floorDiv(centerX, kingdomBlocks) + ":" + Math.floorDiv(centerZ, kingdomBlocks);
            duchiesByKingdom.computeIfAbsent(kingdomKey, ignored -> new ArrayList<>()).add(duchyId);
        }

        if (countiesByRegion.size() == 1) {
            var required = dev.worldforge.realms.Statecraft.Rank.KINGDOM;
            var base = emergentTitleRank(realmId);
            RealmsMod.POLITICS.setTitleRank(crownTitleId,
                    base.ordinal() > required.ordinal() ? base : required);
            linkEmergentTerritoryTitle(duchiesByKingdom.values().iterator().next().get(0), crownTitleId);
            return titleIds;
        }

        // More than one duchy requires an intermediate kingdom tier, even when
        // every duchy belongs to the same macro-region. Never leave sibling
        // duchies unattached to the sovereign title.
        for (var entry : duchiesByKingdom.entrySet()) {
            String kingdomId = emergentTerritoryTitleId("kingdom", polityId, entry.getKey());
            String kingdomBand = entry.getValue().stream()
                    .map(duchyCapitalBandById::get).filter(Objects::nonNull).findFirst().orElse("");
            String kingdomName = uniqueTerritorialName(kingdomId, "realms", kingdomBand,
                    populationIndex, polityId);
            ensureEmergentTerritoryTitle(kingdomId, realmId,
                    dev.worldforge.realms.Statecraft.Rank.KINGDOM, kingdomName);
            if (!kingdomBand.isBlank())
                grantEmergentTerritoryHolder(kingdomId, kingdomBand, populationIndex, year, rulerGlobalId);
            for (String duchyId : entry.getValue()) linkEmergentTerritoryTitle(duchyId, kingdomId);
            titleIds.add(kingdomId);
        }
        var required = dev.worldforge.realms.Statecraft.Rank.EMPIRE;
        var base = emergentTitleRank(realmId);
        RealmsMod.POLITICS.setTitleRank(crownTitleId,
                base.ordinal() > required.ordinal() ? base : required);
        for (String kingdomId : titleIds) {
            if (kingdomId.startsWith("emg-kingdom-")) linkEmergentTerritoryTitle(kingdomId, crownTitleId);
        }
        return titleIds;
    }

    /** A deterministic culture/race-aware name is stored in the canonical emergence state once. */
    private static String emergentRealmName(String polityId, List<dev.worldforge.people.Character> members,
            Set<String> reservedNames) {
        if (members == null || members.isEmpty())
            throw new IllegalStateException("Cannot name a polity without canonical population: " + polityId);
        Map<String, Long> races = members.stream().map(person -> String.valueOf(person.props.getOrDefault("origin.race", "")))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
        Map<String, Long> cultures = members.stream().map(person -> person.culture == null ? "" : person.culture)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
        String race = races.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse("");
        String culture = cultures.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse("");
        return PeopleMod.NAMES.generateUnique(race, culture, "realms",
                new Random(polityId.hashCode()), reservedNames);
    }

    /**
     * The sovereign title must always outrank its direct territorial vassals.
     * Realm development may still report its initial barony-level baseline after
     * the territory tree has already promoted the crown to duchy/kingdom/empire.
     * Never downgrade a crown title during routine reconciliation.
     */
    private static dev.worldforge.realms.Statecraft.Rank requiredEmergentCrownRank(
            String crownTitleId, dev.worldforge.realms.Statecraft.Rank baseline) {
        var current = RealmsMod.POLITICS.title(crownTitleId);
        int required = Math.max(baseline.ordinal(), current == null ? 0 : current.rank.ordinal());
        for (var title : RealmsMod.POLITICS.titles()) {
            if (title == null || !crownTitleId.equals(title.liegeTitleId)) continue;
            required = Math.max(required, Math.min(
                    dev.worldforge.realms.Statecraft.Rank.values().length - 1,
                    title.rank.ordinal() + 1));
        }
        return dev.worldforge.realms.Statecraft.Rank.values()[required];
    }

    /** Binds emergent polities to canonical rulers; succession follows actual Character identity. */
    private static void syncEmergentPolities(MinecraftServer server, JsonObject state, int year) {
        if (server == null || state == null || !state.has("polities") || !state.get("polities").isJsonArray()) return;
        Set<String> activeRealmIds = new HashSet<>();
        for (var element : state.getAsJsonArray("polities")) if (element.isJsonObject())
            activeRealmIds.add("emergent-" + element.getAsJsonObject().get("id").getAsString());
        Set<String> reservedRealmNames = RealmsMod.realms().stream()
                .filter(realm -> realm != null && !Boolean.TRUE.equals(realm.props.get("emergence.virtual")))
                .map(realm -> realm.name).filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Map<CharacterRegistry, EmergentPopulationIndex> populationIndexes = new IdentityHashMap<>();
        for (EmergenceSource source : emergenceSources()) {
            var registry = source.registry;
            if (registry == null) continue;
            boolean changed = false;
            boolean settlementMapMarkersChanged = false;
            EmergentPopulationIndex populationIndex = populationIndexes.computeIfAbsent(
                    registry, ignored -> new EmergentPopulationIndex(registry.all()));
            for (var element : state.getAsJsonArray("polities")) {
                if (!element.isJsonObject()) continue;
                JsonObject polity = element.getAsJsonObject();
                if (!source.id.equals(polity.get("map_id").getAsString())) continue;
                String polityId = polity.get("id").getAsString();
                String realmId = "emergent-" + polityId;
                activeRealmIds.add(realmId);
                String dynastyId = polity.get("dynasty_id").getAsString();
                String polityName = polity.has("name") ? polity.get("name").getAsString() : polityId;
                Set<String> bandIds = new LinkedHashSet<>();
                if (polity.has("settlement_ids") && polity.get("settlement_ids").isJsonArray())
                    for (var band : polity.getAsJsonArray("settlement_ids")) bandIds.add(band.getAsString());
                List<dev.worldforge.people.Character> members = new ArrayList<>();
                for (String bandId : bandIds) members.addAll(populationIndex.livingResidents(bandId));
                Set<String> neighbors = emergentNeighbors(polity, state.getAsJsonArray("polities"),
                        state.getAsJsonArray("bands"), source);
                String biomes = emergentBiomeSummary(bandIds, state.getAsJsonArray("bands"), source);
                String currentRuler = polity.has("ruler_global_id") && !polity.get("ruler_global_id").isJsonNull()
                        ? polity.get("ruler_global_id").getAsString() : "";
                TitleData.Realm initialProjection = RealmsMod.registerEmergentRealm(realmId,
                        polity.get("name").getAsString(), source.id, currentRuler,
                        polity.get("population").getAsLong(), polity.get("stability").getAsInt(),
                        bandIds.size(), neighbors, biomes, emergentRaceShares(members));
                initialProjection.props.put("emergence.province_count", emergentProvinceCount(
                        bandIds, state.getAsJsonArray("bands"), source));
                initialProjection.props.put("emergence.region_count", emergentRegionCount(
                        bandIds, state.getAsJsonArray("bands"), source));
                int territoryProvinceScale = emergentProvinceScale(source);
                initialProjection.props.put("emergence.territory_grid", territoryProvinceScale
                        + "-block-provinces/" + (territoryProvinceScale * 2) + "-block-regions");
                if (members.isEmpty()) continue;
                String normalizedPolityName = polityName == null ? ""
                        : polityName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                boolean managedName = polity.has("name_source")
                        && "wf-people".equals(polity.get("name_source").getAsString());
                boolean duplicateManagedName = managedName && !reservedRealmNames.add(normalizedPolityName);
                if (!managedName || duplicateManagedName || normalizedPolityName.isBlank()) {
                    String generatedName = emergentRealmName(polityId, members, reservedRealmNames);
                    polityName = generatedName;
                    polity.addProperty("name", polityName);
                    polity.addProperty("name_source", "wf-people");
                    reservedRealmNames.add(polityName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
                    changed = true;
                }
                JsonObject binding = emergenceBindings.has(polityId) && emergenceBindings.get(polityId).isJsonObject()
                        ? emergenceBindings.getAsJsonObject(polityId) : new JsonObject();
                String previousRulerId = polity.has("ruler_global_id") && !polity.get("ruler_global_id").isJsonNull()
                        ? polity.get("ruler_global_id").getAsString()
                        : (binding.has("ruler_global_id") && !binding.get("ruler_global_id").isJsonNull()
                            ? binding.get("ruler_global_id").getAsString() : "");
                dev.worldforge.people.Character ruler = members.stream()
                        .filter(person -> person.globalId.equals(previousRulerId)).findFirst().orElse(null);
                boolean foundedNewDynasty = false;
                if (ruler == null) {
                    dev.worldforge.people.Character previous = populationIndex.byGlobalId(previousRulerId);
                    ruler = members.stream()
                            .filter(person -> dev.worldforge.people.PopulationSimulation.age(person, year) >= 18)
                            .sorted(Comparator
                                    .comparingInt((dev.worldforge.people.Character person) -> {
                                        if (previous == null) return 0;
                                        if (person.father.equals(previous.id) || person.mother.equals(previous.id)) return 0;
                                        if (previous.house != null && !previous.house.isBlank()
                                                && previous.house.equals(person.house)) return 1;
                                        return 2;
                                    })
                                    .thenComparingInt(person -> person.birthYear)
                                    .thenComparing(person -> person.globalId))
                            .findFirst().orElse(null);
                    if (ruler == null) continue;
                    boolean directHeir = previous != null
                            && (ruler.father.equals(previous.id) || ruler.mother.equals(previous.id));
                    boolean sameHouse = previous != null && previous.house != null
                            && !previous.house.isBlank() && previous.house.equals(ruler.house);
                    if (previous != null && !directHeir && !sameHouse) {
                        dynastyId = "house_" + UUID.nameUUIDFromBytes(
                                (polityId + "|" + year + "|" + ruler.globalId).getBytes(StandardCharsets.UTF_8))
                                .toString().replace("-", "");
                        polity.addProperty("dynasty_id", dynastyId);
                        foundedNewDynasty = true;
                    }
                    // Character.house is the unique family display name; dynastyId is stored separately.
                    // Descendants inherit the family name through CharacterRegistry.addChild().
                    ruler.props.put("emergence.polity_id", polityId);
                    ruler.props.put("emergence.dynasty_id", dynastyId);
                    ruler.props.put("emergence.polity_role", "ruler");
                    registry.appoint(ruler.id, "emergent_ruler", polityId, year);
                    if (!ensureEmergentCourtOffice(registry, ruler, realmId, year)) continue;
                    // Political succession is committed only after the canonical Character is durable.
                    if (!PeopleMod.persistCharacter(ruler, server)) {
                        LOGGER.error("wf_worldsim: canonical ruler {} for polity {} could not be persisted; succession remains pending",
                                ruler.globalId, polityId);
                        continue;
                    }
                    JsonArray rulerHistory = polity.has("ruler_history") && polity.get("ruler_history").isJsonArray()
                            ? polity.getAsJsonArray("ruler_history") : new JsonArray();
                    if (foundedNewDynasty) rulerHistory = new JsonArray();
                    boolean alreadyRecorded = false;
                    for (var recorded : rulerHistory) if (ruler.globalId.equals(recorded.getAsString())) {
                        alreadyRecorded = true;
                        break;
                    }
                    if (!alreadyRecorded) rulerHistory.add(ruler.globalId);
                    polity.add("ruler_history", rulerHistory);
                    binding.addProperty("ruler_global_id", ruler.globalId);
                    int generation = rulerHistory.size();
                    binding.addProperty("generation", generation);
                    polity.addProperty("ruler_global_id", ruler.globalId);
                    polity.addProperty("dynasty_generations", generation);
                    polity.addProperty("last_succession_year", year);
                    polity.addProperty("years_in_generation", 0);
                    if (previousRulerId.isBlank()) {
                        WorldForgeApi.postEvent("people", ruler.name + " becomes the first recorded ruler of " + polity.get("name").getAsString(),
                                List.of(), Math.max(0L, (long) year * 525600L));
                    } else if (foundedNewDynasty) {
                        WorldForgeApi.postEvent("people", ruler.name + " founds a new ruling house in " + polity.get("name").getAsString(),
                                List.of(), Math.max(0L, (long) year * 525600L));
                    } else {
                        WorldForgeApi.postEvent("people", "Succession in " + polity.get("name").getAsString()
                                        + ": " + ruler.name + " succeeds the previous ruler (dynastic generation " + generation + ")",
                                List.of(), Math.max(0L, (long) year * 525600L));
                    }
                    changed = true;
                } else {
                    ruler.props.put("emergence.polity_id", polityId);
                    ruler.props.put("emergence.dynasty_id", dynastyId);
                    ruler.props.put("emergence.polity_role", "ruler");
                    var canonicalRulerRole = PeopleMod.ROLES.ruler();
                    if (canonicalRulerRole == null) continue;
                    boolean missingEmergenceOffice = !ruler.has("emergent_ruler", polityId);
                    boolean missingCourtOffice = !ruler.has(canonicalRulerRole.id,
                            dev.worldforge.people.Courts.realmPlace(realmId));
                    if (missingEmergenceOffice) registry.appoint(ruler.id, "emergent_ruler", polityId, year);
                    if (!ensureEmergentCourtOffice(registry, ruler, realmId, year)) continue;
                    if (missingEmergenceOffice || missingCourtOffice) {
                        if (!PeopleMod.persistCharacter(ruler, server)) {
                            LOGGER.error("wf_worldsim: existing canonical ruler {} could not be persisted", ruler.globalId);
                            continue;
                        }
                    }
                }
                // Reconcile Statecraft every pass, not only when a ruler changes. This also
                // repairs a missing virtual title after a crash between Character and politics saves.
                String titleId = "emergent-title-" + polityId;
                var politicalTitle = RealmsMod.POLITICS.title(titleId);
                if (politicalTitle == null) {
                    RealmsMod.POLITICS.createTitle(titleId, realmId,
                            emergentTitleRank(realmId), polityName);
                    RealmsMod.POLITICS.grantTitle(titleId, ruler.globalId);
                    RealmsMod.POLITICS.setInheritanceLaw(realmId,
                            dev.worldforge.realms.Statecraft.InheritanceLaw.PRIMOGENITURE);
                } else {
                    var requiredRank = requiredEmergentCrownRank(titleId, emergentTitleRank(realmId));
                    if (politicalTitle.rank != requiredRank
                            && !RealmsMod.POLITICS.setTitleRank(titleId, requiredRank)) {
                        LOGGER.warn("wf_worldsim: deferred rank change for {} to {}: {}",
                                titleId, requiredRank, RealmsMod.POLITICS.lastError);
                    }
                    politicalTitle.name = polityName;
                }
                if (politicalTitle != null && !politicalTitle.holderCharacterId.equals(ruler.globalId)) {
                    if (!previousRulerId.isBlank() && politicalTitle.holderCharacterId.equals(previousRulerId)) {
                        RealmsMod.POLITICS.addClaim(titleId, ruler.globalId);
                        if (!RealmsMod.POLITICS.inheritTitle(titleId, previousRulerId, ruler.globalId))
                            RealmsMod.POLITICS.grantTitle(titleId, ruler.globalId);
                    } else {
                        RealmsMod.POLITICS.grantTitle(titleId, ruler.globalId);
                    }
                }
                var canonicalDynasty = RealmsMod.POLITICS.dynasty(dynastyId);
                if (canonicalDynasty == null || canonicalDynasty.name.equals(dynastyId)) {
                    String houseName = ruler.house;
                    if (houseName == null || houseName.isBlank())
                        throw new IllegalStateException("Ruling Character has no canonical family name: "
                                + ruler.globalId);
                    if (canonicalDynasty == null)
                        RealmsMod.POLITICS.registerDynasty(dynastyId, ruler.globalId, houseName);
                    else if (!RealmsMod.POLITICS.setDynastyName(dynastyId, houseName))
                        throw new IllegalStateException("Could not bind unique family name to dynasty "
                                + dynastyId + ": " + RealmsMod.POLITICS.lastError);
                } else {
                    RealmsMod.POLITICS.addDynastyMember(dynastyId, ruler.globalId);
                }
                for (dev.worldforge.people.Character member : members) {
                    if (member.house != null && !member.house.isBlank()
                            && member.house.equals(ruler.house))
                        RealmsMod.POLITICS.addDynastyMember(dynastyId, member.globalId);
                }
                TitleData.Realm politicalActor = RealmsMod.registerEmergentRealm(realmId,
                        polityName, source.id, ruler.globalId,
                        polity.get("population").getAsLong(), polity.get("stability").getAsInt(),
                        bandIds.size(), neighbors, biomes, emergentRaceShares(members));
                politicalActor.ruler = ruler.name + (ruler.house == null || ruler.house.isBlank() ? "" : " " + ruler.house);
                Set<String> territorialTitleIds = reconcileEmergentTerritorialTitles(
                        polityId, realmId, titleId, polityName, bandIds,
                        state.getAsJsonArray("bands"), source, populationIndex, year, ruler.globalId);
                politicalActor.props.put("emergence.statecraft_title_ids",
                        String.join(",", territorialTitleIds));
                politicalActor.props.put("emergence.title_count", territorialTitleIds.size());
                int politicalLevel = RealmsMod.REALMS.level(realmId);
                politicalActor.props.put("emergence.form_stage", RealmsMod.DEFS.levels.isEmpty()
                        ? "band" : RealmsMod.DEFS.levels.get(politicalLevel).id());
                politicalActor.props.put("emergence.form_stage_name", RealmsMod.DEFS.levels.isEmpty()
                        ? "Стоянка" : RealmsMod.DEFS.levels.get(politicalLevel).name());
                politicalActor.props.put("emergence.title_rank", emergentTitleRank(realmId).name().toLowerCase(Locale.ROOT));
                // Select a data-pack government immediately from this world's actual
                // population mix, terrain and ruler signals. This uses the same Realms
                // policy engine as later autonomous reforms; no scenario preset is involved.
                if (RealmsMod.REALMS.state(realmId).gov == null || RealmsMod.REALMS.state(realmId).gov.isBlank()) {
                    RealmsMod.REALMS.autoDevelop(realmId, source.seed ^ polityId.hashCode());
                }
                // The political realm points at the same physically materialized colonies;
                // this is a projection, not a second settlement/territory registry.
                List<String> physicalSettlementIds = new ArrayList<>();
                List<String> physicalSettlementNames = new ArrayList<>();
                List<String> physicalSettlementCenters = new ArrayList<>();
                for (String bandId : bandIds) {
                    String settlementId = settlementIdForBand(state.getAsJsonArray("bands"), bandId);
                    Colony physical = SettleMod.colony(settlementId);
                    if (physical == null) continue;
                    if (!realmId.equals(SettleMod.realmOf(physical))
                            && !SettleMod.bindEmergentRealmInMemory(settlementId, realmId)) continue;
                    if (!realmId.equals(SettleMod.realmOf(physical))) continue;
                    // Attach the existing data-pack settlement classifier to every
                    // Genesis-created colony immediately. Do not wait for the ordinary
                    // low-frequency settlement tick: the initial political projection
                    // already has canonical population, terrain and race composition.
                    physical.s.types = SettleMod.TYPES;
                    physical.s.refreshType(physical.s.population(),
                            dev.worldforge.mod.api.DemoApi.terrain(realmId),
                            dev.worldforge.mod.api.DemoApi.races(realmId));
                    physicalSettlementIds.add(settlementId);
                    physicalSettlementNames.add(physical.s.name);
                    physicalSettlementCenters.add(settlementId + "@" + physical.cx + "," + physical.cz);
                }
                Collections.sort(physicalSettlementIds);
                Collections.sort(physicalSettlementNames);
                Collections.sort(physicalSettlementCenters);
                String settlementIdsProjection = String.join(",", physicalSettlementIds);
                String settlementNamesProjection = String.join(", ", physicalSettlementNames);
                String settlementCentersProjection = String.join(";", physicalSettlementCenters);
                settlementMapMarkersChanged |= !Objects.equals(
                        String.valueOf(politicalActor.props.getOrDefault("emergence.settlement_centers", "")),
                        settlementCentersProjection);
                politicalActor.props.put("emergence.settlement_ids", settlementIdsProjection);
                politicalActor.props.put("emergence.settlement_names", settlementNamesProjection);
                politicalActor.props.put("emergence.settlement_centers", settlementCentersProjection);
                politicalActor.props.put("emergence.ruler_global_id", ruler.globalId);
                politicalActor.props.put("emergence.dynasty_id", dynastyId);
                for (String bandId : bandIds) {
                    String settlementId = settlementIdForBand(state.getAsJsonArray("bands"), bandId);
                    if (SettleMod.colony(settlementId) != null
                            && !SettleMod.bindEmergentRealmInMemory(settlementId, realmId))
                        LOGGER.error("wf_worldsim: could not bind settlement {} to emergent polity {}",
                                settlementId, polityId);
                }
                polity.addProperty("dynasty_id", dynastyId);
                if (!polity.has("ruler_global_id") || polity.get("ruler_global_id").isJsonNull())
                    polity.addProperty("ruler_global_id", ruler.globalId);
                if (!polity.has("dynasty_generations"))
                    polity.addProperty("dynasty_generations", binding.has("generation") ? binding.get("generation").getAsInt() : 1);
                binding.addProperty("ruler_global_id", ruler.globalId);
                binding.addProperty("dynasty_id", dynastyId);
                emergenceBindings.add(polityId, binding);
                int newCourtPositions = dev.worldforge.people.Courts.ensureRealm(
                        politicalActor, registry, PeopleMod.ROLES, PeopleMod.NAMES, source.seed, year);
                if (newCourtPositions > 0) changed = true;
                changed = true;
            }
            // Fill the ordinary council, steward, marshal, chancellor and household offices
            // for newly emerged realms through the existing deterministic Courts.ensure pipeline.
            // The same realm-level court materializer runs for image and vanilla worlds.
            // Do not backfill offices from pre-authored political overlays on an image map.
            if (changed) {
                dev.worldforge.people.Character durable = registry.all().stream()
                        .filter(person -> person.alive && (person.props.containsKey("emergence.polity_id")
                                || person.posts.stream().anyMatch(post -> post.place.startsWith("realm:"))))
                        .findFirst().orElse(null);
                if (durable != null && !PeopleMod.persistCharacter(durable, server))
                    LOGGER.error("wf_worldsim: ruler/dynasty/court bindings for map {} could not be persisted", source.id);
            }
            if (settlementMapMarkersChanged && source.map != null)
                dev.worldforge.mod.PoliticalMapRenderer.regenerateAll(source.map);
        }
        syncVanillaPoliticalOwnership(state);
        reconcileEmergentVassalage(state);
        RealmsMod.reconcileEmergentRealms(activeRealmIds);
        syncImagePoliticalOwnership(state, year);
        if (!activeRealmIds.isEmpty() && !RealmsMod.persistPoliticalState(server))
            LOGGER.error("wf_worldsim: generated titles, vassal contracts and dynasties could not be durably saved");
        if (!activeRealmIds.isEmpty() && !SettleMod.saveDurably(server))
            LOGGER.error("wf_worldsim: emergent settlement realm bindings could not be durably saved");
    }

    /**
     * Image-derived title trees provide geography and hierarchy only. Every displayed
     * province/region/state name is allocated from wf-people pools, with a world-wide
     * reservation set so map-source labels cannot leak into the political simulation.
     */
    private static boolean ensureImagePoliticalNames(MapData map) {
        if (map == null || map.titles == null) return false;
        List<TitleData.Title> ordered = new ArrayList<>(map.titles.allTitles());
        ordered.sort(Comparator.comparingInt((TitleData.Title title) -> title.tier)
                .thenComparing(title -> title.key));
        Set<String> thisMapTitleIds = ordered.stream()
                .map(title -> dev.worldforge.realms.Statecraft.mapTitleId(map.name, title.key))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> reserved = RealmsMod.POLITICS.titles().stream()
                .filter(title -> !thisMapTitleIds.contains(title.id))
                .map(title -> title.name).filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        SettleMod.COLONIES.values().stream()
                .filter(colony -> colony != null && colony.s != null && colony.s.name != null
                        && !colony.s.name.isBlank())
                .map(colony -> colony.s.name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .forEach(reserved::add);
        boolean changed = false;
        for (TitleData.Title title : ordered) {
            String category = title.tier <= 0 ? "provinces" : title.tier == 1 ? "regions" : "realms";
            TitleData.Realm owner = map.titles.realmOf(title);
            Object raceValue = title.props.getOrDefault("identity.race", title.props.getOrDefault("race", ""));
            Object cultureValue = title.props.getOrDefault("identity.culture", title.props.getOrDefault("culture", ""));
            if ((raceValue == null || String.valueOf(raceValue).isBlank()) && owner != null)
                raceValue = owner.props.getOrDefault("identity.race", owner.props.getOrDefault("race", ""));
            if ((cultureValue == null || String.valueOf(cultureValue).isBlank()) && owner != null)
                cultureValue = owner.props.getOrDefault("identity.culture", owner.props.getOrDefault("culture", ""));
            String race = raceValue == null ? "" : String.valueOf(raceValue);
            String culture = cultureValue == null ? "" : String.valueOf(cultureValue);
            String currentName = title.name == null ? "" : title.name.trim();
            boolean managed = "wf-people".equals(String.valueOf(title.props.getOrDefault("wf.name.source", "")));
            String normalized = currentName.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (managed && !normalized.isBlank() && reserved.add(normalized)) continue;
            String generated = PeopleMod.NAMES.generateUnique(race, culture, category,
                    new Random(Objects.hash(map.name, title.key, title.tier)), reserved);
            title.name = generated;
            title.props.put("wf.name.source", "wf-people");
            reserved.add(generated.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
            changed = true;
        }
        return changed;
    }

    /** Persist the vanilla world's real land/sea political border raster from canonical settlement ownership. */
    private static void syncVanillaPoliticalOwnership(JsonObject state) {
        Map<String, JsonObject> bands = new HashMap<>();
        for (var item : state.getAsJsonArray("bands")) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            bands.put(band.get("id").getAsString(), band);
        }
        Map<String, List<VanillaWorldGeography.PoliticalSeed>> seeds = new HashMap<>();
        for (var item : state.getAsJsonArray("polities")) if (item.isJsonObject()) {
            JsonObject polity = item.getAsJsonObject();
            String mapId = polity.get("map_id").getAsString();
            if (!mapId.startsWith("vanilla-world:")) continue;
            String realmId = "emergent-" + polity.get("id").getAsString();
            for (var settlement : polity.getAsJsonArray("settlement_ids")) {
                JsonObject band = bands.get(settlement.getAsString());
                if (band == null || !band.get("settled").getAsBoolean()) continue;
                seeds.computeIfAbsent(mapId, ignored -> new ArrayList<>()).add(
                        new VanillaWorldGeography.PoliticalSeed(
                                band.get("x").getAsInt(), band.get("z").getAsInt(), realmId));
            }
        }
        int ownedCells = VanillaWorldGeography.applyPoliticalOwnership(seeds);
        if (ownedCells > 0)
            LOGGER.info("wf_worldsim: persisted vanilla political borders across {} observed land samples", ownedCells);
    }

    /**
     * Assigns each real image-map province to the nearest emergent polity settlement.
     * The original image-derived province raster and parent hierarchy are preserved;
     * only the sovereign ownership of each province is reconciled.
     */
    private static void syncImagePoliticalOwnership(JsonObject state, int year) {
        Map<String, JsonObject> bands = new HashMap<>();
        for (var item : state.getAsJsonArray("bands")) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            bands.put(band.get("id").getAsString(), band);
        }
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null || map.titles == null) continue;
            boolean namesChanged = ensureImagePoliticalNames(map);
            Map<String, List<int[]>> seedsByRealm = new TreeMap<>();
            Map<String, List<String>> bandsByRealm = new TreeMap<>();
            for (var item : state.getAsJsonArray("polities")) if (item.isJsonObject()) {
                JsonObject polity = item.getAsJsonObject();
                if (!map.name.equals(polity.get("map_id").getAsString())) continue;
                String realmId = "emergent-" + polity.get("id").getAsString();
                EmergenceSource source = EmergenceSource.authored(map);
                for (var settlement : polity.getAsJsonArray("settlement_ids")) {
                    String bandId = settlement.getAsString();
                    JsonObject band = bands.get(bandId);
                    if (band == null || !band.get("settled").getAsBoolean()) continue;
                    seedsByRealm.computeIfAbsent(realmId, ignored -> new ArrayList<>()).add(
                            source.blockPosition(band.get("x").getAsInt(), band.get("z").getAsInt()));
                    bandsByRealm.computeIfAbsent(realmId, ignored -> new ArrayList<>()).add(bandId);
                }
            }
            if (seedsByRealm.isEmpty()) continue;
            Map<String, TitleData.Realm> realms = new TreeMap<>();
            boolean changed = namesChanged;
            for (String realmId : seedsByRealm.keySet()) {
                TitleData.Realm realm = map.titles.realms.get(realmId);
                if (realm != null) {
                    var contract = RealmsMod.POLITICS.vassalage(realmId);
                    String overlordId = contract == null ? null : contract.liegeRealm;
                    String relation = contract == null ? "" : contract.subjectType.name().toLowerCase(Locale.ROOT);
                    if (!Objects.equals(realm.overlordId, overlordId) || !Objects.equals(realm.relation, relation)) {
                        realm.overlordId = overlordId;
                        realm.relation = relation;
                        changed = true;
                    }
                    realms.put(realmId, realm);
                }
            }
            Map<String, Set<String>> desiredKeys = new TreeMap<>();
            realms.keySet().forEach(id -> desiredKeys.put(id, new TreeSet<>()));
            Map<String, String> provinceOwnerByKey = new HashMap<>();
            double radius = Math.max(512.0, Math.min(map.w, map.h) * map.bpp * 0.25);
            double radiusSquared = radius * radius;
            for (TitleData.Title province : map.titles.allTitles()) {
                if (province == null || province.tier != 0) continue;
                TitleData.Realm existingOwner = map.titles.realmOf(province);
                if (existingOwner != null && realms.containsKey(existingOwner.id)) {
                    provinceOwnerByKey.put(province.key, existingOwner.id);
                    continue;
                }
                // Rehydrate ownership from canonical Statecraft when the client/map
                // projection has just been rebuilt. A title owned by a dissolved realm
                // remains historical/neutral until an explicit transfer is recorded.
                String persistedOwner = "";
                boolean historicalClaim = false;
                for (TitleData.Title ancestor : map.titles.chain(province)) {
                    var persistedTitle = RealmsMod.POLITICS.title(
                            dev.worldforge.realms.Statecraft.mapTitleId(map.name, ancestor.key));
                    if (persistedTitle == null) continue;
                    if (realms.containsKey(persistedTitle.realmId)) persistedOwner = persistedTitle.realmId;
                    else historicalClaim = true;
                    break;
                }
                if (!persistedOwner.isBlank()) {
                    provinceOwnerByKey.put(province.key, persistedOwner);
                    continue;
                }
                if (historicalClaim) continue;
                String nearestRealm = "";
                double nearestDistance = Double.MAX_VALUE;
                for (var entry : seedsByRealm.entrySet()) {
                    if (!realms.containsKey(entry.getKey())) continue;
                    for (int[] seed : entry.getValue()) {
                        double dx = province.capX - seed[0], dz = province.capZ - seed[1];
                        double distance = dx * dx + dz * dz;
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearestRealm = entry.getKey();
                        }
                    }
                }
                if (nearestDistance <= radiusSquared && desiredKeys.containsKey(nearestRealm))
                    provinceOwnerByKey.put(province.key, nearestRealm);
            }
            // A parent title belongs to a realm only when every one of its descendant
            // provinces has the same owner. Mixed sovereign regions remain neutral,
            // while homogeneous provinces/regions/states retain their real hierarchy.
            Map<String, Set<String>> ownersByTitleKey = new HashMap<>();
            for (TitleData.Title province : map.titles.allTitles()) {
                if (province == null || province.tier != 0) continue;
                String owner = provinceOwnerByKey.get(province.key);
                if (owner == null) continue;
                for (TitleData.Title ancestor : map.titles.chain(province))
                    ownersByTitleKey.computeIfAbsent(ancestor.key, ignored -> new TreeSet<>()).add(owner);
            }
            for (var entry : ownersByTitleKey.entrySet()) {
                if (entry.getValue().size() == 1) {
                    String owner = entry.getValue().iterator().next();
                    desiredKeys.get(owner).add(entry.getKey());
                }
            }
            for (var entry : desiredKeys.entrySet()) {
                TitleData.Realm realm = realms.get(entry.getKey());
                Set<String> current = new TreeSet<>(realm.titleKeys);
                if (current.equals(entry.getValue())) continue;
                realm.titleKeys.clear();
                realm.titleKeys.addAll(entry.getValue());
                changed = true;
            }
            if (changed) map.titles.recompute();
            boolean missingImportedTitle = false;
            for (TitleData.Title province : map.titles.allTitles()) {
                if (province == null || province.tier != 0) continue;
                boolean assigned = desiredKeys.values().stream().anyMatch(keys -> keys.contains(province.key));
                if (!assigned) continue;
                for (TitleData.Title ancestor : map.titles.chain(province)) {
                    if (RealmsMod.POLITICS.title(
                            dev.worldforge.realms.Statecraft.mapTitleId(map.name, ancestor.key)) == null) {
                        missingImportedTitle = true;
                        break;
                    }
                }
                if (missingImportedTitle) break;
            }
            if (!changed && !missingImportedTitle) continue;

            // The map's actual province -> region -> state tree is the canonical
            // territorial hierarchy for image worlds; Statecraft imports it directly.
            int imported = RealmsMod.POLITICS.syncMapTitles(map.name, map.titles);
            CharacterRegistry registry = PeopleMod.registry(map);
            Map<String, String> importedIds = new TreeMap<>();
            for (TitleData.Title mapTitle : map.titles.allTitles()) {
                var politicalTitle = RealmsMod.POLITICS.title(
                        dev.worldforge.realms.Statecraft.mapTitleId(map.name, mapTitle.key));
                if (politicalTitle == null || !realms.containsKey(politicalTitle.realmId)) continue;
                importedIds.put(politicalTitle.id, politicalTitle.realmId);
            }
            // Extend the imported geographic province -> region tree into a real
            // political hierarchy. Static map packages deliberately contain geography,
            // not pre-authored sovereigns; the runtime creates duchies and kingdoms from
            // connected region clusters and keeps every intermediate title in Statecraft.
            for (var entry : realms.entrySet()) {
                String realmId = entry.getKey();
                String polityId = realmId.substring("emergent-".length());
                String crownId = "emergent-title-" + polityId;
                var crown = RealmsMod.POLITICS.title(crownId);
                if (crown == null) continue;
                List<TitleData.Title> topMapRegions = map.titles.allTitles().stream()
                        .filter(title -> title != null && title.tier == 1)
                        .filter(title -> importedIds.containsKey(
                                dev.worldforge.realms.Statecraft.mapTitleId(map.name, title.key))
                                && realmId.equals(importedIds.get(
                                dev.worldforge.realms.Statecraft.mapTitleId(map.name, title.key))))
                        .sorted(Comparator.comparingInt((TitleData.Title title) -> title.id)).toList();
                List<String> realmBands = bandsByRealm.getOrDefault(realmId, List.of());
                EmergenceSource hierarchySource = EmergenceSource.authored(map);
                String fallbackHolder = String.valueOf(entry.getValue().props
                        .getOrDefault("emergence.ruler_global_id", ""));
                List<List<TitleData.Title>> regionGroups = connectedTitleGroups(topMapRegions, 3);
                List<String> duchyIds = new ArrayList<>();
                Set<String> groupedRegionTitleIds = new HashSet<>();
                Set<String> kingdomedDuchyIds = new HashSet<>();
                int duchySerial = 0;
                for (List<TitleData.Title> group : regionGroups) {
                    if (group.size() < 2) continue;
                    duchySerial++;
                    String groupKey = group.stream().map(title -> Integer.toString(title.id))
                            .sorted().collect(java.util.stream.Collectors.joining("|"));
                    String duchyId = emergentTerritoryTitleId("duchy", polityId, groupKey);
                    double centerX = group.stream().mapToDouble(title ->
                            hierarchySource.blockPosition(title.capX, title.capZ)[0]).average().orElse(0);
                    double centerZ = group.stream().mapToDouble(title ->
                            hierarchySource.blockPosition(title.capX, title.capZ)[1]).average().orElse(0);
                    String capitalBand = nearestEmergentBand(centerX, centerZ, realmBands, bands,
                            hierarchySource);
                    String duchyName = populationIndex == null
                            ? entry.getValue().name + " Duchy " + duchySerial
                            : uniqueTerritorialName(duchyId, "regions", capitalBand,
                                    populationIndex, polityId + "|duchy|" + groupKey);
                    ensureEmergentTerritoryTitle(duchyId, realmId,
                            dev.worldforge.realms.Statecraft.Rank.DUCHY, duchyName);
                    if (populationIndex != null)
                        grantEmergentTerritoryHolder(duchyId, capitalBand, populationIndex, year, fallbackHolder);
                    for (TitleData.Title region : group) {
                        String regionTitleId = dev.worldforge.realms.Statecraft.mapTitleId(map.name, region.key);
                        linkEmergentTerritoryTitle(regionTitleId, duchyId);
                        groupedRegionTitleIds.add(regionTitleId);
                    }
                    importedIds.put(duchyId, realmId);
                    duchyIds.add(duchyId);
                }

                List<String> kingdomIds = new ArrayList<>();
                int kingdomSerial = 0;
                List<String> sortedDuchies = duchyIds.stream().sorted().toList();
                for (int offset = 0; offset < sortedDuchies.size(); offset += 3) {
                    List<String> group = sortedDuchies.subList(offset, Math.min(sortedDuchies.size(), offset + 3));
                    if (group.size() < 2) continue;
                    kingdomSerial++;
                    String groupKey = String.join("|", group);
                    String kingdomId = emergentTerritoryTitleId("kingdom", polityId, groupKey);
                    String capitalBand = realmBands.isEmpty() ? "" : realmBands.get(
                            Math.floorMod(groupKey.hashCode(), realmBands.size()));
                    String kingdomName = populationIndex == null
                            ? entry.getValue().name + " Kingdom " + kingdomSerial
                            : uniqueTerritorialName(kingdomId, "realms", capitalBand,
                                    populationIndex, polityId + "|kingdom|" + groupKey);
                    ensureEmergentTerritoryTitle(kingdomId, realmId,
                            dev.worldforge.realms.Statecraft.Rank.KINGDOM, kingdomName);
                    if (populationIndex != null)
                        grantEmergentTerritoryHolder(kingdomId, capitalBand, populationIndex, year, fallbackHolder);
                    for (String duchyId : group) {
                        linkEmergentTerritoryTitle(duchyId, kingdomId);
                        kingdomedDuchyIds.add(duchyId);
                    }
                    importedIds.put(kingdomId, realmId);
                    kingdomIds.add(kingdomId);
                }

                List<String> crownChildren = new ArrayList<>(kingdomIds);
                for (String duchyId : duchyIds)
                    if (!kingdomedDuchyIds.contains(duchyId)) crownChildren.add(duchyId);
                for (TitleData.Title region : topMapRegions) {
                    String regionTitleId = dev.worldforge.realms.Statecraft.mapTitleId(map.name, region.key);
                    if (!groupedRegionTitleIds.contains(regionTitleId)) crownChildren.add(regionTitleId);
                }
                if (!crownChildren.isEmpty()) {
                    var required = !kingdomIds.isEmpty()
                            ? dev.worldforge.realms.Statecraft.Rank.EMPIRE
                            : !duchyIds.isEmpty()
                                ? dev.worldforge.realms.Statecraft.Rank.KINGDOM
                                : dev.worldforge.realms.Statecraft.Rank.DUCHY;
                    if (crown.rank.ordinal() < required.ordinal())
                        RealmsMod.POLITICS.setTitleRank(crownId, required);
                    for (String childId : crownChildren) linkEmergentTerritoryTitle(childId, crownId);
                }
            }
            for (var entry : realms.entrySet()) {
                Set<String> realmTitleIds = new TreeSet<>();
                for (var importedTitle : importedIds.entrySet())
                    if (entry.getKey().equals(importedTitle.getValue())) realmTitleIds.add(importedTitle.getKey());
                String crownId = "emergent-title-" + entry.getKey().substring("emergent-".length());
                if (RealmsMod.POLITICS.title(crownId) != null) realmTitleIds.add(crownId);
                entry.getValue().props.put("emergence.statecraft_title_ids", String.join(",", realmTitleIds));
                entry.getValue().props.put("emergence.title_count", realmTitleIds.size());
            }
            // Materialize courts only after the political ownership overlay has been
            // reconciled. This second pass fills every now-owned map title using the
            // map's complete data-driven tier catalogue; it does not invent a parallel
            // five-rank title tree or assign offices to neutral geography.
            CharacterRegistry courtRegistry = PeopleMod.registry(map);
            if (courtRegistry != null) {
                int generatedRoles = PeopleMod.ROLES.ensureTerritorialRulers(map.titles);
                int generatedCourts = dev.worldforge.people.Courts.ensure(map.titles,
                        EditorManager.editorFor(map), courtRegistry, PeopleMod.ROLES, PeopleMod.NAMES,
                        PeopleMod.seed(map), PeopleMod.YEAR);
                var officeSync = dev.worldforge.realms.PoliticalIdentitySynchronizer.synchronizeRulers(
                        map.name, map.titles, courtRegistry, RealmsMod.POLITICS, PeopleMod.ROLES);
                LOGGER.info("wf_worldsim: full title-tree integration map={} sourceTitles={} importedTitles={} "
                                + "tiers={} dynamicRulerRoles={} courtCharacters={} linkedTitleHolders={} unresolved={}",
                        map.name, map.titles.allTitles().size(), RealmsMod.POLITICS.titles().stream()
                                .filter(title -> title.id.startsWith("map:")).count(),
                        map.titles.tiers.size(), generatedRoles, generatedCourts,
                        officeSync.linked() + officeSync.alreadyLinked(), officeSync.problems().size());
                for (String problem : officeSync.problems())
                    LOGGER.warn("wf_worldsim: title court integration map={} {}", map.name, problem);
                if (generatedCourts > 0 && !PeopleMod.persistAllNow(
                        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()))
                    LOGGER.error("wf_worldsim: could not persist generated courts for map {}", map.name);
            } else {
                LOGGER.error("wf_worldsim: canonical Character registry unavailable; title courts were not fabricated map={}",
                        map.name);
            }
            RealmsMod.syncPoliticalMapState();
            LOGGER.info("wf_worldsim: imported {} native image-map titles and bound generated province ownership on {}",
                    imported, map.name);
            if (changed) dev.worldforge.mod.PoliticalMapRenderer.regenerateAll(map);
        }
    }

    /** Deterministic connected grouping of geographic titles into higher political tiers. */
    private static List<List<TitleData.Title>> connectedTitleGroups(
            List<TitleData.Title> titles, int maximumGroupSize) {
        Map<Integer, TitleData.Title> remaining = new TreeMap<>();
        for (TitleData.Title title : titles) remaining.put(title.id, title);
        List<List<TitleData.Title>> groups = new ArrayList<>();
        while (!remaining.isEmpty()) {
            TitleData.Title first = remaining.remove(remaining.keySet().iterator().next());
            List<TitleData.Title> group = new ArrayList<>();
            ArrayDeque<TitleData.Title> queue = new ArrayDeque<>();
            queue.add(first);
            while (!queue.isEmpty() && group.size() < maximumGroupSize) {
                TitleData.Title current = queue.removeFirst();
                group.add(current);
                int[] neighbors = current.neighbors == null ? new int[0] : current.neighbors;
                Arrays.stream(neighbors).sorted().forEach(id -> {
                    TitleData.Title next = remaining.remove(id);
                    if (next != null && queue.size() + group.size() < maximumGroupSize)
                        queue.addLast(next);
                    else if (next != null) remaining.put(id, next);
                });
            }
            groups.add(group);
        }
        return groups;
    }

    private static String nearestEmergentBand(double x, double z, List<String> bandIds,
            Map<String, JsonObject> bands, EmergenceSource source) {
        String nearest = "";
        double best = Double.MAX_VALUE;
        for (String bandId : bandIds) {
            JsonObject band = bands.get(bandId);
            if (band == null) continue;
            int[] position = source.blockPosition(band.get("x").getAsInt(), band.get("z").getAsInt());
            double dx = x - position[0], dz = z - position[1];
            double distance = dx * dx + dz * dz;
            if (distance < best) { best = distance; nearest = bandId; }
        }
        return nearest;
    }

    /** Small neighbouring polities may become dependent on a stable, materially stronger realm. */
    private static void reconcileEmergentVassalage(JsonObject state) {
        Map<String, JsonObject> polities = new HashMap<>();
        for (var item : state.getAsJsonArray("polities")) if (item.isJsonObject()) {
            JsonObject polity = item.getAsJsonObject();
            polities.put(polity.get("id").getAsString(), polity);
        }
        List<JsonObject> ordered = new ArrayList<>(polities.values());
        ordered.sort(Comparator.comparingLong(p -> p.get("population").getAsLong()));
        for (JsonObject vassal : ordered) {
            String vassalId = "emergent-" + vassal.get("id").getAsString();
            if (RealmsMod.POLITICS.vassalage(vassalId) != null
                    || !vassal.has("settlement_ids") || vassal.getAsJsonArray("settlement_ids").size() == 0) continue;
            String mapId = vassal.get("map_id").getAsString();
            JsonObject strongest = null;
            Object neighborRaw = RealmsMod.emergentRealms().stream()
                    .filter(realm -> realm.id.equals(vassalId))
                    .map(realm -> realm.props.get("emergence.neighbors")).findFirst().orElse("");
            for (String neighborRealmId : String.valueOf(neighborRaw).split(",")) {
                if (!neighborRealmId.startsWith("emergent-")) continue;
                JsonObject candidate = polities.get(neighborRealmId.substring("emergent-".length()));
                if (candidate == null || !mapId.equals(candidate.get("map_id").getAsString())) continue;
                long ownPopulation = Math.max(1L, vassal.get("population").getAsLong());
                long candidatePopulation = candidate.get("population").getAsLong();
                // A two-to-one demographic advantage is a meaningful regional
                // power imbalance; the former 3x threshold suppressed most hierarchies.
                // A 2x threshold left almost every emergent polity sovereign in
                // isolation. A 1.5x advantage plus stable institutions is enough for
                // a voluntary/pressured regional dependency; the realm keeps its own
                // ruler, dynasty, government, titles and treasury under the contract.
                if (candidatePopulation * 2L < ownPopulation * 3L
                        || candidate.get("stability").getAsInt() < 55) continue;
                if (strongest == null || candidatePopulation > strongest.get("population").getAsLong())
                    strongest = candidate;
            }
            if (strongest == null) continue;
            String liegeId = "emergent-" + strongest.get("id").getAsString();
            if (RealmsMod.POLITICS.setVassalage(vassalId, liegeId, 0.10, 0.12)) {
                RealmDefs.Gov subjectGovernment = RealmsMod.DEFS.govs.get(RealmsMod.REALMS.state(vassalId).gov);
                RealmDefs.Gov liegeGovernment = RealmsMod.DEFS.govs.get(RealmsMod.REALMS.state(liegeId).gov);
                String subjectCategory = subjectGovernment == null ? "" : subjectGovernment.category().toLowerCase(Locale.ROOT);
                String liegeCategory = liegeGovernment == null ? "" : liegeGovernment.category().toLowerCase(Locale.ROOT);
                TitleData.Realm subjectRealm = RealmsMod.realm(vassalId);
                TitleData.Realm suzerainRealm = RealmsMod.realm(liegeId);
                String subjectDynasty = subjectRealm == null ? "" : String.valueOf(
                        subjectRealm.props.getOrDefault("emergence.dynasty_id", ""));
                String suzerainDynasty = suzerainRealm == null ? "" : String.valueOf(
                        suzerainRealm.props.getOrDefault("emergence.dynasty_id", ""));
                dev.worldforge.realms.Statecraft.SubjectType subjectType;
                if (!subjectDynasty.isBlank() && subjectDynasty.equals(suzerainDynasty)) {
                    subjectType = dev.worldforge.realms.Statecraft.SubjectType.PERSONAL_UNION;
                } else if (liegeCategory.contains("confederation")) {
                    subjectType = dev.worldforge.realms.Statecraft.SubjectType.CONFEDERATION_MEMBER;
                } else if (subjectCategory.contains("tribal") || subjectCategory.contains("nomad")
                        || subjectCategory.contains("clan")) {
                    subjectType = dev.worldforge.realms.Statecraft.SubjectType.TRIBUTARY;
                } else if (subjectCategory.contains("republic") || vassal.get("stability").getAsInt() < 70) {
                    subjectType = dev.worldforge.realms.Statecraft.SubjectType.PROTECTORATE;
                } else {
                    subjectType = dev.worldforge.realms.Statecraft.SubjectType.FEUDAL_VASSAL;
                }
                RealmsMod.POLITICS.setSubjectType(vassalId, subjectType);
                var contract = RealmsMod.POLITICS.vassalage(vassalId);
                if (contract != null) {
                    switch (subjectType) {
                        case TRIBUTARY -> { contract.taxShare = 0.20; contract.levyShare = 0.05; contract.autonomy = 55; }
                        case PROTECTORATE -> { contract.taxShare = 0.08; contract.levyShare = 0.04; contract.autonomy = 70; }
                        case CONFEDERATION_MEMBER -> { contract.taxShare = 0.05; contract.levyShare = 0.08; contract.autonomy = 85; }
                        case PERSONAL_UNION -> { contract.taxShare = 0.03; contract.levyShare = 0.05; contract.autonomy = 90; }
                        default -> { contract.taxShare = 0.10; contract.levyShare = 0.12; contract.autonomy = 35; }
                    }
                }
                LOGGER.info("wf_worldsim: {} entered {} contract with {}",
                        vassalId, subjectType.name().toLowerCase(Locale.ROOT), liegeId);
            }
        }
    }

    private static final class EmergentParents {
        private dev.worldforge.people.Character father;
        private dev.worldforge.people.Character mother;
        private static EmergentParents from(List<dev.worldforge.people.Character> residents, int year) {
            EmergentParents parents = new EmergentParents();
            for (dev.worldforge.people.Character candidate : residents) {
                if (!candidate.alive || dev.worldforge.people.PopulationSimulation.age(candidate, year) < 18) continue;
                if ("m".equals(candidate.gender) && parents.father == null) parents.father = candidate;
                if ("f".equals(candidate.gender) && parents.mother == null) parents.mother = candidate;
                if (parents.father != null && parents.mother != null) break;
            }
            return parents;
        }
    }

    private static dev.worldforge.people.Character createEmergentResident(
            dev.worldforge.people.CharacterRegistry registry, EmergenceSource source, String bandId,
            int year, int ordinal, EmergentParents parents) {
        dev.worldforge.people.Character father = parents.father, mother = parents.mother;
        if (father != null && !father.spouse.isBlank() && (mother == null || !father.spouse.equals(mother.id)))
            parents.father = father = null;
        if (mother != null && !mother.spouse.isBlank() && (father == null || !mother.spouse.equals(father.id)))
            parents.mother = mother = null;
        if (father == null || mother == null || !father.spouse.equals(mother.id) || !mother.spouse.equals(father.id)) {
            String gender = father == null ? "m" : (mother == null ? "f" : (ordinal % 2 == 0 ? "m" : "f"));
            dev.worldforge.people.Character adult = createEmergentAdult(registry, source, bandId, gender, year, ordinal);
            if (adult == null) return null;
            if ("m".equals(gender)) parents.father = adult; else parents.mother = adult;
            father = parents.father; mother = parents.mother;
            if (father != null && mother != null && father.spouse.isBlank() && mother.spouse.isBlank()) {
                try { registry.marry(father.id, mother.id, year); } catch (RuntimeException ignored) { }
            }
            return adult;
        }
        // Founding populations need a realistic adult base, not one married couple
        // surrounded by newborns. Most generated residents are independent adults who
        // can form their own households; a bounded minority are children of the founding
        // family, with a deterministic 0-17 year age distribution.
        if (Math.floorMod(ordinal, 4) != 0) {
            String gender = ordinal % 2 == 0 ? "m" : "f";
            return createEmergentAdult(registry, source, bandId, gender, year, ordinal);
        }
        String gender = ordinal % 2 == 0 ? "m" : "f";
        String name = emergentName(source, bandId, father.culture, gender, ordinal, year);
        int childAge = Math.floorMod((int) (source.seed ^ bandId.hashCode() ^ (long) ordinal * 0x9E3779B97F4A7C15L), 18);
        dev.worldforge.people.Character child = registry.addChild(father, mother, name, gender, year - childAge);
        tagEmergentResident(child, bandId, source);
        return child;
    }

    private static dev.worldforge.people.Character createEmergentAdult(
            dev.worldforge.people.CharacterRegistry registry, EmergenceSource source, String bandId,
            String gender, int year, int ordinal) {
        String culture = PeopleMod.NAMES.culture("");
        String name = emergentName(source, bandId, culture, gender, ordinal, year);
        Set<String> reservedHouseNames = registry.all().stream()
                .map(person -> person.house).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        RealmsMod.POLITICS.dynasties().stream().map(dynasty -> dynasty.name)
                .filter(value -> value != null && !value.isBlank()).forEach(reservedHouseNames::add);
        String house = PeopleMod.NAMES.generateUnique("", culture, "houses",
                new Random(source.seed ^ bandId.hashCode() ^ ordinal), reservedHouseNames);
        dev.worldforge.people.Character person = registry.create(name, house, culture, gender,
                year - (18 + Math.floorMod(bandId.hashCode() + ordinal * 13, 24)));
        tagEmergentResident(person, bandId, source);
        person.props.put("identity.source", "autonomous-emergence");
        return person;
    }

    private static void ensureEmergentOrigin(dev.worldforge.people.Character person,
            EmergenceSource source, int year, int ordinal, String realmId) {
        if (person == null || person.globalId == null || person.globalId.isBlank()) return;
        String owner = "n:" + person.globalId;
        try {
            Origins.Build existing = OriginsMod.ORIGINS.peek(owner);
            if (existing == null) {
                long seed = source.seed ^ person.globalId.hashCode();
                List<String> groups = OriginsMod.DEFS.professions.values().stream()
                        .map(profession -> profession.group()).filter(group -> group != null && !group.isBlank())
                        .distinct().sorted().toList();
                boolean adult = PopulationSimulation.age(person, year) >= 18;
                String hint = adult && !groups.isEmpty()
                        ? groups.get(Math.floorMod((int) seed, groups.size())) : "";
                int level = adult ? Math.max(5, Math.min(60, PopulationSimulation.age(person, year) / 2)) : 1;
                existing = OriginsMod.ORIGINS.assign(owner, seed, hint, level,
                        realmId == null ? "" : realmId);
            }
            if (existing.race != null && !existing.race.isBlank()) {
                person.props.put("origin.race", existing.race);
                if (!person.props.containsKey("emergence.race_name_assigned")) {
                    person.name = PeopleMod.NAMES.pickGivenName(existing.race, person.culture,
                            person.gender, new Random(source.seed ^ person.globalId.hashCode()));
                    person.props.put("emergence.race_name_assigned", "1");
                }
            }
            if (existing.cls != null && !existing.cls.isBlank())
                person.props.put("origin.class", existing.cls);
            if (!existing.prof.isEmpty())
                person.props.put("origin.profession", existing.prof.keySet().iterator().next());
            person.props.put("emergence.origins_initialized", "1");
        } catch (RuntimeException unavailable) {
            LOGGER.debug("wf_worldsim: Origins assignment deferred for canonical Character {}: {}",
                    person.globalId, unavailable.toString());
        }
    }

    private static void tagEmergentResident(dev.worldforge.people.Character person, String bandId,
            EmergenceSource source) {
        person.props.put("emergence.band_id", bandId);
        person.props.put("emergence.household_id", bandId);
        person.props.put("population.location", bandId);
        person.props.put("emergence.origin", "shared-world-simulation");
        person.props.put("emergence.world_space_id", source.id);
        if (source.dimension != null && !source.dimension.isBlank())
            person.props.put("emergence.dimension", source.dimension);
    }

    private static String emergentName(EmergenceSource source, String bandId, String culture, String gender, int ordinal, int year) {
        try {
            return PeopleMod.NAMES.pickGivenName("", culture, gender,
                    new Random(source.seed ^ ((long) bandId.hashCode() << 32) ^ ordinal ^ year));
        } catch (RuntimeException missingNamePool) {
            throw new IllegalStateException("No authoritative personal-name pool for "
                    + source.id + "/" + bandId + " (culture=" + culture + ")", missingNamePool);
        }
    }

    private static void sortObjects(JsonArray array, Comparator<JsonObject> comparator) {
        List<JsonObject> ordered = new ArrayList<>();
        for (var element : array) {
            if (!element.isJsonObject()) throw new IllegalStateException("WorldSim snapshot contains a non-object row");
            ordered.add(element.getAsJsonObject());
        }
        ordered.sort(comparator);
        while (array.size() > 0) array.remove(array.size() - 1);
        ordered.forEach(array::add);
    }

    private static dev.worldforge.mod.api.ActionExecutionService.Reconciliation reconcileInterrupted(
            MinecraftServer server, String requestId, JsonObject action) {
        String type = action.has("type") ? action.get("type").getAsString() : "";
        try {
            return switch (type) {
                case "build" -> {
                    Colony colony = SettleMod.colony(action.get("settlement").getAsString());
                    yield colony == null || colony.s == null
                            ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN
                            : (colony.s.hasPlacementReceipt(requestId)
                                ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.APPLIED
                                : dev.worldforge.mod.api.ActionExecutionService.Reconciliation.NOT_APPLIED);
                }
                case "reassign_profession" -> SettleMod.colony(action.get("settlement").getAsString()) == null
                        ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN
                        // apply() is projection-aware and idempotently completes a partial Character/Worker transition.
                        : dev.worldforge.mod.api.ActionExecutionService.Reconciliation.NOT_APPLIED;
                case "recruit" -> {
                    String realm = action.get("realm").getAsString();
                    ArmyEngine found = null;
                    for (MapData map : WorldForgeMod.loadedMaps().values())
                        if (map.titles != null && map.titles.realms.containsKey(realm)) { found = ArmyMod.armies(map); break; }
                    yield found == null ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN
                            : (found.hasArmyActionReceipt(requestId)
                                ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.APPLIED
                                : dev.worldforge.mod.api.ActionExecutionService.Reconciliation.NOT_APPLIED);
                }
                case "dynastic_marriage" -> {
                    String first = action.get("first").getAsString(), second = action.get("second").getAsString();
                    dev.worldforge.people.Character a = PeopleMod.authority().getGlobal(first);
                    dev.worldforge.people.Character b = PeopleMod.authority().getGlobal(second);
                    if (a == null || b == null) yield dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN;
                    if (second.equals(a.spouse) && first.equals(b.spouse))
                        yield dev.worldforge.mod.api.ActionExecutionService.Reconciliation.APPLIED;
                    if (a.alive && b.alive && a.spouse.isEmpty() && b.spouse.isEmpty())
                        yield dev.worldforge.mod.api.ActionExecutionService.Reconciliation.NOT_APPLIED;
                    yield dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN;
                }
                case "declare_war" -> {
                    String attacker = action.get("attacker").getAsString();
                    String defender = action.get("defender").getAsString();
                    if (attacker.startsWith("emergent-") && defender.startsWith("emergent-"))
                        yield RealmsMod.POLITICS.activeWarBetween(attacker, defender) != null
                                ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.APPLIED
                                : dev.worldforge.mod.api.ActionExecutionService.Reconciliation.NOT_APPLIED;
                    WarEngine found = null;
                    for (MapData map : WorldForgeMod.loadedMaps().values())
                        if (map.titles != null && map.titles.realms.containsKey(attacker)
                                && map.titles.realms.containsKey(defender)) { found = WarMod.engine(map); break; }
                    yield found == null ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN
                            : (found.hasActionReceipt(requestId)
                                ? dev.worldforge.mod.api.ActionExecutionService.Reconciliation.APPLIED
                                : dev.worldforge.mod.api.ActionExecutionService.Reconciliation.NOT_APPLIED);
                }
                default -> dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN;
            };
        } catch (RuntimeException error) {
            LOGGER.warn("wf_worldsim: could not reconcile interrupted action {}: {}", requestId, error.toString());
            return dev.worldforge.mod.api.ActionExecutionService.Reconciliation.UNKNOWN;
        }
    }

    private static boolean apply(MinecraftServer server, String requestId, JsonObject action, long day) {
        String type = action.has("type") ? action.get("type").getAsString() : "";
        if ("build".equals(type)) {
            String settlementId = action.get("settlement").getAsString();
            String building = action.get("building").getAsString();
            Colony colony = SettleMod.colony(settlementId);
            if (colony == null || colony.s == null) return false;
            int hash = requestId.hashCode();
            for (int radius = 0; radius <= 48; radius += 4) {
                for (int x = -radius; x <= radius; x += 4) for (int z = -radius; z <= radius; z += 4) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                    if (colony.s.placeError(building, x + (hash & 7), z + ((hash >>> 3) & 7)) == null) {
                        colony.s.placeOnce(requestId, building, x + (hash & 7), z + ((hash >>> 3) & 7));
                        if (!SettleMod.saveDurably(server)) return false;
                        lastProjectDay.put(settlementId, day);
                        return true;
                    }
                }
            }
            return false;
        }
        if ("reassign_profession".equals(type)) {
            String settlementId = action.get("settlement").getAsString();
            String from = action.get("from").getAsString(), to = action.get("to").getAsString();
            int wanted = action.get("workers").getAsInt();
            Colony colony = SettleMod.colony(settlementId);
            if (colony == null || colony.s == null || wanted <= 0) return false;
            if (!colony.s.defs.professions.containsKey(to)) return false;
            int changed = 0;
            List<dev.worldforge.people.Character> pendingProfessionTransitions = new ArrayList<>();
            List<Worker> candidates = new ArrayList<>(colony.s.workers);
            candidates.sort(Comparator.comparingInt(worker -> worker.id));
            for (Worker worker : candidates) {
                if (changed >= wanted) break;
                if (worker.building != 0 || !worker.lifeAction.isBlank()) continue;
                boolean workerSource = "unemployed".equals(from)
                        ? worker.generalist() : from.equals(worker.permanentProfession());
                boolean workerTarget = to.equals(worker.permanentProfession());
                if (!workerSource && !workerTarget) continue;
                if (worker.person == null || worker.person.isBlank()) continue;

                // A settlement Worker is only a projection of its canonical Character.
                // Reconcile either side of an interrupted legacy transition, but never
                // change a worker whose authoritative Character is missing or unrelated.
                var person = PeopleMod.authority().getGlobal(worker.person);
                CharacterRegistry owner = PeopleMod.authority().registryForGlobal(worker.person);
                if (person == null || owner == null || !person.alive) continue;
                String canonicalProfession = PopulationSimulation.profession(person);
                boolean characterSource = "unemployed".equals(from)
                        ? canonicalProfession.isBlank() || "laborer".equals(canonicalProfession)
                                || "general_worker".equals(canonicalProfession)
                        : from.equals(canonicalProfession);
                boolean characterTarget = to.equals(canonicalProfession);
                if (!characterSource && !characterTarget) continue;
                if (characterSource) {
                    if (!PopulationSimulation.canWork(person, PeopleMod.YEAR)) continue;
                    Map<String, Object> transition = new LinkedHashMap<>();
                    transition.put("request_id", requestId);
                    transition.put("settlement", settlementId);
                    transition.put("worker_id", worker.id);
                    transition.put("character_id", person.globalId);
                    transition.put("from", from);
                    transition.put("profession", to);
                    person.props.put("population.pending_settlement_profession", transition);
                    owner.changeProfession(person.id, to, PeopleMod.YEAR);
                }
                // Persist the Character and its projection-recovery marker first. The
                // settlement synchronizer will honor this marker after restart instead of
                // overwriting the canonical profession with a stale Worker snapshot.
                if (!PeopleMod.persistCharacter(person, server)) return false;
                if (workerSource) colony.s.setProfession(worker.id, to);
                if (characterSource) pendingProfessionTransitions.add(person);
                changed++;
            }
            if (changed == 0 || !SettleMod.saveDurably(server)) return false;
            for (dev.worldforge.people.Character person : pendingProfessionTransitions) {
                Object marker = person.props.remove("population.pending_settlement_profession");
                if (!PeopleMod.persistCharacter(person, server)) {
                    if (marker != null) person.props.put("population.pending_settlement_profession", marker);
                    return false;
                }
            }
            return true;
        }
        if ("recruit".equals(type)) {
            String realm = action.get("realm").getAsString();
            int wanted = Math.max(1, action.get("count").getAsInt());
            for (MapData map : WorldForgeMod.loadedMaps().values()) {
                if (map.titles == null || !map.titles.realms.containsKey(realm)) continue;
                ArmyEngine army = ArmyMod.armies(map);
                // A prepared or completed army-domain operation is authoritative. Resume it
                // before checking current barracks staffing or levy availability: those may
                // have changed after the original action but must not block crash recovery.
                if (army.hasPendingRecruitment(requestId) || army.hasArmyActionReceipt(requestId)) {
                    army.resumeRecruitmentWithPersistence(requestId);
                    return true;
                }
                String requestedType = action.has("unit_role") ? action.get("unit_role").getAsString() : "";
                UnitType selected = requestedType.isBlank() ? null : army.types.get(requestedType);
                if (selected == null) return false;
                String recruitmentBuilding = troopRecruitmentBuilding(selected.id);
                Colony trainingColony = SettleMod.COLONIES.values().stream()
                        .filter(c -> c != null && c.s != null && realm.equals(SettleMod.realmOf(c)))
                        .filter(c -> c.s.buildings.stream().anyMatch(b -> recruitmentBuilding.equals(b.def.id)
                                && b.state == Building.State.DONE && !b.paused
                                && c.s.assigned(b.uid).stream().anyMatch(worker -> worker.lifeAction.isBlank() && "trainer".equals(worker.workRole()))))
                        .findFirst().orElse(null);
                if (trainingColony == null) return false;
                int count = (int) Math.min(wanted, Math.floor(army.pool(realm)));
                if (count <= 0) return false;
                army.recruitWithPersistence(requestId, "raise", realm, selected.id, count);
                return true;
            }
            return false;
        }
        if ("dynastic_marriage".equals(type)) {
            String first = action.get("first").getAsString(), second = action.get("second").getAsString();
            for (MapData map : WorldForgeMod.loadedMaps().values()) {
                if (map.titles == null) continue;
                var court = CourtMod.court(map);
                if (court == null) continue;
                var a = court.reg.all().stream().filter(person -> first.equals(person.globalId)).findFirst().orElse(null);
                var b = court.reg.all().stream().filter(person -> second.equals(person.globalId)).findFirst().orElse(null);
                if (a != null && b != null) {
                    var result = Marriages.propose(court, a.id, b.id);
                    if (!result.ok) return false;
                    // Use the court addon's canonical crash-recoverable persistence path.
                    // The in-memory marriage is not reported as committed unless its state is durable.
                    return CourtMod.persistNow(server, map);
                }
            }
            return false;
        }
        if ("declare_war".equals(type)) {
            String attacker = action.get("attacker").getAsString(), defender = action.get("defender").getAsString();
            if (attacker.startsWith("emergent-") && defender.startsWith("emergent-")) {
                if (RealmsMod.POLITICS.activeWarBetween(attacker, defender) != null)
                    return RealmsMod.persistNow(server);
                TitleData.Realm attackingRealm = RealmsMod.realm(attacker);
                if (attackingRealm == null || attackingRealm.ruler == null || attackingRealm.ruler.isBlank()) return false;
                try {
                    RealmsMod.POLITICS.declareWar(attacker, defender, "emergent_border_conflict",
                            attackingRealm.ruler, (int) Math.min(Integer.MAX_VALUE, day));
                    return RealmsMod.persistNow(server);
                } catch (RuntimeException rejected) {
                    LOGGER.info("wf_worldsim: emergent war {} -> {} rejected by Statecraft: {}",
                            attacker, defender, rejected.getMessage());
                    return false;
                }
            }
            for (MapData map : WorldForgeMod.loadedMaps().values()) {
                if (map.titles == null || !map.titles.realms.containsKey(attacker) || !map.titles.realms.containsKey(defender)) continue;
                WarEngine war = WarMod.engine(map);
                if (war.cbs.isEmpty()) return false;
                WarEngine.CbOption selected = war.cbOptions(attacker, defender).stream()
                        .filter(option -> option.available)
                        .min(Comparator.comparingInt(option -> option.cb.goal == dev.worldforge.war.CasusBelli.Goal.SUBJUGATE ? 0
                                : option.cb.goal == dev.worldforge.war.CasusBelli.Goal.CONQUER ? 1 : 2))
                        .orElse(null);
                if (selected == null) return false;
                List<Integer> goals = selected.cb.goal == dev.worldforge.war.CasusBelli.Goal.CONQUER
                        ? war.goalCandidates(defender).stream().limit(selected.cb.maxGoals).map(title -> title.id).toList()
                        : List.of();
                war.declareOnce(requestId, attacker, defender, selected.cb.id, goals);
                return WarMod.persistNow(server);
            }
            return false;
        }
        // Unknown proposal kinds are rejected rather than silently mutating the world.
        return false;
    }


    private static void recordEvent(long day, String id, JsonObject action, boolean applied) {
        JsonObject event = new JsonObject();
        event.addProperty("day", day);
        event.addProperty("request_id", id);
        event.addProperty("type", action.has("type") ? action.get("type").getAsString() : "unknown");
        event.addProperty("applied", applied);
        if (action.has("reason")) event.add("reason", action.get("reason"));
        if (action.has("attacker")) event.add("attacker", action.get("attacker"));
        if (action.has("defender")) event.add("defender", action.get("defender"));
        if (action.has("settlement")) event.add("settlement", action.get("settlement"));
        if (action.has("building")) event.add("building", action.get("building"));
        if (action.has("first")) event.add("first", action.get("first"));
        if (action.has("second")) event.add("second", action.get("second"));
        journal.add(GSON.toJson(event));
        while (journal.size() > 10000) journal.removeFirst();

        String type = action.has("type") ? action.get("type").getAsString() : "unknown";
        String outcome = applied ? "Исполнено" : "Отклонено исполнительной системой";
        String subject = switch (type) {
            case "declare_war" -> "Объявление войны " + action.get("attacker").getAsString()
                    + " против " + action.get("defender").getAsString();
            case "dynastic_marriage" -> "Династический брак " + action.get("first").getAsString()
                    + " и " + action.get("second").getAsString();
            case "build" -> "Строительный план " + action.get("building").getAsString()
                    + " в поселении " + action.get("settlement").getAsString();
            case "recruit" -> "Набор войск в государстве " + action.get("realm").getAsString();
            case "reassign_profession" -> "Изменение профессии в поселении " + action.get("settlement").getAsString();
            default -> "Решение автономного мира: " + type;
        };
        List<String> affectedRealms = new ArrayList<>();
        for (String key : List.of("attacker", "defender", "realm")) {
            if (action.has(key) && !action.get(key).isJsonNull()) affectedRealms.add(action.get(key).getAsString());
        }
        String reason = action.has("reason") ? action.get("reason").getAsString() : "";
        // A rejected proposal is an AI diagnostic, not a historical event.
        if (applied) {
            WorldForgeApi.postEventScoped(type.equals("declare_war") ? "war"
                            : (type.equals("dynastic_marriage") ? "people"
                            : (type.equals("build") || type.equals("reassign_profession") ? "settlement" : "diplomacy")),
                    outcome + ": " + subject + (reason.isBlank() ? "" : ". Причина: " + reason),
                    affectedRealms, actionEventScopes(action), Math.max(0L, day * 1440L));
        }
    }

    /** Entity scopes for confirmed autonomous actions, resolved from canonical IDs. */
    private static List<String> actionEventScopes(JsonObject action) {
        Set<String> scopes = new LinkedHashSet<>();
        String type = action.has("type") ? action.get("type").getAsString() : "";
        if (type.equals("build") || type.equals("reassign_profession")) {
            String settlementId = action.has("settlement") ? action.get("settlement").getAsString() : "";
            if (!settlementId.isBlank()) scopes.add("settlement:" + settlementId);
        }
        if (type.equals("dynastic_marriage")) {
            for (String key : List.of("first", "second")) {
                if (!action.has(key) || action.get(key).isJsonNull()) continue;
                String characterId = action.get(key).getAsString();
                if (characterId.isBlank()) continue;
                scopes.add("character:" + characterId);
                for (EmergenceSource source : emergenceSources()) {
                    if (source.registry == null) continue;
                    for (dev.worldforge.people.Character person : source.registry.all()) {
                        if (!characterId.equals(person.globalId)) continue;
                        if (person.house != null && !person.house.isBlank())
                            scopes.add("dynasty:" + person.house);
                        Object planet = person.props.get("emergence.world_space_id");
                        if (planet != null && !String.valueOf(planet).isBlank())
                            scopes.add("planet:" + planet);
                    }
                }
            }
        }
        return List.copyOf(scopes);
    }

    private static Path stateFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_worldsim.json");
    }

    private static synchronized boolean saveState(MinecraftServer server) {
        try {
            JsonObject state = new JsonObject();
            JsonObject cooldowns = new JsonObject();
            lastProjectDay.forEach(cooldowns::addProperty);
            state.add("last_project_day", cooldowns);
            if (lastDecisionDay != Long.MIN_VALUE) state.addProperty("last_decision_day", lastDecisionDay);
            JsonArray events = new JsonArray();
            for (String line : journal) events.add(GSON.fromJson(line, JsonObject.class));
            state.add("events", events);
            if (emergenceState != null) state.add("emergence_state", emergenceState);
            state.add("emergence_bindings", emergenceBindings);
            state.add("shared_event_log", com.google.gson.JsonParser.parseString(WorldForgeApi.events().toJson()));
            state.add("historical_chronicle", com.google.gson.JsonParser.parseString(HISTORICAL_CHRONICLE.toJson()));
            state.add("performance_profile", PROFILER.snapshot());
            Path target = stateFile(server);
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            LOGGER.error("wf_worldsim: unable to persist simulation journal; proposals remain idempotent", error);
            return false;
        }
    }

    private static synchronized void loadState(MinecraftServer server) {
        Path file = stateFile(server);
        if (!Files.exists(file)) return;
        try {
            JsonObject state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (state == null) return;
            lastProjectDay.clear();
            lastDecisionDay = state.has("last_decision_day")
                    ? state.get("last_decision_day").getAsLong() : Long.MIN_VALUE;
            if (state.has("last_project_day") && state.get("last_project_day").isJsonObject()) {
                for (var entry : state.getAsJsonObject("last_project_day").entrySet())
                    lastProjectDay.put(entry.getKey(), entry.getValue().getAsLong());
            }
            journal.clear();
            emergenceState = state.has("emergence_state") && state.get("emergence_state").isJsonObject()
                    ? state.getAsJsonObject("emergence_state").deepCopy() : null;
            emergenceBindings = state.has("emergence_bindings") && state.get("emergence_bindings").isJsonObject()
                    ? state.getAsJsonObject("emergence_bindings").deepCopy() : new JsonObject();
            if (state.has("events") && state.get("events").isJsonArray())
                for (var event : state.getAsJsonArray("events")) if (event.isJsonObject()) journal.add(GSON.toJson(event));
            if (state.has("shared_event_log") && state.get("shared_event_log").isJsonObject())
                WorldForgeApi.events().fromJson(GSON.toJson(state.get("shared_event_log")));
            if (state.has("historical_chronicle") && state.get("historical_chronicle").isJsonObject())
                HISTORICAL_CHRONICLE.fromJson(GSON.toJson(state.get("historical_chronicle")));
            LOGGER.info("wf_worldsim: restored {} proposal records, {} shared world events, {} chronicle entries and {} settlement cooldowns",
                    journal.size(), WorldForgeApi.events().size(), HISTORICAL_CHRONICLE.size(), lastProjectDay.size());
        } catch (Exception error) {
            LOGGER.error("wf_worldsim: simulation journal is unreadable; no old data will be overwritten until inspected", error);
        }
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
    private static long fixed(double value) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        return Math.min(Long.MAX_VALUE, Math.round(value * 100.0));
    }
}
