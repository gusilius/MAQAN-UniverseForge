package dev.worldforge.people;

import java.util.Objects;

/**
 * Shared, deterministic family-planning rules for every addon using the canonical
 * Character registry. Pregnancy is persisted on the mother; no parallel person
 * registry or random per-tick population growth is introduced.
 */
public final class FamilyPlanning {
    private static final String PREFIX = "family.pregnancy.";
    private static final int MAX_CHILDREN = 4;
    private static final long GESTATION_DAYS = 270L;

    private FamilyPlanning() {}

    public static boolean isPregnant(Character mother) {
        return mother != null && mother.alive
                && number(mother.props.get(PREFIX + "due_day"), -1L) >= 0L;
    }

    public static boolean engagedTo(Character first, Character second) {
        return first != null && second != null
                && second.id.equals(first.props.get("family.engaged_to"))
                && first.id.equals(second.props.get("family.engaged_to"));
    }

    /** Creates a mutual, persisted betrothal; dating alone never silently becomes marriage. */
    public static boolean engage(Character first, Character second, int year) {
        if (first == null || second == null || first == second || !first.alive || !second.alive
                || !first.spouse.isBlank() || !second.spouse.isBlank()) return false;
        if (engagedTo(first, second)) return true;
        Object firstTarget = first.props.get("family.engaged_to");
        Object secondTarget = second.props.get("family.engaged_to");
        if (firstTarget instanceof String s && !s.isBlank()
                || secondTarget instanceof String s && !s.isBlank()) return false;
        first.props.put("family.engaged_to", second.id);
        second.props.put("family.engaged_to", first.id);
        first.props.put("family.engagement_year", year);
        second.props.put("family.engagement_year", year);
        first.props.put("family.last_event", "engaged:" + second.id);
        second.props.put("family.last_event", "engaged:" + first.id);
        return true;
    }

    public static void cancelEngagement(Character first, Character second, int year, String reason) {
        if (first == null || second == null || !engagedTo(first, second)) return;
        first.props.remove("family.engaged_to");
        second.props.remove("family.engaged_to");
        first.props.remove("family.engagement_year");
        second.props.remove("family.engagement_year");
        first.props.put("family.engagement_ended_year", year);
        second.props.put("family.engagement_ended_year", year);
        first.props.put("family.last_event", "engagement-ended:" + second.id + ":" + reason);
        second.props.put("family.last_event", "engagement-ended:" + first.id + ":" + reason);
    }

    public static void completeMarriage(Character first, Character second) {
        if (first == null || second == null) return;
        for (String key : new String[]{"family.engaged_to", "family.engagement_year"}) {
            first.props.remove(key);
            second.props.remove(key);
        }
    }

    /** Annual conception opportunity. Base probability is deliberately low. */
    public static boolean tryConceive(Character mother, Character father, int year,
                                      long worldDay, long worldSeed) {
        if (!eligible(mother, father, year) || isPregnant(mother)) return false;
        if (number(mother.props.get("family.last_conception_year"), 0) >= year) return false;
        if (number(mother.props.get("family.last_birth_year"), 0) > 0
                && year - number(mother.props.get("family.last_birth_year"), 0) < 3) return false;
        if (Boolean.FALSE.equals(mother.props.get("family.wants_children"))
                || Boolean.FALSE.equals(father.props.get("family.wants_children"))) return false;

        int age = year - mother.birthYear;
        int baseChance = age <= 29 ? 700 : age <= 34 ? 500 : age <= 39 ? 250 : 75;
        int fertility = clamp(number(mother.props.get("family.fertility"), 100L), 0, 200);
        int modifier = clamp(number(mother.props.get("family.conception_modifier"), 100L), 0, 200);
        long chance = (long) baseChance * fertility * modifier / 10_000L;
        long roll = Math.floorMod(stableHash(worldSeed, mother.globalId, father.globalId, year), 10_000L);

        mother.props.put("family.last_conception_year", year);
        father.props.put("family.last_conception_year", year);
        if (roll >= chance) return false;

        long start = Math.max(0L, worldDay);
        mother.props.put(PREFIX + "father_id", father.id);
        mother.props.put(PREFIX + "start_day", start);
        mother.props.put(PREFIX + "due_day", saturatingAdd(start, GESTATION_DAYS));
        mother.props.put(PREFIX + "conception_year", year);
        mother.props.put("family.last_event", "pregnancy-started:" + father.id);
        father.props.put("family.last_event", "pregnancy-started:" + mother.id);
        return true;
    }

    /** Returns the canonical father id only when a persisted pregnancy is due. */
    public static String dueFatherId(Character mother, long worldDay) {
        if (!isPregnant(mother) || worldDay < number(mother.props.get(PREFIX + "due_day"), Long.MAX_VALUE))
            return "";
        Object father = mother.props.get(PREFIX + "father_id");
        return father instanceof String id ? id : "";
    }

    /** Clears pregnancy state after birth or an explicitly handled pregnancy loss. */
    public static void complete(Character mother, Character father, int year, String childId) {
        if (mother == null) return;
        for (String key : new String[]{"father_id", "start_day", "due_day", "conception_year"})
            mother.props.remove(PREFIX + key);
        mother.props.put("family.last_birth_year", year);
        mother.props.put("family.last_event", "child-born:" + childId);
        if (father != null) {
            father.props.put("family.last_birth_year", year);
            father.props.put("family.last_event", "child-born:" + childId);
        }
    }

    public static void cancel(Character mother, Character father, String reason, int year) {
        if (mother == null || !isPregnant(mother)) return;
        for (String key : new String[]{"father_id", "start_day", "due_day", "conception_year"})
            mother.props.remove(PREFIX + key);
        mother.props.put("family.last_pregnancy_outcome", reason == null ? "ended" : reason);
        mother.props.put("family.last_pregnancy_outcome_year", year);
        if (father != null) father.props.put("family.last_pregnancy_outcome", reason == null ? "ended" : reason);
    }

    private static boolean eligible(Character mother, Character father, int year) {
        if (mother == null || father == null || mother == father || !mother.alive || !father.alive) return false;
        if (!"f".equalsIgnoreCase(mother.gender) || !"m".equalsIgnoreCase(father.gender)) return false;
        if (!father.id.equals(mother.spouse) || !mother.id.equals(father.spouse)) return false;
        int motherAge = year - mother.birthYear, fatherAge = year - father.birthYear;
        if (motherAge < 18 || motherAge > 42 || fatherAge < 18 || fatherAge > 70) return false;
        if (mother.children.size() >= MAX_CHILDREN || father.children.size() >= MAX_CHILDREN) return false;
        return true;
    }

    private static long stableHash(long seed, String a, String b, int year) {
        long h = seed ^ 0x9E3779B97F4A7C15L ^ year;
        for (String value : new String[]{a, b}) {
            for (int i = 0; i < value.length(); i++) {
                h ^= value.charAt(i);
                h *= 0x100000001B3L;
                h ^= h >>> 29;
            }
            h ^= 0xFF;
        }
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        return fallback;
    }
    private static int clamp(long value, int min, int max) { return (int) Math.max(min, Math.min(max, value)); }
    private static long saturatingAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}
