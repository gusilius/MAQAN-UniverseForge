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
        int firstRevision = v.revision;
        s.choose(0, firstRevision);
        boolean staleChoiceRejected = false;
        try { s.choose(0, firstRevision); } catch (IllegalStateException e) { staleChoiceRejected = true; }
        ok(staleChoiceRejected && s.view().revision > firstRevision,
                "повторный выбор по устаревшей ревизии отклонён без повторного эффекта");
        ok(ctx.flags.contains("sworn"), "эффект set_flag сработал при входе в реплику");
        Session deferred = new Session(d, new Ctx(new HashSet<>()), true);
        ok(deferred.view() == null, "deferred dialogue does not run entry effects before its journaled start");
        deferred.start();
        ok(deferred.view() != null, "journaled dialogue start exposes the first view");
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

        // Character-backed semantic memory: promises, requests, grievances, callbacks and canonical relationship scores.
        Map<String,Object> characterProps = new LinkedHashMap<>();
        DialogueMemory memory = new DialogueMemory(characterProps);
        memory.remember("player.saved_family", "Вы спасли мою семью");
        ok("Вы спасли мою семью".equals(memory.recall("player.saved_family")), "semantic fact is stored on owner Character.props");
        memory.putRecord("promise", "repair_bridge", Map.of("id","repair_bridge","status","open","text","Починить мост"));
        ok(memory.hasRecord("promise","repair_bridge") && "open".equals(memory.record("promise","repair_bridge").get("status")),
                "promise is a durable semantic obligation");
        ok(memory.resolveRecord("promise","repair_bridge","completed") && "resolved".equals(memory.record("promise","repair_bridge").get("status")),
                "promise can be resolved and remembered");
        memory.adjustRelationship("player-1", 12);
        ok(memory.relationship("player-1") == 12 && characterProps.containsKey("social.relationships"),
                "dialogue memory updates canonical Character social relationship state");
        Dialogue semantic = Dialogue.parse("""
            {"id":"semantic","start":"a","nodes":{
              "a":{"text":"{memory:player.saved_family}","do":[
                {"do":"remember","key":"player.met_before","value":"true"},
                {"do":"promise","id":"repair_roof","text":"Починить крышу","target":"player","due":"tomorrow"},
                {"do":"relationship_delta","target":"player-1","delta":5}],
                "choices":[{"text":"Спасибо","next":"b","if":[{"if":"record_open","category":"promise","id":"repair_roof"}]}]},
              "b":{"text":"Я помню нашу встречу: {memory:player.met_before}."}}}
            """);
        Ctx semanticCtx = new Ctx(null, memory).var("player_id","player-1");
        Session semanticSession = new Session(semantic, semanticCtx);
        ok(semanticSession.view().text.equals("Вы спасли мою семью") && semanticSession.view().choices.equals(List.of("Спасибо")),
                "NPC recalls a meaningful past event and surfaces an open promise in conversation");
        ok(memory.relationship("player-1") == 17 && memory.hasRecord("promise","repair_roof"),
                "dialogue effects alter relationship and create an actionable promise record");
        semanticSession.choose(0);
        ok(semanticSession.view().text.equals("Я помню нашу встречу: true."),
                "NPC can refer to a remembered fact in a later dialogue line");
        ok(memory.recent(10).stream().anyMatch(e -> "choice".equals(e.get("kind"))),
                "dialogue choice history is persisted with character memory");

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
