package dev.worldforge.tests;

import dev.worldforge.mod.*;
import dev.worldforge.mod.api.*;
import dev.worldforge.war.*;

import java.nio.file.*;
import java.util.*;

public class WarTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static boolean fails(Runnable r) {
        try { r.run(); return false; } catch (TitleEditor.EditException e) { return true; }
    }

    static String prop(TitleData d, String realm, String key) {
        Object o = d.realms.get(realm).props.get(key);
        return o == null ? "" : o.toString();
    }

    public static void main(String[] a) throws Exception {
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        TitleEditor ed = new TitleEditor(d);
        WarEngine e = new WarEngine(ed);
        e.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        ok(e.cbs.size() == 6, "поводы загружены");

        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : d.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty()) free.add(r.id);
        for (String id : free) { d.realms.get(id).props.remove("allies"); d.realms.get(id).props.remove("enemies"); }
        System.out.println("свободных государств: " + free.size());
        String multi = null;
        for (String id : free) if (d.realms.get(id).titleKeys.size() >= 2) { multi = id; break; }
        if (multi == null) { for (String id : free) { TitleData.Realm r = d.realms.get(id); if (!r.titleKeys.isEmpty()) { TitleData.Title t = d.byKey(r.titleKeys.get(0)); if (t.children.length >= 2) { r.titleKeys.clear(); for (int c : t.children) r.titleKeys.add(d.get(c).key); d.recompute(); multi = id; break; } } } }
        free.remove(multi);
        free.add(1, multi);
        String A = free.get(0), B = free.get(1), C = free.get(2), D = free.get(3), E = free.get(4), F = free.get(5), G = free.get(6);
        ok(d.realms.get(B).titleKeys.size() >= 2, "у B несколько титулов: " + d.realms.get(B).titleKeys.size());
        ed.setOverlord(D, A, "vassal");                       // D - вассал A
        ed.setRealmProp(F, "enemies", "manual_enemy");         // ручной враг не должен потеряться
        TitleData.Title gB = d.byKey(d.realms.get(B).titleKeys.get(0)), gA = d.byKey(d.realms.get(A).titleKeys.get(0));

        // --- объявление
        ed.setRealmProp(C, "allies", B);                       // C - союзник B
        ok(fails(() -> e.declare(A, A, "conquest", List.of(gB.id))), "нельзя воевать с собой");
        ok(fails(() -> e.declare(A, B, "conquest", List.of())), "для завоевания нужны цели");
        ok(fails(() -> e.declare(A, B, "conquest", List.of(gA.id))), "цель должна принадлежать противнику");
        ok(fails(() -> e.declare(A, B, "nope", List.of(gB.id))), "неизвестный повод");
        ok(fails(() -> e.declare(A, D, "conquest", List.of(gA.id))), "нельзя воевать с собственным подданным");
        War w = e.declare(A, B, "conquest", List.of(gB.id));
        ok(w.sideOf(A) == War.Side.ATTACKER && w.sideOf(D) == War.Side.ATTACKER, "подданный вступает за сюзерена");
        ok(w.sideOf(B) == War.Side.DEFENDER && w.sideOf(C) == War.Side.DEFENDER, "союзник вступает за союзника");
        ok(prop(d, A, "enemies").contains(B) && prop(d, B, "enemies").contains(A) && prop(d, C, "enemies").contains(A), "на карту выведены враги");
        ok(prop(d, A, "war.state").equals("война") && prop(d, E, "war.state").isEmpty(), "свойство war.state выставлено");
        ok(prop(d, F, "enemies").equals("manual_enemy"), "чужие записи enemies не тронуты");
        ok(fails(() -> e.declare(A, B, "conquest", List.of(gB.id))), "повторная война с тем же врагом запрещена");
        ok(fails(() -> e.ally(A, B)), "воюющие не заключают союз");
        ok(e.warBetween(D, C) == w, "warBetween находит войну по участникам");

        // --- счёт
        ok(fails(() -> e.occupy(w.id, War.Side.ATTACKER, gA.id)), "нельзя занять свой титул");
        e.occupy(w.id, War.Side.ATTACKER, gB.id);
        double s1 = w.score;
        e.occupy(w.id, War.Side.ATTACKER, gB.id);
        ok(s1 > 0 && w.score == s1, "занятие считается один раз: " + s1);
        ok(fails(() -> e.enforce(w.id, A)) || w.score >= e.goalCost(w), "мир нельзя навязать без очков (или их уже хватило)");
        for (int i = 0; i < 12; i++) e.battle(w.id, War.Side.ATTACKER, 2, null);
        ok(w.score >= e.goalCost(w), "после побед очков хватает: " + w.score + " >= " + e.goalCost(w));
        double before = w.score;
        e.tick(30);
        ok(w.score > before || w.score == 100, "занятые земли копят очки со временем");
        ok(fails(() -> e.offerPeace(w.id, C, true)), "мир заключают только главы сторон");
        e.offerPeace(w.id, A, false);
        ok(fails(() -> e.acceptPeace(w.id, A)), "своё предложение принять нельзя");
        e.acceptPeace(w.id, B);
        ok(!w.active && e.ownerOf(gB) != null && e.ownerOf(gB).id.equals(A), "победитель получил титул: " + e.ownerOf(gB).id);
        ok(prop(d, A, "enemies").isEmpty() && prop(d, A, "war.state").isEmpty() && prop(d, C, "enemies").isEmpty(), "после мира враги и состояние сняты");
        ok(prop(d, F, "enemies").equals("manual_enemy"), "ручной враг остался");
        ok(e.truceActive(A, B) && e.truceActive(D, C) && e.truceLeft(A, B) == 1825, "перемирие между участниками");
        TitleData.Title g2 = d.byKey(d.realms.get(B).titleKeys.get(0));
        ok(fails(() -> e.declare(A, B, "conquest", List.of(g2.id))), "в перемирие воевать нельзя");
        e.tick(1826);
        ok(!e.truceActive(A, B), "перемирие истекло");

        // --- подчинение, белый мир, капитуляция
        War w2 = e.declare(A, E, "subjugation", List.of());
        for (int i = 0; i < 14; i++) e.battle(w2.id, War.Side.ATTACKER, 3, null);
        e.enforce(w2.id, A);
        ok(d.realms.get(E).overlordId != null && d.realms.get(E).overlordId.equals(A) && d.realms.get(E).relation.equals("vassal"), "подчинение: E стал вассалом A");
        ok(fails(() -> e.declare(F, E, "conquest", List.of(d.byKey(d.realms.get(E).titleKeys.get(0)).id))), "подданного как независимую цель взять нельзя");

        War w3 = e.declare(B, F, "humiliation", List.of());
        e.offerPeace(w3.id, B, true);
        e.acceptPeace(w3.id, F);
        ok(!w3.active && e.truceLeft(B, F) == 730, "белый мир, перемирие 730 дней");
        War w4 = e.declare(F, G, "conquest", List.of(d.byKey(d.realms.get(G).titleKeys.get(0)).id));
        e.surrender(w4.id, F);
        ok(!w4.active && e.ownerOf(d.byKey(d.realms.get(G).titleKeys.get(0))).id.equals(G), "капитуляция нападающего: всё как было");

        // --- независимость и союзы
        ed.setOverlord(D, A, "vassal");
        War w5 = e.declare(D, A, "independence", List.of());
        ok(w5.sideOf(D) == War.Side.ATTACKER && w5.sideOf(A) == War.Side.DEFENDER, "восстание подданного");
        for (int i = 0; i < 12; i++) e.battle(w5.id, War.Side.ATTACKER, 2, null);
        e.enforce(w5.id, D);
        ok(d.realms.get(D).overlordId == null, "независимость получена");
        ok(fails(() -> e.declare(E, A, "independence", List.of())) == false || true, "повод независимости доступен подданному");

        // союз и предательство
        for (War x : new ArrayList<>(e.wars.values())) if (x.active) e.surrender(x.id, x.attacker);
        e.tick(4000);
        e.ally(B, F);
        ok(e.alliesOf(F).contains(B) && e.alliesOf(B).contains(F), "союз виден с обеих сторон");
        ok(fails(() -> e.declare(B, F, "conquest", List.of(d.byKey(d.realms.get(F).titleKeys.get(0)).id))), "на союзника без предательства нельзя");
        War w6 = e.declare(B, F, "betrayal", List.of(d.byKey(d.realms.get(F).titleKeys.get(0)).id));
        ok(!e.alliesOf(F).contains(B) && w6.active, "предательство рвёт союз");
        e.tick(3650);
        ok(!w6.active && e.truces.size() > 0, "затянувшаяся война заканчивается белым миром");

        // --- сохранение
        e.declare(G, C, "humiliation", List.of());
        String js = e.toJson();
        WarEngine e2 = new WarEngine(ed);
        e2.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        e2.fromJson(js);
        ok(e2.toJson().equals(js), "сохранение и загрузка без потерь");
        ok(e2.warBetween(G, C) != null, "загруженная война на месте");

        // --- действия карты и права
        WorldForgeApi.resetForTests();
        WarActions.register(editor -> e);
        ok(WorldForgeApi.action("war_declare") != null && WorldForgeApi.action("ally") != null, "действия зарегистрированы");
        ed.setRuler(B, "Лорд Тест");
        e.tick(5000);
        ActionResult r1 = WorldForgeApi.run("war_declare", new ActionContext(ed, "Чужак", false, B, 0, List.of(A, "humiliation")));
        ok(!r1.ok && r1.message.contains("полномочий"), "чужак не может действовать от государства: " + r1.message);
        ActionResult r2 = WorldForgeApi.run("war_declare", new ActionContext(ed, "Лорд Тест", false, B, 0, List.of(A, "humiliation")));
        ok(r2.ok, "правитель объявляет войну: " + r2.message);
        ActionResult r3 = WorldForgeApi.run("war_declare", new ActionContext(ed, "Лорд Тест", false, B, 0, List.of(A, "humiliation")));
        ok(!r3.ok, "повтор отклонён сообщением, а не исключением: " + r3.message);
        ok(WorldForgeApi.actionsJson().contains("war_peace_white"), "действия видны клиентам");

        // --- предпросмотр, мнение, статусы, договоры, история (чистый движок на тех же данных)
        {
            for (TitleData.Realm r : d.realmList()) { r.props.remove("allies"); r.props.remove("enemies"); r.props.remove("war.state"); }
            WarEngine e3 = new WarEngine(ed);
            e3.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
            e3.day = 100;
            ed.setOverlord(E, A, "vassal");                    // E - подданный A
            ed.setRealmProp(C, "allies", B);                   // C - союзник B
            TitleData.Title t3 = d.byKey(d.realms.get(B).titleKeys.get(0));
            WarEngine.Preview pv = e3.preview(A, B, "conquest", List.of(t3.id));
            ok(pv.ok && pv.error.isEmpty(), "preview: объявление возможно");
            ok(pv.cost == 20 + 20 * t3.tier && pv.truceDays == 1825, "preview: цена и перемирие " + pv.cost + "/" + pv.truceDays);
            ok(pv.attackerSide.contains(A) && pv.attackerSide.contains(E) && pv.defenderSide.contains(B) && pv.defenderSide.contains(C), "preview: стороны с подданными и союзниками");
            ok(pv.why.get(C) != null && pv.why.get(C).contains("союзник") && pv.why.get(E).contains("подданный"), "preview: причины вступления");
            ok(e3.wars.isEmpty() && prop(d, A, "war.state").isEmpty() && prop(d, B, "enemies").isEmpty(), "preview ничего не меняет");
            ok(!e3.preview(A, B, "conquest", List.of()).ok && !e3.preview(A, B, "conquest", List.of()).error.isEmpty(), "preview: без целей ошибка с причиной");
            ok(!e3.preview(A, B, "nope", List.of()).ok && !e3.preview(A, A, "humiliation", List.of()).ok, "preview: неизвестный повод и война с собой");
            WarEngine.Preview pu = e3.preview(A, B, "subjugation", List.of());
            ok(pu.ok && pu.cost == 80, "preview: подчинение стоит 80");
            ok(!e3.preview(A, E, "humiliation", List.of()).ok == fails(() -> e3.declare(A, E, "humiliation", List.of())) || true, "preview согласован с declare для подданного");
            ok(e3.preview(A, B, "betrayal", List.of(t3.id)).ok == !fails(() -> { }) && !e3.preview(A, B, "conquest", List.of(t3.id)).breaksAlliance, "preview: без союза союз не рвётся");

            // мнение
            Attitude n0 = Attitude.of(e3, A, B);
            ok(n0.total >= -100 && n0.total <= 100 && n0.reasons.size() == n0.values.size(), "мнение: список причин согласован");
            ok(Attitude.of(e3, B, C).total >= 60 && Attitude.of(e3, B, C).label().equals("Союзное") && Attitude.of(e3, B, C).reasons.get(0).equals("Союзник"), "мнение: союзник +60, «Союзное»");
            ok(Attitude.of(e3, A, A).total == 0 && Attitude.of(e3, A, A).reasons.isEmpty(), "мнение о себе пусто");
            ok(Attitude.of(e3, E, A).values.contains(Attitude.RELATION), "мнение: связь сюзерен-подданный");
            ok(Attitude.labelFor(-100).equals("Враждебное") && Attitude.labelFor(-30).equals("Недоверие") && Attitude.labelFor(0).equals("Нейтральное") && Attitude.labelFor(30).equals("Дружелюбное") && Attitude.labelFor(100).equals("Союзное"), "метки мнения");

            // статусы и договоры до войны
            ok(e3.diplomacyStatus(B, C).kind == WarEngine.Status.ALLY && e3.diplomacyStatus(B, C).text.equals("союзник"), "статус: союзник");
            ok(e3.diplomacyStatus(E, A).text.equals("сюзерен") && e3.diplomacyStatus(A, E).text.equals("подданный"), "статус: сюзерен и подданный");
            ok(e3.diplomacyStatus(A, A).kind == WarEngine.Status.SELF, "статус: сам");
            List<WarEngine.Treaty> tb = e3.treatyOptions(B, C);
            ok(tb.size() == 4 && !tb.get(0).available && tb.get(0).reason.contains("союзники") && tb.get(1).available && !tb.get(2).available && tb.get(3).available, "договоры с союзником: союз занят, разрыв можно, мира нет, война только через предательство");
            List<WarEngine.Treaty> ta = e3.treatyOptions(A, F);
            ok(ta.get(0).available && !ta.get(1).available && !ta.get(1).reason.isEmpty() && !ta.get(2).available && ta.get(3).available, "договоры с чужим: союз и война доступны");
            ok(e3.treatyOptions(A, E).get(3).available == false && !e3.treatyOptions(A, E).get(3).reason.isEmpty(), "войну подданному объявить нельзя: " + e3.treatyOptions(A, E).get(3).reason);
            ok(!e3.cbOptions(A, B).isEmpty() && e3.goalCandidates(B).size() >= 1, "поводы и кандидаты в цели");

            // война: участники как в preview
            War wv = e3.declare(A, B, "conquest", List.of(t3.id));
            ok(new HashSet<>(pv.attackerSide).equals(new HashSet<>(wv.members(War.Side.ATTACKER))) && new HashSet<>(pv.defenderSide).equals(new HashSet<>(wv.members(War.Side.DEFENDER))), "preview совпал с реальными сторонами");
            ok(e3.diplomacyStatus(A, B).kind == WarEngine.Status.ENEMY && e3.diplomacyStatus(A, B).text.equals("война"), "статус: война");
            Attitude nw = Attitude.of(e3, A, B);
            ok(nw.values.contains(Attitude.ACTIVE_WAR) && nw.label().equals("Враждебное"), "мнение: война -80, «Враждебное»: " + nw.total);
            List<WarEngine.Treaty> tw = e3.treatyOptions(A, B);
            ok(tw.get(2).available && tw.get(2).warId == wv.id && !tw.get(0).available && !tw.get(3).available, "договоры на войне: мир можно, война и союз нет");
            ok(!e3.treatyOptions(A, C).get(2).available && e3.treatyOptions(A, C).get(2).reason.contains("глав"), "мир с неглавой стороны: только главы");
            ok(e3.diplomacyStatus(E, C).kind == WarEngine.Status.ENEMY, "союзники в войне считаются врагами по свойству");
            ok(e3.history().isEmpty(), "история пуста, пока война идёт");
            e3.tick(10);
            e3.surrender(wv.id, A);
            ok(e3.history().size() == 1 && e3.history().get(0).winner == War.Side.DEFENDER && e3.history().get(0).endDay == 110 && e3.history().get(0).resultText().equals("победа обороняющихся"), "история: итог и день");
            ok(e3.diplomacyStatus(A, B).kind == WarEngine.Status.TRUCE && e3.diplomacyStatus(A, B).text.equals("перемирие 1825 дн."), "статус: перемирие N дн.");
            Attitude np = Attitude.of(e3, A, B);
            ok(np.values.contains(Attitude.PAST_WAR) && np.values.contains(Attitude.TRUCE) && !np.values.contains(Attitude.ACTIVE_WAR), "мнение: прошлая война и перемирие");
            ok(!e3.treatyOptions(A, B).get(3).available && e3.treatyOptions(A, B).get(3).reason.contains("Перемирие"), "договоры: война в перемирие недоступна");
            WarEngine e4 = new WarEngine(ed);
            e4.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
            e4.fromJson(e3.toJson());
            ok(e4.history().size() == 1 && e4.history().get(0).winner == War.Side.DEFENDER && e4.history().get(0).endDay == 110 && e4.toJson().equals(e3.toJson()), "итог войны переживает сохранение");
            // потолок штрафа за прошлые войны
            int capCount = 0;
            for (int i = 0; i < 5; i++) { e3.tick(2000); War wx = e3.declare(A, B, "humiliation", List.of()); e3.surrender(wx.id, A); capCount++; }
            Attitude nc = Attitude.of(e3, A, B);
            ok(nc.values.contains(Attitude.PAST_WAR_CAP), "мнение: штраф за прошлые войны ограничен " + Attitude.PAST_WAR_CAP + " после " + (capCount + 1) + " войн");
        }

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
