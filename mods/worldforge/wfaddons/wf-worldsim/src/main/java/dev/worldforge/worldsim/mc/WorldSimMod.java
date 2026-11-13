package dev.worldforge.worldsim.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.Diplomacy;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.worldsim.HistoricalChronicle;
import dev.worldforge.worldsim.MapGeography;
import dev.worldforge.court.CourtSim;
import dev.worldforge.court.mc.CourtMod;
import dev.worldforge.court.Marriages;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.mc.PeopleMod;
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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
    private static final Gson GSON = new Gson();
    private static final int DECISION_INTERVAL_DAYS = 30;
    private static long lastDecisionDay = Long.MIN_VALUE;
    private static long lodTicks;
    private static final Map<String, Long> lastProjectDay = new HashMap<>();
    private static final List<String> journal = new ArrayList<>();
    private static JsonObject emergenceState;
    private static JsonObject emergenceBindings = new JsonObject();
    private static boolean emergentRealmsRehydrated;
    private static final HistoricalChronicle HISTORICAL_CHRONICLE = new HistoricalChronicle();


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
        WorldForgeApi.events().addListener(item -> {
            HISTORICAL_CHRONICLE.record(item, WorldForgeApi.clock().dayNumber(), WorldForgeApi.clock().year());
            publishPlatformTimelineEvent(item);
        });
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        lastDecisionDay = Long.MIN_VALUE;
        lodTicks = 0;
        emergenceState = null;
        emergenceBindings = new JsonObject();
        emergentRealmsRehydrated = false;
        HISTORICAL_CHRONICLE.clear();
        HISTORICAL_CHRONICLE.setStartingYear(WorldForgeApi.clock().startYear);
        publishObservers(event.getServer());
        loadState(event.getServer());
        LOGGER.info("wf_worldsim: autonomous coordinator started; native Rust core={}, initial LOD observers={}",
                NativeWorldSimulation.load() ? "available" : "unavailable",
                serverObserverCount(event.getServer()));
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) saveState(server);
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
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        if (++lodTicks % 20 == 0) publishObservers(server);
        // Rebuild virtual realm/trade projections from the persisted emergence state only
        // after all ServerStarted listeners (including wf-realms) have restored their files.
        if (!emergentRealmsRehydrated && emergenceState != null) {
            syncEmergentPolities(server, emergenceState, WorldForgeApi.clock().year());
            emergentRealmsRehydrated = true;
        }
        long day = WorldForgeApi.clock().dayNumber();
        if (day <= 0 || (lastDecisionDay != Long.MIN_VALUE
                && day - lastDecisionDay < DECISION_INTERVAL_DAYS)) return;
        // Compare elapsed game days instead of day % interval: at accelerated speeds
        // the clock may jump over exact multiples and otherwise starve the coordinator.
        if (!NativeWorldSimulation.load()) return;
        try {
            JsonObject snapshot = snapshot(server, day);
            byte[] input = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            JsonObject emergenceRequest = new JsonObject();
            emergenceRequest.add("snapshot", snapshot);
            if (emergenceState != null) emergenceRequest.add("state", emergenceState);
            JsonObject emergenceFrame = GSON.fromJson(new String(
                    NativeWorldSimulation.advanceEmergence(GSON.toJson(emergenceRequest).getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8), JsonObject.class);
            if (emergenceFrame == null || !emergenceFrame.has("state") || !emergenceFrame.get("state").isJsonObject())
                throw new IllegalStateException("Native emergence engine returned an invalid state frame");
            emergenceState = emergenceFrame.getAsJsonObject("state").deepCopy();
            syncEmergentPeople(server, emergenceState, WorldForgeApi.clock().year());
            syncEmergentPolities(server, emergenceState, WorldForgeApi.clock().year());
            if (emergenceFrame.has("events") && emergenceFrame.get("events").isJsonArray()) {
                for (var eventElement : emergenceFrame.getAsJsonArray("events")) {
                    if (!eventElement.isJsonObject()) continue;
                    JsonObject emergenceEvent = eventElement.getAsJsonObject();
                    String category = emergenceEvent.has("kind") ? emergenceEvent.get("kind").getAsString() : "world";
                    String subject = emergenceEvent.has("text") ? emergenceEvent.get("text").getAsString() : "Emergent world event";
                    long eventDay = Math.max(0L, emergenceEvent.has("year") ? emergenceEvent.get("year").getAsLong() * 365L : day);
                    WorldForgeApi.postEvent("world", "[" + category + "] " + subject, List.of(), eventDay * 1440L);
                }
            }
            JsonObject frame = GSON.fromJson(new String(NativeWorldSimulation.decide(input), StandardCharsets.UTF_8), JsonObject.class);
            if (frame == null || !frame.has("proposals") || !frame.get("proposals").isJsonArray()) return;
            int accepted = 0, rejected = 0;
            for (var element : frame.getAsJsonArray("proposals")) {
                if (!element.isJsonObject()) continue;
                JsonObject proposal = element.getAsJsonObject();
                JsonObject kind = proposal.has("kind") && proposal.get("kind").isJsonObject()
                        ? proposal.getAsJsonObject("kind") : null;
                if (kind == null) continue;
                String proposalId = proposal.get("id").getAsString();
                try {
                    // Reconcile interrupted tasks against authoritative domain receipts before
                    // allowing the action service to retry anything after a restart.
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
            lastDecisionDay = day;
            saveState(server);
            LOGGER.info("wf_worldsim: day={} realms={} settlements={} proposals={} applied={} rejected={}",
                    day, snapshot.getAsJsonArray("realms").size(), snapshot.getAsJsonArray("settlements").size(),
                    frame.getAsJsonArray("proposals").size(), accepted, rejected);
        } catch (Exception error) {
            LOGGER.error("wf_worldsim: decision cycle failed safely at day {}", day, error);
        }
    }


    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("wfworldsim")
                .then(Commands.literal("status").executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "WorldSim: native=" + NativeWorldSimulation.load()
                                    + ", realms=" + WorldForgeMod.loadedMaps().values().stream().filter(m -> m.titles != null)
                                    .mapToInt(m -> m.titles.realmList().size()).sum()
                                    + ", settlements=" + SettleMod.COLONIES.size()
                                    + ", proposal_records=" + journal.size()
                                    + ", world_events=" + WorldForgeApi.events().size()
                                    + ", chronicle_records=" + HISTORICAL_CHRONICLE.size()
                                    + ", day=" + WorldForgeApi.clock().dayNumber()), false);
                    return 1;
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
                        .executes(context -> {
                            List<String> pages = HISTORICAL_CHRONICLE.book(12);
                            for (String page : pages) context.getSource().sendSuccess(() -> Component.literal(page), false);
                            return HISTORICAL_CHRONICLE.size();
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50)).executes(context -> {
                            int count = IntegerArgumentType.getInteger(context, "count");
                            List<String> pages = HISTORICAL_CHRONICLE.book(count);
                            for (String page : pages) context.getSource().sendSuccess(() -> Component.literal(page), false);
                            return Math.min(count, HISTORICAL_CHRONICLE.size());
                        }))));
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

    private static JsonObject snapshot(MinecraftServer server, long day) {
        JsonObject root = new JsonObject();
        var canonicalWorldId = WorldForgeMod.worldId();
        if (canonicalWorldId == null)
            throw new IllegalStateException("Canonical WorldId is unavailable; refusing autonomous simulation without stable world identity");
        root.addProperty("world_id", canonicalWorldId.toString());
        root.addProperty("seed", server.getWorldData().worldGenOptions().seed() & Long.MAX_VALUE);
        root.addProperty("day", day);
        JsonArray realms = new JsonArray(), borders = new JsonArray(), settlements = new JsonArray(), characters = new JsonArray(), workforce = new JsonArray();
        JsonArray geography = new JsonArray();
        // Geography is sampled independently of political overlays. A map with no titles/realms
        // is still a valid source of terrain, biome and river evidence for a future map-first world.
        for (MapData map : WorldForgeMod.loadedMaps().values())
            geography.add(GSON.toJsonTree(MapGeography.describe(map)));
        Set<String> threatenedRealms = new HashSet<>();
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            for (var realm : map.titles.realmList()) {
                JsonObject r = new JsonObject();
                r.addProperty("id", realm.id);
                r.addProperty("population", number(realm.props.get("population"), 100));
                ArmyEngine armyEngine = ArmyMod.armies(map);
                double armyPower = armyEngine.armiesOf(realm.id).stream().mapToDouble(a -> a.power(armyEngine.types)).sum();
                r.addProperty("treasury", fixed(armyEngine.gold(realm.id)));
                r.addProperty("army_strength", Math.max(0L, Math.round(armyPower)));
                r.addProperty("stability", (int) number(realm.props.get("stability"), 50));
                r.addProperty("prestige", (int) number(realm.props.get("prestige"), 0));
                r.addProperty("expansion_pressure", (int) number(realm.props.get("expansion_pressure"), 35));
                String war = String.valueOf(realm.props.getOrDefault("war.state", "peace"));
                r.addProperty("at_war", !war.isBlank() && !"peace".equalsIgnoreCase(war));
                r.addProperty("last_war_day", (long) number(realm.props.get("war.last_day"), 0));
                realms.add(r);
            }
            Map<String, Set<String>> adjacency = Diplomacy.adjacency(map.titles);
            Set<String> emitted = new TreeSet<>();
            for (var entry : adjacency.entrySet()) for (String other : entry.getValue()) {
                String a = entry.getKey().compareTo(other) < 0 ? entry.getKey() : other;
                String b = entry.getKey().compareTo(other) < 0 ? other : entry.getKey();
                if (!emitted.add(a + "|" + b)) continue;
                JsonObject border = new JsonObject();
                border.addProperty("a", a); border.addProperty("b", b);
                var realmA = map.titles.realms.get(a);
                var realmB = map.titles.realms.get(b);
                boolean contested = realmA != null && dev.worldforge.mod.api.Diplomacy.ids(realmA, "claims").contains(b)
                        || realmB != null && dev.worldforge.mod.api.Diplomacy.ids(realmB, "claims").contains(a);
                border.addProperty("contested_claim", contested);
                border.addProperty("strategic_value", 80);
                if (contested) { threatenedRealms.add(a); threatenedRealms.add(b); }
                border.addProperty("distance_cost", 0);
                borders.add(border);
            }
        }
        for (TitleData.Realm realm : RealmsMod.emergentRealms()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", realm.id);
            long population = (long) number(realm.props.get("population"), 0);
            item.addProperty("population", Math.max(0L, population));
            item.addProperty("treasury", fixed(RealmsMod.POLITICS.treasury(realm.id).balance));
            // Population-backed mobilization potential is strategic capacity, not a spawned army.
            item.addProperty("army_strength", Math.max(0L, population / 12L));
            item.addProperty("stability", (int) number(realm.props.get("stability"), 50));
            item.addProperty("prestige", (int) number(realm.props.get("prestige"), 0));
            item.addProperty("expansion_pressure", (int) Math.max(0, Math.min(100, 20 + population / 10)));
            long lastWarDay = 0;
            boolean atWar = false;
            for (var war : RealmsMod.POLITICS.wars()) {
                if (!war.attackerRealm.equals(realm.id) && !war.defenderRealm.equals(realm.id)) continue;
                lastWarDay = Math.max(lastWarDay, war.startDay);
                if (war.status == dev.worldforge.realms.Statecraft.WarStatus.ACTIVE) atWar = true;
            }
            item.addProperty("at_war", atWar);
            item.addProperty("last_war_day", lastWarDay);
            realms.add(item);
        }
        Set<String> emittedEmergentBorders = new TreeSet<>();
        for (TitleData.Realm realm : RealmsMod.emergentRealms()) {
            Object raw = realm.props.get("emergence.neighbors");
            if (raw == null) continue;
            for (String neighbor : String.valueOf(raw).split(",")) {
                if (neighbor.isBlank() || RealmsMod.realm(neighbor) == null) continue;
                String a = realm.id.compareTo(neighbor) < 0 ? realm.id : neighbor;
                String b = realm.id.compareTo(neighbor) < 0 ? neighbor : realm.id;
                if (!emittedEmergentBorders.add(a + "|" + b)) continue;
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
            item.addProperty("roads", 0);
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
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            CourtSim court = CourtMod.court(map);
            if (court == null) continue;
            for (dev.worldforge.people.Character person : court.reg.all()) {
                if (!person.alive) continue;
                String realm = Marriages.family(court, person);
                if (realm.isBlank()) continue;
                JsonObject c = new JsonObject();
                if (person.globalId == null || person.globalId.isBlank()) {
                    LOGGER.warn("wf_worldsim: skipping Character {} without canonical global UUID", person.id);
                    continue;
                }
                c.addProperty("id", person.globalId); c.addProperty("realm_id", realm);
                c.addProperty("age", Math.max(0, court.year() - person.birthYear));
                c.addProperty("alive", person.alive); c.addProperty("married", !person.spouse.isBlank());
                c.addProperty("gender", "m".equals(person.gender) ? 0 : 1);
                c.addProperty("fertility", (int) number(person.props.get("fertility"), 50));
                c.addProperty("prestige", (int) number(person.props.get("prestige"), 0));
                c.addProperty("opinion", (int) number(person.props.get("opinion"), 0));
                c.addProperty("house_id", person.house);
                characters.add(c);
            }
        }
        // Canonical JSON ordering is part of the native simulation contract:
        // identical authoritative state must yield identical Rust input bytes.
        sortObjects(realms, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(borders, Comparator.comparing((JsonObject item) -> item.get("a").getAsString())
                .thenComparing(item -> item.get("b").getAsString()));
        sortObjects(settlements, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(characters, Comparator.comparing(item -> item.get("id").getAsString()));
        sortObjects(workforce, Comparator.comparing(item -> item.get("settlement_id").getAsString()));
        root.add("geography", geography);
        root.add("realms", realms); root.add("borders", borders); root.add("settlements", settlements);
        root.add("characters", characters); root.add("workforce", workforce);
        return root;
    }


    /** Materializes map-first demographic cohorts into the canonical wf-people registry. */
    private static void syncEmergentPeople(MinecraftServer server, JsonObject state, int year) {
        if (server == null || state == null || !state.has("bands") || !state.get("bands").isJsonArray()) return;
        JsonArray bands = state.getAsJsonArray("bands");
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null) continue;
            var registry = PeopleMod.registry(map);
            if (registry == null) {
                LOGGER.warn("wf_worldsim: canonical Character registry unavailable for map {}; materialization deferred", map.name);
                continue;
            }
            boolean hasSettledBands = false;
            for (var element : bands) {
                if (!element.isJsonObject()) continue;
                JsonObject band = element.getAsJsonObject();
                if (!band.has("map_id") || !map.name.equals(band.get("map_id").getAsString())) continue;
                String bandId = band.get("id").getAsString();
                int target = (int) Math.max(0L, Math.min(100_000L, band.get("population").getAsLong()));
                List<dev.worldforge.people.Character> residents = registry.all().stream()
                        .filter(person -> person.alive && bandId.equals(person.props.get("emergence.band_id")))
                        .sorted(Comparator.comparingInt(person -> person.birthYear))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                while (residents.size() < target) {
                    dev.worldforge.people.Character created = createEmergentResident(registry, map, bandId, residents, year, residents.size());
                    if (created == null) break;
                    residents.add(created);
                }
                // The native cohort count is a demographic target, not an order to kill
                // canonical people. A lower target can result from migration, scarcity or
                // modelled decline; only the individual life/death simulation may end a life.
                // Keep every living Character and expose the materialized count separately.
                if (residents.size() != target) {
                    band.addProperty("materialized_population", residents.size());
                    band.addProperty("population_target_gap", target - residents.size());
                } else {
                    band.addProperty("materialized_population", residents.size());
                    band.addProperty("population_target_gap", 0);
                }
                if (!band.get("settled").getAsBoolean()) continue;
                hasSettledBands = true;
                String settlementId = "emg_" + bandId;
                try {
                    int x = (int) Math.max(-29_999_000L, Math.min(29_999_000L,
                            Math.round((band.get("x").getAsDouble() - map.w / 2.0) * Math.max(0.001, map.bpp))));
                    int z = (int) Math.max(-29_999_000L, Math.min(29_999_000L,
                            Math.round((band.get("z").getAsDouble() - map.h / 2.0) * Math.max(0.001, map.bpp))));
                    String display = "Settlement " + bandId.substring(0, Math.min(8, bandId.length()));
                    Colony colony = SettleMod.foundEmergentInMemory(server,
                            server.overworld().dimension().location().toString(), settlementId, display,
                            x, z, "worldsim:settlement:" + bandId);
                    List<String> residentIds = registry.all().stream()
                            .filter(person -> person.alive && bandId.equals(person.props.get("emergence.band_id")))
                            .map(person -> person.globalId).toList();
                    if (!SettleMod.replaceCanonicalResidentsInMemory(colony.s.id, residentIds))
                        LOGGER.error("wf_worldsim: canonical residents for settlement {} could not be staged", settlementId);
                    colony.s.setCanonicalPopulation(residentIds.size());
                    for (Worker worker : new ArrayList<>(colony.s.workers)) {
                        if (worker.person == null || worker.person.isBlank() || residentIds.contains(worker.person)) continue;
                        dev.worldforge.people.Character former = PeopleMod.authority().getGlobal(worker.person);
                        if (former == null) continue;
                        String reason = !former.alive ? "death:emergence" : "transfer:emergence";
                        colony.s.removeWorkerByPerson(worker.person, reason);
                    }
                    for (String residentId : residentIds) {
                        dev.worldforge.people.Character resident = registry.all().stream()
                                .filter(person -> residentId.equals(person.globalId)).findFirst().orElse(null);
                        if (resident == null) continue;
                        boolean workingAge = dev.worldforge.people.PopulationSimulation.age(resident, year) >= 18
                                && dev.worldforge.people.PopulationSimulation.canWork(resident, year);
                        if (!workingAge) {
                            colony.s.removeWorkerByPerson(residentId, "transfer:underage");
                            continue;
                        }
                        if (!SettleMod.attachExistingCharacter(colony.s.id, residentId))
                            LOGGER.warn("wf_worldsim: canonical resident {} could not be attached to settlement {}",
                                    residentId, settlementId);
                    }
                } catch (IllegalStateException deferred) {
                    LOGGER.warn("wf_worldsim: settlement materialization deferred for band {}: {}", bandId, deferred.getMessage());
                }
            }
            List<dev.worldforge.people.Character> emergentResidents = registry.all().stream()
                    .filter(person -> person.props.containsKey("emergence.band_id")).toList();
            boolean peopleDurable = emergentResidents.isEmpty()
                    || PeopleMod.persistCharacter(emergentResidents.get(0), server);
            if (!peopleDurable) {
                LOGGER.error("wf_worldsim: canonical residents for map {} could not be durably saved; settlement batch deferred", map.name);
                continue;
            }
            if (hasSettledBands && !SettleMod.saveDurably(server))
                LOGGER.error("wf_worldsim: settlement/Worker batch for map {} could not be durably saved", map.name);
        }
    }

    private static Set<String> emergentNeighbors(JsonObject polity, JsonArray polities, JsonArray bands, MapData map) {
        Set<String> own = new HashSet<>();
        if (polity.has("settlement_ids") && polity.get("settlement_ids").isJsonArray())
            for (var id : polity.getAsJsonArray("settlement_ids")) own.add(id.getAsString());
        Map<String, JsonObject> samples = new HashMap<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (map.name.equals(band.get("map_id").getAsString())) samples.put(band.get("id").getAsString(), band);
        }
        double maxDistanceSq = Math.pow(Math.min(map.w, map.h) / 4.0, 2.0);
        Set<String> neighbors = new TreeSet<>();
        // Emergent settlement clusters inherit the real political borders of the map
        // provinces they occupy or touch. This bridges new settlements into authored
        // countries without inventing raster provinces or a parallel territory registry.
        for (String bandId : own) {
            JsonObject band = samples.get(bandId);
            if (band == null) continue;
            int px = Math.max(0, Math.min(map.w - 1, band.get("x").getAsInt()));
            int pz = Math.max(0, Math.min(map.h - 1, band.get("z").getAsInt()));
            TitleData.Title province = map.titles == null ? null : map.titles.provinceAtPixelPublic(px, pz);
            if (province == null || map.titles == null) continue;
            TitleData.Realm occupying = map.titles.realmOf(province);
            if (occupying != null && !occupying.id.equals("emergent-" + polity.get("id").getAsString()))
                neighbors.add(occupying.id);
            for (int adjacentProvinceId : province.neighbors) {
                TitleData.Title adjacentProvince = map.titles.get(adjacentProvinceId);
                TitleData.Realm adjacentRealm = adjacentProvince == null ? null : map.titles.realmOf(adjacentProvince);
                if (adjacentRealm != null && !adjacentRealm.id.equals("emergent-" + polity.get("id").getAsString()))
                    neighbors.add(adjacentRealm.id);
            }
        }
        for (var item : polities) {
            if (!item.isJsonObject()) continue;
            JsonObject other = item.getAsJsonObject();
            String otherId = other.get("id").getAsString();
            if (otherId.equals(polity.get("id").getAsString()) || !map.name.equals(other.get("map_id").getAsString())) continue;
            if (!other.has("settlement_ids") || !other.get("settlement_ids").isJsonArray()) continue;
            boolean adjacent = false;
            for (var a : polity.getAsJsonArray("settlement_ids")) {
                JsonObject first = samples.get(a.getAsString());
                if (first == null) continue;
                for (var b : other.getAsJsonArray("settlement_ids")) {
                    JsonObject second = samples.get(b.getAsString());
                    if (second == null) continue;
                    double dx = first.get("x").getAsDouble() - second.get("x").getAsDouble();
                    double dz = first.get("z").getAsDouble() - second.get("z").getAsDouble();
                    if (dx * dx + dz * dz <= maxDistanceSq) { adjacent = true; break; }
                }
                if (adjacent) break;
            }
            if (adjacent) neighbors.add("emergent-" + otherId);
        }
        return neighbors;
    }

    /** Counts distinct canonical map provinces occupied by the polity's settlement network. */
    private static int emergentProvinceCount(Set<String> bandIds, JsonArray bands, MapData map) {
        if (bandIds == null || bandIds.isEmpty() || bands == null || map == null || map.titles == null) return 0;
        Set<Integer> provinceIds = new HashSet<>();
        for (var item : bands) if (item.isJsonObject()) {
            JsonObject band = item.getAsJsonObject();
            if (!map.name.equals(band.get("map_id").getAsString()) || !bandIds.contains(band.get("id").getAsString())) continue;
            int x = Math.max(0, Math.min(map.w - 1, band.get("x").getAsInt()));
            int z = Math.max(0, Math.min(map.h - 1, band.get("z").getAsInt()));
            TitleData.Title province = map.titles.provinceAtPixelPublic(x, z);
            if (province != null) provinceIds.add(province.id);
        }
        return provinceIds.size();
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

    /** Race composition is sampled from the polity's canonical living Characters, not copied into a second population registry. */
    private static Map<String, Double> emergentRaceShares(
            List<dev.worldforge.people.Character> members) {
        Map<String, Double> counts = new TreeMap<>();
        for (dev.worldforge.people.Character person : members) {
            Object rawRace = person.props.get("origin.race");
            String race = rawRace == null ? "" : String.valueOf(rawRace).trim();
            if (!race.isEmpty()) counts.merge(race, 1.0, Double::sum);
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

    /** A deterministic culture/race-aware name is stored in the canonical emergence state once. */
    private static String emergentRealmName(String polityId, List<dev.worldforge.people.Character> members,
            String fallback) {
        if (members == null || members.isEmpty()) return fallback;
        Map<String, Long> races = members.stream().map(person -> String.valueOf(person.props.getOrDefault("origin.race", "")))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
        Map<String, Long> cultures = members.stream().map(person -> person.culture == null ? "" : person.culture)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.groupingBy(value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
        String race = races.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse("");
        String culture = cultures.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse("");
        try {
            return PeopleMod.NAMES.generate(race, culture, "realms", new Random(polityId.hashCode()));
        } catch (RuntimeException unavailableNamePool) {
            return fallback;
        }
    }

    /** Binds emergent polities to canonical rulers; succession follows actual Character identity. */
    private static void syncEmergentPolities(MinecraftServer server, JsonObject state, int year) {
        if (server == null || state == null || !state.has("polities") || !state.get("polities").isJsonArray()) return;
        Set<String> activeRealmIds = new HashSet<>();
        for (var element : state.getAsJsonArray("polities")) if (element.isJsonObject())
            activeRealmIds.add("emergent-" + element.getAsJsonObject().get("id").getAsString());
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null) continue;
            var registry = PeopleMod.registry(map);
            if (registry == null) continue;
            boolean changed = false;
            boolean settlementMapMarkersChanged = false;
            for (var element : state.getAsJsonArray("polities")) {
                if (!element.isJsonObject()) continue;
                JsonObject polity = element.getAsJsonObject();
                if (!map.name.equals(polity.get("map_id").getAsString())) continue;
                String polityId = polity.get("id").getAsString();
                String realmId = "emergent-" + polityId;
                activeRealmIds.add(realmId);
                String dynastyId = polity.get("dynasty_id").getAsString();
                String polityName = polity.has("name") ? polity.get("name").getAsString() : polityId;
                Set<String> bandIds = new LinkedHashSet<>();
                if (polity.has("settlement_ids") && polity.get("settlement_ids").isJsonArray())
                    for (var band : polity.getAsJsonArray("settlement_ids")) bandIds.add(band.getAsString());
                List<dev.worldforge.people.Character> members = registry.all().stream()
                        .filter(person -> person.alive && bandIds.contains(String.valueOf(person.props.get("emergence.band_id"))))
                        .toList();
                Set<String> neighbors = emergentNeighbors(polity, state.getAsJsonArray("polities"),
                        state.getAsJsonArray("bands"), map);
                String biomes = emergentBiomeSummary(bandIds, state.getAsJsonArray("bands"), map);
                String currentRuler = polity.has("ruler_global_id") && !polity.get("ruler_global_id").isJsonNull()
                        ? polity.get("ruler_global_id").getAsString() : "";
                TitleData.Realm initialProjection = RealmsMod.registerEmergentRealm(realmId,
                        polity.get("name").getAsString(), map.name, currentRuler,
                        polity.get("population").getAsLong(), polity.get("stability").getAsInt(),
                        bandIds.size(), neighbors, biomes, emergentRaceShares(members));
                initialProjection.props.put("emergence.province_count", emergentProvinceCount(
                        bandIds, state.getAsJsonArray("bands"), map));
                if (members.isEmpty()) continue;
                if (polityName.equals(polityId + " Confederation") || polityName.startsWith("band_")) {
                    String generatedName = emergentRealmName(polityId, members, polityName);
                    if (!generatedName.equals(polityName)) {
                        polityName = generatedName;
                        polity.addProperty("name", polityName);
                    }
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
                    dev.worldforge.people.Character previous = registry.all().stream()
                            .filter(person -> person.globalId.equals(previousRulerId)).findFirst().orElse(null);
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
                    // The Character's house is authoritative: descendants inherit it through addChild().
                    ruler.house = dynastyId;
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
                    binding.addProperty("ruler_global_id", ruler.globalId);
                    int generation = previousRulerId.isBlank() || foundedNewDynasty ? 1
                            : (polity.has("dynasty_generations") ? polity.get("dynasty_generations").getAsInt()
                                : (binding.has("generation") ? binding.get("generation").getAsInt() : 1)) + 1;
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
                    if (!Objects.equals(ruler.house, dynastyId)) {
                        ruler.house = dynastyId;
                        if (!PeopleMod.persistCharacter(ruler, server)) {
                            LOGGER.error("wf_worldsim: canonical house for ruler {} could not be persisted", ruler.globalId);
                            continue;
                        }
                    }
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
                    if (!RealmsMod.POLITICS.setTitleRank(titleId, emergentTitleRank(realmId))) {
                        LOGGER.warn("wf_worldsim: deferred rank change for {}: {}", titleId, RealmsMod.POLITICS.lastError);
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
                if (RealmsMod.POLITICS.dynasty(dynastyId) == null)
                    RealmsMod.POLITICS.registerDynasty(dynastyId, ruler.globalId);
                else RealmsMod.POLITICS.addDynastyMember(dynastyId, ruler.globalId);
                TitleData.Realm politicalActor = RealmsMod.registerEmergentRealm(realmId,
                        polityName, map.name, ruler.globalId,
                        polity.get("population").getAsLong(), polity.get("stability").getAsInt(),
                        bandIds.size(), neighbors, biomes, emergentRaceShares(members));
                politicalActor.ruler = ruler.name + (ruler.house == null || ruler.house.isBlank() ? "" : " " + ruler.house);
                int politicalLevel = RealmsMod.REALMS.level(realmId);
                politicalActor.props.put("emergence.form_stage", RealmsMod.DEFS.levels.isEmpty()
                        ? "band" : RealmsMod.DEFS.levels.get(politicalLevel).id());
                politicalActor.props.put("emergence.form_stage_name", RealmsMod.DEFS.levels.isEmpty()
                        ? "Стоянка" : RealmsMod.DEFS.levels.get(politicalLevel).name());
                politicalActor.props.put("emergence.title_rank", emergentTitleRank(realmId).name().toLowerCase(Locale.ROOT));
                // The political realm points at the same physically materialized colonies;
                // this is a projection, not a second settlement/territory registry.
                List<String> physicalSettlementIds = new ArrayList<>();
                List<String> physicalSettlementNames = new ArrayList<>();
                List<String> physicalSettlementCenters = new ArrayList<>();
                for (String bandId : bandIds) {
                    String settlementId = "emg_" + bandId;
                    Colony physical = SettleMod.colony(settlementId);
                    if (physical == null) continue;
                    if (!realmId.equals(SettleMod.realmOf(physical))
                            && !SettleMod.bindEmergentRealmInMemory(settlementId, realmId)) continue;
                    if (!realmId.equals(SettleMod.realmOf(physical))) continue;
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
                for (String bandId : bandIds) {
                    String settlementId = "emg_" + bandId;
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
                changed = true;
            }
            // Fill the ordinary council, steward, marshal, chancellor and household offices
            // for newly emerged realms through the existing deterministic Courts.ensure pipeline.
            int newCourtPositions = dev.worldforge.people.Courts.ensure(map.titles,
                    EditorManager.editorFor(map), registry, PeopleMod.ROLES, PeopleMod.NAMES,
                    PeopleMod.seed(map), year);
            if (newCourtPositions > 0) changed = true;
            if (changed) {
                dev.worldforge.people.Character durable = registry.all().stream()
                        .filter(person -> person.alive && (person.props.containsKey("emergence.polity_id")
                                || person.posts.stream().anyMatch(post -> post.place.startsWith("realm:"))))
                        .findFirst().orElse(null);
                if (durable != null && !PeopleMod.persistCharacter(durable, server))
                    LOGGER.error("wf_worldsim: ruler/dynasty/court bindings for map {} could not be persisted", map.name);
            }
            if (settlementMapMarkersChanged)
                dev.worldforge.mod.PoliticalMapRenderer.regenerateAll(map);
        }
        RealmsMod.reconcileEmergentRealms(activeRealmIds);
        if (!activeRealmIds.isEmpty() && !SettleMod.saveDurably(server))
            LOGGER.error("wf_worldsim: emergent settlement realm bindings could not be durably saved");
    }

    private static dev.worldforge.people.Character createEmergentResident(
            dev.worldforge.people.CharacterRegistry registry, MapData map, String bandId,
            List<dev.worldforge.people.Character> residents, int year, int ordinal) {
        dev.worldforge.people.Character father = null, mother = null;
        for (dev.worldforge.people.Character candidate : residents) {
            if (!candidate.alive || dev.worldforge.people.PopulationSimulation.age(candidate, year) < 18) continue;
            if ("m".equals(candidate.gender) && father == null) father = candidate;
            if ("f".equals(candidate.gender) && mother == null) mother = candidate;
        }
        if (father == null || mother == null || !father.spouse.equals(mother.id) || !mother.spouse.equals(father.id)) {
            String gender = father == null ? "m" : (mother == null ? "f" : (ordinal % 2 == 0 ? "m" : "f"));
            dev.worldforge.people.Character adult = createEmergentAdult(registry, map, bandId, gender, year, ordinal);
            if (adult == null) return null;
            if ("m".equals(gender)) father = adult; else mother = adult;
            if (father != null && mother != null && father.spouse.isBlank() && mother.spouse.isBlank()) {
                try { registry.marry(father.id, mother.id, year); } catch (RuntimeException ignored) { }
            }
            return adult;
        }
        String gender = ordinal % 2 == 0 ? "m" : "f";
        String name = emergentName(map, bandId, father.culture, gender, ordinal, year);
        dev.worldforge.people.Character child = registry.addChild(father, mother, name, gender, year);
        tagEmergentResident(child, bandId);
        return child;
    }

    private static dev.worldforge.people.Character createEmergentAdult(
            dev.worldforge.people.CharacterRegistry registry, MapData map, String bandId,
            String gender, int year, int ordinal) {
        String culture = PeopleMod.NAMES.culture("");
        String name = emergentName(map, bandId, culture, gender, ordinal, year);
        String house;
        try { house = PeopleMod.NAMES.pickHouse("", culture, new Random(PeopleMod.seed(map) ^ bandId.hashCode() ^ ordinal)); }
        catch (RuntimeException ignored) { house = "House " + bandId.substring(0, Math.min(8, bandId.length())); }
        dev.worldforge.people.Character person = registry.create(name, house, culture, gender,
                year - (18 + Math.floorMod(bandId.hashCode() + ordinal * 13, 24)));
        tagEmergentResident(person, bandId);
        person.props.put("identity.source", "autonomous-emergence");
        return person;
    }

    private static void tagEmergentResident(dev.worldforge.people.Character person, String bandId) {
        person.props.put("emergence.band_id", bandId);
        person.props.put("emergence.household_id", bandId);
        person.props.put("population.location", bandId);
        person.props.put("emergence.origin", "map-first");
    }

    private static String emergentName(MapData map, String bandId, String culture, String gender, int ordinal, int year) {
        try {
            return PeopleMod.NAMES.pickGivenName("", culture, gender,
                    new Random(PeopleMod.seed(map) ^ ((long) bandId.hashCode() << 32) ^ ordinal ^ year));
        } catch (RuntimeException ignored) {
            return "Settler-" + Integer.toUnsignedString(Objects.hash(map.name, bandId, ordinal), 36);
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
                Colony trainingColony = SettleMod.COLONIES.values().stream()
                        .filter(c -> c != null && c.s != null && realm.equals(SettleMod.realmOf(c)))
                        .filter(c -> c.s.buildings.stream().anyMatch(b -> "barracks".equals(b.def.id)
                                && b.state == Building.State.DONE && !b.paused
                                && c.s.assigned(b.uid).stream().anyMatch(worker -> worker.lifeAction.isBlank() && "trainer".equals(worker.workRole()))))
                        .findFirst().orElse(null);
                if (trainingColony == null) return false;
                UnitType levy = army.types.values().stream().filter(unit -> unit.levy)
                        .sorted(Comparator.comparing(unit -> unit.id)).findFirst().orElse(null);
                int count = (int) Math.min(wanted, Math.floor(army.pool(realm)));
                if (levy == null || count <= 0) return false;
                army.recruitWithPersistence(requestId, "raise", realm, levy.id, count);
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
        while (journal.size() > 10000) journal.remove(0);

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
            WorldForgeApi.postEvent(type.equals("declare_war") ? "war"
                            : (type.equals("dynastic_marriage") ? "people"
                            : (type.equals("build") || type.equals("reassign_profession") ? "settlement" : "diplomacy")),
                    outcome + ": " + subject + (reason.isBlank() ? "" : ". Причина: " + reason),
                    affectedRealms, Math.max(0L, day * 1440L));
        }
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
