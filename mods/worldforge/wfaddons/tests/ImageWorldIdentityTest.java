package dev.worldforge.tests;

import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.origins.OriginDefs;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Courts;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.RoleDefs;
import dev.worldforge.people.TerritorialIdentity;
import dev.worldforge.realms.Statecraft;
import dev.worldforge.realms.RealmDefs;
import dev.worldforge.realms.Realms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** End-to-end identity pass over the actual image-generated .worldmap supplied by CI. */
public final class ImageWorldIdentityTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    private static Map<String, Double> decimalShares(Object raw) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (raw instanceof String text) for (String part : text.split(",")) {
            String[] pair = part.trim().split(":", 2);
            if (pair.length != 2) continue;
            try { double value = Double.parseDouble(pair[1].trim()); if (Double.isFinite(value) && value > 0) result.put(pair[0].trim(), value); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private static Map<String, Integer> shares(Object raw) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (raw instanceof String text) for (String part : text.split(",")) {
            String[] pair = part.trim().split(":", 2);
            if (pair.length != 2) continue;
            try { result.put(pair[0].trim(), Integer.parseInt(pair[1].trim())); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ImageWorldIdentityTest real-map.worldmap");
        Path archive = Path.of(args[0]).toAbsolutePath();
        MapData map = MapData.load("real-map", archive.getParent());
        TitleData titles = map.titles;
        if (titles == null) throw new AssertionError("Actual image map has no political titles");

        Path originsDir = Path.of("wf-origins/src/main/resources/data/wf_origins/wf_origins");
        OriginDefs originDefs = new OriginDefs();
        try (var files = Files.list(originsDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                originDefs.load(Files.readString(file));
        }
        List<String> originProblems = originDefs.validate();
        check(originProblems.isEmpty(), "wf-origins datapack definitions validate: " + originDefs.snapshot().races().size() + " races");
        List<TerritorialIdentity.RaceWeight> raceCatalog = originDefs.snapshot().races().values().stream()
                .map(r -> new TerritorialIdentity.RaceWeight(r.id(), r.parent(), r.weight())).toList();

        NameBank names = new NameBank();
        names.add(Files.readString(Path.of("wf-people/src/main/resources/data/wf_people/wf_names/base.json")));
        RoleDefs roles = new RoleDefs();
        Path roleDir = Path.of("wf-people/src/main/resources/data/wf_people/wf_roles");
        try (var files = Files.list(roleDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                roles.add(Files.readString(file));
        }

        TitleEditor editor = new TitleEditor(titles);
        long seed = 424242L;
        TerritorialIdentity.DemographicResult demographics =
                TerritorialIdentity.ensureDemographics(map.name, titles, editor, seed, raceCatalog);
        CharacterRegistry people = new CharacterRegistry();
        int created = Courts.ensure(titles, editor, people, roles, names, seed, 300);
        TerritorialIdentity.Result identity =
                TerritorialIdentity.ensure(map.name, titles, editor, people, names, seed);

        check(demographics.realmsClassified() == titles.realmList().size(),
                "every generated state receives an origin-backed population mix");
        Set<String> knownRaces = originDefs.snapshot().races().keySet();
        for (TitleData.Realm realm : titles.realmList()) {
            Map<String, Integer> mix = shares(realm.props.get("races"));
            check(!realm.name.isBlank() && !mix.isEmpty()
                            && mix.keySet().stream().allMatch(knownRaces::contains)
                            && mix.values().stream().mapToInt(Integer::intValue).sum() == 100,
                    "named state and valid 100% race composition: " + realm.id);
            Character ruler = people.holder("ruler", Courts.realmPlace(realm.id));
            check(ruler != null && ruler.alive && !ruler.globalId.isBlank()
                            && !ruler.name.isBlank() && !ruler.house.isBlank()
                            && knownRaces.contains(String.valueOf(ruler.props.get("origin.race")))
                            && ruler.globalId.equals(String.valueOf(realm.props.get("rulerCharacterId"))),
                    "canonical ruler, race, named house and Character ID link: " + realm.id);
        }

        // Run the same data-driven AI government selection used by wf-realms at world start.
        RealmDefs realmDefs = new RealmDefs();
        Path realmDefsDir = Path.of("wf-realms/src/main/resources/data/wf_realms/wf_realms");
        try (var files = Files.list(realmDefsDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                realmDefs.load(Files.readString(file));
        }
        check(realmDefs.validate().isEmpty() && realmDefs.govs.size() >= 10
                        && realmDefs.terrains.size() >= 6,
                "real government, terrain, level, doctrine and law definitions load");
        Map<String, Map<String, Double>> realmRaces = new HashMap<>(), realmTerrain = new HashMap<>();
        for (TitleData.Realm realm : titles.realmList()) {
            realmRaces.put(realm.id, decimalShares(realm.props.get("races")));
            realmTerrain.put(realm.id, decimalShares(realm.props.get("terrain")));
            check(!realmTerrain.get(realm.id).isEmpty()
                            && Math.abs(realmTerrain.get(realm.id).values().stream().mapToDouble(Double::doubleValue).sum() - 1.0) < 0.02,
                    "real image-derived terrain mix reaches government suitability: " + realm.id);
        }
        Realms autonomousPolitics = new Realms(realmDefs);
        autonomousPolitics.hooks = new Realms.Hooks() {
            @Override public int provinces(String id) {
                TitleData.Realm realm = titles.realms.get(id);
                if (realm == null) return 0;
                int count = 0;
                for (TitleData.Title title : titles.allTitles())
                    if (title.tier == 0 && titles.realmOf(title) != null
                            && id.equals(titles.realmOf(title).id)) count++;
                return count;
            }
            @Override public int prestige(String id) { return 0; }
            @Override public Map<String, Double> races(String id) { return realmRaces.getOrDefault(id, Map.of()); }
            @Override public Map<String, Double> terrain(String id) { return realmTerrain.getOrDefault(id, Map.of()); }
        };
        Map<String, String> chosenGovernments = new TreeMap<>();
        for (TitleData.Realm realm : titles.realmList()) {
            autonomousPolitics.autoDevelop(realm.id, seed ^ realm.id.hashCode());
            String government = autonomousPolitics.state(realm.id).gov;
            check(!government.isBlank() && realmDefs.govs.containsKey(government)
                            && autonomousPolitics.why(realm.id, realmDefs.govs.get(government)).isEmpty(),
                    "AI chooses a compatible data-driven government: " + realm.id);
            chosenGovernments.put(realm.id, government);
            editor.setRealmProp(realm.id, "politics.government", government);
            editor.setRealmProp(realm.id, "politics.government_name", realmDefs.govs.get(government).name());
            editor.setRealmProp(realm.id, "politics.succession", realmDefs.govs.get(government).succession());
        }

        int ownedTitles = 0;
        boolean allTerritoriesNamed = true, allOwnedTerritoriesClassified = true;
        for (TitleData.Title title : titles.allTitles()) {
            allTerritoriesNamed &= title.name != null && !title.name.isBlank();
            TitleData.Title province = title;
            while (province != null && province.tier > 0)
                province = province.parent > 0 ? titles.get(province.parent) : null;
            TitleData.Realm realm = province == null ? null : titles.realmOf(province);
            if (realm != null) {
                ownedTitles++;
                allOwnedTerritoriesClassified &= title.props.containsKey("identity.race")
                        && title.props.containsKey("identity.culture");
            }
        }
        check(allTerritoriesNamed, "all provinces, regions and state titles have stable names");
        check(ownedTitles > 0 && allOwnedTerritoriesClassified,
                "all owned administrative territories inherit race and culture identity");
        check(created > 0 && people.all().stream().anyMatch(c -> !c.spouse.isBlank() || !c.children.isEmpty()),
                "Courts creates canonical rulers and family relationships");

        Map<String, List<Character>> houses = new TreeMap<>();
        for (Character person : people.all()) if (!person.house.isBlank())
            houses.computeIfAbsent(person.house, ignored -> new ArrayList<>()).add(person);
        Statecraft politics = new Statecraft();
        Set<String> dynastyIds = new HashSet<>();
        for (var entry : houses.entrySet()) {
            Character founder = entry.getValue().stream()
                    .min(Comparator.comparingInt(c -> c.birthYear)).orElseThrow();
            String dynastyId = Statecraft.stableDynastyId(map.name, founder.culture, entry.getKey());
            String race = String.valueOf(founder.props.getOrDefault("origin.race", ""));
            String dynastyName;
            try { dynastyName = names.generate(race, founder.culture, "dynasties",
                    new Random(seed ^ dynastyId.hashCode())); }
            catch (RuntimeException missingPool) { dynastyName = "Дом " + entry.getKey(); }
            Statecraft.Dynasty dynasty = politics.registerDynasty(dynastyId, founder.globalId, dynastyName);
            for (Character member : entry.getValue()) politics.addDynastyMember(dynastyId, member.globalId);
            dynastyIds.add(dynastyId);
            check(!dynasty.name.isBlank() && dynasty.members.size() == entry.getValue().size(),
                    "named dynasty links every canonical house member: " + entry.getKey());
        }
        String peopleSnapshot = people.toJson();
        CharacterRegistry restoredPeople = new CharacterRegistry();
        restoredPeople.fromJson(peopleSnapshot, "image-world-identity-test");
        String politicsSnapshot = politics.toJson();
        Statecraft restoredPolitics = new Statecraft();
        restoredPolitics.fromJson(politicsSnapshot);
        check(restoredPeople.size() == people.size(),
                "canonical Character registry survives identity save/load");
        check(restoredPolitics.toJson().equals(politicsSnapshot)
                        && dynastyIds.stream().allMatch(id -> restoredPolitics.dynasty(id) != null
                        && !restoredPolitics.dynasty(id).name.isBlank()),
                "named houses and dynasties survive political save/load");

        System.out.println("Image identity summary: realms=" + titles.realmList().size()
                + ", territories=" + titles.allTitles().size() + ", owned=" + ownedTitles
                + ", settlements=" + map.settlementSeeds.size() + ", characters=" + people.size()
                + ", houses=" + houses.size() + ", dynasties=" + dynastyIds.size()
                + ", governments=" + chosenGovernments.values().stream().distinct().count()
                + ", namedTitles=" + identity.titlesNamed());
        System.out.println(failures == 0 ? "IMAGE WORLD IDENTITY: ALL OK" : "IMAGE WORLD IDENTITY FAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }
}
