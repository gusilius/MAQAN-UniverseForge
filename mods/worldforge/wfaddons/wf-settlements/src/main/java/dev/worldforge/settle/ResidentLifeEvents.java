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
            if (number(mother.props.get("family.last_birth_year"), 0) >= year) continue;
            if (Math.floorMod(Objects.hash(mother.id, father.id, year, "birth"), 7) != 0) continue;
            String gender = Math.floorMod(Objects.hash(mother.id, father.id, year, "gender"), 2) == 0 ? "f" : "m";
            String childName = "Дитя семьи " + (mother.house == null || mother.house.isBlank() ? mother.name : mother.house);
            Character child = registry.addChild(father, mother, childName, gender, year);
            child.traits.add("curious");
            mother.props.put("family.last_birth_year", year);
            father.props.put("family.last_birth_year", year);
            mother.props.put("family.last_event", "child-born:" + child.id);
            father.props.put("family.last_event", "child-born:" + child.id);
        }
        for (int i = 0; i < people.size(); i++) {
            Character first = people.get(i);
            if (!first.alive || first.spouse != null && !first.spouse.isBlank()
                    || PopulationSimulation.age(first, year) < 18) continue;
            for (int j = i + 1; j < people.size(); j++) {
                Character second = people.get(j);
                if (!second.alive || second.spouse != null && !second.spouse.isBlank()
                        || PopulationSimulation.age(second, year) < 18
                        || Objects.equals(first.gender, second.gender)
                        || closeFamily(first, second)) continue;
                int affinity = Math.min(ResidentSocial.score(first, second.id), ResidentSocial.score(second, first.id));
                if (affinity < 75 || Math.floorMod(Objects.hash(first.id, second.id, year, "marriage"), 4) != 0) continue;
                try {
                    registry.marry(first.id, second.id, year);
                    first.props.put("family.last_event", "married:" + second.id);
                    second.props.put("family.last_event", "married:" + first.id);
                } catch (RuntimeException ignored) {
                    // A concurrent title/lifecycle transition may have changed either partner this year.
                }
                break;
            }
        }
    }

    private static boolean closeFamily(Character a, Character b) {
        if (a.id.equals(b.id) || a.father.equals(b.id) || a.mother.equals(b.id)
                || b.father.equals(a.id) || b.mother.equals(a.id)) return true;
        return !a.father.isBlank() && (a.father.equals(b.father) || a.father.equals(b.mother))
                || !a.mother.isBlank() && (a.mother.equals(b.father) || a.mother.equals(b.mother));
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
