package dev.worldforge.mod.economy;

/**
 * Pure-Java access point for the world economic ledger.
 * Addon simulation and logic tests depend on this service, never on the Minecraft-bound mod entrypoint.
 */
public final class TreasuryService {
    private static volatile TreasuryLedger current = new TreasuryLedger();

    private TreasuryService() { }

    public static TreasuryLedger current() { return current; }

    public static void replace(TreasuryLedger ledger) {
        if (ledger == null) throw new IllegalArgumentException("ledger is required");
        current = ledger;
    }
}
