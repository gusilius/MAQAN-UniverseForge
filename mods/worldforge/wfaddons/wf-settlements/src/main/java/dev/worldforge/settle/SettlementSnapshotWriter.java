package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** Streams a settlement snapshot one colony at a time to avoid world-sized JSON copies. */
public final class SettlementSnapshotWriter {
    private SettlementSnapshotWriter() {}

    public static void write(Writer writer,
                             Iterator<? extends Map<String, Object>> colonies,
                             String tradeJson) throws IOException {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(colonies, "colonies");
        writer.write("{\"format\":");
        writer.write(Json.write("wf-settle-world/1"));
        writer.write(",\"colonies\":[");
        boolean first = true;
        while (colonies.hasNext()) {
            Map<String, Object> colony = Objects.requireNonNull(colonies.next(), "colony snapshot");
            if (!first) writer.write(',');
            writer.write(Json.write(colony));
            first = false;
        }
        writer.write("],\"trade\":");
        writer.write(Json.write(tradeJson == null ? "" : tradeJson));
        writer.write('}');
    }
}
