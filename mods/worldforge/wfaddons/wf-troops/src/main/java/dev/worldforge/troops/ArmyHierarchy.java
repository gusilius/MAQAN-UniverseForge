package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Persistent command hierarchy over canonical strategic armies and physical Squads:
 * Army -> Corps -> Squad -> Character-backed TroopEntity.
 */
public final class ArmyHierarchy {
    public static final class ArmyNode {
        public final int strategicArmyId;
        public String name, owner, realm, commanderCharacterId = "";
        public final List<String> corpsIds = new ArrayList<>();
        ArmyNode(int id, String name, String owner, String realm) {
            this.strategicArmyId = id; this.name = name; this.owner = owner; this.realm = realm;
        }
    }
    public static final class Corps {
        public final String id;
        public final int armyId;
        public String name, commanderCharacterId = "";
        public final List<String> squadIds = new ArrayList<>();
        Corps(String id, int armyId, String name) { this.id = id; this.armyId = armyId; this.name = name; }
    }

    private final Map<Integer,ArmyNode> armies = new LinkedHashMap<>();
    private final Map<String,Corps> corps = new LinkedHashMap<>();
    /** Reverse membership index; the squad remains owned by Squads, this only indexes its command parent. */
    private final Map<String,String> corpsBySquad = new HashMap<>();
    private int nextCorps = 1;

    public Collection<ArmyNode> armies() { return Collections.unmodifiableCollection(armies.values()); }
    public Collection<Corps> corps() { return Collections.unmodifiableCollection(corps.values()); }
    public ArmyNode army(int id) { return armies.get(id); }
    public Corps corps(String id) { return corps.get(id); }
    public Corps corpsForSquad(String squadId) {
        if (squadId == null) return null;
        String indexedId = corpsBySquad.get(squadId);
        Corps indexed = indexedId == null ? null : corps.get(indexedId);
        if (indexed != null && indexed.squadIds.contains(squadId)) return indexed;
        if (indexedId != null) corpsBySquad.remove(squadId);
        // Compatibility repair for older addons that still mutate the public squadIds list.
        for (Corps value : corps.values()) if (value.squadIds.contains(squadId)) {
            corpsBySquad.put(squadId, value.id);
            return value;
        }
        return null;
    }

    public ArmyNode ensureArmy(int strategicId, String name, String owner, String realm) {
        if (strategicId <= 0 || blank(owner)) throw new IllegalArgumentException("army id and owner are required");
        ArmyNode node = armies.get(strategicId);
        if (node == null) {
            node = new ArmyNode(strategicId, safe(name, "Армия " + strategicId), owner, safe(realm, ""));
            armies.put(strategicId, node);
        } else {
            if (!node.owner.equals(owner)) throw new IllegalStateException("army belongs to another owner");
            if (!blank(name)) node.name = name;
            if (!blank(realm)) node.realm = realm;
        }
        return node;
    }

    public Corps defaultCorps(int armyId) {
        ArmyNode army = requireArmy(armyId);
        for (String id : army.corpsIds) {
            Corps existing = corps.get(id);
            if (existing != null) return existing;
        }
        return createCorps(armyId, "1-й корпус");
    }

    public Corps createCorps(int armyId, String name) {
        ArmyNode army = requireArmy(armyId);
        String id = "corps-" + nextCorps++;
        Corps value = new Corps(id, armyId, safe(name, "Корпус " + nextCorps));
        corps.put(id, value);
        army.corpsIds.add(id);
        return value;
    }

    public boolean assignSquad(String corpsId, String squadId, Squads squads) {
        Corps target = corps.get(corpsId);
        Squads.Squad squad = squads == null ? null : squads.get(squadId);
        if (target == null || squad == null) return false;
        ArmyNode army = armies.get(target.armyId);
        if (army == null || !army.owner.equals(squad.owner)) return false;
        String previousId = corpsBySquad.remove(squadId);
        Corps previous = previousId == null ? null : corps.get(previousId);
        if (previous != null) previous.squadIds.remove(squadId);
        // Keep legacy direct-list mutations from creating duplicate command parents.
        for (Corps value : corps.values()) if (value != previous && value != target) value.squadIds.remove(squadId);
        if (!target.squadIds.contains(squadId)) target.squadIds.add(squadId);
        corpsBySquad.put(squadId, target.id);
        return true;
    }

    public boolean appointArmyCommander(int armyId, String characterId) {
        ArmyNode army = armies.get(armyId);
        if (army == null) return false;
        String id = safe(characterId, "");
        if (!validCharacterId(id)) return false;
        army.commanderCharacterId = id;
        return true;
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
        String indexedId = corpsBySquad.remove(squadId);
        Corps indexed = indexedId == null ? null : corps.get(indexedId);
        if (indexed != null) indexed.squadIds.remove(squadId);
        // Also remove any legacy duplicate not represented by the index.
        for (Corps value : corps.values()) if (value != indexed) value.squadIds.remove(squadId);
    }

    public void pruneSquads(Squads squads) {
        if (squads == null) return;
        for (Corps value : corps.values()) value.squadIds.removeIf(id -> {
            if (squads.get(id) != null) return false;
            corpsBySquad.remove(id);
            return true;
        });
    }

    public record DispatchStats(int issued, int delegated, int missing) { }

    public int commandArmy(int armyId, Squads squads, Squads.Order order, boolean queue) {
        return commandArmy(armyId, squads, order, queue, null).issued();
    }

    /** Direct/inherited parent orders are not allowed to overwrite delegated child squads. */
    public DispatchStats commandArmy(int armyId, Squads squads, Squads.Order order, boolean queue,
                                     CommandDelegation delegation) {
        ArmyNode army = armies.get(armyId);
        if (army == null || squads == null || order == null) return new DispatchStats(0, 0, 0);
        int sent = 0, delegated = 0, missing = 0;
        for (String corpsId : army.corpsIds) {
            Corps child = this.corps.get(corpsId);
            if (child == null) continue;
            DispatchStats stats = commandSquads(child.squadIds, army.owner, squads, order, queue, delegation);
            sent += stats.issued(); delegated += stats.delegated(); missing += stats.missing();
        }
        return new DispatchStats(sent, delegated, missing);
    }

    public int commandCorps(String corpsId, Squads squads, Squads.Order order, boolean queue) {
        return commandCorps(corpsId, squads, order, queue, null).issued();
    }

    public DispatchStats commandCorps(String corpsId, Squads squads, Squads.Order order, boolean queue,
                                      CommandDelegation delegation) {
        Corps child = this.corps.get(corpsId);
        if (child == null || squads == null || order == null) return new DispatchStats(0, 0, 0);
        ArmyNode army = armies.get(child.armyId);
        return army == null ? new DispatchStats(0, 0, 0)
                : commandSquads(child.squadIds, army.owner, squads, order, queue, delegation);
    }

    private DispatchStats commandSquads(Collection<String> ids, String owner, Squads squads,
                                        Squads.Order order, boolean queue, CommandDelegation delegation) {
        int sent = 0, delegated = 0, missing = 0;
        for (String id : ids) {
            Squads.Squad squad = squads.get(id);
            if (squad == null || !owner.equals(squad.owner)) { missing++; continue; }
            if (delegation != null && !delegation.acceptsInheritedOrder(id)) { delegated++; continue; }
            squads.command(id, order, queue);
            sent++;
        }
        return new DispatchStats(sent, delegated, missing);
    }

    public String viewJson(int armyId) {
        ArmyNode army = armies.get(armyId);
        if (army == null) return "{}";
        List<Object> children = new ArrayList<>();
        for (String id : army.corpsIds) {
            Corps corps = this.corps.get(id);
            if (corps == null) continue;
            children.add(Map.of("id", corps.id, "name", corps.name,
                    "commander", corps.commanderCharacterId, "squads", new ArrayList<>(corps.squadIds)));
        }
        return Json.write(Map.of("armyId", army.strategicArmyId, "name", army.name,
                "realm", army.realm, "commander", army.commanderCharacterId, "corps", children));
    }

    public String toJson() {
        List<Object> savedArmies = new ArrayList<>();
        for (ArmyNode army : armies.values()) savedArmies.add(Map.of(
                "id", army.strategicArmyId, "name", army.name, "owner", army.owner, "realm", army.realm,
                "commander", army.commanderCharacterId, "corps", new ArrayList<>(army.corpsIds)));
        List<Object> savedCorps = new ArrayList<>();
        for (Corps value : corps.values()) savedCorps.add(Map.of(
                "id", value.id, "army", value.armyId, "name", value.name,
                "commander", value.commanderCharacterId, "squads", new ArrayList<>(value.squadIds)));
        return Json.write(Map.of("format", "wf-army-hierarchy/1", "nextCorps", nextCorps,
                "armies", savedArmies, "corps", savedCorps));
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?,?> root) || !"wf-army-hierarchy/1".equals(root.get("format")))
            throw new IllegalArgumentException("Invalid army hierarchy save");
        armies.clear(); corps.clear(); corpsBySquad.clear(); nextCorps = 1;
        if (root.get("armies") instanceof List<?> values) for (Object item : values) {
            if (!(item instanceof Map<?,?> m)) continue;
            int id = integer(m.get("id"), 0);
            String owner = string(m.get("owner")), realm = string(m.get("realm"));
            if (id <= 0 || owner.isBlank() || armies.containsKey(id)) continue;
            ArmyNode node = new ArmyNode(id, safe(string(m.get("name")), "Армия " + id), owner, realm);
            String commander = string(m.get("commander"));
            if (validCharacterId(commander)) node.commanderCharacterId = commander;
            armies.put(id, node);
        }
        if (root.get("corps") instanceof List<?> values) for (Object item : values) {
            if (!(item instanceof Map<?,?> m)) continue;
            String id = string(m.get("id"));
            int armyId = integer(m.get("army"), 0);
            if (id.isBlank() || corps.containsKey(id) || !armies.containsKey(armyId)) continue;
            Corps value = new Corps(id, armyId, safe(string(m.get("name")), id));
            String commander = string(m.get("commander"));
            if (validCharacterId(commander)) value.commanderCharacterId = commander;
            if (m.get("squads") instanceof List<?> list)
                for (Object squad : list) if (squad instanceof String squadId && !squadId.isBlank()
                        && !value.squadIds.contains(squadId)) value.squadIds.add(squadId);
            corps.put(id, value);
            armies.get(armyId).corpsIds.add(id);
            if (id.startsWith("corps-")) try {
                nextCorps = Math.max(nextCorps, Math.addExact(Integer.parseInt(id.substring(6)), 1));
            } catch (RuntimeException ignored) { }
        }
        if (root.get("nextCorps") instanceof Number n) nextCorps = Math.max(nextCorps, n.intValue());
        // Reconcile the reverse references; a squad may belong to exactly one corps.
        Set<String> seen = new HashSet<>();
        for (Corps value : corps.values()) {
            value.squadIds.removeIf(id -> !seen.add(id));
            for (String squadId : value.squadIds) corpsBySquad.put(squadId, value.id);
        }
    }

    private ArmyNode requireArmy(int id) {
        ArmyNode army = armies.get(id);
        if (army == null) throw new IllegalArgumentException("No strategic army " + id);
        return army;
    }
    private static boolean validCharacterId(String id) { return id.isEmpty() || id.length() <= 64 && id.matches("[A-Za-z0-9_.:-]+"); }
    private static String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
}
