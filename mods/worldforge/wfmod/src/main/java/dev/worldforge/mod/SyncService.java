package dev.worldforge.mod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Рассылает клиентам политическую карту при входе, смене измерения и после правок редактора. */
public final class SyncService {
    private static final Map<MapData, short[]> SNAP = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<MapData, Integer> LAST_VERSION = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<MapData, byte[]> TERRAIN = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<MapData, Integer> MARKER_HASH = Collections.synchronizedMap(new IdentityHashMap<>());

    static void forget() {
        SNAP.clear();
        LAST_VERSION.clear();
        TERRAIN.clear();
        MARKER_HASH.clear();
    }

    private static byte[] terrainPng(MapData m) throws IOException {
        byte[] b = TERRAIN.get(m);
        if (b == null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            MapImages.writePng(TerrainImage.render(m), m.w, m.h, bo);
            b = bo.toByteArray();
            TERRAIN.put(m, b);
        }
        return b;
    }

    static void sendTerrain(ServerPlayer sp) {
        MapData m = EditorManager.dataFor(sp.serverLevel());
        if (m == null || m.titles == null) return;
        try {
            WFNet.send(sp, WFNet.TERRAIN, terrainPng(m));
        } catch (IOException e) {
            WorldForgeMod.LOGGER.error("WorldForge: не удалось отдать рельеф клиенту: {}", e.toString());
        }
    }

    static void sendAll(ServerPlayer sp) {
        MapData m = EditorManager.dataFor(sp.serverLevel());
        if (m == null || m.titles == null) {
            WFNet.send(sp, WFNet.CLEAR, new byte[0]);
            return;
        }
        try {
            SNAP.computeIfAbsent(m, k -> k.titles.pixels().clone());
            LAST_VERSION.putIfAbsent(m, EditorManager.version(m));
            WFNet.send(sp, WFNet.STATE, MapSync.encodeState(m, EditorManager.version(m)));
            WFNet.send(sp, WFNet.MARKERS, markerBytes(sp.server, m));
            WFNet.send(sp, WFNet.ACTIONS, dev.worldforge.mod.api.WorldForgeApi.actionsJson().getBytes(StandardCharsets.UTF_8));
            WFNet.send(sp, WFNet.TERRAIN_INFO, MapSync.sha1(terrainPng(m)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            WorldForgeMod.LOGGER.error("WorldForge: не удалось отправить карту игроку {}: {}", sp.getName().getString(), e.toString());
        }
    }

    private static byte[] markerBytes(MinecraftServer server, MapData m) {
        return EditorManager.markers(server, m).toJson().getBytes(StandardCharsets.UTF_8);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) sendAll(sp);
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) sendAll(sp);
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) sendAll(sp);
    }

    /** Раз в две секунды: дельта правок и метки всем игрокам на картах с изменениями. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 40 != 0 || WFNet.CHANNEL == null) return;
        Map<MapData, byte[]> deltas = new HashMap<>(), marks = new HashMap<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            MapData m = EditorManager.dataFor(sp.serverLevel());
            if (m == null || m.titles == null) continue;
            try {
                if (!deltas.containsKey(m)) {
                    int v = EditorManager.version(m);
                    byte[] d = null;
                    if (LAST_VERSION.getOrDefault(m, v) != v && SNAP.containsKey(m)) {
                        d = MapSync.encodeDelta(m.titles, SNAP.get(m), v);
                        SNAP.put(m, m.titles.pixels().clone());
                    }
                    LAST_VERSION.put(m, v);
                    deltas.put(m, d);
                    byte[] mk = markerBytes(server, m);
                    int h = java.util.Arrays.hashCode(mk);
                    marks.put(m, MARKER_HASH.getOrDefault(m, h) != h ? mk : null);
                    MARKER_HASH.put(m, h);
                }
                if (deltas.get(m) != null) WFNet.send(sp, WFNet.DELTA, deltas.get(m));
                if (marks.get(m) != null) WFNet.send(sp, WFNet.MARKERS, marks.get(m));
            } catch (IOException ex) {
                WorldForgeMod.LOGGER.error("WorldForge: ошибка рассылки правок: {}", ex.toString());
            }
        }
    }
}
