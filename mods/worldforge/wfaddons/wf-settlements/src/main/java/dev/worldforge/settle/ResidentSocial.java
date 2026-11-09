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

    private static int clamp(int value) { return Math.max(-100, Math.min(100, value)); }
}
