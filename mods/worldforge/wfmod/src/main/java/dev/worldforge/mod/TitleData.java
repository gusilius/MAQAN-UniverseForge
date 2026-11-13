package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Иерархия титулов (баронство ... империя) и государства с отношениями сюзерен-вассал/данник/протекторат.
 * Модель изменяема: правки делает {@link TitleEditor}, после правок вызывается {@link #recompute()}.
 * Провинция это титул уровня 0; её номер хранится в provinces.png. Без зависимостей от Minecraft.
 * У титула выше уровня 0 поле capProvince это id дочернего титула-столицы.
 */
public final class TitleData {
    public static final class Tier {
        public final int index;
        public final String key, name;
        Tier(int index, String key, String name) { this.index = index; this.key = key; this.name = name; }
    }

    public static final class Title {
        public int id, tier, parent, rgb, capX, capZ, capProvince;
        public String key, name;
        public double capNx, capNy, areaPct;
        public long areaBlocks;
        public int[] children = new int[0], neighbors = new int[0];
        public Map<String, Object> props = new LinkedHashMap<>();
    }

    public static final class Realm {
        public String id, name, ruler, relation, overlordId;
        public int rgb;
        public List<String> titleKeys = new ArrayList<>(), subjectIds = new ArrayList<>();
        public long areaBlocks;
        public Map<String, Object> props = new LinkedHashMap<>();
        public Realm overlord;       // разрешается после загрузки всех государств
    }

    public final List<Tier> tiers = new ArrayList<>();
    public final Map<String, Realm> realms = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> relationTypes = new LinkedHashMap<>();
    public final int w, h;
    public final double bpp;
    final short[] provinces;
    private Title[] byId = new Title[0];
    private final Map<String, Title> byKey = new HashMap<>();
    private int[] realmOfProvince = new int[0];       // id титула-провинции -> индекс в realmList (1-based), 0 = нет
    private final List<Realm> realmList = new ArrayList<>();
    private String relFormat = "worldforge-realms/1";
    /** Описания свойств для режимов карты: ключ -> {name, colors:{значение:#rrggbb}, order:[значения]}. Хранится в realms.json (prop_defs). */
    public final Map<String, Map<String, Object>> propDefs = new LinkedHashMap<>();

    /** Создание из данных, полученных по сети (клиент). */
    public static TitleData create(String titlesJson, String realmsJson, short[] provinces, int w, int h) {
        return new TitleData(titlesJson, realmsJson, provinces, w, h);
    }

    TitleData(String titlesJson, String realmsJson, short[] provinces, int w, int h) {
        this.provinces = provinces;
        this.w = w;
        this.h = h;
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) Json.parse(titlesJson);
        this.bpp = ((Number) root.get("blocks_per_pixel")).doubleValue();
        loadMeta(titlesJson, realmsJson);
    }

    // ------------------------------------------------------------------ загрузка и запись метаданных

    @SuppressWarnings("unchecked")
    void loadMeta(String titlesJson, String realmsJson) {
        tiers.clear();
        byKey.clear();
        realms.clear();
        realmList.clear();
        relationTypes.clear();
        Map<String, Object> root = (Map<String, Object>) Json.parse(titlesJson);
        for (Object o : (List<Object>) root.get("tiers")) {
            Map<String, Object> t = (Map<String, Object>) o;
            tiers.add(new Tier(((Number) t.get("index")).intValue(), (String) t.get("key"), (String) t.get("name")));
        }
        List<Object> tl = (List<Object>) root.get("titles");
        int max = 0;
        for (Object o : tl) max = Math.max(max, ((Number) ((Map<String, Object>) o).get("id")).intValue());
        byId = new Title[max + 1];
        for (Object o : tl) {
            Map<String, Object> t = (Map<String, Object>) o;
            Map<String, Object> cap = (Map<String, Object>) t.get("capital");
            Title title = new Title();
            title.id = ((Number) t.get("id")).intValue();
            title.key = (String) t.get("key");
            title.name = (String) t.get("name");
            title.tier = ((Number) t.get("tier")).intValue();
            title.parent = ((Number) t.get("parent")).intValue();
            title.rgb = Integer.parseInt(((String) t.get("color")).substring(1), 16);
            title.capX = ((Number) cap.get("x")).intValue();
            title.capZ = ((Number) cap.get("z")).intValue();
            title.capNx = ((Number) cap.get("nx")).doubleValue();
            title.capNy = ((Number) cap.get("ny")).doubleValue();
            title.capProvince = ((Number) cap.get("province")).intValue();
            title.areaBlocks = ((Number) t.get("area_blocks")).longValue();
            title.areaPct = ((Number) t.get("area_pct_of_land")).doubleValue();
            title.children = ints((List<Object>) t.get("children"));
            title.neighbors = ints((List<Object>) t.get("neighbors"));
            if (t.get("props") instanceof Map) title.props = new LinkedHashMap<>((Map<String, Object>) t.get("props"));
            byId[title.id] = title;
            byKey.put(title.key, title);
        }
        if (realmsJson != null) parseRealms((Map<String, Object>) Json.parse(realmsJson));
        rebuildRealmIndex();
    }

    private static int[] ints(List<Object> l) {
        int[] a = new int[l == null ? 0 : l.size()];
        for (int i = 0; i < a.length; i++) a[i] = ((Number) l.get(i)).intValue();
        return a;
    }

    @SuppressWarnings("unchecked")
    private void parseRealms(Map<String, Object> root) {
        if (root.get("format") instanceof String) relFormat = (String) root.get("format");
        propDefs.clear();
        if (root.get("prop_defs") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("prop_defs")).entrySet()) {
                if (e.getValue() instanceof Map) propDefs.put(e.getKey(), new LinkedHashMap<>((Map<String, Object>) e.getValue()));
            }
        }
        if (root.get("relation_types") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("relation_types")).entrySet()) {
                relationTypes.put(e.getKey(), new LinkedHashMap<>((Map<String, Object>) e.getValue()));
            }
        }
        for (Object o : (List<Object>) root.get("realms")) {
            Map<String, Object> r = (Map<String, Object>) o;
            Realm realm = new Realm();
            realm.id = (String) r.get("id");
            realm.name = (String) r.get("name");
            realm.ruler = r.get("ruler") == null ? "" : (String) r.get("ruler");
            realm.rgb = Integer.parseInt(((String) r.get("color")).substring(1), 16);
            for (Object k : (List<Object>) r.get("titles")) realm.titleKeys.add((String) k);
            for (Object k : (List<Object>) r.get("subjects")) realm.subjectIds.add((String) k);
            realm.overlordId = (String) r.get("overlord");
            realm.relation = (String) r.get("relation");
            realm.areaBlocks = ((Number) r.get("area_blocks")).longValue();
            if (r.get("props") instanceof Map) realm.props = new LinkedHashMap<>((Map<String, Object>) r.get("props"));
            realms.put(realm.id, realm);
            realmList.add(realm);
        }
    }

    /** Переразрешает сюзеренов и строит таблицу «провинция -> государство» (побеждает самый конкретный титул). */
    private void rebuildRealmIndex() {
        realmList.clear();
        realmList.addAll(realms.values());
        for (Realm r : realmList) r.overlord = r.overlordId == null ? null : realms.get(r.overlordId);
        Map<Integer, Integer> holder = new HashMap<>();
        for (int i = 0; i < realmList.size(); i++) {
            for (String key : realmList.get(i).titleKeys) {
                Title t = byKey.get(key);
                if (t != null) holder.put(t.id, i + 1);
            }
        }
        realmOfProvince = new int[byId.length];
        for (Title p : byId) {
            if (p == null || p.tier != 0) continue;
            int cur = p.id, guard = 0;
            while (cur > 0 && cur < byId.length && byId[cur] != null && guard++ < 32) {
                Integer r = holder.get(cur);
                if (r != null) { realmOfProvince[p.id] = r; break; }
                cur = byId[cur].parent;
            }
        }
    }

    public String titlesJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "worldforge-titles/1");
        root.put("size_px", Arrays.asList((Object) w, (Object) h));
        root.put("blocks_per_pixel", bpp);
        List<Object> tl = new ArrayList<>();
        for (Tier t : tiers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", t.index);
            m.put("key", t.key);
            m.put("name", t.name);
            tl.add(m);
        }
        root.put("tiers", tl);
        List<Object> out = new ArrayList<>();
        for (Title t : byId) {
            if (t == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("key", t.key);
            m.put("name", t.name);
            m.put("tier", t.tier);
            m.put("parent", t.parent);
            m.put("color", hex(t.rgb));
            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("nx", Math.round(t.capNx * 100000) / 100000.0);
            cap.put("ny", Math.round(t.capNy * 100000) / 100000.0);
            cap.put("x", t.capX);
            cap.put("z", t.capZ);
            cap.put("province", t.capProvince);
            m.put("capital", cap);
            m.put("area_blocks", t.areaBlocks);
            m.put("area_pct_of_land", Math.round(t.areaPct * 1000) / 1000.0);
            m.put("children", t.children);
            m.put("neighbors", t.neighbors);
            m.put("props", t.props);
            out.add(m);
        }
        root.put("titles", out);
        return Json.write(root);
    }

    public String realmsJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", relFormat);
        root.put("relation_types", relationTypes);
        if (!propDefs.isEmpty()) root.put("prop_defs", propDefs);
        List<Object> out = new ArrayList<>();
        for (Realm r : realmList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("name", r.name);
            m.put("ruler", r.ruler);
            m.put("color", hex(r.rgb));
            m.put("titles", new ArrayList<Object>(r.titleKeys));
            m.put("overlord", r.overlordId);
            m.put("relation", r.relation);
            m.put("subjects", new ArrayList<Object>(r.subjectIds));
            m.put("area_blocks", r.areaBlocks);
            m.put("props", r.props);
            out.add(m);
        }
        root.put("realms", out);
        root.put("relations", new ArrayList<Object>());
        return Json.write(root);
    }

    static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ пиксели

    /** Независимая копия (для отрисовки в фоновом потоке). */
    public TitleData copy() {
        return new TitleData(titlesJson(), realmsJson(), provinces.clone(), w, h);
    }

    short[] pixels() {
        return provinces;
    }

    /** Заменяет все пиксели (длина должна совпадать). */
    void setPixels(short[] src) {
        if (src.length != provinces.length) throw new IllegalArgumentException("размер provinces.png не совпадает");
        System.arraycopy(src, 0, provinces, 0, src.length);
    }

    public int provinceIdAtPixel(int px, int pz) {
        if (px < 0 || pz < 0 || px >= w || pz >= h) return 0;
        return provinces[pz * w + px] & 0xFFFF;
    }

    int pixelX(double bx) {
        return (int) Math.floor(bx / bpp + w * 0.5);
    }

    int pixelZ(double bz) {
        return (int) Math.floor(bz / bpp + h * 0.5);
    }

    /** Провинция в пикселе карты (null, если там нет суши). */
    Title provinceAtPixel(int px, int pz) {
        px = px < 0 ? 0 : (px >= w ? w - 1 : px);
        pz = pz < 0 ? 0 : (pz >= h ? h - 1 : pz);
        return get(provinces[pz * w + px] & 0xFFFF);
    }

    // ------------------------------------------------------------------ пересчёт

    /** Пересчитывает площади, столицы, соседей, списки детей и площади государств по текущим пикселям и связям. */
    public void recompute() {
        int n = byId.length;
        long[] cnt = new long[n], sx = new long[n], sz = new long[n];
        Set<Long> pairs = new HashSet<>();
        long land = 0;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int a = provinces[z * w + x] & 0xFFFF;
                if (a == 0) continue;
                land++;
                if (a < n) { cnt[a]++; sx[a] += x; sz[a] += z; }
                if (x + 1 < w) addPair(pairs, a, provinces[z * w + x + 1] & 0xFFFF);
                if (z + 1 < h) addPair(pairs, a, provinces[(z + 1) * w + x] & 0xFFFF);
            }
        }
        // столицы провинций: оставляем, если пиксель ещё принадлежит провинции, иначе берём ближайший к центру масс
        double[] bestD = new double[n];
        int[] bestPx = new int[n];
        Arrays.fill(bestD, Double.MAX_VALUE);
        boolean[] need = new boolean[n];
        boolean any = false;
        for (Title t : byId) {
            if (t == null || t.tier != 0 || cnt[t.id] == 0) continue;
            int px = (int) Math.floor(t.capNx * w), pz = (int) Math.floor(t.capNy * h);
            if (provinceIdAtPixel(px, pz) != t.id) { need[t.id] = true; any = true; }
        }
        if (any) {
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int a = provinces[z * w + x] & 0xFFFF;
                    if (a == 0 || a >= n || !need[a]) continue;
                    double dx = x - (double) sx[a] / cnt[a], dz = z - (double) sz[a] / cnt[a], d = dx * dx + dz * dz;
                    if (d < bestD[a]) { bestD[a] = d; bestPx[a] = z * w + x; }
                }
            }
        }
        for (Title t : byId) {
            if (t == null || t.tier != 0) continue;
            if (need[t.id]) setCapitalPixel(t, bestPx[t.id] % w, bestPx[t.id] / w);
            t.capProvince = t.id;
        }
        // дети
        Map<Integer, List<Integer>> kids = new HashMap<>();
        for (Title t : byId) if (t != null && t.parent > 0) kids.computeIfAbsent(t.parent, k -> new ArrayList<>()).add(t.id);
        for (Title t : byId) {
            if (t == null) continue;
            List<Integer> l = kids.getOrDefault(t.id, List.of());
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = l.get(i);
            Arrays.sort(a);
            t.children = a;
        }
        // титулы выше провинций: от нижних уровней к верхним
        long[] total = cnt.clone();
        for (int tier = 1; tier < tiers.size(); tier++) {
            for (Title t : byId) {
                if (t == null || t.tier != tier) continue;
                long best = -1;
                int bestChild = 0;
                boolean keep = false;
                long sum = 0;
                for (int c : t.children) {
                    sum += total[c];
                    if (total[c] > best) { best = total[c]; bestChild = c; }
                    if (c == t.capProvince) keep = true;
                }
                total[t.id] = sum;
                if (!keep && bestChild != 0) t.capProvince = bestChild;
                Title cc = get(t.capProvince);
                if (cc != null) {
                    t.capX = cc.capX; t.capZ = cc.capZ; t.capNx = cc.capNx; t.capNy = cc.capNy;
                }
            }
        }
        for (Title t : byId) {
            if (t == null) continue;
            t.areaBlocks = (long) (total[t.id] * bpp * bpp);
            t.areaPct = land == 0 ? 0 : 100.0 * total[t.id] / land;
        }
        // соседи
        Map<Integer, Set<Integer>> nb = new HashMap<>();
        for (long pr : pairs) {
            Title a = get((int) (pr >>> 32)), b = get((int) (pr & 0xFFFFFFFFL));
            if (a == null || b == null) continue;
            for (int tier = 0; tier < tiers.size(); tier++) {
                Title ta = ancestor(a, tier), tb = ancestor(b, tier);
                if (ta == null || tb == null || ta.id == tb.id) continue;
                nb.computeIfAbsent(ta.id, k -> new HashSet<>()).add(tb.id);
                nb.computeIfAbsent(tb.id, k -> new HashSet<>()).add(ta.id);
            }
        }
        for (Title t : byId) {
            if (t == null) continue;
            Set<Integer> s = nb.getOrDefault(t.id, Set.of());
            int[] a = new int[s.size()];
            int i = 0;
            for (int v : s) a[i++] = v;
            Arrays.sort(a);
            t.neighbors = a;
        }
        // государства
        for (Realm r : realmList) {
            r.subjectIds.clear();
            r.areaBlocks = 0;
        }
        for (Realm r : realmList) {
            if (r.overlordId != null && realms.containsKey(r.overlordId)) realms.get(r.overlordId).subjectIds.add(r.id);
        }
        rebuildRealmIndex();
        long[] realmPx = new long[realmList.size() + 1];
        for (Title t : byId) {
            if (t == null || t.tier != 0) continue;
            realmPx[realmOfProvince[t.id]] += cnt[t.id];
        }
        for (int i = 0; i < realmList.size(); i++) realmList.get(i).areaBlocks = (long) (realmPx[i + 1] * bpp * bpp);
    }

    private static void addPair(Set<Long> pairs, int a, int b) {
        if (b == 0 || a == b) return;
        int lo = Math.min(a, b), hi = Math.max(a, b);
        pairs.add(((long) lo << 32) | hi);
    }

    void setCapitalPixel(Title t, int px, int pz) {
        t.capNx = (px + 0.5) / w;
        t.capNy = (pz + 0.5) / h;
        t.capX = (int) Math.round((px + 0.5 - w * 0.5) * bpp);
        t.capZ = (int) Math.round((pz + 0.5 - h * 0.5) * bpp);
    }

    // ------------------------------------------------------------------ изменение структуры (используется TitleEditor)

    int maxId() {
        return byId.length - 1;
    }

    Title addTitle(Title t) {
        if (t.id >= byId.length) byId = Arrays.copyOf(byId, t.id + 1);
        byId[t.id] = t;
        byKey.put(t.key, t);
        return t;
    }

    void removeTitle(int id) {
        Title t = get(id);
        if (t == null) return;
        byKey.remove(t.key);
        byId[id] = null;
    }

    void addRealm(Realm r) {
        realms.put(r.id, r);
        rebuildRealmIndex();
    }

    /**
     * Adds a runtime-only realm projection to the same canonical map registry used by
     * diplomacy, courts and political ledgers. The caller owns persistence of its source model.
     */
    public void registerRealmProjection(Realm realm) {
        if (realm == null || realm.id == null || realm.id.isBlank())
            throw new IllegalArgumentException("A projected realm needs a stable id");
        Realm existing = realms.get(realm.id);
        if (existing != null && existing != realm
                && !Boolean.TRUE.equals(existing.props.get("emergence.virtual")))
            throw new IllegalStateException("Projected realm collides with authored realm " + realm.id);
        realms.put(realm.id, realm);
        rebuildRealmIndex();
        recompute();
    }

    /** Replaces the client-side set of runtime projections in one index rebuild. */
    public void replaceRealmProjections(java.util.Collection<Realm> projections) {
        Map<String, Realm> incoming = new LinkedHashMap<>();
        if (projections != null) for (Realm realm : projections) {
            if (realm == null || realm.id == null || realm.id.isBlank()
                    || !Boolean.TRUE.equals(realm.props.get("emergence.virtual")))
                throw new IllegalArgumentException("Invalid runtime realm projection");
            Realm existing = realms.get(realm.id);
            if (existing != null && !Boolean.TRUE.equals(existing.props.get("emergence.virtual")))
                throw new IllegalStateException("Runtime projection collides with authored realm " + realm.id);
            incoming.put(realm.id, realm);
        }
        realms.entrySet().removeIf(entry -> Boolean.TRUE.equals(entry.getValue().props.get("emergence.virtual"))
                && !incoming.containsKey(entry.getKey()));
        realms.putAll(incoming);
        rebuildRealmIndex();
        recompute();
    }

    /** Removes only an autonomous projection; authored map realms are never removed here. */
    public boolean removeRealmProjection(String id) {
        Realm existing = realms.get(id);
        if (existing == null || !Boolean.TRUE.equals(existing.props.get("emergence.virtual"))) return false;
        realms.remove(id);
        rebuildRealmIndex();
        recompute();
        return true;
    }

    void removeRealm(String id) {
        realms.remove(id);
        rebuildRealmIndex();
    }

    /** Вызывается после изменения связей, не трогающих пиксели (владельцы титулов, сюзерены). */
    void relink() {
        rebuildRealmIndex();
    }

    // ------------------------------------------------------------------ запросы

    /** Провинция в пикселе (для клиентского кода вне пакета). */
    public Title provinceAtPixelPublic(int px, int pz) {
        int id = provinceIdAtPixel(px, pz);
        return id == 0 ? null : get(id);
    }

    public Title get(int id) {
        return id > 0 && id < byId.length ? byId[id] : null;
    }

    public Title byKey(String key) {
        return byKey.get(key);
    }

    public List<Title> allTitles() {
        List<Title> out = new ArrayList<>();
        for (Title t : byId) if (t != null) out.add(t);
        return out;
    }

    /** Титул заданного уровня, которому принадлежит титул t (null, если цепочка не доходит). */
    public Title ancestor(Title t, int tierIndex) {
        Title cur = t;
        int guard = 0;
        while (cur != null && cur.tier < tierIndex && guard++ < 16) cur = get(cur.parent);
        return cur != null && cur.tier == tierIndex ? cur : null;
    }

    public List<Title> chain(Title province) {
        List<Title> out = new ArrayList<>();
        for (Title cur = province; cur != null && out.size() < 16; cur = get(cur.parent)) out.add(cur);
        return out;
    }

    /** Государство, владеющее провинцией (null, если ничьё). */
    public Realm realmOf(Title province) {
        if (province == null || province.id >= realmOfProvince.length) return null;
        int r = realmOfProvince[province.id];
        return r == 0 ? null : realmList.get(r - 1);
    }

    /** Таблица «id титула-провинции -> метка» для карты уровня tierIndex (метка = id предка). */
    int[] provinceLut(int tierIndex) {
        int[] lut = new int[byId.length];
        for (Title t : byId) {
            if (t == null || t.tier != 0) continue;
            Title a = ancestor(t, tierIndex);
            lut[t.id] = a == null ? 0 : a.id;
        }
        return lut;
    }

    /** Таблица «id провинции -> номер государства (1-based)». */
    int[] realmLut() {
        return realmOfProvince;
    }

    /** Цепочка государств от данного до верховного сюзерена. */
    public List<Realm> overlordChain(Realm r) {
        List<Realm> out = new ArrayList<>();
        for (Realm cur = r; cur != null && out.size() < 16; cur = cur.overlord) out.add(cur);
        return out;
    }

    public Realm findRealm(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT);
        for (Realm r : realmList) if (r.id.equalsIgnoreCase(s) || r.name.toLowerCase(Locale.ROOT).equals(s)) return r;
        for (Realm r : realmList) if (r.name.toLowerCase(Locale.ROOT).contains(s)) return r;
        return null;
    }

    public Title findTitle(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT);
        Title best = null;
        for (Title t : byId) {
            if (t == null) continue;
            if (t.key.equalsIgnoreCase(s) || t.name.toLowerCase(Locale.ROOT).equals(s)) return t;
            if (best == null && t.name.toLowerCase(Locale.ROOT).contains(s)) best = t;
        }
        return best;
    }

    public List<Realm> realmList() {
        return realmList;
    }

    public String relationLabel(String relation) {
        Object o = relationTypes.get(relation) == null ? null : relationTypes.get(relation).get("label");
        return o == null ? String.valueOf(relation) : (String) o;
    }
}
