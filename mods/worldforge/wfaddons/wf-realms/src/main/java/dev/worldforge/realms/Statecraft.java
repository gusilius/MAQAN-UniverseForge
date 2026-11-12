package dev.worldforge.realms;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.economy.TreasuryService;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.economy.TreasuryLedger;

import java.util.*;

/**
 * Persistent political simulation for WorldForge.
 *
 * Character references are canonical Character IDs owned by wf-people. This module
 * never creates people or copies their identity. It owns titles, feudal contracts,
 * treasuries, diplomatic relations, plots and wars.
 */
public final class Statecraft {
    public enum Rank { BARONY, COUNTY, DUCHY, KINGDOM, EMPIRE }
    public enum Treaty { NON_AGGRESSION, ALLIANCE, TRADE_PACT, DEFENSIVE_PACT }
    public enum PlotKind { CLAIM_TITLE, UNDERMINE_RULER, FABRICATE_CLAIM, ASSASSINATION }
    public enum PlotStatus { GATHERING, ACTIVE, EXPOSED, SUCCEEDED, FAILED }
    public enum WarStatus { ACTIVE, WHITE_PEACE, ATTACKER_VICTORY, DEFENDER_VICTORY }
    public enum InheritanceLaw { PRIMOGENITURE, COGNATIC, ULTIMOGENITURE }

    public static final class Title {
        public final String id;
        public final String realmId;
        public final Rank rank;
        public String name;
        public String holderCharacterId = "";
        public String liegeTitleId = "";
        public final Set<String> claims = new LinkedHashSet<>();

        private Title(String id, String realmId, Rank rank, String name) {
            this.id = id; this.realmId = realmId; this.rank = rank; this.name = name;
        }
    }

    public static final class VassalContract {
        public final String vassalRealm;
        public String liegeRealm;
        public double taxShare;
        public double levyShare;
        public int loyalty = 50;
        public boolean protectedByLiege = true;
        private VassalContract(String vassalRealm, String liegeRealm, double taxShare, double levyShare) {
            this.vassalRealm = vassalRealm; this.liegeRealm = liegeRealm;
            this.taxShare = taxShare; this.levyShare = levyShare;
        }
    }

    public static final class Dynasty {
        public final String houseId;
        public String founderCharacterId;
        public int prestige;
        public final Set<String> members = new LinkedHashSet<>();
        private Dynasty(String houseId, String founderCharacterId) {
            this.houseId = houseId; this.founderCharacterId = founderCharacterId;
            if (!founderCharacterId.isEmpty()) members.add(founderCharacterId);
        }
    }

    public static final class Treasury {
        public double balance;
        public double taxRate = 0.10;
        public double lastIncome;
        public double lastExpenses;
        public long taxDays;
    }

    public static final class Relation {
        public final String realmA, realmB;
        public int opinion;
        public final Set<Treaty> treaties = EnumSet.noneOf(Treaty.class);
        public int truceUntilDay;
        private Relation(String a, String b) { realmA = a; realmB = b; }
    }

    public static final class Plot {
        public final String id, sponsorCharacterId, targetCharacterId, realmId;
        public final PlotKind kind;
        public final Set<String> backers = new LinkedHashSet<>();
        public PlotStatus status = PlotStatus.GATHERING;
        public double progress;
        public boolean discovered;
        private Plot(String id, String realm, String sponsor, String target, PlotKind kind) {
            this.id=id; realmId=realm; sponsorCharacterId=sponsor; targetCharacterId=target; this.kind=kind;
            backers.add(sponsor);
        }
    }

    public static final class War {
        public final String id, attackerRealm, defenderRealm, casusBelli, declaredByCharacterId;
        public int warScore;
        public int startDay;
        public WarStatus status = WarStatus.ACTIVE;
        public String outcome = "";
        private War(String id, String attacker, String defender, String cb, String ruler, int day) {
            this.id=id; attackerRealm=attacker; defenderRealm=defender; casusBelli=cb; declaredByCharacterId=ruler; startDay=day;
        }
    }

    private final Map<String, Title> titles = new LinkedHashMap<>();
    private final Map<String, VassalContract> vassals = new LinkedHashMap<>();
    private final Map<String, Dynasty> dynasties = new LinkedHashMap<>();
    private final Map<String, Treasury> treasuries = new LinkedHashMap<>();
    private final Map<String, Relation> relations = new LinkedHashMap<>();
    private final Map<String, Plot> plots = new LinkedHashMap<>();
    private final Map<String, War> wars = new LinkedHashMap<>();
    private final Map<String, InheritanceLaw> inheritanceLaws = new LinkedHashMap<>();
    private long sequence = 1;
    private int lastVassalReviewDay = -1;
    public String lastError = "";

    private boolean fail(String message) { lastError = message; return false; }
    private static String clean(String value, String what) {
        if (value == null) throw new IllegalArgumentException(what + " is required");
        String s = value.trim();
        if (s.isEmpty() || s.length() > 96 || !s.matches("[A-Za-z0-9_.:-]+"))
            throw new IllegalArgumentException("invalid " + what + ": " + value);
        return s;
    }
    private static double rate(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("rate must be between 0 and 1");
        return value;
    }
    private static double money(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("amount must be finite and nonnegative");
        return value;
    }
    private static String pair(String a, String b) { return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a; }
    private String nextId(String prefix) { return prefix + sequence++; }

    private static Object value(Map<?, ?> map, String key, Object fallback) {
        Object found = map.get(key);
        return found == null ? fallback : found;
    }

    // ---------------- Titles and feudal hierarchy ----------------

    public Title createTitle(String id, String realmId, Rank rank, String name) {
        String key = clean(id, "title id"), realm = clean(realmId, "realm id");
        Objects.requireNonNull(rank, "rank");
        if (titles.containsKey(key)) throw new IllegalArgumentException("title already exists: " + key);
        Title title = new Title(key, realm, rank, name == null || name.isBlank() ? key : name.trim());
        titles.put(key, title);
        return title;
    }

    public Title title(String id) { return titles.get(id); }
    public Collection<Title> titles() { return Collections.unmodifiableCollection(titles.values()); }

    /**
     * Idempotently imports the existing WorldForge map title tree and realm overlord links.
     * Political titles are namespaced by map; character holders are not guessed from display names.
     */
    public int syncMapTitles(String mapName, TitleData data) {
        Objects.requireNonNull(data, "title data");
        String namespace = (mapName == null ? "map" : mapName.trim()).replaceAll("[^A-Za-z0-9_.-]", "_");
        if (namespace.isEmpty()) namespace = "map";
        Map<Integer, String> ids = new LinkedHashMap<>();
        Map<String, TitleData.Title> sourceByKey = new HashMap<>();
        for (TitleData.Title title : data.allTitles()) sourceByKey.put(title.key, title);
        int imported = 0;
        for (TitleData.Realm realm : data.realmList()) {
            String realmId = clean(realm.id, "realm id");
            for (String key : realm.titleKeys) {
                TitleData.Title source = sourceByKey.get(key);
                if (source == null) continue;
                String id = "map:" + UUID.nameUUIDFromBytes((namespace + "|" + key)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ids.put(source.id, id);
                Title target = titles.get(id);
                if (target == null) {
                    int tier = Math.max(0, Math.min(Rank.values().length - 1, source.tier));
                    target = createTitle(id, realmId, Rank.values()[tier], source.name);
                    imported++;
                }
                Object holder = source.props.get("holderCharacterId");
                if (holder instanceof String characterId && !characterId.isBlank())
                    target.holderCharacterId = clean(characterId, "holder Character ID");
            }
        }
        for (TitleData.Title source : data.allTitles()) {
            String childId = ids.get(source.id), parentId = ids.get(source.parent);
            if (childId == null || parentId == null) continue;
            Title child = titles.get(childId), parent = titles.get(parentId);
            if (child != null && parent != null && child.liegeTitleId.isEmpty()
                    && child.realmId.equals(parent.realmId) && child.rank.ordinal() < parent.rank.ordinal())
                setTitleLiege(childId, parentId);
        }
        for (TitleData.Realm realm : data.realmList()) {
            if (realm.overlordId == null || realm.overlordId.isBlank() || vassals.containsKey(realm.id)) continue;
            if (data.realms.containsKey(realm.overlordId)) {
                Object tax = realm.props.get("tax_share"), levy = realm.props.get("levy_share");
                double taxShare = tax instanceof Number n ? Math.max(0, Math.min(1, n.doubleValue())) : 0.10;
                double levyShare = levy instanceof Number n ? Math.max(0, Math.min(1, n.doubleValue())) : 0.10;
                setVassalage(realm.id, realm.overlordId, taxShare, levyShare);
            }
        }
        return imported;
    }

    public boolean grantTitle(String titleId, String characterId) {
        Title title = titles.get(titleId);
        if (title == null) return fail("unknown title");
        title.holderCharacterId = characterId == null ? "" : clean(characterId, "Character ID");
        return true;
    }

    public boolean addClaim(String titleId, String characterId) {
        Title title = titles.get(titleId);
        if (title == null) return fail("unknown title");
        title.claims.add(clean(characterId, "claimant Character ID"));
        return true;
    }

    public boolean setTitleLiege(String titleId, String liegeTitleId) {
        Title title = titles.get(titleId), liege = titles.get(liegeTitleId);
        if (title == null || liege == null) return fail("both titles must exist");
        if (title == liege || title.rank.ordinal() >= liege.rank.ordinal()) return fail("liege title must have a higher rank");
        if (!title.realmId.equals(liege.realmId)) return fail("feudal titles must belong to the same realm");
        for (Title cursor = liege; cursor != null && !cursor.liegeTitleId.isEmpty(); cursor = titles.get(cursor.liegeTitleId))
            if (cursor.id.equals(titleId)) return fail("title hierarchy cycle");
        title.liegeTitleId = liegeTitleId;
        return true;
    }

    // ---------------- Vassalage ----------------

    public boolean setVassalage(String vassalRealm, String liegeRealm, double taxShare, double levyShare) {
        String vassal = clean(vassalRealm, "vassal realm"), liege = clean(liegeRealm, "liege realm");
        if (vassal.equals(liege)) return fail("a realm cannot be its own liege");
        rate(taxShare); rate(levyShare);
        String cursorRealm = liege;
        Set<String> visited = new HashSet<>();
        while (!cursorRealm.isEmpty()) {
            if (!visited.add(cursorRealm)) return fail("existing vassalage cycle");
            if (cursorRealm.equals(vassal)) return fail("vassalage cycle");
            VassalContract cursor = vassals.get(cursorRealm);
            if (cursor == null) break;
            cursorRealm = cursor.liegeRealm;
        }
        VassalContract contract = vassals.get(vassal);
        if (contract == null) vassals.put(vassal, new VassalContract(vassal, liege, taxShare, levyShare));
        else { contract.liegeRealm=liege; contract.taxShare=taxShare; contract.levyShare=levyShare; }
        return true;
    }

    public boolean releaseVassal(String vassalRealm) { return vassals.remove(vassalRealm) != null; }
    public VassalContract vassalage(String realm) { return vassals.get(realm); }
    public Collection<VassalContract> vassalsOf(String liegeRealm) {
        return vassals.values().stream().filter(v -> v.liegeRealm.equals(liegeRealm)).toList();
    }
    public int changeLoyalty(String vassalRealm, int delta) {
        VassalContract v = vassals.get(vassalRealm);
        if (v == null) throw new IllegalArgumentException("realm is not a vassal");
        v.loyalty = Math.max(0, Math.min(100, v.loyalty + delta));
        return v.loyalty;
    }

    /**
     * Monthly autonomous vassal politics. CourtSim supplies the current ruler-to-ruler
     * opinion snapshot; this layer owns contractual loyalty and its persistence.
     * Repeated ticks and save/reload cannot apply the same review twice.
     */
    public int advanceVassalPolitics(int day, Map<String, Integer> rulerOpinions) {
        if (day < 0) return 0;
        if (lastVassalReviewDay < 0) {
            lastVassalReviewDay = day;
            return 0;
        }
        int reviews = 0;
        while (day - lastVassalReviewDay >= 30) {
            lastVassalReviewDay += 30;
            reviews++;
            for (VassalContract contract : vassals.values()) {
                int opinion = Math.max(-100, Math.min(100,
                        rulerOpinions == null ? 0 : rulerOpinions.getOrDefault(contract.vassalRealm, 0)));
                int delta = opinion >= 50 ? 2 : opinion >= 20 ? 1
                        : opinion <= -60 ? -3 : opinion <= -30 ? -2 : 0;
                if (contract.taxShare >= 0.35) delta--;
                if (contract.levyShare >= 0.50) delta--;
                if (contract.protectedByLiege) delta++;
                Relation diplomatic = relations.get(pair(contract.vassalRealm, contract.liegeRealm));
                if (diplomatic != null && (diplomatic.treaties.contains(Treaty.ALLIANCE)
                        || diplomatic.treaties.contains(Treaty.DEFENSIVE_PACT))) delta++;
                contract.loyalty = Math.max(0, Math.min(100, contract.loyalty + delta));
            }
        }
        return reviews;
    }

    public int lastVassalReviewDay() { return lastVassalReviewDay; }

    /** Inheritance policy is stored per realm and translated by the wf-people succession adapter. */
    public void setInheritanceLaw(String realmId, InheritanceLaw law) {
        inheritanceLaws.put(clean(realmId, "realm id"), Objects.requireNonNull(law, "inheritance law"));
    }
    public InheritanceLaw inheritanceLaw(String realmId) {
        return inheritanceLaws.getOrDefault(realmId, InheritanceLaw.PRIMOGENITURE);
    }
    /** Exact law identifier accepted by wf-people Succession.onDeath(...). */
    public String successionLawForPeople(String realmId) {
        return inheritanceLaw(realmId).name().toLowerCase(Locale.ROOT);
    }

    /**
     * Applies a successor already validated by wf-people's family/succession resolver.
     * The successor must hold a recorded claim; this layer never invents kinship.
     */
    public boolean inheritTitle(String titleId, String deceasedCharacterId, String successorCharacterId) {
        Title title = titles.get(titleId);
        if (title == null) return fail("unknown title");
        if (!title.holderCharacterId.equals(deceasedCharacterId)) return fail("deceased character does not hold this title");
        String heir = clean(successorCharacterId, "successor Character ID");
        if (!title.claims.contains(heir)) return fail("successor has no recorded claim");
        title.holderCharacterId = heir;
        title.claims.remove(heir);
        return true;
    }

    // ---------------- Dynasties ----------------

    public Dynasty registerDynasty(String houseId, String founderCharacterId) {
        String house = clean(houseId, "house id");
        String founder = clean(founderCharacterId, "founder Character ID");
        Dynasty d = dynasties.computeIfAbsent(house, k -> new Dynasty(k, founder));
        d.members.add(founder);
        return d;
    }
    public boolean addDynastyMember(String houseId, String characterId) {
        Dynasty d = dynasties.get(houseId);
        if (d == null) return fail("unknown dynasty");
        d.members.add(clean(characterId, "member Character ID"));
        return true;
    }
    public Dynasty dynasty(String houseId) { return dynasties.get(houseId); }
    public boolean addDynastyPrestige(String houseId, int amount) {
        Dynasty d = dynasties.get(houseId);
        if (d == null) return fail("unknown dynasty");
        d.prestige = Math.max(0, Math.min(1_000_000, d.prestige + amount));
        return true;
    }

    // ---------------- Treasury, taxes and expenditure ----------------

    private static String treasuryAccountId(String realmId) { return "realm:" + clean(realmId, "realm id"); }

    /** Legacy view of the canonical core ledger, retained for UI and save compatibility. */
    public Treasury treasury(String realmId) {
        String id = clean(realmId, "realm id");
        Treasury snapshot = treasuries.computeIfAbsent(id, k -> new Treasury());
        TreasuryLedger ledger = TreasuryService.current();
        TreasuryLedger.Account account = ledger.account(treasuryAccountId(id));
        if (account == null) account = ledger.open(treasuryAccountId(id), id, "silver", snapshot.balance);
        snapshot.balance = account.balance;
        return snapshot;
    }

    public boolean fundTreasury(String realmId, double amount, String reference) {
        Treasury t = treasury(realmId);
        boolean ok = TreasuryService.current().deposit(treasuryAccountId(realmId), amount,
                TreasuryLedger.Kind.DEPOSIT, "statecraft", reference, 0, "realm treasury funding");
        if (ok) t.balance = TreasuryService.current().balance(treasuryAccountId(realmId));
        return ok;
    }
    public boolean setTaxRate(String realmId, double taxRate) {
        treasury(realmId).taxRate = rate(taxRate);
        return true;
    }
    public boolean spend(String realmId, double amount, String reason) {
        Treasury t = treasury(realmId);
        double value = money(amount);
        boolean ok = TreasuryService.current().withdraw(treasuryAccountId(realmId), value,
                TreasuryLedger.Kind.WITHDRAWAL, "government", reason, 0, "statecraft expenditure");
        if (!ok) return fail(TreasuryService.current().lastError());
        t.balance = TreasuryService.current().balance(treasuryAccountId(realmId));
        t.lastExpenses += value;
        return true;
    }

    /** Move an approved army budget from the realm treasury into its dedicated military account. */
    public boolean fundArmy(String realmId, double amount, String armyId, long worldMinute) {
        String realm = clean(realmId, "realm id");
        String army = "army:" + clean(armyId, "army id");
        Treasury snapshot = treasury(realm);
        TreasuryLedger ledger = TreasuryService.current();
        ledger.open(army, armyId, "silver", 0);
        boolean ok = ledger.transfer(treasuryAccountId(realm), army, money(amount),
                TreasuryLedger.Kind.ARMY_UPKEEP, "military-budget", armyId, worldMinute, "realm military appropriation");
        if (ok) snapshot.balance = ledger.balance(treasuryAccountId(realm));
        return ok;
    }

    /** Pay soldier/army upkeep from the dedicated military account; never silently overdraw. */
    public boolean payArmyUpkeep(String armyId, double amount, long worldMinute, String reference) {
        String account = "army:" + clean(armyId, "army id");
        TreasuryLedger ledger = TreasuryService.current();
        if (!ledger.hasAccount(account)) ledger.open(account, armyId, "silver", 0);
        return ledger.withdraw(account, money(amount), TreasuryLedger.Kind.ARMY_UPKEEP,
                "military-upkeep", reference, worldMinute, "army maintenance");
    }

    public boolean setRealmCreditLimit(String realmId, double limit) {
        treasury(realmId);
        return TreasuryService.current().setCreditLimit(treasuryAccountId(realmId), money(limit));
    }

    public boolean borrowForRealm(String realmId, double amount, String reference, long worldMinute) {
        Treasury snapshot = treasury(realmId);
        boolean ok = TreasuryService.current().borrow(treasuryAccountId(realmId), money(amount), worldMinute, reference);
        if (ok) snapshot.balance = TreasuryService.current().balance(treasuryAccountId(realmId));
        return ok;
    }

    public boolean accrueRealmInterest(String realmId, double rate, long worldMinute) {
        treasury(realmId);
        return TreasuryService.current().accrueInterest(treasuryAccountId(realmId), rate, worldMinute, "realm-interest");
    }

    public double realmDebt(String realmId) {
        TreasuryLedger.Account account = TreasuryService.current().account(treasuryAccountId(realmId));
        return account == null ? 0 : account.debt;
    }

    public boolean realmBankrupt(String realmId) {
        TreasuryLedger.Account account = TreasuryService.current().account(treasuryAccountId(realmId));
        return account != null && account.bankrupt;
    }

    /** Collects taxes using existing wf-realms law modifiers and sends vassal tribute to the liege. */
    public double collectTaxes(Realms realms, String realmId, double taxableBase, int days) {
        Objects.requireNonNull(realms, "realms");
        double base = money(taxableBase);
        if (days < 1 || days > 3650) throw new IllegalArgumentException("days must be 1..3650");
        Treasury t = treasury(realmId);
        double lawPct = realms.mods(realmId).getOrDefault("tax_pct", 0.0);
        double effectiveRate = Math.max(0, Math.min(1, t.taxRate * (1 + lawPct / 100.0)));
        double gross = base * effectiveRate * days;
        TreasuryLedger ledger = TreasuryService.current();
        long minute = Math.max(0, WorldForgeApi.clock().dayNumber()) * 1440L;
        String taxReference = "tax:" + realmId + ":" + t.taxDays;
        boolean taxAlreadyPosted = ledger.hasCredit(treasuryAccountId(realmId), TreasuryLedger.Kind.TAX, taxReference);
        if (gross > 0 && !ledger.depositOnce(treasuryAccountId(realmId), gross, TreasuryLedger.Kind.TAX,
                "taxation", taxReference, minute, "realm tax collection"))
            throw new IllegalStateException(ledger.lastError());
        double postedTax = taxAlreadyPosted ? 0 : gross;
        double tribute = 0;
        for (VassalContract v : vassalsOf(realmId)) {
            Treasury vt = treasury(v.vassalRealm);
            String vassalAccount = treasuryAccountId(v.vassalRealm);
            String tributeReference = "tribute:" + v.vassalRealm + ":" + t.taxDays;
            if (!ledger.hasDebit(vassalAccount, TreasuryLedger.Kind.VASSAL_TRIBUTE, tributeReference)) {
                double available = ledger.balance(vassalAccount);
                double payment = Math.min(available, available * v.taxShare);
                if (payment > 0 && ledger.transferOnce(vassalAccount, treasuryAccountId(realmId), payment,
                        TreasuryLedger.Kind.VASSAL_TRIBUTE, "feudal-obligation",
                        tributeReference, minute, "vassal tax obligation")) tribute += payment;
            }
            vt.balance = ledger.balance(vassalAccount);
        }
        double income = postedTax + tribute;
        t.balance = ledger.balance(treasuryAccountId(realmId));
        t.lastIncome = income;
        t.taxDays += days;
        return income;
    }

    // ---------------- Diplomacy ----------------

    private Relation relation(String a, String b) {
        String x=clean(a,"realm id"), y=clean(b,"realm id");
        if (x.equals(y)) throw new IllegalArgumentException("realm relation requires two realms");
        return relations.computeIfAbsent(pair(x,y), k -> new Relation(x,y));
    }
    public Relation relationOf(String a, String b) { return relation(a,b); }
    public boolean setOpinion(String a, String b, int opinion) {
        relation(a,b).opinion = Math.max(-100, Math.min(100, opinion)); return true;
    }
    public boolean setTreaty(String a, String b, Treaty treaty, boolean active) {
        Relation r=relation(a,b); Objects.requireNonNull(treaty,"treaty");
        if (active) r.treaties.add(treaty); else r.treaties.remove(treaty);
        return true;
    }
    public boolean hasTreaty(String a, String b, Treaty treaty) { return relation(a,b).treaties.contains(treaty); }
    public boolean setTruce(String a, String b, int untilDay) {
        if (untilDay < 0) throw new IllegalArgumentException("truce day cannot be negative");
        relation(a,b).truceUntilDay=untilDay; return true;
    }

    // ---------------- Intrigue ----------------

    public Plot createPlot(String realm, String sponsorCharacterId, String targetCharacterId, PlotKind kind) {
        String id=nextId("plot-");
        Plot p=new Plot(id, clean(realm,"realm id"), clean(sponsorCharacterId,"sponsor Character ID"),
                clean(targetCharacterId,"target Character ID"), Objects.requireNonNull(kind,"plot kind"));
        plots.put(id,p); return p;
    }
    public boolean backPlot(String plotId, String backerCharacterId) {
        Plot p=plots.get(plotId);
        if (p==null || p.status==PlotStatus.EXPOSED || p.status==PlotStatus.SUCCEEDED || p.status==PlotStatus.FAILED) return fail("plot is not recruiting");
        p.backers.add(clean(backerCharacterId,"backer Character ID")); return true;
    }
    public boolean advancePlot(String plotId, double progress) {
        Plot p=plots.get(plotId);
        if (p==null || p.status==PlotStatus.EXPOSED || p.status==PlotStatus.SUCCEEDED || p.status==PlotStatus.FAILED) return fail("plot is not active");
        if (!Double.isFinite(progress) || progress < 0) throw new IllegalArgumentException("invalid plot progress");
        p.progress=Math.min(100,p.progress+progress);
        if (p.progress>=100) p.status=PlotStatus.SUCCEEDED; else p.status=PlotStatus.ACTIVE;
        return true;
    }
    public boolean exposePlot(String plotId) {
        Plot p=plots.get(plotId);
        if (p==null || p.status==PlotStatus.SUCCEEDED || p.status==PlotStatus.FAILED) return fail("plot cannot be exposed");
        p.discovered=true; p.status=PlotStatus.EXPOSED; return true;
    }
    public Plot plot(String id) { return plots.get(id); }

    // ---------------- Wars ----------------

    public War declareWar(String attacker, String defender, String casusBelli, String rulerCharacterId, int day) {
        String a=clean(attacker,"attacker realm"), d=clean(defender,"defender realm");
        if (a.equals(d)) throw new IllegalArgumentException("cannot declare war on yourself");
        if (day < 0) throw new IllegalArgumentException("war day cannot be negative");
        Relation r=relation(a,d);
        if (r.truceUntilDay > day) throw new IllegalStateException("truce is still active");
        if (r.treaties.contains(Treaty.NON_AGGRESSION) || r.treaties.contains(Treaty.ALLIANCE))
            throw new IllegalStateException("a diplomatic treaty prevents war");
        if (wars.values().stream().anyMatch(w -> w.status==WarStatus.ACTIVE
                && ((w.attackerRealm.equals(a)&&w.defenderRealm.equals(d)) || (w.attackerRealm.equals(d)&&w.defenderRealm.equals(a)))))
            throw new IllegalStateException("these realms are already at war");
        War w=new War(nextId("war-"),a,d,clean(casusBelli,"casus belli"),clean(rulerCharacterId,"ruler Character ID"),day);
        wars.put(w.id,w); return w;
    }
    public boolean changeWarScore(String warId, int delta) {
        War w=wars.get(warId);
        if (w==null || w.status!=WarStatus.ACTIVE) return fail("war is not active");
        w.warScore=Math.max(-100,Math.min(100,w.warScore+delta)); return true;
    }
    public boolean concludeWar(String warId, WarStatus outcome, String terms, int truceUntilDay) {
        War w=wars.get(warId);
        if (w==null || w.status!=WarStatus.ACTIVE) return fail("war is not active");
        if (outcome==null || outcome==WarStatus.ACTIVE) return fail("a final war outcome is required");
        if (truceUntilDay < w.startDay) return fail("truce cannot end before the war began");
        w.status=outcome; w.outcome=terms==null?"":terms;
        setTruce(w.attackerRealm,w.defenderRealm,truceUntilDay);
        return true;
    }
    public War war(String id) { return wars.get(id); }

    // ---------------- Persistence ----------------

    public String toJson() {
        for (Map.Entry<String, Treasury> entry : treasuries.entrySet()) {
            TreasuryLedger.Account account = TreasuryService.current().account(treasuryAccountId(entry.getKey()));
            if (account != null) entry.getValue().balance = account.balance;
        }
        Map<String,Object> root=new LinkedHashMap<>();
        root.put("version",1); root.put("sequence",sequence);
        root.put("lastVassalReviewDay", lastVassalReviewDay);
        List<Object> ts=new ArrayList<>();
        for (Title t:titles.values()) { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",t.id);m.put("realm",t.realmId);m.put("rank",t.rank.name());m.put("name",t.name);m.put("holder",t.holderCharacterId);m.put("liege",t.liegeTitleId);m.put("claims",new ArrayList<>(t.claims));ts.add(m); }
        root.put("titles",ts);
        List<Object> vs=new ArrayList<>();
        for (VassalContract v:vassals.values()) { Map<String,Object> m=new LinkedHashMap<>();m.put("vassal",v.vassalRealm);m.put("liege",v.liegeRealm);m.put("tax",v.taxShare);m.put("levy",v.levyShare);m.put("loyalty",v.loyalty);m.put("protected",v.protectedByLiege);vs.add(m); }
        root.put("vassals",vs);
        List<Object> ds=new ArrayList<>();
        for (Dynasty d:dynasties.values()) { Map<String,Object> m=new LinkedHashMap<>();m.put("house",d.houseId);m.put("founder",d.founderCharacterId);m.put("prestige",d.prestige);m.put("members",new ArrayList<>(d.members));ds.add(m); }
        root.put("dynasties",ds);
        Map<String,Object> tr=new LinkedHashMap<>();
        for (var e:treasuries.entrySet()) { Treasury t=e.getValue();Map<String,Object> m=new LinkedHashMap<>();m.put("balance",t.balance);m.put("taxRate",t.taxRate);m.put("lastIncome",t.lastIncome);m.put("lastExpenses",t.lastExpenses);m.put("taxDays",t.taxDays);tr.put(e.getKey(),m); }
        root.put("treasuries",tr);
        List<Object> rs=new ArrayList<>();
        for (Relation r:relations.values()) { Map<String,Object> m=new LinkedHashMap<>();m.put("a",r.realmA);m.put("b",r.realmB);m.put("opinion",r.opinion);m.put("treaties",r.treaties.stream().map(Enum::name).toList());m.put("truce",r.truceUntilDay);rs.add(m); }
        root.put("relations",rs);
        List<Object> ps=new ArrayList<>();
        for (Plot p:plots.values()) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",p.id);m.put("realm",p.realmId);m.put("sponsor",p.sponsorCharacterId);m.put("target",p.targetCharacterId);m.put("kind",p.kind.name());m.put("backers",new ArrayList<>(p.backers));m.put("status",p.status.name());m.put("progress",p.progress);m.put("discovered",p.discovered);ps.add(m); }
        root.put("plots",ps);
        List<Object> ws=new ArrayList<>();
        for (War w:wars.values()) { Map<String,Object> m=new LinkedHashMap<>();m.put("id",w.id);m.put("attacker",w.attackerRealm);m.put("defender",w.defenderRealm);m.put("casusBelli",w.casusBelli);m.put("ruler",w.declaredByCharacterId);m.put("day",w.startDay);m.put("score",w.warScore);m.put("status",w.status.name());m.put("outcome",w.outcome);ws.add(m); }
        root.put("wars",ws);
        Map<String,Object> succession=new LinkedHashMap<>();
        inheritanceLaws.forEach((realm,law)->succession.put(realm,law.name()));
        root.put("inheritanceLaws",succession);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        Object parsed=Json.parse(json);
        if (!(parsed instanceof Map<?,?> root)) throw new IllegalArgumentException("statecraft save must be an object");
        Statecraft next=new Statecraft();
        next.sequence=root.get("sequence") instanceof Number n?Math.max(1,n.longValue()):1;
        next.lastVassalReviewDay=root.get("lastVassalReviewDay") instanceof Number n?n.intValue():-1;
        if(root.get("titles") instanceof List<?> list) for(Object x:list) {
            Map<?,?> m=(Map<?,?>)x;
            Title t=next.createTitle(String.valueOf(m.get("id")),String.valueOf(m.get("realm")),Rank.valueOf(String.valueOf(m.get("rank"))),String.valueOf(m.get("name")));
            t.holderCharacterId=String.valueOf(value(m,"holder",""));
            t.liegeTitleId=String.valueOf(value(m,"liege",""));
            if(m.get("claims") instanceof List<?> claims) for(Object id:claims)t.claims.add(String.valueOf(id));
        }
        if(root.get("vassals") instanceof List<?> list) for(Object x:list) {
            Map<?,?> m=(Map<?,?>)x; next.setVassalage(String.valueOf(m.get("vassal")),String.valueOf(m.get("liege")),((Number)m.get("tax")).doubleValue(),((Number)m.get("levy")).doubleValue());
            VassalContract v=next.vassals.get(String.valueOf(m.get("vassal")));v.loyalty=((Number)value(m,"loyalty",50)).intValue();v.protectedByLiege=!Boolean.FALSE.equals(m.get("protected"));
        }
        if(root.get("dynasties") instanceof List<?> list) for(Object x:list) {
            Map<?,?> m=(Map<?,?>)x; Dynasty d=next.registerDynasty(String.valueOf(m.get("house")),String.valueOf(m.get("founder")));
            d.prestige=((Number)value(m,"prestige",0)).intValue();if(m.get("members") instanceof List<?> members)for(Object id:members)d.members.add(String.valueOf(id));
        }
        if(root.get("treasuries") instanceof Map<?,?> map) for(var e:map.entrySet()) {
            Treasury t=next.treasury(String.valueOf(e.getKey()));Map<?,?> m=(Map<?,?>)e.getValue();
            t.balance=((Number)value(m,"balance",0.0)).doubleValue();t.taxRate=((Number)value(m,"taxRate",0.1)).doubleValue();
            t.lastIncome=((Number)value(m,"lastIncome",0.0)).doubleValue();t.lastExpenses=((Number)value(m,"lastExpenses",0.0)).doubleValue();t.taxDays=((Number)value(m,"taxDays",0)).longValue();
        }
        if(root.get("relations") instanceof List<?> list) for(Object x:list) {
            Map<?,?> m=(Map<?,?>)x;Relation r=next.relation(String.valueOf(m.get("a")),String.valueOf(m.get("b")));
            r.opinion=((Number)value(m,"opinion",0)).intValue();r.truceUntilDay=((Number)value(m,"truce",0)).intValue();
            if(m.get("treaties") instanceof List<?> treaties)for(Object v:treaties)r.treaties.add(Treaty.valueOf(String.valueOf(v)));
        }
        if(root.get("plots") instanceof List<?> list) for(Object x:list) {
            Map<?,?> m=(Map<?,?>)x;Plot p=new Plot(String.valueOf(m.get("id")),String.valueOf(m.get("realm")),String.valueOf(m.get("sponsor")),String.valueOf(m.get("target")),PlotKind.valueOf(String.valueOf(m.get("kind"))));
            if(m.get("backers") instanceof List<?> bs)for(Object id:bs)p.backers.add(String.valueOf(id));
            p.status=PlotStatus.valueOf(String.valueOf(value(m,"status","GATHERING")));p.progress=((Number)value(m,"progress",0.0)).doubleValue();p.discovered=Boolean.TRUE.equals(m.get("discovered"));next.plots.put(p.id,p);
        }
        if(root.get("inheritanceLaws") instanceof Map<?,?> map) for(var e:map.entrySet())
            next.setInheritanceLaw(String.valueOf(e.getKey()), InheritanceLaw.valueOf(String.valueOf(e.getValue())));
        if(root.get("wars") instanceof List<?> list) for(Object x:list) {
            Map<?,?> m=(Map<?,?>)x;War w=new War(String.valueOf(m.get("id")),String.valueOf(m.get("attacker")),String.valueOf(m.get("defender")),String.valueOf(m.get("casusBelli")),String.valueOf(m.get("ruler")),((Number)m.get("day")).intValue());
            w.warScore=((Number)value(m,"score",0)).intValue();w.status=WarStatus.valueOf(String.valueOf(value(m,"status","ACTIVE")));w.outcome=String.valueOf(value(m,"outcome",""));next.wars.put(w.id,w);
        }
        titles.clear();titles.putAll(next.titles);vassals.clear();vassals.putAll(next.vassals);dynasties.clear();dynasties.putAll(next.dynasties);
        treasuries.clear();treasuries.putAll(next.treasuries);relations.clear();relations.putAll(next.relations);plots.clear();plots.putAll(next.plots);wars.clear();wars.putAll(next.wars);inheritanceLaws.clear();inheritanceLaws.putAll(next.inheritanceLaws);sequence=next.sequence;lastVassalReviewDay=next.lastVassalReviewDay;
    }
}
