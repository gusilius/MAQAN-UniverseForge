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
    private static int CYAN = WorkspaceTheme.ACCENT;

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
        String dock = "DOCK";
        Panel(String id, String title, int x, int y, int w, int h) {
            this.id=id; this.title=title; this.x=x; this.y=y; this.w=w; this.h=h;
        }
        int bodyHeight() { return minimized ? 0 : h - 26; }
    }

    private final Map<String, Panel> panels = new LinkedHashMap<>();
    private final java.util.Set<String> hiddenPanels = new java.util.LinkedHashSet<>();
    /** RTS panels are opt-in overlays; the battlefield is unobstructed by default. */
    private final java.util.Set<String> rtsOverlayPanels = new java.util.LinkedHashSet<>();
    // Panels hidden only because the current viewport cannot fit them without overlap.
    private final java.util.Set<String> viewportSuppressedPanels = new java.util.LinkedHashSet<>();
    private Profile profile = Profile.COSMOS;
    private String selectedDomain = "COSMOS";
    private String selectedCapabilityId = "maqan.atlas.cosmos";
    private String draggingPanel;
    private String resizingPanel;
    private String resizingSplitterPath, draggingPopup;
    private boolean panelDragMoved;
    private int pointerOffsetX, pointerOffsetY, dragMouseX, dragMouseY, dragStartX, dragStartY;
    private int settingsX=-1, settingsY=-1, managerX=-1, managerY=-1, notificationsX=-1, notificationsY=-1;
    private int resizeStartX, resizeStartY, resizeStartW, resizeStartH;
    private WorkspaceDockTree.Node workspaceRoot;
    private WorkspaceProfileStore profileStore;
    private String activeWorkspaceName = "Default";
    private boolean searchVisible;
    private String searchQuery = "";
    private List<WorkspaceRuntime.SearchEntry> searchResults = List.of();
    private boolean contextMenuVisible;
    private int contextMenuX, contextMenuY;
    private List<WorkspaceRuntime.Action> contextActions = List.of();
    private boolean navRailVisible = true;
    private boolean inspectorVisible = true;
    private boolean timelineVisible = true;
    private Screen embeddedView;
    private final Map<String, Screen> providerViews = new LinkedHashMap<>();
    private boolean embeddedViewReady;
    private boolean settingsVisible;
    private boolean workspaceManagerVisible;
    private boolean notificationCenterVisible;
    private boolean ambientGrid = true;
    private boolean panelShadows = true;
    private int previewLayer = 0;
    private int navigationScroll;
    private boolean workspaceStateLoaded;
    private Screen initializedView;
    private int initializedViewWidth = -1, initializedViewHeight = -1;
    private boolean providerMouseCaptured;
    private static final int WORKSPACE_SCHEMA = 2;

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
        WorkspaceMapState.setViewType(mapViewType(view, capability));
        try {
            view.getClass().getMethod("setEmbeddedInMaqan", boolean.class).invoke(view, true);
        } catch (ReflectiveOperationException ignored) {
            // Provider screens without an embedding hook still use the shared input/render host.
        }
        // Selection is a world-level navigation context. Switching provider views must not erase it.
        setProfile(profileForDomain(capability.domain()));
        selectedDomain = capability.domain();
        // MapScreen is the canonical political workspace even when opened through
        // the generic worldforge.map capability. Keep one stable key so switching
        // between profile tabs never falls back to the empty workspace preview.
        String viewKey = "POLITICS".equals(capability.domain())
                && "dev.worldforge.mod.client.MapScreen".equals(view.getClass().getName())
                ? "worldforge.realms.politics" : capability.id();
        selectedCapabilityId = viewKey;
        Screen previous = providerViews.put(viewKey, view);
        if (previous != null && previous != view) previous.removed();
        if (width > 0 && height > 0) {
            layoutWorkspace();
            initViewInContent(view);
        }
        return true;
    }

    private static WorkspaceMapState.ViewType mapViewType(Screen view,
                                                               CapabilityRegistry.Capability capability) {
        String className = view.getClass().getName();
        if (className.endsWith(".MapScreen")) return WorkspaceMapState.ViewType.POLITICAL;
        if (className.endsWith(".NavigatorSurfaceMapScreen"))
            return WorkspaceMapState.ViewType.EXPLORATION;
        if (className.endsWith(".NavigatorTerrain3DScreen"))
            return WorkspaceMapState.ViewType.DIORAMA_3D;
        if (className.endsWith(".NavigatorScreen"))
            return WorkspaceMapState.ViewType.NAVIGATION;
        if ("POLITICS".equals(capability.domain())) return WorkspaceMapState.ViewType.POLITICAL;
        return WorkspaceMapState.viewType();
    }

    /** True only while this exact provider view is the active content of the shared shell. */
    public static boolean isHostedProviderViewActive(Screen view) {
        Minecraft mc = Minecraft.getInstance();
        return view != null && mc.screen instanceof PlatformShellScreen host && host.activeView() == view;
    }

    /** Optional providers may call this directly when opening a view outside ScreenEvent.Opening. */
    public static void openProviderView(String providerModId, Screen view) {
        Minecraft mc = Minecraft.getInstance();
        // Reuse the live MAQAN shell when a provider is opened from inside it.
        // Replacing the shell here discarded workspace state and could leave the
        // provider's camera/input lifecycle detached from the visible host.
        if (mc.screen instanceof PlatformShellScreen current) {
            if (current.attachProviderView(providerModId, view)) return;
        }
        mc.setScreen(wrapProviderView(providerModId, view));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        CYAN = WorkspaceTheme.ACCENT;
        if (panels.isEmpty()) {
            panels.put("navigation", new Panel("navigation", "NAVIGATION", 52, 50, 230, Math.min(440, Math.max(220, height - 60))));
            panels.put("inspector", new Panel("inspector", "INSPECTOR", width - 252, 52, 236, 230));
            panels.put("timeline", new Panel("timeline", "TIMELINE", Math.max(220, width / 2 - 180), height - 116, 360, 82));
            panels.put("tools", new Panel("tools", "COMMANDS", Math.max(220, width / 2 - 145), 82, 290, 180));
            panels.put("content", new Panel("content", "WORKSPACE", 280, 35, Math.max(200, width - 520), Math.max(160, height - 130)));
            // A new workspace opens ready to navigate and inspect; secondary
            // panels stay opt-in so the world/map remains visually dominant.
            panels.get("navigation").visible = true;
            panels.get("inspector").visible = true;
            panels.get("timeline").visible = true;
            panels.get("tools").visible = false;
            workspaceRoot = defaultWorkspaceTree();
        }
        if (workspaceRoot == null) workspaceRoot = defaultWorkspaceTree();
        if (profileStore == null) profileStore = new WorkspaceProfileStore(workspacePreferences().node("profiles"));
        if (!workspaceStateLoaded) loadWorkspaceState();
        if (!profileAvailable(profile)) {
            for (Profile candidate : Profile.values()) {
                if (profileAvailable(candidate)) { profile = candidate; break; }
            }
        }
        if (profile == Profile.COSMOS) ensureAtlasView();
        if (profile == Profile.POLITICS) ensurePoliticalMapView();
        layoutWorkspace();
        clampPanels();
        initViewInContent(activeView());
    }

    private WorkspaceDockTree.Node defaultWorkspaceTree() {
        // Match the supplied 1368x760 concept composition: left navigation,
        // dominant center workspace, right inspector, and a 23% lower strip.
        return new WorkspaceDockTree.Split(WorkspaceDockTree.Axis.HORIZONTAL, 220,
                new WorkspaceDockTree.Panel("navigation"),
                new WorkspaceDockTree.Split(WorkspaceDockTree.Axis.HORIZONTAL, 690,
                        new WorkspaceDockTree.Split(WorkspaceDockTree.Axis.VERTICAL, 770,
                                new WorkspaceDockTree.Panel("content"),
                                new WorkspaceDockTree.Panel("timeline")),
                        new WorkspaceDockTree.Tabs(List.of(
                                new WorkspaceDockTree.Panel("inspector"),
                                new WorkspaceDockTree.Panel("tools")), 0)));
    }

    /** Convert reference-skin coordinates to the current Minecraft GUI-scaled viewport. */
    private int ux(double logicalX) { return (int) Math.round(logicalX * width / 1368); }
    private int uy(double logicalY) { return (int) Math.round(logicalY * height / 760); }
    private int railTop(int index) { return uy(46 + index * 61); }
    private int railBottom(int index) { return uy(46 + index * 61 + 56); }
    private boolean railHit(double mouseX, double mouseY, int index) {
        return mouseX >= 0 && mouseX < ux(58)
                && mouseY >= railTop(index) && mouseY < railBottom(index);
    }

    private boolean compactViewport() { return width < 900 || height < 520; }
    private boolean immersiveRts() { return profile == Profile.RTS && commandCameraActive(); }
    private boolean compactTopbar() { return width < 720; }
    private int profileStartX() { return compactTopbar() ? ux(48) : Math.max(ux(178),width/2-ux(112)); }
    private int profileButtonWidth() { return compactTopbar() ? ux(28) : ux(48); }
    private int profileButtonStep() { return compactTopbar() ? ux(32) : ux(52); }
    private int cameraControlX() { return compactTopbar() ? profileStartX()+4*profileButtonStep()+ux(8) : width-ux(326); }
    /** Only the political overview suppresses the lower timeline; RTS keeps its command/order strip. */
    private boolean politicalCameraWorkspace() { return profile == Profile.POLITICS; }
    private WorkspaceDockTree.Node layoutRoot() {
        return politicalCameraWorkspace() && workspaceRoot != null
                ? WorkspaceDockTree.remove(workspaceRoot, "timeline") : workspaceRoot;
    }
    private WorkspaceDockTree.Rect workspaceBounds() {
        return new WorkspaceDockTree.Rect(ux(44), uy(35), Math.max(1, width - ux(44)), Math.max(1, height - uy(35)));
    }

    /** Shared constraints for layout, splitter hit-testing and drag/drop geometry. */
    private Map<String, WorkspaceDockTree.MinimumSize> workspaceMinimums() {
        double scale = Math.min(1.0, Math.min(width / (double) 1368,
                height / (double) 760));
        return Map.of(
                "navigation", new WorkspaceDockTree.MinimumSize(Math.max(72, (int) (150 * scale)), Math.max(64, (int) (120 * scale))),
                "content", new WorkspaceDockTree.MinimumSize(Math.max(100, (int) (220 * scale)), Math.max(84, (int) (160 * scale))),
                "inspector", new WorkspaceDockTree.MinimumSize(Math.max(76, (int) (180 * scale)), Math.max(76, (int) (140 * scale))),
                "tools", new WorkspaceDockTree.MinimumSize(Math.max(76, (int) (180 * scale)), Math.max(64, (int) (120 * scale))),
                "timeline", new WorkspaceDockTree.MinimumSize(Math.max(100, (int) (160 * scale)), Math.max(40, (int) (64 * scale))));
    }

    private void layoutWorkspace() {
        if (workspaceRoot == null || panels.isEmpty()) return;
        WorkspaceDockTree.Rect bounds = immersiveRts()
                ? new WorkspaceDockTree.Rect(0, 0, Math.max(1, width), Math.max(1, height))
                : workspaceBounds();
        WorkspaceDockTree.Node root = layoutRoot();
        Map<String, WorkspaceDockTree.Rect> layout;
        if (compactViewport()) layout = Map.of("content", bounds);
        else {
            layout = WorkspaceDockTree.layout(root, bounds, workspaceMinimums());
        }
        if (immersiveRts()) {
            Panel content = panels.get("content");
            if (content != null) {
                content.x = 0; content.y = 0; content.w = Math.max(1, width); content.h = Math.max(1, height);
                content.visible = true;
            }
            for (Panel panel : panels.values()) {
                if (panel.id.equals("content")) continue;
                panel.visible = rtsOverlayPanels.contains(panel.id) && !hiddenPanels.contains(panel.id);
                if (panel.visible && "FLOAT".equals(panel.dock)) {
                    panel.x = Math.max(8, Math.min(panel.x, width - panel.w - 8));
                    panel.y = Math.max(8, Math.min(panel.y, height - panel.h - 8));
                }
            }
            return;
        }
        for (Map.Entry<String, WorkspaceDockTree.Rect> entry : layout.entrySet()) {
            Panel panel = panels.get(entry.getKey());
            if (panel == null || "FLOAT".equals(panel.dock)) continue;
            WorkspaceDockTree.Rect rect = entry.getValue();
            panel.x = rect.x(); panel.y = rect.y(); panel.w = rect.width(); panel.h = rect.height();
            panel.visible = !hiddenPanels.contains(panel.id);
        }
        for (String id : List.of("navigation", "content", "inspector", "tools", "timeline")) {
            Panel panel = panels.get(id);
            if (panel == null) continue;
            if (!layout.containsKey(id) && !"FLOAT".equals(panel.dock)) panel.visible = false;
            if ("FLOAT".equals(panel.dock)) panel.visible = !hiddenPanels.contains(id);
            if (compactViewport() && !"content".equals(id) && !"FLOAT".equals(panel.dock)) panel.visible = false;
            if (politicalCameraWorkspace() && "timeline".equals(id)) panel.visible = false;
        }
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
                if (prefs.getBoolean("hidden." + panel.id, false)) hiddenPanels.add(panel.id);
                panel.x = prefs.getInt(key + "x", panel.x);
                panel.y = prefs.getInt(key + "y", panel.y);
                panel.w = prefs.getInt(key + "w", panel.w);
                panel.h = prefs.getInt(key + "h", panel.h);
                panel.visible = prefs.getBoolean(key + "visible", panel.visible);
                panel.minimized = prefs.getBoolean(key + "minimized", panel.minimized);
                panel.pinned = prefs.getBoolean(key + "pinned", panel.pinned);
                panel.dock = prefs.get(key + "dock", panel.dock);
            }
            if (schema < WORKSPACE_SCHEMA) {
                for (Panel panel : panels.values()) panel.dock = "DOCK";
            }
            ambientGrid = prefs.getBoolean("ambient_grid", ambientGrid);
            panelShadows=prefs.getBoolean("panel_shadows",panelShadows);
            settingsX=prefs.getInt("popup.settings.x",-1);settingsY=prefs.getInt("popup.settings.y",-1);
            managerX=prefs.getInt("popup.manager.x",-1);managerY=prefs.getInt("popup.manager.y",-1);
            notificationsX=prefs.getInt("popup.notifications.x",-1);notificationsY=prefs.getInt("popup.notifications.y",-1);
        }
        try {
            String activeName = profileStore.activeProfile();
            if (activeName != null && !activeName.isBlank()) activeWorkspaceName = activeName;
            workspaceRoot = profileStore.load(activeWorkspaceName);
        } catch (RuntimeException ignored) {
            activeWorkspaceName = "Default";
            workspaceRoot = defaultWorkspaceTree();
        }
        workspaceStateLoaded = true;
        layoutWorkspace();
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
            prefs.putInt("popup.settings.x",settingsX);prefs.putInt("popup.settings.y",settingsY);
            prefs.putInt("popup.manager.x",managerX);prefs.putInt("popup.manager.y",managerY);
            prefs.putInt("popup.notifications.x",notificationsX);prefs.putInt("popup.notifications.y",notificationsY);
            for (String id : List.of("navigation", "inspector", "tools", "timeline"))
                prefs.putBoolean("hidden." + id, hiddenPanels.contains(id));
            for (Panel panel : panels.values()) {
                String key = "panel." + panel.id + ".";
                prefs.putInt(key + "x", panel.x);
                prefs.putInt(key + "y", panel.y);
                prefs.putInt(key + "w", panel.w);
                prefs.putInt(key + "h", panel.h);
                prefs.putBoolean(key + "visible", panel.visible || viewportSuppressedPanels.contains(panel.id));
                prefs.putBoolean(key + "minimized", panel.minimized);
                prefs.putBoolean(key + "pinned", panel.pinned);
                prefs.put(key + "dock", panel.dock);
            }
            prefs.flush();
            if (profileStore != null && workspaceRoot != null) profileStore.save(activeWorkspaceName, workspaceRoot);
        } catch (Exception error) {
            LOGGER.warn("Unable to persist MAQAN workspace layout", error);
        }
    }

    /**
     * Political mode is a map-first workspace. Reuse a map view already opened
     * by either political or generic WorldForge navigation; otherwise mount the
     * provider's real MapScreen in the central content panel.
     */
    private void ensurePoliticalMapView() {
        Screen map = providerViews.get("worldforge.realms.politics");
        if (map == null) {
            map = providerViews.remove("worldforge.map");
            if (map != null) providerViews.put("worldforge.realms.politics", map);
        }
        if (map == null) {
            try {
                Class<?> type = Class.forName("dev.worldforge.mod.client.MapScreen");
                Object instance = type.getDeclaredConstructor().newInstance();
                if (!(instance instanceof Screen screen)) return;
                try { type.getMethod("setEmbeddedInMaqan", boolean.class).invoke(screen, true); }
                catch (ReflectiveOperationException ignored) { }
                providerViews.put("worldforge.realms.politics", screen);
                map = screen;
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                LOGGER.warn("Political map provider is unavailable; keeping the politics workspace fallback");
                return;
            }
        }
        selectedCapabilityId = "worldforge.realms.politics";
        initViewInContent(map);
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
        layoutWorkspace();
        initViewInContent(activeView());
        embeddedViewReady = embeddedView != null;
    }

    /** Route Atlas views and WorldForge views into their respective shared workspace slots. */
    public void showEmbeddedView(Screen view) {
        if (view == null) return;
        if (profile == Profile.COSMOS) {
            Screen previous = embeddedView;
            embeddedView = view;
            if (previous != null && previous != view) previous.removed();
            if (width > 0 && height > 0) { layoutWorkspace(); initViewInContent(view); }
            return;
        }
        Screen previous = providerViews.put(selectedCapabilityId, view);
        if (previous != null && previous != view) previous.removed();
        if (width > 0 && height > 0) { layoutWorkspace(); initViewInContent(view); }
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

    private Panel contentPanel() { return panels.get("content"); }

    private void initViewInContent(Screen view) {
        Panel content = contentPanel();
        if (view == null || content == null || width <= 0 || height <= 0) return;
        int viewWidth = Math.max(1, content.w);
        int viewHeight = Math.max(1, content.h);
        if (view == initializedView && viewWidth == initializedViewWidth && viewHeight == initializedViewHeight) return;
        view.init(Minecraft.getInstance(), viewWidth, viewHeight);
        initializedView = view;
        initializedViewWidth = viewWidth;
        initializedViewHeight = viewHeight;
    }

    private boolean forwardViewMouseClicked(double mouseX, double mouseY, int button) {
        Screen view = activeView();
        Panel content = contentPanel();
        if (view == null || content == null
                || !WorkspaceUi.contains(mouseX, mouseY, content.x, content.y, content.w, content.h)) return false;
        providerMouseCaptured = view.mouseClicked(mouseX - content.x, mouseY - content.y, button);
        return providerMouseCaptured;
    }

    private boolean forwardViewMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        Screen view = activeView();
        Panel content = contentPanel();
        if (view == null || content == null || !providerMouseCaptured) return false;
        return view.mouseDragged(mouseX - content.x, mouseY - content.y, button, dx, dy);
    }

    private boolean forwardViewMouseReleased(double mouseX, double mouseY, int button) {
        Screen view = activeView();
        Panel content = contentPanel();
        if (view == null || content == null || !providerMouseCaptured) return false;
        boolean handled = view.mouseReleased(mouseX - content.x, mouseY - content.y, button);
        providerMouseCaptured = false;
        return handled;
    }

    private boolean forwardViewMouseScrolled(double mouseX, double mouseY, double delta) {
        Screen view = activeView();
        Panel content = contentPanel();
        if (view == null || content == null
                || !WorkspaceUi.contains(mouseX, mouseY, content.x, content.y, content.w, content.h)) return false;
        return view.mouseScrolled(mouseX - content.x, mouseY - content.y, delta);
    }

    private void clampPanels() {
        // Restore viewport-suppressed panels before recomputing placement. A larger
        // window can then bring them back; intentional user-hidden panels stay hidden.
        for (String id : new ArrayList<>(viewportSuppressedPanels)) {
            Panel panel = panels.get(id);
            if (panel != null && "FLOAT".equals(panel.dock) && !hiddenPanels.contains(id))
                panel.visible = true;
        }
        viewportSuppressedPanels.clear();
        List<Panel> floating = new ArrayList<>();
        for (Panel p : panels.values()) {
            if (!"FLOAT".equals(p.dock) || !p.visible) continue;
            int mw = Math.max(1, width - 56), mh = Math.max(26, height - 44);
            p.w = Math.max(Math.min(140, mw), Math.min(p.w, mw));
            p.h = Math.max(Math.min(54, mh), Math.min(p.h, mh));
            int visibleHeight = p.minimized ? 26 : p.h;
            int maxX = Math.max(44, width - p.w - 4);
            int maxY = Math.max(35, height - visibleHeight - 4);
            p.x = Math.max(44, Math.min(p.x, maxX));
            p.y = Math.max(35, Math.min(p.y, maxY));
            floating.add(p);
        }

        // Floating windows share one collision-free layer. Resolve saved/dragged
        // positions deterministically, while keeping every rectangle in the viewport.
        List<Panel> placed = new ArrayList<>();
        for (Panel panel : floating) {
            if (placed.stream().noneMatch(other -> overlaps(panel, other))) {
                placed.add(panel);
                continue;
            }
            int originalX = panel.x, originalY = panel.y;
            boolean resolved = false;
            for (int ring = 1; ring <= 16 && !resolved; ring++) {
                int step = 28 * ring;
                int[][] candidates = {
                        {originalX + step, originalY}, {originalX, originalY + step},
                        {originalX - step, originalY}, {originalX, originalY - step},
                        {originalX + step, originalY + step}, {originalX - step, originalY + step}
                };
                for (int[] candidate : candidates) {
                    int maxX = Math.max(44, width - panel.w - 4);
                    int maxY = Math.max(35, height - (panel.minimized ? 26 : panel.h) - 4);
                    panel.x = Math.max(44, Math.min(candidate[0], maxX));
                    panel.y = Math.max(35, Math.min(candidate[1], maxY));
                    if (placed.stream().noneMatch(other -> overlaps(panel, other))) {
                        resolved = true;
                        break;
                    }
                }
            }
            if (!resolved) {
                // Compact fallback: cascade into the first free viewport slot.
                int maxY = Math.max(35, height - (panel.minimized ? 26 : panel.h) - 4);
                int maxX = Math.max(44, width - panel.w - 4);
                for (int y = 35; y <= maxY && !resolved; y += 30) {
                    for (int x = 44; x <= maxX; x += 30) {
                        panel.x = x; panel.y = y;
                        if (placed.stream().noneMatch(other -> overlaps(panel, other))) {
                            resolved = true;
                            break;
                        }
                    }
                }
            }
            if (!resolved) {
                // Never leave two floating windows painted over one another just
                // because a tiny viewport has no remaining collision-free slot.
                panel.x = originalX;
                panel.y = originalY;
                panel.visible = false;
                viewportSuppressedPanels.add(panel.id);
                continue;
            }
            placed.add(panel);
        }
    }

    private static boolean overlaps(Panel a, Panel b) {
        int ah = a.minimized ? 26 : a.h;
        int bh = b.minimized ? 26 : b.h;
        return a.x < b.x + b.w && a.x + a.w > b.x
                && a.y < b.y + bh && a.y + ah > b.y;
    }
    private int popupW(int preferred) { return Math.max(1,Math.min(preferred,width-16)); }
    private int popupH(int preferred) { return Math.max(28,Math.min(preferred,height-42)); }
    private int popupX(int saved,int preferred,boolean right) {
        int w=popupW(preferred), fallback=right?width-w-12:(width-w)/2;
        return Math.max(8,Math.min(saved>=0?saved:fallback,Math.max(8,width-w-8)));
    }
    private int popupY(int saved,int preferred,int fallback) {
        int h=popupH(preferred);
        return Math.max(34,Math.min(saved>=0?saved:fallback,Math.max(34,height-h-8)));
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layoutWorkspace();
        boolean immersive = immersiveRts();
        // The shell must not paint an opaque workspace background over Minecraft's live 3D scene.
        if (!immersive) g.fill(0, 0, width, height, BG);
        Panel content = panels.get("content");
        if (content != null && content.visible) {
            g.enableScissor(content.x, content.y, content.x + content.w, content.y + content.h);
            Screen view = activeView();
            if (view != null) {
                initViewInContent(view);
                g.pose().pushPose();
                g.pose().translate(content.x, content.y, 0.0D);
                view.render(g, mouseX - content.x, mouseY - content.y, partialTick);
                g.pose().popPose();
            } else {
                WorkspacePreviewRenderer.render(g, font, content.w, content.h, profile.name(),
                        previewLayer, mouseX - content.x, mouseY - content.y, ambientGrid);
            }
            if (!immersiveRts()) WorkspaceMapLayerRegistry.render(g, content.x, content.y, content.w, content.h);
            g.disableScissor();
        }
        if (!immersive) {
            drawTopBar(g, mouseX, mouseY);
            drawRail(g, mouseX, mouseY);
            drawDockSplitters(g, mouseX, mouseY);
        }
        for (Panel p : panels.values())
            if (p.visible && !p.id.equals("content")) drawPanel(g, p, mouseX, mouseY);
        if (settingsVisible) drawSettings(g, mouseX, mouseY);
        if (searchVisible) drawSearchOverlay(g, mouseX, mouseY);
        if (contextMenuVisible) drawContextMenu(g, mouseX, mouseY);
        if (workspaceManagerVisible) drawWorkspaceManager(g, mouseX, mouseY);
        if (notificationCenterVisible) drawNotificationCenter(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawDockSplitters(GuiGraphics g, int mx, int my) {
        for (WorkspaceDockTree.Splitter splitter : (compactViewport() ? List.<WorkspaceDockTree.Splitter>of() : WorkspaceDockTree.splitters(layoutRoot(), workspaceBounds(), workspaceMinimums()))) {
            boolean horizontal = splitter.axis() == WorkspaceDockTree.Axis.HORIZONTAL;
            int left = horizontal ? splitter.position() - 2 : splitter.start();
            int top = horizontal ? splitter.start() : splitter.position() - 2;
            int right = horizontal ? splitter.position() + 2 : splitter.end();
            int bottom = horizontal ? splitter.end() : splitter.position() + 2;
            boolean hover = mx >= left - 3 && mx <= right + 3 && my >= top - 3 && my <= bottom + 3;
            g.fill(left, top, right, bottom, hover ? CYAN : 0xAA34495B);
        }
        if (draggingPanel != null) {
            g.fill(dragMouseX - 56, dragMouseY - 18, dragMouseX + 56, dragMouseY + 18, 0xAA173847);
            g.fill(dragMouseX - 56, dragMouseY - 18, dragMouseX + 56, dragMouseY - 16, CYAN);
            g.drawCenteredString(font, "DOCK " + draggingPanel.toUpperCase(java.util.Locale.ROOT),
                    dragMouseX, dragMouseY - 4, TEXT);
        }
    }

    private void drawSearchOverlay(GuiGraphics g, int mx, int my) {
        int boxWidth = Math.min(520, width - 80);
        int x = (width - boxWidth) / 2;
        int y = Math.max(48, height / 5);
        g.fill(0, 35, width, height, 0x99000000);
        g.fill(x, y, x + boxWidth, y + 30, SURFACE_ALT);
        g.fill(x, y + 29, x + boxWidth, y + 31, CYAN);
        g.drawString(font, "⌕", x + 12, y + 10, CYAN, false);
        g.drawString(font, searchQuery.isBlank() ? "Search characters, objects, territories…" : searchQuery,
                x + 34, y + 10, TEXT, false);
        int rowY = y + 40;
        for (int i = 0; i < Math.min(7, searchResults.size()); i++) {
            WorkspaceRuntime.SearchEntry result = searchResults.get(i);
            int top = rowY + i * 25;
            boolean hover = mx >= x && mx <= x + boxWidth && my >= top && my < top + 23;
            g.fill(x, top, x + boxWidth, top + 23, hover ? 0x553B9FB5 : SURFACE);
            g.drawString(font, result.name(), x + 12, top + 4, TEXT, false);
            String meta = result.type() + " · " + result.providerId()
                    + (result.worldId().isBlank() ? "" : " · " + result.worldId());
            g.drawString(font, trimInspector(meta, boxWidth - 24), x + 12, top + 14, MUTED, false);
        }
        if (searchResults.isEmpty())
            g.drawString(font, searchQuery.isBlank() ? "Type to search indexed game entities" : "No indexed entities found",
                    x + 12, rowY + 6, MUTED, false);
        g.drawString(font, "ENTER  OPEN FIRST RESULT     ESC  CLOSE", x + 12,
                rowY + Math.max(1, Math.min(7, searchResults.size())) * 25 + 8, MUTED, false);
    }

    private void drawContextMenu(GuiGraphics g, int mx, int my) {
        int menuWidth = 190;
        int rowHeight = 22;
        int x = Math.max(4, Math.min(contextMenuX, width - menuWidth - 4));
        int y = Math.max(38, Math.min(contextMenuY, height - contextActions.size() * rowHeight - 4));
        g.fill(x, y, x + menuWidth, y + contextActions.size() * rowHeight + 4, SURFACE);
        g.fill(x, y, x + menuWidth, y + 2, CYAN);
        for (int i = 0; i < contextActions.size(); i++) {
            int rowY = y + 4 + i * rowHeight;
            boolean hover = mx >= x && mx <= x + menuWidth && my >= rowY && my < rowY + rowHeight;
            if (hover) g.fill(x + 2, rowY, x + menuWidth - 2, rowY + rowHeight, 0x553B9FB5);
            g.drawString(font, contextActions.get(i).label(), x + 10, rowY + 7, TEXT, false);
        }
    }

    private void refreshSearch() {
        searchResults = WorkspaceRuntime.search(searchQuery, 7);
    }

    private void chooseSearchResult(int index) {
        if (index < 0 || index >= searchResults.size()) return;
        WorkspaceRuntime.SearchEntry entry = searchResults.get(index);
        PlatformSelectionContext.publish(entry.providerId(), entry.id(), entry.type(),
                entry.name(), entry.worldId(), entry.summary());
        try { WorkspaceRuntime.openSearchEntry(entry); }
        catch (RuntimeException error) {
            LOGGER.warn("Provider could not open search result {}", entry.id(), error);
        }
        contextMenuVisible = false;
        searchVisible = false;
    }

    private boolean commandCameraActive() {
        try {
            Class<?> mode = Class.forName("dev.worldforge.troops.mc.client.CommandMode");
            return Boolean.TRUE.equals(mode.getMethod("isCommandCameraActive").invoke(null));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    private String worldClockDate() {
        try {
            Class<?> clock = Class.forName("dev.worldforge.mod.client.ClientClock");
            Object value = clock.getMethod("date").invoke(null);
            return value instanceof String text && !text.isBlank() ? text : "—";
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return "—";
        }
    }

    private int worldClockSpeedIndex() {
        try {
            Class<?> clock = Class.forName("dev.worldforge.mod.client.ClientClock");
            return ((Number) clock.getMethod("speedIndex").invoke(null)).intValue();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return -1;
        }
    }

    private boolean worldClockPaused() {
        try {
            Class<?> clock = Class.forName("dev.worldforge.mod.client.ClientClock");
            return Boolean.TRUE.equals(clock.getMethod("paused").invoke(null));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    private boolean canControlWorldClock() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.hasSingleplayerServer() || mc.player.hasPermissions(2));
    }

    private int clockControlX() {
        if (commandCameraActive()) return cameraControlX() - ux(188);
        return profileStartX() + 4 * profileButtonStep() + ux(10);
    }

    private void sendWorldClockCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (!canControlWorldClock() || mc.player == null || mc.player.connection == null) {
            LOGGER.warn("World clock control requires trusted server permissions");
            return;
        }
        mc.player.connection.sendCommand("wftime " + command);
    }

    private String commandCameraView() {
        try {
            Class<?> mode = Class.forName("dev.worldforge.troops.mc.client.CommandMode");
            Object value = mode.getMethod("commandCameraView").invoke(null);
            return value instanceof String text ? text : "";
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return "";
        }
    }

    private void setCommandCameraView(String view) {
        try {
            Class<?> mode = Class.forName("dev.worldforge.troops.mc.client.CommandMode");
            mode.getMethod("setCommandCameraView", String.class).invoke(null, view);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            LOGGER.warn("RTS camera control is unavailable: {}", view);
        }
    }

    private boolean openUnifiedRts() {
        if (!ModList.get().isLoaded("wf_troops")) return false;
        try {
            Class<?> mode = Class.forName("dev.worldforge.troops.mc.client.CommandMode");
            mode.getMethod("openUnified").invoke(null);
            return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            LOGGER.warn("Unable to open unified RTS command workspace", unavailable);
            return false;
        }
    }

    private void openProfile(Profile target) {
        if (target == Profile.COSMOS) {
            setProfile(target);
            return;
        }
        if (target == Profile.RTS && openUnifiedRts()) return;
        String capabilityId = switch (target) {
            case POLITICS -> "worldforge.realms.politics";
            case SETTLEMENTS -> "worldforge.settlements";
            case RTS -> "worldforge.rts";
            default -> "";
        };
        CapabilityRegistry.all().stream().filter(c -> c.id().equals(capabilityId)).findFirst()
                .ifPresentOrElse(this::openCapability, () -> setProfile(target));
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        g.drawString(font, "M", ux(15), uy(12), CYAN, false);
        g.fill(ux(34), uy(9), ux(35), uy(25), EDGE);
        int x = profileStartX(), buttonWidth = profileButtonWidth(), buttonStep = profileButtonStep();
        for (Profile p : Profile.values()) {
            if (!profileAvailable(p)) continue;
            boolean active = p == profile;
            g.fill(x, uy(5), x + buttonWidth, uy(29), active ? 0x553B9FB5 : 0x00101924);
            if (active) g.fill(x, uy(28), x + buttonWidth, uy(30), CYAN);
            g.drawCenteredString(font, p.icon, x + buttonWidth/2, uy(8), active ? CYAN : MUTED);
            if (mx >= x && mx < x + buttonWidth && my >= uy(5) && my < uy(30))
                g.renderTooltip(font, Component.literal(p.label), mx, my);
            x += buttonStep;
        }
        if (!compactTopbar())
            g.drawString(font, embeddedView != null && profile == Profile.COSMOS ? "ATLAS" : "WORKSPACE", ux(46), uy(13), MUTED, false);
        int clockX = clockControlX();
        if (width >= 720 && clockX > ux(48) && clockX + ux(180) < width - ux(92)) {
            String date = worldClockDate();
            g.drawString(font, date, clockX, uy(13), TEXT, false);
            int controlsX = clockX + ux(86);
            String[] clockLabels = {"Ⅱ", "1", "2", "4"};
            int[] clockSpeeds = {0, 1, 2, 3};
            int activeSpeed = worldClockSpeedIndex();
            for (int i = 0; i < clockLabels.length; i++) {
                int bx = controlsX + i * ux(23);
                boolean activeClock = i == 0 ? worldClockPaused() : activeSpeed == clockSpeeds[i];
                int color = activeClock ? CYAN : MUTED;
                g.fill(bx, uy(5), bx + ux(20), uy(29), activeClock ? 0x553B9FB5 : SURFACE_ALT);
                if (activeClock) g.fill(bx, uy(28), bx + ux(20), uy(30), CYAN);
                g.drawCenteredString(font, clockLabels[i], bx + ux(10), uy(13), color);
                if (mx >= bx && mx < bx + ux(20) && my >= uy(5) && my < uy(30)) {
                    String tip = i == 0 ? "Пауза / продолжить время"
                            : "Скорость мирового времени ×" + clockLabels[i];
                    g.renderTooltip(font, Component.literal(tip), mx, my);
                }
            }
            if (!canControlWorldClock() && mx >= controlsX && mx < controlsX + ux(92)
                    && my >= uy(5) && my < uy(30))
                g.renderTooltip(font, Component.literal("Недостаточно прав для управления временем"), mx, my);
        }
        if (commandCameraActive() && width >= 480) {
            String current = commandCameraView();
            String[] ids = {"SKY", "TACTICS", "FREE", "TOP_DOWN", "STRATEGIC"};
            String[] labels = {"RTS", "ISO", "FREE", "TOP", "POL"};
            int cameraX = cameraControlX();
            if (!compactTopbar()) g.drawString(font, "CAM", cameraX - ux(28), uy(13), MUTED, false);
            for (int i = 0; i < ids.length; i++) {
                int cameraButtonX = cameraX + i * ux(34);
                boolean activeCamera = ids[i].equals(current);
                g.fill(cameraButtonX, uy(5), cameraButtonX + ux(30), uy(29), activeCamera ? 0x663B9FB5 : SURFACE_ALT);
                if (activeCamera) g.fill(cameraButtonX, uy(28), cameraButtonX + ux(30), uy(30), CYAN);
                g.drawCenteredString(font, labels[i], cameraButtonX + ux(15), uy(13), activeCamera ? CYAN : TEXT);
                if (mx >= cameraButtonX && mx < cameraButtonX + ux(30) && my >= uy(5) && my < uy(30))
                    g.renderTooltip(font, Component.literal(switch (ids[i]) {
                        case "SKY" -> "Классическая RTS-камера";
                        case "TACTICS" -> "Изометрическая тактическая камера";
                        case "FREE" -> "Свободная Total War камера";
                        case "TOP_DOWN" -> "Строгий вид сверху";
                        default -> "Стратегический обзор";
                    }), mx, my);
            }
        }
        g.drawCenteredString(font, "▤", width - ux(76), uy(13), workspaceManagerVisible ? CYAN : MUTED);
        g.drawCenteredString(font, "◷", width - ux(48), uy(13), notificationCenterVisible ? CYAN : MUTED);
        g.drawCenteredString(font, "×", width - ux(18), uy(13), MUTED);
        if (mx >= width - ux(88) && mx < width - ux(64) && my < uy(34))
            g.renderTooltip(font, Component.literal("Workspace profiles"), mx, my);
        if (mx >= width - ux(58) && mx < width - ux(38) && my < uy(34))
            g.renderTooltip(font, Component.literal("Notifications and event history"), mx, my);
        if (mx >= width - ux(30) && mx < width - ux(6) && my < uy(34))
            g.renderTooltip(font, Component.literal(activeView() == null ? "Close workspace" : "Close current module view"), mx, my);
        if (mx >= ux(8) && mx < ux(30) && my < uy(34)) g.renderTooltip(font, Component.literal("MAQAN workspace"), mx, my);
    }

    private void drawRail(GuiGraphics g, int mx, int my) {
        // The left-column PNG already contains seven tactile icon buttons.
        // Highlights and hit regions use the same reference-space geometry as that texture.
        String[] tips = {"Module catalogue", "Object inspector", "Global search",
                "Timeline / chronicle", "Political workspace", "Settlement workspace", "RTS command workspace"};
        boolean[] active = {panels.get("navigation").visible, panels.get("inspector").visible,
                searchVisible, panels.get("timeline").visible, profile == Profile.POLITICS,
                profile == Profile.SETTLEMENTS, profile == Profile.RTS};
        for (int i = 0; i < tips.length; i++) {
            int top = railTop(i), bottom = railBottom(i);
            if (active[i]) g.fill(ux(5), top + uy(5), ux(53), bottom - uy(5), 0x443A9FB5);
            if (mx >= 0 && mx < ux(58) && my >= top && my < bottom)
                g.renderTooltip(font, Component.literal(tips[i]), mx, my);
        }
        int settingsTop = height - uy(52);
        if (settingsVisible) g.fill(ux(5), settingsTop, ux(53), height - uy(4), 0x443A9FB5);
        g.drawCenteredString(font, "⚙", ux(29), height - uy(25), settingsVisible ? CYAN : MUTED);
        if (mx < ux(58) && my >= settingsTop)
            g.renderTooltip(font, Component.literal("Workspace settings"), mx, my);
    }

    private void drawWorkspaceManager(GuiGraphics g, int mx, int my) {
        int w=popupW(360),h=popupH(270),x=popupX(managerX,360,false),y=popupY(managerY,270,(height-h)/2);
        g.fill(0, 35, width, height, 0x99000000);
        g.fill(x, y, x + w, y + h, SURFACE);
        g.fill(x, y, x + w, y + 28, SURFACE_ALT);
        g.fill(x, y + 27, x + w, y + 29, CYAN);
        g.drawString(font, "WORKSPACE PROFILES", x + 12, y + 10, TEXT, false);
        g.drawString(font, "×", x + w - 18, y + 10, MUTED, false);
        g.drawString(font, "ACTIVE: " + activeWorkspaceName, x + 12, y + 39, CYAN, false);
        List<String> names = workspaceProfiles();
        int rowY = y + 58;
        int visible = Math.max(1, Math.min(6, (h - 112) / 22));
        for (int i = 0; i < Math.min(visible, names.size()); i++) {
            String name = names.get(i);
            int top = rowY + i * 22;
            boolean hover = mx >= x + 8 && mx < x + w - 8 && my >= top && my < top + 20;
            if (hover) g.fill(x + 8, top, x + w - 8, top + 20, 0x553B9FB5);
            g.drawString(font, name, x + 16, top + 6, name.equalsIgnoreCase(activeWorkspaceName) ? CYAN : TEXT, false);
        }
        int buttonY = y + h - 38;
        g.fill(x + 10, buttonY, x + 112, buttonY + 25, SURFACE_ALT);
        g.drawCenteredString(font, "SAVE CURRENT", x + 61, buttonY + 9, TEXT);
        g.fill(x + 120, buttonY, x + 222, buttonY + 25, SURFACE_ALT);
        g.drawCenteredString(font, "NEW PROFILE", x + 171, buttonY + 9, TEXT);
        g.fill(x + w - 112, buttonY, x + w - 10, buttonY + 25, SURFACE_ALT);
        g.drawCenteredString(font, "DELETE", x + w - 61, buttonY + 9, TEXT);
        g.drawString(font, "Select a profile to restore its dock layout", x + 12, y + h - 52, MUTED, false);
    }

    private void drawNotificationCenter(GuiGraphics g, int mx, int my) {
        int w=popupW(440),h=popupH(300),x=popupX(notificationsX,440,true),y=popupY(notificationsY,300,42);
        g.fill(0, 35, width, height, 0x66000000);
        g.fill(x, y, x + w, y + h, SURFACE);
        g.fill(x, y, x + w, y + 28, SURFACE_ALT);
        g.fill(x, y + 27, x + w, y + 29, CYAN);
        g.drawString(font, "NOTIFICATIONS / EVENT HISTORY", x + 12, y + 10, TEXT, false);
        g.drawString(font, "×", x + w - 18, y + 10, MUTED, false);
        List<WorkspaceRuntime.WorkspaceEvent> events = WorkspaceRuntime.journal(40);
        int rowY = y + 38;
        int rows = Math.max(1, (h - 48) / 28);
        int start = Math.max(0, events.size() - rows);
        for (int i = start; i < events.size(); i++) {
            WorkspaceRuntime.WorkspaceEvent event = events.get(i);
            int top = rowY + (i - start) * 28;
            int color = switch (event.severity()) {
                case SUCCESS -> 0xFF62D69C;
                case WARNING -> 0xFFE5B65C;
                case ERROR -> 0xFFFF7777;
                default -> CYAN;
            };
            g.fill(x + 10, top + 3, x + 13, top + 22, color);
            g.drawString(font, trimInspector(event.message(), w - 42), x + 20, top + 3, TEXT, false);
            String source = event.source() + " · " + event.category()
                    + (event.worldId().isBlank() ? "" : " · " + event.worldId());
            g.drawString(font, trimInspector(source, w - 42), x + 20, top + 14, MUTED, false);
        }
        if (events.isEmpty()) g.drawString(font, "No events have been published yet", x + 12, rowY + 8, MUTED, false);
    }

    private void closeTransientOverlays() {
        settingsVisible = false;
        workspaceManagerVisible = false;
        notificationCenterVisible = false;
        searchVisible = false;
        contextMenuVisible = false;
    }

    private boolean handleSettingsClick(double mx, double my) {
        int w=popupW(260),h=popupH(190),sx=popupX(settingsX,260,false),sy=popupY(settingsY,190,height-h-12);
        if(mx>=sx+w-28&&mx<sx+w&&my>=sy&&my<sy+26){settingsVisible=false;return true;}
        if(mx>=sx&&mx<sx+w-28&&my>=sy&&my<sy+26){
            draggingPopup="settings";pointerOffsetX=(int)mx-sx;pointerOffsetY=(int)my-sy;return true;
        }
        if(mx>=sx&&mx<sx+w&&my>=sy+32&&my<sy+59){ambientGrid=!ambientGrid;saveWorkspaceState();return true;}
        if(mx>=sx&&mx<sx+w&&my>=sy+59&&my<sy+86){panelShadows=!panelShadows;saveWorkspaceState();return true;}
        // Texture themes are disabled during the code-only UI validation pass.
        if(my>=sy+115&&my<sy+139&&mx>=sx+10&&mx<sx+w-10) return true;
        // Settings is modal: clicking outside closes it but never activates a control underneath.
        settingsVisible=false;
        return true;
    }

    private void drawSettings(GuiGraphics g, int mx, int my) {
        int w=popupW(260),h=popupH(190),x=popupX(settingsX,260,false),y=popupY(settingsY,190,height-h-12);
        g.fill(x+3,y+4,x+w+3,y+h+4,0x80000000);
        drawCodeWindow(g, x, y, w, h, "SETTINGS");
        g.enableScissor(x,y,x+w,y+h);
        g.fill(x + 8, y + 6, x + w - 8, y + 22, 0x55101824);
        g.drawString(font,"⚙",x+10,y+9,CYAN,false);
        g.drawString(font,"WORKSPACE / APPEARANCE",x+28,y+9,TEXT,false);
        g.drawCenteredString(font,"×",x+w-15,y+8,MUTED);
        g.drawString(font,"Background grid",x+12,y+42,TEXT,false);
        g.drawString(font,ambientGrid?"✓":"—",x+w-26,y+42,ambientGrid?CYAN:MUTED,false);
        g.drawString(font,"Panel shadows",x+12,y+66,TEXT,false);
        g.drawString(font,panelShadows?"✓":"—",x+w-26,y+66,panelShadows?CYAN:MUTED,false);
        g.fill(x+10,y+91,x+w-10,y+92,EDGE);
        g.drawString(font,"Interface theme",x+12,y+99,TEXT,false);
        g.fill(x+10,y+115,x+w-10,y+139,SURFACE_INSET);
        g.drawCenteredString(font,"CODE-ONLY UI",x+w/2,y+123,CYAN);
        if(h>145)g.drawString(font,"UI assets: disabled · code renderer",x+12,y+151,MUTED,false);
        if(h>168)g.drawString(font,"MAQAN / UI CORE",x+12,y+174,CYAN,false);
        if(mx>=x+w-28&&my>=y&&my<y+26) g.renderTooltip(font,Component.literal("Close settings"),mx,my);
        g.disableScissor();
    }

    private void drawCodeWindow(GuiGraphics g, int x, int y, int w, int h, String title) {
        g.fill(x, y, x + w, y + h, WorkspaceTheme.SURFACE);
        g.fill(x, y, x + w, y + 1, WorkspaceTheme.ACCENT);
        g.fill(x, y + 1, x + 1, y + h, WorkspaceTheme.BORDER);
        g.fill(x + w - 1, y + 1, x + w, y + h, WorkspaceTheme.BORDER);
        g.fill(x, y + h - 1, x + w, y + h, WorkspaceTheme.BORDER);
        g.fill(x + 1, y + 1, x + w - 1, Math.min(y + h, y + 26), WorkspaceTheme.SURFACE_RAISED);
        g.drawString(font, title == null ? "PANEL" : title.toUpperCase(java.util.Locale.ROOT), x + 9, y + 8, WorkspaceTheme.TEXT, false);
    }

    private void drawPanel(GuiGraphics g, Panel p, int mouseX, int mouseY) {
        int visibleH = p.minimized ? 26 : p.h;
        if (panelShadows) g.fill(p.x+3,p.y+4,p.x+p.w+3,p.y+visibleH+4,0x70000000);
        drawCodeWindow(g, p.x, p.y, p.w, visibleH, p.title);
        // Do not paint an opaque title strip over the supplied frame art.
        // A low-alpha backing keeps live text readable while preserving the skin.
        g.fill(p.x + 8, p.y + 6, p.x + p.w - 8, p.y + 22, 0x55101824);
        String icon = panelIcon(p.id);
        g.drawString(font,icon,p.x+9,p.y+8,CYAN,false);
        if (p.id.equals("inspector") || p.id.equals("tools")) {
            g.drawString(font, "INSPECT", p.x + 30, p.y + 8,
                    p.id.equals("inspector") ? CYAN : MUTED, false);
            g.drawString(font, "TOOLS", p.x + 82, p.y + 8,
                    p.id.equals("tools") ? CYAN : MUTED, false);
            g.fill(p.x + 28, p.y + 23, p.x + 76, p.y + 25,
                    p.id.equals("inspector") ? CYAN : EDGE);
            g.fill(p.x + 80, p.y + 23, p.x + 116, p.y + 25,
                    p.id.equals("tools") ? CYAN : EDGE);
        } else {
            g.drawString(font, trimInspector(p.title, Math.max(24, p.w - 100)), p.x + 25, p.y + 8, TEXT, false);
        }
        int bx=p.x+p.w-64;
        g.drawCenteredString(font,p.pinned?"◆":"◇",bx+8,p.y+8,p.pinned?CYAN:MUTED);
        g.drawCenteredString(font,p.minimized?"+":"–",bx+28,p.y+7,MUTED);
        g.drawCenteredString(font,"×",bx+48,p.y+7,MUTED);
        if(mouseX>=p.x&&mouseX<=p.x+26&&mouseY>=p.y&&mouseY<=p.y+26)
            g.renderTooltip(font,Component.literal(p.title),mouseX,mouseY);
        if (p.minimized) return;
        g.enableScissor(p.x + 1, p.y + 26, p.x + p.w - 1, p.y + p.h - 1);
        switch (p.id) {
            case "navigation" -> drawNavigationPanel(g,p);
            case "inspector" -> drawInspectorPanel(g,p);
            case "timeline" -> drawTimelinePanel(g,p);
            case "tools" -> drawCommandPanel(g,p);
            default -> {}
        }
        g.disableScissor();
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
        List<WorkspaceRuntime.Action> actions = WorkspaceRuntime.actionsFor(selected);
        int actionCount = Math.min(3, actions.size());
        int actionTop = p.y + p.h - actionCount * 23 - 8;
        int y = p.y + 210;
        for (WorkspaceRuntime.InspectorField field : WorkspaceRuntime.inspect(selected)) {
            if (y + 24 > (actionCount > 0 ? actionTop - 5 : p.y + p.h - 8)) break;
            if (!field.section().isBlank()) {
                g.drawString(font, trimInspector(field.section().toUpperCase(java.util.Locale.ROOT), p.w - 24),
                        left, y, CYAN, false);
                y += 13;
            }
            g.drawString(font, trimInspector(field.label(), p.w - 24), left, y, MUTED, false);
            y += 11;
            g.drawString(font, trimInspector(field.value(), p.w - 24), left, y, TEXT, false);
            y += 16;
        }
        for (int i = 0; i < actionCount; i++) {
            WorkspaceRuntime.Action action = actions.get(i);
            int top = actionTop + i * 23;
            g.fill(left, top, right, top + 20, 0xAA171D28);
            g.fill(left, top, left + 2, top + 20, CYAN);
            g.drawString(font, trimInspector(action.label(), p.w - 42), left + 9, top + 6, TEXT, false);
            g.drawString(font, "›", right - 10, top + 5, CYAN, false);
        }
        if (actions.size() > actionCount && actionCount > 0)
            g.drawString(font, "+" + (actions.size() - actionCount) + " ещё · ПКМ", left, actionTop - 11, MUTED, false);
    }

    private boolean executeInspectorActionAt(double mx, double my, Panel panel) {
        PlatformSelectionContext.Selection selection = PlatformSelectionContext.current();
        if (selection == null) return false;
        List<WorkspaceRuntime.Action> actions = WorkspaceRuntime.actionsFor(selection);
        int count = Math.min(3, actions.size());
        if (count == 0) return false;
        int top = panel.y + panel.h - count * 23 - 8;
        if (mx < panel.x + 10 || mx >= panel.x + panel.w - 10
                || my < top || my >= top + count * 23) return false;
        int index = (int) ((my - top) / 23);
        if (index < 0 || index >= count) return false;
        WorkspaceRuntime.Action action = actions.get(index);
        if (WorkspaceRuntime.executeAction(action.id(), selection)) {
            WorkspaceRuntime.publish(WorkspaceRuntime.Severity.SUCCESS, action.providerId(),
                    "ui-action", action.label(), selection.entityId(), selection.worldId());
        }
        return true;
    }

    private String trimInspector(String value, int maxWidth) {
        if (value == null || value.isBlank()) return "—";
        return font.plainSubstrByWidth(value, Math.max(16, maxWidth));
    }

    private void drawTimelinePanel(GuiGraphics g, Panel p) {
        g.drawString(font, "◷", p.x + 12, p.y + 38, CYAN, false);
        g.drawString(font, "WORLD EVENT JOURNAL", p.x + 30, p.y + 38, MUTED, false);
        List<WorkspaceRuntime.WorkspaceEvent> events = WorkspaceRuntime.journal(4);
        int y = p.y + 54;
        for (WorkspaceRuntime.WorkspaceEvent event : events) {
            if (y + 11 > p.y + p.h - 4) break;
            int color = switch (event.severity()) {
                case SUCCESS -> WorkspaceTheme.SUCCESS;
                case WARNING -> WorkspaceTheme.WARNING;
                case ERROR -> WorkspaceTheme.DANGER;
                case INFO -> MUTED;
            };
            g.fill(p.x + 12, y + 2, p.x + 15, y + 8, color);
            g.drawString(font, trimInspector(event.message(), p.w - 36), p.x + 22, y, TEXT, false);
            y += 13;
        }
        if (events.isEmpty())
            g.drawString(font, "Events from connected providers appear here", p.x + 12, y, MUTED, false);
    }

    private void drawCommandPanel(GuiGraphics g, Panel p) {
        g.drawString(font, "⌘", p.x + 12, p.y + 42, CYAN, false);
        g.drawString(font, "WORKSPACE COMMANDS", p.x + 30, p.y + 42, TEXT, false);
        g.drawString(font, "Ctrl+K  Global entity search", p.x + 12, p.y + 66, MUTED, false);
        g.drawString(font, WorkspaceRuntime.shortcuts().size() + " registered shortcuts",
                p.x + 12, p.y + 84, MUTED, false);
        g.drawString(font, WorkspaceRuntime.components().size() + " registered UI components",
                p.x + 12, p.y + 102, MUTED, false);
        int y = p.y + 122;
        for (WorkspaceMapLayerRegistry.Layer layer : WorkspaceMapLayerRegistry.layers()) {
            if (y + 14 > p.y + p.h - 6) break;
            g.drawString(font, WorkspaceMapLayerRegistry.isVisible(layer.id()) ? "✓" : "—",
                    p.x + 12, y, WorkspaceTheme.ACCENT, false);
            g.drawString(font, trimInspector(layer.label(), p.w - 142),
                    p.x + 28, y, TEXT, false);
            g.drawString(font, "−", p.x + p.w - 112, y, MUTED, false);
            g.drawString(font, "+", p.x + p.w - 94, y, MUTED, false);
            g.drawString(font, Math.round(WorkspaceMapLayerRegistry.opacity(layer.id()) * 100) + "%",
                    p.x + p.w - 70, y, MUTED, false);
            g.drawString(font, "↑", p.x + p.w - 34, y, MUTED, false);
            g.drawString(font, "↓", p.x + p.w - 18, y, MUTED, false);
            y += 16;
        }
    }

    @Override public void tick() {
        super.tick();
        Screen view = activeView();
        if (view != null) view.tick();
    }

    @Override public boolean mouseClicked(double mx,double my,int button) {
        if (button != 0 && (settingsVisible || workspaceManagerVisible || notificationCenterVisible
                || searchVisible || contextMenuVisible)) return true;
        if (button == 1) {
            Panel content = panels.get("content");
            if (content == null || !content.visible
                    || !WorkspaceUi.contains(mx, my, content.x, content.y, content.w, content.h)) return true;
            PlatformSelectionContext.Selection selection = PlatformSelectionContext.current();
            contextActions = WorkspaceRuntime.actionsFor(selection);
            if (!contextActions.isEmpty()) {
                closeTransientOverlays();
                contextMenuX = (int) mx; contextMenuY = (int) my;
                contextMenuVisible = true;
                return true;
            }
            return forwardViewMouseClicked(mx, my, button);
        }
        if (button != 0) return forwardViewMouseClicked(mx, my, button);
        if (workspaceManagerVisible) {
            int w=popupW(360),h=popupH(270),x=popupX(managerX,360,false),y=popupY(managerY,270,(height-h)/2);
            if (mx < x || mx >= x+w || my < y || my >= y+h) { workspaceManagerVisible=false; return true; }
            if (my < y+28 && mx >= x+w-28) { workspaceManagerVisible=false; return true; }
            if (my < y+28) { draggingPopup="manager"; pointerOffsetX=(int)mx-x; pointerOffsetY=(int)my-y; return true; }
            int buttonY = y + h - 38;
            if (my >= buttonY && my < buttonY + 25) {
                if (mx >= x + 10 && mx < x + 112) {
                    saveWorkspaceProfile(activeWorkspaceName);
                } else if (mx >= x + 120 && mx < x + 222) {
                    List<String> names = workspaceProfiles();
                    int suffix = names.size() + 1;
                    String candidate = "Workspace " + suffix;
                    while (containsProfileName(names, candidate)) {
                        suffix++;
                        candidate = "Workspace " + suffix;
                    }
                    saveWorkspaceProfile(candidate);
                } else if (mx >= x + w - 112 && mx < x + w - 10
                        && !activeWorkspaceName.equalsIgnoreCase("Default")) {
                    deleteWorkspaceProfile(activeWorkspaceName);
                }
                return true;
            }
            int rowY = y + 58;
            int row = (int) ((my - rowY) / 22);
            List<String> names = workspaceProfiles();
            if (my >= rowY && row >= 0 && row < Math.min(6, names.size())) {
                loadWorkspaceProfile(names.get(row));
                return true;
            }
            return true;
        }
        if (notificationCenterVisible) {
            int w=popupW(440),h=popupH(300),x=popupX(notificationsX,440,true),y=popupY(notificationsY,300,42);
            if (mx >= x + w - 28 && mx < x + w && my >= y && my < y + 28) {
                notificationCenterVisible = false;
            } else if (my >= y && my < y+28 && mx >= x && mx < x+w-28) {
                draggingPopup="notifications"; pointerOffsetX=(int)mx-x; pointerOffsetY=(int)my-y;
            } else if (mx < x || mx >= x+w || my < y || my >= y+h) notificationCenterVisible=false;
            return true;
        }
        if (searchVisible) {
            int boxWidth = Math.min(520, width - 80);
            int x = (width - boxWidth) / 2;
            int y = Math.max(48, height / 5) + 40;
            if (mx >= x && mx <= x + boxWidth && my >= y) {
                int index = (int) ((my - y) / 25);
                if (index >= 0 && index < searchResults.size()) chooseSearchResult(index);
            } else searchVisible = false;
            return true;
        }
        if (contextMenuVisible) {
            int menuWidth = 190, rowHeight = 22;
            int x = Math.max(4, Math.min(contextMenuX, width - menuWidth - 4));
            int y = Math.max(38, Math.min(contextMenuY, height - contextActions.size() * rowHeight - 4));
            int index = (int) ((my - y - 4) / rowHeight);
            if (mx >= x && mx <= x + menuWidth && index >= 0 && index < contextActions.size())
                WorkspaceRuntime.executeAction(contextActions.get(index).id(), PlatformSelectionContext.current());
            contextMenuVisible = false;
            return true;
        }
        if (settingsVisible && handleSettingsClick(mx, my)) return true;
        if (!immersiveRts()) for (WorkspaceDockTree.Splitter splitter : WorkspaceDockTree.splitters(layoutRoot(), workspaceBounds(), workspaceMinimums())) {
            boolean hit = splitter.axis() == WorkspaceDockTree.Axis.HORIZONTAL
                    ? Math.abs(mx - splitter.position()) <= 4 && my >= splitter.start() && my <= splitter.end()
                    : Math.abs(my - splitter.position()) <= 4 && mx >= splitter.start() && mx <= splitter.end();
            if (hit) { resizingSplitterPath = splitter.path(); return true; }
        }
        if (!immersiveRts() && my < uy(34)) {
            if (mx >= width - ux(88) && mx < width - ux(64)) {
                boolean open = !workspaceManagerVisible;
                closeTransientOverlays();
                workspaceManagerVisible = open;
                return true;
            }
            if (mx >= width - ux(58) && mx < width - ux(38)) {
                boolean open = !notificationCenterVisible;
                closeTransientOverlays();
                notificationCenterVisible = open;
                return true;
            }
            if (mx >= width - ux(30) && mx < width - ux(6)) {
                Screen view = activeView();
                if (view != null) detachProviderView(view);
                else onClose();
                return true;
            }
            int clockX = clockControlX();
            if (width >= ux(720) && clockX > ux(48) && clockX + ux(180) < width - ux(92)
                    && my >= uy(5) && my < uy(30)) {
                int controlsX = clockX + ux(86);
                for (int i = 0; i < 4; i++) {
                    int bx = controlsX + i * ux(23);
                    if (mx >= bx && mx < bx + ux(20)) {
                        if (i == 0) sendWorldClockCommand("toggle");
                        else sendWorldClockCommand("speed " + i);
                        return true;
                    }
                }
                if (mx >= clockX && mx < controlsX) return true;
            }
            if (commandCameraActive() && width >= 480) {
                int cameraX = cameraControlX();
                if (my >= uy(5) && my < uy(30) && mx >= cameraX && mx < cameraX + ux(170)) {
                    int index = (int) ((mx - cameraX) / Math.max(1, ux(34)));
                    if (index >= 0 && index < 5 && mx < cameraX + index * ux(34) + ux(30)) {
                        setCommandCameraView(new String[]{"SKY", "TACTICS", "FREE", "TOP_DOWN", "STRATEGIC"}[index]);
                        return true;
                    }
                }
            }
            int x=profileStartX(),buttonWidth=profileButtonWidth(),step=profileButtonStep();
            for(Profile p:Profile.values()) {
                if (!profileAvailable(p)) continue;
                if(mx>=x&&mx<x+buttonWidth) { openProfile(p); return true; }
                x+=step;
            }
            return true;
        }
        if(!immersiveRts() && mx < ux(58)) {
            if(my >= height - uy(52)) {
                boolean open = !settingsVisible;
                closeTransientOverlays();
                settingsVisible = open;
                return true;
            }
            for (int index = 0; index < 7; index++) {
                if (!railHit(mx, my, index)) continue;
                switch (index) {
                    case 0 -> {
                        if (profile == Profile.COSMOS && embeddedView != null) invokeAtlasRailAction("toggleListPanel");
                        else toggleWorkspacePanel("navigation");
                    }
                    case 1 -> {
                        if (profile == Profile.COSMOS && embeddedView != null) invokeAtlasRailAction("toggleInfoPanel");
                        else if (panels.get("inspector").visible) toggleWorkspacePanel("inspector");
                        else {
                            hiddenPanels.remove("inspector");
                            workspaceRoot = WorkspaceDockTree.activateTab(workspaceRoot, "inspector");
                            layoutWorkspace(); saveWorkspaceState();
                        }
                    }
                    case 2 -> {
                        boolean open = !searchVisible;
                        closeTransientOverlays();
                        searchVisible = open;
                        searchQuery = "";
                        searchResults = List.of();
                    }
                    case 3 -> toggleWorkspacePanel("timeline");
                    case 4 -> openProfile(Profile.POLITICS);
                    case 5 -> openProfile(Profile.SETTLEMENTS);
                    case 6 -> openProfile(Profile.RTS);
                    default -> { }
                }
                return true;
            }
            return true;
        }
        if(profile==Profile.COSMOS && embeddedView==null && my>=40&&my<68 && mx>=54&&mx<270) {
            int x=58;
            for(int i=0;i<4;i++,x+=52) if(mx>=x&&mx<x+44) { previewLayer=i; return true; }
        }
        List<Panel> reverse=new ArrayList<>(panels.values());
        java.util.Collections.reverse(reverse);
        for(Panel p:reverse) {
            if(!p.visible || p.id.equals("content")) continue;
            int ph=p.minimized?26:p.h;
            if(mx<p.x||mx>p.x+p.w||my<p.y||my>p.y+ph) continue;
            if ("FLOAT".equals(p.dock) && !p.minimized && mx>=p.x+p.w-14 && my>=p.y+p.h-14) {
                resizingPanel=p.id; resizeStartX=(int)mx; resizeStartY=(int)my; resizeStartW=p.w; resizeStartH=p.h; return true;
            }
            if (my <= p.y + 26) {
                if ((p.id.equals("inspector") || p.id.equals("tools"))
                        && mx >= p.x + 28 && mx < p.x + 116) {
                    workspaceRoot = WorkspaceDockTree.activateTab(workspaceRoot,
                            mx < p.x + 80 ? "inspector" : "tools");
                    layoutWorkspace(); saveWorkspaceState(); return true;
                }
                int bx=p.x+p.w-64;
                if(mx>=bx&&mx<bx+16) { p.pinned=!p.pinned; saveWorkspaceState(); return true; }
                if(mx>=bx+18&&mx<bx+38) { p.minimized=!p.minimized; saveWorkspaceState(); return true; }
                if(mx>=bx+38&&mx<bx+58) {
                    if (!p.pinned) hiddenPanels.add(p.id);
                    layoutWorkspace(); saveWorkspaceState(); return true;
                }
                if(p.pinned) return true;
                if((p.id.equals("inspector")||p.id.equals("tools"))&&mx>=p.x+28&&mx<p.x+116) return true;
                draggingPanel=p.id; panelDragMoved=false;
                pointerOffsetX=(int)mx-p.x; pointerOffsetY=(int)my-p.y;
                dragMouseX=(int)mx; dragMouseY=(int)my; dragStartX=(int)mx; dragStartY=(int)my; return true;
            }
            if (p.id.equals("tools")) {
                List<WorkspaceMapLayerRegistry.Layer> layers = WorkspaceMapLayerRegistry.layers();
                int layerIndex = (int) ((my - (p.y + 122)) / 16);
                if (layerIndex >= 0 && layerIndex < layers.size()) {
                    WorkspaceMapLayerRegistry.Layer layer = layers.get(layerIndex);
                    if (mx >= p.x + p.w - 42) {
                        WorkspaceMapLayerRegistry.move(layer.id(), mx < p.x + p.w - 20 ? -1 : 1);
                    } else if (mx >= p.x + p.w - 116 && mx < p.x + p.w - 98) {
                        WorkspaceMapLayerRegistry.setOpacity(layer.id(),
                                Math.max(0f, WorkspaceMapLayerRegistry.opacity(layer.id()) - 0.05f));
                    } else if (mx >= p.x + p.w - 98 && mx < p.x + p.w - 80) {
                        WorkspaceMapLayerRegistry.setOpacity(layer.id(),
                                Math.min(1f, WorkspaceMapLayerRegistry.opacity(layer.id()) + 0.05f));
                    } else {
                        WorkspaceMapLayerRegistry.setVisible(layer.id(), !WorkspaceMapLayerRegistry.isVisible(layer.id()));
                    }
                    saveWorkspaceState();
                    return true;
                }
            }
            if (p.id.equals("inspector") && executeInspectorActionAt(mx, my, p)) return true;
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
        return forwardViewMouseClicked(mx, my, button);
    }

    private void applyPanelDrop(String sourceId, int mouseX, int mouseY) {
        if (sourceId == null || sourceId.equals("content")) return;
        Map<String, WorkspaceDockTree.Rect> rectangles =
                WorkspaceDockTree.layout(layoutRoot(), workspaceBounds(), workspaceMinimums());
        String targetId = null;
        WorkspaceDockTree.Rect targetRect = null;
        for (Map.Entry<String, WorkspaceDockTree.Rect> entry : rectangles.entrySet()) {
            if (entry.getKey().equals(sourceId)) continue;
            WorkspaceDockTree.Rect rect = entry.getValue();
            if (WorkspaceUi.contains(mouseX, mouseY, rect.x(), rect.y(), rect.width(), rect.height())) {
                targetId = entry.getKey(); targetRect = rect; break;
            }
        }
        Panel source = panels.get(sourceId);
        if (targetId == null || targetRect == null) {
            workspaceRoot = WorkspaceDockTree.remove(workspaceRoot, sourceId);
            if (source != null) {
                source.dock = "FLOAT";
                source.x = mouseX - source.w / 2;
                source.y = mouseY - 12;
                source.visible = !hiddenPanels.contains(source.id);
                clampPanels();
            }
            WorkspaceDockTree.validate(workspaceRoot);
            return;
        }
        if (source != null) source.dock = "DOCK";
        int relativeX = mouseX - targetRect.x();
        int relativeY = mouseY - targetRect.y();
        double nx = relativeX / (double) Math.max(1, targetRect.width());
        double ny = relativeY / (double) Math.max(1, targetRect.height());
        WorkspaceDockTree.Node remaining = WorkspaceDockTree.remove(workspaceRoot, sourceId);
        if (remaining == null) remaining = new WorkspaceDockTree.Panel(targetId);
        if (!targetId.equals("content") && nx > 0.3 && nx < 0.7 && ny > 0.3 && ny < 0.7) {
            workspaceRoot = WorkspaceDockTree.group(remaining, targetId, sourceId);
        } else {
            double left = nx, right = 1.0 - nx, top = ny, bottom = 1.0 - ny;
            double nearest = Math.min(Math.min(left, right), Math.min(top, bottom));
            WorkspaceDockTree.Side side = nearest == left ? WorkspaceDockTree.Side.LEFT
                    : nearest == right ? WorkspaceDockTree.Side.RIGHT
                    : nearest == top ? WorkspaceDockTree.Side.TOP : WorkspaceDockTree.Side.BOTTOM;
            workspaceRoot = WorkspaceDockTree.dock(remaining, targetId, sourceId, side, 300);
        }
        WorkspaceDockTree.validate(workspaceRoot);
    }

    private static boolean containsProfileName(List<String> names, String candidate) {
        for (String name : names) if (name.equalsIgnoreCase(candidate)) return true;
        return false;
    }

    public List<String> workspaceProfiles() {
        if (profileStore == null) profileStore = new WorkspaceProfileStore(workspacePreferences().node("profiles"));
        return profileStore.list();
    }

    public void saveWorkspaceProfile(String name) {
        if (profileStore == null) profileStore = new WorkspaceProfileStore(workspacePreferences().node("profiles"));
        profileStore.save(name, workspaceRoot);
        activeWorkspaceName = name.trim();
        saveWorkspaceState();
    }

    public boolean loadWorkspaceProfile(String name) {
        if (profileStore == null) profileStore = new WorkspaceProfileStore(workspacePreferences().node("profiles"));
        try {
            workspaceRoot = profileStore.load(name);
            activeWorkspaceName = name.trim();
            layoutWorkspace();
            saveWorkspaceState();
            return true;
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to load workspace profile {}", name, error);
            return false;
        }
    }

    public void deleteWorkspaceProfile(String name) {
        if (profileStore == null) profileStore = new WorkspaceProfileStore(workspacePreferences().node("profiles"));
        profileStore.delete(name);
        if (activeWorkspaceName.equalsIgnoreCase(name)) {
            activeWorkspaceName = "Default";
            workspaceRoot = defaultWorkspaceTree();
            saveWorkspaceState();
        }
    }

    private void toggleWorkspacePanel(String id) {
        if (immersiveRts() && !"content".equals(id)) {
            if (!rtsOverlayPanels.add(id)) rtsOverlayPanels.remove(id);
            Panel overlay = panels.get(id);
            if (overlay != null) {
                overlay.dock = "FLOAT";
                overlay.visible = rtsOverlayPanels.contains(id);
                if (overlay.x + overlay.w > width) overlay.x = Math.max(8, width - overlay.w - 8);
                if (overlay.y + overlay.h > height) overlay.y = Math.max(8, height - overlay.h - 8);
            }
            return;
        }
        Panel panel=panels.get(id);
        boolean currentlyVisible=panel!=null && panel.visible && !hiddenPanels.contains(id);
        if(!currentlyVisible) {
            hiddenPanels.remove(id);
            if(compactViewport() && panel!=null && !id.equals("content")) {
                panel.dock="FLOAT";
                panel.x=Math.max(48,width-panel.w-16);
                panel.y=Math.max(42,Math.min(48,height-panel.h-8));
            }
        } else hiddenPanels.add(id);
        layoutWorkspace();saveWorkspaceState();
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
            if (embeddedView != null) initViewInContent(embeddedView);
        } else if (next == Profile.POLITICS) {
            ensurePoliticalMapView();
        } else {
            Screen view = providerViews.get(selectedCapabilityId);
            if (view != null) initViewInContent(view);
        }
        selectedDomain=switch(next) {
            case COSMOS -> "COSMOS";
            case POLITICS -> "POLITICS";
            case SETTLEMENTS -> "SETTLEMENTS";
            case RTS -> "TACTICAL";
        };
        if (!panels.isEmpty()) {
            Panel nav = panels.get("navigation");
            Panel inspector = panels.get("inspector");
            Panel timeline = panels.get("timeline");
            if (nav != null && inspector != null && timeline != null) {
                switch (next) {
                    case COSMOS -> { nav.title = "COSMIC CATALOGUE"; inspector.title = "BODY INSPECTOR"; timeline.title = "ORBIT / TIME"; }
                    case POLITICS -> { nav.title = "REALMS & DIPLOMACY"; inspector.title = "CHARACTER / REALM"; timeline.title = "DYNASTY / CHRONICLE"; }
                    case SETTLEMENTS -> { nav.title = "SETTLEMENT LAYERS"; inspector.title = "BUILDING / HOUSEHOLD"; timeline.title = "PRODUCTION"; }
                    case RTS -> { nav.title = "UNITS / FORMATIONS"; inspector.title = "UNIT COMMAND"; timeline.title = "ORDERS / BATTLE LOG"; }
                }
            }
            layoutWorkspace();
            clampPanels();
        }
        saveWorkspaceState();
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
        if ("maqan.atlas.exploration".equals(capability.id())) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            try {
                Class<?> parentType = Class.forName("com.maqan.galacticnavigator.client.NavigatorScreen");
                Screen parent = (Screen) parentType.getDeclaredConstructor().newInstance();
                Class<?> mapType = Class.forName("com.maqan.galacticnavigator.client.NavigatorSurfaceMapScreen");
                Screen map = (Screen) mapType.getConstructor(String.class, Screen.class)
                        .newInstance(mc.level.dimension().location().toString(), parent);
                embeddedView = map;
                initializedView = null;
                WorkspaceMapState.setViewType(WorkspaceMapState.ViewType.EXPLORATION);
                initViewInContent(map);
                saveWorkspaceState();
                return;
            } catch (ReflectiveOperationException | LinkageError error) {
                LOGGER.warn("Unable to open the native exploration map", error);
                return;
            }
        }
        Screen view = activeView();
        if (view != null) {
            initViewInContent(view);
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
        if(button!=0 && (settingsVisible || workspaceManagerVisible || notificationCenterVisible || searchVisible || contextMenuVisible))
            return true;
        if(button!=0) {
            boolean handled = super.mouseDragged(mx,my,button,dx,dy);
            handled = forwardViewMouseDragged(mx, my, button, dx, dy) || handled;
            return handled;
        }
        if (draggingPopup == null && (settingsVisible || workspaceManagerVisible
                || notificationCenterVisible || searchVisible || contextMenuVisible)) return true;
        if (draggingPopup != null) {
            int pw=draggingPopup.equals("manager")?360:draggingPopup.equals("notifications")?440:260;
            int ph=draggingPopup.equals("manager")?270:draggingPopup.equals("notifications")?300:190;
            int x=Math.max(8,Math.min((int)mx-pointerOffsetX,Math.max(8,width-popupW(pw)-8)));
            int y=Math.max(34,Math.min((int)my-pointerOffsetY,Math.max(34,height-popupH(ph)-8)));
            switch(draggingPopup) {
                case "manager" -> {managerX=x;managerY=y;}
                case "notifications" -> {notificationsX=x;notificationsY=y;}
                case "settings" -> {settingsX=x;settingsY=y;}
            }
            return true;
        }
        if (resizingSplitterPath != null) {
            for (WorkspaceDockTree.Splitter splitter : WorkspaceDockTree.splitters(layoutRoot(), workspaceBounds())) {
                if (!splitter.path().equals(resizingSplitterPath)) continue;
                int span = Math.max(1, splitter.end() - splitter.start());
                int coordinate = splitter.axis() == WorkspaceDockTree.Axis.HORIZONTAL ? (int) mx : (int) my;
                int weight = Math.max(WorkspaceDockTree.MIN_SPLIT_WEIGHT,
                        Math.min(WorkspaceDockTree.MAX_SPLIT_WEIGHT,
                                (coordinate - splitter.start()) * WorkspaceDockTree.WEIGHT_SCALE / span));
                workspaceRoot = WorkspaceDockTree.resize(workspaceRoot, resizingSplitterPath, weight);
                layoutWorkspace();
                return true;
            }
        }
        if (draggingPanel != null) {
            if (Math.hypot(mx - dragStartX, my - dragStartY) > 4) panelDragMoved=true;
            dragMouseX=(int)mx; dragMouseY=(int)my;
            Panel p=panels.get(draggingPanel);
            if(p!=null&&"FLOAT".equals(p.dock)){p.x=(int)mx-pointerOffsetX;p.y=(int)my-pointerOffsetY;clampPanels();}
            return true;
        }
        if(resizingPanel!=null) {
            Panel p=panels.get(resizingPanel);
            if(p!=null){p.w=Math.max(140,resizeStartW+(int)mx-resizeStartX);p.h=Math.max(54,resizeStartH+(int)my-resizeStartY);clampPanels();}
            return true;
        }
        boolean handled = super.mouseDragged(mx,my,button,dx,dy);
        handled = forwardViewMouseDragged(mx, my, button, dx, dy) || handled;
        return handled;
    }

    @Override public boolean mouseReleased(double mx,double my,int button) {
        boolean modalCaptured = settingsVisible || workspaceManagerVisible || notificationCenterVisible
                || searchVisible || contextMenuVisible || draggingPopup != null;
        if (button == 0) {
            boolean changed=draggingPanel!=null||resizingPanel!=null||resizingSplitterPath!=null||draggingPopup!=null;
            if(draggingPanel!=null && panelDragMoved) applyPanelDrop(draggingPanel,(int)mx,(int)my);
            draggingPanel=null;panelDragMoved=false;resizingPanel=null;resizingSplitterPath=null;draggingPopup=null;
            if(changed){layoutWorkspace();saveWorkspaceState();}
        }
        boolean handled = super.mouseReleased(mx,my,button);
        if (modalCaptured) return true;
        handled = forwardViewMouseReleased(mx, my, button) || handled;
        return handled;
    }

    @Override public boolean keyReleased(int keyCode,int scanCode,int modifiers) {
        boolean handled = super.keyReleased(keyCode,scanCode,modifiers);
        if (settingsVisible || workspaceManagerVisible || notificationCenterVisible || searchVisible || contextMenuVisible)
            return handled;
        if (activeView() != null)
            handled = activeView().keyReleased(keyCode,scanCode,modifiers) || handled;
        return handled;
    }

    @Override public boolean charTyped(char codePoint,int modifiers) {
        if (settingsVisible || workspaceManagerVisible || notificationCenterVisible || contextMenuVisible) return true;
        if (searchVisible) {
            if (!Character.isISOControl(codePoint) && searchQuery.length() < 96) {
                searchQuery += codePoint;
                refreshSearch();
            }
            return true;
        }
        boolean handled = super.charTyped(codePoint,modifiers);
        if (activeView() != null)
            handled = activeView().charTyped(codePoint,modifiers) || handled;
        return handled;
    }

    @Override public boolean mouseScrolled(double mouseX,double mouseY,double delta) {
        if (settingsVisible || workspaceManagerVisible || notificationCenterVisible || searchVisible || contextMenuVisible)
            return true;
        Panel toolsPanel = panels.get("tools");
        if (toolsPanel != null && toolsPanel.visible && !toolsPanel.minimized
                && mouseX >= toolsPanel.x && mouseX <= toolsPanel.x + toolsPanel.w
                && mouseY >= toolsPanel.y + 122 && mouseY < toolsPanel.y + toolsPanel.h - 4) {
            int index = (int) ((mouseY - (toolsPanel.y + 122)) / 16);
            List<WorkspaceMapLayerRegistry.Layer> layers = WorkspaceMapLayerRegistry.layers();
            if (index >= 0 && index < layers.size()) {
                WorkspaceMapLayerRegistry.Layer layer = layers.get(index);
                float opacity = WorkspaceMapLayerRegistry.opacity(layer.id());
                WorkspaceMapLayerRegistry.setOpacity(layer.id(),
                        Math.max(0f, Math.min(1f, opacity + (delta > 0 ? 0.05f : -0.05f))));
                return true;
            }
        }
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
        for (Panel panel : panels.values()) {
            if (panel.visible && !panel.id.equals("content")
                    && WorkspaceUi.contains(mouseX, mouseY, panel.x, panel.y, panel.w,
                    panel.minimized ? 26 : panel.h)) return true;
        }
        boolean handled = super.mouseScrolled(mouseX,mouseY,delta);
        handled = forwardViewMouseScrolled(mouseX, mouseY, delta) || handled;
        return handled;
    }

    @Override public boolean keyPressed(int keyCode,int scanCode,int modifiers) {
        if (searchVisible) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { searchVisible = false; return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                refreshSearch(); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) { chooseSearchResult(0); return true; }
            return true;
        }
        if (contextMenuVisible && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            contextMenuVisible = false; return true;
        }
        if (keyCode == GLFW.GLFW_KEY_K && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            closeTransientOverlays();
            searchVisible = true;
            searchQuery = "";
            searchResults = List.of();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_P
                && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT))
                == (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT)) {
            boolean open = !workspaceManagerVisible;
            closeTransientOverlays();
            workspaceManagerVisible = open;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_N
                && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT))
                == (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT)) {
            boolean open = !notificationCenterVisible;
            closeTransientOverlays();
            notificationCenterVisible = open;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (workspaceManagerVisible) { workspaceManagerVisible = false; return true; }
            if (notificationCenterVisible) { notificationCenterVisible = false; return true; }
            if (settingsVisible) { settingsVisible = false; return true; }
            if (contextMenuVisible) { contextMenuVisible = false; return true; }
            onClose(); return true;
        }
        if (workspaceManagerVisible || notificationCenterVisible || settingsVisible || contextMenuVisible) return true;
        if (keyCode == GLFW.GLFW_KEY_F6) { toggleWorkspacePanel("timeline"); return true; }
        if (immersiveRts() && keyCode == GLFW.GLFW_KEY_F7) { toggleWorkspacePanel("navigation"); return true; }
        if (immersiveRts() && keyCode == GLFW.GLFW_KEY_F8) { toggleWorkspacePanel("inspector"); return true; }
        if (immersiveRts() && keyCode == GLFW.GLFW_KEY_F9) { toggleWorkspacePanel("tools"); return true; }
        if (immersiveRts() && keyCode == GLFW.GLFW_KEY_F10) { settingsVisible = !settingsVisible; return true; }
        java.util.Optional<WorkspaceRuntime.Shortcut> registered =
                WorkspaceRuntime.shortcutFor(keyCode, modifiers);
        if (registered.isPresent()) {
            WorkspaceRuntime.executeAction(registered.get().actionId(), PlatformSelectionContext.current());
            return true;
        }
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
        super.resize(minecraft,width,height);
        clampPanels();layoutWorkspace();initializedView=null;initViewInContent(activeView());
    }

}
