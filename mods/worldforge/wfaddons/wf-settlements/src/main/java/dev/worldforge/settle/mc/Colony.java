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
import java.util.Set;
import java.util.LinkedHashSet;

/** Поселение в мире: симуляция + центр (x, z) + состояние стройки блоками по каждому зданию. Здания хранят смещения от центра. */
public final class Colony {
    /** Состояние стройки одного здания в мире: угол (x, y, z) - минимальный, размеры, сколько блоков чертежа уже поставлено. */
    public static final class Site {
        public String def = "";
        public int x, y, z, sx, sy, sz, placed;
        /** Blueprint indices placed by the player before the simulated builders reach them. */
        public final Set<Integer> assisted = new LinkedHashSet<>();
        /** Physical block placement phase after the economic work simulation reaches completion. */
        public boolean physicalPending, wasPaused;
    }
    /** Persistent, exact work-area order assigned to one resident. Coordinates are world block coordinates. */
    public static final class WorkOrder {
        public String mode = "wood";
        public int x, z, radius = 8, level, cursor, soil, halfX = -1, halfZ = -1;
        public boolean complete;
        /** 0=hidden, 1=subtle, 2=strong. */
        public int overlay = 2;
    }
    public final Map<Integer, WorkOrder> workOrders = new LinkedHashMap<>();
    /** Canonical wf-people global UUIDs residing in this settlement; Workers are only job projections. */
    public final Set<String> residentCharacterIds = new LinkedHashSet<>();
    /** Temporary access scaffolds placed by workers; persisted so reloads cannot leave orphan blocks. */
    public final Map<BlockPos, String> temporaryScaffolds = new LinkedHashMap<>();

    public final Settlement s;
    public final String dim;
    public final int cx, cz;
    public final Map<Integer, Site> sites = new LinkedHashMap<>();
    public SettlementMap map = new SettlementMap();
    /** UUID игрока-правителя (пусто - поселение без правителя-игрока). Правитель управляет своим поселением без прав оператора. */
    public String owner = "";
    /** Autonomous polity ownership; empty for settlements still outside a realm. */
    public String realmId = "";
    /** Stable origin operation that created this colony; empty for ordinary settlements. */
    public String genesisOperationId = "";
    /** Provisioning is guarded across retries; an interrupted phase requires reconciliation, never replay. */
    public boolean genesisProvisioningStarted, genesisProvisioned;
    /** Durable cursor and immutable input fingerprint for resumable Genesis provisioning. */
    public int genesisProvisioningStep;
    public String genesisProvisioningSpec = "";
    /** Loaded physical chunks already reconciled into this village. */
    public final Set<String> vanillaInfrastructureChunks = new LinkedHashSet<>();
    /** Runtime-only poison flag: do not retry unsaved mutations until the last durable snapshot is reloaded. */
    public transient boolean genesisProvisioningRuntimeBlocked;
    /** Runtime-only freeze after a settlement action changed state but could not persist its receipt-side snapshot. */
    public transient boolean actionPersistenceBlocked;

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
        m.put("realmId", realmId);
        m.put("genesisOperationId", genesisOperationId);
        m.put("residentCharacterIds", new ArrayList<>(residentCharacterIds));
        m.put("genesisProvisioningStarted", genesisProvisioningStarted);
        m.put("genesisProvisioned", genesisProvisioned);
        m.put("genesisProvisioningStep", genesisProvisioningStep);
        m.put("genesisProvisioningSpec", genesisProvisioningSpec);
        m.put("vanillaInfrastructureChunks", new ArrayList<>(vanillaInfrastructureChunks));
        m.put("settlement", Json.parse(s.toJson()));
        List<Object> l = new ArrayList<>();
        for (Map.Entry<Integer, Site> e : sites.entrySet()) {
            Site st = e.getValue();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("uid", e.getKey()); o.put("def", st.def);
            o.put("x", st.x); o.put("y", st.y); o.put("z", st.z); o.put("sx", st.sx); o.put("sy", st.sy); o.put("sz", st.sz); o.put("placed", st.placed); o.put("assisted", new ArrayList<>(st.assisted));
            o.put("physicalPending", st.physicalPending); o.put("wasPaused", st.wasPaused);
            l.add(o);
        }
        m.put("sites", l);
        List<Object> orders = new ArrayList<>();
        for (Map.Entry<Integer, WorkOrder> e : workOrders.entrySet()) {
            WorkOrder o = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("worker", e.getKey()); row.put("mode", o.mode); row.put("x", o.x); row.put("z", o.z);
            row.put("radius", o.radius); row.put("halfX", o.halfX); row.put("halfZ", o.halfZ); row.put("level", o.level); row.put("overlay", o.overlay); row.put("cursor", o.cursor); row.put("soil", o.soil); row.put("complete", o.complete);
            orders.add(row);
        }
        m.put("workOrders", orders);
        List<Object> scaffolds = new ArrayList<>();
        for (Map.Entry<BlockPos, String> e : temporaryScaffolds.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", e.getKey().getX()); row.put("y", e.getKey().getY()); row.put("z", e.getKey().getZ());
            row.put("material", e.getValue());
            scaffolds.add(row);
        }
        m.put("temporaryScaffolds", scaffolds);
        m.put("map", Json.parse(map.toJson()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Colony fromMap(Map<String, Object> m, Defs defs) {
        Settlement s = Settlement.fromJson(Json.write(m.get("settlement")), defs, (String) m.get("dim"));
        Colony c = new Colony(s, (String) m.get("dim"), ((Number) m.get("cx")).intValue(), ((Number) m.get("cz")).intValue());
        if (m.get("map") instanceof Map<?, ?> mapData) c.map = SettlementMap.fromJson(Json.write(mapData));
        if (m.get("owner") instanceof String ow) c.owner = ow;
        if (m.get("realmId") instanceof String realm) c.realmId = realm;
        if (m.get("genesisOperationId") instanceof String operationId) c.genesisOperationId = operationId;
        if (m.get("residentCharacterIds") instanceof List<?> residents) for (Object id : residents)
            if (id instanceof String value && !value.isBlank()) c.residentCharacterIds.add(value);
        c.genesisProvisioningStarted = Boolean.TRUE.equals(m.get("genesisProvisioningStarted"));
        c.genesisProvisioned = Boolean.TRUE.equals(m.get("genesisProvisioned"));
        if (m.get("genesisProvisioningStep") instanceof Number step) c.genesisProvisioningStep = Math.max(0, step.intValue());
        if (m.get("genesisProvisioningSpec") instanceof String spec) c.genesisProvisioningSpec = spec;
        if (m.get("vanillaInfrastructureChunks") instanceof List<?> scanned)
            for (Object value : scanned) if (value instanceof String key && key.length() <= 64) c.vanillaInfrastructureChunks.add(key);
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
                Object halfXValue = row.get("halfX"), halfZValue = row.get("halfZ");
                o.halfX = halfXValue instanceof Number n ? Math.max(0, Math.min(64, n.intValue())) : o.radius;
                o.halfZ = halfZValue instanceof Number n ? Math.max(0, Math.min(64, n.intValue())) : o.radius;
                o.level = levelValue instanceof Number n ? n.intValue() : 0;
                o.overlay = overlayValue instanceof Number n ? Math.max(0, Math.min(2, n.intValue())) : 2;
                Object cursorValue = row.get("cursor");
                o.cursor = cursorValue instanceof Number n ? Math.max(0, n.intValue()) : 0;
                Object soilValue = row.get("soil");
                o.soil = soilValue instanceof Number n ? Math.max(0, n.intValue()) : 0;
                o.complete = Boolean.TRUE.equals(row.get("complete"));
                c.workOrders.put(workerId, o);
            }
        }
        if (m.get("temporaryScaffolds") instanceof List<?> scaffolds) {
            for (Object raw : scaffolds) {
                if (!(raw instanceof Map<?, ?> row)) continue;
                if (!(row.get("x") instanceof Number x) || !(row.get("y") instanceof Number y)
                        || !(row.get("z") instanceof Number z) || !(row.get("material") instanceof String material)) continue;
                if (!material.equals("wood") && !material.equals("stone")) continue;
                c.temporaryScaffolds.put(new BlockPos(x.intValue(), y.intValue(), z.intValue()), material);
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
                st.physicalPending = Boolean.TRUE.equals(e.get("physicalPending"));
                st.wasPaused = Boolean.TRUE.equals(e.get("wasPaused"));
                if (e.get("assisted") instanceof List<?> assisted) for (Object index : assisted) if (index instanceof Number n && n.intValue() >= st.placed) st.assisted.add(n.intValue());
                c.sites.put(uid, st);
            }
        }
        return c;
    }
}
