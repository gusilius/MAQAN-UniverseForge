package dev.worldforge.mod.api;

import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.economy.TreasuryService;

import java.util.*;

/**
 * Compatibility facade for realm finance. Public amounts remain in gold for existing UI,
 * while the canonical ledger stores silver as the world base unit (5 silver = 1 gold).
 */
public final class TreasuryApi {
    public interface Provider {
        boolean has(String realm);
        double balance(String realm);
        void add(String realm, double delta);
    }

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Double>> LEGACY_LEDGER = new HashMap<>();
    private static final double SILVER_PER_GOLD = 5.0;

    private TreasuryApi() { }

    /** Kept for addon binary/source compatibility; new providers should use TreasuryService accounts. */
    @Deprecated public static synchronized void register(String id, Provider provider) {
        if (id != null && provider != null) PROVIDERS.put(id, provider);
    }

    /** Test-only reset. Runtime world loading replaces the service from its persisted ledger. */
    public static synchronized void resetForTests() {
        PROVIDERS.clear();
        LEGACY_LEDGER.clear();
        TreasuryService.replace(new TreasuryLedger());
    }

    private static String accountId(String realm) {
        if (realm == null || realm.isBlank()) throw new IllegalArgumentException("realm is required");
        return "realm:" + realm;
    }

    private static TreasuryLedger ledger() { return TreasuryService.current(); }

    private static TreasuryLedger.Account ensureRealm(String realm) {
        String id = accountId(realm);
        TreasuryLedger.Account account = ledger().account(id);
        if (account == null) account = ledger().open(id, realm, "silver", 0);
        ledger().setCreditLimit(id, 250.0 * SILVER_PER_GOLD);
        return account;
    }

    /**
     * Opens the canonical realm account once. Army legacy balances are denominated in gold;
     * the world ledger stores silver (5 silver = 1 gold) and represents old deficits as debt.
     */
    public static synchronized boolean migrateLegacyRealmBalance(String realm, double legacyGold) {
        if (realm == null || realm.isBlank() || !Double.isFinite(legacyGold)) return false;
        String id = accountId(realm);
        if (!ledger().hasAccount(id)) ledger().open(id, realm, "silver", 0);
        ledger().setCreditLimit(id, 250.0 * SILVER_PER_GOLD);
        double silver = Math.abs(legacyGold) * SILVER_PER_GOLD;
        if (silver <= 1e-9) return true;
        String reference = "legacy-army-balance:" + realm;
        boolean posted = legacyGold > 0
                ? ledger().depositOnce(id, silver, TreasuryLedger.Kind.IMPORT, "legacy-army-balance",
                        reference, worldMinute(), "migrate legacy army gold balance")
                : ledger().withdrawOnce(id, silver, TreasuryLedger.Kind.OPENING, "legacy-army-deficit",
                        reference, worldMinute(), "migrate legacy negative army balance");
        if (!posted) throw new IllegalStateException("Cannot migrate legacy army balance for " + realm + ": " + ledger().lastError());
        return true;
    }
    /** Creates a new realm account with an opening gold balance if no canonical account exists. */
    public static synchronized void ensureRealmAccount(String realm, double openingGold) {
        if (realm == null || realm.isBlank() || !Double.isFinite(openingGold) || openingGold < 0)
            throw new IllegalArgumentException("valid realm and nonnegative opening gold are required");
        String id = accountId(realm);
        TreasuryLedger.Account account = ledger().account(id);
        if (account == null) account = ledger().open(id, realm, "silver", openingGold * SILVER_PER_GOLD);
        else if (openingGold > 0 && account.balance <= 1e-9 && account.debt <= 1e-9
                && ledger().journal().stream().noneMatch(entry -> id.equals(entry.accountId()))) {
            ledger().depositOnce(id, openingGold * SILVER_PER_GOLD, TreasuryLedger.Kind.OPENING,
                    "realm-opening", "realm-opening:" + realm, worldMinute(), "initial realm treasury");
        }
        ledger().setCreditLimit(id, 250.0 * SILVER_PER_GOLD);
    }

    /** Net gold balance includes any carried deficit as debt, preserving the legacy army economy contract. */
    public static synchronized boolean hasRealmAccount(String realm) {
        return realm != null && !realm.isBlank() && ledger().hasAccount(accountId(realm));
    }

    public static synchronized double netBalance(String realm) {
        if (realm == null || realm.isBlank()) return 0;
        TreasuryLedger.Account account = ensureRealm(realm);
        return (account.balance - account.debt) / SILVER_PER_GOLD;
    }

    /** Idempotent gold-denominated posting used by daily army income and upkeep. */
    public static synchronized boolean postDeltaOnce(String realm, double deltaGold, String label,
                                                       String reference, long worldMinute) {
        if (realm == null || realm.isBlank() || !Double.isFinite(deltaGold) || !Double.isFinite(worldMinute)
                || worldMinute < 0 || reference == null || reference.isBlank()) return false;
        ensureRealmAccount(realm, 0);
        double silver = Math.abs(deltaGold) * SILVER_PER_GOLD;
        if (silver <= 1e-9) return true;
        String category = label == null || label.isBlank() ? "army" : label;
        return deltaGold > 0
                ? ledger().depositOnce(accountId(realm), silver, TreasuryLedger.Kind.DEPOSIT,
                        category, reference, worldMinute, "army treasury posting")
                : ledger().withdrawOnce(accountId(realm), silver, TreasuryLedger.Kind.ARMY_UPKEEP,
                        category, reference, worldMinute, "army treasury posting");
    }

    /** Idempotent spending for a named military transaction. */
    public static synchronized boolean spendOnce(String realm, double amountGold, String label,
                                                  String reference, long worldMinute) {
        if (!Double.isFinite(amountGold) || amountGold < 0) return false;
        return postDeltaOnce(realm, -amountGold, label, reference, worldMinute);
    }

    private static long worldMinute() {
        return WorldForgeApi.clock().worldMinute();
    }

    public static synchronized double balance(String realm) {
        if (realm == null || realm.isBlank()) return 0;
        TreasuryLedger.Account account = ledger().account(accountId(realm));
        return account == null ? 0 : (account.balance - account.debt) / SILVER_PER_GOLD;
    }

    /** Adds/removes gold from the canonical realm account; negative values cannot bypass credit limits. */
    public static synchronized void add(String realm, double delta, String label) {
        if (realm == null || realm.isBlank() || !Double.isFinite(delta) || delta == 0) return;
        ensureRealm(realm);
        double silver = Math.abs(delta) * SILVER_PER_GOLD;
        String category = label == null || label.isBlank() ? "other" : label;
        String reference = "treasury-api:" + realm + ":" + category;
        boolean ok = delta > 0
                ? ledger().deposit(accountId(realm), silver, TreasuryLedger.Kind.DEPOSIT, category, reference, worldMinute(), "TreasuryApi gold income")
                : ledger().withdraw(accountId(realm), silver, TreasuryLedger.Kind.WITHDRAWAL, category, reference, worldMinute(), "TreasuryApi gold expense");
        if (!ok) return;
    }

    public static synchronized boolean spend(String realm, double amount, String label) {
        if (!Double.isFinite(amount) || amount < 0 || balance(realm) + 1e-9 < amount) return false;
        if (amount == 0) return true;
        double before = balance(realm);
        add(realm, -amount, label);
        return balance(realm) <= before - amount + 1e-9;
    }

    /** Transfer settlement silver into its realm treasury; conversion to gold is a display-only unit conversion. */
    public static synchronized boolean remitSettlement(String settlementAccountId, String realm, double silver, String label) {
        if (settlementAccountId == null || settlementAccountId.isBlank() || !Double.isFinite(silver) || silver < 0) return false;
        ensureRealm(realm);
        String source = settlementAccountId;
        if (!ledger().hasAccount(source)) return false;
        return ledger().transfer(source, accountId(realm), silver, TreasuryLedger.Kind.TAX,
                label, "city-tax:" + settlementAccountId + ":" + realm, worldMinute(), "settlement crown tax");
    }

    /** Atomic realm-to-settlement grant in silver base units. */
    public static synchronized boolean grantSettlement(String realm, String settlementAccountId, double silver, String label) {
        if (settlementAccountId == null || settlementAccountId.isBlank() || !Double.isFinite(silver) || silver < 0) return false;
        ensureRealm(realm);
        String target = settlementAccountId;
        if (!ledger().hasAccount(target)) return false;
        return ledger().transfer(accountId(realm), target, silver, TreasuryLedger.Kind.GRANT,
                label, "city-grant:" + realm + ":" + settlementAccountId, worldMinute(), "realm grant to settlement");
    }

    /** Source-category projection of the canonical journal, displayed in gold. */
    public static synchronized Map<String, Double> ledger(String realm) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (realm == null || realm.isBlank()) return result;
        String id = accountId(realm);
        for (TreasuryLedger.Entry entry : ledger().journal()) {
            if (!id.equals(entry.accountId()) || !"silver".equals(entry.currency())) continue;
            double signed = (entry.credit() ? 1 : -1) * entry.amount() / SILVER_PER_GOLD;
            result.merge(entry.category().isBlank() ? "Прочее" : entry.category(), signed, Double::sum);
        }
        LEGACY_LEDGER.getOrDefault(realm, Map.of()).forEach((key, value) -> result.putIfAbsent(key, value));
        return result;
    }

    /** Legacy summary import retained for old UI save migrations; the account balance remains canonical. */
    public static synchronized void setLedger(String realm, Map<String, Double> entries) {
        LEGACY_LEDGER.put(realm, new LinkedHashMap<>(entries));
    }

    public static synchronized Map<String, Map<String, Double>> allLedgers() {
        Set<String> realms = new LinkedHashSet<>(LEGACY_LEDGER.keySet());
        for (TreasuryLedger.Entry entry : ledger().journal()) {
            if (entry.accountId().startsWith("realm:")) realms.add(entry.accountId().substring("realm:".length()));
        }
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (String realm : realms) result.put(realm, ledger(realm));
        return result;
    }
}
