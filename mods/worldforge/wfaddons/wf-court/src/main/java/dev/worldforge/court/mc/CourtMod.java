package dev.worldforge.court.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.court.CourtActions;
import dev.worldforge.court.CourtSim;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import net.minecraft.world.level.storage.LevelResource;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аддон поверх wf_people: двор государств (совет, браки, интриги, фракции, события, наследование).
 * События из датапаков (wf_events), состояние - wf_court.json в папке карты, ежедневный тик по игровым часам, /wfcourt и окно (клавиша V).
 */
@Mod("wf_court")
public class CourtMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final int COURT_STEP = 100, SAVE_EVERY = 6000;
    private static final Map<MapData, CourtSim> COURTS = new ConcurrentHashMap<>();
    /** Autonomous courts for vanilla/non-map dimensions, backed by the same canonical CharacterRegistry. */
    private static final Map<String, CourtSim> VIRTUAL_COURTS = new ConcurrentHashMap<>();
    private static final Map<String, TitleData> VIRTUAL_DATA = new ConcurrentHashMap<>();
    /** Исходные JSON событий: уходят клиенту вместе со срезом двора. */
    private static volatile List<String> EVENT_JSON = List.of();
    private static long ticks;

    public CourtMod() {
        MinecraftForge.EVENT_BUS.register(this);
        CourtActions.register(editor -> {
            for (MapData m : WorldForgeMod.loadedMaps().values()) if (EditorManager.editorFor(m) == editor) return court(m);
            return null;
        });
    }

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) {
        COURTS.clear();
        VIRTUAL_COURTS.clear();
        VIRTUAL_DATA.clear();
        ticks = 0;
        List<String> evs = new ArrayList<>();
        Map<ResourceLocation, Resource> found = new TreeMap<>(e.getServer().getResourceManager().listResources("wf_events", rl -> rl.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { evs.add(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_court: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        EVENT_JSON = evs;
        LOGGER.info("wf_court: событий: {}", evs.size());
    }

    public static List<String> eventJson() { return EVENT_JSON; }

    /** Двор карты: события из датапаков, состояние - wf_court.json; связи с войнами идут через действия карты и свойства государств. */
    public static CourtSim court(MapData m) {
        if (m == null || m.titles == null) return null;
        CharacterRegistry reg = PeopleMod.registry(m);
        if (reg == null) return null;
        return COURTS.computeIfAbsent(m, k -> {
            var ed = EditorManager.editorFor(k);
            CourtSim s = new CourtSim(k.titles, ed, reg, PeopleMod.ROLES, PeopleMod.NAMES, PeopleMod.seed(k), PeopleMod.YEAR);
            for (String j : EVENT_JSON) {
                try { s.addEvent(j); } catch (Exception ex) { LOGGER.error("wf_court: ошибка события: {}", ex.toString()); }
            }
            s.hooks = new CourtSim.Hooks() {
                @Override public boolean atWar(String realm) {
                    TitleData.Realm r = k.titles.realms.get(realm);
                    return r != null && r.props.get("war.state") != null && !String.valueOf(r.props.get("war.state")).isEmpty() && !String.valueOf(r.props.get("war.state")).equals("peace");
                }

                @Override public void independence(String realm, String liege) {
                    var ctx = new dev.worldforge.mod.api.ActionContext(ed, "court", true, realm, 0, java.util.List.of());
                    if (dev.worldforge.mod.api.WorldForgeApi.action("war_independence") != null) dev.worldforge.mod.api.WorldForgeApi.run("war_independence", ctx);
                }

                @Override public boolean vassalDemands(String liege, List<String> vassals) {
                    if (dev.worldforge.mod.api.WorldForgeApi.action("vassal_demands") == null) return false;
                    var ctx = new dev.worldforge.mod.api.ActionContext(ed, "court", true, liege, 0,
                            List.of(String.join(",", vassals)));
                    var result = dev.worldforge.mod.api.WorldForgeApi.run("vassal_demands", ctx);
                    if (!result.ok) LOGGER.warn("wf_court: vassal demands for {} were not applied: {}",
                            liege, result.message);
                    return result.ok;
                }

                @Override public void news(String text) { dev.worldforge.mod.api.WorldForgeApi.postEvent("court", text, java.util.List.of()); }

                private void tell(String player, String text) {
                    MinecraftServer srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                    var sp = srv == null ? null : srv.getPlayerList().getPlayerByName(player);
                    if (sp != null) sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
                }

                @Override public void playerHeir(String player, String oldId, String newId) {
                    var n = s.reg.get(newId);
                    tell(player, "Ваш герой умер. Теперь вы ведёте " + (n == null ? newId : n.name) + ".");
                }

                @Override public void playerDied(String player) {
                    tell(player, "Ваш герой умер без наследника. Возьмите нового: /wfcourt play <id> или /wfcourt play realm <государство>.");
                }
            };
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                Path f = EditorManager.overlayDir(server, k).resolve("wf_court.json");
                try { if (Files.exists(f)) s.fromJson(Files.readString(f)); }
                catch (Exception ex) { LOGGER.error("wf_court: не удалось прочитать {}: {}", f, ex.toString()); }
            }
            return s;
        });
    }

    /** Persist one court through the shared crash-recoverable worldforge file transaction. */
    public static boolean persistNow(MinecraftServer server, MapData map) {
        if (server == null || map == null) return false;
        CourtSim court = COURTS.get(map);
        if (court == null) return false;
        try {
            Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("worldforge");
            Path target = EditorManager.overlayDir(server, map).resolve("wf_court.json");
            dev.worldforge.mod.AtomicFileBatch.commit(root, Map.of(
                    target, court.toJson().getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_court: не удалось атомарно сохранить двор карты '{}': {}", map.name, ex.toString(), ex);
            return false;
        }
    }

    static void saveAll(MinecraftServer server) {
        for (MapData map : COURTS.keySet()) persistNow(server, map);
        if (server == null) return;
        for (Map.Entry<String, CourtSim> entry : VIRTUAL_COURTS.entrySet()) {
            try {
                Path root = server.getWorldPath(LevelResource.ROOT).resolve("worldforge");
                Files.createDirectories(root);
                Files.writeString(virtualCourtFile(server, entry.getKey()), entry.getValue().toJson());
            } catch (Exception ex) {
                LOGGER.error("wf_court: не удалось сохранить автономный двор измерения {}: {}",
                        entry.getKey(), ex.toString(), ex);
            }
        }
    }

    private static Path virtualCourtFile(MinecraftServer server, String dimension) {
        String key = UUID.nameUUIDFromBytes(dimension.getBytes(StandardCharsets.UTF_8)).toString();
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge")
                .resolve("wf_court_dimension_" + key + ".json");
    }

    private static TitleData virtualTitleData(String dimension) {
        return VIRTUAL_DATA.computeIfAbsent(dimension, ignored -> TitleData.create(
                "{\\"format\\":\\"worldforge-titles/1\\",\\"blocks_per_pixel\\":1,\\"tiers\\":[],\\"titles\\":[]}",
                "{\\"format\\":\\"worldforge-realms/1\\",\\"realms\\":[]}",
                new short[0], 0, 0));
    }

    /** Refreshes the virtual realm index from canonical ruler offices, never creating duplicate people or titles. */
    private static CourtSim virtualCourt(MinecraftServer server, String dimension, CharacterRegistry registry) {
        if (server == null || registry == null) return null;
        TitleData data = virtualTitleData(dimension);
        int added = 0;
        for (Character person : registry.all()) {
            if (!person.alive || !person.props.containsKey("emergence.polity_id")) continue;
            for (Character.Post post : person.posts) {
                if (!post.place.startsWith("realm:")) continue;
                String realmId = post.place.substring("realm:".length());
                if (realmId.isBlank()) continue;
                TitleData.Realm realm = data.realms.computeIfAbsent(realmId, id -> {
                    TitleData.Realm created = new TitleData.Realm();
                    created.id = id;
                    created.name = String.valueOf(person.props.getOrDefault("emergence.polity_name", id));
                    created.ruler = person.globalId;
                    created.rgb = 0x777777;
                    created.overlordId = "";
                    created.relation = "";
                    created.areaBlocks = 0;
                    return created;
                });
                realm.ruler = person.globalId;
                Object name = person.props.get("emergence.polity_name");
                if (name instanceof String text && !text.isBlank()) realm.name = text;
                added++;
            }
        }
        data.refreshRealmIndex();
        if (data.realmList().isEmpty()) return null;
        CourtSim sim = VIRTUAL_COURTS.computeIfAbsent(dimension, key -> {
            long seed = WorldForgeMod.worldId() == null ? key.hashCode()
                    : WorldForgeMod.worldId().toString().hashCode() ^ key.hashCode();
            CourtSim created = new CourtSim(data, null, registry, PeopleMod.ROLES,
                    PeopleMod.NAMES, seed, PeopleMod.YEAR);
            for (String json : EVENT_JSON) {
                try { created.addEvent(json); }
                catch (Exception ex) { LOGGER.error("wf_court: ошибка события автономного двора: {}", ex.toString()); }
            }
            Path file = virtualCourtFile(server, key);
            try { if (Files.exists(file)) created.fromJson(Files.readString(file)); }
            catch (Exception ex) { LOGGER.error("wf_court: не удалось прочитать автономный двор {}: {}", key, ex.toString()); }
            LOGGER.info("wf_court: autonomous virtual court attached dimension={} realms={} canonical_characters={}",
                    key, data.realmList().size(), registry.size());
            return created;
        });
        return sim;
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        saveAll(e.getServer());
        COURTS.clear();
        VIRTUAL_COURTS.clear();
        VIRTUAL_DATA.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % COURT_STEP == 0) {
                int days = dev.worldforge.mod.api.WorldForgeApi.clock().pull("wf_court");
                if (days > 0) {
                    for (MapData m : WorldForgeMod.loadedMaps().values()) {
                        CourtSim cs = m.titles == null ? null : court(m);
                        if (cs != null) cs.tick(days);
                    }
                    // Vanilla and other non-map dimensions previously had no CourtSim:
                    // their NPCs received offices, but autonomous marriage, births,
                    // succession pressure and court AI never advanced. Attach the same
                    // CourtSim engine to each canonical dimension registry.
                    for (Map.Entry<String, CharacterRegistry> entry : PeopleMod.dimensionRegistries().entrySet()) {
                        CourtSim cs = virtualCourt(e.getServer(), entry.getKey(), entry.getValue());
                        if (cs != null) cs.tick(days);
                    }
                }
            }
            if (ticks % SAVE_EVERY == 0) saveAll(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_court: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) { CourtCommands.register(e.getDispatcher()); }
}
