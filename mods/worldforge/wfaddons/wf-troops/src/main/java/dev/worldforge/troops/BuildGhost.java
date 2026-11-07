package dev.worldforge.troops;

import java.util.*;

/**
 * Призрак здания при постановке мышкой. Размеры и занятые места приходят с сервера; координаты здания в wf-settlements -
 * смещение центра от центра поселения, поэтому всё считается в мировых блоках и переводится в смещение при отправке.
 */
public final class BuildGhost {
    public record Def(String id, String name, String category, int sx, int sz, Map<String, Integer> cost, String desc) {}
    public record Rect(double cx, double cz, int sx, int sz) {}

    public static final int MAX_RADIUS = 96;

    /** Привязка центра к блокам: чётный размер - к границе блоков, нечётный - к центру блока (как кладёт здание по {@code x - sx/2}). */
    public static int[] snap(double wx, double wz) { return new int[]{(int) Math.floor(wx), (int) Math.floor(wz)}; }

    /** Угловые точки основания {x,z} по кругу. */
    public static double[][] corners(int cx, int cz, int sx, int sz) {
        double x0 = cx - sx / 2.0, z0 = cz - sz / 2.0;
        return new double[][]{{x0, z0}, {x0 + sx, z0}, {x0 + sx, z0 + sz}, {x0, z0 + sz}};
    }

    /** Пересечение с занятым местом (то же правило, что Settlement.placeError: |dx|*2 < sx1+sx2). */
    public static boolean overlaps(int cx, int cz, int sx, int sz, Rect o) {
        return Math.abs(o.cx() - cx) * 2 < o.sx() + sx && Math.abs(o.cz() - cz) * 2 < o.sz() + sz;
    }

    /** Почему нельзя ставить здесь; пустая строка - можно. */
    public static String check(Def d, int cx, int cz, int colonyX, int colonyZ, List<Rect> taken, Map<String, Integer> stock) {
        if (Math.hypot(cx - colonyX, cz - colonyZ) > MAX_RADIUS) return "Слишком далеко от центра поселения";
        for (Rect r : taken) if (overlaps(cx, cz, d.sx(), d.sz(), r)) return "Место занято";
        return "";
    }

    /** Нехватка материалов на складе: «дерево 15, камень 4» или пусто. Стройка стартует и ждёт, так что это лишь подсказка. */
    public static String missing(Def d, Map<String, Integer> stock) {
        StringBuilder b = new StringBuilder();
        for (var e : d.cost().entrySet()) {
            int have = stock.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) b.append(b.length() > 0 ? ", " : "").append(e.getKey()).append(' ').append(e.getValue() - have);
        }
        return b.toString();
    }

    public static int[] offset(int wx, int wz, int colonyX, int colonyZ) { return new int[]{wx - colonyX, wz - colonyZ}; }

    @SuppressWarnings("unchecked")
    public static List<Def> parseDefs(Object defs) {
        List<Def> out = new ArrayList<>();
        if (!(defs instanceof List<?> l)) return out;
        for (Object o : l) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Integer> cost = new LinkedHashMap<>();
            if (m.get("cost") instanceof Map<?, ?> c) for (var e : c.entrySet()) cost.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
            int sx = 3, sz = 3;
            if (m.get("size") instanceof List<?> s && s.size() >= 2) { sx = ((Number) s.get(0)).intValue(); sz = ((Number) s.get(1)).intValue(); }
            out.add(new Def(String.valueOf(m.get("id")), String.valueOf(m.getOrDefault("name", m.get("id"))), String.valueOf(m.getOrDefault("category", "other")), sx, sz, cost,
                    String.valueOf(m.getOrDefault("desc", ""))));
        }
        return out;
    }
}
