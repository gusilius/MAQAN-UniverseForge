package dev.worldforge.rts;

/** Что находится под курсором: рабочий, здание или голая земля (координаты - блоки от центра поселения, как у {@code Settlement.place}). */
public final class Target {
    public enum Kind { GROUND, BUILDING, WORKER }

    public final Kind kind;
    public final int id;
    public final int x, z;

    private Target(Kind kind, int id, int x, int z) { this.kind = kind; this.id = id; this.x = x; this.z = z; }

    public static Target ground(int x, int z) { return new Target(Kind.GROUND, 0, x, z); }

    public static Target building(int uid, int x, int z) { return new Target(Kind.BUILDING, uid, x, z); }

    public static Target worker(int id, int x, int z) { return new Target(Kind.WORKER, id, x, z); }
}
