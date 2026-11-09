package dev.worldforge.settle.mc;

import dev.worldforge.mod.Json;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.SettlementMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Поселение в мире: симуляция + центр (x, z) + состояние стройки блоками по каждому зданию. Здания хранят смещения от центра. */
public final class Colony {
    /** Состояние стройки одного здания в мире: угол (x, y, z) - минимальный, размеры, сколько блоков чертежа уже поставлено. */
    public static final class Site {
        public String def = "";
        public int x, y, z, sx, sy, sz, placed;
    }
    /** Persistent, exact work-area order assigned to one resident. Coordinates are world block coordinates. */
    public static final class WorkOrder {
        public String mode = "wood";
        public int x, z, radius = 8, level, cursor;
        /** 0=hidden, 1=subtle, 2=strong. */
        public int overlay = 2;
    }
    public final Map<Integer, WorkOrder> workOrders = new LinkedHashMap<>();

    public final Settlement s;
    public final String dim;
    public final int cx, cz;
    public final Map<Integer, Site> sites = new LinkedHashMap<>();
    public SettlementMap map = new SettlementMap();
    /** UUID игрока-правителя (пусто - поселение без правителя-игрока). Правитель управляет своим поселением без прав оператора. */
    public String owner = "";

    public Colony(Settlement s, String dim, int cx, int cz) {
        this.s = s;
        this.dim = dim;
        if (s != null) s.setLedgerScope(dim);
        this.cx = cx;
        this.cz = cz;
    }

    public ServerLevel level(MinecraftServer server) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dim)));
    }

    public int worldX(Building b) { return cx + b.x; }

    public int worldZ(Building b) { return cz + b.z; }

    /** Здание, под которым лежит блок (x, z) в мировых координатах, или null. */
    public Building buildingAt(int x, int z) {
        for (Building b : s.buildings) {
            Site st = sites.get(b.uid);
            int minX = st != null ? st.x : worldX(b) - b.def.sizeX / 2, minZ = st != null ? st.z : worldZ(b) - b.def.sizeZ / 2;
            int sx = st != null ? st.sx : b.def.sizeX, sz = st != null ? st.sz : b.def.sizeZ;
            if (x >= minX && x < minX + sx && z >= minZ && z < minZ + sz) return b;
        }
        return null;
    }

    /** Точка, куда идёт рабочий здания: сразу за южной стеной (там дверь процедурного чертежа). */
    public BlockPos workPoint(Level level, Building b) {
        Site st = sites.get(b.uid);
        int x = st != null ? st.x + st.sx / 2 : worldX(b);
        int z = st != null ? st.z + st.sz : worldZ(b) - b.def.sizeZ / 2 + b.def.sizeZ;
        int y = level.hasChunkAt(new BlockPos(x, 64, z)) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : 64;
        return new BlockPos(x, y, z);
    }

    /** Samples the initial local area without loading chunks. */
    public int captureTerrain(ServerLevel level) {
        return captureTerrain(level, -64, -64, 64, 64);
    }

    /** Expands the map only where an order needs terrain; no artificial settlement build radius. */
    public int captureTerrain(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        int captured = 0;
        int minGX = SettlementMap.grid(Math.min(minX, maxX)), maxGX = SettlementMap.grid(Math.max(minX, maxX));
        int minGZ = SettlementMap.grid(Math.min(minZ, maxZ)), maxGZ = SettlementMap.grid(Math.max(minZ, maxZ));
        for (int gx = minGX; gx <= maxGX; gx++) for (int gz = minGZ; gz <= maxGZ; gz++) {
            int wx = cx + gx * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2;
            int wz = cz + gz * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2;
            BlockPos probe = new BlockPos(wx, level.getMinBuildHeight(), wz);
            if (!level.hasChunkAt(probe)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
            BlockPos surface = new BlockPos(wx, Math.max(level.getMinBuildHeight(), y - 1), wz);
            var state = level.getBlockState(surface);
            boolean water = !state.getFluidState().isEmpty() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
            String biome = level.getBiome(surface).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
            double fertility = state.is(Blocks.FARMLAND) ? 1.0
                    : state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.ROOTED_DIRT) ? 0.72
                    : state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL) ? 0.28 : 0.45;
            if (biome.contains("plains") || biome.contains("forest") || biome.contains("meadow")) fertility = Math.min(1.0, fertility + 0.12);
            if (biome.contains("desert") || biome.contains("badlands") || biome.contains("snow")) fertility *= 0.55;
            map.sample(gx, gz, y, biome, fertility, water);
            captured++;
        }
        return captured;
    }

    // ------------------------------------------------------------------ сохранение

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dim", dim);
        m.put("cx", cx);
        m.put("cz", cz);
        m.put("owner", owner);
        m.put("settlement", Json.parse(s.toJson()));
        List<Object> l = new ArrayList<>();
        for (Map.Entry<Integer, Site> e : sites.entrySet()) {
            Site st = e.getValue();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("uid", e.getKey()); o.put("def", st.def);
            o.put("x", st.x); o.put("y", st.y); o.put("z", st.z); o.put("sx", st.sx); o.put("sy", st.sy); o.put("sz", st.sz); o.put("placed", st.placed);
            l.add(o);
        }
        m.put("sites", l);
        List<Object> orders = new ArrayList<>();
        for (Map.Entry<Integer, WorkOrder> e : workOrders.entrySet()) {
            WorkOrder o = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("worker", e.getKey()); row.put("mode", o.mode); row.put("x", o.x); row.put("z", o.z);
            row.put("radius", o.radius); row.put("level", o.level); row.put("overlay", o.overlay); row.put("cursor", o.cursor);
            orders.add(row);
        }
        m.put("workOrders", orders);
        m.put("map", Json.parse(map.toJson()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Colony fromMap(Map<String, Object> m, Defs defs) {
        Settlement s = Settlement.fromJson(Json.write(m.get("settlement")), defs, (String) m.get("dim"));
        Colony c = new Colony(s, (String) m.get("dim"), ((Number) m.get("cx")).intValue(), ((Number) m.get("cz")).intValue());
        if (m.get("map") instanceof Map<?, ?> mapData) c.map = SettlementMap.fromJson(Json.write(mapData));
        if (m.get("owner") instanceof String ow) c.owner = ow;
        if (m.get("workOrders") instanceof List<?> orders) {
            for (Object raw : orders) {
                if (!(raw instanceof Map<?, ?> row)) continue;
                Object workerValue = row.get("worker");
                int workerId = workerValue instanceof Number n ? n.intValue() : 0;
                if (s.worker(workerId) == null) continue;
                WorkOrder o = new WorkOrder();
                o.mode = row.get("mode") instanceof String mode ? mode : "wood";
                Object xValue = row.get("x"), zValue = row.get("z"), radiusValue = row.get("radius");
                Object levelValue = row.get("level"), overlayValue = row.get("overlay");
                o.x = xValue instanceof Number n ? n.intValue() : c.cx;
                o.z = zValue instanceof Number n ? n.intValue() : c.cz;
                o.radius = radiusValue instanceof Number n ? Math.max(0, Math.min(64, n.intValue())) : 8;
                o.level = levelValue instanceof Number n ? n.intValue() : 0;
                o.overlay = overlayValue instanceof Number n ? Math.max(0, Math.min(2, n.intValue())) : 2;
                Object cursorValue = row.get("cursor");
                o.cursor = cursorValue instanceof Number n ? Math.max(0, n.intValue()) : 0;
                c.workOrders.put(workerId, o);
            }
        }
        if (m.get("sites") instanceof List) {
            for (Object o : (List<Object>) m.get("sites")) {
                Map<String, Object> e = (Map<String, Object>) o;
                int uid = ((Number) e.get("uid")).intValue();
                if (s.building(uid) == null) continue;
                Site st = new Site();
                st.def = (String) e.get("def");
                st.x = ((Number) e.get("x")).intValue(); st.y = ((Number) e.get("y")).intValue(); st.z = ((Number) e.get("z")).intValue();
                st.sx = ((Number) e.get("sx")).intValue(); st.sy = ((Number) e.get("sy")).intValue(); st.sz = ((Number) e.get("sz")).intValue();
                st.placed = ((Number) e.get("placed")).intValue();
                c.sites.put(uid, st);
            }
        }
        return c;
    }
}
