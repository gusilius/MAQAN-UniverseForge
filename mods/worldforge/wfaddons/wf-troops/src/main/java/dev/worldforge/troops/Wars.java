package dev.worldforge.troops;

/** Войны государств для солдат в мире: свойство государства "enemies" (список id через запятую) - те, с кем оно воюет. Чистая логика. */
public final class Wars {
    private Wars() {}

    public static boolean listed(Object enemiesCsv, String id) {
        if (enemiesCsv == null || id == null || id.isEmpty()) return false;
        for (String s : String.valueOf(enemiesCsv).split(",")) if (s.trim().equals(id)) return true;
        return false;
    }

    /** Воюют ли a и b: достаточно, чтобы одна сторона записала другую во враги. Одно государство или без государства - не воюют. */
    public static boolean atWar(String a, Object enemiesOfA, String b, Object enemiesOfB) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) return false;
        return listed(enemiesOfA, b) || listed(enemiesOfB, a);
    }
}
