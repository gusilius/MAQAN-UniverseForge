package dev.maqan.platform.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
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
    private String selectedCapabilityId = "maqan.atlas.cosmos";
    private String draggingPanel;
    private String resizingPanel;
    private int pointerOffsetX, pointerOffsetY, resizeStartX, resizeStartY, resizeStartW, resizeStartH;
    private boolean navRailVisible = true;
    private boolean inspectorVisible = true;
    private boolean timelineVisible = true;
    private Screen embeddedView;
    private final Map<Profile, Screen> providerViews = new EnumMap<>(Profile.class);
    private boolean embeddedViewReady;
    private boolean settingsVisible;
    private boolean ambientGrid = true;
    private boolean panelShadows = true;
    private int previewLayer = 0;

    public PlatformShellScreen() { super(Component.literal("MAQAN")); }

    public PlatformShellScreen(String capabilityId) {
        this();
        CapabilityRegistry.Capability capability = CapabilityRegistry.all().stream()
                .filter(c -> c.id().equals(capabilityId)).findFirst().orElse(null);
        if (capability != null) {
            selectedCapabilityId = capability.id();
            selectedDomain = capability.domain();
            profile = profileForDomain(capability.domain());
        }
    }

    private static Profile profileForDomain(String domain) {
        return switch (domain) {
            case "POLITICS" -> Profile.POLITICS;
            case "SETTLEMENTS", "SOCIETY", "ECONOMY", "CREATION" -> Profile.SETTLEMENTS;
            case "TACTICAL", "ARMY" -> Profile.RTS;
            default -> Profile.COSMOS;
        };
    }

    /** Called reflectively by optional provider mods to mount their existing view in MAQAN. */
    public static void openProviderView(String providerModId, Screen view) {
        Minecraft mc = Minecraft.getInstance();
        CapabilityRegistry.Capability capability = CapabilityRegistry.all().stream()
                .filter(c -> c.providerModId().equals(providerModId)).findFirst().orElse(null);
        String capabilityId = capability == null ? "worldforge.realms.politics" : capability.id();
        PlatformShellScreen shell = new PlatformShellScreen(capabilityId);
        shell.showEmbeddedView(view);
        mc.setScreen(shell);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        if (panels.isEmpty()) {
            panels.put("navigation", new Panel("navigation", "NAVIGATION", 52, 50, 230, Math.min(440, Math.max(220, height - 60))));
            panels.put("inspector", new Panel("inspector", "INSPECTOR", width - 252, 52, 236, 230));
            panels.put("timeline", new Panel("timeline", "TIMELINE", Math.max(220, width / 2 - 180), height - 116, 360, 82));
            panels.put("tools", new Panel("tools", "COMMANDS", Math.max(220, width / 2 - 145), 82, 290, 180));
            panels.get("navigation").visible = false;
            panels.get("inspector").visible = false;
            panels.get("timeline").visible = false;
            panels.get("tools").visible = false;
        }
        ensureAtlasView();
        clampPanels();
    }

    private void ensureAtlasView() {
        if (embeddedView != null || !ModList.get().isLoaded("maqan_galactic_navigator")) return;
        try {
            Class<?> type = Class.forName("com.maqan.galacticnavigator.client.NavigatorScreen");
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Screen screen)) return;
            try {
                type.getMethod("setEmbeddedInMaqan", boolean.class).invoke(instance, true);
            } catch (ReflectiveOperationException ignored) {
                LOGGER.warn("Atlas version does not expose embedded hosting mode; using its existing screen.");
            }
            embeddedView = screen;
            embeddedView.init(Minecraft.getInstance(), width, height);
            embeddedViewReady = true;
        } catch (ReflectiveOperationException | LinkageError error) {
            LOGGER.error("Unable to mount the existing Atlas screen inside MAQAN", error);
        }
    }

    private void resizeEmbeddedView() {
        if (embeddedView == null) return;
        embeddedView.init(Minecraft.getInstance(), width, height);
        embeddedViewReady = true;
    }

    /** Host a provider-owned view inside its matching global workspace. */
    public void showEmbeddedView(Screen view) {
        if (view == null) return;
        Screen previous = providerViews.put(profile, view);
        if (previous != null && previous != view) previous.removed();
        try {
            view.init(Minecraft.getInstance(), width, height);
            embeddedViewReady = true;
        } catch (RuntimeException error) {
            LOGGER.error("Unable to initialize embedded MAQAN view {}", view.getClass().getName(), error);
        }
    }

    private Screen activeView() {
        return profile == Profile.COSMOS ? embeddedView : providerViews.get(profile);
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
        Screen view = activeView();
        if (view != null) {
            view.render(g, mouseX, mouseY, partialTick);
        } else {
            g.fill(0, 0, width, height, BG);
            WorkspacePreviewRenderer.render(g, font, width, height, profile.name(), previewLayer, mouseX, mouseY, ambientGrid);
        }
        drawTopBar(g, mouseX, mouseY);
        drawRail(g, mouseX, mouseY);
        for (Panel p : panels.values()) if (p.visible) drawPanel(g, p, mouseX, mouseY);
        if (settingsVisible) drawSettings(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, 34, SURFACE_ALT);
        g.fill(0, 33, width, 35, EDGE);
        g.drawString(font, "M", 15, 12, CYAN, false);
        g.fill(34, 9, 35, 25, EDGE);
        int x = Math.max(178, width / 2 - 112);
        for (Profile p : Profile.values()) {
            boolean active = p == profile;
            g.fill(x, 5, x + 48, 29, active ? 0x553B9FB5 : 0x00101924);
            if (active) g.fill(x, 28, x + 48, 30, CYAN);
            g.drawCenteredString(font, p.icon, x + 24, 8, active ? CYAN : MUTED);
            if (mx >= x && mx < x + 48 && my >= 5 && my < 30)
                g.renderTooltip(font, Component.literal(p.label), mx, my);
            x += 52;
        }
        g.drawString(font, embeddedView != null && profile == Profile.COSMOS ? "ATLAS" : "WORKSPACE", 46, 13, MUTED, false);
        if (mx >= 8 && mx < 30 && my < 34) g.renderTooltip(font, Component.literal("MAQAN workspace"), mx, my);
    }

    private void drawRail(GuiGraphics g, int mx, int my) {
        g.fill(0, 35, 44, height, SURFACE_ALT);
        g.fill(43, 35, 44, height, EDGE);
        String[] icons = {"☷", "◈", "⌕", "◷"};
        String[] tips = {"Module catalogue", "Object inspector", "Command palette", "Timeline"};
        boolean[] active = {panels.get("navigation").visible, panels.get("inspector").visible,
                panels.get("tools").visible, panels.get("timeline").visible};
        for (int i=0;i<icons.length;i++) {
            int y=49+i*40;
            if (active[i]) g.fill(5,y-5,39,y+25,0x443A9FB5);
            g.drawCenteredString(font, icons[i], 22, y+3, active[i] ? CYAN : MUTED);
            if(mx<44 && my>=y-5 && my<y+25) g.renderTooltip(font, Component.literal(tips[i]), mx, my);
        }
        g.drawCenteredString(font, "⚙", 22, height-25, settingsVisible ? CYAN : MUTED);
        if(mx<44 && my>=height-42) g.renderTooltip(font, Component.literal("Workspace settings"), mx, my);
    }

    private void drawSettings(GuiGraphics g, int mx, int my) {
        int w=224,h=146,x=52,y=Math.max(42,height-h-12);
        g.fill(x+3,y+4,x+w+3,y+h+4,0x80000000);
        g.fill(x,y,x+w,y+h,SURFACE);
        g.fill(x,y,x+w,y+26,SURFACE_ALT);
        g.drawString(font,"⚙",x+10,y+9,CYAN,false);
        g.drawString(font,"WORKSPACE",x+28,y+9,TEXT,false);
        g.drawCenteredString(font,"×",x+w-15,y+8,MUTED);
        g.drawString(font,"Background grid",x+12,y+42,TEXT,false);
        g.drawString(font,ambientGrid?"✓":"—",x+w-26,y+42,ambientGrid?CYAN:MUTED,false);
        g.drawString(font,"Panel shadows",x+12,y+66,TEXT,false);
        g.drawString(font,panelShadows?"✓":"—",x+w-26,y+66,panelShadows?CYAN:MUTED,false);
        g.fill(x+10,y+91,x+w-10,y+92,EDGE);
        g.drawString(font,"Panels remain draggable and resizable",x+12,y+103,MUTED,false);
        g.drawString(font,"MAQAN / UI CORE",x+12,y+126,CYAN,false);
        if(mx>=x+w-28&&my>=y&&my<y+26) g.renderTooltip(font,Component.literal("Close settings"),mx,my);
    }

    private void drawPanel(GuiGraphics g, Panel p, int mouseX, int mouseY) {
        int visibleH = p.minimized ? 26 : p.h;
        if (panelShadows) g.fill(p.x+3,p.y+4,p.x+p.w+3,p.y+visibleH+4,0x70000000);
        g.fill(p.x,p.y,p.x+p.w,p.y+visibleH,SURFACE);
        g.fill(p.x,p.y,p.x+p.w,p.y+26,SURFACE_ALT);
        g.fill(p.x,p.y+25,p.x+p.w,p.y+26,EDGE);
        String icon = panelIcon(p.id);
        g.drawString(font,icon,p.x+9,p.y+8,CYAN,false);
        int bx=p.x+p.w-64;
        g.drawCenteredString(font,p.pinned?"◆":"◇",bx+8,p.y+8,p.pinned?CYAN:MUTED);
        g.drawCenteredString(font,p.minimized?"+":"–",bx+28,p.y+7,MUTED);
        g.drawCenteredString(font,"×",bx+48,p.y+7,MUTED);
        if(mouseX>=p.x&&mouseX<=p.x+26&&mouseY>=p.y&&mouseY<=p.y+26)
            g.renderTooltip(font,Component.literal(p.title),mouseX,mouseY);
        if (p.minimized) return;
        switch (p.id) {
            case "navigation" -> drawNavigationPanel(g,p);
            case "inspector" -> drawInspectorPanel(g,p);
            case "timeline" -> drawTimelinePanel(g,p);
            case "tools" -> drawCommandPanel(g,p);
            default -> {}
        }
        g.fill(p.x+p.w-7,p.y+p.h-7,p.x+p.w-2,p.y+p.h-2,0xFF426174);
    }

    private String panelIcon(String id) {
        return switch(id) {
            case "navigation" -> "☷";
            case "inspector" -> "◈";
            case "timeline" -> "◷";
            case "tools" -> "⌘";
            default -> "◇";
        };
    }

    private void drawNavigationPanel(GuiGraphics g, Panel p) {
        int y=p.y+38;
        List<CapabilityRegistry.Capability> capabilities=CapabilityRegistry.all();
        for(CapabilityRegistry.Capability c:capabilities) {
            if (!domainVisible(c.domain())) continue;
            boolean installed=ModList.get().isLoaded(c.providerModId());
            boolean active=c.id().equals(selectedCapabilityId);
            if(active) g.fill(p.x+6,y-4,p.x+p.w-6,y+17,0x333B9FB5);
            g.drawString(font,c.icon(),p.x+12,y,installed?CYAN:0xFF536879,false);
            g.drawString(font,c.label(),p.x+34,y,installed?TEXT:0xFF718496,false);
            g.fill(p.x+p.w-17,y+3,p.x+p.w-12,y+8,installed?0xFF62D69C:0xFF53616D);
            if(installed && active) g.fill(p.x+6,y+15,p.x+p.w-6,y+16,CYAN);
            y+=21;
            if(y>p.y+p.h-14) break;
        }
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
        g.drawString(font,"◇",p.x+12,p.y+40,CYAN,false);
        g.drawString(font,"NO SELECTION",p.x+30,p.y+40,TEXT,false);
        g.fill(p.x+10,p.y+59,p.x+p.w-10,p.y+60,EDGE);
        g.drawString(font,"Select an object to inspect",p.x+12,p.y+75,MUTED,false);
        g.drawString(font,"Properties and actions appear here.",p.x+12,p.y+91,MUTED,false);
    }

    private void drawTimelinePanel(GuiGraphics g, Panel p) {
        int lineY=p.y+p.h-22;
        g.drawString(font,"◷",p.x+12,p.y+38,CYAN,false);
        g.drawString(font,"SIMULATION TIMELINE",p.x+30,p.y+38,MUTED,false);
        g.fill(p.x+12,lineY,p.x+p.w-12,lineY+1,0x664A6579);
        g.fill(p.x+28,lineY-3,p.x+34,lineY+3,CYAN);
    }

    private void drawCommandPanel(GuiGraphics g, Panel p) {
        g.drawString(font,"⌘",p.x+12,p.y+42,CYAN,false);
        g.drawString(font,"Command registry",p.x+30,p.y+42,TEXT,false);
        g.drawString(font,"Commands appear when providers register them.",p.x+12,p.y+67,MUTED,false);
    }

    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(button!=0) return activeView() != null
                ? activeView().mouseClicked(mx,my,button) : super.mouseClicked(mx,my,button);
        if(my<34) {
            int x=Math.max(178,width/2-112);
            for(Profile p:Profile.values()) {
                if(mx>=x&&mx<x+48) { setProfile(p); return true; }
                x+=52;
            }
            return true;
        }
        if(mx<44) {
            if(my>=height-44) { settingsVisible=!settingsVisible; return true; }
            int index=(int)((my-44)/40);
            if(index==0 && profile==Profile.COSMOS && embeddedView!=null) {
                invokeAtlasRailAction("toggleListPanel"); return true;
            }
            if(index==1 && profile==Profile.COSMOS && embeddedView!=null) {
                invokeAtlasRailAction("toggleInfoPanel"); return true;
            }
            if(index==0) { panels.get("navigation").visible=!panels.get("navigation").visible; return true; }
            if(index==1) { panels.get("inspector").visible=!panels.get("inspector").visible; return true; }
            if(index==2) { panels.get("tools").visible=!panels.get("tools").visible; return true; }
            if(index==3) { panels.get("timeline").visible=!panels.get("timeline").visible; return true; }
            return true;
        }
        if(settingsVisible) {
            int sx=52,sy=Math.max(42,height-158);
            if(mx>=sx+196&&my>=sy&&my<sy+26) { settingsVisible=false; return true; }
            if(mx>=sx&&mx<=sx+224&&my>=sy+32&&my<sy+59) { ambientGrid=!ambientGrid; return true; }
            if(mx>=sx&&mx<=sx+224&&my>=sy+59&&my<sy+86) { panelShadows=!panelShadows; return true; }
            if(mx>=sx&&mx<=sx+224&&my>=sy&&my<=sy+146) return true;
        }
        if(profile==Profile.COSMOS && embeddedView==null && my>=40&&my<68 && mx>=54&&mx<270) {
            int x=58;
            for(int i=0;i<4;i++,x+=52) if(mx>=x&&mx<x+44) { previewLayer=i; return true; }
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
                int y=p.y+38;
                for(CapabilityRegistry.Capability c:CapabilityRegistry.all()) {
                    if(!domainVisible(c.domain())) continue;
                    if(my>=y-4&&my<y+17) {
                        selectedCapabilityId=c.id(); selectedDomain=c.domain();
                        if(c.id().equals("maqan.atlas.cosmos") || c.id().equals("maqan.atlas.worlds")) setProfile(Profile.COSMOS);
                        else if(c.domain().equals("POLITICS")) setProfile(Profile.POLITICS);
                        else if(c.domain().equals("SETTLEMENTS")) setProfile(Profile.SETTLEMENTS);
                        else if(c.domain().equals("TACTICAL") || c.domain().equals("ARMY")) setProfile(Profile.RTS);
                        return true;
                    }
                    y+=21;
                }
            }
            return true;
        }
        return activeView() != null
                ? activeView().mouseClicked(mx,my,button) : true;
    }

    private void invokeAtlasRailAction(String methodName) {
        if (embeddedView == null) return;
        try {
            embeddedView.getClass().getMethod(methodName).invoke(embeddedView);
        } catch (ReflectiveOperationException error) {
            LOGGER.error("Atlas workspace action {} is unavailable", methodName, error);
        }
    }

    private void setProfile(Profile next) {
        profile=next;
        if (next == Profile.COSMOS) ensureAtlasView();
        selectedDomain=switch(next) {
            case COSMOS -> "COSMOS";
            case POLITICS -> "POLITICS";
            case SETTLEMENTS -> "SETTLEMENTS";
            case RTS -> "TACTICAL";
        };
        Panel nav=panels.get("navigation"), inspector=panels.get("inspector"), timeline=panels.get("timeline");
        switch(next) {
            case COSMOS -> { nav.title="COSMIC CATALOGUE"; inspector.title="BODY INSPECTOR"; timeline.title="ORBIT / TIME"; }
            case POLITICS -> { nav.title="REALMS & DIPLOMACY"; inspector.title="CHARACTER / REALM"; timeline.title="DYNASTY / CHRONICLE"; }
            case SETTLEMENTS -> { nav.title="SETTLEMENT LAYERS"; inspector.title="BUILDING / HOUSEHOLD"; timeline.title="PRODUCTION"; }
            case RTS -> { nav.title="UNITS / FORMATIONS"; inspector.title="UNIT COMMAND"; timeline.title="ORDERS / BATTLE LOG"; }
        }
        // One shared compact panel geometry across all workspace modes.
        // The selected mode changes panel content, never the overall interaction model.
        nav.x=52; nav.y=50; nav.w=230; nav.h=Math.min(440,Math.max(220,height-60));
        inspector.x=width-250; inspector.y=50; inspector.w=232;
        inspector.h=Math.min(260,Math.max(180,height-60));
        timeline.x=Math.max(220,width/2-180); timeline.y=height-104;
        timeline.w=Math.min(360,Math.max(220,width-260)); timeline.h=82;
        clampPanels();
    }

    /**
     * Selects a route inside the MAQAN workspace. Provider screens are deliberately
     * never opened as independent Minecraft screens: their UI is migrated into
     * embedded workspace views while their existing domain logic stays provider-owned.
     */
    private void openCapability(CapabilityRegistry.Capability capability) {
        selectedDomain=capability.domain();
        selectedCapabilityId=capability.id();
        // Keep this Screen alive. A provider must register an embedded view adapter
        // before its existing standalone screen can be retired.
    }

    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        if(button!=0) {
            boolean handled = super.mouseDragged(mx,my,button,dx,dy);
            if (profile == Profile.COSMOS && embeddedView != null)
                handled = embeddedView.mouseDragged(mx,my,button,dx,dy) || handled;
            return handled;
        }
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
        boolean handled = super.mouseDragged(mx,my,button,dx,dy);
        if (activeView() != null)
            handled = activeView().mouseDragged(mx,my,button,dx,dy) || handled;
        return handled;
    }

    @Override public boolean mouseReleased(double mx,double my,int button) {
        if(button==0) { draggingPanel=null; resizingPanel=null; }
        boolean handled = super.mouseReleased(mx,my,button);
        if (activeView() != null)
            handled = activeView().mouseReleased(mx,my,button) || handled;
        return handled;
    }

    @Override public boolean keyReleased(int keyCode,int scanCode,int modifiers) {
        boolean handled = super.keyReleased(keyCode,scanCode,modifiers);
        if (activeView() != null)
            handled = activeView().keyReleased(keyCode,scanCode,modifiers) || handled;
        return handled;
    }

    @Override public boolean charTyped(char codePoint,int modifiers) {
        boolean handled = super.charTyped(codePoint,modifiers);
        if (activeView() != null)
            handled = activeView().charTyped(codePoint,modifiers) || handled;
        return handled;
    }

    @Override public boolean mouseScrolled(double mouseX,double mouseY,double delta) {
        boolean handled = super.mouseScrolled(mouseX,mouseY,delta);
        if (activeView() != null)
            handled = activeView().mouseScrolled(mouseX,mouseY,delta) || handled;
        return handled;
    }

    @Override public boolean keyPressed(int keyCode,int scanCode,int modifiers) {
        // P is owned by PlatformClient's global KeyMapping; handling it here as
        // well would close and immediately reopen the shell during the same tick.
        if(keyCode==GLFW.GLFW_KEY_ESCAPE) { if(settingsVisible){settingsVisible=false;return true;} onClose(); return true; }
        boolean handled = super.keyPressed(keyCode,scanCode,modifiers);
        if (activeView() != null)
            handled = activeView().keyPressed(keyCode,scanCode,modifiers) || handled;
        return handled;
    }

    @Override public void onClose() {
        if (embeddedView != null) embeddedView.removed();
        for (Screen view : providerViews.values()) view.removed();
        providerViews.clear();
        Minecraft.getInstance().setScreen(null);
    }

    @Override public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        resizeEmbeddedView();
        for (Screen view : providerViews.values()) view.init(minecraft, width, height);
    }

}
