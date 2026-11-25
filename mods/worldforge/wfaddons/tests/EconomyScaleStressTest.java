package dev.worldforge.tests;

import dev.worldforge.mod.economy.TreasuryArchiveStore;
import dev.worldforge.mod.economy.TreasuryLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Progressive Contour 05 ledger-scale stress. One wallet per simulated citizen,
 * one stock and one market account per settlement, real double-entry payroll/trade/tax
 * transfers, consumption, debt service, archive checkpoints and restart replay.
 */
public final class EconomyScaleStressTest {
    private record Stage(int population, int settlements) { }

    private static final Stage[] STAGES = {
            new Stage(10_000, 50),
            new Stage(50_000, 150),
            new Stage(100_000, 300),
            new Stage(250_000, 600),
            new Stage(500_000, 600),
            new Stage(750_000, 1_000),
            new Stage(1_000_000, 1_000)
    };

    private static long peakHeapBytes;
    private static long operations;

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void sampleHeap() {
        peakHeapBytes = Math.max(peakHeapBytes, usedHeapBytes());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String settlementAccount(int id) { return "settlement:" + id; }
    private static String stockAccount(int id) { return "stock:settlement:" + id; }
    private static String marketAccount(int id) { return "market:settlement:" + id; }
    private static String walletAccount(int id) { return "wallet:npc:" + id; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("maqan-contour-05-economy-");
        TreasuryArchiveStore store = new TreasuryArchiveStore(root);
        TreasuryLedger ledger = store.loadOrMigrate(null);
        ledger.open("treasury:world", "world-treasury", "silver", 1_000_000_000_000.0);

        int previousPopulation = 0;
        int previousSettlements = 0;
        long totalStarted = System.nanoTime();

        try {
            for (int stageIndex = 0; stageIndex < STAGES.length; stageIndex++) {
                Stage stage = STAGES[stageIndex];
                long stageStarted = System.nanoTime();
                long stageOperationsBefore = operations;

                for (int settlement = previousSettlements; settlement < stage.settlements(); settlement++) {
                    ledger.open(settlementAccount(settlement), "settlement-owner:" + settlement, "silver", 1_000_000.0);
                    ledger.open(stockAccount(settlement), "settlement-owner:" + settlement, "silver", 0);
                    ledger.open(marketAccount(settlement), "market-owner:" + settlement, "silver", 0);
                    operations += 3;
                }

                // One durable canonical wallet per citizen. Payroll is a real transfer
                // from that citizen's settlement; consumption is a separate debit.
                for (int citizen = previousPopulation; citizen < stage.population(); citizen++) {
                    int settlement = citizen % stage.settlements();
                    String wallet = walletAccount(citizen);
                    String wageReference = "payroll:day-1:npc:" + citizen;
                    String consumptionReference = "needs:day-1:npc:" + citizen;
                    ledger.open(wallet, "character:" + citizen, "silver", 0);
                    require(ledger.transferOnce(settlementAccount(settlement), wallet, 10.0,
                                    TreasuryLedger.Kind.WAGE, "citizen-payroll", wageReference,
                                    1_440L, "daily citizen wage"),
                            "wage transfer failed for citizen " + citizen);
                    require(ledger.withdrawOnce(wallet, 4.0, TreasuryLedger.Kind.CONSUMPTION,
                                    "citizen-needs", consumptionReference, 1_440L, "daily food and services"),
                            "consumption debit failed for citizen " + citizen);
                    operations += 3;
                    if ((citizen & 0x3fff) == 0) sampleHeap();
                }

                // Exercise warehouse production/consumption, taxes and inter-account trade
                // for every settlement at each scale boundary.
                for (int settlement = 0; settlement < stage.settlements(); settlement++) {
                    String stock = stockAccount(settlement);
                    String employer = settlementAccount(settlement);
                    String market = marketAccount(settlement);
                    String prefix = "stage:" + stageIndex + ":settlement:" + settlement;
                    require(ledger.depositOnce(stock, 2_000.0, TreasuryLedger.Kind.PRODUCTION,
                                    "warehouse-production", "production:" + prefix, 1_440L,
                                    "harvest and workshop output"),
                            "production failed for settlement " + settlement);
                    require(ledger.withdrawOnce(stock, 1_250.0, TreasuryLedger.Kind.CONSUMPTION,
                                    "warehouse-consumption", "consumption:" + prefix, 1_440L,
                                    "local population and building inputs"),
                            "stock consumption failed for settlement " + settlement);
                    require(ledger.transferOnce(employer, "treasury:world", 50.0,
                                    TreasuryLedger.Kind.TAX, "settlement-tax", "tax:" + prefix,
                                    1_440L, "settlement tax remittance"),
                            "tax transfer failed for settlement " + settlement);
                    require(ledger.transferOnce(employer, market, 25.0,
                                    TreasuryLedger.Kind.TRADE, "settlement-trade", "trade:" + prefix,
                                    1_440L, "market purchase and trade settlement"),
                            "trade transfer failed for settlement " + settlement);
                    operations += 4;
                }

                // Loan, interest and repayment are part of the same canonical ledger.
                if (stageIndex == 0) {
                    String debtAccount = settlementAccount(0);
                    require(ledger.setCreditLimit(debtAccount, 5_000.0), "credit limit setup failed");
                    require(ledger.borrow(debtAccount, 1_000.0, 1_440L, "contour05-loan"),
                            "settlement loan failed");
                    require(ledger.accrueInterest(debtAccount, 0.01, 2_880L, "contour05-interest"),
                            "loan interest failed");
                    require(ledger.repayDebt(debtAccount, 250.0, 4_320L, "contour05-repayment"),
                            "debt repayment failed");
                    operations += 4;
                }

                // Verify a retry of real archived/pending operations is a no-op.
                int sampleCitizen = stage.population() - 1;
                int sampleSettlement = sampleCitizen % stage.settlements();
                long sequenceBeforeRetry = ledger.nextSequence();
                double settlementBeforeRetry = ledger.balance(settlementAccount(sampleSettlement));
                require(ledger.transferOnce(settlementAccount(sampleSettlement), walletAccount(sampleCitizen), 10.0,
                                TreasuryLedger.Kind.WAGE, "citizen-payroll",
                                "payroll:day-1:npc:" + sampleCitizen, 1_440L, "retry daily citizen wage"),
                        "idempotent payroll retry failed");
                require(ledger.withdrawOnce(walletAccount(sampleCitizen), 4.0, TreasuryLedger.Kind.CONSUMPTION,
                                "citizen-needs", "needs:day-1:npc:" + sampleCitizen,
                                1_440L, "retry daily consumption"),
                        "idempotent consumption retry failed");
                require(ledger.nextSequence() == sequenceBeforeRetry
                                && Math.abs(ledger.balance(settlementAccount(sampleSettlement)) - settlementBeforeRetry) < 1e-8,
                        "retry duplicated or changed a committed economic operation");

                long version = store.checkpoint(ledger);
                require(ledger.pendingJournalSize() == 0, "checkpoint did not compact the committed journal tail");
                TreasuryArchiveStore reopenedStore = new TreasuryArchiveStore(root);
                TreasuryLedger restored = reopenedStore.loadOrMigrate(null);
                require(restored.accountCount() == ledger.accountCount(), "account count changed after restart replay");
                require(restored.nextSequence() == ledger.nextSequence(), "journal sequence changed after restart replay");
                require(restored.mutationVersion() == version, "mutation version changed after checkpoint recovery");
                require(Math.abs(restored.balance(walletAccount(sampleCitizen)) - 6.0) < 1e-8,
                        "citizen wallet balance changed after recovery");
                require(restored.entriesForReference(walletAccount(sampleCitizen),
                                TreasuryLedger.Kind.CONSUMPTION,
                                "needs:day-1:npc:" + sampleCitizen).size() == 1,
                        "consumption receipt missing or duplicated after recovery");
                long restoredSequence = restored.nextSequence();
                require(restored.transferOnce(settlementAccount(sampleSettlement), walletAccount(sampleCitizen), 10.0,
                                TreasuryLedger.Kind.WAGE, "citizen-payroll",
                                "payroll:day-1:npc:" + sampleCitizen, 1_440L, "post-restart retry"),
                        "post-restart payroll retry failed");
                require(restored.nextSequence() == restoredSequence, "post-restart retry duplicated payroll");

                ledger = restored;
                store = reopenedStore;
                previousPopulation = stage.population();
                previousSettlements = stage.settlements();
                sampleHeap();

                long elapsedMillis = (System.nanoTime() - stageStarted) / 1_000_000L;
                long stageOperations = operations - stageOperationsBefore;
                double operationsPerSecond = elapsedMillis <= 0 ? 0
                        : stageOperations * 1000.0 / elapsedMillis;
                System.out.printf(Locale.ROOT,
                        "WF_STRESS contour=05_economy stage=%d population=%d settlements=%d accounts=%d journalEntries=%d operations=%d elapsedMs=%d opsPerSec=%.1f heapUsedBytes=%d peakHeapBytes=%d checkpointVersion=%d recovery=PASS idempotency=PASS%n",
                        stageIndex + 1, stage.population(), stage.settlements(), ledger.accountCount(),
                        ledger.journalSize(), stageOperations, elapsedMillis, operationsPerSecond,
                        usedHeapBytes(), peakHeapBytes, version);
            }

            long totalMillis = (System.nanoTime() - totalStarted) / 1_000_000L;
            require(ledger.accountCount() == 1_003_001, "final economy account cardinality mismatch");
            require(ledger.pendingJournalSize() == 0, "final economy journal tail was not checkpointed");
            require(ledger.balance("treasury:world") > 1_000_000_000_000.0,
                    "treasury did not receive settlement taxes");
            System.out.printf(Locale.ROOT,
                    "WF_STRESS contour=05_economy status=PASS population=1000000 settlements=1000 accounts=%d journalEntries=%d operations=%d elapsedMs=%d peakHeapBytes=%d%n",
                    ledger.accountCount(), ledger.journalSize(), operations, totalMillis, peakHeapBytes);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
