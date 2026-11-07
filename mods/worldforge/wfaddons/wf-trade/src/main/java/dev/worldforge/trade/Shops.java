package dev.worldforge.trade;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.TreasuryApi;

import java.util.*;

/**
 * Лавки: торговцы-NPC в мире (сущность ставит склейка) продают и покупают предметы по цене рынка своего государства плюс наценка лавки.
 * Лавка торгует только товарами, у которых есть предмет, и только выбранных категорий (пусто - любыми). Прибыль уходит владельцу (игрок) или купцам государства; казна получает торговый сбор.
 * Лавка покупает у игрока, пока в её кассе хватает денег; касса пополняется выручкой и дневным взносом.
 */
public final class Shops {
    public static final double FEE = 0.05, DEFAULT_MARGIN = 10, MAX_MARGIN = 40, START_CASH = 200, DAILY_FLOAT = 40, CREATE_COST = 100;
    public static final int MAX_PER_PLAYER = 3;

    public static final class Shop {
        public int id;
        public String name, realm, owner = "", dim = "";
        public double x, y, z, cash = START_CASH, margin = DEFAULT_MARGIN, profit;
        public final Set<String> categories = new TreeSet<>();
        public int sold, bought;
    }

    public final Map<Integer, Shop> shops = new LinkedHashMap<>();
    public String lastError = "";
    private int next = 1;
    private final Trade trade;

    public Shops(Trade trade) { this.trade = trade; }

    private boolean fail(String m) { lastError = m; return false; }

    // ---------- создание и управление ----------

    /** Создаёт лавку. owner "" - лавка купцов государства (бесплатно, для администратора). */
    public Shop create(String owner, String name, String realm, String dim, double x, double y, double z, Collection<String> categories) {
        if (name == null || name.isBlank()) { lastError = "Нужно название лавки."; return null; }
        if (!owner.isEmpty()) {
            long own = shops.values().stream().filter(s -> s.owner.equals(owner)).count();
            if (own >= MAX_PER_PLAYER) { lastError = "У вас уже " + MAX_PER_PLAYER + " лавки."; return null; }
            if (trade.hooks.gold(owner) < CREATE_COST) { lastError = "Нужно золота: " + (int) CREATE_COST + "."; return null; }
            trade.hooks.takeGold(owner, (int) CREATE_COST);
        }
        for (Shop s : shops.values()) if (s.dim.equals(dim) && Math.abs(s.x - x) < 4 && Math.abs(s.z - z) < 4) { lastError = "Рядом уже есть лавка."; return null; }
        Shop s = new Shop();
        s.id = next++; s.name = name.trim(); s.realm = realm; s.owner = owner; s.dim = dim; s.x = x; s.y = y; s.z = z;
        if (categories != null) s.categories.addAll(categories);
        shops.put(s.id, s);
        return s;
    }

    public boolean remove(int id, String actor, boolean operator) {
        Shop s = shops.get(id);
        if (s == null) return fail("Нет такой лавки.");
        if (!operator && !s.owner.equals(actor)) return fail("Это не ваша лавка.");
        if (!s.owner.isEmpty()) trade.hooks.giveGold(s.owner, (int) Math.floor(s.cash));
        shops.remove(id);
        return true;
    }

    public boolean setMargin(int id, String actor, boolean operator, double pct) {
        Shop s = shops.get(id);
        if (s == null) return fail("Нет такой лавки.");
        if (!operator && !s.owner.equals(actor)) return fail("Это не ваша лавка.");
        if (pct < 0 || pct > MAX_MARGIN) return fail("Наценка от 0 до " + (int) MAX_MARGIN + "%.");
        s.margin = pct;
        return true;
    }

    /** Владелец забирает кассу сверх резерва. */
    public int collect(int id, String actor) {
        Shop s = shops.get(id);
        if (s == null || !s.owner.equals(actor)) { lastError = "Это не ваша лавка."; return -1; }
        int take = (int) Math.floor(Math.max(0, s.cash - START_CASH / 2));
        if (take <= 0) { lastError = "В кассе нет лишних денег."; return 0; }
        s.cash -= take;
        trade.hooks.giveGold(actor, take);
        return take;
    }

    /** Ассортимент: товары с предметом и подходящей категории. */
    public List<TradeDefs.Good> assortment(Shop s) {
        List<TradeDefs.Good> l = new ArrayList<>();
        for (TradeDefs.Good g : trade.defs.goods.values()) if (!g.item().isEmpty() && !g.contraband() && (s.categories.isEmpty() || s.categories.contains(g.category()))) l.add(g);
        return l;
    }

    // ---------- цены и сделки ----------

    public double buyPrice(Shop s, String good, int qty) { return trade.quote(s.realm, good, qty, true) * (1 + s.margin / 100.0); }
    public double sellPrice(Shop s, String good, int qty) { return trade.quote(s.realm, good, qty, false) * (1 - s.margin / 100.0); }

    private TradeDefs.Good check(Shop s, String good, int qty) {
        TradeDefs.Good g = trade.defs.goods.get(good);
        if (g == null || !assortment(s).contains(g)) { lastError = "Здесь этим не торгуют."; return null; }
        if (qty <= 0 || qty > 500) { lastError = "Количество от 1 до 500."; return null; }
        return g;
    }

    /** Игрок покупает у лавки. */
    public boolean sellToPlayer(int id, String player, String good, int qty) {
        Shop s = shops.get(id);
        if (s == null) return fail("Нет такой лавки.");
        TradeDefs.Good g = check(s, good, qty);
        if (g == null) return false;
        Trade.Market m = trade.market(s.realm);
        if (m.stock.getOrDefault(good, 0.0) < qty + 1) return fail("На рынке нет столько товара.");
        double base = trade.quote(s.realm, good, qty, true), price = base * (1 + s.margin / 100.0);
        int cost = (int) Math.ceil(price);
        if (trade.hooks.gold(player) < cost) return fail("Не хватает золота: нужно " + cost + ".");
        trade.hooks.takeGold(player, cost);
        m.stock.put(good, m.stock.get(good) - qty);
        trade.hooks.give(player, g.item(), qty);
        double margin = cost - base, fee = Math.max(0, margin) * FEE;
        settle(s, cost, fee, margin);
        m.tradeVolume += base;
        s.sold += qty;
        return true;
    }

    /** Игрок продаёт лавке. */
    public boolean buyFromPlayer(int id, String player, String good, int qty) {
        Shop s = shops.get(id);
        if (s == null) return fail("Нет такой лавки.");
        TradeDefs.Good g = check(s, good, qty);
        if (g == null) return false;
        if (trade.hooks.count(player, g.item()) < qty) return fail("Не хватает предметов.");
        double base = trade.quote(s.realm, good, qty, false), pay = base * (1 - s.margin / 100.0);
        int gold = (int) Math.floor(pay);
        if (s.cash < gold) return fail("У торговца не хватает денег (" + (int) s.cash + ").");
        if (!trade.hooks.take(player, g.item(), qty)) return fail("Не удалось забрать предметы.");
        s.cash -= gold;
        trade.hooks.giveGold(player, gold);
        trade.market(s.realm).stock.merge(good, (double) qty, Double::sum);
        double margin = base - gold;
        s.profit += margin * (1 - FEE);
        if (margin * FEE > 0) TreasuryApi.add(s.realm, margin * FEE, "Торговля: сборы лавок");
        s.bought += qty;
        return true;
    }

    private void settle(Shop s, double paid, double fee, double margin) {
        s.cash += margin - fee;      // касса получает наценку; цена закупки уходит «на рынок» (товар берётся из запаса государства)
        s.profit += margin - fee;
        if (fee > 0) TreasuryApi.add(s.realm, fee, "Торговля: сборы лавок");
    }

    /** Один день: касса NPC-лавки пополняется (до стартовой), лавки без владельца отдают часть прибыли купцам государства. */
    public void tick() {
        for (Shop s : shops.values()) {
            if (s.owner.isEmpty()) {
                s.cash = Math.min(START_CASH * 2, s.cash + DAILY_FLOAT);
                if (s.profit > 0) { trade.capital.merge(s.realm, s.profit, Double::sum); s.profit = 0; }
            }
        }
    }

    // ---------- вид и сохранение ----------

    public String viewJson(int id) {
        Shop s = shops.get(id);
        if (s == null) return "{}";
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", s.id); root.put("name", s.name); root.put("realm", s.realm); root.put("owner", s.owner); root.put("cash", Math.round(s.cash)); root.put("margin", s.margin);
        List<Object> gl = new ArrayList<>();
        for (TradeDefs.Good g : assortment(s)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id()); m.put("name", g.name()); m.put("buy", Math.round(buyPrice(s, g.id(), 1) * 10) / 10.0); m.put("sell", Math.round(sellPrice(s, g.id(), 1) * 10) / 10.0);
            m.put("stock", Math.round(trade.market(s.realm).stock.getOrDefault(g.id(), 0.0))); m.put("item", g.item());
            gl.add(m);
        }
        root.put("goods", gl);
        return Json.write(root);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-shops/1"); root.put("next", next);
        List<Object> l = new ArrayList<>();
        for (Shop s : shops.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id); m.put("name", s.name); m.put("realm", s.realm); m.put("owner", s.owner); m.put("dim", s.dim);
            m.put("x", s.x); m.put("y", s.y); m.put("z", s.z); m.put("cash", s.cash); m.put("margin", s.margin); m.put("profit", s.profit);
            m.put("cats", new ArrayList<Object>(s.categories)); m.put("sold", s.sold); m.put("bought", s.bought);
            l.add(m);
        }
        root.put("shops", l);
        return Json.write(root);
    }

    public void fromJson(String json) {
        Object o = Json.parse(json);
        if (!(o instanceof Map<?, ?> root) || !"wf-shops/1".equals(root.get("format"))) throw new IllegalArgumentException("Неверный формат лавок");
        shops.clear();
        next = ((Number) root.get("next")).intValue();
        for (Object x : (List<?>) root.get("shops")) {
            Map<?, ?> m = (Map<?, ?>) x; Shop s = new Shop();
            s.id = ((Number) m.get("id")).intValue(); s.name = String.valueOf(m.get("name")); s.realm = String.valueOf(m.get("realm")); s.owner = String.valueOf(m.get("owner")); s.dim = String.valueOf(m.get("dim"));
            s.x = ((Number) m.get("x")).doubleValue(); s.y = ((Number) m.get("y")).doubleValue(); s.z = ((Number) m.get("z")).doubleValue();
            s.cash = ((Number) m.get("cash")).doubleValue(); s.margin = ((Number) m.get("margin")).doubleValue(); s.profit = ((Number) m.get("profit")).doubleValue();
            for (Object c : (List<?>) m.get("cats")) s.categories.add(String.valueOf(c));
            s.sold = ((Number) m.get("sold")).intValue(); s.bought = ((Number) m.get("bought")).intValue();
            shops.put(s.id, s);
        }
    }
}
