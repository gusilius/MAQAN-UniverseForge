package dev.worldforge.tests;

import dev.worldforge.progress.*;

import java.nio.file.*;
import java.util.*;

public class ProgressTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static ProgressDefs defs() throws Exception {
        ProgressDefs d = new ProgressDefs();
        try (var s = Files.list(Paths.get("wf-progress/src/main/resources/data/wf_progress/wf_progress"))) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p));
        }
        return d;
    }

    public static void main(String[] a) throws Exception {
        ProgressDefs d = defs();
        List<String> bad = new ArrayList<>(), warn = new ArrayList<>();
        for (String x : d.validate()) (x.startsWith("Предупреждение") ? warn : bad).add(x);
        ok(bad.isEmpty(), "данные корректны " + bad);
        ok(warn.isEmpty(), "нет предупреждений " + warn);
        ok(d.nodes.size() >= 200 && d.skills.size() >= 30 && d.attrs.size() >= 10 && d.archetypes.size() == 6, "объём: узлов " + d.nodes.size() + ", навыков " + d.skills.size() + ", атрибутов " + d.attrs.size() + ", архетипов " + d.archetypes.size());
        ok(d.xpFor(2) > d.xpFor(1) && d.xpFor(60) > d.xpFor(30), "кривая опыта растёт");

        Progress pr = new Progress(d);
        String P = "p:1";
        ok(pr.get(P, "max_health") >= 30 && pr.get(P, "max_health") < 40, "стартовое здоровье из базы и выносливости: " + pr.get(P, "max_health"));
        int up = pr.award(P, d.xpFor(1) + d.xpFor(2) + 5);
        Profile p = pr.profile(P);
        ok(up == 2 && p.level == 3 && p.attrFree == 4 && p.skillFree == 2 && p.passiveFree == 2, "два уровня: очки выданы (" + p.attrFree + "/" + p.skillFree + "/" + p.passiveFree + ")");
        double hp0 = pr.get(P, "max_health");
        ok(pr.addAttr(P, "vitality", 2) && pr.get(P, "max_health") > hp0, "атрибут даёт здоровье");
        ok(!pr.addAttr(P, "might", 5), "нельзя вложить больше очков, чем есть");
        ok(pr.canSkill(P, "siegecraft") != null, "осадное дело закрыто: " + pr.canSkill(P, "siegecraft"));
        ok(pr.learnSkill(P, "athletics"), "навык изучен");
        ok(p.skill("athletics") == 1 && pr.get(P, "move_speed_pct") > 0, "навык дал скорость");

        // дерево
        ok(pr.chooseArchetype(P, "warrior") && p.nodes.contains("warrior_start"), "архетип выдаёт старт");
        ok(pr.canNode(P, "warrior_1_5") != null, "далёкий узел недоступен: " + pr.canNode(P, "warrior_1_5"));
        ok(pr.allocate(P, "warrior_1_1") && p.passiveFree == 1, "соседний узел изучен");
        double dmg0 = pr.get(P, "melee_dmg_pct");
        ok(pr.allocate(P, "warrior_1_2") && pr.get(P, "melee_dmg_pct") >= dmg0, "цепочка узлов");
        ok(!pr.allocate(P, "warrior_1_3"), "нет очков: " + pr.lastError);
        ok(!pr.refund(P, "warrior_1_1") && pr.lastError.contains("держит"), "нельзя снять середину цепочки");
        ok(pr.refund(P, "warrior_1_2") && p.passiveFree == 1, "конец цепочки снимается бесплатно");
        // сброс за золото
        pr.hooks = new Progress.Hooks() { int g = 100; public int gold(String o) { return g; } public boolean takeGold(String o, int n) { if (g < n) return false; g -= n; return true; } };
        ok(pr.respecCost(P) == 30, "цена сброса " + pr.respecCost(P));
        ok(pr.respec(P) && p.attrFree == 4 && p.skillFree == 2 && p.passiveFree == 2 && p.nodes.size() == 1 && p.attr("vitality") == 0, "сброс вернул все очки");

        // ключевой камень: сильный бонус и минус
        ProgressDefs.Node ks = null;
        for (ProgressDefs.Node n : d.nodes.values()) if (n.kind().equals("keystone")) { ks = n; break; }
        boolean hasMinus = false; for (double v : ks.mods().values()) if (v < 0) hasMinus = true;
        ok(hasMinus && ks.minLevel() >= 20, "ключевой камень с ценой: " + ks.name());

        // внешние поправки и проценты
        pr.external = o -> Map.of("max_health_pct", 50.0, "discount_pct", 5.0);
        ok(pr.get(P, "max_health") > 30 * 1.4 && pr.get(P, "discount_pct") >= 5, "внешние поправки применяются");
        pr.external = o -> Map.of();

        // опыт за врагов
        ok(pr.killXp(10, 20, 10) > pr.killXp(10, 10, 10) && pr.killXp(30, 5, 10) <= 1.01, "опыт за слабых врагов почти ноль, за сильных больше");
        // NPC
        for (String arch : d.archetypes.keySet()) {
            Profile n = pr.autoBuild("n:" + arch, arch, 30, 11);
            int spent = n.nodes.size() - 1;
            ok(n.level == 30 && n.passiveFree <= 1 && n.attrFree == 0 && spent >= 25, arch + ": NPC 30 уровня собран, узлов " + spent + ", свободно " + n.passiveFree + "/" + n.skillFree);
        }
        Profile w = pr.peek("n:warrior"), r = pr.peek("n:ranger");
        ok(pr.get("n:warrior", "melee_dmg_pct") > pr.get("n:ranger", "melee_dmg_pct") && pr.get("n:ranger", "ranged_dmg_pct") > pr.get("n:warrior", "ranged_dmg_pct"), "архетипы различаются по статам");
        Profile w2 = pr.autoBuild("n:w2", "warrior", 30, 11), w3 = pr.autoBuild("n:w3", "warrior", 30, 12);
        ok(w2.nodes.equals(pr.peek("n:warrior").nodes) && !w3.attrs.equals(w2.attrs) || !w3.nodes.equals(w2.nodes), "сборка детерминирована по сиду и меняется от него");
        Profile weak = pr.autoBuild("n:low", "courtier", 5, 3), strong = pr.autoBuild("n:high", "courtier", 50, 3);
        ok(pr.get("n:high", "tax_pct") > pr.get("n:low", "tax_pct") * 3, "сильный NPC заметно сильнее слабого");

        // сохранение и вид
        Progress p2 = new Progress(d);
        p2.fromJson(pr.toJson());
        ok(p2.peek("n:warrior").nodes.equals(w.nodes) && p2.peek(P).respecs == 1 && Math.abs(p2.get("n:ranger", "crit_pct") - pr.get("n:ranger", "crit_pct")) < 1e-6, "save/load");
        ok(pr.viewJson(P).contains("allocatable") && pr.defsJson().contains("warrior_start"), "JSON для окна");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
