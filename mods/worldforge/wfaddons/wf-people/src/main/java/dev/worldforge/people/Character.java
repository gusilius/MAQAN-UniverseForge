package dev.worldforge.people;

import java.util.*;

/** Человек как данные. Тело в мире (моб) лишь изображает его и может исчезать, персонаж живёт в {@link CharacterRegistry}. */
public final class Character {
    /** Должность: роль и место ("realm:<id>" или "title:<id>"). */
    public static final class Post {
        public final String role, place;

        public Post(String role, String place) { this.role = role; this.place = place; }
    }

    public final String id;
    public String name, house = "", culture = "", gender = "m";
    public int birthYear;
    public boolean alive = true;
    public String father = "", mother = "", spouse = "";
    public final List<String> children = new ArrayList<>();
    public final List<Post> posts = new ArrayList<>();
    public final Set<String> traits = new TreeSet<>();
    public final Map<String, Object> props = new LinkedHashMap<>();

    Character(String id, String name) { this.id = id; this.name = name; }

    public boolean has(String role, String place) {
        for (Post p : posts) if (p.role.equals(role) && p.place.equals(place)) return true;
        return false;
    }

    public boolean hasRole(String role) {
        for (Post p : posts) if (p.role.equals(role)) return true;
        return false;
    }
}
