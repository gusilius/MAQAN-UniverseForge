package dev.worldforge.mod.client;

import dev.worldforge.mod.*;
import dev.worldforge.people.*;
import dev.worldforge.people.Character;
import dev.worldforge.people.mc.client.CourtScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.*;
import java.util.*;

/** java CourtShowcase <datapack.zip> <wfaddons> <out> */
public class CourtShowcase {
    public static void main(String[] a) throws Exception {
        System.setProperty("user.dir", a[1]);
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        TitleData d = m.titles;
        RoleDefs defs = new RoleDefs();
        try (var st = Files.list(Paths.get(a[1], "wf-people/src/main/resources/data/wf_people/wf_roles"))) { for (Path p : (Iterable<Path>) st.sorted()::iterator) defs.add(Files.readString(p)); }
        NameBank names = new NameBank();
        names.add(Files.readString(Paths.get(a[1], "wf-people/src/main/resources/data/wf_people/wf_names/base.json")));
        TitleEditor ed = new TitleEditor(d);
        CharacterRegistry reg = new CharacterRegistry();
        Courts.ensure(d, ed, reg, defs, names, 7, 300);
        CourtSim s = new CourtSim(d, ed, reg, defs, names, 7, 300);
        try (var st = Files.list(Paths.get(a[1], "wf-people/src/main/resources/data/wf_people/wf_events"))) { for (Path p : (Iterable<Path>) st.sorted()::iterator) s.addEvent(Files.readString(p)); }
        List<String> free = new ArrayList<>();
        for (TitleData.Realm r : d.realmList()) if (r.overlordId == null && !r.titleKeys.isEmpty() && r.subjectIds.isEmpty()) free.add(r.id);
        String L = free.get(0);
        for (int i = 1; i <= 4; i++) ed.setOverlord(free.get(i), L, "vassal");
        s.humans.add(L);
        for (int i = 0; i < 400 && s.pending.stream().noneMatch(p -> p.realm.equals(L)); i++) s.tick(30);
        s.news.clear();
        s.opinions.add(s.ruler(free.get(1)).id, s.ruler(L).id, "Обида", -60, 0);
        s.opinions.add(s.ruler(free.get(2)).id, s.ruler(L).id, "Обида", -40, 0);
        s.opinions.add(s.ruler(free.get(3)).id, s.ruler(L).id, "Дары", 25, 0);
        s.tick(100);
        s.startScheme(s.ruler(L).id, s.ruler(free.get(6)).id, Scheme.Kind.SWAY);
        s.tick(5);
        String[] names2 = {"council", "vassals", "schemes", "events", "marriages", "chronicle"};
        for (int t = 0; t < 6; t++) {
            CourtScreen sc = new CourtScreen(s, L, c -> {});
            var f = CourtScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(sc, t);
            sc.width = 1280; sc.height = 720; sc.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            sc.render(g, 700, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[2] + "/court_" + names2[t] + ".png"));
        }
        System.exit(0);
    }
}
