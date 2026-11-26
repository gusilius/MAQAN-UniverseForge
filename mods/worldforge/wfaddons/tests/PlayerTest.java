package dev.worldforge.tests;

import dev.worldforge.mod.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.court.*;

import java.util.*;

public class PlayerTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }
    static boolean bad(Runnable r) { try { r.run(); return false; } catch (IllegalArgumentException e) { return true; } }

    public static void main(String[] a) throws Exception {
        CourtSim s = CourtTest.build(a[0], 21);
        String realm = null;
        for (TitleData.Realm r : s.d.realmList()) if (s.ruler(r.id) != null && s.reg.get(s.ruler(r.id).id) != null) { realm = r.id; break; }
        final String R = realm;
        Character k = s.ruler(R);
        k.birthYear = s.year() - 40;
        Character son = s.reg.create("Наследник", k.house, k.culture, "m", s.year() - 20);
        son.father = k.id; k.children.add(son.id);
        List<String> events = new ArrayList<>();
        s.hooks = new CourtSim.Hooks() {
            public void playerHeir(String p, String o, String n) { events.add("heir:" + p + ":" + n); }
            public void playerDied(String p) { events.add("died:" + p); }
        };
        ok(bad(() -> s.play("Аня", "нет")), "нет такого героя");
        Character kid = s.reg.create("Малыш", k.house, k.culture, "m", s.year() - 5);
        ok(bad(() -> s.play("Аня", kid.id)), "ребёнка вести нельзя");
        s.play("Аня", k.id);
        ok(s.playing("Аня") == k && String.valueOf(k.props.get("player")).equals("Аня"), "игрок ведёт правителя");
        ok(s.humans.contains(R), "государство под управлением игрока");
        ok(bad(() -> s.play("Боря", k.id)), "двоих на одного героя нельзя");
        String[] v = s.viewJson(R, "Аня");
        CourtSim cv = new CourtSim(s.d, null, new CharacterRegistry(), s.defs, s.names, 1, 300);
        cv.reg.fromJson(v[0]); cv.fromJson(v[1]);
        ok(cv.me.equals(k.id) && cv.reg.get(cv.me) != null, "в клиентском срезе виден герой игрока");

        // смерть правителя: власть и управление переходят к наследнику
        s.die(k, "умер");
        Character now = s.playing("Аня");
        ok(now != null && now != k && now.alive, "после смерти игрок ведёт наследника: " + (now == null ? "?" : now.name));
        ok(events.stream().anyMatch(e -> e.startsWith("heir:Аня")), "хук наследования вызван");
        ok(k.props.get("player") == null && String.valueOf(now.props.get("player")).equals("Аня"), "метки игрока перенесены");
        ok(s.humans.contains(R), "государство остаётся за игроком");

        // герой без наследника
        s.release("Аня");
        ok(s.playing("Аня") == null, "можно отпустить героя");
        Character lone = s.reg.create("Одинокий", "", "", "m", s.year() - 30);
        s.play("Боря", lone.id);
        s.die(lone, "умер");
        ok(s.playing("Боря") == null && events.contains("died:Боря"), "без наследника игрок остаётся без героя");

        // сохранение
        s.play("Аня", s.ruler(R).id);
        CourtSim back = CourtTest.build(a[0], 21);
        back.fromJson(s.toJson());
        ok(back.players.equals(s.players), "игроки сохраняются");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
