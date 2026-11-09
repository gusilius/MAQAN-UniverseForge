package dev.worldforge.troops;

import java.util.*;

/** One operational plan: objective, waypoints, assigned squads and explicit supply threshold. */
public final class CampaignOperation {
    public enum Phase { PLANNING, ASSEMBLING, ADVANCING, ENGAGING, RESUPPLYING, HOLDING, COMPLETED, ABORTED }
    public enum Objective { CAPTURE, DEFEND, RAID, ESCORT, RELIEF, SIEGE }

    public final String id;
    public final String owner;
    public final Objective objective;
    public final double targetX, targetZ;
    public final List<double[]> waypoints = new ArrayList<>();
    public final LinkedHashSet<String> squads = new LinkedHashSet<>();
    public Phase phase = Phase.PLANNING;
    public double minimumReadiness = 0.55;
    public String result = "";

    public CampaignOperation(String id, String owner, Objective objective, double targetX, double targetZ) {
        if (id == null || id.isBlank() || owner == null || owner.isBlank() || objective == null
                || !Double.isFinite(targetX) || !Double.isFinite(targetZ))
            throw new IllegalArgumentException("invalid operation identity or target");
        this.id = id; this.owner = owner; this.objective = objective; this.targetX = targetX; this.targetZ = targetZ;
    }

    public boolean assign(String squadId, String squadOwner) {
        if (terminal() || squadId == null || squadId.isBlank() || !owner.equals(squadOwner)) return false;
        return squads.add(squadId);
    }

    public boolean waypoint(double x, double z) {
        if (terminal() || !Double.isFinite(x) || !Double.isFinite(z) || waypoints.size() >= 64) return false;
        waypoints.add(new double[]{x, z});
        return true;
    }

    public boolean start() {
        if (phase != Phase.PLANNING || squads.isEmpty()) return false;
        phase = Phase.ASSEMBLING;
        return true;
    }

    public boolean advance(double averageReadiness) {
        if (phase != Phase.ASSEMBLING && phase != Phase.RESUPPLYING && phase != Phase.HOLDING) return false;
        if (!Double.isFinite(averageReadiness)) return false;
        if (averageReadiness < minimumReadiness) { phase = Phase.RESUPPLYING; return false; }
        phase = Phase.ADVANCING;
        return true;
    }

    public boolean engage() {
        if (phase != Phase.ADVANCING) return false;
        phase = Phase.ENGAGING;
        return true;
    }

    public boolean hold() {
        if (phase != Phase.ADVANCING && phase != Phase.ENGAGING) return false;
        phase = Phase.HOLDING;
        return true;
    }

    public boolean complete(String outcome) {
        if (terminal() || phase == Phase.PLANNING) return false;
        phase = Phase.COMPLETED;
        result = outcome == null ? "" : outcome;
        return true;
    }

    public boolean abort(String reason) {
        if (terminal()) return false;
        phase = Phase.ABORTED;
        result = reason == null ? "" : reason;
        return true;
    }

    public boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.ABORTED; }
}
