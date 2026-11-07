package dev.worldforge.origins;

import dev.worldforge.mod.Json;

import java.util.*;

/** Расы, классы и профессии из JSON (секции "races", "classes", "professions"; несколько файлов объединяются). */
public final class OriginDefs {
    public record Race(String id, String name, String desc, int color, Map<String, Double> mods, Map<String, String> innate, List<String> traits, int weight, String parent,
                       TreeMap<Integer, Map<String, Double>> perks, Map<String, String> perkNames) {}
    public record ClassDef(String id, String name, String desc, int tier, String from, String archetype, int minLevel, Map<String, Integer> reqStat, List<String> reqRaces, List<String> reqFlags,
                           Map<String, Double> mods, TreeMap<Integer, Map<String, Double>> perks, Map<String, String> perkNames,
                           List<String> alsoFrom, String exclusive, Map<String, Integer> reqProf, List<String> banFlags) {}
    /** Переплетение: бонус за сочетание расы, класса, профессий и форм (needs: races/classes - любой из списка, profs - id: мин. ранг, flags - все). */
    public record Synergy(String id, String name, String desc, List<String> races, List<String> classes, Map<String, Integer> profs, List<String> flags, Map<String, Double> mods) {}
    public record Rank(String name, int xp, Map<String, Double> mods) {}
    public record Profession(String id, String name, String desc, String group, int max, List<Rank> ranks, String workplace, int wage, Map<String, Integer> reqStat, List<String> reqRaces, String from, int minRank, String exclusive, List<String> reqClasses) {}

    public final Map<String, Race> races = new LinkedHashMap<>();
    public final Map<String, ClassDef> classes = new LinkedHashMap<>();
    public final Map<String, Profession> professions = new LinkedHashMap<>();
    public final Map<String, Synergy> synergies = new LinkedHashMap<>();
    /** Расовые особенности отдельным разделом "race_perks": {раса: {уровень: {name, mods}}} - можно дописать особенности чужой расе, не переопределяя её. */
    public final Map<String, TreeMap<Integer, Map<String, Double>>> extraPerks = new LinkedHashMap<>();
    public final Map<String, Map<String, String>> extraPerkNames = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }

    private static Map<String, Double> mods(Object o) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), dbl(e.getValue(), 0));
        return m;
    }

    private static List<String> strs(Object o) {
        List<String> l = new ArrayList<>();
        if (o instanceof List<?> ll) for (Object x : ll) l.add(String.valueOf(x));
        return l;
    }

    private static Map<String, Integer> ints(Object o) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), num(e.getValue(), 0));
        return m;
    }

    public void load(String json) {
        // A malformed data file must not leave half of its definitions installed.
        OriginDefs staged = new OriginDefs();
        staged.races.putAll(races);
        staged.classes.putAll(classes);
        staged.professions.putAll(professions);
        staged.synergies.putAll(synergies);
        for (var e : extraPerks.entrySet()) staged.extraPerks.put(e.getKey(), new TreeMap<>(e.getValue()));
        for (var e : extraPerkNames.entrySet()) staged.extraPerkNames.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        staged.loadInto(json);
        races.clear(); races.putAll(staged.races);
        classes.clear(); classes.putAll(staged.classes);
        professions.clear(); professions.putAll(staged.professions);
        synergies.clear(); synergies.putAll(staged.synergies);
        extraPerks.clear(); extraPerks.putAll(staged.extraPerks);
        extraPerkNames.clear(); extraPerkNames.putAll(staged.extraPerkNames);
    }

    @SuppressWarnings("unchecked")
    private void loadInto(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (m.get("races") instanceof List<?> l) for (Object o : l) addRace((Map<String, Object>) o);
        if (m.get("classes") instanceof List<?> l) for (Object o : l) addClass((Map<String, Object>) o);
        if (m.get("professions") instanceof List<?> l) for (Object o : l) addProfession((Map<String, Object>) o);
        if (m.get("race_perks") instanceof Map<?, ?> rp) for (var e : rp.entrySet()) {
            String rid = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> pm)) continue;
            for (var pe : pm.entrySet()) {
                Map<String, Object> p = (Map<String, Object>) pe.getValue();
                int lv = Integer.parseInt(String.valueOf(pe.getKey()));
                extraPerks.computeIfAbsent(rid, k -> new TreeMap<>()).put(lv, mods(p.get("mods")));
                extraPerkNames.computeIfAbsent(rid, k -> new LinkedHashMap<>()).put(String.valueOf(lv), str(p.get("name"), "Расовая особенность"));
            }
        }
        if (m.get("synergies") instanceof List<?> l) for (Object o : l) addSynergy((Map<String, Object>) o);
    }

    public void addSynergy(Map<String, Object> y) {
        String id = str(y.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У переплетения нет id");
        if (synergies.containsKey(id)) throw new IllegalArgumentException("Повторный id переплетения: " + id);
        synergies.put(id, new Synergy(id, str(y.get("name"), id), str(y.get("desc"), ""), strs(y.get("races")), strs(y.get("classes")), ints(y.get("profs")), strs(y.get("flags")), mods(y.get("mods"))));
    }

    @SuppressWarnings("unchecked")
    public void addRace(Map<String, Object> r) {
        String id = str(r.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У расы нет id");
        if (races.containsKey(id)) throw new IllegalArgumentException("Повторный id расы: " + id);
        Map<String, String> innate = new LinkedHashMap<>();
        if (r.get("innate") instanceof Map<?, ?> im) for (var e : im.entrySet()) innate.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        TreeMap<Integer, Map<String, Double>> perks = new TreeMap<>();
        Map<String, String> pn = new LinkedHashMap<>();
        if (r.get("perks") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) {
            Map<String, Object> p = (Map<String, Object>) e.getValue();
            int lv = Integer.parseInt(String.valueOf(e.getKey()));
            perks.put(lv, mods(p.get("mods")));
            pn.put(String.valueOf(lv), str(p.get("name"), "Расовая особенность"));
        }
        races.put(id, new Race(id, str(r.get("name"), id), str(r.get("desc"), ""), num(r.get("color"), 0x888888), mods(r.get("mods")), innate, strs(r.get("traits")), num(r.get("weight"), 10), str(r.get("parent"), ""), perks, pn));
    }

    @SuppressWarnings("unchecked")
    public void addClass(Map<String, Object> c) {
        String id = str(c.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У класса нет id");
        if (classes.containsKey(id)) throw new IllegalArgumentException("Повторный id класса: " + id);
        TreeMap<Integer, Map<String, Double>> perks = new TreeMap<>();
        Map<String, String> pn = new LinkedHashMap<>();
        if (c.get("perks") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) {
            Map<String, Object> p = (Map<String, Object>) e.getValue();
            int lv = Integer.parseInt(String.valueOf(e.getKey()));
            perks.put(lv, mods(p.get("mods")));
            pn.put(String.valueOf(lv), str(p.get("name"), "Умение класса"));
        }
        classes.put(id, new ClassDef(id, str(c.get("name"), id), str(c.get("desc"), ""), num(c.get("tier"), 1), str(c.get("from"), ""), str(c.get("archetype"), ""), num(c.get("min_level"), 1),
                ints(c.get("req_stat")), strs(c.get("req_races")), strs(c.get("req_flags")), mods(c.get("mods")), perks, pn,
                strs(c.get("also_from")), str(c.get("exclusive"), ""), ints(c.get("req_prof")), strs(c.get("ban_flags"))));
    }

    @SuppressWarnings("unchecked")
    public void addProfession(Map<String, Object> p) {
        String id = str(p.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У профессии нет id");
        if (professions.containsKey(id)) throw new IllegalArgumentException("Повторный id профессии: " + id);
        List<Rank> ranks = new ArrayList<>();
        if (p.get("ranks") instanceof List<?> l) for (Object o : l) { Map<String, Object> r = (Map<String, Object>) o; ranks.add(new Rank(str(r.get("name"), "Ранг"), num(r.get("xp"), 0), mods(r.get("mods")))); }
        if (ranks.isEmpty()) throw new IllegalArgumentException(id + ": нужен хотя бы один ранг");
        professions.put(id, new Profession(id, str(p.get("name"), id), str(p.get("desc"), ""), str(p.get("group"), "craft"), ranks.size(), ranks, str(p.get("workplace"), ""), num(p.get("wage"), 0), ints(p.get("req_stat")), strs(p.get("req_races")), str(p.get("from"), ""), num(p.get("min_rank"), 1), str(p.get("exclusive"), ""), strs(p.get("req_classes"))));
    }

    /** Цепочка расы от корня к самой подрасе (подраса наследует свойства родителя). */
    public List<Race> raceChain(String id) {
        LinkedList<Race> l = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        for (Race r = races.get(id); r != null && seen.add(r.id()); r = races.get(r.parent())) l.addFirst(r);
        return l;
    }

    /** Подходит ли раса have под требование req: сама раса или её подраса. */
    public boolean isRace(String have, String req) { for (Race r : raceChain(have)) if (r.id().equals(req)) return true; return false; }

    /** Все особенности расы: собственные и добавленные разделом race_perks (уровень -> поправки). */
    public TreeMap<Integer, Map<String, Double>> perksOf(String race) {
        TreeMap<Integer, Map<String, Double>> out = new TreeMap<>();
        Race r = races.get(race);
        if (r != null) out.putAll(r.perks());
        if (extraPerks.containsKey(race)) out.putAll(extraPerks.get(race));
        return out;
    }

    public String perkName(String race, int level) {
        Race r = races.get(race);
        String n = extraPerkNames.getOrDefault(race, Map.of()).get(String.valueOf(level));
        if (n == null && r != null) n = r.perkNames().get(String.valueOf(level));
        return n == null ? "Расовая особенность" : n;
    }

    /** Предки класса: основной и дополнительные (ветви могут сходиться). */
    public boolean isParent(ClassDef c, String have) { return c.from().equals(have) || c.alsoFrom().contains(have); }

    public boolean raceOk(String have, List<String> req) { if (req.isEmpty()) return true; for (String r : req) if (isRace(have, r)) return true; return false; }

    public List<String> validate() {
        List<String> p = new ArrayList<>();
        for (Race r : races.values()) {
            if (!r.parent().isEmpty() && !races.containsKey(r.parent())) p.add("Раса " + r.id() + ": нет родителя " + r.parent());
            Set<String> seen = new HashSet<>();
            for (String x = r.id(); races.containsKey(x); x = races.get(x).parent()) if (!seen.add(x)) { p.add("Раса " + r.id() + ": цикл родителей"); break; }
        }
        for (ClassDef c : classes.values()) {
            if (!c.from().isEmpty() && !classes.containsKey(c.from())) p.add("Класс " + c.id() + ": нет предка " + c.from());
            if (c.tier() > 1 && c.from().isEmpty()) p.add("Класс " + c.id() + ": ступень " + c.tier() + " требует from");
            if (!c.from().isEmpty() && classes.containsKey(c.from()) && classes.get(c.from()).tier() >= c.tier()) p.add("Класс " + c.id() + ": ступень должна быть выше предка");
            for (String r : c.reqRaces()) if (!races.containsKey(r)) p.add("Класс " + c.id() + ": нет расы " + r);
            for (String x : c.alsoFrom()) {
                if (!classes.containsKey(x)) p.add("Класс " + c.id() + ": нет второго предка " + x);
                else if (classes.get(x).tier() >= c.tier()) p.add("Класс " + c.id() + ": ступень должна быть выше предка " + x);
            }
            for (var req : c.reqProf().entrySet()) {
                Profession required = professions.get(req.getKey());
                if (required == null) p.add("Класс " + c.id() + ": нет профессии " + req.getKey());
                else if (req.getValue() < 0 || req.getValue() >= required.ranks().size())
                    p.add("Класс " + c.id() + ": требуемый ранг вне диапазона профессии " + req.getKey());
            }
        }
        for (String rid : extraPerks.keySet()) if (!races.containsKey(rid)) p.add("race_perks: нет расы " + rid);
        for (Synergy y : synergies.values()) {
            for (String r : y.races()) if (!races.containsKey(r)) p.add("Переплетение " + y.id() + ": нет расы " + r);
            for (String c : y.classes()) if (!classes.containsKey(c)) p.add("Переплетение " + y.id() + ": нет класса " + c);
            for (var req : y.profs().entrySet()) {
                Profession required = professions.get(req.getKey());
                if (required == null) p.add("Переплетение " + y.id() + ": нет профессии " + req.getKey());
                else if (req.getValue() < 0 || req.getValue() >= required.ranks().size())
                    p.add("Переплетение " + y.id() + ": ранг вне диапазона профессии " + req.getKey());
            }
            if (y.races().isEmpty() && y.classes().isEmpty() && y.profs().isEmpty() && y.flags().isEmpty()) p.add("Переплетение " + y.id() + ": нет условий");
        }
        for (Profession pr : professions.values()) {
            if (!pr.from().isEmpty()) {
                Profession par = professions.get(pr.from());
                if (par == null) p.add("Профессия " + pr.id() + ": нет основной " + pr.from());
                else if (!par.from().isEmpty()) p.add("Профессия " + pr.id() + ": специализация не может расти из специализации");
                else if (pr.minRank() < 0 || pr.minRank() >= par.ranks().size()) p.add("Профессия " + pr.id() + ": min_rank вне рангов " + pr.from());
            }
            for (String x : pr.reqClasses()) if (!classes.containsKey(x)) p.add("Профессия " + pr.id() + ": нет класса " + x);
            for (String r : pr.reqRaces()) if (!races.containsKey(r)) p.add("Профессия " + pr.id() + ": нет расы " + r);
            if (pr.wage() < 0) p.add("Профессия " + pr.id() + ": жалованье не может быть отрицательным");
            if (pr.ranks().get(0).xp() != 0) p.add("Профессия " + pr.id() + ": первый ранг должен начинаться с 0 XP");
            for (int i = 0; i < pr.ranks().size(); i++) {
                if (pr.ranks().get(i).xp() < 0) p.add("Профессия " + pr.id() + ": отрицательный порог XP");
                if (i > 0 && pr.ranks().get(i).xp() <= pr.ranks().get(i - 1).xp())
                    p.add("Профессия " + pr.id() + ": опыт рангов должен расти");
            }
        }
        return p;
    }
}
