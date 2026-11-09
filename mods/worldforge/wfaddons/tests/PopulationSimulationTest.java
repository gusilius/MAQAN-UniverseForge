package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.LifeSimulation;
import dev.worldforge.people.ProfessionDefs;
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
        java.util.List<CharacterRegistry.LifecycleEvent> lifecycle = new java.util.ArrayList<>();
        registry.addListener(new CharacterRegistry.Listener() {
            @Override public void lifecycle(Character c, CharacterRegistry.LifecycleEvent event) { lifecycle.add(event); }
        });
        Character smith = registry.create("Aldric", "House Forge", "north", "m", 280);
        String stableId = smith.id;
        ok(lifecycle.size() == 1 && lifecycle.get(0).kind() == CharacterRegistry.LifecycleKind.BORN,
                "birth emits one canonical lifecycle event");
        Character childStage = registry.create("Nell", "", "north", "f", 295);
        registry.refreshLifeStages(300);
        registry.refreshLifeStages(301);
        ok(PopulationSimulation.lifeStage(childStage, 301) == PopulationSimulation.LifeStage.CHILD
                        && lifecycle.stream().anyMatch(e -> e.kind() == CharacterRegistry.LifecycleKind.LIFE_STAGE_CHANGED
                        && e.characterId().equals(childStage.id) && e.from().equals("infant") && e.to().equals("child")),
                "calendar aging emits lifecycle events at life-stage boundaries");
        registry.hire(smith.id, "settlement:forge", "farmer", 300);
        ok(PopulationSimulation.hasDuty(smith, Duty.WORKER)
                        && smith.has("settlement_worker", "settlement:forge"),
                "hiring assigns an existing Character without allocating another identity");
        int hiredEventCount = lifecycle.size();
        registry.hire(smith.id, "settlement:forge", "farmer", 300);
        ok(lifecycle.size() == hiredEventCount,
                "repeated hire is idempotent and does not duplicate lifecycle events");
        registry.fire(smith.id, "settlement:forge", 301);
        ok(!PopulationSimulation.hasDuty(smith, Duty.WORKER)
                        && !smith.has("settlement_worker", "settlement:forge"),
                "firing releases employment but preserves the person");
        registry.migrate(smith.id, "settlement:river", 302);
        ok("settlement:river".equals(smith.props.get("population.location"))
                        && registry.get(stableId) == smith,
                "migration changes location without changing Character ID");
        Character other = registry.create("Other", "", "north", "m", 280);
        ok(registry.bindEntity(smith.id, "npc", "entity-uuid-1")
                        && !registry.bindEntity(other.id, "npc", "entity-uuid-1"),
                "one Minecraft entity UUID cannot be bound to two Characters");
        String boundSave = registry.toJson();
        CharacterRegistry restoredBindings = new CharacterRegistry();
        restoredBindings.fromJson(boundSave);
        ok(restoredBindings.get(stableId).props.containsKey("population.entity_bindings"),
                "Minecraft entity bindings survive registry save/load");

        ok(PopulationSimulation.progressOwner(smith, "middle_earth").equals("n:middle_earth:" + stableId), "NPC progression owner derives from the one Character ID");
        ok(PopulationSimulation.age(smith, 300) == 20, "age derives from the shared world calendar");
        ok(PopulationSimulation.lifeStage(smith, 300) == LifeStage.ADULT, "life-stage thresholds are deterministic");

        ProfessionDefs professionDefs = new ProfessionDefs();
        professionDefs.add("{\"id\":\"farmer\",\"name\":\"Farmer\",\"category\":\"worker\"}");
        professionDefs.add("{\"id\":\"blacksmith\",\"name\":\"Blacksmith\",\"category\":\"specialist\",\"required_skills\":{\"smithing\":1}}");
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

        professionDefs.add("{\"id\":\"armorer\",\"name\":\"Armorer\",\"category\":\"specialist\"}");
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
        PopulationSimulation.promoteToHero(smith);
        PopulationSimulation.setCommand(smith, "defend_capital");
        ok(PopulationSimulation.decide(smith, 302) == Intent.FOLLOW_COMMAND, "hero is the same Character and follows a strategic command");
        PopulationSimulation.setCommand(smith, "");
        ok(PopulationSimulation.hasDuty(smith, Duty.HERO) && PopulationSimulation.hasDuty(smith, Duty.SOLDIER), "hero and soldier duties coexist without a second identity");

        Progress progress = new Progress(new ProgressDefs());
        Character legacyNpc = registry.create("Legacy", "", "north", "m", 280);
        String legacyOwner = PeopleProgressBridge.owner(legacyNpc, "middle_earth");
        progress.profile(legacyOwner).skills.put("archery", 4);
        PeopleProgressBridge.profile(progress, legacyNpc, "middle_earth");
        ok(PopulationSimulation.skillRank(legacyNpc, "archery") == 4,
                "legacy NPC progression ranks migrate into the same Character without data loss");

        Character child = registry.create("Mira", "", "north", "f", 296);
        ok(PopulationSimulation.lifeStage(child, 300) == LifeStage.INFANT, "infant classification");
        ok(PopulationSimulation.decide(child, 300) == Intent.IDLE, "infants do not receive adult work or combat intents");
        ok(!PopulationSimulation.canWork(child, 300), "infants cannot be assigned work");

        PopulationSimulation.advance(registry, 1, 303);
        ok(PopulationSimulation.age(smith, 303) == 23, "population clock advances calendar age");
        ok(PopulationSimulation.lastIntent(smith) == Intent.PATROL, "daily simulation refreshes deterministic AI intent");
        ok(((Number) smith.props.get("population.simulated_days")).longValue() == 1, "elapsed population days are persisted on the Character");
        ok(PopulationSimulation.need(smith, Need.HUNGER) == 10 && PopulationSimulation.need(smith, Need.REST) == 6,
                "shared needs accumulate from the same world-day clock");

        Character autonomous = registry.create("Rowan", "", "north", "m", 280);
        PopulationSimulation.setNeed(autonomous, Need.HUNGER, 92);
        LifeSimulation.Context activeMorning = new LifeSimulation.Context(
                420, true, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE);
        java.util.List<LifeSimulation.Action> executed = new java.util.ArrayList<>();
        LifeSimulation.Decision eatDecision = LifeSimulation.tick(autonomous, activeMorning, (person, action, context) -> {
            executed.add(action);
            return LifeSimulation.Outcome.SUCCESS;
        });
        ok(eatDecision.action() == LifeSimulation.Action.EAT && eatDecision.outcome() == LifeSimulation.Outcome.SUCCESS,
                "behavior-tree survival branch selects eating for critical hunger");
        ok(executed.equals(java.util.List.of(LifeSimulation.Action.EAT))
                        && PopulationSimulation.need(autonomous, Need.HUNGER) == 22,
                "AI executor performs the action and only then satisfies hunger");

        Character blocked = registry.create("Bram", "", "north", "m", 280);
        PopulationSimulation.setNeed(blocked, Need.HUNGER, 90);
        LifeSimulation.Context blockedContext = new LifeSimulation.Context(
                600, false, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE);
        LifeSimulation.Decision blockedDecision = LifeSimulation.tick(blocked, blockedContext,
                (person, action, context) -> LifeSimulation.Outcome.BLOCKED);
        ok(blockedDecision.action() != LifeSimulation.Action.EAT
                        && PopulationSimulation.need(blocked, Need.HUNGER) == 90,
                "unavailable resources and blocked actions do not grant free need relief");

        Character scheduled = registry.create("Edda", "", "north", "f", 280);
        PopulationSimulation.setNeed(scheduled, Need.REST, 60);
        LifeSimulation.Context night = new LifeSimulation.Context(
                1380, true, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE);
        ok(LifeSimulation.tick(scheduled, night, (person, action, context) -> LifeSimulation.Outcome.SUCCESS).action()
                        == LifeSimulation.Action.SLEEP,
                "night schedule biases a tired Character toward sleep");
        PopulationSimulation.setNeed(scheduled, Need.LEISURE, 75);
        ok(LifeSimulation.goapPlan(scheduled, night).contains(LifeSimulation.Action.RECREATE),
                "GOAP planner includes recreation as a satisfiable leisure goal");

        Character remote = registry.create("Mira Remote", "", "north", "f", 280);
        LifeSimulation.Context remoteContext = new LifeSimulation.Context(
                1000, true, true, true, true, true, true, true, false, LifeSimulation.LOD.REMOTE);
        LifeSimulation.Decision remoteFirst = LifeSimulation.tick(remote, remoteContext,
                (person, action, context) -> LifeSimulation.Outcome.SUCCESS);
        LifeSimulation.Decision remoteSecond = LifeSimulation.tick(remote,
                new LifeSimulation.Context(1001, true, true, true, true, true, true, true, false, LifeSimulation.LOD.REMOTE),
                (person, action, context) -> LifeSimulation.Outcome.SUCCESS);
        ok(remoteFirst.nextThinkMinute() == 1360 && remoteSecond.reason().equals("thinking-cooldown"),
                "remote LOD batches autonomous thinking without ticking every minute");

        int bridgeRanks = PeopleProgressBridge.train(progress, smith, "middle_earth", "tactics", 200);
        ok(bridgeRanks == 2, "NPC training uses the unified Character-backed skill record");
        String npcOwner = PeopleProgressBridge.owner(smith, "middle_earth");
        ok(npcOwner.equals("n:middle_earth:" + stableId), "progress profile owner is derived from scoped Character ID");
        ok(progress.profile(npcOwner).skill("tactics") == PopulationSimulation.skillRank(smith, "tactics"), "RPG profile mirrors canonical Character skill ranks");
        progress.profile(npcOwner).skills.put("tactics", 3);
        PeopleProgressBridge.syncToCharacter(smith, progress.profile(npcOwner));
        ok(PopulationSimulation.skillRank(smith, "tactics") == 3, "RPG skill allocation writes back to the same Character");
        PopulationSimulation.setTrainingXp(smith, "combat", 750);
        PopulationSimulation.setSkillRank(smith, "combat", 3);
        ok(PopulationSimulation.trainingXp(smith, "combat") == 750 && PopulationSimulation.skillRank(smith, "combat") == 3,
                "individual soldier combat progression can use the shared Character skill record");

        Character spouse = registry.create("Elena", "", "north", "f", 281);
        registry.marry(smith.id, spouse.id, 300);
        ok(lifecycle.stream().anyMatch(e -> e.kind() == CharacterRegistry.LifecycleKind.MARRIED && e.characterId().equals(smith.id)),
                "marriage emits lifecycle events for both existing Characters");
        Character heir = registry.addChild(smith, spouse, "Rowan", "m", 301);
        ok(lifecycle.stream().anyMatch(e -> e.kind() == CharacterRegistry.LifecycleKind.CHILD_BORN
                        && e.characterId().equals(heir.id) && e.from().equals(smith.id) && e.to().equals(spouse.id)),
                "child birth event carries the same canonical parent Character IDs");
        PopulationSimulation.die(registry, smith.id, 303, "battle");
        ok(!smith.alive && registry.get(stableId) == smith, "death preserves the same Character record and ID");
        ok(registry.get(heir.id).father.equals(stableId), "family graph keeps the deceased parent's identity");
        ok(registry.get(stableId).props.get("population.death_year").equals(303), "death year is recorded");
        ok("battle".equals(registry.get(stableId).props.get("population.death_cause")), "death cause is recorded");

        String saved = registry.toJson();
        ok(saved.contains("wf-people/2"), "Character saves use the versioned lifecycle schema");
        CharacterRegistry legacyLoaded = new CharacterRegistry();
        legacyLoaded.fromJson(saved.replace("wf-people/2", "wf-people/1"));
        ok(legacyLoaded.get(stableId) != null
                        && legacyLoaded.get(stableId).id.equals(stableId)
                        && legacyLoaded.get(stableId).children.equals(smith.children),
                "wf-people/1 saves migrate in place without regenerating Character identity");
        CharacterRegistry loaded = new CharacterRegistry();
        loaded.fromJson(saved);
        Character restored = loaded.get(stableId);
        ok(restored != null && restored.props.containsKey("population.profession"), "population data survives CharacterRegistry JSON save/load");
        ok(PopulationSimulation.skillRank(restored, "smithing") == 3, "skills survive save/load");
        ok(PopulationSimulation.hasDuty(restored, Duty.SOLDIER), "multiple duties survive save/load");
        ok(PopulationSimulation.profession(restored).equals("armorer"), "current profession survives save/load");
        ok(!restored.alive && ((Number) restored.props.get("population.death_year")).intValue() == 303, "dead characters remain in the persistent registry");

        boolean rejectsDeadAssignment = false;
        try { PopulationSimulation.assignProfession(restored, professionDefs, "farmer", 304); }
        catch (IllegalStateException expected) { rejectsDeadAssignment = true; }
        ok(rejectsDeadAssignment, "dead characters cannot receive new professions");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
