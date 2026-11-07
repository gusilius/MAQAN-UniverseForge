package dev.worldforge.mod.client;

import dev.worldforge.dialogue.mc.DialogueNet;
import dev.worldforge.dialogue.mc.client.DialogueScreen;
import dev.worldforge.mod.*;
import dev.worldforge.settle.*;
import dev.worldforge.settle.mc.client.SettleScreen;
import dev.worldforge.war.*;
import dev.worldforge.war.mc.client.WarScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Рисует окна аддонов на данных настоящих движков: java Showcase <datapack.zip> <wfaddons dir> <out dir> */
public class Showcase {
    static void shot(Screen s, int W, int H, int mx, int my, String out, boolean backdrop) throws Exception {
        s.width = W; s.height = H; s.font = new Font();
        GuiGraphics g = new GuiGraphics(W, H);
        g.fill(0, 0, W, H, WFUi.BG);
        if (backdrop) { for (int i = 0; i < 60; i++) g.fill((i * 97) % W, (i * 53) % H, (i * 97) % W + 40, (i * 53) % H + 30, 0xFF1A1E27); }
        s.render(g, mx, my, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(out));
    }

    public static void main(String[] a) throws Exception {
        String dir = a[1], out = a[2];
        // поселение: настоящая симуляция
        Defs d = new Defs();
        for (String f : new String[]{"base.json", "extra.json"}) d.add(Files.readString(Path.of(dir, "wf-settlements/src/main/resources/data/wf_settle/wf_settle/" + f)));
        Settlement st = new Settlement("s1", "Белая Гавань", d);
        st.add("wood", 90); st.add("stone", 25); st.add("food", 60);
        for (int i = 0; i < 6; i++) st.addWorker(null);
        st.place("house", 0, 0); st.place("lumber_camp", 12, 0); st.place("farm", 24, 0); st.place("quarry", 30, 14);
        st.tick(150);
        st.place("sawmill", 12, 12);
        st.place("cottage", 0, 14);
        st.tick(25);
        shot(new SettleScreen(st, c -> {}), 960, 540, 330, 190, out + "/settle.png", true);

        // война: настоящий движок на карте
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData td = m.titles;
        TitleEditor ed = new TitleEditor(td);
        WarEngine e = new WarEngine(ed);
        e.loadCasusBelli(Files.readString(Path.of(dir, "wf-war/src/main/resources/data/wf_war/wf_war/base.json")));
        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : td.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty()) free.add(r.id);
        String A = free.get(0), B = free.get(1), C = free.get(2), D = free.get(3);
        ed.setRealmProp(C, "allies", B);
        ed.setOverlord(D, A, "vassal");
        TitleData.Title goal = td.byKey(td.realms.get(B).titleKeys.get(0));
        War w = e.declare(A, B, "conquest", List.of(goal.id));
        e.occupy(w.id, War.Side.ATTACKER, goal.id);
        e.battle(w.id, War.Side.ATTACKER, 2, "Победа у Переправы");
        e.battle(w.id, War.Side.DEFENDER, 1, "Контратака защитников");
        e.battle(w.id, War.Side.ATTACKER, 3, "Осада сломлена");
        e.tick(40);
        e.offerPeace(w.id, A, true);
        shot(new WarScreen(e, B, c -> {}), 960, 540, 640, 400, out + "/war.png", true);

        // диалог
        DialogueNet.OpenView v = new DialogueNet.OpenView();
        v.sessionId = 1; v.speaker = "Лорд Эддард Старк, Хранитель Севера";
        v.text = "Зима близко, путник. Волки спускаются с гор всё ближе к деревням. Если у тебя есть меч и немного смелости, нам пригодится твоя помощь.";
        v.mode = DialogueNet.MODE_CHOICES;
        v.choices = List.of("Я помогу. Что нужно сделать?", "Что тебе известно о волках?", "Мне нечего предложить.");
        shot(new DialogueScreen(v), 960, 540, 200, 440, out + "/dialogue.png", true);
        System.exit(0);
    }
}
