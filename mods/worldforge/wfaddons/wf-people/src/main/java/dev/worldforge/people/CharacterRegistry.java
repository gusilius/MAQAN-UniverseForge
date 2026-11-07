package dev.worldforge.people;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Все персонажи мира и их должности. Сохраняется целиком в JSON вместе с миром. Аддоны подписываются на события
 * и спрашивают, что разрешает должность ({@link #unlocked}), не зная ничего о ролях друг друга.
 */
public final class CharacterRegistry {
    public interface Listener {
        default void appointed(Character c, String role, String place) { }

        default void dismissed(Character c, String role, String place) { }

        default void died(Character c) { }

        default void born(Character c) { }
    }

    private final Map<String, Character> byId = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private int next = 1;

    public void addListener(Listener l) { listeners.add(l); }

    public Character get(String id) { return byId.get(id); }

    public Collection<Character> all() { return byId.values(); }

    public int size() { return byId.size(); }

    public Character create(String name, String house, String culture, String gender, int birthYear) {
        Character c = new Character("c" + next++, name);
        c.house = house == null ? "" : house;
        c.culture = culture == null ? "" : culture;
        c.gender = gender;
        c.birthYear = birthYear;
        byId.put(c.id, c);
        for (Listener l : listeners) l.born(c);
        return c;
    }

    public void appoint(String id, String role, String place) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " мёртв и не может занять должность");
        if (c.has(role, place)) return;
        c.posts.add(new Character.Post(role, place));
        for (Listener l : listeners) l.appointed(c, role, place);
    }

    public void dismiss(String id, String role, String place) {
        Character c = require(id);
        if (c.posts.removeIf(p -> p.role.equals(role) && p.place.equals(place))) for (Listener l : listeners) l.dismissed(c, role, place);
    }

    /** Смерть освобождает все должности. Возвращает их список (чтобы назначить преемников). */
    public List<Character.Post> kill(String id) {
        Character c = require(id);
        List<Character.Post> vacated = new ArrayList<>(c.posts);
        c.alive = false;
        c.posts.clear();
        for (Character.Post p : vacated) for (Listener l : listeners) l.dismissed(c, p.role, p.place);
        for (Listener l : listeners) l.died(c);
        return vacated;
    }

    public List<Character> holders(String role, String place) {
        List<Character> out = new ArrayList<>();
        for (Character c : byId.values()) if (c.alive && c.has(role, place)) out.add(c);
        return out;
    }

    public Character holder(String role, String place) {
        List<Character> l = holders(role, place);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Все ли условия выполнены: есть ли в этом месте живой носитель роли, разрешающей действие (например "diplomacy"). */
    public boolean unlocked(RoleDefs defs, String place, String capability) {
        for (Role r : defs.all()) {
            if (!r.unlocks.contains(capability)) continue;
            if (!holders(r.id, place).isEmpty()) return true;
        }
        return false;
    }

    public void marry(String a, String b) {
        Character x = require(a), y = require(b);
        if (x == y) throw new IllegalArgumentException("Персонаж не может вступить в брак с самим собой");
        if (!x.alive || !y.alive) throw new IllegalStateException("Умерший персонаж не может вступить в брак");
        clearDeceasedSpouse(x);
        clearDeceasedSpouse(y);
        if (!x.spouse.isEmpty()) throw new IllegalStateException(x.name + " уже состоит в браке");
        if (!y.spouse.isEmpty()) throw new IllegalStateException(y.name + " уже состоит в браке");
        x.spouse = y.id;
        y.spouse = x.id;
    }

    /** A deceased or missing spouse does not permanently prevent remarriage. */
    private void clearDeceasedSpouse(Character character) {
        if (character.spouse.isEmpty()) return;
        Character spouse = byId.get(character.spouse);
        if (spouse != null && spouse.alive) return;
        if (spouse != null && character.id.equals(spouse.spouse)) spouse.spouse = "";
        character.spouse = "";
    }

    public Character addChild(Character father, Character mother, String name, String gender, int year) {
        Character c = create(name, father.house, father.culture, gender, year);
        c.father = father.id;
        c.mother = mother.id;
        father.children.add(c.id);
        mother.children.add(c.id);
        return c;
    }

    private Character require(String id) {
        Character c = byId.get(id);
        if (c == null) throw new IllegalArgumentException("Нет персонажа " + id);
        return c;
    }

    // ------------------------------------------------------------------ сохранение

    public String toJson() { return toJson(null); }

    /** Только перечисленные персонажи (null - все): для окон на клиенте, которым не нужен весь мир. */
    public String toJson(Set<String> only) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-people/1");
        root.put("next", next);
        List<Object> list = new ArrayList<>();
        for (Character c : byId.values()) {
            if (only != null && !only.contains(c.id)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("name", c.name); m.put("house", c.house); m.put("culture", c.culture); m.put("gender", c.gender);
            m.put("birth", c.birthYear); m.put("alive", c.alive); m.put("father", c.father); m.put("mother", c.mother); m.put("spouse", c.spouse);
            m.put("children", new ArrayList<Object>(c.children));
            List<Object> posts = new ArrayList<>();
            for (Character.Post p : c.posts) posts.add(new ArrayList<Object>(List.of(p.role, p.place)));
            m.put("posts", posts);
            m.put("traits", new ArrayList<Object>(c.traits));
            m.put("props", c.props);
            list.add(m);
        }
        root.put("characters", list);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        // Parse and validate into an isolated snapshot. Never destroy the live registry
        // until the entire save has been accepted.
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Повреждён корень сохранения людей");
        if (!"wf-people/1".equals(raw.get("format"))) throw new IllegalArgumentException("Неверный формат людей");
        Object rawNext = raw.get("next");
        if (!(rawNext instanceof Number nextNumber)) throw new IllegalArgumentException("Отсутствует счётчик персонажей");
        long requestedNext = nextNumber.longValue();
        if (requestedNext < 1 || requestedNext > Integer.MAX_VALUE) throw new IllegalArgumentException("Недопустимый счётчик персонажей");
        if (!(raw.get("characters") instanceof List<?> entries)) throw new IllegalArgumentException("Отсутствует список персонажей");

        Map<String, Character> loaded = new LinkedHashMap<>();
        int maxNumericId = 0;
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException("Повреждена запись персонажа");
            String id = requiredString(m, "id");
            String name = requiredString(m, "name");
            if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Пустой ID или имя персонажа");
            Character c = new Character(id, name);
            c.house = optionalString(m, "house");
            c.culture = optionalString(m, "culture");
            c.gender = optionalString(m, "gender");
            if (c.gender.isBlank()) c.gender = "m";
            Object birth = m.get("birth");
            if (!(birth instanceof Number birthNumber)) throw new IllegalArgumentException("Нет года рождения: " + id);
            c.birthYear = birthNumber.intValue();
            c.alive = Boolean.TRUE.equals(m.get("alive"));
            c.father = optionalString(m, "father");
            c.mother = optionalString(m, "mother");
            c.spouse = optionalString(m, "spouse");

            readStringList(m.get("children"), c.children, "children", id);
            Object rawPosts = m.get("posts");
            if (!(rawPosts instanceof List<?> posts)) throw new IllegalArgumentException("Повреждены должности персонажа " + id);
            for (Object post : posts) {
                if (!(post instanceof List<?> pair) || pair.size() != 2
                        || !(pair.get(0) instanceof String role) || role.isBlank()
                        || !(pair.get(1) instanceof String place) || place.isBlank())
                    throw new IllegalArgumentException("Повреждена должность персонажа " + id);
                if (c.has(role, place)) throw new IllegalArgumentException("Повторная должность персонажа " + id);
                c.posts.add(new Character.Post(role, place));
            }
            Object rawTraits = m.get("traits");
            if (!(rawTraits instanceof List<?> traits)) throw new IllegalArgumentException("Повреждены черты персонажа " + id);
            Set<String> uniqueTraits = new HashSet<>();
            for (Object trait : traits) {
                if (!(trait instanceof String s) || s.isBlank() || !uniqueTraits.add(s))
                    throw new IllegalArgumentException("Повреждена или повторяется черта персонажа " + id);
                c.traits.add(s);
            }
            Object props = m.get("props");
            if (props != null) {
                if (!(props instanceof Map<?, ?> p)) throw new IllegalArgumentException("Повреждены свойства персонажа " + id);
                for (Map.Entry<?, ?> entry : p.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Некорректный ключ свойства " + id);
                    c.props.put(key, entry.getValue());
                }
            }
            if (loaded.putIfAbsent(id, c) != null) throw new IllegalArgumentException("Повторный ID персонажа: " + id);
            if (id.matches("c[0-9]+")) {
                try { maxNumericId = Math.max(maxNumericId, Integer.parseInt(id.substring(1))); }
                catch (NumberFormatException ignored) { /* non-allocatable legacy identifier */ }
            }
        }
        if (maxNumericId == Integer.MAX_VALUE) throw new IllegalArgumentException("Исчерпаны ID персонажей");
        int safeNext = (int) Math.max(requestedNext, (long) maxNumericId + 1L);
        byId.clear();
        byId.putAll(loaded);
        next = safeNext;
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("Ожидалась строка " + key);
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return "";
        if (!(value instanceof String s)) throw new IllegalArgumentException("Ожидалась строка " + key);
        return s;
    }

    private static void readStringList(Object raw, List<String> target, String field, String id) {
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("Повреждено поле " + field + " персонажа " + id);
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String s) || s.isBlank() || !unique.add(s))
                throw new IllegalArgumentException("Поврежден список " + field + " персонажа " + id);
            target.add(s);
        }
    }
}
