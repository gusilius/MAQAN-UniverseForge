package dev.worldforge.mod.client;

import dev.worldforge.mod.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.*;
import dev.worldforge.people.mc.client.CharacterScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.*;
import java.util.*;

/** Рисует CharacterScreen на данных настоящих движков: java ShowcaseCharacter <datapack.zip> <wfaddons dir> <out dir> */
public class ShowcaseCharacter {
    static void shot(Screen s, int W, int H, int mx, int my, String out) throws Exception {
        s.width = W; s.height = H; s.font = new Font();
        GuiGraphics g = new GuiGraphics(W, H);
        g.fill(0, 0, W, H, WFUi.BG);
        for (int i = 0; i < 60; i++) g.fill((i * 97) % W, (i * 53) % H, (i * 97) % W + 40, (i * 53) % H + 30, 0xFF1A1E27);
        s.render(g, mx, my, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(out));
    }

    static Character mk(CharacterRegistry r, String name, String house, String gender, int born, boolean alive) {
        Character c = r.create(name, house, "northmen", gender, born);
        if (!alive) r.kill(c.id);
        return c;
    }

    static void link(Character father, Character mother, Character kid) {
        kid.father = father.id; kid.mother = mother.id;
        if (!father.children.contains(kid.id)) father.children.add(kid.id);
        if (!mother.children.contains(kid.id)) mother.children.add(kid.id);
    }

    public static void main(String[] a) throws Exception {
        String dir = a[1], out = a[2];
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData td = m.titles;
        RoleDefs defs = new RoleDefs();
        try (var s = Files.list(Path.of(dir, "wf-people/src/main/resources/data/wf_people/wf_roles"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) defs.add(Files.readString(p)); }
        NameBank names = new NameBank();
        names.add(Files.readString(Path.of(dir, "wf-people/src/main/resources/data/wf_people/wf_names/base.json")));
        CharacterRegistry reg = new CharacterRegistry();
        TitleEditor ed = new TitleEditor(td);
        int year = 300;
        Courts.ensure(td, ed, reg, defs, names, 42L, year);

        List<TitleData.Realm> free = new ArrayList<>();
        for (TitleData.Realm r : td.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty()) free.add(r);
        TitleData.Realm A = free.get(0), B = free.get(1);
        Character rulerA = reg.holder("ruler", Courts.realmPlace(A.id)), rulerB = reg.holder("ruler", Courts.realmPlace(B.id));
        Character chan = reg.holder("chancellor", Courts.realmPlace(A.id));
        TitleData.Title county = null;
        for (String k : A.titleKeys) { TitleData.Title t = td.byKey(k); if (t != null && t.tier == 1) { county = t; break; } }
        if (county != null) { reg.appoint(chan.id, "castellan", Courts.titlePlace(county.id)); }
        chan.name = "Мейстер Рикард"; chan.house = "Кассель";

        // семья канцлера: 3 поколения вверх и вниз
        Character gf1 = mk(reg, "Бринден", "Кассель", "m", 205, false), gm1 = mk(reg, "Алисса", "Рис", "f", 210, false);
        Character gf2 = mk(reg, "Тайвин", "Мормонт", "m", 208, true), gm2 = mk(reg, "Джоанна", "Хорнвуд", "f", 214, true);
        Character fa = mk(reg, "Родрик", "Кассель", "m", 232, false), mo = mk(reg, "Лианна", "Мормонт", "f", 236, true);
        link(gf1, gm1, fa); link(gf2, gm2, mo); reg.marry(gf1.id, gm1.id); reg.marry(gf2.id, gm2.id); reg.marry(fa.id, mo.id);
        link(fa, mo, chan);
        chan.birthYear = 258;
        Character sis = mk(reg, "Лиара", "Кассель", "f", 262, true), bro = mk(reg, "Брандон", "Кассель", "m", 255, false), sis2 = mk(reg, "Мелисса", "Кассель", "f", 266, true);
        link(fa, mo, sis); link(fa, mo, bro); link(fa, mo, sis2);
        Character wife = mk(reg, "Серена", "Тарли", "f", 262, true);
        reg.marry(chan.id, wife.id);
        Character k1 = mk(reg, "Эддард", "Кассель", "m", 281, true), k2 = mk(reg, "Санса", "Кассель", "f", 284, true), k3 = mk(reg, "Робб", "Кассель", "m", 289, true), k4 = mk(reg, "Арья", "Кассель", "f", 294, true);
        for (Character k : List.of(k1, k2, k3, k4)) link(chan, wife, k);
        Character kw = mk(reg, "Джейна", "Пул", "f", 283, true);
        reg.marry(k1.id, kw.id);
        Character gk1 = mk(reg, "Бран", "Кассель", "m", 299, true), gk2 = mk(reg, "Рикон", "Кассель", "m", 297, false);
        link(k1, kw, gk1); link(k1, kw, gk2);
        CharacterStats.ensure(reg);
        chan.traits.clear(); chan.traits.addAll(List.of("scholar", "shrewd", "diligent", "ambitious"));

        CharacterScreen.Opinion op = id -> id.equals(chan.id) ? 46 : id.equals(rulerA.id) ? 71 : -28;
        // 1: игрок = правитель A, смотрит на канцлера
        shot(new CharacterScreen(reg, defs, td, rulerA.id, chan.id, year, op, c -> System.out.println("cmd " + c)), 960, 540, 640, 188, out + "/character.png");
        // 2: игрок = канцлер, наводит на "Устроить брак" у правителя Б (причина отказа)
        shot(new CharacterScreen(reg, defs, td, chan.id, rulerB.id, year, op, c -> {}), 960, 540, 800, 330, out + "/character_ruler.png");
        System.exit(0);
    }
}
