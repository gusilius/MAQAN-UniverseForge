package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterHealth;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.CharacterDevelopment;
import dev.worldforge.people.FamilyPlanning;
import dev.worldforge.settle.ResidentLifeEvents;
import dev.worldforge.settle.ResidentSocial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ResidentLifeEventsTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
        else System.out.println("ok   " + message);
    }

    private static boolean retainsCharacterReference(Object value) {
        if (value instanceof Character) return true;
        if (value instanceof Iterable<?> values)
            for (Object item : values) if (retainsCharacterReference(item)) return true;
        if (value instanceof Map<?, ?> map)
            for (Object item : map.values()) if (retainsCharacterReference(item)) return true;
        return false;
    }

    private static int registryPassField(CharacterRegistry registry, String fieldName) {
        try {
            java.lang.reflect.Field passesField = ResidentLifeEvents.class.getDeclaredField("REGISTRY_PASSES");
            passesField.setAccessible(true);
            java.util.Map<?, ?> passes = (java.util.Map<?, ?>) passesField.get(null);
            Object state = passes.get(registry);
            if (state == null) return Integer.MIN_VALUE;
            java.lang.reflect.Field valueField = state.getClass().getDeclaredField(fieldName);
            valueField.setAccessible(true);
            return valueField.getInt(state);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not inspect resident registry pass state", error);
        }
    }

    public static void main(String[] args) {
        System.setProperty("wf.test.traceResidentLife", "true");
        CharacterRegistry retentionRegistry = new CharacterRegistry();
        for (int i = 0; i < 64; i++)
            retentionRegistry.create("Resident " + i, "", "north", i % 2 == 0 ? "m" : "f", 1980);
        ResidentLifeEvents.advanceYearly(retentionRegistry, 2020, null, 1, 9001L);
        try {
            java.lang.reflect.Field passesField = ResidentLifeEvents.class.getDeclaredField("REGISTRY_PASSES");
            passesField.setAccessible(true);
            Object passState = ((java.util.Map<?, ?>) passesField.get(null)).get(retentionRegistry);
            java.lang.reflect.Field sessionField = passState.getClass().getDeclaredField("session");
            sessionField.setAccessible(true);
            Object session = sessionField.get(passState);
            check(session != null && !retainsCharacterReference(session),
                    "resumable family sessions retain UUIDs rather than population-sized Character references");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("could not inspect UUID-based family session", error);
        }

        CharacterRegistry registry = new CharacterRegistry();
        Character father = registry.create("Арен", "Дом Арен", "north", "m", 1980);
        Character mother = registry.create("Мира", "Дом Мира", "north", "f", 1980);
        ResidentSocial.socialize(father, mother, 80);
        check(ResidentSocial.score(father, mother.globalId) == 80
                        && ResidentSocial.score(mother, father.globalId) == 80,
                "social interaction updates both canonical Characters");
        check("друг".equals(ResidentSocial.bond(father, mother.globalId)),
                "relationship score resolves to a stable friendship bond");

        WorldForgeApi.clock().reset();
        boolean married = false;
        int marriageYear = -1;
        for (int year = 2000; year < 2040 && !married; year++) {
            System.out.println("LIFE_TEST marriage-year-start=" + year);
            WorldForgeApi.clock().advance(365 * 1200);
            ResidentLifeEvents.yearly(registry, year);
            System.out.println("LIFE_TEST marriage-year-end=" + year);
            married = father.id.equals(mother.spouse) && mother.id.equals(father.spouse);
            if (married) marriageYear = year;
        }
        check(married, "mutual high affinity can lead to a persistent marriage event");
        check(father.name.equals(mother.props.get("family.spouse_name"))
                        && mother.name.equals(father.props.get("family.spouse_name")),
                "marriage stores reciprocal partner display names");
        check(CharacterDevelopment.memories(father, 96).stream().anyMatch(m -> "marriage".equals(m.get("kind")))
                        && CharacterDevelopment.memories(mother, 96).stream().anyMatch(m -> "marriage".equals(m.get("kind"))),
                "marriage becomes a remembered emotional milestone for both spouses");
        check(registry.get(father.id) == father && registry.get(mother.id) == mother,
                "family events retain the original Character identities");

        boolean childBorn = false;
        for (int year = marriageYear + 1; marriageYear > 0 && year < marriageYear + 40 && !childBorn; year++) {
            System.out.println("LIFE_TEST birth-year-start=" + year);
            WorldForgeApi.clock().advance(365 * 1200);
            ResidentLifeEvents.yearly(registry, year);
            System.out.println("LIFE_TEST birth-year-end=" + year);
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
            Character surnameFather = registry.create("Отец без дома", "", "north", "m", 1970);
            Character surnameMother = registry.create("Мать без дома", "", "north", "f", 1972);
            surnameFather.props.put("identity.surname", "Речной");
            Character surnameChild = registry.addChild(surnameFather, surnameMother, "Новый", "m", 2020);
            check("Речной".equals(surnameChild.props.get("identity.surname")),
                    "children without a house inherit the canonical family surname from their father");
        }
        NameBank birthNames = new NameBank();
        birthNames.add("{\"default\":\"dwarf\",\"cultures\":{\"dwarf\":{\"male\":[\"Торин\"],\"female\":[\"Диса\"],\"houses\":[\"Каменный Молот\"]}},\"races\":{\"deep_dwarf\":{\"male\":[\"Двалин\"],\"female\":[\"Груна\"],\"houses\":[\"Чёрный Кремень\"]}},\"race_parents\":{\"deep_dwarf\":\"dwarf\"}}");
        CharacterRegistry namedFamily = new CharacterRegistry();
        Character namedFather = namedFamily.create("Отец", "", "dwarf", "m", 1980);
        Character namedMother = namedFamily.create("Мать", "", "dwarf", "f", 1980);
        namedFather.props.put("origin.race", "deep_dwarf");
        namedMother.props.put("origin.race", "deep_dwarf");
        namedFamily.marry(namedFather.id, namedMother.id, 2000);
        WorldForgeApi.clock().reset();
        for (int year = 2000; year <= 2040 && namedMother.children.isEmpty(); year++) {
            System.out.println("LIFE_TEST named-birth-year-start=" + year);
            WorldForgeApi.clock().advance(365 * 1200);
            ResidentLifeEvents.yearly(namedFamily, year, birthNames);
            System.out.println("LIFE_TEST named-birth-year-end=" + year);
        }
        Character namedChild = namedMother.children.isEmpty() ? null : namedFamily.get(namedMother.children.get(0));
        check(namedChild != null && List.of("Торин", "Двалин", "Диса", "Груна").contains(namedChild.name),
                "natural births use the shared race-aware NameBank instead of generic placeholder names");

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
        ResidentSocial.adjust(datingA, datingB.globalId, -100);
        ResidentSocial.adjust(datingB, datingA.globalId, -100);
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
        CharacterDevelopment.setTemperament(grandpa, CharacterDevelopment.Temperament.EMPATHY, 95);
        CharacterDevelopment.setTemperament(grandma, CharacterDevelopment.Temperament.DISCIPLINE, 95);
        ResidentLifeEvents.yearly(familyRegistry, 2011);
        check((grandpa.id.equals(child.props.get("family.guardian_id"))
                        || grandma.id.equals(child.props.get("family.guardian_id")))
                        && "kin_guardianship".equals(child.props.get("family.care_status")),
                "an orphan is assigned a living grandparent as canonical kin guardian");
        check(((Number) child.props.get("family.upbringing.last_year")).intValue() == 2011
                        && CharacterDevelopment.memories(child, 96).stream().anyMatch(m -> "experience_orphaned".equals(m.get("kind")))
                        && CharacterDevelopment.memories(child, 96).stream().anyMatch(m -> "experience_mentorship".equals(m.get("kind"))),
                "orphanhood and kin mentorship become distinct remembered life experiences");
        check(((Number) child.props.get("family.education_level")).intValue() >= 2
                        && ((Number) child.props.get("family.development_year")).intValue() == 2011,
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

        CharacterRegistry dueBirthRegistry = new CharacterRegistry();
        Character dueMother = dueBirthRegistry.create("Мать", "house-due", "north", "f", 1975);
        Character dueFather = dueBirthRegistry.create("Отец", "house-due", "north", "m", 1974);
        dueBirthRegistry.marry(dueMother.id, dueFather.id, 2000);
        dueMother.props.put("family.pregnancy.father_id", dueFather.id);
        dueMother.props.put("family.pregnancy.start_day", 100L);
        dueMother.props.put("family.pregnancy.due_day", 370L);
        dueMother.props.put("family.pregnancy.conception_year", 2000);
        dueMother.props.put("social.last_family_year", 2001);
        check(ResidentLifeEvents.advanceDuePregnanciesBatch(dueBirthRegistry, 2001, 369L, null, 16) > 0
                        && dueMother.children.isEmpty(),
                "a pregnancy is not delivered before its persisted due day");
        String dueSave = dueBirthRegistry.toJson();
        CharacterRegistry dueReload = new CharacterRegistry();
        dueReload.fromJson(dueSave);
        int dueBirthWork = ResidentLifeEvents.advanceDuePregnanciesBatch(dueReload, 2001, 370L, null, 16);
        Character restoredMother = dueReload.get(dueMother.id);
        Character restoredFather = dueReload.get(dueFather.id);
        check(dueBirthWork > 0 && restoredMother.children.size() == 1
                        && restoredFather.children.size() == 1
                        && !FamilyPlanning.isPregnant(restoredMother),
                "due-date queue delivers on the persisted day after reload, independent of the annual family pass marker");

        CharacterRegistry healthRegistry = new CharacterRegistry();
        Character patient = healthRegistry.create("Пациент", "", "north", "f", 1985);
        check(CharacterHealth.health(patient) == 100 && CharacterHealth.nextEventDay(patient) == -1
                        && !patient.props.containsKey("life.health.value"),
                "healthy residents use implicit defaults without per-Character health payload");
        check(CharacterHealth.infect(patient, "seasonal_fever", 100, 100L, "illness:patient:100")
                        && !CharacterHealth.infect(patient, "seasonal_fever", 40, 100L, "illness:patient:100")
                        && CharacterHealth.illnessSeverity(patient) == 100,
                "health event IDs make repeated illness delivery idempotent");
        check(!CharacterHealth.advance(patient, 106L) && CharacterHealth.illnessSeverity(patient) == 100,
                "health recovery does not run before its scheduled deadline");
        check(CharacterHealth.advance(patient, 107L) && CharacterHealth.illnessSeverity(patient) == 99
                        && CharacterHealth.nextEventDay(patient) == 114L,
                "illness recovery advances in scheduled world-day intervals");
        String healthSave = healthRegistry.toJson();
        CharacterRegistry healthReload = new CharacterRegistry();
        healthReload.fromJson(healthSave);
        Character restoredPatient = healthReload.get(patient.id);
        check(restoredPatient != null && CharacterHealth.illnessSeverity(restoredPatient) == 99
                        && CharacterHealth.nextEventDay(restoredPatient) == 114L,
                "illness and recovery deadlines persist through world save/load");
        check(CharacterHealth.treat(restoredPatient, 100, 107L, "treatment:patient:1")
                        && CharacterHealth.illnessSeverity(restoredPatient) == 0
                        && CharacterHealth.health(restoredPatient) < 100
                        && CharacterHealth.nextEventDay(restoredPatient) == 114L,
                "treatment clears illness but keeps a deadline for natural health recovery");
        check(CharacterHealth.advance(restoredPatient, 117L)
                        && CharacterHealth.health(restoredPatient) == 99,
                "natural health recovery continues after the illness itself is cleared");

        CharacterRegistry scheduledHealthRegistry = new CharacterRegistry();
        Character scheduledPatient = scheduledHealthRegistry.create("Scheduled Patient", "", "north", "m", 1980);
        CharacterHealth.injure(scheduledPatient, "sprain", 60, 200L, "injury:scheduled:200");
        int healthWork = ResidentLifeEvents.advanceHealthBatch(scheduledHealthRegistry, 207L, 16);
        check(healthWork >= 2 && CharacterHealth.injurySeverity(scheduledPatient) == 60
                        && CharacterHealth.health(scheduledPatient) == 78
                        && CharacterHealth.nextEventDay(scheduledPatient) == 214L,
                "registry health scheduler discovers and advances only a resident whose persisted deadline is due");

        CharacterRegistry sameYearRegistry = new CharacterRegistry();
        sameYearRegistry.create("Первый", "", "north", "m", 1990);
        ResidentLifeEvents.yearly(sameYearRegistry, 2020);
        Character lateArrival = sameYearRegistry.create("Прибывший", "", "north", "f", 2005);
        ResidentLifeEvents.yearly(sameYearRegistry, 2020);
        check("adolescent".equals(lateArrival.props.get("population.life_stage"))
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
        ResidentSocial.adjust(partnerA, partnerB.globalId, -80);
        ResidentSocial.adjust(partnerB, partnerA.globalId, -80);
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
        check(CharacterDevelopment.memories(partnerA, 96).stream().anyMatch(m -> "divorce".equals(m.get("kind")))
                        && CharacterDevelopment.memories(partnerB, 96).stream().anyMatch(m -> "divorce".equals(m.get("kind"))),
                "divorce is remembered by both former spouses and changes their psychology once");

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
        ResidentSocial.adjust(cousinOne, cousinTwo.globalId, 100);
        ResidentSocial.adjust(cousinTwo, cousinOne.globalId, 100);
        for (int year = 2015; year <= 2023; year++) ResidentLifeEvents.yearly(cousinRegistry, year);
        check(!cousinOne.props.containsKey("family.dating") && !cousinTwo.props.containsKey("family.dating"),
                "close cousins sharing grandparents are excluded from autonomous dating");

        CharacterRegistry guardedRegistry = new CharacterRegistry();
        guardedRegistry.create("Один", "", "north", "m", 1990);
        guardedRegistry.create("Два", "", "north", "f", 1991);
        for (int call = 0; call < 100; call++) ResidentLifeEvents.yearly(guardedRegistry, 2030);
        check(registryPassField(guardedRegistry, "lastCallYear") == 2030
                        && registryPassField(guardedRegistry, "stageSize") == 2
                        && registryPassField(guardedRegistry, "familyYear") == 2030,
                "hundreds of settlement callbacks share one canonical registry/year pass");
        Character lateResident = guardedRegistry.create("Поздний житель", "", "north", "f", 2010);
        ResidentLifeEvents.yearly(guardedRegistry, 2030);
        check(registryPassField(guardedRegistry, "stageSize") == 3
                        && "adult".equals(lateResident.props.get("population.life_stage"))
                        && registryPassField(guardedRegistry, "familyYear") == 2030,
                "late same-year residents receive stage refresh without replaying annual family events");
        for (int call = 0; call < 100; call++) ResidentLifeEvents.yearly(guardedRegistry, 2030);
        check(registryPassField(guardedRegistry, "stageSize") == 3
                        && registryPassField(guardedRegistry, "familyYear") == 2030,
                "same-year callbacks after population changes do not repeat the global family pass");

        if (failures > 0) throw new AssertionError(failures + " resident life event tests failed");
    }
}
