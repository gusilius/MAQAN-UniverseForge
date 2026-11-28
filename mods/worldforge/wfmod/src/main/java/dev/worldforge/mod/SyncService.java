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
    private static long lastEventRevision = Long.MIN_VALUE;
    private static long lastTreasuryRevision = Long.MIN_VALUE;

    static void forget() {
        SNAP.clear();
        LAST_VERSION.clear();
        TERRAIN.clear();
        MARKER_HASH.clear();
        lastEventRevision = Long.MIN_VALUE;
        lastTreasuryRevision = Long.MIN_VALUE;
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
            sendStrategicSnapshots(sp);
            WFNet.send(sp, WFNet.TERRAIN_INFO, MapSync.sha1(terrainPng(m)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            WorldForgeMod.LOGGER.error("WorldForge: не удалось отправить карту игроку {}: {}", sp.getName().getString(), e.toString());
        }
    }

    private static byte[] markerBytes(MinecraftServer server, MapData m) {
        Markers snapshot = Markers.fromJson(EditorManager.markers(server, m).toJson());
        for (Markers.Marker marker : MapMarkerProviders.collect(server, m)) snapshot.addSnapshot(marker);
        return snapshot.toJson().getBytes(StandardCharsets.UTF_8);
    }

    /** Pushes authoritative world history and realm-only treasury projections over the existing VIEW channel. */
    private static void sendStrategicSnapshots(ServerPlayer player) {
        var events = dev.worldforge.mod.api.WorldForgeApi.events();
        WFNet.sendView(player, "wf:events", events.toJson());
        WFNet.sendView(player, "wf:treasury", realmTreasuryJson());
    }

    private static String realmTreasuryJson() {
        List<Object> accounts = new java.util.ArrayList<>();
        for (var account : dev.worldforge.mod.economy.TreasuryService.current().accounts()) {
            if (account == null || account.id == null || !account.id.startsWith("realm:")) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", account.id);
            row.put("currency", account.currency);
            row.put("balance", account.balance);
            row.put("debt", account.debt);
            row.put("bankrupt", account.bankrupt);
            accounts.add(row);
        }
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("format", "worldforge-realm-treasury/1");
        root.put("accounts", accounts);
        return dev.worldforge.mod.Json.write(root);
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
        long eventRevision = dev.worldforge.mod.api.WorldForgeApi.events().revision();
        long treasuryRevision = dev.worldforge.mod.economy.TreasuryService.current().mutationVersion();
        boolean strategicChanged = eventRevision != lastEventRevision || treasuryRevision != lastTreasuryRevision;
        byte[] eventSnapshot = strategicChanged
                ? dev.worldforge.mod.api.WorldForgeApi.events().toJson().getBytes(StandardCharsets.UTF_8) : null;
        byte[] treasurySnapshot = strategicChanged
                ? realmTreasuryJson().getBytes(StandardCharsets.UTF_8) : null;
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
                if (strategicChanged) {
                    WFNet.send(sp, WFNet.VIEW, ("wf:events\\n" + new String(eventSnapshot, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                    WFNet.send(sp, WFNet.VIEW, ("wf:treasury\\n" + new String(treasurySnapshot, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ex) {
                WorldForgeMod.LOGGER.error("WorldForge: ошибка рассылки правок: {}", ex.toString());
            }
        }
        if (strategicChanged) {
            lastEventRevision = eventRevision;
            lastTreasuryRevision = treasuryRevision;
        }
    }
}
