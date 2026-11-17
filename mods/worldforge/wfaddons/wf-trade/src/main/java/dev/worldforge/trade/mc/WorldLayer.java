package dev.worldforge.trade.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.trade.CaravanPath;
import dev.worldforge.trade.Shops;
import dev.worldforge.trade.Trade;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Слой мира для торговли: лавки (житель-торговец с именем, правый клик открывает окно лавки) и видимые караваны (вьючные ламы с возницей идут по прямой между столицами
 * государств, пока игрок рядом). Логика цен и касс - в Shops/Trade; здесь только сущности, команды /wftrade shop и сохранение.
 */
public final class WorldLayer {
    public static final Shops SHOPS = new Shops(TradeMod.TRADE);
    static final String SHOP_TAG = "wf_shop", CARAVAN_TAG = "wf_caravan", BANDIT_TAG = "wf_bandit";
    private static final double SHOW_RADIUS = 110, HIDE_RADIUS = 150;
    private static final Map<Integer, List<UUID>> VISIBLE = new HashMap<>();
    /** Разбойники засад, которые сейчас стоят в мире: id засады -> сущности. Убитые считаются в самой засаде (Trade.Ambush.slain). */
    private static final Map<Integer, List<UUID>> BANDITS = new HashMap<>();
    private static long ticks;

    // ------------------------------------------------------------------ сохранение

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_trade_shops.json"); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) SHOPS.fromJson(Files.readString(f)); }
        catch (Exception ex) { TradeMod.LOGGER.error("wf_trade: лавки не прочитаны: {}", ex.toString()); }
    }

    static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, SHOPS.toJson()); }
        catch (Exception ex) { TradeMod.LOGGER.error("wf_trade: лавки не сохранены: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        save(e.getServer());
        for (ServerLevel lv : e.getServer().getAllLevels()) for (UUID id : allVisible()) { Entity en = lv.getEntity(id); if (en != null) en.discard(); }
        VISIBLE.clear();
    }

    private static List<UUID> allVisible() { List<UUID> l = new ArrayList<>(); for (List<UUID> v : VISIBLE.values()) l.addAll(v); return l; }

    // ------------------------------------------------------------------ тик: лавки и караваны

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            MinecraftServer server = e.getServer();
            if (ticks % 100 == 0) keepShops(server);
            if (ticks % 2 == 0) moveCaravans(server);
            if (ticks % 40 == 0) keepAmbushes(server);
            if (ticks % 6000 == 0) save(server);
        } catch (RuntimeException ex) { TradeMod.LOGGER.error("wf_trade: ошибка слоя мира: {}", ex.toString()); }
    }

    /** Следим, чтобы у каждой лавки в загруженных чанках стоял торговец. */
    private static void keepShops(MinecraftServer server) {
        for (Shops.Shop s : SHOPS.shops.values()) {
            ServerLevel lv = levelOf(server, s.dim);
            if (lv == null) continue;
            BlockPos pos = BlockPos.containing(s.x, s.y, s.z);
            if (!lv.hasChunkAt(pos)) continue;
            if (findShopkeeper(lv, s) != null) continue;
            Villager v = EntityType.VILLAGER.create(lv);
            if (v == null) continue;
            v.moveTo(s.x, s.y, s.z, 0f, 0f);
            v.setNoAi(true);
            v.setInvulnerable(true);
            v.setPersistenceRequired();
            v.setCustomName(Component.literal(s.name));
            v.setCustomNameVisible(true);
            v.setVillagerData(v.getVillagerData().setProfession(s.categories.contains("material") ? VillagerProfession.MASON : s.categories.contains("food") ? VillagerProfession.FARMER : VillagerProfession.LEATHERWORKER));
            v.getPersistentData().putInt(SHOP_TAG, s.id);
            lv.addFreshEntity(v);
        }
    }

    private static Villager findShopkeeper(ServerLevel lv, Shops.Shop s) {
        for (Villager v : lv.getEntitiesOfClass(Villager.class, new AABB(s.x - 6, s.y - 6, s.z - 6, s.x + 6, s.y + 6, s.z + 6))) if (v.getPersistentData().getInt(SHOP_TAG) == s.id) return v;
        return null;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dim) {
        for (ServerLevel lv : server.getAllLevels()) if (lv.dimension().location().toString().equals(dim)) return lv;
        return null;
    }

    /** Закрытая лавка убирает своего торговца. */
    private static void removeShopkeeper(MinecraftServer server, Shops.Shop s) {
        ServerLevel lv = levelOf(server, s.dim);
        if (lv == null) return;
        Villager v = findShopkeeper(lv, s);
        if (v != null) v.discard();
    }

    // ------------------------------------------------------------------ караваны на дорогах

    private record Cap(MapData map, TitleData.Title title) {}

    private static Cap capTitle(String realm) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            TitleData.Realm re = m.titles.realms.get(realm);
            if (re == null) continue;
            for (String k : re.titleKeys) {
                TitleData.Title t = m.titles.byKey(k);
                while (t != null && t.tier > 0 && t.children.length > 0) t = m.titles.get(t.children[0]);
                if (t != null && t.tier == 0) return new Cap(m, t);
            }
        }
        return null;
    }

    private static final Map<String, double[][]> ROUTES = new HashMap<>();
    private static int routesDay = -100;

    /** Путь каравана: от столицы к столице по цепочке соседних провинций (прямая, если пути по графу нет). Пусто - государство без столицы. */
    static double[][] route(String from, String to) {
        int today = TradeMod.day();
        if (today - routesDay >= 10) { ROUTES.clear(); routesDay = today; }
        return ROUTES.computeIfAbsent(from + ">" + to, k -> {
            Cap a = capTitle(from), b = capTitle(to);
            if (a == null || b == null) return new double[0][];
            List<Integer> path = a.map() == b.map()
                    ? CaravanPath.bfs(id -> { TitleData.Title t = a.map().titles.get(id); return t == null ? null : t.neighbors; }, a.title().id, b.title().id, 80)
                    : List.of();
            if (path.size() < 2) return new double[][]{{a.title().capX, a.title().capZ}, {b.title().capX, b.title().capZ}};
            double[][] pts = new double[path.size()][];
            for (int i = 0; i < pts.length; i++) {
                TitleData.Title t = a.map().titles.get(path.get(i));
                pts[i] = new double[]{t.capX, t.capZ};
            }
            return pts;
        });
    }

    /**
     * Settlement shipments reuse the exact province-graph route and CaravanPath interpolation
     * used by national trade caravans. Settlement anchors are prepended/appended to the capital path.
     */
    public static double[][] settlementRoute(String fromRealm, String toRealm,
                                               double fromX, double fromZ, double toX, double toZ) {
        String sourceRealm = fromRealm == null ? "" : fromRealm;
        String destinationRealm = toRealm == null ? "" : toRealm;
        double[][] provincial = settlementProvinceRoute(sourceRealm, destinationRealm, fromX, fromZ, toX, toZ);
        double[][] capitals = provincial.length >= 2 ? provincial
                : sourceRealm.isBlank() || sourceRealm.equals(destinationRealm)
                ? new double[0][] : route(sourceRealm, destinationRealm);
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{fromX, fromZ});
        if (capitals != null) for (double[] point : capitals) {
            if (point == null || point.length < 2 || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) continue;
            double[] last = points.get(points.size() - 1);
            if (CaravanPath.dist(last[0], last[1], point[0], point[1]) > 1.0) points.add(point.clone());
        }
        double[] last = points.get(points.size() - 1);
        if (CaravanPath.dist(last[0], last[1], toX, toZ) > 1.0) points.add(new double[]{toX, toZ});
        if (points.size() == 1) points.add(new double[]{toX, toZ});
        return points.toArray(double[][]::new);
    }

    /** Shortest route between the settlements' actual provinces on the loaded strategic map graph. */
    private static double[][] settlementProvinceRoute(String fromRealm, String toRealm,
                                                               double fromX, double fromZ, double toX, double toZ) {
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            TitleData.Title from = map.provinceAt(fromX, fromZ), to = map.provinceAt(toX, toZ);
            if (from == null || to == null) continue;
            TitleData.Realm sourceOwner = map.titles.realmOf(from), destinationOwner = map.titles.realmOf(to);
            if (sourceOwner == null || destinationOwner == null
                    || (!fromRealm.isBlank() && !fromRealm.equals(sourceOwner.id))
                    || (!toRealm.isBlank() && !toRealm.equals(destinationOwner.id))) continue;
            if (from.id == to.id) return new double[0][];
            List<Integer> ids = CaravanPath.bfs(id -> {
                TitleData.Title province = map.titles.get(id);
                return province == null ? null : province.neighbors;
            }, from.id, to.id, 160);
            if (ids.size() < 2) continue;
            double[][] points = new double[ids.size()][];
            for (int i=0;i<ids.size();i++) {
                TitleData.Title province=map.titles.get(ids.get(i));
                if(province==null) return new double[0][];
                points[i]=new double[]{province.capX,province.capZ};
            }
            return points;
        }
        return new double[0][];
    }

    /** Position query for settlement shipment projections; interpolation is shared with existing caravans. */
    public static double[] settlementPosition(double[][] path, double progress) {
        CaravanPath.Pos pos = CaravanPath.along(path == null ? new double[0][] : path, progress);
        return new double[]{pos.x(), pos.z(), pos.yaw()};
    }

    private static void moveCaravans(MinecraftServer server) {
        double dayFrac = WorldForgeApi.clock().elapsedDays() % 1.0;
        Set<Integer> active = new HashSet<>();
        for (Trade.Caravan c : TradeMod.TRADE.caravans.values()) {
            if (!c.status.equals("в пути")) continue;
            double[][] way = route(c.from, c.to);
            if (way.length < 2) continue;
            CaravanPath.Pos head = CaravanPath.along(way, CaravanPath.progress(c.total, c.left, dayFrac));
            ServerLevel lv = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                double d = CaravanPath.dist(p.getX(), p.getZ(), head.x(), head.z());
                if (d < best) { best = d; lv = p.serverLevel(); }
            }
            if (lv == null || best > (VISIBLE.containsKey(c.id) ? HIDE_RADIUS : SHOW_RADIUS)) continue;
            BlockPos probe = BlockPos.containing(head.x(), 64, head.z());
            if (!lv.hasChunkAt(probe)) continue;
            active.add(c.id);
            List<UUID> ids = VISIBLE.computeIfAbsent(c.id, k -> new ArrayList<>());
            if (ids.isEmpty()) spawnCaravan(lv, c, head, ids);
            for (int i = 0; i < ids.size(); i++) {
                Entity en = lv.getEntity(ids.get(i));
                if (en == null) continue;
                CaravanPath.Pos pos = CaravanPath.member(head, i, 2.6);
                int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(pos.x()), (int) Math.floor(pos.z()));
                en.moveTo(pos.x(), y, pos.z(), (float) pos.yaw(), 0f);
            }
        }
        // убрать то, что вышло из радиуса или закончило путь
        for (Iterator<Map.Entry<Integer, List<UUID>>> it = VISIBLE.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (active.contains(en.getKey())) continue;
            for (ServerLevel lv : server.getAllLevels()) for (UUID id : en.getValue()) { Entity x = lv.getEntity(id); if (x != null) x.discard(); }
            it.remove();
        }
    }

    private static void spawnCaravan(ServerLevel lv, Trade.Caravan c, CaravanPath.Pos head, List<UUID> ids) {
        String label = "Караван №" + c.id + " → " + c.to;
        for (int i = 0; i < 3; i++) {
            Mob m;
            if (i == 0) m = EntityType.VILLAGER.create(lv); else m = EntityType.TRADER_LLAMA.create(lv);
            if (m == null) continue;
            CaravanPath.Pos pos = CaravanPath.member(head, i, 2.6);
            m.moveTo(pos.x(), lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(pos.x()), (int) Math.floor(pos.z())), pos.z(), (float) pos.yaw(), 0f);
            m.setNoAi(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            if (i == 0) { m.setCustomName(Component.literal(label)); m.setCustomNameVisible(true); }
            m.addTag(CARAVAN_TAG);
            lv.addFreshEntity(m);
            ids.add(m.getUUID());
        }
    }

    /** После перезапуска старые сущности караванов не нужны: их создаёт слой заново. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getEntity().getTags().contains(CARAVAN_TAG)) return;
        if (!allVisible().contains(e.getEntity().getUUID())) e.setCanceled(true);
    }


    // ------------------------------------------------------------------ засады

    /** Точка засады на прямом пути между столицами. */
    private static double[] ambushSite(Trade.Ambush a) {
        double[][] way = route(a.from, a.to);
        if (way.length < 2) return null;
        CaravanPath.Pos pos = CaravanPath.along(way, a.progress);
        return new double[]{pos.x(), pos.z()};
    }

    /** Рядом с игроком ставим разбойников засады (сколько ещё не убито); ушёл игрок - убираем; срок вышел - тоже. */
    private static void keepAmbushes(MinecraftServer server) {
        for (Trade.Ambush a : TradeMod.TRADE.ambushes.values()) {
            double[] site = ambushSite(a);
            if (site == null) continue;
            ServerLevel lv = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                double d = CaravanPath.dist(p.getX(), p.getZ(), site[0], site[1]);
                if (d < best) { best = d; lv = p.serverLevel(); }
            }
            boolean here = BANDITS.containsKey(a.id);
            if (lv == null || best > (here ? HIDE_RADIUS : SHOW_RADIUS)) { if (here) clearBandits(server, a.id); continue; }
            if (here || !lv.hasChunkAt(BlockPos.containing(site[0], 64, site[1]))) continue;
            List<UUID> ids = new ArrayList<>();
            int left = a.strength - a.slain;
            for (int i = 0; i < left; i++) {
                Mob m = (i % 3 == 2 ? EntityType.PILLAGER : EntityType.VINDICATOR).create(lv);
                if (m == null) continue;
                double x = site[0] + (i % 3 - 1) * 2.5, z = site[1] + (i / 3 - 1) * 2.5;
                m.moveTo(x, lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z)), z, 0f, 0f);
                m.setPersistenceRequired();
                m.setCustomName(Component.literal("Разбойник"));
                m.addTag(BANDIT_TAG);
                m.getPersistentData().putInt(BANDIT_TAG, a.id);
                lv.addFreshEntity(m);
                ids.add(m.getUUID());
            }
            BANDITS.put(a.id, ids);
        }
        for (Integer id : new ArrayList<>(BANDITS.keySet())) if (!TradeMod.TRADE.ambushes.containsKey(id)) clearBandits(server, id);
    }

    private static void clearBandits(MinecraftServer server, int id) {
        List<UUID> ids = BANDITS.remove(id);
        if (ids == null) return;
        for (ServerLevel lv : server.getAllLevels()) for (UUID u : ids) { Entity x = lv.getEntity(u); if (x != null) x.discard(); }
    }

    /** Убит разбойник засады: когда убиты все, груз возвращается владельцу. */
    @SubscribeEvent
    public void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent e) {
        Entity en = e.getEntity();
        if (en.level().isClientSide() || !en.getTags().contains(BANDIT_TAG)) return;
        int id = en.getPersistentData().getInt(BANDIT_TAG);
        List<UUID> ids = BANDITS.get(id);
        if (ids != null) ids.remove(en.getUUID());
        String msg = TradeMod.TRADE.slay(id, TradeMod.day());
        if (msg == null) return;
        BANDITS.remove(id);
        for (ServerPlayer p : en.getServer().getPlayerList().getPlayers()) p.displayClientMessage(Component.literal(msg), false);
    }

    @SubscribeEvent
    public void onBanditJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !e.getEntity().getTags().contains(BANDIT_TAG)) return;
        List<UUID> ids = BANDITS.get(e.getEntity().getPersistentData().getInt(BANDIT_TAG));
        if (ids == null || !ids.contains(e.getEntity().getUUID())) e.setCanceled(true);          // старые разбойники после перезапуска: слой создаст заново
    }

    private static int ambushes(CommandSourceStack s) {
        if (TradeMod.TRADE.ambushes.isEmpty()) { s.sendSuccess(() -> Component.literal("Засад нет"), false); return 1; }
        for (Trade.Ambush a : TradeMod.TRADE.ambushes.values()) {
            double[] site = ambushSite(a);
            String where = site == null ? "место неизвестно" : "≈ " + (int) site[0] + ", " + (int) site[1];
            s.sendSuccess(() -> Component.literal("Засада #" + a.id + " (караван " + a.caravan + ", " + a.from + " → " + a.to + "): " + where + "; разбойников " + (a.strength - a.slain) + " из " + a.strength + "; до дня " + a.expires), false);
        }
        return TradeMod.TRADE.ambushes.size();
    }

    // ------------------------------------------------------------------ лавка: правый клик

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(e.getTarget() instanceof Villager v) || !v.getPersistentData().contains(SHOP_TAG)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getEntity() instanceof ServerPlayer sp) openShop(sp, v.getPersistentData().getInt(SHOP_TAG));
    }

    static void openShop(ServerPlayer sp, int id) { WFNet.sendView(sp, "wf_trade:shop", SHOPS.viewJson(id)); }

    // ------------------------------------------------------------------ команды /wftrade shop ...

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wftrade").then(Commands.literal("ambush").executes(c -> ambushes(c.getSource()))).then(Commands.literal("shop")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.string()).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), false, ""))
                        .then(Commands.argument("categories", StringArgumentType.word()).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), false, StringArgumentType.getString(c, "categories"))))))
                .then(Commands.literal("create_npc").requires(s -> s.hasPermission(2)).then(Commands.argument("name", StringArgumentType.string()).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), true, ""))
                        .then(Commands.argument("categories", StringArgumentType.word()).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), true, StringArgumentType.getString(c, "categories"))))))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("open").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> { ServerPlayer sp = c.getSource().getPlayer(); if (sp == null || !SHOPS.shops.containsKey(IntegerArgumentType.getInteger(c, "id"))) return 0; openShop(sp, IntegerArgumentType.getInteger(c, "id")); return 1; })))
                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> remove(c.getSource(), IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("margin").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("pct", DoubleArgumentType.doubleArg(0, 100)).executes(c -> margin(c.getSource(), IntegerArgumentType.getInteger(c, "id"), DoubleArgumentType.getDouble(c, "pct"))))))
                .then(Commands.literal("collect").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> collect(c.getSource(), IntegerArgumentType.getInteger(c, "id")))))
                .then(Commands.literal("buy").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("good", StringArgumentType.word()).then(Commands.argument("qty", IntegerArgumentType.integer(1, 500)).executes(c -> deal(c.getSource(), true, c))))))
                .then(Commands.literal("sell").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("good", StringArgumentType.word()).then(Commands.argument("qty", IntegerArgumentType.integer(1, 500)).executes(c -> deal(c.getSource(), false, c))))))));
    }

    private static boolean op(CommandSourceStack s) { return s.hasPermission(2); }
    private static int fail(CommandSourceStack s, String m) { s.sendFailure(Component.literal(m)); return 0; }

    private static int create(CommandSourceStack s, String name, boolean npc, String cats) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return fail(s, "Только для игрока");
        String realm = TradeMod.marketOf(sp);
        if (realm.isEmpty()) return fail(s, "Нет государства для рынка.");
        List<String> cl = new ArrayList<>();
        for (String c : cats.split(",")) if (!c.isBlank()) cl.add(c.trim());
        Shops.Shop shop = SHOPS.create(npc ? "" : "p:" + sp.getUUID(), name, realm, sp.serverLevel().dimension().location().toString(), sp.getX(), sp.getY(), sp.getZ(), cl);
        if (shop == null) return fail(s, SHOPS.lastError);
        keepShops(s.getServer());
        s.sendSuccess(() -> Component.literal("Лавка №" + shop.id + " «" + shop.name + "» открыта (рынок " + realm + ")."), false);
        return 1;
    }

    private static int list(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        String me = sp == null ? "" : "p:" + sp.getUUID();
        StringBuilder sb = new StringBuilder();
        for (Shops.Shop x : SHOPS.shops.values()) if (op(s) || x.owner.isEmpty() || x.owner.equals(me)) sb.append("№").append(x.id).append(" ").append(x.name).append(" (").append(x.realm).append(x.owner.equals(me) ? ", ваша, касса " + (int) x.cash : "").append(")\n");
        s.sendSuccess(() -> Component.literal(sb.length() == 0 ? "Лавок нет." : sb.toString().trim()), false);
        return 1;
    }

    private static int remove(CommandSourceStack s, int id) {
        ServerPlayer sp = s.getPlayer();
        Shops.Shop shop = SHOPS.shops.get(id);
        if (shop == null) return fail(s, "Нет такой лавки.");
        if (!SHOPS.remove(id, sp == null ? "" : "p:" + sp.getUUID(), op(s))) return fail(s, SHOPS.lastError);
        removeShopkeeper(s.getServer(), shop);
        s.sendSuccess(() -> Component.literal("Лавка закрыта."), false);
        return 1;
    }

    private static int margin(CommandSourceStack s, int id, double pct) {
        ServerPlayer sp = s.getPlayer();
        if (!SHOPS.setMargin(id, sp == null ? "" : "p:" + sp.getUUID(), op(s), pct)) return fail(s, SHOPS.lastError);
        s.sendSuccess(() -> Component.literal("Наценка: " + (int) pct + "%"), false);
        return 1;
    }

    private static int collect(CommandSourceStack s, int id) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        int n = SHOPS.collect(id, "p:" + sp.getUUID());
        if (n <= 0) return fail(s, SHOPS.lastError);
        s.sendSuccess(() -> Component.literal("Забрано из кассы: " + n + " зол."), false);
        return 1;
    }

    private static int deal(CommandSourceStack s, boolean buy, com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        int id = IntegerArgumentType.getInteger(c, "id"), qty = IntegerArgumentType.getInteger(c, "qty");
        String good = StringArgumentType.getString(c, "good"), o = "p:" + sp.getUUID();
        boolean ok = buy ? SHOPS.sellToPlayer(id, o, good, qty) : SHOPS.buyFromPlayer(id, o, good, qty);
        if (!ok) return fail(s, SHOPS.lastError);
        openShop(sp, id);
        return 1;
    }
}
