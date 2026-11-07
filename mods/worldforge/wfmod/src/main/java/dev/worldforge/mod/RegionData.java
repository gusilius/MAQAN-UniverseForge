package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Регионы карты (политическая карта): индекс региона на каждый пиксель + описание регионов. Без зависимостей от Minecraft. */
public final class RegionData {
    public static final class Region {
        public final int id;
        public final String name;
        public final int rgb;                 // цвет региона на карте
        public final int capX, capZ;          // столица (блоки мира)
        public final double capNx, capNy;     // столица в долях картинки
        public final long areaBlocks;
        public final double areaPct;          // процент суши
        public final int[] neighbors;
        public final Map<String, Object> props; // свои свойства из regions.json (правитель, фракция...), для будущих действий

        Region(int id, String name, int rgb, int capX, int capZ, double nx, double ny, long area, double pct, int[] nb, Map<String, Object> props) {
            this.id = id; this.name = name; this.rgb = rgb; this.capX = capX; this.capZ = capZ;
            this.capNx = nx; this.capNy = ny; this.areaBlocks = area; this.areaPct = pct; this.neighbors = nb; this.props = props;
        }
    }

    public final List<Region> list = new ArrayList<>();
    private final Region[] byId;
    private final byte[] labels;
    private final int w, h;

    @SuppressWarnings("unchecked")
    RegionData(String json, byte[] labels, int w, int h) {
        this.labels = labels;
        this.w = w;
        this.h = h;
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        List<Object> rs = (List<Object>) root.get("regions");
        int max = 0;
        for (Object o : rs) {
            Map<String, Object> r = (Map<String, Object>) o;
            max = Math.max(max, ((Number) r.get("id")).intValue());
        }
        byId = new Region[max + 1];
        for (Object o : rs) {
            Map<String, Object> r = (Map<String, Object>) o;
            Map<String, Object> cap = (Map<String, Object>) r.get("capital");
            List<Object> nbl = (List<Object>) r.get("neighbors");
            int[] nb = new int[nbl == null ? 0 : nbl.size()];
            for (int i = 0; i < nb.length; i++) nb[i] = ((Number) nbl.get(i)).intValue();
            Map<String, Object> props = r.get("props") instanceof Map ? (Map<String, Object>) r.get("props") : Map.of();
            Region reg = new Region(((Number) r.get("id")).intValue(), (String) r.get("name"),
                    Integer.parseInt(((String) r.get("color")).substring(1), 16),
                    ((Number) cap.get("x")).intValue(), ((Number) cap.get("z")).intValue(),
                    ((Number) cap.get("nx")).doubleValue(), ((Number) cap.get("ny")).doubleValue(),
                    ((Number) r.get("area_blocks")).longValue(), ((Number) r.get("area_pct_of_land")).doubleValue(), nb, props);
            byId[reg.id] = reg;
            list.add(reg);
        }
    }

    /** Номер региона в пикселе карты (0 = нет региона). */
    int labelAt(int px, int pz) {
        px = px < 0 ? 0 : (px >= w ? w - 1 : px);
        pz = pz < 0 ? 0 : (pz >= h ? h - 1 : pz);
        return labels[pz * w + px] & 0xFF;
    }

    public Region get(int id) {
        return id > 0 && id < byId.length ? byId[id] : null;
    }

    /** Поиск по названию: сначала точное совпадение, затем по вхождению (без учёта регистра). */
    public Region find(String name) {
        String q = name.trim().toLowerCase(Locale.ROOT);
        for (Region r : list) if (r.name.toLowerCase(Locale.ROOT).equals(q)) return r;
        for (Region r : list) if (r.name.toLowerCase(Locale.ROOT).contains(q)) return r;
        return null;
    }
}
