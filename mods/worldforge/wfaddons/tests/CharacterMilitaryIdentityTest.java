package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.PopulationSimulation.Duty;

/** Pure-domain regression for Worker -> Troop -> Worker using one canonical Character. */
public final class CharacterMilitaryIdentityTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        Character person = registry.create("Aldric", "House Forge", "north", "m", 280);
        String legacyId = person.id;
        String globalId = person.globalId;
        String troopEntity = "99999999-9999-4999-8999-999999999999";

        registry.hire(legacyId, "settlement:forge", "blacksmith", 300);
        check(registry.get(legacyId) == person && registry.size() == 1
                        && PopulationSimulation.hasDuty(person, Duty.WORKER),
                "settlement worker projection references the existing canonical Character");

        person.props.put("population.pre_military_profession", PopulationSimulation.profession(person));
        registry.fire(legacyId, "settlement:forge", 301);
        registry.setDuty(legacyId, Duty.SOLDIER, true, 301);
        check(!PopulationSimulation.hasDuty(person, Duty.WORKER)
                        && PopulationSimulation.hasDuty(person, Duty.SOLDIER)
                        && registry.bindEntity(legacyId, "troop", troopEntity),
                "military transfer changes duties and binds the physical troop to the same Character");

        String militarySave = registry.toJson();
        CharacterRegistry restored = new CharacterRegistry();
        restored.fromJson(militarySave);
        Character restoredPerson = restored.get(legacyId);
        check(restoredPerson != null && restoredPerson.globalId.equals(globalId)
                        && troopEntity.equals(((java.util.Map<?, ?>) restoredPerson.props.get("population.entity_bindings")).get("troop"))
                        && PopulationSimulation.hasDuty(restoredPerson, Duty.SOLDIER),
                "soldier identity and physical entity binding survive Character registry save/load");

        restored.unbindEntity(legacyId, "troop", troopEntity);
        String formerProfession = String.valueOf(restoredPerson.props.remove("population.pre_military_profession"));
        restored.changeProfession(legacyId, formerProfession, 302);
        restored.setDuty(legacyId, Duty.SOLDIER, false, 302);
        restored.hire(legacyId, "settlement:forge", formerProfession, 302);
        check(restored.get(legacyId) == restoredPerson && restored.size() == 1
                        && restoredPerson.globalId.equals(globalId)
                        && PopulationSimulation.hasDuty(restoredPerson, Duty.WORKER)
                        && !PopulationSimulation.hasDuty(restoredPerson, Duty.SOLDIER),
                "demobilization restores worker duty without creating a replacement Character");
        check(restoredPerson.props.get("population.profession").equals(formerProfession)
                        && !restoredPerson.props.containsKey("population.entity_bindings"),
                "civilian profession is restored and troop body binding is released");
        if (failures > 0) throw new AssertionError(failures + " CharacterMilitaryIdentity tests failed");
        System.out.println("CharacterMilitaryIdentity: ALL OK");
    }
}
