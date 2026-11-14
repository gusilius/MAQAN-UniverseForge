package dev.worldforge.people;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;

import java.util.*;

/**
 * Заполняет карту людьми по ролям: у каждого государства правитель с семьёй, канцлер, маршал и так далее; у каждой деревни жители.
 * Детерминированно: тот же seed даёт тех же людей; повторный вызов ничего не дублирует и только заполняет пустые места
 * (например, после смерти канцлера).
 */
public final class Courts {
    private Courts() {}

    public static String realmPlace(String id) { return "realm:" + id; }

    public static String titlePlace(int id) { return "title:" + id; }

    private static long mix(long seed, String a, String b, int i) {
        long h = seed * 0x9E3779B97F4A7C15L;
        for (char c : (a + "|" + b + "|" + i).toCharArray()) h = (h ^ c) * 0x100000001B3L;
        return h;
    }

    /** Возвращает число созданных персонажей. ed может быть null (тогда правитель в данных государства не меняется). */
    public static int ensure(TitleData d, TitleEditor ed, CharacterRegistry reg, RoleDefs defs, NameBank names, long seed, int year) {
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            return ensureBatched(d, ed, reg, defs, names, seed, year);
        }
    }

    private static int ensureBatched(TitleData d, TitleEditor ed, CharacterRegistry reg, RoleDefs defs, NameBank names, long seed, int year) {
        int created = 0;
        // Only title offices inside an actually owned realm belong to the living political
        // hierarchy. Geography-only maps still contain thousands of unowned barony/county
        // records; filling offices for every one of them creates a phantom population before
        // any polity has claimed territory.
        Set<Integer> realmOwnedTitles = realmOwnedTitleIds(d);
        // Courts.ensure may run during world emergence. Build the role/place counts once:
        // CharacterRegistry.holders() scans the complete population, so calling it for every
        // role and every title made this pass O(roles * places * population).
        Map<String, Integer> holderCounts = new HashMap<>();
        Set<String> usedHouses = new HashSet<>();
        for (Character character : reg.all()) {
            if (!character.house.isEmpty()) usedHouses.add(character.house);
            if (!character.alive) continue;
            for (Character.Post post : character.posts) {
                holderCounts.merge(holderKey(post.role, post.place), 1, Integer::sum);
            }
        }
        for (Role role : defs.all()) {
            List<String[]> places = new ArrayList<>();             // {place, culture, label, race}
            if (role.per.equals("realm")) {
                for (TitleData.Realm r : d.realmList()) places.add(new String[]{realmPlace(r.id), String.valueOf(r.props.getOrDefault("culture", "")), r.id, String.valueOf(r.props.getOrDefault("race", ""))});
            } else {
                int tier = -1;
                for (TitleData.Tier t : d.tiers) if (t.key.equals(role.per)) tier = t.index;
                if (tier < 0) throw new IllegalArgumentException("Роль " + role.id + ": нет уровня титулов '" + role.per + "'");
                for (TitleData.Title t : d.allTitles())
                    if (t.tier == tier && realmOwnedTitles.contains(t.id))
                        places.add(new String[]{titlePlace(t.id), cultureOf(d, t), String.valueOf(t.id), raceOf(d, t)});
            }
            for (String[] pl : places) {
                String key = holderKey(role.id, pl[0]);
                int have = holderCounts.getOrDefault(key, 0);
                for (int i = have; i < role.count; i++) {
                    create(d, ed, reg, defs, names, role, pl[0], pl[1], pl[2], pl[3],
                            new Random(mix(seed, pl[0], role.id, i)), year, usedHouses);
                    holderCounts.merge(key, 1, Integer::sum);
                    created++;
                }
            }
        }
        return created;
    }

    /**
     * Returns title IDs that lie on the parent chain of at least one province owned by
     * a canonical realm. TitleData.realmOf accepts province IDs only, so derive the
     * ancestor set from owned tier-0 provinces in one pass.
     */
    private static Set<Integer> realmOwnedTitleIds(TitleData data) {
        Set<Integer> owned = new HashSet<>();
        for (TitleData.Title province : data.allTitles()) {
            if (province.tier != 0 || data.realmOf(province) == null) continue;
            for (TitleData.Title current = province; current != null;
                 current = current.parent > 0 ? data.get(current.parent) : null) {
                owned.add(current.id);
            }
        }
        return owned;
    }

    private static String holderKey(String role, String place) {
        return role + "#" + place;
    }

    private static String cultureOf(TitleData d, TitleData.Title t) {
        for (TitleData.Title c = t; c != null; c = c.parent > 0 ? d.get(c.parent) : null) {
            Object v = c.props.get("culture");
            if (v != null) return String.valueOf(v);
        }
        return "";
    }

    private static String raceOf(TitleData d, TitleData.Title t) {
        for (TitleData.Title current = t; current != null; current = current.parent > 0 ? d.get(current.parent) : null) {
            Object value = current.props.get("race");
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static String pick(List<String> l, Random r) { return l.get(r.nextInt(l.size())); }

    private static String unusedHouse(NameBank.Pool pool, Random r, Set<String> used) {
        List<String> free = new ArrayList<>();
        for (String house : pool.houses) if (!used.contains(house)) free.add(house);
        if (!free.isEmpty()) {
            String selected = pick(free, r);
            used.add(selected);
            return selected;
        }
        String base = pick(pool.houses, r);
        for (int n = 2; ; n++) {
            String candidate = base + " (ветвь " + n + ")";
            if (used.add(candidate)) return candidate;
        }
    }

    private static void create(TitleData d, TitleEditor ed, CharacterRegistry reg, RoleDefs defs, NameBank names, Role role, String place, String wantedCulture, String label, String race, Random r, int year, Set<String> usedHouses) {
        String culture = names.culture(wantedCulture);
        NameBank.Pool pool = names.poolForRace(race, culture);
        boolean male = r.nextInt(100) < (role.ruler ? 75 : 50);
        String first = pick(male ? pool.male : pool.female, r);
        String house = role.ruler ? unusedHouse(pool, r, usedHouses) : role.noble ? pick(pool.houses, r) : "";
        if (!house.isEmpty()) usedHouses.add(house);
        int age = role.ruler ? 25 + r.nextInt(31) : 20 + r.nextInt(41);
        TitleData.Realm realm = place.startsWith("realm:") ? d.realms.get(place.substring(6)) : null;
        String preset = realm != null && role.ruler && realm.ruler != null && !realm.ruler.isBlank() ? realm.ruler : null;   // правитель, заданный вручную, остаётся
        Character c = reg.create(preset != null ? preset : first, house, culture, male ? "m" : "f", year - age);
        if (!race.isBlank()) c.props.put("origin.race", race);
        if (house.isEmpty() && !pool.surnames.isEmpty()) c.props.put("identity.surname", pick(pool.surnames, r));
        reg.appoint(c.id, role.id, place);
        if (role.ruler && realm != null && preset == null && ed != null) ed.setRuler(realm.id, c.name + (house.isEmpty() ? "" : " " + house));
        if (role.family) makeFamily(reg, pool, c, year, r, race, usedHouses);
    }

    private static void makeFamily(CharacterRegistry reg, NameBank.Pool pool, Character head, int year, Random r, String race, Set<String> usedHouses) {
        boolean headMale = head.gender.equals("m");
        String spouseHouse;
        do { spouseHouse = pick(pool.houses, r); } while (spouseHouse.equals(head.house) && pool.houses.size() > 1);
        usedHouses.add(spouseHouse);
        Character sp = reg.create(pick(headMale ? pool.female : pool.male, r), spouseHouse, head.culture, headMale ? "f" : "m", head.birthYear + r.nextInt(9) - 4);
        if (!race.isBlank()) sp.props.put("origin.race", race);
        if (spouseHouse.isEmpty() && !pool.surnames.isEmpty()) sp.props.put("identity.surname", pick(pool.surnames, r));
        reg.marry(head.id, sp.id);
        int age = year - head.birthYear;
        int kids = age < 20 ? 0 : r.nextInt(4);
        Character father = headMale ? head : sp, mother = headMale ? sp : head;
        for (int i = 0; i < kids; i++) {
            int lo = Math.max(father.birthYear, mother.birthYear) + 18, born = lo + r.nextInt(Math.max(1, year - lo + 1));
            if (born > year) break;
            boolean m = r.nextBoolean();
            Character child = reg.addChild(father, mother, pick(m ? pool.male : pool.female, r), m ? "m" : "f", born);
            if (!child.house.isEmpty()) usedHouses.add(child.house);
            if (!race.isBlank()) child.props.put("origin.race", race);
            if (child.house.isEmpty() && !pool.surnames.isEmpty()) child.props.put("identity.surname", father.props.getOrDefault("identity.surname", pick(pool.surnames, r)));
        }
    }
}
