package dev.worldforge.tests;

import dev.worldforge.settle.*;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SettlementMapTest {
    private static int failures;
    private static void ok(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }
    private static Defs defs() throws Exception {
        Defs d = new Defs();
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/base.json")));
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/economy.json")));
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/extra.json")));
        return d;
    }
    private static SettlementMap flatMap() {
        SettlementMap map = new SettlementMap();
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            boolean water = x == 2 && z == 0;
            map.sample(x, z, 64, "minecraft:plains", 0.85, water);
        }
        return map;
    }
    public static void main(String[] args) throws Exception {
        Defs defs = defs();
        SettlementMap map = flatMap();
        ok(map.cells().size() == 441, "terrain cells are stored as a real sampled grid");
        ok(map.cellAt(0, 0) != null && map.cellAt(0, 0).fertility > 0.8, "terrain lookup exposes fertility and biome data");
        ok(map.cellAt(8, 0).water, "water is represented as a non-buildable terrain cell");

        SettlementMap.Parcel residential = map.zone("homes", -8, -8, 16, 16, SettlementMap.Zone.RESIDENTIAL);
        ok(residential.contains(-4, -4, 8, 8), "land parcel bounds are validated and queryable");
        boolean overlapRejected = false;
        try { map.zone("overlap", 0, 0, 16, 16, SettlementMap.Zone.INDUSTRY); }
        catch (IllegalArgumentException expected) { overlapRejected = true; }
        ok(overlapRejected, "overlapping land parcels are rejected");

        Settlement settlement = new Settlement("map_test", "Map test", defs);
        settlement.add("wood", 100);
        Building house = settlement.place("house", 0, 0);
        map.validatePlacement(house.def.category, house.x, house.z, house.def.sizeX, house.def.sizeZ);
        SettlementMap.ConstructionPlan plan = map.planBuilding(house);
        ok(plan.buildingUid == house.uid && plan.state == SettlementMap.PlanState.PLANNED,
                "building placement creates a linked construction plan");
        boolean wrongZoneRejected = false;
        try { map.validatePlacement("industry", 0, 0, 5, 5); }
        catch (IllegalArgumentException expected) { wrongZoneRejected = true; }
        ok(wrongZoneRejected, "zoning blocks an incompatible industrial building");
        boolean waterRejected = false;
        try { map.validatePlacement("food", 8, 0, 4, 4); }
        catch (IllegalArgumentException expected) { waterRejected = true; }
        ok(waterRejected, "construction on water is rejected");

        SettlementMap.Road road = map.addRoad(0, 0, 32, 0);
        ok(road.path.size() > 5 && road.path.stream().noneMatch(n -> n.gx() == 2 && n.gz() == 0),
                "A* road planning routes around water using passable terrain");
        ok(map.isRoad(0, 0) && map.isRoad(8, 0), "road path is represented as a connected network");

        SettlementMap.Warehouse east = map.addWarehouse("east", 32, 0, 100);
        SettlementMap.LogisticsJob job = map.addLogistics("central", "east", "wood", 60);
        map.tick(settlement, 60);
        ok(east.amount("wood") > 0 && east.amount("wood") < 60 && job.moved == east.amount("wood")
                        && map.totalGoodsMoved == job.moved,
                "road-connected logistics moves inventory with throughput reduced by route distance");
        ok(settlement.amount("wood") == 100, "warehouse transfers preserve the canonical settlement inventory total");

        Settlement producerSettlement = new Settlement("producer_map", "Producer map", defs);
        Building farm = producerSettlement.place("farm", 0, 0);
        farm.state = Building.State.DONE;
        producerSettlement.add("food", 20);
        SettlementMap productionMap = flatMap();
        SettlementMap.Warehouse farmStore = productionMap.addWarehouse("farmstore", 4, 0, 100);
        productionMap.tick(producerSettlement, 1);
        ok(farmStore.amount("food") == 20 && productionMap.warehouses.get("central").amount("food") == 0,
                "production output is allocated to the nearest spatial warehouse");

        SettlementMap steep = new SettlementMap();
        steep.sample(0, 0, 64, "minecraft:plains", 0.8, false);
        steep.sample(1, 0, 70, "minecraft:plains", 0.8, false);
        boolean slopeRejected = false;
        try { steep.validatePlacement("housing", 0, 0, 1, 1); }
        catch (IllegalArgumentException expected) { slopeRejected = true; }
        ok(slopeRejected, "steep terrain rejects a building footprint");

        SettlementMap disconnected = new SettlementMap();
        for (int x = -2; x <= 14; x++) for (int z = -2; z <= 2; z++)
            disconnected.sample(x, z, 64, "minecraft:plains", 0.8, false);
        disconnected.addRoad(0, 0, 8, 0);
        disconnected.addRoad(40, 0, 48, 0);
        disconnected.addWarehouse("west", 0, 0, 100);
        disconnected.addWarehouse("east", 48, 0, 100);
        boolean disconnectedRejected = false;
        try { disconnected.addLogistics("west", "east", "wood", 10); }
        catch (IllegalArgumentException expected) { disconnectedRejected = true; }
        ok(disconnectedRejected, "logistics cannot connect warehouses on separate road components");

        SettlementMap restored = SettlementMap.fromJson(map.toJson());
        ok(restored.cells().size() == map.cells().size() && restored.roads.size() == 1
                && restored.parcels.size() == 1 && restored.plans.containsKey(house.uid),
                "terrain, zoning, roads and construction plans survive save/load");
        ok(restored.warehouses.get("east").amount("wood") == east.amount("wood")
                && restored.logistics.get(job.id).moved == job.moved,
                "warehouse stocks and logistics progress survive save/load");
        restored.tick(settlement, 60);
        ok(restored.simulationSteps == map.simulationSteps + 1,
                "global settlement map simulation advances deterministically without chunk access");
        restored.removeBuilding(house.uid);
        ok(!restored.plans.containsKey(house.uid), "cancelled buildings remove their map construction plan");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
