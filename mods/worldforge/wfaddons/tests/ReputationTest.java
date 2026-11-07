package dev.worldforge.tests;

import dev.worldforge.dialogue.*;
import dev.worldforge.reputation.*;
import dev.worldforge.reputation.bridge.DialogueBridge;

import java.util.*;

public class ReputationTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    public static void main(String[] a) {
        ReputationStore s = new ReputationStore();
        s.loadFactions("""
            {"factions":[{"id":"house:stark","name":"Старк","parent":"realm:north","factor":0.5},
                         {"id":"realm:north","name":"Север","parent":"realm:iron_throne","factor":0.5},
                         {"id":"realm:iron_throne","name":"Железный трон"}]}
            """);
        List<String> log = new ArrayList<>();
        s.addListener((p, t, o, n, r) -> log.add(t + ":" + o + ">" + n));
        s.add("p1", "house:stark", 40, "помощь");
        ok(s.get("p1", "house:stark") == 40 && s.get("p1", "realm:north") == 20 && s.get("p1", "realm:iron_throne") == 10, "отклик вверх по цепочке: 40 -> 20 -> 10");
        ok(s.standing("p1", "house:stark") == Standing.FRIENDLY && s.standing("p1", "realm:north") == Standing.FRIENDLY && Standing.of(60) == Standing.ALLIED && s.standing("p1", "realm:north") == Standing.FRIENDLY && s.standing("p1", "realm:iron_throne") == Standing.NEUTRAL, "уровни отношения");
        s.add("p1", "house:stark", 1, "мелочь");
        ok(s.get("p1", "house:stark") == 41 && s.get("p1", "realm:north") == 20, "мелкое изменение не передаётся (округление к нулю)");
        s.add("p1", "house:stark", 500, "щедрость");
        ok(s.get("p1", "house:stark") == 100, "значение ограничено 100");
        s.add("p2", "house:stark", -80, "обида");
        ok(s.standing("p2", "house:stark") == Standing.HOSTILE && s.get("p2", "realm:north") == -40 && s.get("p2", "realm:iron_throne") == -20, "негативный отклик тоже передаётся");
        ok(s.nameOf("house:stark").equals("Старк") && s.nameOf("house:unknown").equals("house:unknown"), "названия фракций");
        ok(!log.isEmpty() && log.get(0).equals("house:stark:0>40"), "слушатель получает старое и новое значение: " + log.get(0));

        boolean cyc = false;
        try { s.link("realm:iron_throne", "house:stark", 0.5); } catch (IllegalArgumentException e) { cyc = true; }
        ok(cyc, "цикл связей запрещён");

        s.decay(30);
        ok(s.get("p1", "house:stark") == 70 && s.get("p2", "house:stark") == -50, "забвение сдвигает к нулю");
        s.decay(1000);
        ok(s.all("p1").isEmpty() && s.all("p2").isEmpty(), "полностью забытое удаляется");

        s.add("p3", "house:stark", 30, "x");
        String json = s.toJson();
        ReputationStore r = new ReputationStore();
        r.fromJson(json);
        ok(r.get("p3", "house:stark") == 30 && r.get("p3", "realm:north") == 15 && r.toJson().equals(json), "сохранение и загрузка без потерь");

        // мост к диалогам
        DialogueBridge.register();
        Dialogue d = Dialogue.parse("""
            {"id":"stark","start":"a","nodes":{
              "a":{"text":"Что вам нужно?","choices":[
                {"text":"Помочь вам","next":"help","do":[{"do":"rep_add","target":"house:stark","value":20,"reason":"клятва"}]},
                {"text":"Запросить помощь","next":"aid","if":[{"if":"rep_at_least","target":"house:stark","value":30}]},
                {"text":"Вы мне друг?","next":"friend","if":[{"if":"standing_is","target":"house:stark","name":"Дружелюбен"}]}]},
              "help":{"text":"Благодарю.","next":"a"},"aid":{"text":"Вот отряд."},"friend":{"text":"Да."}}}
            """);
        ok(d.validate().isEmpty(), "диалог с условиями репутации проходит проверку");
        ReputationStore live = new ReputationStore();
        Ctx ctx = new Ctx(null).var("player_id", "p9").service(ReputationStore.class, live);
        Session ses = new Session(d, ctx);
        ok(ses.view().choices.equals(List.of("Помочь вам")), "пока репутации нет, лишних ответов нет: " + ses.view().choices);
        ses.choose(0); ses.choose(0);
        ok(live.get("p9", "house:stark") == 20 && ses.view().choices.equals(List.of("Помочь вам", "Вы мне друг?")), "после +20 появился ответ про дружбу: " + ses.view().choices);
        ses.choose(0); ses.choose(0);
        ok(live.get("p9", "house:stark") == 40 && ses.view().choices.size() == 3, "после +40 доступна просьба о помощи: " + ses.view().choices);
        boolean noPlayer = false;
        try { new Session(d, new Ctx(null).service(ReputationStore.class, live)).view(); new Session(d, new Ctx(null).service(ReputationStore.class, live)).choose(0); } catch (IllegalStateException e) { noPlayer = true; }
        ok(noPlayer, "без player_id в контексте понятная ошибка");
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ПРОВАЛОВ: " + fails);
        if (fails > 0) System.exit(1);
    }
}
