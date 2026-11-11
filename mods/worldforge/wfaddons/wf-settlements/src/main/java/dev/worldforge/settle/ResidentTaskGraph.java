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
        ensureCapacity(1);
        Receipt receipt = record(Stage.EXTRACTED, buildingId, workerId, resource, quantity, 0, time);
        localLots.computeIfAbsent(localKey(buildingId, resource), ignored -> new ArrayDeque<>())
                .addLast(new Lot(receipt.id(), quantity));
        compactReceipts();
        return receipt;
    }

    /** Records a processed output lot and links it to the input-processing receipt. */
    public Receipt produce(int buildingId, int workerId, String resource, double quantity, double time) {
        validate(resource, quantity, time);
        ensureCapacity(1);
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
        compactReceipts();
        return receipt;
    }

    /** Delivers only goods already present in the source building's local output buffer. */
    public double deliver(int buildingId, int workerId, String resource, double quantity, double time) {
        validate(resource, quantity, time);
        Deque<Lot> source = localLots.get(localKey(buildingId, resource));
        if (source == null || source.isEmpty()) return 0;
        ensureCapacity(chunksNeeded(source, quantity) * 2);
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
        // Never invent provenance for a quantity absent from the source buffer.
        // Legacy stock must be explicitly imported by a settlement migration adapter.
        compactReceipts();
        return delivered;
    }

    /** Records resource consumption by a real production or construction commit. */
    public double consume(Stage stage, int buildingId, int workerId, String resource, double quantity, double time) {
        if (stage != Stage.PROCESSED && stage != Stage.CONSTRUCTION)
            throw new IllegalArgumentException("Consumption stage must be PROCESSED or CONSTRUCTION");
        validate(resource, quantity, time);
        Deque<Lot> available = storedLots.get(resource);
        if (available == null || available.isEmpty()) return 0;
        ensureCapacity(chunksNeeded(available, quantity));
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
        // Missing stock has no provenance receipt and cannot be reported as consumed.
        compactReceipts();
        return consumed;
    }

    /** Records the completed construction after all required material and work commits succeeded. */
    public Receipt built(int buildingId, int workerId, double time) {
        ensureCapacity(1);
        long parent = 0;
        for (int i = receipts.size() - 1; i >= 0; i--) {
            Receipt receipt = receipts.get(i);
            if (receipt.stage() == Stage.CONSTRUCTION && receipt.buildingId() == buildingId) {
                parent = receipt.id();
                break;
            }
        }
        Receipt receipt = record(Stage.BUILT, buildingId, workerId, "building:" + buildingId, 1, parent, time);
        compactReceipts();
        return receipt;
    }

    private Receipt record(Stage stage, int buildingId, int workerId, String resource,
                           double quantity, long parent, double time) {
        if (receipts.size() >= MAX_RECEIPTS)
            throw new IllegalStateException("Resident task provenance journal is full; mutation was not recorded");
        Receipt receipt = new Receipt(nextId++, stage, buildingId, workerId, resource, quantity, parent, time);
        receipts.add(receipt);
        return receipt;
    }

    /** Preflight the complete write set before any inventory lot is moved or consumed. */
    private void ensureCapacity(int additional) {
        if (additional < 0) throw new IllegalArgumentException("Invalid task receipt reservation");
        compactReceipts();
        if (additional > MAX_RECEIPTS - receipts.size())
            throw new IllegalStateException("Resident task provenance is still referenced by active stock; mutation is blocked safely");
    }

    private static int chunksNeeded(Deque<Lot> lots, double requested) {
        double remaining = requested;
        int chunks = 0;
        for (Lot lot : lots) {
            if (remaining <= 1e-9) break;
            chunks++;
            remaining -= Math.min(remaining, lot.quantity());
        }
        return chunks;
    }

    /**
     * Compact only history that is no longer needed to explain active inventory.
     * Active lots retain their complete parent chain. The latest PROCESSED and
     * CONSTRUCTION receipt per building remain as stable links for subsequent output/build receipts.
     */
    private void compactReceipts() {
        Map<Long, Receipt> byId = new HashMap<>();
        for (Receipt receipt : receipts) byId.put(receipt.id(), receipt);
        Set<Long> keep = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        for (Deque<Lot> lots : localLots.values())
            for (Lot lot : lots) pending.addLast(lot.receiptId());
        for (Deque<Lot> lots : storedLots.values())
            for (Lot lot : lots) pending.addLast(lot.receiptId());
        Map<Integer, Long> latestProcessed = new HashMap<>();
        Map<Integer, Long> latestConstruction = new HashMap<>();
        for (Receipt receipt : receipts) {
            if (receipt.stage() == Stage.PROCESSED) latestProcessed.put(receipt.buildingId(), receipt.id());
            if (receipt.stage() == Stage.CONSTRUCTION) latestConstruction.put(receipt.buildingId(), receipt.id());
        }
        keep.addAll(latestProcessed.values());
        keep.addAll(latestConstruction.values());
        pending.addAll(latestProcessed.values());
        pending.addAll(latestConstruction.values());
        while (!pending.isEmpty()) {
            long id = pending.removeFirst();
            if (!keep.add(id)) continue;
            Receipt receipt = byId.get(id);
            if (receipt != null && receipt.parentReceiptId() > 0) pending.addLast(receipt.parentReceiptId());
        }
        receipts.removeIf(receipt -> !keep.contains(receipt.id()));
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
        if (!(root.get("receipts") instanceof List<?> list) || list.size() > MAX_RECEIPTS)
            throw new IllegalArgumentException("Invalid resident task graph receipts");
        if (!(root.get("next") instanceof Number nextValue) || nextValue.longValue() < 1)
            throw new IllegalArgumentException("Invalid resident task graph sequence");
        if (!(root.get("local") instanceof Map<?,?> local) || !(root.get("stored") instanceof Map<?,?> stored))
            throw new IllegalArgumentException("Missing resident task graph inventory buckets");

        List<Receipt> loaded = new ArrayList<>();
        long previousId = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?,?> m) || !(m.get("stage") instanceof String stage)
                    || !(m.get("resource") instanceof String resource))
                throw new IllegalArgumentException("Malformed resident task receipt");
            try {
                long id = requiredNumber(m.get("id"), "id").longValue();
                Stage parsedStage = Stage.valueOf(stage);
                double quantity = requiredNumber(m.get("quantity"), "quantity").doubleValue();
                double time = requiredNumber(m.get("time"), "time").doubleValue();
                int building = requiredNumber(m.get("building"), "building").intValue();
                int worker = requiredNumber(m.get("worker"), "worker").intValue();
                long parent = requiredNumber(m.get("parent"), "parent").longValue();
                if (id <= previousId || id <= 0 || parent < 0 || parent >= id
                        || building < 0 || worker < 0 || resource.isBlank()
                        || !Double.isFinite(quantity) || quantity <= 0
                        || !Double.isFinite(time) || time < 0)
                    throw new IllegalArgumentException("Invalid resident task receipt " + id);
                loaded.add(new Receipt(id, parsedStage, building, worker, resource, quantity, parent, time));
                previousId = id;
            } catch (RuntimeException ex) {
                if (ex instanceof IllegalArgumentException && ex.getMessage() != null
                        && ex.getMessage().startsWith("Invalid resident task receipt")) throw ex;
                throw new IllegalArgumentException("Invalid resident task receipt", ex);
            }
        }
        long restoredNext = nextValue.longValue();
        if (restoredNext <= previousId)
            throw new IllegalArgumentException("Resident task sequence does not exceed the last receipt");

        Map<String, Deque<Lot>> loadedLocal = new TreeMap<>();
        Map<String, Deque<Lot>> loadedStored = new TreeMap<>();
        Set<Long> receiptIds = new HashSet<>();
        for (Receipt receipt : loaded) receiptIds.add(receipt.id());
        loadLots(local, loadedLocal, restoredNext, receiptIds);
        loadLots(stored, loadedStored, restoredNext, receiptIds);

        receipts.clear();
        receipts.addAll(loaded);
        localLots.clear();
        localLots.putAll(loadedLocal);
        storedLots.clear();
        storedLots.putAll(loadedStored);
        nextId = restoredNext;
    }

    private static Number requiredNumber(Object value, String field) {
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Missing numeric task field: " + field);
        return n;
    }

    private static void loadLots(Object raw, Map<String,Deque<Lot>> target, long nextId, Set<Long> receiptIds) {
        if (!(raw instanceof Map<?,?> map))
            throw new IllegalArgumentException("Invalid resident task inventory buckets");
        for (Map.Entry<?,?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()
                    || !(entry.getValue() instanceof List<?> lots))
                throw new IllegalArgumentException("Malformed resident task inventory bucket");
            Deque<Lot> queue = new ArrayDeque<>();
            for (Object item : lots) {
                if (!(item instanceof Map<?,?> lot))
                    throw new IllegalArgumentException("Malformed resident task inventory lot");
                double quantity = requiredNumber(lot.get("quantity"), "lot.quantity").doubleValue();
                long receipt = requiredNumber(lot.get("receipt"), "lot.receipt").longValue();
                if (receipt <= 0 || receipt >= nextId || !receiptIds.contains(receipt)
                        || quantity <= 0 || !Double.isFinite(quantity))
                    throw new IllegalArgumentException("Invalid resident task inventory lot: missing or invalid provenance receipt " + receipt);
                queue.addLast(new Lot(receipt, quantity));
            }
            // Empty FIFO buckets are significant for stable save/reload.
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
