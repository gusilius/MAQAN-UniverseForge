package dev.worldforge.troops;

import dev.worldforge.mod.Json;
import java.util.*;

/**
 * Explicit, revocable command authority for army -> corps -> squad.
 * A more specific scope overrides its parent. An explicit PLAYER_DIRECT policy
 * blocks inherited AI authority; an absent policy inherits from the nearest parent.
 */
public final class CommandDelegation {
    public enum Mode { PLAYER_DIRECT, DELEGATED, AUTONOMOUS }
    public enum Action { REPOSITION, REFORM, ENGAGE_VISIBLE_THREAT, PURSUE, RETREAT, CHANGE_OBJECTIVE }
    public enum ScopeType { ARMY, CORPS, SQUAD }

    /**
     * The squadId component is retained for source compatibility with the first
     * squad-only policy version. It now stores a canonical scope key; use scopeKey().
     */
    public record Policy(String squadId, Mode mode, String commanderCharacterId,
                         String objective, int leashBlocks, boolean allowEngagement,
                         boolean allowPursuit, boolean allowRetreat) {
        public Policy {
            if (squadId == null || squadId.isBlank() || mode == null)
                throw new IllegalArgumentException("scope and command mode are required");
            commanderCharacterId = commanderCharacterId == null ? "" : commanderCharacterId.trim();
            objective = objective == null ? "" : objective.trim();
            leashBlocks = Math.max(0, Math.min(512, leashBlocks));
            if (mode == Mode.PLAYER_DIRECT) commanderCharacterId = "";
        }
        public String scopeKey() { return squadId; }
    }

    private final Map<String, Policy> policies = new LinkedHashMap<>();

    private static String key(ScopeType type, String id) {
        if (type == null || id == null || id.isBlank()) return "";
        String value = id.trim();
        if (value.length() > 64 || !value.matches("[A-Za-z0-9_.:-]+")) return "";
        if (type == ScopeType.ARMY) {
            try { if (Integer.parseInt(value) <= 0) return ""; }
            catch (NumberFormatException ex) { return ""; }
        }
        return type.name().toLowerCase(Locale.ROOT) + ":" + value;
    }

    private static ScopeType scopeType(String scopeKey) {
        if (scopeKey == null) return null;
        int split = scopeKey.indexOf(':');
        if (split <= 0 || split == scopeKey.length() - 1) return null;
        try { return ScopeType.valueOf(scopeKey.substring(0, split).toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String scopeId(String scopeKey) {
        int split = scopeKey == null ? -1 : scopeKey.indexOf(':');
        return split < 0 || split == scopeKey.length() - 1 ? "" : scopeKey.substring(split + 1);
    }

    private static Policy direct(String scopeKey) {
        return new Policy(scopeKey, Mode.PLAYER_DIRECT, "", "", 0, false, false, false);
    }

    /** Unconfigured squads always remain under direct player control. */
    public Policy get(String squadId) {
        String scope = key(ScopeType.SQUAD, squadId);
        return scope.isEmpty() ? null : policies.getOrDefault(scope, direct(squadId));
    }

    public Policy getScope(ScopeType type, String id) {
        String scope = key(type, id);
        return scope.isEmpty() ? null : policies.getOrDefault(scope, direct(scope));
    }

    /**
     * Resolve the effective policy for a squad. Precedence is squad, then corps,
     * then army. Unassigned squads default to PLAYER_DIRECT.
     */
    public Policy getEffective(String squadId, ArmyHierarchy hierarchy) {
        Resolution resolution = resolveEffective(squadId, hierarchy);
        return resolution == null ? null : resolution.policy();
    }

    public Collection<Policy> all() { return Collections.unmodifiableCollection(policies.values()); }

    /** Effective authority plus the exact scope that supplied it, for truthful UI/status reporting. */
    public record Resolution(Policy policy, ScopeType sourceType, String sourceId, boolean inherited) { }

    public Resolution resolveEffective(String squadId, ArmyHierarchy hierarchy) {
        String squadKey = key(ScopeType.SQUAD, squadId);
        if (squadKey.isEmpty()) return null;
        Policy own = policies.get(squadKey);
        if (own != null) return new Resolution(own, ScopeType.SQUAD, squadId, false);
        if (hierarchy != null) {
            ArmyHierarchy.Corps corps = hierarchy.corpsForSquad(squadId);
            if (corps != null) {
                Policy corpsPolicy = policies.get(key(ScopeType.CORPS, corps.id));
                if (corpsPolicy != null)
                    return new Resolution(corpsPolicy, ScopeType.CORPS, corps.id, true);
                ArmyHierarchy.ArmyNode army = hierarchy.armyForSquad(squadId);
                if (army != null) {
                    Policy armyPolicy = policies.get(key(ScopeType.ARMY, Integer.toString(army.strategicArmyId)));
                    if (armyPolicy != null)
                        return new Resolution(armyPolicy, ScopeType.ARMY,
                                Integer.toString(army.strategicArmyId), true);
                }
            }
        }
        return new Resolution(direct(squadKey), null, "", false);
    }

    /** Legacy squad API: PLAYER_DIRECT removes a squad override and resumes parent inheritance. */
    public boolean assign(String squadId, Mode mode, String commanderCharacterId,
                          String objective, int leashBlocks, boolean allowEngagement,
                          boolean allowPursuit, boolean allowRetreat) {
        if (mode == Mode.PLAYER_DIRECT) return reclaim(squadId);
        return assignScope(ScopeType.SQUAD, squadId, mode, commanderCharacterId, objective,
                leashBlocks, allowEngagement, allowPursuit, allowRetreat);
    }

    /** Assign policy at army, corps or squad scope. PLAYER_DIRECT is stored as an explicit inheritance barrier. */
    public boolean assignScope(ScopeType type, String id, Mode mode, String commanderCharacterId,
                               String objective, int leashBlocks, boolean allowEngagement,
                               boolean allowPursuit, boolean allowRetreat) {
        String scope = key(type, id);
        if (scope.isEmpty() || mode == null) return false;
        String commander = commanderCharacterId == null ? "" : commanderCharacterId.trim();
        if (mode != Mode.PLAYER_DIRECT && (commander.isEmpty()
                || commander.length() > 64 || !commander.matches("[A-Za-z0-9_.:-]+"))) return false;
        if (mode == Mode.PLAYER_DIRECT) {
            policies.put(scope, direct(scope));
            return true;
        }
        policies.put(scope, new Policy(scope, mode, commander, objective, leashBlocks,
                allowEngagement, allowPursuit, allowRetreat));
        return true;
    }

    /** Immediately return this squad to player control, even when a parent scope is delegated. */
    public boolean reclaim(String squadId) {
        String scope = key(ScopeType.SQUAD, squadId);
        if (scope.isEmpty()) return false;
        policies.put(scope, direct(scope));
        return true;
    }

    /** Remove the local override and resume inheritance from corps / army. */
    public boolean resumeInheritance(String squadId) {
        String scope = key(ScopeType.SQUAD, squadId);
        return !scope.isEmpty() && policies.remove(scope) != null;
    }

    public boolean reclaimScope(ScopeType type, String id) {
        String scope = key(type, id);
        return !scope.isEmpty() && policies.remove(scope) != null;
    }

    /** Legacy squad-only pruning; army/corps policies are retained. */
    public void prune(Collection<String> existingSquadIds) {
        Set<String> existing = existingSquadIds == null ? Set.of() : new HashSet<>(existingSquadIds);
        policies.keySet().removeIf(scope -> scopeType(scope) == ScopeType.SQUAD
                && !existing.contains(scopeId(scope)));
    }

    /** Remove policies whose army, corps or squad no longer exists in the authoritative hierarchy. */
    public void prune(ArmyHierarchy hierarchy, Squads squads) {
        policies.keySet().removeIf(scope -> {
            ScopeType type = scopeType(scope);
            String id = scopeId(scope);
            if (type == ScopeType.SQUAD) return squads == null || squads.get(id) == null;
            if (type == ScopeType.CORPS) return hierarchy == null || hierarchy.corps(id) == null;
            if (type == ScopeType.ARMY) {
                if (hierarchy == null) return true;
                try { return hierarchy.army(Integer.parseInt(id)) == null; }
                catch (NumberFormatException ignored) { return true; }
            }
            return true;
        });
    }

    public String toJson() {
        List<Object> saved = new ArrayList<>();
        for (Policy p : policies.values()) saved.add(Map.of(
                "scope", p.scopeKey(), "squad", p.scopeKey(),
                "mode", p.mode().name(), "commander", p.commanderCharacterId(),
                "objective", p.objective(), "leash", p.leashBlocks(), "engage", p.allowEngagement(),
                "pursue", p.allowPursuit(), "retreat", p.allowRetreat()));
        return Json.write(Map.of("format", "wf-command-delegation/2", "policies", saved));
    }

    /** Reads both the original squad-only /1 format and hierarchical /2 format. */
    public void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Invalid command delegation save");
        String format = string(root.get("format"));
        if (!"wf-command-delegation/1".equals(format) && !"wf-command-delegation/2".equals(format))
            throw new IllegalArgumentException("Invalid command delegation save");
        Map<String, Policy> restored = new LinkedHashMap<>();
        if (root.get("policies") instanceof List<?> list) for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String scope = string(m.get("scope"));
            if (scope.isBlank()) {
                String legacySquad = string(m.get("squad"));
                scope = key(ScopeType.SQUAD, legacySquad);
            }
            ScopeType type = scopeType(scope);
            String id = scopeId(scope);
            if (type == null || key(type, id).isEmpty() || restored.containsKey(scope)) continue;
            Mode mode;
            try { mode = Mode.valueOf(string(m.get("mode"))); }
            catch (RuntimeException ignored) { continue; }
            String commander = string(m.get("commander"));
            if (mode != Mode.PLAYER_DIRECT && (commander.isBlank()
                    || !commander.matches("[A-Za-z0-9_.:-]{1,64}"))) continue;
            restored.put(scope, new Policy(scope, mode, commander, string(m.get("objective")),
                    integer(m.get("leash"), 0), bool(m.get("engage")), bool(m.get("pursue")), bool(m.get("retreat"))));
        }
        policies.clear();
        policies.putAll(restored);
    }

    public boolean mayIssue(String squadId, String issuerCharacterId, Action action) {
        return mayIssue(get(squadId), issuerCharacterId, action);
    }

    public boolean mayIssueForSquad(String squadId, String issuerCharacterId,
                                    Action action, ArmyHierarchy hierarchy) {
        return mayIssue(getEffective(squadId, hierarchy), issuerCharacterId, action);
    }

    private static boolean mayIssue(Policy policy, String issuerCharacterId, Action action) {
        if (policy == null || policy.mode() == Mode.PLAYER_DIRECT || action == null) return false;
        if (!policy.commanderCharacterId().equals(issuerCharacterId == null ? "" : issuerCharacterId.trim()))
            return false;
        return switch (action) {
            case REPOSITION, REFORM -> true;
            case ENGAGE_VISIBLE_THREAT -> policy.allowEngagement();
            case PURSUE -> policy.allowEngagement() && policy.allowPursuit();
            case RETREAT -> policy.allowRetreat();
            case CHANGE_OBJECTIVE -> policy.mode() == Mode.AUTONOMOUS;
        };
    }

    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static boolean bool(Object value) { return value instanceof Boolean b && b; }
}
