package dev.worldforge.tests;

import dev.worldforge.dialogue.*;
import dev.worldforge.mod.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.people.bridge.DialogueBridge;

import java.nio.file.*;
import java.util.*;

public class PeopleTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static RoleDefs roles() throws Exception {
        RoleDefs defs = new RoleDefs();
        Path dir = Paths.get("wf-people/src/main/resources/data/wf_people/wf_roles");
        try (var s = Files.list(dir)) { for (Path p : (Iterable<Path>) s.sorted()::iterator) defs.add(Files.readString(p)); }
        return defs;
    }

    static NameBank names() throws Exception {
        NameBank n = new NameBank();
        n.add(Files.readString(Paths.get("wf-people/src/main/resources/data/wf_people/wf_names/base.json")));
        return n;
    }

    public static void main(String[] a) throws Exception {
        String ns = "planets", nm = "game_of_thrones";
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/" + ns + "/worldforge_maps/" + nm + "/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        RoleDefs defs = roles();
        NameBank names = names();
        ok(defs.all().size() == 7 && defs.ruler().id.equals("ruler"), "роли загружены из файлов датапака: " + defs.all().size());

        TitleEditor ed = new TitleEditor(d);
        // один правитель задан вручную: он должен сохраниться
        String preset = d.realmList().get(0).id;
        ed.setRuler(preset, "Эйегон Завоеватель");
        CharacterRegistry reg = new CharacterRegistry();
        int created = Courts.ensure(d, ed, reg, defs, names, 42L, 300);
        int realms = d.realmList().size(), baronies = 0, counties = 0;
        for (TitleData.Title t : d.allTitles()) { if (t.tier == 0) baronies++; if (t.tier == 1) counties++; }
        ok(created >= realms * 5 + counties + baronies * 2, "создано " + created + " человек (государств " + realms + ", графств " + counties + ", баронств " + baronies + ")");
        boolean allRulers = true, houses = true;
        Set<String> rulerHouses = new HashSet<>();
        for (TitleData.Realm r : d.realmList()) {
            Character c = reg.holder("ruler", Courts.realmPlace(r.id));
            if (c == null || r.ruler.isBlank()) allRulers = false;
            else if (!r.id.equals(preset)) { if (c.house.isEmpty() || !rulerHouses.add(c.house)) houses = false; }
        }
        ok(allRulers, "у каждого государства есть правитель и запись в данных");
        ok(houses, "у правителей разные дома (" + rulerHouses.size() + ")");
        ok(reg.holder("ruler", Courts.realmPlace(preset)).name.equals("Эйегон Завоеватель") && d.realms.get(preset).ruler.equals("Эйегон Завоеватель"), "вручную заданный правитель сохранён");
        int again = Courts.ensure(d, ed, reg, defs, names, 42L, 300);
        ok(again == 0, "повторный вызов ничего не дублирует");

        CharacterRegistry reg2 = new CharacterRegistry();
        TitleData d2 = d.copy();
        Courts.ensure(d2, null, reg2, defs, names, 42L, 300);
        CharacterRegistry reg3 = new CharacterRegistry();
        Courts.ensure(d2, null, reg3, defs, names, 7L, 300);
        ok(reg2.toJson().equals(reg.toJson()) || reg2.size() == reg.size(), "тот же seed - то же число людей");
        ok(!reg3.toJson().equals(reg2.toJson()), "другой seed - другие люди");

        // семья правителя
        Character ruler = reg.holder("ruler", Courts.realmPlace(d.realmList().get(1).id));
        Character sp = reg.get(ruler.spouse);
        ok(sp != null && sp.spouse.equals(ruler.id) && !sp.house.equals(ruler.house), "супруг из другого дома");
        Character unmarried = reg.create("Тестовый дворянин", "test_house", "test_culture", "m", 270);
        CharacterActions.Action marriage = CharacterActions.available(reg, defs, ruler, unmarried, 300)
                .stream().filter(a -> a.id.equals("marry")).findFirst().orElseThrow();
        ok(!marriage.enabled && marriage.reason.contains("брак"),
                "состоящий в браке инициатор не может заключить второй брак");
        Character widower = reg.create("Вдовец", "test_house", "test_culture", "m", 260);
        Character deceased = reg.create("Умершая супруга", "other_house", "test_culture", "f", 260);
        Character secondSpouse = reg.create("Вторая супруга", "third_house", "test_culture", "f", 260);
        reg.marry(widower.id, deceased.id);
        reg.kill(deceased.id);
        reg.marry(widower.id, secondSpouse.id);
        ok(widower.spouse.equals(secondSpouse.id) && secondSpouse.spouse.equals(widower.id)
                && deceased.spouse.isEmpty(), "вдовство позволяет повторный брак без устаревшей обратной связи");
        boolean selfMarriageRejected = false;
        try { reg.marry(widower.id, widower.id); } catch (IllegalArgumentException expected) { selfMarriageRejected = true; }
        ok(selfMarriageRejected, "самобрак отклоняется реестром персонажей");
        boolean kidsOk = true;
        for (String k : ruler.children.isEmpty() ? sp.children : ruler.children) { Character kid = reg.get(k); if (kid.birthYear < ruler.birthYear + 17 || kid.birthYear > 300) kidsOk = false; }
        ok(kidsOk, "возраст детей правдоподобен");

        // кто что разрешает
        String place = Courts.realmPlace(d.realmList().get(1).id);
        ok(reg.unlocked(defs, place, "war") && reg.unlocked(defs, place, "diplomacy") && !reg.unlocked(defs, place, "нет_такого"), "роли разрешают действия: war, diplomacy");
        Character marshal = reg.holder("marshal", place), ruler1 = reg.holder("ruler", place);
        reg.kill(marshal.id);
        ok(reg.unlocked(defs, place, "war"), "война всё ещё разрешена правителем после смерти маршала");
        reg.kill(ruler1.id);
        ok(!reg.unlocked(defs, place, "war") && reg.unlocked(defs, place, "diplomacy"), "без правителя и маршала война недоступна, дипломатия есть у канцлера");
        int refill = Courts.ensure(d, ed, reg, defs, names, 42L, 301);
        ok(refill == 2 && reg.unlocked(defs, place, "war"), "ensure заполняет освободившиеся должности: " + refill);

        // наследование
        CharacterRegistry r4 = new CharacterRegistry();
        TitleData d4 = d.copy();
        TitleEditor e4 = new TitleEditor(d4);
        Courts.ensure(d4, e4, r4, defs, names, 5L, 300);
        String heirRealm = null;
        for (TitleData.Realm r : d4.realmList()) { Character c = r4.holder("ruler", Courts.realmPlace(r.id)); if (c != null && !c.children.isEmpty()) { heirRealm = r.id; break; } }
        Character old = r4.holder("ruler", Courts.realmPlace(heirRealm));
        List<Character> kids = new ArrayList<>(); for (String k : old.children) kids.add(r4.get(k));
        List<Succession.Result> res = Succession.onDeath(r4, defs, d4, e4, names, 5L, old.id, 310, "primogeniture");
        Character nw = r4.holder("ruler", Courts.realmPlace(heirRealm));
        boolean sons = kids.stream().anyMatch(k -> k.gender.equals("m"));
        Character expected = kids.stream().sorted(Comparator.comparingInt((Character c) -> c.gender.equals("m") ? 0 : 1).thenComparingInt(c -> c.birthYear)).findFirst().get();
        ok(res.size() == 1 && nw == expected && !res.get(0).usurper && d4.realms.get(heirRealm).ruler.startsWith(nw.name), "наследник по примогенитуре (" + (sons ? "сын" : "дочь") + "), запись государства обновлена");
        Character lone = r4.holder("ruler", Courts.realmPlace(d4.realmList().get(2).id));
        for (String k : new ArrayList<>(lone.children)) r4.get(k).alive = false;
        for (Character c : r4.all()) if (c != lone && c.alive && !lone.father.isEmpty() && lone.father.equals(c.father)) c.alive = false;
        List<Succession.Result> res2 = Succession.onDeath(r4, defs, d4, e4, names, 5L, lone.id, 311, "primogeniture");
        ok(res2.size() == 1 && r4.holder("ruler", Courts.realmPlace(d4.realmList().get(2).id)) != null, "без детей преемник найден среди двора или узурпатор: " + res2.get(0).heir.name + (res2.get(0).usurper ? " (узурпатор)" : ""));
        boolean threw = false;
        try { r4.appoint(old.id, "ruler", "realm:x"); } catch (IllegalStateException e) { threw = true; }
        ok(threw, "мёртвого нельзя назначить");

        // сохранение
        String js = reg.toJson();
        CharacterRegistry back = new CharacterRegistry();
        back.fromJson(js);
        ok(back.size() == reg.size() && back.toJson().equals(js), "сохранение и загрузка без потерь (" + back.size() + " человек)");
        back.create("Новый", "", "", "m", 1);
        ok(back.get("c" + (reg.size() + 1)) != null, "счётчик id продолжается после загрузки");
        // Повреждённый снимок не должен очищать уже загруженный реестр.
        String beforeBadLoad = back.toJson();
        String duplicateIdJson = js.replace("\"id\":\"c2\"", "\"id\":\"c1\"");
        boolean duplicateRejected = false;
        try { back.fromJson(duplicateIdJson); } catch (IllegalArgumentException invalidSave) { duplicateRejected = true; }
        ok(duplicateRejected && back.toJson().equals(beforeBadLoad),
                "дублирующийся ID отклоняется без потери действующего реестра");
        String staleCounterJson = js.replaceFirst("\"next\":\\d+", "\"next\":1");
        CharacterRegistry staleCounter = new CharacterRegistry();
        staleCounter.fromJson(staleCounterJson);
        Character afterStaleCounter = staleCounter.create("После загрузки", "", "", "m", 1);
        ok(staleCounter.get(afterStaleCounter.id) == afterStaleCounter && !js.contains("\"id\":\"" + afterStaleCounter.id + "\""),
                "устаревший счётчик автоматически продолжает последовательность ID");


        // диалог с персонажем
        DialogueBridge.register();
        Character chancellor = reg.holder("chancellor", Courts.realmPlace(d.realmList().get(3).id));
        Dialogue dlg = Dialogue.parse("""
            {"id":"chancellor","start":"a","nodes":{"a":{"text":"Я {npc_title} земель {realm}, {player}.","choices":[
              {"text":"Вы из дома?","next":"h","if":[{"if":"npc_role","role":"chancellor"}]},
              {"text":"Ты правитель?","next":"h","if":[{"if":"npc_role","role":"ruler"}]}]},"h":{"text":"Дом {npc_house}."}}}
            """);
        ok(dlg.validate().isEmpty(), "диалог с условиями ролей проходит проверку");
        Ctx ctx = DialogueBridge.context(chancellor, defs, d, "Стив", "p1", new HashSet<>());
        Session s = new Session(dlg, ctx);
        ok(s.view().text.startsWith("Я Канцлер " + chancellor.name) && s.view().choices.equals(List.of("Вы из дома?")), "контекст персонажа: " + s.view().text);
        ok(DialogueBridge.dialogueId(chancellor, defs).equals("wf_people:chancellor") && DialogueBridge.dialogueId(reg.holder("villager", Courts.titlePlace(d.allTitles().stream().filter(t -> t.tier == 0).findFirst().get().id)), defs).equals("wf_people:villager"), "диалог выбирается по роли");

        boolean bad = false;
        try { defs.add("{\"id\":\"Bad Id\",\"name\":\"x\"}"); } catch (IllegalArgumentException e) { bad = true; }
        ok(bad, "недопустимый id роли отклоняется");
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ПРОВАЛОВ: " + fails);
        if (fails > 0) System.exit(1);
    }
}
