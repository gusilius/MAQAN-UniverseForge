package dev.worldforge.mod;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Веб-карта в браузере (как Dynmap): рельеф, провинции и все режимы Paradox, поиск, метки и игроки.
 * Браузер сам рисует режимы по данным; после правок редактора страница обновляется сама. Без зависимостей от Minecraft.
 */
public final class WebMap {
    public interface Provider {
        MapData map();

        int version();

        Markers markers();

        List<Player> players();
    }

    public static final class Player {
        public final String name;
        public final int x, z;

        public Player(String name, int x, int z) { this.name = name; this.x = x; this.z = z; }
    }

    private final Provider prov;
    private final HttpServer server;
    private volatile byte[] terrainPng;
    private volatile int terrainFor = -2;
    private final java.util.function.Supplier<byte[]> index;

    public WebMap(Provider prov, String bind, int port, java.util.function.Supplier<byte[]> indexHtml) throws IOException {
        this.prov = prov;
        this.index = indexHtml;
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "worldforge-web"); t.setDaemon(true); return t; }));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() { server.stop(0); }

    private void send(HttpExchange x, int code, String type, byte[] body, boolean gzip) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-cache");
        if (gzip && body.length > 1024) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(body); }
            body = bo.toByteArray();
            x.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream o = x.getResponseBody()) { o.write(body); }
        x.close();
    }

    private void json(HttpExchange x, String s) throws IOException {
        send(x, 200, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8), true);
    }

    private synchronized byte[] terrain(MapData m, int version) throws IOException {
        if (terrainPng == null || terrainFor != version) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            MapImages.writePng(TerrainImage.render(m), m.w, m.h, bo);
            terrainPng = bo.toByteArray();
            terrainFor = version;
        }
        return terrainPng;
    }

    private void handle(HttpExchange x) {
        try {
            String path = x.getRequestURI().getPath();
            MapData m = prov.map();
            if (path.equals("/") || path.equals("/index.html")) {
                send(x, 200, "text/html; charset=utf-8", index.get(), false);
            } else if (m == null || m.titles == null) {
                send(x, 503, "text/plain; charset=utf-8", "Карта недоступна".getBytes(StandardCharsets.UTF_8), false);
            } else if (path.equals("/api/info")) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", m.name);
                o.put("w", m.w);
                o.put("h", m.h);
                o.put("bpp", m.bpp);
                o.put("version", prov.version());
                o.put("modes", MapImages.modes(m.titles));
                json(x, Json.write(o));
            } else if (path.equals("/api/version")) {
                send(x, 200, "text/plain", Integer.toString(prov.version()).getBytes(StandardCharsets.UTF_8), false);
            } else if (path.equals("/api/titles.json")) {
                json(x, m.titles.titlesJson());
            } else if (path.equals("/api/realms.json")) {
                json(x, m.titles.realmsJson());
            } else if (path.equals("/api/provinces.bin")) {
                short[] px = m.titles.pixels();
                ByteBuffer bb = ByteBuffer.allocate(px.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (short v : px) bb.putShort(v);
                send(x, 200, "application/octet-stream", bb.array(), true);
            } else if (path.equals("/api/terrain.png")) {
                send(x, 200, "image/png", terrain(m, 0), false);
            } else if (path.equals("/api/actions.json")) {
                json(x, dev.worldforge.mod.api.WorldForgeApi.actionsJson());
            } else if (path.equals("/api/markers.json")) {
                Markers ms = prov.markers();
                json(x, ms == null ? "{\"markers\":[]}" : ms.toJson());
            } else if (path.equals("/api/players.json")) {
                StringBuilder b = new StringBuilder("{\"players\":[");
                boolean first = true;
                for (Player p : prov.players()) {
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"name\":").append(Json.write(p.name)).append(",\"x\":").append(p.x).append(",\"z\":").append(p.z).append('}');
                }
                json(x, b.append("]}").toString());
            } else {
                send(x, 404, "text/plain; charset=utf-8", "Не найдено".getBytes(StandardCharsets.UTF_8), false);
            }
        } catch (Exception e) {
            try { send(x, 500, "text/plain; charset=utf-8", String.valueOf(e).getBytes(StandardCharsets.UTF_8), false); } catch (IOException ignored) { }
        }
    }

    /** Страница из ресурсов мода или файла. */
    public static byte[] readResource(String name) {
        try (InputStream in = WebMap.class.getResourceAsStream(name)) {
            return in == null ? "<h1>index.html не найден</h1>".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
