package dev.worldforge.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Общие метки карты: города, замки, порты, руины и т.д. Хранятся в markers.json рядом с правками границ. Без зависимостей от Minecraft. */
public final class Markers {
    public static final List<String> TYPES = List.of("city", "castle", "port", "ruin", "camp", "temple", "mine", "other");

    public static final class Marker {
        public String id, name, type, desc = "";
        public int x, z, rgb = 0xFFFFFF;
    }

    private final List<Marker> list = new ArrayList<>();
    private boolean dirty;

    public List<Marker> list() { return list; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }

    public Marker find(String q) {
        for (Marker m : list) if (m.id.equalsIgnoreCase(q) || m.name.equalsIgnoreCase(q)) return m;
        return null;
    }

    public Marker add(String name, String type, int x, int z, int rgb) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Пустое имя метки");
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Тип метки: " + TYPES);
        Marker m = new Marker();
        m.name = name.trim();
        m.type = type;
        m.x = x;
        m.z = z;
        m.rgb = rgb & 0xFFFFFF;
        m.id = Slug.unique("", name, k -> find(k) != null);
        list.add(m);
        dirty = true;
        return m;
    }

    public boolean remove(String q) {
        Marker m = find(q);
        if (m == null) return false;
        list.remove(m);
        dirty = true;
        return true;
    }

    public String toJson() {
        List<Object> out = new ArrayList<>();
        for (Marker m : list) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", m.id);
            o.put("name", m.name);
            o.put("type", m.type);
            o.put("x", m.x);
            o.put("z", m.z);
            o.put("color", TitleData.hex(m.rgb));
            o.put("desc", m.desc);
            out.add(o);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("markers", out);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static Markers fromJson(String json) {
        Markers ms = new Markers();
        if (json == null || json.isBlank()) return ms;
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        for (Object o : (List<Object>) root.get("markers")) {
            Map<String, Object> e = (Map<String, Object>) o;
            Marker m = new Marker();
            m.id = (String) e.get("id");
            m.name = (String) e.get("name");
            m.type = (String) e.get("type");
            m.x = ((Number) e.get("x")).intValue();
            m.z = ((Number) e.get("z")).intValue();
            m.rgb = Integer.parseInt(((String) e.get("color")).substring(1), 16);
            m.desc = e.get("desc") == null ? "" : (String) e.get("desc");
            ms.list.add(m);
        }
        return ms;
    }

    public static Markers load(Path file) throws IOException {
        return Files.isRegularFile(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : new Markers();
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }
}
