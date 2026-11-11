package dev.worldforge.tests;

import dev.worldforge.mod.*;
import dev.worldforge.war.*;
import dev.worldforge.army.*;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.economy.TreasuryService;

import java.nio.file.*;
import java.util.*;

public class ArmyTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static boolean fails(Runnable r) { try { r.run(); return false; } catch (TitleEditor.EditException e) { return true; } }

    static ArmyEngine fresh(String pack, String[] ids) throws Exception {
        TreasuryApi.resetForTests();
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(pack)) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        WarEngine w = new WarEngine(new TitleEditor(d));
        w.loadCasusBelli(Files.readString(Path.of("wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : d.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty()) free.add(r.id);
        for (String id : free) { d.realms.get(id).props.remove("allies"); d.realms.get(id).props.remove("enemies"); }
        ArmyEngine e = new ArmyEngine(w);
        // пара соседей с наибольшим числом баронств
        String best = null, bb = null; int bs = -1;
        for (String a : free) for (String b : w.adjacency().getOrDefault(a, Set.of())) {
            if (!free.contains(b)) continue;
            int s = Math.min(e.baroniesOf(a).size(), e.baroniesOf(b).size());
            if (s > bs) { bs = s; best = a; bb = b; }
        }
        ids[0] = best; ids[1] = bb;
        return e;
    }

    public static void main(String[] a) throws Exception {
        String[] ids = new String[2];
        ArmyEngine e = fresh(a[0], ids);
        e.ai = false;
        String A = ids[0], B = ids[1];
        System.out.println("пара: " + A + " (" + e.baroniesOf(A).size() + " баронств) против " + B + " (" + e.baroniesOf(B).size() + ")");
        ok(e.baroniesOf(A).size() > 3 && e.baroniesOf(B).size() > 3, "у государств есть баронства");
        ok(e.capital(A) != 0, "есть столица");
        long dayBeforeInvalidTicks = e.war.day;
        e.tick(Double.NaN);
        e.tick(Double.POSITIVE_INFINITY);
        e.tick(Double.NEGATIVE_INFINITY);
        e.tick(-1);
        e.tick(0);
        ok(e.war.day == dayBeforeInvalidTicks, "невалидный шаг времени армии не двигает календарь");
        boolean oversizedTickRejected = false;
        try { e.tick(3651); } catch (IllegalArgumentException ex) { oversizedTickRejected = true; }
        ok(oversizedTickRejected && e.war.day == dayBeforeInvalidTicks, "чрезмерный шаг времени отклоняется без зависания");
        ArmyEngine clock = fresh(a[0], ids); clock.ai = false;
        long clockStart = clock.war.day;
        clock.tick(0.4);
        ok(clock.war.day == clockStart, "дробный шаг меньше суток пока не продвигает день");
        ArmyEngine clockReload = new ArmyEngine(clock.war);
        clockReload.fromJson(clock.toJson());
        clockReload.tick(0.6);
        ok(clock.war.day == clockStart + 1, "дробные шаги накапливаются и сохраняются между загрузками");

        // набор
        double p0 = e.pool(A);
        Army ar = e.raise(A, "levy", 50);
        ok(ar.men() == 50 && e.pool(A) == p0 - 50, "ополчение берётся из запаса");
        ok(Math.abs(e.gold(A) - ArmyEngine.START_GOLD) < 1e-8, "new realm receives its opening treasury balance exactly once");
        ok(fails(() -> e.raise(A, "levy", 1_000_000)), "нельзя набрать больше запаса");
        double g0 = e.gold(A);
        WorldForgeApi.clock().reset();
        WorldForgeApi.clock().advance(dev.worldforge.mod.api.GameClock.TICKS_PER_DAY * 3 + dev.worldforge.mod.api.GameClock.TICKS_PER_DAY / 2);
        e.raise(A, "archer", 20);
        ok(TreasuryService.current().journal().get(TreasuryService.current().journal().size() - 1).worldMinute() == 5040L,
                "army treasury journal timestamps use canonical world minutes, not the local war-day counter");
        ok(e.gold(A) < g0, "профессионалы стоят золота");
        ok(Math.abs(TreasuryService.current().balance("realm:" + A) - e.gold(A) * 5.0) < 1e-8,
                "army recruitment posts its debit to the canonical silver treasury");
        ok(fails(() -> e.raise(A, "knight", 100_000)), "не хватает золота");
        ok(fails(() -> e.hire("missing_realm", "cavalry", 10)), "нельзя нанимать войско государству без земель");
        Army merc = e.hire(A, "cavalry", 10);
        ok(merc.mercenary && merc != ar, "наёмники отдельной армией");
        ok(e.armiesOf(A).size() == 2 && ar.count("archer") == 20, "армии: войско и наёмники");
        e.merge(ar.id, e.split(ar.id, "archer", 20, A).id, A);
        ok(ar.count("archer") == 20, "split+merge возвращают отряд");
        ok(fails(() -> e.merge(ar.id, merc.id, A)), "наёмников с войском не сливают");

        // движение
        int far = e.baroniesOf(B).get(0);
        List<Integer> path = e.route(ar.location, far);
        ok(!path.isEmpty(), "путь до вражеского баронства: " + path.size() + " шагов");
        ok(fails(() -> e.move(ar.id, far, B)), "чужой армией командовать нельзя");
        War w = e.war.declare(A, B, "conquest", List.of(e.war.goalCandidates(B).get(0).id));
        double score0 = w.score;
        e.move(ar.id, far, A);
        int days = 0;
        while (ar.moving() && days < 200) { e.tick(1); days++; }
        ok(ar.location == far && days >= path.size() / 2, "армия дошла за " + days + " дн.");

        // осада
        e.tick(1);
        ok(ar.siegeTitle == far, "армия на земле врага осаждает");
        for (int i = 0; i < 40 && w.occupied.get(far) == null; i++) e.tick(1);
        ok(w.occupied.get(far) == War.Side.ATTACKER, "баронство взято");
        ok(w.score > score0, "очки войны выросли от осады");

        // снабжение: в чужих землях припасы тают, дома восстанавливаются
        ArmyEngine sp = fresh(a[0], ids);
        sp.ai = false;
        Army foe = sp.raise(ids[0], "levy", 30); foe.location = sp.baroniesOf(ids[1]).get(0);
        sp.invalidate();
        foe.supply = 1.0; int men0 = foe.men();
        double mor0 = foe.morale;
        int dd = 0;
        while (foe.supply > 0 && dd < 60) { sp.tick(1); dd++; }
        ok(dd > 5 && dd < 50, "припасы в чужой земле кончились за " + dd + " дн.");
        ok(foe.supply == 0 || foe.men() < men0, "армия потеряла припасы");
        for (int i = 0; i < 20; i++) sp.tick(1);
        ok(foe.men() < men0 || sp.armies.get(foe.id) == null, "без припасов идут потери: " + men0 + " -> " + (sp.armies.get(foe.id) == null ? 0 : foe.men()));
        Army home = sp.raise(ids[0], "levy", 20); home.location = sp.baroniesOf(ids[0]).get(0); home.supply = 0.2; ok(home != foe, "отдельная армия дома");
        sp.tick(4);
        ok(home.supply == 1.0, "в своих землях припасы возвращаются");

        // торговые договоры
        ArmyEngine tp = fresh(a[0], ids);
        tp.ai = false;
        ok(tp.proposePact(ids[0], ids[1]) == null && tp.hasPact(ids[1], ids[0]), "договор заключён");
        ok(fails(() -> tp.proposePact(ids[1], ids[0])), "второй раз нельзя");
        double tg0 = tp.gold(ids[0]), tg1 = tp.gold(ids[1]);
        double base0 = ArmyEngine.INCOME_PER_BARONY * tp.baroniesOf(ids[0]).size();
        tp.tick(10);
        ok(tp.gold(ids[0]) > tg0 + base0 * 10 + 1 && tp.gold(ids[1]) > tg1, "оба получают золото сверх обычного: " + tg0 + " -> " + tp.gold(ids[0]));
        ok(tp.partners(ids[0]).contains(ids[1]) && tp.pactIncome(ids[0]) > 0, "партнёры и доход видны");
        ArmyEngine tb = new ArmyEngine(tp.war); tb.fromJson(tp.toJson());
        ok(tb.hasPact(ids[0], ids[1]), "договор сохраняется");
        tp.war.declare(ids[0], ids[1], "conquest", List.of(tp.war.goalCandidates(ids[1]).get(0).id));
        tp.tick(1);
        ok(!tp.hasPact(ids[0], ids[1]), "война рвёт договор");
        ok(tp.proposePact(ids[0], ids[1]) != null, "во время войны договор не заключить");

        // бой: кавалерия против лучников
        ArmyEngine t = fresh(a[0], ids);
        t.ai = false;
        t.war.declare(ids[0], ids[1], "conquest", List.of(t.war.goalCandidates(ids[1]).get(0).id));
        Army cav = t.raise(ids[0], "levy", 10); cav.units.clear(); cav.add("cavalry", 100); cav.location = t.capital(ids[0]);
        Army arch = t.raise(ids[1], "levy", 10); arch.units.clear(); arch.add("archer", 100); arch.location = cav.location;
        t.tick(1);
        ok(cav.men() > arch.men(), "конница бьёт лучников: " + cav.men() + " против " + arch.men());
        ok(t.war.wars.values().iterator().next().score != 0, "бой изменил счёт");

        // ИИ: полная война без игроков
        ArmyEngine ai = fresh(a[0], ids);
        War aw = ai.war.declare(ids[0], ids[1], "conquest", List.of(ai.war.goalCandidates(ids[1]).get(0).id));
        int day = 0;
        while (aw.active && day < 1500) { ai.tick(1); day++; }
        ok(ai.events.size() > 5, "ИИ воевал: событий " + ai.events.size());
        ok(!aw.active || Math.abs(aw.score) > 0 || ai.events.stream().anyMatch(x -> x.contains("осаждает") || x.contains("взято") || x.contains("сражается")), "война не застыла: есть мирное соглашение, счёт или подтверждённые осады/захваты (день " + day + ", активна=" + aw.active + ", счёт " + Math.round(aw.score) + ")");
        ai.events.stream().limit(8).forEach(System.out::println);

        // сохранение
        String js = ai.toJson();
        ArmyEngine back = new ArmyEngine(ai.war);
        back.fromJson(js);
        ok(back.armies.size() == ai.armies.size() && back.toJson().equals(js), "сохранение и загрузка совпадают");
        ok(!js.contains("\"gold\"") && js.contains("wf-army/2"), "new army snapshots no longer persist a duplicate local gold balance");
        @SuppressWarnings("unchecked")
        Map<String, Object> legacyRoot = (Map<String, Object>) Json.parse(js);
        legacyRoot.put("format", "wf-army/1");
        legacyRoot.put("gold", Map.of("legacy-army-realm", 17.0));
        ArmyEngine legacyReload = new ArmyEngine(ai.war);
        legacyReload.fromJson(Json.write(legacyRoot));
        ok(Math.abs(legacyReload.gold("legacy-army-realm") - 17.0) < 1e-8,
                "wf-army/1 legacy gold migrates into the shared treasury on first access");
        String upgradedLegacySave = legacyReload.toJson();
        ok(upgradedLegacySave.contains("wf-army/2") && !upgradedLegacySave.contains("\"gold\"")
                        && Math.abs(legacyReload.gold("legacy-army-realm") - 17.0) < 1e-8,
                "legacy army save upgrades without persisting or importing its gold balance twice");

        // Physical troop return -> strategic roster uses one durable operation receipt per entity UUID.
        ArmyEngine rosterTx = fresh(a[0], ids);
        rosterTx.ai = false;
        Army rosterArmy = rosterTx.raise(ids[0], "levy", 20);
        int rosterBefore = rosterArmy.count("levy");
        String returnOperation = "troop-return:11111111-1111-4111-8111-111111111111";
        ok(rosterTx.applyRosterDeltaOnce(returnOperation, rosterArmy.id, Map.of("levy", 10))
                        && rosterArmy.count("levy") == rosterBefore + 10,
                "first physical-troop return applies its manpower delta");
        ok(!rosterTx.applyRosterDeltaOnce(returnOperation, rosterArmy.id, Map.of("levy", 10))
                        && rosterArmy.count("levy") == rosterBefore + 10,
                "replaying the same troop return does not duplicate manpower");
        boolean rosterCollision = false;
        try { rosterTx.applyRosterDeltaOnce(returnOperation, rosterArmy.id, Map.of("levy", 20)); }
        catch (IllegalStateException expected) { rosterCollision = true; }
        ok(rosterCollision && rosterArmy.count("levy") == rosterBefore + 10,
                "reusing a troop return id with a different payload fails closed");
        boolean rosterUnderflow = false;
        try { rosterTx.applyRosterDeltaOnce("troop-return:22222222-2222-4222-8222-222222222222",
                rosterArmy.id, Map.of("levy", -1000)); }
        catch (IllegalArgumentException expected) { rosterUnderflow = true; }
        ok(rosterUnderflow && rosterArmy.count("levy") == rosterBefore + 10,
                "invalid roster deltas are rejected before any partial manpower mutation");
        String rosterSnapshot = rosterTx.toJson();
        ArmyEngine rosterReload = new ArmyEngine(rosterTx.war);
        rosterReload.fromJson(rosterSnapshot);
        ok(!rosterReload.applyRosterDeltaOnce(returnOperation, rosterArmy.id, Map.of("levy", 10))
                        && rosterReload.army(rosterArmy.id).count("levy") == rosterBefore + 10,
                "troop return idempotency receipts survive army save/reload");
        ok(rosterReload.hasRosterOperation(returnOperation),
                "physical return receipt can tombstone a late-loaded troop entity after restart");
        rosterReload.disband(rosterArmy.id, A);
        ok(!rosterReload.armies.containsKey(rosterArmy.id) && rosterReload.hasRosterOperation(returnOperation),
                "physical return tombstone survives disbanding its source army");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
