package dev.worldforge.settle;

/** Рабочий поселения. person - связь с персонажем мода людей (может быть пустой). */
public final class Worker {
    public record ManualOrder(double x,double z) {}
    public static final int MAX_MANUAL_ORDERS=16;
    public final java.util.ArrayDeque<ManualOrder> orderQueue=new java.util.ArrayDeque<>();
    public final int id;
    public String name, profession = "laborer", person = "";
    /** Current assigned job, separate from permanent profession/specialization. */
    public String assignedProfession = "";
    /** General workers are versatile but intentionally do not gain profession skill ranks. */
    public boolean generalist() { return "laborer".equals(profession) || "general_worker".equals(profession); }
    public String workRole() { return assignedProfession == null || assignedProfession.isBlank() ? profession : assignedProfession; }
    public boolean canPerform(String role) {
        return generalist() || profession.equals(role) || ("porter".equals(profession) && "hauler".equals(role));
    }
    public double productivity(String role) {
        if (!canPerform(role)) return 0;
        if (generalist()) return 1.0;
        return switch (role) {
            case "lumberjack" -> 1.5;
            case "builder" -> 1.5;
            case "miner" -> 1.35;
            case "hauler" -> "porter".equals(profession) ? 1.25 : 1.0;
            case "farmer" -> 1.25;
            default -> 1.2;
        };
    }
    public int building;            // uid здания, 0 - свободен
    public String status = "свободен";
    public double x = Settlement.SPAWN_X, z = Settlement.SPAWN_Z;   // положение для показа (блоки от центра поселения)
    public java.util.Map<String, Integer> jobPrio;                   // приоритеты работ 0..4 (null - по умолчанию), см. Settlement.workPriority
    public boolean moving;                                            // шёл в последнем шаге (не сохраняется)
    /** Ручной приказ RTS: удерживать назначенную точку. */
    public boolean manualOrder;
    /** Follow the settlement ruler/player instead of the assigned workplace. */
    public boolean manualFollow;
    public String manualFollowOwner = "";
    public double orderX, orderZ;
    /** Autonomous daily-life state temporarily suspends work without changing profession or job assignment. */
    public String lifeAction = "";
    public double lifeTargetX, lifeTargetZ;
    public double lifeActionUntil;
    public double haulProgress;

    Worker(int id, String name) { this.id = id; this.name = name; }
}
