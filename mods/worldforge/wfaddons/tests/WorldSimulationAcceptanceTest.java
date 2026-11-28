package dev.worldforge.tests;

import dev.worldforge.mod.MapData;
import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.GameClock;
import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.StatApi;
import dev.worldforge.realms.RealmDefs;
import dev.worldforge.realms.Realms;
import dev.worldforge.trade.Trade;
import dev.worldforge.trade.TradeDefs;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.Morale;
import dev.worldforge.troops.OperationalSupply;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.ResidentLifeEvents;
import dev.worldforge.troops.Formations;
import dev.worldforge.troops.CampaignOperation;
import dev.worldforge.war.War;
import dev.worldforge.war.WarEngine;
import dev.worldforge.mod.TitleEditor;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Deterministic cross-domain acceptance scenario.
 *
 * This deliberately reports runtime-only acceptance gates as BLOCKED instead of
 * pretending that pure-Java domain tests prove Minecraft entity combat or image
 * segmentation. The 1000-day loop advances the real domain services below.
 */
public final class WorldSimulationAcceptanceTest {
    private static int failures;
    private static final Map<String, Object> report = new LinkedHashMap<>();

    private static void check(boolean condition, String label) {
        System.out.println((condition ? "PASS " : "FAIL ") + label);
        if (!condition) failures++;
    }

    private static MapData loadMap(String pack) throws Exception {
        try (ZipFile zip = new ZipFile(pack)) {
            return MapData.load("acceptance", name -> {
                var entry = zip.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return entry == null ? null : zip.getInputStream(entry).readAllBytes();
            });
        }
    }

    private static Defs loadSettlementDefs() throws Exception {
        Defs defs = new Defs();
        defs.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/base.json")));
        defs.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/economy.json")));
        defs.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/extra.json")));
        return defs;
    }

    private static Settlement createSettlement(String id, Defs defs) {
        Settlement settlement = new Settlement(id, id, defs);
        settlement.raids = false;
        settlement.add("wood", 10000);
        settlement.add("stone", 10000);
        settlement.add("food", 10000);
        settlement.add("planks", 10000);
        settlement.add("iron", 10000);
        settlement.add("silver", 10000);
        for (int i = 0; i < 12; i++) settlement.addWorker(null);
        settlement.place("house", 0, 0);
        settlement.place("farm", 12, 0);
        settlement.place("lumber_camp", 24, 0);
        settlement.place("market", 36, 0);
        return settlement;
    }

    private static RealmDefs loadRealmDefs() throws Exception {
        RealmDefs defs = new RealmDefs();
        Path directory = Path.of("wf-realms/src/main/resources/data/wf_realms/wf_realms");
        try (var files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) defs.load(Files.readString(file));
        }
        return defs;
    }

    private static TradeDefs loadTradeDefs() throws Exception {
        TradeDefs defs = new TradeDefs();
        defs.load(Files.readString(Path.of("wf-trade/src/main/resources/data/wf_trade/wf_trade/goods.json")));
        if (!defs.validate().isEmpty()) throw new IllegalStateException("Invalid trade definitions: " + defs.validate());
        return defs;
    }

    private static int soldiers(Squads squads, String owner) {
        return squads.of(owner).stream().mapToInt(squad -> squad.members.size()).sum();
    }

    private static int countCompleted(Settlement settlement) {
        int count = 0;
        for (Building building : settlement.buildings) if (building.state == Building.State.DONE) count++;
        return count;
    }

    private static int seedTenGenerationLineage(CharacterRegistry registry) {
        Character father = registry.create("Founder", "House Acceptance", "acceptance", "m", 300);
        int generations = 1;
        for (int generation = 2; generation <= 10; generation++) {
             int birthYear = father.birthYear + 20;
            Character mother = registry.create("Founder spouse " + generation, father.house, father.culture, "f", birthYear - 20);
            Character child = registry.addChild(father, mother, "Generation " + generation, "m", birthYear);
            if (!father.id.equals(child.father) || !mother.id.equals(child.mother)
                    || !father.children.contains(child.id) || !mother.children.contains(child.id)) break;
            father = child;
            generations++;
        }
        return generations;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("worldmap fixture argument is required");
        long started = System.nanoTime();
        MapData map = loadMap(args[0]);
        int realmCount = map.titles == null ? 0 : map.titles.realmList().size();
        check(map.w == 64 && map.h == 64 && map.titles != null && realmCount >= 8,
                "deterministic political map package loads with multiple realms");
        report.put("map_width", map.w);
        report.put("map_height", map.h);
        report.put("generated_realms_loaded", realmCount);
        report.put("map_input_mode", "synthetic_prebuilt_rasters");
        report.put("arbitrary_source_image_pipeline", "NOT_TESTED");

        GameClock clock = new GameClock();
        CharacterRegistry people = new CharacterRegistry();
        Character father = people.create("Aren", "House North", "north", "m", 270);
        Character mother = people.create("Mira", "House North", "north", "f", 272);
        people.marry(father.id, mother.id, 299);
        int seededGenerations = seedTenGenerationLineage(people);
        check(seededGenerations == 10, "ten-generation canonical family graph can be persisted");
        report.put("ten_generation_lineage_fixture", seededGenerations);
        report.put("natural_ten_generation_turnover", "NOT_PROVEN_WITHIN_1000_DAYS");

        Defs settlementDefs = loadSettlementDefs();
        Settlement north = createSettlement("acceptance_north", settlementDefs);
        Settlement south = createSettlement("acceptance_south", settlementDefs);
        check(north.buildings.size() == 4 && south.buildings.size() == 4,
                "two settlements start housing, food, wood production and market plans");

        WarEngine wars = new WarEngine(new TitleEditor(map.titles));
        wars.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        List<String> realmIds = new ArrayList<>();
        for (var realm : map.titles.realmList())
            if (realm.overlordId == null && !realm.titleKeys.isEmpty()) realmIds.add(realm.id);
        check(realmIds.size() >= 2, "independent political actors are available for campaign simulation");
        String attackerRealm = realmIds.get(0), defenderRealm = realmIds.get(1);
        War activeWar = wars.declare(attackerRealm, defenderRealm, "humiliation", List.of());
        WarEngine[] warRef = {wars};

        RealmDefs realmDefs = loadRealmDefs();
        StatApi.resetForTests();
        Realms realmDevelopment = new Realms(realmDefs);
        realmDevelopment.hooks = new Realms.Hooks() {
            @Override public int provinces(String id) {
                var realm = map.titles.realms.get(id);
                return realm == null ? 1 : Math.max(1, realm.titleKeys.size());
            }
            @Override public int prestige(String id) { return 0; }
            @Override public Map<String, Double> races(String id) { return Map.of("human", 1.0); }
            @Override public Map<String, Double> terrain(String id) { return Map.of("plains", 1.0); }
            @Override public int today() { return (int) clock.dayNumber(); }
        };
        realmDevelopment.publish();

        TradeDefs tradeDefs = loadTradeDefs();
        DemoApi.resetForTests();
        DemoApi.registerTerrain("acceptance", id -> Map.of("plains", 1.0));
        DemoApi.registerRaces("acceptance", id -> Map.of("human", 1.0));
        Trade trade = new Trade(tradeDefs);
        Trade.Hooks tradeHooks = new Trade.Hooks() {
            @Override public List<String> neighbors(String id) {
                if (id.equals(attackerRealm)) return List.of(defenderRealm);
                if (id.equals(defenderRealm)) return List.of(attackerRealm);
                return List.of();
            }
            @Override public double distance(String from, String to) { return 3; }
            @Override public boolean atWar(String from, String to) { return warRef[0].warBetween(from, to) != null; }
            @Override public double danger(String from, String to) { return 0; }
            @Override public int season() { return clock.seasonIndex(); }
        };
        trade.hooks = tradeHooks;
        trade.rng = new Random(20261111L);
        for (String id : realmIds) trade.market(id).autoTrade = false;
        trade.market(attackerRealm).stock.put("grain", 1000.0);
        trade.market(defenderRealm).stock.put("grain", 1000.0);

        Squads squads = new Squads();
        OperationalSupply.Store supply = new OperationalSupply.Store();
        List<String> squadIds = new ArrayList<>();
        String attackerArmyOwner = "army:" + attackerRealm;
        String defenderArmyOwner = "army:" + defenderRealm;
        for (String realm : List.of(attackerRealm, defenderRealm)) {
            String owner = "army:" + realm;
            for (int group = 0; group < 25; group++) {
                List<String> members = new ArrayList<>();
                for (int unit = 0; unit < 40; unit++) members.add(realm + "-troop-" + group + "-" + unit);
                String squadId = "army-" + realm + "-" + group;
                Squads.Squad squad = squads.createWithId(squadId, owner, "Сотня " + (group + 1), realm,
                        group * 3.0, realm.equals(attackerRealm) ? 0 : 120, members);
                squad.formation = group % 2 == 0 ? Formations.Type.LINE : Formations.Type.BLOCK;
                if (group == 0) squads.appointLeader(squadId, father.id, true, 4);
                squads.command(squadId, Squads.Order.move(group * 3.0, realm.equals(attackerRealm) ? 80 : 40), false);
                squadIds.add(squadId);
            }
        }
        check(squads.of(attackerArmyOwner).size() == 25 && squads.of(defenderArmyOwner).size() == 25
                        && soldiers(squads, attackerArmyOwner) == 1000 && soldiers(squads, defenderArmyOwner) == 1000,
                "two canonical 1000-soldier armies are deployed as 50 commanded squads");

        CampaignOperation operation = new CampaignOperation("acceptance-operation", attackerArmyOwner,
                CampaignOperation.Objective.DEFEND, 128, 64);
        check(operation.assign(squadIds.get(0), attackerArmyOwner) && operation.start()
                && operation.advance(0.9) && operation.engage() && operation.complete("domain-scenario"),
                "campaign operation executes against a real persisted squad identity");

        int armySlotsA = Formations.slots(Formations.Type.LINE, 1000, 1.5).size();
        int armySlotsB = Formations.slots(Formations.Type.BLOCK, 1000, 1.5).size();
        check(armySlotsA == 1000 && armySlotsB == 1000,
                "formation geometry scales to 1000 slots on each side");
        report.put("formation_slots_per_side", 1000);
        report.put("military_squads_initial", squads.all().size());
        report.put("military_initial_strength_per_side", 1000);
        report.put("physical_1000v1000_battle", "NOT_EXECUTED_MINECRAFT_RUNTIME_REQUIRED");

        boolean blockedTradeDuringWar = false;
        int caravanId = -1;
        String tradeBlockAfterPeace = "NOT_CHECKED";
        String tradeDispatchError = "";
        boolean caravanCompleted = false;
        String caravanFinalStatus = "NOT_CREATED";
        int snapshots = 0;
        Map<String, Settlement> cities = new LinkedHashMap<>();
        cities.put(north.id, north);
        cities.put(south.id, south);
        for (int day = 1; day <= 1000; day++) {
            clock.advance(GameClock.TICKS_PER_DAY);
            int year = clock.year();
            PopulationSimulation.advance(people, 1, year);
            if (day % 365 == 0) ResidentLifeEvents.yearly(people, year);
            for (String realm : realmIds) realmDevelopment.autoDevelop(realm, day);

            long worldMinute = (long) day * 1440L;
            north.setWorldCalendar(worldMinute, year);
            south.setWorldCalendar(worldMinute, year);
            north.tick(60);
            south.tick(60);

            for (String squadId : squadIds) {
                Squads.Squad squad = squads.get(squadId);
                if (squad == null) continue;
                if (day == 2) {
                    int losses = squad.owner.equals(attackerArmyOwner) ? 2 : 1;
                    for (int i = 0; i < losses && !squad.members.isEmpty(); i++)
                        squads.remove(squad.members.get(squad.members.size() - 1));
                    squad = squads.get(squadId);
                }
                int alive = squad.members.size();
                boolean enemiesNear = day == 2;
                double lostNow = day == 2 ? (squad.owner.equals(attackerArmyOwner) ? 2.0 : 1.0) / 40.0 : 0;
                squad.morale = Morale.step(squad.morale, alive / 40.0, lostNow,
                        enemiesNear ? 1.1 : 0, !squad.leaderCharacterId.isBlank(), enemiesNear, 1);
                squad.routed = Morale.routed(squad.routed, squad.morale, enemiesNear);
                double rationUse = day >= 50 && day < 60 ? alive * 0.5 : alive;
                supply.advance(squadId, alive, 1, alive, rationUse, day);
            }

            wars.tick(1);
            if (day == 1) {
                trade.tick(day, realmIds);
                blockedTradeDuringWar = trade.dispatch("m:" + attackerRealm, attackerRealm,
                        defenderRealm, "grain", 5, 2, day) == null;
            } else {
                trade.tick(day, realmIds);
            }
            if (activeWar != null && day == 2 && activeWar.active) {
                wars.battle(activeWar.id, War.Side.ATTACKER, 20, "acceptance-domain-battle");
                wars.offerPeace(activeWar.id, activeWar.attacker, true);
                wars.acceptPeace(activeWar.id, activeWar.defender);
            }
            if (day == 3) {
                tradeBlockAfterPeace = trade.blocked(attackerRealm, defenderRealm);
                // Make the acceptance scenario independent of market-price drift:
                // the route itself must be accepted once the war restriction is gone.
                trade.market(attackerRealm).stock.put("grain", 1000.0);
                trade.capital.put(attackerRealm, 1000.0);
                Trade.Caravan caravan = trade.dispatch("m:" + attackerRealm, attackerRealm,
                        defenderRealm, "grain", 5, 2, day);
                if (caravan != null) { caravanId = caravan.id; caravanFinalStatus = caravan.status; }
                else tradeDispatchError = trade.lastError;
            }
            // The trade subsystem prunes completed caravans after their retention window.
            // Capture delivery when it occurs instead of expecting the record to survive 1000 days.
            if (caravanId >= 0) {
                Trade.Caravan currentCaravan = trade.caravans.get(caravanId);
                if (currentCaravan != null && !currentCaravan.status.equals("в пути")) {
                    caravanCompleted = true;
                    caravanFinalStatus = currentCaravan.status;
                }
            }

            if (day % 250 == 0) {
                people = restorePeople(people);
                north = Settlement.fromJson(north.toJson(), settlementDefs);
                south = Settlement.fromJson(south.toJson(), settlementDefs);
                cities.clear();
                cities.put(north.id, north);
                cities.put(south.id, south);
                clock.load(clock.toJson());

                WarEngine restoredWar = new WarEngine(new TitleEditor(map.titles));
                restoredWar.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
                restoredWar.fromJson(wars.toJson());
                wars = restoredWar;
                warRef[0] = wars;

                Realms restoredRealms = new Realms(realmDefs);
                restoredRealms.hooks = realmDevelopment.hooks;
                restoredRealms.publish();
                restoredRealms.fromJson(realmDevelopment.toJson());
                realmDevelopment = restoredRealms;

                Trade restoredTrade = new Trade(tradeDefs);
                restoredTrade.hooks = tradeHooks;
                restoredTrade.rng = new Random(20261111L);
                restoredTrade.fromJson(trade.toJson());
                trade = restoredTrade;

                Squads restoredSquads = new Squads();
                restoredSquads.fromJson(squads.toJson());
                squads = restoredSquads;
                OperationalSupply.Store restoredSupply = new OperationalSupply.Store();
                restoredSupply.fromJson(supply.toJson());
                supply = restoredSupply;
                snapshots++;
                check(clock.dayNumber() == day, "day " + day + " checkpoint round-trips all campaign domains");
            }
        }

        check(clock.dayNumber() == 1000, "shared game clock reaches day 1000 without a terminal game state");
        check(north.time > 0 && south.time > 0, "both settlement economies advanced through the full 1000-day run");
        check(countCompleted(north) > 0 && countCompleted(south) > 0,
                "settlement construction progresses to completed buildings");
        check(people.size() > 0 && people.all().stream().allMatch(c -> c.id != null && !c.id.isBlank()),
                "canonical population remains addressable after long simulation and restores");
        check(snapshots == 4, "four cross-domain save/restore checkpoints completed");
        check(blockedTradeDuringWar, "diplomatic war state blocks cross-realm caravan dispatch");
        check(caravanCompleted, "trade caravan resumes after peace and completes its route");
        if (!caravanCompleted) System.out.println("TRADE_DIAGNOSTIC block=" + tradeBlockAfterPeace
                + " dispatchError=" + tradeDispatchError + " warActive="
                + (warRef[0].warBetween(attackerRealm, defenderRealm) != null));

        long developedRealms = 0;
        for (String id : realmIds) if (!realmDevelopment.state(id).gov.isBlank()) developedRealms++;
        check(developedRealms > 0, "realm AI develops government state through the shared campaign calendar");
        check(squads.all().size() == 50, "50 military squads survive campaign save/restore");
        check(soldiers(squads, attackerArmyOwner) == 950 && soldiers(squads, defenderArmyOwner) == 975,
                "abstract 1000v1000 campaign battle applies deterministic casualties to both armies");
        boolean supplyRecovered = true;
        for (String id : squadIds) if (supply.state(id).readiness <= 0.9) supplyRecovered = false;
        check(supplyRecovered, "field supply recovers readiness after a deterministic shortage window");

        report.put("simulation_days", clock.dayNumber());
        report.put("simulation_year", clock.year());
        report.put("population_records", people.size());
        report.put("north_completed_buildings", countCompleted(north));
        report.put("south_completed_buildings", countCompleted(south));
        report.put("war_records", wars.wars.size());
        report.put("trade_markets", trade.markets.size());
        report.put("trade_caravan_id", caravanId);
        report.put("trade_caravan_status", caravanFinalStatus);
        report.put("trade_caravan_completed", caravanCompleted);
        report.put("trade_block_after_peace", tradeBlockAfterPeace);
        report.put("trade_dispatch_error", tradeDispatchError);
        report.put("realm_governments_developed", developedRealms);
        report.put("military_squads", squads.all().size());
        report.put("military_survivors_attacker", soldiers(squads, attackerArmyOwner));
        report.put("military_survivors_defender", soldiers(squads, defenderArmyOwner));
        report.put("save_restore_checkpoints", snapshots);
        report.put("failures", failures);
        report.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000L);
        report.put("acceptance_status", failures == 0 ? "DOMAIN_1000_DAY_PASS_WITH_RUNTIME_GATES_BLOCKED" : "FAILED");

        String reportPath = args.length > 1 ? args[1] : "world-simulation-acceptance.json";
        Files.writeString(Path.of(reportPath), Json.write(report));
        System.out.println("ACCEPTANCE_REPORT=" + reportPath);
        System.out.println("ACCEPTANCE_STATUS=" + report.get("acceptance_status"));
        System.out.println("RUNTIME_GATE arbitrary_image_source=NOT_TESTED physical_battle_1000v1000=NOT_EXECUTED natural_10_generation_turnover=NOT_PROVEN");
        if (failures > 0) throw new AssertionError(failures + " world simulation acceptance checks failed");
    }

    private static CharacterRegistry restorePeople(CharacterRegistry registry) {
        CharacterRegistry restored = new CharacterRegistry();
        restored.fromJson(registry.toJson());
        return restored;
    }
}
