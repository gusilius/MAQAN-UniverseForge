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

    /** Canonical world-level identity is the only key for Character-to-Character relationships. */
    private static String globalId(Character person) {
        if (person == null || person.globalId == null || person.globalId.isBlank())
            throw new IllegalStateException("Social relationships require a canonical Character global UUID");
        return person.globalId;
    }

    /**
     * One-time migration for relationships written by older versions under map-local cN IDs.
     * If two partitions reused the same local ID, each new relationship is still isolated by UUID.
     */
    private static void migrateLegacyCounterpart(Character person, Character other) {
        String canonical = globalId(other);
        String legacy = other.id;
        if (legacy == null || legacy.isBlank() || legacy.equals(canonical)) return;

        Object rawRelationships = person.props.get(KEY);
        if (rawRelationships instanceof Map<?, ?> map && map.containsKey(legacy)) {
            Map<String, Object> relationships = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String key && entry.getValue() instanceof Number n)
                    relationships.put(key, clamp(n.intValue()));
            relationships.putIfAbsent(canonical, relationships.get(legacy));
            relationships.remove(legacy);
            person.props.put(KEY, relationships);
        }

        Object rawBonds = person.props.get("social.bonds");
        if (rawBonds instanceof Map<?, ?> map && map.containsKey(legacy)) {
            Map<String, Object> bonds = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value)
                    bonds.put(key, value);
            bonds.putIfAbsent(canonical, bonds.get(legacy));
            bonds.remove(legacy);
            person.props.put("social.bonds", bonds);
        }
    }

    /** Trait/culture compatibility shapes the result of an actual conversation. */
    public static int interactionDelta(Character first, Character second) {
        if (first == null || second == null) return 0;
        int delta = 1;
        if (first.traits.contains("kind") || first.traits.contains("generous")) delta++;
        if (second.traits.contains("kind") || second.traits.contains("generous")) delta++;
        if (first.traits.contains("aggressive") || first.traits.contains("cruel")
                || first.traits.contains("arrogant") || second.traits.contains("aggressive")
                || second.traits.contains("cruel") || second.traits.contains("arrogant")) delta -= 3;
        if (first.culture != null && second.culture != null && !first.culture.isBlank()
                && !second.culture.isBlank() && !first.culture.equals(second.culture)) delta--;
        return Math.max(-4, Math.min(4, delta));
    }

    /** A real resident-to-resident interaction updates both sides of the relationship. */
    public static int socialize(Character first, Character second, int delta) {
        if (first == null || second == null || first == second || !first.alive || !second.alive) return 0;
        migrateLegacyCounterpart(first, second);
        migrateLegacyCounterpart(second, first);
        String firstKey = globalId(first);
        String secondKey = globalId(second);
        int a = adjust(first, secondKey, delta);
        int b = adjust(second, firstKey, delta);
        updateBond(first, secondKey, a);
        updateBond(second, firstKey, b);
        return Math.min(a, b);
    }

    /**
     * Replay-safe conversation effect. Each Character stores its own receipt beside its
     * relationship mutation, so a crash between the two Character saves can be resumed
     * without applying the first participant's opinion delta twice.
     */
    public static int socializeOnce(Character first, Character second, int delta, String operationId) {
        if (first == null || second == null || first == second || !first.alive || !second.alive) return 0;
        if (operationId == null || !operationId.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("A stable social interaction operation ID is required");
        migrateLegacyCounterpart(first, second);
        migrateLegacyCounterpart(second, first);
        applyInteractionOnce(first, second, delta, operationId);
        applyInteractionOnce(second, first, delta, operationId);
        return Math.min(score(first, globalId(second)), score(second, globalId(first)));
    }

    @SuppressWarnings("unchecked")
    private static void applyInteractionOnce(Character person, Character other, int delta, String operationId) {
        final String receiptKey = "social.interaction_receipts";
        Map<String, Object> receipts = new LinkedHashMap<>();
        Object raw = person.props.get(receiptKey);
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value)
                    receipts.put(key, value);
        }
        String counterpartId = globalId(other);
        String payload = counterpartId + "|" + delta;
        Object prior = receipts.get(operationId);
        if (prior != null) {
            if (!payload.equals(prior))
                throw new IllegalStateException("Social interaction ID collision: " + operationId);
            return;
        }
        if (receipts.size() >= 10000)
            throw new IllegalStateException("Social interaction receipt limit reached; refusing unsafe replay");
        adjust(person, counterpartId, delta);
        updateBond(person, counterpartId, score(person, counterpartId));
        receipts.put(operationId, payload);
        person.props.put(receiptKey, receipts);
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
