package dev.worldforge.mod.client;

import dev.worldforge.quests.*;
import dev.worldforge.quests.mc.client.QuestScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java QuestShowcase <wfaddons> <out> */
public class QuestShowcase {
    public static void main(String[] a) throws Exception {
        QuestDefs d = new QuestDefs();
        try (var s = Files.list(Path.of(a[0], "wf-quests/src/main/resources/data/wf_quests/wf_quests"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p)); }
        Quests q = new Quests(d);
        Map<String, Integer> inv = new HashMap<>(Map.of("gold", 80, "wheat", 40));
        Quests.Hooks0 h = new Quests.Hooks0() {
            @Override public int count(String p, String i) { return inv.getOrDefault(i, 0); }
            @Override public int level(String p) { return 12; }
            @Override public String realmOf(String p) { return "stark"; }
            @Override public String rulerOf(String r) { return "P"; }
            @Override public List<String> neighbors(String r) { return List.of("lannister", "baratheon"); }
            @Override public List<String> enemies(String r) { return List.of("lannister"); }
        };
        q.hooks = h;
        String P = "P";
        var mar = new Quests.Giver("n:m", "Маршал Брайен", new HashSet<>(List.of("marshal")), "", "", "stark");
        var stw = new Quests.Giver("n:s", "Казначей Эдрик", new HashSet<>(List.of("steward")), "", "", "stark");
        var vil = new Quests.Giver("n:v", "Вдова Марта", new HashSet<>(), "", "", "stark");
        for (var pair : List.of(new Object[]{mar, "bandit_hunt"}, new Object[]{stw, "grain_reserve"}, new Object[]{vil, "widow_debt"}))
            for (int day = 10; day < 400; day += Quests.WINDOW) { Quests.Active ac = null; for (var o : q.offers(P, (Quests.Giver) pair[0], day)) if (o.qid().equals(pair[1])) ac = q.accept(P, (Quests.Giver) pair[0], o.key(), day); if (ac != null) break; }
        q.report(P, "kill", "zombie", 7, 20);
        for (int i = 0; i < 6; i++) q.fire(d.events.get(i % 2 == 0 ? "bandit_raid" : "plague"), "stark", 25 + i, new Random(i));
        q.rights(P).add("create_quest");
        q.chronicle.add("День 12: выполнено «Волки у ограды»"); q.chronicle.add("День 18: правитель одобрил грамоту");
        String json = q.viewJson(P, 30);
        String[] names = {"journal", "events", "history", "rights"};
        for (int t = 0; t < 4; t++) {
            QuestScreen s = new QuestScreen(json, c -> {});
            java.lang.reflect.Field f = QuestScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 700, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/quests_" + names[t] + ".png"));
        }
        System.exit(0);
    }
}
