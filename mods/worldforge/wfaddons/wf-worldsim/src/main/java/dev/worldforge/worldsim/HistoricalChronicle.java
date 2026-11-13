package dev.worldforge.worldsim;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.EventLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only, book-oriented presentation of events emitted by authoritative game domains.
 * It has no decision engine, RNG, simulation clock, or domain-state mutation. It only
 * turns confirmed EventLog facts into readable prose; disabling it cannot affect gameplay.
 */
public final class HistoricalChronicle {
    public static final int DAYS_PER_YEAR = 365;
    public static final int YEARS_PER_CYCLE = 5;
    public static final int YEARS_PER_EPOCH = 25;
    public static final int MAX_ENTRIES = 100_000;

    public static final class Entry {
        public final String id;
        public final long day;
        public final int year;
        public final int epoch;
        public final int cycle;
        public final String category;
        public final String text;
        public final List<String> realms;

        private Entry(String id, long day, int year, int epoch, int cycle,
                      String category, String text, List<String> realms) {
            this.id = id;
            this.day = day;
            this.year = year;
            this.epoch = epoch;
            this.cycle = cycle;
            this.category = category;
            this.text = text;
            this.realms = List.copyOf(realms);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> knownIds = new LinkedHashSet<>();
    private int startingYear = 300;

    public synchronized void setStartingYear(int year) {
        startingYear = year;
    }

    /** Adds only a real event published by a domain owner. Repeated delivery is harmless. */
    public synchronized boolean record(EventLog.Event event, long worldDay, int calendarYear) {
        if (event == null || event.text == null || event.text.isBlank()) return false;
        // Rejected AI proposals are diagnostics, not events in the world's history.
        if (event.text.startsWith("Отклонено исполнительной системой")) return false;
        String id = "worldforge-event:" + event.seq;
        if (!knownIds.add(id)) return false;
        int safeYear = Math.max(startingYear, calendarYear);
        int epoch = Math.max(0, (safeYear - startingYear) / YEARS_PER_EPOCH);
        int cycle = Math.max(0, (safeYear - startingYear) / YEARS_PER_CYCLE);
        entries.add(new Entry(id, Math.max(0L, worldDay), safeYear, epoch, cycle,
                event.category == null ? "system" : event.category.id(),
                event.text.trim(), event.realms == null ? List.of() : event.realms));
        // Domain listeners can publish a delayed fact after a later event. The book
        // follows occurrence time, with EventLog sequence as a stable tie-breaker.
        entries.sort(java.util.Comparator.comparingLong((Entry entry) -> entry.day)
                .thenComparingLong(HistoricalChronicle::eventSequence));
        while (entries.size() > MAX_ENTRIES) {
            Entry removed = entries.remove(0);
            knownIds.remove(removed.id);
        }
        return true;
    }

    public synchronized int size() { return entries.size(); }

    public synchronized void clear() {
        entries.clear();
        knownIds.clear();
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Produces chronological book pages. The epoch/cycle headings are editorial
     * calendar divisions, not claims that a political era actually began.
     */
    public synchronized List<String> book(int requestedEntries) {
        int from = requestedEntries <= 0 ? 0 : Math.max(0, entries.size() - requestedEntries);
        List<Entry> selected = entries.subList(from, entries.size());
        List<String> pages = new ArrayList<>();
        pages.add("ЛЕТОПИСЬ ЖИВОГО МИРА");
        pages.add("История, записанная не заранее, а прожитая самим миром.");
        int previousEpoch = -1;
        int previousCycle = -1;
        for (Entry entry : selected) {
            if (entry.epoch != previousEpoch) {
                int firstYear = startingYear + entry.epoch * YEARS_PER_EPOCH;
                pages.add("КНИГА " + roman(entry.epoch + 1) + " · ЭПОХА " + roman(entry.epoch + 1)
                        + " · " + firstYear + "–" + (firstYear + YEARS_PER_EPOCH - 1));
                previousEpoch = entry.epoch;
                previousCycle = -1;
            }
            if (entry.cycle != previousCycle) {
                int firstYear = startingYear + entry.cycle * YEARS_PER_CYCLE;
                pages.add("Глава " + (entry.cycle + 1) + ". Пятилетие " + firstYear
                        + "–" + (firstYear + YEARS_PER_CYCLE - 1));
                previousCycle = entry.cycle;
            }
            pages.add(narrate(entry));
        }
        if (selected.isEmpty()) pages.add("Пока страницы чисты. История начнётся тогда, когда в мире произойдёт первое подтверждённое событие.");
        return pages;
    }

    private static String narrate(Entry entry) {
        String fact = cleanFact(entry.text);
        String opening = switch (entry.category) {
            case "war" -> "В летописи этого времени появилась суровая запись: ";
            case "diplomacy" -> "Между державами и их правителями произошло следующее: ";
            case "people" -> "Судьбы людей складывались не менее стремительно, чем судьбы государств: ";
            case "settlement" -> "В поселениях тем временем менялась повседневная жизнь: ";
            default -> "Летописцы сохранили для потомков такую запись: ";
        };
        return "Год " + entry.year + ", день " + entry.day + ". " + opening + fact + punctuation(fact);
    }

    private static long eventSequence(Entry entry) {
        String prefix = "worldforge-event:";
        if (entry == null || !entry.id.startsWith(prefix)) return Long.MAX_VALUE;
        try { return Long.parseLong(entry.id.substring(prefix.length())); }
        catch (NumberFormatException ignored) { return Long.MAX_VALUE; }
    }

    private static String cleanFact(String value) {
        String fact = value == null ? "" : value.trim();
        if (fact.startsWith("Исполнено: ")) fact = fact.substring("Исполнено: ".length());
        if (fact.endsWith(".")) fact = fact.substring(0, fact.length() - 1);
        return fact;
    }

    private static String punctuation(String value) {
        if (value.endsWith("!") || value.endsWith("?") || value.endsWith("…")) return "";
        return ".";
    }

    public synchronized String toJson() {
        List<Object> serialized = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id);
            item.put("day", entry.day);
            item.put("year", entry.year);
            item.put("epoch", entry.epoch);
            item.put("cycle", entry.cycle);
            item.put("category", entry.category);
            item.put("text", entry.text);
            item.put("realms", new ArrayList<>(entry.realms));
            serialized.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "maqan-historical-chronicle/1");
        root.put("starting_year", startingYear);
        root.put("entries", serialized);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public synchronized void fromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Chronicle root must be an object");
        Object start = root.get("starting_year");
        startingYear = start instanceof Number n ? n.intValue() : 300;
        Object rawEntries = root.get("entries");
        if (!(rawEntries instanceof List<?> list)) throw new IllegalArgumentException("Chronicle entries are missing");
        List<Entry> restored = new ArrayList<>();
        Set<String> restoredIds = new LinkedHashSet<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> item)) continue;
            String id = string(item.get("id"));
            String category = string(item.get("category"));
            String text = string(item.get("text"));
            if (id.isBlank() || text.isBlank() || !restoredIds.add(id)) continue;
            long day = number(item.get("day"), 0).longValue();
            int year = number(item.get("year"), startingYear).intValue();
            int epoch = number(item.get("epoch"), Math.max(0, (year - startingYear) / YEARS_PER_EPOCH)).intValue();
            int cycle = number(item.get("cycle"), Math.max(0, (year - startingYear) / YEARS_PER_CYCLE)).intValue();
            List<String> realms = new ArrayList<>();
            Object rawRealms = item.get("realms");
            if (rawRealms instanceof List<?> values) for (Object value : values) realms.add(String.valueOf(value));
            restored.add(new Entry(id, day, year, Math.max(0, epoch), Math.max(0, cycle),
                    category.isBlank() ? "system" : category, text, realms));
        }
        if (restored.size() > MAX_ENTRIES) restored = new ArrayList<>(restored.subList(restored.size() - MAX_ENTRIES, restored.size()));
        entries.clear();
        entries.addAll(restored);
        knownIds.clear();
        for (Entry entry : entries) knownIds.add(entry.id);
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Number number(Object value, Number fallback) { return value instanceof Number n ? n : fallback; }

    private static String roman(int value) {
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        int n = Math.max(1, Math.min(3999, value));
        return thousands[n / 1000] + hundreds[(n % 1000) / 100] + tens[(n % 100) / 10] + ones[n % 10];
    }
}
