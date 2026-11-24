package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAccessService;
import dev.worldforge.people.CharacterRegistry;

/** Scoped access lease lifetime and identity tests. */
public final class CharacterAccessServiceTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
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
        check(registry.activeAccessLeaseCount() == 1,
                "closing a lease repeatedly is idempotent");

        second.close();
        check(!registry.isAccessPinned(original.id) && registry.activeAccessLeaseCount() == 0,
                "last lease release removes the sparse pin entry");

        boolean closedAccessRejected = false;
        try { second.get(); } catch (IllegalStateException expected) { closedAccessRejected = true; }
        check(closedAccessRejected, "closed lease cannot expose a Character reference");

        check(access.acquire("missing") == null, "missing local ID returns no lease");
        check(access.acquireGlobal("not-a-uuid") == null, "unknown global UUID returns no lease");

        Character secondCharacter = registry.create("Second Subject", "", "test", "f", 2);
        CharacterRegistry restored = new CharacterRegistry();
        restored.fromJson(registry.toJson(), "lease-restore-test");
        check(restored.size() == 2
                        && restored.idAt(0).equals(original.id)
                        && restored.idAt(1).equals(secondCharacter.id)
                        && restored.get(original.id) != null,
                "streamed restore publishes the staged map and insertion-order ID directory without changing order");
        if (failures != 0) throw new AssertionError(failures + " CharacterAccessService checks failed");
    }
}
