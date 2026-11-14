package dev.worldforge.mod.economy;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.WorldForgeApi;
import java.util.*;

/**
 * World-wide double-entry treasury ledger.
 * Balances are keyed by stable account IDs; every transfer creates matching debit and credit entries.
 * The ledger is independent of Minecraft so economy rules can be tested deterministically.
 */
public final class TreasuryLedger {
    public enum Kind { OPENING, DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, TAX, DUTY, UPKEEP, WAGE, ARMY_UPKEEP, TRADE, VASSAL_TRIBUTE, GRANT, LOAN, REPAYMENT, INTEREST, WRITE_OFF, PRODUCTION, CONSUMPTION, LOGISTICS, IMPORT, EXPORT }
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
    private final Set<String> postedReferences = new HashSet<>();
    private final Set<String> postedDebits = new HashSet<>();
    private final Set<String> postedCredits = new HashSet<>();
    /** Indexed once on append/load; avoids rescanning the ever-growing journal in settlement hot paths. */
    private final Set<String> armyFieldRationDebitAccounts = new HashSet<>();
    /** Append-only secondary indexes keep journal queries proportional to the selected account/reference. */
    private final Map<String, List<Entry>> entriesByAccount = new HashMap<>();
    private final Map<String, List<Entry>> entriesByReference = new HashMap<>();
    private final Map<String, Double> debitTotals = new HashMap<>();
    private final Set<String> accountsWithEntries = new HashSet<>();
    private long sequence=1, transactionSequence=1;
    private String lastError="";

    private boolean restoring;

    private static final ThreadLocal<LedgerMutationState> ACTIVE_MUTATION = new ThreadLocal<>();

    private static final class LedgerMutationState {
        final TreasuryLedger owner;
        final DomainMutationRevisions.Mutation revision;
        int depth = 1;
        boolean dirty;
        LedgerMutationState(TreasuryLedger owner) {
            this.owner = owner;
            this.revision = WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.ECONOMY_TRADE);
        }
    }

    /** Nested ledger calls (for example transferOnce -> transfer) publish one economy revision. */
    private final class MutationBatch implements AutoCloseable {
        private final LedgerMutationState state;
        private boolean closed;
        MutationBatch(LedgerMutationState state) { this.state = state; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (--state.depth == 0) {
                ACTIVE_MUTATION.remove();
                try {
                    if (state.dirty) state.revision.commit();
                } finally {
                    state.revision.close();
                }
            }
        }
    }

    private MutationBatch beginMutationBatch() {
        if (restoring) return new MutationBatch(null) {
            @Override public void close() { }
        };
        LedgerMutationState state = ACTIVE_MUTATION.get();
        if (state == null) {
            state = new LedgerMutationState(this);
            ACTIVE_MUTATION.set(state);
        } else if (state.owner != this) {
            throw new IllegalStateException("Cannot nest mutations across different TreasuryLedger instances");
        } else {
            state.depth++;
        }
        return new MutationBatch(state);
    }

    private void markMutation() {
        if (restoring) return;
        LedgerMutationState state = ACTIVE_MUTATION.get();
        if (state == null || state.owner != this)
            throw new IllegalStateException("TreasuryLedger mutation escaped its canonical writer scope");
        state.dirty = true;
    }

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

    /** Account-scoped immutable journal view in original append order. */
    public List<Entry> entriesForAccount(String accountId) {
        return Collections.unmodifiableList(entriesByAccount.getOrDefault(accountId, List.of()));
    }

    /** O(1) lookup of the postings sharing one account/kind/reference idempotency key. */
    public List<Entry> entriesForReference(String accountId, Kind kind, String reference) {
        return Collections.unmodifiableList(entriesByReference.getOrDefault(
                referenceKey(accountId, kind, reference), List.of()));
    }

    public boolean hasEntries(String accountId) { return accountsWithEntries.contains(accountId); }

    /** Enumerates canonical account IDs; unlike journal-derived discovery this scales with accounts. */
    public Set<String> accountIdsWithPrefix(String prefix) {
        Set<String> result = new TreeSet<>();
        for (String id : accounts.keySet()) if (id.startsWith(prefix)) result.add(id);
        return Collections.unmodifiableSet(result);
    }

    /** Total successfully posted debit value for an account/category, rebuilt from the durable journal. */
    public double totalDebits(String accountId, Kind kind, String category) {
        return debitTotals.getOrDefault(debitTotalKey(accountId, kind, category), 0.0);
    }

    private static String debitTotalKey(String accountId, Kind kind, String category) {
        return String.valueOf(accountId) + "\\u0000" + String.valueOf(kind) + "\\u0000" + clean(category);
    }

    private void indexEntry(Entry entry) {
        entriesByAccount.computeIfAbsent(entry.accountId(), ignored -> new ArrayList<>()).add(entry);
        entriesByReference.computeIfAbsent(referenceKey(entry.accountId(), entry.kind(), entry.reference()),
                ignored -> new ArrayList<>()).add(entry);
        accountsWithEntries.add(entry.accountId());
        if (!entry.credit()) debitTotals.merge(
                debitTotalKey(entry.accountId(), entry.kind(), entry.category()), entry.amount(), Double::sum);
    }

    private static String referenceKey(String accountId, Kind kind, String reference) {
        return String.valueOf(accountId) + "\\u0000" + String.valueOf(kind) + "\\u0000" + clean(reference);
    }

    /** True when this account already posted the same economic event; used for crash-safe retries. */
    public boolean hasTransaction(String accountId, Kind kind, String reference) {
        return postedReferences.contains(referenceKey(accountId, kind, reference));
    }
    public boolean hasDebit(String accountId, Kind kind, String reference) {
        return postedDebits.contains(referenceKey(accountId, kind, reference));
    }
    public boolean hasCredit(String accountId, Kind kind, String reference) {
        return postedCredits.contains(referenceKey(accountId, kind, reference));
    }
    /** O(1) query for the legacy stock-reconciliation guard used by settlement resource reads. */
    public boolean hasArmyFieldRationDebit(String accountId) {
        return armyFieldRationDebitAccounts.contains(accountId);
    }

    public Account open(String id,String ownerId,String currency,double openingBalance) {
        try (MutationBatch ignored = beginMutationBatch()) {
        id=required(id,"account id"); ownerId=required(ownerId,"owner id"); currency=required(currency,"currency");
        double opening=amount(openingBalance);
        Account existing=accounts.get(id);
        if(existing!=null) {
            if(!existing.ownerId.equals(ownerId) || !existing.currency.equals(currency)) throw new IllegalArgumentException("account identity mismatch: "+id);
            return existing;
        }
        Account a=new Account(id,ownerId,currency,opening); accounts.put(id,a); markMutation();
        if(opening>0) append("opening:"+transactionSequence++,id,"",currency,opening,true,Kind.OPENING,"opening",id,0,a.balance,"opening balance");
        return a;
    
        }}
    public boolean setCreditLimit(String accountId,double limit) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        double normalized = amount(limit);
        if (Double.compare(a.creditLimit, normalized) == 0) return true;
        a.creditLimit=normalized; updateBankruptcy(a); markMutation(); return true;
    
        }}
    public boolean deposit(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute); if(value==0) return true;
        double offset=Math.min(a.debt,value); a.debt-=offset; a.balance+=value-offset; append("tx:"+transactionSequence++,a.id,"external",a.currency,value,true,kind,clean(category),clean(reference),worldMinute,a.balance,clean(memo));
        updateBankruptcy(a); return true;
    
        }}
    private static boolean sameAmount(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right) && Math.abs(left - right) <= 1e-8;
    }

    private boolean matchesExternalPosting(String accountId, double value, Kind kind,
                                           String category, String reference, boolean credit) {
        List<Entry> matches = entriesForReference(accountId, kind, reference);
        if (matches.isEmpty()) return false;
        if (matches.size() != 1) return fail("idempotency key collision: ambiguous existing posting");
        Entry entry = matches.get(0);
        if (entry.credit() != credit || !entry.counterpartyId().equals("external")
                || !entry.category().equals(clean(category)) || !sameAmount(entry.amount(), value))
            return fail("idempotency key collision: existing posting payload differs");
        return true;
    }

    public boolean depositOnce(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        if (hasTransaction(accountId, kind, reference)) {
            if (matchesExternalPosting(accountId, value, kind, category, reference, true)) return true;
            return false;
        }
        return deposit(accountId,value,kind,category,reference,worldMinute,memo);
    
        }}
    public boolean withdraw(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute);
        if(a.available()+1e-9<value) return fail("insufficient funds");
        if(value==0) return true;
        double cash=Math.min(a.balance,value);
        a.balance-=cash;
        a.debt+=value-cash;
        append("tx:"+transactionSequence++,a.id,"external",a.currency,value,false,kind,clean(category),clean(reference),worldMinute,a.balance,clean(memo));
        updateBankruptcy(a); return true;
    
        }}
    public boolean withdrawOnce(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        if (hasTransaction(accountId, kind, reference)) {
            if (matchesExternalPosting(accountId, value, kind, category, reference, false)) return true;
            return false;
        }
        return withdraw(accountId,value,kind,category,reference,worldMinute,memo);
    
        }}
    /** Atomic transfer: no balance changes occur unless both accounts and currency match and payer can cover it. */
    public boolean transfer(String fromId,String toId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account from=accounts.get(fromId), to=accounts.get(toId);
        if(from==null || to==null) return fail("unknown transfer account");
        if(from==to || from.id.equals(to.id)) return fail("self transfer is not allowed");
        if(!from.currency.equals(to.currency)) return fail("currency mismatch");
        value=amount(value); minute(worldMinute);
        if(from.available()+1e-9<value) return fail("insufficient funds");
        if(value==0) return true;
        String tx="tx:"+transactionSequence++;
        double cash=Math.min(from.balance,value);
        from.balance-=cash;
        from.debt+=value-cash;
        double debtOffset=Math.min(to.debt,value);
        to.debt-=debtOffset;
        to.balance+=value-debtOffset;
        append(tx,from.id,to.id,from.currency,value,false,kind,clean(category),clean(reference),worldMinute,from.balance,clean(memo));
        append(tx,to.id,from.id,to.currency,value,true,kind,clean(category),clean(reference),worldMinute,to.balance,clean(memo));
        updateBankruptcy(from); updateBankruptcy(to); return true;
    
        }}
    private boolean matchesExistingTransfer(String fromId, String toId, double value,
                                            Kind kind, String category, String reference) {
        List<Entry> debits = entriesForReference(fromId, kind, reference).stream()
                .filter(entry -> !entry.credit()).toList();
        List<Entry> credits = entriesForReference(toId, kind, reference).stream()
                .filter(Entry::credit).toList();
        if (debits.size() != 1 || credits.size() != 1)
            return fail("idempotency key collision: existing transfer is incomplete or ambiguous");
        Entry debit = debits.get(0), credit = credits.get(0);
        boolean valid = debit.counterpartyId().equals(toId)
                && credit.counterpartyId().equals(fromId)
                && debit.transactionId().equals(credit.transactionId())
                && debit.currency().equals(credit.currency())
                && debit.category().equals(clean(category))
                && credit.category().equals(clean(category))
                && sameAmount(debit.amount(), value)
                && sameAmount(credit.amount(), value);
        if (!valid) return fail("idempotency key collision: existing transfer payload differs");
        return true;
    }

    public boolean transferOnce(String fromId,String toId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        if (hasTransaction(fromId, kind, reference)) {
            return matchesExistingTransfer(fromId,toId,value,kind,category,reference);
        }
        if (hasTransaction(toId, kind, reference))
            return fail("idempotency key collision: destination already has a posting for this reference");
        return transfer(fromId,toId,value,kind,category,reference,worldMinute,memo);
    
        }}
    /** Creates matched cash and debt; unlike a deposit, loan proceeds do not repay existing liabilities. */
    public boolean borrow(String accountId,double value,long worldMinute,String reference) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute);
        if(a.debt+value>a.creditLimit+1e-9) return fail("credit limit exceeded");
        if(value==0) return true;
        a.balance+=value; a.debt+=value;
        append("tx:"+transactionSequence++,a.id,"lender",a.currency,value,true,Kind.LOAN,"finance",clean(reference),worldMinute,a.balance,"loan proceeds");
        updateBankruptcy(a); return true;
    
        }}
    public boolean accrueInterest(String accountId,double rate,long worldMinute,String reference) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        if(!Double.isFinite(rate)||rate<0||rate>1) throw new IllegalArgumentException("interest rate must be 0..1");
        if(a.debt<=0 || rate==0) return true;
        double interest=a.debt*rate;
        a.debt+=interest;
        append("tx:"+transactionSequence++,a.id,"external",a.currency,interest,false,Kind.INTEREST,"finance",clean(reference),minute(worldMinute),a.balance,"debt interest");
        updateBankruptcy(a); return true;
    
        }}
    public boolean repayDebt(String accountId,double value,long worldMinute,String reference) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=Math.min(amount(value),Math.min(a.debt,Math.max(0,a.balance)));
        if(value<=0) return true;
        a.balance-=value; a.debt-=value;
        append("tx:"+transactionSequence++,a.id,"debt",a.currency,value,false,Kind.REPAYMENT,"finance",clean(reference),minute(worldMinute),a.balance,"debt repayment");
        updateBankruptcy(a); return true;
    
        }}
    private void updateBankruptcy(Account a) { a.bankrupt=a.debt>a.creditLimit+1e-9; }
    private boolean fail(String s) { lastError=s; return false; }
    private static String clean(String s) { return s==null?"":s.trim(); }
    private void append(String tx,String account,String other,String currency,double value,boolean credit,Kind kind,String category,String reference,long minute,double balance,String memo) {
        markMutation();
        Entry entry = new Entry(sequence++,tx,account,other,currency,value,credit,kind,category,reference,minute,balance,memo);
        journal.add(entry);
        indexEntry(entry);
        String key = referenceKey(account, kind, reference);
        postedReferences.add(key);
        (credit ? postedCredits : postedDebits).add(key);
        if (!credit && kind == Kind.LOGISTICS && "army-field-rations".equals(category))
            armyFieldRationDebitAccounts.add(account);
    }

    /**
     * Writes the durable ledger incrementally. The journal is intentionally append-only,
     * so building a second object graph and one giant JSON String for every autosave can
     * temporarily require several times the ledger's retained heap.
     */
    public void writeJson(java.io.Writer writer) throws java.io.IOException {
        writer.write("{\"format\":\"wf-treasury-ledger/1\",\"sequence\":");
        writer.write(Long.toString(sequence));
        writer.write(",\"transactionSequence\":");
        writer.write(Long.toString(transactionSequence));
        writer.write(",\"accounts\":[");
        boolean first = true;
        for (Account account : accounts.values()) {
            if (!first) writer.write(',');
            first = false;
            writer.write(Json.write(Map.of(
                    "id", account.id, "owner", account.ownerId, "currency", account.currency,
                    "balance", account.balance, "creditLimit", account.creditLimit,
                    "debt", account.debt, "bankrupt", account.bankrupt)));
        }
        writer.write("],\"journal\":[");
        first = true;
        for (Entry entry : journal) {
            if (!first) writer.write(',');
            first = false;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.sequence());
            row.put("tx", entry.transactionId());
            row.put("account", entry.accountId());
            row.put("other", entry.counterpartyId());
            row.put("currency", entry.currency());
            row.put("amount", entry.amount());
            row.put("credit", entry.credit());
            row.put("kind", entry.kind().name());
            row.put("category", entry.category());
            row.put("reference", entry.reference());
            row.put("minute", entry.worldMinute());
            row.put("balance", entry.balanceAfter());
            row.put("memo", entry.memo());
            writer.write(Json.write(row));
        }
        writer.write("]}");
    }

    public String toJson() {
        java.io.StringWriter writer = new java.io.StringWriter();
        try {
            writeJson(writer);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Unexpected in-memory treasury serialization failure", impossible);
        }
        return writer.toString();
    }
    @SuppressWarnings("unchecked")
    public static TreasuryLedger fromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Treasury ledger root must be an object");
        if (!"wf-treasury-ledger/1".equals(root.get("format"))) throw new IllegalArgumentException("Unsupported treasury ledger format");
        if (!(root.get("accounts") instanceof List<?> accountRows) || !(root.get("journal") instanceof List<?> journalRows))
            throw new IllegalArgumentException("Treasury ledger requires accounts and journal arrays");

        TreasuryLedger ledger = new TreasuryLedger();
        ledger.restoring = true;
        for (Object row : accountRows) {
            if (!(row instanceof Map<?, ?> m)) throw new IllegalArgumentException("Invalid treasury account row");
            String id = requiredString(m.get("id"), "account id");
            String owner = requiredString(m.get("owner"), "account owner");
            String currency = requiredString(m.get("currency"), "account currency");
            double balance = requiredNumber(m.get("balance"), "account balance");
            double creditLimit = requiredNumber(m.get("creditLimit"), "account credit limit");
            double debt = requiredNumber(m.get("debt"), "account debt");
            if (balance < 0 || creditLimit < 0 || debt < 0) throw new IllegalArgumentException("Negative treasury account value: " + id);
            if (ledger.accounts.containsKey(id)) throw new IllegalArgumentException("Duplicate treasury account: " + id);
            Account a = ledger.open(id, owner, currency, 0);
            a.balance = balance;
            a.creditLimit = creditLimit;
            a.debt = debt;
            a.bankrupt = debt > creditLimit + 1e-9;
        }

        ledger.journal.clear();
        long previousSequence = 0;
        Map<String, List<Entry>> transactions = new HashMap<>();
        for (Object row : journalRows) {
            if (!(row instanceof Map<?, ?> m)) throw new IllegalArgumentException("Invalid treasury journal row");
            long seq = requiredLong(m.get("seq"), "journal sequence");
            String tx = requiredString(m.get("tx"), "transaction id");
            String accountId = requiredString(m.get("account"), "journal account");
            String other = stringOrEmpty(m.get("other"), "journal counterparty");
            String currency = requiredString(m.get("currency"), "journal currency");
            double value = requiredNumber(m.get("amount"), "journal amount");
            Object creditValue = m.get("credit");
            if (!(creditValue instanceof Boolean credit)) throw new IllegalArgumentException("Invalid journal debit/credit flag");
            Kind kind;
            try { kind = Kind.valueOf(requiredString(m.get("kind"), "journal kind")); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid treasury journal kind", ex); }
            String category = optionalString(m.get("category"));
            String reference = optionalString(m.get("reference"));
            long worldMinute = requiredLong(m.get("minute"), "journal minute");
            double balanceAfter = requiredNumber(m.get("balance"), "journal balance");
            String memo = optionalString(m.get("memo"));
            Account account = ledger.accounts.get(accountId);
            if (account == null || !account.currency.equals(currency)) throw new IllegalArgumentException("Journal references unknown or mismatched account: " + accountId);
            if (seq <= previousSequence || seq < 1 || worldMinute < 0 || value <= 0 || balanceAfter < 0)
                throw new IllegalArgumentException("Invalid journal ordering or amount at sequence " + seq);
            Entry entry = new Entry(seq, tx, accountId, other, currency, value, credit, kind,
                    category, reference, worldMinute, balanceAfter, memo);
            ledger.journal.add(entry);
            ledger.indexEntry(entry);
            String referenceKey = referenceKey(accountId, kind, reference);
            ledger.postedReferences.add(referenceKey);
            (credit ? ledger.postedCredits : ledger.postedDebits).add(referenceKey);
            if (!credit && kind == Kind.LOGISTICS && "army-field-rations".equals(category))
                ledger.armyFieldRationDebitAccounts.add(accountId);
            transactions.computeIfAbsent(tx, ignored -> new ArrayList<>()).add(entry);
            previousSequence = seq;
        }
        for (Map.Entry<String, List<Entry>> transaction : transactions.entrySet()) {
            List<Entry> entries = transaction.getValue();
            if (entries.size() == 1) {
                Entry single = entries.get(0);
                if (ledger.accounts.containsKey(single.counterpartyId()))
                    throw new IllegalArgumentException("Missing matching double-entry posting: " + transaction.getKey());
                continue; // opening, external income/expense, loan or interest
            }
            if (entries.size() != 2) throw new IllegalArgumentException("Invalid journal transaction cardinality: " + transaction.getKey());
            Entry a = entries.get(0), b = entries.get(1);
            if (a.accountId().equals(b.accountId()) || !a.counterpartyId().equals(b.accountId())
                    || !b.counterpartyId().equals(a.accountId()) || a.credit() == b.credit()
                    || !a.currency().equals(b.currency()) || a.kind() != b.kind()
                    || !a.category().equals(b.category()) || !a.reference().equals(b.reference())
                    || a.worldMinute() != b.worldMinute() || Math.abs(a.amount() - b.amount()) > 1e-8)
                throw new IllegalArgumentException("Unbalanced double-entry transaction: " + transaction.getKey());
        }
        ledger.sequence = Math.max(1, requiredLong(root.get("sequence"), "journal next sequence"));
        ledger.transactionSequence = Math.max(1, requiredLong(root.get("transactionSequence"), "transaction next sequence"));
        if (ledger.sequence <= previousSequence) throw new IllegalArgumentException("Treasury journal sequence is not ahead of the journal");
        ledger.restoring = false;
        return ledger;
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 256)
            throw new IllegalArgumentException("Invalid " + field);
        return text.trim();
    }
    private static String optionalString(Object value) {
        return value instanceof String text ? text : "";
    }
    private static String stringOrEmpty(Object value, String field) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > 256)
            throw new IllegalArgumentException("Invalid " + field);
        return text.trim();
    }
    private static double requiredNumber(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException("Invalid " + field);
        return n.doubleValue();
    }
    private static long requiredLong(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 0
                || n.doubleValue() > Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid " + field);
        return n.longValue();
    }
    private static double number(Object o,double d) { return o instanceof Number n && Double.isFinite(n.doubleValue())?n.doubleValue():d; }
    private static long longNumber(Object o,long d) { return o instanceof Number n?n.longValue():d; }
}
