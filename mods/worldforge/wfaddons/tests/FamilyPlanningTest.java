package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.FamilyPlanning;
import dev.worldforge.people.CharacterStats;

/** Deterministic regression tests for low-growth, persisted pregnancy. */
public final class FamilyPlanningTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        int year = 300;
        int conceptions = 0;
        Character pregnantMother = null, pregnantFather = null;
        for (int i = 0; i < 1000; i++) {
            Character mother = registry.create("Mother" + i, "house" + i, "culture", "f", year - 25);
            Character father = registry.create("Father" + i, "house" + i, "culture", "m", year - 27);
            registry.marry(mother.id, father.id, year);
            if (FamilyPlanning.tryConceive(mother, father, year, 100_000L, 77L + i)) {
                conceptions++;
                if (pregnantMother == null) { pregnantMother = mother; pregnantFather = father; }
            }
        }
        check(conceptions >= 350 && conceptions <= 500,
                "annual conception rate is accessible and deterministic: " + conceptions + "/1000");

        check(pregnantMother != null, "at least one couple conceives in deterministic sample");
        Character mother = pregnantMother, father = pregnantFather;
        check(!FamilyPlanning.tryConceive(mother, father, year, 100_000L, 77L),
                "at most one conception attempt per couple per year");
        check(FamilyPlanning.isPregnant(mother), "pregnancy is persisted on the canonical mother");
        String storedFather = FamilyPlanning.dueFatherId(mother, 100_000L + 270L);
        check(storedFather.equals(father.id), "pregnancy retains the biological father's canonical ID");
        check(FamilyPlanning.dueFatherId(mother, 100_000L + 269L).isEmpty(),
                "birth is not due before gestation completes");
        FamilyPlanning.complete(mother, father, year + 1, "child-test");
        check(!FamilyPlanning.isPregnant(mother), "pregnancy state is cleared after birth");
        check(((Number) mother.props.get("family.last_birth_year")).intValue() == year + 1,
                "birth cooldown is persisted");

        Character fertile = registry.create("Fertile", "house-f", "culture", "f", year - 25);
        check(CharacterStats.addTrait(fertile, "fecund"), "rare fecund trait is a valid canonical character trait");
        check(CharacterStats.traits(fertile).contains("fecund"), "fecund trait persists in the character profile");
        int ordinaryConceptions = 0;
        int fertileConceptions = 0;
        for (int i = 0; i < 1000; i++) {
            Character ordinaryMother = registry.create("OrdinaryMother" + i, "ordinary" + i, "culture", "f", year - 25);
            Character ordinaryFather = registry.create("OrdinaryFather" + i, "ordinary" + i, "culture", "m", year - 27);
            registry.marry(ordinaryMother.id, ordinaryFather.id, year);
            if (FamilyPlanning.tryConceive(ordinaryMother, ordinaryFather, year, 50_000L, 91_000L + i))
                ordinaryConceptions++;

            Character fertileMother = registry.create("FertileMother" + i, "fertile" + i, "culture", "f", year - 25);
            Character fertileFather = registry.create("FertileFather" + i, "fertile" + i, "culture", "m", year - 27);
            CharacterStats.addTrait(fertileMother, "fecund");
            registry.marry(fertileMother.id, fertileFather.id, year);
            if (FamilyPlanning.tryConceive(fertileMother, fertileFather, year, 50_000L, 91_000L + i))
                fertileConceptions++;
        }
        check(fertileConceptions > ordinaryConceptions + 80,
                "fecund trait materially improves conception rate across independent couples: "
                        + ordinaryConceptions + "/1000 vs " + fertileConceptions + "/1000");

        Character betrothedA = registry.create("BetrothedA", "house-b", "culture", "f", year - 22);
        Character betrothedB = registry.create("BetrothedB", "house-b", "culture", "m", year - 23);
        check(FamilyPlanning.engage(betrothedA, betrothedB, year), "mutual engagement can be created");
        check(FamilyPlanning.engagedTo(betrothedA, betrothedB), "engagement is reciprocal and persisted");
        FamilyPlanning.cancelEngagement(betrothedA, betrothedB, year + 1, "changed-opinions");
        check(!FamilyPlanning.engagedTo(betrothedA, betrothedB), "broken engagement clears both sides");

        Character unwilling = registry.create("Unwilling", "house-u", "culture", "f", year - 25);
        Character partner = registry.create("PartnerU", "house-u", "culture", "m", year - 27);
        registry.marry(unwilling.id, partner.id, year);
        unwilling.props.put("family.wants_children", false);
        check(!FamilyPlanning.tryConceive(unwilling, partner, year, 1_000L, 42L),
                "explicitly childfree character does not conceive");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
