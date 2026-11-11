package dev.worldforge.settle;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;

import java.util.*;

/**
 * Deterministic yearly family/social event pass for the canonical Character registry.
 * It never creates a second resident identity and records its year markers on Character props.
 */
public final class ResidentLifeEvents {
    private ResidentLifeEvents() {}

    public static void yearly(CharacterRegistry registry, int year) {
        if (registry == null || year < 1) return;
        List<Character> people = new ArrayList<>(registry.all());
        people.sort(Comparator.comparing(c -> c.id));
        // A settlement may call this pass every simulation step. Refresh the full registry only
        // once per world year, not once per colony tick.
        if (people.isEmpty() || number(people.get(0).props.get("population.life_stage_refresh_year"), 0) < year) {
            registry.refreshLifeStages(year);
            for (Character person : people) person.props.put("population.life_stage_refresh_year", year);
        }
        for (Character person : people) {
            if (!person.alive || year <= number(person.props.get("social.last_family_year"), 0)) continue;
            person.props.put("social.last_family_year", year);
            Character spouse = person.spouse == null || person.spouse.isBlank() ? null : registry.get(person.spouse);
            if (spouse == null || !spouse.alive || !person.id.equals(spouse.spouse)) continue;
            Character mother = "f".equalsIgnoreCase(person.gender) ? person : "f".equalsIgnoreCase(spouse.gender) ? spouse : null;
            Character father = mother == person ? spouse : mother == spouse ? person : null;
            if (mother == null || father == null) continue;
            int motherAge = PopulationSimulation.age(mother, year);
            int fatherAge = PopulationSimulation.age(father, year);
            if (motherAge < 18 || motherAge > 42 || fatherAge < 18 || fatherAge > 65) continue;
            if (mother.children.size() >= 5 || father.children.size() >= 5) continue;
            int lastBirth = number(mother.props.get("family.last_birth_year"), 0);
            if (lastBirth > 0 && year - lastBirth < 2) continue;
            if (Math.floorMod(Objects.hash(mother.id, father.id, year, "birth"), 7) != 0) continue;
            String gender = Math.floorMod(Objects.hash(mother.id, father.id, year, "gender"), 2) == 0 ? "f" : "m";
            String childName = "Дитя семьи " + (mother.house == null || mother.house.isBlank() ? mother.name : mother.house);
            Character child = registry.addChild(father, mother, childName, gender, year);
            child.traits.add("curious");
            child.props.put("family.father_name", father.name);
            child.props.put("family.mother_name", mother.name);
            ResidentSocial.adjust(father, child.id, 35);
            ResidentSocial.adjust(mother, child.id, 35);
            ResidentSocial.adjust(child, father.id, 35);
            ResidentSocial.adjust(child, mother.id, 35);
            mother.props.put("family.last_birth_year", year);
            father.props.put("family.last_birth_year", year);
            mother.props.put("family.last_event", "child-born:" + child.id);
            father.props.put("family.last_event", "child-born:" + child.id);
        }
        advanceChildhood(registry, people, year);

        // Marital conflict is persistent and reciprocal; one bad day does not dissolve a family.
        for (Character person : people) {
            if (!person.alive || person.spouse == null || person.spouse.isBlank()
                    || person.id.compareTo(person.spouse) >= 0) continue;
            Character spouse = registry.get(person.spouse);
            if (spouse == null || !spouse.alive || !person.id.equals(spouse.spouse)) continue;
            int affinity = Math.min(ResidentSocial.score(person, spouse.id), ResidentSocial.score(spouse, person.id));
            if (year <= number(person.props.get("family.marital_review_year"), 0)) continue;
            person.props.put("family.marital_review_year", year);
            spouse.props.put("family.marital_review_year", year);
            int conflictYears = number(person.props.get("family.marital_conflict_years"), 0);
            conflictYears = affinity <= -55 ? conflictYears + 1 : Math.max(0, conflictYears - 1);
            person.props.put("family.marital_conflict_years", conflictYears);
            spouse.props.put("family.marital_conflict_years", conflictYears);
            person.props.put("family.marital_affinity", affinity);
            spouse.props.put("family.marital_affinity", affinity);
            if (conflictYears >= 3 && registry.divorce(person.id, spouse.id, year)) {
                person.props.put("family.last_event", "divorced:" + spouse.id);
                spouse.props.put("family.last_event", "divorced:" + person.id);
                person.props.put("family.separation_year", year);
                spouse.props.put("family.separation_year", year);
            }
        }

        for (int i = 0; i < people.size(); i++) {
            Character first = people.get(i);
            if (!first.alive || first.spouse != null && !first.spouse.isBlank()
                    || PopulationSimulation.age(first, year) < 18
                    || year <= number(first.props.get("family.relationship_review_year"), 0)) continue;
            first.props.put("family.relationship_review_year", year);
            for (int j = i + 1; j < people.size(); j++) {
                Character second = people.get(j);
                if (!second.alive || second.spouse != null && !second.spouse.isBlank()
                        || PopulationSimulation.age(second, year) < 18 || closeFamily(registry, first, second)) continue;
                int affinity = Math.min(ResidentSocial.score(first, second.id), ResidentSocial.score(second, first.id));
                String firstDating = string(first.props.get("family.dating"));
                String secondDating = string(second.props.get("family.dating"));
                boolean mutualDating = second.id.equals(firstDating) && first.id.equals(secondDating);
                if (mutualDating && affinity < 20) {
                    first.props.remove("family.dating");
                    second.props.remove("family.dating");
                    first.props.remove("family.dating_name");
                    second.props.remove("family.dating_name");
                    first.props.put("family.last_event", "separated:" + second.id);
                    second.props.put("family.last_event", "separated:" + first.id);
                    continue;
                }
                if (!firstDating.isBlank() && !firstDating.equals(second.id)
                        || !secondDating.isBlank() && !secondDating.equals(first.id)) continue;
                if (!mutualDating && affinity >= 50
                        && Math.floorMod(Objects.hash(first.id, second.id, year, "dating"), 3) == 0) {
                    first.props.put("family.dating", second.id);
                    second.props.put("family.dating", first.id);
                    first.props.put("family.dating_name", second.name);
                    second.props.put("family.dating_name", first.name);
                    first.props.put("family.last_event", "dating:" + second.id);
                    second.props.put("family.last_event", "dating:" + first.id);
                    mutualDating = true;
                }
                if (Objects.equals(first.gender, second.gender) || affinity < 75
                        || Math.floorMod(Objects.hash(first.id, second.id, year, "marriage"), 4) != 0) continue;
                try {
                    registry.marry(first.id, second.id, year);
                    first.props.remove("family.dating");
                    second.props.remove("family.dating");
                    first.props.remove("family.dating_name");
                    second.props.remove("family.dating_name");
                    first.props.put("family.spouse_name", second.name);
                    second.props.put("family.spouse_name", first.name);
                    first.props.put("family.last_event", "married:" + second.id);
                    second.props.put("family.last_event", "married:" + first.id);
                } catch (RuntimeException ignored) {
                    // A concurrent title/lifecycle transition may have changed either partner this year.
                }
                break;
            }
        }
    }

    private static void advanceChildhood(CharacterRegistry registry, List<Character> people, int year) {
        for (Character child : people) {
            if (!child.alive || PopulationSimulation.age(child, year) > 18
                    || year <= number(child.props.get("family.development_year"), 0)) continue;
            int age = PopulationSimulation.age(child, year);
            if (age == 18) {
                child.props.put("family.development_year", year);
                child.props.put("family.adulthood_year", year);
                child.props.put("family.care_status", "independent_adult");
                child.props.remove("family.guardian_id");
                continue;
            }
            Character father = child.father.isBlank() ? null : registry.get(child.father);
            Character mother = child.mother.isBlank() ? null : registry.get(child.mother);
            boolean fatherAlive = father != null && father.alive;
            boolean motherAlive = mother != null && mother.alive;
            Character guardian = null;
            if (fatherAlive && motherAlive) {
                guardian = ResidentSocial.score(child, father.id) >= ResidentSocial.score(child, mother.id) ? father : mother;
            } else if (fatherAlive) guardian = father;
            else if (motherAlive) guardian = mother;
            else guardian = findKinGuardian(registry, child, year);
            child.props.put("family.guardian_id", guardian == null ? "" : guardian.id);
            child.props.put("family.care_status", guardian == null ? "orphaned" :
                    fatherAlive && motherAlive ? "two_parent_home" :
                    fatherAlive || motherAlive ? "single_parent_home" : "kin_guardianship");
            child.props.put("family.development_year", year);
            child.props.put("family.age_stage", PopulationSimulation.lifeStage(child, year).name().toLowerCase(Locale.ROOT));
            if (age >= 6 && age < 18) {
                int education = number(child.props.get("family.education_level"), 0);
                int progress = guardian == null ? 1 : 2;
                if (guardian != null && number(guardian.props.get("family.education_level"), 0) >= 5) progress++;
                child.props.put("family.education_level", Math.min(100, education + progress));
                child.props.put("family.education_last_year", year);
                if (guardian != null) {
                    ResidentSocial.adjust(child, guardian.id, 1);
                    ResidentSocial.adjust(guardian, child.id, 1);
                }
            }
            if (age == 6) child.props.put("family.school_started_year", year);
            if (age == 13) child.props.put("family.adolescence_started_year", year);
            if (age == 18) child.props.put("family.adulthood_year", year);
        }
    }

    private static Character findKinGuardian(CharacterRegistry registry, Character child, int year) {
        Set<String> candidates = new TreeSet<>();
        addGrandparents(registry, child.father, candidates);
        addGrandparents(registry, child.mother, candidates);
        for (Character possible : registry.all()) {
            if (!possible.alive || possible.id.equals(child.id) || PopulationSimulation.age(possible, year) < 18) continue;
            if (isSiblingOfParent(registry, possible, child.father) || isSiblingOfParent(registry, possible, child.mother))
                candidates.add(possible.id);
        }
        return candidates.stream().map(registry::get).filter(Objects::nonNull)
                .filter(c -> c.alive && PopulationSimulation.age(c, year) >= 18)
                .min(Comparator.comparingInt(c -> Math.abs(PopulationSimulation.age(c, year) - 35)))
                .orElse(null);
    }

    private static void addGrandparents(CharacterRegistry registry, String parentId, Set<String> candidates) {
        if (parentId == null || parentId.isBlank()) return;
        Character parent = registry.get(parentId);
        if (parent == null) return;
        if (!parent.father.isBlank()) candidates.add(parent.father);
        if (!parent.mother.isBlank()) candidates.add(parent.mother);
    }

    private static boolean isSiblingOfParent(CharacterRegistry registry, Character possible, String parentId) {
        if (parentId == null || parentId.isBlank()) return false;
        Character parent = registry.get(parentId);
        if (parent == null) return false;
        return sharesParent(possible, parent);
    }

    private static boolean sharesParent(Character a, Character b) {
        return !a.father.isBlank() && (a.father.equals(b.father) || a.father.equals(b.mother))
                || !a.mother.isBlank() && (a.mother.equals(b.father) || a.mother.equals(b.mother));
    }

    private static boolean closeFamily(CharacterRegistry registryForKinship, Character a, Character b) {
        if (a.id.equals(b.id)) return true;
        Set<String> aAncestors = ancestors(registryForKinship, a, 2);
        Set<String> bAncestors = ancestors(registryForKinship, b, 2);
        return aAncestors.contains(b.id) || bAncestors.contains(a.id)
                || aAncestors.stream().anyMatch(bAncestors::contains);
    }

    private static Set<String> ancestors(CharacterRegistry registry, Character person, int depth) {
        Set<String> result = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(person.father);
        frontier.add(person.mother);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            Set<String> next = new HashSet<>();
            for (String id : frontier) {
                if (id == null || id.isBlank() || !result.add(id)) continue;
                Character ancestor = registry.get(id);
                if (ancestor != null) {
                    next.add(ancestor.father);
                    next.add(ancestor.mother);
                }
            }
            frontier = next;
        }
        return result;
    }

    private static String string(Object value) { return value instanceof String s ? s : ""; }

    private static int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
