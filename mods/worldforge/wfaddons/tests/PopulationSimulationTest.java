package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.progress.Progress;
import dev.worldforge.progress.ProgressDefs;
import dev.worldforge.progress.people.PeopleProgressBridge;
import dev.worldforge.people.PopulationSimulation.Duty;
import dev.worldforge.people.PopulationSimulation.Intent;
import dev.worldforge.people.PopulationSimulation.LifeStage;
import dev.worldforge.people.PopulationSimulation.Need;

public final class PopulationSimulationTest {
    private static int failures;
    private static void ok(boolean condition, String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        Character smith = registry.create("Aldric", "House Forge", "north", "m", 280);
        String stableId = smith.id;

        ok(PopulationSimulation.progressOwner(smith, "middle_earth").equals("n:middle_earth:" + stableId), "NPC progression owner derives from the one Character ID");
        ok(PopulationSimulation.age(smith, 300) == 20, "age derives from the shared world calendar");
        ok(PopulationSimulation.lifeStage(smith, 300) == LifeStage.ADULT, "life-stage thresholds are deterministic");

        ProfessionDefs professionDefs = new ProfessionDefs();
        professionDefs.add("{\\"id\\":\\"farmer\\",\\"name\\":\\"Farmer\\",\\"category\\":\\"worker\\"}");
        professionDefs.add("{\\"id\\":\\"blacksmith\\",\\"name\\":\\"Blacksmith\\",\\"category\\":\\"specialist\\",\\"required_skills\\":{\\"smithing\\":1}}");
        PopulationSimulation.assignProfession(smith, professionDefs, "farmer", 300);
        PopulationSimulation.train(smith, "smithing", 100);
        PopulationSimulation.assignProfession(smith, professionDefs, "blacksmith", 300);
        PopulationSimulation.addDuty(smith, Duty.SPECIALIST);
        PopulationSimulation.setNeed(smith, Need.PURPOSE, 80);
        ok(PopulationSimulation.decide(smith, 300) == Intent.WORK, "specialist with urgent purpose receives a work intent");

        PopulationSimulation.setTrainingTarget(smith, "smithing");
        ok(PopulationSimulation.decide(smith, 300) == Intent.TRAIN, "training target takes precedence over generic work");
        ok(PopulationSimulation.train(smith, "smithing", 250) == 2, "training XP advances two ranks");
        ok(PopulationSimulation.skillRank(smith, "smithing") == 3 && PopulationSimulation.trainingXp(smith, "smithing") == 50,
                "training remainder and skill rank persist in Character data");

        professionDefs.add("{\\"id\\":\\"armorer\\",\\"name\\":\\"Armorer\\",\\"category\\":\\"specialist\\"}");
        PopulationSimulation.assignProfession(smith, professionDefs, "armorer", 302);
        ok(PopulationSimulation.profession(smith).equals("armorer"), "profession can change without replacing Character");
        ok(PopulationSimulation.professionHistory(smith).equals(java.util.List.of("farmer", "blacksmith")), "previous profession is retained in history");
        ok(PopulationSimulation.skillRank(smith, "smithing") == 3, "skills survive profession changes");

        PopulationSimulation.addDuty(smith, Duty.SOLDIER);
        PopulationSimulation.setNeed(smith, Need.HUNGER, 90);
        ok(PopulationSimulation.decide(smith, 302) == Intent.EAT, "shared needs override military and civilian role priorities");
        PopulationSimulation.completeIntent(smith, Intent.EAT);
        ok(PopulationSimulation.need(smith, Need.HUNGER) == 20, "successful action relieves its shared need");
        PopulationSimulation.setNeed(smith, Need.HUNGER, 0);
        PopulationSimulation.setNeed(smith, Need.REST, 0);
        PopulationSimulation.setNeed(smith, Need.SAFETY, 0);
        PopulationSimulation.setNeed(smith, Need.SOCIAL, 0);
        PopulationSimulation.setNeed(smith, Need.PURPOSE, 0);
        ok(PopulationSimulation.decide(smith, 302) == Intent.PATROL, "same person can retain profession and serve as a soldier");

        Character child = registry.create("Mira", "", "north", "f", 296);
        ok(PopulationSimulation.lifeStage(child, 300) == LifeStage.INFANT, "infant classification");
        ok(PopulationSimulation.decide(child, 300) == Intent.IDLE, "infants do not receive adult work or combat intents");
        ok(!PopulationSimulation.canWork(child, 300), "infants cannot be assigned work");

        PopulationSimulation.advance(registry, 1, 303);
        ok(PopulationSimulation.age(smith, 303) == 23, "population clock advances calendar age");
        ok(PopulationSimulation.lastIntent(smith) == Intent.PATROL, "daily simulation refreshes deterministic AI intent");
        ok(((Number) smith.props.get("population.simulated_days")).longValue() == 1, "elapsed population days are persisted on the Character");

        Progress progress = new Progress(new ProgressDefs());
        int bridgeRanks = PeopleProgressBridge.train(progress, smith, "middle_earth", "tactics", 200);
        ok(bridgeRanks == 2, "NPC training uses the unified Character-backed skill record");
        String npcOwner = PeopleProgressBridge.owner(smith, "middle_earth");
        ok(npcOwner.equals("n:middle_earth:" + stableId), "progress profile owner is derived from scoped Character ID");
        ok(progress.profile(npcOwner).skill("tactics") == PopulationSimulation.skillRank(smith, "tactics"), "RPG profile mirrors canonical Character skill ranks");
        progress.profile(npcOwner).skills.put("tactics", 3);
        PeopleProgressBridge.syncToCharacter(smith, progress.profile(npcOwner));
        ok(PopulationSimulation.skillRank(smith, "tactics") == 3, "RPG skill allocation writes back to the same Character");

        Character spouse = registry.create("Elena", "", "north", "f", 281);
        registry.marry(smith.id, spouse.id);
        Character heir = registry.addChild(smith, spouse, "Rowan", "m", 301);
        PopulationSimulation.die(registry, smith.id, 303, "battle");
        ok(!smith.alive && registry.get(stableId) == smith, "death preserves the same Character record and ID");
        ok(registry.get(heir.id).father.equals(stableId), "family graph keeps the deceased parent's identity");
        ok(registry.get(stableId).props.get("population.death_year").equals(303), "death year is recorded");
        ok("battle".equals(registry.get(stableId).props.get("population.death_cause")), "death cause is recorded");

        String saved = registry.toJson();
        CharacterRegistry loaded = new CharacterRegistry();
        loaded.fromJson(saved);
        Character restored = loaded.get(stableId);
        ok(restored != null && restored.props.containsKey("population.profession"), "population data survives CharacterRegistry JSON save/load");
        ok(PopulationSimulation.skillRank(restored, "smithing") == 3, "skills survive save/load");
        ok(PopulationSimulation.hasDuty(restored, Duty.SOLDIER), "multiple duties survive save/load");
        ok(PopulationSimulation.profession(restored).equals("armorer"), "current profession survives save/load");
        ok(!restored.alive && restored.props.get("population.death_year").equals(303), "dead characters remain in the persistent registry");

        boolean rejectsDeadAssignment = false;
        try { PopulationSimulation.assignProfession(restored, professionDefs, "farmer", 304); }
        catch (IllegalStateException expected) { rejectsDeadAssignment = true; }
        ok(rejectsDeadAssignment, "dead characters cannot receive new professions");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
