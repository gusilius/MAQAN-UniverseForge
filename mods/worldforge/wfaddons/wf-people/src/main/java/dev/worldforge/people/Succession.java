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

    private static List<Result> onDeathBatched(CharacterRegistry reg, RoleDefs defs, TitleData d,
                                                    TitleEditor ed, NameBank names, long seed,
                                                    String id, int year, String law) {
        try (CharacterAccessService.Lease deadLease = reg.acquire(id)) {
            if (deadLease == null) throw new IllegalArgumentException("Нет персонажа " + id);
            Character dead = deadLease.get();
            Candidate deadSnapshot = snapshot(dead);
            List<Character.Post> posts = PopulationSimulation.die(reg, id, year, "unspecified");
            List<Result> out = new ArrayList<>();
            for (Character.Post post : posts) {
                Role role = defs.get(post.role);
                if (role == null || !role.ruler) continue;
                String heirId = chooseHeirId(reg, deadSnapshot, law, post.place);
                boolean usurper = heirId == null;
                if (usurper) {
                    Random random = new Random(seed ^ dead.id.hashCode() ^ post.place.hashCode());
                    NameBank.Pool pool = names.pool(names.culture(dead.culture));
                    boolean male = random.nextBoolean();
                    List<String> namesForGender = male ? pool.male : pool.female;
                    String name = namesForGender.isEmpty() ? "?" : namesForGender.get(random.nextInt(namesForGender.size()));
                    String house = pool.houses.isEmpty() ? "" : pool.houses.get(random.nextInt(pool.houses.size()));
                    heirId = reg.create(name, house, dead.culture, male ? "m" : "f", year - 30).id;
                }
                try (CharacterAccessService.Lease heirLease = reg.acquire(heirId)) {
                    if (heirLease == null) throw new IllegalStateException("Не удалось восстановить наследника " + heirId);
                    Character heir = heirLease.get();
                    if (usurper) CharacterStats.addTrait(heir, "usurper");
                    reg.appoint(heir.id, role.id, post.place);
                    final Character successor = heir;
                    if (post.place.startsWith("realm:")) {
                        String realmId = post.place.substring(6);
                        Runnable updatePoliticalState = () -> {
                            String rulerName = successor.name + (successor.house.isEmpty() ? "" : " " + successor.house);
                            if (ed != null) ed.setRuler(realmId, rulerName);
                            String deceasedGlobalId = dead.globalId;
                            String heirGlobalId = successor.globalId;
                            for (TitleData.Title title : d.allTitles()) {
                                Object holder = title.props.get("holderCharacterId");
                                if (holder instanceof String holderId
                                        && (holderId.equals(deceasedGlobalId) || holderId.equals(dead.id))) {
                                    title.props.put("holderCharacterId", heirGlobalId);
                                    title.props.put("succession.last_deceased", deceasedGlobalId);
                                    title.props.put("succession.last_heir", heirGlobalId);
                                    title.props.put("succession.last_year", year);
                                }
                            }
                        };
                        if (ed != null) {
                            ed.group(updatePoliticalState);
                        } else {
                            try (DomainMutationRevisions.Mutation realmMutation =
                                         WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.REALMS_POLITICS)) {
                                TitleData.Realm realm = d.realms.get(realmId);
                                String rulerName = heir.name + (heir.house.isEmpty() ? "" : " " + heir.house);
                                boolean changed = realm != null && !Objects.equals(realm.ruler, rulerName);
                                for (TitleData.Title title : d.allTitles()) {
                                    Object holder = title.props.get("holderCharacterId");
                                    if (holder instanceof String holderId
                                            && (holderId.equals(dead.globalId) || holderId.equals(dead.id))) {
                                        changed |= !Objects.equals(holder, heir.globalId)
                                                || !Objects.equals(title.props.get("succession.last_deceased"), dead.globalId)
                                                || !Objects.equals(title.props.get("succession.last_heir"), heir.globalId)
                                                || !Objects.equals(title.props.get("succession.last_year"), year);
                                    }
                                }
                                updatePoliticalState.run();
                                if (changed) realmMutation.commit();
                            }
                        }
                    }
                    out.add(new Result(dead, heir, usurper, post.place));
                }
            }
            return out;
        }
    }

    private record Candidate(String id, String gender, int birthYear, boolean alive,
                             String father, String house, List<String> children) {}

    private static Candidate snapshot(Character character) {
        return new Candidate(character.id, character.gender, character.birthYear, character.alive,
                character.father == null ? "" : character.father,
                character.house == null ? "" : character.house,
                List.copyOf(character.children));
    }

    private static Candidate readCandidate(CharacterRegistry registry, String localId) {
        if (localId == null || localId.isBlank()) return null;
        try (CharacterAccessService.Lease lease = registry.acquire(localId)) {
            return lease == null ? null : snapshot(lease.get());
        }
    }

    private static String chooseHeirId(CharacterRegistry reg, Candidate dead, String law, String place) {
        // Preserve a deceased elder child's branch through living descendants.
        List<Candidate> children = new ArrayList<>();
        for (String childId : dead.children()) {
            Candidate child = readCandidate(reg, childId);
            if (child != null) children.add(child);
        }
        sortCandidates(children, law);
        Set<String> visited = new HashSet<>();
        for (Candidate child : children) {
            String branchHeir = heirBranchId(reg, child, law, visited);
            if (branchHeir != null) return branchHeir;
        }

        // Siblings are resolved through the father's canonical child IDs; no Character list survives.
        if (!dead.father().isBlank()) {
            Candidate father = readCandidate(reg, dead.father());
            if (father != null) {
                List<Candidate> siblings = new ArrayList<>();
                for (String siblingId : father.children()) {
                    if (siblingId.equals(dead.id())) continue;
                    Candidate sibling = readCandidate(reg, siblingId);
                    if (sibling != null && sibling.alive() && dead.father().equals(sibling.father()))
                        siblings.add(sibling);
                }
                sortCandidates(siblings, law);
                if (!siblings.isEmpty()) return siblings.get(0).id();
            }
        }

        // Fallback to living nobles at this title; IDs are read from the reverse post index.
        Candidate best = null;
        for (String holderId : reg.idsAtPlace(place)) {
            Candidate holder = readCandidate(reg, holderId);
            if (holder == null || !holder.alive() || holder.house().isBlank()) continue;
            if (best == null || compareCandidates(holder, best, law) < 0) best = holder;
        }
        return best == null ? null : best.id();
    }

    private static String heirBranchId(CharacterRegistry reg, Candidate branch, String law,
                                       Set<String> visited) {
        if (branch == null || !visited.add(branch.id())) return null;
        if (branch.alive()) return branch.id();
        List<Candidate> descendants = new ArrayList<>();
        for (String childId : branch.children()) {
            Candidate child = readCandidate(reg, childId);
            if (child != null) descendants.add(child);
        }
        sortCandidates(descendants, law);
        for (Candidate descendant : descendants) {
            String heir = heirBranchId(reg, descendant, law, visited);
            if (heir != null) return heir;
        }
        return null;
    }

    private static void sortCandidates(List<Candidate> candidates, String law) {
        candidates.sort((a, b) -> compareCandidates(a, b, law));
    }

    private static int compareCandidates(Candidate a, Candidate b, String law) {
        int age = Integer.compare(a.birthYear(), b.birthYear());
        if ("ultimogeniture".equals(law)) age = -age;
        else if (!"cognatic".equals(law)) {
            int gender = Integer.compare(a.gender().equals("m") ? 0 : 1,
                    b.gender().equals("m") ? 0 : 1);
            if (gender != 0) return gender;
        }
        return age != 0 ? age : a.id().compareTo(b.id());
    }

}
