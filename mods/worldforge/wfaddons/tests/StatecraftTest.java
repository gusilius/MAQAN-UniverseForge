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
        check(!state.setTitleRank("north_duchy", Statecraft.Rank.KINGDOM)
                        && !state.setTitleRank("winter_county", Statecraft.Rank.KINGDOM),
                "realm rank promotion cannot invalidate an existing liege hierarchy");
        Statecraft.Title frontier = state.createTitle("frontier_rank", "frontier", Statecraft.Rank.COUNTY, "Frontier");
        check(state.setTitleRank(frontier.id, Statecraft.Rank.KINGDOM)
                        && frontier.rank == Statecraft.Rank.KINGDOM,
                "an independent realm title can evolve its canonical rank");
        check(state.addClaim("crown", "c3") && state.inheritTitle("crown", "c1", "c3")
                && kingdom.holderCharacterId.equals("c3"), "validated Character ID claim transfers a title on succession");

        // Image-map import: the realm lists only its crown title, while its provinces
        // are descendants several levels below. All cells must enter Statecraft.
        String titlesJson = """
                {"format":"worldforge-titles/1","blocks_per_pixel":1.0,
                 "tiers":[{"index":0,"key":"province","name":"Province"},{"index":1,"key":"region","name":"Region"},{"index":2,"key":"state","name":"State"}],
                 "titles":[
                  {"id":1,"key":"p_north","name":"North Province","tier":0,"parent":3,"color":"#336699","area_blocks":1,"area_pct_of_land":50.0,"capital":{"nx":0.2,"ny":0.2,"x":0,"z":0,"province":1},"props":{}},
                  {"id":2,"key":"p_south","name":"South Province","tier":0,"parent":4,"color":"#336699","area_blocks":1,"area_pct_of_land":50.0,"capital":{"nx":0.8,"ny":0.8,"x":1,"z":1,"province":2},"props":{}},
                  {"id":3,"key":"r_north","name":"Northern Region","tier":1,"parent":5,"color":"#336699","area_blocks":1,"area_pct_of_land":50.0,"capital":{"nx":0.2,"ny":0.2,"x":0,"z":0,"province":1},"props":{}},
                  {"id":4,"key":"r_south","name":"Southern Region","tier":1,"parent":5,"color":"#336699","area_blocks":1,"area_pct_of_land":50.0,"capital":{"nx":0.8,"ny":0.8,"x":1,"z":1,"province":2},"props":{}},
                  {"id":5,"key":"state_north","name":"Northland","tier":2,"parent":0,"color":"#336699","area_blocks":1,"area_pct_of_land":50.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}}
                 ]}""";
        String realmsJson = """
                {"format":"worldforge-realms/1","realms":[
                 {"id":"northland","name":"Northland","ruler":"","color":"#336699",
                  "titles":["state_north"],"subjects":[],"overlord":null,"relation":"","area_blocks":2,"props":{}}
                ]}""";
        dev.worldforge.mod.TitleData imageTitles = dev.worldforge.mod.TitleData.create(
                titlesJson, realmsJson, new short[]{1, 2}, 2, 1);
        Statecraft importedMap = new Statecraft();
        check(importedMap.syncMapTitles("image-world", imageTitles) == 5
                        && importedMap.title(Statecraft.mapTitleId("image-world", "p_north")) != null
                        && importedMap.title(Statecraft.mapTitleId("image-world", "p_south")) != null
                        && importedMap.title(Statecraft.mapTitleId("image-world", "r_north")) != null
                        && importedMap.title(Statecraft.mapTitleId("image-world", "r_south")) != null
                        && importedMap.title(Statecraft.mapTitleId("image-world", "state_north")) != null,
                "map import traverses crown descendants and registers every province and region cell");
        check(importedMap.title(Statecraft.mapTitleId("image-world", "r_north")).realmId.equals("northland")
                        && importedMap.title(Statecraft.mapTitleId("image-world", "p_south")).realmId.equals("northland"),
                "nested province and region ownership resolves to the sovereign realm");
        check(importedMap.syncMapTitles("image-world", imageTitles) == 0
                        && importedMap.titles().size() == 5,
                "re-import of the same political map is idempotent");
        boolean conflictingOwnershipRejected = false;
        String conflictingRealmsJson = realmsJson.replace("\"northland\"", "\"southland\"")
                .replace("\"Northland\"", "\"Southland\"");
        dev.worldforge.mod.TitleData conflictingMap = dev.worldforge.mod.TitleData.create(
                titlesJson, conflictingRealmsJson, new short[]{1, 2}, 2, 1);
        try { importedMap.syncMapTitles("image-world", conflictingMap); }
        catch (IllegalStateException expected) { conflictingOwnershipRejected = true; }
        check(conflictingOwnershipRejected,
                "an imported province cannot silently change sovereign ownership without a transfer event");

        String unclaimedRealmsJson = """
                {"format":"worldforge-realms/1","realms":[]}
                """;
        dev.worldforge.mod.TitleData unclaimedMap = dev.worldforge.mod.TitleData.create(
                titlesJson, unclaimedRealmsJson, new short[]{1, 2}, 2, 1);
        Statecraft firstClaim = new Statecraft();
        check(firstClaim.syncMapTitles("image-world", unclaimedMap) == 5
                        && firstClaim.title(Statecraft.mapTitleId("image-world", "p_north")).realmId
                                .equals("unclaimed:image-world"),
                "geographic map titles may begin as neutral unclaimed placeholders");
        check(firstClaim.syncMapTitles("image-world", imageTitles) == 0
                        && firstClaim.title(Statecraft.mapTitleId("image-world", "p_north")).realmId.equals("northland")
                        && firstClaim.title(Statecraft.mapTitleId("image-world", "r_north")).realmId.equals("northland")
                        && firstClaim.title(Statecraft.mapTitleId("image-world", "p_south")).realmId.equals("northland"),
                "the first generated sovereign ownership claim propagates through map title descendants");
        boolean firstClaimTransferRejected = false;
        try { firstClaim.syncMapTitles("image-world", conflictingMap); }
        catch (IllegalStateException expected) { firstClaimTransferRejected = true; }
        check(firstClaimTransferRejected,
                "first-claim support does not permit silent transfers between established realms");

        // Deep custom map hierarchies must remain intact beyond the five broad CK rank buckets.
        String deepTitlesJson = """
                {"format":"worldforge-titles/1","blocks_per_pixel":1.0,
                 "tiers":[
                  {"index":0,"key":"province","name":"Province"},
                  {"index":1,"key":"district","name":"District"},
                  {"index":2,"key":"county","name":"County"},
                  {"index":3,"key":"duchy","name":"Duchy"},
                  {"index":4,"key":"kingdom","name":"Kingdom"},
                  {"index":5,"key":"empire","name":"Empire"},
                  {"index":6,"key":"super_empire","name":"Super Empire"}],
                 "titles":[
                  {"id":1,"key":"deep_0","name":"Deep 0","tier":0,"parent":2,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":2,"key":"deep_1","name":"Deep 1","tier":1,"parent":3,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":3,"key":"deep_2","name":"Deep 2","tier":2,"parent":4,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":4,"key":"deep_3","name":"Deep 3","tier":3,"parent":5,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":5,"key":"deep_4","name":"Deep 4","tier":4,"parent":6,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":6,"key":"deep_5","name":"Deep 5","tier":5,"parent":7,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":7,"key":"deep_6","name":"Deep 6","tier":6,"parent":0,"color":"#336699","area_blocks":1,"area_pct_of_land":100.0,"capital":{"nx":0.5,"ny":0.5,"x":0,"z":0,"province":1},"props":{}},
                  {"id":8,"key":"neutral_island","name":"Neutral Island","tier":0,"parent":0,"color":"#888888","area_blocks":1,"area_pct_of_land":0.0,"capital":{"nx":0.9,"ny":0.9,"x":0,"z":0,"province":0},"props":{}}]}
                """;
        String deepRealmsJson = """
                {"format":"worldforge-realms/1","realms":[
                 {"id":"deep_realm","name":"Deep Realm","ruler":"","color":"#336699",
                  "titles":["deep_6"],"subjects":[],"overlord":null,"relation":"","area_blocks":1,"props":{}}
                ]}
                """;
        var deepMap = dev.worldforge.mod.TitleData.create(deepTitlesJson, deepRealmsJson,
                new short[]{1}, 1, 1);
        Statecraft deepPolitics = new Statecraft();
        check(deepPolitics.syncMapTitles("deep-map", deepMap) == 8
                        && deepPolitics.titles().size() == 8,
                "Statecraft imports every title, including unclaimed neutral geography");
        boolean deepHierarchyPreserved = true;
        for (int tier = 0; tier < 7; tier++) {
            var title = deepPolitics.title(Statecraft.mapTitleId("deep-map", "deep_" + tier));
            deepHierarchyPreserved &= title != null && title.sourceTierIndex == tier
                    && title.sourceTierKey.equals(deepMap.tiers.get(tier).key);
            if (tier < 6) {
                var parent = deepPolitics.title(Statecraft.mapTitleId("deep-map", "deep_" + (tier + 1)));
                deepHierarchyPreserved &= title != null && parent != null
                        && title.sourceParentTitleId.equals(parent.id)
                        && title.liegeTitleId.equals(parent.id);
            }
        }
        check(deepHierarchyPreserved,
                "all seven source tiers retain exact tier keys, parent IDs and liege links without five-rank truncation");
        var neutralTitle = deepPolitics.title(Statecraft.mapTitleId("deep-map", "neutral_island"));
        check(neutralTitle != null && neutralTitle.realmId.equals("unclaimed:deep-map"),
                "unclaimed map titles remain known to the simulator without being assigned to a realm");
        Statecraft deepReload = new Statecraft();
        deepReload.fromJson(deepPolitics.toJson());
        check(deepReload.titles().size() == 8
                        && deepReload.title(Statecraft.mapTitleId("deep-map", "deep_0")).sourceTierIndex == 0
                        && deepReload.title(Statecraft.mapTitleId("deep-map", "deep_0")).sourceParentTitleId
                        .equals(Statecraft.mapTitleId("deep-map", "deep_1")),
                "full custom title hierarchy survives Statecraft persistence and reload");

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
        check(state.setSubjectType("north_duchy_realm", Statecraft.SubjectType.TRIBUTARY)
                        && state.vassalage("north_duchy_realm").autonomy == 82
                        && state.setSubjectAutonomy("north_duchy_realm", 74)
                        && !state.setSubjectAutonomy("north_duchy_realm", 101),
                "tributary status grants broad autonomy and custom autonomy is range-checked");
        check(state.setSubjectType("north_duchy_realm", Statecraft.SubjectType.PERSONAL_UNION)
                        && state.vassalage("north_duchy_realm").autonomy == 48,
                "a personal union is distinct from ordinary feudal vassalage");

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
        Statecraft.Dynasty dynasty = state.registerDynasty("house_stark", "c1", "Династия Старков");
        check(state.addDynastyMember("house_stark", "c2") && state.addDynastyPrestige("house_stark", 40)
                && dynasty.members.containsAll(List.of("c1", "c2")) && dynasty.prestige == 40
                && dynasty.name.equals("Династия Старков"),
                "dynasty keeps a human-readable name, canonical house key, Character members and prestige");
        Statecraft.Dynasty cyrillicDynasty = state.registerDynasty("Сервин", "character_servin");
        check(cyrillicDynasty.houseId.equals("Сервин")
                        && state.addDynastyMember("Сервин", "character_heir"),
                "Unicode dynasty names remain valid identifiers and preserve canonical Character references");

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
        check(state.activeWarBetween("dorne", "north") == war,
                "active war lookup is symmetric for trade and diplomacy consumers");
        check(state.changeWarScore(war.id, 65) && war.warScore == 65, "war records its casus belli and war score");
        check(state.concludeWar(war.id, Statecraft.WarStatus.ATTACKER_VICTORY, "crown claim recognized", 1100)
                && war.status == Statecraft.WarStatus.ATTACKER_VICTORY
                && state.activeWarBetween("north", "dorne") == null,
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
        check(restored.dynasty("house_stark") != null
                        && restored.dynasty("house_stark").name.equals("Династия Старков")
                        && restored.dynasty("house_stark").members.containsAll(List.of("c1", "c2")),
                "named dynasty label and canonical family links survive save/load");
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
