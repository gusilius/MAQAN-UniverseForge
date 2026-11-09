package dev.worldforge.settle;

/** Рабочий поселения. person - связь с персонажем мода людей (может быть пустой). */
public final class Worker {
    public record ManualOrder(double x,double z) {}
    public static final int MAX_MANUAL_ORDERS=16;
    public final java.util.ArrayDeque<ManualOrder> orderQueue=new java.util.ArrayDeque<>();
    public final int id;
    public String name, profession = "laborer", person = "";
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

    Worker(int id, String name) { this.id = id; this.name = name; }
}
