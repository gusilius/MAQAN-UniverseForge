package dev.worldforge.origins;

import dev.worldforge.mod.Json;

import java.util.*;
import java.util.function.Supplier;

/** Расы, классы и профессии из JSON (секции "races", "classes", "professions"; несколько файлов объединяются). */
public final class OriginDefs {
    public record Race(String id, String name, String desc, int color, Map<String, Double> mods, Map<String, String> innate, List<String> traits, int weight, String parent,
                       NavigableMap<Integer, Map<String, Double>> perks, Map<String, String> perkNames) {}
    public record ClassDef(String id, String name, String desc, int tier, String from, String archetype, int minLevel, Map<String, Integer> reqStat, List<String> reqRaces, List<String> reqFlags,
                           Map<String, Double> mods, NavigableMap<Integer, Map<String, Double>> perks, Map<String, String> perkNames,
                           List<String> alsoFrom, String exclusive, Map<String, Integer> reqProf, List<String> banFlags) {}
    /** Переплетение: бонус за сочетание расы, класса, профессий и форм (needs: races/classes - любой из списка, profs - id: мин. ранг, flags - все). */
    public record Synergy(String id, String name, String desc, List<String> races, List<String> classes, Map<String, Integer> profs, List<String> flags, Map<String, Double> mods) {}
    public record Rank(String name, int xp, Map<String, Double> mods) {}
    public record Profession(String id, String name, String desc, String group, int max, List<Rank> ranks, String workplace, int wage, Map<String, Integer> reqStat, List<String> reqRaces, String from, int minRank, String exclusive, List<String> reqClasses) {}

    private final LinkedHashMap<String, Race> buildingRaces = new LinkedHashMap<>();
    private final LinkedHashMap<String, ClassDef> buildingClasses = new LinkedHashMap<>();
    private final LinkedHashMap<String, Profession> buildingProfessions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Synergy> buildingSynergies = new LinkedHashMap<>();
    private final LinkedHashMap<String, TreeMap<Integer, Map<String, Double>>> buildingExtraPerks = new LinkedHashMap<>();
    private final LinkedHashMap<String, Map<String, String>> buildingExtraPerkNames = new LinkedHashMap<>();
    private volatile Snapshot current = Snapshot.empty();
    public final Map<String, Race> races = readOnly(() -> current.races());
    public final Map<String, ClassDef> classes = readOnly(() -> current.classes());
    public final Map<String, Profession> professions = readOnly(() -> current.professions());
    public final Map<String, Synergy> synergies = readOnly(() -> current.synergies());
    public final Map<String, TreeMap<Integer, Map<String, Double>>> extraPerks = readOnly(() -> {
        Map<String, TreeMap<Integer, Map<String, Double>>> copy = new LinkedHashMap<>();
        current.extraPerks().forEach((id, perks) -> copy.put(id, new TreeMap<>(perks)));
        return Collections.unmodifiableMap(copy);
    });
    public final Map<String, Map<String, String>> extraPerkNames = readOnly(() -> current.extraPerkNames());

    public static final class Snapshot {
        private final long generation;
        private final Map<String, Race> races;
        private final Map<String, ClassDef> classes;
        private final Map<String, Profession> professions;
        private final Map<String, Synergy> synergies;
        private final Map<String, NavigableMap<Integer, Map<String, Double>>> extraPerks;
        private final Map<String, Map<String, String>> extraPerkNames;
        private Snapshot(long generation, Map<String, Race> races, Map<String, ClassDef> classes, Map<String, Profession> professions, Map<String, Synergy> synergies, Map<String, NavigableMap<Integer, Map<String, Double>>> extraPerks, Map<String, Map<String, String>> extraPerkNames) {
            this.generation=generation; this.races=immutableMap(races); this.classes=immutableMap(classes);
            this.professions=immutableMap(professions); this.synergies=immutableMap(synergies);
            this.extraPerks=immutableMap(extraPerks); this.extraPerkNames=immutableMap(extraPerkNames);
        }
        private static Snapshot empty() { return new Snapshot(0,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of()); }
        public long generation(){return generation;}
        public Map<String,Race> races(){return races;}
        public Map<String,ClassDef> classes(){return classes;}
        public Map<String,Profession> professions(){return professions;}
        public Map<String,Synergy> synergies(){return synergies;}
        public Map<String,NavigableMap<Integer,Map<String,Double>>> extraPerks(){return extraPerks;}
        public Map<String,Map<String,String>> extraPerkNames(){return extraPerkNames;}
        public List<Race> raceChain(String id) {
            LinkedList<Race> chain=new LinkedList<>(); Set<String> seen=new HashSet<>();
            for(Race r=races.get(id);r!=null&&seen.add(r.id());r=races.get(r.parent()))chain.addFirst(r);
            return List.copyOf(chain);
        }
        public boolean isRace(String have,String req){for(Race r:raceChain(have))if(r.id().equals(req))return true;return false;}
        public boolean raceOk(String have,List<String> req){if(req.isEmpty())return true;for(String r:req)if(isRace(have,r))return true;return false;}
        public boolean isParent(ClassDef c,String have){return c.from().equals(have)||c.alsoFrom().contains(have);}
        public NavigableMap<Integer,Map<String,Double>> perksOf(String race){
            TreeMap<Integer,Map<String,Double>> out=new TreeMap<>(); Race r=races.get(race);
            if(r!=null)out.putAll(r.perks()); Map<Integer,Map<String,Double>> extra=extraPerks.get(race);
            if(extra!=null)out.putAll(extra); return Collections.unmodifiableNavigableMap(out);
        }
        public String perkName(String race,int level){
            Race r=races.get(race); String n=extraPerkNames.getOrDefault(race,Map.of()).get(String.valueOf(level));
            if(n==null&&r!=null)n=r.perkNames().get(String.valueOf(level)); return n==null?"Расовая особенность":n;
        }
    }
    private static <K,V> Map<K,V> readOnly(Supplier<Map<K,V>> source){
        return new AbstractMap<>() {
            @Override public Set<Entry<K,V>> entrySet(){return source.get().entrySet();}
            @Override public V get(Object key){return source.get().get(key);}
            @Override public boolean containsKey(Object key){return source.get().containsKey(key);}
            @Override public int size(){return source.get().size();}
        };
    }
    private static <K,V> Map<K,V> immutableMap(Map<K,V> source){return Collections.unmodifiableMap(new LinkedHashMap<>(source));}
    private static Map<String,Double> immutableDoubles(Map<String,Double> source){return immutableMap(source);}
    private static NavigableMap<Integer,Map<String,Double>> immutablePerks(Map<Integer,Map<String,Double>> source){
        TreeMap<Integer,Map<String,Double>> copy=new TreeMap<>(); source.forEach((k,v)->copy.put(k,immutableDoubles(v)));
        return Collections.unmodifiableNavigableMap(copy);
    }
    private static Snapshot freeze(long generation,Map<String,Race> races,Map<String,ClassDef> classes,Map<String,Profession> professions,Map<String,Synergy> synergies,Map<String,TreeMap<Integer,Map<String,Double>>> extraPerks,Map<String,Map<String,String>> extraPerkNames){
        Map<String,Race> rr=new LinkedHashMap<>();
        races.forEach((id,r)->rr.put(id,new Race(r.id(),r.name(),r.desc(),r.color(),immutableDoubles(r.mods()),immutableMap(r.innate()),List.copyOf(r.traits()),r.weight(),r.parent(),immutablePerks(r.perks()),immutableMap(r.perkNames()))));
        Map<String,ClassDef> cc=new LinkedHashMap<>();
        classes.forEach((id,c)->cc.put(id,new ClassDef(c.id(),c.name(),c.desc(),c.tier(),c.from(),c.archetype(),c.minLevel(),immutableMap(c.reqStat()),List.copyOf(c.reqRaces()),List.copyOf(c.reqFlags()),immutableDoubles(c.mods()),immutablePerks(c.perks()),immutableMap(c.perkNames()),List.copyOf(c.alsoFrom()),c.exclusive(),immutableMap(c.reqProf()),List.copyOf(c.banFlags()))));
        Map<String,Synergy> yy=new LinkedHashMap<>();
        synergies.forEach((id,y)->yy.put(id,new Synergy(y.id(),y.name(),y.desc(),List.copyOf(y.races()),List.copyOf(y.classes()),immutableMap(y.profs()),List.copyOf(y.flags()),immutableDoubles(y.mods()))));
        Map<String,Profession> pp=new LinkedHashMap<>();
        professions.forEach((id,p)->{List<Rank> ranks=p.ranks().stream().map(r->new Rank(r.name(),r.xp(),immutableDoubles(r.mods()))).toList();pp.put(id,new Profession(p.id(),p.name(),p.desc(),p.group(),p.max(),ranks,p.workplace(),p.wage(),immutableMap(p.reqStat()),List.copyOf(p.reqRaces()),p.from(),p.minRank(),p.exclusive(),List.copyOf(p.reqClasses())));});
        Map<String,NavigableMap<Integer,Map<String,Double>>> ep=new LinkedHashMap<>();
        extraPerks.forEach((id,p)->ep.put(id,immutablePerks(p)));
        Map<String,Map<String,String>> en=new LinkedHashMap<>();
        extraPerkNames.forEach((id,n)->en.put(id,immutableMap(n)));
        return new Snapshot(generation,rr,cc,pp,yy,ep,en);
    }
    public Snapshot snapshot(){return current;}
    private void publishSnapshot(){current=freeze(Math.incrementExact(current.generation()),buildingRaces,buildingClasses,buildingProfessions,buildingSynergies,buildingExtraPerks,buildingExtraPerkNames);}

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

    /** Atomically replace all datapack definitions after a complete reload has been parsed and validated. */
    public synchronized void replaceWith(OriginDefs source) {
        Objects.requireNonNull(source,"source"); List<String> problems=source.validate();
        if(!problems.isEmpty())throw new IllegalArgumentException("Некорректные определения происхождения: "+String.join("; ",problems));
        Snapshot s=source.snapshot();
        buildingRaces.clear();buildingRaces.putAll(s.races()); buildingClasses.clear();buildingClasses.putAll(s.classes());
        buildingProfessions.clear();buildingProfessions.putAll(s.professions()); buildingSynergies.clear();buildingSynergies.putAll(s.synergies());
        buildingExtraPerks.clear();s.extraPerks().forEach((id,p)->{TreeMap<Integer,Map<String,Double>> copy=new TreeMap<>();copy.putAll(p);buildingExtraPerks.put(id,copy);});
        buildingExtraPerkNames.clear();s.extraPerkNames().forEach((id,n)->buildingExtraPerkNames.put(id,new LinkedHashMap<>(n)));
        publishSnapshot();
    }
    public synchronized void load(String json) {
        OriginDefs staged=new OriginDefs(); Snapshot before=snapshot();
        staged.buildingRaces.putAll(before.races());staged.buildingClasses.putAll(before.classes());staged.buildingProfessions.putAll(before.professions());staged.buildingSynergies.putAll(before.synergies());
        before.extraPerks().forEach((id,p)->{TreeMap<Integer,Map<String,Double>> copy=new TreeMap<>();copy.putAll(p);staged.buildingExtraPerks.put(id,copy);});
        before.extraPerkNames().forEach((id,n)->staged.buildingExtraPerkNames.put(id,new LinkedHashMap<>(n)));
        staged.loadInto(json);
        buildingRaces.clear();buildingRaces.putAll(staged.buildingRaces);buildingClasses.clear();buildingClasses.putAll(staged.buildingClasses);
        buildingProfessions.clear();buildingProfessions.putAll(staged.buildingProfessions);buildingSynergies.clear();buildingSynergies.putAll(staged.buildingSynergies);
        buildingExtraPerks.clear();staged.buildingExtraPerks.forEach((id,p)->buildingExtraPerks.put(id,new TreeMap<>(p)));
        buildingExtraPerkNames.clear();staged.buildingExtraPerkNames.forEach((id,n)->buildingExtraPerkNames.put(id,new LinkedHashMap<>(n)));
        publishSnapshot();
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
                buildingExtraPerks.computeIfAbsent(rid, k -> new TreeMap<>()).put(lv, mods(p.get("mods")));
                buildingExtraPerkNames.computeIfAbsent(rid, k -> new LinkedHashMap<>()).put(String.valueOf(lv), str(p.get("name"), "Расовая особенность"));
            }
        }
        if (m.get("synergies") instanceof List<?> l) for (Object o : l) addSynergy((Map<String, Object>) o);
    }

    public void addSynergy(Map<String, Object> y) {
        String id = str(y.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У переплетения нет id");
        if (buildingSynergies.containsKey(id)) throw new IllegalArgumentException("Повторный id переплетения: " + id);
        buildingSynergies.put(id, new Synergy(id, str(y.get("name"), id), str(y.get("desc"), ""), strs(y.get("races")), strs(y.get("classes")), ints(y.get("profs")), strs(y.get("flags")), mods(y.get("mods"))));
    }

    @SuppressWarnings("unchecked")
    public void addRace(Map<String, Object> r) {
        String id = str(r.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У расы нет id");
        if (buildingRaces.containsKey(id)) throw new IllegalArgumentException("Повторный id расы: " + id);
        Map<String, String> innate = new LinkedHashMap<>();
        if (r.get("innate") instanceof Map<?, ?> im) for (var e : im.entrySet()) innate.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        NavigableMap<Integer, Map<String, Double>> perks = new TreeMap<>();
        Map<String, String> pn = new LinkedHashMap<>();
        if (r.get("perks") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) {
            Map<String, Object> p = (Map<String, Object>) e.getValue();
            int lv = Integer.parseInt(String.valueOf(e.getKey()));
            perks.put(lv, mods(p.get("mods")));
            pn.put(String.valueOf(lv), str(p.get("name"), "Расовая особенность"));
        }
        buildingRaces.put(id, new Race(id, str(r.get("name"), id), str(r.get("desc"), ""), num(r.get("color"), 0x888888), mods(r.get("mods")), innate, strs(r.get("traits")), num(r.get("weight"), 10), str(r.get("parent"), ""), perks, pn));
    }

    @SuppressWarnings("unchecked")
    public void addClass(Map<String, Object> c) {
        String id = str(c.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У класса нет id");
        if (buildingClasses.containsKey(id)) throw new IllegalArgumentException("Повторный id класса: " + id);
        NavigableMap<Integer, Map<String, Double>> perks = new TreeMap<>();
        Map<String, String> pn = new LinkedHashMap<>();
        if (c.get("perks") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) {
            Map<String, Object> p = (Map<String, Object>) e.getValue();
            int lv = Integer.parseInt(String.valueOf(e.getKey()));
            perks.put(lv, mods(p.get("mods")));
            pn.put(String.valueOf(lv), str(p.get("name"), "Умение класса"));
        }
        buildingClasses.put(id, new ClassDef(id, str(c.get("name"), id), str(c.get("desc"), ""), num(c.get("tier"), 1), str(c.get("from"), ""), str(c.get("archetype"), ""), num(c.get("min_level"), 1),
                ints(c.get("req_stat")), strs(c.get("req_races")), strs(c.get("req_flags")), mods(c.get("mods")), perks, pn,
                strs(c.get("also_from")), str(c.get("exclusive"), ""), ints(c.get("req_prof")), strs(c.get("ban_flags"))));
    }

    @SuppressWarnings("unchecked")
    public void addProfession(Map<String, Object> p) {
        String id = str(p.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("У профессии нет id");
        if (buildingProfessions.containsKey(id)) throw new IllegalArgumentException("Повторный id профессии: " + id);
        List<Rank> ranks = new ArrayList<>();
        if (p.get("ranks") instanceof List<?> l) for (Object o : l) { Map<String, Object> r = (Map<String, Object>) o; ranks.add(new Rank(str(r.get("name"), "Ранг"), num(r.get("xp"), 0), mods(r.get("mods")))); }
        if (ranks.isEmpty()) throw new IllegalArgumentException(id + ": нужен хотя бы один ранг");
        buildingProfessions.put(id, new Profession(id, str(p.get("name"), id), str(p.get("desc"), ""), str(p.get("group"), "craft"), ranks.size(), ranks, str(p.get("workplace"), ""), num(p.get("wage"), 0), ints(p.get("req_stat")), strs(p.get("req_races")), str(p.get("from"), ""), num(p.get("min_rank"), 1), str(p.get("exclusive"), ""), strs(p.get("req_classes"))));
    }

    public List<Race> raceChain(String id){return snapshot().raceChain(id);}
    public boolean isRace(String have,String req){return snapshot().isRace(have,req);}
    public boolean isParent(ClassDef c,String have){return snapshot().isParent(c,have);}
    public boolean raceOk(String have,List<String> req){return snapshot().raceOk(have,req);}
    public NavigableMap<Integer,Map<String,Double>> perksOf(String race){return snapshot().perksOf(race);}
    public String perkName(String race,int level){return snapshot().perkName(race,level);}

    public synchronized List<String> validate() {
        List<String> p = new ArrayList<>();
        for (Race r : buildingRaces.values()) {
            if (!r.parent().isEmpty() && !buildingRaces.containsKey(r.parent())) p.add("Раса " + r.id() + ": нет родителя " + r.parent());
            Set<String> seen = new HashSet<>();
            for (String x = r.id(); buildingRaces.containsKey(x); x = buildingRaces.get(x).parent()) if (!seen.add(x)) { p.add("Раса " + r.id() + ": цикл родителей"); break; }
        }
        for (ClassDef c : buildingClasses.values()) {
            if (!c.from().isEmpty() && !buildingClasses.containsKey(c.from())) p.add("Класс " + c.id() + ": нет предка " + c.from());
            if (c.tier() > 1 && c.from().isEmpty()) p.add("Класс " + c.id() + ": ступень " + c.tier() + " требует from");
            if (!c.from().isEmpty() && buildingClasses.containsKey(c.from()) && buildingClasses.get(c.from()).tier() >= c.tier()) p.add("Класс " + c.id() + ": ступень должна быть выше предка");
            for (String r : c.reqRaces()) if (!buildingRaces.containsKey(r)) p.add("Класс " + c.id() + ": нет расы " + r);
            for (String x : c.alsoFrom()) {
                if (!buildingClasses.containsKey(x)) p.add("Класс " + c.id() + ": нет второго предка " + x);
                else if (buildingClasses.get(x).tier() >= c.tier()) p.add("Класс " + c.id() + ": ступень должна быть выше предка " + x);
            }
            for (var req : c.reqProf().entrySet()) {
                Profession required = buildingProfessions.get(req.getKey());
                if (required == null) p.add("Класс " + c.id() + ": нет профессии " + req.getKey());
                else if (req.getValue() < 0 || req.getValue() >= required.ranks().size())
                    p.add("Класс " + c.id() + ": требуемый ранг вне диапазона профессии " + req.getKey());
            }
        }
        for (String rid : buildingExtraPerks.keySet()) if (!buildingRaces.containsKey(rid)) p.add("race_perks: нет расы " + rid);
        for (Synergy y : buildingSynergies.values()) {
            for (String r : y.races()) if (!buildingRaces.containsKey(r)) p.add("Переплетение " + y.id() + ": нет расы " + r);
            for (String c : y.classes()) if (!buildingClasses.containsKey(c)) p.add("Переплетение " + y.id() + ": нет класса " + c);
            for (var req : y.profs().entrySet()) {
                Profession required = buildingProfessions.get(req.getKey());
                if (required == null) p.add("Переплетение " + y.id() + ": нет профессии " + req.getKey());
                else if (req.getValue() < 0 || req.getValue() >= required.ranks().size())
                    p.add("Переплетение " + y.id() + ": ранг вне диапазона профессии " + req.getKey());
            }
            if (y.buildingRaces().isEmpty() && y.buildingClasses().isEmpty() && y.profs().isEmpty() && y.flags().isEmpty()) p.add("Переплетение " + y.id() + ": нет условий");
        }
        for (Profession pr : buildingProfessions.values()) {
            if (!pr.from().isEmpty()) {
                Profession par = buildingProfessions.get(pr.from());
                if (par == null) p.add("Профессия " + pr.id() + ": нет основной " + pr.from());
                else if (!par.from().isEmpty()) p.add("Профессия " + pr.id() + ": специализация не может расти из специализации");
                else if (pr.minRank() < 0 || pr.minRank() >= par.ranks().size()) p.add("Профессия " + pr.id() + ": min_rank вне рангов " + pr.from());
            }
            for (String x : pr.reqClasses()) if (!buildingClasses.containsKey(x)) p.add("Профессия " + pr.id() + ": нет класса " + x);
            for (String r : pr.reqRaces()) if (!buildingRaces.containsKey(r)) p.add("Профессия " + pr.id() + ": нет расы " + r);
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
