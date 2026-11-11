package dev.worldforge.war;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.api.Diplomacy;

import java.util.*;

/**
 * Войны, союзы и перемирия поверх политической карты. Чистая логика: тело армий и сражения в мире потом сообщают сюда
 * только исходы ({@link #battle}, {@link #occupy}), а итоги мира применяются к карте через {@link TitleEditor}.
 * На карту выводятся свойства государств: {@code enemies} и {@code allies} (слой «Дипломатия») и {@code war.state} (слой «Война»).
 */
public final class WarEngine {
    /** Кто имеет право действовать от имени государства. capability: "war", "peace", "alliance". */
    @FunctionalInterface
    public interface Authority { boolean may(String actor, boolean operator, String realmId, String capability); }

    public static final double OCCUPY_PER_DAY = 0.05, STALEMATE_DAYS = 3650, WHITE_MAX = 20;

    public final TitleEditor editor;
    public final Map<String, CasusBelli> cbs = new LinkedHashMap<>();
    public final Map<Integer, War> wars = new LinkedHashMap<>();
    public final Map<String, Long> truces = new LinkedHashMap<>();
    private final Map<String, Set<String>> managedEnemies = new HashMap<>();
    /** Durable idempotency receipts for declarations, peace, alliances and political outcomes. */
    public static final int MAX_ACTION_OPERATIONS = 50_000;
    private final LinkedHashMap<String, String> actionOperations = new LinkedHashMap<>();
    public long day;
    private int nextWar = 1;
    public Authority authority;
    /** Если задан (аддон армий), он двигает время вместо {@link #tick}: армии ходят до того, как война считает день. */
    public java.util.function.DoubleConsumer driver;

    public WarEngine(TitleEditor editor) {
        this.editor = editor;
        this.authority = (actor, op, realmId, cap) -> {
            if (op) return true;
            TitleData.Realm r = editor.data().realms.get(realmId);
            return r != null && r.ruler != null && r.ruler.equalsIgnoreCase(actor);
        };
    }

    public void loadCasusBelli(String json) { cbs.putAll(CasusBelli.parse(json)); }

    private TitleData d() { return editor.data(); }

    private TitleData.Realm realm(String id) {
        TitleData.Realm r = d().realms.get(id);
        if (r == null) throw new TitleEditor.EditException("Нет государства " + id);
        return r;
    }

    private static String actionOperationId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            throw new TitleEditor.EditException("У политического приказа отсутствует корректный requestId");
        return "war-action:" + UUID.nameUUIDFromBytes(requestId.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String actionFingerprint(String... values) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Json.write(Arrays.asList(values)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String priorActionResult(String requestId, String fingerprint) {
        String operationId = actionOperationId(requestId);
        String prior = actionOperations.get(operationId);
        if (prior == null) return null;
        String prefix = "war-action/v1|" + fingerprint + "|";
        if (!prior.startsWith(prefix))
            throw new IllegalStateException("War action request ID was reused with a different payload: " + requestId);
        return prior.substring(prefix.length());
    }

    private void rememberActionResult(String requestId, String fingerprint, String result) {
        String operationId = actionOperationId(requestId);
        if (actionOperations.size() >= MAX_ACTION_OPERATIONS)
            throw new IllegalStateException("War action receipt limit reached; reconcile/compact before another political command");
        actionOperations.put(operationId, "war-action/v1|" + fingerprint + "|" + result);
    }

    private void requireActionReceiptCapacity() {
        if (actionOperations.size() >= MAX_ACTION_OPERATIONS)
            throw new IllegalStateException("War action receipt limit reached; reconcile/compact before another political command");
    }

    /** Stable AI request identity: replay within one world day reuses the same political receipt. */
    public static String autonomousActionRequestId(long worldDay, String realmId, String operation, int warId) {
        if (worldDay < 0 || realmId == null || realmId.isBlank() || operation == null || operation.isBlank()
                || warId <= 0) throw new IllegalArgumentException("Invalid autonomous war action identity");
        String canonical = worldDay + "|" + realmId + "|" + operation + "|" + warId;
        return "ai-war:" + UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }


    private static String key(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    // ------------------------------------------------------------------ запросы

    public boolean truceActive(String a, String b) { return truces.getOrDefault(key(a, b), 0L) > day; }

    public long truceLeft(String a, String b) { return Math.max(0, truces.getOrDefault(key(a, b), 0L) - day); }

    public Set<String> alliesOf(String id) {
        Set<String> out = new LinkedHashSet<>(Diplomacy.ids(realm(id), "allies"));
        for (TitleData.Realm r : d().realmList()) if (!r.id.equals(id) && Diplomacy.ids(r, "allies").contains(id)) out.add(r.id);
        out.remove(id);
        return out;
    }

    public List<War> warsOf(String realmId) {
        List<War> l = new ArrayList<>();
        for (War w : wars.values()) if (w.active && w.sides.containsKey(realmId)) l.add(w);
        return l;
    }

    public War warBetween(String a, String b) {
        for (War w : wars.values()) {
            if (!w.active) continue;
            War.Side sa = w.sides.get(a), sb = w.sides.get(b);
            if (sa != null && sb != null && sa != sb) return w;
        }
        return null;
    }

    /** Титул принадлежит государству, если оно владеет им самим или любым его вышестоящим титулом. */
    public TitleData.Realm ownerOf(TitleData.Title t) {
        for (TitleData.Title c = t; c != null; c = c.parent == 0 ? null : d().get(c.parent)) {
            for (TitleData.Realm r : d().realmList()) if (r.titleKeys.contains(c.key)) return r;
        }
        return null;
    }

    public double goalCost(War w) { return goalCost(w.cb, w.goals); }

    /** Очки войны, нужные для условий мира: по поводу и списку целей (используется и предпросмотром). */
    public double goalCost(String cbId, List<Integer> goals) {
        CasusBelli cb = cbs.get(cbId);
        if (cb == null) return 50;
        switch (cb.goal) {
            case CONQUER: { double c = 0; for (int g : goals) { TitleData.Title t = d().get(g); c += 20 + 20 * (t == null ? 0 : t.tier); } return c; }
            case SUBJUGATE: return 80;
            case INDEPENDENCE: return 60;
            default: return 30;
        }
    }

    // ------------------------------------------------------------------ объявление войны

    /** Результат проверки объявления войны. */
    private static final class Check {
        TitleData.Realm a, t;
        CasusBelli cb;
        boolean ally;
        final List<Integer> goals = new ArrayList<>();
    }

    /** Общая проверка для declare и preview: бросает EditException с причиной. */
    private Check validate(String attackerId, String defenderId, String cbId, List<Integer> goals) {
        Check c = new Check();
        TitleData.Realm a = c.a = realm(attackerId), t = c.t = realm(defenderId);
        CasusBelli cb = c.cb = cbs.get(cbId);
        if (cb == null) throw new TitleEditor.EditException("Нет повода для войны '" + cbId + "'. Есть: " + cbs.keySet());
        if (a == t) throw new TitleEditor.EditException("Нельзя воевать с самим собой");
        if (warBetween(attackerId, defenderId) != null) throw new TitleEditor.EditException("Эти государства уже воюют");
        if (truceActive(attackerId, defenderId)) throw new TitleEditor.EditException("Перемирие ещё " + truceLeft(attackerId, defenderId) + " дн.");
        boolean ally = c.ally = alliesOf(attackerId).contains(defenderId);
        if (ally && !cb.target.equals("ally_ok")) throw new TitleEditor.EditException(t.name + " - ваш союзник; разорвите союз сначала");
        if (t.overlordId != null && t.overlordId.equals(attackerId)) throw new TitleEditor.EditException(t.name + " ваш подданный; используйте «дать независимость» или смените отношение");
        switch (cb.target) {
            case "independent" -> { if (t.overlordId != null) throw new TitleEditor.EditException(t.name + " подчинено - повод требует независимую цель"); }
            case "overlord" -> { if (!defenderId.equals(a.overlordId)) throw new TitleEditor.EditException("Цель должна быть вашим сюзереном"); }
            default -> { }
        }
        if (cb.goal != CasusBelli.Goal.INDEPENDENCE && a.relation != null && Boolean.FALSE.equals(d().relationTypes.getOrDefault(a.relation, Map.of()).get("can_declare_war")))
            throw new TitleEditor.EditException(a.name + " как «" + d().relationLabel(a.relation) + "» не может объявлять войны");
        if (cb.goal == CasusBelli.Goal.CONQUER) {
            if (goals == null || goals.isEmpty()) throw new TitleEditor.EditException("Укажите цели войны: титулы цели");
            if (goals.size() > cb.maxGoals) throw new TitleEditor.EditException("Повод «" + cb.name + "» допускает целей не больше " + cb.maxGoals);
            for (int id : goals) {
                TitleData.Title ti = d().get(id);
                if (ti == null) throw new TitleEditor.EditException("Нет титула " + id);
                if (ownerOf(ti) != t) throw new TitleEditor.EditException(ti.name + " не принадлежит " + t.name);
                c.goals.add(id);
            }
        }
        return c;
    }

    /** Предпросмотр объявления войны: ничего не меняет. При ok=false в error причина. */
    public static final class Preview {
        public boolean ok;
        public String error = "";
        public double cost;                                   // очков войны для условий мира
        public int truceDays;                                 // перемирие после мира
        public CasusBelli cb;
        public final List<String> attackerSide = new ArrayList<>(), defenderSide = new ArrayList<>();
        public final Map<String, String> why = new LinkedHashMap<>();   // участник -> почему вступит («вассал Х», «союзник Х»)
        public boolean breaksAlliance;
    }

    public Preview preview(String attackerId, String defenderId, String cbId, List<Integer> goals) {
        Preview p = new Preview();
        Check c;
        try {
            c = validate(attackerId, defenderId, cbId, goals);
        } catch (TitleEditor.EditException ex) {
            p.error = ex.getMessage();
            // участников всё равно покажем, если оба государства существуют: игрок видит, что было бы
            if (d().realms.containsKey(attackerId) && d().realms.containsKey(defenderId) && !attackerId.equals(defenderId)) fillSides(p, attackerId, defenderId);
            p.cb = cbs.get(cbId);
            if (p.cb != null) { p.truceDays = p.cb.truceDays; p.cost = goalCost(cbId, goals == null ? List.of() : goals); }
            return p;
        }
        p.ok = true;
        p.cb = c.cb;
        p.cost = goalCost(cbId, c.goals);
        p.truceDays = c.cb.truceDays;
        p.breaksAlliance = c.ally;
        fillSides(p, attackerId, defenderId);
        return p;
    }

    private void fillSides(Preview p, String attackerId, String defenderId) {
        Map<String, War.Side> sides = new LinkedHashMap<>();
        sides.put(attackerId, War.Side.ATTACKER);
        sides.put(defenderId, War.Side.DEFENDER);
        gather(sides, p.why, attackerId, War.Side.ATTACKER);
        gather(sides, p.why, defenderId, War.Side.DEFENDER);
        for (Map.Entry<String, War.Side> e : sides.entrySet()) (e.getValue() == War.Side.ATTACKER ? p.attackerSide : p.defenderSide).add(e.getKey());
    }

    public War declare(String attackerId, String defenderId, String cbId, List<Integer> goals) {
        Check c = validate(attackerId, defenderId, cbId, goals);
        TitleData.Realm a = c.a, t = c.t;
        CasusBelli cb = c.cb;
        boolean ally = c.ally;
        List<Integer> g = c.goals;
        War w = new War(nextWar++, attackerId, defenderId, cbId, day);
        w.goals.addAll(g);
        wars.put(w.id, w);
        if (ally) breakAlliance(attackerId, defenderId);
        w.sides.put(attackerId, War.Side.ATTACKER);
        w.sides.put(defenderId, War.Side.DEFENDER);
        gather(w.sides, null, attackerId, War.Side.ATTACKER);
        gather(w.sides, null, defenderId, War.Side.DEFENDER);
        w.say(day, a.name + " объявляет войну " + t.name + " (" + cb.name + ")");
        for (String m : w.sides.keySet()) if (!m.equals(attackerId) && !m.equals(defenderId)) w.say(day, realm(m).name + " вступает на стороне " + (w.sides.get(m) == War.Side.ATTACKER ? "нападающих" : "обороняющихся"));
        syncProps();
        return w;
    }

    /** Replay-safe declaration; the original War object remains addressable after conclusion. */
    public synchronized War declareOnce(String requestId, String attackerId, String defenderId,
                                        String cbId, List<Integer> goals) {
        List<Integer> safeGoals = List.copyOf(goals == null ? List.of() : goals);
        String fingerprint = actionFingerprint("declare", attackerId, defenderId, cbId, Json.write(safeGoals));
        String prior = priorActionResult(requestId, fingerprint);
        if (prior != null) {
            if (!prior.startsWith("war:")) throw new IllegalStateException("Invalid prior war declaration receipt");
            int id;
            try { id = Integer.parseInt(prior.substring(4)); }
            catch (NumberFormatException error) { throw new IllegalStateException("Corrupt prior war declaration receipt", error); }
            War existing = wars.get(id);
            if (existing == null) throw new IllegalStateException("War declaration receipt references a missing campaign " + id);
            return existing;
        }
        requireActionReceiptCapacity();
        War created = declare(attackerId, defenderId, cbId, safeGoals);
        rememberActionResult(requestId, fingerprint, "war:" + created.id);
        return created;
    }

    private void gather(Map<String, War.Side> sides, Map<String, String> why, String leader, War.Side side) {
        Deque<String> q = new ArrayDeque<>();
        q.add(leader);
        while (!q.isEmpty()) {
            String cur = q.poll();
            TitleData.Realm r = realm(cur);
            Map<String, Boolean> next = new LinkedHashMap<>();            // true - подданный, false - союзник
            for (String n : r.subjectIds) next.put(n, true);
            for (String n : alliesOf(cur)) next.putIfAbsent(n, false);
            for (Map.Entry<String, Boolean> e : next.entrySet()) {
                String n = e.getKey();
                if (sides.containsKey(n) || !d().realms.containsKey(n)) continue;
                sides.put(n, side);
                if (why != null) why.put(n, (e.getValue() ? "подданный " : "союзник ") + r.name);
                q.add(n);
            }
        }
    }

    // ------------------------------------------------------------------ союзы

    /** Почему союз заключить нельзя (null - можно). */
    public String allyBlock(String a, String b) {
        if (!d().realms.containsKey(a) || !d().realms.containsKey(b)) return "Нет такого государства";
        if (a.equals(b)) return "Нельзя заключить союз с самим собой";
        if (warBetween(a, b) != null) return "Воюющие государства не заключают союз";
        if (alliesOf(a).contains(b)) return "Они уже союзники";
        return null;
    }

    public synchronized void allyOnce(String requestId, String a, String b) {
        String fingerprint = actionFingerprint("ally", a, b);
        if (priorActionResult(requestId, fingerprint) != null) return;
        requireActionReceiptCapacity();
        ally(a, b);
        rememberActionResult(requestId, fingerprint, "done");
    }

    public void ally(String a, String b) {
        TitleData.Realm ra = realm(a);
        realm(b);
        String block = allyBlock(a, b);
        if (block != null) throw new TitleEditor.EditException(block);
        Set<String> s = new LinkedHashSet<>(Diplomacy.ids(ra, "allies"));
        s.add(b);
        editor.setRealmProp(a, "allies", String.join(",", s));
        syncProps();
    }

    public synchronized void breakAllianceOnce(String requestId, String a, String b) {
        String fingerprint = actionFingerprint("breakAlliance", a, b);
        if (priorActionResult(requestId, fingerprint) != null) return;
        requireActionReceiptCapacity();
        breakAlliance(a, b);
        rememberActionResult(requestId, fingerprint, "done");
    }

    public void breakAlliance(String a, String b) {
        for (String[] p : new String[][]{{a, b}, {b, a}}) {
            TitleData.Realm r = realm(p[0]);
            Set<String> s = new LinkedHashSet<>(Diplomacy.ids(r, "allies"));
            if (s.remove(p[1])) editor.setRealmProp(p[0], "allies", s.isEmpty() ? null : String.join(",", s));
        }
    }

    // ------------------------------------------------------------------ ход войны

    private War active(int id) {
        War w = wars.get(id);
        if (w == null || !w.active) throw new TitleEditor.EditException("Нет активной войны " + id);
        return w;
    }

    private void addScore(War w, War.Side side, double delta, String why) {
        double before = w.score;
        w.score = Math.max(-100, Math.min(100, w.score + (side == War.Side.ATTACKER ? delta : -delta)));
        if (w.score != before) w.say(day, why + " (счёт " + Math.round(w.score) + ")");
    }

    /** Исход сражения. weight 1 - мелкая стычка, 5 - большая битва. */
    public void battle(int warId, War.Side winner, double weight, String text) {
        War w = active(warId);
        addScore(w, winner, 3 * weight, text == null ? "Победа " + (winner == War.Side.ATTACKER ? "нападающих" : "обороняющихся") : text);
    }

    /** Сторона заняла титул врага. Один и тот же титул даёт очки один раз. */
    public void occupy(int warId, War.Side side, int titleId) {
        War w = active(warId);
        TitleData.Title t = d().get(titleId);
        if (t == null) throw new TitleEditor.EditException("Нет титула " + titleId);
        TitleData.Realm owner = ownerOf(t);
        if (owner == null || w.sides.get(owner.id) != side.opposite()) throw new TitleEditor.EditException(t.name + " не принадлежит врагу");
        if (w.occupied.put(titleId, side) != null) return;
        double v = w.goals.contains(titleId) ? 12 + 6 * t.tier : 4 + 3 * t.tier;
        addScore(w, side, v, t.name + " занято");
    }

    /** Освобождение титула прежним владельцем. */
    public void liberate(int warId, int titleId) {
        War w = active(warId);
        War.Side s = w.occupied.remove(titleId);
        if (s != null) { TitleData.Title t = d().get(titleId); addScore(w, s.opposite(), 3, (t == null ? "Титул" : t.name) + " освобождено"); }
    }

    /** Ход времени через ведущий аддон (армии), если он есть, иначе сам по себе. */
    public void advance(double days) { if (driver != null) driver.accept(days); else tick(days); }

    /** Ход времени в днях: занятые земли копят очки, затянувшаяся война заканчивается ничьей, перемирия истекают. */
    public void tick(double days) {
        long target = day + (long) Math.floor(days);
        while (day < target) {
            day++;
            for (War w : new ArrayList<>(wars.values())) {
                if (!w.active) continue;
                for (Map.Entry<Integer, War.Side> e : w.occupied.entrySet()) {
                    w.score = Math.max(-100, Math.min(100, w.score + (e.getValue() == War.Side.ATTACKER ? OCCUPY_PER_DAY : -OCCUPY_PER_DAY)));
                }
                if (day - w.startDay >= STALEMATE_DAYS) finish(w, null, "Война выдохлась: белый мир");
            }
        }
        truces.values().removeIf(u -> u <= day);
    }

    // ------------------------------------------------------------------ мир

    /** Предложение мира от государства-лидера. Белый мир - без итогов; иначе победитель требует цели (нужно хватающее очко). */
    public synchronized void offerPeaceOnce(String requestId, int warId, String fromRealm, boolean white) {
        String fingerprint = actionFingerprint("offerPeace", warId, fromRealm, white);
        if (priorActionResult(requestId, fingerprint) != null) return;
        requireActionReceiptCapacity();
        offerPeace(warId, fromRealm, white);
        rememberActionResult(requestId, fingerprint, "done");
    }

    public void offerPeace(int warId, String fromRealm, boolean white) {
        War w = active(warId);
        War.Side s = leaderSide(w, fromRealm);
        if (!white && w.scoreFor(s) < goalCost(w)) throw new TitleEditor.EditException("Не хватает очков войны: " + Math.round(w.scoreFor(s)) + " из " + Math.round(goalCost(w)));
        w.offerFrom = s;
        w.offerWhite = white;
        w.say(day, realm(fromRealm).name + (white ? " предлагает белый мир" : " предъявляет условия мира"));
    }

    public synchronized void acceptPeaceOnce(String requestId, int warId, String byRealm) {
        String fingerprint = actionFingerprint("acceptPeace", warId, byRealm);
        if (priorActionResult(requestId, fingerprint) != null) return;
        requireActionReceiptCapacity();
        acceptPeace(warId, byRealm);
        rememberActionResult(requestId, fingerprint, "done");
    }

    public void acceptPeace(int warId, String byRealm) {
        War w = active(warId);
        War.Side s = leaderSide(w, byRealm);
        if (w.offerFrom == null || w.offerFrom == s) throw new TitleEditor.EditException("Нет предложения мира, которое вы можете принять");
        finish(w, w.offerWhite ? null : w.offerFrom, w.offerWhite ? "Заключён белый мир" : "Мир на условиях победителя");
    }

    /** Победитель с достаточным счётом навязывает мир сразу (противник держится, но ему некуда деваться). */
    public synchronized void enforceOnce(String requestId, int warId, String byRealm) {
        String fingerprint = actionFingerprint("enforce", warId, byRealm);
        if (priorActionResult(requestId, fingerprint) != null) return;
        requireActionReceiptCapacity();
        enforce(warId, byRealm);
        rememberActionResult(requestId, fingerprint, "done");
    }

    public void enforce(int warId, String byRealm) {
        War w = active(warId);
        War.Side s = leaderSide(w, byRealm);
        double need = goalCost(w);
        if (w.scoreFor(s) < need) throw new TitleEditor.EditException("Не хватает очков войны: " + Math.round(w.scoreFor(s)) + " из " + Math.round(need));
        finish(w, s, "Мир навязан победителем");
    }

    public synchronized void surrenderOnce(String requestId, int warId, String byRealm) {
        String fingerprint = actionFingerprint("surrender", warId, byRealm);
        if (priorActionResult(requestId, fingerprint) != null) return;
        requireActionReceiptCapacity();
        surrender(warId, byRealm);
        rememberActionResult(requestId, fingerprint, "done");
    }

    public void surrender(int warId, String byRealm) {
        War w = active(warId);
        War.Side s = leaderSide(w, byRealm);
        finish(w, s.opposite(), realm(byRealm).name + " капитулирует");
    }

    private War.Side leaderSide(War w, String realmId) {
        if (realmId.equals(w.attacker)) return War.Side.ATTACKER;
        if (realmId.equals(w.defender)) return War.Side.DEFENDER;
        throw new TitleEditor.EditException("Мир заключают только главы сторон: " + realm(w.attacker).name + " и " + realm(w.defender).name);
    }

    /** winner == null - белый мир. Условия применяются, если победил нападающий; обороняющийся победитель сохраняет всё как было. */
    private void finish(War w, War.Side winner, String text) {
        CasusBelli cb = cbs.get(w.cb);
        editor.group(() -> {
            if (winner == War.Side.ATTACKER && cb != null) {
                switch (cb.goal) {
                    case CONQUER -> { for (int g : w.goals) editor.give(w.attacker, g); }
                    case SUBJUGATE -> editor.setOverlord(w.defender, w.attacker, cb.relation);
                    case INDEPENDENCE -> editor.setOverlord(w.attacker, null, null);
                    default -> { }
                }
            }
            adjCache = null;
            w.active = false;
            w.endDay = day;
            w.winner = winner;
            w.result = text;
            w.say(day, text);
            int truce = cb == null ? 1825 : cb.truceDays;
            for (String x : w.members(War.Side.ATTACKER)) for (String y : w.members(War.Side.DEFENDER)) truces.put(key(x, y), day + truce);
            syncProps();
        });
    }

    // ------------------------------------------------------------------ вывод на карту

    /** Приводит свойства государств в соответствие с войнами: enemies (только записанные движком) и war.state. */
    public void syncProps() {
        Map<String, Set<String>> want = new HashMap<>();
        Set<String> atWar = new HashSet<>();
        for (War w : wars.values()) {
            if (!w.active) continue;
            for (String x : w.sides.keySet()) {
                atWar.add(x);
                for (String y : w.sides.keySet()) if (w.sides.get(x) != w.sides.get(y)) want.computeIfAbsent(x, k -> new LinkedHashSet<>()).add(y);
            }
        }
        editor.group(() -> {
            for (TitleData.Realm r : d().realmList()) {
                Set<String> cur = new LinkedHashSet<>(Diplomacy.ids(r, "enemies"));
                Set<String> mine = managedEnemies.getOrDefault(r.id, new HashSet<>());
                cur.removeAll(mine);
                Set<String> add = want.getOrDefault(r.id, Set.of());
                cur.addAll(add);
                managedEnemies.put(r.id, new LinkedHashSet<>(add));
                String nv = cur.isEmpty() ? null : String.join(",", cur), ov = r.props.get("enemies") == null ? null : r.props.get("enemies").toString();
                if (!Objects.equals(nv, ov)) editor.setRealmProp(r.id, "enemies", nv);
                String st = atWar.contains(r.id) ? "война" : null, os = r.props.get("war.state") == null ? null : r.props.get("war.state").toString();
                if (!Objects.equals(st, os)) editor.setRealmProp(r.id, "war.state", st);
            }
        });
    }

    // ------------------------------------------------------------------ дипломатия: статус, договоры, история

    public enum Status { SELF, ENEMY, TRUCE, ALLY, OVERLORD, SUBJECT, NEIGHBOR, NONE }

    /** Сводка отношений a к b: kind для цвета/сортировки, text для таблицы. */
    public static final class DipStatus {
        public final Status kind;
        public final String text;
        DipStatus(Status kind, String text) { this.kind = kind; this.text = text; }
        @Override public String toString() { return text; }
    }

    private Map<String, Set<String>> adjCache;

    /** Соседство государств (кэш; сбрасывается при смене карты после мира). */
    public Map<String, Set<String>> adjacency() {
        if (adjCache == null) adjCache = Diplomacy.adjacency(d());
        return adjCache;
    }

    public boolean neighbours(String a, String b) { return adjacency().getOrDefault(a, Set.of()).contains(b); }

    private boolean overlordOf(String top, String low) {
        int g = 0;
        for (TitleData.Realm c = d().realms.get(low); c != null && c.overlordId != null && g++ < 64; c = d().realms.get(c.overlordId)) if (c.overlordId.equals(top)) return true;
        return false;
    }

    /** a - подданный b (прямо или через цепочку). */
    public boolean isSubjectOf(String a, String b) { return overlordOf(b, a); }

    public DipStatus diplomacyStatus(String a, String b) {
        if (a.equals(b)) return new DipStatus(Status.SELF, "это вы");
        if (warBetween(a, b) != null) return new DipStatus(Status.ENEMY, "война");
        TitleData.Realm ra = d().realms.get(a), rb = d().realms.get(b);
        boolean enemyProp = ra != null && rb != null && (Diplomacy.ids(ra, "enemies").contains(b) || Diplomacy.ids(rb, "enemies").contains(a));
        if (enemyProp) return new DipStatus(Status.ENEMY, "враг");
        if (truceActive(a, b)) return new DipStatus(Status.TRUCE, "перемирие " + truceLeft(a, b) + " дн.");
        if (alliesOf(a).contains(b)) return new DipStatus(Status.ALLY, "союзник");
        if (overlordOf(b, a)) return new DipStatus(Status.OVERLORD, "сюзерен");
        if (overlordOf(a, b)) return new DipStatus(Status.SUBJECT, "подданный");
        if (neighbours(a, b)) return new DipStatus(Status.NEIGHBOR, "сосед");
        return new DipStatus(Status.NONE, "нет связи");
    }

    /** Один договор из панели дипломатии. action - id действия карты; warId - номер войны для мирных. */
    public static final class Treaty {
        public final String id, label, action;
        public final boolean available;
        public final String reason;
        public final int warId;
        Treaty(String id, String label, String action, boolean available, String reason, int warId) {
            this.id = id; this.label = label; this.action = action; this.available = available; this.reason = available ? "" : reason; this.warId = warId;
        }
    }

    public List<Treaty> treatyOptions(String a, String b) {
        List<Treaty> out = new ArrayList<>();
        boolean same = a.equals(b), known = d().realms.containsKey(a) && d().realms.containsKey(b);
        String block = same ? "Это ваше государство" : !known ? "Нет такого государства" : allyBlock(a, b);
        out.add(new Treaty("alliance", "Союз", "ally", block == null, block, 0));
        boolean allied = !same && known && alliesOf(a).contains(b);
        out.add(new Treaty("break_alliance", "Разорвать союз", "ally_break", allied, same ? "Это ваше государство" : "Союза нет", 0));
        War w = same || !known ? null : warBetween(a, b);
        String peaceWhy = same ? "Это ваше государство" : w == null ? "Войны с ними нет" : null;
        if (w != null && !a.equals(w.attacker) && !a.equals(w.defender)) peaceWhy = "Мир заключают только главы сторон: " + realm(w.attacker).name + " и " + realm(w.defender).name;
        if (w != null && peaceWhy == null && !b.equals(w.attacker) && !b.equals(w.defender)) peaceWhy = "Мир заключают только главы сторон: " + realm(w.attacker).name + " и " + realm(w.defender).name;
        out.add(new Treaty("white_peace", "Белый мир", "war_peace_white", peaceWhy == null, peaceWhy, w == null ? 0 : w.id));
        String warWhy = null;
        if (same) warWhy = "Это ваше государство";
        else if (!known) warWhy = "Нет такого государства";
        else {
            String firstErr = null;
            boolean any = false;
            for (CbOption o : cbOptions(a, b)) { if (o.available) any = true; else if (firstErr == null) firstErr = o.reason; }
            if (!any) warWhy = firstErr == null ? "Нет подходящего повода" : firstErr;
        }
        out.add(new Treaty("declare_war", "Объявить войну…", "war_declare", warWhy == null, warWhy, 0));
        return out;
    }

    /** Повод войны против цели с доступностью и причиной недоступности. */
    public static final class CbOption {
        public final CasusBelli cb;
        public final boolean available;
        public final String reason;
        CbOption(CasusBelli cb, boolean available, String reason) { this.cb = cb; this.available = available; this.reason = available ? "" : reason; }
    }

    public List<CbOption> cbOptions(String a, String b) {
        List<CbOption> out = new ArrayList<>();
        for (CasusBelli cb : cbs.values()) {
            List<Integer> goals = new ArrayList<>();
            if (cb.goal == CasusBelli.Goal.CONQUER) { List<TitleData.Title> g = goalCandidates(b); if (!g.isEmpty()) goals.add(g.get(0).id); }
            String err = null;
            try { validate(a, b, cb.id, goals); } catch (TitleEditor.EditException ex) { err = ex.getMessage(); }
            out.add(new CbOption(cb, err == null, err));
        }
        return out;
    }

    /** Титулы государства, которые можно назначить целью войны: корневые титулы и их потомки до баронств, по убыванию ранга. */
    public List<TitleData.Title> goalCandidates(String realmId) {
        TitleData.Realm r = d().realms.get(realmId);
        List<TitleData.Title> out = new ArrayList<>();
        if (r == null) return out;
        Deque<TitleData.Title> q = new ArrayDeque<>();
        for (String k : r.titleKeys) { TitleData.Title t = d().byKey(k); if (t != null) q.add(t); }
        Set<Integer> seen = new HashSet<>();
        while (!q.isEmpty() && out.size() < 200) {
            TitleData.Title t = q.poll();
            if (!seen.add(t.id)) continue;
            out.add(t);
            for (int c : t.children) { TitleData.Title ch = d().get(c); if (ch != null) q.add(ch); }
        }
        out.sort((x, y) -> y.tier != x.tier ? Integer.compare(y.tier, x.tier) : x.name.compareTo(y.name));
        return out;
    }

    /** Завершённые войны, свежие первыми. */
    public List<War> history() {
        List<War> l = new ArrayList<>();
        for (War w : wars.values()) if (!w.active) l.add(w);
        l.sort((x, y) -> Long.compare(y.endDay, x.endDay) != 0 ? Long.compare(y.endDay, x.endDay) : Integer.compare(y.id, x.id));
        return l;
    }

    // ------------------------------------------------------------------ сохранение

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-war/2");
        root.put("day", day); root.put("next_war", nextWar);
        root.put("truces", new LinkedHashMap<String, Object>(truces));
        Map<String, Object> mg = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : managedEnemies.entrySet()) mg.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        root.put("managed", mg);
        List<Object> operationReceipts = new ArrayList<>();
        for (Map.Entry<String, String> entry : actionOperations.entrySet())
            operationReceipts.add(Map.of("id", entry.getKey(), "payload", entry.getValue()));
        root.put("actionOperations", operationReceipts);
        List<Object> ws = new ArrayList<>();
        for (War w : wars.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.id); m.put("attacker", w.attacker); m.put("defender", w.defender); m.put("cb", w.cb);
            m.put("goals", new ArrayList<Object>(w.goals));
            Map<String, Object> sd = new LinkedHashMap<>();
            for (Map.Entry<String, War.Side> e : w.sides.entrySet()) sd.put(e.getKey(), e.getValue().name());
            m.put("sides", sd);
            Map<String, Object> oc = new LinkedHashMap<>();
            for (Map.Entry<Integer, War.Side> e : w.occupied.entrySet()) oc.put(String.valueOf(e.getKey()), e.getValue().name());
            m.put("occupied", oc);
            m.put("score", w.score); m.put("start", w.startDay); m.put("active", w.active);
            m.put("end", w.endDay); m.put("winner", w.winner == null ? "" : w.winner.name()); m.put("result", w.result);
            m.put("offer", w.offerFrom == null ? "" : w.offerFrom.name()); m.put("offer_white", w.offerWhite);
            m.put("log", new ArrayList<Object>(w.log));
            ws.add(m);
        }
        root.put("wars", ws);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        String format = String.valueOf(root.get("format"));
        boolean legacy = "wf-war/1".equals(format);
        if (!legacy && !"wf-war/2".equals(format)) throw new IllegalArgumentException("Неверный формат войн");
        day = ((Number) root.get("day")).longValue();
        nextWar = ((Number) root.get("next_war")).intValue();
        truces.clear(); wars.clear(); managedEnemies.clear(); actionOperations.clear();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("truces")).entrySet()) truces.put(e.getKey(), ((Number) e.getValue()).longValue());
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("managed")).entrySet()) {
            Set<String> s = new LinkedHashSet<>();
            for (Object o : (List<Object>) e.getValue()) s.add((String) o);
            managedEnemies.put(e.getKey(), s);
        }
        if (!legacy && root.get("actionOperations") instanceof List<?> receipts) {
            if (receipts.size() > MAX_ACTION_OPERATIONS)
                throw new IllegalArgumentException("Слишком много квитанций политических действий");
            for (Object raw : receipts) {
                if (!(raw instanceof Map<?, ?> receipt)
                        || !(receipt.get("id") instanceof String operationId)
                        || !(receipt.get("payload") instanceof String payload)
                        || !operationId.matches("[A-Za-z0-9_.:-]{1,128}")
                        || payload.isBlank() || payload.length() > 4096
                        || actionOperations.putIfAbsent(operationId, payload) != null)
                    throw new IllegalArgumentException("Повреждена квитанция политического действия");
            }
        }
        for (Object o : (List<Object>) root.get("wars")) {
            Map<String, Object> m = (Map<String, Object>) o;
            War w = new War(((Number) m.get("id")).intValue(), (String) m.get("attacker"), (String) m.get("defender"), (String) m.get("cb"), ((Number) m.get("start")).longValue());
            for (Object g : (List<Object>) m.get("goals")) w.goals.add(((Number) g).intValue());
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("sides")).entrySet()) w.sides.put(e.getKey(), War.Side.valueOf((String) e.getValue()));
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("occupied")).entrySet()) w.occupied.put(Integer.parseInt(e.getKey()), War.Side.valueOf((String) e.getValue()));
            w.score = ((Number) m.get("score")).doubleValue();
            if (m.get("end") instanceof Number en) w.endDay = en.longValue();
            if (m.get("winner") instanceof String ws && !ws.isEmpty()) w.winner = War.Side.valueOf(ws);
            if (m.get("result") instanceof String rs) w.result = rs;
            w.active = Boolean.TRUE.equals(m.get("active"));
            String of = (String) m.get("offer");
            w.offerFrom = of.isEmpty() ? null : War.Side.valueOf(of);
            w.offerWhite = Boolean.TRUE.equals(m.get("offer_white"));
            for (Object l : (List<Object>) m.get("log")) w.log.add((String) l);
            wars.put(w.id, w);
        }
    }
}
