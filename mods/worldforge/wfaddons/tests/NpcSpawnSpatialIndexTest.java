package dev.worldforge.tests;

import dev.worldforge.people.mc.NpcSpawnSpatialIndex;
import java.util.List;

public final class NpcSpawnSpatialIndexTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        NpcSpawnSpatialIndex<String> index = new NpcSpawnSpatialIndex<>();
        index.add("origin", 0, 0);
        index.add("near-negative", -127, -127);
        index.add("near-positive", 127, 127);
        index.add("next-cell", 129, 0);
        index.add("far", 1000, 1000);

        List<String> local = index.query(0, 0, 160);
        check(local.contains("origin"), "origin candidate must be found");
        check(local.contains("near-negative"), "negative coordinates must floor correctly");
        check(local.contains("near-positive"), "positive neighboring cell must be found");
        check(local.contains("next-cell"), "cell boundary candidate must be found");
        check(!local.contains("far"), "distant candidates must not be returned");

        check(index.query(0, 0, -1).isEmpty(), "negative radius must be rejected safely");
        check(index.query(Double.NaN, 0, 100).isEmpty(), "non-finite coordinates must be rejected");
        System.out.println("NpcSpawnSpatialIndexTest: OK");
    }
}
