package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Explicit per-squad command authority. Parent army/corps orders are inherited;
 * delegated squads retain their local order until their commander/AI changes it.
 * Java remains authoritative for permissions and applying orders.
 */
public final class CommandDelegation {
    public enum Mode { PLAYER_DIRECT, DELEGATED, AUTONOMOUS }
    public enum Action { REPOSITION, REFORM, ENGAGE_VISIBLE_THREAT, PURSUE, RETREAT, CHANGE_OBJECTIVE }

    public record Policy(String squadId, Mode mode, String commanderCharacterId, String objective,
                         double leashBlocks, boolean allowEngagement, boolean allowPursuit,
                         boolean allowRetreat) { }
    public record DispatchResult(int issued, int delegated, int missing) { }

    private final Map<String, Policy> policies = new LinkedHashMap<>();

    public Policy get(String squadId) {
        Policy value = policies.get(squadId);
        return value == null ? new Policy(squadId, Mode.PLAYER_DIRECT, "", "", 0, true, true, true) : value;
    }

    public Collection<Policy> all() { return Collections.unmodifiableCollection(policies.values()); }

    public boolean assign(String squadId, Mode mode, String commanderCharacterId, String objective,
                          double leashBlocks, boolean allowEngagement, boolean allowPursuit,
                          boolean allowRetreat) {
        if (squadId == null || squadId.isBlank() || squadId.length() > 64 || mode == null
                || !Double.isFinite(leashBlocks) || leashBlocks < 0 || leashBlocks > 100000
                || objective != null && objective.length() > 256) return false;
        String commander = commanderCharacterId == null ? "" : commanderCharacterId.trim();
        if (mode != Mode.PLAYER_DIRECT && (commander.isBlank() || commander.length() > 64
                || !commander.matches("[A-Za-z0-9_.:-]+"))) return false;
        if (mode == Mode.PLAYER_DIRECT) { policies.remove(squadId); return true; }
        policies.put(squadId, new Policy(squadId, mode, commander,
                objective == null ? "" : objective.trim(), leashBlocks,
                allowEngagement, allowPursuit, allowRetreat));
        return true;
    }

    /** Parent army/corps fan-out must not overwrite a squad with delegated authority. */
    public boolean acceptsInheritedOrder(String squadId) {
        return get(squadId).mode() == Mode.PLAYER_DIRECT;
    }

    /** A direct player order to the whole squad immediately reclaims it. */
    public boolean reclaim(String squadId) { return squadId != null && policies.remove(squadId) != null; }

    public void prune(Squads squads) {
        if (squads != null) policies.keySet().removeIf(id -> squads.get(id) == null);
    }

    public boolean mayIssue(String squadId, String commanderCharacterId, Action action) {
        Policy policy = get(squadId);
        if (policy.mode() == Mode.PLAYER_DIRECT || action == null
                || !policy.commanderCharacterId().equals(commanderCharacterId)) return false;
        return switch (action) {
            case ENGAGE_VISIBLE_THREAT -> policy.allowEngagement();
            case PURSUE -> policy.allowPursuit();
            case RETREAT -> policy.allowRetreat();
            case REPOSITION, REFORM -> true;
            case CHANGE_OBJECTIVE -> policy.mode() == Mode.AUTONOMOUS
                    && !policy.objective().isBlank();
        };
    }

    public String toJson() {
        List<Object> values = new ArrayList<>();
        for (Policy p : policies.values()) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("squad", p.squadId()); item.put("mode", p.mode().name());
            item.put("commander", p.commanderCharacterId()); item.put("objective", p.objective());
            item.put("leash", p.leashBlocks()); item.put("engage", p.allowEngagement());
            item.put("pursue", p.allowPursuit()); item.put("retreat", p.allowRetreat());
            values.add(item);
        }
        return Json.write(Map.of("format", "wf-command-delegation/1", "policies", values));
    }

    public void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?,?> root)
                || !"wf-command-delegation/1".equals(root.get("format")))
            throw new IllegalArgumentException("Invalid command delegation save");
        Map<String,Policy> loaded = new LinkedHashMap<>();
        if (root.get("policies") instanceof List<?> list) for (Object item : list) {
            if (!(item instanceof Map<?,?> m)) continue;
            String squad = string(m.get("squad")), commander = string(m.get("commander"));
            String objective = string(m.get("objective"));
            Mode mode;
            try { mode = Mode.valueOf(string(m.get("mode"))); }
            catch (RuntimeException invalid) { continue; }
            double leash = number(m.get("leash"), 0);
            if (squad.isBlank() || loaded.containsKey(squad) || !Double.isFinite(leash)
                    || leash < 0 || leash > 100000) continue;
            if (mode == Mode.PLAYER_DIRECT || commander.isBlank() || commander.length() > 64
                    || !commander.matches("[A-Za-z0-9_.:-]+") || objective.length() > 256) continue;
            loaded.put(squad, new Policy(squad, mode, commander, objective, leash,
                    bool(m.get("engage")), bool(m.get("pursue")), bool(m.get("retreat"))));
        }
        policies.clear();
        policies.putAll(loaded);
    }

    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
}
