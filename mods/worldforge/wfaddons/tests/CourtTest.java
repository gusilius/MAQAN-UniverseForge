package dev.worldforge.tests;

import dev.worldforge.mod.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.court.*;
import dev.worldforge.settle.ResidentLifeEvents;
import dev.worldforge.realms.Statecraft;

import java.nio.file.*;
import java.util.*;

public class CourtTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static CourtSim build(String pack, long seed) throws Exception {
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(pack)) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        RoleDefs defs = PeopleTest.roles();
        NameBank names = PeopleTest.names();
        TitleEditor ed = new TitleEditor(d);
        CharacterRegistry reg = new CharacterRegistry();
        Courts.ensure(d, ed, reg, defs, names, seed, 300);
        CourtSim s = new CourtSim(d, ed, reg, defs, names, seed, 300);
        try (var st = Files.list(Paths.get("wf-court/src/main/resources/data/wf_court/wf_events"))) { for (Path p : (Iterable<Path>) st.sorted()::iterator) s.addEvent(Files.readString(p)); }
        return s;
    }

    public static void main(String[] a) throws Exception {
        CourtSim s = build(a[0], 7);
        ok(s.events.size() >= 10, "события загружены из файлов: " + s.events.size());

        // Statecraft mirrors the map's feudal hierarchy, and must release contracts when
        // the authoritative visible map relationship is removed.
        Statecraft political = new Statecraft();
        political.syncMapTitles("court-test", s.d);
        TitleData.Realm mapVassal = s.d.realmList().stream()
                .filter(r -> r.overlordId != null && !r.overlordId.isBlank()).findFirst().orElse(null);
        if (mapVassal == null) {
            List<TitleData.Realm> freeForContract = s.d.realmList().stream()
                    .filter(r -> r.overlordId == null && !r.titleKeys.isEmpty()).toList();
            if (freeForContract.size() >= 2) {
                s.ed.setOverlord(freeForContract.get(1).id, freeForContract.get(0).id, "vassal");
                mapVassal = freeForContract.get(1);
            }
        }
        // Re-import after creating a synthetic test relationship above.
        political.syncMapTitles("court-test", s.d);
        boolean vassalContractImported = mapVassal != null
                && political.vassalage(mapVassal.id) != null
                && political.vassalage(mapVassal.id).liegeRealm.equals(mapVassal.overlordId);
        ok(vassalContractImported, "map vassal hierarchy imports into the canonical Statecraft contract");
        if (mapVassal != null) {
            ok(political.releaseVassal(mapVassal.id) && political.vassalage(mapVassal.id) == null,
                    "releasing a canonical feudal contract removes its tribute obligation");
            s.ed.setOverlord(mapVassal.id, null, null);
            political.syncMapTitles("court-test", s.d);
            ok(political.vassalage(mapVassal.id) == null,
                    "a released map relationship does not recreate a stale tribute contract");
        }

        // Court and settlement clocks share one CharacterRegistry. A birth resolved by either
        // subsystem must be visible to the other so the same mother cannot give birth twice.
        boolean sharedBirthGuard = false;
        for (long seed = 1; seed <= 100 && !sharedBirthGuard; seed++) {
            CharacterRegistry family = new CharacterRegistry();
            Character mother = family.create("Мать", "Дом", "north", "f", 270);
            Character father = family.create("Отец", "Дом", "north", "m", 270);
            family.marry(mother.id, father.id, 300);
            CourtSim familyCourt = new CourtSim(s.d, null, family, s.defs, s.names, seed, 300);
            familyCourt.day = 364;
            familyCourt.tick(1);
            if (mother.children.isEmpty()) continue; // choose a deterministic seed that produces the annual birth
            int childrenAfterCourt = mother.children.size();
            ok(((Number) mother.props.get("family.last_birth_year")).intValue() == 301,
                    "court birth publishes the shared family-year marker");
            ResidentLifeEvents.yearly(family, 301);
            ok(mother.children.size() == childrenAfterCourt,
                    "settlement family pass does not duplicate a birth already resolved by court simulation");
            sharedBirthGuard = true;
        }
        ok(sharedBirthGuard, "shared court/settlement family lifecycle produces a reproducible annual birth for integration testing");

        // совет
        String realm = null;
        for (TitleData.Realm r : s.d.realmList()) if (!r.subjectIds.isEmpty()) { realm = r.id; break; }
        if (realm == null) realm = s.d.realmList().get(0).id;
        var seats = Council.seats(s.reg, s.defs, realm);
        ok(seats.size() == 4 && seats.stream().allMatch(x -> x.holder != null), "в совете 4 места, все заняты (кастелян - по баронствам)");
        var eff = s.council(realm);
        ok(eff.containsKey("income_pct") && eff.containsKey("levy_pct"), "эффекты совета: " + eff);
        Character steward = s.reg.holder("steward", Courts.realmPlace(realm));
        double before = s.council(realm).get("income_pct");
        steward.props.put("stat.stewardship", 20);
        ok(s.council(realm).get("income_pct") > before, "лучший управляющий даёт больший доход");
        s.syncProps();
        ok(s.d.realms.get(realm).props.get("council.income_pct") instanceof Number, "эффекты записаны в свойства государства");

        // мнения
        Character a1 = s.ruler(realm), b1 = steward;
        int o0 = s.opinion(b1.id, a1.id);
        s.opinions.add(b1.id, a1.id, "Тест", -30, s.year() + 3);
        ok(s.opinion(b1.id, a1.id) == o0 - 30, "поправка мнения учитывается");
        s.opinions.expire(s.year() + 3);
        ok(s.opinion(b1.id, a1.id) == o0, "поправка истекает");
        ok(!s.opinions.of(s.reg, b1.id, a1.id, s.year()).reasons.isEmpty() || o0 == 0, "причины мнения перечислены");

        // козни
        Character v = s.reg.holder("chancellor", Courts.realmPlace(realm));
        Scheme sc = s.startScheme(a1.id, v.id, Scheme.Kind.MURDER);
        ok(s.schemeChance(sc) >= 0.05 && s.schemeChance(sc) <= 0.95, "шанс козней ограничен диапазоном 5–95%: " + Math.round(s.schemeChance(sc) * 100) + "%");
        boolean tooMany = false;
        s.startScheme(a1.id, steward.id, Scheme.Kind.BEFRIEND);
        try { s.startScheme(a1.id, b1.id, Scheme.Kind.SWAY); } catch (TitleEditor.EditException e) { tooMany = true; }
        ok(tooMany, "не больше двух козней");
        s.tick(120);
        ok(s.schemes.isEmpty() && s.news.stream().anyMatch(x -> x.contains("Козни")), "козни завершились и попали в новости");

        // смерть и наследование
        CourtSim t = build(a[0], 11);
        String r2 = t.d.realmList().get(1).id;
        Character old = t.ruler(r2);
        t.die(old, "умер");
        Character neu = t.ruler(r2);
        ok(neu != null && neu != old && neu.alive, "у государства новый правитель после смерти");
        TitleData.Title highestRealmTitle = null;
        for (String key : t.d.realms.get(r2).titleKeys) {
            TitleData.Title candidate = t.d.findTitle(key);
            if (candidate != null && (highestRealmTitle == null || candidate.tier > highestRealmTitle.tier))
                highestRealmTitle = candidate;
        }
        Object holderId = highestRealmTitle == null ? null : highestRealmTitle.props.get("holderCharacterId");
        ok(highestRealmTitle != null && neu.globalId.equals(holderId),
                "succession publishes the heir's canonical Character UUID to the realm's highest political title");
        ok(t.news.stream().anyMatch(x -> x.contains("Наследует")), "сообщение о наследовании");

        // события для игрока
        CourtSim u = build(a[0], 21);
        String hr = u.d.realmList().get(2).id;
        u.humans.add(hr);
        for (int i = 0; i < 400 && u.pending.stream().noneMatch(p -> p.realm.equals(hr)); i++) u.tick(30);
        ok(u.pending.stream().anyMatch(p -> p.realm.equals(hr)), "игрок получил событие");
        var p0 = u.pending.stream().filter(p -> p.realm.equals(hr)).findFirst().get();
        ok(!u.text(p0).isEmpty() && !u.text(p0).contains("{"), "текст события подставлен: " + u.text(p0));
        u.choose(p0.id, 0);
        ok(u.pending.stream().noneMatch(p -> p.id == p0.id), "ответ закрывает событие");

        // фракции: недовольный вассал
        CourtSim fz = build(a[0], 5);
        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : fz.d.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty() && r.subjectIds.isEmpty()) free.add(r.id);
        String liege = free.get(0), v1 = free.get(1), v2 = free.get(2);
        fz.ed.setOverlord(v1, liege, "vassal"); fz.ed.setOverlord(v2, liege, "vassal");
        List<String> rebels = new ArrayList<>();
        fz.hooks = new CourtSim.Hooks() { @Override public void independence(String r, String l) { rebels.add(r); } };
        for (String vv : List.of(v1, v2)) fz.opinions.add(fz.ruler(vv).id, fz.ruler(liege).id, "Обида", -60, 0);
        fz.tick(30);
        ok(fz.factions.size() == 1 && fz.factions.values().iterator().next().members.size() == 2, "недовольные вассалы собрались во фракцию");
        fz.syncProps();
        Object publishedFactionPressure = fz.d.realms.get(liege).props.get("politics.faction_pressure");
        Object publishedFactionCount = fz.d.realms.get(liege).props.get("politics.faction_count");
        ok(publishedFactionPressure instanceof Number pressure && pressure.doubleValue() > 0
                        && publishedFactionCount instanceof Number count && count.intValue() == 1,
                "CourtSim publishes its actual faction pressure to the shared realm property bus");
        double pw = fz.factions.values().iterator().next().power;
        ok(pw > 0 && pw < 1, "мощь фракции " + Math.round(pw * 100) + "%");

        CourtSim contractLow = build(a[0], 51);
        contractLow.ed.setOverlord(v1, liege, "vassal");
        contractLow.opinions.add(contractLow.ruler(v1).id, contractLow.ruler(liege).id, "Обида", -30, 0);
        contractLow.d.realms.get(v1).props.put("politics.vassal_loyalty", 0);
        contractLow.tick(30);
        ok(contractLow.factions.size() == 1,
                "low feudal contract loyalty can organize a faction before ruler opinion becomes deeply hostile");

        CourtSim contractHigh = build(a[0], 52);
        contractHigh.ed.setOverlord(v1, liege, "vassal");
        contractHigh.opinions.add(contractHigh.ruler(v1).id, contractHigh.ruler(liege).id, "Обида", -30, 0);
        contractHigh.d.realms.get(v1).props.put("politics.vassal_loyalty", 100);
        contractHigh.tick(30);
        ok(contractHigh.factions.isEmpty(),
                "high contractual loyalty moderates faction recruitment under the same ruler opinion");
        int fid = fz.factions.values().iterator().next().id;
        for (int i = 0; i < 200 && rebels.isEmpty(); i++) fz.tick(30);
        ok(rebels.size() == 2, "фракция предъявила ультиматум: " + rebels.size() + " мятежников");
        ok(fz.factions.isEmpty(), "после ультиматума фракция распущена");
        // умиротворение и подавление
        CourtSim fy = build(a[0], 5);
        fy.ed.setOverlord(v1, liege, "vassal"); fy.ed.setOverlord(v2, liege, "vassal");
        fy.opinions.add(fy.ruler(v1).id, fy.ruler(liege).id, "Обида", -60, 0);
        fy.tick(30);
        ok(fy.factions.size() == 1, "одна фракция у одного вассала");
        int before1 = fy.opinion(fy.ruler(v1).id, fy.ruler(liege).id);
        fy.appease(liege, v1);
        ok(fy.opinion(fy.ruler(v1).id, fy.ruler(liege).id) == before1 + 20, "дары улучшают мнение на 20");
        int tries = 0;
        while (!fy.factions.isEmpty() && tries++ < 30) { try { fy.crush(liege, fy.factions.keySet().iterator().next()); } catch (TitleEditor.EditException e) { break; } }
        ok(fy.factions.isEmpty(), "заговор удалось подавить за " + tries + " попыток");

        // долгая симуляция: ИИ, жизнь, фракции без исключений
        CourtSim w = build(a[0], 33);
        int before0 = w.reg.size();
        List<String> struck = new ArrayList<>();
        w.hooks = new CourtSim.Hooks() { @Override public void independence(String realm, String liege) { struck.add(realm + "<" + liege); } };
        w.tick(365 * 60);
        ok(w.reg.size() > before0, "за 60 лет родились люди: " + before0 + " -> " + w.reg.size());
        ok(w.news.size() > 20, "новостей " + w.news.size());
        int deadRulers = 0, living = 0;
        for (TitleData.Realm r : w.d.realmList()) { Character c = w.ruler(r.id); if (c != null && c.alive) living++; }
        ok(living == w.d.realmList().size(), "после 60 лет у всех государств живой правитель");
        System.out.println("фракции: " + w.factions.size() + ", восстаний: " + struck.size());
        w.news.stream().skip(Math.max(0, w.news.size() - 5)).forEach(System.out::println);

        // срез двора для клиента
        CourtSim vs = build(a[0], 41);
        String vr = vs.d.realmList().get(3).id;
        vs.humans.add(vr);
        for (int i = 0; i < 400 && vs.pending.stream().noneMatch(p -> p.realm.equals(vr)); i++) vs.tick(30);
        String[] view = vs.viewJson(vr);
        CharacterRegistry creg = new CharacterRegistry();
        creg.fromJson(view[0]);
        ok(creg.size() < vs.reg.size() / 5, "срез мал: " + creg.size() + " из " + vs.reg.size());
        CourtSim cs = new CourtSim(vs.d, null, creg, vs.defs, new NameBank(), 0L, 300);
        for (String ev : vs.eventSources()) cs.addEvent(ev);
        cs.fromJson(view[1]);
        ok(Council.seats(creg, cs.defs, vr).size() == 4 && Council.seats(creg, cs.defs, vr).stream().allMatch(x -> x.holder != null), "на клиенте совет собирается");
        ok(cs.pending.stream().anyMatch(p -> p.realm.equals(vr) && !cs.text(p).isEmpty()), "на клиенте видно событие");
        ok(cs.ruler(vr) != null && cs.opinion(cs.ruler(vs.d.realmList().get(4).id).id, cs.ruler(vr).id) == vs.opinion(cs.ruler(vs.d.realmList().get(4).id).id, cs.ruler(vr).id), "мнения на клиенте те же");

        // сохранение
        String js = w.toJson();
        CourtSim x = build(a[0], 33);
        x.fromJson(js);
        ok(x.toJson().equals(js), "сохранение и загрузка двора совпадают");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
