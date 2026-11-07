package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.data.GalaxyCatalog;
import com.maqan.galacticnavigator.network.NavigatorNetwork;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.Map.Entry;

/**
 * Hierarchical data-first navigator:
 * Universe -> Galaxy -> Sector -> Star system -> celestial bodies.
 * Physical coordinates are only drawn when explicitly present in the MAQAN catalogue.
 */
public class NavigatorScreen extends Screen {
    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.maqan_galactic_navigator.open", KeyConflictContext.IN_GAME, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.maqan");
    public static final KeyMapping OPEN_SURFACE_MAP_KEY = new KeyMapping(
            "key.maqan_galactic_navigator.surface_map", KeyConflictContext.IN_GAME, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.maqan");
    public static final KeyMapping CREATE_WAYPOINT_KEY = new KeyMapping(
            "key.maqan_galactic_navigator.create_waypoint", KeyConflictContext.IN_GAME, KeyModifier.NONE,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.maqan");

    private static final int BG = 0xF0060918;
    private static final int PANEL = 0xD90A1220;
    private static final int PANEL_EDGE = 0x665D7895;
    private static final int TEXT = MaqanUiTheme.TEXT;
    private static final int MUTED = MaqanUiTheme.MUTED;
    private static final int CYAN = MaqanUiTheme.CYAN;
    private static final int GOLD = MaqanUiTheme.GOLD;

    private String universe = "";
    private String selectedId;
    private String selectedNodeId;
    private String galaxyPath;
    private String sectorPath;
    private String systemPath;
    private int level = 0; // 0 galaxies, 1 sectors, 2 systems, 3 bodies
    private boolean showList = NavigatorPreferences.showListPanel();
    private boolean showInfo = NavigatorPreferences.showInfoPanel();
    private int mapMode = 1; // 0 = 2D, 1 = Hybrid, 2 = perspective 3D
    // 0 = one system, 1 = systems in the active galaxy, 2 = systems in the active universe.
    private int atlasMode = 3;
    private Button atlasModeButton;
    // Additive workspace mode: show every indexed system on the existing canvas,
    // then restore the user's previous hierarchy and camera state on exit.
    private Button unifiedSceneButton;
    private boolean unifiedSceneMode;
    private int savedSceneLevel, savedSceneAtlasMode, savedSceneListScroll, savedSceneInfoScroll;
    private String savedSceneUniverse, savedSceneGalaxyPath, savedSceneSectorPath, savedSceneSystemPath;
    private String savedSceneSelectedId, savedSceneSelectedNodeId, savedSceneBodyViewId;
    private double savedSceneZoom, savedScenePanX, savedScenePanY, savedSceneCameraYaw, savedSceneCameraPitch;
    private String savedSceneCameraTargetId;
    private Button atlasDetailButton, atlasScaleButton, atlasTextureButton, atlasGlowButton;
    private double cameraYaw = 0.55;
    private double cameraPitch = 0.38;
    private String cameraTargetId;
    private String bodyViewId;
    private int bodyFilter;
    private boolean rotatingBody;
    private boolean rotatingInfoPreview;
    private boolean mapPanDragging;
    private double infoYawOffset;
    private double infoPitchOffset;
    private Rect infoPreviewRect;
    private Rect infoResetRect;
    private Rect infoOrbitEditRect;
    private double bodyYawOffset;
    private double bodyPitchOffset;
    private int bodyRotationHitRadius;
    private int listScroll;
    private int infoScroll;
    private long lastClickTime;
    private String lastClickedId;
    private double zoom = 1.0;
    // Visual-only atlas transition; never mutates catalog, body transforms or selection.
    private long transitionStartedNanos;
    private boolean transitionBackward;
    private double panX, panY;

    private EditBox search;
    private EditBox dimensionInput, xInput, yInput, zInput;
    private Button universeButton, backButton, openButton, listButton, infoButton, mapModeButton, filterButton,
            focusButton, centerButton, toolsButton, closeButton;
    private Button speedButton, rotationMinusButton, rotationPlusButton, orbitMinusButton, orbitPlusButton, travelButton;
    private final List<Button> atlasLayerButtons = new ArrayList<>();
    private final List<Button> atlasVisualButtons = new ArrayList<>();
    private boolean toolsOpen;
    private int toolsMenuAnchorY = 68;
    private boolean systemPickerOpen;
    public String selectedUniverseId(){ return universe==null?"":universe; }
    private int systemPickerScroll;
    private String dragPanel;
    private String panelDropTarget;
    private double panelPressX, panelPressY;
    private boolean panelDragStarted;
    private String resizingPanel;
    private double panelResizeStartX;
    private int panelResizeStartWidth;
    private boolean rightMouseDragging;
    private boolean contextOpen;
    private long contextOpenedAtNanos;
    private int contextX, contextY;
    private String contextNodeId;
    private String pendingContextNodeId;
    private double rightPressX, rightPressY;
    private final List<Button> toolButtons = new ArrayList<>();

    private int mapL, mapT, mapR, mapB, listL, listR, infoL, infoR;
    private final List<Node> renderedNodes = new ArrayList<>();
    private final List<BreadcrumbHit> breadcrumbHits = new ArrayList<>();
    private final List<Rect> infoTabRects = new ArrayList<>();
    private final List<RouteLegendHit> routeLegendHits = new ArrayList<>();
    private int routeFilter; // 0 all, 1 hyperspace, 2 warp, 3 transit links, 4 sublight
    private int infoTab; // 0 overview, 1 orbit, 2 environment, 3 source, 4 politics
    private final Map<String, Rect> listRects = new HashMap<>();
    private final Map<String, Point> mapPoints = new HashMap<>();
    private Node hoveredNode;

    private static final class Node {
        final String id;
        final String label;
        final String kind;
        final List<Entry<String, JsonObject>> entries;
        final int depth;
        Node(String id, String label, String kind, List<Entry<String, JsonObject>> entries) {
            this(id, label, kind, entries, 0);
        }
        Node(String id, String label, String kind, List<Entry<String, JsonObject>> entries, int depth) {
            this.id = id; this.label = label; this.kind = kind; this.entries = entries; this.depth = depth;
        }
        JsonObject first() { return entries.isEmpty() ? new JsonObject() : entries.get(0).getValue(); }
    }
    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px <= x + w && py >= y && py <= y + h; }
    }
    private record Point(int x, int y, int radius) {}
    private record BreadcrumbHit(String action, Rect bounds) {}
    private record InfoField(String label, String value) {}
    private enum RouteKind {
        HYPERSPACE("HYPER", 0xFF65C8F0),
        WARP("WARP", 0xFFB39AF5),
        WORMHOLE("WORM", 0xFFE18BCB),
        RELAY("RELAY", 0xFFE5C27B),
        GATE("GATE", 0xFF7ED6AA),
        SUBLIGHT("SUB", 0xFF9AAFC2),
        UNKNOWN("OTHER", 0xFF8297AA);
        final String label;
        final int color;
        RouteKind(String label, int color) { this.label = label; this.color = color; }
    }
    private record RouteConnection(String target, RouteKind kind) {}
    private record RouteLegendHit(int filter, Rect bounds) {}

    private boolean embeddedInMaqan;

    /** Marks this existing Atlas view as hosted by the MAQAN global workspace. */
    public void setEmbeddedInMaqan(boolean embedded) {
        embeddedInMaqan = embedded;
        if (embedded && closeButton != null) closeButton.visible = false;
    }

    @Override
    public void onClose() {
        if (embeddedInMaqan) return;
        super.onClose();
    }

    /** Workspace rail action: reveal/hide Atlas' existing catalogue panel. */
    public void toggleListPanel() {
        showList = !showList;
        NavigatorPreferences.setShowListPanel(showList);
        if (listButton != null) {
            listButton.setMessage(Component.literal("☷"));
            listButton.setTooltip(Tooltip.create(Component.literal(showList ? "Hide catalogue" : "Show catalogue")));
        }
    }

    /** Workspace rail action: reveal/hide Atlas' existing object dossier. */
    public void toggleInfoPanel() {
        showInfo = !showInfo;
        NavigatorPreferences.setShowInfoPanel(showInfo);
        if (infoButton != null) {
            infoButton.setMessage(Component.literal("◈"));
            infoButton.setTooltip(Tooltip.create(Component.literal(showInfo ? "Hide inspector" : "Show inspector")));
        }
    }

    public NavigatorScreen() { super(Component.literal("MAQAN Galactic Navigator")); }

    @Override
    protected void init() {
        // Screen.init runs again after GUI-scale/window changes. Preserve editable
        // values, but rebuild every widget and all geometry from the new GUI size.
        String previousSearch = search == null ? "" : search.getValue();
        String previousDimension = dimensionInput == null ? "" : dimensionInput.getValue();
        String previousX = xInput == null ? "0" : xInput.getValue();
        String previousY = yInput == null ? "100" : yInput.getValue();
        String previousZ = zInput == null ? "0" : zInput.getValue();
        toolButtons.clear();
        listRects.clear();
        mapPoints.clear();
        infoPreviewRect = infoResetRect = infoOrbitEditRect = null;
        infoTabRects.clear();
        mapL = mapT = mapR = mapB = listL = listR = infoL = infoR = 0;
        draggingPanelReset();
        resizingPanel = null;
        rotatingBody = rotatingInfoPreview = mapPanDragging = rightMouseDragging = false;
        contextOpen = false;
        systemPickerOpen = false;
        toolsOpen = false;
        GalaxyCatalog.ensureLoaded();
        List<String> availableUniverses = GalaxyCatalog.universes();
        if (availableUniverses.isEmpty()) universe = "";
        else if (!availableUniverses.contains(universe)) universe = availableUniverses.get(0);

        // Compact MAQAN command bar. Secondary controls live in a tools popover,
        // keeping the star map clear without removing any functionality.
        int left = embeddedInMaqan ? 54 : 14;
        int barY = embeddedInMaqan ? 58 : 42;
        int searchWidth = Math.max(48, Math.min(210, width - (width < 440 ? 245 : 310)));
        search = new EditBox(font, left, barY, searchWidth, 20, Component.literal("Search MAQAN catalogue"));
        search.setHint(Component.literal("Search systems and planets"));
        search.setValue(previousSearch);
        addRenderableWidget(search);

        int x = left + searchWidth + 6;
        int systemWidth = 26;
        universeButton = addRenderableWidget(Button.builder(Component.literal("◎"), b -> {
            systemPickerOpen = !systemPickerOpen;
            if (systemPickerOpen) { contextOpen = false; toolsOpen = false; updateToolVisibility(); }
        }).bounds(x, barY, systemWidth, 20).build());
        x += systemWidth + 4;
        backButton = addRenderableWidget(Button.builder(Component.literal("‹"), b -> goBack())
                .bounds(x, barY, 26, 20).build());
        x += 30;
        openButton = addRenderableWidget(Button.builder(Component.literal("↗"), b -> openSelected())
                .bounds(x, barY, 26, 20).build());
        toolsButton = addRenderableWidget(Button.builder(Component.literal("⚙"), b -> {
            toolsOpen = !toolsOpen;
            if (toolsOpen) { contextOpen = false; systemPickerOpen = false; }
            updateToolVisibility();
            b.setMessage(Component.literal(toolsOpen ? "×" : "⚙"));
        }).bounds(width - 76, barY, 30, 20).build());
        closeButton = addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
                .bounds(width - 40, barY, 26, 20).build());
        universeButton.setTooltip(Tooltip.create(Component.literal("Select star system")));
        backButton.setTooltip(Tooltip.create(Component.literal("Back to previous spatial layer")));
        openButton.setTooltip(Tooltip.create(Component.literal("Open selected object")));
        toolsButton.setTooltip(Tooltip.create(Component.literal("Atlas tools and visual settings")));
        unifiedSceneButton = addRenderableWidget(Button.builder(Component.literal("✦"),
                b -> toggleUnifiedSceneMode())
                .bounds(width - 108, barY, 26, 20).build());
        unifiedSceneButton.setTooltip(Tooltip.create(Component.literal("Open unified cosmic scene")));
        if (embeddedInMaqan) closeButton.visible = false;

        int menuX = width - 134;
        int menuY = height < 440 ? 42 : 68;
        toolsMenuAnchorY = menuY;
        focusButton = addToolButton("FOCUS", () -> focusSelected(), menuX, menuY);
        centerButton = addToolButton("CENTER VIEW", () -> {
            panX = 0; panY = 0; zoom = 1.0; cameraYaw = 0.55; cameraPitch = 0.38; cameraTargetId = null;
        }, menuX + 36, menuY);
        filterButton = addToolButton("FILTER: ALL", () -> {
            bodyFilter = (bodyFilter + 1) % 17;
            filterButton.setMessage(Component.literal("▽")); filterButton.setTooltip(Tooltip.create(Component.literal("Filter: " + filterName())));
            listScroll = 0;
        }, menuX + 72, menuY);
        mapModeButton = addToolButton("MAP: HYBRID", () -> {
            mapMode = (mapMode + 1) % 3;
            mapModeButton.setMessage(Component.literal("▣")); mapModeButton.setTooltip(Tooltip.create(Component.literal(mapModeName())));
        }, menuX, menuY + 26);
        listButton = addToolButton("LIST −", () -> {
            showList = !showList; NavigatorPreferences.setShowListPanel(showList); listButton.setMessage(Component.literal("☷")); listButton.setTooltip(Tooltip.create(Component.literal(showList ? "Hide catalogue" : "Show catalogue")));
        }, menuX + 36, menuY + 26);
        infoButton = addToolButton("INFO −", () -> {
            showInfo = !showInfo; NavigatorPreferences.setShowInfoPanel(showInfo); infoButton.setMessage(Component.literal("◈")); infoButton.setTooltip(Tooltip.create(Component.literal(showInfo ? "Hide inspector" : "Show inspector")));
        }, menuX + 72, menuY + 26);
        addToolButton("RELOAD DATA", () -> {
            if (minecraft != null) minecraft.reloadResourcePacks();
        }, menuX, menuY + 48);
        speedButton = addToolButton("SPEED: AUTO", () -> {
            String next = NavigatorPreferences.automatic() ? "manual"
                    : NavigatorPreferences.paused() ? "auto" : "paused";
            NavigatorPreferences.setSpeedMode(next);
            refreshSpeedButtons();
        }, menuX, menuY + 70);
        rotationMinusButton = addToolButton("ROT −", () -> {
            NavigatorPreferences.setSpeedMode("manual");
            NavigatorPreferences.setRotationSpeed(NavigatorPreferences.rotationSpeed() - 0.05);
            refreshSpeedButtons();
        }, menuX, menuY + 92);
        rotationPlusButton = addToolButton("ROT +", () -> {
            NavigatorPreferences.setSpeedMode("manual");
            NavigatorPreferences.setRotationSpeed(NavigatorPreferences.rotationSpeed() + 0.05);
            refreshSpeedButtons();
        }, menuX + 36, menuY + 92);
        orbitMinusButton = addToolButton("ORB −", () -> {
            NavigatorPreferences.setSpeedMode("manual");
            NavigatorPreferences.setOrbitSpeed(NavigatorPreferences.orbitSpeed() - 0.05);
            refreshSpeedButtons();
        }, menuX + 36, menuY + 114);
        orbitPlusButton = addToolButton("ORB +", () -> {
            NavigatorPreferences.setSpeedMode("manual");
            NavigatorPreferences.setOrbitSpeed(NavigatorPreferences.orbitSpeed() + 0.05);
            refreshSpeedButtons();
        }, menuX + 72, menuY + 114);
        atlasModeButton = addToolButton("ATLAS: SYSTEM", () -> {
            atlasMode = switch (atlasMode) {
                case 3 -> 1; // Universe → active galaxy
                case 1 -> 4; // Galaxy → sectors
                case 4 -> 2; // Sector layer → all universe systems
                case 2 -> 5; // Universe systems → full datapack catalogue
                default -> 3; // Return to the universe galaxy layer
            };
            sectorPath = null;
            if (atlasMode != 1 && atlasMode != 4) galaxyPath = null;
            systemPath = null;
            selectedId = null;
            selectedNodeId = null;
            listScroll = 0;
            // Preserve camera position and zoom when changing Atlas emphasis.
            refreshButtons();
        }, menuX + 36, menuY + 136, 118);
        atlasLayerButtons.clear();
        atlasLayerButtons.add(addToolButton("ORBITS ON", () -> {
            NavigatorPreferences.toggleAtlasOrbits(); refreshAtlasLayerButtons();
        }, menuX, menuY + 158, 88));
        atlasLayerButtons.add(addToolButton("LABELS ON", () -> {
            NavigatorPreferences.toggleAtlasLabels(); refreshAtlasLayerButtons();
        }, menuX + 36, menuY + 158, 88));
        atlasLayerButtons.add(addToolButton("SECTORS ON", () -> {
            NavigatorPreferences.toggleAtlasSectors(); refreshAtlasLayerButtons();
        }, menuX + 72, menuY + 158, 88));
        atlasLayerButtons.add(addToolButton("GUIDES ON", () -> {
            NavigatorPreferences.toggleAtlasGuides(); refreshAtlasLayerButtons();
        }, menuX, menuY + 180, 88));
        atlasLayerButtons.add(addToolButton("LINKS OFF", () -> {
            NavigatorPreferences.toggleAtlasSectorLinks(); refreshAtlasLayerButtons();
        }, menuX + 36, menuY + 180, 88));
        atlasLayerButtons.add(addToolButton("PACKS OFF", () -> {
            NavigatorPreferences.toggleAtlasPacks(); refreshAtlasLayerButtons();
        }, menuX + 72, menuY + 180, 88));
        atlasLayerButtons.add(addToolButton("ROUTES ON", () -> {
            NavigatorPreferences.toggleAtlasRoutes(); refreshAtlasLayerButtons();
        }, menuX, menuY + 202, 88));
        atlasVisualButtons.clear();
        atlasDetailButton = addToolButton("DETAIL: BALANCED", () -> {
            NavigatorPreferences.cycleAtlasDetail(); refreshAtlasVisualButtons();
        }, menuX, menuY + 224, 88);
        atlasScaleButton = addToolButton("BODY SIZE: 1.00x", () -> {
            NavigatorPreferences.cycleAtlasScale(); refreshAtlasVisualButtons();
        }, menuX + 36, menuY + 224, 88);
        atlasTextureButton = addToolButton("TEXTURES ON", () -> {
            NavigatorPreferences.toggleAtlasTextures(); refreshAtlasVisualButtons();
        }, menuX + 72, menuY + 224, 88);
        atlasGlowButton = addToolButton("STAR GLOW ON", () -> {
            NavigatorPreferences.toggleAtlasStarGlow(); refreshAtlasVisualButtons();
        }, menuX, menuY + 246, 88);
        Button atlasSpacingButton = addToolButton("ORBIT GAP", () -> {
            NavigatorPreferences.cycleAtlasOrbitSpacing(); refreshAtlasVisualButtons();
        }, menuX + 36, menuY + 246, 88);
        addToolButton("ROUTE PLAN", () -> {
            if (minecraft != null) MaqanScreenHost.open(minecraft, new NavigatorTravelScreen(this));
        }, menuX + 72, menuY + 246, 88);
        addToolButton("SPACE MOTION", () -> {
            if (minecraft != null) MaqanScreenHost.open(minecraft, new CosmicOrbitSettingsScreen(this));
        }, menuX, menuY + 268, 118);
        addToolButton("PLAYER MARKER", () -> {
            if (minecraft != null) MaqanScreenHost.open(minecraft, new NavigatorPlayerMarkerScreen(this));
        }, menuX + 36, menuY + 268, 156);
        addToolButton("WAYPOINTS", () -> {
            if (minecraft != null) MaqanScreenHost.open(minecraft, new NavigatorWaypointManagerScreen(this));
        }, menuX, menuY + 290, 136);
        addToolButton("NEW MARKER", () -> {
            if (minecraft != null) MaqanScreenHost.open(minecraft, new NavigatorWaypointEditorScreen(this));
        }, menuX + 36, menuY + 290, 136);
        addToolButton("SURFACE MAP", () -> {
            if (minecraft != null && minecraft.level != null)
                MaqanScreenHost.open(minecraft, new NavigatorSurfaceMapScreen(minecraft.level.dimension().location().toString(), this));
        }, menuX, menuY + 312, 136);
        atlasVisualButtons.add(atlasDetailButton);
        atlasVisualButtons.add(atlasScaleButton);
        atlasVisualButtons.add(atlasTextureButton);
        atlasVisualButtons.add(atlasGlowButton);
        atlasVisualButtons.add(atlasSpacingButton);
        refreshAtlasLayerButtons();
        refreshAtlasVisualButtons();
        updateToolVisibility();
        refreshSpeedButtons();

        int footerGap = 6;
        int travelWidth = 28;
        int footerX = 14;
        if (width < 560) {
            int dimensionY = height - 48;
            int footerY = height - 24;
            dimensionInput = new EditBox(font, 14, dimensionY, Math.max(80, width - 28), 18,
                    Component.literal("Dimension ID"));
            int coordWidth = Math.max(22, Math.min(44, (width - 28 - travelWidth - footerGap * 3) / 3));
            xInput = new EditBox(font, footerX, footerY, coordWidth, 18, Component.literal("X"));
            xInput.setValue(previousX);
            footerX += coordWidth + footerGap;
            yInput = new EditBox(font, footerX, footerY, coordWidth, 18, Component.literal("Y"));
            yInput.setValue(previousY);
            footerX += coordWidth + footerGap;
            zInput = new EditBox(font, footerX, footerY, coordWidth, 18, Component.literal("Z"));
            zInput.setValue(previousZ);
            footerX += coordWidth + footerGap;
            int travelX = Math.min(footerX, width - travelWidth - 14);
            travelButton = addRenderableWidget(Button.builder(Component.literal("⇢"), b -> requestTeleport())
                    .bounds(travelX, footerY, travelWidth, 18).build());
        } else {
            int footerY = height - 24;
            int coordWidth = Math.max(30, Math.min(54, (width - 260) / 7));
            int dimensionWidth = Math.max(72, Math.min(250,
                    width - 28 - travelWidth - coordWidth * 3 - footerGap * 4));
            dimensionInput = new EditBox(font, footerX, footerY, dimensionWidth, 18,
                    Component.literal("Dimension ID"));
            footerX += dimensionWidth + footerGap;
            xInput = new EditBox(font, footerX, footerY, coordWidth, 18, Component.literal("X"));
            xInput.setValue(previousX);
            footerX += coordWidth + footerGap;
            yInput = new EditBox(font, footerX, footerY, coordWidth, 18, Component.literal("Y"));
            yInput.setValue(previousY);
            footerX += coordWidth + footerGap;
            zInput = new EditBox(font, footerX, footerY, coordWidth, 18, Component.literal("Z"));
            zInput.setValue(previousZ);
            footerX += coordWidth + footerGap;
            int travelX = Math.min(footerX, width - travelWidth - 14);
            travelButton = addRenderableWidget(Button.builder(Component.literal("⇢"), b -> requestTeleport())
                    .bounds(travelX, footerY, travelWidth, 18).build());
        }
        if (travelButton != null)
            travelButton.setTooltip(Tooltip.create(Component.literal("Travel to dimension and coordinates")));
        dimensionInput.setMaxLength(256);
        dimensionInput.setHint(Component.literal("dimension:destination"));
        dimensionInput.setValue(previousDimension);
        addRenderableWidget(dimensionInput);
        addRenderableWidget(xInput); addRenderableWidget(yInput); addRenderableWidget(zInput);
        layoutPanels();
        refreshButtons();
    }

    private Button addToolButton(String label, Runnable action, int x, int y) {
        return addToolButton(label, action, x, y, 74);
    }

    private Button addToolButton(String label, Runnable action, int x, int y, int buttonWidth) {
        Button button = addRenderableWidget(Button.builder(Component.literal(toolIcon(label)), b -> action.run())
                .bounds(x, y, 26, 20).build());
        button.setTooltip(Tooltip.create(Component.literal(label)));
        toolButtons.add(button);
        return button;
    }

    private String toolIcon(String label) {
        String key = label.toUpperCase(Locale.ROOT);
        if (key.startsWith("FOCUS")) return "⌖";
        if (key.startsWith("CENTER")) return "◎";
        if (key.startsWith("FILTER")) return "▽";
        if (key.startsWith("MAP:")) return "▣";
        if (key.startsWith("LIST")) return "☷";
        if (key.startsWith("INFO")) return "◈";
        if (key.startsWith("RELOAD")) return "↻";
        if (key.startsWith("SPEED")) return "⏵";
        if (key.startsWith("ROT −")) return "↶";
        if (key.startsWith("ROT +")) return "↷";
        if (key.startsWith("ORB −")) return "⤺";
        if (key.startsWith("ORB +")) return "⤻";
        if (key.startsWith("ATLAS:")) return "✧";
        if (key.startsWith("ORBITS")) return "◌";
        if (key.startsWith("LABELS")) return "Aa";
        if (key.startsWith("SECTORS")) return "▧";
        if (key.startsWith("GUIDES")) return "⊞";
        if (key.startsWith("LINKS")) return "⌁";
        if (key.startsWith("PACKS")) return "▤";
        if (key.startsWith("ROUTES") || key.startsWith("ROUTE PLAN")) return "⇢";
        if (key.startsWith("DETAIL")) return "◉";
        if (key.startsWith("BODY SIZE")) return "◫";
        if (key.startsWith("TEXTURES")) return "▦";
        if (key.startsWith("STAR GLOW")) return "✦";
        if (key.startsWith("ORBIT GAP")) return "↔";
        if (key.startsWith("SPACE MOTION")) return "⟳";
        if (key.startsWith("PLAYER MARKER")) return "⌖";
        if (key.startsWith("WAYPOINTS")) return "⚑";
        if (key.startsWith("NEW MARKER")) return "＋";
        if (key.startsWith("SURFACE MAP")) return "▥";
        return "◇";
    }

    private void refreshAtlasLayerButtons() {
        if (atlasLayerButtons.size() < 5) return;
        String[] icons = {"◌", "Aa", "▧", "⊞", "⌁", "▤", "⇢"};
        String[] names = {"Orbits", "Labels", "Sectors", "Guides", "Sector links", "Datapack labels", "Routes"};
        boolean[] enabled = {NavigatorPreferences.atlasOrbits(), NavigatorPreferences.atlasLabels(),
                NavigatorPreferences.atlasSectors(), NavigatorPreferences.atlasGuides(),
                NavigatorPreferences.atlasSectorLinks(), NavigatorPreferences.atlasPacks(),
                NavigatorPreferences.atlasRoutes()};
        for (int i = 0; i < atlasLayerButtons.size() && i < icons.length; i++) {
            atlasLayerButtons.get(i).setMessage(Component.literal(icons[i]));
            atlasLayerButtons.get(i).setTooltip(Tooltip.create(Component.literal(
                    names[i] + (enabled[i] ? " · ON" : " · OFF"))));
        }
    }

    private void refreshAtlasVisualButtons() {
        if (atlasDetailButton == null) return;
        atlasDetailButton.setMessage(Component.literal("◉"));
        atlasDetailButton.setTooltip(Tooltip.create(Component.literal(
                "Detail · " + NavigatorPreferences.atlasDetailName())));
        atlasScaleButton.setMessage(Component.literal("◫"));
        atlasScaleButton.setTooltip(Tooltip.create(Component.literal(
                "Body size · " + NavigatorPreferences.atlasScaleName())));
        atlasTextureButton.setMessage(Component.literal("▦"));
        atlasTextureButton.setTooltip(Tooltip.create(Component.literal(
                "Textures · " + (NavigatorPreferences.atlasTextures() ? "ON" : "OFF"))));
        atlasGlowButton.setMessage(Component.literal("✦"));
        atlasGlowButton.setTooltip(Tooltip.create(Component.literal(
                "Star glow · " + (NavigatorPreferences.atlasStarGlow() ? "ON" : "OFF"))));
        if (atlasVisualButtons.size() > 4) {
            Button spacing = atlasVisualButtons.get(4);
            spacing.setMessage(Component.literal("↔"));
            spacing.setTooltip(Tooltip.create(Component.literal(
                    "Orbit spacing · " + NavigatorPreferences.atlasOrbitSpacingName())));
        }
    }

    private void updateToolVisibility() {
        for (Button button : toolButtons) {
            boolean isAtlasLayer = atlasLayerButtons.contains(button);
            boolean isAtlasVisual = atlasVisualButtons.contains(button);
            button.visible = toolsOpen && (!isAtlasLayer || height >= 300)
                    && (!isAtlasVisual || height >= 360);
        }
    }

    private void refreshSpeedButtons() {
        if (speedButton == null) return;
        speedButton.setMessage(Component.literal("⏵"));
        speedButton.setTooltip(Tooltip.create(Component.literal("Motion: " +
                (NavigatorPreferences.automatic() ? "AUTO" :
                        NavigatorPreferences.paused() ? "PAUSED" : "MANUAL"))));
        rotationMinusButton.setMessage(Component.literal("↶"));
        rotationMinusButton.setTooltip(Tooltip.create(Component.literal(String.format(Locale.ROOT,
                "Decrease axial rotation · %.2f", NavigatorPreferences.rotationSpeed()))));
        rotationPlusButton.setMessage(Component.literal("↷"));
        rotationPlusButton.setTooltip(Tooltip.create(Component.literal(String.format(Locale.ROOT,
                "Increase axial rotation · %.2f", NavigatorPreferences.rotationSpeed()))));
        orbitMinusButton.setMessage(Component.literal("⤺"));
        orbitMinusButton.setTooltip(Tooltip.create(Component.literal(String.format(Locale.ROOT,
                "Decrease orbital speed · %.2f", NavigatorPreferences.orbitSpeed()))));
        orbitPlusButton.setMessage(Component.literal("⤻"));
        orbitPlusButton.setTooltip(Tooltip.create(Component.literal(String.format(Locale.ROOT,
                "Increase orbital speed · %.2f", NavigatorPreferences.orbitSpeed()))));
    }

    private void openNavigatorSettings() {
        String target = selectedId != null ? selectedId : selectedNodeId;
        if (target == null || minecraft == null) return;
        Node node = bodyNode(target);
        if (node == null) node = findNode(target);
        if (node == null || node.entries.isEmpty()) return;
        JsonObject body = node.first();
        int separator = target.indexOf("::");
        String systemId = separator > 0 ? target.substring(0, separator)
                : GalaxyCatalog.text(body, "system_id", universe);
        String localBodyId = separator > 0 ? target.substring(separator + 2) : target;
        String bodyId = GalaxyCatalog.text(body, "catalog_id", localBodyId);
        String bodyName = GalaxyCatalog.text(body, "name", node.label);
        MaqanScreenHost.open(minecraft, new CosmicBodyOrbitSettingsScreen(this, systemId, bodyId, bodyName));
    }

    private void cycleUniverse() {
        List<String> ids = GalaxyCatalog.universes();
        if (ids.isEmpty()) return;
        int at = ids.indexOf(universe);
        universe = ids.get((at + 1) % ids.size());
        resetPath();
        refreshButtons();
    }

    private void resetPath() {
        level = 0; galaxyPath = null; sectorPath = null; systemPath = null;
        selectedId = null; selectedNodeId = null; listScroll = 0; infoScroll = 0; bodyFilter = 0;
        lastClickedId = null; lastClickTime = 0;
        panX = 0; panY = 0; zoom = 1.0; cameraYaw = 0.55; cameraPitch = 0.38;
        cameraTargetId = null; bodyViewId = null;
        bodyYawOffset = 0.0; bodyPitchOffset = 0.0; bodyRotationHitRadius = 0;
        infoYawOffset = 0.0; infoPitchOffset = 0.0;
        rotatingBody = false; rotatingInfoPreview = false; mapPanDragging = false;
        rightMouseDragging = false; draggingPanelReset();
        contextOpen = false; contextNodeId = null; pendingContextNodeId = null;
        systemPickerOpen = false; systemPickerScroll = 0;
    }

    private void draggingPanelReset() {
        dragPanel = null;
        panelDropTarget = null;
        panelDragStarted = false;
    }

    private void goBack() {
        beginAtlasTransition(true);
        if (atlasMode > 0 && level == 3 && bodyViewId == null) {
            level = 0;
            infoScroll = 0;
            selectedId = null; selectedNodeId = null; listScroll = 0;
            panX = 0; panY = 0; zoom = 1.0; cameraTargetId = null;
            refreshButtons();
            return;
        }
        if (bodyViewId != null) {
            bodyViewId = null;
            infoScroll = 0;
            bodyYawOffset = 0.0; bodyPitchOffset = 0.0; bodyRotationHitRadius = 0;
            rotatingBody = false;
            cameraTargetId = null;
            selectedId = null;
            selectedNodeId = null;
            listScroll = 0;
            refreshButtons();
            return;
        }
        if (level == 0 && sectorPath != null) {
            sectorPath = null;
            atlasMode = 4;
            selectedNodeId = null;
            panX = 0; panY = 0; zoom = 1.0;
            refreshButtons();
            return;
        }
        if (level == 0 && galaxyPath != null) {
            galaxyPath = null;
            atlasMode = 3;
            selectedNodeId = null;
            panX = 0; panY = 0; zoom = 1.0;
            refreshButtons();
            return;
        }
        if (level > 0) {
            level--;
            infoScroll = 0;
            if (level < 3) systemPath = null;
            if (level < 2) sectorPath = null;
            if (level < 1) galaxyPath = null;
            selectedId = null; selectedNodeId = null; listScroll = 0;
        } else resetPath();
        refreshButtons();
    }

    private void openSelected() {
        Node node = findNode(selectedNodeId);
        if (node == null) return;
        beginAtlasTransition(false);
        if (level == 0) {
            if (node.kind.startsWith("atlas_body:")) {
                int separator = node.id.indexOf("::");
                if (separator < 1) return;
                universe = node.id.substring(0, separator);
                String bodyId = node.id.substring(separator + 2);
                level = 3;
                galaxyPath = null;
                sectorPath = null;
                systemPath = null;
                bodyViewId = bodyId;
                selectedId = bodyId;
                selectedNodeId = node.id;
                cameraTargetId = bodyId;
                panX = 0; panY = 0; zoom = 1.0;
                listScroll = 0;
                refreshButtons();
                return;
            }
            if ("galaxy".equals(node.kind) || "sector".equals(node.kind)
                    || GalaxyCatalog.universes().contains(node.id)) {
                // Keep one continuous spatial canvas. Double-clicking a group or
                // system moves the camera deeper into the same map instead of
                // replacing the universe view with a separate hierarchy screen.
                double factor = "galaxy".equals(node.kind) ? 3.2
                        : "sector".equals(node.kind) ? 3.0 : 4.0;
                zoomToAtlasNode(node.id, factor);
                refreshButtons();
                return;
            }
        } else if (level == 1) sectorPath = node.id;
        else if (level == 2) systemPath = node.id;
        else {
            String bodyId = selectedId != null ? selectedId : node.entries.isEmpty()
                    ? null : node.entries.get(0).getKey();
            if (bodyId != null) {
                if (!bodyId.equals(bodyViewId)) {
                    bodyYawOffset = 0.0;
                    bodyPitchOffset = 0.0;
                }
                rotatingBody = false;
                bodyViewId = bodyId;
                cameraTargetId = bodyId;
                selectedId = bodyId;
                selectedNodeId = bodyId;
                listScroll = 0;
                panX = 0; panY = 0; zoom = 1.0;
                refreshButtons();
            }
            return;
        }
        level = Math.min(3, level + 1);
        selectedNodeId = null; selectedId = null; listScroll = 0;
        refreshButtons();
    }

    private String mapModeName() {
        return switch (mapMode) {
            case 0 -> "MAP: 2D";
            case 2 -> "MAP: 3D";
            default -> "MAP: HYBRID";
        };
    }

    private void toggleUnifiedSceneMode() {
        if (!unifiedSceneMode) {
            savedSceneLevel = level;
            savedSceneAtlasMode = atlasMode;
            savedSceneUniverse = universe;
            savedSceneGalaxyPath = galaxyPath;
            savedSceneSectorPath = sectorPath;
            savedSceneSystemPath = systemPath;
            savedSceneSelectedId = selectedId;
            savedSceneSelectedNodeId = selectedNodeId;
            savedSceneBodyViewId = bodyViewId;
            savedSceneListScroll = listScroll;
            savedSceneInfoScroll = infoScroll;
            savedSceneZoom = zoom;
            savedScenePanX = panX;
            savedScenePanY = panY;
            savedSceneCameraYaw = cameraYaw;
            savedSceneCameraPitch = cameraPitch;
            savedSceneCameraTargetId = cameraTargetId;

            unifiedSceneMode = true;
            level = 0;
            atlasMode = 5; // All indexed systems, independent of the active hierarchy path.
            galaxyPath = sectorPath = systemPath = null;
            selectedId = selectedNodeId = bodyViewId = cameraTargetId = null;
            listScroll = infoScroll = 0;
            panX = panY = 0.0;
            zoom = 1.0;
            cameraYaw = 0.55;
            cameraPitch = 0.38;
        } else {
            unifiedSceneMode = false;
            level = savedSceneLevel;
            atlasMode = savedSceneAtlasMode;
            universe = savedSceneUniverse;
            galaxyPath = savedSceneGalaxyPath;
            sectorPath = savedSceneSectorPath;
            systemPath = savedSceneSystemPath;
            selectedId = savedSceneSelectedId;
            selectedNodeId = savedSceneSelectedNodeId;
            bodyViewId = savedSceneBodyViewId;
            listScroll = savedSceneListScroll;
            infoScroll = savedSceneInfoScroll;
            zoom = savedSceneZoom;
            panX = savedScenePanX;
            panY = savedScenePanY;
            cameraYaw = savedSceneCameraYaw;
            cameraPitch = savedSceneCameraPitch;
            cameraTargetId = savedSceneCameraTargetId;
        }
        refreshButtons();
    }

    private String atlasModeName() {
        return switch (atlasMode) {
            case 1 -> sectorPath == null ? "GALAXY" : "SECTOR SYSTEMS";
            case 2 -> "UNIVERSE SYSTEMS";
            case 3 -> "UNIVERSE";
            case 4 -> "SECTORS";
            case 5 -> "DATAPACK";
            default -> "SYSTEM";
        };
    }

    private String filterName() {
        return switch (bodyFilter) {
            case 1 -> "PLANETS";
            case 2 -> "MOONS";
            case 3 -> "STARS";
            case 4 -> "OTHER";
            case 5 -> "LIFE";
            case 6 -> "ATMOSPHERE";
            case 7 -> "STATIONS";
            case 8 -> "ASTEROIDS";
            case 9 -> "BLACK HOLES";
            case 10 -> "JUMP POINTS";
            case 11 -> "LAGRANGE";
            case 12 -> "ANOMALIES";
            case 13 -> "CONTACTS";
            case 14 -> "NEBULAE";
            case 15 -> "COMETS";
            case 16 -> "WRECKS";
            default -> "ALL";
        };
    }

    private boolean hasTruthyMetadata(JsonObject data, String... keys) {
        for (String key : keys) {
            if (data == null || !data.has(key) || data.get(key).isJsonNull()) continue;
            JsonElement element = data.get(key);
            try {
                if (element.isJsonPrimitive()) {
                    JsonPrimitive primitive = element.getAsJsonPrimitive();
                    if (primitive.isBoolean() && primitive.getAsBoolean()) return true;
                    if (primitive.isNumber() && primitive.getAsDouble() > 0.0) return true;
                    if (primitive.isString()) {
                        String value = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
                        if (!value.isEmpty() && !Set.of("false", "no", "none", "null", "0", "off").contains(value))
                            return true;
                    }
                } else if (element.isJsonObject() && element.getAsJsonObject().size() > 0) return true;
                else if (element.isJsonArray() && element.getAsJsonArray().size() > 0) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    private void focusSelected() {
        String id = selectedId != null ? selectedId : selectedNodeId;
        Point point = id == null ? null : mapPoints.get(id);
        // A selected body may belong to a system currently culled outside the
        // viewport. Center its owning system first; the next frame restores the
        // body's exact screen target and the normal focus action can refine it.
        if (point == null && unifiedSceneMode && id != null) {
            int separator = id.indexOf("::");
            if (separator > 0) point = mapPoints.get(id.substring(0, separator));
        }
        if (point == null) return;
        if (level == 3 && mapMode == 2 && selectedId != null) {
            cameraTargetId = selectedId;
            panX = 0; panY = 0;
            return;
        }
        int centerX = (mapL + mapR) / 2;
        int centerY = (mapT + mapB) / 2;
        panX += centerX - point.x;
        panY += centerY - point.y;
    }

    /** Focus a selected object without replacing the global map with another screen. */
    private void zoomToAtlasNode(String id, double factor) {
        Point point = id == null ? null : mapPoints.get(id);
        if (point == null && unifiedSceneMode && id != null) {
            int separator = id.indexOf("::");
            if (separator > 0) point = mapPoints.get(id.substring(0, separator));
        }
        if (point == null) return;
        int centerX = (mapL + mapR) / 2;
        int centerY = (mapT + mapB) / 2;
        panX += centerX - point.x;
        panY += centerY - point.y;
        zoom = Math.max(.025, Math.min(64.0, zoom * factor));
        selectedNodeId = id;
        selectedId = null;
        infoScroll = 0;
    }

    private void refreshButtons() {
        if (universeButton != null) {
            List<String> systems = GalaxyCatalog.universes();
            universeButton.setMessage(Component.literal("◎"));
            universeButton.active = !systems.isEmpty();
        }
        if (backButton != null) backButton.active = level > 0 || galaxyPath != null || sectorPath != null;
        if (atlasModeButton != null) {
            atlasModeButton.setMessage(Component.literal("✧"));
            atlasModeButton.setTooltip(Tooltip.create(Component.literal("Atlas layer: " + atlasModeName())));
            atlasModeButton.active = !unifiedSceneMode;
        }
        if (unifiedSceneButton != null) {
            unifiedSceneButton.setMessage(Component.literal(unifiedSceneMode ? "✧" : "✦"));
            unifiedSceneButton.setTooltip(Tooltip.create(Component.literal(unifiedSceneMode
                    ? "Return to previous Atlas view" : "Open unified cosmic scene")));
        }
        if (openButton != null) openButton.active = selectedNodeId != null || selectedId != null;
        if (focusButton != null) focusButton.active = selectedNodeId != null || selectedId != null;
    }

    private void requestTeleport() {
        try {
            String dimension = dimensionInput.getValue().trim();
            double x = Double.parseDouble(xInput.getValue().trim());
            double y = Double.parseDouble(yInput.getValue().trim());
            double z = Double.parseDouble(zInput.getValue().trim());
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ||
                    !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Invalid destination");
            NavigatorNetwork.teleport(dimension, x, y, z);
        } catch (Exception ex) {
            if (minecraft != null && minecraft.player != null)
                minecraft.player.displayClientMessage(Component.literal("[MAQAN] Check dimension ID and X/Y/Z."), false);
        }
    }

    private static String value(JsonObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.get(key).isJsonNull()) continue;
            JsonElement element = o.get(key);
            try {
                if (element.isJsonPrimitive()) return element.getAsString();
                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("text") && !object.get("text").isJsonNull())
                        return object.get("text").getAsString();
                    if (object.has("translate") && !object.get("translate").isJsonNull())
                        return object.get("translate").getAsString();
                    if (object.has("r") && object.has("g") && object.has("b"))
                        return "RGB " + object.get("r").getAsInt() + ", "
                                + object.get("g").getAsInt() + ", " + object.get("b").getAsInt();
                    return object.size() + " metadata field(s)";
                }
                if (element.isJsonArray()) {
                    JsonArray array = element.getAsJsonArray();
                    if (array.size() == 3 && array.get(0).isJsonPrimitive()
                            && array.get(1).isJsonPrimitive() && array.get(2).isJsonPrimitive())
                        return String.format(Locale.ROOT, "%.2f / %.2f / %.2f",
                                array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
                    return "";
                }
            } catch (Exception ignored) { }
        }
        return "";
    }

    private List<Entry<String, JsonObject>> scopedObjects() {
        List<Entry<String, JsonObject>> result = new ArrayList<>();
        for (Entry<String, JsonObject> e : GalaxyCatalog.objects(universe)) {
            JsonObject o = e.getValue();
            if (galaxyPath != null && !groupKey(o, 0).equals(galaxyPath)) continue;
            if (sectorPath != null && !groupKey(o, 1).equals(sectorPath)) continue;
            if (systemPath != null && !groupKey(o, 2).equals(systemPath)) continue;
            String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
            String hay = (e.getKey() + " " + value(o, "name", "display_name") + " " +
                    value(o, "system", "system_name", "system_id") + " " +
                    value(o, "sector", "sector_id") + " " +
                    value(o, "galaxy", "galaxy_id")).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || hay.contains(q)) result.add(e);
        }
        return result;
    }

    /**
     * Cross-system overview sourced exclusively from active Cosmic Horizons catalogue entries.
     * A system joins a galaxy/universe only when its datapack metadata says so; missing metadata
     * is kept in an explicit unassigned group rather than guessed from its display name.
     */
    private boolean atlasSystemMatchesFilter(List<Entry<String, JsonObject>> bodies) {
        if (bodyFilter == 0) return true;
        for (Entry<String, JsonObject> entry : bodies) {
            JsonObject data = entry.getValue();
            String kind = value(data, "kind", "display_type", "type", "classification")
                    .toLowerCase(Locale.ROOT);
            if (isStarData(data)) kind = "star";
            else if (kind.contains("moon") || kind.contains("satellite")) kind = "moon";
            else if (kind.isBlank()) kind = "planet";
            boolean matches = switch (bodyFilter) {
                case 1 -> kind.contains("planet");
                case 2 -> kind.contains("moon") || kind.contains("satellite");
                case 3 -> kind.contains("star");
                case 4 -> !kind.contains("planet") && !kind.contains("moon")
                        && !kind.contains("satellite") && !kind.contains("star");
                case 5 -> hasTruthyMetadata(entry.getValue(), "life");
                case 6 -> hasTruthyMetadata(entry.getValue(), "atmosphere", "has_atmosphere", "atmosphere_y");
                case 7 -> kind.contains("station");
                case 8 -> kind.contains("asteroid");
                case 9 -> kind.contains("blackhole") || kind.contains("black hole");
                case 10 -> kind.contains("jump") || kind.contains("wormhole") || kind.contains("gate");
                case 11 -> kind.contains("lagrange");
                case 12 -> kind.contains("anomaly") || kind.contains("wreck") || kind.contains("beacon")
                        || kind.contains("signal") || kind.contains("poi");
                case 13 -> kind.contains("ship") || kind.contains("contact") || kind.contains("target");
                case 14 -> kind.contains("nebula");
                case 15 -> kind.contains("comet");
                case 16 -> kind.contains("wreck") || kind.contains("debris");
                default -> true;
            };
            if (matches) return true;
        }
        return false;
    }

    private List<Node> atlasSystemNodes() {
        String[] keys = atlasMode == 1
                ? new String[]{"galaxy_id", "galaxy"}
                : new String[]{"universe_id", "universe", "cosmos_id"};
        String activeGroup = atlasMode == 5 ? null
                : atlasMode == 1 && galaxyPath != null
                ? galaxyPath.toLowerCase(Locale.ROOT) : systemGroupKey(universe, keys);
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Node> result = new ArrayList<>();
        for (String systemId : GalaxyCatalog.universes()) {
            if (activeGroup != null && !Objects.equals(activeGroup, systemGroupKey(systemId, keys))) continue;
            List<Entry<String, JsonObject>> bodies = GalaxyCatalog.objects(systemId);
            if (bodies.isEmpty() || !atlasSystemMatchesFilter(bodies)) continue;
            if (sectorPath != null) {
                String sectorKey = systemGroupKey(systemId, new String[]{"sector_id", "sector"});
                if (sectorKey.equals("__unassigned")) sectorKey = "__sector_unassigned";
                if (!sectorKey.equalsIgnoreCase(sectorPath)) continue;
            }
            JsonObject system = GalaxyCatalog.universe(systemId);
            String name = GalaxyCatalog.text(system, "name", systemId);
            String searchable = (systemId + " " + name + " " +
                    systemGroupKey(systemId, new String[]{"galaxy_name", "galaxy"}) + " " +
                    systemGroupKey(systemId, new String[]{"sector_name", "sector"})).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !searchable.contains(query)) continue;
            result.add(new Node(systemId, name, "star system", bodies));
        }
        result.sort(Comparator.comparing(node -> node.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Build one selectable galaxy glyph per explicit galaxy group in the active universe. */
    private List<Node> atlasGalaxyNodes() {
        String activeUniverse = systemGroupKey(universe,
                new String[]{"universe_id", "universe", "cosmos_id"});
        Map<String, List<Entry<String, JsonObject>>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> labels = new HashMap<>();
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        for (String systemId : GalaxyCatalog.universes()) {
            if (!Objects.equals(activeUniverse, systemGroupKey(systemId,
                    new String[]{"universe_id", "universe", "cosmos_id"}))) continue;
            if (bodyFilter != 0 && !atlasSystemMatchesFilter(GalaxyCatalog.objects(systemId))) continue;
            JsonObject system = GalaxyCatalog.universe(systemId);
            String galaxyId = value(system, "galaxy_id", "galaxy");
            if (galaxyId.isBlank()) {
                for (Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
                    galaxyId = value(body.getValue(), "galaxy_id", "galaxy");
                    if (!galaxyId.isBlank()) break;
                }
            }
            if (galaxyId.isBlank()) galaxyId = "__unassigned";
            String galaxyName = value(system, "galaxy_name", "galaxy");
            if (galaxyName.isBlank()) {
                for (Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
                    galaxyName = value(body.getValue(), "galaxy_name", "galaxy");
                    if (!galaxyName.isBlank()) break;
                }
            }
            if (galaxyName.isBlank()) galaxyName = galaxyId.equals("__unassigned")
                    ? "Unassigned systems" : galaxyId;
            if (!query.isEmpty() && !galaxyId.toLowerCase(Locale.ROOT).contains(query)
                    && !galaxyName.toLowerCase(Locale.ROOT).contains(query)
                    && !systemId.toLowerCase(Locale.ROOT).contains(query)
                    && !GalaxyCatalog.text(system, "name", systemId).toLowerCase(Locale.ROOT).contains(query))
                continue;
            labels.putIfAbsent(galaxyId, galaxyName);
            // Keep system-root metadata as entries so galaxy-level coordinates and provenance
            // remain available without flattening or duplicating the underlying body catalogue.
            groups.computeIfAbsent(galaxyId, ignored -> new ArrayList<>())
                    .add(new AbstractMap.SimpleImmutableEntry<>(systemId, system));
        }
        List<Node> result = new ArrayList<>();
        for (Map.Entry<String, List<Entry<String, JsonObject>>> group : groups.entrySet()) {
            String id = group.getKey();
            result.add(new Node(id, labels.getOrDefault(id, id), "galaxy", group.getValue()));
        }
        result.sort(Comparator.comparing(node -> node.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Data-driven sector clusters inside the active galaxy (or active universe when unscoped). */
    private List<Node> atlasSectorNodes() {
        String activeGalaxy = galaxyPath != null ? galaxyPath.toLowerCase(Locale.ROOT)
                : systemGroupKey(universe, new String[]{"galaxy_id", "galaxy"});
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        Map<String, List<Entry<String, JsonObject>>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> labels = new HashMap<>();
        for (String systemId : GalaxyCatalog.universes()) {
            if (!systemGroupKey(systemId, new String[]{"galaxy_id", "galaxy"}).equals(activeGalaxy))
                continue;
            List<Entry<String, JsonObject>> bodies = GalaxyCatalog.objects(systemId);
            if (bodies.isEmpty() || !atlasSystemMatchesFilter(bodies)) continue;
            String sectorId = systemGroupKey(systemId, new String[]{"sector_id", "sector"});
            String sectorName = metadataLabel(new Node(systemId, systemId, "star system", bodies),
                    "sector_name", "sector");
            if (sectorId.equals("__unassigned")) sectorId = "__sector_unassigned";
            if (sectorName.isBlank()) sectorName = sectorId.equals("__sector_unassigned")
                    ? "Unassigned sector" : sectorId;
            if (!query.isBlank() && !sectorId.toLowerCase(Locale.ROOT).contains(query)
                    && !sectorName.toLowerCase(Locale.ROOT).contains(query)) continue;
            labels.putIfAbsent(sectorId, sectorName);
            groups.computeIfAbsent(sectorId, ignored -> new ArrayList<>())
                    .add(new AbstractMap.SimpleImmutableEntry<>(systemId, GalaxyCatalog.universe(systemId)));
        }
        List<Node> result = new ArrayList<>();
        for (Map.Entry<String, List<Entry<String, JsonObject>>> group : groups.entrySet())
            result.add(new Node(group.getKey(), labels.getOrDefault(group.getKey(), group.getKey()),
                    "sector", group.getValue()));
        result.sort(Comparator.comparing(node -> node.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private String systemGroupKey(String systemId, String[] keys) {
        JsonObject system = GalaxyCatalog.universe(systemId);
        for (String key : keys) {
            String value = value(system, key);
            if (!value.isBlank()) return value.trim().toLowerCase(Locale.ROOT);
        }
        for (Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
            for (String key : keys) {
                String value = value(body.getValue(), key);
                if (!value.isBlank()) return value.trim().toLowerCase(Locale.ROOT);
            }
        }
        return "__unassigned";
    }

    private String metadataLabel(Node node, String... keys) {
        for (Entry<String, JsonObject> entry : node.entries) {
            String label = value(entry.getValue(), keys);
            if (!label.isBlank()) return label;
        }
        return "";
    }

    private String groupKey(JsonObject o, int groupLevel) {
        if (groupLevel == 0) {
            String v = value(o, "galaxy_id", "galaxy");
            return v.isEmpty() ? "__galaxy_unassigned" : v;
        }
        if (groupLevel == 1) {
            String v = value(o, "sector_id", "sector");
            if (!v.isEmpty()) return v;
            return "__sector_unassigned";
        }
        String v = value(o, "system_id", "system", "system_name");
        return v.isEmpty() ? "__system_unassigned" : v;
    }

    private String groupLabel(JsonObject o, int groupLevel, String key) {
        if (key.equals("__galaxy_unassigned")) return "Galaxy metadata unavailable";
        if (key.equals("__sector_unassigned")) return "Sector metadata unavailable";
        if (key.equals("__system_unassigned")) return "System metadata unavailable";
        if (groupLevel == 0) return value(o, "galaxy_name", "galaxy") .isEmpty() ? key : value(o, "galaxy_name", "galaxy");
        if (groupLevel == 1) return value(o, "sector_name", "sector").isEmpty() ? key : value(o, "sector_name", "sector");
        return value(o, "system_name", "system").isEmpty() ? key : value(o, "system_name", "system");
    }

    private List<Node> currentNodes() {
        if (level == 0 && atlasMode == 3) return atlasGalaxyNodes();
        if (level == 0 && atlasMode == 4) return atlasSectorNodes();
        if (level == 0 && atlasMode > 0) return atlasSystemNodes();
        List<Entry<String, JsonObject>> entries = scopedObjects();
        if (level == 3) {
            List<Node> bodies = new ArrayList<>();
            Map<String, Entry<String, JsonObject>> byId = new LinkedHashMap<>();
            for (Entry<String, JsonObject> e : entries) byId.put(e.getKey(), e);
            Map<String, List<Entry<String, JsonObject>>> children = new HashMap<>();
            List<Entry<String, JsonObject>> roots = new ArrayList<>();
            for (Entry<String, JsonObject> e : entries) {
                String parentId = resolveParentId(e, byId);
                if (parentId == null || parentId.equals(e.getKey())) roots.add(e);
                else children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(e);
            }
            Comparator<Entry<String, JsonObject>> byName = (left, right) -> {
                String leftName = value(left.getValue(), "name", "display_name");
                String rightName = value(right.getValue(), "name", "display_name");
                if (leftName.isEmpty()) leftName = left.getKey();
                if (rightName.isEmpty()) rightName = right.getKey();
                return leftName.compareToIgnoreCase(rightName);
            };
            roots.sort(byName);
            for (List<Entry<String, JsonObject>> group : children.values()) group.sort(byName);
            Set<String> visited = new HashSet<>();
            for (Entry<String, JsonObject> root : roots) appendBodyTree(root, 0, children, visited, bodies);
            // Cycles and malformed parent chains are shown as unassigned roots, never discarded.
            for (Entry<String, JsonObject> e : entries)
                if (!visited.contains(e.getKey())) appendBodyTree(e, 0, children, visited, bodies);
            if (bodyViewId != null) {
                int targetIndex = -1;
                for (int i = 0; i < bodies.size(); i++)
                    if (bodies.get(i).id.equals(bodyViewId)) { targetIndex = i; break; }
                if (targetIndex >= 0) {
                    int targetDepth = bodies.get(targetIndex).depth;
                    int end = targetIndex + 1;
                    while (end < bodies.size() && bodies.get(end).depth > targetDepth) end++;
                    List<Node> focused = new ArrayList<>(bodies.subList(targetIndex, end));
                    bodies = focused;
                } else {
                    bodyViewId = null;
                }
            }
            if (bodyFilter != 0) {
                bodies.removeIf(node -> {
                    String kind = node.kind.toLowerCase(Locale.ROOT);
                    return switch (bodyFilter) {
                        case 1 -> !(kind.contains("planet"));
                        case 2 -> !(kind.contains("moon") || kind.contains("satellite"));
                        case 3 -> !(kind.contains("star"));
                        case 4 -> kind.contains("planet") || kind.contains("moon") ||
                                kind.contains("satellite") || kind.contains("star");
                        case 5 -> !hasTruthyMetadata(node.first(), "life");
                        case 6 -> !hasTruthyMetadata(node.first(), "atmosphere", "has_atmosphere", "atmosphere_y");
                        case 7 -> !kind.contains("station");
                        case 8 -> !kind.contains("asteroid");
                        case 9 -> !kind.contains("blackhole") && !kind.contains("black hole");
                        case 10 -> !kind.contains("jump") && !kind.contains("wormhole") && !kind.contains("gate");
                        case 11 -> !kind.contains("lagrange");
                        case 12 -> !kind.contains("anomaly") && !kind.contains("wreck")
                                && !kind.contains("beacon") && !kind.contains("signal") && !kind.contains("poi");
                        case 13 -> !kind.contains("ship") && !kind.contains("contact") && !kind.contains("target");
                        case 14 -> !kind.contains("nebula");
                        case 15 -> !kind.contains("comet");
                        case 16 -> !kind.contains("wreck") && !kind.contains("debris");
                        default -> false;
                    };
                });
            }
            return bodies;
        }

        if (level == 0) {
            if (entries.isEmpty()) return List.of();
            JsonObject system = GalaxyCatalog.universe(universe);
            String label = GalaxyCatalog.text(system, "name", universe);
            return List.of(new Node(universe, label, "star system", entries));
        }

        int groupingLevel = level;
        Map<String, List<Entry<String, JsonObject>>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry<String, JsonObject> e : entries)
            groups.computeIfAbsent(groupKey(e.getValue(), groupingLevel), k -> new ArrayList<>()).add(e);

        List<Node> result = new ArrayList<>();
        for (Map.Entry<String, List<Entry<String, JsonObject>>> g : groups.entrySet()) {
            JsonObject first = g.getValue().get(0).getValue();
            String label = groupLabel(first, groupingLevel, g.getKey());
            String kind = switch (level) {
                case 0 -> "system collection";
                case 1 -> "sector";
                default -> "star system";
            };
            result.add(new Node(g.getKey(), label, kind, g.getValue()));
        }
        return result;
    }

    /**
     * Resolve authored parent links first. When a system contains exactly one
     * star, planets without an explicit parent are safely attached to it for
     * orbit display; moons are never assigned to an arbitrary planet.
     */
    private String resolveParentId(Entry<String, JsonObject> child,
                                   Map<String, Entry<String, JsonObject>> byId) {
        String manualParent = NavigatorPreferences.manualParent(universe, child.getKey());
        if (!manualParent.isEmpty() && byId.containsKey(manualParent)
                && !manualParent.equals(child.getKey())) return manualParent;
        JsonObject body = child.getValue();
        String reference = orbitalParentReference(body);
        if (!reference.isEmpty()) {
            if (byId.containsKey(reference)) return reference;
            for (Map.Entry<String, Entry<String, JsonObject>> candidate : byId.entrySet()) {
                JsonObject candidateBody = candidate.getValue().getValue();
                String candidateId = value(candidateBody, "id", "catalog_id");
                if (reference.equals(candidateId)) return candidate.getKey();
            }
            // A dimension may be shared by several bodies in one system.
            // Only use it as a parent key when it identifies exactly one body.
            String uniqueDimensionOwner = null;
            boolean ambiguousDimension = false;
            for (Map.Entry<String, Entry<String, JsonObject>> candidate : byId.entrySet()) {
                JsonObject candidateBody = candidate.getValue().getValue();
                String dimension = value(candidateBody, "dimension", "dimension_id", "attached_dimension_id");
                if (!reference.equals(dimension)) continue;
                if (uniqueDimensionOwner != null) { ambiguousDimension = true; break; }
                uniqueDimensionOwner = candidate.getKey();
            }
            if (!ambiguousDimension && uniqueDimensionOwner != null
                    && !uniqueDimensionOwner.equals(child.getKey()))
                return uniqueDimensionOwner;
        }

        String kind = value(body, "kind", "classification").toLowerCase(Locale.ROOT);
        if (kind.equals("moon") || kind.contains("satellite")) {
            String onlyPlanet = null;
            for (Map.Entry<String, Entry<String, JsonObject>> candidate : byId.entrySet()) {
                String candidateKind = value(candidate.getValue().getValue(), "kind").toLowerCase(Locale.ROOT);
                if (!candidateKind.equals("planet")) continue;
                if (onlyPlanet != null) return null; // multi-planet systems need explicit moon parents
                onlyPlanet = candidate.getKey();
            }
            return onlyPlanet;
        }
        if (!kind.equals("planet") && !kind.equals("asteroid") && !kind.equals("comet")) return null;
        String onlyStar = null;
        for (Map.Entry<String, Entry<String, JsonObject>> candidate : byId.entrySet()) {
            if (!isStarNode(new Node(candidate.getKey(),
                    value(candidate.getValue().getValue(), "name", candidate.getKey()),
                    value(candidate.getValue().getValue(), "kind"), List.of(candidate.getValue())))) continue;
            if (onlyStar != null) return null; // binary/multiple-star systems need explicit links
            onlyStar = candidate.getKey();
        }
        return onlyStar;
    }

    private void appendBodyTree(Entry<String, JsonObject> entry, int depth,
                                Map<String, List<Entry<String, JsonObject>>> children,
                                Set<String> visited, List<Node> output) {
        if (!visited.add(entry.getKey())) return;
        JsonObject data = entry.getValue();
        String name = value(data, "name", "display_name");
        if (name.isEmpty()) name = entry.getKey();
        String kind = value(data, "kind", "display_type", "type", "classification").trim();
        String normalizedKind = kind.toLowerCase(Locale.ROOT);
        // Keep object identity data-driven and consistent across the catalogue,
        // orbital hierarchy, labels and parent resolution.
        if (isStarData(data)) kind = "star";
        else if (normalizedKind.contains("moon") || normalizedKind.contains("satellite")) kind = "moon";
        else if (normalizedKind.isEmpty()) kind = "planet";
        output.add(new Node(entry.getKey(), name, kind, List.of(entry), Math.min(depth, 8)));
        for (Entry<String, JsonObject> child : children.getOrDefault(entry.getKey(), List.of()))
            appendBodyTree(child, depth + 1, children, visited, output);
    }

    private Node findNode(String id) {
        if (id == null || id.isBlank()) return null;
        for (Node n : currentNodes()) if (n.id.equals(id)) return n;
        // A qualified miniature-body ID allows direct selection from the global
        // universe/galaxy/sector canvas without switching the active system first.
        int separator = id.indexOf("::");
        if (separator > 0) {
            String systemId = id.substring(0, separator);
            String bodyId = id.substring(separator + 2);
            if (GalaxyCatalog.universes().contains(systemId)) {
                for (Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
                    if (!body.getKey().equals(bodyId)) continue;
                    JsonObject data = body.getValue();
                    String kind = value(data, "kind", "display_type", "type", "classification")
                            .trim().toLowerCase(Locale.ROOT);
                    if (isStarData(data)) kind = "star";
                    else if (kind.contains("moon") || kind.contains("satellite")) kind = "moon";
                    else if (kind.isBlank()) kind = "planet";
                    return new Node(id, value(data, "name", bodyId), "atlas_body:" + kind,
                            List.of(body));
                }
            }
        }
        // Galaxy/sector glyphs contain individually selectable miniature systems.
        JsonObject system = GalaxyCatalog.universe(id);
        List<Entry<String, JsonObject>> bodies = GalaxyCatalog.objects(id);
        if (!bodies.isEmpty()) {
            String name = GalaxyCatalog.text(system, "name", id);
            return new Node(id, name, "star system", bodies);
        }
        return null;
    }

    private void selectNode(Node node) {
        if (node != null && node.kind != null && node.kind.startsWith("atlas_body:")) {
            selectBody(node.id);
            return;
        }
        selectedNodeId = node == null ? null : node.id;
        selectedId = null;
        infoScroll = 0;
        if (openButton != null) openButton.active = selectedNodeId != null;
    }

    private void selectBody(String id) {
        if (!Objects.equals(selectedId, id)) {
            infoYawOffset = 0.0;
            infoPitchOffset = 0.0;
        }
        selectedId = id;
        selectedNodeId = id;
        infoScroll = 0;
        JsonObject o = findObject(id);
        if (o != null) {
            setTravelInput(dimensionInput, o, "", "travel_dimension", "dimension",
                    "dimension_id", "attached_dimension_id");
            setTravelInput(xInput, o, "0", "travel_x", "origin_x");
            setTravelInput(yInput, o, "100", "travel_y", "origin_y");
            setTravelInput(zInput, o, "0", "travel_z", "origin_z");
        }
        refreshButtons();
    }

    /** Populate the editable travel fields only when the active datapack supplies a value. */
    private void setTravelInput(EditBox input, JsonObject data, String fallback, String... keys) {
        if (input == null) return;
        String supplied = data == null ? "" : value(data, keys);
        input.setValue(supplied.isEmpty() ? fallback : supplied);
    }

    /** Quiet near-black workspace canvas. Spatial data and celestial models provide the visual focus. */
    private void drawCosmicBackdrop(GuiGraphics g) {
        g.fill(0, 0, width, height, MaqanUiTheme.BACKGROUND);
        int horizon = Math.max(1, Math.min(height - 1, (int) (height * 0.115f)));
        g.fill(0, horizon, width, horizon + 1, 0x183B6175);
    }

    /** Read live positions from MAQAN's N-body simulation, falling back to authored CH coordinates. */
    private double coordinate(JsonObject body, int axis) {
        double gameTime = minecraft != null && minecraft.level != null
                ? minecraft.level.getGameTime() + minecraft.getFrameTime() : 0.0;
        if (universe == null || universe.isBlank() || body == null)
            return GalaxyCatalog.coordinate(body, axis);
        JsonObject system = GalaxyCatalog.universe(universe);
        return NativeOrbitalSimulation.coordinate(universe, system, body, axis, gameTime);
    }

    private JsonObject findObject(String id) {
        if (id == null || id.isBlank()) return null;
        // Global-scene body selections are qualified as systemId::bodyId.
        // Resolve against that owning system instead of whichever system the
        // previous hierarchy view happened to have active.
        int separator = id.indexOf("::");
        if (separator > 0) {
            String systemId = id.substring(0, separator);
            String bodyId = id.substring(separator + 2);
            for (Entry<String, JsonObject> entry : GalaxyCatalog.objects(systemId))
                if (entry.getKey().equals(bodyId)) return entry.getValue();
            return null;
        }
        for (Entry<String, JsonObject> entry : GalaxyCatalog.objects(universe))
            if (entry.getKey().equals(id)) return entry.getValue();
        return null;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        com.maqan.galacticnavigator.navigation.MaqanAnimationClock.beginFrame();
        if (embeddedInMaqan) {
            drawCosmicBackdrop(g);
            drawBreadcrumbs(g, mx, my);
        } else {
            renderBackground(g);
            if (!com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderChrome(g, font, width, height, partial, 0)) drawCosmicBackdrop(g);
            MaqanUiTheme.panel(g,12,4,width-12,38);
            g.fill(0, 38, width, 39, 0x665D7895);
            g.fill(14, 37, 54, 39, CYAN);
            g.drawString(font, "MAQAN", 16, 7, TEXT, false);
            g.drawString(font, "GALACTIC NAVIGATOR", 64, 7, CYAN, false);
            drawBreadcrumbs(g, mx, my);
        }

        layoutPanels();
        if (embeddedInMaqan) mapT = Math.max(mapT, 84);
        boolean useList = showList && width >= 620 && listR > listL;
        boolean useInfo = showInfo && width >= 850 && infoR > infoL;
        int bodyTop = mapT;
        int bodyBottom = mapB;
        infoPreviewRect = infoResetRect = infoOrbitEditRect = null;

        Node listHover = null;
        listRects.clear();
        mapPoints.clear();
        List<Node> nodes = currentNodes();
        renderedNodes.clear(); renderedNodes.addAll(nodes);
        if (useList && listR > listL) {
            panel(g, listL, bodyTop, listR, bodyBottom);
            g.enableScissor(listL + 3, bodyTop + 3, listR - 3, bodyBottom - 3);
            drawList(g, mx, my, nodes);
            g.disableScissor();
            for (Map.Entry<String, Rect> entry : listRects.entrySet()) {
                if (entry.getValue().contains(mx, my)) { listHover = findNode(entry.getKey()); break; }
            }
        }
        Node pinnedNode = selectedId != null ? bodyNode(selectedId)
                : selectedNodeId != null ? findNode(selectedNodeId) : null;
        if (useInfo && infoR > infoL) {
            panel(g, infoL, bodyTop, infoR, bodyBottom);
            // Clip every dossier field to its own column.
            g.enableScissor(infoL + 3, bodyTop + 3, infoR - 3, bodyBottom - 3);
            drawInfo(g, pinnedNode, mx, my);
            g.disableScissor();
        }
        panel(g, mapL, mapT, mapR, mapB);
        g.enableScissor(mapL + 3, mapT + 3, mapR - 3, mapB - 3);
        hoveredNode = drawMap(g, mx, my, nodes);
        g.disableScissor();
        if (hoveredNode == null) hoveredNode = listHover;
        if (hoveredNode != null && (pinnedNode == null || !hoveredNode.id.equals(pinnedNode.id)))
            drawHoverCard(g, hoveredNode, mx, my);
        if (width >= 560) {
            g.fill(0, height - 30, width, height, 0xF00B1520);
            String mapCaption = bodyViewId != null ? "BODY VIEW · " + mapModeName() + " · SATELLITE ORBITS" :
                    level == 3 ? "SYSTEM MAP · " + mapModeName() + " · ORBITS + MOTION" +
                    (mapMode == 2 ? " · LEFT-DRAG MOVE / RIGHT-DRAG ROTATE" : " · DATA-BACKED OBJECT POSITIONS")
                    : atlasMode > 0 ? atlasModeName() + " ATLAS · " + nodes.size() + " SYSTEMS · DATAPACK METADATA"
                    : "STAR SYSTEM OVERVIEW";
            g.drawString(font, trim(mapCaption + "  ·  DRAG PANEL HEADERS TO REORDER",
                    Math.max(40, mapR - mapL - 16)), mapL + 8, height - 20, MUTED, false);
        }
        if (toolsOpen) {
            int menuTop = toolsMenuTop();
            int toolsBottom = toolsMenuBottom();
            g.fill(width - 140, menuTop, width - 8, toolsBottom, 0xD9080D1B);
            g.fill(width - 140, menuTop, width - 8, menuTop + 1, 0x805F7399);
            g.fill(width - 141, menuTop + 1, width - 140, toolsBottom, 0x503B4966);
            g.fill(width - 8, menuTop + 1, width - 7, toolsBottom, 0x503B4966);
        }
        super.render(g, mx, my, partial);
        if (contextOpen) drawContextMenu(g, mx, my);
        if (systemPickerOpen) drawSystemPicker(g);
        // Skin native hit-target widgets with MAQAN's own visual language.
        drawButtonSkin(g, universeButton);
        drawButtonSkin(g, backButton);
        drawButtonSkin(g, openButton);
        drawButtonSkin(g, toolsButton);
        drawButtonSkin(g, unifiedSceneButton);
        drawButtonSkin(g, closeButton);
        drawButtonSkin(g, travelButton);
        for (Button button : toolButtons) drawButtonSkin(g, button);
        MaqanUiTheme.inputFrame(g, search);
        MaqanUiTheme.inputFrame(g, dimensionInput);
        MaqanUiTheme.inputFrame(g, xInput);
        MaqanUiTheme.inputFrame(g, yInput);
        MaqanUiTheme.inputFrame(g, zInput);
        renderAtlasTransition(g);
    }

    private void beginAtlasTransition(boolean backward) {
        transitionBackward = backward;
        transitionStartedNanos = com.maqan.galacticnavigator.navigation.MaqanAnimationClock.frameNanos();
    }

    private void renderAtlasTransition(GuiGraphics g) {
        if (transitionStartedNanos == 0L) return;
        if (com.maqan.galacticnavigator.navigation.MaqanAnimationClock.reducedMotion()) {
            transitionStartedNanos = 0L;
            return;
        }
        double elapsed = (com.maqan.galacticnavigator.navigation.MaqanAnimationClock.frameNanos() - transitionStartedNanos) / 1_000_000_000.0;
        double duration = com.maqan.galacticnavigator.navigation.MaqanAnimationClock.durationSeconds(0.46);
        float linear = (float)Math.max(0.0, Math.min(1.0, elapsed / Math.max(0.001, duration)));
        float progress = linear * linear * (3.0f - 2.0f * linear);
        if (progress >= 1.0f) {
            transitionStartedNanos = 0L;
            return;
        }
        if (!com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderTransitionOverlay(
                g, width, height, progress, transitionBackward)) {
            int alpha = (int)Math.round(34.0 * (1.0 - progress) * (1.0 - progress));
            if (alpha > 0) g.fill(0, 0, width, height, alpha << 24 | 0x00101C2A);
            int sweepX = (int)(width * (transitionBackward ? 1.0 - progress : progress));
            g.fill(Math.max(0, sweepX - 1), 8, Math.min(width, sweepX + 2), Math.max(8, height - 8),
                    ((int)(150.0 * (1.0 - progress)) << 24) | 0x0079D9F2);
        }
    }

    private List<String> filteredSystems() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<String> all = GalaxyCatalog.universes();
        if (query.isEmpty()) return all;
        List<String> filtered = new ArrayList<>();
        for (String id : all) {
            String name = GalaxyCatalog.text(GalaxyCatalog.universe(id), "name", id);
            if (id.toLowerCase(Locale.ROOT).contains(query) ||
                    name.toLowerCase(Locale.ROOT).contains(query)) filtered.add(id);
        }
        return filtered;
    }

    private void drawSystemPicker(GuiGraphics g) {
        List<String> systems = filteredSystems();
        int x = universeButton == null ? 100 : universeButton.getX();
        int y = 66;
        int w = Math.min(320, width - x - 12);
        int rowH = 22;
        int maxRows = Math.max(1, Math.min(12, (height - y - 48) / rowH));
        systemPickerScroll = Math.max(0, Math.min(systemPickerScroll,
                Math.max(0, systems.size() - maxRows)));
        int h = Math.min(systems.size(), maxRows) * rowH + 30;
        g.fill(x, y, x + w, y + h, 0xF00A1424);
        g.fill(x, y, x + w, y + 1, CYAN);
        g.drawString(font, "SELECT STAR SYSTEM  ·  " + systems.size(), x + 9, y + 7, GOLD, false);
        int rowY = y + 24;
        for (int row = 0; row < maxRows && systemPickerScroll + row < systems.size(); row++) {
            int i = systemPickerScroll + row;
            String id = systems.get(i);
            JsonObject system = GalaxyCatalog.universe(id);
            String label = GalaxyCatalog.text(system, "name", id);
            boolean active = id.equals(universe);
            int rowBottom = rowY + rowH;
            if (active) g.fill(x + 3, rowY, x + w - 3, rowBottom, 0xFF25465A);
            g.drawString(font, trim(label, w - 42), x + 10, rowY + 6, active ? GOLD : TEXT, false);
            g.drawString(font, active ? "\u25CF" : "\u25CB", x + w - 22, rowY + 6, active ? CYAN : MUTED, false);
            rowY += rowH;
        }
        if (systems.size() > maxRows)
            g.drawString(font, "Use catalogue search to narrow the list", x + 9, y + h - 15, MUTED, false);
    }

    private boolean selectSystemAt(double mouseX, double mouseY) {
        if (!systemPickerOpen) return false;
        List<String> systems = filteredSystems();
        int x = universeButton == null ? 100 : universeButton.getX();
        int y = 66;
        int w = Math.min(320, width - x - 12);
        int rowH = 22;
        int maxRows = Math.max(1, Math.min(12, (height - y - 48) / rowH));
        systemPickerScroll = Math.max(0, Math.min(systemPickerScroll,
                Math.max(0, systems.size() - maxRows)));
        int count = Math.min(systems.size() - systemPickerScroll, maxRows);
        int h = count * rowH + 30;
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) {
            systemPickerOpen = false;
            return true;
        }
        int index = (int)((mouseY - y - 24) / rowH);
        if (index >= 0 && index < count) {
            String next = systems.get(systemPickerScroll + index);
            if (!next.equals(universe)) {
                universe = next;
                resetPath();
                refreshButtons();
            }
        }
        systemPickerOpen = false;
        return true;
    }

    private double contextAnimationProgress() {
        double elapsed = com.maqan.galacticnavigator.navigation.MaqanAnimationClock.reducedMotion() ? 1.0
                : (com.maqan.galacticnavigator.navigation.MaqanAnimationClock.frameNanos() - contextOpenedAtNanos) / 190_000_000.0;
        double t = Math.max(0.0, Math.min(1.0, elapsed));
        return 1.0 - Math.pow(1.0 - t, 3.0);
    }

    private int fadeColor(int color, double progress) {
        int alpha = (int)Math.round(((color >>> 24) & 255) * Math.max(0.0, Math.min(1.0, progress)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private Rect contextMenuBounds() {
        int w = 178, h = 100;
        int x = Math.max(6, Math.min(contextX, width - w - 6));
        int baseY = Math.max(38, Math.min(contextY, height - h - 34));
        int y = baseY + (int)Math.round((1.0 - contextAnimationProgress()) * 8.0);
        return new Rect(x, y, w, h);
    }

    private void drawContextMenu(GuiGraphics g, int mx, int my) {
        Node node = contextNodeId == null ? null : findNode(contextNodeId);
        if (node == null) { contextOpen = false; return; }
        Rect bounds = contextMenuBounds();
        int x = bounds.x, y = bounds.y, w = bounds.w, h = bounds.h;
        double alpha = contextAnimationProgress();
        int hoveredRow = -1;
        if (bounds.contains(mx, my)) {
            int row = (int)((my - y - 20) / 18);
            if (row >= 0 && row <= 3) hoveredRow = row;
        }
        if (!com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderContextMenu(
                g, x, y, x + w, y + h, hoveredRow, (float)alpha)) {
            g.fill(x, y, x + w, y + h, fadeColor(0xF00A1424, alpha));
            g.fill(x, y, x + w, y + 1, fadeColor(0xFF67D8F2, alpha));
            g.fill(x, y + h - 1, x + w, y + h, fadeColor(0xFF263E50, alpha));
        }
        g.drawString(font, trim(node.label, w - 14), x + 7, y + 6, fadeColor(GOLD, alpha), false);
        g.drawString(font, "FOCUS OBJECT", x + 9, y + 24, fadeColor(TEXT, alpha), false);
        g.drawString(font, level < 3 ? "OPEN LEVEL" : "INSPECT BODY", x + 9, y + 42, fadeColor(TEXT, alpha), false);
        g.drawString(font, "USE AS DESTINATION", x + 9, y + 60, fadeColor(TEXT, alpha), false);
        g.drawString(font, "EDIT PARENT / ORBIT", x + 9, y + 78, fadeColor(CYAN, alpha), false);
    }

    private void runContextAction(int action) {
        Node node = contextNodeId == null ? null : findNode(contextNodeId);
        if (node == null) { contextOpen = false; return; }
        if (action == 0) {
            if (node.kind != null && node.kind.startsWith("atlas_body:")) {
                selectBody(node.id);
                focusSelected();
            } else if (level == 3) {
                selectedId = node.id;
                selectedNodeId = node.id;
                cameraTargetId = node.id;
            } else {
                selectNode(node);
                focusSelected();
            }
        } else if (action == 1) {
            selectedNodeId = node.id;
            if (level == 3) {
                selectBody(node.id);
                bodyViewId = node.id;
                cameraTargetId = node.id;
            } else openSelected();
        } else if (action == 2) {
            JsonObject data = node.first();
            String dimension = value(data, "travel_dimension", "dimension", "dimension_id",
                    "attached_dimension_id", "system_dimension");
            if (!dimension.isEmpty() && dimensionInput != null) dimensionInput.setValue(dimension);
            JsonElement travel = data.get("travel_pos");
            if (travel != null && travel.isJsonArray() && travel.getAsJsonArray().size() >= 3) {
                JsonArray coordinates = travel.getAsJsonArray();
                xInput.setValue(coordinates.get(0).getAsString());
                yInput.setValue(coordinates.get(1).getAsString());
                zInput.setValue(coordinates.get(2).getAsString());
            } else if (data.has("origin_x") && data.has("origin_y") && data.has("origin_z")) {
                xInput.setValue(data.get("origin_x").getAsString());
                yInput.setValue(data.get("origin_y").getAsString());
                zInput.setValue(data.get("origin_z").getAsString());
            }
        } else if (action == 3 && (level == 3
                || (node.kind != null && node.kind.startsWith("atlas_body:")))) {
            JsonObject body = node.first();
            String targetId = node.id;
            int separator = targetId.indexOf("::");
            String systemId = separator > 0 ? targetId.substring(0, separator)
                    : GalaxyCatalog.text(body, "system_id", universe);
            String localBodyId = separator > 0 ? targetId.substring(separator + 2) : targetId;
            String bodyId = GalaxyCatalog.text(body, "catalog_id", localBodyId);
            String bodyName = GalaxyCatalog.text(body, "name", node.label);
            if (level == 3) selectBody(localBodyId);
            contextOpen = false;
            if (minecraft != null)
                MaqanScreenHost.open(minecraft, new CosmicBodyOrbitSettingsScreen(this, systemId, bodyId, bodyName));
            return;
        }
        contextOpen = false;
        refreshButtons();
    }

    private void drawButtonSkin(GuiGraphics g, Button button) {
        MaqanUiTheme.button(g, font, button);
    }

    private String breadcrumb() {
        String systemName = GalaxyCatalog.text(GalaxyCatalog.universe(universe), "name", universe);
        StringBuilder b = new StringBuilder(atlasMode > 0 && level == 0
                ? atlasModeName() + " ATLAS  /  " : "SYSTEM  /  ").append(systemName);
        if (bodyViewId != null) {
            JsonObject body = findObject(bodyViewId);
            String bodyName = body == null ? bodyViewId : value(body, "name", "display_name");
            if (bodyName.isEmpty()) bodyName = bodyViewId;
            b.append("  /  ").append(bodyName);
        } else if (level == 3) {
            b.append("  /  CELESTIAL BODIES");
        }
        return b.toString();
    }

    private String pathLabel(String key, int groupLevel) {
        Node n = new Node(key, key, "", scopedObjects());
        for (Entry<String, JsonObject> e : GalaxyCatalog.objects(universe))
            if (groupKey(e.getValue(), groupLevel).equals(key))
                return groupLabel(e.getValue(), groupLevel, key);
        return key;
    }

    private void drawBreadcrumbs(GuiGraphics g, int mouseX, int mouseY) {
        breadcrumbHits.clear();
        List<String[]> segments = new ArrayList<>();
        segments.add(new String[]{"MAQAN", "root"});
        if (galaxyPath != null) {
            String label = pathLabel(galaxyPath, 0);
            segments.add(new String[]{"GALAXY  /  " + label, "galaxy"});
        }
        if (sectorPath != null) {
            String label = pathLabel(sectorPath, 1);
            segments.add(new String[]{"SECTOR  /  " + label, "sector"});
        }
        if (level == 1) segments.add(new String[]{"SECTORS", "level1"});
        else if (level == 2) segments.add(new String[]{"SYSTEMS", "level2"});
        else if (level == 3) {
            String systemName = GalaxyCatalog.text(GalaxyCatalog.universe(universe), "name", universe);
            segments.add(new String[]{systemName, "system"});
            if (bodyViewId != null) {
                JsonObject body = findObject(bodyViewId);
                String name = body == null ? bodyViewId : value(body, "name", "display_name");
                if (name == null || name.isBlank()) name = bodyViewId;
                segments.add(new String[]{name, "body"});
            } else {
                segments.add(new String[]{"CELESTIAL BODIES", "bodies"});
            }
        } else if (segments.size() == 1) {
            segments.add(new String[]{atlasModeName() + " ATLAS", "atlas"});
        }

        int x = embeddedInMaqan ? 54 : 16;
        int y = embeddedInMaqan ? 38 : 17;
        int maxX = width - 16;
        for (int i = 0; i < segments.size(); i++) {
            String[] segment = segments.get(i);
            String label = segment[0];
            int separatorWidth = i == 0 ? 0 : font.width("  ›  ");
            int available = maxX - x - separatorWidth;
            if (available <= 12) break;
            if (i > 0) {
                g.drawString(font, "›", x, y + 3, 0xFF637B91, false);
                x += 12;
                available = maxX - x;
            }
            int textWidth = Math.min(font.width(label), Math.max(0, available - 14));
            if (textWidth < font.width(label)) label = font.plainSubstrByWidth(label, textWidth);
            int chipWidth = Math.min(available, textWidth + 14);
            Rect hit = new Rect(x, y - 2, chipWidth, 14);
            breadcrumbHits.add(new BreadcrumbHit(segment[1], hit));
            boolean hovered = hit.contains(mouseX, mouseY);
            boolean current = i == segments.size() - 1;
            if (!com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderBreadcrumbChip(
                    g, hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, current, hovered)) {
                int fill = hovered ? 0xCC24465B : current ? 0x88304D62 : 0x55202F40;
                g.fill(hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, fill);
                g.fill(hit.x, hit.y, hit.x + hit.w, hit.y + 1,
                        hovered || current ? 0xCC79D9F2 : 0x445D7895);
            }
            g.drawString(font, label, x + 7, y + 1,
                    hovered ? 0xFFFFFFFF : current ? CYAN : MUTED, false);
            x += chipWidth + 5;
            if (x >= maxX) break;
        }
    }

    private void navigateBreadcrumb(String action) {
        beginAtlasTransition("root".equals(action) || "atlas".equals(action)
                || "galaxy".equals(action) || "sector".equals(action)
                || "level1".equals(action) || "level2".equals(action)
                || "system".equals(action) || "bodies".equals(action));
        switch (action) {
            case "root", "atlas" -> {
                resetPath();
                atlasMode = 3;
                refreshButtons();
            }
            case "galaxy" -> {
                sectorPath = null;
                systemPath = null;
                level = 0;
                atlasMode = 1;
                selectedId = null;
                selectedNodeId = null;
                bodyViewId = null;
                listScroll = 0;
                panX = 0;
                panY = 0;
                zoom = 1.0;
                refreshButtons();
            }
            case "sector" -> {
                systemPath = null;
                level = 0;
                atlasMode = 4;
                selectedId = null;
                selectedNodeId = null;
                bodyViewId = null;
                listScroll = 0;
                panX = 0;
                panY = 0;
                zoom = 1.0;
                refreshButtons();
            }
            case "level1" -> {
                level = 1;
                selectedId = null;
                selectedNodeId = null;
                listScroll = 0;
                refreshButtons();
            }
            case "level2" -> {
                level = 2;
                selectedId = null;
                selectedNodeId = null;
                listScroll = 0;
                refreshButtons();
            }
            case "system", "bodies" -> {
                level = 3;
                galaxyPath = null;
                sectorPath = null;
                systemPath = null;
                bodyViewId = null;
                selectedId = null;
                selectedNodeId = null;
                listScroll = 0;
                panX = 0;
                panY = 0;
                zoom = 1.0;
                refreshButtons();
            }
            case "body" -> {
                if (bodyViewId != null) {
                    selectedId = bodyViewId;
                    selectedNodeId = bodyViewId;
                    cameraTargetId = bodyViewId;
                    refreshButtons();
                }
            }
        }
    }

    private void panel(GuiGraphics g, int l, int t, int r, int b) {
        MaqanUiTheme.panel(g, l, t, r, b);
    }

    private void drawList(GuiGraphics g, int mx, int my, List<Node> nodes) {
        g.drawString(font, levelTitle() + "  ·  " + nodes.size(), listL + 8, mapT + 8, TEXT, false);
        if (nodes.isEmpty()) {
            g.drawString(font, "NO CELESTIAL BODIES", listL + 10, mapT + 38, MUTED, false);
            g.drawString(font, "This system has no", listL + 10, mapT + 54, MUTED, false);
            g.drawString(font, "displayable objects yet.", listL + 10, mapT + 66, MUTED, false);
            return;
        }
        int y = mapT + 26;
        int rowH = 29;
        int visibleRows = Math.max(1, (mapB - 5 - y) / rowH);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, nodes.size() - visibleRows)));
        for (int i = listScroll; i < nodes.size() && y + rowH < mapB - 5; i++) {
            Node n = nodes.get(i);
            Rect rect = new Rect(listL + 4, y - 2, listR - listL - 8, rowH);
            listRects.put(n.id, rect);
            boolean active = n.id.equals(selectedNodeId) || n.id.equals(selectedId);
            boolean hover = rect.contains(mx, my);
            if (!com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderCatalogueCard(
                    g, rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, active, hover)) {
                if (active) g.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, 0xFF25465A);
                else if (hover) g.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, 0xFF1B3040);
            }
            int indent = Math.min(8, n.depth) * 8;
            String prefix = n.depth > 0 ? "› " : "";
            g.drawString(font, trim(prefix + n.label, rect.w - 18 - indent), listL + 10 + indent, y + 2, active ? GOLD : TEXT, false);
            String relation = orbitalParentReference(n.first());
            String parentLabel = relation;
            if (!relation.isEmpty()) {
                JsonObject parent = findObject(relation);
                if (parent == null) {
                    for (Entry<String, JsonObject> candidate : GalaxyCatalog.objects(universe)) {
                        String dimension = value(candidate.getValue(), "dimension", "dimension_id", "attached_dimension_id");
                        if (relation.equals(dimension)) { parent = candidate.getValue(); break; }
                    }
                }
                if (parent != null) {
                    String resolved = value(parent, "name", "display_name");
                    if (!resolved.isEmpty()) parentLabel = resolved;
                }
            }
            String subtitle = relation.isEmpty() ? n.kind : n.kind + " · orbits " + parentLabel;
            g.drawString(font, trim(subtitle, rect.w - 12), listL + 10, y + 14, MUTED, false);
            y += rowH;
        }
    }

    private String levelTitle() {
        return switch (level) {
            case 0 -> "ACTIVE STAR SYSTEM";
            case 1 -> "SECTORS";
            case 2 -> "STAR SYSTEMS";
            default -> "CELESTIAL BODIES";
        };
    }

    private Node bodyNode(String id) {
        JsonObject o = findObject(id);
        if (o == null) return null;
        String kind = value(o, "kind", "display_type", "type", "classification")
                .trim().toLowerCase(Locale.ROOT);
        if (isStarData(o)) kind = "star";
        else if (kind.contains("moon") || kind.contains("satellite")) kind = "moon";
        else if (kind.isBlank()) kind = "planet";
        return new Node(id, value(o, "name", "display_name").isEmpty() ? id
                : value(o, "name", "display_name"), kind,
                List.of(new AbstractMap.SimpleImmutableEntry<>(id, o)));
    }

    private void drawBodyTypeLegend(GuiGraphics g, int mouseX, int mouseY) {
        if (mapR - mapL < 190) return;
        int y = mapT + 13;
        int starX = mapR - 70, planetX = mapR - 46, moonX = mapR - 22;
        drawRadialGlow(g, starX, y, 5, 0x55FFD878, 0xFFFFD878);
        drawDisc(g, starX, y, 2, 0xFFFFF4D0, 0xFFFFD878);
        g.fill(planetX - 3, y - 3, planetX + 4, y + 4, 0xFF63BBD7);
        g.fill(planetX - 2, y - 2, planetX + 1, y - 1, 0xFFDAF7FF);
        drawDisc(g, moonX, y, 2, 0xFFE6EEF5, 0xFF9DAFBE);
        if (Math.hypot(mouseX - starX, mouseY - y) <= 8)
            g.renderTooltip(font, Component.literal("STAR · stellar body"), mouseX, mouseY);
        else if (Math.hypot(mouseX - planetX, mouseY - y) <= 8)
            g.renderTooltip(font, Component.literal("PLANET · cuboid body"), mouseX, mouseY);
        else if (Math.hypot(mouseX - moonX, mouseY - y) <= 8)
            g.renderTooltip(font, Component.literal("MOON · natural satellite"), mouseX, mouseY);
    }

    private Node drawMap(GuiGraphics g, int mx, int my, List<Node> nodes) {
        g.drawString(font, bodyViewId != null ? "PLANET / SATELLITE VIEW" :
                level == 3 ? "STAR SYSTEM" : unifiedSceneMode ? "UNIFIED COSMIC SCENE"
                        : atlasMode > 0 ? atlasModeName() + " ATLAS" : levelTitle(),
                mapL + 10, mapT + 8, TEXT, false);
        drawBodyTypeLegend(g, mx, my);
        if (nodes.isEmpty()) {
            int cx = (mapL + mapR) / 2;
            int cy = (mapT + mapB) / 2;
            g.drawCenteredString(font, "NO CELESTIAL DATA", cx, cy - 14, TEXT);
            g.drawCenteredString(font, "No bodies are available in this system.", cx, cy + 6, MUTED);
            return null;
        }
        if (level == 3 && bodyViewId != null) return drawPlanetExplorer(g, mx, my, nodes);
        if (level == 3) return drawOrbitalMap(g, mx, my, nodes);
        if (level == 0 && atlasMode > 0) return drawAtlasOverview(g, mx, my, nodes);

        int cols = Math.max(1, (int)Math.floor((mapR - mapL - 70) / 150.0));
        cols = Math.min(cols, Math.max(1, nodes.size()));
        int rows = (int)Math.ceil(nodes.size() / (double)cols);
        double cellW = (mapR - mapL - 42.0) / cols;
        double cellH = Math.max(74, Math.min(112, (mapB - mapT - 66.0) / Math.max(1, rows)));
        int startY = mapT + 50;
        Node hovered = null;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            int col = i % cols, row = i / cols;
            int cx = (int)(mapL + 21 + cellW * (col + .5) + panX);
            int cy = (int)(startY + cellH * (row + .5) + panY);
            int radius = (int)Math.round((level == 0 ? 22 : level == 1 ? 17 : 12)
                    * Math.sqrt(Math.max(.2, Math.min(8.0, zoom))));
            Point point = new Point(cx, cy, radius);
            mapPoints.put(n.id, point);
            boolean hit = Math.hypot(mx - cx, my - cy) <= radius + 8;
            if (hit) hovered = n;
            boolean active = n.id.equals(selectedNodeId);
            int color = active ? GOLD : 0xFFD5DFEA;
            // Map entities are star points, not coloured planet placeholders.
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
            if (active) {
                g.fill(cx - radius - 5, cy, cx + radius + 6, cy + 1, 0x8867D8F2);
                g.fill(cx, cy - radius - 5, cx + 1, cy + radius + 6, 0x8867D8F2);
            }
            int labelY = cy + radius + 8;
            g.drawCenteredString(font, trim(n.label, Math.max(70, (int)cellW - 12)), cx, labelY, active ? GOLD : TEXT);
            g.drawCenteredString(font, n.entries.size() + " objects", cx, labelY + 12, MUTED);
            if (hit) {
                g.fill(cx - radius - 8, cy - radius - 8, cx + radius + 9, cy - radius - 6, CYAN);
            }
        }
        return hovered;
    }

    private RouteKind routeKind(String raw, RouteKind fallback) {
        String type = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        if (type.contains("hyper") || type.contains("jump")) return RouteKind.HYPERSPACE;
        if (type.contains("warp")) return RouteKind.WARP;
        if (type.contains("wormhole") || type.contains("rift") || type.contains("portal"))
            return RouteKind.WORMHOLE;
        if (type.contains("relay")) return RouteKind.RELAY;
        if (type.contains("gate")) return RouteKind.GATE;
        if (type.contains("sublight") || type.contains("sub_light")) return RouteKind.SUBLIGHT;
        return fallback;
    }

    private void collectRouteConnections(JsonElement element, RouteKind fallback,
                                         Set<RouteConnection> routes, int depth) {
        if (element == null || element.isJsonNull() || depth > 7) return;
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                String target = primitive.getAsString().trim();
                if (!target.isBlank()) routes.add(new RouteConnection(target, fallback));
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray())
                collectRouteConnections(child, fallback, routes, depth + 1);
            return;
        }
        if (!element.isJsonObject()) return;

        JsonObject object = element.getAsJsonObject();
        RouteKind kind = fallback;
        for (String key : new String[]{"route_type", "travel_type", "drive_type", "drive",
                "mode", "type", "kind", "transport"}) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                kind = routeKind(object.get(key).getAsString(), kind);
                if (kind != fallback) break;
            }
        }
        String[] targetKeys = {"to", "target", "destination", "target_system",
                "destination_system", "system", "system_id", "destination_id",
                "target_id", "connected_system", "connected_system_id", "id"};
        boolean foundTarget = false;
        for (String key : targetKeys) {
            JsonElement target = object.get(key);
            if (target == null || !target.isJsonPrimitive()) continue;
            String value = target.getAsString().trim();
            if (!value.isBlank()) {
                routes.add(new RouteConnection(value, kind));
                foundTarget = true;
            }
        }
        if (!foundTarget) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (Set.of("from", "source", "origin", "route_type", "travel_type",
                        "drive_type", "drive", "mode", "type", "kind", "transport").contains(entry.getKey()))
                    continue;
                collectRouteConnections(entry.getValue(), kind, routes, depth + 1);
            }
        }
    }

    private Set<RouteConnection> routeConnections(Node system) {
        Set<RouteConnection> routes = new LinkedHashSet<>();
        Map<String, RouteKind> routeFields = new LinkedHashMap<>();
        routeFields.put("hyperspace_routes", RouteKind.HYPERSPACE);
        routeFields.put("hyperdrive_routes", RouteKind.HYPERSPACE);
        routeFields.put("hyperlane_routes", RouteKind.HYPERSPACE);
        routeFields.put("jump_routes", RouteKind.HYPERSPACE);
        routeFields.put("warp_routes", RouteKind.WARP);
        routeFields.put("wormhole_routes", RouteKind.WORMHOLE);
        routeFields.put("wormholes", RouteKind.WORMHOLE);
        routeFields.put("mass_relay_routes", RouteKind.RELAY);
        routeFields.put("relay_routes", RouteKind.RELAY);
        routeFields.put("jump_gate_routes", RouteKind.GATE);
        routeFields.put("gate_routes", RouteKind.GATE);
        routeFields.put("sublight_routes", RouteKind.SUBLIGHT);
        routeFields.put("travel_routes", RouteKind.UNKNOWN);
        routeFields.put("system_routes", RouteKind.UNKNOWN);
        routeFields.put("connected_systems", RouteKind.UNKNOWN);
        routeFields.put("connections", RouteKind.UNKNOWN);
        routeFields.put("routes", RouteKind.UNKNOWN);

        JsonObject metadata = GalaxyCatalog.universe(system.id);
        for (Map.Entry<String, RouteKind> field : routeFields.entrySet()) {
            if (metadata.has(field.getKey()))
                collectRouteConnections(metadata.get(field.getKey()), field.getValue(), routes, 0);
        }
        for (Entry<String, JsonObject> entry : system.entries) {
            JsonObject data = entry.getValue();
            for (Map.Entry<String, RouteKind> field : routeFields.entrySet()) {
                if (data.has(field.getKey()))
                    collectRouteConnections(data.get(field.getKey()), field.getValue(), routes, 0);
            }
        }
        return routes;
    }

    private boolean routeMatchesFilter(RouteKind kind) {
        return switch (routeFilter) {
            case 1 -> kind == RouteKind.HYPERSPACE;
            case 2 -> kind == RouteKind.WARP;
            case 3 -> kind == RouteKind.WORMHOLE;
            case 4 -> kind == RouteKind.RELAY;
            case 5 -> kind == RouteKind.GATE;
            case 6 -> kind == RouteKind.SUBLIGHT;
            case 7 -> kind == RouteKind.UNKNOWN;
            default -> true;
        };
    }

    private void drawRouteLegend(GuiGraphics g, int mouseX, int mouseY) {
        routeLegendHits.clear();
        String[] labels = {"ALL", "HYPER", "WARP", "WORM", "RELAY", "GATE", "SUB", "OTHER"};
        int[] filters = {0, 1, 2, 3, 4, 5, 6, 7};
        int x = mapL + 10, y = mapT + 8;
        int maxX = mapR - 10;
        for (int i = 0; i < labels.length; i++) {
            int w = labels[i].length() * 6 + 14;
            if (x + w > maxX && x > mapL + 10) {
                x = mapL + 10;
                y += 19;
            }
            Rect hit = new Rect(x, y, w, 16);
            routeLegendHits.add(new RouteLegendHit(filters[i], hit));
            boolean active = routeFilter == filters[i];
            boolean hovered = hit.contains(mouseX, mouseY);
            g.fill(x, y, x + w, y + 16, active ? 0xDD244A60 : hovered ? 0xCC1D3548 : 0xAA101C2A);
            g.fill(x, y, x + w, y + 1, active ? CYAN : 0x665E7D94);
            int textColor = i == 0 ? (active ? TEXT : MUTED)
                    : active || routeFilter == 0 ? switch (filters[i]) {
                        case 1 -> RouteKind.HYPERSPACE.color;
                        case 2 -> RouteKind.WARP.color;
                        case 3 -> RouteKind.WORMHOLE.color;
                        case 4 -> RouteKind.RELAY.color;
                        case 5 -> RouteKind.GATE.color;
                        case 6 -> RouteKind.SUBLIGHT.color;
                        default -> RouteKind.UNKNOWN.color;
                    } : MUTED;
            g.drawCenteredString(font, labels[i], x + w / 2, y + 4, textColor);
            x += w + 4;
        }
    }

    private void drawAtlasRoute(GuiGraphics g, Point from, Point to, RouteKind kind, int routeIndex) {
        double dx = to.x() - from.x(), dy = to.y() - from.y();
        double distance = Math.max(1.0, Math.hypot(dx, dy));
        double bend = Math.min(28.0, distance * 0.055);
        if (kind == RouteKind.WORMHOLE || kind == RouteKind.GATE) {
            double nx = -dy / distance, ny = dx / distance;
            double cx = (from.x() + to.x()) * .5 + nx * bend;
            double cy = (from.y() + to.y()) * .5 + ny * bend;
            int segments = 18;
            for (int i = 0; i < segments; i += 2) {
                double t0 = (double)i / segments, t1 = (double)(i + 1) / segments;
                double u0 = 1 - t0, u1 = 1 - t1;
                double x0 = u0*u0*from.x() + 2*u0*t0*cx + t0*t0*to.x();
                double y0 = u0*u0*from.y() + 2*u0*t0*cy + t0*t0*to.y();
                double x1 = u1*u1*from.x() + 2*u1*t1*cx + t1*t1*to.x();
                double y1 = u1*u1*from.y() + 2*u1*t1*cy + t1*t1*to.y();
                SolarSystemRenderer.line(g, (int)x0, (int)y0, (int)x1, (int)y1,
                        kind.color, kind == RouteKind.WORMHOLE ? 2 : 1);
            }
        } else {
            SolarSystemRenderer.curvedLine(g, from.x(), from.y(), to.x(), to.y(),
                    bend, kind.color, kind == RouteKind.HYPERSPACE ? 1.65f : 1.25f);
        }
        int midX = (from.x() + to.x()) / 2;
        int midY = (from.y() + to.y()) / 2;
        int marker = kind == RouteKind.RELAY ? 3 : 2;
        g.fill(midX - marker, midY - marker, midX + marker + 1, midY + marker + 1, kind.color);
    }

    private double[] atlasSourcePosition(Node system) {
        JsonObject metadata = GalaxyCatalog.universe(system.id);
        double[] position = atlasVector(metadata);
        if (position != null) return position;
        for (Entry<String, JsonObject> entry : system.entries) {
            JsonObject body = entry.getValue();
            if (!isStarNode(new Node(entry.getKey(), entry.getKey(),
                    value(body, "kind", "type"), List.of(entry)))) continue;
            position = atlasVector(body);
            if (position != null) return position;
        }
        return null;
    }

    /** Only explicit atlas/global coordinates are accepted; ordinary planet pos is system-local. */
    private double[] atlasVector(JsonObject object) {
        if (object == null) return null;
        for (String key : new String[]{"atlas_pos", "system_pos", "galaxy_pos", "universe_pos", "galactic_pos",
                "galaxy_position", "universe_position", "universe_galaxy_pos", "galaxy_map_pos", "galaxy_location"}) {
            if (!object.has(key) || object.get(key).isJsonNull()) continue;
            JsonElement element = object.get(key);
            try {
                if (element.isJsonArray() && element.getAsJsonArray().size() >= 3) {
                    JsonArray a = element.getAsJsonArray();
                    double x = a.get(0).getAsDouble(), z = a.get(2).getAsDouble();
                    if (Double.isFinite(x) && Double.isFinite(z)) return new double[]{x, z};
                } else if (element.isJsonObject()) {
                    JsonObject p = element.getAsJsonObject();
                    double x = p.has("x") ? p.get("x").getAsDouble() : Double.NaN;
                    double z = p.has("z") ? p.get("z").getAsDouble()
                            : p.has("y") ? p.get("y").getAsDouble() : Double.NaN;
                    if (Double.isFinite(x) && Double.isFinite(z)) return new double[]{x, z};
                }
            } catch (RuntimeException ignored) { }
        }
        for (String prefix : new String[]{"atlas", "galaxy", "universe", "map"}) {
            try {
                String xKey = prefix + "_x", zKey = prefix + "_z";
                if (object.has(xKey) && object.has(zKey)) {
                    double x = object.get(xKey).getAsDouble(), z = object.get(zKey).getAsDouble();
                    if (Double.isFinite(x) && Double.isFinite(z)) return new double[]{x, z};
                }
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    /** One shared starfield: each datapack system is a small orbital diagram, never a card. */
    private Node drawAtlasOverview(GuiGraphics g, int mx, int my, List<Node> nodes) {
        if (nodes.isEmpty()) {
            g.drawCenteredString(font, "NO STAR SYSTEMS IN THIS DATA GROUP",
                    (mapL + mapR) / 2, (mapT + mapB) / 2 - 8, MUTED);
            g.drawCenteredString(font, "Systems and their grouping are read from active datapacks.",
                    (mapL + mapR) / 2, (mapT + mapB) / 2 + 9, MUTED);
            return null;
        }

        int areaLeft = mapL + 28, areaTop = mapT + 34;
        int areaWidth = Math.max(1, mapR - mapL - 56);
        int areaHeight = Math.max(1, mapB - mapT - 74);
        Map<String, double[]> positions = new LinkedHashMap<>();
        int authoredCount = 0;
        for (Node system : nodes) {
            double[] source = "galaxy".equals(system.kind)
                    ? atlasGalaxySourcePosition(system) : atlasSourcePosition(system);
            if (source != null) { positions.put(system.id, source); authoredCount++; }
        }

        // When datapacks provide global coordinates, preserve their relative placement.
        // Missing positions are placed in a clearly schematic local cluster, never confused
        // with ordinary system-local planet coordinates.
        if (authoredCount > 0) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (double[] p : positions.values()) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
            }
            double span = Math.max(1.0, Math.max(maxX - minX, maxZ - minZ));
            int missing = 0;
            for (Node system : nodes) if (!positions.containsKey(system.id)) {
                double angle = missing * 2.399963229728653;
                double radius = span * (.12 + .035 * Math.sqrt(missing + 1.0));
                positions.put(system.id, new double[]{(minX + maxX) * .5 + Math.cos(angle) * radius,
                        (minZ + maxZ) * .5 + Math.sin(angle) * radius});
                missing++;
            }
        } else {
            // Deterministic schematic layout for datapacks without galactic coordinates.
            for (int i = 0; i < nodes.size(); i++) {
                double angle = i * 2.399963229728653;
                double radius = Math.sqrt(i + .5);
                positions.put(nodes.get(i).id, new double[]{Math.cos(angle) * radius,
                        Math.sin(angle) * radius});
            }
        }

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (double[] p : positions.values()) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]);
        }
        double spanX = Math.max(1.0E-6, maxX - minX);
        double spanZ = Math.max(1.0E-6, maxZ - minZ);
        double fit = Math.min(areaWidth / spanX, areaHeight / spanZ) * .88;
        double centerSourceX = (minX + maxX) * .5;
        double centerSourceZ = (minZ + maxZ) * .5;
        double centerScreenX = areaLeft + areaWidth * .5;
        double centerScreenY = areaTop + areaHeight * .5;
        double viewScale = Math.max(.025, Math.min(64.0, zoom));
        // Keep miniature systems legible across the full navigation range. The map
        // footprint grows more slowly than camera zoom so deep zoom does not turn
        // every distant system into an enormous overlapping marker.
        // One continuous camera: miniature system geometry grows with zoom rather
        // than being replaced by a different screen at each hierarchy level.
        double glyphScale = Math.max(.45, Math.min(7.0, Math.pow(viewScale, .42)));
        int glyphRadius = Math.max(7, Math.min(24,
                (int)(Math.min(areaWidth, areaHeight) / Math.sqrt(nodes.size() + 1.0) * .22)));
        glyphRadius = Math.max(5, Math.min(160, (int)Math.round(glyphRadius * glyphScale)));

        // Subtle galactic reference rings make this read as one navigable map.
        int guideRadius = Math.min(areaWidth, areaHeight) / 3;
        if (NavigatorPreferences.atlasGuides()) {
            for (int ring = 1; ring <= 3; ring++)
                SolarSystemRenderer.orbit(g, (int)centerScreenX + (int)panX,
                        (int)centerScreenY + (int)panY, guideRadius * ring / 3.0,
                        guideRadius * ring / 3.0 * .58, 0x183A6178, 1);
        }

        Map<String, Point> previousSectorPoint = new HashMap<>();
        Map<String, Point> screenPositions = new LinkedHashMap<>();
        Map<String, List<double[]>> territoryPoints = new LinkedHashMap<>();
        for (Node system : nodes) {
            double[] p = positions.get(system.id);
            double baseX = centerScreenX + (p[0] - centerSourceX) * fit;
            double baseY = centerScreenY + (p[1] - centerSourceZ) * fit;
            int cx = (int)Math.round(centerScreenX + (baseX - centerScreenX) * viewScale + panX);
            int cy = (int)Math.round(centerScreenY + (baseY - centerScreenY) * viewScale + panY);
            screenPositions.put(system.id, new Point(cx, cy, glyphRadius));
            String territory = metadataLabel(system, "sector_id", "sector_name", "sector");
            if (!territory.isBlank())
                territoryPoints.computeIfAbsent(territory, ignored -> new ArrayList<>())
                        .add(new double[]{cx, cy});
        }
        // Sector territories are rendered behind routes and system markers.
        if (NavigatorPreferences.atlasSectors()) {
            for (Map.Entry<String, List<double[]>> entry : territoryPoints.entrySet()) {
                int tint = 0x704B9BC2 | (entry.getKey().hashCode() & 0x001F1F1F);
                SolarSystemRenderer.territory(g, entry.getValue(),
                        Math.max(18.0, glyphRadius * 1.15), tint);
            }
        }
        if (NavigatorPreferences.atlasSectorLinks()) {
            for (Node system : nodes) {
                String sector = metadataLabel(system, "sector_id", "sector_name", "sector");
                Point point = screenPositions.get(system.id);
                if (sector.isBlank() || point == null) continue;
                Point previous = previousSectorPoint.put(sector, point);
                if (previous != null) SolarSystemRenderer.curvedLine(g,
                        previous.x(), previous.y(), point.x(), point.y(),
                        Math.min(18.0, Math.hypot(point.x() - previous.x(),
                                point.y() - previous.y()) * 0.035),
                        0x553E8EAA, 0.9f);
            }
        }
        if (NavigatorPreferences.atlasRoutes()) {
            drawRouteLegend(g, mx, my);
            Map<String, Node> lookup = new HashMap<>();
            for (Node system : nodes) {
                lookup.put(system.id.toLowerCase(Locale.ROOT), system);
                lookup.put(system.label.toLowerCase(Locale.ROOT), system);
            }
            Set<String> drawnRoutes = new HashSet<>();
            for (Node system : nodes) {
                Point from = screenPositions.get(system.id);
                if (from == null) continue;
                for (RouteConnection route : routeConnections(system)) {
                    if (!routeMatchesFilter(route.kind())) continue;
                    Node destination = lookup.get(route.target().toLowerCase(Locale.ROOT));
                    if (destination == null) continue;
                    Point to = screenPositions.get(destination.id);
                    if (to == null || destination.id.equals(system.id)) continue;
                    String a = system.id.compareToIgnoreCase(destination.id) <= 0
                            ? system.id : destination.id;
                    String b = system.id.compareToIgnoreCase(destination.id) <= 0
                            ? destination.id : system.id;
                    String key = a + "|" + b + "|" + route.kind().name();
                    if (drawnRoutes.add(key)) drawAtlasRoute(g, from, to, route.kind(), drawnRoutes.size());
                }
            }
        } else routeLegendHits.clear();

        Node hovered = null;
        for (Node system : nodes) {
            double[] p = positions.get(system.id);
            double baseX = centerScreenX + (p[0] - centerSourceX) * fit;
            double baseY = centerScreenY + (p[1] - centerSourceZ) * fit;
            int cx = (int)Math.round(centerScreenX + (baseX - centerScreenX) * viewScale + panX);
            int cy = (int)Math.round(centerScreenY + (baseY - centerScreenY) * viewScale + panY);
            boolean active = system.id.equals(selectedNodeId) || system.id.equals(universe);
            boolean clusterNode = "galaxy".equals(system.kind) || "sector".equals(system.kind);
            int radius = clusterNode ? glyphRadius
                    : Math.max(30, Math.min(180, (int)Math.round(glyphRadius * 2.25)));
            int cullRadius = clusterNode ? Math.max(24, Math.min(190, glyphRadius + 16)) : radius;
            // Keep off-screen nodes addressable for focus/search, but do not build
            // their expensive miniature solar systems until they enter the viewport.
            mapPoints.put(system.id, new Point(cx, cy, cullRadius + 5));
            int overscan = Math.max(220, Math.min(720, cullRadius * 2 + 100));
            if (cx < mapL - overscan || cx > mapR + overscan
                    || cy < mapT - overscan || cy > mapB + overscan) continue;
            if ("galaxy".equals(system.kind) || "sector".equals(system.kind)) {
                int galaxyRadius = Math.max(24, Math.min(190, glyphRadius + 16));
                boolean activeGalaxy = system.id.equals(galaxyPath) || system.id.equals(sectorPath)
                        || system.id.equals(selectedNodeId);
                Node hoveredMiniature = drawSystemClusterGlyph(g, cx, cy, galaxyRadius,
                        system.id.hashCode(), activeGalaxy ? GOLD : 0xFF78B9D4,
                        system, "galaxy".equals(system.kind), mx, my);
                boolean hit = Math.hypot(mx - cx, my - cy) <= galaxyRadius + 10;
                if (hoveredMiniature != null) hovered = hoveredMiniature;
                else if (hit) hovered = system;
                if (NavigatorPreferences.atlasLabels())
                    g.drawCenteredString(font, trim(system.label, Math.max(70, galaxyRadius * 5)),
                            cx, cy + galaxyRadius + 8, activeGalaxy ? GOLD : TEXT);
                int bodyCount = 0;
                for (Entry<String, JsonObject> systemRoot : system.entries)
                    bodyCount += GalaxyCatalog.objects(systemRoot.getKey()).size();
                g.drawCenteredString(font, system.entries.size() + " SYSTEMS · " + bodyCount + " BODIES",
                        cx, cy + galaxyRadius + 20, MUTED);
                if ("sector".equals(system.kind) && NavigatorPreferences.atlasSectors())
                    g.drawCenteredString(font, "SECTOR", cx, cy - galaxyRadius - 10, 0xFF83A6B9);
                continue;
            }

            List<Node> primaryBodies = new ArrayList<>();
            List<Node> moons = new ArrayList<>();
            Map<String, Node> primaryById = new HashMap<>();
            Map<String, Node> primaryByDimension = new HashMap<>();
            Set<String> ambiguousPrimaryDimensions = new HashSet<>();
            Node star = null;
            for (Entry<String, JsonObject> entry : system.entries) {
                JsonObject data = entry.getValue();
                String kind = value(data, "kind", "display_type", "type", "classification")
                        .toLowerCase(Locale.ROOT);
                Node body = new Node(entry.getKey(), value(data, "name", entry.getKey()),
                        kind.isBlank() ? "planet" : kind, List.of(entry));
                if (isStarNode(body)) {
                    if (star == null || (!hasTextureId(star.first()) && hasTextureId(body.first()))) star = body;
                } else if (kind.contains("moon") || kind.contains("satellite")) {
                    moons.add(body);
                } else {
                    primaryBodies.add(body);
                    primaryById.put(body.id, body);
                    String catalogId = value(data, "id", "catalog_id");
                    if (!catalogId.isBlank()) primaryById.put(catalogId, body);
                    String dimensionId = value(data, "dimension", "dimension_id", "attached_dimension_id");
                    if (!dimensionId.isBlank()) {
                        if (primaryByDimension.putIfAbsent(dimensionId, body) != null)
                            ambiguousPrimaryDimensions.add(dimensionId);
                    }
                }
            }

            // Render every primary body on its stellar orbit, then render moons
            // around the parent planet resolved from explicit catalogue metadata.
            double sceneTime = minecraft != null && minecraft.level != null
                    ? minecraft.level.getGameTime() + minecraft.getFrameTime() : 0.0;
            double[] starPosition = star == null ? new double[]{0, 0, 0}
                    : atlasPosition(system.id, star.first(), sceneTime);
            Map<String, double[]> bodyPositions = new HashMap<>();
            Map<String, Point> bodyScreenPositions = new HashMap<>();
            double maxOrbitDistance = 0.0;
            for (Node body : primaryBodies) {
                double[] position = atlasPosition(system.id, body.first(), sceneTime);
                bodyPositions.put(body.id, position);
                maxOrbitDistance = Math.max(maxOrbitDistance, Math.hypot(
                        position[0] - starPosition[0], position[2] - starPosition[2]));
            }
            boolean authoredOrbitalGeometry = maxOrbitDistance > 1.0E-6
                    && Double.isFinite(maxOrbitDistance);
            int bodyRadius = Math.max(6, Math.min(15,
                    (int)Math.round(radius / (NavigatorPreferences.atlasDetail() == 2 ? 7.0 : 8.0)
                            * NavigatorPreferences.atlasScale())));
            for (int i = 0; i < primaryBodies.size(); i++) {
                Node body = primaryBodies.get(i);
                double[] position = bodyPositions.get(body.id);
                double dx = position[0] - starPosition[0];
                double dz = position[2] - starPosition[2];
                double distance = Math.hypot(dx, dz);
                double orbit = authoredOrbitalGeometry
                        ? radius * (.24 + .66 * distance / maxOrbitDistance)
                        : radius * (.28 + .14 * i);
                orbit *= NavigatorPreferences.atlasOrbitSpacing();
                double orbitY = orbit * .68;
                if (NavigatorPreferences.atlasOrbits())
                    SolarSystemRenderer.orbit(g, cx, cy, orbit, orbitY,
                            active ? 0x886F91C8 : 0x506B789B, 1);
                double angle = authoredOrbitalGeometry
                        ? Math.atan2(dz, dx) : i * 2.399963229728653;
                int px = cx + (int)Math.round(Math.cos(angle) * orbit);
                int py = cy + (int)Math.round(Math.sin(angle) * orbitY);
                bodyScreenPositions.put(body.id, new Point(px, py, bodyRadius));
                drawAtlasBodyVisual(g, px, py, bodyRadius, body.first(), kindColor(body.kind));
                String qualifiedBodyId = system.id + "::" + body.id;
                mapPoints.put(qualifiedBodyId, new Point(px, py, bodyRadius + 5));
                if (qualifiedBodyId.equals(selectedNodeId) || qualifiedBodyId.equals(selectedId)) {
                    SolarSystemRenderer.orbit(g, px, py, bodyRadius + 7, (bodyRadius + 7) * .72,
                            0xCC69CDE2, 1);
                }
                if (Math.hypot(mx - px, my - py) <= bodyRadius + 5) {
                    hovered = new Node(qualifiedBodyId, body.label, "atlas_body:" + body.kind,
                            body.entries);
                }
            }
            for (int moonIndex = 0; moonIndex < moons.size(); moonIndex++) {
                Node moon = moons.get(moonIndex);
                String parentId = orbitalParentReference(moon.first());
                Node parent = parentId.isBlank() ? null : primaryById.get(parentId);
                if (parent == null && !parentId.isBlank()
                        && !ambiguousPrimaryDimensions.contains(parentId))
                    parent = primaryByDimension.get(parentId);
                if (parent == null && parentId.isBlank() && primaryBodies.size() == 1
                        && primaryBodies.get(0).kind.toLowerCase(Locale.ROOT).contains("planet"))
                    parent = primaryBodies.get(0);
                if (parent == null) continue;
                Point parentPoint = bodyScreenPositions.get(parent.id);
                double[] moonPosition = atlasPosition(system.id, moon.first(), sceneTime);
                double[] parentPosition = bodyPositions.get(parent.id);
                if (parentPoint == null || parentPosition == null) continue;
                double mdx = moonPosition[0] - parentPosition[0];
                double mdz = moonPosition[2] - parentPosition[2];
                double moonDistance = Math.hypot(mdx, mdz);
                double moonOrbit = Math.max(3.0, Math.min(Math.max(4.0, radius * .22),
                        moonDistance > 1.0E-6 ? radius * (.07 + .12 * moonDistance /
                                Math.max(1.0, maxOrbitDistance)) : radius * .12));
                double moonAngle = moonDistance > 1.0E-6 ? Math.atan2(mdz, mdx)
                        : moonIndex * 2.399963229728653;
                if (NavigatorPreferences.atlasOrbits())
                    SolarSystemRenderer.orbit(g, parentPoint.x(), parentPoint.y(),
                            moonOrbit, moonOrbit * .68, 0x668CA8B8, 1);
                int mxp = parentPoint.x() + (int)Math.round(Math.cos(moonAngle) * moonOrbit);
                int myp = parentPoint.y() + (int)Math.round(Math.sin(moonAngle) * moonOrbit * .68);
                int moonRadius = Math.max(3, bodyRadius - 3);
                drawAtlasBodyVisual(g, mxp, myp, moonRadius, moon.first(), kindColor("moon"));
                String qualifiedMoonId = system.id + "::" + moon.id;
                mapPoints.put(qualifiedMoonId, new Point(mxp, myp, moonRadius + 5));
                if (qualifiedMoonId.equals(selectedNodeId) || qualifiedMoonId.equals(selectedId)) {
                    SolarSystemRenderer.orbit(g, mxp, myp, moonRadius + 7, (moonRadius + 7) * .72,
                            0xCC69CDE2, 1);
                }
                if (Math.hypot(mx - mxp, my - myp) <= moonRadius + 5) {
                    hovered = new Node(qualifiedMoonId, moon.label, "atlas_body:moon", moon.entries);
                }
            }
            if (star != null) {
                int starRadius = Math.max(7, Math.min(13,
                        (int)Math.round(radius / 4.5 * NavigatorPreferences.atlasScale())));
                drawAtlasBodyVisual(g, cx, cy, starRadius, star.first(), kindColor("star"));
                String qualifiedStarId = system.id + "::" + star.id;
                mapPoints.put(qualifiedStarId, new Point(cx, cy, starRadius + 6));
                if (qualifiedStarId.equals(selectedNodeId) || qualifiedStarId.equals(selectedId)) {
                    SolarSystemRenderer.orbit(g, cx, cy, starRadius + 9, (starRadius + 9) * .72,
                            0xCC69CDE2, 1);
                }
                if (Math.hypot(mx - cx, my - cy) <= starRadius + 6) {
                    hovered = new Node(qualifiedStarId, star.label, "atlas_body:star", star.entries);
                }
            } else {
                drawRadialGlow(g, cx, cy, Math.max(8, radius / 2), 0x66E8B9FF, 0xFFE8D8FF);
                drawDisc(g, cx, cy, 5, 0xFFFFF2D6, 0xFFFFDCA0);
            }
            if (active) {
                SolarSystemRenderer.orbit(g, cx, cy, radius + 4, (radius + 4) * .68,
                        0x887E9BCB, 1);
            }

            int hitRadius = Math.max(14, radius + 7);
            boolean hit = Math.hypot(mx - cx, my - cy) <= hitRadius + 5;
            if (hit && hovered == null) hovered = system;
            if (NavigatorPreferences.atlasLabels())
                g.drawCenteredString(font, trim(system.label, Math.max(48, radius * 7)),
                        cx, cy + radius + 7, active ? GOLD : TEXT);
            String sector = metadataLabel(system, "sector_name", "sector", "sector_id");
            int detailY = cy + radius + (NavigatorPreferences.atlasLabels() ? 18 : 7);
            if (NavigatorPreferences.atlasSectors() && !sector.isBlank() && radius >= 10) {
                g.drawCenteredString(font, trim(sector, Math.max(48, radius * 7)), cx, detailY, MUTED);
                detailY += 11;
            }
            if (NavigatorPreferences.atlasPacks() && radius >= 10) {
                Set<String> packNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Entry<String, JsonObject> body : system.entries) {
                    String pack = value(body.getValue(), "pack", "source_pack", "datapack");
                    if (!pack.isBlank()) packNames.add(pack);
                }
                if (!packNames.isEmpty()) {
                    String packLabel = packNames.size() == 1 ? packNames.iterator().next()
                            : packNames.size() + " DATA PACKS";
                    g.drawCenteredString(font, trim(packLabel, Math.max(48, radius * 7)),
                            cx, detailY, 0xFF83A6B9);
                }
            }
        }
        String placement = authoredCount == nodes.size() ? "DATAPACK COORDINATES"
                : authoredCount > 0 ? "DATAPACK COORDINATES + SCHEMATIC UNPLACED " +
                        ("galaxy".equals(nodes.get(0).kind) ? "GALAXIES" : "SYSTEMS")
                : "SCHEMATIC LAYOUT · NO " +
                        ("galaxy".equals(nodes.get(0).kind) ? "UNIVERSE GALAXY" : "GALACTIC") +
                        " COORDINATES IN DATAPACK";
        placement += " · " + authoredCount + "/" + nodes.size() + " PLACED";
        g.drawString(font, trim(placement, mapR - mapL - 20),
                mapL + 10, mapB - 15, MUTED, false);
        return hovered;
    }

    /** Sample a body from the shared Cosmic Horizons/MAQAN orbital authority. */
    private double[] atlasPosition(String systemId, JsonObject body, double gameTime) {
        JsonObject system = GalaxyCatalog.universe(systemId);
        return new double[]{
                NativeOrbitalSimulation.coordinate(systemId, system, body, 0, gameTime),
                NativeOrbitalSimulation.coordinate(systemId, system, body, 1, gameTime),
                NativeOrbitalSimulation.coordinate(systemId, system, body, 2, gameTime)
        };
    }

    /** Resolve explicit positions of galaxies inside a universe; never reuse local system coordinates. */
    private double[] atlasGalaxySourcePosition(Node galaxy) {
        for (Entry<String, JsonObject> entry : galaxy.entries) {
            double[] position = galaxyCoordinate(entry.getValue());
            if (position != null) return position;
            // The scanner preserves GUI-body metadata on the body. Accept a galaxy
            // coordinate authored there as well as one authored on the system root.
            for (Entry<String, JsonObject> body : GalaxyCatalog.objects(entry.getKey())) {
                position = galaxyCoordinate(body.getValue());
                if (position != null) return position;
            }
        }
        return null;
    }

    private double[] galaxyCoordinate(JsonObject source) {
        if (source == null) return null;
        for (String key : new String[]{"universe_galaxy_pos", "galaxy_map_pos", "galaxy_location"}) {
            if (!source.has(key) || source.get(key).isJsonNull()) continue;
            JsonObject wrapper = new JsonObject();
            wrapper.add(key, source.get(key));
            double[] position = atlasVector(wrapper);
            if (position != null) return position;
        }
        return null;
    }

    /**
     * Galaxy backdrop with miniature previews of real datapack systems.
     * Every visible body uses the same Cosmic Horizons GUI texture renderer as system view.
     */
    private Node drawSystemClusterGlyph(GuiGraphics g, int cx, int cy, int radius, int seed,
                                        int color, Node galaxy, boolean galaxyArtwork,
                                        int mouseX, int mouseY) {
        Node hoveredSystem = null;
        Node hoveredBody = null;
        if (galaxyArtwork) {
            SolarSystemRenderer.orbit(g, cx, cy, radius * 1.08, radius * .58, 0x285D658E, 1);
            SolarSystemRenderer.orbit(g, cx, cy, radius * .72, radius * .38, 0x355D658E, 1);
        } else {
            SolarSystemRenderer.orbit(g, cx, cy, radius * 1.05, radius * .72, 0x405F9DB8, 1);
            SolarSystemRenderer.orbit(g, cx, cy, radius * .76, radius * .52, 0x304F89A5, 1);
        }
        double rotation = (seed & 0x7fffffff) * 0.000013;
        if (galaxyArtwork) for (int arm = 0; arm < 3; arm++) {
            double phase = rotation + arm * Math.PI * 2.0 / 3.0;
            int previousX = cx, previousY = cy;
            for (int step = 1; step <= 34; step++) {
                double t = step / 34.0;
                double angle = phase + t * Math.PI * 2.25;
                double distance = radius * (.10 + .88 * t);
                int x = cx + (int)Math.round(Math.cos(angle) * distance);
                int y = cy + (int)Math.round(Math.sin(angle) * distance * .52);
                SolarSystemRenderer.line(g, previousX, previousY, x, y,
                        step < 10 ? 0x667AB7D0 : color & 0x88FFFFFF, 1);
                previousX = x; previousY = y;
            }
        }
        int detail = NavigatorPreferences.atlasDetail();
        // Never truncate a galaxy/sector to a handful of preview systems. LOD
        // changes the size of each miniature, not which datapack systems exist.
        int count = galaxy.entries.size();
        double perSystemBudget = Math.sqrt(Math.max(1.0,
                radius * radius / Math.max(1.0, count * 1.35)));
        for (int i = 0; i < count; i++) {
            Entry<String, JsonObject> systemEntry = galaxy.entries.get(i);
            List<Entry<String, JsonObject>> bodies = GalaxyCatalog.objects(systemEntry.getKey());
            if (bodies.isEmpty()) continue;
            double t = (i + .5) / Math.max(1.0, count);
            double angle = rotation + i * 2.399963229728653;
            double distance = radius * (.12 + .70 * Math.sqrt(t));
            int sx = cx + (int)Math.round(Math.cos(angle) * distance);
            int sy = cy + (int)Math.round(Math.sin(angle) * distance * .52);
            List<Entry<String, JsonObject>> systemBodies = GalaxyCatalog.objects(systemEntry.getKey());
            Node systemNode = new Node(systemEntry.getKey(),
                    GalaxyCatalog.text(systemEntry.getValue(), "name", systemEntry.getKey()),
                    "star system", systemBodies);
            int systemHitRadius = Math.max(5, Math.min(12, (int)Math.round(perSystemBudget * .42)));
            mapPoints.put(systemNode.id, new Point(sx, sy, systemHitRadius));
            int miniOrbit = Math.max(3, Math.min(116, (int)Math.round(
                    Math.min(radius * .46, perSystemBudget * (detail == 0 ? .75 : 1.15))
                            * NavigatorPreferences.atlasOrbitSpacing())));
            double bodyLodScale = Math.max(.8, Math.min(2.8, Math.pow(Math.max(.025, zoom), .24)));
            JsonObject star = null;
            String starBodyId = null;
            List<Entry<String, JsonObject>> planets = new ArrayList<>();
            List<Entry<String, JsonObject>> moons = new ArrayList<>();
            Map<String, Entry<String, JsonObject>> planetById = new HashMap<>();
            Map<String, Entry<String, JsonObject>> planetByDimension = new HashMap<>();
            Set<String> ambiguousPlanetDimensions = new HashSet<>();
            for (Entry<String, JsonObject> body : bodies) {
                JsonObject data = body.getValue();
                String kind = value(data, "kind", "display_type", "type", "classification")
                        .toLowerCase(Locale.ROOT);
                if (isStarData(data) || kind.contains("star")) {
                    if (star == null || (!hasTextureId(star) && hasTextureId(data))) {
                        star = data;
                        starBodyId = body.getKey();
                    }
                } else if (kind.contains("moon") || kind.contains("satellite")) {
                    moons.add(body);
                } else {
                    // Keep planets and other primary orbiting bodies distinct from moons.
                    planets.add(body);
                    planetById.put(body.getKey(), body);
                    String catalogId = value(data, "id", "catalog_id");
                    if (!catalogId.isBlank()) planetById.put(catalogId, body);
                    String dimensionId = value(data, "dimension", "dimension_id", "attached_dimension_id");
                    if (!dimensionId.isBlank()) {
                        if (planetByDimension.putIfAbsent(dimensionId, body) != null)
                            ambiguousPlanetDimensions.add(dimensionId);
                    }
                }
            }
            if (star != null) {
                int starRadius = Math.max(2, Math.min(14, (int)Math.round(
                        Math.min(10.0, perSystemBudget * .22) * NavigatorPreferences.atlasScale()
                                * bodyLodScale)));
                drawAtlasBodyVisual(g, sx, sy, starRadius, star, kindColor("star"));
                // Cosmic Horizons stars are selectable catalogue entities, not decorative
                // center lights. Their qualified IDs remain unique across the global scene.
                if (starBodyId != null) {
                    String qualifiedStarId = systemEntry.getKey() + "::" + starBodyId;
                    mapPoints.put(qualifiedStarId, new Point(sx, sy, starRadius + 5));
                    if (Math.hypot(mouseX - sx, mouseY - sy) <= starRadius + 5) {
                        hoveredBody = new Node(qualifiedStarId,
                                value(star, "name", starBodyId), "atlas_body:star",
                                List.of(new AbstractMap.SimpleImmutableEntry<>(starBodyId, star)));
                    }
                    if (qualifiedStarId.equals(selectedNodeId)) {
                        SolarSystemRenderer.orbit(g, sx, sy, starRadius + 8,
                                (starRadius + 8) * .72, 0xCC69CDE2, 1);
                    }
                }
            } else {
                drawRadialGlow(g, sx, sy, 7, 0x558EA6D8, 0xFFE7EAF5);
                drawDisc(g, sx, sy, 4, 0xFFFFF4E0, 0xFFE2D9F2);
            }
            double sceneTime = minecraft != null && minecraft.level != null
                    ? minecraft.level.getGameTime() + minecraft.getFrameTime() : 0.0;
            double[] starPosition = star == null ? new double[]{0, 0, 0}
                    : atlasPosition(systemEntry.getKey(), star, sceneTime);
            Map<String, double[]> planetPositions = new HashMap<>();
            Map<String, Point> planetScreenPositions = new HashMap<>();
            double maxDistance = 0.0;
            for (Entry<String, JsonObject> planet : planets) {
                double[] p = atlasPosition(systemEntry.getKey(), planet.getValue(), sceneTime);
                planetPositions.put(planet.getKey(), p);
                maxDistance = Math.max(maxDistance, Math.hypot(
                        p[0] - starPosition[0], p[2] - starPosition[2]));
            }
            boolean hasOrbitalData = maxDistance > 1.0E-6 && Double.isFinite(maxDistance);
            int bodyRadius = Math.max(2, Math.min(12, (int)Math.round(
                    Math.min(8.0, perSystemBudget * .18) * NavigatorPreferences.atlasScale()
                            * bodyLodScale)));
            for (int planet = 0; planet < planets.size(); planet++) {
                Entry<String, JsonObject> entry = planets.get(planet);
                JsonObject data = entry.getValue();
                double[] p = planetPositions.get(entry.getKey());
                double dx = p[0] - starPosition[0], dz = p[2] - starPosition[2];
                double orbitalDistance = Math.hypot(dx, dz);
                double orbit = hasOrbitalData
                        ? miniOrbit * (.28 + .68 * orbitalDistance / maxDistance)
                        : miniOrbit * (.34 + .105 * planet);
                double planetAngle = hasOrbitalData ? Math.atan2(dz, dx)
                        : planet * 2.399963229728653;
                if (NavigatorPreferences.atlasOrbits() && orbit >= 2.0)
                    SolarSystemRenderer.orbit(g, sx, sy, orbit, orbit * .68, 0x557E789E, 1);
                int px = sx + (int)Math.round(Math.cos(planetAngle) * orbit);
                int py = sy + (int)Math.round(Math.sin(planetAngle) * orbit * .68);
                planetScreenPositions.put(entry.getKey(), new Point(px, py, bodyRadius));
                String qualifiedPlanetId = systemEntry.getKey() + "::" + entry.getKey();
                mapPoints.put(qualifiedPlanetId, new Point(px, py, bodyRadius + 4));
                drawAtlasBodyVisual(g, px, py, bodyRadius, data,
                        bodyFallbackColor(data, kindColor("planet")));
                if (qualifiedPlanetId.equals(selectedNodeId) || qualifiedPlanetId.equals(selectedId)) {
                    SolarSystemRenderer.orbit(g, px, py, bodyRadius + 5, (bodyRadius + 5) * .72,
                            0xCC69CDE2, 1);
                }
                if (Math.hypot(mouseX - px, mouseY - py) <= bodyRadius + 5) {
                    String bodyKind = isStarData(data) ? "star"
                            : value(data, "kind", "display_type", "type", "classification")
                                    .toLowerCase(Locale.ROOT);
                    hoveredBody = new Node(systemEntry.getKey() + "::" + entry.getKey(),
                            value(data, "name", entry.getKey()), "atlas_body:" + bodyKind,
                            List.of(entry));
                }
            }
            // Moons orbit their actual parent planet, not the system star. If a
            // datapack omits parent metadata, use the only unambiguous planet only.
            for (int moonIndex = 0; moonIndex < moons.size(); moonIndex++) {
                Entry<String, JsonObject> moon = moons.get(moonIndex);
                String parentId = orbitalParentReference(moon.getValue());
                Entry<String, JsonObject> parent = parentId.isBlank() ? null : planetById.get(parentId);
                if (parent == null && !parentId.isBlank()
                        && !ambiguousPlanetDimensions.contains(parentId))
                    parent = planetByDimension.get(parentId);
                if (parent == null && parentId.isBlank() && planets.size() == 1) {
                    JsonObject onlyBody = planets.get(0).getValue();
                    String onlyKind = value(onlyBody, "kind", "display_type", "type", "classification")
                            .toLowerCase(Locale.ROOT);
                    if (onlyKind.isBlank() || onlyKind.contains("planet")) parent = planets.get(0);
                }
                if (parent == null) continue;
                Point parentPoint = planetScreenPositions.get(parent.getKey());
                if (parentPoint == null) continue;
                double[] moonPosition = atlasPosition(systemEntry.getKey(), moon.getValue(), sceneTime);
                double[] parentPosition = planetPositions.get(parent.getKey());
                double mdx = moonPosition[0] - parentPosition[0], mdz = moonPosition[2] - parentPosition[2];
                double moonDistance = Math.hypot(mdx, mdz);
                double moonOrbit = Math.max(3.0, Math.min(Math.max(4.0, miniOrbit * .22),
                        moonDistance > 1.0E-6 ? miniOrbit * (.08 + .12 * moonDistance /
                                Math.max(1.0, maxDistance)) : miniOrbit * .12));
                double moonAngle = moonDistance > 1.0E-6 ? Math.atan2(mdz, mdx)
                        : moonIndex * 2.399963229728653;
                if (NavigatorPreferences.atlasOrbits())
                    SolarSystemRenderer.orbit(g, parentPoint.x(), parentPoint.y(),
                            moonOrbit, moonOrbit * .68, 0x668CA8B8, 1);
                int mx = parentPoint.x() + (int)Math.round(Math.cos(moonAngle) * moonOrbit);
                int my = parentPoint.y() + (int)Math.round(Math.sin(moonAngle) * moonOrbit * .68);
                int moonRadius = Math.max(2, Math.min(7, bodyRadius - 1));
                String qualifiedMoonId = systemEntry.getKey() + "::" + moon.getKey();
                mapPoints.put(qualifiedMoonId, new Point(mx, my, moonRadius + 4));
                drawAtlasBodyVisual(g, mx, my, moonRadius, moon.getValue(),
                        bodyFallbackColor(moon.getValue(), kindColor("moon")));
                if (qualifiedMoonId.equals(selectedNodeId) || qualifiedMoonId.equals(selectedId)) {
                    SolarSystemRenderer.orbit(g, mx, my, moonRadius + 5, (moonRadius + 5) * .72,
                            0xCC69CDE2, 1);
                }
                if (Math.hypot(mouseX - mx, mouseY - my) <= moonRadius + 5)
                    hoveredBody = new Node(systemEntry.getKey() + "::" + moon.getKey(),
                            value(moon.getValue(), "name", moon.getKey()), "atlas_body:moon",
                            List.of(moon));
            }
            if (detail == 2 && radius >= 48 && count <= 18) {
                String systemName = GalaxyCatalog.text(systemEntry.getValue(), "name", systemEntry.getKey());
                g.drawCenteredString(font, trim(systemName, 62), sx, sy + miniOrbit + 7, 0xFFD5E4EF);
            }
            if (Math.hypot(mouseX - sx, mouseY - sy) <= systemHitRadius + 3)
                hoveredSystem = systemNode;
        }
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFE8B8);
        return hoveredBody != null ? hoveredBody : hoveredSystem;
    }

    /** Dedicated close-up planet scene with a large rotating body and source-positioned satellites. */
    private Node drawPlanetExplorer(GuiGraphics g, int mx, int my, List<Node> nodes) {
        if (nodes.isEmpty()) return null;
        Node primary = nodes.get(0);
        JsonObject data = primary.first();
        double ponderScale = numeric(data, "ponder_scale", 60.0);
        double sourceScale = Math.max(0.45, Math.min(1.55, ponderScale / 60.0));
        int baseRadius = Math.max(22, Math.min(68, Math.min(mapR - mapL, mapB - mapT) / 5));

        // Reserve enough room for the cube's full 3D diagonal and any native
        // Cosmic Horizons ring mesh, not just the cube's unrotated half-width.
        // A conservative 2x radius envelope includes every projected cube
        // corner plus small transform/GUI rounding differences. This avoids
        // edge clipping in narrow or short workspaces.
        double extentFactor = 2.0;
        double bodyScale = numeric(data, "scale", 0.0);
        if (bodyScale > 0.0 && data.has("ring_data") && data.get("ring_data").isJsonObject()) {
            for (com.google.gson.JsonElement element : data.getAsJsonObject("ring_data").entrySet()
                    .stream().map(Map.Entry::getValue).toList()) {
                if (!element.isJsonObject()) continue;
                double ringScale = numeric(element.getAsJsonObject(), "scale_radius", 0.0);
                if (ringScale > 0.0 && Double.isFinite(ringScale))
                    extentFactor = Math.max(extentFactor, Math.sqrt(2.0) * ringScale / bodyScale);
            }
        }

        int contentTop = mapT + 40;
        int contentBottom = mapB - 30;
        // Keep a small additional safety margin for the cube's rotated corners
        // and the ring mesh. visualExtent adds three pixels to the geometric bound.
        double availableWidth = Math.max(1, mapR - mapL - 56);
        double availableHeight = Math.max(1, contentBottom - contentTop - 24);
        int fitRadius = Math.max(3, (int)Math.floor(
                Math.min(availableWidth, availableHeight) / (2.0 * extentFactor)));
        int requestedRadius = (int)Math.round(baseRadius * zoom * sourceScale);
        // Permit deliberate close-up zoom beyond the initial fit. The map remains
        // pannable so users can inspect a large body without changing its renderer.
        int planetRadius = Math.max(3, Math.min(fitRadius * 3, requestedRadius));
        int visualExtent = (int)Math.ceil(planetRadius * extentFactor) + 3;

        int panelCenterX = (mapL + mapR) / 2;
        int contentCenterY = (contentTop + contentBottom) / 2;
        int minCenterX = mapL + 12 + visualExtent;
        int maxCenterX = mapR - 12 - visualExtent;
        int minCenterY = contentTop + visualExtent;
        int maxCenterY = contentBottom - visualExtent;
        if (minCenterX > maxCenterX) {
            minCenterX = mapL + 12 + Math.min(visualExtent, (mapR - mapL) / 4);
            maxCenterX = mapR - 12 - Math.min(visualExtent, (mapR - mapL) / 4);
        }
        if (minCenterY > maxCenterY) {
            minCenterY = contentTop + Math.min(visualExtent, (contentBottom - contentTop) / 4);
            maxCenterY = contentBottom - Math.min(visualExtent, (contentBottom - contentTop) / 4);
        }
        int cx = Math.max(minCenterX, Math.min(maxCenterX, panelCenterX + (int)Math.round(panX)));
        int cy = Math.max(minCenterY, Math.min(maxCenterY, contentCenterY + (int)Math.round(panY)));

        boolean hasAtmosphere = hasAtmosphere(data);
        if (hasAtmosphere) {
            int atmosphere = parseCosmicColor(data, "atmosphere_color", 0);
            if ((atmosphere & 0x00FFFFFF) != 0)
                drawAtmosphereHalo(g, cx, cy, planetRadius, atmosphere);
        }
        // Cosmic Horizons' original GUI renderer places its 3D meshes at depth +1000.
        // This keeps the rotating cube and its ring plane inside the GUI depth range.
        bodyRotationHitRadius = (int)Math.ceil(planetRadius * 2.0) + 8;
        // Rings are a background layer; render them first so the opaque
        // cube correctly covers the inner portions of the ring plane.
        drawBodyRing(g, cx, cy, planetRadius, data, 1000.0f,
                (float)bodyYawOffset, (float)bodyPitchOffset);
        boolean rendered = CosmicBodyGuiRenderer.render(g, cx, cy, planetRadius, data,
                1000.0f, (float)bodyYawOffset, (float)bodyPitchOffset);
        if (!rendered) {
            g.drawCenteredString(font, "TEXTURE NOT AVAILABLE",
                    cx, cy - 4, MUTED);
            g.drawCenteredString(font, value(data, "texture_id").isEmpty()
                            ? "Cosmic Horizons has no texture_id for this object"
                            : "Missing resource: " + trim(value(data, "texture_id"), 64),
                    cx, cy + 10, MUTED);
        }
        g.drawCenteredString(font, trim(primary.label, mapR - mapL - 24),
                cx, cy + visualExtent + 12, GOLD);
        g.drawCenteredString(font, "LEFT-DRAG TO ROTATE · SCROLL TO ZOOM",
                cx, mapT + 26, CYAN);

        // Show only direct children of the selected body. Grandchildren
        // belong to their own parent and must not be drawn on the wrong orbit.
        List<Node> satellites = new ArrayList<>();
        for (Node candidate : nodes) {
            if (candidate == primary) continue;
            Node parent = resolveParent(nodes, candidate);
            if (parent != null && parent.id.equals(primary.id)) satellites.add(candidate);
        }
        boolean primaryHasPosition = hasPosition(data) ||
                (isStarNode(primary) && nodes.stream().filter(this::isStarNode).count() == 1);
        List<Node> located = new ArrayList<>();
        Node satelliteHovered = null;
        for (Node satellite : satellites) {
            if (hasPosition(satellite.first()) && primaryHasPosition) located.add(satellite);
        }
        if (!located.isEmpty()) {
            double maxDistance = 1.0;
            for (Node satellite : located) {
                // Fit the scene to authored positions so the camera does not
                // breathe or jump while the satellites move along their orbits.
                double dx = GalaxyCatalog.coordinate(satellite.first(), 0) - GalaxyCatalog.coordinate(data, 0);
                double dz = GalaxyCatalog.coordinate(satellite.first(), 2) - GalaxyCatalog.coordinate(data, 2);
                maxDistance = Math.max(maxDistance, Math.hypot(dx, dz));
            }
            double orbitScale = Math.max(0.000001,
                    Math.min((Math.min(mapR - mapL, mapB - mapT) * .42) / maxDistance, 8.0));
            for (Node satellite : located) {
                double dx = coordinate(satellite.first(), 0) - coordinate(data, 0);
                double dz = coordinate(satellite.first(), 2) - coordinate(data, 2);
                int sx = cx + (int)(dx * orbitScale);
                int sy = cy + (int)(dz * orbitScale);
                double sourceDx = GalaxyCatalog.coordinate(satellite.first(), 0) - GalaxyCatalog.coordinate(data, 0);
                double sourceDz = GalaxyCatalog.coordinate(satellite.first(), 2) - GalaxyCatalog.coordinate(data, 2);
                double orbitalRadius = Math.max(planetRadius + 18,
                        Math.hypot(sourceDx * orbitScale, sourceDz * orbitScale));
                boolean selectedOrbit = satellite.id.equals(selectedId) || satellite.id.equals(selectedNodeId)
                        || primary.id.equals(selectedId) || primary.id.equals(selectedNodeId);
                if (NavigatorPreferences.showOrbitFor(selectedOrbit, isStarNode(primary)))
                    SolarSystemRenderer.orbit(g, cx, cy, orbitalRadius, orbitalRadius * .72, 0xA067D8F2);
                int radius = satellite.kind.toLowerCase(Locale.ROOT).contains("moon") ? 6 : 8;
                mapPoints.put(satellite.id, new Point(sx, sy, radius));
                if (Math.hypot(mx - sx, my - sy) <= radius + 7) satelliteHovered = satellite;
                int color = satellite.id.equals(selectedId) ? GOLD : kindColor(satellite.kind);
                drawBodyVisual(g, sx, sy, radius, satellite.first(), color, true);
                g.drawString(font, trim(satellite.label, 90), sx + radius + 4, sy - 4,
                        satellite.id.equals(selectedId) ? GOLD : TEXT, false);
            }
        } else if (!satellites.isEmpty()) {
            g.drawCenteredString(font, "Satellite positions are not present in this catalogue",
                    cx, mapB - 34, MUTED);
        } else {
            g.drawCenteredString(font, "No child bodies are linked to this object",
                    cx, mapB - 34, MUTED);
        }
        mapPoints.put(primary.id, new Point(cx, cy, visualExtent));
        if (Math.hypot(mx - cx, my - cy) <= visualExtent + 6) return primary;
        return satelliteHovered;
    }

    private boolean hasAtmosphere(JsonObject data) {
        if (data == null) return false;
        if (data.has("atmosphere_color") && !data.get("atmosphere_color").isJsonNull()) {
            int color = parseCosmicColor(data, "atmosphere_color", 0);
            if ((color & 0x00FFFFFF) != 0 && colorAlpha(color) > 0) return true;
        }
        String atmosphere = value(data, "atmosphere", "has_atmosphere", "atmosphere_y");
        if (atmosphere.isEmpty()) return false;
        return !atmosphere.equalsIgnoreCase("false") && !atmosphere.equals("0") &&
                !atmosphere.equalsIgnoreCase("none");
    }

    private Node drawOrbitalMap(GuiGraphics g, int mx, int my, List<Node> nodes) {
        List<Node> positioned = new ArrayList<>();
        long starCount = nodes.stream().filter(this::isStarNode).count();
        for (Node n : nodes) {
            // Some CH systems omit the star's position because the local
            // system origin is implicitly (0, 0, 0). Use that origin only
            // when exactly one star exists; never collapse binary stars.
            if (hasPosition(n.first()) || (starCount == 1 && isStarNode(n)))
                positioned.add(n);
        }
        if (positioned.isEmpty()) {
            g.drawCenteredString(font, "No physical coordinates in this catalogue",
                    (mapL + mapR) / 2, (mapT + mapB) / 2 - 8, MUTED);
            g.drawCenteredString(font, "Bodies remain available in the object list.",
                    (mapL + mapR) / 2, (mapT + mapB) / 2 + 7, MUTED);
            return null;
        }
        if (mapMode == 2) return drawPerspectiveSystem(g, mx, my, positioned);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Node n : positioned) {
            // Keep map framing tied to the datapack's authored system bounds.
            double x = GalaxyCatalog.coordinate(n.first(), 0);
            double z = GalaxyCatalog.coordinate(n.first(), 2);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        // Include complete orbit circumferences in the fit, not only the
        // bodies' initial positions, so wide outer orbits remain on-screen.
        for (Node child : positioned) {
            Node parent = resolveParent(positioned, child);
            if (parent == null) continue;
            double px = GalaxyCatalog.coordinate(parent.first(), 0);
            double pz = GalaxyCatalog.coordinate(parent.first(), 2);
            double dx = GalaxyCatalog.coordinate(child.first(), 0) - px;
            double dz = GalaxyCatalog.coordinate(child.first(), 2) - pz;
            double orbitRadius = Math.hypot(dx, dz);
            minX = Math.min(minX, px - orbitRadius); maxX = Math.max(maxX, px + orbitRadius);
            minZ = Math.min(minZ, pz - orbitRadius); maxZ = Math.max(maxZ, pz + orbitRadius);
        }
        double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        double usableW = Math.max(1, mapR - mapL - 100), usableH = Math.max(1, mapB - mapT - 100);
        double scale = Math.min(usableW / Math.max(1, maxX - minX),
                usableH / Math.max(1, maxZ - minZ)) * zoom;
        scale = Math.max(0.000001, Math.min(scale, 10.0));
        int centerX = (mapL + mapR) / 2 + (int)panX;
        int centerY = (mapT + mapB) / 2 + (int)panY;
        Map<String, Point> projected = new HashMap<>();
        Map<String, Point> orbitAnchors = new HashMap<>();
        for (Node n : positioned) {
            double currentX = coordinate(n.first(), 0);
            double currentZ = coordinate(n.first(), 2);
            Node parent = resolveParent(positioned, n);
            if (parent != null) {
                double parentX = coordinate(parent.first(), 0);
                double parentZ = coordinate(parent.first(), 2);
                double spacing = NavigatorPreferences.atlasOrbitSpacing();
                currentX = parentX + (currentX - parentX) * spacing;
                currentZ = parentZ + (currentZ - parentZ) * spacing;
            }
            int px = centerX + (int)((currentX - cx) * scale);
            int py = centerY + (int)((currentZ - cz) * scale);
            int sourceX = centerX + (int)((GalaxyCatalog.coordinate(n.first(), 0) - cx) * scale);
            int sourceY = centerY + (int)((GalaxyCatalog.coordinate(n.first(), 2) - cz) * scale);
            int radius = n.kind.equalsIgnoreCase("star") ? 12 : n.kind.equalsIgnoreCase("moon") ? 5 : 8;
            projected.put(n.id, new Point(px, py, radius));
            orbitAnchors.put(n.id, new Point(sourceX, sourceY, radius));
        }
        drawParentLinks(g, positioned, projected, orbitAnchors, false);
        drawSystemTerritories2D(g, centerX, centerY, cx, cz, scale);
        Node hovered = null;
        for (Node n : positioned) {
            Point point = projected.get(n.id);
            int px = point.x, py = point.y, radius = point.radius;
            mapPoints.put(n.id, point);
            if (Math.hypot(mx - px, my - py) <= Math.max(10, radius + 5)) hovered = n;
            int color = n.id.equals(selectedId) ? GOLD : kindColor(n.kind);
            if (n.kind.equalsIgnoreCase("star")) {
                g.fill(px - 21, py - 21, px + 22, py + 22, 0x20FFE08A);
                g.fill(px - 15, py - 15, px + 16, py + 16, 0x45FFD46A);
            }
            drawBodyRing(g, px, py, radius, n.first());
            drawBodyVisual(g, px, py, radius, n.first(), color, mapMode == 1);
            if (n.id.equals(selectedId) || n.kind.equalsIgnoreCase("star"))
                g.drawString(font, trim(n.label, 120), px + radius + 5, py - 5,
                        n.id.equals(selectedId) ? GOLD : TEXT, false);
        }
        drawPlayerMarker2D(g, centerX, centerY, cx, cz, scale);
        drawSystemWaypoints2D(g, centerX, centerY, cx, cz, scale);
        return hovered;
    }

    private void drawSystemTerritories2D(GuiGraphics g, int centerX, int centerY,
                                         double worldCenterX, double worldCenterZ, double scale) {
        for (com.maqan.galacticnavigator.navigation.NavigatorTerritory territory :
                com.maqan.galacticnavigator.navigation.NavigatorTerritoryStore.all()) {
            if (!territory.visible || territory.frame !=
                    com.maqan.galacticnavigator.navigation.NavigatorTerritory.CoordinateFrame.SYSTEM_SPACE
                    || !universe.equals(territory.systemId) || territory.vertices.size() < 3) continue;
            List<double[]> points = new ArrayList<>();
            for (var vertex : territory.vertices) {
                double x=centerX+(vertex.x-worldCenterX)*scale;
                double y=centerY+(vertex.z-worldCenterZ)*scale;
                if (!Double.isFinite(x)||!Double.isFinite(y)) { points.clear(); break; }
                points.add(new double[]{x,y});
            }
            if(points.size()<3)continue;
            int color=territory.color==0?0xFF58B9D7:territory.color;
            int alpha=Math.max(0,Math.min(160,territory.fillAlpha));
            double minY=points.stream().mapToDouble(p->p[1]).min().orElse(0);
            double maxY=points.stream().mapToDouble(p->p[1]).max().orElse(0);
            for(int y=Math.max(mapT,(int)Math.floor(minY));y<=Math.min(mapB,(int)Math.ceil(maxY));y++){
                List<Double> intersections=new ArrayList<>();
                for(int i=0,j=points.size()-1;i<points.size();j=i++){
                    double[] a=points.get(j),b=points.get(i);
                    if((a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y))
                        intersections.add(a[0]+(y-a[1])*(b[0]-a[0])/(b[1]-a[1]));
                }
                intersections.sort(Double::compare);
                for(int i=0;i+1<intersections.size();i+=2){
                    int left=Math.max(mapL,(int)Math.ceil(intersections.get(i)));
                    int right=Math.min(mapR,(int)Math.floor(intersections.get(i+1)));
                    if(right>=left)g.fill(left,y,right+1,y+1,(alpha<<24)|(color&0x00FFFFFF));
                }
            }
            for(int i=0;i<points.size();i++){
                double[] a=points.get(i),b=points.get((i+1)%points.size());
                int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]))));
                for(int step=0;step<=steps;step++){
                    double t=step/(double)steps;
                    int x=(int)Math.round(a[0]+(b[0]-a[0])*t);
                    int y=(int)Math.round(a[1]+(b[1]-a[1])*t);
                    if(x>=mapL&&x<=mapR&&y>=mapT&&y<=mapB)g.fill(x-1,y-1,x+2,y+2,color);
                }
            }
            double[] label=points.get(0);
            if(label[0]>=mapL&&label[0]<=mapR&&label[1]>=mapT&&label[1]<=mapB)
                g.drawString(font,trim(territory.name+" · "+territory.owner,48),
                        (int)label[0]+4,(int)label[1]-10,color,false);
        }
    }

    private void drawSystemWaypoints2D(GuiGraphics g, int centerX, int centerY,
                                       double worldCenterX, double worldCenterZ, double scale) {
        for (com.maqan.galacticnavigator.navigation.NavigatorWaypoint waypoint :
                com.maqan.galacticnavigator.navigation.NavigatorWaypointStore.all()) {
            if (!waypoint.visible || waypoint.frame !=
                    com.maqan.galacticnavigator.navigation.NavigatorWaypoint.CoordinateFrame.SYSTEM_SPACE
                    || !universe.equals(waypoint.systemId)) continue;
            double x = centerX + (waypoint.x - worldCenterX) * scale;
            double y = centerY + (waypoint.z - worldCenterZ) * scale;
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || x < mapL + 5 || x > mapR - 5 || y < mapT + 5 || y > mapB - 5) continue;
            int px=(int)Math.round(x), py=(int)Math.round(y);
            int color=waypoint.color==0?0xFFFFC66D:waypoint.color;
            g.fill(px-6,py-6,px+7,py+7,0x99050B14);
            g.fill(px-4,py-4,px+5,py+5,color);
            g.drawString(font,waypoint.icon+" "+trim(waypoint.name,32),px+9,py-5,color,false);
        }
    }

    private void drawPlayerMarker2D(GuiGraphics g, int centerX, int centerY,
                                    double worldCenterX, double worldCenterZ, double scale) {
        if (!NavigatorPlayerMarkerPreferences.visible()) return;
        com.maqan.galacticnavigator.navigation.NavigatorPlayerMarker marker =
                com.maqan.galacticnavigator.navigation.NavigatorPlayerMarkerTracker.current();
        if (marker == null || !marker.positionKnown() || !isPlayerMarkerForActiveSystem(marker)) return;
        double x = centerX + (marker.systemPosition().x() - worldCenterX) * scale;
        double y = centerY + (marker.systemPosition().z() - worldCenterZ) * scale;
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < mapL + 6 || x > mapR - 6 || y < mapT + 6 || y > mapB - 6) return;
        int px = (int)Math.round(x), py = (int)Math.round(y);
        int color = NavigatorPlayerMarkerPreferences.color();
        int radius = Math.max(4, (int)Math.round(7 * NavigatorPlayerMarkerPreferences.scale()));
        // Outer halo and crosshair make the user's position distinguishable from bodies.
        g.fill(px-radius-4,py-radius-4,px+radius+5,py-radius-2,0x6655DDEE);
        g.fill(px-radius-4,py+radius+3,px+radius+5,py+radius+5,0x6655DDEE);
        g.fill(px-radius-4,py-radius-2,px-radius-2,py+radius+4,0x6655DDEE);
        g.fill(px+radius+3,py-radius-2,px+radius+5,py+radius+4,0x6655DDEE);
        switch (NavigatorPlayerMarkerPreferences.shape()) {
            case DOT -> g.fill(px-3,py-3,px+4,py+4,color);
            case TRIANGLE -> {
                g.fill(px,py-radius,px+1,py-radius+1,color);
                g.fill(px-1,py-radius+1,px+2,py-radius+2,color);
                g.fill(px-2,py-radius+2,px+3,py-radius+3,color);
                g.fill(px-3,py-radius+3,px+4,py+3,color);
                g.fill(px-2,py+3,px+3,py+4,color);
                g.fill(px-1,py+4,px+2,py+5,color);
            }
            case ARROW, SHIP -> {
                g.fill(px-1,py-radius,px+2,py+radius+1,color);
                g.fill(px-2,py-radius+3,px-1,py+radius-2,color);
                g.fill(px+2,py-radius+3,px+3,py+radius-2,color);
                g.fill(px-radius/2,py+radius/2,px-radius/2+1,py+radius,color);
                g.fill(px+radius/2,py+radius/2,px+radius/2+1,py+radius,color);
            }
        }
        if (NavigatorPlayerMarkerPreferences.showHeading()) {
            double hx=Math.sin(marker.headingRadians()), hz=Math.cos(marker.headingRadians());
            int ex=px+(int)Math.round(hx*(radius+12));
            int ey=py+(int)Math.round(hz*(radius+12));
            drawThinLine(g,px,py,ex,ey,color);
        }
        if (NavigatorPlayerMarkerPreferences.showVelocity() && marker.speed()>1.0E-6) {
            double vx=marker.velocity().x(), vz=marker.velocity().z();
            double length=Math.hypot(vx,vz);
            if(length>1.0E-6) drawThinLine(g,px,py,
                    px+(int)Math.round(vx/length*26),py+(int)Math.round(vz/length*26),0xFFB8E9FF);
        }
        if (NavigatorPlayerMarkerPreferences.showLabel())
            g.drawString(font,marker.displayName()+" · YOU",px+radius+8,py-radius-5,color,false);
        if (NavigatorPlayerMarkerPreferences.showRange())
            g.drawString(font,String.format(Locale.ROOT,"%.1f u/s",marker.speed()),
                    px+radius+8,py+radius+2,MUTED,false);
    }

    private boolean isPlayerMarkerForActiveSystem(
            com.maqan.galacticnavigator.navigation.NavigatorPlayerMarker marker){
        if(marker==null)return false;
        if(universe.equals(marker.systemId()))return true;
        String dimension=GalaxyCatalog.text(GalaxyCatalog.universe(universe),"dimension","");
        return marker.systemId().isBlank()&&!dimension.isBlank()
                &&dimension.equals(marker.dimensionId());
    }

    private void drawPerspectiveRoutePreview(GuiGraphics g,
            com.maqan.galacticnavigator.navigation.NavigatorRoutePreview.Preview preview,
            double[] center,double fit,double cyaw,double syaw,double cpitch,double spitch,
            double focal,double cameraDistance){
        var waypoints=preview.route().waypoints();
        int routeColor=preview.route().clear()?0xFF63E5C1:0xFFFF6F79;
        int previousX=0,previousY=0;
        boolean hasPrevious=false;
        for(int i=0;i<waypoints.size();i++){
            var point=waypoints.get(i);
            double x=(point.x()-center[0])*fit;
            double y=(point.y()-center[1])*fit;
            double z=(point.z()-center[2])*fit;
            double rx=x*cyaw-z*syaw;
            double rz=x*syaw+z*cyaw;
            double ry=y*cpitch-rz*spitch;
            double depthAxis=y*spitch+rz*cpitch;
            double depth=Math.max(120,cameraDistance-depthAxis);
            double perspective=focal/depth;
            int px=(mapL+mapR)/2+(int)(rx*perspective+panX);
            int py=(mapT+mapB)/2-(int)(ry*perspective-panY);
            if(hasPrevious)drawThinLine(g,previousX,previousY,px,py,routeColor);
            int radius=i==0||i==waypoints.size()-1?4:3;
            g.fill(px-radius-2,py-radius-2,px+radius+3,py+radius+3,0x5536D8D0);
            g.fill(px-radius,py-radius,px+radius+1,py+radius+1,routeColor);
            previousX=px;previousY=py;hasPrevious=true;
        }
        String status=preview.route().clear()?"ROUTE PREVIEW · CLEAR AGAINST KNOWN HAZARDS"
                :"ROUTE PREVIEW · BLOCKED / REPLAN REQUIRED";
        g.drawString(font,status,mapL+10,mapB-18,routeColor,false);
        g.drawString(font,preview.route().waypoints().size()+" waypoints · "
                +preview.obstacleCount()+" explicit hazard volumes · "
                +preview.unknownBodyCount()+" bodies without physical radius",
                mapL+10,mapB-7,MUTED,false);
    }

    private void drawPlayerMarkerPerspective(GuiGraphics g,int x,int y,
            com.maqan.galacticnavigator.navigation.NavigatorPlayerMarker marker,
            double cyaw,double syaw,double cpitch,double spitch,
            double focal,double depth){
        int color=NavigatorPlayerMarkerPreferences.color();
        int radius=Math.max(4,(int)Math.round(7*NavigatorPlayerMarkerPreferences.scale()));
        g.fill(x-radius-5,y-radius-5,x+radius+6,y-radius-3,0x7755DDEE);
        g.fill(x-radius-5,y+radius+4,x+radius+6,y+radius+6,0x7755DDEE);
        g.fill(x-radius-5,y-radius-3,x-radius-3,y+radius+5,0x7755DDEE);
        g.fill(x+radius+4,y-radius-3,x+radius+6,y+radius+5,0x7755DDEE);
        switch(NavigatorPlayerMarkerPreferences.shape()){
            case DOT -> g.fill(x-3,y-3,x+4,y+4,color);
            case TRIANGLE -> {
                g.fill(x,y-radius,x+1,y-radius+1,color);
                g.fill(x-2,y-radius+2,x+3,y+3,color);
                g.fill(x-1,y+3,x+2,y+5,color);
            }
            case ARROW,SHIP -> {
                g.fill(x-1,y-radius,x+2,y+radius+1,color);
                g.fill(x-3,y-radius+3,x+4,y-radius+5,color);
                g.fill(x-radius/2,y+radius/2,x-radius/2+1,y+radius,color);
                g.fill(x+radius/2,y+radius/2,x+radius/2+1,y+radius,color);
            }
        }
        if(NavigatorPlayerMarkerPreferences.showHeading()){
            double hx=Math.sin(marker.headingRadians()),hz=Math.cos(marker.headingRadians());
            double rx=hx*cyaw-hz*syaw,rz=hx*syaw+hz*cyaw;
            double ry=-rz*spitch;
            int ex=x+(int)Math.round(rx*focal/depth*(radius+12));
            int ey=y-(int)Math.round(ry*focal/depth*(radius+12));
            drawThinLine(g,x,y,ex,ey,color);
        }
        if(NavigatorPlayerMarkerPreferences.showVelocity()&&marker.speed()>1.0E-6){
            double vx=marker.velocity().x(),vy=marker.velocity().y(),vz=marker.velocity().z();
            double rx=vx*cyaw-vz*syaw,rz=vx*syaw+vz*cyaw;
            double ry=vy*cpitch-rz*spitch;
            double magnitude=marker.speed();
            double length=Math.min(42,Math.max(10,10+magnitude*2));
            int ex=x+(int)Math.round(rx/magnitude*focal/depth*length);
            int ey=y-(int)Math.round(ry/magnitude*focal/depth*length);
            drawThinLine(g,x,y,ex,ey,0xFFB8E9FF);
        }
        if(NavigatorPlayerMarkerPreferences.showLabel())
            g.drawString(font,marker.displayName()+" · YOU",x+radius+8,y-radius-5,color,false);
        if(NavigatorPlayerMarkerPreferences.showRange())
            g.drawString(font,String.format(Locale.ROOT,"%.1f u/s",marker.speed()),
                    x+radius+8,y+radius+2,MUTED,false);
    }

    private void drawThinLine(GuiGraphics g,int x1,int y1,int x2,int y2,int color) {
        int steps=Math.max(1,Math.max(Math.abs(x2-x1),Math.abs(y2-y1)));
        for(int i=0;i<=steps;i++){
            double t=(double)i/steps;
            int x=(int)Math.round(x1+(x2-x1)*t), y=(int)Math.round(y1+(y2-y1)*t);
            g.fill(x,y,x+2,y+2,color);
        }
    }

    /** Perspective projection of source XYZ coordinates with a freely rotatable camera. */
    private Node drawPerspectiveSystem(GuiGraphics g, int mx, int my, List<Node> nodes) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Node n : nodes) for (int axis = 0; axis < 3; axis++) {
            double v = GalaxyCatalog.coordinate(n.first(), axis);
            min[axis] = Math.min(min[axis], v); max[axis] = Math.max(max[axis], v);
        }
        var routePreview=com.maqan.galacticnavigator.navigation.NavigatorRoutePreview.current();
        boolean renderRoutePreview=routePreview!=null&&universe.equals(routePreview.systemId())
                &&routePreview.route()!=null&&routePreview.route().waypoints().size()>1;
        if(renderRoutePreview){
            for(var waypoint:routePreview.route().waypoints()){
                min[0]=Math.min(min[0],waypoint.x());max[0]=Math.max(max[0],waypoint.x());
                min[1]=Math.min(min[1],waypoint.y());max[1]=Math.max(max[1],waypoint.y());
                min[2]=Math.min(min[2],waypoint.z());max[2]=Math.max(max[2],waypoint.z());
            }
        }
        var playerMarker=com.maqan.galacticnavigator.navigation.NavigatorPlayerMarkerTracker.current();
        boolean renderPlayerMarker=NavigatorPlayerMarkerPreferences.visible()
                &&playerMarker!=null&&playerMarker.positionKnown()
                &&isPlayerMarkerForActiveSystem(playerMarker);
        if(renderPlayerMarker){
            double[] playerCoordinates={playerMarker.systemPosition().x(),
                    playerMarker.systemPosition().y(),playerMarker.systemPosition().z()};
            for(int axis=0;axis<3;axis++){
                min[axis]=Math.min(min[axis],playerCoordinates[axis]);
                max[axis]=Math.max(max[axis],playerCoordinates[axis]);
            }
        }
        // Conservative 3D bounds include the full parent-centred orbit loops.
        for (Node child : nodes) {
            Node parent = resolveParent(nodes, child);
            if (parent == null) continue;
            double[] p = new double[]{GalaxyCatalog.coordinate(parent.first(), 0),
                    GalaxyCatalog.coordinate(parent.first(), 1),
                    GalaxyCatalog.coordinate(parent.first(), 2)};
            double dx = GalaxyCatalog.coordinate(child.first(), 0) - p[0];
            double dy = GalaxyCatalog.coordinate(child.first(), 1) - p[1];
            double dz = GalaxyCatalog.coordinate(child.first(), 2) - p[2];
            double orbitRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], p[axis] - orbitRadius);
                max[axis] = Math.max(max[axis], p[axis] + orbitRadius);
            }
        }
        double[] center = {(min[0] + max[0]) * .5, (min[1] + max[1]) * .5, (min[2] + max[2]) * .5};
        if (cameraTargetId != null) {
            for (Node target : nodes) if (target.id.equals(cameraTargetId)) {
                center[0] = coordinate(target.first(), 0);
                center[1] = coordinate(target.first(), 1);
                center[2] = coordinate(target.first(), 2);
                break;
            }
        }
        double extent = Math.max(1, Math.max(max[0] - min[0],
                Math.max(max[1] - min[1], max[2] - min[2])));
        double fit = Math.min(mapR - mapL - 120, mapB - mapT - 110) / extent * zoom;
        fit = Math.max(0.000001, Math.min(fit, 10.0));
        double focal = Math.max(240, Math.min(mapR - mapL, mapB - mapT) * 1.35);
        // Keep camera distance tied to the fitted scene, not live zoom. Otherwise
        // fit and distance grow together and cancel most of the perspective zoom.
        double baseFit = Math.min(mapR - mapL - 120, mapB - mapT - 110) / extent;
        double cameraDistance = 720.0 + Math.max(0.0, extent * baseFit - 240.0) * 0.72;
        double cyaw = Math.cos(cameraYaw), syaw = Math.sin(cameraYaw);
        double cpitch = Math.cos(cameraPitch), spitch = Math.sin(cameraPitch);
        Map<String, Point> projected = new HashMap<>();
        Map<String, Double> depths = new HashMap<>();
        for (Node n : nodes) {
            double worldX = coordinate(n.first(), 0);
            double worldY = coordinate(n.first(), 1);
            double worldZ = coordinate(n.first(), 2);
            Node parent = resolveParent(nodes, n);
            if (parent != null) {
                double spacing = NavigatorPreferences.atlasOrbitSpacing();
                worldX = coordinate(parent.first(), 0) + (worldX - coordinate(parent.first(), 0)) * spacing;
                worldY = coordinate(parent.first(), 1) + (worldY - coordinate(parent.first(), 1)) * spacing;
                worldZ = coordinate(parent.first(), 2) + (worldZ - coordinate(parent.first(), 2)) * spacing;
            }
            double x = (worldX - center[0]) * fit;
            double y = (worldY - center[1]) * fit;
            double z = (worldZ - center[2]) * fit;
            double rx = x * cyaw - z * syaw;
            double rz = x * syaw + z * cyaw;
            double ry = y * cpitch - rz * spitch;
            double depthAxis = y * spitch + rz * cpitch;
            double depth = Math.max(120, cameraDistance - depthAxis);
            double perspective = focal / depth;
            int px = (mapL + mapR) / 2 + (int)(rx * perspective + panX);
            int py = (mapT + mapB) / 2 - (int)(ry * perspective - panY);
            int baseRadius = isStarNode(n) ? 20 :
                    n.kind.toLowerCase(Locale.ROOT).contains("moon") ? 9 : 13;
            int radius = Math.max(4, Math.min(34, (int)(baseRadius * Math.max(.62, perspective)
                    * Math.sqrt(Math.max(.2, Math.min(8.0, zoom))))));
            projected.put(n.id, new Point(px, py, radius));
            depths.put(n.id, depth);
        }
        Map<String, Point> orbitAnchors = new HashMap<>();
        for (Node n : nodes) {
            double x = (GalaxyCatalog.coordinate(n.first(), 0) - center[0]) * fit;
            double y = (GalaxyCatalog.coordinate(n.first(), 1) - center[1]) * fit;
            double z = (GalaxyCatalog.coordinate(n.first(), 2) - center[2]) * fit;
            double rx = x * cyaw - z * syaw;
            double rz = x * syaw + z * cyaw;
            double ry = y * cpitch - rz * spitch;
            double depthAxis = y * spitch + rz * cpitch;
            double depth = Math.max(120, cameraDistance - depthAxis);
            double perspectiveScale = focal / depth;
            int px = (mapL + mapR) / 2 + (int)(rx * perspectiveScale + panX);
            int py = (mapT + mapB) / 2 - (int)(ry * perspectiveScale - panY);
            orbitAnchors.put(n.id, new Point(px, py, 0));
        }
        drawPerspectiveOrbits(g, nodes, center, fit, cyaw, syaw, cpitch, spitch,
                focal, cameraDistance);
        List<Node> drawOrder = new ArrayList<>(nodes);
        drawOrder.sort((a, b) -> Double.compare(depths.getOrDefault(b.id, 0.0),
                depths.getOrDefault(a.id, 0.0)));
        Node hovered = null;
        for (Node n : drawOrder) {
            Point p = projected.get(n.id);
            if (p == null) continue;
            mapPoints.put(n.id, p);
            if (Math.hypot(mx - p.x, my - p.y) <= Math.max(11, p.radius + 5)) hovered = n;
            int color = n.id.equals(selectedId) ? GOLD : kindColor(n.kind);
            if (n.kind.equalsIgnoreCase("star"))
                g.fill(p.x - p.radius - 9, p.y - p.radius - 9,
                        p.x + p.radius + 10, p.y + p.radius + 10, 0x20FFE08A);
            drawBodyRing(g, p.x, p.y, p.radius, n.first());
            drawBodyVisual(g, p.x, p.y, p.radius, n.first(), color, true);
            if (n.id.equals(selectedId) || n.kind.equalsIgnoreCase("star"))
                g.drawString(font, trim(n.label, 120), p.x + p.radius + 5, p.y - 5,
                        n.id.equals(selectedId) ? GOLD : TEXT, false);
        }
        if(renderRoutePreview){
            drawPerspectiveRoutePreview(g,routePreview,center,fit,cyaw,syaw,cpitch,spitch,
                    focal,cameraDistance);
        }
        if(renderPlayerMarker){
            double wx=playerMarker.systemPosition().x();
            double wy=playerMarker.systemPosition().y();
            double wz=playerMarker.systemPosition().z();
            double x=(wx-center[0])*fit;
            double y=(wy-center[1])*fit;
            double z=(wz-center[2])*fit;
            double rx=x*cyaw-z*syaw;
            double rz=x*syaw+z*cyaw;
            double ry=y*cpitch-rz*spitch;
            double depthAxis=y*spitch+rz*cpitch;
            double depth=Math.max(120,cameraDistance-depthAxis);
            double perspective=focal/depth;
            int px=(mapL+mapR)/2+(int)(rx*perspective+panX);
            int py=(mapT+mapB)/2-(int)(ry*perspective-panY);
            drawPlayerMarkerPerspective(g,px,py,playerMarker,cyaw,syaw,cpitch,spitch,focal,depth);
        }
        g.drawString(font, "3D SYSTEM VIEW · LEFT-DRAG MOVE · RIGHT-DRAG ROTATE",
                mapL + 10, mapT + 8, MUTED, false);
        return hovered;
    }

    /** Draw true projected orbital curves in the 3D camera plane, not screen-space ellipses. */
    private void drawPerspectiveOrbits(GuiGraphics g, List<Node> nodes, double[] center,
                                        double fit, double cyaw, double syaw,
                                        double cpitch, double spitch,
                                        double focal, double cameraDistance) {
        for (Node child : nodes) {
            Node parent = resolveParent(nodes, child);
            if (parent == null) continue;
            boolean selectedOrbit = child.id.equals(selectedId) || child.id.equals(selectedNodeId)
                    || parent.id.equals(selectedId) || parent.id.equals(selectedNodeId);
            if (!NavigatorPreferences.showOrbitFor(selectedOrbit, isStarNode(parent))) continue;
            double px = GalaxyCatalog.coordinate(parent.first(), 0);
            double py = GalaxyCatalog.coordinate(parent.first(), 1);
            double pz = GalaxyCatalog.coordinate(parent.first(), 2);
            double dx = GalaxyCatalog.coordinate(child.first(), 0) - px;
            double dy = GalaxyCatalog.coordinate(child.first(), 1) - py;
            double dz = GalaxyCatalog.coordinate(child.first(), 2) - pz;
            double sourceRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double radius = sourceRadius * NavigatorPreferences.atlasOrbitSpacing();
            if (!Double.isFinite(sourceRadius) || sourceRadius < 1.0E-8) continue;
            // Basis one follows the authored radius vector; basis two lies in
            // the default XZ orbital plane and is stable for near-polar bodies.
            // Normalize against sourceRadius, then expand only the rendered orbit.
            double bx = dx / sourceRadius, by = dy / sourceRadius, bz = dz / sourceRadius;
            double nx = -bz, ny = 0.0, nz = bx;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl < 1.0E-8) { nx = 1.0; ny = 0.0; nz = 0.0; nl = 1.0; }
            nx /= nl; ny /= nl; nz /= nl;
            int segments = Math.max(128, Math.min(384, (int)(radius * fit / 2.5)));
            int previousX = 0, previousY = 0;
            for (int i = 0; i <= segments; i++) {
                double angle = Math.PI * 2.0 * i / segments;
                double wx = px + radius * (bx * Math.cos(angle) + nx * Math.sin(angle));
                double wy = py + radius * (by * Math.cos(angle) + ny * Math.sin(angle));
                double wz = pz + radius * (bz * Math.cos(angle) + nz * Math.sin(angle));
                double x = (wx - center[0]) * fit;
                double y = (wy - center[1]) * fit;
                double z = (wz - center[2]) * fit;
                double rx = x * cyaw - z * syaw;
                double rz = x * syaw + z * cyaw;
                double ry = y * cpitch - rz * spitch;
                double depthAxis = y * spitch + rz * cpitch;
                double depth = Math.max(120.0, cameraDistance - depthAxis);
                double perspective = focal / depth;
                int sx = (mapL + mapR) / 2 + (int)(rx * perspective + panX);
                int sy = (mapT + mapB) / 2 - (int)(ry * perspective - panY);
                if (i > 0) SolarSystemRenderer.line(g, previousX, previousY, sx, sy,
                        0xA067D8F2, NavigatorPreferences.orbitThickness());
                previousX = sx; previousY = sy;
            }
        }
    }

    /** Existing system-view renderer. Kept behavior-compatible with the established planetary view. */
    private void drawBodyVisual(GuiGraphics g, int x, int y, int radius, JsonObject data,
                                int color, boolean allowTexture) {
        drawBodyVisual(g, x, y, radius, data, color, allowTexture, false);
    }

    /** Atlas-only renderer: same Cosmic Horizons model and textures with independent atlas controls. */
    private boolean hasTextureId(JsonObject data) {
        if (data == null) return false;
        for (String key : new String[]{"texture_id", "texture", "texture_location", "planet_texture"}) {
            if (!data.has(key) || data.get(key).isJsonNull()) continue;
            try {
                if (!data.get(key).getAsString().trim().isEmpty()) return true;
            } catch (RuntimeException ignored) { }
        }
        return false;
    }

    private boolean hasTextureId(Node node) {
        return node != null && hasTextureId(node.first());
    }

    private void drawAtlasBodyVisual(GuiGraphics g, int x, int y, int radius, JsonObject data,
                                     int color) {
        drawBodyVisual(g, x, y, radius, data, color, true, true);
    }

    private void drawBodyVisual(GuiGraphics g, int x, int y, int radius, JsonObject data,
                                int color, boolean allowTexture, boolean atlasPresentation) {
        if (isNebulaData(data)) {
            double volumeScale = Math.max(1.2, Math.min(6.0,
                    metadataNumber(data, "volume_render_scale", 2.6)));
            drawNebulaCloud(g, x, y, Math.max(7, (int)Math.round(radius * volumeScale)), data);
            return;
        }
        String objectKind = value(data, "kind", "type", "display_type", "classification")
                .toLowerCase(Locale.ROOT);
        if (objectKind.contains("blackhole") || objectKind.contains("black hole")) {
            drawBlackHole(g, x, y, Math.max(4, radius), data);
            return;
        }
        if (isAsteroidBeltData(data)) {
            drawAsteroidBelt(g, x, y, Math.max(5, radius), data);
            drawDisc(g, x, y, Math.max(3, radius / 3), 0xFFE0C89A, 0xFF75624D);
            return;
        }
        if (objectKind.contains("comet")) {
            drawComet(g, x, y, Math.max(3, radius), data);
            return;
        }
        if (data != null && data.has("atmosphere_color")) {
            int atmosphere = parseCosmicColor(data, "atmosphere_color", 0);
            if ((atmosphere & 0x00FFFFFF) != 0)
                drawAtmosphereHalo(g, x, y, radius, atmosphere);
        }
        boolean star = isStarData(data);
        boolean glowing = !star && "true".equalsIgnoreCase(value(data, "glowing"));
        if (glowing) {
            int bloom = parseCosmicColor(data, "bloom_color", 0xFFFFD98A);
            int core = parseCosmicColor(data, "core_color", bloom);
            drawRadialGlow(g, x, y, radius, bloom, core);
        }
        // In atlas views stars can use their authored Cosmic Horizons texture.
        // The established system view keeps its original luminous-disc star renderer.
        if (star) {
            // Stars are rendered as luminous stellar bodies. Planet and moon
            // rendering below still uses the original Cosmic Horizons cube model.
            // The caller's panel scissor clips the corona and rays to its own viewport.
            if (!atlasPresentation || NavigatorPreferences.atlasStarGlow()) {
                int bloom = parseCosmicColor(data, "bloom_color", 0xFFFFD98A);
                int core = parseCosmicColor(data, "core_color", bodyFallbackColor(data, color));
                StellarVisualRenderer.render(g, x, y, radius, bloom, core);
            } else {
                drawDisc(g, x, y, Math.max(4, radius), 0xFFFFF4D0,
                        bodyFallbackColor(data, color));
            }
            return;
        }
        if (allowTexture && (!atlasPresentation || NavigatorPreferences.atlasTextures())
                && CosmicBodyGuiRenderer.render(g, x, y, radius, data)) return;
        int base = bodyFallbackColor(data, color);
        drawDisc(g, x, y, Math.max(3, radius), lighten(base), base);
    }

    /**
     * Lightweight GUI approximation of the atmospheric rim. The color is sourced
     * from Cosmic Horizons atmosphere_color; this deliberately does not interpret
     * atmosphere_y as a screen-space radius or claim to reproduce CH scattering.
     */
    private void drawBlackHole(GuiGraphics g, int cx, int cy, int radius, JsonObject data) {
        int disk = parseCosmicColor(data, "accretion_disk_color", 0xFFD98B54);
        drawAccretionDisk(g, cx, cy, radius * 2.25, Math.max(2.0, radius * .58),
                (0xA0 << 24) | (disk & 0x00FFFFFF));
        drawAccretionDisk(g, cx, cy, radius * 1.55, Math.max(1.5, radius * .34),
                0xC0F3B875);
        drawDisc(g, cx, cy, radius, 0xFF25202D, 0xFF05050A);
        g.fill(cx - 1, cy - radius - 2, cx + 2, cy - radius + 1, 0x88FFFFFF);
    }

    private void drawAccretionDisk(GuiGraphics g, int cx, int cy, double rx, double ry, int color) {
        int rows = Math.max(12, Math.min(48, (int)Math.ceil(ry * 2.0)));
        for (int row = 0; row < rows; row++) {
            double ny = (row + .5) / rows * 2.0 - 1.0;
            double shape = Math.sqrt(Math.max(0.0, 1.0 - ny * ny));
            int outer = (int)Math.ceil(rx * shape);
            int inner = (int)(outer * .68);
            int y = cy - (int)Math.ceil(ry) + (int)(2.0 * ry * row / rows);
            int h = Math.max(1, (int)Math.ceil(2.0 * ry / rows));
            if (outer > inner) {
                g.fill(cx - outer, y, cx - inner, y + h, color);
                g.fill(cx + inner, y, cx + outer + 1, y + h, color);
            }
        }
    }

    private double metadataNumber(JsonObject data, String key, double fallback) {
        try {
            if (data != null && data.has(key) && data.get(key).isJsonPrimitive()) {
                double value = data.get(key).getAsDouble();
                return Double.isFinite(value) ? value : fallback;
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private void drawComet(GuiGraphics g, int cx, int cy, int radius, JsonObject data) {
        int tail = parseCosmicColor(data, "dust_tail_color", 0xFF8BDCEB);
        int ion = parseCosmicColor(data, "ion_tail_color", 0xFF6D9DEB);
        double tailLength = Math.max(radius * 2.5,
                Math.min(radius * 8.0, metadataNumber(data, "tail_length", radius * 5.0)));
        double angle = cometTailAngle(data);
        double ux = Math.cos(angle), uy = Math.sin(angle);
        double px = -uy, py = ux;
        double startX = cx + ux * radius * 0.35;
        double startY = cy + uy * radius * 0.35;
        double endX = cx + ux * tailLength;
        double endY = cy + uy * tailLength;
        SolarSystemRenderer.curvedLine(g, startX + px * radius * .10,
                startY + py * radius * .10, endX + px * radius * .30,
                endY + py * radius * .30, radius * .35,
                (0x55 << 24) | (tail & 0x00FFFFFF), Math.max(1.2f, radius * .42f));
        SolarSystemRenderer.curvedLine(g, startX - px * radius * .08,
                startY - py * radius * .08, endX - px * radius * .18,
                endY - py * radius * .18, -radius * .22,
                (0x70 << 24) | (ion & 0x00FFFFFF), Math.max(0.9f, radius * .24f));
        drawDisc(g, cx, cy, radius, 0xFFEAF8FF, 0xFF78A9BD);
        drawRadialGlow(g, cx, cy, Math.max(2, radius / 2), 0x667EDFF5, 0xFFBDEEFF);
    }

    private boolean isAsteroidBeltData(JsonObject data) {
        if (data == null) return false;
        String identity = (value(data, "kind", "type", "display_type", "classification") + " "
                + value(data, "name", "id")).toLowerCase(Locale.ROOT);
        return (identity.contains("asteroid") || identity.contains("belt"))
                && (identity.contains("belt") || data.has("belt_width")
                || data.has("belt_inner_radius") || data.has("belt_outer_radius")
                || data.has("belt_density") || data.has("asteroid_count"));
    }

    /** Screen-space belt silhouette; physical particle motion is handled separately. */
    private void drawAsteroidBelt(GuiGraphics g, int cx, int cy, int radius, JsonObject data) {
        int tint = parseCosmicColor(data, "belt_color",
                parseCosmicColor(data, "dust_tail_color", 0xFFB6A18A));
        double authoredWidth = metadataNumber(data, "belt_width", 0.8);
        // Datapack belt_width may use world units. Only small dimensionless values
        // are used as a visual ratio; otherwise preserve a readable default.
        double widthRatio = authoredWidth > 0.0 && authoredWidth <= 3.0 ? authoredWidth : 0.8;
        widthRatio = Math.max(0.35, Math.min(1.8, widthRatio));
        double inner = radius * 1.25;
        double outer = inner + radius * widthRatio;
        double inclination = Math.toRadians(metadataNumber(data, "belt_inclination_degrees", 12.0));
        double node = Math.toRadians(metadataNumber(data, "belt_node_degrees", 0.0));
        double flatten = Math.max(0.16, Math.min(0.62,
                0.20 + Math.abs(Math.sin(inclination)) * 0.42));
        int baseRgb = tint & 0x00FFFFFF;
        for (int ring = 0; ring < 2; ring++) {
            double r = ring == 0 ? inner : outer;
            int alpha = ring == 0 ? 0x86 : 0x55;
            int color = (alpha << 24) | baseRgb;
            int segments = 48;
            int px = 0, py = 0;
            for (int i = 0; i <= segments; i++) {
                double a = Math.PI * 2.0 * i / segments;
                double ex = Math.cos(a) * r;
                double ey = Math.sin(a) * r * flatten;
                double rx = ex * Math.cos(node) - ey * Math.sin(node);
                double ry = ex * Math.sin(node) + ey * Math.cos(node);
                int sx = cx + (int)Math.round(rx);
                int sy = cy + (int)Math.round(ry);
                if (i > 0) SolarSystemRenderer.line(g, px, py, sx, sy, color,
                        ring == 0 ? 2 : 1);
                px = sx;
                py = sy;
            }
        }
        // Deterministic dust/rock specks: stable across frames and clients.
        int seed = value(data, "catalog_id", "id").hashCode();
        int particles = Math.max(12, Math.min(36,
                (int)metadataNumber(data, "particle_count",
                        metadataNumber(data, "asteroid_count", 24))));
        for (int i = 0; i < particles; i++) {
            double angle = Math.floorMod(seed + i * 97, 10007) / 10007.0 * Math.PI * 2.0;
            double radial = inner + (outer - inner)
                    * (0.12 + 0.76 * Math.floorMod(seed * 31 + i * 193, 997) / 997.0);
            double ex = Math.cos(angle) * radial;
            double ey = Math.sin(angle) * radial * flatten;
            double rx = ex * Math.cos(node) - ey * Math.sin(node);
            double ry = ex * Math.sin(node) + ey * Math.cos(node);
            int size = i % 7 == 0 ? 2 : 1;
            int alpha = 0x55 + Math.floorMod(seed + i * 43, 0x55);
            g.fill(cx + (int)rx, cy + (int)ry, cx + (int)rx + size, cy + (int)ry + size,
                    (alpha << 24) | baseRgb);
        }
    }

    /** Tail points away from the resolved parent star unless the datapack overrides it. */
    private double cometTailAngle(JsonObject body) {
        if (body == null) return Math.PI;
        if (body.has("tail_direction") && body.get("tail_direction").isJsonArray()) {
            JsonArray direction = body.getAsJsonArray("tail_direction");
            if (direction.size() >= 3) {
                try {
                    double dx = direction.get(0).getAsDouble();
                    double dz = direction.get(2).getAsDouble();
                    if (Double.isFinite(dx) && Double.isFinite(dz)
                            && Math.hypot(dx, dz) > 1.0E-9) return Math.atan2(dz, dx);
                } catch (RuntimeException ignored) { }
            }
        }
        String systemId = value(body, "system_id");
        if (!systemId.isBlank()) {
            JsonObject system = GalaxyCatalog.universe(systemId);
            String parentRef = value(body, "orbit_parent_id", "orbit_parent", "parent_id",
                    "parent", "satellite_of", "parent_body", "central_body");
            JsonObject parent = null;
            if (!parentRef.isBlank()) {
                for (Entry<String, JsonObject> entry : GalaxyCatalog.objects(systemId)) {
                    JsonObject candidate = entry.getValue();
                    if (entry.getKey().equals(parentRef)
                            || parentRef.equals(value(candidate, "catalog_id", "id"))) {
                        parent = candidate;
                        break;
                    }
                }
            } else {
                for (Entry<String, JsonObject> entry : GalaxyCatalog.objects(systemId)) {
                    if (!isStarData(entry.getValue())) continue;
                    if (parent != null) { parent = null; break; }
                    parent = entry.getValue();
                }
            }
            if (parent != null && minecraft != null && minecraft.level != null) {
                double time = minecraft.level.getGameTime() + minecraft.getFrameTime();
                double bx = NativeOrbitalSimulation.coordinate(systemId, system, body, 0, time);
                double bz = NativeOrbitalSimulation.coordinate(systemId, system, body, 2, time);
                double px = NativeOrbitalSimulation.coordinate(systemId, system, parent, 0, time);
                double pz = NativeOrbitalSimulation.coordinate(systemId, system, parent, 2, time);
                double dx = bx - px, dz = bz - pz;
                if (Math.hypot(dx, dz) > 1.0E-9) return Math.atan2(dz, dx);
            }
        }
        return Math.toRadians(metadataNumber(body, "tail_orientation", 180.0));
    }

    private boolean isNebulaData(JsonObject data) {
        if (data == null) return false;
        String identity = (value(data, "kind", "type", "display_type", "classification") + " "
                + value(data, "name", "id")).toLowerCase(Locale.ROOT);
        return identity.contains("nebula") || identity.contains("molecular cloud")
                || identity.contains("gas cloud") || identity.contains("dust cloud")
                || identity.contains("supernova remnant");
    }

    /** Soft multi-lobed nebula marker for atlas/system views; no invented texture assets. */
    private void drawNebulaCloud(GuiGraphics g, int cx, int cy, int radius, JsonObject data) {
        int bloom = parseCosmicColor(data, "volume_color",
                parseCosmicColor(data, "bloom_color",
                        parseCosmicColor(data, "nebula_color", 0xFF8B6CCB)));
        double volumeOpacity = Math.max(0.0, Math.min(1.0,
                metadataNumber(data, "volume_opacity", 0.5)));
        int core = parseCosmicColor(data, "core_color",
                parseCosmicColor(data, "cloud_color", 0xFF63A8C8));
        int[][] lobes = {{-radius / 2, 0, 100}, {radius / 3, -radius / 4, 82},
                {radius / 3, radius / 3, 76}, {-radius / 4, radius / 3, 68}};
        for (int[] lobe : lobes) {
            int extent = Math.max(4, radius * lobe[2] / 100);
            int rows = Math.max(8, Math.min(28, extent * 2));
            for (int row = 0; row < rows; row++) {
                double ny = (row + .5) / rows * 2.0 - 1.0;
                double shape = Math.sqrt(Math.max(0.0, 1.0 - ny * ny));
                int half = (int)(extent * 1.35 * shape);
                int alpha = Math.max(0, (int)(colorAlpha(bloom) * .40
                        * volumeOpacity * (1.0 - Math.abs(ny))));
                if (alpha == 0) continue;
                int yy = cy + lobe[1] - extent + (int)(2.0 * extent * row / rows);
                g.fill(cx + lobe[0] - half, yy, cx + lobe[0] + half + 1,
                        yy + Math.max(1, 2 * extent / rows),
                        (alpha << 24) | (bloom & 0x00FFFFFF));
            }
        }
        int coreRadius = Math.max(2, radius / 3);
        drawDisc(g, cx, cy, coreRadius, (0x55 << 24) | (core & 0x00FFFFFF),
                (0x20 << 24) | (bloom & 0x00FFFFFF));
    }

    private boolean isStarData(JsonObject data) {
        if (data == null) return false;

        // Display names and ids are not type declarations. In particular,
        // substring checks for "sun" classified unrelated bodies as stars.
        String name = value(data, "name", "display_name", "id", "catalog_id")
                .trim().toLowerCase(Locale.ROOT);
        String compactName = name.replaceAll("[^a-z0-9]+", "");
        if (Set.of("mercury", "venus", "earth", "mars", "jupiter", "saturn",
                "uranus", "neptune", "pluto", "moon", "europa", "ganymede",
                "callisto", "titan", "enceladus", "triton", "charon").contains(compactName))
            return false;

        String stellarClass = value(data, "stellar_class", "spectral_class",
                "star_class", "spectral_type").trim().toLowerCase(Locale.ROOT);
        if (!stellarClass.isEmpty()) return true;

        String kind = value(data, "kind", "display_type", "type", "classification")
                .trim().toLowerCase(Locale.ROOT);
        if (kind.equals("planet") || kind.equals("moon") || kind.equals("satellite")
                || kind.equals("asteroid") || kind.equals("comet") || kind.equals("dwarf planet"))
            return false;

        // Accept explicit stellar classifications, not incidental words in a
        // body name, texture, description, or unrelated metadata.
        if (Set.of("star", "stellar", "sun", "yellow dwarf", "red dwarf",
                "white dwarf", "blue giant", "red giant", "neutron star",
                "main sequence star", "brown dwarf").contains(kind)) return true;

        String type = value(data, "type", "display_type", "classification")
                .trim().toLowerCase(Locale.ROOT);
        if (Set.of("star", "stellar", "sun", "yellow dwarf", "red dwarf",
                "white dwarf", "blue giant", "red giant", "neutron star",
                "main sequence star", "brown dwarf").contains(type)) return true;

        return compactName.equals("sol") || compactName.equals("sun");
    }

    private int bodyFallbackColor(JsonObject data, int fallback) {
        String identity = value(data, "name", "id", "kind").toLowerCase(Locale.ROOT);
        if (identity.contains("mars")) return 0xFFCF6847;
        if (identity.contains("mercury")) return 0xFF9A9287;
        if (identity.contains("venus")) return 0xFFE3B879;
        if (identity.contains("earth")) return 0xFF3D91D2;
        if (identity.contains("jupiter")) return 0xFFC69A72;
        if (identity.contains("saturn")) return 0xFFD9C59A;
        if (identity.contains("uranus")) return 0xFF83C8D0;
        if (identity.contains("neptune")) return 0xFF416BD0;
        if (isStarData(data)) return 0xFFFFD36D;
        return fallback == 0 ? 0xFF7E9DBB : fallback;
    }

    private int lighten(int color) {
        int r = Math.min(255, ((color >> 16) & 255) + 48);
        int gg = Math.min(255, ((color >> 8) & 255) + 48);
        int b = Math.min(255, (color & 255) + 48);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    private void drawDisc(GuiGraphics g, int cx, int cy, int radius, int highlight, int base) {
        for (int y = -radius; y <= radius; y++) {
            int half = (int)Math.floor(Math.sqrt(Math.max(0, radius * radius - y * y)));
            int color = y < -radius / 3 ? highlight : base;
            g.fill(cx - half, cy + y, cx + half + 1, cy + y + 1, color);
        }
        g.fill(cx - radius / 3, cy - radius / 2, cx + 1, cy - radius / 2 + 1, 0x66FFFFFF);
    }

    private void drawAtmosphereHalo(GuiGraphics g, int cx, int cy, int radius, int argb) {
        int outer = Math.max(radius + 2, radius + Math.max(2, radius / 3));
        int rows = Math.max(12, Math.min(40, radius * 2));
        int sourceAlpha = (argb >>> 24) & 255;
        int alphaMax = Math.max(8, Math.min(72, sourceAlpha / 4));
        for (int row = 0; row < rows; row++) {
            double ny = (row + .5) / rows * 2.0 - 1.0;
            double ellipse = Math.sqrt(Math.max(0.0, 1.0 - ny * ny));
            int outerHalf = (int)(outer * ellipse);
            int innerHalf = (int)(radius * ellipse);
            int alpha = Math.max(1, (int)(alphaMax * (1.0 - Math.abs(ny)) * (1.0 - Math.abs(ny))));
            int color = (alpha << 24) | (argb & 0x00FFFFFF);
            int y = cy - outer + (int)(2.0 * outer * row / rows);
            int h = Math.max(1, 2 * outer / rows);
            g.fill(cx - outerHalf, y, cx - innerHalf, y + h, color);
            g.fill(cx + innerHalf, y, cx + outerHalf + 1, y + h, color);
        }
    }

    /**
     * Screen-space bloom built from narrow horizontal bands. Colors come from the
     * Cosmic Horizons body definition; the shape is an approximation of its glow,
     * not a replacement for CH's shader-based world bloom pipeline.
     */
    private void drawRadialGlow(GuiGraphics g, int cx, int cy, int radius, int bloom, int core) {
        int outer = Math.max(radius + 3, radius * 3);
        int rows = Math.max(12, Math.min(48, radius * 2));
        for (int row = 0; row < rows; row++) {
            double ny = (row + .5) / rows * 2.0 - 1.0;
            double shape = Math.sqrt(Math.max(0.0, 1.0 - ny * ny));
            int half = (int)(outer * shape);
            double falloff = 1.0 - Math.abs(ny);
            int alpha = Math.max(1, (int)(colorAlpha(bloom) * .22 * falloff));
            int color = (alpha << 24) | (bloom & 0x00FFFFFF);
            int y = cy - outer + (int)(2.0 * outer * row / rows);
            g.fill(cx - half, y, cx + half + 1, y + Math.max(1, 2 * outer / rows), color);
        }
        int inner = Math.max(radius + 2, radius + radius / 2);
        int coreAlpha = Math.min(90, Math.max(18, colorAlpha(core) / 3));
        for (int row = 0; row < 12; row++) {
            double ny = (row + .5) / 12.0 * 2.0 - 1.0;
            int half = (int)(inner * Math.sqrt(Math.max(0.0, 1.0 - ny * ny)));
            int alpha = Math.max(1, (int)(coreAlpha * (1.0 - Math.abs(ny))));
            int y = cy - inner + row * Math.max(1, 2 * inner / 12);
            g.fill(cx - half, y, cx + half + 1, y + Math.max(1, 2 * inner / 12),
                    (alpha << 24) | (core & 0x00FFFFFF));
        }
    }

    private int parseCosmicColor(JsonObject data, String key, int fallback) {
        if (data == null || !data.has(key) || data.get(key).isJsonNull()) return fallback;
        JsonElement element = data.get(key);
        try {
            if (element.isJsonPrimitive()) {
                String raw = element.getAsString().trim();
                if (raw.startsWith("#")) raw = raw.substring(1);
                if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);
                long parsed = Long.parseLong(raw, 16);
                return (int)(parsed <= 0xFFFFFFL ? 0xFF000000L | parsed : parsed);
            }
            if (element.isJsonObject()) {
                JsonObject rgb = element.getAsJsonObject();
                int red = Math.max(0, Math.min(255, rgb.get("r").getAsInt()));
                int green = Math.max(0, Math.min(255, rgb.get("g").getAsInt()));
                int blue = Math.max(0, Math.min(255, rgb.get("b").getAsInt()));
                int alpha = rgb.has("a") ? Math.max(0, Math.min(255, rgb.get("a").getAsInt())) :
                        rgb.has("alpha") ? Math.max(0, Math.min(255, rgb.get("alpha").getAsInt())) : 255;
                return (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private int colorAlpha(int argb) { return (argb >>> 24) & 255; }

    /** Render rings only when the active source explicitly marks the body as ringed. */
    private void drawBodyRing(GuiGraphics g, int x, int y, int radius, JsonObject data) {
        drawBodyRing(g, x, y, radius, data, 0.0f);
    }

    private void drawBodyRing(GuiGraphics g, int x, int y, int radius, JsonObject data, float depth) {
        CosmicBodyGuiRenderer.renderRings(g, x, y, radius, data, depth);
    }

    private void drawBodyRing(GuiGraphics g, int x, int y, int radius, JsonObject data,
                              float depth, float yawOffset, float pitchOffset) {
        CosmicBodyGuiRenderer.renderRings(g, x, y, radius, data, depth, yawOffset, pitchOffset);
    }

    private boolean isRinged(JsonObject data) {
        String flag = value(data, "ringed");
        if ("true".equalsIgnoreCase(flag) || "1".equals(flag)) return true;
        return data.has("ring_data") && !data.get("ring_data").isJsonNull();
    }

    private double numeric(JsonObject data, String key, double fallback) {
        try { return data.has(key) ? data.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private void drawParentLinks(GuiGraphics g, List<Node> nodes,
                                 Map<String, Point> projected,
                                 Map<String, Point> orbitAnchors,
                                 boolean perspective) {
        for (Node child : nodes) {
            Node parent = resolveParent(nodes, child);
            if (parent == null) continue;
            Point parentNow = projected.get(parent.id);
            Point parentOrigin = orbitAnchors.get(parent.id);
            Point childOrigin = orbitAnchors.get(child.id);
            if (parentNow == null || parentOrigin == null || childOrigin == null) continue;

            // Keep the orbit's size anchored to the datapack's original
            // positions; only the body marker moves during simulation.
            boolean selectedOrbit = child.id.equals(selectedId) || child.id.equals(selectedNodeId)
                    || parent.id.equals(selectedId) || parent.id.equals(selectedNodeId);
            boolean majorOrbit = isStarNode(parent);
            if (!NavigatorPreferences.showOrbitFor(selectedOrbit, majorOrbit)) continue;
            double radius = Math.max(5,
                    Math.hypot(childOrigin.x - parentOrigin.x, childOrigin.y - parentOrigin.y)
                            * NavigatorPreferences.atlasOrbitSpacing());
            SolarSystemRenderer.orbit(g, parentNow.x, parentNow.y, radius,
                    perspective ? Math.max(3, radius * .34) : radius, 0xA067D8F2);
        }
    }

    private boolean isStarNode(Node node) {
        return node != null && isStarData(node.first());
    }

    private String orbitalParentReference(JsonObject body) {
        String reference = value(body, "orbit_parent_id", "orbit_parent",
                "parent_dimension_id", "satellite_of", "parent_id", "parent",
                "parent_body", "central_body");
        if (!reference.isEmpty()) return reference;
        for (String key : new String[]{"maqan_orbit", "orbit_data", "orbit"}) {
            if (!body.has(key) || !body.get(key).isJsonObject()) continue;
            reference = value(body.getAsJsonObject(key),
                    "orbit_parent_id", "orbit_parent", "parent_id", "parent",
                    "parent_dimension_id", "satellite_of", "parent_body", "central_body");
            if (!reference.isEmpty()) return reference;
        }
        return "";
    }

    /** Parent resolution mirrors the hierarchy builder so tree and orbit links cannot disagree. */
    private Node resolveParent(List<Node> nodes, Node child) {
        String manualParent = NavigatorPreferences.manualParent(universe, child.id);
        if (!manualParent.isEmpty()) {
            for (Node candidate : nodes)
                if (candidate != child && candidate.id.equals(manualParent)) return candidate;
        }
        String reference = orbitalParentReference(child.first());
        if (!reference.isEmpty()) {
            for (Node candidate : nodes) {
                if (candidate == child) continue;
                if (candidate.id.equals(reference)
                        || reference.equals(value(candidate.first(), "id", "catalog_id"))) return candidate;
            }
            Node uniqueDimensionOwner = null;
            boolean ambiguousDimension = false;
            for (Node candidate : nodes) {
                String dimension = value(candidate.first(), "dimension", "dimension_id", "attached_dimension_id");
                if (!reference.equals(dimension)) continue;
                if (uniqueDimensionOwner != null) { ambiguousDimension = true; break; }
                uniqueDimensionOwner = candidate;
            }
            if (!ambiguousDimension && uniqueDimensionOwner != null && uniqueDimensionOwner != child)
                return uniqueDimensionOwner;
        }
        String kind = child.kind.toLowerCase(Locale.ROOT);
        if (kind.equals("moon") || kind.contains("satellite")) {
            Node onlyPlanet = null;
            for (Node candidate : nodes) {
                if (candidate == child || !candidate.kind.equalsIgnoreCase("planet")) continue;
                if (onlyPlanet != null) return null;
                onlyPlanet = candidate;
            }
            return onlyPlanet;
        }
        if (!kind.equals("planet") && !kind.equals("asteroid") && !kind.equals("comet")) return null;
        Node onlyStar = null;
        for (Node candidate : nodes) {
            if (candidate == child || !isStarNode(candidate)) continue;
            if (onlyStar != null) return null;
            onlyStar = candidate;
        }
        return onlyStar;
    }

    private boolean hasPosition(JsonObject o) {
        if (o == null) return false;
        JsonElement e = o.get("map_pos");
        if (e == null) e = o.get("pos");
        if (e != null && e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
            try {
                return Double.isFinite(e.getAsJsonArray().get(0).getAsDouble()) &&
                        Double.isFinite(e.getAsJsonArray().get(1).getAsDouble()) &&
                        Double.isFinite(e.getAsJsonArray().get(2).getAsDouble());
            } catch (Exception ignored) { return false; }
        }
        // Some Cosmic Horizons datapacks keep the authored coordinates as
        // xpos/ypos/zpos rather than pos/map_pos arrays.
        String[] keys = o.has("x") && o.has("y") && o.has("z")
                ? new String[]{"x", "y", "z"} : new String[]{"xpos", "ypos", "zpos"};
        try {
            return o.has(keys[0]) && o.has(keys[1]) && o.has(keys[2]) &&
                    Double.isFinite(o.get(keys[0]).getAsDouble()) &&
                    Double.isFinite(o.get(keys[1]).getAsDouble()) &&
                    Double.isFinite(o.get(keys[2]).getAsDouble());
        } catch (Exception ignored) { return false; }
    }

    private void drawInfo(GuiGraphics g, Node n, int mx, int my) {
        infoPreviewRect = null;
        infoResetRect = null;
        infoOrbitEditRect = null;
        infoTabRects.clear();
        if (n == null) {
            g.drawString(font, "DATA INSPECTOR", infoL + 10, mapT + 8, TEXT, false);
            g.drawString(font, "Nothing selected", infoL + 10, mapT + 28, MUTED, false);
            g.drawString(font, "Select a body from the", infoL + 10, mapT + 48, MUTED, false);
            g.drawString(font, "MAQAN catalogue to inspect it.", infoL + 10, mapT + 60, MUTED, false);
            return;
        }
        com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderDossierCard(
                g, infoL + 5, mapT + 3, infoR - 5, mapT + 68, true);
        g.drawString(font, "OBJECT DOSSIER", infoL + 10, mapT + 8, TEXT, false);
        g.drawString(font, trim(n.label, infoR - infoL - 20), infoL + 10, mapT + 28, GOLD, false);
        if (level == 3 && n.entries.size() == 1) {
            int bx = infoR - 88, by = mapT + 4;
            g.fill(bx, by, bx + 78, by + 16, 0xFF1A3547);
            g.fill(bx, by, bx + 78, by + 1, CYAN);
            g.drawCenteredString(font, "EDIT ORBIT", bx + 39, by + 4, TEXT);
            infoOrbitEditRect = new Rect(bx, by, 78, 16);
        }
        g.drawString(font, n.kind.toUpperCase(Locale.ROOT), infoL + 10, mapT + 42, CYAN, false);
        g.drawString(font, n.entries.size() + " catalogue object(s)", infoL + 10, mapT + 58, MUTED, false);

        boolean showPoliticsTab = isPlanetNode(n);
        String[] tabs = showPoliticsTab
                ? new String[]{"OVERVIEW", "ORBIT", "ENV.", "SOURCE", "POLITICS"}
                : new String[]{"OVERVIEW", "ORBIT", "ENV.", "SOURCE"};
        if (!showPoliticsTab && infoTab > 3) infoTab = 0;
        int tabsLeft = infoL + 8;
        int tabsWidth = Math.max(1, infoR - infoL - 16);
        int tabWidth = tabsWidth / tabs.length;
        int tabsY = mapT + 73;
        for (int i = 0; i < tabs.length; i++) {
            int left = tabsLeft + i * tabWidth;
            int right = i == tabs.length - 1 ? infoR - 8 : left + tabWidth;
            Rect rect = new Rect(left, tabsY, Math.max(1, right - left - 2), 15);
            infoTabRects.add(rect);
            boolean active = infoTab == i;
            boolean hovered = rect.contains(mx, my);
            if (!com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.renderNavigationTab(
                    g, rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, active, hovered)) {
                g.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h,
                        active ? 0xD02A5064 : hovered ? 0xA91B3547 : 0x88303D4D);
                g.fill(rect.x, rect.y, rect.x + rect.w, rect.y + 1,
                        active ? CYAN : 0x665D7895);
            }
            g.drawCenteredString(font, tabs[i], rect.x + rect.w / 2, rect.y + 4,
                    active ? TEXT : MUTED);
        }

        boolean showBodyPreview = n.entries.size() == 1 && infoR - infoL >= 230
                && mapB - mapT >= 250;
        int contentTop = mapT + (showBodyPreview ? 174 : 96);
        if (showBodyPreview) {
            JsonObject preview = n.first();
            // Keep the cube preview inside a deliberately reserved preview slot.
            // Never size it from the full panel height: that caused the model to
            // cover tabs, reset controls and the first rows of profile metadata.
            int slotTop = mapT + 94;
            int slotBottom = mapT + 148;
            int slotHeight = Math.max(1, slotBottom - slotTop);
            int radius = Math.max(10, Math.min(24,
                    Math.min((infoR - infoL - 36) / 4, slotHeight / 2 - 2)));
            int px = (infoL + infoR) / 2;
            int py = slotTop + slotHeight / 2;
            g.enableScissor(infoL + 5, slotTop, infoR - 5, slotBottom);
            drawBodyRing(g, px, py, radius, preview, 1000.0f,
                    (float)infoYawOffset, (float)infoPitchOffset);
            boolean renderedPreview = CosmicBodyGuiRenderer.render(g, px, py, radius, preview,
                    1000.0f, (float)infoYawOffset, (float)infoPitchOffset);
            if (!renderedPreview) drawBodyVisual(g, px, py, radius, preview, kindColor(n.kind), false);
            g.disableScissor();
            infoPreviewRect = new Rect(px - radius - 4, slotTop, radius * 2 + 8, slotHeight);
            int resetX = infoL + 10, resetY = mapT + 151;
            g.fill(resetX, resetY, resetX + 104, resetY + 15, 0xFF1A3547);
            g.drawCenteredString(font, "RESET ROTATION", resetX + 52, resetY + 4, CYAN);
            infoResetRect = new Rect(resetX, resetY, 104, 15);
        }

        List<InfoField> fields = buildInfoFields(n.first(), infoTab);
        int contentBottom = mapB - 10;
        int contentY = contentTop - infoScroll;
        int valueWidth = Math.max(24, infoR - infoL - 20);
        g.enableScissor(infoL + 4, contentTop, infoR - 4, contentBottom);
        for (InfoField field : fields) {
            List<String> lines = wrapInfoValue(field.value(), valueWidth);
            int rowHeight = 14 + lines.size() * 10 + 4;
            if (contentY + rowHeight >= contentTop && contentY <= contentBottom) {
                g.drawString(font, trim(field.label(), valueWidth), infoL + 10, contentY, MUTED, false);
                for (int line = 0; line < lines.size(); line++)
                    g.drawString(font, lines.get(line), infoL + 10, contentY + 11 + line * 10, TEXT, false);
            }
            contentY += rowHeight;
        }
        if (fields.isEmpty()) {
            g.drawString(font, infoTab == 3 ? "No source metadata" : "No data in this section",
                    infoL + 10, contentTop + 8, MUTED, false);
        }
        g.disableScissor();
        int totalContentHeight = 0;
        for (InfoField field : fields)
            totalContentHeight += 18 + wrapInfoValue(field.value(), valueWidth).size() * 10;
        int maxInfoScroll = Math.max(0, totalContentHeight - Math.max(0, contentBottom - contentTop));
        infoScroll = Math.max(0, Math.min(infoScroll, maxInfoScroll));
    }

    private List<String> wrapInfoValue(String value, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String remaining = value == null ? "" : value;
        if (remaining.isEmpty()) return List.of("");
        while (!remaining.isEmpty()) {
            String line = font.plainSubstrByWidth(remaining, Math.max(8, maxWidth));
            if (line.isEmpty()) line = remaining.substring(0, 1);
            lines.add(line);
            remaining = remaining.substring(line.length());
        }
        return lines;
    }

    private boolean isPlanetNode(Node node) {
        if (node == null || node.entries.size() != 1) return false;
        String kind = node.kind == null ? "" : node.kind.toLowerCase(Locale.ROOT);
        return kind.equals("planet") || kind.equals("atlas_body:planet");
    }

    private List<InfoField> buildInfoFields(JsonObject object, int tab) {
        List<InfoField> fields = new ArrayList<>();
        if (object == null) return fields;
        if (tab == 4) {
            com.maqan.galacticnavigator.data.PlanetPoliticalMetadata politics =
                    com.maqan.galacticnavigator.data.PlanetPoliticalMetadata.from(object);
            if (politics == null) {
                fields.add(new InfoField("Planet-wide sovereignty", "No verified full-planet control data"));
                fields.add(new InfoField("Display rule", "Regional claims are not presented as ownership of the entire planet"));
            } else {
                fields.add(new InfoField("Owner", politics.owner()));
                fields.add(new InfoField("Political system", politics.government().isBlank() ? "Not specified" : politics.government()));
                if (!politics.capital().isBlank()) fields.add(new InfoField("Capital", politics.capital()));
                fields.add(new InfoField("Control", politics.controlStatus()));
                if (!politics.source().isBlank()) fields.add(new InfoField("Source", politics.source()));
                if (!politics.updated().isBlank()) fields.add(new InfoField("Last verified", politics.updated()));
            }
            return fields;
        }
        if (tab == 3) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement element = entry.getValue();
                if (element == null || element.isJsonNull()) continue;
                String raw = element.isJsonPrimitive() ? element.getAsString() : element.toString();
                raw = raw.replace("\n", " ").replace("\r", " ");
                fields.add(new InfoField(entry.getKey(), raw));
            }
            return fields;
        }

        String[][] overview = {
                {"Galaxy", "galaxy_name", "galaxy", "galaxy_id"},
                {"Sector", "sector_name", "sector", "sector_id"},
                {"System", "system_name", "system", "system_id"},
                {"Dimension", "dimension", "dimension_id", "attached_dimension_id"},
                {"Travel dimension", "travel_dimension"},
                {"Unlocking dimension", "unlocking_dimension"},
                {"GUI category", "gui_category_title", "gui_category"},
                {"Object category", "category", "type", "kind"},
                {"Conditions", "conditions"}
        };
        String[][] orbit = {
                {"Travel X", "travel_x"}, {"Travel Y", "travel_y"}, {"Travel Z", "travel_z"},
                {"Rotation period", "rotation_period", "day_length"},
                {"Axial tilt", "axial_tilt", "obliquity"},
                {"Orbital period", "orbital_period"}, {"Radius", "radius"}, {"Mass", "mass"}
        };
        String[][] environment = {
                {"Life", "life"}, {"Physical scale", "scale", "size"},
                {"Atmosphere / space", "space", "atmosphere"},
                {"Atmosphere", "atmosphere", "has_atmosphere", "atmosphere_y"},
                {"Gravity", "gravity"}, {"Temperature", "temperature"},
                {"Rings", "ringed"}
        };
        String[][] selected = tab == 0 ? overview : tab == 1 ? orbit : environment;
        for (String[] field : selected) {
            String fieldValue = value(object, Arrays.copyOfRange(field, 1, field.length));
            if (!fieldValue.isEmpty()) fields.add(new InfoField(field[0], fieldValue));
        }
        if (tab == 1) {
            if (hasPosition(object)) {
                fields.add(new InfoField("Position · source coordinates",
                        String.format(Locale.ROOT, "%.2f / %.2f / %.2f",
                                coordinate(object, 0), coordinate(object, 1), coordinate(object, 2))));
            }
            String travel = value(object, "travel_pos");
            if (!travel.isEmpty()) fields.add(new InfoField("Travel target", travel));
            String parent = value(object, "parent", "parent_id", "orbit_parent", "orbit_parent_id");
            if (!parent.isEmpty()) fields.add(new InfoField("Orbital parent", parent));
        }
        if (tab == 2) {
            for (String key : new String[]{"ring_data", "atmosphere_data", "environment", "biomes"}) {
                if (object.has(key) && !object.get(key).isJsonNull())
                    fields.add(new InfoField(key, object.get(key).toString()));
            }
        }
        return fields;
    }

    private void drawHoverCard(GuiGraphics g, Node n, int mx, int my) {
        int w = Math.min(232, mapR - mapL - 20), h = 54;
        int x = Math.max(mapL + 6, Math.min(mapR - w - 6, mx + 14));
        int y = Math.max(mapT + 24, Math.min(mapB - h - 6, my + 12));
        g.fill(x, y, x + w, y + h, 0xF0182B3B);
        g.fill(x, y, x + w, y + 1, CYAN);
        g.drawString(font, trim(n.label, w - 12), x + 7, y + 7, GOLD, false);
        g.drawString(font, n.kind + " · " + n.entries.size() + " object(s)", x + 7, y + 22, MUTED, false);
        JsonObject data = n.first();
        String dimension = value(data, "dimension", "dimension_id", "attached_dimension_id");
        String detail = !dimension.isEmpty() ? dimension : "Select to inspect metadata";
        g.drawString(font, trim(detail, w - 12), x + 7, y + 37, TEXT, false);
    }

    private String trim(String s, int maxWidth) {
        String out = s == null ? "" : s;
        if (maxWidth <= 0) return "";
        if (font.width(out) <= maxWidth) return out;
        String ellipsis = "…";
        int limit = Math.max(0, maxWidth - font.width(ellipsis));
        while (!out.isEmpty() && font.width(out) > limit)
            out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "" : out + ellipsis;
    }

    private static int kindColor(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "star" -> 0xFFFFD878;
            case "moon", "satellite" -> 0xFFB8C8D8;
            case "station" -> 0xFFFFB45E;
            case "nebula" -> 0xFFAA83D8;
            case "blackhole" -> 0xFF8A75B8;
            case "comet" -> 0xFF8DE2E8;
            case "asteroid" -> 0xFFB49B7C;
            case "neutron star", "white dwarf", "red giant", "protostar", "quasar" -> 0xFFFFD878;
            case "planet" -> 0xFF63BBD7;
            default -> 0xFF8B9CCB;
        };
    }

    private int toolsMenuTop() { return toolsMenuAnchorY; }
    /** Includes waypoint and surface-map controls through menuY + 312 with their full hitboxes. */
    private int toolsMenuBottom() { return Math.min(height - 2, toolsMenuTop() + 336); }

    private void layoutPanels() {
        // The global MAQAN rail owns the first 44 pixels. Recompute the existing
        // Atlas layout in the remaining width instead of letting map/list content
        // sit underneath the shared navigation.
        int railReserve = embeddedInMaqan ? 48 : 0;
        int layoutWidth = Math.max(120, width - railReserve);
        int[] nativeLayout = com.maqan.galacticnavigator.navigation.NavigatorNativeBridge.computePanelLayout(
                layoutWidth, height, showList, showInfo, NavigatorPreferences.listPanelWidth(),
                NavigatorPreferences.infoPanelWidth(), NavigatorPreferences.panelOrder());
        if (nativeLayout != null) {
            mapT = nativeLayout[0]; mapB = nativeLayout[1];
            listL = nativeLayout[2] + railReserve; listR = nativeLayout[3] + railReserve;
            infoL = nativeLayout[4] + railReserve; infoR = nativeLayout[5] + railReserve;
            mapL = nativeLayout[6] + railReserve; mapR = nativeLayout[7] + railReserve;
            return;
        }
        int[] fallback = WorkspaceLayout.compute(layoutWidth, height, NavigatorPreferences.workspace());
        if (fallback == null) {
            mapT = mapB = 0;
            listL = listR = infoL = infoR = mapL = mapR = 0;
            return;
        }
        mapT = fallback[0]; mapB = fallback[1];
        listL = fallback[2] + railReserve; listR = fallback[3] + railReserve;
        infoL = fallback[4] + railReserve; infoR = fallback[5] + railReserve;
        mapL = fallback[6] + railReserve; mapR = fallback[7] + railReserve;
    }

    private String panelAt(double x, double y) {
        if (y < mapT || y > mapT + 22) return null;
        if (listR > listL && x >= listL && x <= listR) return "list";
        if (infoR > infoL && x >= infoL && x <= infoR) return "info";
        if (mapR > mapL && x >= mapL && x <= mapR) return "map";
        return null;
    }

    private void reorderPanels(String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        String[] order = NavigatorPreferences.panelOrder();
        int a = Arrays.asList(order).indexOf(from);
        int b = Arrays.asList(order).indexOf(to);
        if (a < 0 || b < 0) return;
        String value = order[a];
        order[a] = order[b];
        order[b] = value;
        NavigatorPreferences.setPanelOrder(order);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        // Popups are modal: resolve them before any underlying panel, preview,
        // map object, or native widget can receive the same pointer event.
        if (contextOpen) {
            Rect menu = contextMenuBounds();
            if (button == 0 && menu.contains(x, y)) {
                int row = (int)((y - menu.y - 20) / 18);
                if (row >= 0 && row <= 3) runContextAction(row);
                else contextOpen = false;
                return true;
            }
            contextOpen = false;
            pendingContextNodeId = null;
            return true;
        }
        if (systemPickerOpen) {
            if (button == 0) return selectSystemAt(x, y);
            systemPickerOpen = false;
            return true;
        }
        if (button == 0) {
            for (BreadcrumbHit hit : breadcrumbHits) {
                if (hit.bounds().contains(x, y)) {
                    navigateBreadcrumb(hit.action());
                    return true;
                }
            }
        }
        if (toolsOpen) {
            boolean insideMenu = x >= width - 140 && x <= width - 8
                    && y >= toolsMenuTop() && y <= toolsMenuBottom();
            boolean onToggle = toolsButton != null && x >= toolsButton.getX()
                    && x <= toolsButton.getX() + toolsButton.getWidth()
                    && y >= toolsButton.getY() && y <= toolsButton.getY() + toolsButton.getHeight();
            if (!insideMenu && !onToggle) {
                toolsOpen = false;
                updateToolVisibility();
                if (toolsButton != null) toolsButton.setMessage(Component.literal("⚙"));
                return true;
            }
            if (button != 0 && insideMenu) return true;
        }
        if (button == 0) {
            for (RouteLegendHit hit : routeLegendHits) {
                if (hit.bounds().contains(x, y)) {
                    routeFilter = hit.filter();
                    return true;
                }
            }
            for (int i = 0; i < infoTabRects.size(); i++) {
                if (infoTabRects.get(i).contains(x, y)) {
                    infoTab = i;
                    infoScroll = 0;
                    return true;
                }
            }
        }
        if (button == 0 && infoResetRect != null && infoResetRect.contains(x, y)) {
            infoYawOffset = 0.0; infoPitchOffset = 0.0;
            return true;
        }
        if (button == 0 && infoOrbitEditRect != null && infoOrbitEditRect.contains(x, y)) {
            if (selectedId != null) openNavigatorSettings();
            return true;
        }
        if (button == 0 && infoPreviewRect != null && infoPreviewRect.contains(x, y)
                && infoL <= x && x <= infoR && mapT <= y && y <= mapB) {
            rotatingInfoPreview = true;
            return true;
        }
        if (super.mouseClicked(x, y, button)) return true;
        if (button == 0 && !toolsOpen && !systemPickerOpen) {
            if (listR > listL && Math.abs(x - listR) <= 5 && y >= mapT + 24 && y <= mapB) {
                resizingPanel = "list"; panelResizeStartX = x;
                panelResizeStartWidth = NavigatorPreferences.listPanelWidth(); return true;
            }
            if (infoR > infoL && Math.abs(x - infoR) <= 5 && y >= mapT + 24 && y <= mapB) {
                resizingPanel = "info"; panelResizeStartX = x;
                panelResizeStartWidth = NavigatorPreferences.infoPanelWidth(); return true;
            }
        }
        if (button == 0 && !toolsOpen && !systemPickerOpen) {
            dragPanel = panelAt(x, y);
            if (dragPanel != null) {
                panelPressX = x; panelPressY = y; panelDragStarted = false;
                return true;
            }
        }
        if (button == 0 && level == 3 && bodyViewId != null
                && x >= mapL && x <= mapR && y >= mapT && y <= mapB) {
            Point bodyPoint = mapPoints.get(bodyViewId);
            if (bodyPoint != null
                    && Math.hypot(x - bodyPoint.x, y - bodyPoint.y) <= Math.max(14, bodyRotationHitRadius)) {
                rotatingBody = true;
                return true;
            }
        }
        if (button == 1 && infoR > infoL && x >= infoL && x <= infoR
                && y >= mapT && y <= mapB && level == 3 && selectedId != null) {
            openNavigatorSettings();
            return true;
        }
        if (button == 1 && showList && x >= listL && x <= listR
                && y >= mapT + 26 && y <= mapB) {
            for (Map.Entry<String, Rect> entry : listRects.entrySet()) {
                if (!entry.getValue().contains(x, y)) continue;
                Node selected = findNode(entry.getKey());
                if (selected != null && level == 3) {
                    selectBody(selected.id);
                    contextNodeId = selected.id;
                    pendingContextNodeId = selected.id;
                    rightPressX = x; rightPressY = y;
                    return true;
                }
            }
        }
        if (button == 1 && x >= mapL && x <= mapR && y >= mapT && y <= mapB) {
            Node nearest = null;
            double best = Double.MAX_VALUE;
            for (Map.Entry<String, Point> entry : mapPoints.entrySet()) {
                Point p = entry.getValue();
                double distance = Math.hypot(x - p.x, y - p.y);
                if (distance <= Math.max(14, p.radius + 7) && distance < best) {
                    nearest = findNode(entry.getKey());
                    best = distance;
                }
            }
            pendingContextNodeId = nearest == null ? null : nearest.id;
            if (nearest != null) {
                if (level == 3) selectBody(nearest.id); else selectNode(nearest);
                contextNodeId = nearest.id;
            }
            rightPressX = x; rightPressY = y;
            rightMouseDragging = true;
            return true;
        }
        if (button == 0) {
            for (Map.Entry<String, Rect> entry : listRects.entrySet()) {
                if (entry.getValue().contains(x, y)) {
                    Node n = findNode(entry.getKey());
                    if (n != null) {
                        long now = System.currentTimeMillis();
                        boolean doubleClick = entry.getKey().equals(lastClickedId) && now - lastClickTime < 360;
                        if (level == 3) selectBody(entry.getKey()); else selectNode(n);
                        if (doubleClick && level < 3) openSelected();
                        lastClickedId = entry.getKey(); lastClickTime = now;
                    }
                    return true;
                }
            }
            Map.Entry<String, Point> nearestHit = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Map.Entry<String, Point> entry : mapPoints.entrySet()) {
                Point p = entry.getValue();
                double distance = Math.hypot(x - p.x, y - p.y);
                boolean bodyHit = entry.getKey().contains("::");
                boolean nearestIsSystem = nearestHit != null && !nearestHit.getKey().contains("::");
                if (distance <= Math.max(8, p.radius + 5)
                        && (distance < nearestDistance || (Math.abs(distance - nearestDistance) < 0.001
                        && bodyHit && nearestIsSystem))) {
                    nearestHit = entry;
                    nearestDistance = distance;
                }
            }
            if (nearestHit != null) {
                Node n = findNode(nearestHit.getKey());
                if (n != null) {
                    long now = System.currentTimeMillis();
                    boolean doubleClick = nearestHit.getKey().equals(lastClickedId)
                            && now - lastClickTime < 360;
                    if (level == 3) selectBody(nearestHit.getKey()); else selectNode(n);
                    if (doubleClick && level < 3) openSelected();
                    lastClickedId = nearestHit.getKey(); lastClickTime = now;
                }
                return true;
            }
            if (x >= mapL && x <= mapR && y >= mapT && y <= mapB) {
                mapPanDragging = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) {
            rotatingBody = false;
            rotatingInfoPreview = false;
            resizingPanel = null;
            mapPanDragging = false;
            if (dragPanel != null) {
                if (panelDragStarted && panelDropTarget != null && !panelDropTarget.equals(dragPanel))
                    reorderPanels(dragPanel, panelDropTarget);
                draggingPanelReset();
            }
        }
        if (button == 1) {
            if (pendingContextNodeId != null
                    && Math.hypot(x - rightPressX, y - rightPressY) <= 6) {
                contextNodeId = pendingContextNodeId;
                contextX = (int)rightPressX;
                contextY = (int)rightPressY;
                contextOpen = true;
                contextOpenedAtNanos = com.maqan.galacticnavigator.navigation.MaqanAnimationClock.frameNanos();
            }
            pendingContextNodeId = null;
            rightMouseDragging = false;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (contextOpen || systemPickerOpen) return true;
        if (resizingPanel != null && button == 0) {
            int desired = (int)Math.round(panelResizeStartWidth + x - panelResizeStartX);
            if ("list".equals(resizingPanel)) NavigatorPreferences.setListPanelWidth(desired);
            else if ("info".equals(resizingPanel)) NavigatorPreferences.setInfoPanelWidth(desired);
            layoutPanels();
            return true;
        }
        if (dragPanel != null && button == 0) {
            if (!panelDragStarted && Math.hypot(x - panelPressX, y - panelPressY) >= 6)
                panelDragStarted = true;
            if (panelDragStarted) panelDropTarget = panelAt(x, y);
            return true;
        }
        if (rotatingBody && button == 0 && level == 3 && bodyViewId != null) {
            bodyYawOffset = Math.IEEEremainder(bodyYawOffset + dx * 0.012, Math.PI * 2.0);
            bodyPitchOffset = Math.max(-Math.PI / 2.0,
                    Math.min(Math.PI / 2.0, bodyPitchOffset + dy * 0.012));
            return true;
        }
        if (rotatingInfoPreview && button == 0) {
            infoYawOffset = Math.IEEEremainder(infoYawOffset + dx * 0.012, Math.PI * 2.0);
            infoPitchOffset = Math.max(-Math.PI / 2.0,
                    Math.min(Math.PI / 2.0, infoPitchOffset + dy * 0.012));
            return true;
        }
        if (mapPanDragging && button == 0) {
            panX += dx; panY += dy;
            return true;
        }
        if (rightMouseDragging && button == 1) {
            if (pendingContextNodeId != null && Math.hypot(x - rightPressX, y - rightPressY) > 6)
                pendingContextNodeId = null;
            if (level == 3 && mapMode == 2) {
                cameraYaw += dx * 0.008;
                cameraPitch = Math.max(-1.35, Math.min(1.35, cameraPitch + dy * 0.008));
            } else {
                panX += dx; panY += dy;
            }
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (contextOpen) return true;
        if (toolsOpen && x >= width - 140 && x <= width - 8
                && y >= toolsMenuTop() && y <= toolsMenuBottom()) return true;
        if (delta == 0.0) return false;
        if (systemPickerOpen) {
            int pickerX = universeButton == null ? 100 : universeButton.getX();
            int pickerY = 66;
            int pickerW = Math.min(320, width - pickerX - 12);
            int maxRows = Math.max(1, Math.min(12, (height - pickerY - 48) / 22));
            int pickerH = Math.min(filteredSystems().size(), maxRows) * 22 + 30;
            if (x >= pickerX && x <= pickerX + pickerW
                    && y >= pickerY && y <= pickerY + pickerH) {
                List<String> systems = filteredSystems();
                systemPickerScroll = Math.max(0, Math.min(
                        Math.max(0, systems.size() - maxRows),
                        systemPickerScroll - (int)Math.signum(delta) * 3));
            }
            return true;
        }
        if (showList && listR > listL && x >= listL && x <= listR
                && y >= mapT && y <= mapB) {
            int visibleRows = Math.max(1, (mapB - 5 - (mapT + 26)) / 29);
            int maxScroll = Math.max(0, currentNodes().size() - visibleRows);
            listScroll = Math.max(0, Math.min(maxScroll,
                    listScroll - (int)Math.signum(delta) * 3));
            return true;
        }
        if (showInfo && infoR > infoL && x >= infoL && x <= infoR
                && y >= mapT && y <= mapB && selectedId != null) {
            int maxScroll = Math.max(0, infoContentHeight() - infoViewportHeight());
            infoScroll = Math.max(0, Math.min(maxScroll,
                    infoScroll - (int)Math.signum(delta) * 24));
            return true;
        }
        if (mapR > mapL && x >= mapL && x <= mapR && y >= mapT && y <= mapB) {
            double oldZoom = zoom;
            double factor = Math.pow(1.12, delta);
            if (Double.isFinite(factor)) {
                zoom = Math.max(.025, Math.min(64.0, zoom * factor));
                double ratio = zoom / oldZoom;
                double anchorX = x - (mapL + mapR) * .5;
                double anchorY = y - (mapT + mapB) * .5;
                panX = anchorX - (anchorX - panX) * ratio;
                panY = anchorY - (anchorY - panY) * ratio;
            }
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    private int infoViewportHeight() {
        JsonObject data = selectedId == null ? null : findObject(selectedId);
        boolean preview = data != null && infoR - infoL >= 230 && mapB - mapT >= 250;
        int top = mapT + (preview ? 174 : 96);
        return Math.max(0, mapB - 10 - top);
    }

    private int infoContentHeight() {
        JsonObject data = selectedId == null ? null : findObject(selectedId);
        if (data == null) return 0;
        int width = Math.max(24, infoR - infoL - 20);
        int total = 0;
        for (InfoField field : buildInfoFields(data, infoTab))
            total += 18 + wrapInfoValue(field.value(), width).size() * 10;
        return total;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_F5) {
            if (minecraft != null) minecraft.reloadResourcePacks();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && search != null && !search.isFocused() && level > 0) {
            goBack(); return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
