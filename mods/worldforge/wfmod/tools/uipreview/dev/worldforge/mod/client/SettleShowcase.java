package dev.worldforge.mod.client;

import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.mc.client.SettleScreen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class SettleShowcase {
    public static void main(String[] a) throws Exception {
        Settlement st = RtsShowcase.make(a[0]);
        for (String f : new String[]{"economy"}) st.defs.add(java.nio.file.Files.readString(java.nio.file.Path.of(a[0], "wf-settlements/src/main/resources/data/wf_settle/wf_settle/" + f + ".json")));
        st.add("silver", 80); st.add("planks", 40);
        st.add("wood", 100); st.place("market", 40, 10);
        st.tick(200);
        st.place("tavern", 40, 30);
        st.tick(200);
        st.setAutoTrade("stone", "sell", 60);
        java.util.List<dev.worldforge.settle.CityRow> rows = new java.util.ArrayList<>();
        rows.add(dev.worldforge.settle.CityRow.of(st));
        String[] cn = {"Каменный Брод", "Ивовая Заводь", "Серый Утёс", "Новый Торг", "Дымная Гавань"};
        for (int i = 0; i < cn.length; i++) {
            Settlement o = RtsShowcase.make(a[0]);
            for (String f : new String[]{"economy"}) o.defs.add(java.nio.file.Files.readString(java.nio.file.Path.of(a[0], "wf-settlements/src/main/resources/data/wf_settle/wf_settle/economy.json")));
            o.name = cn[i]; o.add("silver", 20 + i * 45); o.add("planks", 30);
            for (int k = 0; k < i * 2; k++) o.addWorker(null);
            o.tick(100 + i * 120);
            if (i == 3) { o.raidTimer = 1; o.tick(5); }
            var cr = dev.worldforge.settle.CityRow.of(o); cr.id = "c" + i; rows.add(cr);
        }
        String[] names = {"overview", "buildings", "people", "build", "trade", "budget", "cities", "journal"};
        for (int t = 0; t < 8; t++) {
            SettleScreen s = new SettleScreen(st, c -> {});
            s.setCities(rows, dev.worldforge.settle.TradeNetwork.fromJson("{\"format\":\"wf-trade/1\",\"next\":3,\"routes\":[{\"id\":1,\"from\":\"c0\",\"to\":\"c3\",\"res\":\"wood\",\"rate\":10,\"carried\":84,\"profit\":31,\"acc\":0,\"status\":\"возит\"},{\"id\":2,\"from\":\"c1\",\"to\":\"c2\",\"res\":\"stone\",\"rate\":6,\"carried\":12,\"profit\":5,\"acc\":0,\"status\":\"нет разницы\"}]}"));
            var f = SettleScreen.class.getDeclaredField("tab"); f.setAccessible(true); f.setInt(s, t);
            if (t == 1) { var b = SettleScreen.class.getDeclaredField("selBuilding"); b.setAccessible(true); b.setInt(s, st.buildings.get(1).uid); }
            if (t == 2) { var b = SettleScreen.class.getDeclaredField("selWorker"); b.setAccessible(true); b.setInt(s, st.workers.get(2).id); }
            s.width = 1280; s.height = 720; s.font = new Font();
            GuiGraphics g = new GuiGraphics(1280, 720);
            g.fill(0, 0, 1280, 720, WFUi.BG);
            s.render(g, 400, 300, 0f);
            javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[1] + "/settle_" + names[t] + ".png"));
        }
        System.exit(0);
    }
}
