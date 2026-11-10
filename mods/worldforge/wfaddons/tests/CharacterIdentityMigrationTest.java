package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;

/** Proves deterministic migration of old per-map saves without losing family references. */
public final class CharacterIdentityMigrationTest {
    private static int failures;
    private static void check(boolean value, String message) {
        System.out.println((value ? "ok   " : "FAIL ") + message);
        if (!value) failures++;
    }

    private static String legacyV2(CharacterRegistry registry) {
        return registry.toJson().replace("\"format\":\"wf-people/3\"", "\"format\":\"wf-people/2\"")
                .replaceAll(",\"global_id\":\"[^\"]+\"", "");
    }

    public static void main(String[] args) {
        CharacterRegistry source = new CharacterRegistry();
        Character father = source.create("Father", "House", "north", "m", 100);
        Character mother = source.create("Mother", "House", "north", "f", 102);
        Character child = source.addChild(father, mother, "Child", "f", 120);
        source.marry(father.id, mother.id, 110);
        String oldSave = legacyV2(source);

        CharacterRegistry northA = new CharacterRegistry();
        northA.fromJson(oldSave, "world-A|north");
        CharacterRegistry northB = new CharacterRegistry();
        northB.fromJson(oldSave, "world-A|north");
        CharacterRegistry south = new CharacterRegistry();
        south.fromJson(oldSave, "world-A|south");

        Character migratedFather = northA.get(father.id);
        Character migratedMother = northA.get(mother.id);
        Character migratedChild = northA.get(child.id);
        check(migratedFather.globalId.equals(northB.get(father.id).globalId),
                "legacy migration is deterministic across repeated loads of the same world/map");
        check(!migratedFather.globalId.equals(south.get(father.id).globalId),
                "same legacy cN in another map receives a distinct global UUID");
        check(migratedChild.father.equals(father.id) && migratedChild.mother.equals(mother.id)
                        && migratedFather.children.contains(child.id) && migratedMother.children.contains(child.id),
                "family links remain intact through legacy identity migration");
        check(migratedFather.spouse.equals(mother.id) && migratedMother.spouse.equals(father.id),
                "marriage links remain intact through migration");

        CharacterRegistry roundTrip = new CharacterRegistry();
        roundTrip.fromJson(northA.toJson(), "world-A|north");
        check(roundTrip.get(father.id).globalId.equals(migratedFather.globalId),
                "schema v3 persists global UUID without regenerating it");
        if (failures > 0) throw new AssertionError(failures + " CharacterIdentityMigration tests failed");
        System.out.println("CharacterIdentityMigration: ALL OK");
    }
}
