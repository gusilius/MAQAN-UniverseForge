package dev.worldforge.tests;

import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.Json;
import java.util.*;

public final class TreasuryLedgerTest {
    private static int failures;
    private static void ok(boolean value,String label) {
        System.out.println((value?"ok   ":"FAIL ")+label);
        if(!value) failures++;
    }
    private static boolean close(double a,double b) { return Math.abs(a-b)<1e-8; }

    public static void main(String[] args) {
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

        System.out.println(failures==0?"ALL OK":failures+" FAILED");
        if(failures>0) System.exit(1);
    }
}
