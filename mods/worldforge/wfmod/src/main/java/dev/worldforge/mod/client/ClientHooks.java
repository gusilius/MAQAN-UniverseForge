package dev.worldforge.mod.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.worldforge.mod.MapSync;
import dev.worldforge.mod.Markers;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WorldForgeMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Приём данных карты от сервера (главный поток клиента). */
public final class ClientHooks {
    private static String pendingHash = "";

    private ClientHooks() {}

    public static void onBlob(WFNet.Blob b) {
        byte[] full = ClientState.RE.add(b.id, b.index, b.total, b.data);
        if (full == null) return;
        try {
            switch (b.kind) {
                case WFNet.STATE -> {
                    ClientState.state = MapSync.decodeState(full);
                    Waypoints.load(ClientState.state.name);
                    ClientState.revision++;
                }
                case WFNet.DELTA -> {
                    if (ClientState.state != null) {
                        MapSync.applyDelta(ClientState.state, full);
                        ClientState.revision++;
                    }
                }
                case WFNet.MARKERS -> {
                    ClientState.markers = Markers.fromJson(new String(full, StandardCharsets.UTF_8));
                    ClientState.revision++;
                }
                case WFNet.VIEW -> {
                    String all = new String(full, StandardCharsets.UTF_8);
                    int nl = all.indexOf('\n');
                    if (nl > 0) ClientViews.open(all.substring(0, nl), all.substring(nl + 1));
                }
                case WFNet.ACTIONS -> ClientState.actions = ClientAction.parseList(new String(full, StandardCharsets.UTF_8));
                case WFNet.TERRAIN_INFO -> onTerrainInfo(new String(full, StandardCharsets.UTF_8));
                case WFNet.TERRAIN -> {
                    cache(pendingHash, full);
                    setTerrain(full, pendingHash);
                }
                case WFNet.CLEAR -> {
                    ClientState.state = null;
                    ClientState.revision++;
                }
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            WorldForgeMod.LOGGER.error("WorldForge: не удалось принять данные карты: {}", e.toString());
        }
    }

    private static Path cacheFile(String hash) {
        return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("cache").resolve(hash + ".png");
    }

    private static void cache(String hash, byte[] png) {
        try {
            Path f = cacheFile(hash);
            Files.createDirectories(f.getParent());
            Files.write(f, png);
        } catch (IOException ignored) {
            // кэш необязателен
        }
    }

    private static void onTerrainInfo(String hash) throws IOException {
        pendingHash = hash;
        if (hash.equals(ClientState.terrainHash) && ClientState.terrain != null) return;
        Path f = cacheFile(hash);
        if (Files.isRegularFile(f)) {
            setTerrain(Files.readAllBytes(f), hash);
        } else if (WFNet.CHANNEL != null) {
            WFNet.CHANNEL.sendToServer(new WFNet.NeedTerrain());
        }
    }

    private static void setTerrain(byte[] png, String hash) throws IOException {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(png))) {
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = new int[w * h];
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int abgr = img.getPixelRGBA(x, z);
                    argb[z * w + x] = (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
                }
            }
            ClientState.terrain = argb;
            ClientState.terrainHash = hash;
            ClientState.revision++;
        }
    }
}
