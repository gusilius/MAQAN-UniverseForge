package dev.worldforge.tests;

import dev.worldforge.dialogue.*;

import java.util.*;

public class DialogueTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static final String TREE = """
        {"id": "ruler", "start": "hello", "nodes": {
          "hello": {"speaker": "npc", "text": "Приветствую, {player}! Вы в землях {realm}.",
                    "choices": [
                      {"text": "Я принёс клятву", "next": "oath_done", "if": [{"if": "flag", "name": "sworn"}]},
                      {"text": "Служить вам", "next": "oath", "if": [{"if": "not", "of": [{"if": "flag", "name": "sworn"}]}]},
                      {"text": "Расскажите о землях", "next": "lore"},
                      {"text": "Прощайте"}]},
          "oath": {"text": "Добро. Помните о клятве.", "do": [{"do": "set_flag", "name": "sworn"}], "next": "hello"},
          "oath_done": {"text": "Рад вас видеть снова."},
          "lore": {"text": "Земли обширны.", "next": "hello"},
          "lonely": {"text": "Сюда не попасть."}}}
        """;

    public static void main(String[] a) {
        Dialogue d = Dialogue.parse(TREE);
        List<String> problems = d.validate();
        ok(problems.size() == 1 && problems.get(0).contains("lonely"), "validate: только предупреждение о недостижимой реплике " + problems);

        Ctx ctx = new Ctx(new HashSet<>()).var("player", "Стив").var("realm", "Север");
        Session s = new Session(d, ctx);
        Session.View v = s.view();
        ok(v.text.equals("Приветствую, Стив! Вы в землях Север."), "подстановка переменных");
        ok(v.choices.equals(List.of("Служить вам", "Расскажите о землях", "Прощайте")), "условия скрывают ответ про клятву: " + v.choices);
        s.choose(0);
        ok(ctx.flags.contains("sworn"), "эффект set_flag сработал при входе в реплику");
        v = s.view();
        ok(v.cont && v.choices.isEmpty() && v.text.startsWith("Добро"), "реплика без ответов, с next: кнопка «Далее»");
        s.choose(0);
        v = s.view();
        ok(v.choices.get(0).equals("Я принёс клятву") && !v.choices.contains("Служить вам"), "после клятвы ответы изменились: " + v.choices);
        s.choose(0);
        v = s.view();
        ok(v.end && !s.ended(), "последняя реплика ждёт закрытия");
        s.choose(0);
        ok(s.ended() && s.view() == null, "разговор завершён");

        Session s2 = new Session(d, new Ctx(null));
        s2.choose(2);
        ok(s2.ended(), "выбор без next завершает разговор");
        boolean threw = false;
        try { new Session(d, new Ctx(null)).choose(9); } catch (IllegalArgumentException e) { threw = true; }
        ok(threw, "неверный номер ответа - ошибка");

        // свои условия и эффекты (так подключатся репутация и задания)
        DialogueApi.registerCondition("gold_at_least", (c, args) -> Integer.parseInt(c.vars.getOrDefault("gold", "0")) >= ((Number) args.get("value")).intValue());
        List<String> log = new ArrayList<>();
        DialogueApi.registerEffect("log", (c, args) -> log.add(String.valueOf(args.get("msg"))));
        Dialogue t = Dialogue.parse("""
            {"id":"shop","start":"a","nodes":{"a":{"text":"Купите?","do":[{"do":"log","msg":"вошёл"}],"choices":[
              {"text":"Купить","next":"b","if":[{"if":"gold_at_least","value":50}],"do":[{"do":"log","msg":"куплено"}]},
              {"text":"Уйти"}]},"b":{"text":"Спасибо."}}}
            """);
        ok(t.validate().isEmpty(), "своё условие и эффект проходят проверку");
        Session poor = new Session(t, new Ctx(null).var("gold", "10"));
        ok(poor.view().choices.equals(List.of("Уйти")), "своё условие скрывает ответ");
        Session rich = new Session(t, new Ctx(null).var("gold", "80"));
        rich.choose(0);
        ok(log.equals(List.of("вошёл", "вошёл", "куплено")) && rich.view().text.equals("Спасибо."), "эффекты выполняются в порядке: " + log);

        Dialogue bad = Dialogue.parse("{\"id\":\"x\",\"start\":\"q\",\"nodes\":{\"a\":{\"text\":\"t\",\"next\":\"zz\",\"do\":[{\"do\":\"нет_эффекта\"}],\"choices\":[]}}}");
        List<String> bp = bad.validate();
        ok(bp.stream().anyMatch(x -> x.contains("Нет стартовой")) && bp.stream().anyMatch(x -> x.contains("zz")) && bp.stream().anyMatch(x -> x.contains("нет_эффекта")), "validate находит ошибки: " + bp.size());

        Dialogue loop = Dialogue.parse("{\"id\":\"l\",\"start\":\"a\",\"nodes\":{\"a\":{\"text\":\"1\",\"next\":\"b\"},\"b\":{\"text\":\"2\",\"next\":\"a\"}}}");
        Session ls = new Session(loop, new Ctx(null));
        for (int i = 0; i < 500 && !ls.ended(); i++) ls.choose(0);
        ok(ls.ended(), "бесконечный цикл реплик обрывается");

        boolean dup = false;
        try { DialogueApi.registerEffect("log", (c, args) -> { }); } catch (IllegalStateException e) { dup = true; }
        ok(dup, "повторная регистрация id запрещена");

        // примеры диалогов из ресурсов аддона должны проходить validate (запуск из папки wfaddons)
        try { dev.worldforge.people.bridge.DialogueBridge.register(); } catch (IllegalStateException e) { /* уже зарегистрировано */ }
        try { dev.worldforge.relations.bridge.RelationsBridge.register(); } catch (IllegalStateException e) { /* уже зарегистрировано */ }
        dev.worldforge.quests.bridge.QuestsDialogueBridge.quests = new dev.worldforge.quests.Quests(new dev.worldforge.quests.QuestDefs());
        try { dev.worldforge.quests.bridge.QuestsDialogueBridge.register(); } catch (IllegalStateException e) { /* уже зарегистрировано */ }
        dev.worldforge.trade.bridge.TradeDialogueBridge.trade = new dev.worldforge.trade.Trade(new dev.worldforge.trade.TradeDefs());
        try { dev.worldforge.trade.bridge.TradeDialogueBridge.register(); } catch (IllegalStateException e) { /* уже зарегистрировано */ }
        java.nio.file.Path dir = java.nio.file.Path.of("wf-dialogue/src/main/resources/data/wf_dialogue/wf_dialogue");
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var st = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) st.filter(x -> x.toString().endsWith(".json")).sorted()::iterator) {
                    List<String> pr = new ArrayList<>();
                    try {
                        Dialogue sd = Dialogue.parse(java.nio.file.Files.readString(f));
                        for (String x : sd.validate()) if (!x.startsWith("Предупреждение")) pr.add(x);
                        Session ss = new Session(sd, new Ctx(new HashSet<>()).var("player", "Стив").var("npc", "Х").var("realm", "Север"));
                        if (ss.view() == null) pr.add("пустой старт");
                    } catch (Exception e) { pr.add(e.toString()); }
                    ok(pr.isEmpty(), "пример диалога " + f.getFileName() + " " + pr);
                }
            } catch (java.io.IOException e) { ok(false, "чтение примеров: " + e); }
        }
        System.out.println(fails == 0 ? "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ" : "ПРОВАЛОВ: " + fails);
        if (fails > 0) System.exit(1);
    }
}
