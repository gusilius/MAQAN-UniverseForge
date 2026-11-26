package dev.worldforge.tests;

import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.economy.TreasuryJournalSegment;
import dev.worldforge.mod.economy.TreasuryJournalArchive;
import dev.worldforge.mod.economy.TreasuryLedgerSnapshot;
import dev.worldforge.mod.economy.TreasuryCheckpointStore;
import dev.worldforge.mod.economy.TreasuryService;
import dev.worldforge.mod.economy.TreasuryArchiveStore;
import dev.worldforge.mod.economy.TreasuryArchiveMigration;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.mod.Json;
import java.util.*;
import java.io.StringWriter;

public final class TreasuryLedgerTest {
    private static int failures;
    private static void ok(boolean value,String label) {
        System.out.println((value?"ok   ":"FAIL ")+label);
        if(!value) failures++;
    }
    private static boolean close(double a,double b) { return Math.abs(a-b)<1e-8; }

    public static void main(String[] args) {
        dev.worldforge.mod.api.DomainMutationRevisions revisions = dev.worldforge.mod.api.WorldForgeApi.revisions();
        revisions.resetForTests();
        TreasuryLedger revisionLedger = new TreasuryLedger();
        long economyBefore = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        revisionLedger.open("revision:account", "revision:account", "silver", 100);
        long afterOpen = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        ok(afterOpen == economyBefore + 1, "opening account publishes one economy revision");
        long versionBeforeLimit = revisionLedger.mutationVersion();
        revisionLedger.setCreditLimit("revision:account", 50);
        long versionAfterLimit = revisionLedger.mutationVersion();
        long afterLimit = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        revisionLedger.setCreditLimit("revision:account", 50);
        ok(afterLimit == afterOpen + 1 && revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE) == afterLimit
                && versionAfterLimit > versionBeforeLimit && revisionLedger.mutationVersion() == versionAfterLimit,
                "credit limit publishes once; mutation version advances only for canonical changes");
        revisionLedger.open("revision:target", "revision:target", "silver", 0);
        revisionLedger.transfer("revision:account", "revision:target", 10,
                TreasuryLedger.Kind.GRANT, "revision-test", "revision-tail", 1, "tail view");
        List<TreasuryLedger.Entry> tailView = revisionLedger.entriesFromSequence(2);
        ok(tailView.size() == revisionLedger.journalSize() - 1
                        && tailView.get(0).sequence() == 2
                        && tailView.get(tailView.size() - 1).sequence() == revisionLedger.nextSequence() - 1,
                "treasury archive cursor exposes only the contiguous unarchived suffix without copying history");
        StringWriter versionedSnapshot = new StringWriter();
        long versionBeforeFullSnapshot = revisionLedger.mutationVersion();
        try {
            long writtenVersion = revisionLedger.writeJsonVersioned(versionedSnapshot);
            ok(writtenVersion == versionBeforeFullSnapshot
                    && TreasuryLedger.fromJson(versionedSnapshot.toString()).accountCount() == 2,
                    "versioned streaming snapshot returns the exact revision represented by its bytes");
        } catch (java.io.IOException ex) { ok(false, "versioned streaming snapshot completes"); }
        boolean rejectedConflictingSnapshotIndexes = false;
        try {
            TreasuryLedgerSnapshot.encode(new TreasuryLedgerSnapshot.State(1, 1, 0, List.of(),
                    Set.of("same-key"), Set.of("same-key"), Set.of("same-key"), Set.of()));
        } catch (IllegalArgumentException expected) { rejectedConflictingSnapshotIndexes = true; }
        boolean rejectedUnknownRationAccount = false;
        try {
            TreasuryLedgerSnapshot.encode(new TreasuryLedgerSnapshot.State(1, 1, 0, List.of(),
                    Set.of(), Set.of(), Set.of(), Set.of("missing:account")));
        } catch (IllegalArgumentException expected) { rejectedUnknownRationAccount = true; }
        ok(rejectedConflictingSnapshotIndexes && rejectedUnknownRationAccount,
                "treasury snapshots reject contradictory debit/credit keys and orphaned ration indexes");
        String compactSnapshot = TreasuryLedgerSnapshot.capture(new TreasuryLedger());
        boolean compactSnapshotOmitsPostingSets = !compactSnapshot.contains("postedReferences")
                && !compactSnapshot.contains("postedDebits") && !compactSnapshot.contains("postedCredits");
        ok(compactSnapshotOmitsPostingSets,
                "version-2 treasury checkpoints omit unbounded idempotency key arrays");
        boolean legacySnapshotRoundTrips = false;
        try {
            Map<String, Object> legacyPayload = new java.util.LinkedHashMap<>();
            legacyPayload.put("format", "wf-treasury-snapshot/1");
            legacyPayload.put("nextSequence", 1L);
            legacyPayload.put("nextTransactionSequence", 1L);
            legacyPayload.put("mutationVersion", 0L);
            legacyPayload.put("accounts", List.of());
            legacyPayload.put("postedReferences", List.of());
            legacyPayload.put("postedDebits", List.of());
            legacyPayload.put("postedCredits", List.of());
            legacyPayload.put("armyFieldRationDebitAccounts", List.of());
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Json.write(legacyPayload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder checksum = new StringBuilder();
            for (byte value : digest) checksum.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            legacyPayload.put("sha256", checksum.toString());
            TreasuryLedgerSnapshot.State legacyState = TreasuryLedgerSnapshot.decode(Json.write(legacyPayload));
            legacySnapshotRoundTrips = legacyState.legacyIdempotencyIndexes()
                    && legacyState.postedReferences().isEmpty();
        } catch (Exception ignored) { }
        ok(legacySnapshotRoundTrips, "version-1 treasury checkpoints remain readable during migration");

        StringWriter escapedString = new StringWriter();
        try {
            String fixture = "quote=\" slash=\\ line=\n tab=\t control=" + (char) 1;
            Json.writeString(escapedString, fixture);
            ok(escapedString.toString().equals(Json.write(fixture)), "direct JSON string writer preserves canonical escaping");
        } catch (java.io.IOException ex) { ok(false, "direct JSON string writer completes"); }
        revisionLedger.open("revision:target", "revision:target", "silver", 0);
        long beforeTransfer = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        revisionLedger.transferOnce("revision:account", "revision:target", 10,
                TreasuryLedger.Kind.GRANT, "revision-test", "revision-transfer", 1, "revision test");
        long afterTransfer = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        ok(afterTransfer == beforeTransfer + 1, "nested transferOnce and transfer publish one economy revision");
        TreasuryLedger.fromJson(revisionLedger.toJson());
        ok(revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE) == afterTransfer,
                "ledger restore rebuilds state without publishing a live economy mutation");

        TreasuryLedger concurrent = new TreasuryLedger();
        concurrent.open("concurrent:source", "concurrent:source", "silver", 1000);
        concurrent.open("concurrent:target", "concurrent:target", "silver", 0);
        long concurrentBefore = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        java.util.concurrent.atomic.AtomicInteger concurrentSuccess = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> writers = new java.util.ArrayList<>();
        for (int worker = 0; worker < 4; worker++) {
            final int workerId = worker;
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    if (concurrent.transfer("concurrent:source", "concurrent:target", 1,
                            TreasuryLedger.Kind.TRADE, "concurrency", "worker:" + workerId + ":" + i,
                            i, "concurrent transfer")) concurrentSuccess.incrementAndGet();
                }
            });
            writers.add(writer);
            writer.start();
        }
        for (Thread writer : writers) {
            try { writer.join(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
        }
        long concurrentAfter = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        ok(concurrentSuccess.get() == 100 && close(concurrent.balance("concurrent:source"), 900)
                        && close(concurrent.balance("concurrent:target"), 100),
                "concurrent treasury writers serialize without lost transfers or balance corruption");
        ok(concurrentAfter == concurrentBefore + 100,
                "each concurrent logical transfer publishes exactly one economy revision");

        TreasuryLedger ledger=new TreasuryLedger();
        ledger.open("realm:kingdom","realm:kingdom","silver",1000);
        ledger.open("settlement:capital","settlement:capital","silver",100);
        ledger.open("army:royal","army:royal","silver",25);
        ledger.open("merchant:caravan","merchant:caravan","silver",0);
        ledger.open("foreign:account","foreign:account","gold",10);
        ok(ledger.transfer("realm:kingdom","settlement:capital",125,TreasuryLedger.Kind.GRANT,"budget","grant-1",10,"settlement grant"),
                "government grant transfers funds between shared accounts");
        ok(close(ledger.balance("realm:kingdom"),875)&&close(ledger.balance("settlement:capital"),225),
                "transfer debits and credits both account balances exactly once");
        long journalBefore=ledger.journal().size();
        ok(!ledger.transfer("realm:kingdom","settlement:capital",5000,TreasuryLedger.Kind.GRANT,"budget","grant-fail",11,"overdraft denied")
                && ledger.journal().size()==journalBefore,
                "failed transfer is atomic and creates no journal entries");
        ok(!ledger.transfer("realm:kingdom","foreign:account",1,TreasuryLedger.Kind.TRADE,"trade","currency-fail",12,""),
                "currency or account policy rejects invalid operations");

        ledger.setCreditLimit("merchant:caravan",50);
        ok(ledger.withdraw("merchant:caravan",40,TreasuryLedger.Kind.LOAN,"credit","loan-1",20,"caravan working capital"),
                "withdrawal can use an explicitly configured credit line");
        ok(close(ledger.balance("merchant:caravan"),0)&&close(ledger.account("merchant:caravan").debt,40)
                && !ledger.account("merchant:caravan").bankrupt,
                "credit draw is recorded as debt without negative cash balance");
        ok(ledger.deposit("merchant:caravan",15,TreasuryLedger.Kind.TRADE,"trade","sale-1",21,"caravan sale"),
                "income is accepted and applied to outstanding debt first");
        ok(close(ledger.account("merchant:caravan").debt,25)&&close(ledger.balance("merchant:caravan"),0),
                "debt-first deposit does not create spendable cash before liabilities are reduced");
        ok(ledger.deposit("merchant:caravan",40,TreasuryLedger.Kind.TRADE,"trade","sale-2",22,"second sale")
                && close(ledger.account("merchant:caravan").debt,0)&&close(ledger.balance("merchant:caravan"),15),
                "surplus income becomes cash after debt repayment");
        ok(ledger.repayDebt("merchant:caravan",10,23,"repay-none")&&close(ledger.balance("merchant:caravan"),15),
                "repaying a cleared debt does not change cash");

        ok(ledger.withdraw("merchant:caravan",15,TreasuryLedger.Kind.WITHDRAWAL,"operations","withdraw-1",24,"spend cash")
                && close(ledger.balance("merchant:caravan"),0),
                "cash withdrawal is reflected in the ledger");
        ok(ledger.withdraw("merchant:caravan",60,TreasuryLedger.Kind.LOAN,"credit","loan-2",25,"exceed credit")
                ==false && close(ledger.account("merchant:caravan").debt,0),
                "credit limit prevents an overdraw and leaves the account unchanged");

        ledger.open("realm:debt-test","realm:debt-test","silver",0);
        ledger.setCreditLimit("realm:debt-test",50);
        ok(ledger.borrow("realm:debt-test",40,30,"sovereign-loan")
                        && close(ledger.balance("realm:debt-test"),40)
                        && close(ledger.account("realm:debt-test").debt,40),
                "loan origination creates matched cash and liability");
        ok(ledger.withdraw("realm:debt-test",30,TreasuryLedger.Kind.UPKEEP,"public-works","roads",31,"road program")
                        && close(ledger.balance("realm:debt-test"),10),
                "public spending consumes loan cash while preserving debt principal");
        ok(ledger.accrueInterest("realm:debt-test",0.5,32,"interest-cycle")
                        && close(ledger.account("realm:debt-test").debt,60)
                        && ledger.account("realm:debt-test").bankrupt,
                "interest can trigger a credit-limit breach and bankruptcy state");
        ok(ledger.repayDebt("realm:debt-test",10,33,"partial-repayment")
                        && close(ledger.account("realm:debt-test").debt,50)
                        && !ledger.account("realm:debt-test").bankrupt,
                "repayment restores solvency at the credit limit");

        TreasuryLedger rationIndex = new TreasuryLedger();
        rationIndex.open("stock:settlement:one:food", "settlement:one", "food", 50);
        rationIndex.open("army:field", "army:field", "food", 0);
        ok(!rationIndex.hasArmyFieldRationDebit("stock:settlement:one:food"),
                "army-ration journal index starts empty");
        ok(rationIndex.withdraw("stock:settlement:one:food", 3, TreasuryLedger.Kind.LOGISTICS,
                        "army-field-rations", "ration:one", 60, "field rations")
                        && rationIndex.hasArmyFieldRationDebit("stock:settlement:one:food"),
                "army field-ration debit is indexed immediately without scanning journal");
        ok(!rationIndex.hasArmyFieldRationDebit("army:field"),
                "army ration index only marks the source debit account");
        TreasuryLedger rationRestored = TreasuryLedger.fromJson(rationIndex.toJson());
        ok(rationRestored.hasArmyFieldRationDebit("stock:settlement:one:food")
                        && !rationRestored.hasArmyFieldRationDebit("army:field"),
                "army field-ration debit index is rebuilt from durable journal on load");

        TreasuryLedger streaming = new TreasuryLedger();
        streaming.open("streaming:account", "streaming:account", "silver", 1_000_000);
        boolean streamingEntriesAccepted = true;
        for (int i = 0; i < 12000; i++) {
            streamingEntriesAccepted &= streaming.deposit("streaming:account", 1, TreasuryLedger.Kind.PRODUCTION,
                    "streaming-stress", "streaming:" + i, i, "streamed journal entry");
        }
        ok(streamingEntriesAccepted, "large treasury journal stress fixture records every entry");
        StringWriter streamingWriter = new StringWriter();
        try {
            streaming.writeJson(streamingWriter);
            TreasuryLedger streamingRestored = TreasuryLedger.fromJson(streamingWriter.toString());
            ok(streamingRestored.journal().size() == streaming.journal().size()
                            && close(streamingRestored.balance("streaming:account"), 1_012_000),
                    "large treasury journals stream to JSON and restore without changing balances or receipts");
        } catch (java.io.IOException ex) {
            ok(false, "large treasury journal streaming serialization completes");
        }

        TreasuryLedger scale = new TreasuryLedger();
        scale.open("scale:account", "scale:account", "silver", 1_000_000);
        for (int i = 0; i < 100_000; i++)
            scale.deposit("scale:account", 1, TreasuryLedger.Kind.PRODUCTION, "scale-stress",
                    "scale:" + i, i, "large campaign ledger");
        final long[] streamedChars = {0};
        java.io.Writer countingWriter = new java.io.Writer() {
            @Override public void write(char[] chars, int off, int len) { streamedChars[0] += len; }
            @Override public void write(int c) { streamedChars[0]++; }
            @Override public void write(String value, int off, int len) { streamedChars[0] += len; }
            @Override public void flush() { }
            @Override public void close() { }
        };
        long benchmarkStarted = System.nanoTime();
        try {
            long version = scale.writeJsonVersioned(countingWriter);
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - benchmarkStarted);
            ok(version == scale.mutationVersion() && streamedChars[0] > 10_000_000,
                    "100k-entry treasury journal streams directly without a giant JSON string");
            System.out.println("TREASURY_STREAM_BENCHMARK entries=100000 chars=" + streamedChars[0] + " elapsedMs=" + elapsedMillis);
        } catch (java.io.IOException ex) { ok(false, "100k-entry treasury streaming stress completes"); }

        ok(rationIndex.entriesForAccount("stock:settlement:one:food").size() == 3
                        && rationIndex.entriesForAccount("stock:settlement:one:food").get(0).kind() == TreasuryLedger.Kind.ACCOUNT_CREATED
                        && rationIndex.entriesForAccount("stock:settlement:one:food").get(1).kind() == TreasuryLedger.Kind.OPENING
                        && rationIndex.entriesForAccount("stock:settlement:one:food").get(2).reference().equals("ration:one")
                        && rationIndex.entriesForReference("stock:settlement:one:food", TreasuryLedger.Kind.LOGISTICS, "ration:one").size() == 1
                        && rationIndex.hasEntries("stock:settlement:one:food"),
                "account and reference journal indexes are populated on append");
        ok(rationRestored.entriesForAccount("stock:settlement:one:food").size() == 3
                        && rationRestored.entriesForAccount("stock:settlement:one:food").get(0).kind() == TreasuryLedger.Kind.ACCOUNT_CREATED
                        && rationRestored.entriesForAccount("stock:settlement:one:food").get(1).kind() == TreasuryLedger.Kind.OPENING
                        && rationRestored.entriesForAccount("stock:settlement:one:food").get(2).reference().equals("ration:one")
                        && rationRestored.entriesForReference("stock:settlement:one:food", TreasuryLedger.Kind.LOGISTICS, "ration:one").size() == 1
                        && rationRestored.accountIdsWithPrefix("stock:").contains("stock:settlement:one:food"),
                "account/reference indexes and account enumeration are rebuilt on restore");
        ok(close(rationRestored.totalDebits("stock:settlement:one:food", TreasuryLedger.Kind.LOGISTICS,
                        "army-field-rations"), 3),
                "debit category aggregates are rebuilt from the durable journal");

        TreasuryLedger categoryStats = new TreasuryLedger();
        categoryStats.open("stats:account", "stats:account", "silver", 100);
        categoryStats.deposit("stats:account", 1, TreasuryLedger.Kind.UPKEEP,
                "building-upkeep:17", "stats:one", 1, "building");
        categoryStats.deposit("stats:account", 1, TreasuryLedger.Kind.UPKEEP,
                "building-upkeep:18", "stats:two", 2, "building");
        categoryStats.deposit("stats:account", 1, TreasuryLedger.Kind.TAX,
                "population-tax", "stats:three", 3, "tax");
        ok(categoryStats.topJournalStats(2).get(0).kind() == TreasuryLedger.Kind.UPKEEP
                        && categoryStats.topJournalStats(2).get(0).category().equals("building-upkeep")
                        && categoryStats.topJournalStats(2).get(0).entries() == 2
                        && TreasuryLedger.fromJson(categoryStats.toJson()).topJournalStats(1).get(0).entries() == 2,
                "journal source aggregates group per-building receipts and rebuild on restore without rescanning");

        TreasuryLedger restored=TreasuryLedger.fromJson(ledger.toJson());
        ok(close(restored.balance("realm:kingdom"),875)&&close(restored.balance("settlement:capital"),225)
                && restored.journal().size()==ledger.journal().size(),
                "balances and immutable financial journal survive save/load");
        ok(restored.journal().stream().filter(e->e.kind()==TreasuryLedger.Kind.GRANT && e.reference().equals("grant-1")).count()==2,
                "double-entry transfer has matching debit and credit records under one transaction ID");
        ok(restored.account("merchant:caravan").creditLimit==50,
                "credit terms survive save/load");

        TreasuryLedger retry = new TreasuryLedger();
        retry.open("realm:retry", "realm:retry", "silver", 100);
        retry.open("army:retry", "army:retry", "silver", 0);
        ok(retry.transferOnce("realm:retry", "army:retry", 25, TreasuryLedger.Kind.ARMY_UPKEEP,
                        "payroll-funding", "payroll-fund:retry:1", 1440, "daily army funding")
                        && retry.transferOnce("realm:retry", "army:retry", 25, TreasuryLedger.Kind.ARMY_UPKEEP,
                        "payroll-funding", "payroll-fund:retry:1", 1440, "retry after interrupted save")
                        && close(retry.balance("realm:retry"), 75) && close(retry.balance("army:retry"), 25),
                "replaying a committed army funding reference cannot transfer funds twice");
        int journalCount = retry.journal().size();
        ok(retry.withdrawOnce("army:retry", 10, TreasuryLedger.Kind.WAGE, "soldier-payroll",
                        "payroll:retry:1", 1440, "daily wages")
                        && retry.withdrawOnce("army:retry", 10, TreasuryLedger.Kind.WAGE, "soldier-payroll",
                        "payroll:retry:1", 1440, "retry after interrupted save")
                        && close(retry.balance("army:retry"), 15) && retry.journal().size() == journalCount + 1,
                "daily wage posting is idempotent and cannot pay twice after a retry");
        ok(close(retry.totalDebits("army:retry", TreasuryLedger.Kind.ARMY_UPKEEP, "payroll-funding"), 0)
                        && close(retry.totalDebits("realm:retry", TreasuryLedger.Kind.ARMY_UPKEEP, "payroll-funding"), 25),
                "journal-derived debit totals reconcile source-account tax and funding outflows");
        TreasuryLedger retryRestored = TreasuryLedger.fromJson(retry.toJson());
        int restoredCount = retryRestored.journal().size();
        ok(retryRestored.transferOnce("realm:retry", "army:retry", 25, TreasuryLedger.Kind.ARMY_UPKEEP,
                        "payroll-funding", "payroll-fund:retry:1", 1440, "retry after world reload")
                        && retryRestored.journal().size() == restoredCount,
                "idempotency keys are rebuilt from the persisted journal after world reload");

        @SuppressWarnings("unchecked")
        Map<String, Object> damaged = (Map<String, Object>) Json.parse(retry.toJson());
        List<Object> damagedJournal = new ArrayList<>((List<Object>) damaged.get("journal"));
        damagedJournal.remove(damagedJournal.size() - 2);
        damaged.put("journal", damagedJournal);
        boolean rejectedUnbalanced = false;
        try { TreasuryLedger.fromJson(Json.write(damaged)); }
        catch (IllegalArgumentException expected) { rejectedUnbalanced = true; }
        ok(rejectedUnbalanced, "ledger restore rejects a transfer whose matching debit/credit entry is missing");

        TreasuryLedger collision = new TreasuryLedger();
        collision.open("source", "source", "silver", 100);
        collision.open("destination-a", "destination-a", "silver", 0);
        collision.open("destination-b", "destination-b", "silver", 0);
        ok(collision.withdrawOnce("source", 12, TreasuryLedger.Kind.LOGISTICS,
                        "ration-feed", "ration-day-1", 50, "army food issue")
                        && collision.withdrawOnce("source", 12, TreasuryLedger.Kind.LOGISTICS,
                        "ration-feed", "ration-day-1", 50, "retry after restart")
                        && close(collision.balance("source"), 88),
                "same durable withdrawal payload is idempotent across retries");
        double beforeCollision = collision.balance("source");
        ok(!collision.withdrawOnce("source", 13, TreasuryLedger.Kind.LOGISTICS,
                        "ration-feed", "ration-day-1", 50, "changed quantity")
                        && close(collision.balance("source"), beforeCollision)
                        && collision.lastError().contains("idempotency key collision"),
                "reusing a withdrawal key with a different amount fails closed without another debit");
        ok(!collision.withdrawOnce("source", 12, TreasuryLedger.Kind.LOGISTICS,
                        "different-category", "ration-day-1", 50, "changed category")
                        && close(collision.balance("source"), beforeCollision),
                "reusing a withdrawal key with a different category fails closed");
        ok(collision.depositOnce("source", 5, TreasuryLedger.Kind.IMPORT,
                        "estate-return", "estate-1", 51, "return property")
                        && collision.depositOnce("source", 5, TreasuryLedger.Kind.IMPORT,
                        "estate-return", "estate-1", 51, "retry property")
                        && close(collision.balance("source"), 93),
                "same durable deposit payload is idempotent across retries");
        ok(!collision.depositOnce("source", 7, TreasuryLedger.Kind.IMPORT,
                        "estate-return", "estate-1", 51, "changed property value")
                        && close(collision.balance("source"), 93),
                "reusing a deposit key with different value fails closed");
        ok(collision.transferOnce("source", "destination-a", 20, TreasuryLedger.Kind.LOGISTICS,
                        "army-resupply", "supply:army-9:day-2", 2880, "field ration transfer")
                        && collision.transferOnce("source", "destination-a", 20, TreasuryLedger.Kind.LOGISTICS,
                        "army-resupply", "supply:army-9:day-2", 2880, "retry field ration transfer")
                        && close(collision.balance("source"), 73)
                        && close(collision.balance("destination-a"), 20),
                "same double-entry transfer payload is idempotent across retries");
        double sourceAfterTransfer = collision.balance("source");
        ok(!collision.transferOnce("source", "destination-b", 20, TreasuryLedger.Kind.LOGISTICS,
                        "army-resupply", "supply:army-9:day-2", 2880, "redirected destination")
                        && !collision.transferOnce("source", "destination-a", 21, TreasuryLedger.Kind.LOGISTICS,
                        "army-resupply", "supply:army-9:day-2", 2880, "changed amount")
                        && close(collision.balance("source"), sourceAfterTransfer)
                        && close(collision.balance("destination-a"), 20)
                        && close(collision.balance("destination-b"), 0),
                "reusing a transfer key with a changed destination or amount fails closed");
        TreasuryLedger collisionRestored = TreasuryLedger.fromJson(collision.toJson());
        ok(collisionRestored.transferOnce("source", "destination-a", 20, TreasuryLedger.Kind.LOGISTICS,
                        "army-resupply", "supply:army-9:day-2", 2880, "retry after reload")
                        && !collisionRestored.transferOnce("source", "destination-b", 20, TreasuryLedger.Kind.LOGISTICS,
                        "army-resupply", "supply:army-9:day-2", 2880, "collision after reload"),
                "transfer payload identity is rebuilt from persisted journal after restart");

        TreasuryApi.resetForTests();
        ok(TreasuryApi.migrateLegacyRealmBalance("legacy-positive", 25)
                        && TreasuryApi.migrateLegacyRealmBalance("legacy-positive", 25)
                        && close(TreasuryApi.netBalance("legacy-positive"), 25),
                "legacy positive army balance migrates exactly once into an existing-capable realm account");
        ok(TreasuryService.current().journal().stream().filter(e -> e.reference().equals("legacy-army-balance:legacy-positive")).count() == 1,
                "legacy positive migration is protected by the durable journal idempotency key");
        ok(TreasuryApi.migrateLegacyRealmBalance("legacy-negative", -20)
                        && TreasuryApi.migrateLegacyRealmBalance("legacy-negative", -20)
                        && close(TreasuryApi.netBalance("legacy-negative"), -20),
                "legacy negative army balance becomes treasury debt without losing the deficit");
        TreasuryApi.ensureRealmAccount("legacy-existing", 50);
        ok(TreasuryApi.migrateLegacyRealmBalance("legacy-existing", 20)
                        && close(TreasuryApi.netBalance("legacy-existing"), 70),
                "legacy army balance merges once into a realm treasury already opened by settlements");
        ok(TreasuryApi.spendOnce("legacy-positive", 5, "army-test", "army-spend-once", 10)
                        && TreasuryApi.spendOnce("legacy-positive", 5, "army-test", "army-spend-once", 10)
                        && close(TreasuryApi.netBalance("legacy-positive"), 20),
                "idempotent military spending cannot debit twice");
        TreasuryLedger checkpointLedger = new TreasuryLedger();
        checkpointLedger.open("checkpoint:realm", "realm:checkpoint", "silver", 240);
        checkpointLedger.setCreditLimit("checkpoint:realm", 80);
        checkpointLedger.depositOnce("checkpoint:realm", 35, TreasuryLedger.Kind.TAX,
                "realm-tax", "checkpoint-tax:year-4", 5760, "annual tax");
        String checkpointJson = TreasuryLedgerSnapshot.capture(checkpointLedger);
        TreasuryLedgerSnapshot.State checkpoint = TreasuryLedgerSnapshot.decode(checkpointJson);
        ok(checkpoint.nextSequence() == checkpointLedger.nextSequence() && checkpoint.nextSequence() == 5
                        && checkpoint.accounts().size() == 1
                        && close(checkpoint.accounts().get(0).balance(), 275)
                        && close(checkpoint.accounts().get(0).creditLimit(), 80)
                        && !checkpoint.legacyIdempotencyIndexes()
                        && checkpoint.postedReferences().isEmpty()
                        && checkpoint.postedDebits().isEmpty()
                        && checkpoint.postedCredits().isEmpty(),
                "treasury checkpoint captures balances, limits, sequence and idempotency indexes");
        try {
            java.nio.file.Path checkpointDir = java.nio.file.Files.createTempDirectory("maqan-treasury-checkpoint-");
            java.nio.file.Path archiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-checkpoint-archive-");
            TreasuryJournalArchive checkpointArchive = new TreasuryJournalArchive(archiveDir, 1);
            checkpointArchive.append(checkpointLedger.journal());
            TreasuryCheckpointStore checkpointStore = new TreasuryCheckpointStore(checkpointDir);
            TreasuryCheckpointStore.Checkpoint published = checkpointStore.publish(checkpointJson, checkpointArchive);
            TreasuryLedger.Entry postCheckpointEntry = new TreasuryLedger.Entry(checkpointLedger.nextSequence(), "tx:post-checkpoint",
                    "checkpoint:realm", "external", "silver", 2, false, TreasuryLedger.Kind.UPKEEP,
                    "test", "post-checkpoint", 5761, 273, "after checkpoint");
            checkpointArchive.append(List.of(postCheckpointEntry));
            TreasuryCheckpointStore.Checkpoint loaded = new TreasuryCheckpointStore(checkpointDir).load(checkpointArchive);
            ok(published.nextSequence() == loaded.nextSequence()
                            && loaded.snapshotJson().equals(checkpointJson)
                            && loaded.checksum().equals(published.checksum()),
                    "treasury checkpoint remains valid when later journal entries are appended after its verified prefix");
            boolean archiveFingerprintMismatchRejected = false;
            try {
                TreasuryLedger otherLedger = new TreasuryLedger();
                otherLedger.open("other:account", "other:account", "silver", 999);
                otherLedger.deposit("other:account", 1, TreasuryLedger.Kind.TAX,
                        "different", "different-ref", 1, "different archive");
                java.nio.file.Path otherArchiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-other-archive-");
                TreasuryJournalArchive otherArchive = new TreasuryJournalArchive(otherArchiveDir, 1);
                otherArchive.append(otherLedger.journal());
                new TreasuryCheckpointStore(checkpointDir).load(otherArchive);
                archiveFingerprintMismatchRejected = false;
                try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(otherArchiveDir)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                    });
                }
            } catch (java.io.IOException expected) {
                archiveFingerprintMismatchRejected = expected.getMessage().contains("does not match");
            }
            ok(archiveFingerprintMismatchRejected, "treasury checkpoint refuses a different archive with the same sequence boundary");
            boolean archiveBoundaryRejected = false;
            try {
                TreasuryCheckpointStore mismatchedStore = new TreasuryCheckpointStore(
                        java.nio.file.Files.createTempDirectory("maqan-treasury-checkpoint-mismatch-"));
                mismatchedStore.publish(checkpointJson, new TreasuryJournalArchive(
                        java.nio.file.Files.createTempDirectory("maqan-treasury-empty-archive-"), 1));
            } catch (java.io.IOException expected) {
                archiveBoundaryRejected = expected.getMessage().contains("snapshot requires");
            }
            ok(archiveBoundaryRejected, "treasury checkpoint refuses a snapshot ahead of the durable archive");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(checkpointDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(archiveDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "treasury checkpoint publication tests complete: " + ex.getMessage());
        }


        boolean segmentInternalGapRejected = false;
        try {
            TreasuryLedger.Entry first = new TreasuryLedger.Entry(11, "tx:11", "a", "", "silver", 1,
                    true, TreasuryLedger.Kind.TAX, "test", "eleven", 1, 1, "");
            TreasuryLedger.Entry third = new TreasuryLedger.Entry(13, "tx:13", "a", "", "silver", 1,
                    true, TreasuryLedger.Kind.TAX, "test", "thirteen", 2, 2, "");
            TreasuryJournalSegment.encode(List.of(first, third));
        } catch (IllegalArgumentException expected) { segmentInternalGapRejected = true; }
        ok(segmentInternalGapRejected, "treasury segment encoder rejects internal gaps");
        TreasuryLedger.Entry laterFirst = new TreasuryLedger.Entry(11, "tx:11", "a", "", "silver", 1,
                true, TreasuryLedger.Kind.TAX, "test", "eleven", 1, 1, "");
        TreasuryLedger.Entry laterSecond = new TreasuryLedger.Entry(12, "tx:12", "a", "", "silver", 1,
                true, TreasuryLedger.Kind.TAX, "test", "twelve", 2, 2, "");
        ok(TreasuryJournalSegment.decode(TreasuryJournalSegment.encode(List.of(laterFirst, laterSecond)))
                        .firstSequence() == 11,
                "treasury archive segments may begin after sequence one while remaining internally contiguous");

        boolean nonOneArchivePrefixVerified = false;
        try {
            java.nio.file.Path nonOneArchiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-non-one-archive-");
            TreasuryJournalArchive nonOneArchive = new TreasuryJournalArchive(nonOneArchiveDir, 11);
            nonOneArchive.append(List.of(laterFirst, laterSecond));
            nonOneArchivePrefixVerified = nonOneArchive.nextSequence() == 13
                    && nonOneArchive.archiveDigestThrough(13).matches("[0-9a-f]{64}");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(nonOneArchiveDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            nonOneArchivePrefixVerified = false;
        }
        ok(nonOneArchivePrefixVerified,
                "treasury archive checkpoint fingerprints honor a configured non-one starting sequence");
        boolean checkpointBeforeRetainedRangeRejected = false;
        try {
            java.nio.file.Path nonOneArchiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-before-retained-");
            TreasuryJournalArchive nonOneArchive = new TreasuryJournalArchive(nonOneArchiveDir, 11);
            nonOneArchive.archiveDigestThrough(10);
        } catch (java.io.IOException expected) {
            checkpointBeforeRetainedRangeRejected = expected.getMessage().contains("retained range");
        }
        ok(checkpointBeforeRetainedRangeRejected,
                "treasury archive rejects checkpoint boundaries before the retained history starts");

        boolean checkpointTamperRejected = false;
        String changedChecksum = checkpointJson.substring(0, checkpointJson.length() - 3)
                + (checkpointJson.charAt(checkpointJson.length() - 3) == '0' ? '1' : '0')
                + checkpointJson.substring(checkpointJson.length() - 2);
        try { TreasuryLedgerSnapshot.decode(changedChecksum); }
        catch (IllegalArgumentException expected) { checkpointTamperRejected = expected.getMessage().contains("checksum"); }
        ok(checkpointTamperRejected, "treasury checkpoint rejects altered payloads");
        boolean inconsistentBankruptcyRejected = false;
        try {
            TreasuryLedgerSnapshot.encode(new TreasuryLedgerSnapshot.State(1, 1, 0,
                    List.of(new TreasuryLedgerSnapshot.AccountState("bad", "bad", "silver",
                            0, 10, 20, false)), Set.of(), Set.of(), Set.of(), Set.of()));
        } catch (IllegalArgumentException expected) { inconsistentBankruptcyRejected = true; }
        ok(inconsistentBankruptcyRejected, "treasury checkpoint rejects inconsistent account solvency flags");

        TreasuryLedger segmentSource = new TreasuryLedger();
        segmentSource.open("segment:source", "segment:source", "silver", 100);
        segmentSource.open("segment:target", "segment:target", "silver", 0);
        segmentSource.transfer("segment:source", "segment:target", 17, TreasuryLedger.Kind.TRADE,
                "archive-test", "segment-transfer-1", 100, "archived transfer");
        String segmentJson = TreasuryJournalSegment.encode(segmentSource.journal());
        TreasuryJournalSegment.Decoded decodedSegment = TreasuryJournalSegment.decode(segmentJson);
        ok(decodedSegment.entries().equals(segmentSource.journal())
                        && decodedSegment.firstSequence() == 1
                        && decodedSegment.lastSequence() == segmentSource.journal().get(segmentSource.journalSize() - 1).sequence()
                        && decodedSegment.entries().stream().filter(e -> e.kind() == TreasuryLedger.Kind.TRADE)
                                .map(TreasuryLedger.Entry::transactionId).distinct().count() == 1,
                "checksummed treasury segment round-trips structural events and financial double-entry postings");
        boolean checksumRejected = false;
        try {
            TreasuryJournalSegment.decode(segmentJson.replace("archived transfer", "altered transfer"));
        } catch (IllegalArgumentException expected) { checksumRejected = expected.getMessage().contains("checksum"); }
        ok(checksumRejected, "treasury segment rejects payload tampering before archive replay");
        boolean emptySegmentRejected = false;
        try { TreasuryJournalSegment.encode(List.of()); }
        catch (IllegalArgumentException expected) { emptySegmentRejected = true; }
        ok(emptySegmentRejected, "treasury segment rejects empty archive ranges");
        boolean sequenceGapRejected = false;
        try {
            TreasuryJournalSegment.decode(segmentJson.replace((char) 34 + "lastSequence" + (char) 34 + ":" + decodedSegment.lastSequence(),
                    (char) 34 + "lastSequence" + (char) 34 + ":" + (decodedSegment.lastSequence() + 1)));
        } catch (IllegalArgumentException expected) { sequenceGapRejected = true; }
        ok(sequenceGapRejected, "treasury segment rejects altered sequence bounds");

        try {
            java.nio.file.Path archiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-archive-");
            TreasuryJournalArchive archive = new TreasuryJournalArchive(archiveDir, 1);
            ok(archive.append(segmentSource.journal()) && archive.nextSequence() == segmentSource.nextSequence(),
                    "treasury archive durably publishes a contiguous segment and advances its checkpoint");
            ok(archive.append(segmentSource.journal()) && archive.readAll().size() == segmentSource.journalSize(),
                    "treasury archive accepts an identical retry without duplicating history");
            java.nio.file.Files.writeString(archiveDir.resolve(".treasury-segment-crash.tmp"), "{\"format\":");
            TreasuryJournalArchive reopenedArchive = new TreasuryJournalArchive(archiveDir, 1);
            ok(reopenedArchive.nextSequence() == segmentSource.nextSequence()
                            && reopenedArchive.readAll().equals(segmentSource.journal()),
                    "treasury archive restart ignores an unpublished temporary segment left by a crash");
            java.util.List<TreasuryLedger.Entry> suffixEntries = new java.util.ArrayList<>();
            reopenedArchive.forEachEntryFrom(2, suffixEntries::add);
            ok(suffixEntries.equals(segmentSource.journal().subList(1, segmentSource.journalSize())),
                    "treasury archive streams an exact suffix from a checkpoint without materializing its prefix");
            java.util.List<TreasuryLedger.Entry> firstPage = reopenedArchive.readPage(1, 2);
            java.util.List<TreasuryLedger.Entry> secondPage = reopenedArchive.readPage(firstPage.get(1).sequence() + 1, 2);
            java.util.List<TreasuryLedger.Entry> thirdPage = reopenedArchive.readPage(secondPage.get(1).sequence() + 1, 2);
            ok(firstPage.size() == 2 && secondPage.size() == 2 && thirdPage.size() == 1
                            && firstPage.get(0).sequence() == 1 && firstPage.get(1).sequence() == 2
                            && secondPage.get(0).sequence() == 3 && secondPage.get(1).sequence() == 4
                            && thirdPage.get(0).sequence() == 5,
                    "treasury archive exposes bounded sequence pages without loading the full journal");
            TreasuryJournalArchive.AccountPage accountPage = reopenedArchive.readAccountPage(
                    "segment:source", 1, 1);
            ok(accountPage.entries().size() == 1
                            && accountPage.entries().get(0).accountId().equals("segment:source")
                            && accountPage.nextSequence() > accountPage.entries().get(0).sequence(),
                    "treasury archive pages account history by global cursor without a growing account index");
            boolean oversizedPageRejected = false;
            try { reopenedArchive.readPage(1, 4097); }
            catch (IllegalArgumentException expected) { oversizedPageRejected = true; }
            ok(oversizedPageRejected, "treasury archive enforces a hard upper bound on page memory");
            java.util.List<TreasuryLedger.Entry> emptySuffix = new java.util.ArrayList<>();
            reopenedArchive.forEachEntryFrom(reopenedArchive.nextSequence(), emptySuffix::add);
            ok(emptySuffix.isEmpty(), "treasury archive accepts a suffix starting immediately after its last entry");
            boolean gapRejected = false;
            try { reopenedArchive.append(List.of(new TreasuryLedger.Entry(reopenedArchive.nextSequence() + 1, "tx:gap", "x", "y",
                    "silver", 1, false, TreasuryLedger.Kind.TRADE, "test", "gap", 1, 0, ""))); }
            catch (java.io.IOException expected) { gapRejected = expected.getMessage().contains("expected sequence"); }
            ok(gapRejected, "treasury archive refuses to publish a sequence gap");
            java.nio.file.Path segmentFile;
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(archiveDir)) {
                segmentFile = files.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
            }
            String originalSegment = java.nio.file.Files.readString(segmentFile);
            java.nio.file.Files.writeString(segmentFile, originalSegment.replace("archived transfer", "tampered"));
            boolean corruptArchiveRejected = false;
            try { new TreasuryJournalArchive(archiveDir, 1); }
            catch (java.io.IOException expected) { corruptArchiveRejected = true; }
            ok(corruptArchiveRejected, "treasury archive refuses startup when a persisted segment is corrupted");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(archiveDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "treasury archive filesystem durability tests complete: " + ex.getMessage());
        }

        try {
            java.nio.file.Path batchedArchiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-batched-archive-");
            TreasuryJournalArchive batchedArchive = new TreasuryJournalArchive(batchedArchiveDir, 1);
            java.util.List<TreasuryLedger.Entry> largeBatch = new java.util.ArrayList<>(TreasuryJournalSegment.MAX_ENTRIES + 1);
            for (int i = 1; i <= TreasuryJournalSegment.MAX_ENTRIES + 1; i++) {
                largeBatch.add(new TreasuryLedger.Entry(i, "tx:batch:" + i, "batch:account", "external",
                        "silver", 1, true, TreasuryLedger.Kind.PRODUCTION, "batch", "batch:" + i,
                        i, i, "bounded archive batch"));
            }
            long archivedEntries = batchedArchive.appendAll(largeBatch);
            ok(archivedEntries == largeBatch.size()
                            && batchedArchive.nextSequence() == (long) largeBatch.size() + 1
                            && batchedArchive.readAll().size() == largeBatch.size(),
                    "large treasury journal batches are split into bounded contiguous archive segments");
            long[] suffixCount = {0};
            batchedArchive.forEachEntryFrom(TreasuryJournalSegment.MAX_ENTRIES, ignored -> suffixCount[0]++);
            ok(suffixCount[0] == 2,
                    "batched treasury archive resumes from an exact sequence across a segment boundary");
            long retriedEntries = batchedArchive.appendAll(largeBatch);
            ok(retriedEntries == largeBatch.size()
                            && batchedArchive.nextSequence() == (long) largeBatch.size() + 1,
                    "retrying a partially or fully published large treasury batch is idempotent");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(batchedArchiveDir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "bounded treasury archive batching tests complete: " + ex.getMessage());
        }

        try {
            java.nio.file.Path migrationRoot = java.nio.file.Files.createTempDirectory("maqan-treasury-migration-");
            TreasuryLedger migrationLedger = new TreasuryLedger();
            migrationLedger.open("migration:treasury", "migration:realm", "silver", 500);
            migrationLedger.open("migration:merchant", "migration:merchant", "silver", 20);
            migrationLedger.open("migration:empty", "migration:empty", "silver", 0);
            migrationLedger.open("migration:borrower", "migration:borrower", "silver", 0);
            boolean borrowerStateApplied = migrationLedger.setCreditLimit("migration:borrower", 105)
                    && migrationLedger.borrow("migration:borrower", 100, 40, "migration:loan")
                    && migrationLedger.accrueInterest("migration:borrower", 0.10, 41, "migration:interest")
                    && migrationLedger.repayDebt("migration:borrower", 25, 42, "migration:repayment");
            boolean structuralEventsDurable = migrationLedger.journal().stream().anyMatch(e ->
                            e.kind() == TreasuryLedger.Kind.ACCOUNT_CREATED && e.accountId().equals("migration:empty")
                                    && e.amount() == 0)
                    && migrationLedger.journal().stream().anyMatch(e ->
                            e.kind() == TreasuryLedger.Kind.CREDIT_LIMIT_CHANGED
                                    && e.accountId().equals("migration:borrower") && e.memo().equals("105.0"));
            ok(structuralEventsDurable,
                    "zero-balance account creation and credit-limit changes are represented as durable zero-value journal events");
            ok(borrowerStateApplied, "migration fixture creates credit, interest and repayment state");
            migrationLedger.transfer("migration:treasury", "migration:merchant", 75,
                    TreasuryLedger.Kind.TRADE, "migration-test", "migration:transfer", 43, "migration transfer");
            TreasuryJournalArchive migrationArchive = new TreasuryJournalArchive(migrationRoot.resolve("archive"), 1);
            TreasuryCheckpointStore migrationCheckpoints = new TreasuryCheckpointStore(migrationRoot.resolve("checkpoints"));
            TreasuryArchiveMigration.Result migration = TreasuryArchiveMigration.migrate(
                    migrationLedger, migrationArchive, migrationCheckpoints);
            TreasuryArchiveMigration.Result migrationRetry = TreasuryArchiveMigration.migrate(
                    migrationLedger, migrationArchive, migrationCheckpoints);
            TreasuryCheckpointStore.Checkpoint restoredCheckpoint = migrationCheckpoints.load(migrationArchive);
            TreasuryLedgerSnapshot.State restoredState = TreasuryLedgerSnapshot.decode(restoredCheckpoint.snapshotJson());
            TreasuryLedger restoredLedger = TreasuryLedger.restoreFromCheckpoint(restoredCheckpoint, migrationArchive);
            ok(restoredLedger.pendingJournalSize() == 0
                            && restoredLedger.journalSize() == migrationLedger.journalSize()
                            && restoredLedger.entriesForAccount("migration:treasury").size() > 0,
                    "checkpoint restore verifies the full archive without retaining prefix or suffix entries in the live heap");
            ok(migration.nextSequence() == migrationLedger.nextSequence()
                            && migration.archivedEntries() == migrationLedger.journalSize()
                            && migrationArchive.nextSequence() == migrationLedger.nextSequence()
                            && migrationRetry.nextSequence() == migration.nextSequence()
                            && restoredState.accounts().size() == migrationLedger.accountCount(),
                    "legacy treasury migration archives the complete journal and safely retries checkpoint publication");
            ok(close(restoredState.accounts().stream().filter(a -> a.id().equals("migration:treasury"))
                            .findFirst().orElseThrow().balance(), migrationLedger.balance("migration:treasury"))
                            && close(restoredState.accounts().stream().filter(a -> a.id().equals("migration:merchant"))
                            .findFirst().orElseThrow().balance(), migrationLedger.balance("migration:merchant")),
                    "migrated treasury checkpoint preserves both sides of a double-entry transfer");
            ok(restoredLedger.nextSequence() == migrationLedger.nextSequence()
                            && restoredLedger.journalSize() == migrationLedger.journalSize()
                            && close(restoredLedger.balance("migration:treasury"), migrationLedger.balance("migration:treasury"))
                            && close(restoredLedger.balance("migration:merchant"), migrationLedger.balance("migration:merchant"))
                            && restoredLedger.hasTransaction("migration:treasury", TreasuryLedger.Kind.TRADE, "migration:transfer")
                            && restoredLedger.hasDebit("migration:treasury", TreasuryLedger.Kind.TRADE, "migration:transfer")
                            && restoredLedger.hasCredit("migration:merchant", TreasuryLedger.Kind.TRADE, "migration:transfer")
                            && close(restoredLedger.totalDebits("migration:treasury", TreasuryLedger.Kind.TRADE, "migration-test"),
                                    migrationLedger.totalDebits("migration:treasury", TreasuryLedger.Kind.TRADE, "migration-test")),
                    "checkpoint restore rebuilds balances, journal indexes, debit totals and idempotency keys");
            double treasuryBeforeRetry = restoredLedger.balance("migration:treasury");
            double merchantBeforeRetry = restoredLedger.balance("migration:merchant");
            boolean sameTransferRetry = restoredLedger.transferOnce("migration:treasury", "migration:merchant", 75,
                    TreasuryLedger.Kind.TRADE, "migration-test", "migration:transfer", 99, "replayed migration transfer");
            boolean alteredTransferRejected = !restoredLedger.transferOnce("migration:treasury", "migration:merchant", 76,
                    TreasuryLedger.Kind.TRADE, "migration-test", "migration:transfer", 100, "conflicting replay");
            ok(sameTransferRetry && alteredTransferRejected
                            && close(restoredLedger.balance("migration:treasury"), treasuryBeforeRetry)
                            && close(restoredLedger.balance("migration:merchant"), merchantBeforeRetry)
                            && restoredLedger.journalSize() == migrationLedger.journalSize(),
                    "restored treasury treats an identical transfer retry as success and rejects changed payload without reposting");
            ok(restoredLedger.journal().stream().anyMatch(e ->
                            e.kind() == TreasuryLedger.Kind.ACCOUNT_CREATED && e.accountId().equals("migration:empty"))
                            && restoredLedger.journal().stream().anyMatch(e ->
                            e.kind() == TreasuryLedger.Kind.CREDIT_LIMIT_CHANGED
                                    && e.accountId().equals("migration:borrower") && e.memo().equals("105.0")),
                    "checkpoint archive retains structural account events alongside financial postings");
            TreasuryLedger.Account restoredBorrower = restoredLedger.account("migration:borrower");
            TreasuryLedger.Account originalBorrower = migrationLedger.account("migration:borrower");
            ok(restoredLedger.hasAccount("migration:empty")
                            && close(restoredLedger.balance("migration:empty"), 0)
                            && close(restoredBorrower.balance, originalBorrower.balance)
                            && close(restoredBorrower.creditLimit, originalBorrower.creditLimit)
                            && close(restoredBorrower.debt, originalBorrower.debt)
                            && restoredBorrower.bankrupt == originalBorrower.bankrupt
                            && close(restoredBorrower.debt, 85)
                            && close(restoredBorrower.balance, 75)
                            && !restoredBorrower.bankrupt,
                    "checkpoint restore preserves empty accounts, credit limits, loan, interest, repayment and solvency");
            boolean rejectedSuffix = false;
            try {
                restoredLedger = TreasuryLedger.restoreFromCheckpoint(restoredCheckpoint,
                        new TreasuryJournalArchive(migrationRoot.resolve("empty-suffix"), 2));
            } catch (java.io.IOException expectedFailure) { rejectedSuffix = true; }
            ok(rejectedSuffix, "checkpoint restore fails closed when the archive does not contain its full prefix");
            boolean rejectedDifferentArchive = false;
            try {
                java.nio.file.Path alternateArchiveDir = migrationRoot.resolve("alternate-archive");
                TreasuryJournalArchive alternateArchive = new TreasuryJournalArchive(alternateArchiveDir, 1);
                java.util.List<TreasuryLedger.Entry> alteredHistory = new java.util.ArrayList<>(migrationLedger.journal());
                TreasuryLedger.Entry originalFirst = alteredHistory.get(0);
                alteredHistory.set(0, new TreasuryLedger.Entry(originalFirst.sequence(), originalFirst.transactionId(),
                        originalFirst.accountId(), originalFirst.counterpartyId(), originalFirst.currency(),
                        originalFirst.amount(), originalFirst.credit(), originalFirst.kind(), originalFirst.category(),
                        originalFirst.reference(), originalFirst.worldMinute(), originalFirst.balanceAfter(),
                        "different archive history"));
                alternateArchive.appendAll(alteredHistory);
                TreasuryLedger.restoreFromCheckpoint(restoredCheckpoint, alternateArchive);
            } catch (java.io.IOException expectedFailure) { rejectedDifferentArchive = true; }
            ok(rejectedDifferentArchive,
                    "checkpoint restore refuses a different archive with the same sequence boundary");
            java.nio.file.Path replayRoot = java.nio.file.Files.createTempDirectory("maqan-treasury-suffix-replay-");
            TreasuryLedger replaySource = new TreasuryLedger();
            replaySource.open("replay:treasury", "replay:treasury", "silver", 1000);
            replaySource.open("replay:debtor", "replay:debtor", "silver", 0);
            TreasuryJournalArchive replayArchive = new TreasuryJournalArchive(replayRoot.resolve("archive"), 1);
            replayArchive.appendAll(replaySource.journal());
            TreasuryCheckpointStore replayCheckpoints = new TreasuryCheckpointStore(replayRoot.resolve("checkpoints"));
            TreasuryCheckpointStore.Checkpoint replayCheckpoint = replayCheckpoints.publish(
                    TreasuryLedgerSnapshot.capture(replaySource), replayArchive);
            long replayCursor = replaySource.nextSequence();
            replaySource.open("replay:empty", "replay:empty", "silver", 0);
            replaySource.open("replay:funded", "replay:funded", "silver", 50);
            boolean replayMutationsApplied = replaySource.setCreditLimit("replay:debtor", 200)
                    && replaySource.borrow("replay:debtor", 80, 10, "replay:loan")
                    && replaySource.accrueInterest("replay:debtor", 0.10, 11, "replay:interest")
                    && replaySource.repayDebt("replay:debtor", 20, 12, "replay:repayment")
                    && replaySource.transfer("replay:treasury", "replay:debtor", 100,
                            TreasuryLedger.Kind.TRADE, "replay-test", "replay:transfer", 13, "suffix transfer");
            ok(replayMutationsApplied, "treasury suffix fixture applies account, credit, debt and transfer mutations");
            replayArchive.appendAll(replaySource.entriesFromSequence(replayCursor));
            TreasuryCheckpointStore.Checkpoint replayLoaded = replayCheckpoints.load(replayArchive);
            TreasuryLedger replayed = TreasuryLedger.restoreFromCheckpoint(replayLoaded, replayArchive);
            ok(replayed.pendingJournalSize() == 0
                            && replayed.journalSize() == replaySource.journalSize()
                            && replayed.entriesForAccount("replay:treasury").size()
                                    == replaySource.entriesForAccount("replay:treasury").size(),
                    "checkpoint suffix replay reconstructs state and indexes without retaining archived suffix entries");
            boolean replayAccountsMatch = replayed.accountCount() == replaySource.accountCount()
                    && replayed.hasAccount("replay:empty") && replayed.hasAccount("replay:funded")
                    && close(replayed.balance("replay:treasury"), replaySource.balance("replay:treasury"))
                    && close(replayed.balance("replay:debtor"), replaySource.balance("replay:debtor"))
                    && close(replayed.balance("replay:funded"), replaySource.balance("replay:funded"));
            TreasuryLedger.Account replayedDebtor = replayed.account("replay:debtor");
            TreasuryLedger.Account sourceDebtor = replaySource.account("replay:debtor");
            boolean replayDebtMatches = close(replayedDebtor.creditLimit, sourceDebtor.creditLimit)
                    && close(replayedDebtor.debt, sourceDebtor.debt)
                    && replayedDebtor.bankrupt == sourceDebtor.bankrupt
                    && close(replayedDebtor.debt, 0)
                    && close(replayedDebtor.balance, 92);
            ok(replayAccountsMatch && replayDebtMatches
                            && replayed.nextSequence() == replaySource.nextSequence()
                            && replayed.journalSize() == replaySource.journalSize()
                            && replayed.mutationVersion() == replaySource.mutationVersion()
                            && replayed.hasTransaction("replay:treasury", TreasuryLedger.Kind.TRADE, "replay:transfer")
                            && replayed.hasDebit("replay:treasury", TreasuryLedger.Kind.TRADE, "replay:transfer")
                            && replayed.hasCredit("replay:debtor", TreasuryLedger.Kind.TRADE, "replay:transfer")
                            && close(replayed.totalDebits("replay:treasury", TreasuryLedger.Kind.TRADE, "replay-test"),
                                    replaySource.totalDebits("replay:treasury", TreasuryLedger.Kind.TRADE, "replay-test")),
                    "checkpoint restore replays suffix account creation, opening cash, credit limits, debt and transfer indexes");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(replayRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(migrationRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "legacy treasury archive migration completes and verifies: " + ex.getMessage());
        }

        try {
            java.nio.file.Path digestRoot = java.nio.file.Files.createTempDirectory("maqan-treasury-digest-cache-");
            TreasuryLedger digestLedger = new TreasuryLedger();
            digestLedger.open("digest:account", "digest:account", "silver", 100);
            digestLedger.deposit("digest:account", 5, TreasuryLedger.Kind.PRODUCTION,
                    "digest-test", "digest:first", 1, "first immutable prefix");
            TreasuryJournalArchive digestArchive = new TreasuryJournalArchive(digestRoot.resolve("archive"), 1);
            digestArchive.appendAll(digestLedger.journal());
            long immutablePrefixNext = digestArchive.nextSequence();
            String digestFirst = digestArchive.archiveDigestThrough(immutablePrefixNext);
            String digestRepeated = digestArchive.archiveDigestThrough(immutablePrefixNext);
            digestLedger.deposit("digest:account", 7, TreasuryLedger.Kind.PRODUCTION,
                    "digest-test", "digest:second", 2, "new append-only suffix");
            digestArchive.appendAll(digestLedger.entriesFromSequence(immutablePrefixNext));
            String digestOldPrefixAfterAppend = digestArchive.archiveDigestThrough(immutablePrefixNext);
            String digestFullArchive = digestArchive.archiveDigestThrough(digestArchive.nextSequence());
            ok(digestFirst.equals(digestRepeated) && digestFirst.equals(digestOldPrefixAfterAppend)
                            && !digestFirst.equals(digestFullArchive),
                    "treasury archive digest cache reuses immutable prefixes and excludes later appended history");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(digestRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                    try { java.nio.file.Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "treasury immutable digest cache lifecycle: " + ex.getMessage());
        }

        try {
            java.nio.file.Path storeRoot = java.nio.file.Files.createTempDirectory("maqan-treasury-canonical-store-");
            java.nio.file.Path legacyFile = storeRoot.resolve("wf_treasury.json");
            TreasuryLedger legacySource = new TreasuryLedger();
            legacySource.open("store:treasury", "store:realm", "silver", 250);
            legacySource.open("store:empty", "store:empty", "silver", 0);
            String legacyBytes = legacySource.toJson();
            java.nio.file.Files.writeString(legacyFile, legacyBytes);
            TreasuryArchiveStore canonicalStore = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"));
            TreasuryLedger migrated = canonicalStore.loadOrMigrate(legacyFile);
            ok(migrated.accountCount() == 2
                            && close(migrated.balance("store:treasury"), 250)
                            && java.nio.file.Files.readString(legacyFile).equals(legacyBytes)
                            && java.nio.file.Files.isRegularFile(storeRoot.resolve("treasury-store/checkpoints/CURRENT.json")),
                    "canonical treasury store migrates legacy JSON once and leaves the source untouched");
            ok(java.nio.file.Files.isRegularFile(storeRoot.resolve("treasury-store/LEGACY_RECONCILED")),
                    "canonical treasury store records the reconciled legacy backup fingerprint");
            migrated.open("store:later", "store:later", "silver", 40);
            String newerLegacyBytes = migrated.toJson();
            java.nio.file.Files.writeString(legacyFile, newerLegacyBytes);
            java.nio.file.Files.setLastModifiedTime(legacyFile,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));
            TreasuryArchiveStore reopenedStore = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"));
            TreasuryLedger reopenedLedger = reopenedStore.loadOrMigrate(legacyFile);
            ok(reopenedLedger.hasAccount("store:later")
                            && close(reopenedLedger.balance("store:later"), 40)
                            && reopenedLedger.accountCount() == 3
                            && java.nio.file.Files.readString(legacyFile).equals(newerLegacyBytes),
                    "canonical treasury startup imports a newer legacy suffix after an interrupted shadow save");
            reopenedLedger.open("store:final", "store:final", "silver", 5);
            long committedVersion = reopenedStore.save(reopenedLedger);
            boolean tailReleased = reopenedLedger.pendingJournalSize() == 0
                    && reopenedLedger.journalSize() > 0
                    && reopenedLedger.entriesForAccount("store:final").stream()
                            .anyMatch(entry -> entry.kind() == TreasuryLedger.Kind.OPENING);
            ok(tailReleased,
                    "verified treasury save releases the in-memory tail while archive-backed account history remains readable");
            reopenedLedger.open("store:later-again", "store:later-again", "silver", 12);
            boolean newTailOnly = reopenedLedger.pendingJournalSize() == 2
                    && reopenedLedger.entriesFromSequence(reopenedStore.nextSequence()).size() == 2;
            ok(newTailOnly,
                    "post-checkpoint postings form a bounded tail beginning at the archive's exclusive sequence");
            long secondCommittedVersion = reopenedStore.save(reopenedLedger);
            ok(reopenedLedger.pendingJournalSize() == 0
                            && reopenedLedger.entriesForAccount("store:later-again").size() == 2,
                    "second checkpoint archives only the new tail and keeps archive queries complete");
            TreasuryLedger finalReload = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"))
                    .loadOrMigrate(legacyFile);
            ok(finalReload.entriesForReference("store:final", TreasuryLedger.Kind.OPENING, "store:final").size() == 1
                            && finalReload.entriesForReference("store:later-again", TreasuryLedger.Kind.OPENING,
                                    "store:later-again").size() == 1,
                    "disk-backed treasury reference index returns exact historical postings after restart");
            ok(finalReload.hasTransaction("store:final", TreasuryLedger.Kind.OPENING, "store:final")
                            && finalReload.hasTransaction("store:later-again", TreasuryLedger.Kind.OPENING,
                                    "store:later-again"),
                    "archived opening references remain protected against duplicate transaction retries after restart");
            java.nio.file.Path referenceIndexCursor = storeRoot.resolve(
                    "treasury-store/archive/reference-index/CURRENT");
            java.nio.file.Files.writeString(referenceIndexCursor, "stale-index");
            TreasuryLedger rebuiltReferenceIndex = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"))
                    .loadOrMigrate(legacyFile);
            ok(rebuiltReferenceIndex.entriesForReference("store:final", TreasuryLedger.Kind.OPENING,
                            "store:final").size() == 1
                            && java.nio.file.Files.readString(referenceIndexCursor).trim()
                                    .equals(Long.toString(rebuiltReferenceIndex.nextSequence())),
                    "damaged treasury reference-index cursor is rebuilt from the verified canonical archive");
            java.nio.file.Path referenceIndexDirectory = referenceIndexCursor.getParent();
            try (java.util.stream.Stream<java.nio.file.Path> buckets = java.nio.file.Files.list(referenceIndexDirectory)) {
                java.nio.file.Path bucketToDelete = buckets.filter(path -> path.getFileName().toString().endsWith(".bin"))
                        .findFirst().orElseThrow();
                java.nio.file.Files.delete(bucketToDelete);
            }
            TreasuryLedger rebuiltMissingBucket = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"))
                    .loadOrMigrate(legacyFile);
            ok(rebuiltMissingBucket.entriesForReference("store:final", TreasuryLedger.Kind.OPENING,
                            "store:final").size() == 1
                            && rebuiltMissingBucket.entriesForReference("store:later-again", TreasuryLedger.Kind.OPENING,
                                    "store:later-again").size() == 1,
                    "reference index rebuilds when a complete bucket is missing despite a valid cursor");
            ok(secondCommittedVersion == finalReload.mutationVersion()
                            && finalReload.hasAccount("store:final")
                            && close(finalReload.balance("store:final"), 5),
                    "canonical treasury save appends only new entries and atomically publishes a verified checkpoint"
                            + " (savedVersion=" + committedVersion + ", reloadVersion=" + finalReload.mutationVersion()
                            + ", hasFinal=" + finalReload.hasAccount("store:final")
                            + ", finalBalance=" + finalReload.balance("store:final")
                            + ", archiveNext=" + new TreasuryArchiveStore(storeRoot.resolve("treasury-store")).nextSequence()
                            + ")");
            java.nio.file.Path checkpointDir = storeRoot.resolve("treasury-store/checkpoints");
            java.nio.file.Path currentManifest = checkpointDir.resolve("CURRENT.json");
            Object manifestObject = Json.parse(java.nio.file.Files.readString(currentManifest));
            String snapshotName = String.valueOf(((Map<?, ?>) manifestObject).get("snapshot"));
            java.nio.file.Path newestSnapshot = checkpointDir.resolve(snapshotName);
            String validNewestSnapshot = java.nio.file.Files.readString(newestSnapshot);
            java.nio.file.Files.writeString(newestSnapshot, "{ truncated snapshot");
            TreasuryLedger recoveredPrevious = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"))
                    .loadOrMigrate(legacyFile);
            ok(recoveredPrevious.hasAccount("store:final")
                            && close(recoveredPrevious.balance("store:final"), 5)
                            && recoveredPrevious.mutationVersion() == secondCommittedVersion,
                    "treasury checkpoint recovery uses PREVIOUS and replays the journal suffix after snapshot damage"
                            + " (hasFinal=" + recoveredPrevious.hasAccount("store:final")
                            + ", finalBalance=" + recoveredPrevious.balance("store:final")
                            + ", recoveredVersion=" + recoveredPrevious.mutationVersion()
                            + ", expectedVersion=" + secondCommittedVersion
                            + ", archiveNext=" + new TreasuryArchiveStore(storeRoot.resolve("treasury-store")).nextSequence()
                            + ")");
            java.nio.file.Files.writeString(newestSnapshot, validNewestSnapshot);
            java.nio.file.Files.writeString(currentManifest, "{ interrupted");
            TreasuryLedger recoveredPointer = new TreasuryArchiveStore(storeRoot.resolve("treasury-store"))
                    .loadOrMigrate(legacyFile);
            ok(recoveredPointer.hasAccount("store:final")
                            && close(recoveredPointer.balance("store:final"), 5)
                            && !java.nio.file.Files.readString(currentManifest).equals("{ interrupted"),
                    "treasury checkpoint recovery repairs a damaged CURRENT pointer from PREVIOUS");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(storeRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                    try { java.nio.file.Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "canonical treasury archive store lifecycle: " + ex.getMessage());
        }

        try {
            java.nio.file.Path incrementalRoot = java.nio.file.Files.createTempDirectory("maqan-treasury-incremental-");
            TreasuryArchiveStore incrementalStore = new TreasuryArchiveStore(incrementalRoot);
            TreasuryLedger incrementalLedger = incrementalStore.loadOrMigrate(null);
            incrementalLedger.open("incremental:realm", "realm:incremental", "silver", 100);
            incrementalStore.appendPending(incrementalLedger);
            java.nio.file.Path checkpointManifest = incrementalRoot.resolve("checkpoints/CURRENT.json");
            String checkpointBeforePosting = java.nio.file.Files.readString(checkpointManifest);
            incrementalLedger.deposit("incremental:realm", 25, TreasuryLedger.Kind.TAX,
                    "tax", "incremental-tax-1", 60, "incremental durable journal");
            long appendedVersion = incrementalStore.appendPending(incrementalLedger);
            boolean journalOnly = incrementalLedger.pendingJournalSize() == 0
                    && checkpointBeforePosting.equals(java.nio.file.Files.readString(checkpointManifest));
            TreasuryLedger recoveredIncremental = new TreasuryArchiveStore(incrementalRoot)
                    .loadOrMigrate(null);
            ok(journalOnly && appendedVersion == incrementalLedger.mutationVersion()
                            && close(recoveredIncremental.balance("incremental:realm"), 125)
                            && recoveredIncremental.entriesForReference("incremental:realm",
                                    TreasuryLedger.Kind.TAX, "incremental-tax-1").size() == 1,
                    "durable journal append is independently recoverable without a synchronous full checkpoint");
            long checkpointVersion = incrementalStore.checkpoint(incrementalLedger);
            TreasuryLedger checkpointedIncremental = new TreasuryArchiveStore(incrementalRoot)
                    .loadOrMigrate(null);
            ok(checkpointVersion == incrementalLedger.mutationVersion()
                            && checkpointedIncremental.pendingJournalSize() == 0
                            && close(checkpointedIncremental.balance("incremental:realm"), 125),
                    "background-style checkpoint compacts only after publishing a verified snapshot");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(incrementalRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                    try { java.nio.file.Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "incremental treasury journal/checkpoint lifecycle: " + ex.getMessage());
        }

        System.out.println(failures==0?"ALL OK":failures+" FAILED");
        if(failures>0) System.exit(1);
    }
}
