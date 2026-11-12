package dev.worldforge.people;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Единый data-driven банк имён. Культуры остаются основным ключом для исторических
 * популяций; расовые пулы наследуют имена предков и добавляют собственные варианты.
 * Датапаки могут дополнять, но не затирать уже загруженные списки.
 */
public final class NameBank {
    public static final class Pool {
        public final List<String> male = new ArrayList<>(), female = new ArrayList<>(),
                surnames = new ArrayList<>(), houses = new ArrayList<>(), dynasties = new ArrayList<>(),
                clans = new ArrayList<>(), settlements = new ArrayList<>(), realms = new ArrayList<>(),
                regions = new ArrayList<>(), provinces = new ArrayList<>(), rivers = new ArrayList<>(),
                mountains = new ArrayList<>(), forests = new ArrayList<>(), taverns = new ArrayList<>(),
                ships = new ArrayList<>(), orders = new ArrayList<>(), factions = new ArrayList<>(),
                guilds = new ArrayList<>(), companies = new ArrayList<>(), armies = new ArrayList<>(),
                regiments = new ArrayList<>(), castles = new ArrayList<>(), fortresses = new ArrayList<>(),
                districts = new ArrayList<>(), landmarks = new ArrayList<>(), roads = new ArrayList<>(),
                markets = new ArrayList<>(), temples = new ArrayList<>(), religions = new ArrayList<>(),
                artifacts = new ArrayList<>(), monuments = new ArrayList<>();
        private void merge(Pool other) {
            mergeList(male, other.male); mergeList(female, other.female); mergeList(surnames, other.surnames);
            mergeList(houses, other.houses); mergeList(dynasties, other.dynasties); mergeList(clans, other.clans);
            mergeList(settlements, other.settlements); mergeList(realms, other.realms); mergeList(regions, other.regions);
            mergeList(provinces, other.provinces); mergeList(rivers, other.rivers); mergeList(mountains, other.mountains);
            mergeList(forests, other.forests); mergeList(taverns, other.taverns); mergeList(ships, other.ships); mergeList(orders, other.orders);
            mergeList(factions, other.factions); mergeList(guilds, other.guilds); mergeList(companies, other.companies);
            mergeList(armies, other.armies); mergeList(regiments, other.regiments); mergeList(castles, other.castles);
            mergeList(fortresses, other.fortresses); mergeList(districts, other.districts); mergeList(landmarks, other.landmarks);
            mergeList(roads, other.roads); mergeList(markets, other.markets); mergeList(temples, other.temples);
            mergeList(religions, other.religions); mergeList(artifacts, other.artifacts); mergeList(monuments, other.monuments);
        }
        private static void mergeList(List<String> target, List<String> source) {
            for (String value : source) if (!target.contains(value)) target.add(value);
        }
    }

    private static final List<String> CATEGORIES = List.of("male","female","surnames","houses","dynasties","clans",
            "settlements","realms","regions","provinces","rivers","mountains","forests","taverns","ships","orders",
            "factions","guilds","companies","armies","regiments","castles","fortresses","districts","landmarks",
            "roads","markets","temples","religions","artifacts","monuments");
    private final Map<String, Pool> cultures = new LinkedHashMap<>(), races = new LinkedHashMap<>();
    private final Map<String, String> raceParents = new LinkedHashMap<>();
    private String def = "";

    @SuppressWarnings("unchecked")
    public void add(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Банк имён должен быть JSON-объектом");
        Map<String,Object> root = (Map<String,Object>) raw;
        if (root.get("default") != null) def = String.valueOf(root.get("default"));
        readPools(root.get("cultures"), cultures, true, "cultures");
        readPools(root.get("races"), races, false, "races");
        if (root.get("race_parents") instanceof Map<?,?> parents)
            for (Map.Entry<?,?> entry : parents.entrySet()) {
                String race = String.valueOf(entry.getKey()), parent = String.valueOf(entry.getValue());
                if (!race.isBlank() && !parent.isBlank() && !race.equals(parent)) raceParents.put(race, parent);
            }
    }

    @SuppressWarnings("unchecked")
    private static void readPools(Object raw, Map<String,Pool> target, boolean required, String label) {
        if (raw == null) {
            if (required) throw new IllegalArgumentException("Нужен раздел " + label);
            return;
        }
        if (!(raw instanceof Map<?,?> map)) throw new IllegalArgumentException("Раздел " + label + " должен быть объектом");
        for (Map.Entry<?,?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?,?> values))
                throw new IllegalArgumentException("Пул " + entry.getKey() + " должен быть объектом");
            Pool pool = target.computeIfAbsent(String.valueOf(entry.getKey()), key -> new Pool());
            for (String category : CATEGORIES) fill(list(pool, category), values.get(category));
        }
    }

    private static List<String> list(Pool p, String category) {
        return switch (category) {
            case "male" -> p.male; case "female" -> p.female; case "surnames" -> p.surnames;
            case "houses" -> p.houses; case "dynasties" -> p.dynasties; case "clans" -> p.clans;
            case "settlements" -> p.settlements; case "realms" -> p.realms; case "regions" -> p.regions;
            case "provinces" -> p.provinces; case "rivers" -> p.rivers; case "mountains" -> p.mountains;
            case "forests" -> p.forests; case "taverns" -> p.taverns; case "ships" -> p.ships;
            case "orders" -> p.orders; case "factions" -> p.factions; case "guilds" -> p.guilds;
            case "companies" -> p.companies; case "armies" -> p.armies; case "regiments" -> p.regiments;
            case "castles" -> p.castles; case "fortresses" -> p.fortresses; case "districts" -> p.districts;
            case "landmarks" -> p.landmarks; case "roads" -> p.roads; case "markets" -> p.markets;
            case "temples" -> p.temples; case "religions" -> p.religions; case "artifacts" -> p.artifacts;
            case "monuments" -> p.monuments; default -> throw new IllegalArgumentException("Неизвестная категория имён: " + category);
        };
    }

    private static void fill(List<String> to, Object from) {
        if (from instanceof List<?> values) for (Object value : values) {
            String item = String.valueOf(value).trim();
            if (!item.isEmpty() && !to.contains(item)) to.add(item);
        }
    }

    public Pool pool(String culture) {
        Pool pool = cultures.get(culture);
        if (pool == null) pool = cultures.get(def);
        if (!validGivenNames(pool)) throw new IllegalStateException("Нет имён для культуры '" + culture + "'");
        return pool;
    }

    /** Собирает расовый пул от корневого народа к подрасе, затем дополняет его культурным пулом. */
    public Pool poolForRace(String race, String culture) {
        Pool result = new Pool();
        if (race != null && !race.isBlank()) {
            List<String> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String current = race;
            while (current != null && !current.isBlank() && seen.add(current)) {
                chain.add(current);
                current = raceParents.get(current);
            }
            Collections.reverse(chain);
            for (String id : chain) {
                Pool part = races.get(id);
                if (part != null) result.merge(part);
            }
        }
        Pool cultural = pool(culture);
        result.merge(cultural);
        if (!validGivenNames(result)) throw new IllegalStateException("Нет имён для расы '" + race + "' и культуры '" + culture + "'");
        return result;
    }

    private static boolean validGivenNames(Pool p) {
        return p != null && !p.male.isEmpty() && !p.female.isEmpty() && !p.houses.isEmpty();
    }

    public String culture(String wanted) { return cultures.containsKey(wanted) ? wanted : def; }

    public String pickGivenName(String race, String culture, String gender, Random random) {
        Pool p = poolForRace(race, culture);
        return pick("f".equalsIgnoreCase(gender) ? p.female : p.male, random);
    }

    public String pickHouse(String race, String culture, Random random) {
        return pick(poolForRace(race, culture).houses, random);
    }

    /** Генерация любого именуемого объекта одним детерминируемым Random вызывающей системы. */
    public String generate(String race, String culture, String category, Random random) {
        Pool p = poolForRace(race, culture);
        List<String> values = list(p, category == null ? "" : category.toLowerCase(Locale.ROOT));
        if (values.isEmpty()) {
            values = switch (category == null ? "" : category.toLowerCase(Locale.ROOT)) {
                case "surnames", "dynasties" -> !p.houses.isEmpty() ? p.houses : p.clans;
                case "clans" -> !p.houses.isEmpty() ? p.houses : p.dynasties;
                case "realms", "regions", "provinces" -> p.settlements;
                default -> List.of();
            };
        }
        if (values.isEmpty()) throw new IllegalStateException("Нет заполненного пула '" + category + "' для расы '" + race + "'");
        return pick(values, random);
    }

    private static String pick(List<String> values, Random random) {
        Objects.requireNonNull(random, "random");
        if (values == null || values.isEmpty()) throw new IllegalStateException("Пул имён пуст");
        return values.get(random.nextInt(values.size()));
    }
}
