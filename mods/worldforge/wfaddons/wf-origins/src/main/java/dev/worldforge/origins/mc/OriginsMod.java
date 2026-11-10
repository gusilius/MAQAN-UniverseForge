package dev.worldforge.origins.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.origins.OriginDefs;
import dev.worldforge.origins.Origins;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Склейка wf-origins: расы, классы, профессии из датапаков (wf_origins), состояние в мире, команда /wforigin.
 * Опыт профессий идёт за работу: руда - шахтёр, брёвна - дровосек, урожай - земледелец, ковка - кузнец. Владелец в общей шине - "p:<uuid>".
 */
@Mod("wf_origins")
public class OriginsMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final OriginDefs DEFS = new OriginDefs();
    public static final Origins ORIGINS = new Origins(DEFS);
    private static long ticks;

    public OriginsMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(ORIGINS::publish));
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static String owner(ServerPlayer p) { return "p:" + p.getUUID(); }

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_origins.json"); }

    private static void loadDefs(ResourceManager rm) {
        Map<ResourceLocation, Resource> found = rm.listResources("wf_origins", rl -> rl.getPath().endsWith(".json"));
        OriginDefs staged = new OriginDefs();
        try {
            List<Map.Entry<ResourceLocation, Resource>> ordered = new ArrayList<>(found.entrySet());
            ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));
            for (Map.Entry<ResourceLocation, Resource> resource : ordered) {
                try (InputStream in = resource.getValue().open()) {
                    staged.load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            List<String> problems = staged.validate();
            if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
            DEFS.replaceWith(staged);
            LOGGER.info("wf_origins: загружено рас {}, классов {}, профессий {}, переплетений {}",
                    DEFS.races.size(), DEFS.classes.size(), DEFS.professions.size(), DEFS.synergies.size());
        } catch (Exception ex) {
            // Keep the last known-good registry; never accumulate stale or partially reloaded datapack entries.
            LOGGER.error("wf_origins: набор определений не применён, сохранена предыдущая версия: {}", ex.toString());
        }
    }

    @SubscribeEvent public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }
    @SubscribeEvent public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) OriginsMod::loadDefs); }
    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) ORIGINS.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_origins: не удалось прочитать состояние: {}", ex.toString()); }
    }

    private static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, ORIGINS.toJson()); }
        catch (Exception ex) { LOGGER.error("wf_origins: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) { if (e.phase == TickEvent.Phase.END && ++ticks % 6000 == 0) save(e.getServer()); }

    // ------------------------------------------------------------------ работа даёт опыт профессии

    private static void work(ServerPlayer p, String prof, int units) {
        int r = ORIGINS.work(owner(p), prof, units);
        if (r >= 0) {
            OriginDefs.Profession d = DEFS.professions.get(prof);
            p.sendSystemMessage(Component.literal("Новое звание: " + d.name() + " - " + d.ranks().get(r).name()));
        }
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        BlockState st = e.getState();
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        if (id.endsWith("_ore") || id.equals("ancient_debris")) work(p, "miner", 2);
        else if (id.endsWith("_log") || id.endsWith("_stem")) work(p, "woodcutter", 1);
        else if (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock c && c.isMaxAge(st)) work(p, "farmer", 1);
    }

    @SubscribeEvent
    public void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        String id = BuiltInRegistries.ITEM.getKey(e.getCrafting().getItem()).getPath();
        if (id.endsWith("_sword") || id.endsWith("_axe") || id.endsWith("_pickaxe") || id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") || id.endsWith("_boots")) work(p, "smith", 3);
    }

    // ------------------------------------------------------------------ команда

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wforigin")
                .executes(c -> open(c.getSource()))
                .then(Commands.literal("info").executes(c -> show(c.getSource())))
                .then(Commands.literal("retrain").executes(c -> act(c.getSource(), "retrain", "")))
                .then(Commands.literal("race").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "race", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("class").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "class", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("learn").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "learn", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("drop").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "drop", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("list").executes(c -> list(c.getSource()))));
    }

    private static int open(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        ORIGINS.today = (int) dev.worldforge.mod.api.WorldForgeApi.clock().dayNumber();
        dev.worldforge.mod.WFNet.sendView(sp, "wf_origins:origins", ORIGINS.viewJson(owner(sp)));
        return 1;
    }

    private static int show(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        Origins.Build b = ORIGINS.build(owner(sp));
        StringBuilder sb = new StringBuilder("Раса: " + (b.race.isEmpty() ? "не выбрана" : DEFS.races.get(b.race).name()) + "; класс: " + (b.cls.isEmpty() ? "нет" : DEFS.classes.get(b.cls).name()));
        for (var e : b.prof.entrySet()) { OriginDefs.Profession p = DEFS.professions.get(e.getKey()); if (p != null) sb.append("; ").append(p.name()).append(" - ").append(p.ranks().get(ORIGINS.rank(owner(sp), e.getKey())).name()).append(" (").append(e.getValue()).append(" оп.)"); }
        String m = sb.toString();
        s.sendSuccess(() -> Component.literal(m), false);
        return 1;
    }

    private static int list(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        StringBuilder sb = new StringBuilder("Расы: " + String.join(", ", DEFS.races.keySet()) + "\nПрофессии: " + String.join(", ", DEFS.professions.keySet()) + "\nКлассы, доступные сейчас:");
        for (var e : ORIGINS.options(owner(sp)).entrySet()) sb.append("\n  ").append(e.getKey().id()).append(" - ").append(e.getKey().name()).append(e.getValue() == null ? "" : " (недоступен: " + e.getValue() + ")");
        String m = sb.toString();
        s.sendSuccess(() -> Component.literal(m), false);
        return 1;
    }

    private static int act(CommandSourceStack s, String what, String id) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String actor = sp.getUUID().toString();
        String o = owner(sp);
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String requestId = "origins:" + UUID.nameUUIDFromBytes(
                (actor + ":" + s.getServer().getTickCount() + ":" + what + ":" + id)
                        .getBytes(StandardCharsets.UTF_8));
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, "origins." + what, actor, o, List.of(id), minute),
                    req -> s.getServer().getPlayerList().getPlayer(sp.getUUID()) == null
                            ? "Игрок отключился до изменения происхождения" : null,
                    (req, attempt) -> {
                        boolean ok;
                        String message;
                        switch (what) {
                            case "race" -> { ok = ORIGINS.setRace(o, id); message = "Раса выбрана."; }
                            case "class" -> { ok = ORIGINS.chooseClass(o, id); message = "Класс выбран."; }
                            case "learn" -> { ok = ORIGINS.learn(o, id); message = "Профессия изучена."; }
                            case "retrain" -> {
                                ORIGINS.today = (int) dev.worldforge.mod.api.WorldForgeApi.clock().dayNumber();
                                String removed = ORIGINS.retrain(o, ORIGINS.today);
                                ok = removed != null;
                                message = ok ? "Класс снят: " + removed : ORIGINS.lastError;
                            }
                            case "drop" -> {
                                if (!ORIGINS.build(o).prof.containsKey(id)) {
                                    ok = false;
                                    message = "Эта профессия не изучена.";
                                } else {
                                    ORIGINS.drop(o, id);
                                    ok = true;
                                    message = "Профессия и её зависимые специализации сброшены.";
                                }
                            }
                            default -> { return dev.worldforge.mod.api.ActionResult.fail("Неизвестная операция происхождения: " + what); }
                        }
                        return ok ? dev.worldforge.mod.api.ActionResult.ok(message)
                                : dev.worldforge.mod.api.ActionResult.fail(message == null || message.isBlank() ? ORIGINS.lastError : message);
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(s.getServer()));
            boolean ok = task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED;
            if (ok) s.sendSuccess(() -> Component.literal(task.message()), false);
            else s.sendFailure(Component.literal(task.message()));
            open(s);
            return ok ? 1 : 0;
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Не удалось изменить происхождение: " + ex.getMessage()));
            open(s);
            return 0;
        }
    }
}
