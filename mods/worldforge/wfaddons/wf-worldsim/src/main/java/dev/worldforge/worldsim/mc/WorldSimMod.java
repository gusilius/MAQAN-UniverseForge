package dev.worldforge.worldsim.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.Diplomacy;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.court.CourtSim;
import dev.worldforge.court.mc.CourtMod;
import dev.worldforge.court.Marriages;
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

    public WorldSimMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        lastDecisionDay = Long.MIN_VALUE;
        lodTicks = 0;
        loadState(event.getServer());
        LOGGER.info("wf_worldsim: autonomous coordinator started; native Rust core={}", NativeWorldSimulation.load() ? "available" : "unavailable");
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) saveState(server);
        lastDecisionDay = Long.MIN_VALUE;
        lodTicks = 0;
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
        long day = WorldForgeApi.clock().dayNumber();
        if (day <= 0 || (lastDecisionDay != Long.MIN_VALUE
                && day - lastDecisionDay < DECISION_INTERVAL_DAYS)) return;
        // Compare elapsed game days instead of day % interval: at accelerated speeds
        // the clock may jump over exact multiples and otherwise starve the coordinator.
        if (!NativeWorldSimulation.load()) return;
        try {
            JsonObject snapshot = snapshot(server, day);
            byte[] input = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
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
                    // Reuse the project's canonical durable ActionExecutionService. Its RUNNING
                    // checkpoint prevents replay after a crash between a domain save and receipt.
                    String requestId = "worldsim:" + proposalId;
                    String payload = GSON.toJson(kind);
                    long worldMinute = Math.max(0L, day * 1440L);
                    var task = WorldForgeApi.actionExecution().execute(
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
                        }))));
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
        root.addProperty("world_id", server.getWorldData().getLevelName());
        root.addProperty("seed", server.getWorldData().worldGenOptions().seed() & Long.MAX_VALUE);
        root.addProperty("day", day);
        JsonArray realms = new JsonArray(), borders = new JsonArray(), settlements = new JsonArray(), characters = new JsonArray(), workforce = new JsonArray();
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
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || colony.s == null) continue;
            var s = colony.s;
            String settlementRealm = SettleMod.realmOf(colony);
            if (settlementRealm == null || settlementRealm.isBlank()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", s.id);
            item.addProperty("realm_id", settlementRealm);
            item.addProperty("population", s.workers.size());
            item.addProperty("food", fixed(s.amount("food")));
            item.addProperty("food_capacity", fixed(s.capacity("food")));
            item.addProperty("wood", fixed(s.amount("wood")));
            item.addProperty("stone", fixed(s.amount("stone")));
            item.addProperty("housing", s.housing());
            item.addProperty("housing_used", s.workers.size());
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
            item.addProperty("local_pressure", Math.max(0, s.workers.size() - s.housing()));
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
                c.addProperty("id", person.id); c.addProperty("realm_id", realm);
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
        root.add("realms", realms); root.add("borders", borders); root.add("settlements", settlements);
        root.add("characters", characters); root.add("workforce", workforce);
        return root;
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
                        colony.s.place(building, x + (hash & 7), z + ((hash >>> 3) & 7));
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
            int changed = 0;
            for (Worker worker : colony.s.workers) {
                if (changed >= wanted) break;
                boolean sourceMatches = "unemployed".equals(from) ? worker.generalist() : from.equals(worker.permanentProfession());
                if (!sourceMatches || worker.building != 0 || !worker.lifeAction.isBlank()) continue;
                colony.s.setProfession(worker.id, to);
                changed++;
            }
            return changed > 0 && SettleMod.saveDurably(server);
        }
        if ("recruit".equals(type)) {
            String realm = action.get("realm").getAsString();
            int wanted = Math.max(1, action.get("count").getAsInt());
            for (MapData map : WorldForgeMod.loadedMaps().values()) {
                if (map.titles == null || !map.titles.realms.containsKey(realm)) continue;
                Colony trainingColony = SettleMod.COLONIES.values().stream()
                        .filter(c -> c != null && c.s != null && realm.equals(SettleMod.realmOf(c)))
                        .filter(c -> c.s.buildings.stream().anyMatch(b -> "barracks".equals(b.def.id)
                                && b.state == Building.State.DONE && !b.paused
                                && c.s.assigned(b.uid).stream().anyMatch(worker -> worker.lifeAction.isBlank() && "trainer".equals(worker.workRole()))))
                        .findFirst().orElse(null);
                if (trainingColony == null) return false;
                ArmyEngine army = ArmyMod.armies(map);
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
                var a = court.reg.get(first); var b = court.reg.get(second);
                if (a != null && b != null) {
                    var result = Marriages.propose(court, first, second);
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
            for (MapData map : WorldForgeMod.loadedMaps().values()) {
                if (map.titles == null || !map.titles.realms.containsKey(attacker) || !map.titles.realms.containsKey(defender)) continue;
                WarEngine war = WarMod.engine(map);
                if (war.cbs.isEmpty()) return false;
                String cb = war.cbs.keySet().stream().sorted().findFirst().orElse("");
                war.declareOnce(requestId, attacker, defender, cb, List.of());
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
        WorldForgeApi.postEvent(type.equals("declare_war") ? "war"
                        : (type.equals("dynastic_marriage") ? "people"
                        : (type.equals("build") || type.equals("reassign_profession") ? "settlement" : "diplomacy")),
                outcome + ": " + subject + (reason.isBlank() ? "" : ". Причина: " + reason),
                affectedRealms, Math.max(0L, day * 1440L));
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
            state.add("shared_event_log", com.google.gson.JsonParser.parseString(WorldForgeApi.events().toJson()));
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
            if (state.has("events") && state.get("events").isJsonArray())
                for (var event : state.getAsJsonArray("events")) if (event.isJsonObject()) journal.add(GSON.toJson(event));
            if (state.has("shared_event_log") && state.get("shared_event_log").isJsonObject())
                WorldForgeApi.events().fromJson(GSON.toJson(state.get("shared_event_log")));
            LOGGER.info("wf_worldsim: restored {} proposal records, {} shared world events and {} settlement cooldowns",
                    journal.size(), WorldForgeApi.events().size(), lastProjectDay.size());
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
