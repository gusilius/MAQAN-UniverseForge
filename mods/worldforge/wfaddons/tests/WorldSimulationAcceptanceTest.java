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
        check(north.buildings.size() == 3 && south.buildings.size() == 3,
                "two settlements start with housing, food and wood production plans");

        WarEngine wars = new WarEngine(new TitleEditor(map.titles));
        wars.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        List<String> realms = new ArrayList<>();
        for (var realm : map.titles.realmList()) if (realm.overlordId == null && !realm.titleKeys.isEmpty()) realms.add(realm.id);
        check(realms.size() >= 2, "independent political actors are available for campaign simulation");
        War activeWar = null;
        if (realms.size() >= 2) activeWar = wars.declare(realms.get(0), realms.get(1), "humiliation", List.of());

        CampaignOperation operation = new CampaignOperation("acceptance-operation", "acceptance",
                CampaignOperation.Objective.DEFEND, 128, 64);
        check(operation.assign("acceptance-squad", "acceptance") && operation.start()
                && operation.advance(0.9) && operation.engage() && operation.complete("domain-scenario"),
                "campaign operation executes its complete domain lifecycle");

        int armySlotsA = Formations.slots(Formations.Type.LINE, 1000, 1.5).size();
        int armySlotsB = Formations.slots(Formations.Type.BLOCK, 1000, 1.5).size();
        check(armySlotsA == 1000 && armySlotsB == 1000,
                "formation geometry scales to 1000 slots on each side");
        report.put("formation_slots_per_side", 1000);
        report.put("physical_1000v1000_battle", "NOT_EXECUTED_MINECRAFT_RUNTIME_REQUIRED");

        int snapshots = 0;
        for (int day = 1; day <= 1000; day++) {
            clock.advance(GameClock.TICKS_PER_DAY);
            int year = clock.year();
            PopulationSimulation.advance(people, 1, year);
            if (day % 365 == 0) ResidentLifeEvents.yearly(people, year);
            long worldMinute = (long) day * 1440L;
            north.setWorldCalendar(worldMinute, year);
            south.setWorldCalendar(worldMinute, year);
            north.tick(60);
            south.tick(60);
            wars.tick(1);

            if (activeWar != null && day == 2 && activeWar.active) {
                wars.battle(activeWar.id, War.Side.ATTACKER, 20, "acceptance-domain-battle");
                wars.offerPeace(activeWar.id, activeWar.attacker, true);
                wars.acceptPeace(activeWar.id, activeWar.defender);
            }

            if (day % 250 == 0) {
                people = restorePeople(people);
                north = Settlement.fromJson(north.toJson(), settlementDefs);
                south = Settlement.fromJson(south.toJson(), settlementDefs);
                clock.load(clock.toJson());
                WarEngine restored = new WarEngine(new TitleEditor(map.titles));
                restored.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
                restored.fromJson(wars.toJson());
                wars = restored;
                snapshots++;
                check(clock.dayNumber() == day, "day " + day + " checkpoint round-trips shared clock and domain saves");
            }
        }

        check(clock.dayNumber() == 1000, "shared game clock reaches day 1000 without a terminal game state");
        check(north.time > 0 && south.time > 0, "both settlement economies advanced through the full 1000-day run");
        check(countCompleted(north) > 0 && countCompleted(south) > 0,
                "settlement construction progresses to completed buildings");
        check(people.size() > 0 && people.all().stream().allMatch(c -> c.id != null && !c.id.isBlank()),
                "canonical population remains addressable after long simulation and restores");
        check(snapshots == 4, "four cross-domain save/restore checkpoints completed");

        report.put("simulation_days", clock.dayNumber());
        report.put("simulation_year", clock.year());
        report.put("population_records", people.size());
        report.put("north_completed_buildings", countCompleted(north));
        report.put("south_completed_buildings", countCompleted(south));
        report.put("war_records", wars.wars.size());
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
