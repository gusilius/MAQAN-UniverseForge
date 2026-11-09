package dev.worldforge.tests;

import dev.worldforge.mod.*;
import dev.worldforge.mod.api.*;

import java.util.*;

public class LedgerTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        TitleEditor ed = new TitleEditor(d);
        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : d.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty()) { free.add(r.id); r.props.remove("allies"); r.props.remove("enemies"); }
        String A = free.get(0), B = free.get(1), C = free.get(2), D = free.get(3), E = free.get(4);
        ed.setOverlord(D, A, "vassal");
        ed.setOverlord(E, D, "vassal");
        ed.setRealmProp(B, "allies", C);
        ed.setRealmProp(A, "enemies", B);
        int i = 0;
        for (String id : free) { ed.setRealmProp(id, "war.score", String.valueOf((i % 5) * 10 - 20)); ed.setRealmProp(id, "mood", i % 3 == 0 ? "calm" : "angry"); i++; }
        ed.setRealmProp(A, "war.exhaustion", "2.5");        // только у одного: остальные пустые
        d.recompute();

        Ledger L = Ledger.build(d, A);
        ok(L.rows.size() == d.realmList().size(), "строка на каждое государство: " + L.rows.size());
        ok(L.rows.stream().filter(r -> r.viewer).count() == 1 && L.rows.stream().filter(r -> r.viewer).findFirst().get().id.equals(A), "зритель отмечен");

        // колонки по вкладкам
        Set<String> ov = new HashSet<>(), ti = new HashSet<>(), di = new HashSet<>(), pr = new HashSet<>();
        for (Ledger.Col c : L.columns(0)) ov.add(c.key);
        for (Ledger.Col c : L.columns(1)) ti.add(c.key);
        for (Ledger.Col c : L.columns(2)) di.add(c.key);
        for (Ledger.Col c : L.columns(3)) pr.add(c.key);
        ok(ov.containsAll(List.of("name", "ruler", "overlord", "area", "pct", "vassals", "titles")), "вкладка Обзор");
        boolean tiers = true;
        for (TitleData.Tier t : d.tiers) tiers &= ti.contains("tier." + t.index);
        ok(tiers && ti.contains("name"), "вкладка Титулы: колонка на каждый уровень (" + d.tiers.size() + ")");
        ok(di.containsAll(List.of("neighbours", "allies", "enemies", "rel", "vassals", "vassalsAll")), "вкладка Дипломатия");
        ok(pr.contains("prop.war.score") && pr.contains("prop.war.exhaustion"), "числовые свойства стали колонками");
        ok(!pr.contains("prop.mood") && !pr.contains("prop.allies") && !pr.contains("prop.enemies"), "нечисловые свойства и allies/enemies не колонки");
        ok(Ledger.build(d, null).col("rel") == null, "без зрителя нет колонки отношения");

        // значения
        Ledger.Row ra = row(L, A), rd = row(L, D), re = row(L, E), rb = row(L, B), rc = row(L, C);
        TitleData.Realm RA = d.realms.get(A);
        ok(ra.num("area") == RA.areaBlocks, "площадь совпадает с государством");
        ok(Math.abs(ra.num("pct") - 100.0 * RA.areaBlocks / L.landBlocks) < 1e-9, "процент карты от суши");
        double pctSum = 0;
        for (Ledger.Row r : L.rows) pctSum += r.num("pct");
        ok(pctSum > 99.0 && pctSum <= 100.1, "проценты государств дают ~100: " + pctSum);
        System.out.println("A subj=" + RA.subjectIds + " D subj=" + d.realms.get(D).subjectIds + " all=" + ra.num("vassalsAll"));
        ok(ra.num("vassals") == RA.subjectIds.size() && ra.num("vassalsAll") >= 2 && ra.num("vassalsAll") >= ra.num("vassals"), "вассалы прямые и всего (транзитивно)");
        ok(d.realms.get(D).subjectIds.contains(E) && rd.num("vassalsAll") >= 1 && re.num("vassalsAll") >= re.num("vassals"), "цепочка вассалов");
        ok(rd.text("overlord").equals(RA.name) && ra.text("overlord").isEmpty() && ra.text("relation").equals("Независимое"), "сюзерен и статус");
        ok(ra.num("enemies") == 1 && rb.num("enemies") == 1, "враги считаются с обеих сторон");
        ok(rb.num("allies") == 1 && rc.num("allies") == 1 && ra.num("allies") == 0, "союзники с обеих сторон");
        Map<String, Set<String>> adj = Diplomacy.adjacency(d);
        ok(ra.num("neighbours") == adj.getOrDefault(A, Set.of()).size(), "соседи из Diplomacy.adjacency");
        ok(rb.text("rel").equals(Diplomacy.Rel.ENEMY.label) && ra.text("rel").equals(Diplomacy.Rel.SELF.label), "отношение к зрителю");
        double t0 = 0;
        for (Ledger.Row r : L.rows) t0 += r.num("tier.0");
        long prov = d.allTitles().stream().filter(t -> t.tier == 0 && d.realmOf(t) != null).count();
        ok((long) t0 == prov, "провинции по государствам в сумме = все провинции с владельцем (" + prov + ")");
        ok(ra.num("prop.war.score") != null && rb.num("prop.war.exhaustion") == null, "числа из свойств, пустые = null");
        ok(Ledger.parse("12") == 12.0 && Ledger.parse(" 3.5 ") == 3.5 && Ledger.parse("x") == null && Ledger.parse("") == null && Ledger.parse("NaN") == null, "разбор чисел");

        // сортировка
        List<Ledger.Row> v = L.view("area", false, "");
        boolean desc = true;
        for (int k = 1; k < v.size(); k++) desc &= v.get(k - 1).num("area") >= v.get(k).num("area");
        ok(desc, "сортировка по площади по убыванию");
        v = L.view("area", true, "");
        boolean up = true;
        for (int k = 1; k < v.size(); k++) up &= v.get(k - 1).num("area") <= v.get(k).num("area");
        ok(up, "сортировка по площади по возрастанию");
        v = L.view("name", true, "");
        boolean nm = true;
        for (int k = 1; k < v.size(); k++) nm &= v.get(k - 1).name.compareToIgnoreCase(v.get(k).name) <= 0;
        ok(nm, "сортировка по имени");
        v = L.view("prop.war.exhaustion", true, "");
        ok(v.get(0).id.equals(A) && v.get(1).num("prop.war.exhaustion") == null, "пустые числа в конце (по возрастанию)");
        v = L.view("prop.war.exhaustion", false, "");
        ok(v.get(0).id.equals(A) && v.get(v.size() - 1).num("prop.war.exhaustion") == null, "пустые числа в конце (по убыванию)");
        v = L.view("overlord", true, "");
        ok(!v.get(0).text("overlord").isEmpty() && v.get(v.size() - 1).text("overlord").isEmpty(), "пустой текст в конце");
        v = L.view("vassals", false, "");
        boolean stable = true;
        for (int k = 1; k < v.size(); k++) if (v.get(k - 1).num("vassals").equals(v.get(k).num("vassals"))) stable &= v.get(k - 1).name.compareToIgnoreCase(v.get(k).name) <= 0;
        ok(stable, "равные значения упорядочены по имени");
        ok(L.view("nope", true, "").size() == L.rows.size(), "неизвестная колонка не ломает");

        // фильтр
        String part = ra.name.substring(ra.name.length() - 1).toUpperCase(); // unique realm suffix in the synthetic fixture
        v = L.view("name", true, part);
        ok(v.contains(ra) && v.size() < L.rows.size() && v.stream().allMatch(r -> r.name.toUpperCase().contains(part) || r.id.toUpperCase().contains(part)), "фильтр по подстроке без учёта регистра");
        ok(L.view("name", true, "zzzz_nothing").isEmpty(), "фильтр без совпадений");
        ok(L.view("name", true, "   ").size() == L.rows.size(), "пробелы = без фильтра");

        // итоги
        List<Ledger.Col> cols = L.columns(0);
        Map<String, Double> tot = L.totals(L.rows, cols);
        double areaSum = 0;
        for (Ledger.Row r : L.rows) areaSum += r.num("area");
        ok(tot.get("area") == areaSum && tot.containsKey("titles") && tot.containsKey("pct"), "итог площади и титулов");
        ok(!tot.containsKey("name") && !tot.containsKey("vassalsAll"), "у текста и несуммируемых колонок итога нет");
        Map<String, Double> sub = L.totals(L.view("name", true, part), cols);
        ok(sub.get("area") < tot.get("area"), "итоги считаются по отфильтрованным строкам");
        ok(L.totals(List.of(), cols).get("area") == 0.0, "итоги пустого списка = 0");
        ok(L.totals(L.rows, L.columns(3)).get("prop.war.exhaustion") == 2.5, "итог свойства");

        // форматирование
        ok(Ledger.fmtNum(Ledger.Kind.INT, 1234) .equals("1 234") && Ledger.fmtNum(Ledger.Kind.INT, 12) .equals("12") && Ledger.fmtNum(Ledger.Kind.INT, 123456).equals("123 456"), "группы разрядов");
        ok(Ledger.fmtNum(Ledger.Kind.INT, 12_345_678).equals("12.35M") && Ledger.fmtNum(Ledger.Kind.INT, 3_200_000_000L).equals("3.20G"), "сжатие больших чисел");
        ok(Ledger.fmtNum(Ledger.Kind.PCT, 12.345).equals("12.3%") && Ledger.fmtNum(Ledger.Kind.PCT, 1.5).equals("1.50%"), "проценты");
        ok(Ledger.fmtNum(Ledger.Kind.NUM, 2.5).equals("2.5") && Ledger.fmtNum(Ledger.Kind.NUM, -20).equals("-20") && Ledger.fmtNum(Ledger.Kind.NUM, 0.125).equals("0.13") || Ledger.fmtNum(Ledger.Kind.NUM, 0.125).equals("0.12"), "числа свойств");
        ok(L.cell(L.col("prop.war.exhaustion"), rb).equals("-") && L.cell(L.col("overlord"), ra).equals("-"), "пустая ячейка = тире");

        // ---- журнал событий
        EventLog log = new EventLog();
        log.post("war", "Война началась", List.of(A, B), 100);
        log.post("DIPLOMACY", "Союз", List.of(B, C), 110);
        log.post("people", "Свадьба", List.of(A), 120);
        log.post("bogus", "Что-то", null, 130);
        log.post(EventLog.Category.SETTLEMENT, "Построена ферма", List.of(C), 140);
        ok(log.size() == 5 && log.all().get(0).text.equals("Война началась") && log.all().get(4).seq == 5, "события записаны по порядку");
        ok(log.all().get(3).category == EventLog.Category.SYSTEM && log.all().get(3).realms.isEmpty(), "неизвестная категория = system, realms null = пусто");
        ok(log.recent(2).get(0).text.equals("Построена ферма") && log.recent(2).size() == 2, "recent: новые первыми");
        ok(log.filter(EnumSet.of(EventLog.Category.WAR, EventLog.Category.PEOPLE), null, 0).size() == 2, "фильтр по категориям");
        ok(log.filter(null, A, 0).size() == 2 && log.filter(null, B, 0).size() == 2, "фильтр по государству");
        ok(log.filter(EnumSet.of(EventLog.Category.WAR), C, 0).isEmpty() && log.filter(EnumSet.of(EventLog.Category.DIPLOMACY), C, 0).size() == 1, "категория и государство вместе");
        ok(log.filter(EnumSet.noneOf(EventLog.Category.class), null, 3).size() == 3, "пустой набор категорий = все; limit");
        int[] got = {0};
        log.addListener(e -> got[0]++);
        log.addListener(e -> { throw new RuntimeException("плохой слушатель"); });
        log.post("war", "x", List.of(), 150);
        ok(got[0] == 1, "слушатель вызван, сбой другого не мешает");
        EventLog big = new EventLog();
        for (int k = 0; k < 730; k++) big.post("system", "e" + k, List.of(), k);
        ok(big.size() == 500 && big.all().get(0).text.equals("e230") && big.all().get(499).text.equals("e729"), "кольцевой буфер на 500: старые вытеснены");
        EventLog back = new EventLog();
        String js = log.toJson();
        back.fromJson(js);
        boolean same = back.size() == log.size();
        for (int k = 0; same && k < log.size(); k++) {
            EventLog.Event x = log.all().get(k), y = back.all().get(k);
            same = x.seq == y.seq && x.time == y.time && x.category == y.category && x.text.equals(y.text) && x.realms.equals(y.realms);
        }
        ok(same, "JSON: выгрузка и загрузка совпадают");
        back.post("war", "после", List.of(), 1);
        ok(back.all().get(back.size() - 1).seq == log.all().get(log.size() - 1).seq + 1, "номера продолжаются после загрузки");
        EventLog q = new EventLog();
        q.post("people", "Кавычки \"да\" и\nперенос, юникод: Ёж", List.of("a"), 1_700_000_000_000L);
        EventLog q2 = new EventLog();
        q2.fromJson(q.toJson());
        ok(q2.all().get(0).text.equals("Кавычки \"да\" и\nперенос, юникод: Ёж") && q2.all().get(0).time == 1_700_000_000_000L, "JSON: спецсимволы и большое время");
        EventLog bigBack = new EventLog();
        bigBack.fromJson(big.toJson());
        ok(bigBack.size() == 500, "JSON буфера на 500");

        // API
        WorldForgeApi.resetForTests();
        WorldForgeApi.postEvent("war", "Через API", List.of(A), 5);
        WorldForgeApi.postEvent("diplomacy", "Без времени", List.of());
        ok(WorldForgeApi.events().size() == 2 && WorldForgeApi.events().all().get(0).text.equals("Через API"), "WorldForgeApi.postEvent/events");
        WorldForgeApi.resetForTests();
        ok(WorldForgeApi.events().size() == 0, "resetForTests чистит журнал");

        System.out.println(fails == 0 ? "LedgerTest: всё хорошо" : "LedgerTest: ошибок " + fails);
        if (fails > 0) System.exit(1);
    }

    static Ledger.Row row(Ledger L, String id) {
        for (Ledger.Row r : L.rows) if (r.id.equals(id)) return r;
        throw new IllegalStateException(id);
    }
}
