package dev.worldforge.people;

import java.util.*;

/**
 * Shared population domain for every person in a WorldForge world.
 *
 * A Character remains the only identity. Profession, population duty, RPG profile,
 * military command and Minecraft body are projections/references of that identity,
 * never independent people. This class is deliberately free of Minecraft classes.
 */
public final class PopulationSimulation {
    public static final int MAX_NEED = 100;
    public static final int SKILL_XP_PER_RANK = 100;
    public static final int MAX_SKILL_RANK = 20;

    public enum Need { HUNGER, REST, SAFETY, SOCIAL, PURPOSE }
    public enum Duty { WORKER, SPECIALIST, SOLDIER, HERO }
    public enum LifeStage { INFANT, CHILD, ADOLESCENT, ADULT, ELDER }
    public enum Intent { EAT, REST, SEEK_SAFETY, SOCIALIZE, WORK, TRAIN, PATROL, FOLLOW_COMMAND, IDLE }

    private static final String PROFESSION = "population.profession";
    private static final String PROFESSION_HISTORY = "population.profession_history";
    private static final String DUTIES = "population.duties";
    private static final String NEEDS = "population.needs";
    private static final String SKILLS = "population.skills";
    private static final String TRAINING = "population.training";
    private static final String TRAINING_TARGET = "population.training_target";
    private static final String LAST_INTENT = "population.last_intent";

    private PopulationSimulation() { }

    /** Canonical owner key understood by wf-progress for NPC profiles. */
    public static String progressOwner(Character character, String populationScopeId) {
        Objects.requireNonNull(character, "character");
        String rawScope = Objects.requireNonNull(populationScopeId, "populationScopeId").trim().toLowerCase(Locale.ROOT);
        if (rawScope.isEmpty()) throw new IllegalArgumentException("population scope id is required");
        String scope = rawScope.matches("[a-z0-9_.:-]{1,64}") ? rawScope
                : UUID.nameUUIDFromBytes(rawScope.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return "n:" + scope + ":" + character.id;
    }

    /** Immutable copy used by the shared RPG progression adapter. */
    public static Map<String, Integer> skillRanksSnapshot(Character character) {
        Map<String, Integer> copy = new TreeMap<>();
        for (var entry : mapProp(character, SKILLS).entrySet()) {
            if (entry.getValue() instanceof Number n) copy.put(entry.getKey(), clamp(n.intValue(), 0, MAX_SKILL_RANK));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Imports ranks from the shared RPG progression profile into this Character's canonical skill store. */
    public static void syncSkillRanks(Character character, Map<String, Integer> ranks) {
        requireAlive(character);
        Map<String, Object> target = mapProp(character, SKILLS);
        target.clear();
        for (var entry : ranks.entrySet()) {
            String id = normalizeId(entry.getKey(), "skill");
            target.put(id, clamp(entry.getValue(), 0, MAX_SKILL_RANK));
        }
    }

    public static int age(Character character, int year) {
        return Math.max(0, year - character.birthYear);
    }

    public static LifeStage lifeStage(Character character, int year) {
        int age = age(character, year);
        if (age < 6) return LifeStage.INFANT;
        if (age < 13) return LifeStage.CHILD;
        if (age < 18) return LifeStage.ADOLESCENT;
        if (age < 60) return LifeStage.ADULT;
        return LifeStage.ELDER;
    }

    public static boolean canWork(Character character, int year) {
        LifeStage stage = lifeStage(character, year);
        return character.alive && (stage == LifeStage.ADOLESCENT || stage == LifeStage.ADULT || stage == LifeStage.ELDER);
    }

    public static String profession(Character character) {
        Object value = character.props.get(PROFESSION);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Changes the current profession without deleting previous skill ranks.
     * Profession IDs are stable data identifiers, not localized display names.
     */
    public static void assignProfession(Character character, String professionId, int year) {
        requireAlive(character);
        String next = normalizeId(professionId, "profession");
        String previous = profession(character);
        if (previous.equals(next)) return;
        if (!previous.isEmpty()) {
            List<Object> history = listProp(character, PROFESSION_HISTORY);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", previous);
            entry.put("ended_year", year);
            history.add(entry);
        }
        character.props.put(PROFESSION, next);
        character.props.put("population.profession_started_year", year);
    }

    public static List<String> professionHistory(Character character) {
        List<String> result = new ArrayList<>();
        Object raw = character.props.get(PROFESSION_HISTORY);
        if (raw instanceof List<?> list) for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("id") instanceof String id) result.add(id);
        }
        return Collections.unmodifiableList(result);
    }

    /** Duties are orthogonal tags: one person may be a specialist and a soldier in reserve. */
    public static void addDuty(Character character, Duty duty) {
        requireAlive(character);
        List<Object> duties = listProp(character, DUTIES);
        if (!duties.contains(duty.name().toLowerCase(Locale.ROOT))) duties.add(duty.name().toLowerCase(Locale.ROOT));
    }

    public static void removeDuty(Character character, Duty duty) {
        listProp(character, DUTIES).remove(duty.name().toLowerCase(Locale.ROOT));
    }

    public static boolean hasDuty(Character character, Duty duty) {
        Object raw = character.props.get(DUTIES);
        return raw instanceof List<?> list && list.contains(duty.name().toLowerCase(Locale.ROOT));
    }

    public static Set<String> duties(Character character) {
        TreeSet<String> result = new TreeSet<>();
        Object raw = character.props.get(DUTIES);
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof String s) result.add(s);
        return Collections.unmodifiableSet(result);
    }

    /** Needs are shared by civilian, specialist, soldier and hero AI; values are deficits (0=met, 100=critical). */
    public static int need(Character character, Need need) {
        Object value = mapProp(character, NEEDS).get(need.name().toLowerCase(Locale.ROOT));
        return value instanceof Number n ? clamp(n.intValue(), 0, MAX_NEED) : 0;
    }

    public static void setNeed(Character character, Need need, int deficit) {
        mapProp(character, NEEDS).put(need.name().toLowerCase(Locale.ROOT), clamp(deficit, 0, MAX_NEED));
    }

    public static void changeNeed(Character character, Need need, int delta) {
        setNeed(character, need, need(character, need) + delta);
    }

    /** Skill history is retained across profession changes; training never creates another Character. */
    public static int skillRank(Character character, String skillId) {
        String id = normalizeId(skillId, "skill");
        Object value = mapProp(character, SKILLS).get(id);
        return value instanceof Number n ? clamp(n.intValue(), 0, MAX_SKILL_RANK) : 0;
    }

    public static long trainingXp(Character character, String skillId) {
        String id = normalizeId(skillId, "skill");
        Object value = mapProp(character, TRAINING).get(id);
        return value instanceof Number n ? Math.max(0, n.longValue()) : 0;
    }

    /** Adds training XP and returns the number of ranks gained. */
    public static int train(Character character, String skillId, long xp) {
        requireAlive(character);
        String id = normalizeId(skillId, "skill");
        if (xp <= 0) throw new IllegalArgumentException("Training XP must be positive");
        int rank = skillRank(character, id);
        long banked = trainingXp(character, id);
        if (rank >= MAX_SKILL_RANK) return 0;
        long total = Math.min((long) MAX_SKILL_RANK * SKILL_XP_PER_RANK, banked + xp);
        int nextRank = Math.min(MAX_SKILL_RANK, (int) (total / SKILL_XP_PER_RANK));
        mapProp(character, SKILLS).put(id, nextRank);
        mapProp(character, TRAINING).put(id, nextRank >= MAX_SKILL_RANK ? 0L : total % SKILL_XP_PER_RANK);
        return nextRank - rank;
    }

    public static void setTrainingTarget(Character character, String skillId) {
        requireAlive(character);
        character.props.put(TRAINING_TARGET, normalizeId(skillId, "skill"));
    }

    public static String trainingTarget(Character character) {
        Object value = character.props.get(TRAINING_TARGET);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Deterministic priority AI. It emits an intent only; settlement, troop and quest
     * systems execute that intent using their own authoritative resources and rules.
     */
    public static Intent decide(Character character, int year) {
        if (character == null || !character.alive) return Intent.IDLE;
        if (need(character, Need.HUNGER) >= 70) return Intent.EAT;
        if (need(character, Need.REST) >= 70) return Intent.REST;
        if (need(character, Need.SAFETY) >= 70) return Intent.SEEK_SAFETY;
        if (need(character, Need.SOCIAL) >= 70) return Intent.SOCIALIZE;

        LifeStage stage = lifeStage(character, year);
        if (stage == LifeStage.INFANT || stage == LifeStage.CHILD) return Intent.IDLE;
        if (stage == LifeStage.ADOLESCENT && need(character, Need.PURPOSE) >= 45) return Intent.TRAIN;

        if (need(character, Need.PURPOSE) >= 65) {
            return trainingTarget(character).isEmpty() ? Intent.WORK : Intent.TRAIN;
        }
        if (hasDuty(character, Duty.HERO) && !String.valueOf(character.props.getOrDefault("population.command", "")).isBlank()) return Intent.FOLLOW_COMMAND;
        if (hasDuty(character, Duty.SOLDIER)) return Intent.PATROL;
        if (!trainingTarget(character).isEmpty()) return Intent.TRAIN;
        if (canWork(character, year) && !profession(character).isEmpty()) return Intent.WORK;
        if (hasDuty(character, Duty.SPECIALIST) || hasDuty(character, Duty.WORKER)) return Intent.WORK;
        if (hasDuty(character, Duty.HERO)) return Intent.FOLLOW_COMMAND;
        return Intent.IDLE;
    }

    /** Records the chosen intent for UI/other addons; it does not spend food or move entities. */
    public static Intent updateIntent(Character character, int year) {
        Intent intent = decide(character, year);
        character.props.put(LAST_INTENT, intent.name().toLowerCase(Locale.ROOT));
        character.props.put("population.age_years", age(character, year));
        character.props.put("population.life_stage", lifeStage(character, year).name().toLowerCase(Locale.ROOT));
        return intent;
    }

    public static Intent lastIntent(Character character) {
        Object raw = character.props.get(LAST_INTENT);
        if (!(raw instanceof String s)) return Intent.IDLE;
        try { return Intent.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return Intent.IDLE; }
    }

    /** Applies the need changes of a completed action. Call only after the owning system confirms success. */
    public static void completeIntent(Character character, Intent intent) {
        requireAlive(character);
        switch (Objects.requireNonNull(intent, "intent")) {
            case EAT -> changeNeed(character, Need.HUNGER, -70);
            case REST -> changeNeed(character, Need.REST, -70);
            case SEEK_SAFETY -> changeNeed(character, Need.SAFETY, -55);
            case SOCIALIZE -> changeNeed(character, Need.SOCIAL, -60);
            case WORK -> { changeNeed(character, Need.PURPOSE, -20); changeNeed(character, Need.REST, 8); changeNeed(character, Need.HUNGER, 6); }
            case TRAIN -> { changeNeed(character, Need.PURPOSE, -12); changeNeed(character, Need.REST, 5); }
            case PATROL -> { changeNeed(character, Need.SAFETY, -20); changeNeed(character, Need.REST, 10); }
            case FOLLOW_COMMAND -> { changeNeed(character, Need.PURPOSE, -20); changeNeed(character, Need.REST, 8); }
            case IDLE -> { }
        }
    }

    /** Stores a death cause and year while preserving the Character and family graph. */
    public static List<Character.Post> die(CharacterRegistry registry, String characterId, int year, String cause) {
        Character character = registry.get(characterId);
        if (character == null) throw new IllegalArgumentException("No character " + characterId);
        if (!character.alive) return List.of();
        character.props.put("population.death_year", year);
        character.props.put("population.death_cause", cause == null || cause.isBlank() ? "unknown" : cause);
        return registry.kill(characterId);
    }

    /** Daily hook used by PeopleMod. Aging is calendar-derived; AI intents are refreshed, but actions remain externally owned. */
    public static void advance(CharacterRegistry registry, long elapsedDays, int year) {
        if (elapsedDays < 0) throw new IllegalArgumentException("elapsedDays cannot be negative");
        if (elapsedDays == 0) return;
        for (Character character : registry.all()) {
            if (!character.alive) continue;
            updateIntent(character, year);
            Object previous = character.props.get("population.simulated_days");
            long simulated = previous instanceof Number n ? Math.max(0, n.longValue()) : 0;
            character.props.put("population.simulated_days", simulated + elapsedDays);
            character.props.put("population.last_simulated_year", year);
        }
    }

    private static void requireAlive(Character character) {
        Objects.requireNonNull(character, "character");
        if (!character.alive) throw new IllegalStateException(character.name + " is dead");
    }

    private static String normalizeId(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " id is required");
        String id = value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_.:-]{1,64}")) throw new IllegalArgumentException("Invalid " + label + " id: " + value);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapProp(Character character, String key) {
        Object raw = character.props.get(key);
        if (raw instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> created = new LinkedHashMap<>();
        character.props.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listProp(Character character, String key) {
        Object raw = character.props.get(key);
        if (raw instanceof List<?> list) return (List<Object>) list;
        List<Object> created = new ArrayList<>();
        character.props.put(key, created);
        return created;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
