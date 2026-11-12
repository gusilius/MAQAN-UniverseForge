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
                .stream().filter(action -> action.id.equals("marry")).findFirst().orElseThrow();
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
        boolean registryViewReadOnly = false;
        try { back.all().clear(); } catch (UnsupportedOperationException readOnlyFailure) { registryViewReadOnly = true; }
        ok(registryViewReadOnly && back.size() == reg.size(),
                "публичная коллекция персонажей не позволяет обходить операции реестра");
        String invalidAliveJson = js.replaceFirst("\\\"alive\\\":(?:true|false)", "\\\"alive\\\":\\\"yes\\\"");
        boolean invalidAliveRejected = false;
        try { back.fromJson(invalidAliveJson); } catch (IllegalArgumentException invalidSave) { invalidAliveRejected = true; }
        ok(invalidAliveRejected && back.toJson().equals(js),
                "неверный тип alive отклоняется без повреждения действующего реестра");
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


        // Canonical Character lifecycle journal is shared by every subsystem and persisted with Character.props.
        CharacterRegistry journal = new CharacterRegistry();
        Character carrier = journal.create("Переносчик", "", "", "m", 1);
        int[] lifecycleCallbacks = {0, 0};
        journal.addListener(new CharacterRegistry.Listener() {
            @Override public void lifecycle(Character person, CharacterRegistry.LifecycleEvent event) { lifecycleCallbacks[0]++; }
        });
        journal.addListener(new CharacterRegistry.Listener() {
            @Override public void lifecycle(Character person, CharacterRegistry.LifecycleEvent event) { lifecycleCallbacks[1]++; }
        });
        Character journalPerson = journal.create("Житель журнала", "", "", "m", 1);
        CharacterInventory.add(journalPerson,"minecraft:bread",8);
        CharacterInventory.add(journalPerson,"minecraft:iron_sword",1,"{Damage:3}");
        ok(CharacterInventory.count(journalPerson,"minecraft:bread")==8
                        &&CharacterInventory.items(journalPerson).size()==2,
                "canonical Character inventory stores stack counts and distinct item data");
        ok(CharacterInventory.transfer(journalPerson,carrier,"minecraft:bread",3)
                        &&CharacterInventory.count(journalPerson,"minecraft:bread")==5
                        &&CharacterInventory.count(carrier,"minecraft:bread")==3,
                "inventory items transfer between two canonical people without duplicating identity");
        ok(!CharacterInventory.remove(journalPerson,"minecraft:bread",99)
                        &&CharacterInventory.count(journalPerson,"minecraft:bread")==5,
                "failed inventory removal is atomic and cannot create negative stock");
        CharacterDevelopment.setMilitaryMorale(journalPerson, 15);
        journalPerson.props.put("wf_unit_kind", "soldier");
        int baseMartial = CharacterStats.value(journalPerson, "martial");
        List<String> generatedTraits = CharacterStats.traits(journalPerson);
        ok(generatedTraits.size() >= 3 && generatedTraits.size() <= 5
                        && CharacterStats.compatibleTraits(generatedTraits)
                        && generatedTraits.equals(CharacterStats.traits(journalPerson)),
                "personality generation yields 3-5 deterministic, internally compatible traits");
        Character personality = journal.create("Последовательный", "", "", "m", 1);
        personality.traits.add("brave");
        ok(!CharacterStats.addTrait(personality, "craven")
                        && CharacterStats.traits(personality).equals(List.of("brave")),
                "adding a contradictory personality trait is rejected without replacing established traits");
        Character legacyPersonality = journal.create("Старый профиль", "", "", "m", 1);
        legacyPersonality.traits.add("brave");
        legacyPersonality.traits.add("craven");
        CharacterStats.ensure(journal);
        ok(legacyPersonality.traits.equals(Set.of("brave")),
                "legacy contradictory trait combinations are normalized deterministically on ensure");
        ok(CharacterStats.allTraits().size() >= 50
                        && CharacterStats.trait("paranoid") != null
                        && CharacterStats.trait("honest") != null
                        && CharacterStats.trait("adaptable") != null,
                "expanded personality catalog exposes broad social, ethical and emotional dimensions");
        ok(CharacterStats.allSkills().size() >= 25
                        && CharacterStats.skill("swordsmanship").governingAttribute().equals("martial")
                        && CharacterStats.skill("persuasion").governingAttribute().equals("diplomacy"),
                "skill catalog maps specialized abilities to the five core attributes");
        int ranksBefore = PopulationSimulation.skillRank(journalPerson, "swordsmanship");
        ok(CharacterStats.trainSkill(journalPerson, "swordsmanship", 250) == 2
                        && PopulationSimulation.skillRank(journalPerson, "swordsmanship") == ranksBefore + 2,
                "character skill growth uses the existing PopulationSimulation rank and XP store");
        ok(CharacterDevelopment.militaryMorale(journalPerson) == 15
                        && CharacterDevelopment.effectiveAttribute(journalPerson, "martial", 0) == Math.max(0, baseMartial - 3),
                "soldier morale is persisted on canonical Character and affects effective martial ability");
        CharacterDevelopment.setMilitaryMorale(journalPerson, 95);
        ok(CharacterDevelopment.effectiveAttribute(journalPerson, "martial", 0) == Math.min(20, baseMartial + 1),
                "high soldier morale positively affects the same character's effective martial ability");

        int severity=CharacterDevelopment.recordInjury(journalPerson,2,"arrow",120);
        ok(severity==2&&CharacterDevelopment.injurySeverity(journalPerson)==2
                        &&CharacterDevelopment.injuryCount(journalPerson)==1
                        &&Math.abs(CharacterDevelopment.injuryCombatMultiplier(journalPerson)-0.82)<1e-9,
                "combat injury is persisted on canonical Character and reduces combat readiness");
        CharacterDevelopment.recordInjury(journalPerson,1,"sword",121);
        ok(CharacterDevelopment.injurySeverity(journalPerson)==3&&CharacterDevelopment.injuryCount(journalPerson)==2,
                "repeated wounds accumulate and retain individual injury history");
        CharacterDevelopment.healInjury(journalPerson,2);
        ok(CharacterDevelopment.injurySeverity(journalPerson)==1,
                "medical recovery gradually reduces injury severity without erasing history");
        CharacterDevelopment.healInjury(journalPerson,1);
        ok(CharacterDevelopment.injurySeverity(journalPerson)==0
                        &&CharacterDevelopment.injuryCount(journalPerson)==2
                        &&Math.abs(CharacterDevelopment.injuryCombatMultiplier(journalPerson)-1.0)<1e-9,
                "fully healed soldier regains readiness while keeping lifetime injury count");

        journal.appoint(journalPerson.id, "guard", "settlement:test", 10);
        journal.dismiss(journalPerson.id, "guard", "settlement:test", 11);
        journal.kill(journalPerson.id);
        List<Map<String, Object>> lifeHistory = journal.recentLifecycle(journalPerson.id, 20);
        ok(lifeHistory.size() == 4
                        && "BORN".equals(lifeHistory.get(0).get("kind"))
                        && "POST_APPOINTED".equals(lifeHistory.get(1).get("kind"))
                        && "POST_DISMISSED".equals(lifeHistory.get(2).get("kind"))
                        && "DIED".equals(lifeHistory.get(3).get("kind")),
                "one canonical per-character journal records birth, appointment, dismissal and death");
        ok(lifecycleCallbacks[0] == 4 && lifecycleCallbacks[1] == 4,
                "lifecycle event bus notifies each listener exactly once per transition");
        String journalSave = journal.toJson();
        CharacterRegistry journalReload = new CharacterRegistry();
        journalReload.fromJson(journalSave);
        List<Map<String, Object>> restoredHistory = journalReload.recentLifecycle(journalPerson.id, 20);
        ok(restoredHistory.size() == lifeHistory.size()
                        && restoredHistory.stream().map(e -> String.valueOf(e.get("kind"))).toList()
                        .equals(lifeHistory.stream().map(e -> String.valueOf(e.get("kind"))).toList()),
                "canonical character event sequence survives save/load without synthetic restore events");
        ok(journalReload.toJson().equals(journalSave),
                "loading a character registry does not mutate its persisted lifecycle history");
        ok(CharacterInventory.count(journalReload.get(journalPerson.id),"minecraft:bread")==5
                        &&CharacterInventory.count(journalReload.get(carrier.id),"minecraft:bread")==3
                        &&CharacterInventory.items(journalReload.get(journalPerson.id)).stream()
                        .anyMatch(item->item.id().equals("minecraft:iron_sword")&&item.data().equals("{Damage:3}")),
                "canonical personal inventories and item data survive CharacterRegistry save/load");

        Character heir = journal.create("Наследник", "", "", "m", 20);
        Character parent = journal.create("Умерший житель", "", "", "m", 0);
        parent.props.put("population.location","settlement:oak");
        CharacterInventory.add(parent,"minecraft:iron_ingot",12);
        CharacterInventory.add(parent,"minecraft:iron_sword",1,"{Damage:7}");
        journal.marry(parent.id,heir.id,30);
        PopulationSimulation.die(journal, parent.id, 31, "illness");
        ok(!parent.alive&&CharacterInventory.count(parent,"minecraft:iron_ingot")==0
                        &&CharacterInventory.count(heir,"minecraft:iron_ingot")==12
                        &&CharacterInventory.items(heir).stream().anyMatch(item->item.id().equals("minecraft:iron_sword")
                        &&item.data().equals("{Damage:7}"))
                        &&heir.id.equals(parent.props.get("estate.recipient")),
                "death from illness transfers the deceased's complete item estate to the surviving spouse");
        Character solitary = journal.create("Одинокий житель", "", "", "m", 0);
        solitary.props.put("population.location","settlement:oak");
        CharacterInventory.add(solitary,"minecraft:bread",4);
        PopulationSimulation.die(journal, solitary.id, 32, "natural");
        ok(!solitary.alive&&CharacterInventory.count(solitary,"minecraft:bread")==4
                        &&"awaiting_kin_or_home_claim".equals(solitary.props.get("estate.status"))
                        &&"settlement:oak".equals(solitary.props.get("estate.home")),
                "death without heirs preserves all belongings as a recoverable estate at the recorded home");
        Character laterKin = journal.create("Поздний наследник", "", "", "m", 40);
        Character descendant = journal.addChild(laterKin,solitary,"Потомок", "m", 41);
        ok(CharacterInventory.claimEstate(solitary,descendant)
                        &&CharacterInventory.count(solitary,"minecraft:bread")==0
                        &&CharacterInventory.count(descendant,"minecraft:bread")==4
                        &&"inherited".equals(solitary.props.get("estate.status")),
                "a later-registered descendant can claim the preserved home estate exactly once");
        ok(!CharacterInventory.claimEstate(solitary,heir),
                "estate cannot be claimed again after inheritance has been settled");

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
