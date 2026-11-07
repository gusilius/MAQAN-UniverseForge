package dev.worldforge.relations;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterStats;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Личные отношения игроков с жителями и придворными (идеи MCA, MineColonies, Millenaire - только механика, код свой).
 * Для пары «игрок - персонаж» хранится симпатия (-100..100), любовь (0..100), последние дни действий (перезарядка),
 * свидание/брак, найм. Исход разговора зависит от черт характера собеседника и случайности (генератор подаёт вызывающий,
 * так что тесты детерминированы). Просьбы жителей (MineColonies): принести предметы за награду. Всё без Minecraft:
 * предметы, золото, поручения идут через {@link Hooks}.
 */
public final class Relations {
    /** То, чего чистая логика не знает: инвентарь игрока, золото, поручения картой, сплетни мира. */
    public interface Hooks {
        int count(String player, String item);
        boolean take(String player, String item, int n);
        void give(String player, String item, int n);
        /** Государство, которым правит игрок, или "". */
        String realmOf(String player);
        /** Выполнить действие карты от имени игрока; вернуть сообщение или null при неудаче (причина в lastError). */
        String run(String player, String action, String realm, List<String> args);
        List<String> rumors();
        /** Ценность предмета в руке игрока и его снятие (0 - нет подарка). */
        int takeGift(String player);
        /** Соседние государства (id) для поручений канцлеру. */
        List<String> neighbors(String realm);
    }

    public static class Hooks0 implements Hooks {
        public int count(String p, String i) { return 0; }
        public boolean take(String p, String i, int n) { return false; }
        public void give(String p, String i, int n) {}
        public String realmOf(String p) { return ""; }
        public String run(String p, String a, String r, List<String> args) { return null; }
        public List<String> rumors() { return List.of(); }
        public int takeGift(String p) { return 0; }
        public List<String> neighbors(String r) { return List.of(); }
    }

    public static final class Bond {
        public int affinity, love, met, talks;
        public boolean dating, married, employed;
        public int lastContact = Integer.MIN_VALUE / 2;
        public int wage;
        public final Map<String, Integer> last = new LinkedHashMap<>();
        public int giftsToday, giftDay = -1;

        public int hearts() { return Math.max(0, Math.min(5, affinity / 20)); }

        public String heartsText() { return "♥".repeat(hearts()) + "♡".repeat(5 - hearts()); }

        public String mood() {
            return affinity <= -60 ? "Ненавидит" : affinity <= -25 ? "Враждебен" : affinity < 10 ? "Равнодушен" : affinity < 30 ? "Знакомый"
                    : affinity < 55 ? "Приятель" : affinity < 80 ? "Друг" : "Близкий друг";
        }
    }

    public static final class Result {
        public final boolean ok;
        public final int delta;
        public final String line;
        Result(boolean ok, int delta, String line) { this.ok = ok; this.delta = delta; this.line = line; }
    }

    public static final class Request {
        public int id;
        public String npc, item, text;
        public int count, reward, aff, expires;
        public boolean done;
    }

    /** Вид общения: id, название, перезарядка в днях, минимальная симпатия. */
    public record Kind(String id, String name, int cooldown, int minAffinity) {}

    public static final Kind[] KINDS = {
            new Kind("chat", "Поболтать", 1, -100), new Kind("joke", "Пошутить", 1, -30), new Kind("compliment", "Сделать комплимент", 1, -30),
            new Kind("flirt", "Флиртовать", 2, 20), new Kind("insult", "Оскорбить", 0, -100), new Kind("hug", "Обнять", 1, 45),
            new Kind("kiss", "Поцеловать", 1, 60)};

    public static Kind kind(String id) { for (Kind k : KINDS) if (k.id.equals(id)) return k; return null; }

    public static final int MAX_GIFTS_PER_DAY = 3;
    public static final int REQUEST_DAYS = 12;
    public static final int DECAY_AFTER = 6;

    private final Map<String, Bond> bonds = new LinkedHashMap<>();
    private final Map<Integer, Request> requests = new LinkedHashMap<>();
    private int nextRequest = 1;
    public Hooks hooks = new Hooks0();
    public final List<String> history = new ArrayList<>();
    public String lastError = "";

    private static String key(String player, String npc) { return player + "|" + npc; }

    public Bond bond(String player, String npc) { return bonds.computeIfAbsent(key(player, npc), k -> new Bond()); }

    public Bond peek(String player, String npc) { return bonds.get(key(player, npc)); }

    public Collection<String> keys() { return bonds.keySet(); }

    // ---------- общение ----------

    private static boolean has(Character c, String t) { return CharacterStats.traits(c).contains(t); }

    public boolean ready(String player, Character npc, String kindId, int day) {
        Kind k = kind(kindId);
        if (k == null) return false;
        Bond b = bond(player, npc.id);
        if (b.affinity < k.minAffinity) return false;
        if (kindId.equals("flirt") && b.married) return false;
        if ((kindId.equals("hug") || kindId.equals("kiss")) && !(b.dating || b.married)) return false;
        Integer l = b.last.get(kindId);
        return l == null || day - l >= k.cooldown;
    }

    public Result interact(String player, Character npc, String kindId, int day, Random rng) {
        Kind k = kind(kindId);
        if (k == null) return new Result(false, 0, "...");
        Bond b = bond(player, npc.id);
        if (b.affinity <= -60 && !kindId.equals("insult")) return new Result(false, 0, npc.name + " отворачивается и не желает говорить.");
        if (!ready(player, npc, kindId, day)) {
            return new Result(false, 0, b.affinity < k.minAffinity ? "Вы слишком мало знакомы для этого." : "Не сегодня - вы уже говорили об этом.");
        }
        b.last.put(kindId, day);
        b.lastContact = day;
        b.met++;
        int d, lv = 0;
        String line;
        switch (kindId) {
            case "chat" -> {
                d = 2 + (has(npc, "gregarious") ? 2 : 0) - (has(npc, "shy") ? 1 : 0) - (has(npc, "wroth") && rng.nextInt(3) == 0 ? 3 : 0);
                b.talks++;
                line = d > 2 ? "Разговор получился тёплым." : d > 0 ? "Вы мило поболтали." : "Беседа не клеится.";
            }
            case "joke" -> {
                int p = 55 + (has(npc, "gregarious") ? 20 : 0) + (has(npc, "content") ? 10 : 0) - (has(npc, "just") ? 15 : 0) - (has(npc, "cruel") ? 20 : 0) - (has(npc, "scholar") ? 10 : 0) - (has(npc, "wroth") ? 10 : 0);
                boolean laughed = rng.nextInt(100) < p;
                d = laughed ? 4 : -3;
                line = laughed ? "Собеседник смеётся от души." : "Шутка не удалась. Повисла неловкая тишина.";
            }
            case "compliment" -> {
                d = 3 + (has(npc, "shy") ? 1 : 0) + (has(npc, "ambitious") ? 2 : 0) - (has(npc, "cruel") ? 2 : 0) - (has(npc, "just") && b.affinity < 20 ? 1 : 0);
                line = d >= 4 ? "Собеседник заметно польщён." : "Приятно слышать, спасибо.";
            }
            case "insult" -> {
                d = -8 - (has(npc, "wroth") ? 6 : 0) - (has(npc, "just") ? 3 : 0) + (has(npc, "content") ? 3 : 0);
                line = has(npc, "wroth") ? "Лицо собеседника багровеет от гнева." : "Собеседник холодно смотрит на вас.";
            }
            case "flirt" -> {
                int p = 25 + b.affinity / 2 + b.love / 3 + (has(npc, "gregarious") ? 15 : 0) - (has(npc, "shy") ? 10 : 0) - (has(npc, "content") ? 0 : 0) - (has(npc, "just") ? 5 : 0);
                if (!npc.spouse.isEmpty() && !npc.spouse.equals("")) p -= 40;
                boolean win = rng.nextInt(100) < p;
                d = win ? 3 : -5; lv = win ? 6 + (has(npc, "shy") ? 2 : 0) : -2;
                line = win ? "Собеседник краснеет и улыбается. Кажется, это взаимно." : "Ваши слова повисают в воздухе: флирт не удался.";
            }
            case "hug" -> { d = 2; lv = 3; line = "Объятие вышло тёплым."; }
            case "kiss" -> { d = 2; lv = 5; line = "Поцелуй долгий и нежный."; }
            default -> { d = 0; line = "..."; }
        }
        b.affinity = clamp(b.affinity + d, -100, 100);
        b.love = clamp(b.love + lv, 0, 100);
        return new Result(d >= 0, d, line);
    }

    /** Подарок ценности value (1 - безделушка, 10 - сокровище). За день полезны только MAX_GIFTS_PER_DAY подарка, дальше ценность падает. */
    public Result gift(String player, Character npc, int value, int day) {
        Bond b = bond(player, npc.id);
        if (value <= 0) return new Result(false, 0, "Вам нечего подарить: возьмите подарок в руку.");
        if (b.giftDay != day) { b.giftDay = day; b.giftsToday = 0; }
        b.giftsToday++;
        double mul = b.giftsToday > MAX_GIFTS_PER_DAY ? 0.2 : 1.0;
        double trait = 1 + (has(npc, "greedy") ? 0.5 : 0) + (has(npc, "generous") ? -0.2 : 0) + (has(npc, "scholar") ? 0.0 : 0) + (has(npc, "just") && b.affinity < 20 ? -0.3 : 0);
        int d = Math.max(1, (int) Math.round(Math.min(value, 12) * 1.5 * trait * mul));
        b.affinity = clamp(b.affinity + d, -100, 100);
        b.lastContact = day;
        b.met++;
        String line = b.giftsToday > MAX_GIFTS_PER_DAY ? "Спасибо... но вы уже столько подарили сегодня." : value >= 8 ? "Какой роскошный дар! Я тронут." : value >= 4 ? "Благодарю, это очень кстати." : "Спасибо за внимание.";
        return new Result(true, d, line);
    }

    // ---------- свидания, брак, найм ----------

    public String canDate(String player, Character npc) {
        Bond b = bond(player, npc.id);
        if (b.dating || b.married) return "Вы уже вместе.";
        if (!npc.spouse.isEmpty()) return npc.name + " уже в браке.";
        if (b.affinity < 40) return "Нужно больше доверия (симпатия 40+).";
        if (b.love < 35) return "Чувства ещё слабы (любовь 35+). Пофлиртуйте.";
        for (String k : bonds.keySet()) if (k.startsWith(player + "|") && !k.equals(key(player, npc.id)) && (bonds.get(k).dating || bonds.get(k).married)) return "Вы уже связаны с другим.";
        return null;
    }

    public boolean startDating(String player, Character npc) {
        if (canDate(player, npc) != null) return false;
        bond(player, npc.id).dating = true;
        history.add(player + " и " + npc.name + " начали встречаться");
        return true;
    }

    public String canMarry(String player, Character npc) {
        Bond b = bond(player, npc.id);
        if (b.married) return "Вы уже женаты.";
        if (!b.dating) return "Сначала нужно начать встречаться.";
        if (b.affinity < 60 || b.love < 60) return "Чувства ещё не созрели (симпатия и любовь 60+).";
        if (!npc.spouse.isEmpty()) return npc.name + " уже в браке.";
        return null;
    }

    public boolean marry(String player, Character npc, int day) {
        if (canMarry(player, npc) != null) return false;
        Bond b = bond(player, npc.id);
        b.married = true; b.dating = false; b.employed = false; b.lastContact = day;
        b.affinity = Math.max(b.affinity, 70);
        npc.props.put("people.spouse_player", player);
        history.add(player + " женился(ась) на " + npc.name);
        return true;
    }

    public void divorce(String player, Character npc) {
        Bond b = bond(player, npc.id);
        if (!b.married && !b.dating) return;
        b.married = b.dating = false;
        b.love = 0; b.affinity = Math.min(b.affinity, -20);
        npc.props.remove("people.spouse_player");
        history.add(player + " расстался(ась) с " + npc.name);
    }

    public String spouseOf(String player) {
        for (Map.Entry<String, Bond> e : bonds.entrySet()) if (e.getKey().startsWith(player + "|") && e.getValue().married) return e.getKey().substring(player.length() + 1);
        return "";
    }

    public String partnerOf(String player) {
        for (Map.Entry<String, Bond> e : bonds.entrySet()) if (e.getKey().startsWith(player + "|") && (e.getValue().married || e.getValue().dating)) return e.getKey().substring(player.length() + 1);
        return "";
    }

    public int wageFor(Character npc) { return 5 + (npc.posts.isEmpty() ? 0 : 5) + (has(npc, "greedy") ? 3 : 0) - (has(npc, "diligent") ? 0 : 0); }

    public String canHire(String player, Character npc) {
        Bond b = bond(player, npc.id);
        if (b.employed) return "Уже у вас на службе.";
        if (b.affinity < 25 && !b.married) return "Не доверяет вам настолько (симпатия 25+).";
        if (npc.props.get("employer") != null && !String.valueOf(npc.props.get("employer")).equals(player)) return "Служит другому.";
        if (has(npc, "lazy") && b.affinity < 40) return "Ленивый и без особой охоты (симпатия 40+).";
        return null;
    }

    public boolean hire(String player, Character npc, int day) {
        if (canHire(player, npc) != null) return false;
        Bond b = bond(player, npc.id);
        b.employed = true; b.wage = wageFor(npc); b.lastContact = day;
        npc.props.put("employer", player);
        return true;
    }

    public void dismiss(String player, Character npc) {
        Bond b = bond(player, npc.id);
        if (!b.employed) return;
        b.employed = false;
        npc.props.remove("employer");
    }

    // ---------- просьбы жителей ----------

    private static final String[][] FETCH = {{"wheat", "пшеницы", "6", "10"}, {"bread", "хлеба", "3", "8"}, {"coal", "угля", "8", "12"}, {"oak_log", "брёвен", "10", "16"},
            {"cobblestone", "камня", "16", "24"}, {"iron_ingot", "железных слитков", "3", "6"}, {"wool", "шерсти", "4", "8"}, {"apple", "яблок", "4", "8"}};

    public Request openRequest(String npcId) { for (Request r : requests.values()) if (r.npc.equals(npcId) && !r.done) return r; return null; }

    public Request request(int id) { return requests.get(id); }

    /** Выдаёт просьбу, если открытой нет. Предмет зависит от роли и черт. */
    public Request offerRequest(Character npc, int day, Random rng) {
        Request cur = openRequest(npc.id);
        if (cur != null && cur.expires > day) return cur;
        if (cur != null) cur.done = true;
        String[] t;
        if (npc.hasRole("marshal")) t = new String[]{"iron_ingot", "железных слитков", "4", "8"};
        else if (npc.hasRole("steward")) t = new String[]{"gold_ingot", "золотых слитков", "2", "4"};
        else if (has(npc, "scholar")) t = new String[]{"book", "книг", "2", "4"};
        else if (has(npc, "greedy")) t = new String[]{"gold_ingot", "золотых слитков", "1", "3"};
        else t = FETCH[rng.nextInt(FETCH.length)];
        int lo = Integer.parseInt(t[2]), hi = Integer.parseInt(t[3]);
        Request r = new Request();
        r.id = nextRequest++;
        r.npc = npc.id; r.item = t[0]; r.count = lo + rng.nextInt(hi - lo + 1);
        r.reward = r.count * (has(npc, "generous") ? 3 : 2) + (has(npc, "greedy") ? 0 : 2);
        r.aff = 6 + (has(npc, "generous") ? 2 : 0);
        r.expires = day + REQUEST_DAYS;
        r.text = "Не принесёте ли " + r.count + " " + t[1] + "? Заплачу " + r.reward + " монет и не забуду вашей помощи.";
        requests.put(r.id, r);
        return r;
    }

    public boolean requestReady(String player, String npcId, int day) {
        Request r = openRequest(npcId);
        return r != null && r.expires > day && hooks.count(player, r.item) >= r.count;
    }

    public Result completeRequest(String player, Character npc, int day) {
        Request r = openRequest(npc.id);
        if (r == null) return new Result(false, 0, "У меня к вам нет просьб.");
        if (r.expires <= day) { r.done = true; return new Result(false, 0, "Увы, срок вышел - я уже справился сам."); }
        if (hooks.count(player, r.item) < r.count || !hooks.take(player, r.item, r.count)) return new Result(false, 0, "У вас нет нужного: " + r.count + " " + r.item + ".");
        r.done = true;
        hooks.give(player, "gold", r.reward);
        Bond b = bond(player, npc.id);
        b.affinity = clamp(b.affinity + r.aff, -100, 100);
        b.lastContact = day; b.met++;
        return new Result(true, r.aff, "Благодарю! Вот " + r.reward + " монет. Вы настоящий друг.");
    }

    // ---------- слухи ----------

    public String rumor(Character npc, Random rng) {
        List<String> r = new ArrayList<>(hooks.rumors());
        if (has(npc, "shy") && rng.nextInt(2) == 0) return "Я мало что знаю... Не люблю сплетен.";
        if (r.isEmpty()) return has(npc, "greedy") ? "Говорят, цены на рынке опять растут." : "Тихо в наших краях. Новостей нет.";
        return r.get(rng.nextInt(r.size()));
    }

    // ---------- время ----------

    /** Раз в игровой день: симпатия без общения сползает к нулю; браки и любовь не остывают быстро. */
    public void tick(int day) {
        for (Bond b : bonds.values()) {
            if (b.married) { b.love = Math.min(100, b.love); continue; }
            if (day - b.lastContact >= DECAY_AFTER && b.affinity != 0 && day % 3 == 0) b.affinity -= Integer.signum(b.affinity);
            if (day - b.lastContact >= DECAY_AFTER * 2 && b.love > 0 && !b.dating && day % 3 == 0) b.love--;
        }
        requests.values().removeIf(r -> r.done || r.expires < day - 30);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }


    // ---------- данные для окна «Отношения» ----------

    /** Все знакомые игрока: для списка в окне. label: подпись роли персонажа, года считает окно. */
    public String viewJson(String player, java.util.function.Function<String, Character> npcs, java.util.function.Function<Character, String> label, int day) {
        List<Object> rows = new ArrayList<>();
        for (Map.Entry<String, Bond> e : bonds.entrySet()) {
            if (!e.getKey().startsWith(player + "|")) continue;
            Character c = npcs.apply(e.getKey().substring(player.length() + 1));
            Bond b = e.getValue();
            if (c == null || !c.alive || (b.met == 0 && !b.married && !b.dating && !b.employed)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("name", c.name); m.put("house", c.house); m.put("g", c.gender); m.put("born", c.birthYear);
            m.put("role", label.apply(c)); m.put("aff", b.affinity); m.put("love", b.love); m.put("hearts", b.heartsText()); m.put("mood", b.mood());
            m.put("dating", b.dating); m.put("married", b.married); m.put("employed", b.employed); m.put("wage", b.wage); m.put("met", b.met);
            m.put("traits", new ArrayList<Object>(CharacterStats.traits(c)));
            Request r = openRequest(c.id);
            if (r != null && r.expires > day) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("text", r.text); q.put("item", r.item); q.put("n", r.count); q.put("reward", r.reward); q.put("left", r.expires - day);
                m.put("req", q);
            }
            rows.add(m);
        }
        rows.sort((x, y) -> Integer.compare(num(((Map<?, ?>) y).get("aff")), num(((Map<?, ?>) x).get("aff"))));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rows", rows); root.put("day", day); root.put("partner", partnerOf(player));
        return Json.write(root);
    }

    // ---------- сохранение ----------

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> bs = new LinkedHashMap<>();
        for (Map.Entry<String, Bond> e : bonds.entrySet()) {
            Bond b = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", b.affinity); m.put("l", b.love); m.put("m", b.met); m.put("t", b.talks); m.put("c", b.lastContact);
            m.put("d", b.dating); m.put("w", b.married); m.put("e", b.employed); m.put("g", b.wage);
            m.put("last", new LinkedHashMap<String, Object>(b.last));
            bs.put(e.getKey(), m);
        }
        root.put("bonds", bs);
        List<Object> rs = new ArrayList<>();
        for (Request r : requests.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id); m.put("npc", r.npc); m.put("item", r.item); m.put("n", r.count); m.put("reward", r.reward); m.put("aff", r.aff);
            m.put("exp", r.expires); m.put("done", r.done); m.put("text", r.text);
            rs.add(m);
        }
        root.put("requests", rs); root.put("next", nextRequest);
        return Json.write(root);
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    public void fromJson(String json) {
        bonds.clear(); requests.clear(); nextRequest = 1;
        if (!(Json.parse(json) instanceof Map<?, ?> root)) return;
        if (root.get("bonds") instanceof Map<?, ?> bs) for (Map.Entry<?, ?> e : bs.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            Bond b = new Bond();
            b.affinity = num(m.get("a")); b.love = num(m.get("l")); b.met = num(m.get("m")); b.talks = num(m.get("t"));
            if (m.get("c") != null) b.lastContact = num(m.get("c"));
            b.dating = Boolean.TRUE.equals(m.get("d")); b.married = Boolean.TRUE.equals(m.get("w")); b.employed = Boolean.TRUE.equals(m.get("e")); b.wage = num(m.get("g"));
            if (m.get("last") instanceof Map<?, ?> l) for (Map.Entry<?, ?> x : l.entrySet()) b.last.put(String.valueOf(x.getKey()), num(x.getValue()));
            bonds.put(String.valueOf(e.getKey()), b);
        }
        if (root.get("requests") instanceof List<?> rs) for (Object o : rs) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Request r = new Request();
            r.id = num(m.get("id")); r.npc = String.valueOf(m.get("npc")); r.item = String.valueOf(m.get("item")); r.count = num(m.get("n"));
            r.reward = num(m.get("reward")); r.aff = num(m.get("aff")); r.expires = num(m.get("exp")); r.done = Boolean.TRUE.equals(m.get("done"));
            r.text = String.valueOf(m.get("text"));
            requests.put(r.id, r);
        }
        nextRequest = Math.max(1, num(root.get("next")));
    }
}
