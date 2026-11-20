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
    public enum Kind { ACCOUNT_CREATED, CREDIT_LIMIT_CHANGED, OPENING, DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, TAX, DUTY, UPKEEP, WAGE, ARMY_UPKEEP, TRADE, VASSAL_TRIBUTE, GRANT, LOAN, REPAYMENT, INTEREST, WRITE_OFF, PRODUCTION, CONSUMPTION, LOGISTICS, IMPORT, EXPORT }
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
    /** Bounded-cardinality aggregate for diagnosing which economic domain grows the audit journal. */
    public record JournalStat(Kind kind, String category, long entries) { }

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Entry> journal = new ArrayList<>();
    /** Durable history is queried from its archive; only entries after this cursor remain in journal. */
    private TreasuryJournalArchive historyArchive;
    private long archivedThroughSequence;
    private final Set<String> postedReferences = new HashSet<>();
    private final Set<String> postedDebits = new HashSet<>();
    private final Set<String> postedCredits = new HashSet<>();
    /** Indexed once on append/load; avoids rescanning the ever-growing journal in settlement hot paths. */
    private final Set<String> armyFieldRationDebitAccounts = new HashSet<>();
    private final Map<String, Double> debitTotals = new HashMap<>();
    private final Map<String, Long> journalCountsByKindCategory = new HashMap<>();
    private final Set<String> accountsWithEntries = new HashSet<>();
    private long sequence=1, transactionSequence=1;
    private long mutationVersion;
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
            if (state == null) return;
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
        if (restoring) return new MutationBatch(null);
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
        mutationVersion++;
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
    public synchronized String lastError() { return lastError; }
    public synchronized long mutationVersion() { return mutationVersion; }

    /** Immutable state required to checkpoint account balances and crash-retry identity. */
    synchronized TreasuryLedgerSnapshot.State checkpointState() {
        List<TreasuryLedgerSnapshot.AccountState> accountStates = accounts.values().stream()
                .map(a -> new TreasuryLedgerSnapshot.AccountState(a.id, a.ownerId, a.currency,
                        a.balance, a.creditLimit, a.debt, a.bankrupt)).toList();
        return new TreasuryLedgerSnapshot.State(sequence, transactionSequence, mutationVersion,
                accountStates, Set.copyOf(postedReferences), Set.copyOf(postedDebits),
                Set.copyOf(postedCredits), Set.copyOf(armyFieldRationDebitAccounts));
    }

    private static void validateArchivedTransaction(List<Entry> postings, Map<String, Account> accounts) {
        if (postings.size() == 1) {
            Entry single = postings.get(0);
            if (accounts.containsKey(single.counterpartyId()))
                throw new IllegalStateException("Treasury archive is missing a matching double-entry posting: " + single.transactionId());
            return;
        }
        if (postings.size() != 2)
            throw new IllegalStateException("Invalid treasury transaction cardinality: " + postings.get(0).transactionId());
        Entry a = postings.get(0), b = postings.get(1);
        if (a.accountId().equals(b.accountId()) || !a.counterpartyId().equals(b.accountId())
                || !b.counterpartyId().equals(a.accountId()) || a.credit() == b.credit()
                || !a.currency().equals(b.currency()) || a.kind() != b.kind()
                || !a.category().equals(b.category()) || !a.reference().equals(b.reference())
                || a.worldMinute() != b.worldMinute() || Math.abs(a.amount() - b.amount()) > 1e-8)
            throw new IllegalStateException("Unbalanced treasury transaction: " + a.transactionId());
    }

    /**
     * Restores the checkpoint state, verifies its immutable archive prefix, then replays
     * only the append-only suffix. Snapshot balances/debt are the replay base; journal
     * postings rebuild indexes and apply later account mutations exactly once.
     */
    public static TreasuryLedger restoreFromCheckpoint(TreasuryCheckpointStore.Checkpoint checkpoint,
                                                        TreasuryJournalArchive archive) throws java.io.IOException {
        TreasuryCheckpointStore.verify(checkpoint, archive);
        TreasuryLedgerSnapshot.State state;
        try {
            state = TreasuryLedgerSnapshot.decode(checkpoint.snapshotJson());
        } catch (RuntimeException invalid) {
            throw new java.io.IOException("Treasury checkpoint state is invalid", invalid);
        }
        if (checkpoint.nextSequence() != state.nextSequence()
                || archive.nextSequence() < state.nextSequence())
            throw new java.io.IOException("Treasury checkpoint sequence does not match its verified archive");

        TreasuryLedger restored = new TreasuryLedger();
        restored.restoring = true;
        for (TreasuryLedgerSnapshot.AccountState saved : state.accounts()) {
            Account account = new Account(saved.id(), saved.ownerId(), saved.currency(), saved.balance());
            account.creditLimit = saved.creditLimit();
            account.debt = saved.debt();
            account.bankrupt = saved.bankrupt();
            if (restored.accounts.putIfAbsent(account.id, account) != null)
                throw new java.io.IOException("Duplicate account in treasury checkpoint: " + account.id);
        }

        Set<String> prefixReferences = new HashSet<>();
        Set<String> prefixDebits = new HashSet<>();
        Set<String> prefixCredits = new HashSet<>();
        Set<String> prefixRationAccounts = new HashSet<>();
        Map<String, Double> prefixBalances = new HashMap<>();
        List<Entry> transaction = new ArrayList<>(2);
        String[] transactionId = {null};
        long[] expected = {1};
        try {
            archive.forEachEntryFrom(1, entry -> {
                if (entry.sequence() >= state.nextSequence())
                    return;
                if (entry.sequence() != expected[0]++)
                    throw new IllegalStateException("Treasury checkpoint prefix sequence discontinuity");
                Account account = restored.accounts.get(entry.accountId());
                if (account == null || !account.currency.equals(entry.currency()))
                    throw new IllegalStateException("Treasury checkpoint prefix references an unknown or mismatched account: " + entry.accountId());
                if (transactionId[0] != null && !transactionId[0].equals(entry.transactionId())) {
                    validateArchivedTransaction(transaction, restored.accounts);
                    transaction.clear();
                }
                transactionId[0] = entry.transactionId();
                transaction.add(entry);
                if (transaction.size() > 2)
                    throw new IllegalStateException("Invalid treasury transaction cardinality: " + entry.transactionId());
                String key = referenceKey(entry.accountId(), entry.kind(), entry.reference());
                prefixReferences.add(key);
                (entry.credit() ? prefixCredits : prefixDebits).add(key);
                if (!entry.credit() && entry.kind() == Kind.LOGISTICS
                        && "army-field-rations".equals(entry.category()))
                    prefixRationAccounts.add(entry.accountId());
                restored.journal.add(entry);
                restored.indexEntry(entry);
                prefixBalances.put(entry.accountId(), entry.balanceAfter());
                restored.transactionSequence = Math.max(restored.transactionSequence,
                        nextTransactionSequence(entry.transactionId()));
            });
            if (!transaction.isEmpty()) validateArchivedTransaction(transaction, restored.accounts);
        } catch (RuntimeException invalid) {
            throw new java.io.IOException("Treasury checkpoint prefix failed ledger reconstruction", invalid);
        }
        if (expected[0] != state.nextSequence())
            throw new java.io.IOException("Treasury archive does not contain the complete checkpoint prefix");
        if (!prefixReferences.equals(state.postedReferences()) || !prefixDebits.equals(state.postedDebits())
                || !prefixCredits.equals(state.postedCredits())
                || !prefixRationAccounts.equals(state.armyFieldRationDebitAccounts()))
            throw new java.io.IOException("Treasury checkpoint idempotency indexes do not match its archive prefix");
        for (Account account : restored.accounts.values()) {
            double journalBalance = prefixBalances.getOrDefault(account.id, 0.0);
            if (Math.abs(journalBalance - account.balance) > 1e-8)
                throw new java.io.IOException("Treasury checkpoint cash balance does not match its prefix journal: " + account.id);
        }
        restored.postedReferences.addAll(prefixReferences);
        restored.postedDebits.addAll(prefixDebits);
        restored.postedCredits.addAll(prefixCredits);
        restored.armyFieldRationDebitAccounts.addAll(prefixRationAccounts);

        List<Entry> suffixTransaction = new ArrayList<>(2);
        String[] suffixTransactionId = {null};
        long[] suffixExpected = {state.nextSequence()};
        long[] suffixEntries = {0};
        long[] extraAccountMutations = {0};
        try {
            archive.forEachEntryFrom(state.nextSequence(), entry -> {
                if (entry.sequence() != suffixExpected[0]++)
                    throw new IllegalStateException("Treasury suffix sequence discontinuity");
                if (suffixTransactionId[0] != null && !suffixTransactionId[0].equals(entry.transactionId())) {
                    validateArchivedTransaction(suffixTransaction, restored.accounts);
                    suffixTransaction.clear();
                }
                suffixTransactionId[0] = entry.transactionId();
                suffixTransaction.add(entry);
                if (suffixTransaction.size() > 2)
                    throw new IllegalStateException("Invalid treasury transaction cardinality: " + entry.transactionId());

                Account account = restored.accounts.get(entry.accountId());
                if (entry.kind() == Kind.ACCOUNT_CREATED) {
                    if (account != null || entry.amount() != 0 || !entry.credit()
                            || !"@treasury:structural".equals(entry.counterpartyId())
                            || !entry.reference().equals(entry.accountId())
                            || entry.memo().isBlank() || entry.balanceAfter() < 0)
                        throw new IllegalStateException("Invalid treasury account-created event: " + entry.accountId());
                    account = new Account(entry.accountId(), entry.memo(), entry.currency(), entry.balanceAfter());
                    restored.accounts.put(account.id, account);
                    extraAccountMutations[0]++;
                } else {
                    if (account == null || !account.currency.equals(entry.currency()))
                        throw new IllegalStateException("Treasury suffix references an unknown or mismatched account: " + entry.accountId());
                    if (entry.kind() == Kind.CREDIT_LIMIT_CHANGED) {
                        if (entry.amount() != 0 || !entry.credit()
                                || !"@treasury:structural".equals(entry.counterpartyId())
                                || Math.abs(account.balance - entry.balanceAfter()) > 1e-8)
                            throw new IllegalStateException("Invalid treasury credit-limit event: " + entry.accountId());
                        double limit;
                        try { limit = Double.parseDouble(entry.memo()); }
                        catch (RuntimeException invalid) {
                            throw new IllegalStateException("Invalid treasury credit limit payload", invalid);
                        }
                        if (!Double.isFinite(limit) || limit < 0)
                            throw new IllegalStateException("Invalid treasury credit limit value");
                        account.creditLimit = limit;
                        restored.updateBankruptcy(account);
                    } else if (entry.kind() == Kind.OPENING) {
                        if (!entry.credit() || Math.abs(account.balance - entry.balanceAfter()) > 1e-8)
                            throw new IllegalStateException("Opening posting changed the already initialized account balance");
                    } else {
                        double previousBalance = account.balance;
                        double nextBalance = entry.balanceAfter();
                        if (!Double.isFinite(nextBalance) || nextBalance < 0)
                            throw new IllegalStateException("Invalid treasury suffix balance");
                        double debtDelta;
                        if (entry.kind() == Kind.LOAN) {
                            if (!entry.credit()) throw new IllegalStateException("Loan posting must be a credit");
                            debtDelta = entry.amount();
                        } else if (entry.kind() == Kind.INTEREST) {
                            if (entry.credit() || Math.abs(nextBalance - previousBalance) > 1e-8)
                                throw new IllegalStateException("Invalid interest posting");
                            debtDelta = entry.amount();
                        } else if (entry.kind() == Kind.REPAYMENT) {
                            if (entry.credit() || !"debt".equals(entry.counterpartyId()))
                                throw new IllegalStateException("Invalid debt repayment posting");
                            debtDelta = -entry.amount();
                        } else {
                            double cashDelta = nextBalance - previousBalance;
                            debtDelta = entry.credit()
                                    ? -(entry.amount() - cashDelta)
                                    : entry.amount() + cashDelta;
                        }
                        double nextDebt = account.debt + debtDelta;
                        if (!Double.isFinite(nextDebt) || nextDebt < -1e-8)
                            throw new IllegalStateException("Treasury suffix produces invalid debt for " + account.id);
                        account.balance = nextBalance;
                        account.debt = Math.max(0, nextDebt);
                        restored.updateBankruptcy(account);
                    }
                }

                String key = referenceKey(entry.accountId(), entry.kind(), entry.reference());
                restored.postedReferences.add(key);
                (entry.credit() ? restored.postedCredits : restored.postedDebits).add(key);
                if (!entry.credit() && entry.kind() == Kind.LOGISTICS
                        && "army-field-rations".equals(entry.category()))
                    restored.armyFieldRationDebitAccounts.add(entry.accountId());
                restored.journal.add(entry);
                restored.indexEntry(entry);
                restored.transactionSequence = Math.max(restored.transactionSequence,
                        nextTransactionSequence(entry.transactionId()));
                suffixEntries[0]++;
            });
            if (!suffixTransaction.isEmpty()) validateArchivedTransaction(suffixTransaction, restored.accounts);
        } catch (RuntimeException invalid) {
            throw new java.io.IOException("Treasury archive suffix replay failed validation", invalid);
        }
        if (suffixExpected[0] != archive.nextSequence())
            throw new java.io.IOException("Treasury archive suffix is incomplete");

        restored.sequence = archive.nextSequence();
        restored.historyArchive = archive;
        restored.archivedThroughSequence = archive.nextSequence() - 1;
        restored.transactionSequence = Math.max(state.nextTransactionSequence(), restored.transactionSequence);
        restored.mutationVersion = state.mutationVersion() + suffixEntries[0] + extraAccountMutations[0];
        restored.restoring = false;
        return restored;
    }

    private static long nextTransactionSequence(String transactionId) {
        if (transactionId == null) return 1;
        int separator = transactionId.lastIndexOf(':');
        if (separator < 0 || separator == transactionId.length() - 1) return 1;
        try {
            long value = Long.parseLong(transactionId.substring(separator + 1));
            return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** Next global journal sequence; 1 means no journal entries have been posted. */
    public synchronized long nextSequence() { return sequence; }

    public synchronized int accountCount() { return accounts.size(); }
    public synchronized int journalSize() { return (int) Math.min(Integer.MAX_VALUE, sequence - 1); }
    public synchronized Collection<Account> accounts() { return Collections.unmodifiableCollection(accounts.values()); }

    /** Explicit compatibility export. Normal restore and save paths never materialize archived history. */
    public synchronized List<Entry> journal() {
        List<Entry> result = new ArrayList<>();
        if (historyArchive != null) {
            try { historyArchive.forEachEntry(result::add); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        result.addAll(journal);
        return List.copyOf(result);
    }

    /** Bounded view of the unarchived operational tail, starting at an exact global sequence. */
    public synchronized List<Entry> entriesFromSequence(long firstSequence) {
        long firstUnarchived = archivedThroughSequence + 1;
        if (firstSequence < firstUnarchived || firstSequence > sequence)
            throw new IllegalArgumentException("Treasury archive cursor is outside the unarchived journal tail");
        int start = Math.toIntExact(firstSequence - firstUnarchived);
        if (start > journal.size()
                || (start < journal.size() && journal.get(start).sequence() != firstSequence)
                || journal.size() != sequence - firstUnarchived)
            throw new IllegalStateException("Treasury unarchived journal tail is not contiguous");
        return List.copyOf(journal.subList(start, journal.size()));
    }

    /** Drop the in-memory tail only after its complete sequence has been durably archived and checkpointed. */
    public synchronized void acknowledgeArchivedThrough(TreasuryJournalArchive archive, long exclusiveNextSequence) {
        if (archive == null || archive.nextSequence() != exclusiveNextSequence)
            throw new IllegalArgumentException("Verified treasury archive is required at the acknowledged boundary");
        if (exclusiveNextSequence != sequence || exclusiveNextSequence < archivedThroughSequence + 1
                || journal.size() != sequence - archivedThroughSequence - 1)
            throw new IllegalStateException("Cannot acknowledge an incomplete treasury archive boundary");
        historyArchive = archive;
        archivedThroughSequence = exclusiveNextSequence - 1;
        journal.clear();
    }
    public synchronized Account account(String id) { return accounts.get(id); }
    public synchronized boolean hasAccount(String id) { return accounts.containsKey(id); }
    public synchronized double balance(String id) { Account a=accounts.get(id); return a==null?0:a.balance; }

    /**
     * Account-scoped journal view in original append order. History is scanned on demand
     * rather than retained in a second per-account collection alongside the canonical journal.
     */
    public synchronized List<Entry> entriesForAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) return List.of();
        List<Entry> result = new ArrayList<>();
        if (historyArchive != null) {
            try {
                long cursor = 1;
                while (cursor < historyArchive.nextSequence()) {
                    TreasuryJournalArchive.AccountPage page = historyArchive.readAccountPage(accountId, cursor, 512);
                    result.addAll(page.entries());
                    if (page.complete()) break;
                    if (page.nextSequence() <= cursor) throw new java.io.IOException("Treasury account history cursor did not advance");
                    cursor = page.nextSequence();
                }
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        for (Entry entry : journal)
            if (accountId.equals(entry.accountId())) result.add(entry);
        return List.copyOf(result);
    }

    /**
     * Returns postings for one idempotency key. The compact posted-reference sets remain
     * the O(1) duplicate guard; full Entry objects are not duplicated in a reference index.
     */
    public synchronized List<Entry> entriesForReference(String accountId, Kind kind, String reference) {
        String key = referenceKey(accountId, kind, reference);
        List<Entry> result = new ArrayList<>(2);
        if (historyArchive != null) {
            try {
                historyArchive.forEachEntry(entry -> {
                    if (key.equals(referenceKey(entry.accountId(), entry.kind(), entry.reference())))
                        result.add(entry);
                });
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        for (Entry entry : journal) {
            if (key.equals(referenceKey(entry.accountId(), entry.kind(), entry.reference())))
                result.add(entry);
        }
        return List.copyOf(result);
    }

    public synchronized boolean hasEntries(String accountId) { return accountsWithEntries.contains(accountId); }

    /** Enumerates canonical account IDs; unlike journal-derived discovery this scales with accounts. */
    public synchronized Set<String> accountIdsWithPrefix(String prefix) {
        Set<String> result = new TreeSet<>();
        for (String id : accounts.keySet()) if (id.startsWith(prefix)) result.add(id);
        return Collections.unmodifiableSet(result);
    }

    /** Total successfully posted debit value for an account/category, rebuilt from the durable journal. */
    public synchronized double totalDebits(String accountId, Kind kind, String category) {
        return debitTotals.getOrDefault(debitTotalKey(accountId, kind, category), 0.0);
    }

    private static String debitTotalKey(String accountId, Kind kind, String category) {
        return String.valueOf(accountId) + "\\u0000" + String.valueOf(kind) + "\\u0000" + clean(category);
    }

    private static String categoryGroup(String category) {
        String value = clean(category);
        int separator = value.indexOf(':');
        return separator > 0 ? value.substring(0, separator) : value;
    }

    /** Top journal sources are maintained on append/load; diagnostics never rescan millions of rows. */
    public synchronized List<JournalStat> topJournalStats(int limit) {
        int bounded = Math.max(0, Math.min(32, limit));
        return journalCountsByKindCategory.entrySet().stream()
                .map(entry -> {
                    int separator = entry.getKey().indexOf(0);
                    Kind kind = Kind.valueOf(entry.getKey().substring(0, separator));
                    String category = entry.getKey().substring(separator + 1);
                    return new JournalStat(kind, category, entry.getValue());
                })
                .sorted(Comparator.comparingLong(JournalStat::entries).reversed()
                        .thenComparing(stat -> stat.kind().name()).thenComparing(JournalStat::category))
                .limit(bounded).toList();
    }

    private void indexEntry(Entry entry) {
        journalCountsByKindCategory.merge(entry.kind().name() + (char) 0 + categoryGroup(entry.category()), 1L, Long::sum);
        accountsWithEntries.add(entry.accountId());
        if (!entry.credit()) debitTotals.merge(
                debitTotalKey(entry.accountId(), entry.kind(), entry.category()), entry.amount(), Double::sum);
    }

    private static String referenceKey(String accountId, Kind kind, String reference) {
        return String.valueOf(accountId) + "\\u0000" + String.valueOf(kind) + "\\u0000" + clean(reference);
    }

    /** True when this account already posted the same economic event; used for crash-safe retries. */
    public synchronized boolean hasTransaction(String accountId, Kind kind, String reference) {
        return postedReferences.contains(referenceKey(accountId, kind, reference));
    }
    public synchronized boolean hasDebit(String accountId, Kind kind, String reference) {
        return postedDebits.contains(referenceKey(accountId, kind, reference));
    }
    public synchronized boolean hasCredit(String accountId, Kind kind, String reference) {
        return postedCredits.contains(referenceKey(accountId, kind, reference));
    }
    /** O(1) query for the legacy stock-reconciliation guard used by settlement resource reads. */
    public synchronized boolean hasArmyFieldRationDebit(String accountId) {
        return armyFieldRationDebitAccounts.contains(accountId);
    }

    public synchronized Account open(String id,String ownerId,String currency,double openingBalance) {
        try (MutationBatch ignored = beginMutationBatch()) {
        id=required(id,"account id"); ownerId=required(ownerId,"owner id"); currency=required(currency,"currency");
        double opening=amount(openingBalance);
        Account existing=accounts.get(id);
        if(existing!=null) {
            if(!existing.ownerId.equals(ownerId) || !existing.currency.equals(currency)) throw new IllegalArgumentException("account identity mismatch: "+id);
            return existing;
        }
        Account a=new Account(id,ownerId,currency,opening); accounts.put(id,a); markMutation();
        append("struct:"+transactionSequence++, id, "@treasury:structural", currency, 0, true,
                Kind.ACCOUNT_CREATED, "account-created", id, 0, a.balance, ownerId);
        if(opening>0) append("opening:"+transactionSequence++,id,"",currency,opening,true,Kind.OPENING,"opening",id,0,a.balance,"opening balance");
        return a;
    
        }}
    public synchronized boolean setCreditLimit(String accountId,double limit) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        double normalized = amount(limit);
        if (Double.compare(a.creditLimit, normalized) == 0) return true;
        a.creditLimit=normalized; updateBankruptcy(a);
        append("struct:"+transactionSequence++, a.id, "@treasury:structural", a.currency, 0, true,
                Kind.CREDIT_LIMIT_CHANGED, "credit-limit", "credit-limit:"+transactionSequence,
                0, a.balance, Double.toString(normalized));
        return true;
    
        }}
    public synchronized boolean deposit(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
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

    public synchronized boolean depositOnce(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        if (hasTransaction(accountId, kind, reference)) {
            if (matchesExternalPosting(accountId, value, kind, category, reference, true)) return true;
            return false;
        }
        return deposit(accountId,value,kind,category,reference,worldMinute,memo);
    
        }}
    public synchronized boolean withdraw(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
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
    public synchronized boolean withdrawOnce(String accountId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        if (hasTransaction(accountId, kind, reference)) {
            if (matchesExternalPosting(accountId, value, kind, category, reference, false)) return true;
            return false;
        }
        return withdraw(accountId,value,kind,category,reference,worldMinute,memo);
    
        }}
    /** Atomic transfer: no balance changes occur unless both accounts and currency match and payer can cover it. */
    public synchronized boolean transfer(String fromId,String toId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
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

    public synchronized boolean transferOnce(String fromId,String toId,double value,Kind kind,String category,String reference,long worldMinute,String memo) {
        try (MutationBatch ignored = beginMutationBatch()) {
        if (hasTransaction(fromId, kind, reference)) {
            return matchesExistingTransfer(fromId,toId,value,kind,category,reference);
        }
        if (hasTransaction(toId, kind, reference))
            return fail("idempotency key collision: destination already has a posting for this reference");
        return transfer(fromId,toId,value,kind,category,reference,worldMinute,memo);
    
        }}
    /** Creates matched cash and debt; unlike a deposit, loan proceeds do not repay existing liabilities. */
    public synchronized boolean borrow(String accountId,double value,long worldMinute,String reference) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        value=amount(value); minute(worldMinute);
        if(a.debt+value>a.creditLimit+1e-9) return fail("credit limit exceeded");
        if(value==0) return true;
        a.balance+=value; a.debt+=value;
        append("tx:"+transactionSequence++,a.id,"lender",a.currency,value,true,Kind.LOAN,"finance",clean(reference),worldMinute,a.balance,"loan proceeds");
        updateBankruptcy(a); return true;
    
        }}
    public synchronized boolean accrueInterest(String accountId,double rate,long worldMinute,String reference) {
        try (MutationBatch ignored = beginMutationBatch()) {
        Account a=accounts.get(accountId); if(a==null) return fail("unknown account");
        if(!Double.isFinite(rate)||rate<0||rate>1) throw new IllegalArgumentException("interest rate must be 0..1");
        if(a.debt<=0 || rate==0) return true;
        double interest=a.debt*rate;
        a.debt+=interest;
        append("tx:"+transactionSequence++,a.id,"external",a.currency,interest,false,Kind.INTEREST,"finance",clean(reference),minute(worldMinute),a.balance,"debt interest");
        updateBankruptcy(a); return true;
    
        }}
    public synchronized boolean repayDebt(String accountId,double value,long worldMinute,String reference) {
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
    public synchronized void writeJson(java.io.Writer writer) throws java.io.IOException {
        writeJsonContents(writer);
    }

    public synchronized long writeJsonVersioned(java.io.Writer writer) throws java.io.IOException {
        writeJsonContents(writer);
        return mutationVersion;
    }

    private void writeJsonContents(java.io.Writer writer) throws java.io.IOException {
        writer.write("{\"format\":\"wf-treasury-ledger/1\",\"sequence\":");
        writer.write(Long.toString(sequence));
        writer.write(",\"transactionSequence\":");
        writer.write(Long.toString(transactionSequence));
        writer.write(",\"accounts\":[");
        boolean first = true;
        for (Account account : accounts.values()) {
            if (!first) writer.write(',');
            first = false;
            writer.write("{\"id\":"); Json.writeString(writer, account.id);
            writer.write(",\"owner\":"); Json.writeString(writer, account.ownerId);
            writer.write(",\"currency\":"); Json.writeString(writer, account.currency);
            writer.write(",\"balance\":"); Json.writeNumber(writer, account.balance);
            writer.write(",\"creditLimit\":"); Json.writeNumber(writer, account.creditLimit);
            writer.write(",\"debt\":"); Json.writeNumber(writer, account.debt);
            writer.write(",\"bankrupt\":"); writer.write(Boolean.toString(account.bankrupt));
            writer.write('}');
        }
        writer.write("],\"journal\":[");
        first = true;
        for (Entry entry : journal) {
            if (!first) writer.write(',');
            first = false;
            writer.write("{\"seq\":"); Json.writeNumber(writer, entry.sequence());
            writer.write(",\"tx\":"); Json.writeString(writer, entry.transactionId());
            writer.write(",\"account\":"); Json.writeString(writer, entry.accountId());
            writer.write(",\"other\":"); Json.writeString(writer, entry.counterpartyId());
            writer.write(",\"currency\":"); Json.writeString(writer, entry.currency());
            writer.write(",\"amount\":"); Json.writeNumber(writer, entry.amount());
            writer.write(",\"credit\":"); writer.write(Boolean.toString(entry.credit()));
            writer.write(",\"kind\":"); Json.writeString(writer, entry.kind().name());
            writer.write(",\"category\":"); Json.writeString(writer, entry.category());
            writer.write(",\"reference\":"); Json.writeString(writer, entry.reference());
            writer.write(",\"minute\":"); Json.writeNumber(writer, entry.worldMinute());
            writer.write(",\"balance\":"); Json.writeNumber(writer, entry.balanceAfter());
            writer.write(",\"memo\":"); Json.writeString(writer, entry.memo());
            writer.write('}');
        }
        writer.write("]}");
    }

    public synchronized String toJson() {
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
            if (seq <= previousSequence || seq < 1 || worldMinute < 0 || (value <= 0 && !(value == 0 && (kind == Kind.ACCOUNT_CREATED || kind == Kind.CREDIT_LIMIT_CHANGED))) || balanceAfter < 0)
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
