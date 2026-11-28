package dev.worldforge.mod.client;

import dev.worldforge.mod.MapImages;
import dev.worldforge.mod.TitleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/** Миникарта в углу экрана и баннер «Вы вошли в…» при смене королевства (как в Paradox). */
public final class MiniMap implements IGuiOverlay {
    private static final int SIZE = 96, SRC = 48;
    private int lastKingdom = -1;
    private String banner = "";
    private long bannerUntil;

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float pt, int sw, int sh) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || ClientState.state == null) return;
        MapTextures.ensure(ClientState.mode);
        if (!MapTextures.ready()) return;
        TitleData d = MapTextures.titles;
        int px = (int) Math.floor((mc.player.getX() - (ClientState.localWorldSnapshot ? ClientState.localWorldOriginX : 0.0)) / d.bpp + d.w / 2.0),
                pz = (int) Math.floor((mc.player.getZ() - (ClientState.localWorldSnapshot ? ClientState.localWorldOriginZ : 0.0)) / d.bpp + d.h / 2.0);
        trackBanner(g, d, px, pz, sw);
        if (!ClientState.minimap) return;
        drawMap(g,sw- SIZE - 8,8,px,pz,d);
    }

    /** Called by the shared MAQAN shell while the RTS workspace is active. Coordinates are screen-root coordinates. */
    public static void renderWorkspace(GuiGraphics g,int sw,int sh){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null||mc.options.hideGui||ClientState.state==null||!ClientState.minimap)return;
        MapTextures.ensure(ClientState.mode);
        if(!MapTextures.ready())return;
        TitleData d=MapTextures.titles;
        int px=(int)Math.floor((mc.player.getX()-(ClientState.localWorldSnapshot?ClientState.localWorldOriginX:0.0))/d.bpp+d.w/2.0);
        int pz=(int)Math.floor((mc.player.getZ()-(ClientState.localWorldSnapshot?ClientState.localWorldOriginZ:0.0))/d.bpp+d.h/2.0);
        drawMap(g,sw-SIZE-8,sh-SIZE-28,px,pz,d);
    }

    private static void drawMap(GuiGraphics g,int x,int y,int px,int pz,TitleData d){
        int u=Math.max(0,Math.min(d.w-SRC,px-SRC/2)),v=Math.max(0,Math.min(d.h-SRC,pz-SRC/2));
        g.fill(x-2,y-2,x+SIZE+2,y+SIZE+2,0xFF2B3547);
        g.blit(MapTextures.id,x,y,SIZE,SIZE,(float)u,(float)v,SRC,SRC,MapTextures.w,MapTextures.h);
        double sx=x+(px+0.5-u)*SIZE/SRC,sy=y+(pz+0.5-v)*SIZE/SRC;
        if(sx>=x&&sx<x+SIZE&&sy>=y&&sy<y+SIZE){
            g.fill((int)sx-2,(int)sy-2,(int)sx+3,(int)sy+3,0xFFFFFFFF);
            g.fill((int)sx-1,(int)sy-1,(int)sx+2,(int)sy+2,0xFFFF3B3B);
        }
        TitleData.Title province=d.provinceAtPixelPublic(px,pz);
        if(province!=null)g.drawString(Minecraft.getInstance().font,province.name,
                x+SIZE-Minecraft.getInstance().font.width(province.name),y+SIZE+5,0xFFE8E2D0);
    }

    private void trackBanner(GuiGraphics g, TitleData d, int px, int pz, int sw) {
        Minecraft mc = Minecraft.getInstance();
        TitleData.Title p = d.provinceAtPixelPublic(px, pz);
        int kt = d.tiers.size() >= 4 ? 3 : d.tiers.size() - 1;
        TitleData.Title k = p == null ? null : d.ancestor(p, kt);
        int id = k == null ? 0 : k.id;
        if (id != lastKingdom) {
            if (lastKingdom != -1 && k != null) {
                banner = k.name;
                TitleData.Realm r = d.realmOf(p);
                if (r != null && !r.name.equals(k.name)) banner += "  ·  " + r.name;
                bannerUntil = System.currentTimeMillis() + 3500;
            }
            lastKingdom = id;
        }
        long left = bannerUntil - System.currentTimeMillis();
        if (left > 0 && !banner.isEmpty()) {
            int alpha = (int) Math.min(255, left * 255 / 700);
            if (alpha > 8) {
                int w = mc.font.width(banner) * 2;
                g.pose().pushPose();
                g.pose().translate(sw / 2f, 40f, 0f);
                g.pose().scale(2f, 2f, 1f);
                g.drawCenteredString(mc.font, banner, 0, 0, (alpha << 24) | 0xF4ECD2);
                g.pose().popPose();
            }
        }
    }
}
