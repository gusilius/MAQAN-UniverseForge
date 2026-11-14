package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.LifeSimulation;
import dev.worldforge.people.CharacterDevelopment;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.troops.MilitaryPsychology;
import dev.worldforge.people.ProfessionDefs;
import dev.worldforge.progress.Progress;
import dev.worldforge.progress.ProgressDefs;
import dev.worldforge.progress.people.PeopleProgressBridge;
import dev.worldforge.people.PopulationSimulation.Duty;
import dev.worldforge.people.PopulationSimulation.Intent;
import dev.worldforge.people.PopulationSimulation.LifeStage;
import dev.worldforge.people.PopulationSimulation.Need;
import dev.worldforge.settle.ResidentSocial;

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
        Character readOnlyProbe = registry.create("ReadOnly", "", "north", "m", 280);
        long readRevision = registry.revision();
        PopulationSimulation.skillRanksSnapshot(readOnlyProbe);
        PopulationSimulation.need(readOnlyProbe, Need.HUNGER);
        PopulationSimulation.skillRank(readOnlyProbe, "smithing");
        PopulationSimulation.trainingXp(readOnlyProbe, "smithing");
        PopulationSimulation.decide(readOnlyProbe, 280);
        ok(!readOnlyProbe.props.containsKey("population.skills")
                        && !readOnlyProbe.props.containsKey("population.needs")
                        && !readOnlyProbe.props.containsKey("population.training")
                        && registry.revision() == readRevision,
                "population read queries do not materialize empty Character state or advance revisions");

        Character smith = registry.create("Aldric", "House Forge", "north", "m", 280);
        CharacterDevelopment.setRace(smith, "elf_forest");
        CharacterDevelopment.setClass(smith, "ranger");
        CharacterDevelopment.setRarity(smith, CharacterDevelopment.Rarity.RARE);
        CharacterDevelopment.setLevel(smith, 12);
        CharacterDevelopment.setMood(smith, CharacterDevelopment.Mood.INSPIRED, 100, 30, "won_a_tournament");
        int calmLearning = CharacterStats.value(smith, "learning");
        ok(CharacterDevelopment.race(smith).equals("elf_forest")
                        && CharacterDevelopment.characterClass(smith).equals("ranger")
                        && CharacterDevelopment.rarity(smith) == CharacterDevelopment.Rarity.RARE
                        && CharacterDevelopment.level(smith) == 12,
                "race, class, rarity and level are independent facets of one Character");
        ok(CharacterDevelopment.effectiveAttribute(smith, "learning", 110) == Math.min(20, calmLearning + 1)
                        && CharacterDevelopment.mood(smith, 131) == CharacterDevelopment.Mood.NEUTRAL,
                "temporary mood modifies effective attributes and expires on the world clock");
        ok(CharacterDevelopment.learnAbility(smith, "forest_sense")
                        && !CharacterDevelopment.learnAbility(smith, "forest_sense")
                        && CharacterDevelopment.abilities(smith).contains("forest_sense")
                        && CharacterDevelopment.forgetAbility(smith, "forest_sense"),
                "ability acquisition is idempotent and persists on the same Character");
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

        ok(PopulationSimulation.progressOwner(smith, "middle_earth").equals("n:" + smith.globalId), "NPC progression owner derives from the immutable global Character UUID");
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

        Character generalWorker = registry.create("General", "", "", "m", 280);
        professionDefs.add("{\"id\":\"laborer\",\"name\":\"General Worker\",\"category\":\"worker\"}");
        PopulationSimulation.assignProfession(generalWorker, professionDefs, "laborer", 300);
        boolean generalTrainingBlocked = false;
        try { PopulationSimulation.train(generalWorker, "smithing", 100); }
        catch (IllegalStateException expected) { generalTrainingBlocked = true; }
        ok(generalTrainingBlocked && PopulationSimulation.skillRank(generalWorker, "smithing") == 0,
                "general workers cannot gain specialist skill ranks through any training caller");

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

        CharacterRegistry batchedRegistry = new CharacterRegistry();
        Character batched = batchedRegistry.create("Batched", "", "north", "f", 280);
        PopulationSimulation.advance(batchedRegistry, 3, 303);
        ok(PopulationSimulation.need(batched, Need.HUNGER) == 30
                        && PopulationSimulation.need(batched, Need.REST) == 18
                        && PopulationSimulation.need(batched, Need.SOCIAL) == 6
                        && PopulationSimulation.need(batched, Need.PURPOSE) == 3
                        && PopulationSimulation.need(batched, Need.LEISURE) == 9
                        && ((Number) batched.props.get("population.simulated_days")).longValue() == 3,
                "batched population advancement preserves exact per-day need drift and persisted elapsed days");

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

        ok(LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.EAT, "eating")
                        && LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.SLEEP, "sleeping")
                        && LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.SOCIALIZE, "socializing")
                        && LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.SEEK_SAFETY, "fleeing"),
                "physical settlement actions map only to their matching Character AI actions");
        ok(!LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.EAT, "sleeping")
                        && !LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.SLEEP, "eating")
                        && !LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.WORK, "socializing")
                        && !LifeSimulation.matchesPhysicalAction(LifeSimulation.Action.EAT, ""),
                "mismatched or missing physical action cannot satisfy a Character need");

        Character conversationalist = registry.create("Conversationalist", "", "north", "m", 280);
        Character companion = registry.create("Companion", "", "north", "f", 280);
        int firstBefore = ResidentSocial.score(conversationalist, companion.globalId);
        int secondBefore = ResidentSocial.score(companion, conversationalist.globalId);
        ResidentSocial.socializeOnce(conversationalist, companion, 3, "life:conversation:one");
        ResidentSocial.socializeOnce(conversationalist, companion, 3, "life:conversation:one");
        ok(ResidentSocial.score(conversationalist, companion.globalId) == firstBefore + 3
                        && ResidentSocial.score(companion, conversationalist.globalId) == secondBefore + 3,
                "replayed conversation applies each participant relationship delta exactly once");
        boolean collisionRejected = false;
        try { ResidentSocial.socializeOnce(conversationalist, smith, 3, "life:conversation:one"); }
        catch (IllegalStateException expected) { collisionRejected = true; }
        ok(collisionRejected && ResidentSocial.score(conversationalist, companion.globalId) == firstBefore + 3,
                "conversation receipt rejects reuse of one operation ID for another participant");
        boolean changedPayloadRejected = false;
        try { ResidentSocial.socializeOnce(conversationalist, companion, 4, "life:conversation:one"); }
        catch (IllegalStateException expected) { changedPayloadRejected = true; }
        ok(changedPayloadRejected && ResidentSocial.score(conversationalist, companion.globalId) == firstBefore + 3,
                "conversation receipt rejects changed relationship delta on replay");

        Character delayed = registry.create("Delayed", "", "north", "m", 280);
        PopulationSimulation.setNeed(delayed, Need.HUNGER, 90);
        LifeSimulation.Decision travelling = LifeSimulation.tick(delayed, activeMorning,
                (person, action, context) -> LifeSimulation.Outcome.IN_PROGRESS);
        ok(travelling.outcome() == LifeSimulation.Outcome.IN_PROGRESS
                        && PopulationSimulation.need(delayed, Need.HUNGER) == 90
                        && "EAT".equals(delayed.props.get("population.ai.pending_action")),
                "a planned daily-life action remains pending and grants no need relief before completion");
        LifeSimulation.Decision completed = LifeSimulation.tick(delayed,
                new LifeSimulation.Context(421, true, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE),
                (person, action, context) -> LifeSimulation.Outcome.SUCCESS);
        ok(completed.outcome() == LifeSimulation.Outcome.SUCCESS
                        && PopulationSimulation.need(delayed, Need.HUNGER) == 20
                        && !delayed.props.containsKey("population.ai.pending_action")
                        && !LifeSimulation.completePending(delayed),
                "authoritative completion applies pending effects exactly once and is idempotent");

        Character interrupted = registry.create("Interrupted", "", "north", "m", 280);
        PopulationSimulation.setNeed(interrupted, Need.HUNGER, 90);
        LifeSimulation.tick(interrupted, activeMorning,
                (person, action, context) -> LifeSimulation.Outcome.IN_PROGRESS);
        LifeSimulation.Decision interruptedDecision = LifeSimulation.tick(interrupted,
                new LifeSimulation.Context(421, true, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE),
                (person, action, context) -> LifeSimulation.Outcome.BLOCKED);
        ok(interruptedDecision.outcome() == LifeSimulation.Outcome.BLOCKED
                        && PopulationSimulation.need(interrupted, Need.HUNGER) == 90
                        && !interrupted.props.containsKey("population.ai.pending_action"),
                "interrupted daily-life actions are cancelled without granting effects");

        Character fallbackNpc = registry.create("Tarin", "", "north", "m", 280);
        PopulationSimulation.setNeed(fallbackNpc, Need.HUNGER, 90);
        PopulationSimulation.setNeed(fallbackNpc, Need.REST, 65);
        java.util.List<LifeSimulation.Action> fallbackAttempts = new java.util.ArrayList<>();
        LifeSimulation.Decision fallbackDecision = LifeSimulation.tick(fallbackNpc,
                new LifeSimulation.Context(700, true, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE),
                new LifeSimulation.Executor() {
                    @Override public LifeSimulation.Outcome execute(Character person, LifeSimulation.Action action, LifeSimulation.Context context) {
                        fallbackAttempts.add(action);
                        return action == LifeSimulation.Action.EAT ? LifeSimulation.Outcome.BLOCKED : LifeSimulation.Outcome.SUCCESS;
                    }
                    @Override public String failureReason(Character person, LifeSimulation.Action action, LifeSimulation.Context context, LifeSimulation.Outcome outcome) {
                        return "no_edible_target_found";
                    }
                });
        Object failureHistory = fallbackNpc.props.get("population.ai.failure_history");
        ok(fallbackDecision.action() == LifeSimulation.Action.SLEEP
                        && fallbackDecision.outcome() == LifeSimulation.Outcome.SUCCESS
                        && fallbackAttempts.size() == 2 && fallbackAttempts.get(0) == LifeSimulation.Action.EAT,
                "blocked survival action triggers a bounded alternative and the NPC completes the fallback");
        ok(PopulationSimulation.need(fallbackNpc, Need.HUNGER) == 90
                        && PopulationSimulation.need(fallbackNpc, Need.REST) == 0
                        && failureHistory instanceof java.util.List<?> history && !history.isEmpty()
                        && "no_edible_target_found".equals(((java.util.Map<?,?>) history.get(0)).get("reason")),
                "failed action does not grant free effects; exact failure reason is retained in Character memory");

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
        ok(npcOwner.equals("n:" + smith.globalId), "progress profile owner is derived from global Character UUID");
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
        ok(saved.contains("wf-people/3"), "Character saves use the versioned lifecycle schema");
        CharacterRegistry legacyLoaded = new CharacterRegistry();
        legacyLoaded.fromJson(saved.replace("wf-people/3", "wf-people/1"));
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

        // Stable CK-style psychology is generated from the canonical identity, persisted, and used by daily AI.
        Character psychological = registry.create("Aster", "", "north", "m", 280);
        psychological.traits.add("gregarious");
        psychological.traits.add("diligent");
        psychological.traits.add("curious");
        CharacterDevelopment.ensurePsychology(psychological);
        int sociability = CharacterDevelopment.temperament(psychological, CharacterDevelopment.Temperament.SOCIABILITY);
        int dutyDrive = CharacterDevelopment.drive(psychological, CharacterDevelopment.Drive.DUTY);
        ok(sociability >= 50 && dutyDrive >= 50,
                "stable temperament and motivations are shaped by permanent traits");
        ok(CharacterDevelopment.temperament(psychological, CharacterDevelopment.Temperament.SOCIABILITY) == sociability
                        && CharacterDevelopment.drive(psychological, CharacterDevelopment.Drive.DUTY) == dutyDrive,
                "generated psychological profile remains deterministic for one Character identity");
        CharacterDevelopment.setTemperament(psychological, CharacterDevelopment.Temperament.SOCIABILITY, 100);
        CharacterDevelopment.setDrive(psychological, CharacterDevelopment.Drive.COMMUNITY, 100);
        PopulationSimulation.setNeed(psychological, Need.SOCIAL, 60);
        LifeSimulation.Context personalityContext = new LifeSimulation.Context(
                1200, true, true, true, true, true, true, true, false, LifeSimulation.LOD.ACTIVE);
        double socialUtility = LifeSimulation.utility(psychological, personalityContext, LifeSimulation.Action.SOCIALIZE);
        Character loner = registry.create("Bram", "", "north", "m", 280);
        loner.traits.add("reclusive");
        CharacterDevelopment.setTemperament(loner, CharacterDevelopment.Temperament.SOCIABILITY, 0);
        CharacterDevelopment.setDrive(loner, CharacterDevelopment.Drive.COMMUNITY, 0);
        PopulationSimulation.setNeed(loner, Need.SOCIAL, 60);
        double lonerUtility = LifeSimulation.utility(loner, personalityContext, LifeSimulation.Action.SOCIALIZE);
        ok(socialUtility > lonerUtility + 25,
                "two people with the same social need prioritize social contact differently by personality");
        CharacterDevelopment.setTemperament(psychological, CharacterDevelopment.Temperament.STABILITY, 50);
        CharacterDevelopment.setTemperament(loner, CharacterDevelopment.Temperament.STABILITY, 50);
        CharacterDevelopment.setDrive(psychological, CharacterDevelopment.Drive.ADVENTURE, 50);
        CharacterDevelopment.setDrive(loner, CharacterDevelopment.Drive.ADVENTURE, 50);
        CharacterDevelopment.setMood(psychological, CharacterDevelopment.Mood.STRESSED, 1200, 300, "court pressure");
        ok(LifeSimulation.utility(psychological, personalityContext, LifeSimulation.Action.RECREATE)
                        > LifeSimulation.utility(loner, personalityContext, LifeSimulation.Action.RECREATE),
                "temporary stress changes recreation priorities without replacing permanent personality");
        CharacterDevelopment.changeStress(psychological, 40);
        ok(CharacterDevelopment.stress(psychological) == 40, "psychological stress is bounded and character-owned");
        String psychologySave = registry.toJson();
        CharacterRegistry psychologyReload = new CharacterRegistry();
        psychologyReload.fromJson(psychologySave);
        Character restoredPsychological = psychologyReload.get(psychological.id);
        ok(CharacterDevelopment.temperament(restoredPsychological, CharacterDevelopment.Temperament.SOCIABILITY) == 100
                        && CharacterDevelopment.drive(restoredPsychological, CharacterDevelopment.Drive.COMMUNITY) == 100
                        && CharacterDevelopment.stress(restoredPsychological) == 40,
                "temperament, motivations and stress survive CharacterRegistry save/load");

        Character evolving = registry.create("Mara", "", "north", "f", 260);
        evolving.traits.add("honest");
        CharacterDevelopment.recordConduct(evolving, CharacterDevelopment.Conduct.BETRAYAL, psychological,
                "test:betrayal:one", 10);
        int stressAfter = CharacterDevelopment.stress(evolving);
        int duplicateConductDelta = CharacterDevelopment.recordConduct(evolving, CharacterDevelopment.Conduct.BETRAYAL,
                psychological, "test:betrayal:one", 10);
        ok(stressAfter > 0 && duplicateConductDelta == 0 && CharacterDevelopment.stress(evolving) == stressAfter,
                "acting against honesty creates stress and duplicate conduct receipts are ignored");
        CharacterDevelopment.setDrive(evolving, CharacterDevelopment.Drive.SECURITY, 40);
        CharacterDevelopment.recordExperience(evolving, CharacterDevelopment.Experience.BETRAYED,
                "test:betrayed:one", "trusted ally betrayed Mara", 20, 2);
        CharacterDevelopment.recordExperience(evolving, CharacterDevelopment.Experience.BETRAYED,
                "test:betrayed:two", "second betrayal", 30, 2);
        ok(CharacterDevelopment.memories(evolving, 10).size() >= 3
                        && CharacterDevelopment.drive(evolving, CharacterDevelopment.Drive.SECURITY) > 40,
                "personal memory and repeated betrayal alter long-term security motivation");
        CharacterDevelopment.recordExperience(evolving, CharacterDevelopment.Experience.BETRAYED,
                "test:betrayed:three", "third betrayal", 40, 2);
        ok(evolving.traits.contains("paranoid") || evolving.traits.contains("cynical"),
                "repeated experiences can unlock a compatible personality trait");

        Character parentA = registry.create("Parent A", "House", "north", "m", 240);
        Character parentB = registry.create("Parent B", "House", "north", "f", 241);
        CharacterStats.addTrait(parentA, "compassionate");
        CharacterStats.addTrait(parentB, "diligent");
        CharacterDevelopment.setTemperament(parentA, CharacterDevelopment.Temperament.EMPATHY, 95);
        CharacterDevelopment.setTemperament(parentB, CharacterDevelopment.Temperament.DISCIPLINE, 90);
        Character inheritedChild = registry.addChild(parentA, parentB, "Child", "f", 280);
        ok(inheritedChild.props.containsKey("personality.temperament.empathy")
                        && inheritedChild.props.containsKey("personality.drive.family")
                        && CharacterDevelopment.memories(inheritedChild, 5).stream().anyMatch(m -> "birth".equals(m.get("kind"))),
                "children inherit blended psychology and begin with a personal birth memory");
        int empathyBefore = CharacterDevelopment.temperament(inheritedChild, CharacterDevelopment.Temperament.EMPATHY);
        CharacterDevelopment.upbringingTick(inheritedChild, parentA, parentB, 281);
        ok(CharacterDevelopment.temperament(inheritedChild, CharacterDevelopment.Temperament.EMPATHY) >= empathyBefore
                        && ((Number) inheritedChild.props.get("family.upbringing.last_year")).intValue() == 281,
                "annual upbringing applies parental influence once per year");
        CharacterDevelopment.upbringingTick(inheritedChild, parentA, parentB, 281);
        ok(((Number) inheritedChild.props.get("family.upbringing.last_year")).intValue() == 281,
                "replayed upbringing year is idempotent");

        Character wounded = registry.create("Ser Edric", "House North", "north", "m", 270);
        CharacterDevelopment.setMilitaryMorale(wounded, 90);
        boolean injuryApplied = MilitaryPsychology.recordInjury(wounded, 2, "test:combat-hit", "arrow", 900);
        int injuryStress = CharacterDevelopment.stress(wounded);
        int injuryMorale = CharacterDevelopment.militaryMorale(wounded);
        boolean duplicateInjury = MilitaryPsychology.recordInjury(wounded, 2, "test:combat-hit", "arrow", 900);
        ok(injuryApplied && !duplicateInjury && CharacterDevelopment.injurySeverity(wounded) == 2
                        && injuryStress > 0 && injuryMorale < 90,
                "combat wounds create one persistent injury, trauma and morale loss per hit receipt");

        Character fallenComrade = registry.create("Dain", "House North", "north", "m", 268);
        Character witness = registry.create("Ronan", "House North", "north", "m", 269);
        MilitaryPsychology.recordComradeLoss(witness, fallenComrade, "test:comrade-loss", 1200);
        int comradeBond = ResidentSocial.score(witness, fallenComrade.globalId);
        int comradeStress = CharacterDevelopment.stress(witness);
        MilitaryPsychology.recordComradeLoss(witness, fallenComrade, "test:comrade-loss", 1200);
        ok(comradeBond > 0 && comradeStress > 0
                        && ResidentSocial.score(witness, fallenComrade.globalId) == comradeBond
                        && CharacterDevelopment.stress(witness) == comradeStress,
                "a fallen squadmate strengthens a canonical social bond and records grief exactly once");

        Character victor = registry.create("Captain Arlen", "House North", "north", "m", 250);
        CharacterDevelopment.setMilitaryMorale(victor, 50);
        MilitaryPsychology.recordBattleOutcome(victor, true, "test:battle-victory", 1500, 2);
        int victoryMorale = CharacterDevelopment.militaryMorale(victor);
        MilitaryPsychology.recordBattleOutcome(victor, true, "test:battle-victory", 1500, 2);
        ok(victoryMorale > 50 && CharacterDevelopment.militaryMorale(victor) == victoryMorale,
                "battle victory raises canonical soldier morale and is replay-safe");

        Character deceased = registry.create("Lord Alaric", "House North", "north", "m", 230);
        Character widow = registry.create("Lady Elin", "House North", "north", "f", 232);
        Character orphanedChild = registry.addChild(deceased, widow, "Tomas", "m", 260);
        Character trustedFriend = registry.create("Old Friend", "House North", "north", "m", 231);
        registry.marry(deceased.id, widow.id, 260);
        ResidentSocial.socialize(deceased, trustedFriend, 75);
        PopulationSimulation.die(registry, deceased.id, 304, "battle");
        int widowGrief = CharacterDevelopment.stress(widow);
        int childGrief = CharacterDevelopment.stress(orphanedChild);
        int friendGrief = CharacterDevelopment.stress(trustedFriend);
        registry.kill(deceased.id);
        ok(widowGrief > 0 && childGrief > 0 && friendGrief > 0
                        && CharacterDevelopment.stress(widow) == widowGrief
                        && CharacterDevelopment.stress(orphanedChild) == childGrief
                        && CharacterDevelopment.stress(trustedFriend) == friendGrief,
                "death creates idempotent bereavement for spouse, child and trusted friend");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
