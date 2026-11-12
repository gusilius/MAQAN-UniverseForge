package dev.worldforge.tests;

import dev.worldforge.genesis.*;
import dev.worldforge.mod.Json;
import dev.worldforge.origins.OriginDefs;
import dev.worldforge.progress.ProgressDefs;
import dev.worldforge.people.NameBank;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class GenesisTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    static void loadAll(String dir, java.util.function.Consumer<String> sink) throws Exception {
        try (Stream<Path> s = Files.list(Paths.get(dir))) { for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) sink.accept(Files.readString(p)); }
    }

    public static void main(String[] a) throws Exception {
        OriginDefs o = new OriginDefs();
        loadAll("wf-origins/src/main/resources/data/wf_origins/wf_origins", o::load);
        ProgressDefs p = new ProgressDefs();
        loadAll("wf-progress/src/main/resources/data/wf_progress/wf_progress", p::load);
        GenesisDefs g = new GenesisDefs();
        loadAll("wf-genesis/src/main/resources/data/wf_genesis/wf_genesis", g::load);
        ok(p.attrs.size() >= 28, "атрибутов: " + p.attrs.size());
        Set<String> groups = new TreeSet<>(); for (var at : p.attrs.values()) groups.add(at.group());
        ok(groups.size() >= 7, "групп атрибутов " + groups);
        List<String> pv = p.validate(); pv.removeIf(x -> x.startsWith("Предупреждение"));
        ok(pv.isEmpty(), "прогресс валиден " + pv);
        List<String> gv = g.validate(o.races.keySet(), p.attrs.keySet(), o.professions.keySet(), List.of(), List.of());
        gv.removeIf(x -> x.startsWith("Предупреждение"));
        ok(gv.isEmpty(), "genesis валиден " + gv);
        ok(g.starts.size() >= 10 && g.features.size() >= 15, "стартов " + g.starts.size() + ", особенностей " + g.features.size());

        Genesis ge = new Genesis(g, o, p);
        Draft d = ge.draft("p:1");
        ok(!ge.setRace(d, "nope").isEmpty(), "несуществующая раса отклонена");
        ok(ge.setRace(d, "dwarf").isEmpty() && d.look.get("height").equals("85"), "раса гном: рост по умолчанию " + d.look.get("height"));
        ok(ge.setRace(d, "mountain_dwarf").isEmpty() && d.look.get("height").equals("85") && d.look.get("width").equals("115"), "подраса наследует внешность родителя");
        ok(ge.toggleFeature(d, "long_beard").isEmpty() && d.features.contains("long_beard"), "борода гнома");
        ok(!ge.toggleFeature(d, "tail").isEmpty(), "хвост гному недоступен");
        ok(ge.toggleFeature(d, "braided_beard").isEmpty() && !d.features.contains("long_beard") && d.features.contains("braided_beard"), "одна особенность на группу");
        ok(!ge.setLook(d, "height", "200").isEmpty() && ge.setLook(d, "height", "90").isEmpty(), "ползунок в пределах");
        ok(!ge.setLook(d, "skin", "red").isEmpty() && ge.setLook(d, "skin", "#AABBCC").isEmpty() && d.look.get("skin").equals("#aabbcc"), "цвет hex");
        String before = d.look.get("hair");
        ok(ge.shiftColor(d, "hair", 30, 0, 0).isEmpty() && !before.equals(d.look.get("hair")), "сдвиг цвета");

        ok(!ge.setStart(d, "bandit_x").isEmpty(), "нет такого старта");
        ok(ge.setStart(d, "leader_village").isEmpty() && !d.settleName.isEmpty() && !d.focus.isEmpty(), "лидер: имя и направление по умолчанию: " + d.settleName);
        ok(ge.settleNoun(d, "village").equals("Горный чертог") && ge.rulerTitle(d, "capital").equals("Король под горой"), "расовые названия гнома");
        Set<String> inheritedPlaceNames = new HashSet<>();
        for (int seed = 0; seed < 500; seed++) inheritedPlaceNames.add(ge.randomName(d, new Random(seed)));
        ok(inheritedPlaceNames.stream().anyMatch(g.namePool.get("mountain_dwarf")::contains)
                        && inheritedPlaceNames.stream().anyMatch(g.namePool.get("dwarf")::contains),
                "subrace settlement naming combines its own pool with inherited parent-culture names");

        NameBank sharedNames = new NameBank();
        sharedNames.add("""
                {"default":"dwarf",
                 "cultures":{"dwarf":{"male":["Торин"],"female":["Диса"],"houses":["Каменный Молот"]}},
                 "races":{"dwarf":{"settlements":["Кхазад-Рун"]},
                          "mountain_dwarf":{"settlements":["Гримнир"]}},
                 "race_parents":{"mountain_dwarf":"dwarf"}}
                """);
        ge.sharedNameBank(() -> sharedNames);
        Set<String> canonicalPlaceNames = new HashSet<>();
        for (int seed = 0; seed < 100; seed++) canonicalPlaceNames.add(ge.randomName(d, new Random(seed)));
        ok(canonicalPlaceNames.containsAll(List.of("Кхазад-Рун", "Гримнир")),
                "Genesis settlement names use the shared race-inherited NameBank instead of a parallel pool");
        ok(sharedNames.generate("mountain_dwarf", "dwarf", "houses", new Random(1)).equals("Каменный Молот"),
                "the same canonical bank remains the source for house/dynasty naming");
        Draft el = ge.draft("p:2"); ge.setRace(el, "wood_elf");
        ok(ge.settleNoun(el, "town").equals("Лесной город"), "подраса эльфа берёт стиль родителя");
        ok(!ge.setSettle(d, "").isEmpty() && !ge.setSettle(d, "A".repeat(40)).isEmpty() && !ge.setSettle(d, "Bad<>").isEmpty() && ge.setSettle(d, "Новый-Чертог").isEmpty(), "название поселения проверяется");

        ok(ge.attrLeft(d) == 40, "очков 40: " + ge.attrLeft(d));
        for (int i = 0; i < 6; i++) ge.adjustAttr(d, "might", 1);
        ok(d.attr("might") == 6 && ge.spent(d) == 4 + 2 * 2, "мягкий предел: 4 по 1 и 2 по 2 = " + ge.spent(d));
        ok(!ge.adjustAttr(d, "might", 5).isEmpty() && d.attr("might") == 6, "предел +8");
        ok(!ge.adjustAttr(d, "might", -9).isEmpty(), "ниже базы нельзя");
        ok(ge.adjustAttr(d, "might", -6).isEmpty() && ge.attrLeft(d) == 40, "возврат очков");
        Draft rich = new Draft("p:9"); ge.setRace(rich, "human"); ge.setStart(rich, "villager");
        int guard = 0; for (String id : p.attrs.keySet()) for (int i = 0; i < 8 && guard++ < 1000; i++) ge.adjustAttr(rich, id, 1);
        ok(ge.attrLeft(rich) >= 0, "нельзя уйти в минус " + ge.attrLeft(rich));

        ok(ge.toggleProf(d, "smith").isEmpty() && ge.toggleProf(d, "miner").isEmpty(), "две профессии");
        ok(!ge.toggleProf(d, "farmer").isEmpty(), "третья отклонена");
        ok(!ge.problems(d).isEmpty(), "пока есть проблемы: " + ge.problems(d).size());
        ok(ge.problems(d).stream().anyMatch(s -> s.contains("класс")), "нужен класс");
        ok(ge.pickClass(d, "warrior").isEmpty(), "взяли воина");
        ok(!ge.pickClass(d, "knight").isEmpty(), "рыцарь не на старте");
        while (ge.skillLeft(d) > 0) { String f = null; for (String id : p.skills.keySet()) if (ge.skillBlocked(d, id, 1) == null && p.skills.get(id).cost().get(0) <= ge.skillLeft(d) && d.skills.getOrDefault(id, 0) == 0) { f = id; break; } if (f == null) break; ge.adjustSkill(d, f, 1); }
        ok(ge.skillLeft(d) >= 0 && !d.skills.isEmpty(), "навыки взяты, осталось " + ge.skillLeft(d));

        String vj = ge.viewJson(d);
        Map<?, ?> view = (Map<?, ?>) Json.parse(vj);
        ok(((List<?>) view.get("races")).size() >= 30 && ((List<?>) view.get("attrs")).size() >= 28 && !((List<?>) view.get("starts")).isEmpty(), "view: расы/атрибуты/старты");
        ok(view.get("tier") != null && ((List<?>) view.get("classes")).size() >= 6, "view: поселение и классы");

        int bad = 0;
        for (int i = 0; i < 300; i++) {
            Draft r = new Draft("r:" + i);
            ge.randomize(r, new Random(i));
            List<String> pr = ge.problems(r);
            if (!pr.isEmpty()) { bad++; if (bad < 4) System.out.println("  случайный " + i + " " + r.race + " " + r.start + " " + pr); }
            if (!ge.viewJson(r).startsWith("{")) bad++;
        }
        ok(bad == 0, "300 случайных персонажей допустимы, проблем: " + bad);

        // подтверждение и сохранение
        Draft c = ge.draft("p:3"); ge.randomize(c, new Random(5));
        ok(ge.confirm(c).isEmpty() && ge.isDone("p:3"), "подтверждение");
        Genesis g2 = new Genesis(g, o, p); g2.fromJson(ge.toJson());
        ok(g2.isDone("p:3") && g2.finished("p:3").race.equals(c.race) && g2.finished("p:3").look.equals(c.look) && g2.finished("p:3").attrs.equals(c.attrs), "сохранение и загрузка");
        ok(g2.lookString("p:3").startsWith(c.race + "|"), "строка облика");
        ge.forget("p:3");
        ok(!ge.isDone("p:3"), "сброс персонажа");
        ok(Colors.shift("#808080", 0, 0, 20).compareTo("#808080") != 0 && Colors.valid("#a1b2c3") && !Colors.valid("a1b2c3"), "цвета");

        // сводка характеристик учитывает расу и атрибуты
        Draft pvw = new Draft("p:7"); ge.setRace(pvw, "orc");
        double hp0 = ge.preview(pvw).get("max_health");
        ge.setStart(pvw, "villager"); ge.adjustAttr(pvw, "vitality", 3);
        ok(ge.preview(pvw).get("max_health") > hp0, "предпросмотр: здоровье растёт от Выносливости " + hp0 + " -> " + ge.preview(pvw).get("max_health"));

        System.out.println(fails == 0 ? "ALL OK" : "FAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
