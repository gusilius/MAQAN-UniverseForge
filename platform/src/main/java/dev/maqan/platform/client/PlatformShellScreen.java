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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * MAQAN's shared workspace shell. It owns presentation and layout only;
 * simulation, world data and actions remain in their provider mods.
 */
public final class PlatformShellScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BG = WorkspaceTheme.BACKGROUND;
    private static final int SURFACE = WorkspaceTheme.SURFACE;
    private static final int SURFACE_ALT = WorkspaceTheme.SURFACE_RAISED;
    private static final int EDGE = WorkspaceTheme.BORDER;
    private static final int TEXT = WorkspaceTheme.TEXT;
    private static final int MUTED = WorkspaceTheme.TEXT_MUTED;
    private static final int CYAN = WorkspaceTheme.ACCENT;

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
    private final Map<String, Screen> providerViews = new LinkedHashMap<>();
    private boolean embeddedViewReady;
    private boolean settingsVisible;
    private boolean ambientGrid = true;
    private boolean panelShadows = true;
    private int previewLayer = 0;
    private int navigationScroll;
    private boolean workspaceStateLoaded;
    private static final int WORKSPACE_SCHEMA = 1;

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

    /** Build a MAQAN host for an existing provider screen without changing Minecraft's screen mid-event. */
    public static PlatformShellScreen wrapProviderView(String providerModId, Screen view) {
        CapabilityRegistry.Capability capability = CapabilityRegistry.forProvider(providerModId, view.getClass().getName());
        String capabilityId = capability == null ? "worldforge.map" : capability.id();
        PlatformShellScreen shell = new PlatformShellScreen(capabilityId);
        shell.attachProviderView(capability, view);
        return shell;
    }

    /** Mounts a provider screen into this live shell without replacing the shared workspace. */
    public boolean attachProviderView(String providerModId, Screen view) {
        return attachProviderView(CapabilityRegistry.forProvider(providerModId, view.getClass().getName()), view);
    }

    private boolean attachProviderView(CapabilityRegistry.Capability capability, Screen view) {
        if (capability == null || view == null) return false;
        try {
            view.getClass().getMethod("setEmbeddedInMaqan", boolean.class).invoke(view, true);
        } catch (ReflectiveOperationException ignored) {
            // Provider screens without an embedding hook still use the shared input/render host.
        }
        setProfile(profileForDomain(capability.domain()));
        selectedDomain = capability.domain();
        selectedCapabilityId = capability.id();
        Screen previous = providerViews.put(capability.id(), view);
        if (previous != null && previous != view) previous.removed();
        if (width > 0 && height > 0) view.init(Minecraft.getInstance(), width, height);
        return true;
    }

    /** Optional providers may call this directly when opening a view outside ScreenEvent.Opening. */
    public static void openProviderView(String providerModId, Screen view) {
        Minecraft.getInstance().setScreen(wrapProviderView(providerModId, view));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        if (panels.isEmpty()) {
            panels.put("navigation", new Panel("navigation", "NAVIGATION", 52, 50, 230, Math.min(440, Math.max(220, height - 60))));
            panels.put("inspector", new Panel("inspector", "INSPECTOR", width - 252, 52, 236, 230));
            panels.put("timeline", new Panel("timeline", "TIMELINE", Math.max(220, width / 2 - 180), height - 116, 360, 82));
            panels.put("tools", new Panel("tools", "COMMANDS", Math.max(220, width / 2 - 145), 82, 290, 180));
            // A new workspace opens ready to navigate and inspect; secondary
            // panels stay opt-in so the world/map remains visually dominant.
            panels.get("navigation").visible = true;
            panels.get("inspector").visible = true;
            panels.get("timeline").visible = false;
            panels.get("tools").visible = false;
        }
        if (!workspaceStateLoaded) loadWorkspaceState();
        if (!profileAvailable(profile)) {
            for (Profile candidate : Profile.values()) {
                if (profileAvailable(candidate)) { profile = candidate; break; }
            }
        }
        if (profile == Profile.COSMOS) ensureAtlasView();
        if (embeddedView != null) embeddedView.init(Minecraft.getInstance(), width, height);
        Screen providerView = providerViews.get(selectedCapabilityId);
        if (providerView != null) providerView.init(Minecraft.getInstance(), width, height);
        clampPanels();
    }

    private Preferences workspacePreferences() {
        return Preferences.userNodeForPackage(PlatformShellScreen.class).node("workspace");
    }

    private void loadWorkspaceState() {
        Preferences prefs = workspacePreferences();
        int schema = prefs.getInt("schema", 0);
        if (schema <= WORKSPACE_SCHEMA) {
            try { profile = Profile.valueOf(prefs.get("profile", profile.name())); }
            catch (IllegalArgumentException ignored) { profile = Profile.COSMOS; }
            selectedCapabilityId = prefs.get("capability", selectedCapabilityId);
            for (Panel panel : panels.values()) {
                String key = "panel." + panel.id + ".";
                panel.x = prefs.getInt(key + "x", panel.x);
                panel.y = prefs.getInt(key + "y", panel.y);
                panel.w = prefs.getInt(key + "w", panel.w);
                panel.h = prefs.getInt(key + "h", panel.h);
                panel.visible = prefs.getBoolean(key + "visible", panel.visible);
                panel.minimized = prefs.getBoolean(key + "minimized", panel.minimized);
                panel.pinned = prefs.getBoolean(key + "pinned", panel.pinned);
                panel.dock = prefs.get(key + "dock", panel.dock);
            }
            ambientGrid = prefs.getBoolean("ambient_grid", ambientGrid);
            panelShadows = prefs.getBoolean("panel_shadows", panelShadows);
        }
        workspaceStateLoaded = true;
    }

    private void saveWorkspaceState() {
        if (!workspaceStateLoaded) return;
        try {
            Preferences prefs = workspacePreferences();
            prefs.putInt("schema", WORKSPACE_SCHEMA);
            prefs.put("profile", profile.name());
            prefs.put("capability", selectedCapabilityId);
            prefs.putBoolean("ambient_grid", ambientGrid);
            prefs.putBoolean("panel_shadows", panelShadows);
            for (Panel panel : panels.values()) {
                String key = "panel." + panel.id + ".";
                prefs.putInt(key + "x", panel.x);
                prefs.putInt(key + "y", panel.y);
                prefs.putInt(key + "w", panel.w);
                prefs.putInt(key + "h", panel.h);
                prefs.putBoolean(key + "visible", panel.visible);
                prefs.putBoolean(key + "minimized", panel.minimized);
                prefs.putBoolean(key + "pinned", panel.pinned);
                prefs.put(key + "dock", panel.dock);
            }
            prefs.flush();
        } catch (Exception error) {
            LOGGER.warn("Unable to persist MAQAN workspace layout", error);
        }
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
        } catch (ReflectiveOperationException | LinkageError error) {
            LOGGER.error("Unable to mount the existing Atlas screen inside MAQAN", error);
        }
    }

    private void resizeEmbeddedView() {
        if (embeddedView == null) return;
        embeddedView.init(Minecraft.getInstance(), width, height);
        embeddedViewReady = true;
    }

    /** Route Atlas views and WorldForge views into their respective shared workspace slots. */
    public void showEmbeddedView(Screen view) {
        if (view == null) return;
        if (profile == Profile.COSMOS) {
            Screen previous = embeddedView;
            embeddedView = view;
            if (previous != null && previous != view) previous.removed();
            if (width > 0 && height > 0) embeddedView.init(Minecraft.getInstance(), width, height);
            return;
        }
        Screen previous = providerViews.put(selectedCapabilityId, view);
        if (previous != null && previous != view) previous.removed();
        if (width > 0 && height > 0) view.init(Minecraft.getInstance(), width, height);
    }

    /** Close a hosted provider view without dismissing the MAQAN workspace itself. */
    public static boolean closeEmbeddedProviderView(Screen view) {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen instanceof PlatformShellScreen shell && shell.detachProviderView(view);
    }

    private boolean detachProviderView(Screen view) {
        if (view == null) return false;
        if (embeddedView == view) {
            embeddedView = null;
            view.removed();
            return true;
        }
        String found = null;
        for (Map.Entry<String, Screen> entry : providerViews.entrySet())
            if (entry.getValue() == view) { found = entry.getKey(); break; }
        if (found == null) return false;
        providerViews.remove(found);
        view.removed();
        return true;
    }

    private Screen activeView() {
        return profile == Profile.COSMOS ? embeddedView : providerViews.get(selectedCapabilityId);
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
            if (!profileAvailable(p)) continue;
            boolean active = p == profile;
            g.fill(x, 5, x + 48, 29, active ? 0x553B9FB5 : 0x00101924);
            if (active) g.fill(x, 28, x + 48, 30, CYAN);
            g.drawCenteredString(font, p.icon, x + 24, 8, active ? CYAN : MUTED);
            if (mx >= x && mx < x + 48 && my >= 5 && my < 30)
                g.renderTooltip(font, Component.literal(p.label), mx, my);
            x += 52;
        }
        g.drawString(font, embeddedView != null && profile == Profile.COSMOS ? "ATLAS" : "WORKSPACE", 46, 13, MUTED, false);
        g.drawCenteredString(font, "×", width - 18, 13, MUTED);
        if (mx >= width - 30 && mx < width - 6 && my < 34)
            g.renderTooltip(font, Component.literal(activeView() == null ? "Close workspace" : "Close current module view"), mx, my);
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

    private List<CapabilityRegistry.Capability> navigationCapabilities() {
        return CapabilityRegistry.available().stream()
                .filter(c -> domainVisible(c.domain()))
                .toList();
    }

    private int navigationViewportTop(Panel p) { return p.y + 31; }
    private int navigationViewportBottom(Panel p) { return p.y + p.h - 9; }
    private int navigationVisibleRows(Panel p) {
        return Math.max(1, (navigationViewportBottom(p) - navigationViewportTop(p)) / 21);
    }
    private int navigationMaxScroll(Panel p) {
        return Math.max(0, navigationCapabilities().size() - navigationVisibleRows(p));
    }

    private void drawNavigationPanel(GuiGraphics g, Panel p) {
        List<CapabilityRegistry.Capability> capabilities = navigationCapabilities();
        navigationScroll = Math.max(0, Math.min(navigationScroll, navigationMaxScroll(p)));
        int top = navigationViewportTop(p);
        int bottom = navigationViewportBottom(p);
        g.enableScissor(p.x + 5, top, p.x + p.w - 5, bottom);
        for (int row = 0; row < navigationVisibleRows(p); row++) {
            int index = navigationScroll + row;
            if (index >= capabilities.size()) break;
            CapabilityRegistry.Capability c = capabilities.get(index);
            int y = p.y + 38 + row * 21;
            boolean installed = ModList.get().isLoaded(c.providerModId());
            boolean active = c.id().equals(selectedCapabilityId);
            if (active) g.fill(p.x + 6, y - 4, p.x + p.w - 6, y + 17, 0x333B9FB5);
            g.drawString(font, c.icon(), p.x + 12, y, installed ? CYAN : 0xFF536879, false);
            g.drawString(font, c.label(), p.x + 34, y, installed ? TEXT : 0xFF718496, false);
            g.fill(p.x + p.w - 17, y + 3, p.x + p.w - 12, y + 8,
                    installed ? 0xFF62D69C : 0xFF53616D);
            if (installed && active) g.fill(p.x + 6, y + 15, p.x + p.w - 6, y + 16, CYAN);
        }
        g.disableScissor();
        if (capabilities.size() > navigationVisibleRows(p)) {
            int trackX = p.x + p.w - 5;
            int trackTop = top + 2;
            int trackBottom = bottom - 2;
            int trackHeight = Math.max(1, trackBottom - trackTop);
            int thumbHeight = Math.max(16, trackHeight * navigationVisibleRows(p) / capabilities.size());
            int travel = Math.max(0, trackHeight - thumbHeight);
            int thumbY = trackTop + (navigationMaxScroll(p) == 0 ? 0
                    : travel * navigationScroll / navigationMaxScroll(p));
            g.fill(trackX, trackTop, trackX + 2, trackBottom, 0x554A6579);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, CYAN);
        }
    }

    private boolean profileAvailable(Profile candidate) {
        return switch (candidate) {
            case COSMOS -> !CapabilityRegistry.inDomain("COSMOS").isEmpty()
                    || !CapabilityRegistry.inDomain("WORLDS").isEmpty();
            case POLITICS -> !CapabilityRegistry.inDomain("POLITICS").isEmpty()
                    || !CapabilityRegistry.inDomain("SOCIETY").isEmpty()
                    || !CapabilityRegistry.inDomain("ECONOMY").isEmpty();
            case SETTLEMENTS -> !CapabilityRegistry.inDomain("SETTLEMENTS").isEmpty()
                    || !CapabilityRegistry.inDomain("SOCIETY").isEmpty()
                    || !CapabilityRegistry.inDomain("ECONOMY").isEmpty()
                    || !CapabilityRegistry.inDomain("CREATION").isEmpty();
            case RTS -> !CapabilityRegistry.inDomain("TACTICAL").isEmpty()
                    || !CapabilityRegistry.inDomain("ARMY").isEmpty();
        };
    }

    private boolean domainVisible(String domain) {
        return switch(profile) {
            case COSMOS -> domain.equals("COSMOS") || domain.equals("WORLDS");
            case POLITICS -> domain.equals("POLITICS") || domain.equals("SOCIETY") || domain.equals("ECONOMY") || domain.equals("ARMY");
            case SETTLEMENTS -> domain.equals("SETTLEMENTS") || domain.equals("SOCIETY") || domain.equals("ECONOMY") || domain.equals("CREATION");
            case RTS -> domain.equals("TACTICAL") || domain.equals("ARMY") || domain.equals("SETTLEMENTS") || domain.equals("ECONOMY");
        };
    }

    private void drawInspectorPanel(GuiGraphics g, Panel p) {
        PlatformSelectionContext.Selection selected = PlatformSelectionContext.current();
        if (selected == null) {
            g.drawString(font,"◇",p.x+12,p.y+40,CYAN,false);
            g.drawString(font,"NO SELECTION",p.x+30,p.y+40,TEXT,false);
            g.fill(p.x+10,p.y+59,p.x+p.w-10,p.y+60,EDGE);
            g.drawString(font,"Select an object in a workspace",p.x+12,p.y+75,MUTED,false);
            g.drawString(font,"Its identity and context appear here.",p.x+12,p.y+91,MUTED,false);
            return;
        }
        int left = p.x + 12;
        int right = p.x + p.w - 12;
        g.drawString(font, "◇ " + selected.entityType().toUpperCase(java.util.Locale.ROOT),
                left, p.y + 40, CYAN, false);
        g.drawString(font, trimInspector(selected.displayName(), p.w - 24),
                left, p.y + 59, TEXT, false);
        g.fill(left, p.y + 75, right, p.y + 76, EDGE);
        g.drawString(font, "SOURCE", left, p.y + 87, MUTED, false);
        g.drawString(font, trimInspector(selected.providerId(), p.w - 24),
                left, p.y + 99, TEXT, false);
        g.drawString(font, "IDENTITY", left, p.y + 118, MUTED, false);
        g.drawString(font, trimInspector(selected.entityId(), p.w - 24),
                left, p.y + 130, TEXT, false);
        if (!selected.worldId().isBlank()) {
            g.drawString(font, "WORLD / SYSTEM", left, p.y + 149, MUTED, false);
            g.drawString(font, trimInspector(selected.worldId(), p.w - 24),
                    left, p.y + 161, TEXT, false);
        }
        if (!selected.summary().isBlank()) {
            g.drawString(font, "DIMENSION / CONTEXT", left, p.y + 180, MUTED, false);
            g.drawString(font, trimInspector(selected.summary(), p.w - 24),
                    left, p.y + 192, TEXT, false);
        }
    }

    private String trimInspector(String value, int maxWidth) {
        if (value == null || value.isBlank()) return "—";
        return font.plainSubstrByWidth(value, Math.max(16, maxWidth));
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

    @Override public void tick() {
        super.tick();
        Screen view = activeView();
        if (view != null) view.tick();
    }

    @Override public boolean mouseClicked(double mx,double my,int button) {
        if(button!=0) return activeView() != null
                ? activeView().mouseClicked(mx,my,button) : super.mouseClicked(mx,my,button);
        if(my<34) {
            if (mx >= width - 30 && mx < width - 6) {
                Screen view = activeView();
                if (view != null) detachProviderView(view);
                else onClose();
                return true;
            }
            int x=Math.max(178,width/2-112);
            for(Profile p:Profile.values()) {
                if (!profileAvailable(p)) continue;
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
            if(index==0) { panels.get("navigation").visible=!panels.get("navigation").visible; saveWorkspaceState(); return true; }
            if(index==1) { panels.get("inspector").visible=!panels.get("inspector").visible; saveWorkspaceState(); return true; }
            if(index==2) { panels.get("tools").visible=!panels.get("tools").visible; saveWorkspaceState(); return true; }
            if(index==3) { panels.get("timeline").visible=!panels.get("timeline").visible; saveWorkspaceState(); return true; }
            return true;
        }
        if(settingsVisible) {
            int sx=52,sy=Math.max(42,height-158);
            if(mx>=sx+196&&my>=sy&&my<sy+26) { settingsVisible=false; return true; }
            if(mx>=sx&&mx<=sx+224&&my>=sy+32&&my<sy+59) { ambientGrid=!ambientGrid; saveWorkspaceState(); return true; }
            if(mx>=sx&&mx<=sx+224&&my>=sy+59&&my<sy+86) { panelShadows=!panelShadows; saveWorkspaceState(); return true; }
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
                if(mx>=bx&&mx<bx+16) { p.pinned=!p.pinned; saveWorkspaceState(); return true; }
                if(mx>=bx+18&&mx<bx+38) { p.minimized=!p.minimized; saveWorkspaceState(); return true; }
                if(mx>=bx+38&&mx<bx+58) { if(!p.pinned) p.visible=false; saveWorkspaceState(); return true; }
                draggingPanel=p.id; pointerOffsetX=(int)mx-p.x; pointerOffsetY=(int)my-p.y;
                return true;
            }
            if(!p.minimized&&mx>=p.x+p.w-12&&my>=p.y+p.h-12) {
                resizingPanel=p.id; resizeStartX=(int)mx; resizeStartY=(int)my; resizeStartW=p.w; resizeStartH=p.h;
                return true;
            }
            if(p.id.equals("navigation")) {
                int top = navigationViewportTop(p);
                int bottom = navigationViewportBottom(p);
                if (my >= top && my < bottom) {
                    int row = (int) ((my - (p.y + 34)) / 21);
                    int index = navigationScroll + row;
                    List<CapabilityRegistry.Capability> capabilities = navigationCapabilities();
                    if (index >= 0 && index < capabilities.size()) {
                        openCapability(capabilities.get(index));
                    }
                    return true;
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
        if (profile != next) PlatformSelectionContext.clear(null);
        profile=next;
        navigationScroll = 0;
        selectedCapabilityId = switch (next) {
            case COSMOS -> "maqan.atlas.cosmos";
            case POLITICS -> "worldforge.realms.politics";
            case SETTLEMENTS -> "worldforge.settlements";
            case RTS -> "worldforge.rts";
        };
        if (next == Profile.COSMOS) {
            ensureAtlasView();
            if (embeddedView != null) embeddedView.init(Minecraft.getInstance(), width, height);
        } else {
            Screen view = providerViews.get(selectedCapabilityId);
            if (view != null) view.init(Minecraft.getInstance(), width, height);
        }
        selectedDomain=switch(next) {
            case COSMOS -> "COSMOS";
            case POLITICS -> "POLITICS";
            case SETTLEMENTS -> "SETTLEMENTS";
            case RTS -> "TACTICAL";
        };
        saveWorkspaceState();
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
        Profile target = profileForDomain(capability.domain());
        setProfile(target);
        selectedDomain = capability.domain();
        selectedCapabilityId = capability.id();
        Screen view = activeView();
        if (view != null) {
            view.init(Minecraft.getInstance(), width, height);
            return;
        }
        // Some providers expose a complete no-argument screen (for example the
        // WorldForge political map). Mount that real screen instead of a preview.
        if (capability.screenClass() != null && !capability.screenClass().isBlank()) {
            try {
                Class<?> type = Class.forName(capability.screenClass());
                if (Screen.class.isAssignableFrom(type)) {
                    Object instance = type.getDeclaredConstructor().newInstance();
                    if (instance instanceof Screen providerScreen
                            && attachProviderView(capability, providerScreen)) return;
                }
            } catch (NoSuchMethodException ignored) {
                // Data-driven screens need a server payload; request them below.
            } catch (ReflectiveOperationException | LinkageError error) {
                LOGGER.warn("Unable to create provider view for {}", capability.id(), error);
            }
        }
        String command = switch (capability.id()) {
            case "worldforge.settlements" -> "wfsettle view panel";
            case "worldforge.rts" -> "wfsettle view hud";
            case "worldforge.genesis" -> "wfgenesis";
            case "worldforge.origins" -> "wforigin";
            case "worldforge.progress" -> "wfprog";
            case "worldforge.forms" -> "wfform";
            case "worldforge.quests" -> "wfquest";
            case "worldforge.orders" -> "wforders";
            case "worldforge.relations" -> "wfrel";
            // Reputation currently exposes a server-authoritative command, not a client screen.
            // Keep the shell entry actionable without fabricating a local reputation view.
            case "worldforge.reputation" -> "wfrep show";
            case "worldforge.court" -> "wfcourt";
            case "worldforge.trade" -> "wftrade";
            case "worldforge.army" -> "wfarmy screen";
            case "worldforge.war" -> "wfwar screen";
            default -> null;
        };
        Minecraft mc = Minecraft.getInstance();
        if (command != null && mc.player != null && mc.player.connection != null)
            mc.player.connection.sendCommand(command);
    }

    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy) {
        if(button!=0) {
            boolean handled = super.mouseDragged(mx,my,button,dx,dy);
            if (activeView() != null)
                handled = activeView().mouseDragged(mx,my,button,dx,dy) || handled;
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
        if(button==0) {
            boolean changed = draggingPanel != null || resizingPanel != null;
            draggingPanel=null; resizingPanel=null;
            if (changed) saveWorkspaceState();
        }
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
        Panel nav = panels.get("navigation");
        if (nav != null && nav.visible && !nav.minimized
                && mouseX >= nav.x && mouseX <= nav.x + nav.w
                && mouseY >= navigationViewportTop(nav)
                && mouseY < navigationViewportBottom(nav)
                && !navigationCapabilities().isEmpty()) {
            int direction = delta > 0 ? -1 : 1;
            navigationScroll = Math.max(0, Math.min(navigationMaxScroll(nav), navigationScroll + direction * 2));
            return true;
        }
        boolean handled = super.mouseScrolled(mouseX,mouseY,delta);
        if (activeView() != null)
            handled = activeView().mouseScrolled(mouseX,mouseY,delta) || handled;
        return handled;
    }

    @Override public boolean keyPressed(int keyCode,int scanCode,int modifiers) {
        // The global workspace toggle is F10 (not P): WorldForge progress and
        // other provider screens retain their own P shortcut bindings.
        if(keyCode==GLFW.GLFW_KEY_ESCAPE) { if(settingsVisible){settingsVisible=false;return true;} onClose(); return true; }
        boolean handled = super.keyPressed(keyCode,scanCode,modifiers);
        if (activeView() != null)
            handled = activeView().keyPressed(keyCode,scanCode,modifiers) || handled;
        return handled;
    }

    @Override public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override public void removed() {
        if (embeddedView != null) {
            embeddedView.removed();
            embeddedView = null;
        }
        for (Screen view : providerViews.values()) view.removed();
        providerViews.clear();
        super.removed();
    }

    @Override public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        resizeEmbeddedView();
        for (Screen view : providerViews.values()) view.init(minecraft, width, height);
    }

}
