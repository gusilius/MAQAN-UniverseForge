package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.FamilyPlanning;

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
        for (int i = 0; i < 1000; i++) {
            Character mother = registry.create("Mother" + i, "house" + i, "culture", "f", year - 25);
            Character father = registry.create("Father" + i, "house" + i, "culture", "m", year - 27);
            registry.marry(mother.id, father.id, year);
            if (FamilyPlanning.tryConceive(mother, father, year, 100_000L, 77L + i)) conceptions++;
            check(!FamilyPlanning.tryConceive(mother, father, year, 100_000L, 77L + i),
                    "at most one conception attempt per couple per year");
        }
        check(conceptions >= 25 && conceptions <= 120,
                "annual conception rate remains low and deterministic: " + conceptions + "/1000");

        Character mother = registry.create("Pregnant", "house-p", "culture", "f", year - 25);
        Character father = registry.create("Partner", "house-p", "culture", "m", year - 27);
        registry.marry(mother.id, father.id, year);
        boolean conceived = false;
        for (long seed = 1; seed < 1000 && !conceived; seed++)
            conceived = FamilyPlanning.tryConceive(mother, father, year, 1_000L, seed);
        check(conceived && FamilyPlanning.isPregnant(mother), "pregnancy is persisted on the canonical mother");
        String storedFather = FamilyPlanning.dueFatherId(mother, 1_000L + 270L);
        check(storedFather.equals(father.id), "pregnancy retains the biological father's canonical ID");
        check(FamilyPlanning.dueFatherId(mother, 1_000L + 269L).isEmpty(),
                "birth is not due before gestation completes");
        FamilyPlanning.complete(mother, father, year + 1, "child-test");
        check(!FamilyPlanning.isPregnant(mother), "pregnancy state is cleared after birth");
        check(((Number) mother.props.get("family.last_birth_year")).intValue() == year + 1,
                "birth cooldown is persisted");

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
