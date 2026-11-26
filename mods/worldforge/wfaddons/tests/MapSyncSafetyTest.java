package dev.worldforge.tests;

import dev.worldforge.mod.MapSync;

import java.io.IOException;
import java.util.Arrays;

/** Регрессии на повреждённые и чрезмерные пакеты синхронизации карты. */
public final class MapSyncSafetyTest {
    private static int failures;

    private static void check(boolean condition, String name) {
        System.out.println((condition ? "ok   " : "FAIL ") + name);
        if (!condition) failures++;
    }

    private static void rejects(Runnable action, String name) {
        try {
            action.run();
            check(false, name);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            check(true, name);
        }
    }

    public static void main(String[] args) {
        boolean invalidStateRejected = false;
        try {
            MapSync.decodeState(new byte[]{1, 2, 3});
        } catch (IOException expected) {
            invalidStateRejected = true;
        }
        check(invalidStateRejected, "короткое/неверное полное состояние отклоняется");

        rejects(() -> MapSync.split(new byte[1], 0), "нулевой размер чанка отклоняется");
        rejects(() -> MapSync.split(new byte[1], 1024 * 1024 + 1), "чрезмерный размер чанка отклоняется");

        MapSync.Reassembler reassembler = new MapSync.Reassembler();
        rejects(() -> reassembler.add(1, 0, 0, new byte[]{1}), "передача без чанков отклоняется");
        rejects(() -> reassembler.add(1, 1, 1, new byte[]{1}), "индекс вне диапазона отклоняется");

        byte[] first = {1, 2}, second = {3, 4};
        check(reassembler.add(7, 0, 2, first) == null, "неполная передача ожидает второй чанк");
        rejects(() -> reassembler.add(7, 1, 3, second), "изменение количества чанков отклоняется");
        check(reassembler.activeTransfers() == 0, "несогласованная передача удаляется");

        check(reassembler.add(8, 0, 2, first) == null, "первый чанк принимается");
        check(reassembler.add(8, 0, 2, first) == null, "идентичный повтор чанка идемпотентен");
        byte[] assembled = reassembler.add(8, 1, 2, second);
        check(Arrays.equals(assembled, new byte[]{1, 2, 3, 4}), "чанки собираются в исходном порядке");

        check(failures == 0, "MapSync safety regressions: " + failures + " failures");
        if (failures != 0) throw new AssertionError("MapSync safety tests failed: " + failures);
    }
}
