package dev.worldforge.mod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Запускает веб-карту. Настройки: config/worldforge/web.json (enabled, port, bind, show_players, map). */
public final class WebService {
    private static volatile WebMap web;
    private static volatile String url = "";

    public static String url() { return url; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config() throws IOException {
        Path f = FMLPaths.CONFIGDIR.get().resolve(WorldForgeMod.MOD_ID).resolve("web.json");
        if (!Files.isRegularFile(f)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("enabled", true);
            d.put("port", 8123);
            d.put("bind", "0.0.0.0");
            d.put("show_players", true);
            d.put("map", "");
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(d), StandardCharsets.UTF_8);
            return d;
        }
        return (Map<String, Object>) Json.parse(Files.readString(f, StandardCharsets.UTF_8));
    }

    private static MapData pick(String id) {
        if (!id.isEmpty()) return WorldForgeMod.loadedMaps().get(id);
        for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null) return m;
        return null;
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try {
            Map<String, Object> c = config();
            if (!Boolean.TRUE.equals(c.get("enabled"))) return;
            MinecraftServer server = e.getServer();
            String mapId = c.get("map") == null ? "" : c.get("map").toString();
            boolean showPlayers = !Boolean.FALSE.equals(c.get("show_players"));
            int port = ((Number) c.get("port")).intValue();
            web = new WebMap(new WebMap.Provider() {
                public MapData map() { return pick(mapId); }
                public int version() { return EditorManager.version(map()); }
                public Markers markers() { return EditorManager.markers(server, map()); }
                public List<WebMap.Player> players() {
                    List<WebMap.Player> out = new ArrayList<>();
                    MapData m = map();
                    if (!showPlayers || m == null) return out;
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        if (EditorManager.dataFor(p.serverLevel()) == m) out.add(new WebMap.Player(p.getName().getString(), (int) p.getX(), (int) p.getZ()));
                    }
                    return out;
                }
            }, c.get("bind").toString(), port, () -> WebMap.readResource("/assets/worldforge/web/index.html"));
            url = "http://<адрес сервера>:" + web.port() + "/";
            WorldForgeMod.LOGGER.info("WorldForge: веб-карта запущена на порту {}", web.port());
        } catch (IOException | RuntimeException ex) {
            WorldForgeMod.LOGGER.error("WorldForge: веб-карта не запущена: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        if (web != null) web.stop();
        web = null;
        url = "";
        SyncService.forget();
    }
}
