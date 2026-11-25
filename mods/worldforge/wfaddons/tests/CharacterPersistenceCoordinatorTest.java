package dev.worldforge.tests;

import dev.worldforge.people.storage.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Exercises the existing WAL -> streaming checkpoint store -> eviction eligibility boundary. */
public final class CharacterPersistenceCoordinatorTest {
    private static int failures;
    private static void physicalRegistryEvictionRoundTrip(Path root) throws Exception {
        Files.createDirectories(root);
        CharacterRegistry registry = new CharacterRegistry();
        CharacterEvictionGate gate = new CharacterEvictionGate();
        CharacterWal wal = new CharacterWal(root.resolve("wal"), 4);
        CharacterWalWriter writer = new CharacterWalWriter(wal, 16, "wf-character-physical-eviction-test");
        CharacterRecordStore records = new CharacterRecordStore(root.resolve("records"));
        CharacterPersistenceCoordinator coordinator =
                new CharacterPersistenceCoordinator(gate, wal, writer, records);
        registry.access().attachPersistenceCoordinator(coordinator);
        try {
            Character original = registry.create("Eviction Test", "House Durable", "culture-durable", "f", 1200);
            original.props.put("eviction.round_trip.marker", "preserved");
            registry.appoint(original.id, "test_regent", "realm:eviction-test", 1200);
            String entityUuid = "00000000-0000-0000-0000-000000000123";
            check(registry.bindEntity(original.id, "villager", entityUuid),
                    "canonical Character accepts a durable physical entity binding");
            String globalId = original.globalId;
            try (var lease = registry.access().acquireGlobal(globalId)) {
                coordinator.persistMutation(globalId,
                        () -> registry.toJsonRecord(lease.get()).getBytes(StandardCharsets.UTF_8))
                        .get(10, TimeUnit.SECONDS);
            }
            coordinator.checkpointLatest();
            check(coordinator.markCheckpointCompleted(coordinator.dirtyEpoch()),
                    "physical eviction fixture publishes a complete addressable checkpoint");
            check(registry.evictMaterialized(globalId),
                    "registry physically removes a clean, unleased canonical Character object");
            check(registry.materializedSize() == 0 && registry.size() == 1,
                    "eviction removes only the materialized object while retaining UUID directory identity");
            check(registry.idsAtPlace("realm:eviction-test").contains(original.id)
                            && globalId.equals(registry.entityBindingOwner(entityUuid))
                            && registry.materializedSize() == 0,
                    "reverse office and entity indexes resolve UUIDs without hydrating evicted NPCs");
            java.io.StringWriter partitionSnapshot = new java.io.StringWriter();
            registry.writeJson(partitionSnapshot);
            check(registry.materializedSize() == 0 && partitionSnapshot.toString().contains(globalId)
                            && partitionSnapshot.toString().contains("Eviction Test"),
                    "full partition checkpoint serializes evicted records directly without rehydrating NPCs");
            try (var lease = registry.access().acquireGlobal(globalId)) {
                check(lease != null, "UUID lookup lazily rehydrates an evicted Character");
                Character restored = lease.get();
                check(restored != original && globalId.equals(restored.globalId)
                                && "House Durable".equals(restored.house)
                                && "culture-durable".equals(restored.culture)
                                && "preserved".equals(restored.props.get("eviction.round_trip.marker")),
                        "lazy hydration restores the same canonical UUID and complete durable record");
                check(registry.size() == 1 && registry.materializedSize() == 1,
                        "rehydration does not create a duplicate Character identity");
            }
        } finally {
            registry.access().detachPersistenceCoordinator();
            coordinator.close();
        }
    }

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
            CharacterWal.Record first = firstFuture.get(10, TimeUnit.SECONDS);
            check(writer.pending() == 0, "completed WAL receipt leaves no outstanding writer work");
            check(first.globalId().equals(id) && gate.decision(id) == CharacterEvictionGate.Decision.ELIGIBLE
                            && !coordinator.canEvict(id),
                    "forced WAL receipt cleans the captured generation but checkpoint is still required");
            coordinator.checkpointLatest();
            check(coordinator.markCheckpointCompleted(coordinator.dirtyEpoch()) && coordinator.canEvict(id),
                    "durable checkpoint publishes the eviction-eligible generation");

            try (CharacterEvictionGate.Pin lease = coordinator.acquireLease(id)) {
                check(!coordinator.canEvict(id), "coordinator lease pin prevents eviction");
            }
            check(coordinator.canEvict(id), "released lease permits eviction after durable checkpoint");

            coordinator.markDirty(id);
            byte[] secondPayload = "{\"v\":2}".getBytes(StandardCharsets.UTF_8);
            coordinator.persist(id, secondPayload).get(10, TimeUnit.SECONDS);
            check(!coordinator.canEvict(id) && gate.decision(id) == CharacterEvictionGate.Decision.ELIGIBLE,
                    "WAL receipt clears the generation but does not bypass checkpoint publication");
            coordinator.checkpointLatest();
            check(coordinator.markCheckpointCompleted(coordinator.dirtyEpoch()) && coordinator.canEvict(id),
                    "new dirty generation becomes evictable only after WAL force and checkpoint");


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
            check(!coordinator.canEvict(id) && gate.decision(id) == CharacterEvictionGate.Decision.ELIGIBLE,
                    "a fresh snapshot receipt acknowledges the current generation without bypassing checkpoint");
            coordinator.checkpointLatest();
            check(coordinator.markCheckpointCompleted(coordinator.dirtyEpoch()) && coordinator.canEvict(id),
                    "fresh snapshot becomes evictable after its durable checkpoint");

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
                            && gate.decision(id) == CharacterEvictionGate.Decision.DIRTY
                            && coordinator.dirtyQueue().contains(id),
                    "failed WAL submission keeps identity dirty and queued for retry");

            String batchId = "00000000-0000-0000-0000-000000000088";
            coordinator.register(batchId);
            int batchAccepted = coordinator.persistDirtyBatch(key -> {
                // The queue still contains the previously dirtied Character. A production
                // scheduler serializes the current canonical record for every UUID; keep
                // this fixture equivalent instead of overwriting it with a synthetic payload.
                if (id.equals(key)) return "{\"v\":\"2-final\"}".getBytes(StandardCharsets.UTF_8);
                return dev.worldforge.mod.Json.write(java.util.Map.of("id", key)).getBytes(StandardCharsets.UTF_8);
            }, 2, 1024);
            long batchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (coordinator.dirtyQueue().pendingCount() != 0 && System.nanoTime() < batchDeadline)
                Thread.sleep(5);
            check(batchAccepted == 2 && coordinator.dirtyQueue().pendingCount() == 0
                            && gate.decision(id) == CharacterEvictionGate.Decision.ELIGIBLE
                            && gate.decision(batchId) == CharacterEvictionGate.Decision.ELIGIBLE
                            && !coordinator.canEvict(id) && !coordinator.canEvict(batchId),
                    "bounded dirty service clears UUIDs only after durable WAL receipts");
            coordinator.checkpointLatest();
            check(coordinator.markCheckpointCompleted(coordinator.dirtyEpoch())
                            && coordinator.canEvict(id) && coordinator.canEvict(batchId),
                    "bounded dirty batch becomes evictable only after its checkpoint");

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
        physicalRegistryEvictionRoundTrip(root.resolve("physical-eviction"));
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
        if (failures != 0) throw new AssertionError(failures + " CharacterPersistenceCoordinator checks failed");
    }
}
