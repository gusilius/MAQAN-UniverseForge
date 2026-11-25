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
        check(gate.markPersisted(first) && gate.canEvict(id),
                "successful persistence receipt makes the exact generation clean");

        CharacterEvictionGate.PersistenceTicket stale = gate.capture(id);
        gate.markDirty(id);
        check(!gate.markPersisted(stale)
                        && gate.decision(id) == CharacterEvictionGate.Decision.DIRTY,
                "stale persistence receipt cannot clean a newer mutation");

        CharacterEvictionGate.PersistenceTicket second = gate.capture(id);
        check(gate.markPersisted(second), "latest generation can be acknowledged as durable");

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
        check(gate.completeEviction(id, reservation)
                        && gate.decision(id) == CharacterEvictionGate.Decision.UNKNOWN_IDENTITY
                        && gate.trackedIdentityCount() == 0,
                "successful physical eviction releases its per-identity runtime fence");
        gate.registerPersistedIfAbsent(id);
        check(gate.canEvict(id), "lazy identity re-registration restores the durable clean state");
        try (CharacterEvictionGate.Pin lease = gate.acquireLease(id)) {
            check(lease != null && !gate.canEvict(id),
                    "a rehydrated identity is pinned again before it is exposed");
        }

        if (failures != 0) throw new AssertionError(failures + " CharacterEvictionGate checks failed");
    }
}
