package dev.worldforge.court.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.court.CourtActions;
import dev.worldforge.court.CourtSim;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.people.CharacterRegistry;
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
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) { saveAll(e.getServer()); COURTS.clear(); }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % COURT_STEP == 0) {
                int days = dev.worldforge.mod.api.WorldForgeApi.clock().pull("wf_court");
                if (days > 0) for (MapData m : WorldForgeMod.loadedMaps().values()) { CourtSim cs = m.titles == null ? null : court(m); if (cs != null) cs.tick(days); }
            }
            if (ticks % SAVE_EVERY == 0) saveAll(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_court: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) { CourtCommands.register(e.getDispatcher()); }
}
