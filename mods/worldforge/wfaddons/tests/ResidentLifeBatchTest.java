package dev.worldforge.tests;

import dev.worldforge.settle.mc.ResidentLifeBatch;
import java.util.HashSet;
import java.util.Set;

public final class ResidentLifeBatchTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int size = 1003, budget = 96, cursor = 0, visited = 0;
        Set<Integer> firstCycle = new HashSet<>();
        while (visited < size) {
            ResidentLifeBatch.Window window = ResidentLifeBatch.next(size, cursor, budget);
            check(window.count() > 0 && window.count() <= budget, "batch must respect budget");
            for (int i = 0; i < window.count(); i++)
                check(firstCycle.add((window.start() + i) % size), "a resident must not repeat before a full cycle");
            visited += window.count();
            cursor = window.nextCursor();
        }
        check(firstCycle.size() == size, "all residents must eventually receive a turn");

        ResidentLifeBatch.Window shrunk = ResidentLifeBatch.next(7, 991, 3);
        check(shrunk.start() == Math.floorMod(991, 7), "population shrink must normalize cursor");
        check(shrunk.count() == 3, "shrunk population still respects batch budget");
        ResidentLifeBatch.Window grown = ResidentLifeBatch.next(20, shrunk.nextCursor(), 4);
        check(grown.count() == 4, "population growth continues bounded processing");
        check(ResidentLifeBatch.next(0, 100, 8).count() == 0, "empty population is a no-op");
        check(ResidentLifeBatch.next(10, 2, 0).count() == 0, "zero budget is a no-op");

        System.out.println("ResidentLifeBatchTest: OK");
    }
}
