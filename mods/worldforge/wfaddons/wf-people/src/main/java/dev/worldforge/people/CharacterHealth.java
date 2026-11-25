package dev.worldforge.people;

import java.util.*;

/**
 * Compact event-driven health state for the canonical Character.
 * Healthy residents have no health keys at all; only illness, injury or a non-default
 * condition is persisted. Recovery is advanced at scheduled world-day boundaries.
 */
public final class CharacterHealth {
    private static final String HEALTH = "life.health.value";
    private static final String ILLNESS = "life.health.illness";
    private static final String ILLNESS_SEVERITY = "life.health.illness_severity";
    private static final String INJURY = "life.health.injury";
    private static final String INJURY_SEVERITY = "life.health.injury_severity";
    private static final String LAST_DAY = "life.health.last_day";
    private static final String NEXT_DAY = "life.health.next_day";
    private static final String RECEIPTS = "life.health.receipts";
    private CharacterHealth() {}

    public static int health(Character person) { return number(person, HEALTH, 100, 0, 100); }
    public static String illness(Character person) { return string(person, ILLNESS); }
    public static int illnessSeverity(Character person) { return number(person, ILLNESS_SEVERITY, 0, 0, 100); }
    public static String injury(Character person) { return string(person, INJURY); }
    public static int injurySeverity(Character person) { return number(person, INJURY_SEVERITY, 0, 0, 100); }
    public static long nextEventDay(Character person) { return numberLong(person, NEXT_DAY, -1L); }

    /** Applies a deterministic injury once. The event ID is a durable idempotency receipt. */
    public static boolean injure(Character person, String injuryId, int severity, long worldDay, String eventId) {
        validate(person, injuryId, severity, worldDay, eventId);
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            if (!receipt(person, eventId)) return false;
            int old = injurySeverity(person);
            person.props.put(INJURY, injuryId);
            person.props.put(INJURY_SEVERITY, Math.max(old, severity));
            person.props.put(HEALTH, Math.max(1, health(person) - Math.max(1, severity / 3)));
            schedule(person, worldDay);
            CharacterRegistry.recordCharacterMutation(person);
        }
        return true;
    }

    /** Applies an illness once; severity is bounded and treatment can reduce it later. */
    public static boolean infect(Character person, String diseaseId, int severity, long worldDay, String eventId) {
        validate(person, diseaseId, severity, worldDay, eventId);
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            if (!receipt(person, eventId)) return false;
            int old = illnessSeverity(person);
            person.props.put(ILLNESS, diseaseId);
            person.props.put(ILLNESS_SEVERITY, Math.max(old, severity));
            schedule(person, worldDay);
            CharacterRegistry.recordCharacterMutation(person);
        }
        return true;
    }

    /** Treatment reduces active conditions and restores a limited amount of health. */
    public static boolean treat(Character person, int strength, long worldDay, String eventId) {
        if (person == null || strength < 1 || strength > 100 || worldDay < 0
                || eventId == null || !eventId.matches("[A-Za-z0-9_.:-]{1,160}"))
            throw new IllegalArgumentException("invalid treatment");
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            if (!receipt(person, eventId)) return false;
            int illness = Math.max(0, illnessSeverity(person) - strength);
            int injury = Math.max(0, injurySeverity(person) - strength);
            if (illness == 0) { person.props.remove(ILLNESS); person.props.remove(ILLNESS_SEVERITY); }
            else person.props.put(ILLNESS_SEVERITY, illness);
            if (injury == 0) { person.props.remove(INJURY); person.props.remove(INJURY_SEVERITY); }
            else person.props.put(INJURY_SEVERITY, injury);
            person.props.put(HEALTH, Math.min(100, health(person) + Math.max(1, strength / 20)));
            if (illness == 0 && injury == 0 && health(person) == 100) person.props.remove(NEXT_DAY);
            else schedule(person, worldDay);
            CharacterRegistry.recordCharacterMutation(person);
        }
        return true;
    }

    /** Advances one due resident by elapsed world days; never called for healthy unscheduled residents. */
    public static boolean advance(Character person, long worldDay) {
        if (person == null || worldDay < 0 || worldDay < nextEventDay(person)) return false;
        long previous = numberLong(person, LAST_DAY, worldDay);
        long elapsed = Math.max(0L, worldDay - previous);
        if (elapsed == 0 && illnessSeverity(person) == 0 && injurySeverity(person) == 0
                && health(person) == 100) {
            person.props.remove(NEXT_DAY);
            return false;
        }
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            int illness = illnessSeverity(person);
            int injury = injurySeverity(person);
            long illnessLoss = illness == 0 ? 0 : Math.max(1, elapsed * illness / 100L);
            long injuryLoss = injury == 0 ? 0 : Math.max(0, elapsed * injury / 200L);
            int nextHealth = Math.max(1, health(person) - (int) Math.min(100, illnessLoss + injuryLoss));
            int healedIllness = Math.max(0, illness - (int) Math.min(illness, elapsed / 7L));
            int healedInjury = Math.max(0, injury - (int) Math.min(injury, elapsed / 14L));
            if (healedIllness == 0) { person.props.remove(ILLNESS); person.props.remove(ILLNESS_SEVERITY); }
            else person.props.put(ILLNESS_SEVERITY, healedIllness);
            if (healedInjury == 0) { person.props.remove(INJURY); person.props.remove(INJURY_SEVERITY); }
            else person.props.put(INJURY_SEVERITY, healedInjury);
            if (healedIllness == 0 && healedInjury == 0)
                nextHealth = Math.min(100, nextHealth + (int) Math.min(100, elapsed / 10L));
            person.props.put(HEALTH, nextHealth);
            person.props.put(LAST_DAY, worldDay);
            if (healedIllness == 0 && healedInjury == 0 && nextHealth == 100) person.props.remove(NEXT_DAY);
            else schedule(person, worldDay);
            CharacterRegistry.recordCharacterMutation(person);
        }
        return true;
    }

    private static void schedule(Character person, long worldDay) {
        person.props.put(LAST_DAY, worldDay);
        person.props.put(NEXT_DAY, worldDay > Long.MAX_VALUE - 7 ? Long.MAX_VALUE : worldDay + 7);
    }

    private static boolean receipt(Character person, String eventId) {
        if (person == null || eventId == null || !eventId.matches("[A-Za-z0-9_.:-]{1,160}"))
            throw new IllegalArgumentException("invalid health event");
        Object raw = person.props.get(RECEIPTS);
        List<String> receipts = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list)
            if (item instanceof String value) receipts.add(value);
        if (receipts.contains(eventId)) return false;
        receipts.add(eventId);
        while (receipts.size() > 64) receipts.remove(0);
        person.props.put(RECEIPTS, receipts);
        CharacterRegistry.recordCharacterMutation(person);
        return true;
    }

    private static void validate(Character person, String condition, int severity, long worldDay, String eventId) {
        if (person == null || condition == null || !condition.matches("[a-zA-Z0-9_.:-]{1,64}")
                || severity < 1 || severity > 100 || worldDay < 0
                || eventId == null || !eventId.matches("[A-Za-z0-9_.:-]{1,160}"))
            throw new IllegalArgumentException("invalid health condition");
    }
    private static int number(Character c, String key, int fallback, int min, int max) {
        Object raw = c.props.get(key);
        return raw instanceof Number n ? Math.max(min, Math.min(max, n.intValue())) : fallback;
    }
    private static long numberLong(Character c, String key, long fallback) {
        Object raw = c.props.get(key);
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        return fallback;
    }
    private static String string(Character c, String key) {
        Object raw = c.props.get(key);
        return raw instanceof String s ? s : "";
    }
}
