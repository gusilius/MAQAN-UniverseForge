package dev.worldforge.settle;

import java.util.*;

/**
 * Скользящая статистика потоков ресурсов за последние {@link #WINDOW} секунд: сколько произведено и сколько потрачено.
 * Данные лежат в секундных корзинах, поэтому «в минуту» считается без накопления ошибки и сохраняется в JSON.
 */
public final class Flow {
    public static final int WINDOW = 60;

    private final TreeMap<Long, TreeMap<String, double[]>> buckets = new TreeMap<>();   // секунда -> ресурс -> {произведено, потрачено}

    public void record(double now, String res, double amount, boolean produced) {
        if (!validTime(now) || res == null || res.isBlank() || !Double.isFinite(amount) || amount <= 0) return;
        long idx = (long) Math.floor(now);
        buckets.computeIfAbsent(idx, k -> new TreeMap<>()).computeIfAbsent(res, k -> new double[2])[produced ? 0 : 1] += amount;
        while (!buckets.isEmpty() && buckets.firstKey() <= idx - WINDOW) buckets.pollFirstEntry();
    }

    /** Среднее в минуту за окно. produced=true - выработка, false - расход. */
    public double perMinute(double now, String res, boolean produced) {
        if (!validTime(now) || res == null || res.isBlank()) return 0;
        long idx = (long) Math.floor(now);
        double sum = 0;
        for (Map.Entry<Long, TreeMap<String, double[]>> e : buckets.tailMap(idx - WINDOW, false).entrySet()) {
            double[] v = e.getValue().get(res);
            if (v != null) sum += v[produced ? 0 : 1];
        }
        double span = now >= WINDOW ? WINDOW - 1 + (now - idx) : now;
        return sum / Math.max(1, span) * 60;
    }

    private static boolean validTime(double now) {
        return Double.isFinite(now) && now >= 0 && now < Long.MAX_VALUE - WINDOW;
    }

    public Set<String> resources() {
        Set<String> r = new TreeSet<>();
        for (TreeMap<String, double[]> m : buckets.values()) r.addAll(m.keySet());
        return r;
    }

    public List<Object> save() {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<Long, TreeMap<String, double[]>> e : buckets.entrySet()) {
            for (Map.Entry<String, double[]> r : e.getValue().entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("t", e.getKey()); m.put("r", r.getKey()); m.put("p", r.getValue()[0]); m.put("c", r.getValue()[1]);
                out.add(m);
            }
        }
        return out;
    }

    public void load(Object o) {
        buckets.clear();
        if (!(o instanceof List<?> list)) return;
        for (Object x : list) {
            if (!(x instanceof Map<?, ?> m)) continue;
            Object time = m.get("t"), resource = m.get("r"), produced = m.get("p"), consumed = m.get("c");
            if (!(time instanceof Number tn) || !(resource instanceof String res) || res.isBlank()
                    || !(produced instanceof Number pn) || !(consumed instanceof Number cn)) continue;
            double t = tn.doubleValue(), p = pn.doubleValue(), c = cn.doubleValue();
            if (!validTime(t) || !Double.isFinite(p) || !Double.isFinite(c) || p < 0 || c < 0) continue;
            long idx = (long) Math.floor(t);
            double[] v = buckets.computeIfAbsent(idx, k -> new TreeMap<>()).computeIfAbsent(res, k -> new double[2]);
            v[0] += p;
            v[1] += c;
        }
    }
}
