package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAccessService;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.people.storage.*;

import java.nio.file.Files;
import java.nio.file.Path;

/** Registry lease lifetime and the shared persistence eviction fence. */
public final class CharacterAccessServiceTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) throws Exception {
        CharacterRegistry registry = new CharacterRegistry();
        Character original = registry.create("Lease Subject", "", "test", "m", 0);
        CharacterAccessService access = registry.access();

        CharacterAccessService.Lease first = access.acquire(original.id);
        check(first != null && first.get() == original, "lease resolves the canonical Character instance");
        check(registry.isAccessPinned(original.id) && registry.activeAccessLeaseCount() == 1,
                "first lease pins the Character in the registry");

        CharacterAccessService.Lease second = access.acquireGlobal(original.globalId);
        check(second != null && second.get() == original && registry.activeAccessLeaseCount() == 2,
                "global UUID acquisition shares the same canonical object and increments the pin count");

        first.close();
        check(first.isClosed() && registry.isAccessPinned(original.id)
                        && registry.activeAccessLeaseCount() == 1,
                "closing one of multiple leases keeps the Character pinned");
        first.close();
        check(registry.activeAccessLeaseCount() == 1, "closing a lease repeatedly is idempotent");
        second.close();
        check(!registry.isAccessPinned(original.id) && registry.activeAccessLeaseCount() == 0,
                "last lease release removes the sparse registry pin entry");

        check(registry.evictionCandidateCount() == 1,
                "last lease release enqueues one deduplicated idle-eviction candidate");
        try (CharacterAccessService.Lease renewed = access.acquire(original.id)) {
            check(renewed != null && registry.evictionCandidateCount() == 0,
                    "new access cancels the queued idle candidate in constant time");
        }
        check(registry.evictionCandidateCount() == 1
                        && registry.pollEvictionCandidates(1, Long.MAX_VALUE).isEmpty()
                        && registry.evictionCandidateCount() == 1,
                "idle candidate remains queued until its minimum idle delay expires");
        check(registry.pollEvictionCandidates(1, 0L).equals(java.util.List.of(original.id))
                        && registry.evictionCandidateCount() == 0,
                "bounded candidate polling returns only released UUIDs without scanning the registry");

        boolean closedAccessRejected = false;
        try { second.get(); } catch (IllegalStateException expected) { closedAccessRejected = true; }
        check(closedAccessRejected, "closed lease cannot expose a Character reference");
        check(access.acquire("missing") == null, "missing local ID returns no lease");
        check(access.acquireGlobal("not-a-uuid") == null, "unknown global UUID returns no lease");

        Path root = Files.createTempDirectory("wf-character-access-");
        CharacterEvictionGate gate = new CharacterEvictionGate();
        CharacterWal wal = new CharacterWal(root.resolve("wal"), 2);
        CharacterWalWriter writer = new CharacterWalWriter(wal, 8, "wf-character-access-test");
        CharacterRecordStore store = new CharacterRecordStore(root.resolve("records"));
        CharacterPersistenceCoordinator coordinator =
                new CharacterPersistenceCoordinator(gate, wal, writer, store);
        String secondId = null;
        String evictableLocalId = null;
        try {
            access.attachPersistenceCoordinator(coordinator);
            try (CharacterAccessService.Lease lease = access.acquireGlobal(original.globalId)) {
                check(gate.decision(original.globalId) == CharacterEvictionGate.Decision.ACTIVE_LEASE,
                        "registry lease is mirrored into the shared persistence eviction gate");
            }
            check(gate.decision(original.globalId) == CharacterEvictionGate.Decision.NEVER_PERSISTED,
                    "closing registry lease releases the persistence pin without falsely marking clean");

            access.mutateGlobal(original.globalId, character -> character.name = "Mutated Through Scoped API");
            check("Mutated Through Scoped API".equals(original.name)
                            && gate.decision(original.globalId) == CharacterEvictionGate.Decision.DIRTY,
                    "UUID mutation API marks the canonical identity dirty after releasing pins");


            Character secondPerson = registry.create("Multi Subject", "", "test", "f", 2);
            secondId = secondPerson.id;
            dev.worldforge.people.CharacterAuthority authority = new dev.worldforge.people.CharacterAuthority();
            authority.beginWorld(WorldId.parse("00000000-0000-0000-0000-000000000001"));
            authority.registerMap("overworld", registry);
            access.mutateGlobals(authority, java.util.List.of(secondPerson.globalId, original.globalId, secondPerson.globalId),
                    participants -> {
                        participants.get(original.globalId).name = "Batch First";
                        participants.get(secondPerson.globalId).name = "Batch Second";
                    });
            check("Batch First".equals(original.name) && "Batch Second".equals(secondPerson.name)
                            && gate.decision(original.globalId) == CharacterEvictionGate.Decision.DIRTY
                            && gate.decision(secondPerson.globalId) == CharacterEvictionGate.Decision.DIRTY
                            && registry.activeAccessLeaseCount() == 0,
                    "multi-character mutation deduplicates UUIDs and dirties every canonical participant");

            long generationBeforeNoop = gate.capture(original.globalId).generation();
            boolean noOpChanged = CharacterAccessService.mutateGlobalsIfChanged(authority,
                    java.util.List.of(original.globalId), participants -> false);
            check(!noOpChanged && gate.capture(original.globalId).generation() == generationBeforeNoop,
                    "successful scoped no-op does not advance dirty generation");
            boolean failedScopedMutation = false;
            try {
                CharacterAccessService.mutateGlobalsIfChanged(authority,
                        java.util.List.of(original.globalId, secondPerson.globalId), participants -> {
                            participants.get(original.globalId).name = "Failed Batch";
                            throw new IllegalStateException("simulated batch failure");
                        });
            } catch (IllegalStateException expected) { failedScopedMutation = true; }
            check(failedScopedMutation
                            && gate.decision(original.globalId) == CharacterEvictionGate.Decision.DIRTY
                            && gate.decision(secondPerson.globalId) == CharacterEvictionGate.Decision.DIRTY
                            && registry.activeAccessLeaseCount() == 0,
                    "failed scoped mutation dirties every participant and releases all pins");

            boolean invalidBatchRejected = false;
            try { CharacterAccessService.mutateGlobals(authority, java.util.List.of(original.globalId, " "),
                    ignored -> { }); }
            catch (IllegalArgumentException expected) { invalidBatchRejected = true; }
            check(invalidBatchRejected && registry.activeAccessLeaseCount() == 0,
                    "invalid multi-character scope is rejected without leaking leases");

            boolean failedMutation = false;
            try {
                access.mutate(original.id, character -> {
                    character.name = "Partially Mutated";
                    throw new IllegalStateException("simulated domain failure");
                });
            } catch (IllegalStateException expected) { failedMutation = true; }
            check(failedMutation && gate.decision(original.globalId) == CharacterEvictionGate.Decision.DIRTY
                            && registry.activeAccessLeaseCount() == 0,
                    "failed partial mutation remains dirty and releases lease/transaction pins");

            Character evictable = registry.create("Eviction Subject", "", "test", "f", 12);
            evictableLocalId = evictable.id;
            String evictableGlobalId = evictable.globalId;
            evictable.name = "Persisted Eviction Subject";
            evictable.house = "House of Eviction";
            evictable.spouse = "family-spouse-local";
            evictable.father = "family-father-local";
            evictable.mother = "family-mother-local";
            evictable.children.add("family-child-local");
            evictable.props.put("profession.id", "lumberjack");
            evictable.props.put("estate.transfer_receipt", "estate-receipt-eviction-test");
            evictable.props.put("family.last_event", "inheritance:eviction-test");
            CharacterInventory.add(evictable, "minecraft:bread", 3);
            CharacterRegistry.recordCharacterMutation(evictable);
            check(!registry.evictMaterialized(evictableGlobalId),
                    "dirty identity cannot be evicted before its WAL frame and checkpoint are durable");
            int acceptedDirty = coordinator.persistDirtyBatch(globalId -> {
                try (CharacterAccessService.Lease lease = access.acquireGlobal(globalId)) {
                    if (lease == null) throw new IllegalStateException("missing Character during test flush");
                    return registry.toJsonRecord(lease.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            }, 32, 1L << 20);
            check(acceptedDirty > 0, "dirty Characters are submitted through the canonical WAL before checkpoint");
            writer.close();
            wal.checkpoint(() -> wal.replayLatestEach(store::put));
            long checkpointEpoch = coordinator.dirtyEpoch();
            check(coordinator.markCheckpointCompleted(checkpointEpoch),
                    "eviction becomes eligible only after all dirty UUIDs are durably checkpointed");
            check(registry.evictMaterialized(evictableGlobalId)
                            && registry.materializedSize() == registry.size() - 1,
                    "durably checkpointed unleased Character can leave the materialized object map");
            try (CharacterAccessService.Lease hydratedLease = access.acquireGlobal(evictableGlobalId)) {
                Character hydrated = hydratedLease.get();
                check(hydratedLease != null && evictableGlobalId.equals(hydrated.globalId)
                                && registry.materializedSize() == registry.size(),
                        "global UUID acquisition lazily hydrates the canonical identity from checkpoint");
                check(registry.get(evictableLocalId) == hydrated
                                && registry.getByGlobalId(evictableGlobalId) == hydrated,
                        "local/global lookups reuse the one hydrated canonical object");
                check("Persisted Eviction Subject".equals(hydrated.name)
                                && "House of Eviction".equals(hydrated.house)
                                && "family-spouse-local".equals(hydrated.spouse)
                                && "family-father-local".equals(hydrated.father)
                                && "family-mother-local".equals(hydrated.mother)
                                && hydrated.children.contains("family-child-local")
                                && "lumberjack".equals(hydrated.props.get("profession.id"))
                                && "estate-receipt-eviction-test".equals(hydrated.props.get("estate.transfer_receipt"))
                                && CharacterInventory.count(hydrated, "minecraft:bread") == 3,
                        "rehydration preserves family, profession, estate receipt and canonical inventory");
                check(!registry.evictMaterialized(evictableGlobalId),
                        "active scoped lease prevents concurrent physical eviction");
            }
            try (CharacterAccessService.Lease presence = access.acquirePresenceGlobal(evictableGlobalId)) {
                check(presence != null && !coordinator.canEvict(evictableGlobalId),
                        "physical presence lease blocks eviction while its body is active");
                try (CharacterAccessService.Lease unrelated = access.acquireGlobal(original.globalId)) {
                    unrelated.get().name = "Unrelated mutation while body is present";
                    CharacterRegistry.recordCharacterMutation(unrelated.get());
                }
            }
            check(coordinator.canEvict(evictableGlobalId),
                    "presence lease is not a thread-local mutation scope and does not dirty unrelated NPCs");
            check(registry.evictMaterialized(evictableGlobalId),
                    "presence pin release returns the unchanged Character to the bounded eviction path");
            try (CharacterAccessService.Lease rehydratedAgain = access.acquireGlobal(evictableGlobalId)) {
                check(rehydratedAgain != null
                                && "Persisted Eviction Subject".equals(rehydratedAgain.get().name)
                                && CharacterInventory.count(rehydratedAgain.get(), "minecraft:bread") == 3,
                        "second eviction/rehydration cycle preserves the durable record");
            }
        } finally {
            coordinator.close();
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }

        Character secondCharacter = registry.create("Second Subject", "", "test", "f", 2);
        CharacterRegistry restored = new CharacterRegistry();
        restored.fromJson(registry.toJson(), "lease-restore-test");
        check(restored.size() == 4 && restored.idAt(0).equals(original.id)
                        && restored.idAt(1).equals(secondId) && restored.idAt(2).equals(evictableLocalId)
                        && restored.idAt(3).equals(secondCharacter.id) && restored.get(original.id) != null,
                "streamed restore preserves the complete identity directory, including lazily evicted NPCs");
        original.traits.add("resilient");
        String validRecord = registry.toJsonRecord(original);
        String duplicateTraitRecord = validRecord.replace(
                "\"traits\":[\"resilient\"]", "\"traits\":[\"resilient\",\"resilient\"]");
        boolean duplicateTraitRejected = false;
        try {
            new CharacterRegistry().applyJsonRecord(duplicateTraitRecord, "lease-restore-test", original.globalId);
        } catch (IllegalArgumentException expected) { duplicateTraitRejected = true; }
        check(duplicateTraitRejected, "incremental decoder still rejects duplicate traits");
        if (failures != 0) throw new AssertionError(failures + " CharacterAccessService checks failed");
    }
}
