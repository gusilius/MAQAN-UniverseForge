package dev.worldforge.mod.api;

import dev.worldforge.mod.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Журнал событий мира (как «лог сообщений» Paradox): ограниченный кольцевой буфер на {@link #CAPACITY} записей, категории,
 * фильтры по категории и государству, JSON-выгрузка и загрузка. Без Minecraft; цвета категорий задаёт клиент (EventFeed).
 */
public final class EventLog {
    public static final int CAPACITY = 500;

    public enum Category {
        WAR("Войны"), DIPLOMACY("Дипломатия"), PEOPLE("Люди"), SETTLEMENT("Поселения"), SYSTEM("Система");

        public final String label;

        Category(String label) { this.label = label; }

        /** Разбор по имени без учёта регистра; неизвестное - SYSTEM. */
        public static Category parse(String s) {
            if (s != null) for (Category c : values()) if (c.name().equalsIgnoreCase(s.trim())) return c;
            return SYSTEM;
        }

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final class Event {
        public final long seq, time;
        public final Category category;
        public final String text;
        public final List<String> realms;

        Event(long seq, Category category, String text, List<String> realms, long time) {
            this.seq = seq; this.category = category; this.text = text; this.realms = realms; this.time = time;
        }
    }

    private final Deque<Event> buf = new ArrayDeque<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private long seq;

    public Event post(Category c, String text, Collection<String> realmIds, long time) {
        Event e;
        try (DomainMutationRevisions.Mutation mutation =
                     WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.EVENTS_ACTIONS)) {
            synchronized (this) {
                List<String> ids = realmIds == null ? List.of() : List.copyOf(new ArrayList<>(realmIds));
                e = new Event(++seq, c == null ? Category.SYSTEM : c, text == null ? "" : text, ids, time);
                buf.addLast(e);
                while (buf.size() > CAPACITY) buf.removeFirst();
            }
            mutation.commit();
        }
        for (Consumer<Event> l : listeners) {
            try { l.accept(e); } catch (RuntimeException ex) { System.err.println("WorldForge: ошибка слушателя журнала: " + ex); }
        }
        return e;
    }

    public Event post(String category, String text, Collection<String> realmIds, long time) {
        return post(Category.parse(category), text, realmIds, time);
    }

    public void addListener(Consumer<Event> l) { listeners.add(l); }

    public synchronized int size() { return buf.size(); }

    public void clear() {
        try (DomainMutationRevisions.Mutation mutation =
                     WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.EVENTS_ACTIONS)) {
            synchronized (this) { buf.clear(); }
            mutation.commit();
        }
    }

    /** Все записи, старые первыми. */
    public synchronized List<Event> all() { return new ArrayList<>(buf); }

    /** Записи, новые первыми. categories == null или пусто - любые; realmId == null - любое государство; limit <= 0 - без предела. */
    public synchronized List<Event> filter(java.util.Set<Category> categories, String realmId, int limit) {
        List<Event> out = new ArrayList<>();
        java.util.Iterator<Event> it = buf.descendingIterator();
        while (it.hasNext() && (limit <= 0 || out.size() < limit)) {
            Event e = it.next();
            if (categories != null && !categories.isEmpty() && !categories.contains(e.category)) continue;
            if (realmId != null && !e.realms.contains(realmId)) continue;
            out.add(e);
        }
        return out;
    }

    public synchronized List<Event> recent(int n) { return filter(null, null, n); }

    // ------------------------------------------------------------------ JSON

    public synchronized String toJson() {
        List<Object> arr = new ArrayList<>();
        for (Event e : buf) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq);
            m.put("time", e.time);
            m.put("category", e.category.id());
            m.put("text", e.text);
            m.put("realms", new ArrayList<Object>(e.realms));
            arr.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "worldforge-events/1");
        root.put("events", arr);
        return Json.write(root);
    }

    /** Заменяет содержимое журнала записями из JSON (лишнее сверх ёмкости отбрасывается с начала). Слушатели не зовутся. */
    @SuppressWarnings("unchecked")
    public synchronized void fromJson(String json) {
        Object root = Json.parse(json);
        List<Object> arr = root instanceof Map ? (List<Object>) ((Map<String, Object>) root).get("events") : (List<Object>) root;
        try (DomainMutationRevisions.Mutation mutation =
                     WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.EVENTS_ACTIONS)) {
            synchronized (this) {
                buf.clear();
                seq = 0;
                if (arr != null) for (Object o : arr) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) o;
                    List<String> ids = new ArrayList<>();
                    Object rs = m.get("realms");
                    if (rs instanceof List) for (Object x : (List<Object>) rs) ids.add(String.valueOf(x));
                    Object t = m.get("time"), s = m.get("seq");
                    long sq = s instanceof Number ? ((Number) s).longValue() : seq + 1;
                    seq = Math.max(seq + 1, sq);
                    buf.addLast(new Event(seq, Category.parse(m.get("category") == null ? null : m.get("category").toString()),
                            m.get("text") == null ? "" : m.get("text").toString(), List.copyOf(ids), t instanceof Number ? ((Number) t).longValue() : 0));
                    while (buf.size() > CAPACITY) buf.removeFirst();
                }
            }
            mutation.commit();
        }
    }
}
