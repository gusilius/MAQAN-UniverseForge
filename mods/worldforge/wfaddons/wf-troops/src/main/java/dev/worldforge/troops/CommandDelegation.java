package dev.worldforge.troops;

import java.util.*;

/**
 * Explicit, revocable AI command authority for a single canonical squad.
 * Direct player orders are never gated by this policy; it only authorizes autonomous decisions.
 */
public final class CommandDelegation {
    public enum Mode { PLAYER_DIRECT, DELEGATED, AUTONOMOUS }
    public enum Action { REPOSITION, REFORM, ENGAGE_VISIBLE_THREAT, PURSUE, RETREAT, CHANGE_OBJECTIVE }

    public record Policy(String squadId, Mode mode, String commanderCharacterId,
                         String objective, int leashBlocks, boolean allowEngagement,
                         boolean allowPursuit, boolean allowRetreat) {
        public Policy {
            if (squadId == null || squadId.isBlank() || mode == null)
                throw new IllegalArgumentException("squad and command mode are required");
            commanderCharacterId = commanderCharacterId == null ? "" : commanderCharacterId.trim();
            objective = objective == null ? "" : objective.trim();
            leashBlocks = Math.max(0, Math.min(512, leashBlocks));
            if (mode == Mode.PLAYER_DIRECT && !commanderCharacterId.isEmpty())
                commanderCharacterId = "";
        }
    }

    private final Map<String, Policy> policies = new LinkedHashMap<>();

    /** Unconfigured squads always remain under direct player control. */
    public Policy get(String squadId) {
        if (squadId == null || squadId.isBlank()) return null;
        return policies.getOrDefault(squadId,
                new Policy(squadId, Mode.PLAYER_DIRECT, "", "", 0, false, false, false));
    }

    public Collection<Policy> all() { return Collections.unmodifiableCollection(policies.values()); }

    public boolean assign(String squadId, Mode mode, String commanderCharacterId,
                          String objective, int leashBlocks, boolean allowEngagement,
                          boolean allowPursuit, boolean allowRetreat) {
        if (squadId == null || squadId.isBlank() || mode == null) return false;
        String commander = commanderCharacterId == null ? "" : commanderCharacterId.trim();
        if (mode != Mode.PLAYER_DIRECT && (commander.isEmpty()
                || commander.length() > 64 || !commander.matches("[A-Za-z0-9_.:-]+"))) return false;
        if (mode == Mode.PLAYER_DIRECT) {
            policies.remove(squadId);
            return true;
        }
        policies.put(squadId, new Policy(squadId, mode, commander, objective, leashBlocks,
                allowEngagement, allowPursuit, allowRetreat));
        return true;
    }

    /** Reclaiming a squad is always permitted and immediately removes AI authority. */
    public boolean reclaim(String squadId) {
        return squadId != null && policies.remove(squadId) != null;
    }

    public void prune(Collection<String> existingSquadIds) {
        Set<String> existing = existingSquadIds == null ? Set.of() : new HashSet<>(existingSquadIds);
        policies.keySet().removeIf(id -> !existing.contains(id));
    }

    public boolean mayIssue(String squadId, String issuerCharacterId, Action action) {
        Policy p = get(squadId);
        if (p == null || p.mode() == Mode.PLAYER_DIRECT || action == null) return false;
        if (!p.commanderCharacterId().equals(issuerCharacterId == null ? "" : issuerCharacterId.trim()))
            return false;
        return switch (action) {
            case REPOSITION, REFORM -> true;
            case ENGAGE_VISIBLE_THREAT -> p.allowEngagement();
            case PURSUE -> p.allowEngagement() && p.allowPursuit();
            case RETREAT -> p.allowRetreat();
            case CHANGE_OBJECTIVE -> p.mode() == Mode.AUTONOMOUS;
        };
    }
}
