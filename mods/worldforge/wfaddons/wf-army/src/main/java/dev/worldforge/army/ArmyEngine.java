package dev.worldforge.army;

import dev.worldforge.war.War;
import dev.worldforge.war.WarEngine;
import dev.worldforge.war.Attitude;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.api.TreasuryApi;

import java.util.*;

/**
 * Армии, сражения и осады поверх {@link WarEngine}. Армии стоят и ходят по баронствам (граф соседства карты). Столкнувшиеся армии
 * воюющих сторон дерутся (камень-ножницы-бумага, мораль, командир, местность), проигравший отступает; армия на земле врага без
 * вражеской армии осаждает баронство и, взяв его, даёт очки войны; взяты все баронства титула - занят и сам титул.
 * Люди берутся из ополчения (запас растёт сам), наёмников и профессиональные отряды покупают за золото.
 * Ходом {@link #tick(double)} управляют днём: движение, бои, осады, содержание, ИИ, затем ход войн.
 */
public final class ArmyEngine {
    public static final double LEVY_PER_BARONY = 40, POOL_REGEN = 0.01, SIEGE_BASE_DAYS = 10, START_GOLD = 60, INCOME_PER_BARONY = 0.4, SUPPLY_DAYS = 25, RESUPPLY_PER_DAY = 0.25, FORAGE_MEN = 80, STARVE_LOSS = 0.02, STARVE_MORALE = 0.03;

    public final WarEngine war;
    public final Map<String, UnitType> types = UnitType.defaults();
    public final Map<Integer, Army> armies = new LinkedHashMap<>();
    public final Map<String, Double> gold = new LinkedHashMap<>(), pool = new LinkedHashMap<>();
    public final Set<String> humans = new LinkedHashSet<>();                 // государства под управлением игроков: ИИ их не трогает
    public final Map<Integer, double[]> sieges = new LinkedHashMap<>();       // баронство -> {id армии, прогресс, нужно дней}
    public final List<String> events = new ArrayList<>();
    public boolean ai = true;
    private int nextArmy = 1;
    /** Дробная часть суток между вызовами tick; сохраняется, чтобы частые малые шаги не терялись. */
    private double dayAccumulator;
    private Map<String, List<Integer>> baronyCache = new HashMap<>();

    public ArmyEngine(WarEngine war) { this.war = war; war.driver = this::tick; }

    private TitleData d() { return war.editor.data(); }

    public void loadUnits(String json) { Map<String, UnitType> m = UnitType.parse(json); if (!m.isEmpty()) { types.clear(); types.putAll(m); } }

    public final Economy eco = new Economy(this);
    void log(String s) { say(s); }

    private void say(String s) { events.add("[день " + war.day + "] " + s); while (events.size() > 120) events.remove(0); }

    // ------------------------------------------------------------------ земли и ресурсы

    /** Баронства государства (потомки его титулов). */
    public List<Integer> baroniesOf(String realm) {
        return baronyCache.computeIfAbsent(realm, r -> {
            List<Integer> out = new ArrayList<>();
            TitleData.Realm re = d().realms.get(r);
            if (re == null) return out;
            Deque<TitleData.Title> q = new ArrayDeque<>();
            for (String k : re.titleKeys) { TitleData.Title t = d().byKey(k); if (t != null) q.add(t); }
            Set<Integer> seen = new HashSet<>();
            while (!q.isEmpty()) {
                TitleData.Title t = q.poll();
                if (!seen.add(t.id)) continue;
                if (t.tier == 0) out.add(t.id);
                for (int c : t.children) { TitleData.Title ch = d().get(c); if (ch != null) q.add(ch); }
            }
            return out;
        });
    }

    public void invalidate() { baronyCache.clear(); owners.clear(); }

    private final Map<Integer, String> owners = new HashMap<>();

    /** Кто владеет баронством (с учётом того, что вассал - отдельное государство). */
    public String ownerOf(int barony) {
        return owners.computeIfAbsent(barony, id -> { TitleData.Realm r = war.ownerOf(d().get(id)); return r == null ? "" : r.id; });
    }

    public double levyCap(String realm) { return LEVY_PER_BARONY * baroniesOf(realm).size(); }

    public double pool(String realm) { return pool.computeIfAbsent(realm, r -> levyCap(r)); }

    /**
     * Canonical realm wealth is held by TreasuryLedger in silver; this adapter preserves
     * ArmyEngine's historic gold-facing API and migrates a wf_army/1 balance exactly once.
     */
    public double gold(String realm) {
        if (realm == null || realm.isBlank()) return 0;
        Double legacy = gold.remove(realm);
        if (legacy != null) TreasuryApi.migrateLegacyRealmBalance(realm, legacy);
        TreasuryApi.ensureRealmAccount(realm, legacy == null ? START_GOLD : 0);
        return TreasuryApi.netBalance(realm);
    }

    void postGoldDelta(String realm, double delta, String category, String reference) {
        long minute = Math.max(0L, war.day) * 1440L;
        if (!TreasuryApi.postDeltaOnce(realm, delta, category, reference, minute))
            throw new TitleEditor.EditException("Не удалось провести операцию через казну: " + realm);
    }

    public int capital(String realm) {
        TitleData.Realm re = d().realms.get(realm);
        if (re != null) for (String k : re.titleKeys) {
            TitleData.Title t = d().byKey(k);
            while (t != null && t.tier > 0 && t.children.length > 0) t = d().get(t.children[0]);
            if (t != null && t.tier == 0) return t.id;
        }
        List<Integer> b = baroniesOf(realm);
        return b.isEmpty() ? 0 : b.get(0);
    }

    public String title(int id) { TitleData.Title t = d().get(id); return t == null ? "#" + id : t.name; }

    public String realmName(String id) { TitleData.Realm r = d().realms.get(id); return r == null ? id : r.name; }

    public List<Army> armiesOf(String realm) {
        List<Army> l = new ArrayList<>();
        for (Army a : armies.values()) if (a.realm.equals(realm)) l.add(a);
        return l;
    }

    // ------------------------------------------------------------------ набор

    /** Стоимость найма в золоте (ополчение бесплатно). */
    public double cost(String type, int n, boolean merc) { UnitType u = types.get(type); return u == null || (u.levy && !merc) ? 0 : u.cost * n * (merc ? 2 : 1) / 10.0; }

    private Army armyAt(String realm, int loc, boolean merc) {
        for (Army a : armies.values()) if (a.realm.equals(realm) && a.location == loc && !a.moving() && a.mercenary == merc) return a;
        Army a = new Army(nextArmy++, realm, (armiesOf(realm).size() + 1) + "-я армия", loc);
        a.mercenary = merc;
        TitleData.Realm re = d().realms.get(realm);
        if (re != null && re.props.get("council.commander") instanceof Number n) a.martial = (int) Math.round(n.doubleValue());   // мягкая связь с wf-people: маршал и правитель
        armies.put(a.id, a);
        return a;
    }

    /** Набрать n человек типа type в столице. Ополченцы тратят запас людей, остальные - золото (n/10 * цена). */
    public Army raise(String realm, String type, int n) {
        UnitType u = types.get(type);
        if (u == null) throw new TitleEditor.EditException("Нет типа отряда " + type);
        if (n <= 0) throw new TitleEditor.EditException("Нужно положительное число");
        if (pool(realm) < n) throw new TitleEditor.EditException("Не хватает людей в ополчении: " + (int) pool(realm) + " из " + n);
        double c = cost(type, n, false);
        if (gold(realm) < c) throw new TitleEditor.EditException("Не хватает золота: " + (int) gold(realm) + " из " + Math.ceil(c));
        int cap = capital(realm);
        if (cap == 0) throw new TitleEditor.EditException("У государства нет земель");
        if (c > 0) postGoldDelta(realm, -c, "army-recruitment", "army-recruit:" + nextArmy + ":" + type + ":" + n);
        pool.put(realm, pool(realm) - n);
        Army a = armyAt(realm, cap, false);
        a.add(type, n);
        say(realmName(realm) + " набирает " + n + " " + u.name.toLowerCase());
        return a;
    }

    /** Нанять наёмников: дорого (x2), не тратит запас людей, содержание x2. */
    public Army hire(String realm, String type, int n) {
        UnitType u = types.get(type);
        if (u == null) throw new TitleEditor.EditException("Нет типа отряда " + type);
        if (n <= 0) throw new TitleEditor.EditException("Нужно положительное число");
        int cap = capital(realm);
        if (cap == 0) throw new TitleEditor.EditException("У государства нет земель для размещения наёмников");
        double c = Math.max(1, cost(type, n, true));
        if (gold(realm) < c) throw new TitleEditor.EditException("Не хватает золота: " + (int) gold(realm) + " из " + Math.ceil(c));
        postGoldDelta(realm, -c, "army-mercenary-hiring", "army-mercenary:" + nextArmy + ":" + type + ":" + n);
        Army a = armyAt(realm, cap, true);
        a.add(type, n);
        say(realmName(realm) + " нанимает " + n + " " + u.name.toLowerCase() + " (наёмники)");
        return a;
    }

    public Army army(int id) { Army a = armies.get(id); if (a == null) throw new TitleEditor.EditException("Нет армии " + id); return a; }

    private Army own(int id, String realm) {
        Army a = army(id);
        if (realm != null && !a.realm.equals(realm)) throw new TitleEditor.EditException("Это армия другого государства");
        return a;
    }

    public void disband(int id, String realm) {
        Army a = own(id, realm);
        if (!a.mercenary) pool.put(a.realm, Math.min(levyCap(a.realm), pool(a.realm) + a.units.entrySet().stream().filter(e -> types.get(e.getKey()) != null && types.get(e.getKey()).levy).mapToInt(Map.Entry::getValue).sum()));
        armies.remove(id);
        sieges.values().removeIf(s -> (int) s[0] == id);
    }

    public void merge(int into, int from, String realm) {
        Army a = own(into, realm), b = own(from, realm);
        if (a == b) throw new TitleEditor.EditException("Нельзя слить армию с самой собой");
        if (a.location != b.location) throw new TitleEditor.EditException("Армии должны стоять в одном месте");
        if (a.mercenary != b.mercenary) throw new TitleEditor.EditException("Наёмников нельзя сливать с войском");
        for (Map.Entry<String, Integer> e : b.units.entrySet()) a.add(e.getKey(), e.getValue());
        armies.remove(from);
        sieges.values().removeIf(s -> (int) s[0] == from);
    }

    /** Отделить отряд заданного типа и размера в новую армию на том же месте. */
    public Army split(int id, String type, int n, String realm) {
        Army a = own(id, realm);
        if (n <= 0 || a.count(type) < n) throw new TitleEditor.EditException("В армии нет столько: " + type);
        if (a.count(type) == a.men() && n == a.men()) throw new TitleEditor.EditException("Нельзя отделить всё: используйте приказ движения");
        a.units.merge(type, -n, Integer::sum);
        if (a.units.get(type) == 0) a.units.remove(type);
        Army b = new Army(nextArmy++, a.realm, (armiesOf(a.realm).size() + 1) + "-я армия", a.location);
        b.mercenary = a.mercenary; b.martial = 0;
        b.add(type, n);
        armies.put(b.id, b);
        return b;
    }

    // ------------------------------------------------------------------ движение

    private int[] nb(int t) { TitleData.Title x = d().get(t); return x == null ? new int[0] : x.neighbors; }

    /** Кратчайший путь по баронствам (без начала); пусто - нет пути. */
    public List<Integer> route(int from, int to) {
        if (from == to) return new ArrayList<>();
        Map<Integer, Integer> prev = new HashMap<>();
        Deque<Integer> q = new ArrayDeque<>();
        prev.put(from, 0);
        q.add(from);
        while (!q.isEmpty()) {
            int c = q.poll();
            if (c == to) break;
            for (int n : nb(c)) if (!prev.containsKey(n)) { prev.put(n, c); q.add(n); }
        }
        if (!prev.containsKey(to)) return new ArrayList<>();
        LinkedList<Integer> p = new LinkedList<>();
        for (int c = to; c != from; c = prev.get(c)) p.addFirst(c);
        return p;
    }

    /** Приказ идти в баронство. */
    public void move(int id, int target, String realm) {
        Army a = own(id, realm);
        TitleData.Title t = d().get(target);
        if (t == null || t.tier != 0) throw new TitleEditor.EditException("Армии ходят по баронствам");
        List<Integer> p = route(a.location, target);
        if (p.isEmpty() && a.location != target) throw new TitleEditor.EditException("Нет пути в " + t.name);
        a.path.clear();
        a.path.addAll(p);
        endSiege(a);
        a.lastEvent = p.isEmpty() ? "стоит" : "идёт в " + t.name;
    }

    public void stop(int id, String realm) { Army a = own(id, realm); a.path.clear(); a.lastEvent = "стоит"; }

    private void endSiege(Army a) {
        if (a.siegeTitle != 0) { sieges.remove(a.siegeTitle); a.siegeTitle = 0; }
    }

    // ------------------------------------------------------------------ отношения

    /** Активная война, в которой a и b на разных сторонах. */
    public War enemyWar(String a, String b) {
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) return null;
        for (War w : war.wars.values()) {
            if (!w.active) continue;
            War.Side sa = w.sides.get(a), sb = w.sides.get(b);
            if (sa != null && sb != null && sa != sb) return w;
        }
        return null;
    }

    // ------------------------------------------------------------------ ход времени

    public void tick(double days) {
        if (!Double.isFinite(days) || days <= 0) return;
        if (days > 3650) throw new IllegalArgumentException("За один вызов можно обработать не более 3650 игровых дней");
        dayAccumulator += days;
        long wholeDays = (long) Math.floor(dayAccumulator);
        if (wholeDays <= 0) return;
        dayAccumulator -= wholeDays;
        long target = war.day + wholeDays;
        while (war.day < target) {
            if (war.day % 30 == 0) invalidate();
            upkeep();
            movement();
            supplies();
            battles();
            sieges();
            if (ai && war.day % 2 == 0) aiTurn();
            war.tick(1);
            armies.values().removeIf(a -> a.men() <= 0);
        }
    }

    // ---- торговые договоры: взаимный доход золота, пока нет войны между сторонами
    public final Set<String> pacts = new TreeSet<>();
    public static final double PACT_RATE = 0.12;

    private static String pk(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }
    public boolean hasPact(String a, String b) { return pacts.contains(pk(a, b)); }
    public Set<String> partners(String r) {
        Set<String> out = new TreeSet<>();
        for (String k : pacts) { String[] p = k.split("\\|"); if (p[0].equals(r)) out.add(p[1]); else if (p[1].equals(r)) out.add(p[0]); }
        return out;
    }
    /** Доход каждой стороны в день: растёт с корнем из произведения числа баронств сторон. */
    public double pactIncome(String a, String b) { return PACT_RATE * Math.sqrt((double) Math.max(1, baroniesOf(a).size()) * Math.max(1, baroniesOf(b).size())); }
    public double pactIncome(String r) { double t = 0; for (String o : partners(r)) t += pactIncome(r, o); return t; }

    /** Предложение торгового договора: принимается, если отношение другой стороны не хуже нуля и войны нет. Возвращает причину отказа или null. */
    public String proposePact(String from, String to) {
        if (from.equals(to) || !d().realms.containsKey(from) || !d().realms.containsKey(to)) throw new TitleEditor.EditException("Нужно другое государство");
        if (hasPact(from, to)) throw new TitleEditor.EditException("Договор уже есть");
        if (war.warBetween(from, to) != null) return "Идёт война";
        Attitude at = Attitude.of(war, to, from);
        if (at.total < -10) return "Недоверие (" + at.total + ")";
        pacts.add(pk(from, to));
        say("Торговый договор: " + realmName(from) + " и " + realmName(to));
        return null;
    }
    public void cancelPact(String a, String b) {
        if (!pacts.remove(pk(a, b))) throw new TitleEditor.EditException("Договора нет");
        say("Торговый договор расторгнут: " + realmName(a) + " и " + realmName(b));
    }

    private void pactsDaily() {
        for (String k : new ArrayList<>(pacts)) {
            String[] p = k.split("\\|");
            if (war.warBetween(p[0], p[1]) != null) { pacts.remove(k); say("Война разорвала торговый договор: " + realmName(p[0]) + " и " + realmName(p[1])); continue; }
        }
    }

    private void upkeep() {
        pactsDaily();
        Set<String> realms = new LinkedHashSet<>();
        for (TitleData.Realm r : d().realmList()) if (!r.titleKeys.isEmpty()) realms.add(r.id);
        for (String r : realms) {
            if (!gold.containsKey(r) && !pool.containsKey(r) && armiesOf(r).isEmpty() && !isAtWar(r)) continue;   // спящие государства не считаем
            double inc = eco.net(r), cost = 0;
            for (Army a : armiesOf(r)) for (Map.Entry<String, Integer> e : a.units.entrySet()) {
                UnitType u = types.get(e.getKey());
                if (u != null) cost += e.getValue() * u.upkeep * (a.mercenary ? 2 : 1) / 10.0;
            }
            double g = inc - cost;
            postGoldDelta(r, g, "army-daily-net", "army-day:" + (war.day + 1));
            double cap = levyCap(r);
            pool.put(r, Math.min(cap, pool(r) + cap * POOL_REGEN));
            if (g < 0) for (Army a : armiesOf(r)) {
                a.morale = Math.max(0.1, a.morale - 0.03);
                for (String k : new ArrayList<>(a.units.keySet())) { int left = (int) Math.floor(a.units.get(k) * 0.98); if (left <= 0) a.units.remove(k); else a.units.put(k, left); }
            }
        }
    }

    private boolean isAtWar(String r) { for (War w : war.wars.values()) if (w.active && w.sides.containsKey(r)) return true; return false; }

    private void movement() {
        for (Army a : armies.values()) {
            if (a.moving()) {
                a.moveAcc += a.speed(types);
                while (a.moveAcc >= 1 && a.moving()) {
                    a.moveAcc -= 1;
                    a.location = a.path.remove(0);
                    // вошли в землю, где стоит армия врага - остановка: сражение
                    if (hostileArmyAt(a)) { a.path.clear(); a.lastEvent = "встретила врага в " + title(a.location); break; }
                }
                if (!a.moving()) { a.moveAcc = 0; if (a.lastEvent.startsWith("идёт")) a.lastEvent = "прибыла в " + title(a.location); }
            } else a.morale = Math.min(1.0, a.morale + 0.02);
        }
    }

    private boolean hostileArmyAt(Army a) {
        for (Army b : armies.values()) if (b != a && b.location == a.location && enemyWar(a.realm, b.realm) != null) return true;
        return false;
    }

    // ------------------------------------------------------------------ сражения

    private void battles() {
        Map<Integer, List<Army>> byLoc = new LinkedHashMap<>();
        for (Army a : armies.values()) byLoc.computeIfAbsent(a.location, k -> new ArrayList<>()).add(a);
        for (Map.Entry<Integer, List<Army>> e : byLoc.entrySet()) {
            List<Army> here = e.getValue();
            if (here.size() < 2) continue;
            // найти пару врагов и разыграть бой между всеми армиями их сторон (один бой на место в день)
            boolean done = false;
            for (Army a : here) { if (done) break; for (Army b : here) {
                if (a.men() <= 0 || b.men() <= 0) continue;
                War w = enemyWar(a.realm, b.realm);
                if (w == null) continue;
                War.Side sa = w.sides.get(a.realm);
                List<Army> sideA = new ArrayList<>(), sideB = new ArrayList<>();
                for (Army x : here) { War.Side s = w.sides.get(x.realm); if (x.men() <= 0 || s == null) continue; (s == sa ? sideA : sideB).add(x); }
                fight(w, e.getKey(), sa, sideA, sideB);
                done = true;
                break;
            } }
        }
    }

    private double[] strength(List<Army> side, List<Army> foe) {
        Map<String, Double> share = new HashMap<>();
        double tot = 0;
        for (Army f : foe) for (Map.Entry<String, Integer> u : f.units.entrySet()) { share.merge(u.getKey(), (double) u.getValue(), Double::sum); tot += u.getValue(); }
        double off = 0, def = 0;
        for (Army a : side) {
            double cmd = (1 + 0.03 * a.martial) * (0.5 + 0.5 * a.morale);
            for (Map.Entry<String, Integer> e : a.units.entrySet()) {
                UnitType u = types.get(e.getKey());
                if (u == null) continue;
                double mult = 1;
                if (tot > 0) for (Map.Entry<String, Double> s : share.entrySet()) mult += s.getValue() / tot * (u.counter(s.getKey()) - 1);
                off += e.getValue() * u.attack * mult * cmd;
                def += e.getValue() * u.defense * cmd;
            }
        }
        return new double[]{off, def};
    }

    private void fight(War w, int loc, War.Side sideOfA, List<Army> A, List<Army> B) {
        Random rnd = new Random(war.day * 7919L + loc);
        int menA0 = men(A), menB0 = men(B);
        String owner = ownerOf(loc);
        boolean defA = A.stream().anyMatch(x -> x.realm.equals(owner)) || !A.stream().anyMatch(Army::moving) && B.stream().anyMatch(Army::moving);
        double tA = 1, tB = 1;
        if (defA) tA = 1.15; else tB = 1.15;
        for (int round = 0; round < 8 && men(A) > 0 && men(B) > 0; round++) {
            double[] sa = strength(A, B), sb = strength(B, A);
            double dmgToB = sa[0] * tA * 0.10 * (0.85 + 0.3 * rnd.nextDouble());
            double dmgToA = sb[0] * tB * 0.10 * (0.85 + 0.3 * rnd.nextDouble());
            // защита снижает потери: доля убитых = урон / (оборона + урон)
            double kB = Math.min(men(B), dmgToB * (men(B) / Math.max(1, sb[1] + dmgToB)));
            double kA = Math.min(men(A), dmgToA * (men(A) / Math.max(1, sa[1] + dmgToA)));
            casualties(B, kB);
            casualties(A, kA);
            double lossShareA = kA / Math.max(1, men(A) + kA), lossShareB = kB / Math.max(1, men(B) + kB);
            for (Army x : A) x.morale = Math.max(0, x.morale - lossShareA * 2.2 - 0.03);
            for (Army x : B) x.morale = Math.max(0, x.morale - lossShareB * 2.2 - 0.03);
            if (avgMorale(A) <= 0.15 || avgMorale(B) <= 0.15) break;
        }
        int la = menA0 - men(A), lb = menB0 - men(B);
        boolean aWins = men(A) > 0 && (men(B) == 0 || avgMorale(A) * Math.sqrt(men(A)) >= avgMorale(B) * Math.sqrt(men(B)));
        List<Army> win = aWins ? A : B, lose = aWins ? B : A;
        War.Side ws = aWins ? sideOfA : sideOfA.opposite();
        double weight = Math.max(1, Math.min(5, (la + lb) / 150.0));
        String txt = "Битва за " + title(loc) + ": победа " + realmName(win.get(0).realm) + " (потери " + (aWins ? la : lb) + " против " + (aWins ? lb : la) + ")";
        war.battle(w.id, ws, weight, txt);
        say(txt);
        for (Army x : lose) retreat(x);
        for (Army x : win) { x.lastEvent = "победила в " + title(loc); x.path.clear(); }
        for (Army x : A) x.path.clear();
        for (Army x : B) x.path.clear();
    }

    private int men(List<Army> l) { int n = 0; for (Army a : l) n += a.men(); return n; }

    private double avgMorale(List<Army> l) { double s = 0; int n = 0; for (Army a : l) { s += a.morale * a.men(); n += a.men(); } return n == 0 ? 0 : s / n; }

    /** Распределить потери пропорционально по всем отрядам стороны. */
    /** Свои и союзные земли кормят войско; в чужих припасы тают (быстрее у больших армий), а без них идут потери и падает мораль. */
    private void supplies() {
        for (Army a : new ArrayList<>(armies.values())) {
            if (a.men() <= 0) continue;
            String owner = ownerOf(a.location);
            boolean home = owner == null || owner.equals(a.realm) || war.alliesOf(a.realm).contains(owner);
            if (!home) {
                War w = enemyWar(a.realm, owner);
                if (w != null && w.occupied.get(a.location) == w.sides.get(a.realm)) home = true;       // занятая земля работает на нас
            }
            if (home) { a.supply = Math.min(1, a.supply + RESUPPLY_PER_DAY); continue; }
            double use = Math.max(1, Math.min(3, a.men() / FORAGE_MEN)) / SUPPLY_DAYS;
            a.supply = Math.max(0, a.supply - use);
            if (a.supply <= 0) {
                a.morale = Math.max(0.05, a.morale - STARVE_MORALE);
                casualties(List.of(a), Math.max(1, Math.round(a.men() * STARVE_LOSS)));
                if (war.day % 5 == 0) say(a.name + " голодает вдали от своих земель");
                a.lastEvent = "голодает";
            } else if (a.supply < 0.25 && war.day % 5 == 0) say(a.name + ": припасы на исходе");
        }
    }

    private void casualties(List<Army> side, double kill) {
        int total = men(side);
        if (total <= 0 || kill <= 0) return;
        double frac = Math.min(1, kill / total);
        for (Army a : side) for (String k : new ArrayList<>(a.units.keySet())) {
            int left = (int) Math.round(a.units.get(k) * (1 - frac));
            if (left <= 0) a.units.remove(k); else a.units.put(k, left);
        }
    }

    private void retreat(Army a) {
        endSiege(a);
        a.path.clear();
        a.lastEvent = "разбита, отступает";
        if (a.men() < 0.1 * Math.max(1, a.men() + 1) && a.men() < 5) { a.units.clear(); return; }
        // отступить в ближайшее своё баронство в соседях; иначе в любого соседа без врагов
        int best = 0;
        for (int n : nb(a.location)) {
            if (ownerOf(n).equals(a.realm)) { best = n; break; }
            boolean hostile = false;
            for (Army b : armies.values()) if (b.location == n && enemyWar(a.realm, b.realm) != null) hostile = true;
            if (!hostile && best == 0) best = n;
        }
        if (best != 0) a.location = best;
        else a.units.clear();
    }

    // ------------------------------------------------------------------ осады

    private void sieges() {
        for (Army a : new ArrayList<>(armies.values())) {
            if (a.moving() || a.men() <= 0) continue;
            String owner = ownerOf(a.location);
            War w = enemyWar(a.realm, owner);
            if (w == null) { endSiege(a); continue; }
            if (hostileArmyAt(a)) continue;                                  // сначала бой
            War.Side me = w.sides.get(a.realm);
            if (w.occupied.get(a.location) == me) continue;                  // уже занято мной
            double[] s = sieges.get(a.location);
            if (s == null || (int) s[0] != a.id) {
                TitleData.Title t = d().get(a.location);
                double fort = t != null && t.props.get("fort") instanceof Number n ? n.doubleValue() : 1;
                s = new double[]{a.id, 0, SIEGE_BASE_DAYS + 6 * (fort - 1)};
                sieges.put(a.location, s);
                a.siegeTitle = a.location;
                a.lastEvent = "осаждает " + title(a.location);
                say(realmName(a.realm) + " осаждает " + title(a.location));
            }
            double engines = a.count("siege");
            s[1] += Math.min(4, 1 + 0.002 * a.men() + 0.25 * engines) * (0.6 + 0.4 * a.morale);
            if (s[1] >= s[2]) {
                sieges.remove(a.location);
                a.siegeTitle = 0;
                try { takeBarony(w, me, a.location); a.lastEvent = "взяла " + title(a.location); } catch (TitleEditor.EditException ex) { a.lastEvent = ex.getMessage(); }
            }
        }
        // освобождение: армия хозяина стоит на занятой врагом земле без осаждающих
        for (Army a : armies.values()) {
            if (a.moving() || a.men() <= 0) continue;
            for (War w : war.wars.values()) {
                if (!w.active) continue;
                War.Side mine = w.sides.get(a.realm), occ = w.occupied.get(a.location);
                if (mine != null && occ != null && occ != mine && ownerOf(a.location).equals(a.realm) && !hostileArmyAt(a)) { w.occupied.remove(a.location); war.liberate(w.id, a.location); say(title(a.location) + " освобождено"); }
            }
        }
    }

    private void takeBarony(War w, War.Side side, int barony) {
        war.occupy(w.id, side, barony);
        say(title(barony) + " взято");
        // все баронства титула заняты - занят и титул
        for (TitleData.Title t = d().get(barony); t != null && t.parent != 0; ) {
            TitleData.Title p = d().get(t.parent);
            if (p == null) break;
            if (!allOccupied(w, side, p)) break;
            try { war.occupy(w.id, side, p.id); } catch (TitleEditor.EditException ex) { break; }
            t = p;
        }
    }

    private boolean allOccupied(War w, War.Side side, TitleData.Title t) {
        for (int c : t.children) {
            TitleData.Title ch = d().get(c);
            if (ch == null) continue;
            if (ch.tier == 0 ? w.occupied.get(c) != side : !allOccupied(w, side, ch)) return false;
        }
        return t.children.length > 0;
    }

    // ------------------------------------------------------------------ ИИ

    private void aiTurn() {
        for (War w : new ArrayList<>(war.wars.values())) {
            if (!w.active) continue;
            for (String r : new ArrayList<>(w.sides.keySet())) {
                if (humans.contains(r) || d().realms.get(r) == null) continue;
                aiRealm(w, r);
                if (!w.active) break;
            }
        }
    }

    private void aiRealm(War w, String r) {
        War.Side me = w.sides.get(r);
        // 1. набор
        if (armiesOf(r).stream().mapToInt(Army::men).sum() < 0.3 * levyCap(r)) {
            double p = pool(r);
            int n = (int) Math.min(p, levyCap(r) * 0.6);
            if (n >= 20) {
                try {
                    raise(r, "levy", (int) (n * 0.5));
                    if (n * 0.25 >= 5) raise(r, "spear", (int) (n * 0.25));
                    if (gold(r) > 40 && n * 0.15 >= 3) raise(r, "archer", (int) (n * 0.15));
                    if (gold(r) > 120 && n * 0.1 >= 2) raise(r, "cavalry", (int) (n * 0.1));
                    if (gold(r) > 120) hire(r, "siege", 2);
                } catch (TitleEditor.EditException ignored) { }
            }
        }
        // 2. слить армии в одной точке
        List<Army> mine = armiesOf(r);
        for (Army a : mine) for (Army b : mine) if (a.id < b.id && armies.containsKey(a.id) && armies.containsKey(b.id) && a.location == b.location && !a.moving() && !b.moving() && a.mercenary == b.mercenary) merge(a.id, b.id, r);
        // 3. приказы простаивающим
        for (Army a : armiesOf(r)) {
            if (a.moving() || a.siegeTitle != 0 || a.men() < 10) continue;
            Army foe = nearestEnemyArmy(a);
            if (foe != null && foe.power(types) < 0.85 * a.power(types) * 1.2) { tryMove(a, foe.location); continue; }
            int tgt = nearestEnemyBarony(w, a, me);
            if (tgt != 0) tryMove(a, tgt);
            else if (a.location != capital(r)) tryMove(a, capital(r));
        }
        // 4. мир
        boolean leader = r.equals(w.attacker) || r.equals(w.defender);
        if (!leader) return;
        double mineScore = w.scoreFor(me);
        try {
            if (w.offerFrom != null && w.offerFrom != me) {
                if (mineScore < 10 || (w.offerWhite && war.day - w.startDay > 360)) war.acceptPeace(w.id, r);
            } else if (mineScore >= war.goalCost(w) && !w.goals.isEmpty()) war.enforce(w.id, r);
            else if (mineScore <= -45 && w.offerFrom == null) war.offerPeace(w.id, r, true);
        } catch (TitleEditor.EditException ignored) { }
    }

    private void tryMove(Army a, int target) { try { move(a.id, target, a.realm); } catch (TitleEditor.EditException ignored) { } }

    private Army nearestEnemyArmy(Army a) {
        Army best = null; int bd = 1 << 30;
        for (Army b : armies.values()) {
            if (enemyWar(a.realm, b.realm) == null) continue;
            int dist = route(a.location, b.location).size();
            if (dist > 0 && dist < bd && dist <= 8) { bd = dist; best = b; }
        }
        return best;
    }

    /** Ближайшее ещё не занятое баронство врага, цели войны предпочтительнее. */
    private int nearestEnemyBarony(War w, Army a, War.Side me) {
        Set<Integer> goalBaronies = new HashSet<>();
        for (int g : w.goals) collect(d().get(g), goalBaronies);
        Map<Integer, Integer> dist = new HashMap<>();
        Deque<Integer> q = new ArrayDeque<>();
        dist.put(a.location, 0);
        q.add(a.location);
        int best = 0, bestScore = Integer.MAX_VALUE;
        while (!q.isEmpty()) {
            int c = q.poll();
            int dd = dist.get(c);
            if (dd > 25) break;
            String o = ownerOf(c);
            War ow = enemyWar(a.realm, o);
            if (ow == w && w.occupied.get(c) != me) {
                int sc = dd * 2 - (goalBaronies.contains(c) ? 12 : 0);
                if (sc < bestScore) { bestScore = sc; best = c; }
            }
            for (int n : nb(c)) if (!dist.containsKey(n)) { dist.put(n, dd + 1); q.add(n); }
        }
        return best;
    }

    private void collect(TitleData.Title t, Set<Integer> out) {
        if (t == null) return;
        if (t.tier == 0) { out.add(t.id); return; }
        for (int c : t.children) collect(d().get(c), out);
    }

    // ------------------------------------------------------------------ сохранение

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-army/2");
        root.put("next", nextArmy);
        root.put("dayAccumulator", dayAccumulator);
        root.put("pool", new LinkedHashMap<String, Object>(pool));
        root.put("humans", new ArrayList<Object>(humans)); root.put("pacts", new ArrayList<Object>(pacts)); root.put("eco", eco.save());
        List<Object> al = new ArrayList<>();
        for (Army a : armies.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id); m.put("realm", a.realm); m.put("name", a.name); m.put("commander", a.commander); m.put("martial", a.martial);
            m.put("loc", a.location); m.put("morale", a.morale); m.put("supply", a.supply); m.put("merc", a.mercenary); m.put("siege", a.siegeTitle);
            m.put("units", new LinkedHashMap<String, Object>(a.units));
            m.put("path", new ArrayList<Object>(a.path));
            al.add(m);
        }
        root.put("armies", al);
        Map<String, Object> sg = new LinkedHashMap<>();
        for (Map.Entry<Integer, double[]> e : sieges.entrySet()) sg.put(String.valueOf(e.getKey()), List.of(e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        root.put("sieges", sg);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        String format = String.valueOf(root.get("format"));
        if (!"wf-army/1".equals(format) && !"wf-army/2".equals(format)) throw new IllegalArgumentException("Неверный формат армий");
        armies.clear(); sieges.clear(); pacts.clear(); gold.clear(); pool.clear(); humans.clear();
        nextArmy = ((Number) root.get("next")).intValue();
        Object savedAccumulator = root.get("dayAccumulator");
        dayAccumulator = savedAccumulator instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0 && n.doubleValue() < 1 ? n.doubleValue() : 0;
        if ("wf-army/1".equals(format) && root.get("gold") instanceof Map<?, ?> legacyGold)
            for (Map.Entry<?, ?> e : legacyGold.entrySet())
                if (e.getKey() instanceof String realm && e.getValue() instanceof Number amount && Double.isFinite(amount.doubleValue()))
                    gold.put(realm, amount.doubleValue());
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("pool")).entrySet()) pool.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        for (Object o : (List<Object>) root.get("humans")) humans.add((String) o);
        eco.load(root.get("eco"));
        pacts.clear();
        if (root.get("pacts") instanceof List<?> pl) for (Object o : pl) pacts.add(String.valueOf(o));
        for (Object o : (List<Object>) root.get("armies")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Army a = new Army(((Number) m.get("id")).intValue(), (String) m.get("realm"), (String) m.get("name"), ((Number) m.get("loc")).intValue());
            a.commander = (String) m.get("commander"); a.martial = ((Number) m.get("martial")).intValue();
            a.morale = ((Number) m.get("morale")).doubleValue(); if (m.get("supply") instanceof Number sp) a.supply = sp.doubleValue(); a.mercenary = Boolean.TRUE.equals(m.get("merc")); a.siegeTitle = ((Number) m.get("siege")).intValue();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("units")).entrySet()) a.units.put(e.getKey(), ((Number) e.getValue()).intValue());
            for (Object p : (List<Object>) m.get("path")) a.path.add(((Number) p).intValue());
            armies.put(a.id, a);
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("sieges")).entrySet()) {
            List<Object> v = (List<Object>) e.getValue();
            sieges.put(Integer.parseInt(e.getKey()), new double[]{((Number) v.get(0)).doubleValue(), ((Number) v.get(1)).doubleValue(), ((Number) v.get(2)).doubleValue()});
        }
        invalidate();
    }
}
