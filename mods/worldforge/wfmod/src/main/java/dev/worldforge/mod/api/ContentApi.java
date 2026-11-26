package dev.worldforge.mod.api;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Общее хранилище пользовательского контента: организации, квесты, навыки, угрозы и всё, что аддоны решат сделать настраиваемым.
 * Аддон регистрирует вид ({@link #registerKind}) с проверкой и применением JSON; администратор и игроки (по праву) создают и правят записи
 * командами /wfcontent. Записи хранятся в мире и применяются заново при старте и перезагрузке. Чистая логика, без Minecraft.
 */
public final class ContentApi {
    public interface Handler {
        /** Заготовка JSON для новой записи. */
        String template(String id, String name);
        /** null, если JSON годится, иначе причина отказа. */
        String validate(String json);
        void apply(String id, String json);
        void remove(String id);
        /** Занят ли id системным (не пользовательским) содержимым. */
        default boolean exists(String id) { return false; }
    }

    /** Проверка прав: null = можно, иначе причина. owned - сколько записей этого вида уже у игрока. */
    public interface Permission {
        String check(String player, boolean op, String kind, String action, String id, int owned);
    }

    public record Entry(String kind, String id, String owner, String json) {}

    private static final Map<String, Handler> HANDLERS = new TreeMap<>();
    private static final Map<String, Map<String, Entry>> STORE = new TreeMap<>();
    public static volatile Permission permission = (pl, op, kind, action, id, owned) -> op ? null : "Нужны права оператора или разрешение (квест, грамота государства).";

    private ContentApi() {}

    public static synchronized void registerKind(String kind, Handler h) { HANDLERS.put(kind, h); STORE.computeIfAbsent(kind, k -> new LinkedHashMap<>()); }

    public static synchronized Set<String> kinds() { return new TreeSet<>(HANDLERS.keySet()); }

    public static synchronized List<Entry> list(String kind) { return new ArrayList<>(STORE.getOrDefault(kind, Map.of()).values()); }

    public static synchronized Entry get(String kind, String id) { return STORE.getOrDefault(kind, Map.of()).get(id); }

    public static synchronized void resetForTests() { HANDLERS.clear(); STORE.clear(); permission = (pl, op, kind, action, id, owned) -> op ? null : "Нужны права оператора."; }

    private static int owned(String kind, String player) {
        int n = 0;
        for (Entry e : STORE.getOrDefault(kind, Map.of()).values()) if (e.owner().equals(player)) n++;
        return n;
    }

    private static String checkId(String id) { return id.matches("[a-z0-9_]{2,40}") ? null : "id: только строчные латинские буквы, цифры и _ (2-40 знаков)."; }

    private static String allowed(String kind, String player, boolean op, String action, String id) {
        if (!HANDLERS.containsKey(kind)) return "Неизвестный вид: " + kind + ". Доступны: " + kinds();
        Entry cur = STORE.get(kind).get(id);
        if (cur != null && !op && !cur.owner().equals(player)) return "Это чужая запись.";
        return permission.check(player, op, kind, action, id, owned(kind, player));
    }

    /** Заготовка новой записи (сохраняется сразу). */
    public static synchronized String create(String kind, String id, String name, String player, boolean op) {
        String why = allowed(kind, player, op, "create", id);
        if (why != null) return why;
        if ((why = checkId(id)) != null) return why;
        Handler h = HANDLERS.get(kind);
        if (STORE.get(kind).containsKey(id) || h.exists(id)) return "id уже занят: " + id;
        return put(kind, id, h.template(id, name), player, op);
    }

    /** Сохраняет запись целиком: проверка, применение, хранение. */
    public static synchronized String put(String kind, String id, String json, String player, boolean op) {
        String why = allowed(kind, player, op, "edit", id);
        if (why != null) return why;
        if ((why = checkId(id)) != null) return why;
        Handler h = HANDLERS.get(kind);
        Entry cur = STORE.get(kind).get(id);
        if (cur == null && h.exists(id)) return "id уже занят системным содержимым: " + id;
        try { if ((why = h.validate(json)) != null) return why; }
        catch (RuntimeException e) { return "Ошибка в JSON: " + e.getMessage(); }
        Object parsed = Json.parse(json);
        if (parsed instanceof Map<?, ?> m && m.get("id") != null && !String.valueOf(m.get("id")).equals(id)) return "Поле id в JSON должно совпадать: " + id;
        String owner = cur != null ? cur.owner() : player;
        try { h.apply(id, json); } catch (RuntimeException e) { return "Не удалось применить: " + e.getMessage(); }
        STORE.get(kind).put(id, new Entry(kind, id, owner, json));
        return null;
    }

    /** Меняет одно поле верхнего уровня (строка, число, true/false). */
    @SuppressWarnings("unchecked")
    public static synchronized String set(String kind, String id, String field, String value, String player, boolean op) {
        Entry e = get(kind, id);
        if (e == null) return "Нет записи " + kind + "/" + id;
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) Json.parse(e.json()));
        Object v = value;
        if (value.matches("-?\\d+")) v = Long.parseLong(value); else if (value.matches("-?\\d+\\.\\d+")) v = Double.parseDouble(value);
        else if (value.equals("true") || value.equals("false")) v = Boolean.parseBoolean(value);
        if (field.equals("id")) return "id менять нельзя.";
        m.put(field, v);
        return put(kind, id, Json.write(m), player, op);
    }

    public static synchronized String remove(String kind, String id, String player, boolean op) {
        String why = allowed(kind, player, op, "remove", id);
        if (why != null) return why;
        Entry e = STORE.get(kind).remove(id);
        if (e == null) return "Нет записи " + kind + "/" + id;
        HANDLERS.get(kind).remove(id);
        return null;
    }

    /** Применяет все сохранённые записи заново (старт сервера, перезагрузка датапаков). Битые пропускаются, их список возвращается. */
    public static synchronized List<String> reapply() {
        List<String> bad = new ArrayList<>();
        for (var ke : STORE.entrySet()) {
            Handler h = HANDLERS.get(ke.getKey());
            if (h == null) continue;
            for (Entry e : ke.getValue().values()) {
                try { h.apply(e.id(), e.json()); } catch (RuntimeException ex) { bad.add(e.kind() + "/" + e.id() + ": " + ex.getMessage()); }
            }
        }
        return bad;
    }

    public static synchronized String toJson() {
        List<Object> out = new ArrayList<>();
        for (var ke : STORE.entrySet()) for (Entry e : ke.getValue().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.kind()); m.put("id", e.id()); m.put("owner", e.owner()); m.put("json", e.json());
            out.add(m);
        }
        return Json.write(new LinkedHashMap<String, Object>(Map.of("entries", out)));
    }

    /** Загружает записи (виды должны быть уже зарегистрированы; неизвестные вид сохраняются, чтобы не терять данные). */
    public static synchronized void fromJson(String json) {
        for (var m : STORE.values()) m.clear();
        if (!(Json.parse(json) instanceof Map<?, ?> root) || !(root.get("entries") instanceof List<?> l)) return;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String kind = String.valueOf(m.get("kind"));
            STORE.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(String.valueOf(m.get("id")), new Entry(kind, String.valueOf(m.get("id")), String.valueOf(m.get("owner")), String.valueOf(m.get("json"))));
        }
    }
}
