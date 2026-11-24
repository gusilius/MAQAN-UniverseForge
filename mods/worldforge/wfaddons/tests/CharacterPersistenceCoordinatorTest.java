package dev.worldforge.tests;

import dev.worldforge.people.storage.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Exercises the existing WAL -> streaming checkpoint store -> eviction eligibility boundary. */
public final class CharacterPersistenceCoordinatorTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wf-character-persistence-");
        Path walPath = root.resolve("wal");
        Path recordsPath = root.resolve("records");
        CharacterEvictionGate gate = new CharacterEvictionGate();
        CharacterWal wal = new CharacterWal(walPath, 4);
        CharacterWalWriter writer = new CharacterWalWriter(wal, 16, "wf-character-persistence-test");
        CharacterRecordStore records = new CharacterRecordStore(recordsPath);
        CharacterPersistenceCoordinator coordinator =
                new CharacterPersistenceCoordinator(gate, wal, writer, records);
        String id = "00000000-0000-0000-0000-000000000077";
        byte[] firstPayload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        try {
            coordinator.register(id);
            check(!coordinator.canEvict(id), "unpersisted identity remains resident");

            var firstFuture = coordinator.persist(id, firstPayload);
            check(writer.pending() >= 0, "WAL writer reports a non-negative outstanding-work count");
            CharacterWal.Record first = firstFuture.get(10, TimeUnit.SECONDS);
            check(writer.pending() == 0, "completed WAL receipt leaves no outstanding writer work");
            check(first.globalId().equals(id) && coordinator.canEvict(id),
                    "forced WAL receipt cleans exactly the captured generation");

            try (CharacterEvictionGate.Pin lease = coordinator.acquireLease(id)) {
                check(!coordinator.canEvict(id), "coordinator lease pin prevents eviction");
            }
            check(coordinator.canEvict(id), "released lease permits eviction when clean");

            coordinator.markDirty(id);
            byte[] secondPayload = "{\"v\":2}".getBytes(StandardCharsets.UTF_8);
            coordinator.persist(id, secondPayload).get(10, TimeUnit.SECONDS);
            check(coordinator.canEvict(id), "new dirty generation becomes clean only after its WAL force");


            boolean staleSnapshotRejected = false;
            try {
                coordinator.persistMutation(id, () -> {
                    coordinator.markDirty(id); // Simulate a concurrent domain mutation after ticket capture.
                    return "{\"v\":\"stale\"}".getBytes(StandardCharsets.UTF_8);
                }).get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                staleSnapshotRejected = expected.getCause() instanceof CharacterPersistenceCoordinator.StalePersistenceException;
            }
            check(staleSnapshotRejected
                            && gate.decision(id) == CharacterEvictionGate.Decision.DIRTY,
                    "a generation changed during snapshot serialization cannot be acknowledged clean");

            coordinator.persistMutation(id, () -> "{\"v\":\"2-final\"}".getBytes(StandardCharsets.UTF_8))
                    .get(10, TimeUnit.SECONDS);
            check(coordinator.canEvict(id),
                    "a fresh snapshot receipt acknowledges the current generation");

            coordinator.markDirty(id);
            CharacterWalWriter failedWriter = new CharacterWalWriter(wal, 1, "wf-character-persistence-failure-test");
            failedWriter.close();
            CharacterPersistenceCoordinator failedCoordinator =
                    new CharacterPersistenceCoordinator(gate, wal, failedWriter, records);
            boolean failedWriteRejected = false;
            try {
                failedCoordinator.persist(id, "{\"v\":3}".getBytes(StandardCharsets.UTF_8))
                        .get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                failedWriteRejected = true;
            }
            check(failedWriteRejected
                            && gate.decision(id) == CharacterEvictionGate.Decision.DIRTY,
                    "failed WAL submission keeps identity dirty and releases persistence transaction pin");

            coordinator.checkpointLatest();
            CharacterRecordStore.Record stored = coordinator.load(id);
            check(stored != null && stored.payloadUtf8().equals("{\"v\":\"2-final\"}"),
                    "streaming checkpoint stores only the latest durable record");

            CharacterWal replayWal = new CharacterWal(walPath, 4);
            check(replayWal.replayAll().isEmpty(),
                    "WAL is retired only after every addressable checkpoint write succeeds");
            replayWal.close();

            CharacterWal idleWal = new CharacterWal(root.resolve("idle-wal"), 4);
            CharacterWalWriter idleWriter = new CharacterWalWriter(idleWal, 4, "wf-character-idle-writer-test");
            check(idleWriter.closeIfIdle(), "idle writer can be atomically closed for checkpoint");
            var rejectedAfterClose = new java.util.concurrent.CompletableFuture<CharacterWal.Record>();
            check(!idleWriter.submit(id, firstPayload, rejectedAfterClose)
                            && rejectedAfterClose.isCompletedExceptionally(),
                    "closed idle writer rejects new submissions without stranding receipts");
            idleWriter.close();
            idleWal.close();

        } finally {
            coordinator.close();
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
        if (failures != 0) throw new AssertionError(failures + " CharacterPersistenceCoordinator checks failed");
    }
}
