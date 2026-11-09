package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Durable execution ledger for resident work: extraction -> haul -> storage -> processing/construction.
 * Domain effects are committed by Settlement only after stock or work services accept them.
 */
public final class ResidentTaskGraph {
    public enum Stage { EXTRACTED, HAULED, STORED, PROCESSED, PRODUCED, CONSTRUCTION, BUILT }
    public record Receipt(long id, Stage stage, int buildingId, int workerId, String resource,
                          double quantity, long parentReceiptId, double worldTime) { }
    private record Lot(long receiptId, double quantity) { }

    private static final int MAX_RECEIPTS = 5000;
    private long nextId = 1;
    private final List<Receipt> receipts = new ArrayList<>();
    private final Map<String, Deque<Lot>> localLots = new TreeMap<>();
    private final Map<String, Deque<Lot>> storedLots = new TreeMap<>();

    public List<Receipt> receipts() { return Collections.unmodifiableList(receipts); }
    public long nextId() { return nextId; }

    public Receipt extract(int buildingId, int workerId, String resource, double quantity, double time) {
        validate(resource, quantity, time);
        Receipt receipt = record(Stage.EXTRACTED, buildingId, workerId, resource, quantity, 0, time);
        localLots.computeIfAbsent(localKey(buildingId, resource), ignored -> new ArrayDeque<>())
                .addLast(new Lot(receipt.id(), quantity));
        return receipt;
    }

    /** Records a processed output lot and links it to the input-processing receipt. */
    public Receipt produce(int buildingId, int workerId, String resource, double quantity, double time) {
        validate(resource, quantity, time);
        long parent = 0;
        for (int i = receipts.size() - 1; i >= 0; i--) {
            Receipt receipt = receipts.get(i);
            if (receipt.stage() == Stage.PROCESSED && receipt.buildingId() == buildingId) {
                parent = receipt.id();
                break;
            }
        }
        Receipt receipt = record(Stage.PRODUCED, buildingId, workerId, resource, quantity, parent, time);
        localLots.computeIfAbsent(localKey(buildingId, resource), ignored -> new ArrayDeque<>())
                .addLast(new Lot(receipt.id(), quantity));
        return receipt;
    }

    /** Delivers only goods already present in the source building's local output buffer. */
    public double deliver(int buildingId, int workerId, String resource, double quantity, double time) {
        validate(resource, quantity, time);
        Deque<Lot> source = localLots.computeIfAbsent(localKey(buildingId, resource), ignored -> new ArrayDeque<>());
        double remaining = quantity, delivered = 0;
        while (remaining > 1e-9 && !source.isEmpty()) {
            Lot lot = source.removeFirst();
            double part = Math.min(remaining, lot.quantity());
            Receipt hauled = record(Stage.HAULED, buildingId, workerId, resource, part, lot.receiptId(), time);
            Receipt stored = record(Stage.STORED, buildingId, workerId, resource, part, hauled.id(), time);
            storedLots.computeIfAbsent(resource, ignored -> new ArrayDeque<>()).addLast(new Lot(stored.id(), part));
            remaining -= part;
            delivered += part;
            double tail = lot.quantity() - part;
            if (tail > 1e-9) {
                source.addFirst(new Lot(lot.receiptId(), tail));
                break;
            }
        }
        // Migration-safe fallback: local buffers created before this ledger existed are still deliverable.
        if (remaining > 1e-9) {
            Receipt hauled = record(Stage.HAULED, buildingId, workerId, resource, remaining, 0, time);
            Receipt stored = record(Stage.STORED, buildingId, workerId, resource, remaining, hauled.id(), time);
            storedLots.computeIfAbsent(resource, ignored -> new ArrayDeque<>()).addLast(new Lot(stored.id(), remaining));
            delivered += remaining;
        }
        return delivered;
    }

    /** Records resource consumption by a real production or construction commit. */
    public double consume(Stage stage, int buildingId, int workerId, String resource, double quantity, double time) {
        if (stage != Stage.PROCESSED && stage != Stage.CONSTRUCTION)
            throw new IllegalArgumentException("Consumption stage must be PROCESSED or CONSTRUCTION");
        validate(resource, quantity, time);
        Deque<Lot> available = storedLots.computeIfAbsent(resource, ignored -> new ArrayDeque<>());
        double remaining = quantity, consumed = 0;
        while (remaining > 1e-9 && !available.isEmpty()) {
            Lot lot = available.removeFirst();
            double part = Math.min(remaining, lot.quantity());
            record(stage, buildingId, workerId, resource, part, lot.receiptId(), time);
            remaining -= part;
            consumed += part;
            double tail = lot.quantity() - part;
            if (tail > 1e-9) {
                available.addFirst(new Lot(lot.receiptId(), tail));
                break;
            }
        }
        if (remaining > 1e-9) {
            record(stage, buildingId, workerId, resource, remaining, 0, time);
            consumed += remaining;
        }
        return consumed;
    }

    /** Records the completed construction after all required material and work commits succeeded. */
    public Receipt built(int buildingId, int workerId, double time) {
        long parent = 0;
        for (int i = receipts.size() - 1; i >= 0; i--) {
            Receipt receipt = receipts.get(i);
            if (receipt.stage() == Stage.CONSTRUCTION && receipt.buildingId() == buildingId) {
                parent = receipt.id();
                break;
            }
        }
        return record(Stage.BUILT, buildingId, workerId, "building:" + buildingId, 1, parent, time);
    }

    private Receipt record(Stage stage, int buildingId, int workerId, String resource,
                           double quantity, long parent, double time) {
        Receipt receipt = new Receipt(nextId++, stage, buildingId, workerId, resource, quantity, parent, time);
        receipts.add(receipt);
        if (receipts.size() > MAX_RECEIPTS) receipts.subList(0, receipts.size() - MAX_RECEIPTS).clear();
        return receipt;
    }

    public long count(Stage stage) { return receipts.stream().filter(r -> r.stage() == stage).count(); }

    public String toJson() {
        List<Object> savedReceipts = new ArrayList<>();
        for (Receipt r : receipts) savedReceipts.add(Map.of("id", r.id(), "stage", r.stage().name(),
                "building", r.buildingId(), "worker", r.workerId(), "resource", r.resource(),
                "quantity", r.quantity(), "parent", r.parentReceiptId(), "time", r.worldTime()));
        return Json.write(Map.of("format", "wf-resident-task-graph/1", "next", nextId,
                "receipts", savedReceipts, "local", saveLots(localLots), "stored", saveLots(storedLots)));
    }

    private static Map<String,Object> saveLots(Map<String,Deque<Lot>> source) {
        Map<String,Object> out = new TreeMap<>();
        for (Map.Entry<String,Deque<Lot>> entry : source.entrySet()) {
            List<Object> lots = new ArrayList<>();
            for (Lot lot : entry.getValue()) lots.add(Map.of("receipt", lot.receiptId(), "quantity", lot.quantity()));
            out.put(entry.getKey(), lots);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?,?> root) || !"wf-resident-task-graph/1".equals(root.get("format")))
            throw new IllegalArgumentException("Invalid resident task graph save");
        receipts.clear(); localLots.clear(); storedLots.clear(); nextId = 1;
        if (root.get("receipts") instanceof List<?> list) for (Object item : list) {
            if (!(item instanceof Map<?,?> m) || !(m.get("stage") instanceof String stage)
                    || !(m.get("resource") instanceof String resource)) continue;
            try {
                long id = number(m.get("id"), 0).longValue();
                Stage parsedStage = Stage.valueOf(stage);
                double quantity = number(m.get("quantity"), 0).doubleValue();
                double time = number(m.get("time"), 0).doubleValue();
                if (id <= 0 || quantity <= 0 || !Double.isFinite(quantity) || !Double.isFinite(time) || resource.isBlank()) continue;
                receipts.add(new Receipt(id, parsedStage, number(m.get("building"), 0).intValue(),
                        number(m.get("worker"), 0).intValue(), resource, quantity,
                        number(m.get("parent"), 0).longValue(), time));
                nextId = Math.max(nextId, id + 1);
            } catch (RuntimeException ignored) { }
        }
        if (root.get("next") instanceof Number n && n.longValue() >= nextId) nextId = n.longValue();
        loadLots(root.get("local"), localLots);
        loadLots(root.get("stored"), storedLots);
        receipts.sort(Comparator.comparingLong(Receipt::id));
        if (receipts.size() > MAX_RECEIPTS) receipts.subList(0, receipts.size() - MAX_RECEIPTS).clear();
    }

    private static void loadLots(Object raw, Map<String,Deque<Lot>> target) {
        if (!(raw instanceof Map<?,?> map)) return;
        for (Map.Entry<?,?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof List<?> lots)) continue;
            Deque<Lot> queue = new ArrayDeque<>();
            for (Object item : lots) {
                if (!(item instanceof Map<?,?> lot)) continue;
                double quantity = number(lot.get("quantity"), 0).doubleValue();
                long receipt = number(lot.get("receipt"), 0).longValue();
                if (receipt > 0 && quantity > 0 && Double.isFinite(quantity)) queue.addLast(new Lot(receipt, quantity));
            }
            // Preserve empty FIFO buckets as well: their presence is part of the canonical
            // serialized graph, and removing them on load breaks byte-stable save/reload.
            target.put(key, queue);
        }
    }

    private static Number number(Object value, Number fallback) { return value instanceof Number n ? n : fallback; }
    private static String localKey(int buildingId, String resource) { return buildingId + "|" + resource; }
    private static void validate(String resource, double quantity, double time) {
        if (resource == null || resource.isBlank() || !Double.isFinite(quantity) || quantity <= 0
                || !Double.isFinite(time) || time < 0) throw new IllegalArgumentException("Invalid task graph receipt");
    }
}
