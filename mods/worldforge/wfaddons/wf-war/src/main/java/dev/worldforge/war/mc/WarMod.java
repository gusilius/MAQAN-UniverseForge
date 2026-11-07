package dev.worldforge.war.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.war.War;
import dev.worldforge.war.WarActions;
import dev.worldforge.war.WarEngine;
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
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Склейка wf-war с Minecraft: по движку на каждую карту, ход времени, сохранение в мир, команда /wfwar. Армии и экономика - аддон wf-army.
 * Один военный день = {@link #TICKS_PER_DAY} тиков (минута реального времени).
 */
@Mod("wf_war")
public class WarMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final int TICKS_PER_DAY = 1200, STEP = 100, SAVE_EVERY = 6000;
    private static final Map<MapData, WarEngine> ENGINES = new ConcurrentHashMap<>();
    /** Исходные JSON датапаков wf_war (поводы войны и типы отрядов): уходят клиенту вместе с состоянием. */
    static volatile java.util.List<String> RAW = java.util.List.of();
    private static double acc;
    private static long ticks;

    public WarMod() {
        MinecraftForge.EVENT_BUS.register(this);
        WarActions.register(editor -> {
            for (MapData m : WorldForgeMod.loadedMaps().values()) if (EditorManager.editorFor(m) == editor) return engine(m);
            return null;
        });
    }

    /** Движок карты; создаётся при первом обращении, поводы берутся из датапаков, состояние - из папки мира. */
    public static WarEngine engine(MapData m) {
        return ENGINES.computeIfAbsent(m, k -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            WarEngine e = new WarEngine(EditorManager.editorFor(k));
            if (server != null) {
                var found = new java.util.TreeMap<ResourceLocation, Resource>(server.getResourceManager().listResources("wf_war", rl -> rl.getPath().endsWith(".json")));
                java.util.List<String> raw = new java.util.ArrayList<>();
                for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
                    try (InputStream in = r.getValue().open()) {
                        String txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        raw.add(txt);
                        e.loadCasusBelli(txt);
                    } catch (Exception ex) {
                        LOGGER.error("wf_war: ошибка в {}: {}", r.getKey(), ex.toString());
                    }
                }
                RAW = raw;
                Path f = EditorManager.overlayDir(server, k).resolve("wf_war.json");
                try {
                    if (Files.exists(f)) {
                        e.fromJson(Files.readString(f));
                        LOGGER.info("wf_war: войны карты '{}' загружены (день {})", k.name, e.day);
                    }
                } catch (Exception ex) {
                    LOGGER.error("wf_war: не удалось прочитать {}: {}", f, ex.toString());
                }
            }
            return e;
        });
    }

    private static void saveAll(MinecraftServer server) {
        for (Map.Entry<MapData, WarEngine> en : ENGINES.entrySet()) {
            try {
                Path dir = EditorManager.overlayDir(server, en.getKey());
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("wf_war.json"), en.getValue().toJson());
            } catch (Exception ex) {
                LOGGER.error("wf_war: не удалось сохранить войны карты '{}': {}", en.getKey().name, ex.toString());
            }
        }
    }

    @SubscribeEvent
    public void onStart(ServerAboutToStartEvent e) {
        ENGINES.clear();
        acc = 0;
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        saveAll(e.getServer());
        ENGINES.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        if (ticks % STEP != 0) return;
        int days = dev.worldforge.mod.api.WorldForgeApi.clock().pull("wf_war");
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            WarEngine en = engine(m);
            if (days > 0) en.advance(days);   // если есть аддон армий, он ведёт время и двигает сам ход войн
        }
        if (ticks % SAVE_EVERY == 0) saveAll(e.getServer());
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("wfwar")
                .then(Commands.literal("screen")
                        .executes(c -> screen(c.getSource(), ""))
                        .then(Commands.argument("realm", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> screen(c.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("log").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> log(c.getSource(), IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("save").requires(s -> s.hasPermission(2)).executes(c -> { saveAll(c.getSource().getServer()); return 1; })));
    }

    private static WarEngine here(CommandSourceStack s) {
        MapData m = EditorManager.dataFor(s.getLevel());
        if (m == null || m.titles == null) {
            s.sendFailure(Component.literal("В этом измерении нет политической карты"));
            return null;
        }
        return engine(m);
    }

    private static int screen(CommandSourceStack s, String realm) {
        WarEngine en = here(s);
        if (en == null) return 0;
        net.minecraft.server.level.ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        MapData m = EditorManager.dataFor(s.getLevel());
        if (!realm.isEmpty() && en.editor.data().realms.get(realm) == null) { s.sendFailure(Component.literal("Нет государства " + realm)); return 0; }
        if (realm.isEmpty()) {                                   // по умолчанию - государство, на земле которого стоит игрок
            var pr = m.provinceAt(sp.getX(), sp.getZ());
            var re = pr == null ? null : m.titles.realmOf(pr);
            if (re != null) realm = re.id;
        }
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("realm", realm);
        p.put("raw", new java.util.ArrayList<Object>(RAW));
        p.put("war", en.toJson());
        dev.worldforge.mod.WFNet.sendView(sp, "wf_war:main", dev.worldforge.mod.Json.write(p));
        return 1;
    }

    private static int list(CommandSourceStack s) {
        WarEngine en = here(s);
        if (en == null) return 0;
        TitleData d = en.editor.data();
        int n = 0;
        for (War w : en.wars.values()) {
            if (!w.active) continue;
            n++;
            String line = "#" + w.id + " " + d.realms.get(w.attacker).name + " против " + d.realms.get(w.defender).name + " | счёт " + Math.round(w.score) + " | день " + (en.day - w.startDay);
            s.sendSuccess(() -> Component.literal(line), false);
        }
        if (n == 0) s.sendSuccess(() -> Component.literal("Сейчас никто не воюет (день " + en.day + ")"), false);
        return n;
    }

    private static int log(CommandSourceStack s, int id) {
        WarEngine en = here(s);
        if (en == null) return 0;
        War w = en.wars.get(id);
        if (w == null) {
            s.sendFailure(Component.literal("Нет войны #" + id));
            return 0;
        }
        for (String l : w.log) s.sendSuccess(() -> Component.literal(l), false);
        return w.log.size();
    }
}
