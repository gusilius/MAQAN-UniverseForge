package dev.worldforge.mod.client;

import dev.worldforge.mod.Json;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Личные вейпойнты игрока (только на этом компьютере), отдельный список для каждой карты. */
public final class Waypoints {
    public static final class Wp {
        public String name;
        public int x, z, rgb;
    }

    public static final List<Wp> LIST = new ArrayList<>();
    private static String loadedFor = "";

    private Waypoints() {}

    private static Path file(String map) {
        return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("waypoints_" + map.replace(':', '_').replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }

    @SuppressWarnings("unchecked")
    public static void load(String map) {
        LIST.clear();
        loadedFor = map;
        try {
            Path f = file(map);
            if (!Files.isRegularFile(f)) return;
            Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            for (Object o : (List<Object>) root.get("waypoints")) {
                Map<String, Object> e = (Map<String, Object>) o;
                Wp w = new Wp();
                w.name = (String) e.get("name");
                w.x = ((Number) e.get("x")).intValue();
                w.z = ((Number) e.get("z")).intValue();
                w.rgb = ((Number) e.get("rgb")).intValue();
                LIST.add(w);
            }
        } catch (IOException | RuntimeException ignored) {
            // повреждённый файл вейпойнтов не должен ломать игру
        }
    }

    public static void save() {
        if (loadedFor.isEmpty()) return;
        try {
            List<Object> out = new ArrayList<>();
            for (Wp w : LIST) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", w.name);
                e.put("x", w.x);
                e.put("z", w.z);
                e.put("rgb", w.rgb);
                out.add(e);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("waypoints", out);
            Path f = file(loadedFor);
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // не удалось записать: вейпойнты останутся до выхода из игры
        }
    }

    public static Wp add(String name, int x, int z) {
        Wp w = new Wp();
        w.name = name;
        w.x = x;
        w.z = z;
        w.rgb = 0x40E0D0;
        LIST.add(w);
        save();
        return w;
    }

    public static void remove(Wp w) {
        LIST.remove(w);
        save();
    }
}
