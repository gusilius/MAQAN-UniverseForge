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
        x.spouse = y.id;
        y.spouse = x.id;
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
        byId.clear();
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (!"wf-people/1".equals(root.get("format"))) throw new IllegalArgumentException("Неверный формат людей");
        next = ((Number) root.get("next")).intValue();
        for (Object o : (List<Object>) root.get("characters")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Character c = new Character((String) m.get("id"), (String) m.get("name"));
            c.house = (String) m.get("house"); c.culture = (String) m.get("culture"); c.gender = (String) m.get("gender");
            c.birthYear = ((Number) m.get("birth")).intValue(); c.alive = Boolean.TRUE.equals(m.get("alive"));
            c.father = (String) m.get("father"); c.mother = (String) m.get("mother"); c.spouse = (String) m.get("spouse");
            for (Object k : (List<Object>) m.get("children")) c.children.add((String) k);
            for (Object p : (List<Object>) m.get("posts")) { List<Object> l = (List<Object>) p; c.posts.add(new Character.Post((String) l.get(0), (String) l.get(1))); }
            for (Object t : (List<Object>) m.get("traits")) c.traits.add((String) t);
            if (m.get("props") instanceof Map) c.props.putAll((Map<String, Object>) m.get("props"));
            byId.put(c.id, c);
        }
    }
}
