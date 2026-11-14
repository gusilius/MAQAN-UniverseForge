package dev.worldforge.people;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.WorldForgeApi;

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
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            return onDeathBatched(reg, defs, d, ed, names, seed, id, year, law);
        }
    }

    private static List<Result> onDeathBatched(CharacterRegistry reg, RoleDefs defs, TitleData d, TitleEditor ed, NameBank names, long seed, String id, int year, String law) {
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
                CharacterStats.addTrait(heir, "usurper");
                usurper = true;
            }
            reg.appoint(heir.id, role.id, p.place);
            if (p.place.startsWith("realm:")) {
                String realmId = p.place.substring(6);
                // Succession mutates two canonical domains in one logical operation:
                // Character ownership/posts are covered by the outer Character batch,
                // while title-holder metadata and the realm ruler belong to REALMS_POLITICS.
                // Open the political writer before any map mutation so snapshots cannot
                // observe the mixed old/new state. Do not advance the lane for a no-op.
                try (DomainMutationRevisions.Mutation realmMutation =
                             WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.REALMS_POLITICS)) {
                    boolean realmChanged = false;
                    String rulerName = heir.name + (heir.house.isEmpty() ? "" : " " + heir.house);
                    TitleData.Realm realm = d.realms.get(realmId);
                    if (ed != null) {
                        realmChanged = realm != null && !Objects.equals(realm.ruler, rulerName);
                        ed.setRuler(realmId, rulerName);
                    }
                    // Keep the map's canonical title-holder references in step with the same
                    // succession result. RealmsMod imports these IDs into Statecraft on sync.
                    String deceasedGlobalId = dead.globalId;
                    String heirGlobalId = heir.globalId;
                    for (TitleData.Title title : d.allTitles()) {
                        Object holder = title.props.get("holderCharacterId");
                        if (holder instanceof String holderId
                                && (holderId.equals(deceasedGlobalId) || holderId.equals(dead.id))) {
                            realmChanged |= !Objects.equals(holder, heirGlobalId)
                                    || !Objects.equals(title.props.get("succession.last_deceased"), deceasedGlobalId)
                                    || !Objects.equals(title.props.get("succession.last_heir"), heirGlobalId)
                                    || !Objects.equals(title.props.get("succession.last_year"), year);
                            title.props.put("holderCharacterId", heirGlobalId);
                            title.props.put("succession.last_deceased", deceasedGlobalId);
                            title.props.put("succession.last_heir", heirGlobalId);
                            title.props.put("succession.last_year", year);
                        }
                    }
                    if (realmChanged) realmMutation.commit();
                }
            }
            out.add(new Result(dead, heir, usurper, p.place));
        }
        return out;
    }

    private static Character chooseHeir(CharacterRegistry reg, Character dead, String law, String place) {
        // Preserve a deceased elder child's branch through its living descendants.
        // Keep deceased children in the ordered branch list: their descendants may inherit
        // by representation. Filtering to living children here would erase the entire branch.
        List<Character> kids = new ArrayList<>();
        for (String childId : dead.children) {
            Character child = reg.get(childId);
            if (child != null) kids.add(child);
        }
        sort(kids, law);
        Set<String> visited = new HashSet<>();
        for (Character child : kids) {
            Character branchHeir = heirBranch(reg, child, law, visited);
            if (branchHeir != null) return branchHeir;
        }
        List<Character> sibs = new ArrayList<>();
        for (Character c : reg.all()) if (c.alive && c != dead && !dead.father.isEmpty() && dead.father.equals(c.father)) sibs.add(c);
        sort(sibs, law);
        if (!sibs.isEmpty()) return sibs.get(0);
        List<Character> nobles = new ArrayList<>();                    // придворная знать того же места
        for (Character c : reg.all()) if (c.alive && !c.house.isEmpty() && c.posts.stream().anyMatch(po -> po.place.equals(place))) nobles.add(c);
        nobles.sort(Comparator.comparingInt((Character c) -> c.birthYear).thenComparing(c -> c.id));
        return nobles.isEmpty() ? null : nobles.get(0);
    }

    private static Character heirBranch(CharacterRegistry reg, Character branch, String law, Set<String> visited) {
        if (branch == null || !visited.add(branch.id)) return null;
        if (branch.alive) return branch;
        // Representation applies recursively, not only to the first deceased child.
        // A dead grandchild must not erase a great-grandchild's place in the same branch.
        List<Character> descendants = new ArrayList<>();
        for (String childId : branch.children) {
            Character child = reg.get(childId);
            if (child != null) descendants.add(child);
        }
        sort(descendants, law);
        for (Character descendant : descendants) {
            Character heir = heirBranch(reg, descendant, law, visited);
            if (heir != null) return heir;
        }
        return null;
    }

    private static void sort(List<Character> l, String law) {
        Comparator<Character> byAge = Comparator.comparingInt((Character c) -> c.birthYear).thenComparing(c -> c.id);
        if ("ultimogeniture".equals(law)) l.sort(byAge.reversed());
        else if ("cognatic".equals(law)) l.sort(byAge);
        else l.sort(Comparator.comparingInt((Character c) -> c.gender.equals("m") ? 0 : 1).thenComparing(byAge));
    }
}
