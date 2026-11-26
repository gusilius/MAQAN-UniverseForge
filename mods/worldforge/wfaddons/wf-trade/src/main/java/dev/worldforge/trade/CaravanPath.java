package dev.worldforge.trade;

/**
 * Путь каравана по прямой между столицами государств (в блоках мира). Прогресс - доля пройденного пути: прошедшие дни плюс доля текущего дня.
 * Чистая математика для склейки, которая ставит сущности каравана рядом с игроками.
 */
public final class CaravanPath {
    private CaravanPath() {}

    public record Pos(double x, double z, double yaw) {}

    /** Доля пути 0..1: total - дней в пути, left - осталось полных дней, dayFraction - сколько прошло текущего дня (0..1). */
    public static double progress(int total, int left, double dayFraction) {
        if (total <= 0) return 1;
        double done = (total - left) + Math.max(0, Math.min(1, dayFraction));
        return Math.max(0, Math.min(1, done / total));
    }

    /** Точка пути и курс (yaw Майнкрафта: 0 - на юг, +Z). */
    public static Pos at(double ax, double az, double bx, double bz, double p) {
        p = Math.max(0, Math.min(1, p));
        double dx = bx - ax, dz = bz - az;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return new Pos(ax + dx * p, az + dz * p, yaw);
    }

    /** Смещение отряда в колонне: i-й участник идёт на gap блоков позади головы, с боковым разбросом. */
    public static Pos member(Pos head, int i, double gap) {
        double rad = Math.toRadians(head.yaw());
        double fx = -Math.sin(rad), fz = Math.cos(rad);       // вперёд
        double side = i == 0 ? 0 : (i % 2 == 0 ? 1 : -1) * 1.2;
        return new Pos(head.x() - fx * gap * i + (-fz) * side, head.z() - fz * gap * i + fx * side, head.yaw());
    }

    /** Расстояние до точки, блоки. */
    public static double dist(double x1, double z1, double x2, double z2) { return Math.hypot(x1 - x2, z1 - z2); }

    // ---- путь по цепочке провинций ----

    /** Кратчайший путь по графу соседей (в числе переходов); пусто, если пути нет или он длиннее limit. Включает начало и конец. */
    public static java.util.List<Integer> bfs(java.util.function.IntFunction<int[]> neighbors, int from, int to, int limit) {
        if (from == to) return java.util.List.of(from);
        java.util.Map<Integer, Integer> prev = new java.util.HashMap<>();
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        java.util.Map<Integer, Integer> depth = new java.util.HashMap<>();
        q.add(from); prev.put(from, -1); depth.put(from, 0);
        while (!q.isEmpty()) {
            int cur = q.poll();
            if (depth.get(cur) >= limit) continue;
            int[] nb = neighbors.apply(cur);
            if (nb == null) continue;
            for (int n : nb) {
                if (prev.containsKey(n)) continue;
                prev.put(n, cur); depth.put(n, depth.get(cur) + 1);
                if (n == to) {
                    java.util.LinkedList<Integer> path = new java.util.LinkedList<>();
                    for (int x = to; x != -1; x = prev.get(x)) path.addFirst(x);
                    return path;
                }
                q.add(n);
            }
        }
        return java.util.List.of();
    }

    /** Длина ломаной в блоках. */
    public static double length(double[][] pts) {
        double l = 0;
        for (int i = 1; i < pts.length; i++) l += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
        return l;
    }

    /** Точка и курс на доле p (0..1) длины ломаной: караван идёт от провинции к провинции, а не напрямик. */
    public static Pos along(double[][] pts, double p) {
        if (pts.length == 0) return new Pos(0, 0, 0);
        if (pts.length == 1) return new Pos(pts[0][0], pts[0][1], 0);
        double target = Math.max(0, Math.min(1, p)) * length(pts), acc = 0;
        for (int i = 1; i < pts.length; i++) {
            double seg = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
            if (acc + seg >= target || i == pts.length - 1) return at(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1], seg <= 0 ? 1 : (target - acc) / seg);
            acc += seg;
        }
        return new Pos(pts[pts.length - 1][0], pts[pts.length - 1][1], 0);
    }
}
