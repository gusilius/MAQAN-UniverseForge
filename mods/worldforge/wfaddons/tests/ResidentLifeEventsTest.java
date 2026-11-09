package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.settle.ResidentLifeEvents;
import dev.worldforge.settle.ResidentSocial;

public final class ResidentLifeEventsTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
        else System.out.println("ok   " + message);
    }

    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        Character father = registry.create("Арен", "Дом Арен", "north", "m", 1980);
        Character mother = registry.create("Мира", "Дом Мира", "north", "f", 1980);
        ResidentSocial.socialize(father, mother, 80);
        check(ResidentSocial.score(father, mother.id) == 80
                        && ResidentSocial.score(mother, father.id) == 80,
                "social interaction updates both canonical Characters");
        check("друг".equals(ResidentSocial.bond(father, mother.id)),
                "relationship score resolves to a stable friendship bond");

        boolean married = false;
        for (int year = 2000; year < 2040 && !married; year++) {
            ResidentLifeEvents.yearly(registry, year);
            married = father.id.equals(mother.spouse) && mother.id.equals(father.spouse);
        }
        check(married, "mutual high affinity can lead to a persistent marriage event");
        check(father.name.equals(mother.props.get("family.spouse_name"))
                        && mother.name.equals(father.props.get("family.spouse_name")),
                "marriage stores reciprocal partner display names");
        check(registry.get(father.id) == father && registry.get(mother.id) == mother,
                "family events retain the original Character identities");

        boolean childBorn = false;
        for (int year = 2000; year < 2040 && !childBorn; year++) {
            ResidentLifeEvents.yearly(registry, year);
            childBorn = !mother.children.isEmpty() && !father.children.isEmpty();
        }
        check(childBorn, "married residents can have a child through the canonical family registry");
        if (childBorn) {
            Character child = registry.get(mother.children.get(0));
            check(child != null && father.id.equals(child.father) && mother.id.equals(child.mother),
                    "child stores both canonical parent links");
            check(child != null && father.children.contains(child.id) && mother.children.contains(child.id),
                    "birth updates both family trees");
            check(child != null && father.name.equals(child.props.get("family.father_name"))
                            && mother.name.equals(child.props.get("family.mother_name")),
                    "child stores readable canonical parent names");
        }
        CharacterRegistry restored = new CharacterRegistry();
        restored.fromJson(registry.toJson());
        Character savedMother = restored.get(mother.id);
        check(savedMother != null && !savedMother.children.isEmpty()
                        && savedMother.spouse.equals(father.id),
                "marriage and children survive CharacterRegistry world save");

        Character datingA = registry.create("Лея", "", "north", "f", 1982);
        Character datingB = registry.create("Тарен", "", "north", "m", 1981);
        ResidentSocial.socialize(datingA, datingB, 60);
        for (int year = 2000; year < 2004; year++) ResidentLifeEvents.yearly(registry, year);
        check(datingB.id.equals(datingA.props.get("family.dating"))
                        && datingA.id.equals(datingB.props.get("family.dating")),
                "mutual affinity can start a reciprocal dating relationship");
        ResidentSocial.adjust(datingA, datingB.id, -100);
        ResidentSocial.adjust(datingB, datingA.id, -100);
        ResidentLifeEvents.yearly(registry, 2004);
        check(!datingA.props.containsKey("family.dating") && !datingB.props.containsKey("family.dating"),
                "severe relationship damage ends the dating relationship with a separation event");
        if (failures > 0) throw new AssertionError(failures + " resident life event tests failed");
    }
}
