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
        if (amount <= 0) return;
        long idx = (long) Math.floor(now);
        buckets.computeIfAbsent(idx, k -> new TreeMap<>()).computeIfAbsent(res, k -> new double[2])[produced ? 0 : 1] += amount;
        while (!buckets.isEmpty() && buckets.firstKey() <= idx - WINDOW) buckets.pollFirstEntry();
    }

    /** Среднее в минуту за окно. produced=true - выработка, false - расход. */
    public double perMinute(double now, String res, boolean produced) {
        long idx = (long) Math.floor(now);
        double sum = 0;
        for (Map.Entry<Long, TreeMap<String, double[]>> e : buckets.tailMap(idx - WINDOW, false).entrySet()) {
            double[] v = e.getValue().get(res);
            if (v != null) sum += v[produced ? 0 : 1];
        }
        double span = now >= WINDOW ? WINDOW - 1 + (now - idx) : now;
        return sum / Math.max(1, span) * 60;
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

    @SuppressWarnings("unchecked")
    public void load(Object o) {
        buckets.clear();
        if (!(o instanceof List)) return;
        for (Object x : (List<Object>) o) {
            Map<String, Object> m = (Map<String, Object>) x;
            double[] v = buckets.computeIfAbsent(((Number) m.get("t")).longValue(), k -> new TreeMap<>()).computeIfAbsent((String) m.get("r"), k -> new double[2]);
            v[0] = ((Number) m.get("p")).doubleValue(); v[1] = ((Number) m.get("c")).doubleValue();
        }
    }
}
