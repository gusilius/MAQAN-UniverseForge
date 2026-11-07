package dev.worldforge.war;

import java.util.*;

/** Одна война. Счёт ведётся с позиции нападающего: +100 - полная победа нападающих, -100 - обороняющихся. */
public final class War {
    public enum Side { ATTACKER, DEFENDER;
        public Side opposite() { return this == ATTACKER ? DEFENDER : ATTACKER; }
    }

    public final int id;
    public final String attacker, defender, cb;
    public final List<Integer> goals = new ArrayList<>();
    public final Map<String, Side> sides = new LinkedHashMap<>();
    public final Map<Integer, Side> occupied = new LinkedHashMap<>();     // титул -> кто его занимает
    public final List<String> log = new ArrayList<>();
    public double score;
    public long startDay, endDay;                                           // endDay - день окончания (для завершённых)
    public Side winner;                                                     // null при белом мире и пока война идёт
    public String result = "";                                              // короткий итог для истории
    public boolean active = true;
    public Side offerFrom;                                                  // кто предложил мир (null - никто)
    public boolean offerWhite;

    War(int id, String attacker, String defender, String cb, long startDay) { this.id = id; this.attacker = attacker; this.defender = defender; this.cb = cb; this.startDay = startDay; }

    public double scoreFor(Side s) { return s == Side.ATTACKER ? score : -score; }

    /** Итог для списков: «идёт», «победа нападающих», «белый мир». */
    public String resultText() {
        if (active) return "идёт";
        return winner == Side.ATTACKER ? "победа нападающих" : winner == Side.DEFENDER ? "победа обороняющихся" : "белый мир";
    }

    public Side sideOf(String realm) { return sides.get(realm); }

    public List<String> members(Side s) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Side> e : sides.entrySet()) if (e.getValue() == s) l.add(e.getKey());
        return l;
    }

    void say(long day, String text) {
        log.add("[день " + day + "] " + text);
        while (log.size() > 80) log.remove(0);
    }
}
