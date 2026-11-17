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

    /**
     * Reusable primitive workspace for server-side formation updates.
     * Capacity grows only when a larger squad is first encountered; normal
     * command ticks reuse the same arrays and allocate no per-soldier objects.
     */
    public static final class Workspace {
        public double[] slotX = new double[0], slotZ = new double[0];
        public double[] worldX = new double[0], worldZ = new double[0], localX = new double[0];
        public int[] roleRank = new int[0], unitOrder = new int[0], slotOrder = new int[0], assignment = new int[0];
        private final Random looseRandom = new Random(0);

        public void ensureCapacity(int required) {
            if (required <= slotX.length) return;
            int capacity = Math.max(16, slotX.length);
            while (capacity < required) capacity = Math.max(required, capacity + (capacity >> 1));
            slotX = Arrays.copyOf(slotX, capacity);
            slotZ = Arrays.copyOf(slotZ, capacity);
            worldX = Arrays.copyOf(worldX, capacity);
            worldZ = Arrays.copyOf(worldZ, capacity);
            localX = Arrays.copyOf(localX, capacity);
            roleRank = Arrays.copyOf(roleRank, capacity);
            unitOrder = Arrays.copyOf(unitOrder, capacity);
            slotOrder = Arrays.copyOf(slotOrder, capacity);
            assignment = Arrays.copyOf(assignment, capacity);
        }
    }

    /** Fills local slot coordinates into a reusable workspace. */
    public static void slotsInto(Type type, int n, double spacing, Workspace w) {
        if (n < 0) throw new IllegalArgumentException("negative formation size");
        w.ensureCapacity(n);
        if (n == 0) return;
        switch (type) {
            case LINE -> rowsInto(w, n, Math.min(n, 12), spacing);
            case BLOCK -> rowsInto(w, n, Math.max(1, (int) Math.ceil(Math.sqrt(n * 1.4))), spacing);
            case COLUMN -> rowsInto(w, n, Math.min(n, 2), spacing);
            case WEDGE -> {
                int row = 0, placed = 0;
                double sumZ = 0;
                while (placed < n) {
                    int inRow = row + 1;
                    for (int i = 0; i < inRow && placed < n; i++, placed++) {
                        w.slotX[placed] = (i - (inRow - 1) / 2.0) * spacing;
                        w.slotZ[placed] = -row * spacing;
                        sumZ += w.slotZ[placed];
                    }
                    row++;
                }
                double meanZ = sumZ / n;
                for (int i = 0; i < n; i++) w.slotZ[i] -= meanZ;
            }
            case CIRCLE -> {
                int ring = 0, placed = 0;
                while (placed < n) {
                    int cap = ring == 0 ? 1 : ring * 6;
                    for (int i = 0; i < cap && placed < n; i++, placed++) {
                        double angle = 2 * Math.PI * i / cap, radius = ring * spacing;
                        w.slotX[placed] = Math.sin(angle) * radius;
                        w.slotZ[placed] = Math.cos(angle) * radius;
                    }
                    ring++;
                }
            }
            case LOOSE -> {
                w.looseRandom.setSeed(n * 31L + 7);
                double side = Math.max(2, Math.sqrt(n)) * spacing * 1.6;
                for (int i = 0; i < n; i++) {
                    w.slotX[i] = (w.looseRandom.nextDouble() - 0.5) * side;
                    w.slotZ[i] = (w.looseRandom.nextDouble() - 0.5) * side;
                }
            }
        }
    }

    private static void rowsInto(Workspace w, int n, int perRow, double spacing) {
        int rowCount = (n + perRow - 1) / perRow;
        for (int i = 0; i < n; i++) {
            int row = i / perRow, column = i % perRow, inRow = Math.min(perRow, n - row * perRow);
            w.slotX[i] = (column - (inRow - 1) / 2.0) * spacing;
            w.slotZ[i] = ((rowCount - 1) / 2.0 - row) * spacing;
        }
    }

    /** Transforms the first n local slots into world coordinates in-place. */
    public static void placeInto(Workspace w, int n, double cx, double cz, double yawDeg) {
        double rad = Math.toRadians(yawDeg), fx = -Math.sin(rad), fz = Math.cos(rad), rx = -fz, rz = fx;
        for (int i = 0; i < n; i++) {
            double sx = w.slotX[i], sz = w.slotZ[i];
            w.worldX[i] = cx + rx * sx + fx * sz;
            w.worldZ[i] = cz + rz * sx + fz * sz;
        }
    }

    /**
     * Role-aware deterministic assignment using primitive index arrays.
     * roleRank uses the same ordering as rank(): melee, cavalry, ranged, support.
     * Ties are resolved by original index to preserve deterministic ordering.
     */
    public static void assignInto(Workspace w, int n) {
        w.ensureCapacity(n);
        for (int i = 0; i < n; i++) { w.unitOrder[i] = i; w.slotOrder[i] = i; }
        sortUnits(w, 0, n - 1);
        sortSlots(w, 0, n - 1);
        for (int i = 0; i < n; i++) w.assignment[w.unitOrder[i]] = w.slotOrder[i];
    }

    private static int compareUnits(Workspace w, int a, int b) {
        int c = Integer.compare(w.roleRank[a], w.roleRank[b]);
        if (c == 0) c = Double.compare(w.localX[a], w.localX[b]);
        return c == 0 ? Integer.compare(a, b) : c;
    }

    private static int compareSlots(Workspace w, int a, int b) {
        int c = Double.compare(w.slotZ[b], w.slotZ[a]);
        if (c == 0) c = Double.compare(w.slotX[a], w.slotX[b]);
        return c == 0 ? Integer.compare(a, b) : c;
    }

    private static void sortUnits(Workspace w, int low, int high) {
        int i = low, j = high;
        if (low >= high) return;
        int pivot = w.unitOrder[(low + high) >>> 1];
        while (i <= j) {
            while (compareUnits(w, w.unitOrder[i], pivot) < 0) i++;
            while (compareUnits(w, w.unitOrder[j], pivot) > 0) j--;
            if (i <= j) { int t = w.unitOrder[i]; w.unitOrder[i++] = w.unitOrder[j]; w.unitOrder[j--] = t; }
        }
        if (low < j) sortUnits(w, low, j);
        if (i < high) sortUnits(w, i, high);
    }

    private static void sortSlots(Workspace w, int low, int high) {
        int i = low, j = high;
        if (low >= high) return;
        int pivot = w.slotOrder[(low + high) >>> 1];
        while (i <= j) {
            while (compareSlots(w, w.slotOrder[i], pivot) < 0) i++;
            while (compareSlots(w, w.slotOrder[j], pivot) > 0) j--;
            if (i <= j) { int t = w.slotOrder[i]; w.slotOrder[i++] = w.slotOrder[j]; w.slotOrder[j--] = t; }
        }
        if (low < j) sortSlots(w, low, j);
        if (i < high) sortSlots(w, i, high);
    }

    private static int rank(String role) { return switch (role) { case "melee" -> 0; case "cavalry" -> 1; case "ranged" -> 2; default -> 3; }; }
}
