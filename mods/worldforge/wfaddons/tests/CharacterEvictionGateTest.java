package dev.worldforge.tests;

import dev.worldforge.people.storage.CharacterEvictionGate;

/** Generation, durability, lease and transaction fences for future physical eviction. */
public final class CharacterEvictionGateTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        CharacterEvictionGate gate = new CharacterEvictionGate();
        String id = "00000000-0000-0000-0000-000000000001";
        gate.register(id);
        check(gate.decision(id) == CharacterEvictionGate.Decision.NEVER_PERSISTED,
                "new identity cannot be evicted before first durable persistence");

        String recovered = "00000000-0000-0000-0000-000000000099";
        gate.registerPersisted(recovered);
        check(gate.canEvict(recovered),
                "identity recovered from an existing durable snapshot starts clean without a redundant rewrite");

        CharacterEvictionGate.PersistenceTicket first = gate.capture(id);
        check(gate.markPersisted(first)
                        && gate.decision(id) == CharacterEvictionGate.Decision.CHECKPOINT_PENDING
                        && !gate.canEvict(id),
                "WAL receipt cleans the exact generation but still requires checkpoint publication");
        gate.beginCheckpoint();
        gate.completeCheckpoint();
        check(gate.canEvict(id), "checkpoint publication makes the clean generation evictable");

        CharacterEvictionGate.PersistenceTicket stale = gate.capture(id);
        gate.markDirty(id);
        check(!gate.markPersisted(stale)
                        && gate.decision(id) == CharacterEvictionGate.Decision.DIRTY,
                "stale persistence receipt cannot clean a newer mutation");

        CharacterEvictionGate.PersistenceTicket second = gate.capture(id);
        check(gate.markPersisted(second)
                        && gate.decision(id) == CharacterEvictionGate.Decision.CHECKPOINT_PENDING,
                "latest generation can be acknowledged by WAL but remains checkpoint-pending");
        gate.beginCheckpoint();
        gate.completeCheckpoint();

        String raceId = "00000000-0000-0000-0000-000000000088";
        gate.register(raceId);
        CharacterEvictionGate.PersistenceTicket raceInitial = gate.capture(raceId);
        gate.markPersisted(raceInitial);
        gate.beginCheckpoint();
        gate.markDirty(raceId);
        CharacterEvictionGate.PersistenceTicket raceUpdated = gate.capture(raceId);
        check(gate.markPersisted(raceUpdated), "concurrent checkpoint mutation receives a durable WAL receipt");
        gate.completeCheckpoint();
        check(!gate.canEvict(raceId)
                        && gate.decision(raceId) == CharacterEvictionGate.Decision.CHECKPOINT_PENDING,
                "checkpoint does not publish an identity mutated after its checkpoint cut");
        gate.beginCheckpoint();
        gate.completeCheckpoint();
        check(gate.canEvict(raceId), "the next checkpoint publishes the latest unchanged generation");

        try (CharacterEvictionGate.Pin lease = gate.acquireLease(id)) {
            check(gate.decision(id) == CharacterEvictionGate.Decision.ACTIVE_LEASE,
                    "active object lease blocks eviction");
        }
        check(gate.canEvict(id), "closing the last lease restores eligibility");

        try (CharacterEvictionGate.Pin transaction = gate.beginTransaction(id)) {
            check(gate.decision(id) == CharacterEvictionGate.Decision.ACTIVE_TRANSACTION,
                    "unfinished transaction blocks eviction");
        }
        check(gate.canEvict(id), "closing the transaction pin restores eligibility");

        try (CharacterEvictionGate.Pin firstLease = gate.acquireLease(id);
             CharacterEvictionGate.Pin secondLease = gate.acquireLease(id)) {
            check(!gate.canEvict(id), "multiple simultaneous leases remain pinned");
        }
        check(gate.canEvict(id), "all leases release without leaking a pin");

        CharacterEvictionGate.Pin reservation = gate.tryBeginEviction(id);
        check(reservation != null
                        && gate.decision(id) == CharacterEvictionGate.Decision.ACTIVE_TRANSACTION
                        && !gate.canEvict(id),
                "eviction reservation atomically blocks new access and competing eviction");
        boolean acquisitionRejected = false;
        try { gate.acquireLease(id); }
        catch (IllegalStateException expected) { acquisitionRejected = true; }
        check(acquisitionRejected, "new leases cannot race an in-progress physical eviction");
        boolean evictionCompleted = gate.completeEviction(id, reservation);
        CharacterEvictionGate.Decision postEvictionDecision = gate.decision(id);
        int trackedAfterEviction = gate.trackedIdentityCount();
        check(evictionCompleted
                        && postEvictionDecision == CharacterEvictionGate.Decision.UNKNOWN_IDENTITY
                        && trackedAfterEviction == 0,
                "successful physical eviction releases its per-identity runtime fence (completed="
                        + evictionCompleted + ", decision=" + postEvictionDecision
                        + ", tracked=" + trackedAfterEviction + ")");
        gate.registerPersistedIfAbsent(id);
        check(gate.canEvict(id), "lazy identity re-registration restores the durable clean state");
        try (CharacterEvictionGate.Pin lease = gate.acquireLease(id)) {
            check(lease != null && !gate.canEvict(id),
                    "a rehydrated identity is pinned again before it is exposed");
        }

        if (failures != 0) throw new AssertionError(failures + " CharacterEvictionGate checks failed");
    }
}
