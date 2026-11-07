package dev.worldforge.settle.mc;

import dev.worldforge.mod.Json;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

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

    public final Settlement s;
    public final String dim;
    public final int cx, cz;
    public final Map<Integer, Site> sites = new LinkedHashMap<>();
    /** UUID игрока-правителя (пусто - поселение без правителя-игрока). Правитель управляет своим поселением без прав оператора. */
    public String owner = "";

    public Colony(Settlement s, String dim, int cx, int cz) {
        this.s = s;
        this.dim = dim;
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
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Colony fromMap(Map<String, Object> m, Defs defs) {
        Settlement s = Settlement.fromJson(Json.write(m.get("settlement")), defs);
        Colony c = new Colony(s, (String) m.get("dim"), ((Number) m.get("cx")).intValue(), ((Number) m.get("cz")).intValue());
        if (m.get("owner") instanceof String ow) c.owner = ow;
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
