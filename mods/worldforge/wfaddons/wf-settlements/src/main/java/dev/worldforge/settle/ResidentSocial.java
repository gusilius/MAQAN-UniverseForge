package dev.worldforge.settle;

import dev.worldforge.people.Character;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent, bounded social relationship scores stored on the canonical Character. */
public final class ResidentSocial {
    private static final String KEY = "social.relationships";
    private ResidentSocial() {}

    public static int score(Character person, String otherCharacterOrPlayerId) {
        if (person == null || otherCharacterOrPlayerId == null || otherCharacterOrPlayerId.isBlank()) return 0;
        Object raw = person.props.get(KEY);
        if (!(raw instanceof Map<?, ?> map)) return 0;
        Object value = map.get(otherCharacterOrPlayerId);
        return value instanceof Number n ? clamp(n.intValue()) : 0;
    }

    public static int adjust(Character person, String otherCharacterOrPlayerId, int delta) {
        if (person == null) throw new IllegalArgumentException("resident Character is required");
        if (otherCharacterOrPlayerId == null || otherCharacterOrPlayerId.isBlank())
            throw new IllegalArgumentException("relationship target is required");
        Object raw = person.props.get(KEY);
        Map<String, Object> relationships = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String key && entry.getValue() instanceof Number n)
                    relationships.put(key, clamp(n.intValue()));
        }
        int next = clamp(score(person, otherCharacterOrPlayerId) + delta);
        relationships.put(otherCharacterOrPlayerId, next);
        person.props.put(KEY, relationships);
        return next;
    }

    /** A real resident-to-resident interaction updates both sides of the relationship. */
    public static int socialize(Character first, Character second, int delta) {
        if (first == null || second == null || first == second || !first.alive || !second.alive) return 0;
        int a = adjust(first, second.id, delta);
        int b = adjust(second, first.id, delta);
        updateBond(first, second.id, a);
        updateBond(second, first.id, b);
        return Math.min(a, b);
    }

    /** Stable social labels are derived from the durable score, not stored as a second source of truth. */
    public static String bond(Character person, String otherId) {
        int value = score(person, otherId);
        if (value >= 70) return "друг";
        if (value >= 35) return "приятель";
        if (value <= -70) return "враг";
        if (value <= -35) return "соперник";
        return "знакомый";
    }

    private static void updateBond(Character person, String otherId, int score) {
        Object raw = person.props.get("social.bonds");
        Map<String,Object> bonds = new LinkedHashMap<>();
        if (raw instanceof Map<?,?> map) for (Map.Entry<?,?> entry : map.entrySet())
            if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) bonds.put(key, value);
        String label = score >= 70 ? "friend" : score >= 35 ? "companion" : score <= -70 ? "enemy" : score <= -35 ? "rival" : "acquaintance";
        bonds.put(otherId, label);
        person.props.put("social.bonds", bonds);
    }

    private static int clamp(int value) { return Math.max(-100, Math.min(100, value)); }
}
