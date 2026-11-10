package dev.worldforge.orders.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WFPerm;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.orders.OrgDef;
import dev.worldforge.orders.Orders;
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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
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
 * Склейка wf-orders: организации из датапаков (wf_orders), состояние в мире, ежедневный тик по игровым часам, /wforders и окно (клавиша O),
 * убийства врагов засчитываются заданиям-деяниям (kill_hostile). Золото игрока здесь - золотые самородки.
 */
@Mod("wf_orders")
public class OrdersMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final Orders ORDERS = new Orders();
    private static final int SAVE_EVERY = 6000, STEP = 100;
    private static long ticks;
    private static final Random RNG = new Random();

    public OrdersMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            if (ModList.get().isLoaded("wf_dialogue")) { DialogueLink.register(); LOGGER.info("wf_orders: мост к wf_dialogue подключён"); }
        }));
        MinecraftForge.EVENT_BUS.register(this);
        ORDERS.hooks = HOOKS;
    }

    static ServerPlayer player(String id) {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return null;
        try { return s.getPlayerList().getPlayer(UUID.fromString(id)); } catch (IllegalArgumentException e) { return null; }
    }

    static Item item(String id) {
        ResourceLocation rl = id.contains(":") ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    static Item itemOf(String id) { return id.equals(Orders.GOLD) ? Items.GOLD_NUGGET : item(id); }

    public static int day() { return (int) WorldForgeApi.clock().dayNumber(); }

    public static final Orders.Hooks HOOKS = new Orders.Hooks() {
        @Override public int count(String pl, String it) {
            ServerPlayer p = player(pl); Item i = itemOf(it);
            if (p == null || i == null) return 0;
            int n = 0;
            for (ItemStack s : p.getInventory().items) if (s.is(i)) n += s.getCount();
            return n;
        }
        @Override public boolean take(String pl, String it, int n) {
            ServerPlayer p = player(pl); Item i = itemOf(it);
            if (p == null || i == null || count(pl, it) < n) return false;
            for (ItemStack s : p.getInventory().items) { if (n <= 0) break; if (s.is(i)) { int d = Math.min(n, s.getCount()); s.shrink(d); n -= d; } }
            return true;
        }
        @Override public void give(String pl, String it, int n) {
            ServerPlayer p = player(pl); Item i = itemOf(it);
            if (p == null || i == null) return;
            while (n > 0) { int d = Math.min(n, 64); ItemStack st = new ItemStack(i, d); if (!p.getInventory().add(st)) p.drop(st, false); n -= d; }
        }
        @Override public String realmOf(String pl) {
            ServerPlayer p = player(pl);
            if (p == null) return "";
            String name = p.getGameProfile().getName();
            for (MapData m : WorldForgeMod.loadedMaps().values()) {
                if (m.titles == null) continue;
                for (TitleData.Realm r : m.titles.realmList()) {
                    Object who = r.props.get("player");
                    if (who != null && (String.valueOf(who).equals(name) || String.valueOf(who).equals(pl))) return r.id;
                }
            }
            return "";
        }
        @Override public String run(String pl, String action, String realm, List<String> args) {
            ServerPlayer p = player(pl);
            MapData m = p == null ? null : EditorManager.dataFor(p.serverLevel());
            if (m == null) { ORDERS.lastError = "Карта недоступна."; return null; }
            ActionContext context = new ActionContext(EditorManager.editorFor(m),
                    p.getGameProfile().getName(), WFPerm.trusted(p.createCommandSourceStack()), realm, 0, args);
            String actor = p.getUUID().toString();
            String payload = action + "|" + realm + "|" + String.join("|", args);
            String requestId = "orders:" + UUID.nameUUIDFromBytes(
                    (actor + ":" + WorldForgeApi.clock().worldMinute() + ":" + payload)
                            .getBytes(StandardCharsets.UTF_8));
            try {
                var task = WorldForgeApi.submitAction(requestId, action, context,
                        WorldForgeMod.actionExecutionFile(p.server), WorldForgeApi.clock().worldMinute());
                if (task.status() != dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                    ORDERS.lastError = task.message();
                    return null;
                }
                EditorManager.touch(m);
                return task.message();
            } catch (RuntimeException ex) {
                ORDERS.lastError = "Не удалось выполнить действие организации: " + ex.getMessage();
                return null;
            }
        }
    };

    // ------------------------------------------------------------------ жизненный цикл

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_orders.json"); }

    private static void loadDefs(ResourceManager rm) {
        Map<ResourceLocation, Resource> found = rm.listResources("wf_orders", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { ORDERS.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_orders: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        LOGGER.info("wf_orders: организаций: {}", ORDERS.defs.size());
    }

    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) { ticks = 0; }

    @SubscribeEvent
    public void onStarted(net.minecraftforge.event.server.ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) ORDERS.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_orders: не удалось прочитать состояние: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) OrdersMod::loadDefs); }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    private static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, ORDERS.toJson()); }
        catch (Exception ex) { LOGGER.error("wf_orders: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % STEP == 0) {
                int days = WorldForgeApi.clock().pull("wf_orders");
                int today = day();
                for (int i = days - 1; i >= 0; i--) ORDERS.tick(today - i, RNG);
            }
            if (ticks % SAVE_EVERY == 0) save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_orders: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onKill(LivingDeathEvent e) {
        if (e.getEntity() instanceof Enemy && e.getSource().getEntity() instanceof ServerPlayer p) ORDERS.report(p.getUUID().toString(), "kill_hostile", 1);
    }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wforders")
                .executes(c -> open(c.getSource()))
                .then(Commands.literal("join").then(Commands.argument("org", StringArgumentType.word()).executes(c -> act(c.getSource(), "join", StringArgumentType.getString(c, "org"), "", 0))))
                .then(Commands.literal("leave").then(Commands.argument("org", StringArgumentType.word()).executes(c -> act(c.getSource(), "leave", StringArgumentType.getString(c, "org"), "", 0))))
                .then(Commands.literal("donate").then(Commands.argument("org", StringArgumentType.word()).then(Commands.argument("gold", IntegerArgumentType.integer(1, 100000)).executes(c -> act(c.getSource(), "donate", StringArgumentType.getString(c, "org"), "", IntegerArgumentType.getInteger(c, "gold"))))))
                .then(Commands.literal("offers").then(Commands.argument("org", StringArgumentType.word()).executes(c -> act(c.getSource(), "offers", StringArgumentType.getString(c, "org"), "", 0))))
                .then(Commands.literal("accept").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> act(c.getSource(), "accept", "", "", IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("complete").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> act(c.getSource(), "complete", "", "", IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("petition").then(Commands.argument("org", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "petition", StringArgumentType.getString(c, "org"), StringArgumentType.getString(c, "id"), 0))))));
    }

    private static int open(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        String id = sp.getUUID().toString();
        int today = day();
        for (String org : ORDERS.orgsOf(id)) {
            String requestId = "orders-offers:" + UUID.nameUUIDFromBytes(
                    (id + ":" + org + ":" + today).getBytes(StandardCharsets.UTF_8));
            long minute = WorldForgeApi.clock().worldMinute();
            try {
                WorldForgeApi.actionExecution().execute(
                        new dev.worldforge.mod.api.ActionExecutionService.Request(
                                requestId, "orders.generate_offers", id, org, List.of(Integer.toString(today)), minute),
                        req -> s.getServer().getPlayerList().getPlayer(sp.getUUID()) == null
                                ? "Игрок отключился до обновления предложений" : null,
                        (req, attempt) -> {
                            ORDERS.offers(id, org, today, RNG);
                            return ActionResult.ok("Предложения обновлены");
                        }, null, minute, WorldForgeMod.actionExecutionFile(s.getServer()));
            } catch (RuntimeException ex) {
                LOGGER.error("wf_orders: не удалось обновить предложения для {}: {}", id, ex.toString());
            }
        }
        WFNet.sendView(sp, "wf_orders:orders", ORDERS.viewJson(id, today, HOOKS.realmOf(id)));
        return 1;
    }

    private static int act(CommandSourceStack s, String what, String org, String arg, int n) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String actor = sp.getUUID().toString();
        String payload = what + "|" + org + "|" + arg + "|" + n;
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = "orders-command:" + UUID.nameUUIDFromBytes(
                (actor + ":" + s.getServer().getTickCount() + ":" + payload).getBytes(StandardCharsets.UTF_8));
        try {
            var task = WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, "orders." + what, actor, org.isBlank() ? arg : org,
                            List.of(payload), minute),
                    req -> s.getServer().getPlayerList().getPlayer(sp.getUUID()) == null
                            ? "Игрок отключился до исполнения команды" : null,
                    (req, attempt) -> {
                        String message;
                        switch (what) {
                            case "join" -> {
                                if (!ORDERS.join(actor, org, day())) return ActionResult.fail(ORDERS.lastError);
                                message = "Вы приняты.";
                            }
                            case "leave" -> {
                                if (!ORDERS.orgsOf(actor).contains(org)) return ActionResult.fail("Вы не состоите в этой организации.");
                                ORDERS.leave(actor, org, day());
                                message = "Вы покинули организацию.";
                            }
                            case "donate" -> {
                                if (!ORDERS.donate(actor, org, n)) return ActionResult.fail(ORDERS.lastError);
                                message = "Пожертвование внесено.";
                            }
                            case "offers" -> {
                                if (!ORDERS.orgsOf(actor).contains(org)) return ActionResult.fail("Вы не состоите в этой организации.");
                                ORDERS.offers(actor, org, day(), RNG);
                                message = "Предложения обновлены.";
                            }
                            case "accept" -> {
                                if (!ORDERS.accept(actor, n)) return ActionResult.fail(ORDERS.lastError);
                                message = "Задание принято.";
                            }
                            case "complete" -> {
                                if (!ORDERS.complete(actor, n, day())) return ActionResult.fail(ORDERS.lastError);
                                message = "Задание выполнено.";
                            }
                            case "petition" -> {
                                String result = ORDERS.petition(actor, org, arg, day());
                                if (result == null) return ActionResult.fail(ORDERS.lastError);
                                message = result;
                            }
                            default -> { return ActionResult.fail("Неизвестная команда организации: " + what); }
                        }
                        return ActionResult.ok(message);
                    }, null, minute, WorldForgeMod.actionExecutionFile(s.getServer()));
            boolean ok = task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED;
            if (ok && !task.message().isBlank()) s.sendSuccess(() -> Component.literal(task.message()), false);
            else if (!ok) s.sendFailure(Component.literal(task.message()));
            open(s);
            return ok ? 1 : 0;
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Не удалось выполнить команду организации: " + ex.getMessage()));
            open(s);
            return 0;
        }
    }
}
