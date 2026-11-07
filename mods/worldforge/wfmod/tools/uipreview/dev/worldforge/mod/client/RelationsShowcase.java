package dev.worldforge.mod.client;

import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.people.mc.client.RelationsScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.*;

/** java RelationsShowcase <out> */
public class RelationsShowcase {
    public static void main(String[] a) throws Exception {
        CharacterRegistry reg = new CharacterRegistry();
        Relations r = new Relations();
        Relations.Hooks0 h = new Relations.Hooks0();
        r.hooks = h;
        Random rng = new Random(4);
        String P = "p";
        String[][] ppl = {{"Мира", "f", "gregarious,generous", "Хозяйка таверны"}, {"Борис", "m", "greedy,diligent", "Кузнец"}, {"Элиза Старк", "f", "brave,just", "Леди Винтерфелла"},
                {"Тихон", "m", "shy,scholar", "Писец"}, {"Гарольд", "m", "wroth,brave", "Маршал"}, {"Ульрика", "f", "content,patient", "Травница"}, {"Рольф", "m", "lazy,cruel", "Страж"}};
        int i = 0;
        Map<String, Character> by = new HashMap<>();
        for (String[] p : ppl) {
            Character c = reg.create(p[0].split(" ")[0], p[0].contains(" ") ? p[0].split(" ")[1] : "", "x", p[1], 270 + i * 4);
            for (String t : p[2].split(",")) c.traits.add(t);
            by.put(c.id, c);
            Relations.Bond b = r.bond(P, c.id);
            b.met = 3 + i;
            for (int d = 1; d <= 12 - i; d++) { r.interact(P, c, "chat", d * 2, rng); r.interact(P, c, "compliment", d * 2, rng); if (d % 3 == 0) r.gift(P, c, 5, d * 2); }
            i++;
        }
        List<Character> all = new ArrayList<>(reg.all());
        Character mira = all.get(0);
        r.bond(P, mira.id).affinity = 72; r.bond(P, mira.id).love = 66; r.bond(P, mira.id).dating = true;
        r.bond(P, all.get(1).id).affinity = 48;
        r.hire(P, all.get(1), 30);
        r.bond(P, all.get(2).id).affinity = 86;
        r.bond(P, all.get(6).id).affinity = -42;
        r.offerRequest(all.get(1), 30, new Random(2));
        r.offerRequest(all.get(3), 30, new Random(2));
        Map<String, String> lab = new HashMap<>();
        for (int k = 0; k < ppl.length; k++) lab.put(all.get(k).id, ppl[k][3]);
        String json = r.viewJson(P, reg::get, c -> lab.getOrDefault(c.id, ""), 32);
        for (int sel = 0; sel < 2; sel++) {
            RelationsScreen s = new RelationsScreen(json, c -> {});
            java.lang.reflect.Field f = RelationsScreen.class.getDeclaredField("sel"); f.setAccessible(true); f.setInt(s, sel == 0 ? 0 : 3);
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 400, 200, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[0] + "/rel_" + sel + ".png"));
        }
        System.exit(0);
    }
}
