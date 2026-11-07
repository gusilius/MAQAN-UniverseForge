package dev.worldforge.people;

import java.util.*;

/** Набор ролей. Хозяин мода загружает файлы датапаков через {@link #add}. Порядок добавления сохраняется. */
public final class RoleDefs {
    private final Map<String, Role> roles = new LinkedHashMap<>();

    public void add(String json) {
        Role r = Role.parse(json);
        roles.put(r.id, r);                      // поздний файл заменяет ранний: так датапак переопределяет роль
    }

    public Role get(String id) { return roles.get(id); }

    public Collection<Role> all() { return roles.values(); }

    public Role ruler() {
        for (Role r : roles.values()) if (r.ruler) return r;
        return null;
    }
}
