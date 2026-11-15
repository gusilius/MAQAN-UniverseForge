package dev.worldforge.settle;

import dev.worldforge.mod.Json;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional Rust provider for immutable settlement-market ranking snapshots. */
final class TradeCompute {
    private static volatile Method method;
    private static volatile boolean resolved, unavailable;
    private TradeCompute() {}

    static List<String[]> suggestions(List<CityRow> cities, int limit) {
        if (cities == null || limit <= 0) return List.of();
        List<Object> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (CityRow city : cities) {
            if (city == null || city.id == null || city.id.isBlank() || !ids.add(city.id)) continue;
            rows.add(encode(city.id, city.tradeRate, city.prices, city.stock, city.capacity, city.consumption));
        }
        return invoke(rows, limit);
    }

    static List<String[]> suggestions(Map<String, Settlement> cities, int limit) {
        if (cities == null || limit <= 0) return List.of();
        List<Object> rows = new ArrayList<>();
        for (Settlement city : cities.values()) {
            if (city == null) continue;
            Map<String, Double> prices = new LinkedHashMap<>(), stock = new LinkedHashMap<>(),
                    capacity = new LinkedHashMap<>(), consumption = new LinkedHashMap<>();
            for (String resource : city.defs.prices.keySet()) {
                if (Defs.SILVER.equals(resource)) continue;
                prices.put(resource, city.price(resource));
                stock.put(resource, city.amount(resource));
                capacity.put(resource, city.capacity(resource));
                consumption.put(resource, city.consumedPerMin(resource));
            }
            rows.add(encode(city.id, city.tradePerMin(), prices, stock, capacity, consumption));
        }
        return invoke(rows, limit);
    }

    private static Map<String,Object> encode(String id, double tradeRate, Map<String,Double> prices,
                                              Map<String,Double> stock, Map<String,Double> capacity,
                                              Map<String,Double> consumption) {
        List<Object> resources = new ArrayList<>();
        for (Map.Entry<String,Double> entry : prices.entrySet()) {
            String key = entry.getKey();
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", key); row.put("price", entry.getValue());
            row.put("stock", stock.getOrDefault(key, 0.0));
            row.put("capacity", capacity.getOrDefault(key, 0.0));
            row.put("consumption", consumption.getOrDefault(key, 0.0));
            resources.add(row);
        }
        Map<String,Object> city = new LinkedHashMap<>();
        city.put("id", id); city.put("trade_rate", tradeRate); city.put("resources", resources);
        return city;
    }

    private static List<String[]> invoke(List<Object> cities, int limit) {
        Method provider = provider();
        if (provider == null) return null;
        try {
            Map<String,Object> request = new LinkedHashMap<>();
            request.put("schema_version", 1); request.put("operation", "suggestions");
            request.put("limit", limit); request.put("cities", cities);
            byte[] output = (byte[]) provider.invoke(null, (Object) Json.write(request).getBytes(StandardCharsets.UTF_8));
            if (output == null || output.length == 0) throw new IllegalStateException("native trade compute returned an empty frame");
            Object parsed = Json.parse(new String(output, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> root) || !"ok".equals(root.get("status"))
                    || !(root.get("suggestions") instanceof List<?> rows))
                throw new IllegalStateException("native trade compute rejected market snapshot: " + parsed);
            if (rows.size() > limit) throw new IllegalStateException("native trade compute exceeded requested result limit");
            List<String[]> result = new ArrayList<>(rows.size());
            Set<String> unique = new HashSet<>();
            for (Object item : rows) {
                if (!(item instanceof Map<?,?> row) || !(row.get("from") instanceof String from)
                        || !(row.get("to") instanceof String to) || !(row.get("resource") instanceof String resource)
                        || !(row.get("score") instanceof Number score) || !Double.isFinite(score.doubleValue())
                        || from.equals(to) || from.isBlank() || to.isBlank() || resource.isBlank()
                        || !unique.add(from + "\u0000" + to + "\u0000" + resource))
                    throw new IllegalStateException("native trade compute returned malformed or duplicate candidate");
                result.add(new String[]{from, to, resource});
            }
            return List.copyOf(result);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("native trade compute failed after ownership", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("native trade compute contract mismatch", error);
        }
    }

    private static Method provider() {
        if (resolved) {
            if (unavailable) return null;
            if (method == null) throw new IllegalStateException("native trade provider resolution failed");
            return method;
        }
        synchronized (TradeCompute.class) {
            if (resolved) return provider();
            try {
                Class<?> type = Class.forName("dev.worldforge.worldsim.mc.NativeWorldSimulation", false,
                        TradeCompute.class.getClassLoader());
                if (!Boolean.TRUE.equals(type.getMethod("load").invoke(null))) unavailable = true;
                else method = type.getMethod("advanceTradeCompute", byte[].class);
            } catch (ClassNotFoundException | NoClassDefFoundError absent) {
                unavailable = true;
            } catch (InvocationTargetException error) {
                throw new IllegalStateException("native trade provider failed during load", error.getCause());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("native trade provider contract mismatch", error);
            }
            resolved = true;
            return method;
        }
    }
}
