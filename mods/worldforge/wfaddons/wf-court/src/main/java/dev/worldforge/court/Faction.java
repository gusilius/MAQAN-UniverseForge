package dev.worldforge.court;


import java.util.*;

/** Фракция вассалов против сюзерена. Растёт мощью, пока не предъявит ультиматум (см. {@link CourtSim}). */
public final class Faction {
    public final int id;
    public final String type, liege;                                  // тип: independence | demands
    public final List<String> members = new ArrayList<>();           // id государств-вассалов
    public double power;                                              // доля силы всех подданных сюзерена (0..1)
    public String leader = "";
    public int formedDay;
    public double progress;                                           // 0..100: ультиматум при 100

    Faction(int id, String type, String liege, int formedDay) { this.id = id; this.type = type; this.liege = liege; this.formedDay = formedDay; }

    public String typeName() { return type.equals("independence") ? "Независимость" : "Требования вассалов"; }
}
