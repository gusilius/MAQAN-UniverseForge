package dev.worldforge.tests;

import dev.worldforge.mod.economy.TreasuryLedger;
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
        revisionLedger.setCreditLimit("revision:account", 50);
        long afterLimit = revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE);
        revisionLedger.setCreditLimit("revision:account", 50);
        ok(afterLimit == afterOpen + 1 && revisions.snapshot().orElseThrow().revision(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.ECONOMY_TRADE) == afterLimit,
                "credit limit mutation publishes once and an identical value is a no-op");
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
        System.out.println(failures==0?"ALL OK":failures+" FAILED");
        if(failures>0) System.exit(1);
    }
}
