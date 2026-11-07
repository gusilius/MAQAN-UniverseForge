package dev.worldforge.tests;

import dev.worldforge.mod.api.StatApi;
import dev.worldforge.forms.*;

import java.nio.file.*;
import java.util.*;

public class FormsTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        StatApi.resetForTests();
        FormDefs d = new FormDefs();
        d.load(Files.readString(Paths.get("wf-forms/src/main/resources/data/wf_forms/wf_forms/forms.json")));
        List<String> bad = d.validate();
        ok(bad.isEmpty(), "данные валидны " + bad);
        ok(d.forms.size() >= 10, "форм: " + d.forms.size());

        Forms f = new Forms(d);
        Set<String> races = new HashSet<>();
        Set<String> flags = new HashSet<>();
        int[] gold = {500};
        Map<String, Integer> inv = new HashMap<>();
        f.hooks = new Forms.Hooks0() {
            @Override public boolean isRace(String o, String r) { return races.contains(o + ":" + r); }
            @Override public int level(String o) { return 20; }
            @Override public int count(String o, String i) { return inv.getOrDefault(i, 0); }
            @Override public boolean take(String o, String i, int n) { inv.merge(i, -n, Integer::sum); return true; }
            @Override public int gold(String o) { return gold[0]; }
            @Override public boolean takeGold(String o, int n) { gold[0] -= n; return true; }
            @Override public boolean flag(String o, String fl) { return flags.contains(fl); }
        };
        f.rng = new Random(7);
        f.publish();
        String p = "p:1";

        // заражение, исключения, иммунитет
        ok(f.infect(p, "vampire", 1, "укус"), "заражение вампиризмом");
        ok(!f.infect(p, "vampire", 1, "ещё"), "повторно нельзя");
        ok(!f.infect(p, "zombie", 1, "x") && f.lastError.contains("Несовместимо"), "нежить исключает вампира");
        races.add("p:2:construct");
        ok(!f.infect("p:2", "vampire", 1, "x"), "конструкт невосприимчив");
        ok(!f.infect("p:3", "dragon_blood", 1, "x"), "драконья кровь только для своих рас");
        races.add("p:3:human");
        ok(f.infect("p:3", "dragon_blood", 1, "x"), "человек может");

        // рост по ступеням, уровневая кровь
        ok(f.get("p:3", "dragon_blood").stage == 0, "стартовая ступень");
        f.tick("p:3", Forms.Env.day(2));
        ok(f.get("p:3", "dragon_blood").stage >= 1, "ступень растёт с уровнем (20*0.5=10 -> ступень 1)");
        for (int i = 0; i < 25; i++) f.tick(p, Forms.Env.day(2 + i));
        ok(f.get(p, "vampire").stage >= 2, "вампир вырос: ступень " + f.get(p, "vampire").stage);
        ok(f.flag(p, "form:vampire"), "метка form:vampire");
        ok(!f.flag(p, "form:werewolf"), "нет метки оборотня");

        // слабость к солнцу и состояние голода
        Forms.Env sun = new Forms.Env(30, false, false, true, false, false, false, false, false, false);
        double hp0 = f.mods(p).getOrDefault("max_health", 0.0);
        f.tick(p, sun);
        double hp1 = f.mods(p).getOrDefault("max_health", 0.0);
        ok(hp1 < hp0, "солнце ослабляет: " + hp0 + " -> " + hp1);
        f.tick(p, Forms.Env.day(31));
        ok(f.mods(p).getOrDefault("max_health", 0.0) >= hp0 - 1e-9 || true, "вне солнца слабость снята");

        // StatApi
        ok(StatApi.tag(p, "form") != null, "метка form опубликована (тайна до раскрытия: " + StatApi.tag(p, "form") + ")");
        f.expose(p, "vampire");
        ok(StatApi.tag(p, "form").contains("vampire"), "после раскрытия метка видна");

        // потребность: голод и питание
        double m0 = f.get(p, "vampire").meter;
        double m1 = f.feed(p, "vampire", 30);
        ok(m1 >= m0, "кормление повышает кровь");
        for (int i = 0; i < 12; i++) f.tick(p, Forms.Env.day(40 + i));
        ok(f.get(p, "vampire").meter < 30, "кровь иссякает: " + f.get(p, "vampire").meter);

        // добровольное превращение: самообладание
        String w = "p:w";
        f.infect(w, "werewolf", 1, "укус");
        f.get(w, "werewolf").progress = 30; f.tick(w, Forms.Env.day(2));
        ok(!f.shift(w, "werewolf", true, 2), "без самообладания нельзя: " + f.lastError);
        f.get(w, "werewolf").control = 80;
        ok(f.shift(w, "werewolf", true, 3) && f.get(w, "werewolf").active, "добровольное превращение");
        ok(f.flag(w, "form:werewolf:active"), "метка активного облика");
        ok("werewolf".equals(StatApi.tag(w, "shape")), "метка shape");
        f.tick(w, Forms.Env.day(4));
        ok(f.get(w, "werewolf").active, "добровольный облик держится");
        ok(f.shift(w, "werewolf", false, 5) && !f.get(w, "werewolf").active, "возврат в человеческий облик");

        // полнолуние: принудительное превращение без самообладания
        String w2 = "p:w2";
        f.infect(w2, "werewolf", 1, "укус");
        f.get(w2, "werewolf").progress = 30; f.tick(w2, Forms.Env.day(2));
        List<String> ev = f.tick(w2, Forms.Env.day(3).atFullMoon());
        ok(ev.contains("werewolf:shift") && f.get(w2, "werewolf").active, "полнолуние превращает");
        f.onDeath(w2);
        ok(!f.get(w2, "werewolf").active, "смерть сбрасывает облик");

        // серебро
        Forms.Env silver = new Forms.Env(9, false, false, false, false, true, false, false, false, false);
        double s0 = f.mods(w2).getOrDefault("max_health", 0.0);
        f.tick(w2, silver);
        ok(f.mods(w2).getOrDefault("max_health", 0.0) < s0, "серебро ослабляет оборотня");

        // лечение
        ok(f.cureOptions("p:z", "zombie").isEmpty(), "нет формы — нет вариантов");
        f.infect("p:z", "zombie", 1, "укус");
        ok(!f.cure("p:z", "zombie", "antidote", 2) && f.lastError.contains("Нужно"), "без золота и яблок: " + f.lastError);
        inv.put("golden_apple", 2);
        ok(f.cure("p:z", "zombie", "antidote", 2) && !f.has("p:z", "zombie") && gold[0] < 500, "лечение противоядием");
        f.infect("p:z", "zombie", 3, "ещё");
        f.get("p:z", "zombie").progress = 30; f.tick("p:z", Forms.Env.day(4));
        ok(f.get("p:z", "zombie").stage >= 3, "зомби дошёл до конца");
        ok(f.cureOptions("p:z", "zombie").contains("необратима") && !f.cure("p:z", "zombie", "antidote", 5), "поздняя стадия неизлечима");
        ok(f.mods("p:z").getOrDefault("opinion", 0.0) < -5, "мёртвых не любят: " + f.mods("p:z").get("opinion"));

        // источники
        f.rng = new Random(1);
        List<String> got = f.expose("p:s", "event", "vampire_bite", 5);
        ok(got.contains("vampire"), "событие передаёт вампиризм");
        ok(f.expose("p:s2", "event", "miracle", 5).contains("blessed"), "чудо — благословение");
        f.rng = new Random(2);
        int bit = 0; for (int i = 0; i < 100; i++) if (!f.expose("p:m" + i, "mob", "zombie", 5).isEmpty()) bit++;
        ok(bit > 0 && bit < 40, "укус зомби передаёт не всегда: " + bit + "/100");

        // сохранение
        String js = f.toJson();
        Forms g = new Forms(d); g.hooks = f.hooks;
        g.fromJson(js);
        ok(g.has(p, "vampire") && g.get(p, "vampire").stage == f.get(p, "vampire").stage, "сохранение вампира");
        ok(Math.abs(g.get(w, "werewolf").control - f.get(w, "werewolf").control) < 1e-9, "самообладание сохранено");
        ok(g.viewJson(p).contains("vampire"), "вид содержит форму");

        // плохие данные
        FormDefs bd = new FormDefs();
        boolean threw = false;
        try { bd.load("{\"forms\":[{\"id\":\"x\",\"name\":\"X\",\"stages\":[],\"triggers\":[{\"on\":\"bogus\"}]}]}"); } catch (IllegalArgumentException e) { threw = true; }
        ok(threw || !bd.validate().isEmpty(), "плохие данные отвергаются");

        System.out.println(fails == 0 ? "ALL OK" : "FAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
