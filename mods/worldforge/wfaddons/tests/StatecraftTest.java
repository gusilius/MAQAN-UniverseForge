package dev.worldforge.tests;

import dev.worldforge.realms.*;

import java.nio.file.*;
import java.util.*;

public final class StatecraftTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) failures++;
    }
    private static RealmDefs definitions() throws Exception {
        RealmDefs defs = new RealmDefs();
        Path dir = Paths.get("wf-realms/src/main/resources/data/wf_realms/wf_realms");
        try (var files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) defs.load(Files.readString(file));
        }
        return defs;
    }

    public static void main(String[] args) throws Exception {
        Statecraft state = new Statecraft();

        // Titles and feudal hierarchy
        Statecraft.Title kingdom = state.createTitle("crown", "north", Statecraft.Rank.KINGDOM, "Kingdom of the North");
        Statecraft.Title duchy = state.createTitle("north_duchy", "north", Statecraft.Rank.DUCHY, "Northern Duchy");
        Statecraft.Title county = state.createTitle("winter_county", "north", Statecraft.Rank.COUNTY, "Winter County");
        check(state.grantTitle("crown", "c1") && state.grantTitle("north_duchy", "c2")
                && state.setTitleLiege("north_duchy", "crown") && state.setTitleLiege("winter_county", "north_duchy"),
                "titles have holders and a ranked liege hierarchy");
        check(!state.setTitleLiege("crown", "winter_county"), "lower-rank liege is rejected");
        check(!state.setTitleLiege("crown", "crown"), "self-liege is rejected");
        check(state.addClaim("crown", "c3") && state.inheritTitle("crown", "c1", "c3")
                && kingdom.holderCharacterId.equals("c3"), "validated Character ID claim transfers a title on succession");

        // Vassalage and feudal obligations
        check(state.setVassalage("north_duchy_realm", "north", 0.2, 0.35)
                && state.setVassalage("winter_county_realm", "north_duchy_realm", 0.1, 0.2),
                "nested vassal contracts are accepted");
        check(!state.setVassalage("north", "winter_county_realm", 0.1, 0.1),
                "nested vassalage cycle is rejected");
        check(!state.concedeVassalDemands("north", List.of("north_duchy_realm", "not-a-vassal"))
                        && state.vassalage("north_duchy_realm").taxShare == 0.2,
                "liberty demands validate every member before changing any feudal contract");
        check(state.concedeVassalDemands("north", List.of("north_duchy_realm"))
                        && Math.abs(state.vassalage("north_duchy_realm").taxShare - 0.15) < 1e-9
                        && Math.abs(state.vassalage("north_duchy_realm").levyShare - 0.25) < 1e-9
                        && state.vassalage("north_duchy_realm").loyalty == 65,
                "accepted liberty demands reduce canonical tribute and levy obligations and restore loyalty");
        check(state.changeLoyalty("north_duchy_realm", -70) == 0
                && state.changeLoyalty("north_duchy_realm", 25) == 25,
                "vassal loyalty is clamped and mutable");
        check(state.vassalsOf("north").size() == 1, "liege can enumerate direct vassals");

        state.vassalage("north_duchy_realm").loyalty = 50;
        check(state.advanceVassalPolitics(1000, Map.of("north_duchy_realm", 60)) == 0
                        && state.vassalage("north_duchy_realm").loyalty == 50,
                "monthly vassal politics establishes its first review baseline without retroactive drift");
        check(state.advanceVassalPolitics(1030, Map.of("north_duchy_realm", 60)) == 1
                        && state.vassalage("north_duchy_realm").loyalty == 53,
                "vassal loyalty rises from ruler relations and protection, with contract burden applied");
        check(state.advanceVassalPolitics(1030, Map.of("north_duchy_realm", 60)) == 0
                        && state.vassalage("north_duchy_realm").loyalty == 53,
                "repeated realm ticks on the same day cannot duplicate a monthly political review");
        check(state.advanceVassalPolitics(1060, Map.of("north_duchy_realm", -80)) == 1
                        && state.vassalage("north_duchy_realm").loyalty == 51,
                "high tax and levy obligations combined with hostile ruler relations reduce vassal loyalty");

        // Dynasty and canonical identity references
        Statecraft.Dynasty dynasty = state.registerDynasty("house_stark", "c1");
        check(state.addDynastyMember("house_stark", "c2") && state.addDynastyPrestige("house_stark", 40)
                && dynasty.members.containsAll(List.of("c1", "c2")) && dynasty.prestige == 40,
                "dynasty records members by canonical Character ID and prestige");

        // Treasury, tax laws and tribute
        RealmDefs defs = definitions();
        Realms realmRules = new Realms(defs);
        realmRules.hooks = new Realms.Hooks() {
            @Override public int provinces(String realm) { return 20; }
            @Override public int prestige(String realm) { return 1000; }
            @Override public int today() { return 1000; }
        };
        realmRules.state("north").gov = "feudal_monarchy";
        check(realmRules.setLaw("north", "taxation", "heavy"), "existing taxation law can be activated");
        state.fundTreasury("north_duchy_realm", 100, "seed-vassal-treasury");
        state.setTaxRate("north", 0.1);
        double income = state.collectTaxes(realmRules, "north", 1000, 1);
        check(income > 100 && state.treasury("north").balance == income,
                "tax revenue applies existing realm-law modifiers and vassal tribute");
        check(Math.abs(dev.worldforge.mod.api.TreasuryApi.balance("north")
                        - state.treasury("north").balance / 5.0) < 1e-9,
                "legacy gold treasury facade and Statecraft silver treasury read the same canonical account");
        double afterAssessment = state.treasury("north").balance;
        int journalAfterAssessment = dev.worldforge.mod.economy.TreasuryService.current().journal().size();
        state.treasury("north").taxDays = 0; // simulate stale Statecraft snapshot after a ledger commit
        state.collectTaxes(realmRules, "north", 1000, 1);
        check(Math.abs(state.treasury("north").balance - afterAssessment) < 1e-9
                        && dev.worldforge.mod.economy.TreasuryService.current().journal().size() == journalAfterAssessment,
                "replayed tax assessment cannot duplicate realm tax or vassal tribute after interrupted save");
        check(state.spend("north", 10, "roads") && !state.spend("north", 1_000_000, "unfunded"),
                "treasury expenses are constrained by available funds");

        check(state.fundArmy("north", 25, "north_guard", 2000)
                        && state.payArmyUpkeep("north_guard", 5, 2001, "garrison-month")
                        && dev.worldforge.mod.economy.TreasuryService.current().balance("army:north_guard") == 20,
                "realm budget funds a dedicated army account and upkeep is paid from it");
        state.treasury("debt_realm");
        check(state.setRealmCreditLimit("debt_realm", 10)
                        && state.borrowForRealm("debt_realm", 10, "emergency-loan", 2002)
                        && state.accrueRealmInterest("debt_realm", 0.5, 2003)
                        && state.realmBankrupt("debt_realm") && state.realmDebt("debt_realm") == 15,
                "sovereign borrowing, interest and bankruptcy are tracked by the shared ledger");

        // Diplomacy, treaties and truces
        state.setOpinion("north", "dorne", 80);
        state.setTreaty("north", "dorne", Statecraft.Treaty.NON_AGGRESSION, true);
        boolean treatyBlocksWar = false;
        try { state.declareWar("north", "dorne", "claim", "c1", 1001); }
        catch (IllegalStateException expected) { treatyBlocksWar = true; }
        check(treatyBlocksWar && state.hasTreaty("dorne", "north", Statecraft.Treaty.NON_AGGRESSION),
                "non-aggression treaty blocks a war in either realm ordering");
        state.setTreaty("north", "dorne", Statecraft.Treaty.NON_AGGRESSION, false);
        Statecraft.War war = state.declareWar("north", "dorne", "claim", "c1", 1001);
        check(state.changeWarScore(war.id, 65) && war.warScore == 65, "war records its casus belli and war score");
        check(state.concludeWar(war.id, Statecraft.WarStatus.ATTACKER_VICTORY, "crown claim recognized", 1100)
                && war.status == Statecraft.WarStatus.ATTACKER_VICTORY,
                "peace outcome and truce are recorded");
        boolean truceBlocksWar = false;
        try { state.declareWar("north", "dorne", "new_claim", "c1", 1050); }
        catch (IllegalStateException expected) { truceBlocksWar = true; }
        check(truceBlocksWar, "truce prevents renewed war before its expiry");

        // Intrigue
        Statecraft.Plot plot = state.createPlot("north", "c2", "c3", Statecraft.PlotKind.CLAIM_TITLE);
        check(state.backPlot(plot.id, "c4") && state.advancePlot(plot.id, 45)
                && plot.status == Statecraft.PlotStatus.ACTIVE, "intrigue recruits backers and accumulates progress");
        check(state.exposePlot(plot.id) && plot.discovered && plot.status == Statecraft.PlotStatus.EXPOSED
                && !state.advancePlot(plot.id, 80), "exposed plot cannot continue");
        Statecraft.Plot successful = state.createPlot("north", "c2", "c3", Statecraft.PlotKind.FABRICATE_CLAIM);
        check(state.advancePlot(successful.id, 100) && successful.status == Statecraft.PlotStatus.SUCCEEDED,
                "plot can complete successfully");

        // Succession law and complete save/load
        state.setInheritanceLaw("north", Statecraft.InheritanceLaw.COGNATIC);
        check(state.successionLawForPeople("north").equals("cognatic")
                && state.successionLawForPeople("unknown").equals("primogeniture"),
                "realm inheritance policy maps to the existing wf-people succession contract");
        String snapshot = state.toJson();
        Statecraft restored = new Statecraft();
        restored.fromJson(snapshot);
        check(restored.toJson().equals(snapshot), "political state survives deterministic save/load");
        check(restored.inheritanceLaw("north") == Statecraft.InheritanceLaw.COGNATIC
                && restored.title("crown").holderCharacterId.equals("c3")
                && restored.war(war.id).status == Statecraft.WarStatus.ATTACKER_VICTORY
                && restored.plot(plot.id).status == Statecraft.PlotStatus.EXPOSED
                && restored.lastVassalReviewDay() == 1060
                && restored.vassalage("north_duchy_realm").loyalty == 51,
                "succession, title, war, intrigue and monthly vassal politics survive save/load");
        boolean badRateRejected = false;
        try { restored.setTaxRate("north", 2); } catch (IllegalArgumentException expected) { badRateRejected = true; }
        check(badRateRejected, "invalid tax rates are rejected");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAIL");
        if (failures > 0) System.exit(1);
    }
}
