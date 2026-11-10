package dev.worldforge.army.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.army.ArmyActions;
import dev.worldforge.army.ArmyEngine;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.war.WarEngine;
import dev.worldforge.war.mc.WarMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аддон поверх wf_war: армии, осады, экономика государства и торговые договоры. Типы отрядов из датапаков (wf_army/units.json),
 * состояние - wf_army.json в папке карты. Армии ведут время войн: ArmyEngine ставит себя водителем WarEngine (driver).
 */
@Mod("wf_army")
public class ArmyMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final int STEP = 100, SAVE_EVERY = 6000;
    private static final Map<MapData, ArmyEngine> ARMIES = new ConcurrentHashMap<>();
    /** Исходные JSON типов отрядов: уходят клиенту вместе с состоянием. */
    static volatile List<String> RAW = List.of();
    private static long ticks;

    public ArmyMod() {
        MinecraftForge.EVENT_BUS.register(this);
        dev.worldforge.mod.api.TreasuryApi.register("army", new dev.worldforge.mod.api.TreasuryApi.Provider() {
            private ArmyEngine of(String realm) {
                for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null && m.titles.realms.containsKey(realm)) return armies(m);
                return null;
            }
            @Override public boolean has(String realm) { return of(realm) != null; }
            @Override public double balance(String realm) { ArmyEngine e = of(realm); return e == null ? 0 : e.gold(realm); }
            @Override public void add(String realm, double delta) { if (of(realm) != null) dev.worldforge.mod.api.TreasuryApi.add(realm, delta, "army-provider-compatibility"); }
        });
        ArmyActions.registerArmies(editor -> {
            for (MapData m : WorldForgeMod.loadedMaps().values()) if (EditorManager.editorFor(m) == editor) return armies(m);
            return null;
        });
    }

    /** Армии карты поверх движка войн; создание ставит их водителем времени войн. */
    public static ArmyEngine armies(MapData m) {
        return ARMIES.computeIfAbsent(m, k -> {
            ArmyEngine a = new ArmyEngine(WarMod.engine(k));
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                List<String> raw = new ArrayList<>();
                var found = new TreeMap<ResourceLocation, Resource>(server.getResourceManager().listResources("wf_army", rl -> rl.getPath().endsWith(".json")));
                for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
                    try (InputStream in = r.getValue().open()) {
                        String txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        a.loadUnits(txt);
                        raw.add(txt);
                    } catch (Exception ex) { LOGGER.error("wf_army: ошибка в {}: {}", r.getKey(), ex.toString()); }
                }
                RAW = raw;
                Path f = EditorManager.overlayDir(server, k).resolve("wf_army.json");
                try { if (Files.exists(f)) a.fromJson(Files.readString(f)); }
                catch (Exception ex) { LOGGER.error("wf_army: не удалось прочитать {}: {}", f, ex.toString()); }
            }
            return a;
        });
    }

    private static void saveAll(MinecraftServer server) {
        for (Map.Entry<MapData, ArmyEngine> en : ARMIES.entrySet()) {
            try {
                Path dir = EditorManager.overlayDir(server, en.getKey());
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("wf_army.json"), en.getValue().toJson());
            } catch (Exception ex) { LOGGER.error("wf_army: не удалось сохранить армии карты '{}': {}", en.getKey().name, ex.toString()); }
        }
    }

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) { ARMIES.clear(); ticks = 0; }

    /** Армии создаются при старте, чтобы водитель времени был подключён до первого дня. */
    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null) armies(m);
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        saveAll(e.getServer()); // wf-army/1 balances are migrated while snapshots are written
        WorldForgeMod.saveTreasuryNow(e.getServer()); // persist those migrations after the army addon has completed
        ARMIES.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % STEP == 0) for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null) armies(m);
            if (ticks % SAVE_EVERY == 0) saveAll(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_army: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("wfarmy")
                .then(Commands.literal("screen")
                        .executes(c -> screen(c.getSource(), ""))
                        .then(Commands.argument("realm", StringArgumentType.word()).executes(c -> screen(c.getSource(), StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("save").requires(s -> s.hasPermission(2)).executes(c -> { saveAll(c.getSource().getServer()); return 1; })));
    }

    private static int screen(CommandSourceStack s, String realm) {
        net.minecraft.server.level.ServerPlayer sp = s.getPlayer();
        MapData m = EditorManager.dataFor(s.getLevel());
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        if (m == null || m.titles == null) { s.sendFailure(Component.literal("В этом измерении нет политической карты")); return 0; }
        if (!realm.isEmpty() && m.titles.realms.get(realm) == null) { s.sendFailure(Component.literal("Нет государства " + realm)); return 0; }
        if (realm.isEmpty()) {
            var pr = m.provinceAt(sp.getX(), sp.getZ());
            var re = pr == null ? null : m.titles.realmOf(pr);
            if (re != null) realm = re.id;
        }
        ArmyEngine ar = armies(m);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("realm", realm);
        p.put("raw", new ArrayList<Object>(RAW));
        p.put("war", ar.war.toJson());
        p.put("army", ar.toJson());
        dev.worldforge.mod.WFNet.sendView(sp, "wf_army:main", dev.worldforge.mod.Json.write(p));
        return 1;
    }
}
