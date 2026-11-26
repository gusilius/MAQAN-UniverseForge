package dev.worldforge.court;


/** Козни: персонаж втайне действует против другого; успех бросается детерминированно по семени и дню. */
public final class Scheme {
    public enum Kind {
        BEFRIEND("Подружиться", 40, 0.70), SWAY("Склонить на свою сторону", 60, 0.55), MURDER("Убийство", 90, 0.35);

        public final String title;
        public final int days;
        public final double base;

        Kind(String title, int days, double base) { this.title = title; this.days = days; this.base = base; }
    }

    public final int id;
    public final Kind kind;
    public final String owner, target;                                // персонажи
    public final String ownerRealm;
    public double progress;                                           // дней из kind.days
    public double secrecy = 0.8;                                      // растёт и падает: 0..1
    public boolean exposed;
    public String result = "";

    Scheme(int id, Kind kind, String owner, String target, String ownerRealm) { this.id = id; this.kind = kind; this.owner = owner; this.target = target; this.ownerRealm = ownerRealm; }
}
