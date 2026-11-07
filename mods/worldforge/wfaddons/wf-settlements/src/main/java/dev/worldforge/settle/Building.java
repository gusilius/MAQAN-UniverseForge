package dev.worldforge.settle;

/** Здание поселения. PLANNED - ждёт материалов, BUILDING - строят, DONE - работает. */
public final class Building {
    public enum State { PLANNED, BUILDING, DONE }

    public final int uid;
    public final Defs.Building def;
    public final int x, z;
    public State state = State.PLANNED;
    public double progress, cycleTime;
    public int priority = 5;                 // 1..9, выше - раньше получает рабочих и материалы
    public boolean unpaid;                   // не хватило серебра на содержание (не сохраняется)
    public boolean paused;                   // производство остановлено вручную
    public String status = "";

    Building(int uid, Defs.Building def, int x, int z) { this.uid = uid; this.def = def; this.x = x; this.z = z; }
}
