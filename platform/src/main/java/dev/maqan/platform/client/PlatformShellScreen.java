package dev.maqan.platform.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MAQAN's shared workspace shell. It owns presentation and layout only;
 * simulation, world data and actions remain in their provider mods.
 */
public final class PlatformShellScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BG = 0xF0060B12;
    private static final int SURFACE = 0xE8101924;
    private static final int SURFACE_ALT = 0xF014202D;
    private static final int EDGE = 0x883B5265;
    private static final int TEXT = 0xFFE7F0F8;
    private static final int MUTED = 0xFF91A5B7;
    private static final int CYAN = 0xFF69CDE2;

    private enum Profile {
        COSMOS("◎", "COSMOS", "maqan.atlas.cosmos"),
        POLITICS("♜", "POLITICS", "worldforge.realms.politics"),
        SETTLEMENTS("⌂", "SETTLEMENTS", "worldforge.settlements"),
        RTS("⌖", "RTS", "worldforge.rts");

        final String icon, label, capability;
        Profile(String icon, String label, String capability) {
            this.icon = icon; this.label = label; this.capability = capability;
        }
    }

    private static final class Panel {
        final String id;
        String title;
        int x, y, w, h;
        boolean visible = true, minimized, pinned;
        String dock = "FLOAT";
        Panel(String id, String title, int x, int y, int w, int h) {
            this.id=id; this.title=title; this.x=x; this.y=y; this.w=w; this.h=h;
        }
        int bodyHeight() { return minimized ? 0 : h - 26; }
    }

    private final Map<String, Panel> panels = new LinkedHashMap<>();
    private Profile profile = Profile.COSMOS;
    private String selectedDomain = "COSMOS";
    private String draggingPanel;
    private String resizingPanel;
    private int pointerOffsetX, pointerOffsetY, resizeStartX, resizeStartY, resizeStartW, resizeStartH;
    private boolean navRailVisible = true;
    private boolean inspectorVisible = true;
    private boolean timelineVisible = true;

    public PlatformShellScreen() { super(Component.literal("MAQAN")); }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        if (panels.isEmpty()) {
            panels.put("navigation", new Panel("navigation", "NAVIGATION", 54, 44, 224, Math.max(180, height - 170)));
            panels.put("inspector", new Panel("inspector", "INSPECTOR", width - 270, 44, 252, Math.max(180, height - 170)));
            panels.put("timeline", new Panel("timeline", "TIMELINE", 292, height - 116, Math.max(260, width - 590), 92));
            panels.put("tools", new Panel("tools", "COMMANDS", Math.max(290, width / 2 - 150), 100, 300, 180));
            panels.get("tools").visible = false;
        }
        clampPanels();
    }

    private void clampPanels() {
        for (Panel p : panels.values()) {
            p.w = Math.max(150, Math.min(p.w, width - 24));
            p.h = Math.max(54, Math.min(p.h, height - 48));
            p.x = Math.max(44, Math.min(p.x, width - p.w - 8));
            p.y = Math.max(38, Math.min(p.y, height - (p.minimized ? 26 : p.h) - 8));
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
        drawAmbientGrid(g);
        drawTopBar(g);
        drawRail(g);
        // Back-to-front panel order. The central work surface is never replaced by a click.
        for (Panel p : panels.values()) if (p.visible) drawPanel(g, p, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTopBar(GuiGraphics g) {
        g.fill(0, 0, width, 34, SURFACE_ALT);
        g.fill(0, 33, width, 35, EDGE);
        g.drawString(font, "MAQAN", 14, 13, TEXT, false);
        g.drawString(font, "UNIVERSE PLATFORM", 78, 13, MUTED, false);
        int x = Math.max(250, width / 2 - 116);
        for (Profile p : Profile.values()) {
            boolean active = p == profile;
            g.fill(x, 5, x + 48, 29, active ? 0x553B9FB5 : 0x00101924);
            if (active) g.fill(x, 28, x + 48, 30, CYAN);
            g.drawCenteredString(font, p.icon, x + 24, 8, active ? CYAN : MUTED);
            x += 52;
        }
        g.drawString(font, profile.label, width - 120, 13, CYAN, false);
    }

    private void drawRail(GuiGraphics g) {
        g.fill(0, 35, 44, height, SURFACE_ALT);
        g.fill(43, 35, 44, height, EDGE);
        String[] icons = {"☰", "◈", "⌕", "▤"};
        for (int i=0;i<icons.length;i++) {
            int y=48+i*38;
            boolean active = (i==0 && navRailVisible) || (i==1 && inspectorVisible) || (i==2 && timelineVisible);
            if (active) g.fill(5,y-4,39,y+26,0x443A9FB5);
            g.drawCenteredString(font, icons[i], 22, y+3, active ? CYAN : MUTED);
        }
        g.drawCenteredString(font, "⚙", 22, height-25, MUTED);
    }

    private void drawAmbientGrid(GuiGraphics g) {
        int left = 44, right = width - 1, top = 35, bottom = height - 1;
        for (int x=left+24;x<right;x+=48) g.fill(x,top,x+1,bottom,0x102B5266);
        for (int y=top+24;y<bottom;y+=48) g.fill(left,y,right,y+1,0x102B5266);
    }

    private void drawPanel(GuiGraphics g, Panel p, int mouseX, int mouseY) {
        int visibleH = p.minimized ? 26 : p.h;
        g.fill(p.x+3,p.y+4,p.x+p.w+3,p.y+visibleH+4,0x70000000);
        g.fill(p.x,p.y,p.x+p.w,p.y+visibleH,SURFACE);
        g.fill(p.x,p.y,p.x+p.w,p.y+26,SURFACE_ALT);
        g.fill(p.x,p.y+25,p.x+p.w,p.y+26,EDGE);
        g.drawString(font,p.title,p.x+10,p.y+9,TEXT,false);
        int bx=p.x+p.w-64;
        g.drawCenteredString(font,p.pinned?"◆":"◇",bx+8,p.y+8,p.pinned?CYAN:MUTED);
        g.drawCenteredString(font,p.minimized?"+":"–",bx+28,p.y+7,MUTED);
        g.drawCenteredString(font,p.pinned?"":"×",bx+48,p.y+7,MUTED);
        if (p.minimized) return;
        switch (p.id) {
            case "navigation" -> drawNavigationPanel(g,p);
            case "inspector" -> drawInspectorPanel(g,p);
            case "timeline" -> drawTimelinePanel(g,p);
            case "tools" -> drawCommandPanel(g,p);
            default -> g.drawString(font,"Workspace panel",p.x+12,p.y+40,MUTED,false);
        }
        g.fill(p.x+p.w-7,p.y+p.h-7,p.x+p.w-2,p.y+p.h-2,0xFF426174);
    }

    private void drawNavigationPanel(GuiGraphics g, Panel p) {
        g.drawString(font,"AVAILABLE MODULE VIEWS",p.x+10,p.y+37,MUTED,false);
        int y=p.y+55;
        List<CapabilityRegistry.Capability> capabilities=CapabilityRegistry.available();
        for(CapabilityRegistry.Capability c:capabilities) {
            if (!domainVisible(c.domain())) continue;
            boolean active=c.domain().equals(selectedDomain);
            if(active) g.fill(p.x+6,y-4,p.x+p.w-6,y+15,0x333B9FB5);
            g.drawString(font,c.icon(),p.x+12,y,CYAN,false);
            g.drawString(font,c.label(),p.x+34,y,active?TEXT:0xFFB8C7D5,false);
            y+=21;
            if(y>p.y+p.h-18) break;
        }
        if(capabilities.isEmpty()) g.drawString(font,"No compatible MAQAN providers",p.x+10,y,MUTED,false);
    }

    private boolean domainVisible(String domain) {
        return switch(profile) {
            case COSMOS -> domain.equals("COSMOS") || domain.equals("WORLDS");
            case POLITICS -> domain.equals("POLITICS") || domain.equals("SOCIETY") || domain.equals("ECONOMY");
            case SETTLEMENTS -> domain.equals("SETTLEMENTS") || domain.equals("SOCIETY") || domain.equals("ECONOMY");
            case RTS -> domain.equals("TACTICAL") || domain.equals("ARMY") || domain.equals("SETTLEMENTS");
        };
    }

    private void drawInspectorPanel(GuiGraphics g, Panel p) {
        g.drawString(font,"SELECTION",p.x+10,p.y+38,MUTED,false);
        g.drawString(font,"No entity selected",p.x+10,p.y+59,TEXT,false);
        g.drawString(font,"Select a world object or unit",p.x+10,p.y+78,MUTED,false);
        g.drawString(font,"to inspect its properties.",p.x+10,p.y+91,MUTED,false);
        g.fill(p.x+8,p.y+108,p.x+p.w-8,p.y+109,EDGE);
        g.drawString(font,"CONTEXT ACTIONS",p.x+10,p.y+121,MUTED,false);
        g.drawString(font,"Actions are supplied by modules",p.x+10,p.y+142,MUTED,false);
        g.drawString(font,"when a compatible entity is active.",p.x+10,p.y+155,MUTED,false);
    }

    private void drawTimelinePanel(GuiGraphics g, Panel p) {
        g.drawString(font,"SIMULATION / EVENT TIMELINE",p.x+10,p.y+37,MUTED,false);
        g.drawString(font,"Provider-owned time controls and events appear here.",p.x+10,p.y+57,TEXT,false);
        int lineY=p.y+p.h-18;
        g.fill(p.x+12,lineY,p.x+p.w-12,lineY+1,0x664A6579);
        g.fill(p.x+28,lineY-3,p.x+34,lineY+3,CYAN);
    }

    private void drawCommandPanel(GuiGraphics g, Panel p) {
        g.drawString(font,"COMMAND PALETTE",p.x+12,p.y+43,CYAN,false);
        g.drawString(font,"Search and invoke commands registered by",p.x+12,p.y+68,TEXT,false);
        g.drawString(font,"the currently loaded MAQAN modules.",p.x+12,p.y+84,TEXT,false);
        g.drawString(font,"No provider commands are fabricated.",p.x+12,p.y+112,MUTED,false);
    }

    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(button!=0) return super.mouseClicked(mx,my,button);
        if(my<34) {
            int x=Math.max(250,width/2-116);
            for(Profile p:Profile.values()) {
                if(mx>=x&&mx<x+48) { setProfile(p); return true; }
                x+=52;
            }
            if(mx<70) { navRailVisible=!navRailVisible; panels.get("navigation").visible=navRailVisible; return true; }
            return true;
        }
        if(mx<44) {
            int index=(int)((my-44)/38);
            if(index==0) { navRailVisible=!navRailVisible; panels.get("navigation").visible=navRailVisible; return true; }
            if(index==1) { inspectorVisible=!inspectorVisible; panels.get("inspector").visible=inspectorVisible; return true; }
            if(index==2) { timelineVisible=!timelineVisible; panels.get("timeline").visible=timelineVisible; return true; }
            if(index==3) { Panel p=panels.get("tools"); p.visible=!p.visible; return true; }
            return true;
        }
        List<Panel> reverse=new ArrayList<>(panels.values());
        java.util.Collections.reverse(reverse);
        for(Panel p:reverse) {
            if(!p.visible) continue;
            int ph=p.minimized?26:p.h;
            if(mx<p.x||mx>p.x+p.w||my<p.y||my>p.y+ph) continue;
            if(my<=p.y+26) {
                int bx=p.x+p.w-64;
                if(mx>=bx&&mx<bx+16) { p.pinned=!p.pinned; return true; }
                if(mx>=bx+18&&mx<bx+38) { p.minimized=!p.minimized; return true; }
                if(mx>=bx+38&&mx<bx+58) { if(!p.pinned) p.visible=false; return true; }
                draggingPanel=p.id; pointerOffsetX=(int)mx-p.x; pointerOffsetY=(int)my-p.y;
                return true;
            }
            if(!p.minimized&&mx>=p.x+p.w-12&&my>=p.y+p.h-12) {
                resizingPanel=p.id; resizeStartX=(int)mx; resizeStartY=(int)my; resizeStartW=p.w; resizeStartH=p.h;
                return true;
            }
            if(p.id.equals("navigation")) {
                int y=p.y+55;
                for(CapabilityRegistry.Capability c:CapabilityRegistry.available()) {
                    if(!domainVisible(c.domain())) continue;
                    if(my>=y-4&&my<y+16) { selectedDomain=c.domain(); openCapability(c); return true; }
                    y+=21;
                }
            }
            // Clicking a panel body selects/focuses it; it never dismisses the workspace.
            return true;
        }
        return true;
    }

    private void setProfile(Profile next) {
        profile=next;
        selectedDomain=switch(next) {
            case COSMOS -> "COSMOS";
            case POLITICS -> "POLITICS";
            case SETTLEMENTS -> "SETTLEMENTS";
            case RTS -> "TACTICAL";
        };
        Panel nav=panels.get("navigation"), inspector=panels.get("inspector"), timeline=panels.get("timeline");
        nav.visible=true; inspector.visible=true; timeline.visible=true;
        navRailVisible=inspectorVisible=timelineVisible=true;
        switch(next) {
            case COSMOS -> { nav.title="COSMIC CATALOGUE"; inspector.title="BODY INSPECTOR"; timeline.title="ORBIT / TIME"; }
            case POLITICS -> { nav.title="REALMS & DIPLOMACY"; inspector.title="CHARACTER / REALM"; timeline.title="DYNASTY / CHRONICLE"; }
            case SETTLEMENTS -> { nav.title="SETTLEMENT LAYERS"; inspector.title="BUILDING / HOUSEHOLD"; timeline.title="PRODUCTION"; }
            case RTS -> { nav.title="UNITS / FORMATIONS"; inspector.title="UNIT COMMAND"; timeline.title="ORDERS / BATTLE LOG"; }
        }
        if(next==Profile.RTS) {
            nav.x=54; nav.y=44; nav.w=190; nav.h=Math.max(180,height-90);
            inspector.x=width-246; inspector.y=44; inspector.w=228; inspector.h=Math.max(180,height-90);
            timeline.x=248; timeline.y=height-112; timeline.w=Math.max(220,width-510); timeline.h=88;
        } else if(next==Profile.POLITICS) {
            nav.x=54; nav.y=44; nav.w=250; nav.h=Math.max(180,height-170);
            inspector.x=width-292; inspector.y=44; inspector.w=274; inspector.h=Math.max(180,height-170);
            timeline.x=320; timeline.y=height-116; timeline.w=Math.max(220,width-640); timeline.h=92;
        } else if(next==Profile.SETTLEMENTS) {
            nav.x=54; nav.y=44; nav.w=214; nav.h=Math.max(180,height-170);
            inspector.x=width-280; inspector.y=44; inspector.w=262; inspector.h=Math.max(180,height-170);
            timeline.x=284; timeline.y=height-116; timeline.w=Math.max(220,width-590); timeline.h=92;
        } else {
            nav.x=54; nav.y=44; nav.w=224; nav.h=Math.max(180,height-170);
            inspector.x=width-270; inspector.y=44; inspector.w=252; inspector.h=Math.max(180,height-170);
            timeline.x=292; timeline.y=height-116; timeline.w=Math.max(220,width-590); timeline.h=92;
        }
        clampPanels();
    }

    private void openCapability(CapabilityRegistry.Capability capability) {
        if(!ModList.get().isLoaded(capability.providerModId())) return;
        try {
            Class<?> type=Class.forName(capability.screenClass());
            Object instance=type.getDeclaredConstructor().newInstance();
            if(instance instanceof Screen screen) Minecraft.getInstance().setScreen(screen);
            else LOGGER.error("MAQAN provider {} returned a non-screen view {}",capability.id(),capability.screenClass());
        } catch(ReflectiveOperationException|LinkageError error) {
            LOGGER.error("Unable to open MAQAN capability {} from {}",capability.id(),capability.providerModId(),error);
        }
    }

    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        if(button!=0) return super.mouseDragged(mx,my,button,dx,dy);
        if(draggingPanel!=null) {
            Panel p=panels.get(draggingPanel);
            p.x=(int)mx-pointerOffsetX; p.y=(int)my-pointerOffsetY;
            if(p.x<=48) p.dock="LEFT";
            else if(p.x+p.w>=width-12) p.dock="RIGHT";
            else if(p.y+p.h>=height-12) p.dock="BOTTOM";
            else p.dock="FLOAT";
            clampPanels(); return true;
        }
        if(resizingPanel!=null) {
            Panel p=panels.get(resizingPanel);
            p.w=Math.max(160,resizeStartW+(int)mx-resizeStartX);
            p.h=Math.max(54,resizeStartH+(int)my-resizeStartY);
            clampPanels(); return true;
        }
        return super.mouseDragged(mx,my,button,dx,dy);
    }

    @Override public boolean mouseReleased(double mx,double my,int button) {
        if(button==0) { draggingPanel=null; resizingPanel=null; }
        return super.mouseReleased(mx,my,button);
    }

    @Override public boolean keyPressed(int keyCode,int scanCode,int modifiers) {
        // P is owned by PlatformClient's global KeyMapping; handling it here as
        // well would close and immediately reopen the shell during the same tick.
        if(keyCode==GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode,scanCode,modifiers);
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }

    private void drawAmbientGrid(GuiGraphics g) {
        for(int x=48;x<width;x+=48) g.fill(x,35,x+1,height,0x102B5266);
        for(int y=35;y<height;y+=48) g.fill(44,y,width,y+1,0x102B5266);
    }
}
