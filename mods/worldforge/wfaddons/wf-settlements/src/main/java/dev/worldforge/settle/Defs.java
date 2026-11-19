package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Описания зданий и профессий из JSON (датапак data/<ns>/wf_settle/*.json). Новое здание или профессия - только файл:
 * <pre>
 * {"professions": {"farmer": {"name": "Земледелец"}},
 *  "buildings": {"farm": {"name": "Ферма", "cost": {"wood": 15}, "work": 25, "size": [5, 5], "blueprint": "wf_settle:farm",
 *                         "jobs": {"farmer": 4}, "recipe": {"in": {}, "out": {"food": 4}, "cycle": 20}}}}
 * </pre>
 * Поля здания: cost (материалы, списываются при старте стройки), work (работа строителей; 1 строитель = 1 в секунду), housing (мест для жителей),
 * storage (добавка к вместимости склада по ресурсам), jobs (рабочие места по профессиям), recipe (in/out за цикл, cycle секунд при полном штате),
 * requires (здания, которые должны уже стоять), limit (сколько можно построить), builders (максимум строителей одновременно),
 * upkeep (серебра в минуту за содержание готового здания), trade (пропускная способность торговли, единиц товара в минуту),
 * safety (очки безопасности для настроения), approval (постоянная прибавка к настроению, пока здание работает).
 * Верхний уровень набора: resources (id -> имя), prices (id -> базовая цена в серебре), comfort (id -> {bonus, rate}: товары для
 * настроения, расход на жителя в секунду), food_kinds (какие ресурсы - еда; жители едят их вместе, разнообразие даёт настроение).
 */
public final class Defs {
    public static final class Profession {
        public final String id, name;

        Profession(String id, String name) { this.id = id; this.name = name; }
    }

    public static final class Building {
        public final String id, name, blueprint;
        public String description = "";
        /** Optional data-driven identity metadata; empty lists preserve legacy universal behavior. */
        public String structureClass = "";
        /** Procedural palette key; unknown values preserve legacy oak palette. */
        public String architectureMaterial = "oak";
        public final Set<String> raceAllow = new LinkedHashSet<>(), raceDeny = new LinkedHashSet<>();
        public final Set<String> subraceAllow = new LinkedHashSet<>(), cultureAllow = new LinkedHashSet<>(), cultureTags = new LinkedHashSet<>();
        public final Set<String> architectureTags = new LinkedHashSet<>(), governmentAllow = new LinkedHashSet<>(), settlementTypeAllow = new LinkedHashSet<>();
        public final Set<String> governmentTags = new LinkedHashSet<>(), formationAllow = new LinkedHashSet<>(), architectureStyleAllow = new LinkedHashSet<>();
        public final Map<String, Double> effects = new LinkedHashMap<>();
        public final Map<String, Integer> cost = new LinkedHashMap<>(), storage = new LinkedHashMap<>(), jobs = new LinkedHashMap<>(), in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        public final List<String> requires = new ArrayList<>();
        /** Раздел меню стройки: один из {@link Defs#CATEGORIES}. */
        public String category = "other";
        public double work = 30, cycle = 0, upkeep = 0;
        public int housing, limit = 0, builders = 3, sizeX = 3, sizeZ = 3, trade = 0, safety = 0, approval = 0;

        Building(String id, String name, String blueprint) { this.id = id; this.name = name; this.blueprint = blueprint; }

        public int slots() { int n = 0; for (int v : jobs.values()) n += v; return n; }

        public boolean produces() { return cycle > 0 && !out.isEmpty(); }
    }

    /** Разделы меню стройки (поле `category` здания) и их названия. */
    public static final List<String> CATEGORIES = List.of("housing", "food", "industry", "storage", "military", "other", "trade", "civic", "schem");
    private static final String[] CATEGORY_NAMES = {"Жильё", "Еда", "Производство", "Склад", "Армия", "Прочее", "Торговля", "Общество", "Схемы"};

    public static String categoryName(String id) { int i = CATEGORIES.indexOf(id); return CATEGORY_NAMES[i < 0 ? 5 : i]; }

    /** Серебро: деньги поселения (налоги, содержание зданий, торговля). */
    public static final String SILVER = "silver";

    /** Товар для настроения: прибавка к настроению, пока он есть на складе, и расход на жителя в секунду. */
    public static final class Comfort {
        public final int bonus;
        public final double rate;

        Comfort(int bonus, double rate) { this.bonus = bonus; this.rate = rate; }
    }

    /** Группа товара для окна поселения: food, material, goods или other. */
    public static String resourceCategory(String res) {
        return switch (res) {
            case "food", "bread", "fish", "meat", "grain", "flour" -> "food";
            case "wood", "planks", "stone", "iron", "wool" -> "material";
            case "tools", "cloth", "clothes", "ale" -> "goods";
            case "silver" -> "money";
            default -> "other";
        };
    }

    public static String resourceCategoryName(String cat) {
        return switch (cat) { case "food" -> "Еда"; case "material" -> "Материалы"; case "goods" -> "Изделия"; case "money" -> "Деньги"; default -> "Прочее"; };
    }

    public final Map<String, Profession> professions = new LinkedHashMap<>();
    public final Map<String, Building> buildings = new LinkedHashMap<>();
    public final Map<String, String> resourceNames = new LinkedHashMap<>();
    /** Базовые цены (серебро за единицу). Покупка дороже на {@link Settlement#TRADE_MARGIN}, продажа дешевле. */
    public final Map<String, Double> prices = new LinkedHashMap<>();
    public final Map<String, Comfort> comfort = new LinkedHashMap<>();
    /** Ресурсы-еда. По умолчанию только "food". */
    public final List<String> foodKinds = new ArrayList<>(List.of("food"));

    public Defs() {
        professions.put("laborer", new Profession("laborer", "Работник"));
        professions.put("builder", new Profession("builder", "Строитель"));
    }

    /** Register a canonical wf-people profession as a valid settlement Worker assignment. */
    public void registerCanonicalProfession(String id, String name) {
        if (id == null || id.isBlank()) return;
        professions.putIfAbsent(id, new Profession(id, name == null || name.isBlank() ? id : name));
    }

    @SuppressWarnings("unchecked")
    public void add(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (root.get("resources") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("resources")).entrySet()) resourceNames.put(e.getKey(), String.valueOf(e.getValue()));
        if (root.get("prices") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("prices")).entrySet()) prices.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        if (root.get("comfort") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("comfort")).entrySet()) {
                if (e.getValue() instanceof Number n) comfort.put(e.getKey(), new Comfort(n.intValue(), 0.004));
                else {
                    Map<String, Object> m = (Map<String, Object>) e.getValue();
                    comfort.put(e.getKey(), new Comfort(m.get("bonus") instanceof Number n ? n.intValue() : 3, m.get("rate") instanceof Number r ? r.doubleValue() : 0.004));
                }
            }
        }
        if (root.get("food_kinds") instanceof List) for (Object o : (List<Object>) root.get("food_kinds")) if (!foodKinds.contains(String.valueOf(o))) foodKinds.add(String.valueOf(o));
        if (root.get("professions") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("professions")).entrySet()) {
                professions.put(e.getKey(), new Profession(e.getKey(), String.valueOf(((Map<String, Object>) e.getValue()).get("name"))));
            }
        }
        if (root.get("buildings") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("buildings")).entrySet()) {
                Map<String, Object> m = (Map<String, Object>) e.getValue();
                Building b = new Building(e.getKey(), String.valueOf(m.get("name")), m.get("blueprint") == null ? "" : String.valueOf(m.get("blueprint")));
                ints(b.cost, m.get("cost")); ints(b.storage, m.get("storage")); ints(b.jobs, m.get("jobs"));
                if (m.get("recipe") instanceof Map) {
                    Map<String, Object> r = (Map<String, Object>) m.get("recipe");
                    ints(b.in, r.get("in")); ints(b.out, r.get("out"));
                    b.cycle = r.get("cycle") instanceof Number n ? n.doubleValue() : 0;
                }
                if (m.get("requires") instanceof List) for (Object o : (List<Object>) m.get("requires")) b.requires.add(String.valueOf(o));
                if (m.get("category") != null) b.category = String.valueOf(m.get("category"));
                if (m.get("description") != null) b.description = String.valueOf(m.get("description"));
                if (m.get("structure_class") != null) b.structureClass = String.valueOf(m.get("structure_class"));
                if (m.get("architecture_material") != null) b.architectureMaterial = String.valueOf(m.get("architecture_material")).trim().toLowerCase(Locale.ROOT);
                strings(b.raceAllow, m.get("race_compatibility"));
                strings(b.raceDeny, m.get("race_incompatibility"));
                strings(b.subraceAllow, m.get("subrace_compatibility"));
                strings(b.cultureAllow, m.get("culture_compatibility"));
                strings(b.cultureTags, m.get("culture_tags"));
                strings(b.architectureTags, m.get("architecture_tags"));
                strings(b.governmentAllow, m.get("government_compatibility"));
                strings(b.governmentAllow, m.get("government_tags"));
                strings(b.governmentTags, m.get("government_tags"));
                strings(b.formationAllow, m.get("polity_formation_compatibility"));
                strings(b.formationAllow, m.get("formation_compatibility"));
                strings(b.architectureStyleAllow, m.get("architecture_style_compatibility"));
                strings(b.settlementTypeAllow, m.get("settlement_types"));
                if (m.get("settlement_type_compatibility") != null) strings(b.settlementTypeAllow, m.get("settlement_type_compatibility"));
                if (m.get("effects") instanceof Map<?,?> effectMap) for (Map.Entry<?,?> effect : effectMap.entrySet()) {
                    if (effect.getKey() instanceof String key && effect.getValue() instanceof Number value) b.effects.put(key, value.doubleValue());
                }
                if (m.get("work") instanceof Number n) b.work = n.doubleValue();
                if (m.get("upkeep") instanceof Number n) b.upkeep = n.doubleValue();
                if (m.get("trade") instanceof Number n) b.trade = n.intValue();
                if (m.get("safety") instanceof Number n) b.safety = n.intValue();
                if (m.get("approval") instanceof Number n) b.approval = n.intValue();
                if (m.get("housing") instanceof Number n) b.housing = n.intValue();
                if (m.get("limit") instanceof Number n) b.limit = n.intValue();
                if (m.get("builders") instanceof Number n) b.builders = n.intValue();
                if (m.get("size") instanceof List<?> l && l.size() == 2) { b.sizeX = ((Number) l.get(0)).intValue(); b.sizeZ = ((Number) l.get(1)).intValue(); }
                if (b.work <= 0) throw new IllegalArgumentException("Здание " + b.id + ": work должно быть больше 0");
                buildings.put(b.id, b);
            }
        }
    }

    private static void strings(Set<String> target, Object raw) {
        if (raw instanceof List<?> values) for (Object value : values) {
            if (value instanceof String s && !s.isBlank()) target.add(s.trim());
        }
    }

    /** Runtime Genesis selection. Empty result means explicit incompatibility with no safe replacement. */
    public String selectGenesisBuilding(String requestedId, String raceId, long seed) {
        return selectIdentityBuilding(requestedId, raceId, null, null, null, null, seed);
    }

    /** Shared identity-aware selection API. Unknown IDs are neutral; explicit exclusions remain hard constraints. */
    public String selectIdentityBuilding(String requestedId, String raceId, String subraceId, String cultureId,
                                        String governmentId, String settlementTypeId, long seed) {
        return selectIdentityBuilding(requestedId, raceId, subraceId, cultureId, governmentId,
                settlementTypeId, null, null, seed);
    }

    /** Full data-driven identity resolver; blank dimensions are neutral and explicit constraints are hard filters. */
    public String selectIdentityBuilding(String requestedId, String raceId, String subraceId, String cultureId,
                                        String governmentId, String settlementTypeId, String formationId,
                                        String architectureStyleId, long seed) {
        Building requested = buildings.get(requestedId);
        if (requested == null) return requestedId;
        boolean requestedCompatible = compatible(requested, raceId, subraceId, cultureId, governmentId,
                settlementTypeId, formationId, architectureStyleId);
        List<Building> alternatives = buildings.values().stream()
                .filter(candidate -> Objects.equals(candidate.category, requested.category))
                .filter(candidate -> !candidate.id.equals(requested.id))
                .filter(Defs::hasIdentityMetadata)
                .filter(candidate -> compatible(candidate, raceId, subraceId, cultureId, governmentId,
                        settlementTypeId, formationId, architectureStyleId))
                .filter(candidate -> identityPreferenceScore(candidate, raceId, subraceId, cultureId, governmentId,
                        settlementTypeId, formationId, architectureStyleId) > 0)
                .sorted(Comparator.comparingInt((Building candidate) ->
                        identityPreferenceScore(candidate, raceId, subraceId, cultureId, governmentId,
                                settlementTypeId, formationId, architectureStyleId)).reversed()
                        .thenComparing(candidate -> candidate.id))
                .toList();
        if (alternatives.isEmpty()) return requestedCompatible ? requestedId : "";
        int bestScore = identityPreferenceScore(alternatives.get(0), raceId, subraceId, cultureId, governmentId,
                settlementTypeId, formationId, architectureStyleId);
        List<Building> best = alternatives.stream()
                .filter(candidate -> identityPreferenceScore(candidate, raceId, subraceId, cultureId, governmentId,
                        settlementTypeId, formationId, architectureStyleId) == bestScore).toList();
        long mixed = seed ^ (seed >>> 33) ^ requestedId.hashCode() * 0x9E3779B97F4A7C15L;
        return best.get((int) Math.floorMod(mixed, best.size())).id;
    }

    private static int identityPreferenceScore(Building b, String race, String subrace, String culture,
                                               String government, String settlementType, String formation, String style) {
        int score = 0;
        if (race != null && b.raceAllow.contains(race)) score += 100;
        if (subrace != null && b.subraceAllow.contains(subrace)) score += 140;
        if (culture != null && b.cultureAllow.contains(culture)) score += 120;
        if (culture != null && b.cultureTags.contains(culture)) score += 45;
        if (government != null && b.governmentAllow.contains(government)) score += 80;
        if (government != null && b.governmentTags.contains(government)) score += 80;
        if (settlementType != null && b.settlementTypeAllow.contains(settlementType)) score += 30;
        if (formation != null && b.formationAllow.contains(formation)) score += 110;
        if (style != null && b.architectureStyleAllow.contains(style)) score += 90;
        if (style != null && b.architectureTags.contains(style)) score += 50;
        return score;
    }

    private static boolean hasIdentityMetadata(Building b) {
        return !b.structureClass.isBlank() || !b.raceAllow.isEmpty() || !b.raceDeny.isEmpty()
                || !b.subraceAllow.isEmpty() || !b.cultureAllow.isEmpty() || !b.cultureTags.isEmpty()
                || !b.architectureTags.isEmpty() || !b.governmentAllow.isEmpty() || !b.governmentTags.isEmpty()
                || !b.settlementTypeAllow.isEmpty() || !b.formationAllow.isEmpty() || !b.architectureStyleAllow.isEmpty();
    }

    private static boolean compatible(Building b, String race, String subrace, String culture,
                                      String government, String settlementType, String formation, String style) {
        if (race != null && b.raceDeny.contains(race)) return false;
        if (race != null && !b.raceAllow.isEmpty() && !b.raceAllow.contains(race)) return false;
        if (subrace != null && !b.subraceAllow.isEmpty() && !b.subraceAllow.contains(subrace)) return false;
        if (culture != null && !b.cultureAllow.isEmpty() && !b.cultureAllow.contains(culture)) return false;
        if (government != null && !b.governmentAllow.isEmpty() && !b.governmentAllow.contains(government)
                && !b.governmentTags.contains(government)) return false;
        if (settlementType != null && !b.settlementTypeAllow.isEmpty() && !b.settlementTypeAllow.contains(settlementType)) return false;
        if (formation != null && !b.formationAllow.isEmpty() && !b.formationAllow.contains(formation)) return false;
        if (style != null && !b.architectureStyleAllow.isEmpty() && !b.architectureStyleAllow.contains(style)) return false;
        return true;
    }

    /** Ошибки в наборе (без префикса) и предупреждения (с префиксом «Предупреждение»): ссылки на неизвестные здания, профессии и ресурсы, циклы requires. */
    public List<String> validate() {
        List<String> p = new ArrayList<>();
        Set<String> produced = new HashSet<>();
        for (Building b : buildings.values()) produced.addAll(b.out.keySet());
        for (Building b : buildings.values()) {
            if (!CATEGORIES.contains(b.category)) p.add(b.id + ": неизвестная категория '" + b.category + "'");
            for (String r : b.requires) if (!buildings.containsKey(r)) p.add(b.id + ": requires неизвестное здание '" + r + "'");
            for (String j : b.jobs.keySet()) if (!professions.containsKey(j) && !j.equals("hauler")) p.add(b.id + ": неизвестная профессия '" + j + "'");
            for (Map<String, Integer> m : List.of(b.cost, b.in, b.out, b.storage)) for (String r : m.keySet()) if (!resourceNames.containsKey(r)) p.add(b.id + ": неизвестный ресурс '" + r + "'");
            if (!b.jobs.isEmpty() && !b.produces() && b.housing == 0 && b.storage.isEmpty() && b.trade == 0 && b.approval == 0 && b.safety == 0 && b.effects.isEmpty()) p.add("Предупреждение: " + b.id + " даёт рабочие места, но ничего не делает");
            if (b.cycle > 0 && b.out.isEmpty()) p.add("Предупреждение: " + b.id + " имеет cycle, но нет out");
            if (b.upkeep < 0) p.add(b.id + ": upkeep не может быть отрицательным");
            if (b.trade > 0 && prices.isEmpty()) p.add("Предупреждение: " + b.id + " торгует, но нет цен (prices)");
            for (String r : b.in.keySet()) if (resourceNames.containsKey(r) && !produced.contains(r) && !r.equals(SILVER)) p.add("Предупреждение: " + b.id + " требует " + r + ", но его никто не производит");
            if (cyclic(b.id)) p.add(b.id + ": requires образует цикл");
        }
        for (String r : prices.keySet()) if (!resourceNames.containsKey(r)) p.add("prices: неизвестный ресурс '" + r + "'");
        for (String r : comfort.keySet()) if (!resourceNames.containsKey(r)) p.add("comfort: неизвестный ресурс '" + r + "'");
        for (String r : foodKinds) if (!resourceNames.containsKey(r)) p.add("food_kinds: неизвестный ресурс '" + r + "'");
        return p;
    }

    private boolean cyclic(String start) {
        Deque<String> q = new ArrayDeque<>(buildings.get(start).requires);
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (c.equals(start)) return true;
            Building b = buildings.get(c);
            if (b != null && seen.add(c)) q.addAll(b.requires);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void ints(Map<String, Integer> to, Object from) {
        if (from instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) from).entrySet()) to.put(e.getKey(), ((Number) e.getValue()).intValue());
    }

    public String professionName(String id) { Profession p = professions.get(id); return p == null ? id : p.name; }
}
