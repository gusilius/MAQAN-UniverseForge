package dev.worldforge.tests;

import dev.worldforge.mod.api.SimulationLod;
import java.util.List;

public final class SimulationLodTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String dimension = "worldforge:map";
        SimulationLod.clear();
        check(SimulationLod.band(dimension, 0, 0) == SimulationLod.Band.STRATEGIC,
                "no observer must leave the world in abstract strategic simulation");

        SimulationLod.publish(List.of(new SimulationLod.Observer(dimension, 0, 0)));
        check(SimulationLod.band(dimension, 0, 0) == SimulationLod.Band.INTERACTIVE, "player position must be interactive");
        check(SimulationLod.band(dimension, 64, 0) == SimulationLod.Band.LOCAL, "nearby settlement must be local");
        check(SimulationLod.band(dimension, 300, 0) == SimulationLod.Band.REGIONAL, "middle distance must be regional");
        check(SimulationLod.band(dimension, 900, 0) == SimulationLod.Band.STRATEGIC, "far world must remain strategic");

        check(SimulationLod.shouldSpawnPeople(dimension, 96, 0), "people spawn boundary must be inclusive");
        check(!SimulationLod.shouldSpawnPeople(dimension, 96.01, 0), "people must not materialize beyond spawn boundary");
        check(SimulationLod.shouldRetainPeople(dimension, 160, 0), "people retention must use hysteresis");
        check(!SimulationLod.shouldRetainPeople(dimension, 160.01, 0), "people bodies must be removed beyond retention boundary");
        check(SimulationLod.shouldSpawnWorkers(dimension, 64, 0), "worker spawn boundary must be inclusive");
        check(!SimulationLod.shouldSpawnWorkers(dimension, 64.01, 0), "workers must not materialize beyond spawn boundary");
        check(SimulationLod.shouldRetainWorkers(dimension, 80, 0), "worker retention must be inclusive");
        check(!SimulationLod.shouldRetainWorkers(dimension, 80.01, 0), "worker bodies must be removed beyond retention boundary");
        check(!SimulationLod.shouldMaterializeSettlement(dimension, 300, 0), "regional settlement must not update block geometry");
        check(SimulationLod.shouldMaterializeSettlement(dimension, 150, 0), "local settlement must update block geometry");
        check(SimulationLod.band("minecraft:overworld", 0, 0) == SimulationLod.Band.STRATEGIC,
                "dimension boundaries must be isolated");

        SimulationLod.publish(List.of(
                new SimulationLod.Observer(dimension, 0, 0),
                new SimulationLod.Observer(dimension, 500, 0),
                new SimulationLod.Observer("minecraft:overworld", 0, 0)));
        check(SimulationLod.observerCount(dimension) == 2, "multiple players must be retained per dimension");
        check(SimulationLod.band(dimension, 500, 0) == SimulationLod.Band.INTERACTIVE,
                "nearest of multiple players must determine detail");

        long beforeClear = SimulationLod.revision();
        SimulationLod.clear();
        check(SimulationLod.revision() > beforeClear, "snapshot revision must advance on reset");
        check(SimulationLod.observerCount(dimension) == 0, "clear must remove stale players");
        System.out.println("SimulationLodTest: OK");
    }
}
