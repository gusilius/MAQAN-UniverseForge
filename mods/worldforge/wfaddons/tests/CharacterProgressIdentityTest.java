package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.progress.Profile;
import dev.worldforge.progress.Progress;
import dev.worldforge.progress.ProgressDefs;
import dev.worldforge.progress.people.PeopleProgressBridge;

/** Regression for one RPG profile following its canonical Character UUID across maps. */
public final class CharacterProgressIdentityTest {
    private static int failures;

    private static void check(boolean condition, String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        CharacterRegistry north = new CharacterRegistry();
        Character person = north.create("Aldric", "", "", "m", 280);
        Progress progress = new Progress(new ProgressDefs());

        String oldScopedOwner = PopulationSimulation.legacyProgressOwner(person, "North");
        Profile oldScoped = progress.profile(oldScopedOwner);
        oldScoped.level = 8;
        oldScoped.xp = 37;
        oldScoped.totalXp = 940;
        oldScoped.attrFree = 3;
        oldScoped.skillFree = 2;
        oldScoped.passiveFree = 1;
        oldScoped.archetype = "warrior";
        oldScoped.attrs.put("strength", 4);
        oldScoped.skills.put("melee", 3);
        oldScoped.nodes.add("warrior_start");
        oldScoped.respecs = 2;

        Profile canonical = PeopleProgressBridge.profile(progress, person, "North");
        check(canonical.owner.equals("n:" + person.globalId),
                "new NPC progression owner uses immutable global Character UUID");
        check(canonical.level == 8 && canonical.xp == 37 && canonical.totalXp == 940
                        && canonical.attrFree == 3 && canonical.skillFree == 2
                        && canonical.passiveFree == 1 && canonical.respecs == 2,
                "legacy scoped profile progression totals and points migrate intact");
        check(canonical.attrs.get("strength") == 4 && canonical.skills.get("melee") == 3
                        && canonical.nodes.contains("warrior_start"),
                "legacy scoped attributes, skills and perk nodes migrate intact");
        check(progress.peek(oldScopedOwner) == null,
                "superseded scoped profile is removed after successful migration");
        check(PeopleProgressBridge.owner(person, "North").equals(PeopleProgressBridge.owner(person, "South")),
                "the same Character keeps one progression owner after moving to another map scope");

        CharacterRegistry south = new CharacterRegistry();
        Character sameLocalId = south.create("Beatrice", "", "", "f", 280);
        check(person.id.equals(sameLocalId.id) && !person.globalId.equals(sameLocalId.globalId)
                        && !PeopleProgressBridge.owner(person, "North").equals(PeopleProgressBridge.owner(sameLocalId, "South")),
                "colliding legacy cN IDs in separate maps still receive different global progression owners");

        Progress ambiguousProgress = new Progress(new ProgressDefs());
        Profile ambiguousLegacy = ambiguousProgress.profile("n:" + person.id);
        ambiguousLegacy.level = 12;
        Profile ambiguousTarget = PeopleProgressBridge.profile(ambiguousProgress, person, "North");
        check(ambiguousTarget.level == 1 && ambiguousProgress.peek("n:" + person.id) == ambiguousLegacy,
                "ambiguous unscoped legacy cN profile is not assigned to an arbitrary Character");

        if (failures > 0) throw new AssertionError(failures + " CharacterProgressIdentity tests failed");
        System.out.println("CharacterProgressIdentity: ALL OK");
    }
}
