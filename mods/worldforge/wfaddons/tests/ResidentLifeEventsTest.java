package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.settle.ResidentLifeEvents;
import dev.worldforge.settle.ResidentSocial;

import java.util.ArrayList;
import java.util.List;

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
        CharacterRegistry familyRegistry = new CharacterRegistry();
        Character grandpa = familyRegistry.create("Орин", "Дом", "north", "m", 1940);
        Character grandma = familyRegistry.create("Эйра", "Дом", "north", "f", 1942);
        Character orphanFather = familyRegistry.addChild(grandpa, grandma, "Дорен", "m", 1970);
        Character orphanMother = familyRegistry.create("Лина", "Дом Лины", "north", "f", 1972);
        Character child = familyRegistry.addChild(orphanFather, orphanMother, "Кай", "m", 2000);
        orphanFather.alive = false;
        orphanMother.alive = false;
        ResidentLifeEvents.yearly(familyRegistry, 2010);
        check((grandpa.id.equals(child.props.get("family.guardian_id"))
                        || grandma.id.equals(child.props.get("family.guardian_id")))
                        && "kin_guardianship".equals(child.props.get("family.care_status")),
                "an orphan is assigned a living grandparent as canonical kin guardian");
        check(((Number) child.props.get("family.education_level")).intValue() >= 2
                        && ((Number) child.props.get("family.development_year")).intValue() == 2010,
                "childhood advances age-appropriate education exactly once per world year");
        ResidentLifeEvents.yearly(familyRegistry, 2018);
        check("independent_adult".equals(child.props.get("family.care_status"))
                        && ((Number) child.props.get("family.adulthood_year")).intValue() == 2018,
                "the family-care lifecycle releases a resident into adulthood at eighteen");

        CharacterRegistry mentorshipRegistry = new CharacterRegistry();
        Character mentor = mentorshipRegistry.create("Мастер", "", "north", "m", 1970);
        Character otherParent = mentorshipRegistry.create("Родитель", "", "north", "f", 1971);
        Character pupil = mentorshipRegistry.addChild(mentor, otherParent, "Ученик", "f", 2000);
        PopulationSimulation.setSkillRank(mentor, "farming", 4);
        ResidentLifeEvents.yearly(mentorshipRegistry, 2010);
        long firstMentorshipXp = PopulationSimulation.trainingXp(pupil, "farming");
        ResidentLifeEvents.yearly(mentorshipRegistry, 2010);
        check(firstMentorshipXp == 5 && PopulationSimulation.trainingXp(pupil, "farming") == firstMentorshipXp,
                "guardian mentorship grants age-scaled skill experience only once per world year");
        ResidentLifeEvents.yearly(mentorshipRegistry, 2011);
        ResidentLifeEvents.yearly(mentorshipRegistry, 2012);
        check(PopulationSimulation.trainingXp(pupil, "farming") == 15
                        && PopulationSimulation.skillRank(pupil, "farming") == 0,
                "children inherit learning opportunities gradually without copying guardian skill ranks");

        CharacterRegistry sameYearRegistry = new CharacterRegistry();
        sameYearRegistry.create("Первый", "", "north", "m", 1990);
        ResidentLifeEvents.yearly(sameYearRegistry, 2020);
        Character lateArrival = sameYearRegistry.create("Прибывший", "", "north", "f", 2005);
        ResidentLifeEvents.yearly(sameYearRegistry, 2020);
        check("child".equals(lateArrival.props.get("population.life_stage"))
                        && ((Number) lateArrival.props.get("population.age_years")).intValue() == 15,
                "characters registered mid-year receive calendar-derived life stages without waiting a full year");

        CharacterRegistry missedYearsRegistry = new CharacterRegistry();
        Character grown = missedYearsRegistry.create("Повзрослевший", "", "north", "m", 2000);
        ResidentLifeEvents.yearly(missedYearsRegistry, 2025);
        check("independent_adult".equals(grown.props.get("family.care_status"))
                        && ((Number) grown.props.get("family.adulthood_year")).intValue() == 2025,
                "a resident loaded after several missed years still transitions to adulthood");

        CharacterRegistry conflictRegistry = new CharacterRegistry();
        Character partnerA = conflictRegistry.create("Алан", "", "north", "m", 1970);
        Character partnerB = conflictRegistry.create("Селия", "", "north", "f", 1971);
        conflictRegistry.marry(partnerA.id, partnerB.id, 2000);
        ResidentSocial.adjust(partnerA, partnerB.id, -80);
        ResidentSocial.adjust(partnerB, partnerA.id, -80);
        List<CharacterRegistry.LifecycleEvent> conflictEvents = new ArrayList<>();
        conflictRegistry.addListener(new CharacterRegistry.Listener() {
            @Override public void lifecycle(Character character, CharacterRegistry.LifecycleEvent event) {
                conflictEvents.add(event);
            }
        });
        ResidentLifeEvents.yearly(conflictRegistry, 2001);
        ResidentLifeEvents.yearly(conflictRegistry, 2001);
        ResidentLifeEvents.yearly(conflictRegistry, 2001);
        check(((Number) partnerA.props.get("family.marital_conflict_years")).intValue() == 1,
                "repeated settlement ticks cannot advance the same annual marital conflict more than once");
        for (int year = 2002; year <= 2003; year++) ResidentLifeEvents.yearly(conflictRegistry, year);
        check(partnerA.spouse.isBlank() && partnerB.spouse.isBlank()
                        && conflictEvents.stream().anyMatch(e -> e.kind() == CharacterRegistry.LifecycleKind.MARRIAGE_DISSOLVED),
                "sustained severe marital conflict produces one reciprocal divorce lifecycle event");

        CharacterRegistry cousinRegistry = new CharacterRegistry();
        Character rootA = cousinRegistry.create("RootA", "", "north", "m", 1940);
        Character rootB = cousinRegistry.create("RootB", "", "north", "f", 1942);
        Character siblingOne = cousinRegistry.addChild(rootA, rootB, "SiblingOne", "m", 1970);
        Character siblingTwo = cousinRegistry.addChild(rootA, rootB, "SiblingTwo", "f", 1972);
        Character cousinOne = cousinRegistry.create("CousinOne", "", "north", "m", 1995);
        Character cousinTwo = cousinRegistry.create("CousinTwo", "", "north", "f", 1996);
        cousinOne.father = siblingOne.id;
        cousinOne.mother = cousinRegistry.create("OtherMotherA", "", "north", "f", 1971).id;
        cousinTwo.father = siblingTwo.id;
        cousinTwo.mother = cousinRegistry.create("OtherMotherB", "", "north", "f", 1973).id;
        ResidentSocial.adjust(cousinOne, cousinTwo.id, 100);
        ResidentSocial.adjust(cousinTwo, cousinOne.id, 100);
        for (int year = 2015; year <= 2023; year++) ResidentLifeEvents.yearly(cousinRegistry, year);
        check(!cousinOne.props.containsKey("family.dating") && !cousinTwo.props.containsKey("family.dating"),
                "close cousins sharing grandparents are excluded from autonomous dating");

        if (failures > 0) throw new AssertionError(failures + " resident life event tests failed");
    }
}
