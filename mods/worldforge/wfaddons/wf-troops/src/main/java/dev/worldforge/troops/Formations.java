package dev.worldforge.troops;

import java.util.*;

/**
 * Строи отряда. Места задаются в локальных координатах отряда: x - вправо от направления движения, z - вперёд; (0,0) - центр.
 * place() поворачивает места на курс (yaw Майнкрафта: 0 - на юг, +Z) и сдвигает в точку. Чистая математика.
 */
public final class Formations {
    private Formations() {}

    public enum Type {
        LINE("Шеренга"), BLOCK("Каре"), COLUMN("Колонна"), WEDGE("Клин"), CIRCLE("Круг"), LOOSE("Россыпь");
        public final String title;
        Type(String t) { title = t; }
        public Type next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public record Slot(double x, double z) {}

    /** n мест в строю; spacing - расстояние между солдатами, блоки. Передние ряды первыми (z больше). */
    public static List<Slot> slots(Type type, int n, double spacing) {
        List<Slot> out = new ArrayList<>();
        if (n <= 0) return out;
        switch (type) {
            case LINE -> rows(out, n, Math.min(n, 12), spacing);
            case BLOCK -> rows(out, n, Math.max(1, (int) Math.ceil(Math.sqrt(n * 1.4))), spacing);
            case COLUMN -> rows(out, n, Math.min(n, 2), spacing);
            case WEDGE -> {
                int row = 0, placed = 0;
                while (placed < n) {
                    int inRow = row + 1;
                    for (int i = 0; i < inRow && placed < n; i++, placed++) out.add(new Slot((i - (inRow - 1) / 2.0) * spacing, -row * spacing));
                    row++;
                }
                double mz = out.stream().mapToDouble(Slot::z).average().orElse(0);
                out.replaceAll(s -> new Slot(s.x(), s.z() - mz));
            }
            case CIRCLE -> {
                int ring = 0, placed = 0;
                while (placed < n) {
                    int cap = ring == 0 ? 1 : ring * 6;
                    for (int i = 0; i < cap && placed < n; i++, placed++) {
                        double a = 2 * Math.PI * i / cap, r = ring * spacing;
                        out.add(new Slot(Math.sin(a) * r, Math.cos(a) * r));
                    }
                    ring++;
                }
            }
            case LOOSE -> {
                Random rng = new Random(n * 31L + 7);
                double side = Math.max(2, Math.sqrt(n)) * spacing * 1.6;
                for (int i = 0; i < n; i++) out.add(new Slot((rng.nextDouble() - 0.5) * side, (rng.nextDouble() - 0.5) * side));
            }
        }
        return out;
    }

    private static void rows(List<Slot> out, int n, int perRow, double spacing) {
        int rowsCount = (n + perRow - 1) / perRow;
        for (int i = 0; i < n; i++) {
            int r = i / perRow, c = i % perRow, inRow = Math.min(perRow, n - r * perRow);
            out.add(new Slot((c - (inRow - 1) / 2.0) * spacing, ((rowsCount - 1) / 2.0 - r) * spacing));
        }
    }

    /** Поворот мест на курс и сдвиг: {x, z} в блоках мира. */
    public static List<double[]> place(List<Slot> slots, double cx, double cz, double yawDeg) {
        double rad = Math.toRadians(yawDeg), fx = -Math.sin(rad), fz = Math.cos(rad), rx = -fz, rz = fx;      // вперёд и вправо в мире
        List<double[]> out = new ArrayList<>();
        for (Slot s : slots) out.add(new double[]{cx + rx * s.x() + fx * s.z(), cz + rz * s.x() + fz * s.z()});
        return out;
    }

    /** Курс, чтобы смотреть из (fx,fz) на (tx,tz). */
    public static double yawTo(double fx, double fz, double tx, double tz) { return Math.toDegrees(Math.atan2(-(tx - fx), tz - fz)); }

    /**
     * Раздаёт места солдатам: ближние к фронту (по роли) идут вперёд: ближний бой впереди, стрелки и поддержка позади; внутри роли - по боковому положению,
     * чтобы строй не перекрещивался. roles[i] - роль i-го солдата; возвращает индекс места для каждого солдата.
     */
    public static int[] assign(List<String> roles, List<Slot> slots, List<double[]> currentLocal) {
        int n = roles.size();
        Integer[] unitOrder = new Integer[n], slotOrder = new Integer[n];
        for (int i = 0; i < n; i++) { unitOrder[i] = i; slotOrder[i] = i; }
        Arrays.sort(unitOrder, Comparator.<Integer>comparingInt(i -> rank(roles.get(i))).thenComparingDouble(i -> currentLocal == null ? 0 : currentLocal.get(i)[0]));
        Arrays.sort(slotOrder, Comparator.<Integer>comparingDouble(i -> -slots.get(i).z()).thenComparingDouble(i -> slots.get(i).x()));
        int[] res = new int[n];
        for (int k = 0; k < n; k++) res[unitOrder[k]] = slotOrder[k];
        return res;
    }

    private static int rank(String role) { return switch (role) { case "melee" -> 0; case "cavalry" -> 1; case "ranged" -> 2; default -> 3; }; }
}
