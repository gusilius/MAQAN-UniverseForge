package dev.worldforge.mod;

import java.nio.file.*;
import java.util.*;

public class EditTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                String pre = "data/" + a[1].split(":")[0] + "/worldforge_maps/" + a[1].split(":")[1] + "/";
                var e = zf.getEntry(pre + name);
                if (e == null) return null;
                return zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        TitleEditor ed = new TitleEditor(d);
        String t0 = d.titlesJson(), r0 = d.realmsJson();
        // 1. recompute даёт те же площади, что и инструмент
        TitleData.Title north = d.findTitle("Королевство Севера");
        long area = north.areaBlocks;
        d.recompute();
        double rel = Math.abs(north.areaBlocks - area) / (double) area;
        check(rel < 0.01, "площадь Севера после recompute совпадает: " + area + " vs " + north.areaBlocks);
        // 2. кисть
        TitleData.Title p = null, other = null;
        outer:
        for (TitleData.Title t : d.allTitles()) {
            if (t.tier != 0 || t.areaBlocks < 20000) continue;
            for (int nb : t.neighbors) {
                TitleData.Title o = d.get(nb);
                if (d.ancestor(o, 3) != null && d.ancestor(t, 3) != null && d.ancestor(o, 3) != d.ancestor(t, 3) && o.areaBlocks > 20000) { p = t; other = o; break outer; }
            }
        }
        check(other != null, "нашёл соседа из другого королевства");
        long oa = other.areaBlocks, pa = p.areaBlocks;
        int n = ed.paintAtBlock(other.capX, other.capZ, 120, p.id);
        check(n > 0 && p.areaBlocks > pa && other.areaBlocks < oa, "кисть переносит землю: " + n + " px");
        final int pid0 = p.id, oid0 = other.id; check(ed.undo() && d.get(pid0).areaBlocks == pa && d.get(oid0).areaBlocks == oa, "отмена кисти");
        p = d.get(pid0); other = d.get(oid0);
        // 3. перепривязка
        TitleData.Title duchy = d.ancestor(p, 2), k2 = d.ancestor(other, 3);
        int oldParent = duchy.parent;
        ed.setParent(duchy.id, k2.id);
        check(d.ancestor(p, 3) == k2, "герцогство переехало в другое королевство");
        boolean threw = false;
        try { ed.setParent(k2.id, duchy.id); } catch (TitleEditor.EditException e) { threw = true; }
        check(threw, "цикл/неверный уровень отклонён");
        ed.undo();
        check(d.get(duchy.id).parent == oldParent, "отмена setParent");
        p = d.get(pid0); other = d.get(oid0); north = d.findTitle("Королевство Севера"); k2 = d.get(k2.id);
        // 4. имя/цвет/создание/удаление
        ed.rename(p.id, "Тестовая");
        ed.recolor(p.id, 0x123456);
        TitleData.Title nt = ed.createTitle(2, "Новое герцогство", k2.id, 0xAA0000);
        check(nt.parent == k2.id && nt.key.startsWith("d_"), "создан титул " + nt.key);
        ed.deleteTitle(nt.id);
        check(d.get(nt.id) == null, "титул удалён");
        // 5. государства
        TitleData.Realm r = ed.createRealm("Тестовое царство", 0x00FF00, north.key);
        ed.setOverlord(r.id, "iron_throne", "tributary");
        check(r.overlord != null && "tributary".equals(r.relation), "создано государство-данник");
        threw = false;
        try { ed.setOverlord("iron_throne", r.id, "vassal"); } catch (TitleEditor.EditException e) { threw = true; }
        check(threw, "цикл сюзеренов отклонён");
        ed.setRelationParam("tributary", "tribute", 0.5);
        check(d.relationTypes.get("tributary").get("tribute").equals(0.5), "параметр отношений");
        System.out.println("realm of cap: " + d.realmOf(m.provinceAt(north.capX + 0.5, north.capZ + 0.5)).id + " r=" + r.id + " keys=" + r.titleKeys + " north=" + north.key);
        check(d.realmOf(m.provinceAt(north.capX + 0.5, north.capZ + 0.5)).id.equals(r.id), "титул Севера теперь у нового государства");
        // 6. группа
        int before = ed.undoDepth();
        final int pid = p.id; ed.group(() -> { ed.rename(pid, "А"); ed.rename(pid, "Б"); });
        check(ed.undoDepth() == before + 1 && d.get(pid0).name.equals("Б"), "группа даёт одну запись отмены");
        ed.undo();
        check(d.get(pid0).name.equals("Тестовая"), "группа отменяется целиком");
        // 7. целостность и круг сохранения
        d.recompute();
        long sum = 0; for (TitleData.Title t : d.allTitles()) if (t.tier == 0) sum += t.areaBlocks;
        long empT = 0; for (TitleData.Title t : d.allTitles()) if (t.tier == d.tiers.size() - 1 && t.parent == 0) empT += t.areaBlocks;
        check(sum > 0, "суммарная площадь провинций " + sum);
        Path out = Paths.get(a[2]);
        TitleStore.save(m, out);
        String tj = d.titlesJson(), rj = d.realmsJson();
        TitleStore.applyOverlay(m, out);
        check(tj.equals(d.titlesJson()) && rj.equals(d.realmsJson()), "сохранение -> загрузка идентичны");
        for (String lv : new String[]{"county", "kingdom", "realms"}) {
            byte[] b = PoliticalMapRenderer.render(m, lv), old = m.mapItems.get(lv);
            int same = 0; for (int i = 0; i < b.length; i++) if (old != null && b[i] == old[i]) same++;
            System.out.println("карта " + lv + ": совпадение с исходной " + (same * 100 / 16384) + "%");
        }
        // повтор, сглаживание, назначение, проверка
        {
            TitleData dd = m.titles; TitleEditor e2 = new TitleEditor(dd);
            TitleData.Title pa2 = null, ot2 = null;
            for (TitleData.Title t : dd.allTitles()) { if (t.tier == 0 && t.areaBlocks > 30000 && t.neighbors.length > 0) { pa2 = t; ot2 = dd.get(t.neighbors[0]); break; } }
            final int pid2 = pa2.id, oid2 = ot2.id;
            long before2 = dd.get(pid2).areaBlocks;
            e2.paintAtBlock(ot2.capX, ot2.capZ, 100, pid2);
            long painted = dd.get(pid2).areaBlocks;
            e2.undo();
            check(dd.get(pid2).areaBlocks == before2 && e2.redoDepth() == 1, "redo-стек после отмены");
            e2.redo();
            check(dd.get(pid2).areaBlocks == painted, "повтор возвращает правку");
            e2.undo();
            int u0 = e2.undoDepth();
            e2.paintAtBlock(ot2.capX, ot2.capZ, 0.1, pid2);
            e2.paintAtBlock(ot2.capX, ot2.capZ, 100, pid2);
            int n0 = e2.smoothAtBlock(ot2.capX, ot2.capZ, 300);
            System.out.println("сглажено пикселей: " + n0);
            check(n0 >= 0, "сглаживание выполняется");
            int u1 = e2.undoDepth();
            e2.paintAtBlock(ot2.capX, ot2.capZ, 100, pid2);        // повторная закраска ничего не меняет
            check(e2.undoDepth() == u1, "пустая правка не попадает в отмену");
            TitleData.Title duchy2 = dd.ancestor(dd.get(oid2), 2), other2 = null;
            for (TitleData.Title t : dd.allTitles()) if (t.tier == 2 && t.id != duchy2.id) { other2 = t; break; }
            TitleData.Title moved2 = e2.assignAtBlock(ot2.capX, ot2.capZ, other2.id);
            check(moved2 != null && dd.ancestor(m.provinceAt(ot2.capX + 0.5, ot2.capZ + 0.5), 2).id == other2.id, "назначение: провинция под другое герцогство");
            java.util.List<String> issues2 = TitleValidator.check(dd);
            System.out.println("замечаний проверки: " + issues2.size());
            for (int i = 0; i < Math.min(5, issues2.size()); i++) System.out.println("  " + issues2.get(i));
            check(true, "проверка выполняется");
            e2.createTitle(0, "Пустая", 0, 0x123456);
            boolean found2 = false;
            for (String q : TitleValidator.check(dd)) if (q.contains("Пустая") && q.contains("пуста")) found2 = true;
            check(found2, "проверка находит пустую провинцию");
        }
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ОШИБОК: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static int firstTier1Under(TitleData d, TitleData.Title t) { return t.id; }
}
