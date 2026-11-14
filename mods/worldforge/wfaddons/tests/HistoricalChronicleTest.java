package dev.worldforge.tests;

import dev.worldforge.mod.api.EventLog;
import dev.worldforge.worldsim.HistoricalChronicle;

import java.util.List;

public final class HistoricalChronicleTest {
    private static int failures;
    private static void check(boolean condition, String label) {
        System.out.println((condition ? "PASS " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        EventLog source = new EventLog();
        HistoricalChronicle book = new HistoricalChronicle();
        book.setStartingYear(300);
        source.addListener(event -> book.record(event, event.time / 1440L, 300 + (int) (event.time / 1440L / 365L)));

        EventLog.Event war = source.post("war", "Исполнено: Объявление войны realm_north против realm_south. Причина: спор о наследовании",
                List.of("realm_north", "realm_south"), 12L * 1440L);
        source.post("settlement", "В поселении riverstead завершено строительство мельницы",
                List.of("realm_north"), 6L * 365L * 1440L);
        source.post("system", "Отклонено исполнительной системой: недостаточно ресурсов",
                List.of("realm_north"), 7L * 365L * 1440L);

        check(book.size() == 2, "only published world facts enter the chronicle; rejected proposals stay out");
        check(source.size() == 3, "the book is a read-only projection and never changes the source event journal");
        check(!book.record(war, 12, 300), "duplicate event delivery is idempotent");
        List<String> pages = book.book(10);
        String rendered = String.join("\n", pages);
        check(pages.get(0).equals("ЛЕТОПИСЬ ЖИВОГО МИРА"), "book has a distinct literary title page");
        check(rendered.contains("ЭПОХА I") && rendered.contains("Пятилетие 300–304")
                        && rendered.contains("Пятилетие 305–309"),
                "events are grouped into chronological epochs and five-year cycles");
        check(rendered.contains("спор о наследовании") && rendered.contains("строительство мельницы"),
                "narrative preserves the exact confirmed domain facts");
        check(rendered.contains("В летописи этого времени") && rendered.contains("В поселениях тем временем"),
                "category-aware prose makes the history readable as a book");

        HistoricalChronicle restored = new HistoricalChronicle();
        restored.fromJson(book.toJson());
        check(restored.size() == 2 && restored.book(10).equals(pages),
                "chronicle survives JSON save/restore without changing its pages");
        check(!restored.record(war, 12, 300), "restored event identity prevents replay duplicates");

        EventLog delayedSource = new EventLog();
        HistoricalChronicle delayedBook = new HistoricalChronicle();
        delayedBook.setStartingYear(300);
        delayedSource.addListener(event -> delayedBook.record(event, event.time / 1440L,
                300 + (int) (event.time / 1440L / HistoricalChronicle.DAYS_PER_YEAR)));
        delayedSource.post("people", "Поздно доставленное событие с ранней датой",
                List.of("realm_north"), 5L * 1440L);
        delayedSource.post("war", "Событие, произошедшее позже",
                List.of("realm_north", "realm_south"), 40L * 1440L);
        check(delayedBook.entries().get(0).day == 5 && delayedBook.entries().get(1).day == 40,
                "delayed event delivery is reordered by actual occurrence time, not listener arrival");

        EventLog orderedSource = new EventLog();
        HistoricalChronicle orderedBook = new HistoricalChronicle();
        orderedBook.setStartingYear(300);
        orderedSource.addListener(event -> orderedBook.record(event, event.time / 1440L,
                300 + (int) (event.time / 1440L / HistoricalChronicle.DAYS_PER_YEAR)));
        for (int i = 0; i < 2_000; i++) {
            orderedSource.post("people", "Chronological event " + i, List.of(),
                    (long) i * 1440L);
        }
        List<HistoricalChronicle.Entry> orderedEntries = orderedBook.entries();
        check(orderedEntries.size() == 2_000
                        && orderedEntries.get(0).day == 0
                        && orderedEntries.get(1_999).day == 1_999,
                "large in-order event streams preserve full chronological order on the append fast path");
        HistoricalChronicle restoredOutOfOrder = new HistoricalChronicle();
        restoredOutOfOrder.fromJson("{\"starting_year\":300,\"entries\":["
                + "{\"id\":\"worldforge-event:2\",\"day\":40,\"year\":300,\"epoch\":0,\"cycle\":0,\"category\":\"war\",\"text\":\"later\",\"realms\":[]},"
                + "{\"id\":\"worldforge-event:1\",\"day\":5,\"year\":300,\"epoch\":0,\"cycle\":0,\"category\":\"people\",\"text\":\"earlier\",\"realms\":[]}]}" );
        check(restoredOutOfOrder.entries().get(0).day == 5,
                "legacy or imported chronicle saves are normalized into chronological order");

        if (failures > 0) throw new AssertionError(failures + " historical chronicle checks failed");
        System.out.println("HISTORICAL_CHRONICLE_STATUS=PASS");
    }
}
