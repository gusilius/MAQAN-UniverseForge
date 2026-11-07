package dev.worldforge.mod.api;

import dev.worldforge.mod.TitleData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Реестр государств (как «леджер» в Europa Universalis / сравнительные таблицы Stellaris и Victoria): по одной строке на государство,
 * колонки собираются из {@link TitleData}: имя, правитель, сюзерен, титулы по уровням, площадь, вассалы, соседи, союзники и враги,
 * а также каждое числовое свойство государства ({@code war.*}, свойства аддонов). Только данные и сортировка, без Minecraft.
 */
public final class Ledger {
    public enum Kind { TEXT, INT, PCT, NUM }

    public static final int T_OVERVIEW = 1, T_TITLES = 2, T_DIPLO = 4, T_PROPS = 8;
    public static final String[] TABS = {"Обзор", "Титулы", "Дипломатия", "Свойства"};

    /** Колонка: key уникален, tabs - битовая маска вкладок, sum - есть ли сумма в строке итогов. */
    public static final class Col {
        public final String key, label;
        public final Kind kind;
        public final int tabs;
        public final boolean sum;

        Col(String key, String label, Kind kind, int tabs, boolean sum) {
            this.key = key; this.label = label; this.kind = kind; this.tabs = tabs; this.sum = sum;
        }

        public boolean numeric() { return kind != Kind.TEXT; }
    }

    public static final class Row {
        public final String id, name;
        public final int rgb;
        public boolean viewer;
        /** Цвет отношения к государству зрителя (Diplomacy.Rel.rgb) или -1. */
        public int relRgb = -1;
        private final Map<String, Object> v = new LinkedHashMap<>();

        Row(String id, String name, int rgb) { this.id = id; this.name = name; this.rgb = rgb; }

        public String text(String key) { Object o = v.get(key); return o == null ? "" : o.toString(); }

        /** Число или null, если значения нет. */
        public Double num(String key) { Object o = v.get(key); return o instanceof Double ? (Double) o : null; }
    }

    public final List<Col> columns = new ArrayList<>();
    public final List<Row> rows = new ArrayList<>();
    public final long landBlocks;

    private Ledger(long landBlocks) { this.landBlocks = landBlocks; }

    // ------------------------------------------------------------------ сборка

    /** Строит реестр. viewerId - государство зрителя (подсветка и колонка «К вам»), может быть null. */
    public static Ledger build(TitleData d, String viewerId) {
        long land = 0;
        for (TitleData.Title t : d.allTitles()) if (t.tier == 0) land += t.areaBlocks;
        Ledger L = new Ledger(land);
        Map<String, Set<String>> adj = Diplomacy.adjacency(d);
        TitleData.Realm viewer = viewerId == null ? null : d.realms.get(viewerId);

        L.columns.add(new Col("name", "Государство", Kind.TEXT, 15, false));
        L.columns.add(new Col("ruler", "Правитель", Kind.TEXT, T_OVERVIEW, false));
        L.columns.add(new Col("overlord", "Сюзерен", Kind.TEXT, T_OVERVIEW | T_DIPLO, false));
        L.columns.add(new Col("relation", "Статус", Kind.TEXT, T_OVERVIEW | T_DIPLO, false));
        if (viewer != null) L.columns.add(new Col("rel", "К вам", Kind.TEXT, T_DIPLO, false));
        L.columns.add(new Col("titles", "Титулов", Kind.INT, T_OVERVIEW | T_TITLES, true));
        for (TitleData.Tier t : d.tiers) L.columns.add(new Col("tier." + t.index, t.name, Kind.INT, T_TITLES, true));
        L.columns.add(new Col("area", "Площадь, бл²", Kind.INT, T_OVERVIEW, true));
        L.columns.add(new Col("pct", "Карта, %", Kind.PCT, T_OVERVIEW, true));
        L.columns.add(new Col("vassals", "Вассалов", Kind.INT, T_OVERVIEW | T_DIPLO, true));
        L.columns.add(new Col("vassalsAll", "Всего подч.", Kind.INT, T_OVERVIEW | T_DIPLO, false));
        L.columns.add(new Col("neighbours", "Соседей", Kind.INT, T_DIPLO, false));
        L.columns.add(new Col("allies", "Союзн.", Kind.INT, T_DIPLO, false));
        L.columns.add(new Col("enemies", "Врагов", Kind.INT, T_DIPLO, false));

        // числовые свойства: ключ берём, если все заданные значения - числа
        Map<String, Boolean> numericProp = new LinkedHashMap<>();
        for (TitleData.Realm r : d.realmList())
            for (Map.Entry<String, Object> e : r.props.entrySet()) {
                if (e.getKey().equals("allies") || e.getKey().equals("enemies")) continue;
                boolean isNum = parse(e.getValue()) != null;
                numericProp.merge(e.getKey(), isNum, (a, b) -> a && b);
            }
        List<String> propKeys = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : numericProp.entrySet()) if (e.getValue()) propKeys.add(e.getKey());
        for (String k : propKeys) {
            Object nm = d.propDefs.get(k) == null ? null : d.propDefs.get(k).get("name");
            L.columns.add(new Col("prop." + k, nm == null ? k : nm.toString(), Kind.NUM, T_PROPS, true));
        }

        for (TitleData.Realm r : d.realmList()) {
            Row row = new Row(r.id, r.name, r.rgb);
            row.viewer = r.id.equals(viewerId);
            row.v.put("name", r.name);
            row.v.put("ruler", r.ruler == null ? "" : r.ruler);
            row.v.put("overlord", r.overlord == null ? "" : r.overlord.name);
            row.v.put("relation", r.overlord == null ? "Независимое" : d.relationLabel(r.relation));
            if (viewer != null) {
                Diplomacy.Rel rel = Diplomacy.relation(d, viewer, r, adj);
                row.v.put("rel", rel.label);
                row.relRgb = rel.rgb;
            }
            // титулы по уровням: различные предки провинций государства
            List<Set<Integer>> per = new ArrayList<>();
            for (int i = 0; i < d.tiers.size(); i++) per.add(new HashSet<>());
            for (TitleData.Title p : d.allTitles()) {
                if (p.tier != 0 || d.realmOf(p) != r) continue;
                for (int i = 0; i < d.tiers.size(); i++) {
                    TitleData.Title a = d.ancestor(p, i);
                    if (a != null) per.get(i).add(a.id);
                }
            }
            int total = 0;
            for (int i = 0; i < d.tiers.size(); i++) {
                row.v.put("tier." + d.tiers.get(i).index, (double) per.get(i).size());
                total += per.get(i).size();
            }
            row.v.put("titles", (double) total);
            row.v.put("area", (double) r.areaBlocks);
            row.v.put("pct", L.landBlocks == 0 ? 0.0 : 100.0 * r.areaBlocks / L.landBlocks);
            row.v.put("vassals", (double) r.subjectIds.size());
            row.v.put("vassalsAll", (double) allSubjects(d, r));
            row.v.put("neighbours", (double) adj.getOrDefault(r.id, Set.of()).size());
            Set<String> al = new HashSet<>(Diplomacy.ids(r, "allies")), en = new HashSet<>(Diplomacy.ids(r, "enemies"));
            for (TitleData.Realm o : d.realmList()) {
                if (o == r) continue;
                if (Diplomacy.ids(o, "allies").contains(r.id)) al.add(o.id);
                if (Diplomacy.ids(o, "enemies").contains(r.id)) en.add(o.id);
            }
            al.remove(r.id); en.remove(r.id);
            row.v.put("allies", (double) al.size());
            row.v.put("enemies", (double) en.size());
            for (String k : propKeys) {
                Double n = parse(r.props.get(k));
                if (n != null) row.v.put("prop." + k, n);
            }
            L.rows.add(row);
        }
        return L;
    }

    private static int allSubjects(TitleData d, TitleData.Realm r) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(r.subjectIds);
        while (!todo.isEmpty()) {
            String id = todo.remove(todo.size() - 1);
            if (id.equals(r.id) || !seen.add(id)) continue;
            TitleData.Realm s = d.realms.get(id);
            if (s != null) todo.addAll(s.subjectIds);
        }
        return seen.size();
    }

    /** Значение свойства как число или null (число, либо строка, которую можно разобрать как число). */
    public static Double parse(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty()) return null;
            try {
                double v = Double.parseDouble(s);
                return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
            } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    // ------------------------------------------------------------------ вкладки, сортировка, итоги

    public List<Col> columns(int tab) {
        int mask = 1 << Math.max(0, Math.min(3, tab));
        List<Col> out = new ArrayList<>();
        for (Col c : columns) if ((c.tabs & mask) != 0) out.add(c);
        return out;
    }

    public Col col(String key) {
        for (Col c : columns) if (c.key.equals(key)) return c;
        return null;
    }

    /** Строки по фильтру (подстрока имени, правителя или id без учёта регистра; пусто - все) в нужном порядке. Отсутствующие числа всегда в конце. */
    public List<Row> view(String sortKey, boolean asc, String filter) {
        List<Row> out = new ArrayList<>();
        String q = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        for (Row r : rows)
            if (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q) || r.id.toLowerCase(Locale.ROOT).contains(q)) out.add(r);
        Col c = sortKey == null ? null : col(sortKey);
        Comparator<Row> byName = Comparator.comparing((Row r) -> r.name.toLowerCase(Locale.ROOT)).thenComparing(r -> r.id);
        if (c == null) { out.sort(byName); return out; }
        Comparator<Row> cmp;
        if (c.numeric()) {
            cmp = (a, b) -> {
                Double x = a.num(c.key), y = b.num(c.key);
                if (x == null || y == null) return x == y ? 0 : x == null ? 1 : -1;   // пустые в конец при любом порядке
                return asc ? Double.compare(x, y) : Double.compare(y, x);
            };
        } else {
            cmp = (a, b) -> {
                String x = a.text(c.key).toLowerCase(Locale.ROOT), y = b.text(c.key).toLowerCase(Locale.ROOT);
                boolean ex = x.isEmpty(), ey = y.isEmpty();
                if (ex || ey) return ex == ey ? 0 : ex ? 1 : -1;
                return asc ? x.compareTo(y) : y.compareTo(x);
            };
        }
        out.sort(cmp.thenComparing(byName));
        return out;
    }

    /** Суммы по колонкам с sum=true для переданных строк; ключи без суммы отсутствуют. */
    public Map<String, Double> totals(List<Row> rs, List<Col> cols) {
        Map<String, Double> t = new LinkedHashMap<>();
        for (Col c : cols) {
            if (!c.sum) continue;
            double s = 0;
            for (Row r : rs) { Double n = r.num(c.key); if (n != null) s += n; }
            t.put(c.key, s);
        }
        return t;
    }

    // ------------------------------------------------------------------ форматирование

    public static String fmtNum(Kind k, double v) {
        switch (k) {
            case PCT: return String.format(Locale.ROOT, Math.abs(v) < 10 ? "%.2f%%" : "%.1f%%", v);
            case INT: return grouped(Math.round(v));
            default:
                if (v == Math.rint(v) && Math.abs(v) < 1e15) return grouped((long) v);
                String s = String.format(Locale.ROOT, "%.2f", v);
                return s.indexOf('.') < 0 ? s : s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
    }

    private static String grouped(long n) {
        long a = Math.abs(n);
        String sign = n < 0 ? "-" : "";
        if (a >= 1_000_000_000L) return sign + String.format(Locale.ROOT, "%.2fG", a / 1e9);
        if (a >= 1_000_000L) return sign + String.format(Locale.ROOT, "%.2fM", a / 1e6);
        String s = Long.toString(a);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return sign + b;
    }

    /** Текст ячейки; для отсутствующего числа и пустого текста - тире. */
    public String cell(Col c, Row r) {
        if (c.numeric()) { Double n = r.num(c.key); return n == null ? "-" : fmtNum(c.kind, n); }
        String s = r.text(c.key);
        return s.isEmpty() ? "-" : s;
    }
}
