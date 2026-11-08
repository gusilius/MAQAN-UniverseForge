package dev.worldforge.progress.people;

import dev.worldforge.people.Character;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.progress.Profile;
import dev.worldforge.progress.Progress;

import java.util.Map;
import java.util.Objects;

/**
 * Optional bridge between wf-people and wf-progress.
 * The Character owns the stable identity and the canonical skill/attribute ranks.
 * Progression profiles keep XP, levels, points and perk nodes, but are keyed by
 * the world-scoped Character ID and mirror ranks back to the Character.
 */
public final class PeopleProgressBridge {
    private PeopleProgressBridge() { }

    public static String owner(Character character, String populationScopeId) {
        return PopulationSimulation.progressOwner(character, populationScopeId);
    }

    public static Profile profile(Progress progress, Character character, String populationScopeId) {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(character, "character");
        String owner = owner(character, populationScopeId);
        Profile profile = progress.profile(owner);
        syncFromCharacter(character, profile);
        return profile;
    }

    /** NPC practice updates the Character first, then mirrors the resulting ranks into the RPG sheet. */
    public static int train(Progress progress, Character character, String populationScopeId, String skillId, long xp) {
        Profile profile = profile(progress, character, populationScopeId);
        int gained = PopulationSimulation.train(character, skillId, xp);
        syncFromCharacter(character, profile);
        return gained;
    }

    /** RPG skill-point allocation is reflected back into the same Character record. */
    public static boolean learnSkill(Progress progress, Character character, String populationScopeId, String skillId) {
        Profile profile = profile(progress, character, populationScopeId);
        boolean learned = progress.learnSkill(owner(character, populationScopeId), skillId);
        if (learned) syncToCharacter(character, profile);
        return learned;
    }

    public static void syncFromCharacter(Character character, Profile profile) {
        Map<String, Integer> ranks = PopulationSimulation.skillRanksSnapshot(character);
        profile.skills.clear();
        profile.skills.putAll(ranks);
    }

    public static void syncToCharacter(Character character, Profile profile) {
        PopulationSimulation.syncSkillRanks(character, profile.skills);
    }
}
