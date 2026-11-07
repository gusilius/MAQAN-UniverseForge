package dev.worldforge.forms;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Формы и проклятья из JSON (секция "forms"). Форма - состояние персонажа со ступенями (заражён -> проклят -> полностью преображён), триггерами превращения,
 * облик (поправки в превращённом виде), слабостями от окружения (солнце, серебро, святая земля), шкалой потребности (кровь, плоть), самообладанием и способами излечения.
 */
public final class FormDefs {
    public record Stage(String id, String name, double at, String desc, Map<String, Double> mods) {}
    /** on: night, day, full_moon, rage, hunger, water, random, always. chance - вероятность за срабатывание, duration - дней в облике (0 = до конца условия). */
    public record Trigger(String on, int minStage, double chance, int duration) {}
    public record Source(String type, String key, double chance) {}
    public record Weakness(String when, Map<String, Double> mods, double damage) {}
    public record Need(String id, String name, double max, double drainPerDay, double belowPct, Map<String, Double> starve, double feedGain) {}
    public record Cure(String id, String name, String item, int count, int gold, int maxStage, String flag) {}
    public record Control(double gainPerResist, double max, double voluntaryAt, int cooldownDays) {}
    public record Form(String id, String name, String kind, String desc, double rate, double levelScale, int flagStage, boolean secret, int opinion,
                       List<Stage> stages, List<Trigger> triggers, List<Source> sources, Map<String, Double> shiftMods, String shiftName, String entity,
                       List<Weakness> weaknesses, Need need, List<Cure> cures, Control control, List<String> onlyRaces, List<String> immuneRaces, String exclusive, int incurableFrom) {}

    public final Map<String, Form> forms = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static Map<String, Double> mods(Object o) { Map<String, Double> m = new LinkedHashMap<>(); if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), dbl(e.getValue(), 0)); return m; }
    private static List<String> strs(Object o) { List<String> l = new ArrayList<>(); if (o instanceof List<?> ll) for (Object x : ll) l.add(String.valueOf(x)); return l; }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        if (((Map<String, Object>) root).get("forms") instanceof List<?> l) for (Object o : l) add((Map<String, Object>) o);
    }

    @SuppressWarnings("unchecked")
    public void add(Map<String, Object> f) {
        String id = str(f.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У формы нет id");
        List<Stage> st = new ArrayList<>();
        if (f.get("stages") instanceof List<?> l) for (Object o : l) { Map<String, Object> s = (Map<String, Object>) o; st.add(new Stage(str(s.get("id"), "s" + st.size()), str(s.get("name"), "Ступень"), dbl(s.get("at"), 0), str(s.get("desc"), ""), mods(s.get("mods")))); }
        if (st.isEmpty()) throw new IllegalArgumentException(id + ": нужна хотя бы одна ступень");
        List<Trigger> tr = new ArrayList<>();
        if (f.get("triggers") instanceof List<?> l) for (Object o : l) { Map<String, Object> t = (Map<String, Object>) o; tr.add(new Trigger(str(t.get("on"), "random"), num(t.get("min_stage"), 1), dbl(t.get("chance"), 1), num(t.get("duration"), 1))); }
        List<Source> so = new ArrayList<>();
        if (f.get("sources") instanceof List<?> l) for (Object o : l) { Map<String, Object> s = (Map<String, Object>) o; so.add(new Source(str(s.get("type"), "event"), str(s.get("key"), ""), dbl(s.get("chance"), 1))); }
        List<Weakness> wk = new ArrayList<>();
        if (f.get("weaknesses") instanceof List<?> l) for (Object o : l) { Map<String, Object> w = (Map<String, Object>) o; wk.add(new Weakness(str(w.get("when"), ""), mods(w.get("mods")), dbl(w.get("damage"), 0))); }
        Need need = null;
        if (f.get("need") instanceof Map<?, ?> nm) { Map<String, Object> n = (Map<String, Object>) nm; need = new Need(str(n.get("id"), "need"), str(n.get("name"), "Потребность"), dbl(n.get("max"), 100), dbl(n.get("drain"), 5), dbl(n.get("below_pct"), 25), mods(n.get("starve")), dbl(n.get("feed_gain"), 20)); }
        List<Cure> cu = new ArrayList<>();
        if (f.get("cures") instanceof List<?> l) for (Object o : l) { Map<String, Object> c = (Map<String, Object>) o; cu.add(new Cure(str(c.get("id"), "cure"), str(c.get("name"), "Лечение"), str(c.get("item"), ""), num(c.get("count"), 1), num(c.get("gold"), 0), num(c.get("max_stage"), 99), str(c.get("flag"), ""))); }
        Control ctl = null;
        if (f.get("control") instanceof Map<?, ?> cm) { Map<String, Object> c = (Map<String, Object>) cm; ctl = new Control(dbl(c.get("gain"), 2), dbl(c.get("max"), 100), dbl(c.get("voluntary_at"), 60), num(c.get("cooldown"), 3)); }
        Map<String, Object> shift = f.get("shift") instanceof Map<?, ?> sm ? (Map<String, Object>) sm : Map.of();
        forms.put(id, new Form(id, str(f.get("name"), id), str(f.get("kind"), "curse"), str(f.get("desc"), ""), dbl(f.get("rate"), 1), dbl(f.get("level_scale"), 0), num(f.get("flag_stage"), 1), Boolean.TRUE.equals(f.get("secret")),
                num(f.get("opinion"), 0), st, tr, so, mods(shift.get("mods")), str(shift.get("name"), "Иной облик"), str(shift.get("entity"), ""), wk, need, cu, ctl,
                strs(f.get("only_races")), strs(f.get("immune_races")), str(f.get("exclusive"), ""), num(f.get("incurable_from"), 99)));
    }

    public List<String> validate() {
        List<String> p = new ArrayList<>();
        for (Form f : forms.values()) {
            for (int i = 1; i < f.stages().size(); i++) if (f.stages().get(i).at() <= f.stages().get(i - 1).at()) p.add(f.id() + ": пороги ступеней должны расти");
            for (Trigger t : f.triggers()) {
                if (!TRIGGERS.contains(t.on())) p.add(f.id() + ": неизвестный триггер '" + t.on() + "'");
                if (t.minStage() >= f.stages().size()) p.add(f.id() + ": триггер " + t.on() + " требует несуществующую ступень");
            }
            for (Weakness w : f.weaknesses()) if (!ENV.contains(w.when())) p.add(f.id() + ": неизвестное условие слабости '" + w.when() + "'");
            for (Source s : f.sources()) if (!SOURCES.contains(s.type())) p.add(f.id() + ": неизвестный источник '" + s.type() + "'");
            if (f.control() != null && f.control().voluntaryAt() > f.control().max()) p.add(f.id() + ": порог добровольного превращения выше предела самообладания");
            if (f.rate() <= 0 && f.levelScale() <= 0) p.add(f.id() + ": форма не развивается (rate и level_scale равны нулю)");
        }
        return p;
    }

    public static final Set<String> TRIGGERS = Set.of("night", "day", "full_moon", "rage", "hunger", "water", "random", "always");
    public static final Set<String> ENV = Set.of("sun", "holy", "silver", "water", "dark");
    public static final Set<String> SOURCES = Set.of("mob", "event", "quest", "item", "birth", "place");
}
