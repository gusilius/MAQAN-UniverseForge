package dev.worldforge.settle;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.economy.TreasuryService;
import dev.worldforge.mod.economy.TreasuryLedger;

import java.util.*;

/**
 * Поселение как числа: склад, здания, рабочие. Считается шагами {@link #tick}, поэтому тянет сотни поселений на сервере;
 * тела рабочих и стройка блоков в мире лишь показывают это состояние (их делает слой Minecraft, пока игрок рядом).
 * Приказы как в RTS: {@link #place}, {@link #assign}, {@link #setProfession}, {@link #setPriority}, {@link #cancel}.
 */
public final class Settlement {
    /** Persisted departures bridge settlement population changes to the canonical Character registry. */
    public record WorkerDeparture(String personId, String name, String reason) { }
    public final List<WorkerDeparture> departures = new ArrayList<>();
    /** Durable idempotency receipts for autonomous placement requests. */
    private final Map<String, String> placementReceipts = new TreeMap<>();

    public static final double BASE_CAP = 100, EAT = 0.02, GROW_EVERY = 45, STARVE_EVERY = 30, MAX_STEP = 2, MAX_TICK = 86400;
    public static final int BASE_HOUSING = 4, LOG_MAX = 60;
    public static final double LOCAL_BUFFER_CAP = 50, HAUL_RATE = 0.5;
    /** Скорость рабочего (блоков в секунду), точка появления новых жителей, пороги настроения. */
    public static final double WORKER_SPEED = 2.5, SPAWN_X = -16, SPAWN_Z = 0, LEAVE_EVERY = 20;
    /** Торговля и казна: наценка рынка, налог с жителя в минуту (на уровне «обычный»), множители уровней налога. */
    public static final double TRADE_MARGIN = 0.25, TAX_PER_MIN = 0.6;
    public static final double[] TAX_MULT = {0, 0.5, 1, 1.7};
    public static final String[] TAX_NAMES = {"Нет", "Низкий", "Обычный", "Высокий"};
    public static final int APPROVAL_BASE = 50, APPROVAL_GROWTH_MIN = 40, APPROVAL_LEAVE_BELOW = 15;

    public static final class OrderException extends RuntimeException {
        public OrderException(String m) { super(m); }
    }

    /** Строка разбора настроения: причина и вклад (может быть отрицательным). */
    public static final class Factor {
        public final String label;
        public final int value;

        Factor(String label, int value) { this.label = label; this.value = value; }
    }

    public final String id;
    /** Dimension/world namespace prevents settlement IDs from colliding across maps. */
    private String ledgerScope = "";
    public String name;
    public final Defs defs;
    public final Map<String, Double> stock = new TreeMap<>();
    /** Number of world-ledger journal entries represented by the last durable stock snapshot; -1 means legacy. */
    private long stockLedgerEntries = -1;
    private boolean stockReconciliationChecked;
    private boolean reconcileStockMirror;
    public final List<Building> buildings = new ArrayList<>();
    public final List<Worker> workers = new ArrayList<>();
    public final List<String> log = new ArrayList<>();
    public boolean auto = true;
    /** Уровень налога 0..3, правила автоторговли (ресурс -> порог: продавать всё выше / докупать до), запас торговых операций. */
    public int taxLevel = 2;
    /** Налоги, собранные с прошлой отправки в казну; доля короны (в процентах) и итоги по казне (серебро). */
    public double taxCollected, remittedSilver, grantedSilver;
    public int crownShare = 25;
    public final Map<String, Integer> autoSell = new TreeMap<>(), autoBuy = new TreeMap<>();
    public double tradeLeft;
    public double time;
    private int nextBuilding = 1, nextWorker = 1;
    public final Flow flow = new Flow();
    public final ResidentTaskGraph taskGraph = new ResidentTaskGraph();
    private double growTimer, starveTimer, leaveTimer;
    /** -1 keeps legacy worker-count semantics; >=0 is the authoritative canonical resident count. */
    private int canonicalPopulation = -1;

    public int population() { return canonicalPopulation >= 0 ? canonicalPopulation : workers.size(); }
    public boolean hasCanonicalPopulation() { return canonicalPopulation >= 0; }
    public void setCanonicalPopulation(int residents) {
        if (residents < 0 || residents > 100_000) throw new IllegalArgumentException("canonical population out of range");
        canonicalPopulation = residents;
        growTimer = 0;
    }

    // ---- времена года и набеги
    public static final double SEASON_LEN = 5475;                   // 91.25 world days at 60 simulation seconds/day
    private long externalWorldMinute = -1;
    private int externalWorldYear = -1;
    public void setWorldCalendar(long worldMinute, int worldYear) {
        if (worldMinute < 0 || worldYear < 1) throw new IllegalArgumentException("invalid world calendar");
        externalWorldMinute = worldMinute;
        externalWorldYear = worldYear;
    }
    public long worldMinute() { return externalWorldMinute >= 0 ? externalWorldMinute : Math.max(0L, (long) Math.floor(time * 24.0)); }
    public static final String[] SEASONS = {"Весна", "Лето", "Осень", "Зима"};
    public static final double[] FARM_MULT = {1.0, 1.25, 1.5, 0.2};   // выход зданий без сырья в категории «food»
    public static final double RAID_FIRST = 1500, RAID_EVERY = 900, RAID_WARN = 90, RAID_MIN_PEOPLE = 8;
    public double raidTimer = RAID_FIRST, raidStrength, raidWarnLeft;
    public boolean raidIncoming;
    public int raidsRepelled, raidsLost;
    public boolean raids = true;
    public int seasonIndex() {
        if (externalWorldMinute >= 0) return (int) Math.min(3L, (externalWorldMinute / 1440L % 365L) / 91L);
        return (int) (time / SEASON_LEN) % 4;
    }
    public int year() {
        if (externalWorldYear >= 0) return externalWorldYear;
        return (int) (time / (SEASON_LEN * 4)) + 1;
    }
    public double seasonProgress() {
        if (externalWorldMinute >= 0) {
            long day = externalWorldMinute / 1440L % 365L;
            int season = seasonIndex();
            int start = season * 91;
            int duration = season == 3 ? 92 : 91;
            return Math.max(0, Math.min(1, (day - start + (externalWorldMinute % 1440L) / 1440.0) / duration));
        }
        return (time % SEASON_LEN) / SEASON_LEN;
    }
    public String seasonName() { return SEASONS[seasonIndex()]; }
    public double seasonMult(Building b) { return b.def.category.equals("food") && b.def.in.isEmpty() ? FARM_MULT[seasonIndex()] : 1.0; }
    /** Богатство, привлекающее налётчиков: запасы и построенное. */
    public double wealth() {
        double w = amount(Defs.SILVER) * 1.0 + amount("food") * 0.5;
        for (Building b : buildings) if (b.state == Building.State.DONE) w += 6;
        return w;
    }
    /** Оборона: стража (3 за человека), башни (+6 за каждую, если рядом есть хоть один страж). */
    public double defense() {
        int guards = 0;
        for (Worker w : workers) if (w.profession.equals("guard")) guards++;
        return guards * 3.0 + (guards > 0 ? count("watchtower", true) * 6.0 : count("watchtower", true) * 2.0)
                + effect("fortification") * 2.0 + effect("military");
    }
    private void raidStep(double dt) {
        if (!raids || workers.size() < RAID_MIN_PEOPLE) return;
        if (raidIncoming) {
            raidWarnLeft -= dt;
            if (raidWarnLeft > 0) return;
            raidIncoming = false;
            double def = defense();
            if (def >= raidStrength) {
                raidsRepelled++;
                say("Набег отбит: стража " + Math.round(def) + " против " + Math.round(raidStrength));
            } else {
                raidsLost++;
                double k = Math.min(0.6, 0.15 + (raidStrength - def) / Math.max(1, raidStrength) * 0.5);
                String lost = "";
                for (String r : new String[]{Defs.SILVER, "food", "wood", "planks"}) {
                    double take = Math.floor(amount(r) * k);
                    if (take > 0) {
                        debitResource(r, take, TreasuryLedger.Kind.WRITE_OFF, "raid-loss", r);
                        flow.record(time, r, take, false);
                        lost += (lost.isEmpty() ? "" : ", ") + (int) take + " " + defs.resourceNames.getOrDefault(r, r);
                    }
                }
                int dead = (int) Math.min(workers.size() - 1, Math.round((raidStrength - def) / 12.0));
                for (int i = 0; i < dead && workers.size() > 1; i++) removeWorker(workers.get(workers.size() - 1), "death:raid");
                say("Налёт! Унесли: " + (lost.isEmpty() ? "ничего" : lost) + (dead > 0 ? "; погибло жителей: " + dead : ""));
            }
            raidTimer = RAID_EVERY;
            return;
        }
        raidTimer -= dt;
        if (raidTimer > 0) return;
        raidStrength = Math.round((6 + wealth() / 18.0) * 10) / 10.0;
        raidIncoming = true; raidWarnLeft = RAID_WARN;
        say("Дозорные видят налётчиков: сила " + Math.round(raidStrength) + ", будут через " + (int) RAID_WARN + " с");
    }

    public Settlement(String id, String name, Defs defs) { this.id = id; this.name = name; this.defs = defs; }

    public void setLedgerScope(String scope) { ledgerScope = scope == null ? "" : scope.trim(); }
    public String ledgerOwnerId() { return ledgerScope.isBlank() ? id : ledgerScope + ":" + id; }
    public String ledgerAccountId(String resource) { return accountId(resource); }

    // ------------------------------------------------------------------ склад

    public double capacity(String res) {
        if (res.equals(Defs.SILVER)) return 1e9;                       // казна не ограничена складом
        double c = BASE_CAP;
        for (Building b : buildings) if (b.state == Building.State.DONE) c += b.def.storage.getOrDefault(res, 0);
        return c;
    }

    private String accountId(String resource) {
        String owner = ledgerOwnerId();
        return Defs.SILVER.equals(resource) ? "settlement:" + owner : "stock:" + owner + ":" + resource;
    }

    private String legacyAccountId(String resource) {
        return Defs.SILVER.equals(resource) ? "settlement:" + id : "stock:" + id + ":" + resource;
    }

    /** Every settlement resource, including silver, is an account in the shared economic ledger. */
    private TreasuryLedger.Account resourceAccount(String resource) {
        TreasuryLedger ledger = TreasuryService.current();
        String accountId = accountId(resource);
        TreasuryLedger.Account account = ledger.account(accountId);
        if (account == null && !ledgerScope.isBlank()) {
            TreasuryLedger.Account legacy = ledger.account(legacyAccountId(resource));
            account = ledger.open(accountId, ledgerOwnerId(), resource, 0);
            if (legacy != null && legacy.balance > 0) {
                ledger.transfer(legacy.id, accountId, legacy.balance, TreasuryLedger.Kind.TRANSFER_IN,
                        "settlement-scope-migration", id + ":" + resource,
                        Math.max(0, (long) worldMinute()), "migrate legacy unscoped settlement account");
            } else if (legacy == null) {
                account = ledger.open(accountId, ledgerOwnerId(), resource, stock.getOrDefault(resource, 0.0));
            }
        } else if (account == null) account = ledger.open(accountId, ledgerOwnerId(), resource, stock.getOrDefault(resource, 0.0));
        return account;
    }

    private void syncResourceMirror(String resource) { stock.put(resource, resourceAccount(resource).balance); }

    private void syncAllResourceMirrors() {
        Set<String> resources = new TreeSet<>(stock.keySet());
        resources.add(Defs.SILVER);
        for (String resource : resources) syncResourceMirror(resource);
    }

    public double amount(String resource) {
        if (resource == null || resource.isBlank()) return 0;
        TreasuryLedger ledger = TreasuryService.current();
        String sourceAccountId = accountId(resource);
        if (!stockReconciliationChecked) {
            if (stockLedgerEntries >= 0) {
                // The settlement file may have been committed after the treasury file.
                // Decide once per loaded snapshot, before any imports append reconciliation entries.
                reconcileStockMirror = stockLedgerEntries > ledger.journal().size();
            } else {
                // Legacy snapshots have no sequence marker. Keep their old one-time mirror
                // reconciliation unless a durable army food transfer proves the ledger is newer.
                reconcileStockMirror = ledger.journal().stream().noneMatch(entry ->
                        entry.accountId().equals(sourceAccountId) && !entry.credit()
                                && entry.kind() == TreasuryLedger.Kind.LOGISTICS
                                && entry.category().equals("army-field-rations"));
            }
            stockReconciliationChecked = true;
        }

        TreasuryLedger.Account account = resourceAccount(resource);
        Double legacyMirror = stock.get(resource);
        boolean committedArmyFoodTransfer = ledger.journal().stream().anyMatch(entry ->
                entry.accountId().equals(sourceAccountId) && !entry.credit()
                        && entry.kind() == TreasuryLedger.Kind.LOGISTICS
                        && entry.category().equals("army-field-rations"));
        if (reconcileStockMirror && !committedArmyFoodTransfer
                && legacyMirror != null && Double.isFinite(legacyMirror)
                && Math.abs(legacyMirror - account.balance) > 1e-9) {
            double delta = legacyMirror - account.balance;
            if (delta > 0) ledger.deposit(sourceAccountId, delta, TreasuryLedger.Kind.IMPORT,
                    "legacy-stock-mirror", "settlement:" + id + ":snapshot-reconcile",
                    worldMinute(), "reconcile legacy settlement snapshot newer than treasury");
            else ledger.withdraw(sourceAccountId, -delta, TreasuryLedger.Kind.WRITE_OFF,
                    "legacy-stock-mirror", "settlement:" + id + ":snapshot-reconcile",
                    worldMinute(), "reconcile legacy settlement snapshot newer than treasury");
            account = resourceAccount(resource);
        }
        stock.put(resource, account.balance);
        return account.balance;
    }

    private boolean debitResource(String resource, double value, TreasuryLedger.Kind kind, String category, String reference) {
        if (!Double.isFinite(value) || value < 0) return false;
        if (value == 0) return true;
        amount(resource);
        resourceAccount(resource);
        boolean ok = TreasuryService.current().withdraw(accountId(resource), value, kind, category, reference,
                worldMinute(), category);
        syncResourceMirror(resource);
        // Retire task-graph provenance only after the canonical stock debit commits.
        // Construction and recipe inputs keep their richer stage-specific receipts below.
        if (ok && !"construction".equals(category) && !"production-input".equals(category))
            taskGraph.consumeStock(resource, value);
        return ok;
    }

    private double creditResource(String resource, double value, TreasuryLedger.Kind kind, String category, String reference) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        amount(resource);
        resourceAccount(resource);
        TreasuryLedger ledger = TreasuryService.current();
        double before = ledger.balance(accountId(resource));
        if (!ledger.deposit(accountId(resource), value, kind, category, reference,
                worldMinute(), category)) return 0;
        syncResourceMirror(resource);
        return Math.max(0, ledger.balance(accountId(resource)) - before);
    }

    private boolean debitSilver(double value, TreasuryLedger.Kind kind, String category, String reference) {
        return debitResource(Defs.SILVER, value, kind, category, reference);
    }

    private double creditSilver(double value, TreasuryLedger.Kind kind, String category, String reference) {
        return creditResource(Defs.SILVER, value, kind, category, reference);
    }

    /** Atomic fractional withdrawal for individual Character actions and production inputs. */
    public boolean consumeResource(String resource, double quantity) {
        if (resource == null || resource.isBlank() || !Double.isFinite(quantity) || quantity < 0) return false;
        if (amount(resource) + 1e-9 < quantity) return false;
        if (quantity == 0) return true;
        if (!debitResource(resource, quantity, Defs.SILVER.equals(resource)
                ? TreasuryLedger.Kind.WITHDRAWAL : TreasuryLedger.Kind.CONSUMPTION,
                "consumption", "settlement-consumption")) return false;
        flow.record(time, resource, quantity, false);
        return true;
    }

    /** Deposits output or income into the shared resource ledger, respecting physical stock capacity. */
    public double add(String resource, double quantity) {
        return addResourceWithKind(resource, quantity, Defs.SILVER.equals(resource)
                ? TreasuryLedger.Kind.DEPOSIT : TreasuryLedger.Kind.IMPORT, "settlement-stock-in", "settlement:" + id);
    }

    /**
     * Crash-safe one-time endowment for autonomous settlement founding.
     * The TreasuryLedger receipt is authoritative, so retries cannot mint the
     * same founding stock twice even when the settlement snapshot is replayed.
     */
    public boolean addOnce(String resource, double quantity, String operationId) {
        if (resource == null || resource.isBlank() || operationId == null
                || !operationId.matches("[A-Za-z0-9_.:-]{1,128}")
                || !Double.isFinite(quantity) || quantity <= 0) return false;
        amount(resource);
        String reference = "worldgen:" + id + ":" + operationId + ":" + resource;
        TreasuryLedger ledger = TreasuryService.current();
        boolean alreadyPosted = ledger.hasTransaction(accountId(resource), Defs.SILVER.equals(resource)
                ? TreasuryLedger.Kind.DEPOSIT : TreasuryLedger.Kind.IMPORT, reference);
        boolean accepted = ledger.depositOnce(accountId(resource), quantity,
                Defs.SILVER.equals(resource) ? TreasuryLedger.Kind.DEPOSIT : TreasuryLedger.Kind.IMPORT,
                "autonomous-settlement-founding", reference, worldMinute(),
                "one-time founding endowment for " + id);
        if (!accepted) return false;
        syncResourceMirror(resource);
        if (!alreadyPosted) flow.record(time, resource, quantity, true);
        return true;
    }

    private double addResourceWithKind(String resource, double quantity, TreasuryLedger.Kind kind, String category, String reference) {
        if (!Double.isFinite(quantity) || quantity <= 0) return 0;
        double current = amount(resource);
        double accepted = Math.max(0, Math.min(quantity, capacity(resource) - current));
        return accepted <= 0 ? 0 : creditResource(resource, accepted, kind, category, reference);
    }

    /** Record locally produced goods in the durable ledger. */
    private double produceResource(String resource, double quantity) {
        if (!Double.isFinite(quantity) || quantity <= 0) return 0;
        double current = amount(resource);
        double accepted = Math.max(0, Math.min(quantity, capacity(resource) - current));
        return accepted <= 0 ? 0 : creditResource(resource, accepted, TreasuryLedger.Kind.PRODUCTION,
                "building-production", "settlement:" + id + ":production");
    }

    /** Receive physical goods into canonical settlement stock and record the inbound flow. */
    public double receiveResource(String resource, double quantity) {
        double accepted = add(resource, quantity);
        if (accepted > 0) flow.record(time, resource, accepted, true);
        return accepted;
    }

    /** Double-entry transfer of a commodity between settlement accounts. */
    public boolean transferResourceTo(Settlement recipient, String resource, double quantity, String category, String reference) {
        if (recipient == null || recipient == this || resource == null || resource.isBlank()
                || !Double.isFinite(quantity) || quantity < 0
                || amount(resource) + 1e-9 < quantity
                || recipient.capacity(resource) - recipient.amount(resource) + 1e-9 < quantity) return false;
        resourceAccount(resource);
        recipient.resourceAccount(resource);
        boolean ok = TreasuryService.current().transfer(accountId(resource), recipient.accountId(resource), quantity,
                TreasuryLedger.Kind.TRADE, category, reference, worldMinute(),
                "settlement-to-settlement commodity transfer");
        syncResourceMirror(resource);
        recipient.syncResourceMirror(resource);
        if (ok && quantity > 0) {
            flow.recordTrade(time, resource, quantity, false);
            recipient.flow.recordTrade(recipient.time, resource, quantity, true);
        }
        return ok;
    }

    public boolean transferSilverTo(Settlement recipient, double quantity, String category, String reference) {
        return transferResourceTo(recipient, Defs.SILVER, quantity, category, reference);
    }

    /**
     * Transfers a settlement resource to a durable external ledger account.
     * A retry is accepted only when both persisted journal legs match the same target,
     * currency, category and quantity. The TreasuryLedger remains the source of truth.
     */
    public boolean transferResourceToAccount(String resource, String targetAccountId, String targetOwnerId,
                                             double quantity, TreasuryLedger.Kind kind,
                                             String category, String reference) {
        if (resource == null || resource.isBlank() || targetAccountId == null || targetAccountId.isBlank()
                || targetOwnerId == null || targetOwnerId.isBlank() || !Double.isFinite(quantity)
                || quantity < 0 || kind == null || category == null || category.isBlank()
                || reference == null || reference.isBlank()) return false;
        String sourceId = accountId(resource);
        String cleanCategory = category.trim(), cleanReference = reference.trim();
        TreasuryLedger ledger = TreasuryService.current();
        List<TreasuryLedger.Entry> debits = ledger.journal().stream()
                .filter(entry -> entry.accountId().equals(sourceId) && !entry.credit()
                        && entry.kind() == kind && entry.reference().equals(cleanReference)).toList();
        if (!debits.isEmpty()) {
            List<TreasuryLedger.Entry> credits = ledger.journal().stream()
                    .filter(entry -> entry.accountId().equals(targetAccountId) && entry.credit()
                            && entry.kind() == kind && entry.reference().equals(cleanReference)).toList();
            if (debits.size() != 1 || credits.size() != 1) return false;
            TreasuryLedger.Entry debit = debits.get(0), credit = credits.get(0);
            boolean match = debit.counterpartyId().equals(targetAccountId)
                    && credit.counterpartyId().equals(sourceId)
                    && debit.transactionId().equals(credit.transactionId())
                    && debit.currency().equals(resource) && credit.currency().equals(resource)
                    && debit.category().equals(cleanCategory) && credit.category().equals(cleanCategory)
                    && Math.abs(debit.amount() - quantity) <= 1e-8
                    && Math.abs(credit.amount() - quantity) <= 1e-8;
            if (!match) return false;
            syncResourceMirror(resource);
            return true;
        }
        if (ledger.hasCredit(targetAccountId, kind, cleanReference)) return false;
        if (amount(resource) + 1e-9 < quantity) return false;
        TreasuryLedger.Account target = ledger.account(targetAccountId);
        if (target == null) target = ledger.open(targetAccountId, targetOwnerId, resource, 0);
        else if (!target.ownerId.equals(targetOwnerId) || !target.currency.equals(resource)) return false;
        boolean transferred = ledger.transferOnce(sourceId, targetAccountId, quantity, kind,
                cleanCategory, cleanReference, worldMinute(), "settlement resource transfer to external account");
        syncResourceMirror(resource);
        if (transferred && quantity > 0) flow.record(time, resource, quantity, false);
        return transferred;
    }

    /** Transfer settlement silver to a realm, army or other canonical treasury account. */
    public boolean transferSilverToAccount(String targetAccountId, double quantity, TreasuryLedger.Kind kind,
                                            String category, String reference) {
        if (targetAccountId == null || targetAccountId.isBlank() || !Double.isFinite(quantity) || quantity < 0) return false;
        amount(Defs.SILVER);
        boolean ok = TreasuryService.current().transfer(accountId(Defs.SILVER), targetAccountId, quantity,
                kind, category, reference, worldMinute(),
                "settlement treasury transfer");
        syncResourceMirror(Defs.SILVER);
        if (ok && quantity > 0) flow.record(time, Defs.SILVER, quantity, false);
        return ok;
    }

    /** Book market fees and other external charges against settlement cash. */
    public boolean chargeSilver(double quantity, String category, String reference) {
        if (!debitSilver(quantity, TreasuryLedger.Kind.TRADE, category, reference)) return false;
        if (quantity > 0) flow.record(time, Defs.SILVER, quantity, false);
        return true;
    }

    public boolean canPay(Map<String, Integer> cost) {
        if (cost == null || cost.size() > 64) return false;
        for (Map.Entry<String, Integer> e : cost.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 64
                    || e.getValue() == null || e.getValue() < 0 || e.getValue() > 1_000_000_000)
                return false;
        }
        for (Map.Entry<String, Integer> e : cost.entrySet())
            if (amount(e.getKey()) + 1e-9 < e.getValue()) return false;
        return true;
    }

    /** Atomic resource withdrawal used by recruitment and other settlement services. */
    public boolean spend(Map<String, Integer> cost) {
        if (cost == null || !canPay(cost)) return false;
        return pay(cost);
    }

    /**
     * Crash-replay-safe resource debit for a named domain operation.
     * Each resource has its own immutable TreasuryLedger receipt, allowing a retry to
     * finish a partially persisted multi-resource debit without charging completed legs twice.
     * The operation owner must keep its own durable intent until every leg and dependent
     * domain snapshot has been committed.
     */
    public synchronized boolean spendOnce(Map<String, Integer> cost, String operationId) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9_.:-]{1,96}")
                || cost == null || cost.size() > 64) return false;

        TreeMap<String, Integer> normalized = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : cost.entrySet()) {
            String resource = entry.getKey();
            Integer quantity = entry.getValue();
            if (resource == null || !resource.matches("[a-z0-9_.:-]{1,64}")
                    || quantity == null || quantity < 0 || quantity > 1_000_000_000)
                return false;
            normalized.put(resource, quantity);
        }

        // Bind every resource leg to the complete operation payload. Without this manifest,
        // replaying an operation ID with an added/removed resource could silently debit a
        // different basket while each individual resource receipt still looked valid.
        StringBuilder payload = new StringBuilder("settlement-spend-v1:");
        for (Map.Entry<String, Integer> entry : normalized.entrySet())
            payload.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        String payloadFingerprint = payload.toString();

        TreasuryLedger ledger = TreasuryService.current();
        String operationPrefix = "settlement-spend:" + operationId + ":";
        Map<String, String> references = new LinkedHashMap<>();
        Map<String, Integer> existingLegs = new HashMap<>();
        for (TreasuryLedger.Entry receipt : ledger.journal()) {
            if (!receipt.reference().startsWith(operationPrefix)) continue;
            String resource = receipt.reference().substring(operationPrefix.length());
            Integer expected = normalized.get(resource);
            TreasuryLedger.Kind expectedKind = Defs.SILVER.equals(resource)
                    ? TreasuryLedger.Kind.WITHDRAWAL : TreasuryLedger.Kind.CONSUMPTION;
            if (expected == null || expected <= 0 || receipt.credit()
                    || !receipt.accountId().equals(accountId(resource))
                    || !receipt.counterpartyId().equals("external")
                    || !receipt.currency().equals(resource) || receipt.kind() != expectedKind
                    || !receipt.category().equals(payloadFingerprint)
                    || Math.abs(receipt.amount() - expected) > 1e-8
                    || existingLegs.putIfAbsent(resource, 1) != null) return false;
        }

        for (Map.Entry<String, Integer> entry : normalized.entrySet()) {
            if (entry.getValue() == 0) continue;
            String resource = entry.getKey();
            TreasuryLedger.Kind kind = Defs.SILVER.equals(resource)
                    ? TreasuryLedger.Kind.WITHDRAWAL : TreasuryLedger.Kind.CONSUMPTION;
            String reference = operationPrefix + resource;
            String account = accountId(resource);
            amount(resource);
            resourceAccount(resource);
            references.put(resource, reference);
            if (!ledger.hasTransaction(account, kind, reference)
                    && ledger.balance(account) + 1e-9 < entry.getValue()) return false;
        }

        for (Map.Entry<String, Integer> entry : normalized.entrySet()) {
            int quantity = entry.getValue();
            if (quantity == 0) continue;
            String resource = entry.getKey();
            TreasuryLedger.Kind kind = Defs.SILVER.equals(resource)
                    ? TreasuryLedger.Kind.WITHDRAWAL : TreasuryLedger.Kind.CONSUMPTION;
            String account = accountId(resource);
            String reference = references.get(resource);
            boolean alreadyPosted = ledger.hasTransaction(account, kind, reference);
            if (!ledger.withdrawOnce(account, quantity, kind, payloadFingerprint, reference,
                    worldMinute(), "idempotent settlement resource debit")) return false;
            syncResourceMirror(resource);
            if (!alreadyPosted) flow.record(time, resource, quantity, false);
        }
        return true;
    }

    private boolean pay(Map<String, Integer> cost) { return pay(cost, "settlement-spend", "resource-cost"); }

    private boolean pay(Map<String, Integer> cost, String category, String reference) {
        if (cost == null || !canPay(cost)) return false;
        for (Map.Entry<String, Integer> e : cost.entrySet()) {
            if (e.getValue() < 0 || !debitResource(e.getKey(), e.getValue(), Defs.SILVER.equals(e.getKey())
                    ? TreasuryLedger.Kind.WITHDRAWAL : TreasuryLedger.Kind.CONSUMPTION, category, reference)) return false;
            flow.record(time, e.getKey(), e.getValue(), false);
        }
        return true;
    }

    /** Выработка, расход и чистый доход ресурса в минуту (скользящее окно 60 с). */
    public double producedPerMin(String res) { return flow.perMinute(time, res, true); }

    public double consumedPerMin(String res) { return flow.perMinute(time, res, false); }

    public double income(String res) { return producedPerMin(res) - consumedPerMin(res); }

    private void refund(Map<String, Integer> cost, double share) { for (Map.Entry<String, Integer> e : cost.entrySet()) add(e.getKey(), Math.floor(e.getValue() * share)); }

    /** Sum of active building capabilities. Effects are data-driven and shared with domain/UI integrations. */
    public double effect(String key) {
        if (key == null || key.isBlank()) return 0;
        double total = 0;
        for (Building b : buildings) {
            if (b.state == Building.State.DONE && !b.unpaid)
                total += b.def.effects.getOrDefault(key, 0.0);
        }
        return total;
    }

    /** Immutable snapshot of all active numeric building effects. */
    public Map<String, Double> effects() {
        Map<String, Double> totals = new TreeMap<>();
        for (Building b : buildings) if (b.state == Building.State.DONE && !b.unpaid)
            b.def.effects.forEach((key, value) -> totals.merge(key, value, Double::sum));
        return Collections.unmodifiableMap(totals);
    }

    public int housing() {
        int h = BASE_HOUSING;
        for (Building b : buildings) if (b.state == Building.State.DONE && !b.unpaid) h += b.def.housing;
        return h + (int) Math.floor(effect("housing"));
    }

    private void say(String s) {
        log.add(String.format("[%d:%02d] %s", (int) (time / 60), (int) (time % 60), s));
        while (log.size() > LOG_MAX) log.remove(0);
    }

    // ------------------------------------------------------------------ поиск

    public Building building(int uid) {
        for (Building b : buildings) if (b.uid == uid) return b;
        return null;
    }

    public Worker worker(int id) {
        for (Worker w : workers) if (w.id == id) return w;
        return null;
    }

    private static void clearLifeAction(Worker worker) {
        worker.lifeAction = "";
        worker.lifeActionStage = "";
        worker.lifeSocialTarget = "";
        worker.lifeActionDuration = 0;
        worker.lifeActionUntil = 0;
    }

    public int count(String defId, boolean doneOnly) {
        int n = 0;
        for (Building b : buildings) if (b.def.id.equals(defId) && (!doneOnly || b.state == Building.State.DONE)) n++;
        return n;
    }

    public List<Worker> assigned(int uid) {
        List<Worker> l = new ArrayList<>();
        for (Worker w : workers) if (w.building == uid) l.add(w);
        return l;
    }

    private int workerForRole(int uid, Collection<String> roles) {
        for (Worker worker : workers)
            if (worker.building == uid && worker.lifeAction.isBlank() && roles.contains(worker.workRole())) return worker.id;
        return 0;
    }

    private int assignedWith(int uid, String profession) {
        int n = 0;
        for (Worker w : workers) if (w.building == uid && w.lifeAction.isBlank() && w.workRole().equals(profession)) n++;
        return n;
    }
    private double assignedEfficiency(int uid, String profession) {
        double value = 0;
        for (Worker w : workers) if (w.building == uid && w.lifeAction.isBlank() && w.workRole().equals(profession))
            value += w.productivity(profession);
        return value;
    }
    private String availableRole(Worker worker, Building building, String requestedRole) {
        if (requestedRole == null || requestedRole.isBlank()) return availableRole(worker, building);
        if (!worker.canPerform(requestedRole)) return "";
        if (building.state == Building.State.BUILDING) {
            if (!"builder".equals(requestedRole)) return "";
            int occupied = assignedWith(building.uid, "builder")
                    - (worker.building == building.uid && worker.workRole().equals("builder") ? 1 : 0);
            return occupied < building.def.builders ? "builder" : "";
        }
        if (building.state != Building.State.DONE || !building.def.jobs.containsKey(requestedRole)) return "";
        int occupied = assignedWith(building.uid, requestedRole)
                - (worker.building == building.uid && worker.workRole().equals(requestedRole) ? 1 : 0);
        return occupied < building.def.jobs.get(requestedRole) ? requestedRole : "";
    }

    private String availableRole(Worker worker, Building building) {
        if (building.state == Building.State.BUILDING) {
            int occupied = assignedWith(building.uid, "builder")
                    - (worker.building == building.uid && worker.workRole().equals("builder") ? 1 : 0);
            return worker.canPerform("builder") && occupied < building.def.builders ? "builder" : "";
        }
        for (Map.Entry<String,Integer> job : building.def.jobs.entrySet()) {
            int occupied = assignedWith(building.uid, job.getKey())
                    - (worker.building == building.uid && worker.workRole().equals(job.getKey()) ? 1 : 0);
            if (worker.canPerform(job.getKey()) && occupied < job.getValue()) return job.getKey();
        }
        return "";
    }

    public Worker addWorker(String name) {
        int id = nextWorker++;
        Worker w = new Worker(id, name == null ? "Поселенец " + id : name);
        workers.add(w);
        return w;
    }

    private void removeWorker(Worker worker, String reason) {
        if (worker == null || !workers.remove(worker)) return;
        departures.add(new WorkerDeparture(worker.person == null ? "" : worker.person,
                worker.name == null ? "" : worker.name, reason));
    }

    /** Removes a worker by canonical Character ID and persists the reason for lifecycle reconciliation. */
    public boolean removeWorkerByPerson(String personId, String reason) {
        if (personId == null || personId.isBlank() || reason == null || reason.isBlank()) return false;
        for (Worker worker : new ArrayList<>(workers)) {
            if (personId.equals(worker.person)) {
                removeWorker(worker, reason);
                return true;
            }
        }
        return false;
    }

    /** Drained only after the owning CharacterRegistry confirms the lifecycle transition. */
    public List<WorkerDeparture> pendingDepartures() { return Collections.unmodifiableList(departures); }
    public void acknowledgeDeparture(WorkerDeparture departure) { departures.remove(departure); }

    /**
     * Cancels a stale departure after the same canonical person has been reattached.
     * A military transfer is a temporary projection change, not a death or migration.
     */
    public int acknowledgeDepartureForPerson(String personId, String reason) {
        if (personId == null || personId.isBlank() || reason == null || reason.isBlank()) return 0;
        int before = departures.size();
        departures.removeIf(event -> personId.equals(event.personId()) && reason.equals(event.reason()));
        return before - departures.size();
    }

    // ------------------------------------------------------------------ приказы

    /** Почему здание нельзя заложить здесь (те же правила, что у {@link #place}); null - можно. Для призрака в окне RTS. */
    public String placeError(String defId, int x, int z) {
        Defs.Building d = defs.buildings.get(defId);
        if (d == null) return "Нет такого здания: " + defId;
        for (String r : d.requires) if (count(r, true) == 0) return d.name + ": сначала нужно построить " + defs.buildings.get(r).name;
        if (d.limit > 0 && count(defId, false) >= d.limit) return d.name + ": достигнут предел (" + d.limit + ")";
        if (types != null && !typeId.isEmpty() && types.types.containsKey(typeId)) {
            String te = types.canBuild(types.types.get(typeId), d, count(defId, false));
            if (!te.isEmpty()) return d.name + ": " + te;
        }
        for (Building b : buildings) {
            if (Math.abs(b.x - x) * 2 < b.def.sizeX + d.sizeX && Math.abs(b.z - z) * 2 < b.def.sizeZ + d.sizeZ) return "Место занято зданием " + b.def.name;
        }
        return null;
    }

    /** Виды поселений (необязательно) и текущий вид; ограничивают стройку. Пересчитывается {@link #refreshType}. */
    public SettleTypes types;
    public String typeId = "";

    /** Выбирает лучший вид по числу жителей, зданиям, местности и расам; возвращает id вида (пусто - нет подходящего). */
    public String refreshType(int pop, Map<String, Double> terrain, Map<String, Double> races) {
        if (types == null) return typeId;
        List<String> built = new ArrayList<>();
        for (Building b : buildings) if (b.state == Building.State.DONE) built.add(b.def.id);
        SettleTypes.Type t = types.best(pop, built, terrain, races);
        String old = typeId;
        typeId = t == null ? "" : t.id();
        if (!typeId.equals(old) && t != null) say("Вид поселения: " + t.name());
        return typeId;
    }

    public Building place(String defId, int x, int z) {
        String err = placeError(defId, x, z);
        if (err != null) throw new OrderException(err);
        Defs.Building d = defs.buildings.get(defId);
        Building b = new Building(nextBuilding++, d, x, z);
        b.status = "ждёт материалов";
        buildings.add(b);
        say("Заложено: " + d.name);
        return b;
    }

    /** Replay-safe placement; receipt and building are written in the same settlement snapshot. */
    public Building placeOnce(String requestId, String defId, int x, int z) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new OrderException("Некорректный идентификатор строительного приказа");
        String fingerprint = defId + "|" + x + "|" + z;
        String prior = placementReceipts.get(requestId);
        if (prior != null) {
            int separator = prior.lastIndexOf('|');
            if (separator < 0 || !prior.substring(0, separator).equals(fingerprint))
                throw new IllegalStateException("Settlement placement request ID collision: " + requestId);
            int uid;
            try { uid = Integer.parseInt(prior.substring(separator + 1)); }
            catch (NumberFormatException error) { throw new IllegalStateException("Corrupt settlement placement receipt", error); }
            Building existing = building(uid);
            if (existing == null) throw new IllegalStateException("Placement receipt references missing building " + uid);
            return existing;
        }
        if (placementReceipts.size() >= 10000)
            throw new IllegalStateException("Settlement placement receipt limit reached");
        Building created = place(defId, x, z);
        placementReceipts.put(requestId, fingerprint + "|" + created.uid);
        return created;
    }

    public boolean hasPlacementReceipt(String requestId) { return placementReceipts.containsKey(requestId); }

    /** Resolve a committed placement so later terrain scans cannot move its blueprint. */
    public Building placement(String requestId) {
        String prior = placementReceipts.get(requestId);
        if (prior == null) return null;
        int separator = prior.lastIndexOf('|');
        if (separator < 0) throw new IllegalStateException("Corrupt settlement placement receipt: " + requestId);
        int uid;
        try { uid = Integer.parseInt(prior.substring(separator + 1)); }
        catch (NumberFormatException error) { throw new IllegalStateException("Corrupt settlement placement receipt: " + requestId, error); }
        Building existing = building(uid);
        if (existing == null) throw new IllegalStateException("Placement receipt references missing building " + requestId);
        return existing;
    }

    /** Register an existing physical building without charging resources or placing a blueprint. */
    public Building adoptExistingBuilding(String requestId, String defId, int x, int z) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new OrderException("Некорректный идентификатор существующего здания");
        Defs.Building definition = defs.buildings.get(defId);
        if (definition == null) throw new OrderException("Неизвестный тип здания: " + defId);
        String fingerprint = "existing|" + defId + "|" + x + "|" + z;
        String prior = placementReceipts.get(requestId);
        if (prior != null) {
            int separator = prior.lastIndexOf('|');
            if (separator < 0 || !prior.substring(0, separator).equals(fingerprint))
                throw new IllegalStateException("Settlement adoption request ID collision: " + requestId);
            int uid;
            try { uid = Integer.parseInt(prior.substring(separator + 1)); }
            catch (NumberFormatException error) { throw new IllegalStateException("Corrupt settlement adoption receipt", error); }
            Building existing = building(uid);
            if (existing == null) throw new IllegalStateException("Adoption receipt references missing building " + uid);
            return existing;
        }
        if (placementReceipts.size() >= 10000)
            throw new IllegalStateException("Settlement placement receipt limit reached");
        Building adopted = new Building(nextBuilding++, definition, x, z);
        adopted.state = Building.State.DONE;
        adopted.progress = definition.work;
        adopted.externallyMaterialized = true;
        adopted.status = "существующее сооружение Minecraft подключено к симуляции";
        buildings.add(adopted);
        placementReceipts.put(requestId, fingerprint + "|" + adopted.uid);
        say("Подключено существующее здание: " + definition.name);
        return adopted;
    }

    /** Отмена стройки или снос. Возврат: не начатое 100%, строящееся 50%, готовое 25%. */
    public void cancel(int uid) {
        Building b = building(uid);
        if (b == null) throw new OrderException("Нет здания " + uid);
        if (b.state == Building.State.DONE) {
            for (Building o : buildings) {
                if (o != b && o.state != Building.State.PLANNED && o.def.requires.contains(b.def.id) && count(b.def.id, true) <= 1) throw new OrderException("Нельзя снести: нужно для " + o.def.name);
            }
        }
        double share = b.state == Building.State.PLANNED ? 0 : b.state == Building.State.BUILDING ? 0.5 : 0.25;
        for (Worker w : assigned(uid)) { w.building = 0; w.status = "свободен"; }
        buildings.remove(b);
        if (share > 0) refund(b.def.cost, share);
        say((b.state == Building.State.DONE ? "Снесено: " : "Отменено: ") + b.def.name);
    }

    public void assign(int workerId, int uid) { assign(workerId, uid, null); }

    /** Assigns a resident to one exact role when requested by a direct dialogue/RTS order. */
    public void assign(int workerId, int uid, String requestedRole) {
        Worker w = worker(workerId);
        Building b = building(uid);
        if (w == null) throw new OrderException("Нет рабочего " + workerId);
        if (b == null) throw new OrderException("Нет здания " + uid);
        String role = requestedRole == null || requestedRole.isBlank()
                ? availableRole(w, b) : availableRole(w, b, requestedRole);
        if (role.isEmpty()) throw new OrderException(w.name + " (" + defs.professionName(w.profession)
                + ") не подходит для роли " + (requestedRole == null ? "этого здания" : requestedRole)
                + " или нет свободных мест в " + b.def.name);
        w.assignedProfession = role;
        w.manualHauler = false;
        clearLifeAction(w);
        w.building = uid;
        w.manualFollow = false;
        w.manualFollowOwner = "";
        w.manualOrder = false;
        w.orderQueue.clear();
        w.status = "";
    }

    public void unassign(int workerId) {
        Worker w = worker(workerId);
        if (w == null) throw new OrderException("Нет рабочего " + workerId);
        w.building = 0;
        w.assignedProfession = "";
        w.manualHauler = false;
        clearLifeAction(w);
        w.manualFollow = false;
        w.manualFollowOwner = "";
        w.manualOrder = false;
        w.orderQueue.clear();
        w.status = "свободен";
    }

    /** Прямой приказ RTS; координаты относительно центра поселения. */
    public void moveTo(int workerId,double x,double z){moveTo(workerId,x,z,false);}

    /** Queue appends a bounded waypoint; otherwise the current manual route is replaced. */
    public void moveTo(int workerId,double x,double z,boolean queue) {
        Worker w=worker(workerId);
        if(w==null)throw new OrderException("Нет рабочего "+workerId);
        if(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>100000||Math.abs(z)>100000)
            throw new OrderException("Некорректная точка приказа");
        if(queue&&w.manualOrder){
            if(w.orderQueue.size()>=Worker.MAX_MANUAL_ORDERS)throw new OrderException("Очередь рабочего заполнена (максимум "+Worker.MAX_MANUAL_ORDERS+")");
            w.orderQueue.addLast(new Worker.ManualOrder(x,z));
            return;
        }
        w.orderQueue.clear();
        w.manualHauler = false;
        clearLifeAction(w);
        w.manualFollow=false;
        w.manualFollowOwner="";
        w.building=0;w.manualOrder=true;w.orderX=x;w.orderZ=z;w.status="приказ: движение";
    }

    /** Called by the physical worker after reaching its active waypoint. */
    public boolean advanceManualOrder(int workerId) {
        Worker w=worker(workerId);
        if(w==null||!w.manualOrder)return false;
        Worker.ManualOrder next=w.orderQueue.pollFirst();
        if(next==null){w.manualOrder=false;w.status="свободен";return false;}
        w.orderX=next.x();w.orderZ=next.z();w.status="приказ: движение";
        return true;
    }

    /** Stop means hold here and discard all queued waypoints. */
    public void holdAt(int workerId,double x,double z) {
        moveTo(workerId,x,z,false);
        Worker w=worker(workerId);
        w.status="приказ: удерживать позицию";
    }

    public void setProfession(int workerId, String profession) {
        Worker w = worker(workerId);
        if (w == null) throw new OrderException("Нет рабочего " + workerId);
        if (!defs.professions.containsKey(profession)) throw new OrderException("Нет профессии " + profession);
        w.profession = profession;
        w.assignedProfession = "";
        w.manualHauler = false;
        clearLifeAction(w);
        w.building = 0;
        w.manualFollow = false;
        w.manualFollowOwner = "";
        w.manualOrder = false;
        w.orderQueue.clear();
        w.status = "свободен";
    }

    /** Пауза производства. Только для готового здания; на паузе здание не берёт сырьё, не даёт товар и не зовёт новых работников. */
    public void setPaused(int uid, boolean paused) {
        Building b = building(uid);
        if (b == null) throw new OrderException("Нет здания " + uid);
        if (b.state != Building.State.DONE) throw new OrderException(b.def.name + ": пауза только для готового здания");
        b.paused = paused;
        b.status = paused ? "пауза" : b.def.produces() ? "работает" : "готово";
    }

    /** Отправляет нескольких рабочих в здание; кому места не хватило или профессия не подошла - пропускаются. Возвращает, сколько назначено; 0 - ошибка с причиной первого отказа. */
    public int assignMany(Collection<Integer> workerIds, int uid) {
        if (building(uid) == null) throw new OrderException("Нет здания " + uid);
        int n = 0;
        String first = null;
        for (int id : new LinkedHashSet<>(workerIds)) {
            try { assign(id, uid); n++; } catch (OrderException e) { if (first == null) first = e.getMessage(); }
        }
        if (n == 0) throw new OrderException(first == null ? "Никто не выбран" : first);
        return n;
    }

    /** Меняет профессию нескольким рабочим (они становятся свободными). Неизвестные номера пропускаются; возвращает число изменённых. */
    public int setProfessionMany(Collection<Integer> workerIds, String profession) {
        if (!defs.professions.containsKey(profession)) throw new OrderException("Нет профессии " + profession);
        int n = 0;
        for (int id : new LinkedHashSet<>(workerIds)) if (worker(id) != null) { setProfession(id, profession); n++; }
        if (n == 0) throw new OrderException("Никто не выбран");
        return n;
    }

    /** Снимает всех работников со здания; возвращает сколько. */
    public int unassignAll(int uid) {
        if (building(uid) == null) throw new OrderException("Нет здания " + uid);
        int n = 0;
        for (Worker w : assigned(uid)) { unassign(w.id); n++; }
        return n;
    }

    /** Свободные работники (без здания) по возрастанию номера: кнопка «простаивающие» в RTS. */
    public List<Worker> idleWorkers() {
        List<Worker> l = new ArrayList<>();
        for (Worker w : workers) if (w.building == 0) l.add(w);
        l.sort(Comparator.comparingInt(w -> w.id));
        return l;
    }

    /** Список номеров «1 2 3» или «1,2,3» из команды; пустой набор и мусор - ошибка. */
    public static List<Integer> parseIds(String s) {
        List<Integer> out = new ArrayList<>();
        for (String t : s.trim().split("[\\s,]+")) {
            if (t.isEmpty()) continue;
            try { int v = Integer.parseInt(t); if (v < 1) throw new NumberFormatException(); out.add(v); }
            catch (NumberFormatException e) { throw new OrderException("Плохой номер: " + t); }
        }
        if (out.isEmpty()) throw new OrderException("Не указаны рабочие");
        return out;
    }

    public void setPriority(int uid, int p) {
        Building b = building(uid);
        if (b == null) throw new OrderException("Нет здания " + uid);
        b.priority = Math.max(1, Math.min(9, p));
    }

    // ------------------------------------------------------------------ шаг симуляции

    public void tick(double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        if (dt > MAX_TICK) throw new IllegalArgumentException("За один вызов можно обработать не более " + (int) MAX_TICK + " секунд симуляции");
        while (dt > 0) {
            double step = Math.min(dt, MAX_STEP);
            step(step);
            dt -= step;
        }
    }

    private void step(double dt) {
        time += dt;
        decayDemand(dt);
        eat(dt);
        economy(dt);
        grow(dt);
        startConstruction();
        if (auto) { autoAssign(); draftBuilders(); }
        build(dt);
        produce(dt);
        haul(dt);
        raidStep(dt);
        move(dt);
    }

    private void eat(double dt) {
        int residents = population();
        double need = residents * EAT * dt, have = amount("food");
        if (residents == 0) { starveTimer = 0; return; }
        if (have >= need) {
            debitResource("food", need, TreasuryLedger.Kind.CONSUMPTION, "population-food", "daily-rations");
            flow.record(time, "food", need, false);
            starveTimer = 0;
            return;
        }
        debitResource("food", have, TreasuryLedger.Kind.CONSUMPTION, "population-food", "starvation-rations");
        flow.record(time, "food", have, false);
        starveTimer += dt;
        if (starveTimer >= STARVE_EVERY) {
            starveTimer = 0;
            // Only an actual adult Worker can be removed here. Non-working canonical residents
            // remain in the people registry and are reconciled by the world population engine.
            if (!workers.isEmpty()) {
                Worker w = workers.get(workers.size() - 1);
                removeWorker(w, "death:starvation");
                say(w.name + " умер от голода");
            }
        }
    }

    private void grow(double dt) {
        if (hasCanonicalPopulation()) { growTimer = 0; leaveTimer = 0; return; }
        if (approval() < APPROVAL_LEAVE_BELOW && !workers.isEmpty()) {
            leaveTimer += dt;
            if (leaveTimer >= LEAVE_EVERY) {
                leaveTimer = 0;
                Worker w = null;
                for (Worker o : workers) if (o.building == 0 && (w == null || o.id > w.id)) w = o;
                if (w == null) w = workers.get(workers.size() - 1);
                removeWorker(w, "migration:dissatisfaction");
                say(w.name + " покинул поселение (недовольство)");
            }
        } else leaveTimer = 0;
        if (workers.size() < housing() && amount("food") > workers.size() * 5 && approval() >= APPROVAL_GROWTH_MIN) {
            growTimer += dt;
            if (growTimer >= GROW_EVERY) {
                growTimer = 0;
                Worker w = addWorker(null);
                say(w.name + " поселился");
            }
        } else growTimer = 0;
    }

    private List<Building> byPriority(java.util.function.Predicate<Building> f) {
        List<Building> l = new ArrayList<>();
        for (Building b : buildings) if (f.test(b)) l.add(b);
        l.sort(Comparator.comparingInt((Building b) -> -b.priority).thenComparingInt(b -> b.uid));
        return l;
    }

    private void startConstruction() {
        for (Building b : byPriority(x -> x.state == Building.State.PLANNED)) {
            if (!canPay(b.def.cost)) { b.status = "ждёт материалов"; continue; }
            if (!pay(b.def.cost, "construction", "building:" + b.uid)) { b.status = "ждёт материалов"; continue; }
            for (Map.Entry<String,Integer> cost : b.def.cost.entrySet())
                taskGraph.consume(ResidentTaskGraph.Stage.CONSTRUCTION, b.uid, 0, cost.getKey(), cost.getValue(), time);
            b.state = Building.State.BUILDING;
            b.status = "строится";
            say("Начата стройка: " + b.def.name);
        }
    }

    private void autoAssign() {
        List<Worker> idle = new ArrayList<>();
        for (Worker w : workers) if (w.building == 0 && !w.manualOrder && !w.manualFollow && w.lifeAction.isBlank()) idle.add(w);
        idle.sort(Comparator.comparingInt(w -> w.id));
        for (Worker w : idle) {
            Building best = null;
            String role = "";
            int bestPrio = Integer.MAX_VALUE;
            double bestFill = Double.MAX_VALUE;
            for (Building site : byPriority(x -> x.state == Building.State.BUILDING)) {
                String candidate = availableRole(w, site);
                if (!candidate.isEmpty()) { best = site; role = candidate; break; }
            }
            if (best == null) {
                for (Building b : byPriority(x -> x.state == Building.State.DONE && !x.paused && x.def.slots() > 0)) {
                    for (Map.Entry<String, Integer> job : b.def.jobs.entrySet()) {
                        if (!w.canPerform(job.getKey())) continue;
                        int have = assignedWith(b.uid, job.getKey());
                        if (have >= job.getValue()) continue;
                        int priority = workPriority(w, job.getKey());
                        if (priority <= 0) continue;
                        double fill = (double) have / job.getValue();
                        if (best == null || priority < bestPrio || (priority == bestPrio
                                && (b.priority > best.priority || (b.priority == best.priority && fill < bestFill)))) {
                            best = b; role = job.getKey(); bestPrio = priority; bestFill = fill;
                        }
                    }
                }
            }
            if (best != null) {
                w.assignedProfession = role;
                w.building = best.uid;
                w.status = "";
            }
        }
        autoAssignHaulers();
    }

    /** Assigns one compatible porter/general worker to each active production buffer. */
    private void autoAssignHaulers() {
        for (Worker worker : workers) {
            if (!worker.workRole().equals("hauler")) continue;
            Building source = building(worker.building);
            if ((source == null || source.localStock.isEmpty()) && !worker.manualHauler) {
                worker.building = 0;
                worker.assignedProfession = "";
                if (!worker.manualOrder && !worker.manualFollow && worker.lifeAction.isBlank())
                    worker.status = "свободен";
            }
        }
        for (Building source : byPriority(b -> b.state == Building.State.DONE && !b.localStock.isEmpty())) {
            if (assignedWith(source.uid, "hauler") > 0) continue;
            Worker pick = null;
            for (Worker worker : workers) {
                if (worker.manualOrder || worker.manualFollow || !worker.lifeAction.isBlank()
                        || worker.building != 0 || !worker.canPerform("hauler")) continue;
                if (pick == null || worker.id < pick.id) pick = worker;
            }
            if (pick == null) continue;
            pick.building = source.uid;
            pick.assignedProfession = "hauler";
            pick.status = "переносит ресурсы";
        }
    }

    /** Explicit management command for a porter/general worker. */
    public void assignHauler(int workerId, int buildingId) {
        Worker worker = worker(workerId);
        Building source = building(buildingId);
        if (worker == null || source == null) throw new OrderException("Нет рабочего или здания");
        if (source.state != Building.State.DONE || (!source.def.produces() && source.localStock.isEmpty()))
            throw new OrderException("Носильщик назначается только на действующее производственное здание");
        if (!worker.canPerform("hauler")) throw new OrderException(worker.name + " не может переносить ресурсы");
        worker.building = buildingId;
        worker.assignedProfession = "hauler";
        worker.manualHauler = true;
        worker.lifeAction = "";
        worker.manualOrder = false;
        worker.manualFollow = false;
        worker.manualFollowOwner = "";
        worker.orderQueue.clear();
        worker.status = "переносит ресурсы";
    }

    /** Executes the haul leg: local production buffers become canonical stock only after a worker delivery. */
    private void haul(double dt) {
        for (Worker worker : workers) {
            if (worker.building == 0 || !worker.lifeAction.isBlank() || !worker.workRole().equals("hauler")) continue;
            Building source = building(worker.building);
            if (source == null || source.state != Building.State.DONE) continue;
            worker.haulProgress = Math.min(100, worker.haulProgress + dt * HAUL_RATE * worker.productivity("hauler"));
            int capacity = (int) Math.floor(worker.haulProgress + 1e-9);
            if (capacity <= 0) {
                worker.status = source.localStock.isEmpty() ? "ждёт груз" : "собирает груз";
                continue;
            }
            for (String resource : new ArrayList<>(source.localStock.keySet())) {
                if (capacity <= 0) break;
                double available = source.localStock.getOrDefault(resource, 0.0);
                if (available <= 1e-9) { source.localStock.remove(resource); continue; }
                double accepted = receiveResource(resource, Math.min(capacity, available));
                if (accepted <= 0) break;
                taskGraph.deliver(source.uid, worker.id, resource, accepted, time);
                double remaining = available - accepted;
                if (remaining <= 1e-9) source.localStock.remove(resource);
                else source.localStock.put(resource, remaining);
                worker.haulProgress -= accepted;
                capacity -= (int) Math.floor(accepted + 1e-9);
                worker.status = "доставляет: " + resourceName(resource);
            }
        }
    }

    /** Каждой стройке нужен хотя бы один строитель: если свободных нет, снимаем работника с самого укомплектованного здания. */
    private void draftBuilders() {
        for (Building site : byPriority(x -> x.state == Building.State.BUILDING)) {
            if (assignedWith(site.uid, "builder") > 0) continue;
            Worker pick = null;
            int bestStaff = 1;
            for (Worker w : workers) {
                if (w.building == 0 || w.manualOrder || w.manualFollow || w.manualHauler
                        || !w.lifeAction.isBlank() || !w.canPerform("builder")) continue;
                Building from = building(w.building);
                if (from == null || from.state != Building.State.DONE) continue;
                int staff = assigned(from.uid).size();
                if (staff > bestStaff || staff == bestStaff && pick != null && w.id > pick.id) { pick = w; bestStaff = staff; }
            }
            if (pick != null) {
                pick.assignedProfession = "builder";
                clearLifeAction(pick);
                pick.building = site.uid;
                pick.status = "";
            }
        }
    }

    private void build(double dt) {
        for (Building b : new ArrayList<>(buildings)) {
            if (b.state != Building.State.BUILDING) continue;
            double builderWork = assignedEfficiency(b.uid, "builder");
            if (builderWork <= 0) { b.status = "нет строителей"; continue; }
            // A specialist builder contributes 1.5 work units per second; a general worker contributes 1.0.
            b.progress += builderWork * dt;
            b.status = "строится " + (int) Math.min(99, 100 * b.progress / b.def.work) + "%";
            if (b.progress >= b.def.work) {
                b.state = Building.State.DONE;
                b.progress = b.def.work;
                b.status = b.def.produces() ? "работает" : "готово";
                taskGraph.built(b.uid, workerForRole(b.uid, Set.of("builder")), time);
                for (Worker w : assigned(b.uid)) { w.building = 0; w.assignedProfession = ""; clearLifeAction(w); w.status = "свободен"; }
                say("Построено: " + b.def.name);
            }
        }
    }

    private void produce(double dt) {
        for (Building b : buildings) {
            if (b.state != Building.State.DONE) continue;
            if (b.paused) { b.status = "пауза"; continue; }
            if (b.unpaid) { b.status = "нет серебра"; continue; }
            if (!b.def.produces()) continue;
            double slots = b.def.slots(), eff = 0;
            for (Map.Entry<String, Integer> j : b.def.jobs.entrySet())
                eff += Math.min(assignedEfficiency(b.uid, j.getKey()), j.getValue());
            double rate = (slots == 0 ? 1 : eff / slots) * seasonMult(b);                      // без рабочих мест здание работает само (например, колодец)
            if (rate <= 0) { b.status = "нет работников"; continue; }
            b.cycleTime += dt * rate;
            while (b.cycleTime >= b.def.cycle) {
                if (!canPay(b.def.in)) { b.status = "нет сырья"; b.cycleTime = b.def.cycle; break; }
                boolean room = true;
                for (Map.Entry<String, Integer> o : b.def.out.entrySet())
                    if (b.localStock.getOrDefault(o.getKey(), 0.0) + o.getValue() > LOCAL_BUFFER_CAP + 1e-9) room = false;
                if (!room) { b.status = "цеховой буфер полон: нужен носильщик"; b.cycleTime = b.def.cycle; break; }
                if (!pay(b.def.in, "production-input", "building:" + b.uid)) { b.status = "нет сырья"; b.cycleTime = b.def.cycle; break; }
                for (Map.Entry<String,Integer> input : b.def.in.entrySet())
                    taskGraph.consume(ResidentTaskGraph.Stage.PROCESSED, b.uid, workerForRole(b.uid, b.def.jobs.keySet()), input.getKey(), input.getValue(), time);
                for (Map.Entry<String, Integer> o : b.def.out.entrySet()) {
                    b.localStock.merge(o.getKey(), (double) o.getValue(), Double::sum);
                    int producerId = workerForRole(b.uid, b.def.jobs.keySet());
                    if (b.def.in.isEmpty()) taskGraph.extract(b.uid, producerId, o.getKey(), o.getValue(), time);
                    else taskGraph.produce(b.uid, producerId, o.getKey(), o.getValue(), time);
                }
                b.cycleTime -= b.def.cycle;
                b.status = "работает";
            }
        }
    }


    // ------------------------------------------------------------------ казна, торговля, комфорт

    private boolean staffedBuilding(Building b) { return b.state == Building.State.DONE && !b.paused && (b.def.slots() == 0 || !assigned(b.uid).isEmpty()); }

    /** Торговых операций в минуту (сумма trade действующих рынков) и вместимость запаса операций. */
    public double tradePerMin() { double t = 0; for (Building b : buildings) if (staffedBuilding(b)) t += b.def.trade * 6; return t; }

    public double tradeCap() { return tradePerMin() * 3; }

    /** Давление спроса: покупки поднимают цену, продажи опускают; со временем сходит на нет. */
    public final Map<String, Double> pressure = new TreeMap<>();
    public static final double PRESSURE_STEP = 0.015, PRESSURE_DECAY_PER_MIN = 0.08;

    public double basePrice(String res) { return defs.prices.getOrDefault(res, 0.0); }

    /** Множитель цены: нехватка на складе дорожит товар, избыток дешевит; плюс давление сделок. Границы 0.5..2.0. */
    public double priceFactor(String res) {
        double cap = capacity(res);
        double fill = cap <= 0 || cap > 1e6 ? 0.5 : Math.min(1, amount(res) / cap);
        double f = 1.5 - fill + pressure.getOrDefault(res, 0.0);
        return Math.max(0.5, Math.min(2.0, f));
    }

    public double price(String res) { return basePrice(res) * priceFactor(res); }

    /** Куда движется цена: 1 дорожает, -1 дешевеет, 0 стоит (по давлению сделок). */
    public int priceTrend(String res) { double p = pressure.getOrDefault(res, 0.0); return p > 0.03 ? 1 : p < -0.03 ? -1 : 0; }

    /** Сдвиг цены внешней торговлей: n>0 - товар уходит (дорожает), n<0 - приходит (дешевеет). */
    public void shiftDemand(String res, double n) { moveDemand(res, n); }

    private void moveDemand(String res, double n) { pressure.merge(res, n * PRESSURE_STEP, Double::sum); pressure.put(res, Math.max(-0.5, Math.min(0.5, pressure.get(res)))); }

    private void decayDemand(double dt) {
        for (var it = pressure.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            double v = e.getValue(), d = PRESSURE_DECAY_PER_MIN * dt / 60;
            double nv = v > 0 ? Math.max(0, v - d) : Math.min(0, v + d);
            if (Math.abs(nv) < 1e-4) it.remove(); else e.setValue(nv);
        }
    }

    public double buyPrice(String res) { return price(res) * (1 + TRADE_MARGIN); }

    public double sellPrice(String res) { return price(res) * (1 - TRADE_MARGIN); }

    public void setTax(int level) {
        if (level < 0 || level > 3) throw new OrderException("Налог: 0..3");
        taxLevel = level;
    }

    private void checkTrade(String res, int n) {
        if (n <= 0) throw new OrderException("Нужно положительное число");
        if (res.equals(Defs.SILVER)) throw new OrderException("Серебро не продаётся за серебро");
        if (price(res) <= 0) throw new OrderException(resourceName(res) + " не торгуется");
        if (tradePerMin() <= 0) throw new OrderException("Нужен рынок с торговцами");
        if (n > tradeLeft + 1e-9) throw new OrderException("Рынок успевает ещё " + (int) tradeLeft + " ед.");
    }

    public void buy(String res, int n) {
        checkTrade(res, n);
        double cost = buyPrice(res) * n;
        if (amount(Defs.SILVER) < cost) throw new OrderException("Не хватает серебра: " + (int) amount(Defs.SILVER) + " из " + (int) Math.ceil(cost));
        if (amount(res) + n > capacity(res) + 1e-9) throw new OrderException("Нет места на складе");
        if (!debitSilver(cost, TreasuryLedger.Kind.TRADE, "market-purchase", res)) throw new OrderException("Не хватает серебра");
        flow.record(time, Defs.SILVER, cost, false);
        add(res, n);
        moveDemand(res, n);
        tradeLeft -= n;
        say("Куплено " + n + " " + resourceName(res).toLowerCase() + " за " + Math.round(cost) + " серебра");
    }

    public void sell(String res, int n) {
        checkTrade(res, n);
        if (amount(res) < n) throw new OrderException("Нет столько: " + (int) amount(res));
        double gain = sellPrice(res) * n;
        debitResource(res, n, TreasuryLedger.Kind.TRADE, "market-sale", res);
        flow.record(time, res, n, false);
        flow.record(time, Defs.SILVER, add(Defs.SILVER, gain), true);
        moveDemand(res, -n);
        tradeLeft -= n;
        say("Продано " + n + " " + resourceName(res).toLowerCase() + " за " + Math.round(gain) + " серебра");
    }

    /** Правило автоторговли: mode "sell" - продавать всё выше порога, "buy" - докупать до порога, "off" - убрать. */
    public void setAutoTrade(String res, String mode, int threshold) {
        if (!defs.resourceNames.containsKey(res)) throw new OrderException("Нет ресурса " + res);
        autoSell.remove(res); autoBuy.remove(res);
        switch (mode) {
            case "sell" -> autoSell.put(res, Math.max(0, threshold));
            case "buy" -> autoBuy.put(res, Math.max(0, threshold));
            case "off" -> { }
            default -> throw new OrderException("Режим: sell, buy, off");
        }
    }

    /** Доход казны в минуту при текущих налогах: налоги, торговля минус содержание (оценка по потокам за минуту). */
    public double silverPerMin() { return producedPerMin(Defs.SILVER) - consumedPerMin(Defs.SILVER); }

    /** Налоги в минуту при текущем уровне (для окна бюджета). */
    public double taxPerMin() {
        double t;
        if (hasCanonicalPopulation()) {
            t = population() * 0.5;
            for (Worker worker : workers) if (worker.building != 0) t += 0.5;
        } else {
            t = 0;
            for (Worker worker : workers) t += worker.building == 0 ? 0.5 : 1.0;
        }
        return t * TAX_PER_MIN * TAX_MULT[taxLevel];
    }

    /** Содержание зданий в минуту. */
    public double upkeepPerMin() { double u = 0; for (Building b : buildings) if (b.state == Building.State.DONE && !b.paused) u += b.def.upkeep; return u; }

    private void economy(double dt) {
        double tax = taxPerMin() / 60 * dt;
        if (tax > 0) { double got = add(Defs.SILVER, tax); flow.record(time, Defs.SILVER, got, true); taxCollected += got; }
        for (Building b : buildings) {
            b.unpaid = false;
            if (b.state != Building.State.DONE || b.paused || b.def.upkeep <= 0) continue;
            double c = b.def.upkeep / 60 * dt;
            if (amount(Defs.SILVER) >= c) {
                if (debitSilver(c, TreasuryLedger.Kind.UPKEEP, "building-upkeep", b.def.id)) flow.record(time, Defs.SILVER, c, false);
                else b.unpaid = true;
            } else b.unpaid = true;
        }
        tradeLeft = Math.min(tradeCap(), tradeLeft + tradePerMin() / 60 * dt);
        for (var e : autoSell.entrySet()) {
            int spare = (int) Math.min(amount(e.getKey()) - e.getValue(), tradeLeft);
            if (spare >= 1 && price(e.getKey()) > 0 && tradePerMin() > 0) { try { sell(e.getKey(), Math.min(spare, 5)); } catch (OrderException ignored) { } }
        }
        for (var e : autoBuy.entrySet()) {
            int lack = (int) Math.min(e.getValue() - amount(e.getKey()), tradeLeft);
            if (lack >= 1 && price(e.getKey()) > 0 && tradePerMin() > 0) { try { buy(e.getKey(), Math.min(lack, 5)); } catch (OrderException ignored) { } }
        }
        int n = population();
        for (var e : defs.comfort.entrySet()) {
            double c = Math.min(amount(e.getKey()), e.getValue().rate * n * dt);
            if (c > 0) {
                debitResource(e.getKey(), c, Defs.SILVER.equals(e.getKey())
                    ? TreasuryLedger.Kind.WITHDRAWAL : TreasuryLedger.Kind.CONSUMPTION, "consumption", "comfort-goods");
                flow.record(time, e.getKey(), c, false);
            }
        }
    }

    /** Вклад налогов, зданий, безопасности и товаров комфорта в настроение. */
    private List<Factor> economyFactors() {
        List<Factor> l = new ArrayList<>();
        int[] taxF = {8, 3, 0, -10};
        if (taxF[taxLevel] != 0) l.add(new Factor("Налог: " + TAX_NAMES[taxLevel].toLowerCase(), taxF[taxLevel]));
        int crown = crownShare < 10 ? 2 : crownShare <= 25 ? 0 : -Math.min(12, (crownShare - 25 + 2) / 3);
        if (crown != 0) l.add(new Factor("Подать в казну: " + crownShare + "%", crown));
        int ap = 0, safe = 0;
        for (Building b : buildings) if (b.state == Building.State.DONE && !b.unpaid) { ap += b.def.approval; safe += b.def.safety; }
        ap += (int) Math.floor(effect("approval"));
        safe += (int) Math.floor(effect("safety"));
        if (ap != 0) l.add(new Factor("Трактиры и храмы", Math.min(20, ap)));
        if (safe != 0) l.add(new Factor("Укрепления", Math.min(10, safe)));
        int comfort = 0;
        List<String> goods = new ArrayList<>();
        for (var e : defs.comfort.entrySet()) if (amount(e.getKey()) >= 1) { comfort += e.getValue().bonus; goods.add(resourceName(e.getKey()).toLowerCase()); }
        if (comfort != 0) l.add(new Factor("Товары: " + String.join(", ", goods), Math.min(15, comfort)));
        return l;
    }

    // ------------------------------------------------------------------ приоритеты работ

    /** Приоритет профессии для рабочего: 0 - не делает, 1 - самое важное .. 4. Без записи: подёнщик берётся за всё (1), остальные - только своё (1). */
    public int workPriority(Worker w, String prof) {
        if (!w.canPerform(prof)) return 0;
        if (w.jobPrio != null && w.jobPrio.containsKey(prof)) return w.jobPrio.get(prof);
        return 1;
    }

    public void setWorkPriority(int workerId, String prof, int n) {
        Worker w = worker(workerId);
        if (w == null) throw new OrderException("Нет рабочего " + workerId);
        if (!defs.professions.containsKey(prof)) throw new OrderException("Нет профессии " + prof);
        if (n < 0 || n > 4) throw new OrderException("Приоритет 0..4");
        if (w.jobPrio == null) w.jobPrio = new TreeMap<>();
        w.jobPrio.put(prof, n);
    }

    // ------------------------------------------------------------------ движение рабочих (для показа)

    /** Куда идёт рабочий: у здания - точка внутри его площадки (у каждого своя), свободный - у центра поселения. {x, z}. */
    public double[] targetOf(Worker w) {
        if (w.manualOrder) return new double[]{w.orderX, w.orderZ};
        if (w.manualFollow) return new double[]{w.x, w.z};
        if (!w.lifeAction.isBlank()) return new double[]{w.lifeTargetX, w.lifeTargetZ};
        Building b = w.building == 0 ? null : building(w.building);
        double a = w.id * 2.399963;
        if (b == null) {
            double r = 2 + w.id % 3;
            return new double[]{StrictMath.cos(a) * r, StrictMath.sin(a) * r};
        }
        double r = 0.6 + 0.35 * (w.id % 3);
        double rx = Math.min(r, Math.max(0, b.def.sizeX / 2.0 - 0.6)), rz = Math.min(r, Math.max(0, b.def.sizeZ / 2.0 - 0.6));
        return new double[]{b.x + StrictMath.cos(a) * rx, b.z + StrictMath.sin(a) * rz};
    }

    private void move(double dt) {
        for (Worker w : workers) {
            double[] t = targetOf(w);
            double dx = t[0] - w.x, dz = t[1] - w.z, d = StrictMath.sqrt(dx * dx + dz * dz), step = WORKER_SPEED * dt;
            if (d <= step) { w.x = t[0]; w.z = t[1]; w.moving = false; }
            else { w.x += dx / d * step; w.z += dz / d * step; w.moving = true; }
        }
    }

    // ------------------------------------------------------------------ сведения для окна поселения

    /** Снимает с работы нескольких рабочих; неизвестные номера пропускаются; возвращает сколько снято. */
    public int unassignMany(Collection<Integer> workerIds) {
        int n = 0;
        for (int id : new LinkedHashSet<>(workerIds)) if (worker(id) != null) { unassign(id); n++; }
        if (n == 0) throw new OrderException("Никто не выбран");
        return n;
    }

    public String resourceName(String res) { return defs.resourceNames.getOrDefault(res, res); }

    /** Через сколько секунд кончится еда при текущем чистом потоке; -1, если еда не убывает. 0 - уже кончилась. */
    public double suppliesSeconds() {
        double net = income("food");
        if (workers.isEmpty() || net >= -1e-6) return -1;
        return amount("food") / (-net / 60);
    }

    /** Излишек: запас минус то, что уже обещано (стоимость заложенных зданий) и расход на ближайшие 2 минуты. Может быть отрицательным. */
    public double surplus(String res) {
        double reserve = consumedPerMin(res) * 2;
        for (Building b : buildings) if (b.state == Building.State.PLANNED) reserve += b.def.cost.getOrDefault(res, 0);
        return amount(res) - reserve;
    }

    /** Первый не хватающий ресурс для оплаты (имя ресурса) или null. */
    public String missing(Map<String, Integer> cost) {
        for (Map.Entry<String, Integer> e : cost.entrySet()) if (amount(e.getKey()) + 1e-9 < e.getValue()) return e.getKey();
        return null;
    }

    /** Тревога для окна: severity 0 сведение, 1 внимание, 2 тревога; kind - вид (food, starving, housing, materials, inputs, workers, storage, idle, approval); building - uid или 0. */
    public static final class Alert {
        public final int severity, building;
        public final String text, kind;

        Alert(int severity, String kind, String text, int building) { this.severity = severity; this.kind = kind; this.text = text; this.building = building; }
    }

    /** Текущие тревоги поселения, самые важные первыми. */
    public List<Alert> alerts() {
        List<Alert> l = new ArrayList<>();
        int n = population();
        if (n > 0 && amount("food") < 1) l.add(new Alert(2, "starving", "Голод: еда закончилась, жители умирают", 0));
        else {
            double sec = suppliesSeconds();
            if (sec >= 0 && sec < 300) l.add(new Alert(sec < 120 ? 2 : 1, "food", "Еды хватит ещё на " + Math.max(1, Math.round(sec / 60)) + " мин", 0));
        }
        if (raidIncoming) l.add(new Alert(2, "raid", "Налётчики (сила " + Math.round(raidStrength) + ") через " + Math.max(1, Math.round(raidWarnLeft)) + " с; оборона " + Math.round(defense()), 0));
        if (seasonIndex() == 2 && seasonProgress() > 0.7) l.add(new Alert(0, "season", "Скоро зима: фермы почти встанут, копите еду", 0));
        if (n >= housing()) l.add(new Alert(1, "housing", "Жильё заполнено (" + n + "/" + housing() + "): новые жители не придут", 0));
        for (Building b : buildings) {
            if (b.state == Building.State.PLANNED) {
                String m = missing(b.def.cost);
                if (m != null) l.add(new Alert(1, "materials", b.def.name + " ждёт материалов: не хватает " + resourceName(m).toLowerCase() + " (" + (int) amount(m) + "/" + b.def.cost.get(m) + ")", b.uid));
            } else if (b.state == Building.State.BUILDING) {
                if (assignedWith(b.uid, "builder") == 0) l.add(new Alert(1, "workers", b.def.name + ": стройка без строителей", b.uid));
            } else if (!b.paused) {
                if (b.def.slots() > 0 && assigned(b.uid).isEmpty()) l.add(new Alert(1, "workers", b.def.name + " без работников", b.uid));
                else if (b.def.produces()) {
                    Chain c = chain(b);
                    if (c.blocker.equals("inputs")) l.add(new Alert(1, "inputs", b.def.name + ": " + c.bottleneck.toLowerCase(), b.uid));
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (Building b : buildings) {
            if (b.state != Building.State.DONE || b.paused || !b.def.produces()) continue;
            for (String r : b.def.out.keySet()) {
                if (amount(r) >= capacity(r) - 1.5 && seen.add(r)) l.add(new Alert(1, "storage", "Склад полон: " + resourceName(r).toLowerCase() + " (" + (int) amount(r) + "/" + (int) capacity(r) + "), " + b.def.name + " стоит", b.uid));
            }
        }
        int idle = idleWorkers().size();
        if (idle > 0) l.add(new Alert(idle >= 3 ? 1 : 0, "idle", "Свободных работников: " + idle, 0));
        int ap = approval();
        if (ap < APPROVAL_LEAVE_BELOW) l.add(new Alert(2, "approval", "Настроение " + ap + ": жители уходят", 0));
        else if (ap < APPROVAL_GROWTH_MIN) l.add(new Alert(1, "approval", "Настроение " + ap + ": новые жители не приходят", 0));
        l.sort(Comparator.comparingInt((Alert a) -> -a.severity));
        return l;
    }


    /** Производственная цепочка здания: потоки в минуту при текущем штате, КПД и узкое место. */
    public static final class Chain {
        public final Map<String, Double> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        public int staffed, slots;
        /** 0..1: доля штата, а при остановке (нет сырья, склад полон, пауза) 0. */
        public double efficiency;
        /** Текст причины, пусто - узких мест нет. */
        public String bottleneck = "";
        /** Вид: "", paused, workers, inputs, storage, materials, builders. */
        public String blocker = "";
    }

    public Chain chain(Building b) {
        Chain c = new Chain();
        Defs.Building d = b.def;
        if (b.state == Building.State.PLANNED) {
            String m = missing(d.cost);
            if (m != null) { c.blocker = "materials"; c.bottleneck = "Не хватает: " + resourceName(m) + " (" + (int) amount(m) + " из " + d.cost.get(m) + ")"; }
            c.slots = d.builders;
            return c;
        }
        if (b.state == Building.State.BUILDING) {
            c.slots = d.builders;
            c.staffed = Math.min(d.builders, assignedWith(b.uid, "builder"));
            c.efficiency = c.slots == 0 ? 1 : (double) c.staffed / c.slots;
            if (c.staffed == 0) { c.blocker = "builders"; c.bottleneck = "Нет строителей"; }
            return c;
        }
        c.slots = d.slots();
        StringBuilder lack = new StringBuilder();
        for (Map.Entry<String, Integer> j : d.jobs.entrySet()) {
            int have = Math.min(assignedWith(b.uid, j.getKey()), j.getValue());
            c.staffed += have;
            if (have < j.getValue()) lack.append(lack.length() == 0 ? "" : ", ").append(defs.professionName(j.getKey())).append(" x").append(j.getValue() - have);
        }
        double rate = c.slots == 0 ? 1 : (double) c.staffed / c.slots;
        c.efficiency = rate;
        if (d.produces()) {
            double perMin = 60 / d.cycle * rate;
            for (Map.Entry<String, Integer> e : d.in.entrySet()) c.in.put(e.getKey(), e.getValue() * perMin);
            for (Map.Entry<String, Integer> e : d.out.entrySet()) c.out.put(e.getKey(), e.getValue() * perMin);
        }
        if (b.paused) { c.blocker = "paused"; c.bottleneck = "Пауза"; c.efficiency = 0; }
        else if (c.slots > 0 && c.staffed == 0) { c.blocker = "workers"; c.bottleneck = "Нет работников: " + lack; c.efficiency = d.produces() ? 0 : 0; }
        else if (d.produces() && !canPay(d.in)) {
            String m = missing(d.in);
            c.blocker = "inputs"; c.bottleneck = "Нет сырья: " + resourceName(m).toLowerCase(); c.efficiency = 0;
        } else if (d.produces() && !roomFor(d.out)) {
            String full = "";
            for (Map.Entry<String, Integer> o : d.out.entrySet()) if (amount(o.getKey()) + o.getValue() > capacity(o.getKey()) + 1e-9) { full = o.getKey(); break; }
            c.blocker = "storage"; c.bottleneck = "Склад полон: " + resourceName(full).toLowerCase(); c.efficiency = 0;
        } else if (lack.length() > 0) c.bottleneck = "Не хватает работников: " + lack;
        return c;
    }

    private boolean roomFor(Map<String, Integer> out) {
        for (Map.Entry<String, Integer> o : out.entrySet()) if (amount(o.getKey()) + o.getValue() > capacity(o.getKey()) + 1e-9) return false;
        return true;
    }

    // ------------------------------------------------------------------ настроение

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * Разбор настроения жителей: база, еда, жильё, занятость, охрана. Сумма (0..100) - {@link #approval}.
     * Меньше {@value #APPROVAL_GROWTH_MIN} - новые жители не приходят, меньше {@value #APPROVAL_LEAVE_BELOW} - жители уходят.
     */
    public List<Factor> approvalBreakdown() {
        List<Factor> l = new ArrayList<>();
        int n = population();
        l.add(new Factor("Базовое настроение", APPROVAL_BASE));
        int food = 0;
        if (n > 0) food = amount("food") < 1 ? -30 : clamp((int) Math.round((amount("food") / n - 5) * 2), -30, 15);
        l.add(new Factor(food < 0 ? "Голод, мало еды" : "Еда", food));
        int over = n - housing(), spare = housing() - n;
        l.add(over > 0 ? new Factor("Теснота в домах", -Math.min(20, over * 5)) : new Factor("Жильё", spare >= 2 ? 5 : 0));
        int emp = 0;
        if (!workers.isEmpty()) emp = (int) Math.round(10 - 20.0 * idleWorkers().size() / workers.size());
        l.add(new Factor(emp < 0 ? "Безработица" : "Занятость", emp));
        int guards = 0;
        for (Worker w : workers) if (w.profession.equals("guard")) guards++;
        int need = (int) Math.ceil(n / 6.0), guard = n < 6 ? 0 : guards >= need ? 8 : guards == 0 ? -5 : 2;
        l.add(new Factor(guard < 0 ? "Нет охраны" : "Охрана", guard));
        l.addAll(economyFactors());
        return l;
    }

    public int approval() {
        int t = 0;
        for (Factor f : approvalBreakdown()) t += f.value;
        return clamp(t, 0, 100);
    }

    // ------------------------------------------------------------------ сохранение

    public String toJson() {
        for (String resource : new TreeSet<>(stock.keySet())) amount(resource);
        amount(Defs.SILVER);
        syncAllResourceMirrors();
        stockLedgerEntries = TreasuryService.current().journal().size();
        stockReconciliationChecked = true;
        reconcileStockMirror = false;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-settlement/2");
        root.put("schema_version", 2);
        root.put("id", id); root.put("name", name); root.put("ledger_scope", ledgerScope); root.put("time", time); root.put("auto", auto);
        root.put("canonical_population", canonicalPopulation);
        root.put("next_building", nextBuilding); root.put("next_worker", nextWorker); root.put("grow", growTimer); root.put("starve", starveTimer); root.put("leave", leaveTimer);
        root.put("flow", flow.save());
        root.put("resident_task_graph", taskGraph.toJson());
        root.put("raid_timer", raidTimer); root.put("raid_strength", raidStrength); root.put("raid_warn", raidWarnLeft); root.put("raid_in", raidIncoming); root.put("raid_ok", raidsRepelled); root.put("raid_bad", raidsLost); root.put("raids", raids);
        root.put("pressure", new LinkedHashMap<String, Object>(pressure)); root.put("tax", taxLevel); root.put("trade_left", tradeLeft);
        root.put("tax_got", taxCollected); root.put("remitted", remittedSilver); root.put("granted", grantedSilver); root.put("crown", crownShare);
        root.put("auto_sell", new LinkedHashMap<String, Object>(autoSell)); root.put("auto_buy", new LinkedHashMap<String, Object>(autoBuy));
        root.put("stock", stock);
        root.put("stock_ledger_entries", stockLedgerEntries);
        List<Object> bs = new ArrayList<>();
        for (Building b : buildings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uid", b.uid); m.put("def", b.def.id); m.put("x", b.x); m.put("z", b.z); m.put("state", b.state.name());
            m.put("progress", b.progress); m.put("cycle", b.cycleTime); m.put("priority", b.priority); m.put("status", b.status); m.put("paused", b.paused); m.put("external", b.externallyMaterialized);
            m.put("local_stock", new LinkedHashMap<>(b.localStock));
            bs.add(m);
        }
        root.put("buildings", bs);
        List<Object> ws = new ArrayList<>();
        for (Worker w : workers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.id); m.put("name", w.name); m.put("profession", w.profession); m.put("assigned_profession", w.assignedProfession); m.put("person", w.person); m.put("building", w.building); m.put("status", w.status); m.put("x", w.x); m.put("z", w.z);
            m.put("life_action", w.lifeAction); m.put("life_target_x", w.lifeTargetX); m.put("life_target_z", w.lifeTargetZ);
            m.put("life_action_until", w.lifeActionUntil); m.put("life_action_stage", w.lifeActionStage);
            m.put("life_social_target", w.lifeSocialTarget); m.put("life_action_duration", w.lifeActionDuration);
            m.put("haul_progress", w.haulProgress);
            m.put("manual_hauler", w.manualHauler);
            m.put("manual_order", w.manualOrder); m.put("manual_follow", w.manualFollow); m.put("manual_follow_owner", w.manualFollowOwner); m.put("order_x", w.orderX); m.put("order_z", w.orderZ);
            List<Object> queuedOrders=new ArrayList<>();
            for(Worker.ManualOrder order:w.orderQueue)queuedOrders.add(Map.of("x",order.x(),"z",order.z()));
            m.put("order_queue",queuedOrders);
            if (w.jobPrio != null) m.put("prio", new LinkedHashMap<String, Object>(w.jobPrio));
            ws.add(m);
        }
        root.put("workers", ws);
        List<Object> ds = new ArrayList<>();
        for (WorkerDeparture d : departures) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("person", d.personId()); m.put("name", d.name()); m.put("reason", d.reason());
            ds.add(m);
        }
        root.put("departures", ds);
        root.put("placement_receipts", new TreeMap<>(placementReceipts));
        root.put("log", new ArrayList<Object>(log));
        return Json.write(root);
    }

    public static Settlement fromJson(String json, Defs defs) { return fromJson(json, defs, null); }

    @SuppressWarnings("unchecked")
    public static Settlement fromJson(String json, Defs defs, String scope) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        String format = root.get("format") instanceof String f ? f : "";
        if (!"wf-settlement/1".equals(format) && !"wf-settlement/2".equals(format))
            throw new IllegalArgumentException("Неверный формат поселения: " + format);
        Settlement s = new Settlement((String) root.get("id"), (String) root.get("name"), defs);
        Object savedScope = root.get("ledger_scope");
        s.setLedgerScope(scope != null && !scope.isBlank() ? scope : savedScope instanceof String v ? v : "");
        s.time = ((Number) root.get("time")).doubleValue();
        s.auto = Boolean.TRUE.equals(root.get("auto"));
        if (root.get("canonical_population") instanceof Number population) {
            int savedPopulation = population.intValue();
            s.canonicalPopulation = savedPopulation < 0 ? -1 : Math.min(100_000, savedPopulation);
        }
        s.nextBuilding = ((Number) root.get("next_building")).intValue();
        s.nextWorker = ((Number) root.get("next_worker")).intValue();
        s.growTimer = ((Number) root.get("grow")).doubleValue();
        s.starveTimer = ((Number) root.get("starve")).doubleValue();
        if (root.get("leave") instanceof Number n) s.leaveTimer = n.doubleValue();
        s.flow.load(root.get("flow"));
        if (root.get("resident_task_graph") instanceof String graph)
            s.taskGraph.fromJson(graph);
        if (root.get("raid_timer") instanceof Number rt) s.raidTimer = rt.doubleValue();
        if (root.get("raid_strength") instanceof Number rs) s.raidStrength = rs.doubleValue();
        if (root.get("raid_warn") instanceof Number rw) s.raidWarnLeft = rw.doubleValue();
        s.raidIncoming = Boolean.TRUE.equals(root.get("raid_in"));
        if (root.get("raid_ok") instanceof Number ro) s.raidsRepelled = ro.intValue();
        if (root.get("raid_bad") instanceof Number rb) s.raidsLost = rb.intValue();
        if (root.get("raids") instanceof Boolean rbo) s.raids = rbo;
        if (root.get("pressure") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) s.pressure.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
        if (root.get("tax") instanceof Number tx) s.taxLevel = Math.max(0, Math.min(3, tx.intValue()));
        if (root.get("trade_left") instanceof Number tl) s.tradeLeft = tl.doubleValue();
        if (root.get("tax_got") instanceof Number tg) s.taxCollected = tg.doubleValue();
        if (root.get("remitted") instanceof Number rm) s.remittedSilver = rm.doubleValue();
        if (root.get("granted") instanceof Number gr) s.grantedSilver = gr.doubleValue();
        if (root.get("crown") instanceof Number cr) s.crownShare = Math.max(0, Math.min(60, cr.intValue()));
        if (root.get("auto_sell") instanceof Map<?, ?> am) for (var e : am.entrySet()) s.autoSell.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
        if (root.get("auto_buy") instanceof Map<?, ?> bm) for (var e : bm.entrySet()) s.autoBuy.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("stock")).entrySet()) s.stock.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        if (root.get("stock_ledger_entries") instanceof Number entries)
            s.stockLedgerEntries = Math.max(0, entries.longValue());
        for (Object o : (List<Object>) root.get("buildings")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Defs.Building d = defs.buildings.get((String) m.get("def"));
            if (d == null) continue;                                              // здание из удалённого датапака пропускаем
            Building b = new Building(((Number) m.get("uid")).intValue(), d, ((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue());
            b.state = Building.State.valueOf((String) m.get("state"));
            b.progress = ((Number) m.get("progress")).doubleValue(); b.cycleTime = ((Number) m.get("cycle")).doubleValue();
            b.priority = ((Number) m.get("priority")).intValue(); b.status = (String) m.get("status"); b.paused = Boolean.TRUE.equals(m.get("paused")); b.externallyMaterialized = Boolean.TRUE.equals(m.get("external"));
            if (m.get("local_stock") instanceof Map<?, ?> local)
                for (Map.Entry<?, ?> entry : local.entrySet())
                    if (entry.getKey() instanceof String resource && entry.getValue() instanceof Number quantity
                            && Double.isFinite(quantity.doubleValue()) && quantity.doubleValue() > 0
                            && quantity.doubleValue() <= LOCAL_BUFFER_CAP)
                        b.localStock.put(resource, quantity.doubleValue());
            s.buildings.add(b);
        }
        for (Object o : (List<Object>) root.get("workers")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Worker w = new Worker(((Number) m.get("id")).intValue(), (String) m.get("name"));
            w.profession = m.get("profession") instanceof String profession && !profession.isBlank() ? profession : "laborer";
            w.assignedProfession = m.get("assigned_profession") instanceof String ap ? ap : "";
            w.person = (String) m.get("person"); w.building = ((Number) m.get("building")).intValue(); w.status = (String) m.get("status");
            if (m.get("x") instanceof Number nx) w.x = nx.doubleValue();
            if (m.get("z") instanceof Number nz) w.z = nz.doubleValue();
            w.lifeAction = m.get("life_action") instanceof String la ? la : "";
            if (m.get("life_target_x") instanceof Number lx) w.lifeTargetX = lx.doubleValue();
            if (m.get("life_target_z") instanceof Number lz) w.lifeTargetZ = lz.doubleValue();
            if (m.get("life_action_until") instanceof Number lu && Double.isFinite(lu.doubleValue())) w.lifeActionUntil = lu.doubleValue();
            w.lifeActionStage = m.get("life_action_stage") instanceof String stage
                    && Set.of("", "travelling", "performing").contains(stage) ? stage : "";
            w.lifeSocialTarget = m.get("life_social_target") instanceof String target ? target : "";
            if (m.get("life_action_duration") instanceof Number duration && Double.isFinite(duration.doubleValue()))
                w.lifeActionDuration = Math.max(0, Math.min(1440, duration.doubleValue()));
            if (m.get("haul_progress") instanceof Number hp && Double.isFinite(hp.doubleValue())) w.haulProgress = Math.max(0, Math.min(100, hp.doubleValue()));
            w.manualHauler = Boolean.TRUE.equals(m.get("manual_hauler"));
            if (!Set.of("", "sleeping", "eating", "fleeing", "defending", "socializing").contains(w.lifeAction)) clearLifeAction(w);
            w.manualOrder = Boolean.TRUE.equals(m.get("manual_order")); w.manualFollow = Boolean.TRUE.equals(m.get("manual_follow")); w.manualFollowOwner = m.get("manual_follow_owner") instanceof String fo ? fo : "";
            if (m.get("order_x") instanceof Number ox) w.orderX = ox.doubleValue();
            if (m.get("order_z") instanceof Number oz) w.orderZ = oz.doubleValue();
            if(m.get("order_queue") instanceof List<?> queued){
                for(Object item:queued){
                    if(w.orderQueue.size()>=Worker.MAX_MANUAL_ORDERS)break;
                    if(!(item instanceof Map<?,?> point)||!(point.get("x") instanceof Number qx)||!(point.get("z") instanceof Number qz))continue;
                    double qxx=qx.doubleValue(),qzz=qz.doubleValue();
                    if(Double.isFinite(qxx)&&Double.isFinite(qzz)&&Math.abs(qxx)<=100000&&Math.abs(qzz)<=100000)
                        w.orderQueue.addLast(new Worker.ManualOrder(qxx,qzz));
                }
            }
            if (m.get("prio") instanceof Map<?, ?> pm) { w.jobPrio = new TreeMap<>(); for (var e : pm.entrySet()) w.jobPrio.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue()); }
            if (w.building != 0 && s.building(w.building) == null) w.building = 0;
            s.workers.add(w);
        }
        if (root.get("placement_receipts") instanceof Map<?, ?> receipts) {
            for (Map.Entry<?, ?> entry : receipts.entrySet()) {
                if (!(entry.getKey() instanceof String request) || !request.matches("[A-Za-z0-9_.:-]{1,128}")
                        || !(entry.getValue() instanceof String receipt) || receipt.length() > 256)
                    throw new IllegalArgumentException("Malformed settlement placement receipt");
                s.placementReceipts.put(request, receipt);
            }
            if (s.placementReceipts.size() > 10000)
                throw new IllegalArgumentException("Settlement placement receipt limit exceeded");
        }
        if (root.get("departures") instanceof List<?> ds) for (Object o : ds) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String person = m.get("person") instanceof String v ? v : "";
            String name = m.get("name") instanceof String v ? v : "";
            String reason = m.get("reason") instanceof String v ? v : "migration:legacy";
            s.departures.add(new WorkerDeparture(person, name, reason));
        }
        for (Object o : (List<Object>) root.get("log")) s.log.add((String) o);
        s.syncAllResourceMirrors();
        return s;
    }
}
