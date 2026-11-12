package dev.worldforge.trade.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.Diplomacy;
import dev.worldforge.mod.api.MapAction;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.trade.Trade;
import dev.worldforge.trade.TradeDefs;
import dev.worldforge.realms.Statecraft;
import dev.worldforge.realms.mc.RealmsMod;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
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
 * Склейка wf-trade: товары из датапаков wf_trade, рынки государств, караваны игрока и купцов, пошлины в казну (казна - золото wf_army, иначе свойство treasury),
 * действия карты trade_tariff/trade_embargo/trade_auto/trade_shock, команда /wftrade и окно торговли (клавиша Y).
 */
@Mod("wf_trade")
public class TradeMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final TradeDefs DEFS = new TradeDefs();
    public static final Trade TRADE = new Trade(DEFS);
    private static final int STEP = 100, SAVE_EVERY = 6000;
    private static long ticks;
    private static boolean armyLoaded;
    private static Map<String, Set<String>> adjacency = new HashMap<>();
    private static int adjacencyDay = -999;

    public TradeMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            armyLoaded = ModList.get().isLoaded("wf_army");
            registerActions();
            if (ModList.get().isLoaded("wf_dialogue")) { DialogueLink.register(); LOGGER.info("wf_trade: мост к wf_dialogue подключён"); }
        }));
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new WorldLayer());
        TRADE.hooks = HOOKS;
    }

    // ------------------------------------------------------------------ данные мира

    static List<TitleData.Realm> realms() { return RealmsMod.realms(); }

    static TitleData.Realm realm(String id) { return RealmsMod.realm(id); }

    private static Set<String> neighborsOf(String realm) {
        int today = day();
        if (today != adjacencyDay) {
            adjacency = new HashMap<>();
            for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null)
                Diplomacy.adjacency(m.titles).forEach((k, v) -> adjacency.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
            for (TitleData.Realm emergent : RealmsMod.emergentRealms()) {
                Set<String> linked = csv(emergent.props.get("emergence.neighbors"));
                adjacency.computeIfAbsent(emergent.id, x -> new HashSet<>()).addAll(linked);
                for (String other : linked) adjacency.computeIfAbsent(other, x -> new HashSet<>()).add(emergent.id);
            }
            adjacencyDay = today;
        }
        return adjacency.getOrDefault(realm, Set.of());
    }

    private static Set<String> csv(Object o) {
        Set<String> s = new HashSet<>();
        if (o != null) for (String p : String.valueOf(o).split("[,;\\s]+")) if (!p.isBlank()) s.add(p.trim());
        return s;
    }

    static int day() { return (int) WorldForgeApi.clock().dayNumber(); }

    static ServerPlayer player(String id) {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return null;
        try { return s.getPlayerList().getPlayer(UUID.fromString(id.startsWith("p:") ? id.substring(2) : id)); } catch (IllegalArgumentException e) { return null; }
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = id.contains(":") ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    static String realmOfPlayer(ServerPlayer p) {
        String name = p.getGameProfile().getName();
        for (TitleData.Realm r : realms()) { Object who = r.props.get("player"); if (who != null && String.valueOf(who).equals(name)) return r.id; }
        return "";
    }

    /** Рынок игрока: его государство, иначе государство, в чьих землях он стоит (по свойству), иначе первое государство. */
    static String marketOf(ServerPlayer p) {
        String own = realmOfPlayer(p);
        if (!own.isEmpty()) return own;
        List<TitleData.Realm> all = realms();
        return all.isEmpty() ? "" : all.get(0).id;
    }

    public static final Trade.Hooks HOOKS = new Trade.Hooks() {
        @Override public int size(String realm) {
            TitleData.Realm r = realm(realm);
            if (r == null) return 100;
            if (r.props.get("population") instanceof Number n) return Math.max(20, n.intValue());
            return 60 + 40 * Math.max(1, countBaronies(r));
        }
        @Override public List<String> neighbors(String realm) { return new ArrayList<>(neighborsOf(realm)); }
        @Override public double distance(String a, String b) { return neighborsOf(a).contains(b) ? 3 : 8; }
        @Override public boolean atWar(String a, String b) {
            TitleData.Realm x = realm(a), y = realm(b);
            boolean mapWar = x != null && y != null
                    && (csv(x.props.get("enemies")).contains(b) || csv(y.props.get("enemies")).contains(a));
            boolean statecraftWar = RealmsMod.POLITICS.wars().stream().anyMatch(war ->
                    war.status == Statecraft.WarStatus.ACTIVE
                            && ((war.attackerRealm.equals(a) && war.defenderRealm.equals(b))
                                || (war.attackerRealm.equals(b) && war.defenderRealm.equals(a))));
            return mapWar || statecraftWar;
        }
        @Override public double danger(String a, String b) { return neighborsOf(a).contains(b) ? 0.04 : 0.07; }
        @Override public double treaty(String a, String b) {
            if (armyLoaded && ArmyLink.pact(a, b)) return 10;
            return RealmsMod.POLITICS.hasTreaty(a, b, Statecraft.Treaty.TRADE_PACT) ? 10 : 0;
        }
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
        @Override public void give(String o, String it, int n) {
            ServerPlayer p = player(o); Item i = itemOf(it);
            if (p == null || i == null) return;
            while (n > 0) { int d = Math.min(n, 64); ItemStack st = new ItemStack(i, d); if (!p.getInventory().add(st)) p.drop(st, false); n -= d; }
        }
        @Override public int gold(String o) { return count(o, "gold_nugget"); }
        @Override public boolean takeGold(String o, int n) { return take(o, "gold_nugget", n); }
        @Override public void giveGold(String o, int n) { give(o, "gold_nugget", n); }
        @Override public void notify(String o, String text) { ServerPlayer p = player(o); if (p != null) p.sendSystemMessage(Component.literal(text)); }
        @Override public void news(String text) { WorldForgeApi.postEvent("trade", text, List.of()); }
        @Override public int season() { int m = (int) ((day() % 365) / 30.4); return ((m + 1) / 3) % 4; }
    };

    private static int countBaronies(TitleData.Realm r) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null || !m.titles.realms.containsKey(r.id)) continue;
            int n = 0;
            for (TitleData.Title t : m.titles.allTitles()) if (t.tier == 0 && m.titles.realmOf(t) == r) n++;
            return n;
        }
        return 1;
    }

    // ------------------------------------------------------------------ действия карты

    private static boolean mayRule(dev.worldforge.mod.api.ActionContext c) {
        if (c.operator) return true;
        TitleData.Realm r = realm(c.realmId);
        return r != null && r.props.get("player") != null && String.valueOf(r.props.get("player")).equals(c.actor);
    }

    private static void act(String id, String label, MapAction.Perm perm, List<String> params, String hint, java.util.function.Function<dev.worldforge.mod.api.ActionContext, String> body) {
        WorldForgeApi.registerAction(new MapAction(id, label, MapAction.Scope.REALM, perm, params, hint, ctx -> {
            if (!mayRule(ctx)) return ActionResult.fail("Это может решать только правитель государства.");
            try { return ActionResult.ok(body.apply(ctx)); } catch (Trade.TradeException ex) { return ActionResult.fail(ex.getMessage()); } catch (RuntimeException ex) { return ActionResult.fail("Неверные параметры: " + ex.getMessage()); }
        }));
    }

    private static void registerActions() {
        act("trade_tariff", "Торговая пошлина", MapAction.Perm.ANY, List.of("процент 0-30"), "Пошлина на ввозимые товары идёт в казну; высокая отпугивает купцов", c -> {
            TRADE.setTariff(c.realmId, Double.parseDouble(c.args.get(0).replace(',', '.')));
            return "Пошлина: " + (int) TRADE.market(c.realmId).tariff + "%";
        });
        act("trade_embargo", "Эмбарго", MapAction.Perm.ANY, List.of("id государства", "on/off"), "Закрывает рынки друг для друга", c -> {
            boolean on = !c.args.get(1).equalsIgnoreCase("off");
            TRADE.setEmbargo(c.realmId, c.args.get(0), on);
            return (on ? "Эмбарго против " : "Эмбарго снято с ") + c.args.get(0);
        });
        act("trade_auto", "Купцы государства", MapAction.Perm.ANY, List.of("on/off"), "Купеческие караваны ходят сами и платят налог гильдии", c -> {
            TRADE.market(c.realmId).autoTrade = !c.args.get(0).equalsIgnoreCase("off");
            return TRADE.market(c.realmId).autoTrade ? "Купцы работают" : "Купцы остановлены";
        });
        WorldForgeApi.registerAction(new MapAction("trade_shock", "Потрясение рынка", MapAction.Scope.REALM, MapAction.Perm.OP, List.of("товар или *", "множитель выпуска", "дней"),
                "Неурожай (0.3), расцвет (2.0); используется событиями", ctx -> {
            try {
                TRADE.shock(ctx.realmId, ctx.args.get(0), Double.parseDouble(ctx.args.get(1).replace(',', '.')), Integer.parseInt(ctx.args.get(2)));
                return ActionResult.ok("Потрясение рынка добавлено");
            } catch (RuntimeException ex) { return ActionResult.fail("Неверные параметры: " + ex.getMessage()); }
        }));
    }

    // ------------------------------------------------------------------ жизненный цикл

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_trade.json"); }

    private static void loadDefs(ResourceManager rm) {
        Map<ResourceLocation, Resource> found = rm.listResources("wf_trade", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { DEFS.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_trade: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        for (String bad : DEFS.validate()) LOGGER.warn("wf_trade: {}", bad);
        LOGGER.info("wf_trade: товаров {}", DEFS.goods.size());
    }

    @SubscribeEvent public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }
    @SubscribeEvent public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) TradeMod::loadDefs); }
    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) TRADE.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_trade: не удалось прочитать состояние: {}", ex.toString()); }
    }

    private static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, TRADE.toJson()); }
        catch (Exception ex) { LOGGER.error("wf_trade: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % STEP == 0) {
                int days = WorldForgeApi.clock().pull("wf_trade");
                int today = day();
                List<String> ids = new ArrayList<>();
                for (TitleData.Realm r : realms()) ids.add(r.id);
                for (int i = days - 1; i >= 0; i--) { TRADE.tick(today - i, ids); WorldLayer.SHOPS.tick(); }
            }
            if (ticks % SAVE_EVERY == 0) save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_trade: ошибка тика: {}", ex.toString()); }
    }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wftrade")
                .executes(c -> open(c.getSource(), ""))
                .then(Commands.literal("market").then(Commands.argument("realm", StringArgumentType.word()).executes(c -> open(c.getSource(), StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("caravan").then(Commands.argument("to", StringArgumentType.word()).then(Commands.argument("good", StringArgumentType.word()).then(Commands.argument("qty", IntegerArgumentType.integer(1))
                        .executes(c -> caravan(c.getSource(), StringArgumentType.getString(c, "to"), StringArgumentType.getString(c, "good"), IntegerArgumentType.getInteger(c, "qty"), 0))
                        .then(Commands.argument("guards", IntegerArgumentType.integer(0, 10))
                                .executes(c -> caravan(c.getSource(), StringArgumentType.getString(c, "to"), StringArgumentType.getString(c, "good"), IntegerArgumentType.getInteger(c, "qty"), IntegerArgumentType.getInteger(c, "guards"))))))))
                .then(Commands.literal("sell").then(Commands.argument("good", StringArgumentType.word()).then(Commands.argument("qty", IntegerArgumentType.integer(1))
                        .executes(c -> direct(c.getSource(), true, StringArgumentType.getString(c, "good"), IntegerArgumentType.getInteger(c, "qty"))))))
                .then(Commands.literal("buy").then(Commands.argument("good", StringArgumentType.word()).then(Commands.argument("qty", IntegerArgumentType.integer(1))
                        .executes(c -> direct(c.getSource(), false, StringArgumentType.getString(c, "good"), IntegerArgumentType.getInteger(c, "qty")))))));
    }

    private static int open(CommandSourceStack s, String realm) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        String r = realm.isEmpty() ? marketOf(sp) : realm;
        List<String> ids = new ArrayList<>();
        for (TitleData.Realm x : realms()) ids.add(x.id);
        if (!ids.contains(r)) { s.sendFailure(Component.literal("Нет такого государства.")); return 0; }
        WFNet.sendView(sp, "wf_trade:market", TRADE.viewJson("p:" + sp.getUUID(), r, ids));
        return 1;
    }

    /** Караван от рынка игрока (его государства) в соседнее. */
    private static boolean executeTradeMutation(ServerPlayer player, String actionId, String target,
                                                 String payload, java.util.function.Supplier<ActionResult> operation) {
        String actor = player.getUUID().toString();
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = "trade:" + java.util.UUID.randomUUID();
        try {
            var task = WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, actionId, actor, target, List.of(payload), minute),
                    req -> player.server.getPlayerList().getPlayer(player.getUUID()) == null
                            ? "Игрок отключился до исполнения торговой операции" : null,
                    (req, attempt) -> operation.get(), null, minute,
                    WorldForgeMod.actionExecutionFile(player.server));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) return true;
            player.sendSystemMessage(Component.literal(task.message()));
            return false;
        } catch (RuntimeException ex) {
            player.sendSystemMessage(Component.literal("Не удалось выполнить торговую операцию: " + ex.getMessage()));
            return false;
        }
    }

    private static int caravan(CommandSourceStack s, String to, String good, int qty, int guards) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String from = marketOf(sp);
        String owner = "p:" + sp.getUUID();
        String payload = from + "|" + to + "|" + good + "|" + qty + "|" + guards;
        boolean ok = executeTradeMutation(sp, "trade.dispatch_caravan", to, payload, () ->
                TRADE.dispatch(owner, from, to, good, qty, guards, day()) != null
                        ? ActionResult.ok("Караван отправлен")
                        : ActionResult.fail(TRADE.lastError));
        return ok ? open(s, from) : 0;
    }

    private static int direct(CommandSourceStack s, boolean sell, String good, int qty) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String realm = marketOf(sp), owner = "p:" + sp.getUUID();
        String actionId = sell ? "trade.player_sell" : "trade.player_buy";
        String payload = owner + "|" + realm + "|" + good + "|" + qty;
        boolean ok = executeTradeMutation(sp, actionId, realm, payload, () ->
                (sell ? TRADE.playerSell(owner, realm, good, qty) : TRADE.playerBuy(owner, realm, good, qty))
                        ? ActionResult.ok(sell ? "Товар продан" : "Товар куплен")
                        : ActionResult.fail(TRADE.lastError));
        return ok ? open(s, realm) : 0;
    }
}
