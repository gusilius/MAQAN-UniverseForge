package dev.worldforge.troops;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.identity.WorldId;

import java.util.*;
import java.util.function.Function;

/**
 * Persistent command hierarchy over canonical strategic armies and physical Squads:
 * WorldId + mapId + armyId -> Corps -> Squad -> Character-backed TroopEntity.
 */
public final class ArmyHierarchy {
    public record LegacyArmy(int armyId, String realm, String owner) { }

    public static final class ArmyNode {
        public final ArmyRef ref;
        /** Compatibility projection; never use this field as a global identity. */
        public final int strategicArmyId;
        public String name, owner, realm, commanderCharacterId = "";
        public final List<String> corpsIds = new ArrayList<>();

        ArmyNode(ArmyRef ref, String name, String owner, String realm) {
            this.ref = ref;
            this.strategicArmyId = ref.armyId();
            this.name = name;
            this.owner = owner;
            this.realm = realm;
        }
    }

    public static final class Corps {
        public final String id;
        public final ArmyRef armyRef;
        /** Compatibility projection; use armyRef for lookup. */
        public final int armyId;
        public String name, commanderCharacterId = "";
        public final List<String> squadIds = new ArrayList<>();

        Corps(String id, ArmyRef armyRef, String name) {
            this.id = id;
            this.armyRef = armyRef;
            this.armyId = armyRef.armyId();
            this.name = name;
        }
    }

    private final Map<ArmyRef, ArmyNode> armies = new LinkedHashMap<>();
    private final Map<String, Corps> corps = new LinkedHashMap<>();
    private int nextCorps = 1;

    public Collection<ArmyNode> armies() { return Collections.unmodifiableCollection(armies.values()); }
    public Collection<Corps> corps() { return Collections.unmodifiableCollection(corps.values()); }
    public ArmyNode army(ArmyRef ref) { return ref == null ? null : armies.get(ref); }

    /**
     * Legacy numeric lookup is deliberately fail-closed when more than one map contains
     * the same map-local army ID.
     */
    @Deprecated
    public ArmyNode army(int id) {
        ArmyNode match = null;
        for (ArmyNode candidate : armies.values()) {
            if (candidate.strategicArmyId != id) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    public Corps corps(String id) { return corps.get(id); }

    public Corps corpsForSquad(String squadId) {
        if (squadId == null) return null;
        for (Corps value : corps.values()) if (value.squadIds.contains(squadId)) return value;
        return null;
    }

    public ArmyNode ensureArmy(ArmyRef ref, String name, String owner, String realm) {
        if (ref == null || blank(owner)) throw new IllegalArgumentException("army reference and owner are required");
        ArmyNode node = armies.get(ref);
        if (node == null) {
            node = new ArmyNode(ref, safe(name, "Армия " + ref.armyId()), owner, safe(realm, ""));
            armies.put(ref, node);
        } else {
            if (!node.owner.equals(owner)) throw new IllegalStateException("army belongs to another owner");
            if (!blank(name)) node.name = name;
            if (!blank(realm)) node.realm = realm;
        }
        return node;
    }

    /** Legacy API retained for old integrations; its synthetic scope cannot collide with real worlds. */
    @Deprecated
    public ArmyNode ensureArmy(int strategicId, String name, String owner, String realm) {
        return ensureArmy(legacyRef(strategicId), name, owner, realm);
    }

    public Corps defaultCorps(ArmyRef ref) {
        ArmyNode army = requireArmy(ref);
        for (String id : army.corpsIds) {
            Corps existing = corps.get(id);
            if (existing != null) return existing;
        }
        return createCorps(ref, "1-й корпус");
    }

    @Deprecated
    public Corps defaultCorps(int armyId) {
        ArmyNode army = army(armyId);
        if (army == null) throw new IllegalArgumentException("No unique strategic army " + armyId);
        return defaultCorps(army.ref);
    }

    public Corps createCorps(ArmyRef ref, String name) {
        ArmyNode army = requireArmy(ref);
        String id = "corps-" + nextCorps++;
        Corps value = new Corps(id, ref, safe(name, "Корпус " + nextCorps));
        corps.put(id, value);
        army.corpsIds.add(id);
        return value;
    }

    @Deprecated
    public Corps createCorps(int armyId, String name) {
        ArmyNode army = army(armyId);
        if (army == null) throw new IllegalArgumentException("No unique strategic army " + armyId);
        return createCorps(army.ref, name);
    }

    public boolean assignSquad(String corpsId, String squadId, Squads squads) {
        Corps target = corps.get(corpsId);
        Squads.Squad squad = squads == null ? null : squads.get(squadId);
        if (target == null || squad == null) return false;
        ArmyNode army = armies.get(target.armyRef);
        if (army == null || !army.owner.equals(squad.owner)) return false;
        for (Corps value : corps.values()) value.squadIds.remove(squadId);
        target.squadIds.add(squadId);
        return true;
    }

    public boolean appointArmyCommander(ArmyRef ref, String characterId) {
        ArmyNode army = armies.get(ref);
        if (army == null) return false;
        String id = safe(characterId, "");
        if (!validCharacterId(id)) return false;
        army.commanderCharacterId = id;
        return true;
    }

    @Deprecated
    public boolean appointArmyCommander(int armyId, String characterId) {
        ArmyNode army = army(armyId);
        return army != null && appointArmyCommander(army.ref, characterId);
    }

    public boolean appointCorpsCommander(String corpsId, String characterId) {
        Corps value = corps.get(corpsId);
        if (value == null) return false;
        String id = safe(characterId, "");
        if (!validCharacterId(id)) return false;
        value.commanderCharacterId = id;
        return true;
    }

    public void unassignSquad(String squadId) {
        if (squadId == null) return;
        for (Corps value : corps.values()) value.squadIds.remove(squadId);
    }

    public void pruneSquads(Squads squads) {
        if (squads == null) return;
        for (Corps value : corps.values()) value.squadIds.removeIf(id -> squads.get(id) == null);
    }

    public int commandArmy(ArmyRef ref, Squads squads, Squads.Order order, boolean queue) {
        ArmyNode army = armies.get(ref);
        if (army == null || squads == null || order == null) return 0;
        int sent = 0;
        for (String corpsId : army.corpsIds) {
            Corps corps = this.corps.get(corpsId);
            if (corps == null || !corps.armyRef.equals(ref)) continue;
            sent += commandSquads(corps.squadIds, army.owner, squads, order, queue);
        }
        return sent;
    }

    @Deprecated
    public int commandArmy(int armyId, Squads squads, Squads.Order order, boolean queue) {
        ArmyNode army = army(armyId);
        return army == null ? 0 : commandArmy(army.ref, squads, order, queue);
    }

    public int commandCorps(String corpsId, Squads squads, Squads.Order order, boolean queue) {
        Corps corps = this.corps.get(corpsId);
        if (corps == null || squads == null || order == null) return 0;
        ArmyNode army = armies.get(corps.armyRef);
        return army == null ? 0 : commandSquads(corps.squadIds, army.owner, squads, order, queue);
    }

    private int commandSquads(Collection<String> ids, String owner, Squads squads,
                              Squads.Order order, boolean queue) {
        int sent = 0;
        for (String id : new ArrayList<>(ids)) {
            Squads.Squad squad = squads.get(id);
            if (squad == null || !owner.equals(squad.owner)) continue;
            squads.command(id, order, queue);
            sent++;
        }
        return sent;
    }

    public String viewJson(ArmyRef ref) {
        ArmyNode army = armies.get(ref);
        if (army == null) return "{}";
        List<Object> children = new ArrayList<>();
        for (String id : army.corpsIds) {
            Corps corps = this.corps.get(id);
            if (corps == null || !corps.armyRef.equals(ref)) continue;
            children.add(Map.of("id", corps.id, "name", corps.name,
                    "commander", corps.commanderCharacterId, "squads", new ArrayList<>(corps.squadIds)));
        }
        return Json.write(Map.of("worldId", ref.worldId().toString(), "mapId", ref.mapId(),
                "armyId", army.strategicArmyId, "name", army.name, "realm", army.realm,
                "commander", army.commanderCharacterId, "corps", children));
    }

    @Deprecated
    public String viewJson(int armyId) {
        ArmyNode army = army(armyId);
        return army == null ? "{}" : viewJson(army.ref);
    }

    public String toJson() {
        List<Object> savedArmies = new ArrayList<>();
        for (ArmyNode army : armies.values()) savedArmies.add(Map.of(
                "worldId", army.ref.worldId().toString(), "mapId", army.ref.mapId(),
                "id", army.strategicArmyId, "name", army.name, "owner", army.owner, "realm", army.realm,
                "commander", army.commanderCharacterId, "corps", new ArrayList<>(army.corpsIds)));
        List<Object> savedCorps = new ArrayList<>();
        for (Corps value : corps.values()) savedCorps.add(Map.of(
                "id", value.id, "worldId", value.armyRef.worldId().toString(),
                "mapId", value.armyRef.mapId(), "army", value.armyId, "name", value.name,
                "commander", value.commanderCharacterId, "squads", new ArrayList<>(value.squadIds)));
        return Json.write(Map.of("format", "wf-army-hierarchy/2", "nextCorps", nextCorps,
                "armies", savedArmies, "corps", savedCorps));
    }

    public void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Invalid army hierarchy save");
        String format = string(root.get("format"));
        if ("wf-army-hierarchy/1".equals(format)) {
            WorldId legacyWorld = WorldId.parse("00000000-0000-0000-0000-000000000000");
            fromJson(json, legacyWorld, ignored -> "legacy-map");
            return;
        }
        fromJson(json, null, null);
    }

    /**
     * Loads v2 saves with world validation and migrates v1 map-local numeric references.
     * A legacy army is migrated only when the caller resolves its realm to one unambiguous map.
     */
    @SuppressWarnings("unchecked")
    public void fromJson(String json, WorldId currentWorld, Function<LegacyArmy, String> legacyMapResolver) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Invalid army hierarchy save");
        String format = string(root.get("format"));
        boolean legacy = "wf-army-hierarchy/1".equals(format);
        if (!legacy && !"wf-army-hierarchy/2".equals(format))
            throw new IllegalArgumentException("Invalid army hierarchy save format");

        Map<Integer, ArmyRef> legacyRefs = new HashMap<>();
        Map<ArmyRef, ArmyNode> stagedArmies = new LinkedHashMap<>();
        Map<String, Corps> stagedCorps = new LinkedHashMap<>();
        int stagedNextCorps = 1;

        if (root.get("armies") instanceof List<?> values) for (Object item : values) {
            if (!(item instanceof Map<?, ?> m)) throw new IllegalArgumentException("Invalid army hierarchy army entry");
            int id = integer(m.get("id"), 0);
            String owner = string(m.get("owner")), realm = string(m.get("realm"));
            if (id <= 0 || owner.isBlank()) throw new IllegalArgumentException("Invalid army hierarchy army identity");
            ArmyRef ref;
            if (legacy) {
                if (currentWorld == null || legacyMapResolver == null)
                    throw new IllegalStateException("Legacy army hierarchy requires a world/map migration resolver");
                String mapId = legacyMapResolver.apply(new LegacyArmy(id, realm, owner));
                if (mapId == null || mapId.isBlank())
                    throw new IllegalStateException("Cannot unambiguously migrate legacy army " + id + " in realm " + realm);
                ref = new ArmyRef(currentWorld, mapId, id);
                if (legacyRefs.putIfAbsent(id, ref) != null)
                    throw new IllegalStateException("Legacy army hierarchy contains duplicate map-local army id " + id);
            } else {
                WorldId savedWorld = WorldId.parse(string(m.get("worldId")));
                if (currentWorld != null && !currentWorld.equals(savedWorld))
                    throw new IllegalStateException("Army hierarchy belongs to another WorldId");
                ref = new ArmyRef(savedWorld, string(m.get("mapId")), id);
            }
            if (stagedArmies.containsKey(ref)) throw new IllegalStateException("Duplicate army reference " + ref.stableKey());
            ArmyNode node = new ArmyNode(ref, safe(string(m.get("name")), "Армия " + id), owner, realm);
            String commander = string(m.get("commander"));
            if (validCharacterId(commander)) node.commanderCharacterId = commander;
            stagedArmies.put(ref, node);
        }

        if (root.get("corps") instanceof List<?> values) for (Object item : values) {
            if (!(item instanceof Map<?, ?> m)) throw new IllegalArgumentException("Invalid army hierarchy corps entry");
            String id = string(m.get("id"));
            if (id.isBlank() || stagedCorps.containsKey(id)) throw new IllegalArgumentException("Invalid or duplicate corps id");
            ArmyRef ref;
            if (legacy) {
                ref = legacyRefs.get(integer(m.get("army"), 0));
            } else {
                WorldId savedWorld = WorldId.parse(string(m.get("worldId")));
                if (currentWorld != null && !currentWorld.equals(savedWorld))
                    throw new IllegalStateException("Corps belongs to another WorldId");
                ref = new ArmyRef(savedWorld, string(m.get("mapId")), integer(m.get("army"), 0));
            }
            ArmyNode parent = ref == null ? null : stagedArmies.get(ref);
            if (parent == null) throw new IllegalStateException("Corps " + id + " references a missing army");
            Corps value = new Corps(id, ref, safe(string(m.get("name")), id));
            String commander = string(m.get("commander"));
            if (validCharacterId(commander)) value.commanderCharacterId = commander;
            if (m.get("squads") instanceof List<?> list)
                for (Object squad : list) if (squad instanceof String squadId && !squadId.isBlank()
                        && !value.squadIds.contains(squadId)) value.squadIds.add(squadId);
            stagedCorps.put(id, value);
            parent.corpsIds.add(id);
            if (id.startsWith("corps-")) try {
                stagedNextCorps = Math.max(stagedNextCorps, Math.addExact(Integer.parseInt(id.substring(6)), 1));
            } catch (RuntimeException ignored) { }
        }
        if (root.get("nextCorps") instanceof Number n) stagedNextCorps = Math.max(stagedNextCorps, n.intValue());

        Set<String> seen = new HashSet<>();
        for (Corps value : stagedCorps.values()) value.squadIds.removeIf(id -> !seen.add(id));
        armies.clear();
        armies.putAll(stagedArmies);
        corps.clear();
        corps.putAll(stagedCorps);
        nextCorps = Math.max(1, stagedNextCorps);
    }

    private ArmyNode requireArmy(ArmyRef ref) {
        ArmyNode army = armies.get(ref);
        if (army == null) throw new IllegalArgumentException("No strategic army " + (ref == null ? "<null>" : ref.stableKey()));
        return army;
    }

    private static ArmyRef legacyRef(int id) {
        if (id <= 0) throw new IllegalArgumentException("army id must be positive");
        return new ArmyRef(WorldId.parse("00000000-0000-0000-0000-000000000000"), "legacy-map", id);
    }

    private static boolean validCharacterId(String id) { return id.isEmpty() || id.length() <= 64 && id.matches("[A-Za-z0-9_.:-]+"); }
    private static String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
}
