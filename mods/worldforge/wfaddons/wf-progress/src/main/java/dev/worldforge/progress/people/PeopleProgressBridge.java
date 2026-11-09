package dev.worldforge.progress.people;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
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
        migrateLegacyProfile(progress, character, profile);
        syncFromCharacter(character, profile);
        return profile;
    }

    /** Migrate the pre-scope NPC key only when that Character ID is unique across loaded map registries. */
    private static void migrateLegacyProfile(Progress progress, Character character, Profile target) {
        Profile legacy = progress.peek("n:" + character.id);
        if (legacy == null || legacy == target || !isUniqueCharacterId(character.id)) return;
        boolean fresh = target.level == 1 && target.xp == 0 && target.totalXp == 0
                && target.attrFree == 0 && target.skillFree == 0 && target.passiveFree == 0
                && target.attrs.isEmpty() && target.skills.isEmpty() && target.nodes.isEmpty();
        if (!fresh) return;
        target.level = legacy.level;
        target.xp = legacy.xp;
        target.totalXp = legacy.totalXp;
        target.attrFree = legacy.attrFree;
        target.skillFree = legacy.skillFree;
        target.passiveFree = legacy.passiveFree;
        target.archetype = legacy.archetype;
        target.attrs.putAll(legacy.attrs);
        target.skills.putAll(legacy.skills);
        target.nodes.addAll(legacy.nodes);
        target.respecs = legacy.respecs;
    }

    private static boolean isUniqueCharacterId(String id) {
        try {
            Class<?> peopleMod = Class.forName("dev.worldforge.people.mc.PeopleMod");
            Object raw = peopleMod.getMethod("registries").invoke(null);
            if (!(raw instanceof Map<?, ?> registries)) return false;
            int matches = 0;
            for (Object value : registries.values()) {
                if (!(value instanceof CharacterRegistry registry)) continue;
                for (Character character : registry.all()) if (id.equals(character.id) && ++matches > 1) return false;
            }
            return matches == 1;
        } catch (ReflectiveOperationException ex) {
            return false;
        }
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
        Map<String, Integer> merged = new java.util.LinkedHashMap<>(profile.skills);
        merged.putAll(PopulationSimulation.skillRanksSnapshot(character));
        PopulationSimulation.syncSkillRanks(character, merged);
        profile.skills.clear();
        profile.skills.putAll(merged);
    }

    public static void syncToCharacter(Character character, Profile profile) {
        PopulationSimulation.syncSkillRanks(character, profile.skills);
    }

    /** Synchronizes only NPCs that already carry skills or have an RPG profile. Reflection keeps wf-people optional. */
    public static void syncAll(Progress progress) {
        try {
            Class<?> peopleMod = Class.forName("dev.worldforge.people.mc.PeopleMod");
            Object raw = peopleMod.getMethod("registries").invoke(null);
            if (!(raw instanceof Map<?, ?> registries)) return;
            for (Map.Entry<?, ?> entry : registries.entrySet()) {
                Object mapData = entry.getKey();
                Object rawRegistry = entry.getValue();
                if (!(rawRegistry instanceof dev.worldforge.people.CharacterRegistry registry)) continue;
                String scope = String.valueOf(mapData.getClass().getField("name").get(mapData));
                for (Character character : registry.all()) {
                    String owner = owner(character, scope);
                    Profile profile = progress.peek(owner);
                    Map<String, Integer> ranks = PopulationSimulation.skillRanksSnapshot(character);
                    if (profile == null && ranks.isEmpty() && progress.peek("n:" + character.id) == null) continue;
                    profile = profile(progress, character, scope);
                    if (ranks.isEmpty() && !profile.skills.isEmpty()) syncToCharacter(character, profile);
                    else syncFromCharacter(character, profile);
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("wf_people registry bridge failed", ex);
        }
    }
}
