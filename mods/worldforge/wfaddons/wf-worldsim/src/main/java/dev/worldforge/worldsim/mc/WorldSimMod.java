package dev.worldforge.worldsim.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.Diplomacy;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.court.mc.CourtMod;
import dev.worldforge.court.Marriages;
import dev.worldforge.war.WarEngine;
import dev.worldforge.war.mc.WarMod;
import dev.worldforge.settle.mc.Colony;
import dev.worldforge.settle.mc.SettleMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
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
    private static final Map<String, Long> lastProjectDay = new HashMap<>();
    private static final List<String> journal = new ArrayList<>();

    public WorldSimMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        lastDecisionDay = Long.MIN_VALUE;
        loadState(event.getServer());
        LOGGER.info("wf_worldsim: autonomous coordinator started; native Rust core={}", NativeWorldSimulation.load() ? "available" : "unavailable");
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) saveState(server);
        lastDecisionDay = Long.MIN_VALUE;
        lastProjectDay.clear();
        journal.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long day = WorldForgeApi.clock().dayNumber();
        if (day <= 0 || day % DECISION_INTERVAL_DAYS != 0 || day == lastDecisionDay) return;
        lastDecisionDay = day;
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
                try {
                    boolean applied = apply(server, proposal.get("id").getAsString(), kind, day);
                    if (applied) accepted++; else rejected++;
                    recordEvent(day, proposal.get("id").getAsString(), kind, applied);
                } catch (RuntimeException error) {
                    rejected++;
                    LOGGER.warn("wf_worldsim: proposal {} rejected: {}", proposal.get("id"), error.toString());
                }
            }
            saveState(server);
            LOGGER.info("wf_worldsim: day={} realms={} settlements={} proposals={} applied={} rejected={}",
                    day, snapshot.getAsJsonArray("realms").size(), snapshot.getAsJsonArray("settlements").size(),
                    frame.getAsJsonArray("proposals").size(), accepted, rejected);
        } catch (Exception error) {
            LOGGER.error("wf_worldsim: decision cycle failed safely at day {}", day, error);
        }
    }

    private static JsonObject snapshot(MinecraftServer server, long day) {
        JsonObject root = new JsonObject();
        root.addProperty("world_id", server.getWorldData().getLevelName());
        root.addProperty("seed", server.getWorldData().worldGenOptions().seed() & Long.MAX_VALUE);
        root.addProperty("day", day);
        JsonArray realms = new JsonArray(), borders = new JsonArray(), settlements = new JsonArray(), characters = new JsonArray(), workforce = new JsonArray();
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            for (var realm : map.titles.realmList()) {
                JsonObject r = new JsonObject();
                r.addProperty("id", realm.id);
                r.addProperty("population", number(realm.props.get("population"), 100));
                r.addProperty("treasury", (long) (number(realm.props.get("treasury"), 500) * 100));
                r.addProperty("army_strength", (long) number(realm.props.get("army_strength"), 0));
                r.addProperty("stability", (int) number(realm.props.get("stability"), 50));
                r.addProperty("prestige", (int) number(realm.props.get("prestige"), 0));
                r.addProperty("expansion_pressure", (int) number(realm.props.get("expansion_pressure"), 10));
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
                border.addProperty("contested_claim", false);
                border.addProperty("strategic_value", 30);
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
            item.addProperty("workshops", s.count("workshop", false));
            item.addProperty("barracks", s.count("barracks", false));
            item.addProperty("roads", s.count("road", false));
            item.addProperty("local_pressure", Math.max(0, s.workers.size() - s.housing()));
            item.addProperty("last_project_day", lastProjectDay.getOrDefault(s.id, Math.max(0, day - 365)));
            settlements.add(item);
        }
        for (CharacterRegistry registry : PeopleMod.registries().values()) {
            for (dev.worldforge.people.Character person : registry.all()) {
                if (!person.alive) continue;
                String realm = "";
                for (var post : person.posts) if (post.place.startsWith("realm:")) { realm = post.place.substring(6); break; }
                if (realm.isBlank()) continue;
                JsonObject c = new JsonObject();
                c.addProperty("id", person.id); c.addProperty("realm_id", realm);
                c.addProperty("age", Math.max(0, WorldForgeApi.clock().year() - person.birthYear));
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
                        lastProjectDay.put(settlementId, day);
                        return SettleMod.saveDurably(server);
                    }
                }
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
                    return result.ok;
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
        // Recruitment and profession changes remain proposals until their dedicated
        // transactional executors are exposed by wf_army and wf_people.
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
    }

    private static Path stateFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_worldsim.json");
    }

    private static synchronized void saveState(MinecraftServer server) {
        try {
            JsonObject state = new JsonObject();
            JsonObject cooldowns = new JsonObject();
            lastProjectDay.forEach(cooldowns::addProperty);
            state.add("last_project_day", cooldowns);
            JsonArray events = new JsonArray();
            for (String line : journal) events.add(GSON.fromJson(line, JsonObject.class));
            state.add("events", events);
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
        } catch (Exception error) {
            LOGGER.error("wf_worldsim: unable to persist simulation journal; proposals remain idempotent", error);
        }
    }

    private static synchronized void loadState(MinecraftServer server) {
        Path file = stateFile(server);
        if (!Files.exists(file)) return;
        try {
            JsonObject state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (state == null) return;
            lastProjectDay.clear();
            if (state.has("last_project_day") && state.get("last_project_day").isJsonObject()) {
                for (var entry : state.getAsJsonObject("last_project_day").entrySet())
                    lastProjectDay.put(entry.getKey(), entry.getValue().getAsLong());
            }
            journal.clear();
            if (state.has("events") && state.get("events").isJsonArray())
                for (var event : state.getAsJsonArray("events")) if (event.isJsonObject()) journal.add(GSON.toJson(event));
            LOGGER.info("wf_worldsim: restored {} event records and {} settlement cooldowns", journal.size(), lastProjectDay.size());
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
