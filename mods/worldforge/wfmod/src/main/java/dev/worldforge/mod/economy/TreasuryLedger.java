package dev.worldforge.mod.economy;

import dev.worldforge.mod.Json;
import java.util.*;

/**
 * World-wide double-entry treasury ledger.
 * Balances are keyed by stable account IDs; every transfer creates matching debit and credit entries.
 * The ledger is independent of Minecraft so economy rules can be tested deterministically.
 */
public final class TreasuryLedger {
    public enum Kind { OPENING, DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, TAX, DUTY, UPKEEP, WAGE, ARMY_UPKEEP, TRADE, VASSAL_TRIBUTE, GRANT, LOAN, REPAYMENT, INTEREST, WRITE_OFF }
    public static final class Account {
        public final String id, ownerId, currency;
        public double balance, creditLimit, debt;
        public boolean bankrupt;
        private Account(String id, String ownerId, String currency, double opening) {
            this.id=id; this.ownerId=ownerId; this.currency=currency; this.balance=opening;
        }
        public double available() { return Math.max(0, balance + creditLimit - debt); }
    }
    public record Entry(long sequence, String transactionId, String accountId, String counterpartyId,
                        String currency, double amount, boolean credit, Kind kind, String category,
                        String reference, long worldMinute, double balanceAfter, String memo) { }

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Entry> journal = new ArrayList<>();
    private long sequence=1, transactionSequence=1;
    private String lastError="";

    private static String required(String v,String field) {
        if(v==null || v.isBlank() || v.length()>128) throw new IllegalArgumentException(field+" is required");
        return v.trim();
    }
    private static double amount(double v) {
        if(!Double.isFinite(v) || v<0) throw new IllegalArgumentException("amount must be finite and nonnegative");
        return v;
    }
    private static long minute(long v) { if(v<0) throw new IllegalArgumentException("worldMinute cannot be negative"); return v; }
    public String lastError() { return lastError; }
    public Collection<Account> accounts() { return Collections.unmodifiableCollection(accounts.values()); }
    public List<Entry> journal() { return Collections.unmodifiableList(journal); }
    public Account account(String id) { return accounts.get(id); }
    public boolean hasAccount(String id) { return accounts.containsKey(id); }
    public double balance(String id) { Account a=accounts.get(id); return a==null?0:a.balance; }

    public Account open(String id,String ownerId,String currency,double openingBalance) {
        id=required(id,"account id"); ownerId=required(ownerId,"owner id"); currency=required(currency,"currency");
        double opening=amount(openingBalance);
        Account existing=accounts.get(id);
        if(existing!=null) {
            if(!existing.ownerId.equals(ownerId) || !existing.currency.equals(currency)) throw new IllegalArgumentException("account identity mismatch: "+id);
            return existing;
        }
        Account a=new Account(id,ownerId,currency,opening); accounts.put(id,a);
        if(opening>0) append("opening:"+transactionSequence++,id,"",currency,opening,true,Kind.OPENING,"opening",id,0,a.balance,"opening balance");
        return a;
    }
    public boolean setCreditLimit(String accountId,double limit) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        a.creditLimit=amount(limit); updateBankruptcy(a); return true;
    }
    public boolean deposit(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute); if(value==0) return true;
        double offset=Math.min(a.debt,value); a.debt-=offset; a.balance+=value-offset; append("tx:"+transactionSequence++,a.id,"external",a.currency,value,true,kind,clean(category),clean(reference),worldMinute,a.balance,clean(memo));
        updateBankruptcy(a); return true;
    }
    public boolean withdraw(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute); if(a.balance+a.creditLimit+1e-9<value) return fail("insufficient funds");
        if(value==0) return true;
        a.balance-=value; a.debt=Math.max(0,-a.balance); append("tx:"+transactionSequence++,a.id,"external",a.currency,value,false,kind,clean(category),clean(reference),worldMinute,a.balance,clean(memo));
        updateBankruptcy(a); return true;
    }
    /** Atomic transfer: no balance changes occur unless both accounts and currency match and payer can cover it. */
    public boolean transfer(String fromId,String toId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        Account from=accounts.get(fromId), to=accounts.get(toId);
        if(from==null || to==null) return fail("unknown transfer account");
        if(from==to || from.id.equals(to.id)) return fail("self transfer is not allowed");
        if(!from.currency.equals(to.currency)) return fail("currency mismatch");
        value=amount(value); minute(worldMinute);
        if(from.balance+from.creditLimit+1e-9<value) return fail("insufficient funds");
        if(value==0) return true;
        String tx="tx:"+transactionSequence++;
        from.balance-=value; to.balance+=value; from.debt=Math.max(0,-from.balance);
        append(tx,from.id,to.id,from.currency,value,false,kind,clean(category),clean(reference),worldMinute,from.balance,clean(memo));
        append(tx,to.id,from.id,to.currency,value,true,kind,clean(category),clean(reference),worldMinute,to.balance,clean(memo));
        updateBankruptcy(from); updateBankruptcy(to); return true;
    }
    /** Creates matched cash and debt; unlike a deposit, loan proceeds do not repay existing liabilities. */
    public boolean borrow(String accountId,double value,long worldMinute,String reference) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute);
        if(a.debt+value>a.creditLimit+1e-9) return fail("credit limit exceeded");
        if(value==0) return true;
        a.balance+=value; a.debt+=value;
        append("tx:"+transactionSequence++,a.id,"lender",a.currency,value,true,Kind.LOAN,"finance",clean(reference),worldMinute,a.balance,"loan proceeds");
        updateBankruptcy(a); return true;
    }
    public boolean accrueInterest(String accountId,double rate,long worldMinute,String reference) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        if(!Double.isFinite(rate)||rate<0||rate>1) throw new IllegalArgumentException("interest rate must be 0..1");
        if(a.debt<=0 || rate==0) return true;
        double interest=a.debt*rate;
        a.debt+=interest;
        append("tx:"+transactionSequence++,a.id,"external",a.currency,interest,false,Kind.INTEREST,"finance",clean(reference),minute(worldMinute),a.balance,"debt interest");
        updateBankruptcy(a); return true;
    }
    public boolean repayDebt(String accountId,double value,long worldMinute,String reference) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=Math.min(amount(value),Math.min(a.debt,Math.max(0,a.balance)));
        if(value<=0) return true;
        a.balance-=value; a.debt-=value;
        append("tx:"+transactionSequence++,a.id,"debt",a.currency,value,false,Kind.REPAYMENT,"finance",clean(reference),minute(worldMinute),a.balance,"debt repayment");
        updateBankruptcy(a); return true;
    }
    private void updateBankruptcy(Account a) { a.bankrupt=a.debt>a.creditLimit+1e-9; }
    private boolean fail(String s) { lastError=s; return false; }
    private static String clean(String s) { return s==null?"":s.trim(); }
    private void append(String tx,String account,String other,String currency,double value,boolean credit,Kind kind,String category,String reference,long minute,double balance,String memo) {
        journal.add(new Entry(sequence++,tx,account,other,currency,value,credit,kind,category,reference,minute,balance,memo));
    }

    public String toJson() {
        Map<String,Object> root=new LinkedHashMap<>(); root.put("format","wf-treasury-ledger/1"); root.put("sequence",sequence); root.put("transactionSequence",transactionSequence);
        List<Object> aa=new ArrayList<>();
        for(Account a:accounts.values()) aa.add(Map.of("id",a.id,"owner",a.ownerId,"currency",a.currency,"balance",a.balance,"creditLimit",a.creditLimit,"debt",a.debt,"bankrupt",a.bankrupt));
        root.put("accounts",aa);
        List<Object> jj=new ArrayList<>();
        for(Entry e:journal) { Map<String,Object> m=new LinkedHashMap<>();m.put("seq",e.sequence());m.put("tx",e.transactionId());m.put("account",e.accountId());m.put("other",e.counterpartyId());m.put("currency",e.currency());m.put("amount",e.amount());m.put("credit",e.credit());m.put("kind",e.kind().name());m.put("category",e.category());m.put("reference",e.reference());m.put("minute",e.worldMinute());m.put("balance",e.balanceAfter());m.put("memo",e.memo());jj.add(m); }
        root.put("journal",jj); return Json.write(root);
    }
    @SuppressWarnings("unchecked")
    public static TreasuryLedger fromJson(String json) {
        Map<String,Object> root=(Map<String,Object>)Json.parse(json);
        if(!"wf-treasury-ledger/1".equals(root.get("format"))) throw new IllegalArgumentException("Unsupported treasury ledger format");
        TreasuryLedger ledger=new TreasuryLedger();
        if(root.get("accounts") instanceof List<?> list) for(Object o:list) {
            if(!(o instanceof Map<?,?> m)) continue;
            String id=String.valueOf(m.get("id")), owner=String.valueOf(m.get("owner")), currency=String.valueOf(m.get("currency"));
            double balance=number(m.get("balance"),0);
            Account a=ledger.open(id,owner,currency,Math.max(0,balance));
            a.balance=Math.max(0,balance);a.creditLimit=number(m.get("creditLimit"),0);a.debt=number(m.get("debt"),0);a.bankrupt=Boolean.TRUE.equals(m.get("bankrupt"));
        }
        ledger.journal.clear();
        if(root.get("journal") instanceof List<?> list) for(Object o:list) {
            if(!(o instanceof Map<?,?> m)) continue;
            try { ledger.journal.add(new Entry(longNumber(m.get("seq"),0),String.valueOf(m.get("tx")),String.valueOf(m.get("account")),String.valueOf(m.get("other")),String.valueOf(m.get("currency")),number(m.get("amount"),0),Boolean.TRUE.equals(m.get("credit")),Kind.valueOf(String.valueOf(m.get("kind"))),String.valueOf(m.get("category")),String.valueOf(m.get("reference")),longNumber(m.get("minute"),0),number(m.get("balance"),0),String.valueOf(m.get("memo")))); } catch(IllegalArgumentException ignored) { }
        }
        ledger.sequence=Math.max(1,longNumber(root.get("sequence"),ledger.journal.size()+1));
        ledger.transactionSequence=Math.max(1,longNumber(root.get("transactionSequence"),ledger.sequence));
        return ledger;
    }
    private static double number(Object o,double d) { return o instanceof Number n && Double.isFinite(n.doubleValue())?n.doubleValue():d; }
    private static long longNumber(Object o,long d) { return o instanceof Number n?n.longValue():d; }
}
