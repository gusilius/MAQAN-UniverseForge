package dev.worldforge.forms.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.forms.FormDefs;
import dev.worldforge.forms.Forms;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.api.WorldForgeApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Склейка wf-forms: превращения (вампиризм, оборотничество, драконья кровь, проклятья, нежить) из датапаков wf_forms.
 * Заражение - укусы мобов (LivingHurt), события и задания (через /wfform infect и действия), ежедневный тик по игровым часам, среда: ночь, луна, солнце, вода.
 */
@Mod("wf_forms")
public class FormsMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final FormDefs DEFS = new FormDefs();
    public static final Forms FORMS = new Forms(DEFS);
    private static final int STEP = 100, SAVE_EVERY = 6000;
    private static long ticks;
    private static final Map<UUID, Long> rageUntil = new HashMap<>();

    public FormsMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            FORMS.publish();
            if (ModList.get().isLoaded("wf_origins")) OriginsHook.install();
        }));
        MinecraftForge.EVENT_BUS.register(this);
        FORMS.hooks = HOOKS;
    }

    static ServerPlayer player(String id) {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return null;
        try { return s.getPlayerList().getPlayer(UUID.fromString(id.startsWith("p:") ? id.substring(2) : id)); } catch (IllegalArgumentException e) { return null; }
    }

    static String owner(ServerPlayer p) { return "p:" + p.getUUID(); }
    static int day() { return (int) WorldForgeApi.clock().dayNumber(); }

    private static Item itemOf(String id) {
        ResourceLocation rl = id.contains(":") ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    public static final Forms.Hooks HOOKS = new Forms.Hooks0() {
        @Override public int level(String o) { ServerPlayer p = player(o); return p == null ? 1 : Math.max(1, p.experienceLevel); }
        @Override public int count(String o, String it) {
            ServerPlayer p = player(o); Item i = itemOf(it);
            if (p == null || i == null) return 0;
            int n = 0;
            for (ItemStack s : p.getInventory().items) if (s.is(i)) n += s.getCount();
            return n;
        }
        @Override public boolean take(String o, String it, int n) {
            ServerPlayer p = player(o); Item i = itemOf(it);
            if (p == null || i == null || count(o, it) < n) return false;
            for (ItemStack s : p.getInventory().items) { if (n <= 0) break; if (s.is(i)) { int d = Math.min(n, s.getCount()); s.shrink(d); n -= d; } }
            return true;
        }
        @Override public int gold(String o) { return count(o, "gold_nugget"); }
        @Override public boolean takeGold(String o, int n) { return take(o, "gold_nugget", n); }
        @Override public boolean flag(String o, String f) { return false; }
        @Override public void notify(String o, String t) { ServerPlayer p = player(o); if (p != null) p.sendSystemMessage(Component.literal(t)); }
        @Override public void news(String t) { if (t != null && !t.isEmpty()) WorldForgeApi.postEvent("forms", t, List.of()); }
    };

    /** Среда игрока: ночь, полнолуние, солнце на коже, святое место (колокол рядом), серебро в руке, вода, ярость. */
    static Forms.Env envOf(ServerPlayer p, int day) {
        ServerLevel lv = p.serverLevel();
        BlockPos pos = p.blockPosition();
        boolean night = lv.isNight();
        boolean sun = !night && lv.canSeeSky(pos) && !lv.isRaining();
        boolean holy = false;
        for (BlockPos b : BlockPos.betweenClosed(pos.offset(-6, -3, -6), pos.offset(6, 3, 6))) { if (lv.getBlockState(b).is(net.minecraft.world.level.block.Blocks.BELL)) { holy = true; break; } }
        String hand = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).getPath();
        boolean silver = hand.contains("silver");
        boolean rage = rageUntil.getOrDefault(p.getUUID(), 0L) > ticks;
        return new Forms.Env(day, night, lv.getMoonPhase() == 0, sun, holy, silver, rage, false, p.isInWater(), lv.getMaxLocalRawBrightness(pos) < 5);
    }

    // ------------------------------------------------------------------ жизненный цикл

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_forms.json"); }

    private static void loadDefs(ResourceManager rm) {
        Map<ResourceLocation, Resource> found = rm.listResources("wf_forms", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { DEFS.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_forms: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        for (String bad : DEFS.validate()) LOGGER.warn("wf_forms: {}", bad);
        LOGGER.info("wf_forms: форм {}", DEFS.forms.size());
    }

    @SubscribeEvent public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }
    @SubscribeEvent public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) FormsMod::loadDefs); }
    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) FORMS.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_forms: не удалось прочитать состояние: {}", ex.toString()); }
    }

    private static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, FORMS.toJson()); }
        catch (Exception ex) { LOGGER.error("wf_forms: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % STEP == 0) {
                int days = WorldForgeApi.clock().pull("wf_forms");
                int today = day();
                for (int i = days - 1; i >= 0; i--)
                    for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) FORMS.tick(owner(p), envOf(p, today - i));
            }
            if (ticks % SAVE_EVERY == 0) save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_forms: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onHurt(LivingHurtEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        rageUntil.put(p.getUUID(), ticks + 200);
        if (e.getSource().getEntity() instanceof LivingEntity src && !(src instanceof ServerPlayer)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(src.getType()).getPath();
            FORMS.expose(owner(p), "mob", type, day());
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) { if (e.getEntity() instanceof ServerPlayer p) FORMS.onDeath(owner(p)); }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfform")
                .executes(c -> info(c.getSource()))
                .then(Commands.literal("shift").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("on", StringArgumentType.word())
                        .executes(c -> act(c.getSource(), "shift", StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "on"))))))
                .then(Commands.literal("feed").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "feed", StringArgumentType.getString(c, "id"), ""))))
                .then(Commands.literal("cure").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "options", StringArgumentType.getString(c, "id"), ""))
                        .then(Commands.argument("cure", StringArgumentType.word()).executes(c -> act(c.getSource(), "cure", StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "cure"))))))
                .then(Commands.literal("infect").requires(s -> s.hasPermission(2)).then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "infect", StringArgumentType.getString(c, "id"), ""))))
                .then(Commands.literal("remove").requires(s -> s.hasPermission(2)).then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "remove", StringArgumentType.getString(c, "id"), "")))));
    }

    private static int info(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        WFNet.sendView(sp, "wf_forms:journal", FORMS.viewJson(owner(sp)));
        return 1;
    }

    private static int act(CommandSourceStack source, String what, String id, String arg) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if ("options".equals(what)) {
            source.sendSuccess(() -> Component.literal(FORMS.cureOptions(owner(player), id)), false);
            return 1;
        }
        String actor = player.getUUID().toString();
        String owner = owner(player);
        String payload = what + "|" + id + "|" + arg;
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = "forms:" + UUID.nameUUIDFromBytes(
                (actor + ":" + source.getServer().getTickCount() + ":" + payload).getBytes(StandardCharsets.UTF_8));
        try {
            var task = WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, "forms." + what, actor, owner, List.of(payload), minute),
                    req -> {
                        if (source.getServer().getPlayerList().getPlayer(player.getUUID()) == null)
                            return "Игрок отключился до изменения состояния";
                        if (("infect".equals(what) || "remove".equals(what)) && !source.hasPermission(2))
                            return "Для этой операции нужны права оператора";
                        return null;
                    },
                    (req, attempt) -> {
                        boolean ok;
                        String message;
                        switch (what) {
                            case "shift" -> {
                                ok = FORMS.shift(owner, id, !arg.equals("off"), day());
                                message = ok ? "Облик изменён." : FORMS.lastError;
                            }
                            case "feed" -> {
                                String food = HOOKS.count(owner, "rotten_flesh") > 0 ? "rotten_flesh"
                                        : HOOKS.count(owner, "beef") > 0 ? "beef" : "";
                                if (food.isEmpty()) {
                                    ok = false;
                                    message = "Нужна пища: гнилая плоть или говядина.";
                                } else {
                                    double fed = FORMS.feed(owner, id, 25);
                                    if (fed < 0) {
                                        ok = false;
                                        message = FORMS.lastError;
                                    } else {
                                        HOOKS.take(owner, food, 1);
                                        ok = true;
                                        message = "Потребность насыщена.";
                                    }
                                }
                            }
                            case "cure" -> {
                                ok = FORMS.cure(owner, id, arg, day());
                                message = ok ? "Состояние вылечено." : FORMS.lastError;
                            }
                            case "infect" -> {
                                ok = FORMS.infect(owner, id, day(), "по воле администратора");
                                message = ok ? "Состояние добавлено." : FORMS.lastError;
                            }
                            case "remove" -> {
                                ok = FORMS.of(owner).remove(id) != null;
                                message = ok ? "Состояние удалено." : "Такой формы нет.";
                                if (!ok) FORMS.lastError = message;
                            }
                            default -> { return dev.worldforge.mod.api.ActionResult.fail("Неизвестная операция формы: " + what); }
                        }
                        return ok ? dev.worldforge.mod.api.ActionResult.ok(message)
                                : dev.worldforge.mod.api.ActionResult.fail(message);
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(source.getServer()));
            boolean ok = task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED;
            if (ok) source.sendSuccess(() -> Component.literal(task.message()), false);
            else source.sendFailure(Component.literal(task.message()));
            if (ok) info(source);
            return ok ? 1 : 0;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось выполнить операцию формы: " + ex.getMessage()));
            return 0;
        }
    }
}
