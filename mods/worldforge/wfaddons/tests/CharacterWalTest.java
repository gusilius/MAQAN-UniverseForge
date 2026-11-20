package dev.worldforge.tests;

import dev.worldforge.people.storage.CharacterWal;
import dev.worldforge.people.storage.CharacterWalWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Crash-tail, checksum, batch ordering and writer-drain tests for the canonical Character WAL. */
public final class CharacterWalTest {
    private static int failures;
    private static void ok(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }
    private static Path shard(Path directory, int index) {
        return directory.resolve(String.format(java.util.Locale.ROOT, "characters-%04d.wal", index));
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wf-character-wal-test-");
        try {
            Path recovery = root.resolve("recovery");
            String id = "00000000-0000-0000-0000-000000000001";
            int shardId;
            try (CharacterWal wal = new CharacterWal(recovery, 16)) {
                shardId = wal.shardFor(id);
                wal.append(id, "{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                wal.append(id, "{\"version\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Files.write(shard(recovery, shardId), new byte[]{0x57, 0x46, 0x50}, StandardOpenOption.APPEND);
            try (CharacterWal recovered = new CharacterWal(recovery, 16)) {
                List<CharacterWal.Record> records = recovered.replayAll();
                ok(records.size() == 2, "recovery keeps complete records and discards only an incomplete trailing frame");
                CharacterWal.Record next = recovered.append(id, "{\"version\":3}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ok(next.sequence() == 3 && recovered.replayAll().size() == 3, "repaired WAL resumes sequence without losing records");
                recovered.checkpoint();
                ok(recovered.replayAll().isEmpty(), "checkpoint retires records after durable snapshot");
                ok(recovered.append(id, new byte[]{1}).sequence() == 1, "checkpoint safely restarts shard sequence");
            }

            Path batchDir = root.resolve("batch");
            try (CharacterWal wal = new CharacterWal(batchDir, 16)) {
                List<CharacterWal.Write> writes = new ArrayList<>();
                for (int i = 0; i < 300; i++) writes.add(new CharacterWal.Write(id, ("record-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                List<CharacterWal.Record> receipts = wal.appendBatch(writes);
                List<CharacterWal.Record> replayed = wal.replayAll();
                ok(receipts.size() == 300 && replayed.size() == 300
                                && receipts.get(0).sequence() == 1 && receipts.get(299).sequence() == 300
                                && new String(replayed.get(299).payload(), java.nio.charset.StandardCharsets.UTF_8).equals("record-299"),
                        "batch append preserves per-shard order and every durable payload");
            }

            Path corrupt = root.resolve("corrupt");
            try (CharacterWal wal = new CharacterWal(corrupt, 4)) {
                shardId = wal.shardFor(id);
                wal.append(id, new byte[]{10, 20, 30, 40});
            }
            Path corruptShard = shard(corrupt, shardId);
            byte[] bytes = Files.readAllBytes(corruptShard);
            bytes[24 + id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length] ^= 0x01;
            Files.write(corruptShard, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            boolean checksumRejected = false;
            try { new CharacterWal(corrupt, 4).close(); }
            catch (IOException expected) { checksumRejected = expected.getMessage().contains("checksum"); }
            ok(checksumRejected, "bad checksum fails closed instead of silently dropping identity data");

            Path writerDirectory = root.resolve("writer");
            CharacterWal wal = new CharacterWal(writerDirectory, 8);
            CharacterWalWriter writer = new CharacterWalWriter(wal, 512, "wf-character-wal-test");
            List<CompletableFuture<CharacterWal.Record>> futures = new ArrayList<>();
            int acceptedCount = 0;
            for (int i = 0; i < 300; i++) {
                CompletableFuture<CharacterWal.Record> future = new CompletableFuture<>();
                if (writer.submit(id, ("writer-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8), future)) acceptedCount++;
                futures.add(future);
            }
            for (CompletableFuture<CharacterWal.Record> future : futures) future.get(10, TimeUnit.SECONDS);
            writer.close();
            wal.close();
            try (CharacterWal replay = new CharacterWal(writerDirectory, 8)) {
                ok(acceptedCount == 300 && replay.replayAll().size() == 300
                                && futures.get(299).get().sequence() == 300,
                        "bounded writer batches records, completes durable receipts and drains on shutdown");
            }
        } finally {
            try (var files = Files.walk(root)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { }
                });
            }
        }
        System.out.println("CharacterWalTest failures=" + failures);
        if (failures > 0) throw new AssertionError("Character WAL tests failed: " + failures);
    }
}
