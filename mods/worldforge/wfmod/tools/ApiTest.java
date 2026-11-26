package dev.worldforge.mod;

import dev.worldforge.mod.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Проверка API расширений: действия, проверки, уведомления, свойства государств как режимы, prop_defs. */
public class ApiTest {
    static int fails;

    static void ok(boolean c, String what) {
        System.out.println((c ? "ok   " : "FAIL ") + what);
        if (!c) fails++;
    }

    public static void main(String[] a) throws Exception {
        String ns = a[1].split(":")[0], nm = a[1].split(":")[1];
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/" + ns + "/worldforge_maps/" + nm + "/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        TitleEditor ed = new TitleEditor(d);
        AtomicInteger changes = new AtomicInteger();
        List<String> kinds = new ArrayList<>();
        WorldForgeApi.addChangeListener(c -> { changes.incrementAndGet(); kinds.add(c.kind + ":" + c.detail); });
        WorldForgeApi.addChangeListener(c -> { throw new RuntimeException("слушатель упал"); });   // не должен ломать остальных

        String rid = d.realmList().get(0).id;
        ActionContext op = new ActionContext(ed, "admin", true, rid, 0, List.of("Эддард Старк"));
        ActionContext pl = new ActionContext(ed, "steve", false, rid, 0, List.of("Хитрец"));

        ActionResult r = WorldForgeApi.run("set_ruler", op);
        ok(r.ok && d.realms.get(rid).ruler.equals("Эддард Старк"), "set_ruler от оператора: " + r.message);
        ok(changes.get() >= 2 && kinds.contains("action:set_ruler") && kinds.contains("edit:"), "уведомления: правка + действие " + kinds);
        ok(!WorldForgeApi.run("set_ruler", pl).ok, "игрок без прав получает отказ");
        ok(!WorldForgeApi.run("нет_такого", op).ok, "неизвестное действие");
        ok(!WorldForgeApi.run("set_ruler", new ActionContext(ed, "admin", true, "no_such_realm", 0, List.of("x"))).ok, "несуществующее государство даёт отказ, а не исключение");
        ok(!WorldForgeApi.run("set_ruler", new ActionContext(ed, "admin", true, rid, 0, List.of())).ok, "не хватает аргумента");

        // проверка перед действием (так аддон запретит, например, смену правителя во время войны)
        WorldForgeApi.addGuard((act, ctx) -> act.id.equals("set_ruler") && ctx.args.get(0).equals("Запрещён") ? "Идёт война" : null);
        ActionResult v = WorldForgeApi.run("set_ruler", new ActionContext(ed, "admin", true, rid, 0, List.of("Запрещён")));
        ok(!v.ok && v.message.equals("Идёт война") && !d.realms.get(rid).ruler.equals("Запрещён"), "guard запрещает действие");

        // пользовательское действие аддона
        WorldForgeApi.registerAction(new MapAction("levy", "Собрать ополчение", MapAction.Scope.REALM, MapAction.Perm.ANY, List.of("число"), "",
                ctx -> { ctx.editor.setRealmProp(ctx.realmId, "levy", ctx.arg(0, "число")); return ActionResult.ok("ok"); }));
        ok(WorldForgeApi.run("levy", pl).ok && "Хитрец".equals(d.realms.get(rid).props.get("levy")), "действие аддона для любого игрока");
        boolean dup = false;
        try { WorldForgeApi.registerAction(new MapAction("levy", "x", MapAction.Scope.MAP, MapAction.Perm.ANY, List.of(), "", c -> ActionResult.ok(""))); } catch (IllegalStateException e) { dup = true; }
        ok(dup, "повторная регистрация id запрещена");
        String json = WorldForgeApi.actionsJson();
        Object parsed = Json.parse(json);
        ok(parsed instanceof List && ((List<?>) parsed).size() >= 5 && json.contains("\"levy\""), "actionsJson парсится: " + ((List<?>) parsed).size() + " действий");

        // свойство государства становится режимом карты, prop_defs задают название, цвета и порядок
        ed.setRealmProp(rid, "war_status", "война");
        String r2 = d.realmList().get(1).id;
        ed.setRealmProp(r2, "war_status", "мир");
        ok(MapImages.propKeys(d).contains("war_status") && MapImages.modes(d).contains("p:war_status"), "свойство государства даёт режим p:war_status");
        MapImages.PropInfo pi = MapImages.propInfo(d, "war_status");
        ok(pi.vals.size() == 2, "два значения: " + pi.vals);
        int[] lut = pi.lut;
        TitleData.Title anyOfRealm = null;
        for (TitleData.Title t : d.allTitles()) if (t.tier == 0 && d.realmOf(t) == d.realms.get(rid)) { anyOfRealm = t; break; }
        ok(anyOfRealm != null && lut[anyOfRealm.id] > 0 && pi.vals.get(lut[anyOfRealm.id] - 1).equals("война"), "земли государства получают его значение");

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", "Война и мир");
        def.put("order", List.of("мир", "война"));
        def.put("colors", Map.of("война", "#cc2222", "мир", "#2266cc"));
        d.propDefs.put("war_status", def);
        String rj = d.realmsJson();
        TitleData copy = TitleData.create(d.titlesJson(), rj, d.pixels().clone(), d.w, d.h);
        MapImages.PropInfo p2 = MapImages.propInfo(copy, "war_status");
        ok("Война и мир".equals(p2.name) && "Война и мир".equals(MapImages.propName(copy, "war_status")), "prop_defs: название проходит через json");
        ok(p2.vals.get(0).equals("мир") && p2.rgb[0] == 0x2266cc && p2.rgb[1] == 0xcc2222, "prop_defs: порядок и цвета " + p2.vals);
        int[] terr = new int[d.w * d.h];
        int[] img = MapImages.render(copy, terr, "p:war_status");
        ok(img.length == terr.length, "режим рисуется");

        // дипломатический слой: иерархия, союзы, войны, соседи
        List<TitleData.Realm> rl = d.realmList();
        String A = rl.get(0).id, B = rl.get(1).id, C = rl.get(2).id, D = rl.get(3).id, E = rl.get(4).id;
        ed.setOverlord(B, A, "vassal");
        ed.setOverlord(C, A, "vassal");
        ed.setOverlord(D, B, "vassal");
        ed.setRealmProp(A, "enemies", E);
        ed.setRealmProp(B, "allies", rl.get(5).id + ", " + rl.get(6).id);
        Map<String, Set<String>> adj = Diplomacy.adjacency(d);
        TitleData.Realm ra = d.realms.get(A), rb = d.realms.get(B), rc = d.realms.get(C), rd = d.realms.get(D), re = d.realms.get(E);
        ok(Diplomacy.relation(d, rb, rb, adj) == Diplomacy.Rel.SELF, "dip: сам");
        ok(Diplomacy.relation(d, rb, ra, adj) == Diplomacy.Rel.OVERLORD, "dip: сюзерен");
        ok(Diplomacy.relation(d, rb, rd, adj) == Diplomacy.Rel.SUBJECT, "dip: подчинённый");
        ok(Diplomacy.relation(d, rd, ra, adj) == Diplomacy.Rel.OVERLORD, "dip: сюзерен сюзерена (цепочка)");
        ok(Diplomacy.relation(d, ra, rd, adj) == Diplomacy.Rel.SUBJECT, "dip: подчинённый подчинённого");
        ok(Diplomacy.relation(d, rb, rc, adj) == Diplomacy.Rel.SIBLING, "dip: вассалы одного сюзерена");
        ok(Diplomacy.relation(d, ra, re, adj) == Diplomacy.Rel.ENEMY && Diplomacy.relation(d, re, ra, adj) == Diplomacy.Rel.ENEMY, "dip: война симметрична");
        ok(Diplomacy.relation(d, d.realms.get(rl.get(5).id), rb, adj) == Diplomacy.Rel.ALLY, "dip: союз виден с обеих сторон");
        // соседство проверяем на паре государств с общей границей, не связанных иерархией (первое государство может быть островом)
        String pa = null, pb = null;
        for (Map.Entry<String, Set<String>> e : adj.entrySet()) {
            for (String o : e.getValue()) {
                Diplomacy.Rel x = Diplomacy.relation(d, d.realms.get(e.getKey()), d.realms.get(o), adj);
                if (x == Diplomacy.Rel.NEIGHBOR) { pa = e.getKey(); pb = o; break; }
            }
            if (pa != null) break;
        }
        ok(pa != null && !adj.isEmpty(), "dip: соседи найдены (" + adj.size() + " государств с границами)");
        int[] dimg = MapImages.render(d, new int[d.w * d.h], "dip:" + A);
        ok(dimg.length == d.w * d.h && MapImages.lutFor(d, "dip:" + A) != null, "режим dip:<государство> рисуется");
        ok(!MapImages.modes(d).contains("dip:" + A), "dip не засоряет список режимов (его включает выбор государства)");

        ed.undo();
        ok(kinds.contains("edit:undo"), "undo тоже уведомляет");
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ПРОВАЛОВ: " + fails);
        if (fails > 0) System.exit(1);
    }
}
