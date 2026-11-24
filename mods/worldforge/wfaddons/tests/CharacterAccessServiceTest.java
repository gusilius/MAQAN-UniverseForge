package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAccessService;
import dev.worldforge.people.CharacterRegistry;
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
        try {
            access.attachPersistenceCoordinator(coordinator);
            try (CharacterAccessService.Lease lease = access.acquireGlobal(original.globalId)) {
                check(gate.decision(original.globalId) == CharacterEvictionGate.Decision.ACTIVE_LEASE,
                        "registry lease is mirrored into the shared persistence eviction gate");
            }
            check(gate.decision(original.globalId) == CharacterEvictionGate.Decision.NEVER_PERSISTED,
                    "closing registry lease releases the persistence pin without falsely marking clean");
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
        check(restored.size() == 2 && restored.idAt(0).equals(original.id)
                        && restored.idAt(1).equals(secondCharacter.id) && restored.get(original.id) != null,
                "streamed restore publishes staged objects and identity order");
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
