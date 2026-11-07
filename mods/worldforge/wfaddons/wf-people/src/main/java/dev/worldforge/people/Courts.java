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
        int created = 0;
        for (Role role : defs.all()) {
            List<String[]> places = new ArrayList<>();             // {place, culture, label}
            if (role.per.equals("realm")) {
                for (TitleData.Realm r : d.realmList()) places.add(new String[]{realmPlace(r.id), String.valueOf(r.props.getOrDefault("culture", "")), r.id});
            } else {
                int tier = -1;
                for (TitleData.Tier t : d.tiers) if (t.key.equals(role.per)) tier = t.index;
                if (tier < 0) throw new IllegalArgumentException("Роль " + role.id + ": нет уровня титулов '" + role.per + "'");
                for (TitleData.Title t : d.allTitles()) if (t.tier == tier) places.add(new String[]{titlePlace(t.id), cultureOf(d, t), String.valueOf(t.id)});
            }
            for (String[] pl : places) {
                int have = reg.holders(role.id, pl[0]).size();
                for (int i = have; i < role.count; i++) {
                    create(d, ed, reg, defs, names, role, pl[0], pl[1], pl[2], new Random(mix(seed, pl[0], role.id, i)), year);
                    created++;
                }
            }
        }
        return created;
    }

    private static String cultureOf(TitleData d, TitleData.Title t) {
        for (TitleData.Title c = t; c != null; c = c.parent > 0 ? d.get(c.parent) : null) {
            Object v = c.props.get("culture");
            if (v != null) return String.valueOf(v);
        }
        return "";
    }

    private static String pick(List<String> l, Random r) { return l.get(r.nextInt(l.size())); }

    private static String unusedHouse(CharacterRegistry reg, NameBank.Pool pool, Random r) {
        Set<String> used = new HashSet<>();
        for (Character c : reg.all()) if (!c.house.isEmpty()) used.add(c.house);
        List<String> free = new ArrayList<>();
        for (String h : pool.houses) if (!used.contains(h)) free.add(h);
        if (!free.isEmpty()) return pick(free, r);
        String base = pick(pool.houses, r);                      // имена кончились: побочная ветвь известного дома
        for (int n = 2; ; n++) {
            String cand = base + " (ветвь " + n + ")";
            if (!used.contains(cand)) return cand;
        }
    }

    private static void create(TitleData d, TitleEditor ed, CharacterRegistry reg, RoleDefs defs, NameBank names, Role role, String place, String wantedCulture, String label, Random r, int year) {
        String culture = names.culture(wantedCulture);
        NameBank.Pool pool = names.pool(culture);
        boolean male = r.nextInt(100) < (role.ruler ? 75 : 50);
        String first = pick(male ? pool.male : pool.female, r);
        String house = role.ruler ? unusedHouse(reg, pool, r) : role.noble ? pick(pool.houses, r) : "";
        int age = role.ruler ? 25 + r.nextInt(31) : 20 + r.nextInt(41);
        TitleData.Realm realm = place.startsWith("realm:") ? d.realms.get(place.substring(6)) : null;
        String preset = realm != null && role.ruler && realm.ruler != null && !realm.ruler.isBlank() ? realm.ruler : null;   // правитель, заданный вручную, остаётся
        Character c = reg.create(preset != null ? preset : first, house, culture, male ? "m" : "f", year - age);
        reg.appoint(c.id, role.id, place);
        if (role.ruler && realm != null && preset == null && ed != null) ed.setRuler(realm.id, c.name + (house.isEmpty() ? "" : " " + house));
        if (role.family) makeFamily(reg, pool, c, year, r);
    }

    private static void makeFamily(CharacterRegistry reg, NameBank.Pool pool, Character head, int year, Random r) {
        boolean headMale = head.gender.equals("m");
        String spouseHouse;
        do { spouseHouse = pick(pool.houses, r); } while (spouseHouse.equals(head.house) && pool.houses.size() > 1);
        Character sp = reg.create(pick(headMale ? pool.female : pool.male, r), spouseHouse, head.culture, headMale ? "f" : "m", head.birthYear + r.nextInt(9) - 4);
        reg.marry(head.id, sp.id);
        int age = year - head.birthYear;
        int kids = age < 20 ? 0 : r.nextInt(4);
        Character father = headMale ? head : sp, mother = headMale ? sp : head;
        for (int i = 0; i < kids; i++) {
            int lo = Math.max(father.birthYear, mother.birthYear) + 18, born = lo + r.nextInt(Math.max(1, year - lo + 1));
            if (born > year) break;
            boolean m = r.nextBoolean();
            reg.addChild(father, mother, pick(m ? pool.male : pool.female, r), m ? "m" : "f", born);
        }
    }
}
