package dev.worldforge.troops;

/**
 * Policy for orders issued to a mixed selection of settlement workers and military units.
 * Workers remain non-combatants: they can move, hold or stop, but never receive direct attack orders.
 */
public final class MixedOrders {
    private MixedOrders() {}

    public enum WorkerAction { NONE, MOVE, FOLLOW, HOLD, STOP }

    public record Plan(boolean commandTroops, WorkerAction workers) {
        public boolean hasWork() { return commandTroops || workers != WorkerAction.NONE; }
    }

    public static Plan plan(Squads.Kind order, boolean workersSelected, boolean troopsSelected) {
        if (order == null) throw new IllegalArgumentException("order is required");
        WorkerAction workerAction = WorkerAction.NONE;
        if (workersSelected) {
            workerAction = switch (order) {
                case MOVE, ATTACK_MOVE, PATROL -> WorkerAction.MOVE;
                case FOLLOW -> WorkerAction.FOLLOW;
                case HOLD, GUARD -> WorkerAction.HOLD;
                case STOP, RETREAT -> WorkerAction.STOP;
                case ATTACK -> WorkerAction.NONE;
            };
        }
        return new Plan(troopsSelected, workerAction);
    }
}
