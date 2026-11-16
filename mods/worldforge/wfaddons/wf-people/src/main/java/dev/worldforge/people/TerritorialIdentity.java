package dev.worldforge.people;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Idempotent identity bootstrap for image-generated and authored political maps.
 * Geography owns IDs and hierarchy; this pass fills only missing identity fields.
 */
public final class TerritorialIdentity {
    private TerritorialIdentity() {}

    public record Result(int realmsNamed, int titlesNamed, int titlesClassified, int rulersLinked) {}

    public record RaceWeight(String id, String parent, int weight) {}
    public record DemographicResult(int realmsClassified, int distinctRaces) {}

    /**
     * Seeds missing realm population mixes from the live wf-origins registry.
     * Existing valid mixes are authoritative and never rerolled. This runs before Courts.ensure,
     * so rulers, houses and their families are born from the same racial/cultural population.
     */
    public static DemographicResult ensureDemographics(String mapId, TitleData data, TitleEditor editor, long worldSeed) {
        Objects.requireNonNull(data, "title data");
        Objects.requireNonNull(editor, "title editor");
        List<RaceWeight> raceCatalog = originRaces();
        List<RaceWeight> roots = raceCatalog.stream().filter(r -> r.parent() == null || r.parent().isBlank()).toList();
        if (roots.isEmpty()) return new DemographicResult(0, 0);
        int changed = 0;
        Set<String> used = new HashSet<>();
        for (TitleData.Realm realm : data.realmList()) {
            Map<String, Integer> existing = parseShares(realm.props.get("races"));
            if (!existing.isEmpty() && existing.keySet().stream().allMatch(id -> raceCatalog.stream().anyMatch(r -> r.id().equals(id)))) {
                used.addAll(existing.keySet());
                if (text(realm.props.get("race")).isBlank()) {
                    String dominant = existing.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                    editor.setRealmProp(realm.id, "race", dominant);
                }
                continue;
            }
            long seed = seed(worldSeed, mapId, "demography", realm.id);
            Map<String, Integer> mix = weightedComposition(roots, seed);
            if (mix.isEmpty()) continue;
            String encoded = mix.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
            editor.setRealmProp(realm.id, "races", encoded);
            String dominant = mix.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            editor.setRealmProp(realm.id, "race", dominant);
            changed++;
            used.addAll(mix.keySet());
        }
        return new DemographicResult(changed, used.size());
    }

    /** Pure deterministic weighted sampling; exposed for logic tests and tooling. */
    public static Map<String, Integer> weightedComposition(List<RaceWeight> source, long seed) {
        List<RaceWeight> candidates = source.stream()
                .filter(r -> r != null && r.id() != null && !r.id().isBlank()
                        && (r.parent() == null || r.parent().isBlank()) && r.weight() > 0)
                .sorted(Comparator.comparing(RaceWeight::id)).toList();
        if (candidates.isEmpty()) return Map.of();
        Random random = new Random(seed);
        int count = candidates.size() == 1 ? 1 : 2 + random.nextInt(Math.min(2, candidates.size() - 1));
        List<RaceWeight> selected = new ArrayList<>();
        List<RaceWeight> pool = new ArrayList<>(candidates);
        for (int n = 0; n < count && !pool.isEmpty(); n++) {
            long total = pool.stream().mapToLong(r -> Math.max(1, r.weight())).sum();
            long roll = (long) (random.nextDouble() * total);
            RaceWeight pick = pool.get(pool.size() - 1);
            for (RaceWeight candidate : pool) {
                roll -= Math.max(1, candidate.weight());
                if (roll < 0) { pick = candidate; break; }
            }
            selected.add(pick);
            pool.remove(pick);
        }
        int[] shares = selected.size() == 1 ? new int[]{100}
                : selected.size() == 2 ? new int[]{70, 30} : new int[]{60, 25, 15};
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < selected.size(); i++) result.put(selected.get(i).id(), shares[i]);
        return Collections.unmodifiableMap(result);
    }

    /** Chooses a direct, data-pack-defined child race for an individual while preserving the realm's parent-race mix. */
    public static String chooseSubrace(String parentRace, List<RaceWeight> catalog, Random random) {
        if (parentRace == null || parentRace.isBlank() || random == null) return parentRace == null ? "" : parentRace;
        List<RaceWeight> children = catalog.stream()
                .filter(r -> r != null && parentRace.equals(r.parent()) && r.weight() > 0)
                .sorted(Comparator.comparing(RaceWeight::id)).toList();
        if (children.isEmpty() || random.nextInt(3) == 0) return parentRace;
        long total = children.stream().mapToLong(r -> Math.max(1, r.weight())).sum();
        long roll = (long) (random.nextDouble() * total);
        for (RaceWeight child : children) {
            roll -= Math.max(1, child.weight());
            if (roll < 0) return child.id();
        }
        return children.get(children.size() - 1).id();
    }

    /** Uses the optional wf-origins registry; absent addon means no guessed race variants. */
    public static String chooseSubrace(String parentRace, Random random) {
        return chooseSubrace(parentRace, originRaces(), random);
    }

    private static List<RaceWeight> originRaces() {
        try {
            Class<?> mod = Class.forName("dev.worldforge.origins.mc.OriginsMod");
            Object defs = mod.getField("DEFS").get(null);
            Object snapshot = defs.getClass().getMethod("snapshot").invoke(defs);
            Object raw = snapshot.getClass().getMethod("races").invoke(snapshot);
            if (!(raw instanceof Map<?, ?> races)) return List.of();
            List<RaceWeight> result = new ArrayList<>();
            for (Object race : races.values()) {
                Class<?> type = race.getClass();
                String id = String.valueOf(type.getMethod("id").invoke(race));
                String parent = String.valueOf(type.getMethod("parent").invoke(race));
                int weight = ((Number) type.getMethod("weight").invoke(race)).intValue();
                result.add(new RaceWeight(id, parent, weight));
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return List.of(); // wf-origins remains an optional addon; no duplicate race catalog is invented.
        }
    }

    private static Map<String, Integer> parseShares(Object raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> { if (value instanceof Number n && n.intValue() > 0) result.put(String.valueOf(key), n.intValue()); });
        } else if (raw instanceof String text) {
            for (String part : text.split(",")) {
                String[] pair = part.trim().split(":", 2);
                if (pair.length != 2) continue;
                try { int value = Integer.parseInt(pair[1].trim()); if (value > 0) result.put(pair[0].trim(), value); }
                catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }

    public static Result ensure(String mapId, TitleData data, TitleEditor editor,
                                CharacterRegistry people, NameBank names, long worldSeed) {
        Objects.requireNonNull(data, "title data");
        Objects.requireNonNull(editor, "title editor");
        Objects.requireNonNull(people, "character registry");
        Objects.requireNonNull(names, "name bank");
        return editor.group(() -> ensureBatch(mapId, data, editor, people, names, worldSeed));
    }

    private static Result ensureBatch(String mapId, TitleData data, TitleEditor editor,
                                       CharacterRegistry people, NameBank names, long worldSeed) {
        int realmsNamed = 0, titlesNamed = 0, titlesClassified = 0, rulersLinked = 0;
        Map<String, Set<String>> used = new HashMap<>();

        for (TitleData.Realm realm : data.realmList()) {
            String race = text(realm.props.get("race"));
            if (race.isBlank()) race = dominantRace(realm.props.get("races"));
            String wantedCulture = text(realm.props.get("culture"));
            String culture = names.culture(wantedCulture.isBlank() ? race : wantedCulture);
            if (!usable(realm.name, realm.id)) {
                String generated = generate(names, race, culture, "realms", seed(worldSeed, mapId, "realm", realm.id));
                editor.setRealmName(realm.id, unique(generated, "realm:" + realm.id, used));
                realmsNamed++;
            }
            if (!realm.props.containsKey("identity.culture")) editor.setRealmProp(realm.id, "identity.culture", culture);
            if (!race.isBlank() && !realm.props.containsKey("identity.race")) editor.setRealmProp(realm.id, "identity.race", race);
            String rulerPlace = Courts.realmPlace(realm.id);
            Character ruler = people.holder("ruler", rulerPlace);
            if (ruler != null && ruler.alive) {
                String globalId = ruler.globalId == null || ruler.globalId.isBlank() ? ruler.id : ruler.globalId;
                if (!globalId.equals(String.valueOf(realm.props.getOrDefault("rulerCharacterId", "")))) {
                    editor.setRealmProp(realm.id, "rulerCharacterId", globalId);
                    rulersLinked++;
                }
                if (!ruler.house.isBlank() && !realm.props.containsKey("identity.ruler_house")) editor.setRealmProp(realm.id, "identity.ruler_house", ruler.house);
                if (!ruler.house.isBlank() && !realm.props.containsKey("identity.dynasty_house")) editor.setRealmProp(realm.id, "identity.dynasty_house", ruler.house);
                if (!ruler.culture.isBlank() && !realm.props.containsKey("identity.ruler_culture")) editor.setRealmProp(realm.id, "identity.ruler_culture", ruler.culture);
                Object originRace = ruler.props.get("origin.race");
                if (originRace != null && !String.valueOf(originRace).isBlank() && !realm.props.containsKey("identity.ruler_race"))
                    editor.setRealmProp(realm.id, "identity.ruler_race", String.valueOf(originRace));
            }
        }

        List<TitleData.Title> ordered = new ArrayList<>(data.allTitles());
        ordered.sort(Comparator.comparingInt((TitleData.Title t) -> t.tier).thenComparing(t -> t.key));
        for (TitleData.Title title : ordered) {
            TitleData.Title province = title;
            while (province != null && province.tier > 0) province = province.parent > 0 ? data.get(province.parent) : null;
            TitleData.Realm realm = province == null ? null : data.realmOf(province);
            String race = inherited(title, data, "race");
            String culture = inherited(title, data, "culture");
            if (race.isBlank() && realm != null) race = text(realm.props.getOrDefault("identity.race", realm.props.get("race")));
            if (culture.isBlank() && realm != null) culture = text(realm.props.getOrDefault("identity.culture", realm.props.get("culture")));
            culture = names.culture(culture);
            if (!title.props.containsKey("identity.culture")) { editor.setProp(title.id, "identity.culture", culture); titlesClassified++; }
            if (!race.isBlank() && !title.props.containsKey("identity.race")) { editor.setProp(title.id, "identity.race", race); titlesClassified++; }

            if (!usable(title.name, title.key)) {
                String category = title.tier == 0 ? "provinces" : title.tier == 1 ? "regions" : "realms";
                String generated = title.tier >= 2 && realm != null ? realm.name
                        : generate(names, race, culture, category, seed(worldSeed, mapId, category, title.key));
                String scope = realm == null ? "unclaimed" : realm.id;
                editor.rename(title.id, unique(generated, "title:" + scope + ":" + title.key, used));
                titlesNamed++;
            }
        }
        return new Result(realmsNamed, titlesNamed, titlesClassified, rulersLinked);
    }

    private static String inherited(TitleData.Title title, TitleData data, String key) {
        for (TitleData.Title current = title; current != null;
             current = current.parent > 0 ? data.get(current.parent) : null) {
            String value = text(current.props.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String dominantRace(Object raw) {
        if (raw instanceof Map<?, ?> shares) {
            return shares.entrySet().stream()
                    .filter(e -> e.getValue() instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0)
                    .max(Comparator.comparingDouble(e -> ((Number) e.getValue()).doubleValue()))
                    .map(e -> String.valueOf(e.getKey())).orElse("");
        }
        if (raw instanceof String value) {
            String best = ""; double max = 0;
            for (String item : value.split(",")) {
                String[] pair = item.trim().split(":", 2);
                if (pair.length != 2) continue;
                try {
                    double share = Double.parseDouble(pair[1].trim());
                    if (Double.isFinite(share) && share > max) { max = share; best = pair[0].trim(); }
                } catch (NumberFormatException ignored) { }
            }
            return best;
        }
        return "";
    }

    private static String generate(NameBank names, String race, String culture, String category, long seed) {
        try { return names.generate(race, culture, category, new Random(seed)); }
        catch (IllegalStateException missingPool) {
            return names.generate("", culture, category, new Random(seed));
        }
    }

    private static String unique(String candidate, String identity, Map<String, Set<String>> used) {
        String value = candidate == null || candidate.isBlank() ? "Безымянное место" : candidate.trim();
        String[] parts = identity.split(":", 3);
        String key = parts.length >= 2 && parts[0].equals("title") ? "title:" + parts[1] : "realm";
        Set<String> names = used.computeIfAbsent(key, ignored -> new HashSet<>());
        if (names.add(value.toLowerCase(Locale.ROOT))) return value;
        String suffix = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 4);
        String distinct = value + " " + suffix;
        names.add(distinct.toLowerCase(Locale.ROOT));
        return distinct;
    }

    private static boolean usable(String name, String key) {
        return name != null && !name.isBlank() && !name.equals(key) && !name.matches("(?:province|region|state|realm)[_:-]?\\d+");
    }

    private static long seed(long worldSeed, String map, String category, String id) {
        long hash = worldSeed ^ 0x9E3779B97F4A7C15L;
        String value = String.valueOf(map) + "|" + category + "|" + id;
        for (int i = 0; i < value.length(); i++) hash = (hash ^ value.charAt(i)) * 0x100000001B3L;
        return hash;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
