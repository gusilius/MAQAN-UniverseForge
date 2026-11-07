package dev.worldforge.trade;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.TreasuryApi;

import java.util.*;

/**
 * Рынки государств и караваны между ними.
 * Выпуск товара зависит от местности (доли из DemoApi), потребление - от рас (спрос по расам) и числа жителей; запас гонит цену вверх (дефицит) или вниз (избыток).
 * Караван покупает товар в одном государстве, везёт несколько дней (риск нападений, охрана снижает его) и продаёт в другом за вычетом пошлины, которая идёт в казну.
 * Хозяин каравана: "p:<игрок>" (платит золотом), "m:<государство>" (купцы государства со своим капиталом; отдают гильдейский налог в казну).
 */
public final class Trade {
    public static final double SPREAD = 0.06, GUILD_TAX = 0.05, MAX_TARIFF = 30, GUARD_COST = 2, CART_BASE = 40, CART_PER_GUARD = 10;
    public static final double CONTRABAND_TARIFF_X = 3, SEIZE_CHANCE = 0.3, START_CAPITAL = 300;
    public static final int MAX_CARAVANS = 6;
    public static final String[] SEASONS = {"Весна", "Лето", "Осень", "Зима"};

    public interface Hooks {
        default int size(String realm) { return 100; }
        default List<String> neighbors(String realm) { return List.of(); }
        default double distance(String a, String b) { return 5; }
        default boolean atWar(String a, String b) { return false; }
        /** Шанс нападения за день пути (0..1). */
        default double danger(String a, String b) { return 0.04; }
        /** Скидка на пошлину по договору, в процентных пунктах. */
        default double treaty(String a, String b) { return 0; }
        default int count(String owner, String item) { return 0; }
        default boolean take(String owner, String item, int n) { return false; }
        default void give(String owner, String item, int n) { }
        default int gold(String owner) { return 0; }
        default boolean takeGold(String owner, int n) { return false; }
        default void giveGold(String owner, int n) { }
        default void notify(String owner, String text) { }
        default void news(String text) { }
        default String realmOf(String owner) { return ""; }
        default int season() { return 0; }
    }

    public static final class Market {
        public final Map<String, Double> stock = new LinkedHashMap<>();
        public double tariff = 5;
        public final Set<String> embargo = new TreeSet<>();
        public double tariffIncome, guildIncome, tradeVolume;
        public boolean autoTrade = true;
    }

    public static final class Shock {
        public final String realm, good; public final double factor; public int days;
        Shock(String realm, String good, double factor, int days) { this.realm = realm; this.good = good; this.factor = factor; this.days = days; }
    }

    public static final class Caravan {
        public int id;
        public String owner, from, to, status = "в пути";
        public final Map<String, Integer> cargo = new LinkedHashMap<>();
        public double spent, revenue;
        public int guards, total, left;
        public int started;
    }

    /** Засада бандитов на дороге: груз лежит у них, пока отряд не разгромит разбойников; через AMBUSH_DAYS дней добыча пропадает. */
    public static final class Ambush {
        public int id, caravan, strength, expires, slain;
        public double progress, spent;                       // progress - доля пути каравана, где напали
        public String owner, from, to;
        public final Map<String, Integer> cargo = new LinkedHashMap<>();
    }

    public static final int AMBUSH_DAYS = 10;
    public static final double RECOVER_SHARE = 0.6;          // доля затрат, возвращаемая владельцу при отбитой добыче (часть товара испорчена)
    public final Map<Integer, Ambush> ambushes = new LinkedHashMap<>();

    public static final class TradeException extends RuntimeException { public TradeException(String m) { super(m); } }

    public final TradeDefs defs;
    public Hooks hooks = new Hooks() {};
    public Random rng = new Random();
    public String lastError = "";
    public final Map<String, Market> markets = new LinkedHashMap<>();
    public final Map<Integer, Caravan> caravans = new LinkedHashMap<>();
    public final Map<String, Double> capital = new LinkedHashMap<>();
    public final List<Shock> shocks = new ArrayList<>();
    public final List<String> chronicle = new ArrayList<>();
    private int next = 1;

    public Trade(TradeDefs defs) { this.defs = defs; }

    public Market market(String realm) {
        Market m = markets.get(realm);
        if (m == null) {
            m = new Market();
            markets.put(realm, m);
            for (TradeDefs.Good g : defs.goods.values()) m.stock.put(g.id(), Math.max(0, consumption(realm, g) * 8));
        }
        return m;
    }

    // ---------- выпуск, спрос, цены ----------

    private Map<String, Double> terrainOf(String realm) {
        Map<String, Double> t = DemoApi.terrain(realm);
        return t.isEmpty() ? Map.of("plains", 1.0) : t;
    }
    private Map<String, Double> racesOf(String realm) {
        Map<String, Double> r = DemoApi.races(realm);
        return r.isEmpty() ? Map.of("human", 1.0) : r;
    }

    public double shockFactor(String realm, String good) {
        double f = 1;
        for (Shock s : shocks) if (s.realm.equals(realm) && (s.good.equals(good) || s.good.equals("*"))) f *= s.factor;
        return f;
    }

    /** Выпуск в день: потребность x сумма(доля местности x множитель) x сезон x потрясения. Чужие товары, которых местность не даёт, почти не выпускаются. */
    public double production(String realm, TradeDefs.Good g) {
        double size = hooks.size(realm) / 100.0, f;
        if (g.terrain().isEmpty()) f = 0.8;
        else { f = 0.04; for (var e : terrainOf(realm).entrySet()) f += e.getValue() * g.terrain().getOrDefault(e.getKey(), 0.0); }
        double season = g.season()[Math.floorMod(hooks.season(), 4)];
        return size * Math.max(0.6, g.need()) * f * season * shockFactor(realm, g.id());
    }

    public double consumption(String realm, TradeDefs.Good g) {
        double size = hooks.size(realm) / 100.0, f = 0;
        for (var e : racesOf(realm).entrySet()) f += e.getValue() * g.races().getOrDefault(e.getKey(), 1.0);
        return size * g.need() * f;
    }

    /** Цена единицы при данном запасе. Запас на 10 дней потребления - базовая цена. */
    public double priceAt(String realm, TradeDefs.Good g, double stock) {
        double c = consumption(realm, g);
        if (c <= 0.0001) return g.base() * 0.5;
        double cover = stock / (c * 10);
        return g.base() * Math.max(0.4, Math.min(3.0, 3.0 / (1 + 2 * cover)));
    }

    public double price(String realm, String good) {
        TradeDefs.Good g = defs.goods.get(good);
        return g == null ? 0 : priceAt(realm, g, market(realm).stock.getOrDefault(good, 0.0));
    }

    /** Стоимость покупки/продажи пачки: цена пересчитывается по каждой единице (дорожает при скупке, дешевеет при продаже). */
    public double quote(String realm, String good, int qty, boolean buy) {
        TradeDefs.Good g = defs.goods.get(good);
        if (g == null || qty <= 0) return 0;
        double s = market(realm).stock.getOrDefault(good, 0.0), sum = 0;
        for (int i = 0; i < qty; i++) {
            double p = priceAt(realm, g, s);
            if (buy) { sum += p * (1 + SPREAD); s = Math.max(0, s - 1); } else { sum += p * (1 - SPREAD); s += 1; }
        }
        return sum;
    }

    // ---------- день ----------

    public List<String> tick(int day, Collection<String> realms) {
        List<String> ev = new ArrayList<>();
        for (String r : realms) {
            Market m = market(r);
            for (TradeDefs.Good g : defs.goods.values()) {
                double cap = Math.max(60, consumption(r, g) * 40);
                double v = m.stock.getOrDefault(g.id(), 0.0) + production(r, g) - consumption(r, g);
                m.stock.put(g.id(), Math.max(0, Math.min(cap, v)));
            }
            capital.putIfAbsent(r, START_CAPITAL * hooks.size(r) / 100.0);
        }
        shocks.removeIf(s -> --s.days <= 0);
        for (Ambush a : new ArrayList<>(ambushes.values())) {
            if (day < a.expires) continue;
            ambushes.remove(a.id);
            log(day, "бандиты увезли добычу каравана " + a.caravan + ": груз пропал");
            ev.add("ambush:" + a.id + ":expired");
        }
        for (Caravan c : new ArrayList<>(caravans.values())) {
            if (!c.status.equals("в пути")) { if (day - c.started > c.total + 20) caravans.remove(c.id); continue; }
            if (hooks.atWar(c.from, c.to)) { returnHome(c, day, "война"); ev.add("caravan:" + c.id + ":war"); continue; }
            double p = hooks.danger(c.from, c.to) * Math.max(0.2, 1 - c.guards * 0.08);
            if (c.cargo.keySet().stream().anyMatch(id -> defs.goods.get(id) != null && defs.goods.get(id).contraband())) p *= 1.5;
            if (rng.nextDouble() < p) {
                Ambush a = rob(c, day);
                ev.add("caravan:" + c.id + ":robbed");
                if (a != null) ev.add("ambush:" + a.id + ":new");
                continue;
            }
            if (c.guards > 0) spend(c.owner, c.from, c.guards * GUARD_COST);
            if (--c.left <= 0) { arrive(c, day); ev.add("caravan:" + c.id + ":" + c.status); }
        }
        for (String r : realms) if (market(r).autoTrade) { String s = aiTrade(r, day); if (s != null) ev.add(s); }
        return ev;
    }

    private void log(int day, String text) { chronicle.add("День " + day + ": " + text); if (chronicle.size() > 120) chronicle.remove(0); }
    private boolean fail(String m) { lastError = m; return false; }

    private void spend(String owner, String realm, double amt) {
        if (owner.startsWith("m:")) capital.merge(owner.substring(2), -amt, Double::sum);
        else if (owner.startsWith("r:")) TreasuryApi.add(owner.substring(2), -amt, "Торговля: казённые караваны");
        else hooks.takeGold(owner, (int) Math.ceil(amt));
    }

    private void pay(String owner, double amt) {
        if (owner.startsWith("m:")) capital.merge(owner.substring(2), amt, Double::sum);
        else if (owner.startsWith("r:")) TreasuryApi.add(owner.substring(2), amt, "Торговля: казённые караваны");
        else hooks.giveGold(owner, (int) Math.floor(amt));
    }

    private double funds(String owner) {
        if (owner.startsWith("m:")) return capital.getOrDefault(owner.substring(2), 0.0);
        if (owner.startsWith("r:")) return Double.MAX_VALUE / 4;
        return hooks.gold(owner);
    }

    // ---------- пошлины и запреты ----------

    public double tariffFor(String from, String to, TradeDefs.Good g) {
        double t = market(to).tariff - hooks.treaty(from, to);
        if (g.contraband()) t *= CONTRABAND_TARIFF_X;
        return Math.max(0, t) / 100.0;
    }

    public String blocked(String from, String to) {
        if (from.equals(to)) return "Нужны два разных государства.";
        if (market(from).embargo.contains(to)) return "Государство " + from + " запретило торговлю с " + to + ".";
        if (market(to).embargo.contains(from)) return "Государство " + to + " закрыло рынок для " + from + ".";
        if (hooks.atWar(from, to)) return "Идёт война: караваны не ходят.";
        return "";
    }

    public void setTariff(String realm, double pct) {
        if (pct < 0 || pct > MAX_TARIFF) throw new TradeException("Пошлина от 0 до " + (int) MAX_TARIFF + "%.");
        market(realm).tariff = pct;
    }

    public void setEmbargo(String realm, String target, boolean on) {
        if (on) market(realm).embargo.add(target); else market(realm).embargo.remove(target);
    }

    // ---------- караваны ----------

    public int capacity(int guards) { return (int) (CART_BASE + CART_PER_GUARD * guards); }

    public int load(Caravan c) { double w = 0; for (var e : c.cargo.entrySet()) w += e.getValue() * defs.goods.get(e.getKey()).weight(); return (int) Math.ceil(w); }

    /** Снаряжает караван с одним товаром. Возвращает null и lastError при отказе. */
    public Caravan dispatch(String owner, String from, String to, String good, int qty, int guards, int day) {
        TradeDefs.Good g = defs.goods.get(good);
        if (g == null) { lastError = "Нет такого товара."; return null; }
        String b = blocked(from, to);
        if (!b.isEmpty()) { lastError = b; return null; }
        if (guards < 0 || guards > 10) { lastError = "Охрана от 0 до 10."; return null; }
        if (qty <= 0) { lastError = "Нужно положительное количество."; return null; }
        if (qty * g.weight() > capacity(guards)) { lastError = "Не помещается: телеги везут до " + capacity(guards) + " (охрана добавляет места)."; return null; }
        long own = caravans.values().stream().filter(c -> c.owner.equals(owner) && c.status.equals("в пути")).count();
        if (own >= MAX_CARAVANS) { lastError = "Слишком много караванов в пути."; return null; }
        Market mk = market(from);
        if (mk.stock.getOrDefault(good, 0.0) < qty + 1) { lastError = "На рынке нет столько товара."; return null; }
        double cost = quote(from, good, qty, true), fee = guards * GUARD_COST;
        if (cost + fee > funds(owner)) { lastError = "Не хватает денег: нужно " + Math.round(cost + fee) + "."; return null; }
        spend(owner, from, cost + fee);
        mk.stock.put(good, mk.stock.get(good) - qty);
        Caravan c = new Caravan();
        c.id = next++; c.owner = owner; c.from = from; c.to = to; c.guards = guards; c.spent = cost + fee; c.started = day;
        c.cargo.put(good, qty);
        c.total = c.left = Math.max(1, (int) Math.ceil(hooks.distance(from, to)));
        caravans.put(c.id, c);
        log(day, "караван " + c.id + ": " + g.name() + " x" + qty + " из " + from + " в " + to);
        return c;
    }

    private Ambush rob(Caravan c, int day) {
        c.status = "разграблен";
        double keep = c.guards >= 6 ? 0.4 : 0;
        if (keep > 0) { c.cargo.replaceAll((k, v) -> (int) Math.floor(v * keep)); c.status = "отбит частично"; }
        log(day, "караван " + c.id + " ограблен по пути в " + c.to + (keep > 0 ? ", часть товара спасена охраной" : ""));
        hooks.notify(c.owner, "Ваш караван " + c.id + " подвергся нападению.");
        hooks.news("Купеческий караван ограблен на дороге из " + c.from + " в " + c.to + ".");
        if (keep > 0) { arrive(c, day); c.status = "отбит частично"; return null; }
        // весь груз у разбойников: его можно отбить
        Ambush a = new Ambush();
        a.id = nextAmbush++; a.caravan = c.id; a.owner = c.owner; a.from = c.from; a.to = c.to; a.spent = c.spent;
        a.progress = c.total <= 0 ? 0.5 : Math.max(0.1, Math.min(0.9, (double) (c.total - c.left) / c.total));
        a.expires = day + AMBUSH_DAYS;
        double weight = 0;
        for (var e : c.cargo.entrySet()) { TradeDefs.Good g = defs.goods.get(e.getKey()); weight += e.getValue() * (g == null ? 1 : g.weight()); }
        a.strength = (int) Math.max(3, Math.min(8, 3 + weight / 20));
        a.cargo.putAll(c.cargo);
        ambushes.put(a.id, a);
        c.cargo.clear();
        log(day, "груз каравана " + c.id + " у разбойников (" + a.strength + " чел.), его можно отбить за " + AMBUSH_DAYS + " дней");
        return a;
    }

    private int nextAmbush = 1;

    /** Убит один разбойник; когда убиты все - добыча отбита. Возвращает сообщение при победе, иначе null. */
    public String slay(int id, int day) {
        Ambush a = ambushes.get(id);
        if (a == null) return null;
        if (++a.slain < a.strength) return null;
        return recover(id, day);
    }

    /** Разбойники разгромлены: груз возвращается на рынок отправления, владелец получает долю затрат. Возвращает сообщение или null. */
    public String recover(int id, int day) {
        Ambush a = ambushes.remove(id);
        if (a == null) { lastError = "Нет такой засады."; return null; }
        Market home = market(a.from);
        for (var e : a.cargo.entrySet()) home.stock.merge(e.getKey(), (double) e.getValue(), Double::sum);
        double refund = Math.round(a.spent * RECOVER_SHARE);
        pay(a.owner, refund);
        Caravan c = caravans.get(a.caravan);
        if (c != null) { c.status = "добыча возвращена"; c.revenue = refund; }
        log(day, "добыча каравана " + a.caravan + " отбита у разбойников");
        hooks.notify(a.owner, "Добыча каравана " + a.caravan + " отбита, возвращено " + (int) refund + ".");
        hooks.news("Разбойники на дороге из " + a.from + " в " + a.to + " разбиты, купцы вернули груз.");
        return "Добыча возвращена: " + a.cargo.size() + " видов товара, выплата " + (int) refund;
    }

    private void returnHome(Caravan c, int day, String why) {
        Market home = market(c.from);
        for (var e : c.cargo.entrySet()) home.stock.merge(e.getKey(), (double) e.getValue(), Double::sum);
        double refund = c.spent * 0.8;
        pay(c.owner, refund);
        c.revenue = refund; c.status = "вернулся (" + why + ")"; c.cargo.clear();
        log(day, "караван " + c.id + " вернулся: " + why);
    }

    private void arrive(Caravan c, int day) {
        double revenue = 0, duty = 0;
        boolean seized = false;
        for (var e : new ArrayList<>(c.cargo.entrySet())) {
            TradeDefs.Good g = defs.goods.get(e.getKey());
            int qty = e.getValue();
            if (g.contraband() && rng.nextDouble() < SEIZE_CHANCE) { seized = true; TreasuryApi.add(c.to, qty * g.base() * 0.5, "Торговля: изъятая контрабанда"); duty += qty * g.base() * 0.5; c.cargo.remove(e.getKey()); continue; }
            double sale = quote(c.to, g.id(), qty, false);
            double d = sale * tariffFor(c.from, c.to, g);
            market(c.to).stock.merge(g.id(), (double) qty, Double::sum);
            revenue += sale - d; duty += d;
        }
        Market dest = market(c.to);
        dest.tariffIncome += duty; dest.tradeVolume += revenue;
        if (duty > 0) TreasuryApi.add(c.to, duty, "Торговля: пошлины");
        double profit = revenue - c.spent;
        double guild = 0;
        if (c.owner.startsWith("m:") && profit > 0) { guild = profit * GUILD_TAX; revenue -= guild; TreasuryApi.add(c.owner.substring(2), guild, "Торговля: налог гильдии"); market(c.owner.substring(2)).guildIncome += guild; }
        c.revenue = revenue;
        pay(c.owner, revenue);
        c.status = seized ? "конфискован (контрабанда)" : "доставлен";
        log(day, "караван " + c.id + " доставлен в " + c.to + ": выручка " + Math.round(revenue) + ", пошлина " + Math.round(duty) + (seized ? ", контрабанда изъята" : ""));
        hooks.notify(c.owner, "Караван " + c.id + " прибыл в " + c.to + ": выручка " + Math.round(revenue) + " (прибыль " + Math.round(revenue - c.spent) + ").");
    }

    // ---------- купцы государства (ИИ) ----------

    /** Лучшая пара «что и куда» для государства: ожидаемая прибыль на единицу после пошлины. Возвращает {to, good, margin}. */
    public Object[] bestRoute(String realm, double minMargin) {
        Object[] best = null;
        double bestM = minMargin;
        for (String to : hooks.neighbors(realm)) {
            if (!blocked(realm, to).isEmpty()) continue;
            for (TradeDefs.Good g : defs.goods.values()) {
                double surplus = market(realm).stock.getOrDefault(g.id(), 0.0) - consumption(realm, g) * 5;
                if (surplus < 8) continue;
                double buy = price(realm, g.id()) * (1 + SPREAD);
                double sell = price(to, g.id()) * (1 - SPREAD) * (1 - tariffFor(realm, to, g));
                double m = sell / buy - 1;
                if (m > bestM) { bestM = m; best = new Object[]{to, g.id(), m}; }
            }
        }
        return best;
    }

    private String aiTrade(String realm, int day) {
        if (rng.nextDouble() > 0.35) return null;
        String owner = "m:" + realm;
        if (caravans.values().stream().filter(c -> c.owner.equals(owner) && c.status.equals("в пути")).count() >= 2 + hooks.size(realm) / 200) return null;
        Object[] r = bestRoute(realm, 0.2);
        if (r == null) return null;
        TradeDefs.Good g = defs.goods.get((String) r[1]);
        int guards = rng.nextInt(4);
        int qty = (int) Math.min(capacity(guards) / g.weight(), market(realm).stock.get(g.id()) - consumption(realm, g) * 5);
        qty = (int) Math.min(qty, funds(owner) / Math.max(1, price(realm, g.id()) * 1.1));
        if (qty < 5) return null;
        Caravan c = dispatch(owner, realm, (String) r[0], g.id(), qty, guards, day);
        return c == null ? null : "ai:" + realm + ":" + c.id;
    }

    // ---------- личная торговля игрока ----------

    public boolean playerSell(String owner, String realm, String good, int qty) {
        TradeDefs.Good g = defs.goods.get(good);
        if (g == null || g.item().isEmpty()) return fail("Этот товар нельзя продать напрямую.");
        if (qty <= 0) return fail("Нужно положительное количество.");
        if (hooks.count(owner, g.item()) < qty) return fail("Не хватает предметов.");
        if (g.contraband()) return fail("Запрещённый товар: нужен тайный торговец.");
        double sum = quote(realm, good, qty, false), duty = sum * market(realm).tariff / 200.0;
        if (!hooks.take(owner, g.item(), qty)) return fail("Не удалось забрать предметы.");
        market(realm).stock.merge(good, (double) qty, Double::sum);
        hooks.giveGold(owner, (int) Math.floor(sum - duty));
        market(realm).tradeVolume += sum;
        if (duty > 0) { TreasuryApi.add(realm, duty, "Торговля: пошлины"); market(realm).tariffIncome += duty; }
        return true;
    }

    public boolean playerBuy(String owner, String realm, String good, int qty) {
        TradeDefs.Good g = defs.goods.get(good);
        if (g == null || g.item().isEmpty()) return fail("Этот товар нельзя купить напрямую.");
        if (qty <= 0) return fail("Нужно положительное количество.");
        if (g.contraband()) return fail("Запрещённый товар: нужен тайный торговец.");
        Market m = market(realm);
        if (m.stock.getOrDefault(good, 0.0) < qty + 1) return fail("На рынке нет столько товара.");
        double sum = quote(realm, good, qty, true);
        int cost = (int) Math.ceil(sum);
        if (hooks.gold(owner) < cost) return fail("Не хватает золота: нужно " + cost + ".");
        hooks.takeGold(owner, cost);
        m.stock.put(good, m.stock.get(good) - qty);
        hooks.give(owner, g.item(), qty);
        m.tradeVolume += sum;
        return true;
    }

    // ---------- потрясения (события, голод, чума) ----------

    public void shock(String realm, String good, double factor, int days) { shocks.add(new Shock(realm, good, factor, days)); }

    // ---------- отчёты ----------

    /** Торговые доходы государства для окна экономики: {пошлины, налог гильдии, объём}. */
    public double[] income(String realm) { Market m = market(realm); return new double[]{m.tariffIncome, m.guildIncome, m.tradeVolume}; }

    public String viewJson(String owner, String realm, Collection<String> realms) {
        Map<String, Object> root = new LinkedHashMap<>();
        Market mk = market(realm);
        List<Object> gl = new ArrayList<>();
        for (TradeDefs.Good g : defs.goods.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            double p = price(realm, g.id()), prod = production(realm, g), cons = consumption(realm, g);
            m.put("id", g.id()); m.put("name", g.name()); m.put("cat", g.category()); m.put("price", Math.round(p * 10) / 10.0); m.put("base", g.base());
            m.put("stock", Math.round(mk.stock.getOrDefault(g.id(), 0.0))); m.put("prod", Math.round(prod * 10) / 10.0); m.put("cons", Math.round(cons * 10) / 10.0);
            m.put("tag", p > g.base() * 1.3 ? "дефицит" : p < g.base() * 0.75 ? "избыток" : "");
            m.put("direct", !g.item().isEmpty()); m.put("contra", g.contraband());
            List<Object> nb = new ArrayList<>();
            for (String to : hooks.neighbors(realm)) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("realm", to); n.put("price", Math.round(price(to, g.id()) * 10) / 10.0);
                nb.add(n);
            }
            m.put("near", nb);
            gl.add(m);
        }
        root.put("realm", realm); root.put("tariff", mk.tariff); root.put("embargo", new ArrayList<Object>(mk.embargo));
        root.put("goods", gl);
        List<Object> cl = new ArrayList<>();
        for (Caravan c : caravans.values()) {
            if (!c.owner.equals(owner) && !c.owner.equals("r:" + realm) && !c.owner.equals("m:" + realm)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("from", c.from); m.put("to", c.to); m.put("status", c.status); m.put("left", c.left); m.put("total", c.total); m.put("guards", c.guards);
            { Map<String, Object> cg = new LinkedHashMap<>(); for (var ce : c.cargo.entrySet()) cg.put(defs.goods.get(ce.getKey()).name(), ce.getValue()); m.put("cargo", cg); } m.put("spent", Math.round(c.spent)); m.put("revenue", Math.round(c.revenue)); m.put("mine", c.owner.equals(owner));
            cl.add(m);
        }
        root.put("caravans", cl);
        List<Object> al = new ArrayList<>();
        for (Ambush a : ambushes.values()) {
            if (!a.owner.equals(owner) && !a.owner.equals("r:" + realm) && !a.owner.equals("m:" + realm)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id); m.put("from", a.from); m.put("to", a.to); m.put("strength", a.strength); m.put("expires", a.expires); m.put("progress", Math.round(a.progress * 100));
            al.add(m);
        }
        root.put("ambushes", al);
        root.put("income", new ArrayList<Object>(List.of(Math.round(mk.tariffIncome), Math.round(mk.guildIncome), Math.round(mk.tradeVolume))));
        root.put("capital", Math.round(capital.getOrDefault(realm, 0.0)));
        root.put("neighbors", new ArrayList<Object>(hooks.neighbors(realm)));
        root.put("chronicle", new ArrayList<Object>(chronicle.subList(Math.max(0, chronicle.size() - 12), chronicle.size())));
        return Json.write(root);
    }

    // ---------- сохранение ----------

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-trade/2"); root.put("next", next);
        Map<String, Object> ms = new LinkedHashMap<>();
        for (var e : markets.entrySet()) {
            Market m = e.getValue();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("stock", new LinkedHashMap<String, Object>(m.stock)); o.put("tariff", m.tariff); o.put("embargo", new ArrayList<Object>(m.embargo));
            o.put("ti", m.tariffIncome); o.put("gi", m.guildIncome); o.put("tv", m.tradeVolume); o.put("auto", m.autoTrade);
            ms.put(e.getKey(), o);
        }
        root.put("markets", ms);
        root.put("capital", new LinkedHashMap<String, Object>(capital));
        List<Object> cl = new ArrayList<>();
        for (Caravan c : caravans.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("owner", c.owner); m.put("from", c.from); m.put("to", c.to); m.put("status", c.status); m.put("cargo", new LinkedHashMap<String, Object>(c.cargo));
            m.put("spent", c.spent); m.put("revenue", c.revenue); m.put("guards", c.guards); m.put("total", c.total); m.put("left", c.left); m.put("started", c.started);
            cl.add(m);
        }
        root.put("caravans", cl);
        List<Object> sl = new ArrayList<>();
        for (Shock s : shocks) { Map<String, Object> m = new LinkedHashMap<>(); m.put("realm", s.realm); m.put("good", s.good); m.put("factor", s.factor); m.put("days", s.days); sl.add(m); }
        root.put("shocks", sl);
        List<Object> aml = new ArrayList<>();
        for (Ambush a : ambushes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id); m.put("caravan", a.caravan); m.put("owner", a.owner); m.put("from", a.from); m.put("to", a.to); m.put("spent", a.spent); m.put("progress", a.progress);
            m.put("strength", a.strength); m.put("expires", a.expires); m.put("slain", a.slain); m.put("cargo", new LinkedHashMap<String, Object>(a.cargo));
            aml.add(m);
        }
        root.put("ambushes", aml); root.put("nextAmbush", nextAmbush);
        root.put("chronicle", new ArrayList<Object>(chronicle));
        return Json.write(root);
    }

    private static double d(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static int i(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (!"wf-trade/2".equals(root.get("format"))) throw new IllegalArgumentException("Неверный формат торговли");
        markets.clear(); caravans.clear(); ambushes.clear(); capital.clear(); shocks.clear(); chronicle.clear();
        next = i(root.get("next"));
        for (var e : ((Map<String, Object>) root.get("markets")).entrySet()) {
            Map<String, Object> o = (Map<String, Object>) e.getValue();
            Market m = new Market();
            for (var s : ((Map<String, Object>) o.get("stock")).entrySet()) m.stock.put(s.getKey(), d(s.getValue()));
            m.tariff = d(o.get("tariff")); for (Object x : (List<Object>) o.get("embargo")) m.embargo.add(String.valueOf(x));
            m.tariffIncome = d(o.get("ti")); m.guildIncome = d(o.get("gi")); m.tradeVolume = d(o.get("tv")); m.autoTrade = !Boolean.FALSE.equals(o.get("auto"));
            markets.put(e.getKey(), m);
        }
        for (var e : ((Map<String, Object>) root.get("capital")).entrySet()) capital.put(e.getKey(), d(e.getValue()));
        for (Object o : (List<Object>) root.get("caravans")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Caravan c = new Caravan();
            c.id = i(m.get("id")); c.owner = String.valueOf(m.get("owner")); c.from = String.valueOf(m.get("from")); c.to = String.valueOf(m.get("to")); c.status = String.valueOf(m.get("status"));
            for (var e : ((Map<String, Object>) m.get("cargo")).entrySet()) c.cargo.put(e.getKey(), i(e.getValue()));
            c.spent = d(m.get("spent")); c.revenue = d(m.get("revenue")); c.guards = i(m.get("guards")); c.total = i(m.get("total")); c.left = i(m.get("left")); c.started = i(m.get("started"));
            caravans.put(c.id, c);
        }
        for (Object o : (List<Object>) root.get("shocks")) { Map<String, Object> m = (Map<String, Object>) o; shocks.add(new Shock(String.valueOf(m.get("realm")), String.valueOf(m.get("good")), d(m.get("factor")), i(m.get("days")))); }
        ambushes.clear(); nextAmbush = Math.max(1, i(root.get("nextAmbush")));
        if (root.get("ambushes") instanceof List<?> al) for (Object o : al) {
            Map<String, Object> m = (Map<String, Object>) o;
            Ambush a = new Ambush();
            a.id = i(m.get("id")); a.caravan = i(m.get("caravan")); a.owner = String.valueOf(m.get("owner")); a.from = String.valueOf(m.get("from")); a.to = String.valueOf(m.get("to"));
            a.spent = d(m.get("spent")); a.progress = d(m.get("progress")); a.strength = i(m.get("strength")); a.expires = i(m.get("expires")); a.slain = i(m.get("slain"));
            for (var e : ((Map<String, Object>) m.get("cargo")).entrySet()) a.cargo.put(e.getKey(), i(e.getValue()));
            ambushes.put(a.id, a);
        }
        for (Object o : (List<Object>) root.get("chronicle")) chronicle.add(String.valueOf(o));
    }
}
