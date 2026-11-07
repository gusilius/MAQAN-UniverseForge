package dev.worldforge.quests.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.RegionEnterEvent;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEnterEvent;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WFPerm;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.quests.QuestDefs;
import dev.worldforge.quests.Quests;
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
 * Склейка wf-quests: задания и события из датапаков (wf_quests), состояние мира, ежедневный тик по игровым часам, события мира (раз в день шанс по каждому
 * государству), убийства и заход в регион/провинцию засчитываются шагам, /wfquest и журнал (клавиша J). Золото - золотые самородки.
 */
@Mod("wf_quests")
public class QuestsMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final QuestDefs DEFS = new QuestDefs();
    public static final Quests QUESTS = new Quests(DEFS);
    private static final int SAVE_EVERY = 6000, STEP = 100;
    private static final double EVENT_CHANCE = 0.08;
    private static long ticks;
    private static final Random RNG = new Random();

    public QuestsMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            QUESTS.registerContent();
            dev.worldforge.mod.api.ContentApi.permission = QUESTS.permission(20);
            if (ModList.get().isLoaded("wf_origins")) OriginsHook.install();
            if (ModList.get().isLoaded("wf_dialogue")) { DialogueLink.register(); LOGGER.info("wf_quests: мост к wf_dialogue подключён"); }
        }));
        MinecraftForge.EVENT_BUS.register(this);
        QUESTS.hooks = HOOKS;
    }

    static ServerPlayer player(String id) {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return null;
        try { return s.getPlayerList().getPlayer(UUID.fromString(id.startsWith("p:") ? id.substring(2) : id)); } catch (IllegalArgumentException e) { return null; }
    }

    static Item itemOf(String id) {
        if (id.equals("gold")) return Items.GOLD_NUGGET;
        ResourceLocation rl = id.contains(":") ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    public static int day() { return (int) WorldForgeApi.clock().dayNumber(); }

    private static List<TitleData.Realm> realms() {
        List<TitleData.Realm> l = new ArrayList<>();
        for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null) l.addAll(m.titles.realmList());
        return l;
    }

    private static TitleData.Realm realm(String id) {
        for (TitleData.Realm r : realms()) if (r.id.equals(id)) return r;
        return null;
    }

    private static List<String> csv(Object o) {
        List<String> l = new ArrayList<>();
        if (o != null) for (String s : String.valueOf(o).split("[,;]")) if (!s.isBlank()) l.add(s.trim());
        return l;
    }

    public static final Quests.Hooks HOOKS = new Quests.Hooks() {
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
        @Override public void xp(String pl, int n) { ServerPlayer p = player(pl); if (p != null) p.giveExperiencePoints(n); }
        @Override public int level(String pl) { ServerPlayer p = player(pl); return p == null ? 1 : Math.max(1, p.experienceLevel); }
        @Override public void rep(String pl, String target, int n) {
            ServerPlayer p = player(pl);
            if (p != null) p.displayClientMessage(Component.literal((n >= 0 ? "Репутация +" : "Репутация ") + n + " (" + target + ")"), true);
        }
        @Override public void relation(String pl, String npc, int d) { }
        @Override public void influence(String pl, String org, int n) { if (ModList.get().isLoaded("wf_orders")) OrdersLink.influence(pl, org, n); }
        @Override public int orgRank(String pl, String org) { return ModList.get().isLoaded("wf_orders") ? OrdersLink.rank(pl, org) : -1; }
        @Override public String realmOf(String pl) {
            ServerPlayer p = player(pl);
            if (p == null) return "";
            String name = p.getGameProfile().getName();
            for (TitleData.Realm r : realms()) {
                Object who = r.props.get("player");
                if (who != null && (String.valueOf(who).equals(name) || String.valueOf(who).equals(pl))) return r.id;
            }
            return "";
        }
        @Override public String rulerOf(String realm) {
            TitleData.Realm r = realm(realm);
            Object who = r == null ? null : r.props.get("player");
            if (who == null) return "";
            ServerPlayer p = ServerLifecycleHooks.getCurrentServer() == null ? null : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayerByName(String.valueOf(who));
            return p == null ? "" : p.getUUID().toString();
        }
        @Override public boolean atWar(String realm) { TitleData.Realm r = realm(realm); return r != null && !csv(r.props.get("enemies")).isEmpty(); }
        @Override public List<String> neighbors(String realm) {
            List<String> l = new ArrayList<>();
            for (TitleData.Realm r : realms()) if (!r.id.equals(realm)) l.add(r.id);
            return l;
        }
        @Override public List<String> enemies(String realm) { TitleData.Realm r = realm(realm); return r == null ? List.of() : csv(r.props.get("enemies")); }
        @Override public String run(String pl, String action, String realm, List<String> args) {
            ServerPlayer p = player(pl);
            MapData m = p == null ? null : EditorManager.dataFor(p.serverLevel());
            if (m == null) { QUESTS.lastError = "Карта недоступна."; return null; }
            ActionResult r = WorldForgeApi.run(action, new ActionContext(EditorManager.editorFor(m), p.getGameProfile().getName(), WFPerm.trusted(p.createCommandSourceStack()), realm, 0, args));
            if (!r.ok) { QUESTS.lastError = r.message; return null; }
            EditorManager.touch(m);
            return r.message;
        }
        @Override public void news(String text) { WorldForgeApi.postEvent("quests", text, List.of()); }
        @Override public void notify(String pl, String text) { ServerPlayer p = player(pl); if (p != null) p.sendSystemMessage(Component.literal(text)); }
        @Override public void realmProp(String realm, String key, double delta) {
            TitleData.Realm r = realm(realm);
            if (r == null) return;
            Object cur = r.props.get(key);
            double v = cur instanceof Number n ? n.doubleValue() : 0;
            r.props.put(key, v + delta);
        }
        @Override public void shock(String realm, String good, double factor, int days) { if (ModList.get().isLoaded("wf_trade")) TradeLink.shock(realm, good, factor, days); }
        @Override public void treasury(String realm, double delta) { dev.worldforge.mod.api.TreasuryApi.add(realm, delta, "События и задания"); }
    };

    // ------------------------------------------------------------------ жизненный цикл

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_quests.json"); }

    private static void loadDefs(ResourceManager rm) {
        Map<ResourceLocation, Resource> found = rm.listResources("wf_quests", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { DEFS.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_quests: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        for (String bad : DEFS.validate()) LOGGER.warn("wf_quests: {}", bad);
        LOGGER.info("wf_quests: заданий {}, событий {}", DEFS.quests.size(), DEFS.events.size());
    }

    @SubscribeEvent public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }
    @SubscribeEvent public void onAboutToStart(ServerAboutToStartEvent e) { ticks = 0; }
    @SubscribeEvent public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) QuestsMod::loadDefs); }
    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) QUESTS.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_quests: не удалось прочитать состояние: {}", ex.toString()); }
    }

    private static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, QUESTS.toJson()); }
        catch (Exception ex) { LOGGER.error("wf_quests: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % STEP == 0) {
                int days = WorldForgeApi.clock().pull("wf_quests");
                int today = day();
                List<String> ids = new ArrayList<>();
                for (TitleData.Realm r : realms()) ids.add(r.id);
                for (int i = days - 1; i >= 0; i--) { QUESTS.tick(today - i); QUESTS.rollEvents(today - i, RNG, ids, EVENT_CHANCE); }
            }
            if (ticks % SAVE_EVERY == 0) save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_quests: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onKill(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p)) return;
        String id = p.getUUID().toString();
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).getPath();
        QUESTS.report(id, "kill", e.getEntity() instanceof Enemy ? "hostile" : type, 1, day());
        if (e.getEntity() instanceof Enemy) QUESTS.report(id, "kill", type, 1, day());
    }

    @SubscribeEvent
    public void onTitle(TitleEnterEvent e) {
        TitleData.Realm r = e.getRealm();
        if (r != null) QUESTS.report(e.getPlayer().getUUID().toString(), "reach", r.id, 1, day());
    }

    @SubscribeEvent
    public void onRegion(RegionEnterEvent e) {
        if (e.getTo() != null) QUESTS.report(e.getPlayer().getUUID().toString(), "reach", e.getTo().name, 1, day());
    }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfquest")
                .executes(c -> open(c.getSource()))
                .then(Commands.literal("choose").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("option", StringArgumentType.word())
                        .executes(c -> act(c.getSource(), "choose", IntegerArgumentType.getInteger(c, "id"), StringArgumentType.getString(c, "option"))))))
                .then(Commands.literal("resolve").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("option", StringArgumentType.word())
                        .executes(c -> act(c.getSource(), "resolve", IntegerArgumentType.getInteger(c, "id"), StringArgumentType.getString(c, "option"))))))
                .then(Commands.literal("abandon").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> act(c.getSource(), "abandon", IntegerArgumentType.getInteger(c, "id"), ""))))
                .then(Commands.literal("fire").requires(s -> s.hasPermission(2)).then(Commands.argument("event", StringArgumentType.word()).then(Commands.argument("realm", StringArgumentType.word()).executes(c -> {
                    QuestDefs.Event ev = DEFS.events.get(StringArgumentType.getString(c, "event"));
                    if (ev == null) { c.getSource().sendFailure(Component.literal("Нет такого события.")); return 0; }
                    QUESTS.fire(ev, StringArgumentType.getString(c, "realm"), day(), RNG);
                    c.getSource().sendSuccess(() -> Component.literal("Событие запущено."), true);
                    return 1;
                })))));
    }

    private static int open(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        WFNet.sendView(sp, "wf_quests:journal", QUESTS.viewJson(sp.getUUID().toString(), day()));
        return 1;
    }

    private static int act(CommandSourceStack s, String what, int id, String opt) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String p = sp.getUUID().toString();
        boolean ok = switch (what) {
            case "choose" -> QUESTS.choose(p, id, opt, day());
            case "resolve" -> QUESTS.resolve(p, id, opt, day());
            case "abandon" -> QUESTS.abandon(p, id, day());
            default -> true;
        };
        if (!ok) s.sendFailure(Component.literal(QUESTS.lastError));
        return open(s);
    }
}
