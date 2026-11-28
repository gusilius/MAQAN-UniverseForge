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
    public static final List<String> TYPES = List.of("city", "settlement", "village", "capital", "castle", "fort", "port", "ruin", "camp", "temple", "mine", "army", "corps", "unit", "commander", "caravan", "other");

    public static final class Marker {
        public String id, name, type, desc = "";
        /** Optional stable shared visual profile id; empty keeps type-based compatibility defaults. */
        public String visualProfile = "";
        /** Optional registry item id used as the native 3D map/Diorama emblem. */
        public String visualItem = "";
        public int x, z, rgb = 0xFFFFFF;
    }

    private final List<Marker> list = new ArrayList<>();
    /** Persisted overrides for provider-owned live objects (e.g. armies) that are not user markers. */
    private final Map<String, String> visualOverrides = new LinkedHashMap<>();
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
        if (type == null || !TYPES.contains(type)) throw new IllegalArgumentException("Тип метки: " + TYPES);
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

    /** Provider-owned live marker added to an outgoing snapshot; does not dirty persisted user markers. */
    public void addSnapshot(Marker marker) {
        if (marker == null || marker.id == null || marker.id.isBlank()
                || marker.name == null || marker.name.isBlank()
                || marker.type == null || !TYPES.contains(marker.type)) return;
        String override = visualOverrides.get(marker.id);
        if (override != null) marker.visualProfile = override;
        list.add(marker);
    }

    /** Updates a marker's explicit visual profile; blank restores category-based automatic selection. */
    public boolean setVisualProfile(String id, String visualProfile) {
        if (id == null || id.isBlank()) return false;
        String markerId = id.trim();
        String normalized = visualProfile == null ? "" : visualProfile.trim();
        if (markerId.length() > 128 || normalized.length() > 128)
            throw new IllegalArgumentException("Слишком длинный идентификатор визуального профиля или объекта");
        Marker marker = find(markerId);
        if (normalized.isBlank()) visualOverrides.remove(markerId);
        else visualOverrides.put(markerId, normalized);
        if (marker != null) marker.visualProfile = normalized;
        dirty = true;
        return true;
    }

    public boolean remove(String q) {
        Marker m = find(q);
        if (m == null) return false;
        list.remove(m);
        visualOverrides.remove(m.id);
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
            if (m.visualProfile != null && !m.visualProfile.isBlank()) o.put("visual", m.visualProfile);
            if (m.visualItem != null && !m.visualItem.isBlank()) o.put("map_item", m.visualItem);
            out.add(o);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("markers", out);
        if (!visualOverrides.isEmpty()) root.put("visual_overrides", new LinkedHashMap<>(visualOverrides));
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static Markers fromJson(String json) {
        Markers ms = new Markers();
        if (json == null || json.isBlank()) return ms;
        final Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException malformedJson) {
            return ms;
        }
        if (!(parsed instanceof Map<?, ?> root)) return ms;

        Object rawOverrides = root.get("visual_overrides");
        if (rawOverrides instanceof Map<?, ?> overrides) {
            for (Map.Entry<?, ?> entry : overrides.entrySet()) {
                if (entry.getKey() instanceof String id && entry.getValue() instanceof String profile
                        && !id.isBlank() && !profile.isBlank()
                        && id.length() <= 128 && profile.length() <= 128) {
                    ms.visualOverrides.put(id.trim(), profile.trim());
                }
            }
        }

        Object rawMarkers = root.get("markers");
        if (!(rawMarkers instanceof List<?> markerList)) return ms;
        for (Object raw : markerList) {
            if (!(raw instanceof Map<?, ?> entry)) continue;
            Object idValue = entry.get("id");
            Object nameValue = entry.get("name");
            Object typeValue = entry.get("type");
            if (!(idValue instanceof String id) || id.isBlank() || id.length() > 128
                    || !(nameValue instanceof String name) || name.isBlank()
                    || !(typeValue instanceof String type) || !TYPES.contains(type)) continue;

            Marker marker = new Marker();
            marker.id = id.trim();
            marker.name = name.trim();
            marker.type = type;
            marker.x = integer(entry.get("x"), 0);
            marker.z = integer(entry.get("z"), 0);
            marker.rgb = parseColor(entry.get("color"), 0xFFFFFF);
            marker.desc = entry.get("desc") instanceof String desc ? desc : "";
            marker.visualProfile = boundedString(entry.get("visual"), 128);
            marker.visualItem = boundedString(entry.get("map_item"), 128);
            String override = ms.visualOverrides.get(marker.id);
            if (override != null) marker.visualProfile = override;
            ms.list.add(marker);
        }
        return ms;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String boundedString(Object value, int maxLength) {
        if (!(value instanceof String text)) return "";
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : "";
    }

    private static int parseColor(Object value, int fallback) {
        if (!(value instanceof String color)) return fallback;
        String hex = color.startsWith("#") ? color.substring(1) : color.startsWith("0x") ? color.substring(2) : color;
        if (hex.length() != 6) return fallback;
        try { return Integer.parseInt(hex, 16) & 0xFFFFFF; }
        catch (NumberFormatException invalid) { return fallback; }
    }

    public static Markers load(Path file) throws IOException {
        return Files.isRegularFile(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : new Markers();
    }

    public void save(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }
}
