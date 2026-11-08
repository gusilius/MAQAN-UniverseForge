package dev.worldforge.people;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;

import java.util.*;

/** Смерть правителя и наследование. Законы: primogeniture (старший, сыновья раньше дочерей), cognatic (старший без различия пола), ultimogeniture (младший). */
public final class Succession {
    public static final class Result {
        public final Character deceased, heir;
        public final boolean usurper;
        public final String place;

        Result(Character deceased, Character heir, boolean usurper, String place) { this.deceased = deceased; this.heir = heir; this.usurper = usurper; this.place = place; }
    }

    private Succession() {}

    public static List<Result> onDeath(CharacterRegistry reg, RoleDefs defs, TitleData d, TitleEditor ed, NameBank names, long seed, String id, int year, String law) {
        Character dead = reg.get(id);
        if (dead == null) throw new IllegalArgumentException("Нет персонажа " + id);
        List<Character.Post> posts = PopulationSimulation.die(reg, id, year, "unspecified");
        List<Result> out = new ArrayList<>();
        for (Character.Post p : posts) {
            Role role = defs.get(p.role);
            if (role == null || !role.ruler) continue;
            Character heir = chooseHeir(reg, dead, law, p.place);
            boolean usurper = false;
            if (heir == null) {
                Random r = new Random(seed ^ dead.id.hashCode() ^ p.place.hashCode());
                NameBank.Pool pool = names.pool(names.culture(dead.culture));
                boolean male = r.nextBoolean();
                heir = reg.create(pool.male.isEmpty() ? "?" : (male ? pool.male : pool.female).get(r.nextInt((male ? pool.male : pool.female).size())), pool.houses.get(r.nextInt(pool.houses.size())), dead.culture, male ? "m" : "f", year - 30);
                heir.traits.add("usurper");
                usurper = true;
            }
            reg.appoint(heir.id, role.id, p.place);
            if (ed != null && p.place.startsWith("realm:")) ed.setRuler(p.place.substring(6), heir.name + (heir.house.isEmpty() ? "" : " " + heir.house));
            out.add(new Result(dead, heir, usurper, p.place));
        }
        return out;
    }

    private static Character chooseHeir(CharacterRegistry reg, Character dead, String law, String place) {
        List<Character> kids = living(reg, dead.children);
        sort(kids, law);
        if (!kids.isEmpty()) return kids.get(0);
        List<Character> sibs = new ArrayList<>();
        for (Character c : reg.all()) if (c.alive && c != dead && !dead.father.isEmpty() && dead.father.equals(c.father)) sibs.add(c);
        sort(sibs, law);
        if (!sibs.isEmpty()) return sibs.get(0);
        List<Character> nobles = new ArrayList<>();                    // придворная знать того же места
        for (Character c : reg.all()) if (c.alive && !c.house.isEmpty() && c.posts.stream().anyMatch(po -> po.place.equals(place))) nobles.add(c);
        nobles.sort(Comparator.comparingInt((Character c) -> c.birthYear).thenComparing(c -> c.id));
        return nobles.isEmpty() ? null : nobles.get(0);
    }

    private static List<Character> living(CharacterRegistry reg, List<String> ids) {
        List<Character> l = new ArrayList<>();
        for (String id : ids) {
            Character c = reg.get(id);
            if (c != null && c.alive) l.add(c);
        }
        return l;
    }

    private static void sort(List<Character> l, String law) {
        Comparator<Character> byAge = Comparator.comparingInt((Character c) -> c.birthYear).thenComparing(c -> c.id);
        if ("ultimogeniture".equals(law)) l.sort(byAge.reversed());
        else if ("cognatic".equals(law)) l.sort(byAge);
        else l.sort(Comparator.comparingInt((Character c) -> c.gender.equals("m") ? 0 : 1).thenComparing(byAge));
    }
}
