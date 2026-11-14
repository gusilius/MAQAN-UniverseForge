package dev.worldforge.mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Правки политической карты: граница провинций (кисть), родители титулов, имена, цвета, столицы,
 * государства, сюзерены, типы отношений. Каждая операция атомарна и отменяется через {@link #undo()}.
 * Без зависимостей от Minecraft.
 */
public final class TitleEditor {
    public static final class EditException extends RuntimeException {
        public EditException(String msg) { super(msg); }
    }

    private static final class Snap {
        String titles, realms;
        int[] idx = new int[0];
        short[] old = new short[0];
    }

    private static final int MAX_UNDO = 40;

    private final TitleData d;
    private final Deque<Snap> undo = new ArrayDeque<>();
    private final Deque<Snap> redo = new ArrayDeque<>();
    private boolean dirty;
    private int mods;
    private int depth;
    private Snap current;

    public TitleEditor(TitleData d) {
        this.d = d;
    }

    public TitleData data() { return d; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }
    public int undoDepth() { return undo.size(); }
    /** Счётчик изменений (растёт после каждой правки и отмены). */
    public int modCount() { return mods; }

    // ------------------------------------------------------------------ транзакции

    private Snap snap() {
        Snap s = new Snap();
        s.titles = d.titlesJson();
        s.realms = d.realmsJson();
        return s;
    }

    private void restore(Snap s) {
        for (int i = 0; i < s.idx.length; i++) d.provinces[s.idx[i]] = s.old[i];
        d.loadMeta(s.titles, s.realms);
    }

    /** Выполняет несколько правок как одну (одна отмена). При ошибке всё откатывается. */
    public <T> T group(java.util.function.Supplier<T> body) {
        if (depth > 0) return body.get();
        try (dev.worldforge.mod.api.DomainMutationRevisions.Mutation mutation =
                     dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                             dev.worldforge.mod.api.DomainMutationRevisions.Domain.REALMS_POLITICS)) {
            Snap s = snap();
            current = s;
            depth++;
            boolean changed = false;
            try {
                T r = body.get();
                if (s.idx.length == 0 && s.titles.equals(d.titlesJson()) && s.realms.equals(d.realmsJson())) return r;   // ничего не изменилось
                undo.push(s);
                redo.clear();
                while (undo.size() > MAX_UNDO) undo.removeLast();
                dirty = true;
                mods++;
                mutation.commit();
                changed = true;
                return r;
            } catch (RuntimeException e) {
                restore(s);
                throw e;
            } finally {
                depth--;
                current = null;
                if (changed && depth == 0) dev.worldforge.mod.api.WorldForgeApi.fireChange(new dev.worldforge.mod.api.MapChange(d, "edit", ""));
            }
        }
    }

    public void group(Runnable body) {
        group(() -> { body.run(); return null; });
    }

    /** Снимок текущего состояния для тех же пикселей, что затрагивает запись s (чтобы можно было вернуть). */
    private Snap inverse(Snap s) {
        Snap r = snap();
        r.idx = s.idx;
        r.old = new short[s.idx.length];
        for (int i = 0; i < s.idx.length; i++) r.old[i] = d.provinces[s.idx[i]];
        return r;
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        try (dev.worldforge.mod.api.DomainMutationRevisions.Mutation mutation =
                     dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                             dev.worldforge.mod.api.DomainMutationRevisions.Domain.REALMS_POLITICS)) {
            Snap s = undo.pop();
            redo.push(inverse(s));
            restore(s);
            dirty = true;
            mods++;
            mutation.commit();
        }
        dev.worldforge.mod.api.WorldForgeApi.fireChange(new dev.worldforge.mod.api.MapChange(d, "edit", "undo"));
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        try (dev.worldforge.mod.api.DomainMutationRevisions.Mutation mutation =
                     dev.worldforge.mod.api.WorldForgeApi.revisions().begin(
                             dev.worldforge.mod.api.DomainMutationRevisions.Domain.REALMS_POLITICS)) {
            Snap s = redo.pop();
            undo.push(inverse(s));
            restore(s);
            dirty = true;
            mods++;
            mutation.commit();
        }
        dev.worldforge.mod.api.WorldForgeApi.fireChange(new dev.worldforge.mod.api.MapChange(d, "edit", "redo"));
        return true;
    }

    public int redoDepth() { return redo.size(); }

    // ------------------------------------------------------------------ вспомогательное

    private TitleData.Title title(int id) {
        TitleData.Title t = d.get(id);
        if (t == null) throw new EditException("Нет титула с номером " + id);
        return t;
    }

    private TitleData.Realm realm(String id) {
        TitleData.Realm r = d.realms.get(id);
        if (r == null) throw new EditException("Нет государства '" + id + "'");
        return r;
    }

    private static int rgbOf(int rgb) {
        return rgb & 0xFFFFFF;
    }

    private boolean isDescendant(TitleData.Title t, int ancestorId) {
        for (TitleData.Title c = t; c != null; c = d.get(c.parent)) if (c.id == ancestorId) return true;
        return false;
    }

    // ------------------------------------------------------------------ граница

    /** Кисть: закрашивает сушу в круге радиусом radiusPx вокруг пикселя (cx, cz) провинцией provinceId. Возвращает число пикселей. */
    public int paint(int cx, int cz, double radiusPx, int provinceId) {
        TitleData.Title target = title(provinceId);
        if (target.tier != 0) throw new EditException("Кисть красит только провинции (уровень 0), а " + target.name + " выше");
        return group(() -> {
            int r = (int) Math.ceil(radiusPx);
            List<int[]> changed = new ArrayList<>();
            double r2 = radiusPx * radiusPx;
            for (int z = Math.max(0, cz - r); z <= Math.min(d.h - 1, cz + r); z++) {
                for (int x = Math.max(0, cx - r); x <= Math.min(d.w - 1, cx + r); x++) {
                    double dx = x - cx, dz = z - cz;
                    if (dx * dx + dz * dz > r2) continue;
                    int i = z * d.w + x, cur = d.provinces[i] & 0xFFFF;
                    if (cur == 0 || cur == provinceId) continue;
                    changed.add(new int[]{i, cur});
                }
            }
            if (changed.isEmpty()) return 0;
            Snap s = current;
            int base = s.idx.length;
            s.idx = Arrays.copyOf(s.idx, base + changed.size());
            s.old = Arrays.copyOf(s.old, base + changed.size());
            for (int k = 0; k < changed.size(); k++) {
                int[] c = changed.get(k);
                s.idx[base + k] = c[0];
                s.old[base + k] = (short) c[1];
                d.provinces[c[0]] = (short) provinceId;
            }
            d.recompute();
            return changed.size();
        });
    }

    /**
     * Сглаживание границ: в круге радиусом radiusPx каждый пиксель суши переходит к провинции, которая преобладает среди его соседей
     * (убирает «зубцы» и одиночные пиксели). Возвращает число изменённых пикселей.
     */
    public int smooth(int cx, int cz, double radiusPx) {
        return group(() -> {
            int r = (int) Math.ceil(radiusPx);
            double r2 = radiusPx * radiusPx;
            List<int[]> changes = new ArrayList<>();
            for (int z = Math.max(1, cz - r); z <= Math.min(d.h - 2, cz + r); z++) {
                for (int x = Math.max(1, cx - r); x <= Math.min(d.w - 2, cx + r); x++) {
                    double dx = x - cx, dz = z - cz;
                    if (dx * dx + dz * dz > r2) continue;
                    int cur = d.provinces[z * d.w + x] & 0xFFFF;
                    if (cur == 0) continue;
                    int best = cur, bc = 0, curCount = 0;
                    int[] ids = new int[8], cnt = new int[8];
                    int n = 0;
                    for (int oz = -1; oz <= 1; oz++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            if (ox == 0 && oz == 0) continue;
                            int v = d.provinces[(z + oz) * d.w + x + ox] & 0xFFFF;
                            if (v == cur) { curCount++; continue; }
                            if (v == 0) continue;
                            int k = 0;
                            while (k < n && ids[k] != v) k++;
                            if (k == n) { ids[n] = v; cnt[n] = 0; n++; }
                            cnt[k]++;
                        }
                    }
                    for (int k = 0; k < n; k++) if (cnt[k] > bc) { bc = cnt[k]; best = ids[k]; }
                    if (best != cur && bc >= 5 && bc > curCount) changes.add(new int[]{z * d.w + x, cur, best});
                }
            }
            if (changes.isEmpty()) return 0;
            Snap s = current;
            int base = s.idx.length;
            s.idx = Arrays.copyOf(s.idx, base + changes.size());
            s.old = Arrays.copyOf(s.old, base + changes.size());
            for (int k = 0; k < changes.size(); k++) {
                int[] c = changes.get(k);
                s.idx[base + k] = c[0];
                s.old[base + k] = (short) c[1];
                d.provinces[c[0]] = (short) c[2];
            }
            d.recompute();
            return changes.size();
        });
    }

    public int smoothAtBlock(double bx, double bz, double radiusBlocks) {
        return smooth(d.pixelX(bx), d.pixelZ(bz), Math.max(2.0, radiusBlocks / d.bpp));
    }

    /**
     * Инструмент «назначить»: провинция в блоке (bx, bz) вместе со своим ближайшим дочерним титулом под tierIndex
     * переходит под титул newParentId (он должен быть уровня tierIndex). Возвращает перенесённый титул.
     */
    public TitleData.Title assignAtBlock(double bx, double bz, int newParentId) {
        TitleData.Title parent = title(newParentId);
        TitleData.Title prov = d.get(d.provinceIdAtPixel(d.pixelX(bx), d.pixelZ(bz)));
        if (prov == null) throw new EditException("Здесь нет провинции");
        if (parent.tier == 0) throw new EditException("Провинции нельзя вкладывать друг в друга");
        TitleData.Title child = d.ancestor(prov, parent.tier - 1);
        if (child == null) throw new EditException("У " + prov.name + " нет титула уровня " + d.tiers.get(parent.tier - 1).name);
        if (child.parent == parent.id) return child;
        setParent(child.id, parent.id);
        return child;
    }

    /** То же по координатам блоков мира. */
    public int paintAtBlock(double bx, double bz, double radiusBlocks, int provinceId) {
        return paint(d.pixelX(bx), d.pixelZ(bz), Math.max(1.0, radiusBlocks / d.bpp), provinceId);
    }

    // ------------------------------------------------------------------ титулы

    public void setParent(int childId, int newParentId) {
        TitleData.Title c = title(childId);
        group(() -> {
            if (newParentId != 0) {
                TitleData.Title p = title(newParentId);
                if (p.tier <= c.tier) throw new EditException(p.name + " (" + d.tiers.get(p.tier).name + ") не может быть выше, чем " + c.name);
                if (isDescendant(p, c.id)) throw new EditException("Цикл: " + p.name + " уже внутри " + c.name);
            }
            c.parent = newParentId;
            d.recompute();
        });
    }

    public void rename(int id, String name) {
        if (name == null || name.isBlank()) throw new EditException("Пустое имя");
        TitleData.Title t = title(id);
        group(() -> { t.name = name.trim(); });
    }

    public void recolor(int id, int rgb) {
        TitleData.Title t = title(id);
        group(() -> { t.rgb = rgbOf(rgb); });
    }

    /** Столица титула: блок (bx, bz) должен лежать внутри титула. Для провинции это точка, для выше это дочерний титул. */
    public void setCapitalAtBlock(int id, double bx, double bz) {
        TitleData.Title t = title(id);
        int px = d.pixelX(bx), pz = d.pixelZ(bz);
        TitleData.Title prov = d.get(d.provinceIdAtPixel(px, pz));
        if (prov == null || !isDescendant(prov, id)) throw new EditException("Эта точка не внутри " + t.name);
        group(() -> {
            if (t.tier == 0) {
                d.setCapitalPixel(t, px, pz);
            } else {
                TitleData.Title child = prov;
                while (child.parent != t.id) child = title(child.parent);
                t.capProvince = child.id;
            }
            d.recompute();
        });
    }

    private static final String[] PREFIX = {"b_", "c_", "d_", "k_", "e_"};

    public TitleData.Title createTitle(int tier, String name, int parentId, int rgb) {
        if (tier < 0 || tier >= d.tiers.size()) throw new EditException("Нет уровня " + tier);
        if (name == null || name.isBlank()) throw new EditException("Пустое имя");
        return group(() -> {
            TitleData.Title t = new TitleData.Title();
            t.id = d.maxId() + 1;
            t.tier = tier;
            t.name = name.trim();
            t.key = Slug.unique(tier < PREFIX.length ? PREFIX[tier] : "t_", name, k -> d.byKey(k) != null);
            t.rgb = rgbOf(rgb);
            t.capProvince = tier == 0 ? t.id : 0;
            t.parent = 0;
            d.addTitle(t);
            if (parentId != 0) setParent(t.id, parentId);
            d.recompute();
            return t;
        });
    }

    public void deleteTitle(int id) {
        TitleData.Title t = title(id);
        group(() -> {
            if (t.tier == 0) {
                // пиксели отдаём соседу с самой длинной общей границей
                Map<Integer, Integer> border = new HashMap<>();
                boolean has = false;
                for (int z = 0; z < d.h; z++) {
                    for (int x = 0; x < d.w; x++) {
                        if ((d.provinces[z * d.w + x] & 0xFFFF) != id) continue;
                        has = true;
                        int[][] nbs = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
                        for (int[] n : nbs) {
                            int v = d.provinceIdAtPixel(n[0], n[1]);
                            if (v != 0 && v != id) border.merge(v, 1, Integer::sum);
                        }
                    }
                }
                if (has) {
                    int best = 0, bc = -1;
                    for (Map.Entry<Integer, Integer> e : border.entrySet()) {
                        if (e.getValue() > bc) { bc = e.getValue(); best = e.getKey(); }
                    }
                    if (best == 0) throw new EditException("У " + t.name + " нет соседей, некому отдать землю");
                    Snap s = current;
                    List<Integer> idx = new ArrayList<>();
                    for (int i = 0; i < d.provinces.length; i++) if ((d.provinces[i] & 0xFFFF) == id) idx.add(i);
                    int base = s.idx.length;
                    s.idx = Arrays.copyOf(s.idx, base + idx.size());
                    s.old = Arrays.copyOf(s.old, base + idx.size());
                    for (int k = 0; k < idx.size(); k++) {
                        s.idx[base + k] = idx.get(k);
                        s.old[base + k] = (short) id;
                        d.provinces[idx.get(k)] = (short) best;
                    }
                }
            }
            for (int c : t.children) {
                TitleData.Title ch = d.get(c);
                if (ch != null) ch.parent = t.parent != 0 && d.get(t.parent).tier > ch.tier ? t.parent : 0;
            }
            for (TitleData.Realm r : d.realmList()) r.titleKeys.remove(t.key);
            d.removeTitle(id);
            d.recompute();
        });
    }

    public void setProp(int id, String key, Object value) {
        TitleData.Title t = title(id);
        group(() -> {
            if (value == null) t.props.remove(key); else t.props.put(key, value);
        });
    }

    // ------------------------------------------------------------------ государства

    public TitleData.Realm createRealm(String name, int rgb, String... titleKeys) {
        if (name == null || name.isBlank()) throw new EditException("Пустое имя");
        return group(() -> {
            TitleData.Realm r = new TitleData.Realm();
            r.name = name.trim();
            r.id = Slug.unique("", name, k -> d.realms.containsKey(k));
            r.ruler = "";
            r.rgb = rgbOf(rgb);
            d.addRealm(r);
            for (String k : titleKeys) {
                TitleData.Title t = d.byKey(k);
                if (t == null) throw new EditException("Нет титула '" + k + "'");
                give(r.id, t.id);
            }
            d.recompute();
            return r;
        });
    }

    public void deleteRealm(String id) {
        TitleData.Realm r = realm(id);
        group(() -> {
            for (TitleData.Realm o : d.realmList()) {
                if (id.equals(o.overlordId)) { o.overlordId = null; o.relation = null; }
            }
            d.removeRealm(r.id);
            d.recompute();
        });
    }

    public void setRealmName(String id, String name) {
        TitleData.Realm r = realm(id);
        if (name == null || name.isBlank()) throw new EditException("Пустое имя");
        group(() -> { r.name = name.trim(); });
    }

    public void setRuler(String id, String ruler) {
        TitleData.Realm r = realm(id);
        group(() -> { r.ruler = ruler == null ? "" : ruler; });
    }

    public void recolorRealm(String id, int rgb) {
        TitleData.Realm r = realm(id);
        group(() -> { r.rgb = rgbOf(rgb); });
    }

    /** Передаёт титул государству (у прежнего владельца титул отбирается). */
    public void give(String realmId, int titleId) {
        TitleData.Realm r = realm(realmId);
        TitleData.Title t = title(titleId);
        group(() -> {
            for (TitleData.Realm o : d.realmList()) o.titleKeys.remove(t.key);
            r.titleKeys.add(t.key);
            d.recompute();
        });
    }

    public void take(String realmId, int titleId) {
        TitleData.Realm r = realm(realmId);
        TitleData.Title t = title(titleId);
        if (!r.titleKeys.contains(t.key)) throw new EditException(r.name + " не владеет " + t.name);
        group(() -> {
            r.titleKeys.remove(t.key);
            d.recompute();
        });
    }

    /** Сюзерен (overlordId = null снимает подчинение). Тип отношения должен существовать в relation_types. */
    public void setOverlord(String id, String overlordId, String relation) {
        TitleData.Realm r = realm(id);
        group(() -> {
            if (overlordId == null) {
                r.overlordId = null;
                r.relation = null;
            } else {
                TitleData.Realm o = realm(overlordId);
                if (o == r) throw new EditException("Государство не может быть своим сюзереном");
                for (TitleData.Realm c = o; c != null; c = c.overlordId == null ? null : d.realms.get(c.overlordId)) {
                    if (c == r) throw new EditException("Цикл: " + o.name + " уже подчинён " + r.name);
                }
                String rel = relation == null ? (r.relation == null ? "vassal" : r.relation) : relation;
                if (!d.relationTypes.containsKey(rel)) throw new EditException("Нет типа отношений '" + rel + "'. Есть: " + d.relationTypes.keySet());
                r.overlordId = overlordId;
                r.relation = rel;
            }
            d.recompute();
        });
    }

    public void setRelation(String id, String relation) {
        TitleData.Realm r = realm(id);
        if (r.overlordId == null) throw new EditException(r.name + " ни от кого не зависит");
        setOverlord(id, r.overlordId, relation);
    }

    /** Параметр типа отношений: tribute, levy (числа), can_declare_war (true/false), label, pattern. */
    public void setRelationParam(String relation, String param, Object value) {
        Map<String, Object> m = d.relationTypes.get(relation);
        if (m == null) throw new EditException("Нет типа отношений '" + relation + "'");
        group(() -> { m.put(param, value); });
    }



    /** Addon-owned projection into canonical map title data; persisted with the map overlay. */
    public boolean setDerivedTitleProp(int id, String key, Object value) {
        TitleData.Title title = title(id);
        Object old = title.props.get(key);
        if (java.util.Objects.equals(old, value)) return false;
        if (value == null) title.props.remove(key); else title.props.put(key, value);
        dirty = true;
        return true;
    }

    public boolean setDerivedProp(String id, String key, Object value) {
        TitleData.Realm r = realm(id);
        Object old = r.props.get(key);
        if (java.util.Objects.equals(old, value)) return false;
        if (value == null) r.props.remove(key); else r.props.put(key, value);
        dirty = true;
        return true;
    }

    public void setRealmProp(String id, String key, Object value) {
        TitleData.Realm r = realm(id);
        group(() -> {
            if (value == null) r.props.remove(key); else r.props.put(key, value);
        });
    }
}
