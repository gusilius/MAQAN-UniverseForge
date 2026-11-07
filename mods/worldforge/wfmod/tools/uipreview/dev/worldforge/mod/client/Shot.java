package dev.worldforge.mod.client;

import dev.worldforge.mod.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Рисует MapScreen в PNG на заглушках Minecraft: проверка раскладки. Аргументы: датапак ns:имя режим выход.png [w h] */
public class Shot {
    public static void main(String[] a) throws Exception {
        String ns = a[1].split(":")[0], nm = a[1].split(":")[1];
        MapData m = MapData.load("t", name -> {
            try (var zf = new java.util.zip.ZipFile(a[0])) {
                var e = zf.getEntry("data/" + ns + "/worldforge_maps/" + nm + "/" + name);
                return e == null ? null : zf.getInputStream(e).readAllBytes();
            }
        });
        ClientState.terrain = TerrainImage.render(m);
        ClientState.state = MapSync.decodeState(MapSync.encodeState(m, 1));
        ClientState.markers.add("Королевская Гавань", "port", 1200, 1500, 0xFFFFFF);
        ClientState.mode = a[2];
        int W = a.length > 4 ? Integer.parseInt(a[4]) : 960, H = a.length > 5 ? Integer.parseInt(a[5]) : 540;
        MapScreen s = new MapScreen();
        s.width = W; s.height = H; s.font = new Font();
        s.init();
        for (int i = 0; i < 200 && !(MapTextures.ready() && MapTextures.mode.equals(a[2])); i++) { Thread.sleep(100); MapTextures.ensure(a[2]); }
        GuiGraphics g = new GuiGraphics(W, H);
        s.render(g, W / 2 + 40, H / 2, 0f);
        javax.imageio.ImageIO.write(g.img, "png", new java.io.File(a[3]));
        System.exit(0);
    }
}
