package dev.worldforge.tests;

import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.economy.TreasuryJournalSegment;
import dev.worldforge.mod.economy.TreasuryJournalArchive;
import dev.worldforge.mod.economy.TreasuryLedgerSnapshot;
import dev.worldforge.mod.economy.TreasuryCheckpointStore;
import dev.worldforge.mod.economy.TreasuryService;
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
        StringWriter versionedSnapshot = new StringWriter();
        try {
            long writtenVersion = revisionLedger.writeJsonVersioned(versionedSnapshot);
            ok(writtenVersion == versionAfterLimit
                    && TreasuryLedger.fromJson(versionedSnapshot.toString()).accountCount() == 1,
                    "versioned streaming snapshot returns the exact revision represented by its bytes");
        } catch (java.io.IOException ex) { ok(false, "versioned streaming snapshot completes"); }
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

        ok(rationIndex.entriesForAccount("stock:settlement:one:food").size() == 2
                        && rationIndex.entriesForAccount("stock:settlement:one:food").get(0).kind() == TreasuryLedger.Kind.OPENING
                        && rationIndex.entriesForAccount("stock:settlement:one:food").get(1).reference().equals("ration:one")
                        && rationIndex.entriesForReference("stock:settlement:one:food", TreasuryLedger.Kind.LOGISTICS, "ration:one").size() == 1
                        && rationIndex.hasEntries("stock:settlement:one:food"),
                "account and reference journal indexes are populated on append");
        ok(rationRestored.entriesForAccount("stock:settlement:one:food").size() == 2
                        && rationRestored.entriesForAccount("stock:settlement:one:food").get(0).kind() == TreasuryLedger.Kind.OPENING
                        && rationRestored.entriesForAccount("stock:settlement:one:food").get(1).reference().equals("ration:one")
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
        ok(checkpoint.nextSequence() == 3 && checkpoint.accounts().size() == 1
                        && close(checkpoint.accounts().get(0).balance(), 275)
                        && close(checkpoint.accounts().get(0).creditLimit(), 80)
                        && checkpoint.postedReferences().containsAll(checkpoint.postedCredits())
                        && checkpoint.postedReferences().containsAll(checkpoint.postedDebits()),
                "treasury checkpoint captures balances, limits, sequence and idempotency indexes");
        try {
            java.nio.file.Path checkpointDir = java.nio.file.Files.createTempDirectory("maqan-treasury-checkpoint-");
            java.nio.file.Path archiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-checkpoint-archive-");
            TreasuryJournalArchive checkpointArchive = new TreasuryJournalArchive(archiveDir, 1);
            checkpointArchive.append(checkpointLedger.journal());
            TreasuryCheckpointStore checkpointStore = new TreasuryCheckpointStore(checkpointDir);
            TreasuryCheckpointStore.Checkpoint published = checkpointStore.publish(checkpointJson, checkpointArchive);
            TreasuryLedger.Entry postCheckpointEntry = new TreasuryLedger.Entry(3, "tx:post-checkpoint",
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
        try { TreasuryLedgerSnapshot.decode(checkpointJson.replace("annual tax", "tampered tax")); }
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
        ok(decodedSegment.entries().size() == segmentSource.journalSize()
                        && decodedSegment.firstSequence() == 1
                        && decodedSegment.lastSequence() == segmentSource.journal().get(segmentSource.journalSize() - 1).sequence()
                        && decodedSegment.entries().get(1).transactionId().equals(decodedSegment.entries().get(2).transactionId()),
                "checksummed treasury segment round-trips the complete ordered double-entry audit");
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
            TreasuryJournalSegment.decode(segmentJson.replace((char) 34 + "lastSequence" + (char) 34 + ":3", (char) 34 + "lastSequence" + (char) 34 + ":4"));
        } catch (IllegalArgumentException expected) { sequenceGapRejected = true; }
        ok(sequenceGapRejected, "treasury segment rejects altered sequence bounds");

        try {
            java.nio.file.Path archiveDir = java.nio.file.Files.createTempDirectory("maqan-treasury-archive-");
            TreasuryJournalArchive archive = new TreasuryJournalArchive(archiveDir, 1);
            ok(archive.append(segmentSource.journal()) && archive.nextSequence() == 4,
                    "treasury archive durably publishes a contiguous segment and advances its checkpoint");
            ok(archive.append(segmentSource.journal()) && archive.readAll().size() == segmentSource.journalSize(),
                    "treasury archive accepts an identical retry without duplicating history");
            TreasuryJournalArchive reopenedArchive = new TreasuryJournalArchive(archiveDir, 1);
            ok(reopenedArchive.nextSequence() == 4
                            && reopenedArchive.readAll().equals(segmentSource.journal()),
                    "treasury archive restart verifies checksums and restores the next expected sequence");
            java.util.List<TreasuryLedger.Entry> suffixEntries = new java.util.ArrayList<>();
            reopenedArchive.forEachEntryFrom(2, suffixEntries::add);
            ok(suffixEntries.equals(segmentSource.journal().subList(1, segmentSource.journalSize())),
                    "treasury archive streams an exact suffix from a checkpoint without materializing its prefix");
            java.util.List<TreasuryLedger.Entry> emptySuffix = new java.util.ArrayList<>();
            reopenedArchive.forEachEntryFrom(reopenedArchive.nextSequence(), emptySuffix::add);
            ok(emptySuffix.isEmpty(), "treasury archive accepts a suffix starting immediately after its last entry");
            boolean gapRejected = false;
            try { reopenedArchive.append(List.of(new TreasuryLedger.Entry(5, "tx:gap", "x", "y",
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
            migrationLedger.transfer("migration:treasury", "migration:merchant", 75,
                    TreasuryLedger.Kind.TRADE, "migration-test", "migration:transfer", 42, "migration transfer");
            TreasuryJournalArchive migrationArchive = new TreasuryJournalArchive(migrationRoot.resolve("archive"), 1);
            TreasuryCheckpointStore migrationCheckpoints = new TreasuryCheckpointStore(migrationRoot.resolve("checkpoints"));
            TreasuryArchiveMigration.Result migration = TreasuryArchiveMigration.migrate(
                    migrationLedger, migrationArchive, migrationCheckpoints);
            TreasuryArchiveMigration.Result migrationRetry = TreasuryArchiveMigration.migrate(
                    migrationLedger, migrationArchive, migrationCheckpoints);
            TreasuryCheckpointStore.Checkpoint restoredCheckpoint = migrationCheckpoints.load(migrationArchive);
            TreasuryLedgerSnapshot.State restoredState = TreasuryLedgerSnapshot.decode(restoredCheckpoint.snapshotJson());
            TreasuryLedger restoredLedger = TreasuryLedger.restoreFromCheckpoint(restoredCheckpoint, migrationArchive);
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
                            && close(restoredLedger.totalDebits("migration:treasury", TreasuryLedger.Kind.TRADE, "migration-test"),
                                    migrationLedger.totalDebits("migration:treasury", TreasuryLedger.Kind.TRADE, "migration-test")),
                    "checkpoint restore rebuilds balances, journal indexes, debit totals and idempotency keys");
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
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(migrationRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        } catch (java.io.IOException ex) {
            ok(false, "legacy treasury archive migration completes and verifies: " + ex.getMessage());
        }

        System.out.println(failures==0?"ALL OK":failures+" FAILED");
        if(failures>0) System.exit(1);
    }
}
