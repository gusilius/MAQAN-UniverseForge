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
            String culture = names.culture(text(realm.props.get("culture")));
            if (race.isBlank()) race = dominantRace(realm.props.get("races"));
            if (culture.isBlank()) culture = names.culture("");
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
                String category = title.tier == 0 ? "provinces" : "regions";
                String generated = generate(names, race, culture, category,
                        seed(worldSeed, mapId, category, title.key));
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
