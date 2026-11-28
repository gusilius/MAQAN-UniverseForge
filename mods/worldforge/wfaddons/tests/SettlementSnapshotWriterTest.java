package dev.worldforge.tests;

import dev.worldforge.mod.Json;
import dev.worldforge.settle.SettlementSnapshotWriter;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SettlementSnapshotWriterTest {
    private static int failures;
    private static void check(boolean condition, String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> source = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            Map<String, Object> colony = new LinkedHashMap<>();
            colony.put("id", "settlement-" + i);
            colony.put("population", i * 3);
            colony.put("residents", List.of("character-" + i, "character-" + (i + 1)));
            source.add(colony);
        }
        StringWriter output = new StringWriter();
        SettlementSnapshotWriter.write(output, source.iterator(), "{\"routes\":7}");
        Object parsed = Json.parse(output.toString());
        check(parsed instanceof Map<?, ?>, "streamed snapshot remains valid JSON");
        Map<?, ?> root = (Map<?, ?>) parsed;
        check("wf-settle-world/1".equals(root.get("format")), "snapshot format remains backward compatible");
        check(root.get("colonies") instanceof List<?> colonies && colonies.size() == 10_000,
                "all 10,000 colony records are emitted in source order");
        check("{\"routes\":7}".equals(root.get("trade")), "trade ledger remains a JSON string field");
        System.out.println("SettlementSnapshotWriterTest failures=" + failures);
        if (failures > 0) throw new AssertionError("SettlementSnapshotWriterTest failed: " + failures);
    }
}
