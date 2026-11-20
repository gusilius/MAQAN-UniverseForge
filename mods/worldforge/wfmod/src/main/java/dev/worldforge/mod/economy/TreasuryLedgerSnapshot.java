package dev.worldforge.mod.economy;

import dev.worldforge.mod.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checksummed checkpoint payload for treasury account state and idempotency indexes.
 * It intentionally does not replace the audit archive: history remains append-only and
 * must be retained/replayed separately before a compacted ledger can become authoritative.
 */
public final class TreasuryLedgerSnapshot {
    public static final String FORMAT = "wf-treasury-snapshot/1";

    public record AccountState(String id, String ownerId, String currency, double balance,
                               double creditLimit, double debt, boolean bankrupt) { }
    public record State(long nextSequence, long nextTransactionSequence, long mutationVersion,
                        List<AccountState> accounts, Set<String> postedReferences,
                        Set<String> postedDebits, Set<String> postedCredits,
                        Set<String> armyFieldRationDebitAccounts) {
        public State {
            accounts = List.copyOf(accounts);
            postedReferences = Set.copyOf(postedReferences);
            postedDebits = Set.copyOf(postedDebits);
            postedCredits = Set.copyOf(postedCredits);
            armyFieldRationDebitAccounts = Set.copyOf(armyFieldRationDebitAccounts);
        }
    }

    private TreasuryLedgerSnapshot() { }

    public static String capture(TreasuryLedger ledger) {
        if (ledger == null) throw new IllegalArgumentException("Treasury ledger is required");
        return encode(ledger.checkpointState());
    }

    public static String encode(State state) {
        validate(state);
        Map<String, Object> payload = payload(state);
        String checksum = sha256(Json.write(payload));
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("sha256", checksum);
        return Json.write(envelope);
    }

    public static State decode(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root) || !FORMAT.equals(root.get("format")))
            throw new IllegalArgumentException("Unsupported treasury snapshot format");
        long nextSequence = requiredLong(root.get("nextSequence"), "next sequence");
        long nextTransactionSequence = requiredLong(root.get("nextTransactionSequence"), "next transaction sequence");
        long mutationVersion = requiredLong(root.get("mutationVersion"), "mutation version");
        if (!(root.get("accounts") instanceof List<?> accountRows)
                || !(root.get("postedReferences") instanceof List<?> references)
                || !(root.get("postedDebits") instanceof List<?> debits)
                || !(root.get("postedCredits") instanceof List<?> credits)
                || !(root.get("armyFieldRationDebitAccounts") instanceof List<?> rationAccounts))
            throw new IllegalArgumentException("Treasury snapshot is missing required state arrays");

        List<AccountState> accounts = new ArrayList<>(accountRows.size());
        for (Object row : accountRows) {
            if (!(row instanceof Map<?, ?> account)) throw new IllegalArgumentException("Invalid treasury snapshot account");
            accounts.add(new AccountState(requiredString(account.get("id"), "account id"),
                    requiredString(account.get("owner"), "account owner"),
                    requiredString(account.get("currency"), "account currency"),
                    requiredNumber(account.get("balance"), "account balance"),
                    requiredNumber(account.get("creditLimit"), "account credit limit"),
                    requiredNumber(account.get("debt"), "account debt"),
                    requiredBoolean(account.get("bankrupt"), "account bankrupt flag")));
        }
        State state = new State(nextSequence, nextTransactionSequence, mutationVersion, accounts,
                stringSet(references, "posted reference"), stringSet(debits, "posted debit"),
                stringSet(credits, "posted credit"), stringSet(rationAccounts, "ration account"));
        validate(state);
        String expected = requiredString(root.get("sha256"), "snapshot checksum");
        String actual = sha256(Json.write(payload(state)));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Treasury snapshot checksum mismatch");
        return state;
    }

    private static Map<String, Object> payload(State state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("nextSequence", state.nextSequence());
        root.put("nextTransactionSequence", state.nextTransactionSequence());
        root.put("mutationVersion", state.mutationVersion());
        List<Map<String, Object>> accounts = new ArrayList<>();
        state.accounts().stream().sorted(Comparator.comparing(AccountState::id)).forEach(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.id()); row.put("owner", a.ownerId()); row.put("currency", a.currency());
            row.put("balance", a.balance()); row.put("creditLimit", a.creditLimit());
            row.put("debt", a.debt()); row.put("bankrupt", a.bankrupt());
            accounts.add(row);
        });
        root.put("accounts", accounts);
        root.put("postedReferences", sorted(state.postedReferences()));
        root.put("postedDebits", sorted(state.postedDebits()));
        root.put("postedCredits", sorted(state.postedCredits()));
        root.put("armyFieldRationDebitAccounts", sorted(state.armyFieldRationDebitAccounts()));
        return root;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static Set<String> stringSet(List<?> values, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            String text = requiredString(value, field);
            if (!result.add(text)) throw new IllegalArgumentException("Duplicate treasury snapshot " + field);
        }
        return result;
    }

    private static void validate(State state) {
        if (state.nextSequence() < 1 || state.nextTransactionSequence() < 1 || state.mutationVersion() < 0)
            throw new IllegalArgumentException("Invalid treasury snapshot sequence metadata");
        Set<String> accountIds = new LinkedHashSet<>();
        for (AccountState a : state.accounts()) {
            if (a.id() == null || a.id().isBlank() || a.ownerId() == null || a.ownerId().isBlank()
                    || a.currency() == null || a.currency().isBlank() || !accountIds.add(a.id())
                    || !Double.isFinite(a.balance()) || !Double.isFinite(a.creditLimit())
                    || !Double.isFinite(a.debt()) || a.balance() < 0 || a.creditLimit() < 0 || a.debt() < 0)
                throw new IllegalArgumentException("Invalid or duplicate treasury snapshot account");
            boolean expectedBankrupt = a.debt() > a.creditLimit() + 1e-9;
            if (a.bankrupt() != expectedBankrupt)
                throw new IllegalArgumentException("Treasury snapshot bankruptcy flag does not match account debt");
        }
        if (!state.postedReferences().containsAll(state.postedDebits())
                || !state.postedReferences().containsAll(state.postedCredits()))
            throw new IllegalArgumentException("Treasury snapshot posting indexes are inconsistent");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 512)
            throw new IllegalArgumentException("Invalid treasury snapshot " + field);
        return text;
    }
    private static boolean requiredBoolean(Object value, String field) {
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Invalid treasury snapshot " + field);
        return flag;
    }
    private static long requiredLong(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 0
                || n.doubleValue() > Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid treasury snapshot " + field);
        return n.longValue();
    }
    private static double requiredNumber(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException("Invalid treasury snapshot " + field);
        return n.doubleValue();
    }
}
