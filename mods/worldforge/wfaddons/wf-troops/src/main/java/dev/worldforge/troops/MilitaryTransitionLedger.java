package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/** Durable outbox for non-lethal troop-to-civilian transitions (desertion and demobilization). */
public final class MilitaryTransitionLedger {
    public enum Kind { DESERTION, DEMOBILIZATION }

    public record Pending(String characterGlobalId, String troopEntityId, Kind kind,
                          int year, String destinationColonyId) {
        public Pending {
            characterGlobalId = uuid(characterGlobalId, "Character UUID");
            troopEntityId = uuid(troopEntityId, "troop entity UUID");
            if (kind == null) throw new IllegalArgumentException("transition kind is required");
            if (year < 1) throw new IllegalArgumentException("transition year must be positive");
            destinationColonyId = destinationColonyId == null ? "" : destinationColonyId.trim();
            if (destinationColonyId.length() > 256)
                throw new IllegalArgumentException("destination colony ID is too long");
        }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public synchronized boolean stage(Pending value) {
        if (value == null) throw new IllegalArgumentException("pending transition is required");
        Pending existing = pending.get(value.characterGlobalId());
        if (existing != null) {
            if (!existing.equals(value))
                throw new IllegalStateException("military transition idempotency collision for Character "
                        + value.characterGlobalId());
            return false;
        }
        for (Pending entry : pending.values()) {
            if (entry.troopEntityId().equals(value.troopEntityId()))
                throw new IllegalStateException("troop entity already has a pending military transition");
        }
        pending.put(value.characterGlobalId(), value);
        return true;
    }

    public synchronized Pending get(String characterGlobalId) { return pending.get(characterGlobalId); }
    public synchronized List<Pending> pending() { return List.copyOf(pending.values()); }
    public synchronized int size() { return pending.size(); }
    public synchronized boolean complete(String characterGlobalId) { return pending.remove(characterGlobalId) != null; }
    public synchronized void clear() { pending.clear(); }

    public synchronized String toJson() {
        List<Object> rows = new ArrayList<>();
        for (Pending value : pending.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("character", value.characterGlobalId());
            row.put("entity", value.troopEntityId());
            row.put("kind", value.kind().name());
            row.put("year", value.year());
            row.put("colony", value.destinationColonyId());
            rows.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-military-transition-outbox/1");
        root.put("pending", rows);
        return Json.write(root);
    }

    public synchronized void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root)
                || !"wf-military-transition-outbox/1".equals(root.get("format"))
                || !(root.get("pending") instanceof List<?> rows))
            throw new IllegalArgumentException("Invalid military transition outbox format");
        Map<String, Pending> restored = new LinkedHashMap<>();
        Set<String> entities = new HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row))
                throw new IllegalArgumentException("Invalid pending military transition row");
            String character = requiredString(row.get("character"), "Character UUID");
            String entity = requiredString(row.get("entity"), "troop entity UUID");
            String kind = requiredString(row.get("kind"), "transition kind");
            if (!(row.get("year") instanceof Number year))
                throw new IllegalArgumentException("Pending military transition year is not numeric");
            String colony = row.get("colony") instanceof String value ? value : "";
            Pending value;
            try {
                value = new Pending(character, entity, Kind.valueOf(kind), year.intValue(), colony);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid pending military transition", error);
            }
            if (restored.putIfAbsent(value.characterGlobalId(), value) != null)
                throw new IllegalArgumentException("Duplicate pending Character transition: " + character);
            if (!entities.add(value.troopEntityId()))
                throw new IllegalArgumentException("Duplicate pending troop transition: " + entity);
        }
        pending.clear();
        pending.putAll(restored);
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(field + " is required");
        return text;
    }

    private static String uuid(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        UUID parsed;
        try { parsed = UUID.fromString(value); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid " + field, error); }
        String canonical = parsed.toString();
        if (!canonical.equalsIgnoreCase(value.trim()))
            throw new IllegalArgumentException("Non-canonical " + field);
        return canonical;
    }
}
