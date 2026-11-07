package dev.worldforge.tests;

import dev.worldforge.mod.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.court.*;

import java.util.*;

public class MarriageTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static Character kid(CourtSim s, String realm, String g, int age) {
        Character r = s.ruler(realm);
        Character c = s.reg.create("Тест" + g + realm, r.house, r.culture, g, s.year() - age);
        c.father = r.id; r.children.add(c.id);
        return c;
    }

    public static void main(String[] a) throws Exception {
        CourtSim s = CourtTest.build(a[0], 11);
        List<String> rs = new ArrayList<>();
        for (TitleData.Realm r : s.d.realmList()) if (s.ruler(r.id) != null) rs.add(r.id);
        String A = rs.get(0), B = rs.get(1);
        Character son = kid(s, A, "m", 20), daughter = kid(s, B, "f", 19), son2 = kid(s, B, "m", 20), young = kid(s, B, "f", 10);
        ok(Marriages.family(s, son).equals(A), "дом ребёнка определяется по отцу");
        ok(!Marriages.evaluate(s, son.id, son2.id).ok, "однополый брак отклонён");
        ok(!Marriages.evaluate(s, son.id, young.id).ok, "несовершеннолетняя отклонена");
        Character sis = kid(s, A, "f", 21);
        ok(!Marriages.evaluate(s, son.id, sis.id).ok, "внутри одного государства нельзя");

        // враждебный правитель отказывает
        s.opinions.add(s.ruler(B).id, s.ruler(A).id, "Вражда", -80, 0);
        Marriages.Offer no = Marriages.evaluate(s, son.id, daughter.id);
        ok(!no.ok && !no.reasons.isEmpty(), "отказ объяснён причинами: " + no.reasons);
        ok(son.spouse.isEmpty(), "при отказе брака нет");
        s.opinions.mods.removeIf(m -> m.label.equals("Вражда"));

        int before = s.opinion(s.ruler(B).id, s.ruler(A).id);
        Marriages.Offer yes = Marriages.propose(s, son.id, daughter.id);
        ok(yes.ok && son.spouse.equals(daughter.id) && daughter.spouse.equals(son.id), "брак заключён: " + yes.reasons);
        ok(s.opinion(s.ruler(B).id, s.ruler(A).id) >= before + Marriages.TIE_OPINION, "правители сблизились");
        ok(Marriages.ties(s, A).contains(B) && Marriages.ties(s, B).contains(A), "родственный узел виден обоим");
        s.syncProps();
        ok(String.valueOf(s.d.realms.get(A).props.get("people.ties")).contains(B), "узел записан в свойство государства");
        ok(!Marriages.evaluate(s, son.id, son2.id).ok, "женатый не может жениться");

        s.die(daughter, "умерла");
        ok(!Marriages.ties(s, A).contains(B), "со смертью супруги узел исчезает");

        // ИИ женит наследников за десятилетия
        CourtSim w = CourtTest.build(a[0], 5);
        int n0 = 0;
        for (Character c : w.reg.all()) if (!c.spouse.isEmpty()) n0++;
        w.tick(365 * 30);
        int cross = 0;
        for (TitleData.Realm r : w.d.realmList()) cross += Marriages.ties(w, r.id).size();
        ok(cross > 0, "за 30 лет ИИ заключил межгосударственные браки: узлов " + cross);
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
