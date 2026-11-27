package dev.worldforge.mod.client;

import dev.worldforge.mod.MapImages;
import dev.worldforge.mod.Markers;
import dev.worldforge.mod.TitleData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Большая карта: режимы как в Paradox (рельеф, провинции...империи, государства), колесо = масштаб, перетаскивание, клик по провинции
 * показывает цепочку титулов и государство, метки сервера и личные вейпойнты (B), Shift+ПКМ = телепорт для оператора.
 */
public class MapScreen extends Screen {
    private static final int BAR = 0, BOTTOM = 46;
    // A small UUID index is rebuilt at most once per five client ticks, never once per frame.
    private static net.minecraft.client.multiplayer.ClientLevel leaderIndexLevel;
    private static long leaderIndexTick = Long.MIN_VALUE;
    private static Map<String, LivingEntity> leaderBodyIndex = Map.of();
    private boolean embeddedInMaqan;
    public void setEmbeddedInMaqan(boolean embedded) { embeddedInMaqan = embedded; }
    private static final String[][] PROP_NAMES = {{"culture", "Культуры"}, {"religion", "Религии"}, {"faith", "Религии"}, {"race", "Народы"}, {"people", "Народы"}, {"house", "Дома"}, {"dynasty", "Династии"}, {"development", "Развитие"}, {"climate", "Климат"}};
    private double camX, camY, zoom;
    private boolean fitted;
    private int selected;                 // выбранная провинция
    private int hoverLabel = -1, selLabel = -1;
    private int hoverProv;
    private ResourceLocation ovId;
    private int ovX, ovY, ovW, ovH;
    private int ovCount;
    private EditBox nameBox;
    private int pendingX, pendingZ;
    private final List<Slot> slots = new ArrayList<>();
    private final SidePanel panel = new SidePanel(this);
    private EditBox argBox;
    private ClientAction pendingAction;
    private String pendingTarget = "";
    private long lastEditApply;
    private boolean dragOverview;
    private boolean draggingMap;
    private int mapDragButton = -1;
    private String selectedMarkerId = "";
    private int hiddenMarkerRevision = Integer.MIN_VALUE;
    private java.util.Set<String> hiddenArmyMarkerIds = java.util.Set.of();
    private int ovwX, ovwY, ovwW, ovwH;

    private static final class Slot {
        final String key; final int x, y, size;
        Slot(String key, int x, int y, int size) { this.key = key; this.x = x; this.y = y; this.size = size; }
        boolean hit(double mx, double my) { return mx >= x && my >= y && mx < x + size && my < y + size; }
    }
    public MapScreen() {
        super(Component.literal("Карта"));
    }

    // ------------------------------------------------------------------ инициализация

    @Override
    protected void init() {
        slots.clear();
        List<String> modes = modes();
        int size = 28, gap = 3, sep = 8;
        int columns = Math.max(1, Math.min(modes.size(), (width - 24 + gap) / (size + gap + 1)));
        int rows = Math.max(1, (modes.size() + columns - 1) / columns);
        int y0 = Math.max(8, height - rows * size - (rows - 1) * 5 - 10);
        for (int i = 0; i < modes.size(); i++) {
            int row = i / columns, column = i % columns;
            int rowCount = Math.min(columns, modes.size() - row * columns);
            int rowWidth = rowCount * (size + gap) - gap;
            int x = Math.max(8, (width - rowWidth) / 2);
            int y = y0 + row * (size + 5);
            // Preserve visual grouping while keeping every hit target inside the viewport.
            int previousGroup = column == 0 ? -1 : group(modes.get(i - 1));
            int currentGroup = group(modes.get(i));
            if (column > 0 && previousGroup != currentGroup) x += Math.min(sep, Math.max(0, width - x - rowWidth));
            for (int j = 0; j < column; j++) {
                if (group(modes.get(row * columns + j)) != group(modes.get(row * columns + j + 1)))
                    x += Math.min(sep, 4);
            }
            slots.add(new Slot(modes.get(i), x + column * (size + gap), y, size));
        }
        nameBox = new EditBox(font, width / 2 - 100, height - 78, 200, 18, Component.literal("Название"));
        nameBox.setMaxLength(32);
        nameBox.setVisible(false);
        nameBox.setHint(Component.literal("Название метки, Enter"));
        addRenderableWidget(nameBox);
        argBox = new EditBox(font, width / 2 - 130, height - 78, 260, 18, Component.literal("Аргументы"));
        argBox.setMaxLength(120);
        argBox.setVisible(false);
        addRenderableWidget(argBox);
        MapTextures.ensure(ClientState.mode);
    }

    // ---- доступ для боковой панели
    int width() { return width; }
    int height() { return height; }
    net.minecraft.client.gui.Font font() { return font; }
    int selectedProv() { return selected; }
    int panelBottom() { return ovwW > 0 ? ovwY - 14 : height - BOTTOM - 20; }

    /** Открывает вкладку панели по имени (TITLE, REALM, DIP, EDIT, MARKS); для предпросмотра и горячих клавиш. */
    void showTab(String name) { panel.show(SidePanel.Tab.valueOf(name)); }

    void command(String cmd) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(cmd);
    }

    void selectProvince(int prov) {
        if (prov <= 0 || !MapTextures.ready()) return;
        selected = prov;
        selLabel = labelOfProvince(prov);
        setOverlay(selLabel);
        TitleData.Realm r = MapTextures.titles.realmOf(MapTextures.titles.get(prov));
        if (MapImages.isDip(ClientState.mode) && r != null && !ClientState.mode.equals(MapImages.DIP + r.id)) setMode(MapImages.DIP + r.id, true);
        panel.onSelected(prov);
        TitleData.Title selectedTitle = MapTextures.titles == null ? null : MapTextures.titles.get(prov);
        TitleData.Realm selectedRealm = selectedTitle == null ? null : MapTextures.titles.realmOf(selectedTitle);
        if (selectedRealm != null) {
            PlatformWorkspaceBridge.publish("wf_realms", selectedRealm.id, "realm",
                    selectedRealm.name, "Selected on political map · province " + prov);
        } else {
            PlatformWorkspaceBridge.publish("worldforge", "province:" + prov, "province",
                    selectedTitle == null ? "Province " + prov : selectedTitle.name,
                    "Selected on political map");
        }
        centerOnProvince(prov);
    }

    void centerOnProvince(int prov) {
        TitleData.Title t = MapTextures.titles == null ? null : MapTextures.titles.get(prov);
        if (t != null) centerOnBlock(t.capX, t.capZ);
    }

    void centerOnBlock(double bx, double bz) {
        if (!MapTextures.ready()) return;
        zoom = Math.max(zoom, 1.5);
        double[] s = blockToScreen(bx, bz);
        camX += (width - 250) / 2.0 - s[0];
        camY += height / 2.0 - s[1];
    }

    /** Кнопка действия: без аргументов выполняется сразу, с аргументами открывает строку ввода (аргументы через |). */
    void runAction(ClientAction a, String target) {
        if (a.params.isEmpty()) {
            command("wfaction run " + a.id + " " + target);
            return;
        }
        pendingAction = a;
        pendingTarget = target;
        argBox.setValue("");
        argBox.setHint(Component.literal(a.label + ": " + String.join(" | ", a.params) + ", Enter"));
        argBox.setVisible(true);
        setFocused(argBox);
        argBox.setFocused(true);
    }

    private static int group(String key) {
        if (MapImages.TERRAIN.equals(key)) return 0;
        if (MapImages.REALMS.equals(key) || MapImages.isDip(key)) return 2;
        return MapImages.isProp(key) ? 3 : 1;
    }

    private static List<String> modes() {
        if (ClientState.state == null) return new ArrayList<>(List.of(MapImages.TERRAIN));
        return MapImages.modes(ClientState.state.titles);
    }

    private static String modeName(String key) {
        if (MapImages.TERRAIN.equals(key)) return "Рельеф";
        if (MapImages.REALMS.equals(key)) return "Государства";
        if (MapImages.isDip(key)) return "Дипломатия";
        if (MapImages.isProp(key)) {
            String k = key.substring(2);
            String dn = ClientState.state == null ? null : MapImages.propName(ClientState.state.titles, k);
            if (dn != null) return dn;
            for (String[] pn : PROP_NAMES) if (pn[0].equals(k)) return pn[1];
            return k;
        }
        if (ClientState.state != null) for (TitleData.Tier t : ClientState.state.titles.tiers) if (t.key.equals(key)) return t.name;
        return key;
    }

    private void setMode(String key) {
        if (MapImages.DIP.equals(key) && selected > 0 && MapTextures.ready()) {            // слот дипломатии: фокус на государстве выбранной земли
            TitleData.Realm r = MapTextures.titles.realmOf(MapTextures.titles.get(selected));
            if (r != null) key = MapImages.DIP + r.id;
        }
        setMode(key, false);
    }

    void setMode(String key, boolean keepSelection) {
        ClientState.mode = key;
        if (!keepSelection) {
            selected = 0;
            hoverLabel = selLabel = -1;
            freeOverlay();
        }
        MapTextures.ensure(key);
    }

    private static boolean isCurrent(String slotKey) {
        String m = ClientState.mode;
        return slotKey.equals(m) || (MapImages.isDip(slotKey) && MapImages.isDip(m));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        freeOverlay();
        super.removed();
    }

    // ------------------------------------------------------------------ вспомогательное

    private double bpp() { return MapTextures.titles.bpp; }

    private double[] blockToScreen(double bx, double bz) {
        double px = bx / bpp() + MapTextures.w / 2.0, pz = bz / bpp() + MapTextures.h / 2.0;
        return new double[]{camX + px * zoom, camY + pz * zoom};
    }

    private int[] screenToPixel(double mx, double my) {
        return new int[]{(int) Math.floor((mx - camX) / zoom), (int) Math.floor((my - camY) / zoom)};
    }

    private void fit() {
        int bottomReserve = embeddedInMaqan ? 0 : BOTTOM;
        double zx = (width - 24) / (double) MapTextures.w, zy = (height - BAR - bottomReserve - 14) / (double) MapTextures.h;
        zoom = Math.min(zx, zy);
        camX = (width - MapTextures.w * zoom) / 2;
        camY = BAR + (height - BAR - bottomReserve - MapTextures.h * zoom) / 2;
        fitted = true;
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !MapTextures.ready()) return;
        double[] s = blockToScreen(mc.player.getX(), mc.player.getZ());
        zoom = Math.max(zoom, 2.0);
        s = blockToScreen(mc.player.getX(), mc.player.getZ());
        camX += width / 2.0 - s[0];
        camY += height / 2.0 - s[1];
    }

    private void freeOverlay() {
        if (ovId != null) Minecraft.getInstance().getTextureManager().release(ovId);
        ovId = null;
    }

    private void setOverlay(int label) {
        freeOverlay();
        if (label <= 0 || !MapTextures.ready()) return;
        MapImages.Overlay o = MapImages.overlay(MapTextures.titles, MapTextures.mode, label);
        if (o == null) return;
        com.mojang.blaze3d.platform.NativeImage ni = new com.mojang.blaze3d.platform.NativeImage(o.w, o.h, false);
        for (int z = 0; z < o.h; z++) {
            for (int x = 0; x < o.w; x++) {
                int a = o.argb[z * o.w + x];
                ni.setPixelRGBA(x, z, (a & 0xFF00FF00) | ((a & 0xFF) << 16) | ((a >> 16) & 0xFF));
            }
        }
        DynamicTexture t = new DynamicTexture(ni);
        ovId = new ResourceLocation("worldforge", "overlay/" + (ovCount++));
        Minecraft.getInstance().getTextureManager().register(ovId, t);
        ovX = o.x; ovY = o.y; ovW = o.w; ovH = o.h;
    }

    private int labelOfProvince(int prov) {
        int[] lut = MapTextures.lut;
        return lut == null || prov <= 0 || prov >= lut.length ? 0 : lut[prov];
    }

    // ------------------------------------------------------------------ рисование

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        MapTextures.ensure(ClientState.mode);
        g.fill(0, 0, width, height, 0xFF12141B);
        if (!MapTextures.ready()) {
            g.drawCenteredString(font, ClientState.state == null ? "На этой планете нет политической карты" : "Строю карту…", width / 2, height / 2, 0xFFE8E2D0);
            super.render(g, mx, my, pt);
            return;
        }
        if (!fitted) fit();
        double centerPixelX = (width * 0.5 - camX) / zoom;
        double centerPixelZ = (height * 0.5 - camY) / zoom;
        double mapBpp = bpp();
        PlatformWorkspaceBridge.publishMapViewport(
                (centerPixelX - MapTextures.w * 0.5) * mapBpp,
                (centerPixelZ - MapTextures.h * 0.5) * mapBpp, zoom,
                MapImages.TERRAIN.equals(ClientState.mode) ? "GEOGRAPHIC" : "POLITICAL");
        int mxp = (int) camX, myp = (int) camY, mw = (int) (MapTextures.w * zoom), mh = (int) (MapTextures.h * zoom);
        g.fill(mxp + 5, myp + 6, mxp + mw + 5, myp + mh + 6, 0x66000000);                 // тень под картой
        g.pose().pushPose();
        g.pose().translate((float) camX, (float) camY, 0f);
        g.pose().scale((float) zoom, (float) zoom, 1f);
        g.blit(MapTextures.id, 0, 0, 0f, 0f, MapTextures.w, MapTextures.h, MapTextures.w, MapTextures.h);
        if (ovId != null) g.blit(ovId, ovX, ovY, 0f, 0f, ovW, ovH, ovW, ovH);
        g.pose().popPose();
        g.renderOutline(mxp - 2, myp - 2, mw + 4, mh + 4, 0xFF000000);                    // рамка: чёрный кант и золотая линия
        g.renderOutline(mxp - 4, myp - 4, mw + 8, mh + 8, 0x8CF0C85A);

        drawLabels(g);
        drawMarkers(g, mx, my);

        int prov = pixelProvince(mx, my);
        boolean overUi = overUi(mx, my);
        if (!overUi) {
            int lab = prov == 0 ? 0 : labelOfProvince(prov);
            if (lab != hoverLabel) {
                hoverLabel = lab;
                if (selLabel <= 0) setOverlay(lab);
            }
            hoverProv = prov;
        } else hoverProv = 0;

        drawPlate(g);
        if (embeddedInMaqan) drawMapTypeBar(g, mx, my);
        drawLegend(g);
        drawOverview(g);
        if (!embeddedInMaqan) {
            drawScale(g);
            drawHotbar(g, mx, my);
            drawCoords(g, mx, my);
        }
        panel.render(g, mx, my);
        drawSelectedMarkerInspector(g);
        super.render(g, mx, my, pt);
        if (hoverProv > 0 && !overUi) {
            List<Component> tip = describe(hoverProv, false);
            if (!tip.isEmpty()) g.renderComponentTooltip(font, tip, mx, my);
        }
        Markers.Marker hoveredMarker = markerAt(mx, my);
        if (hoveredMarker != null && !overUi)
            g.renderComponentTooltip(font, describeMarker(hoveredMarker), mx, my);
        if (!embeddedInMaqan) for (Slot sl : slots) if (sl.hit(mx, my)) g.renderTooltip(font, Component.literal(isCurrent(sl.key) ? modeName(sl.key) : (slots.indexOf(sl) + 1) + ": " + modeName(sl.key)), mx, my);
    }

    // ---- оформление: рамки со скосом, как у слотов инвентаря
    static void bevel(GuiGraphics g, int x, int y, int w, int h, boolean inset) {
        WFUi.bevel(g, x, y, w, h, inset);
    }

    private boolean overUi(int mx, int my) {
        if (!embeddedInMaqan && !slots.isEmpty()) {
            for (Slot sl : slots) if (sl.hit(mx, my)) return true;
            // Treat only the actual occupied row bands as UI; do not block the map
            // in the empty rectangle between wrapped rows or beyond the last row.
            java.util.Map<Integer, int[]> rows = new java.util.HashMap<>();
            for (Slot sl : slots) {
                int[] bounds = rows.computeIfAbsent(sl.y, ignored -> new int[]{sl.x, sl.x + sl.size});
                bounds[0] = Math.min(bounds[0], sl.x);
                bounds[1] = Math.max(bounds[1], sl.x + sl.size);
            }
            for (var entry : rows.entrySet()) {
                int y = entry.getKey(),[] = entry.getValue();
                if (mx >= bounds[0] - 6 && mx <= bounds[1] + 6 && my >= y - 6 && my <= y + 34) return true;
            }
        }
        if (embeddedInMaqan && mx >= mapTypeBarX() - 2
                && mx < mapTypeBarX() + mapTypeBarWidth() + 2
                && my >= MAP_TYPE_BAR_Y - 4 && my < MAP_TYPE_BAR_Y + MAP_TYPE_BAR_H + 4) return true;
        // The overview is a small viewport, not a full lower-right exclusion zone.
        if (ovwW > 0 && mx >= ovwX - 6 && mx < ovwX + ovwW + 6
                && my >= ovwY - 6 && my < ovwY + ovwH + 6) return true;
        if (!selectedMarkerId.isBlank() && mx >= 8 && mx < 8 + Math.max(1, Math.min(218, width - 16))
                && my >= 48 && my < 142) return true;
        return panel.contains(mx, my);
    }

    private void drawHotbar(GuiGraphics g, int mx, int my) {
        if (slots.isEmpty()) return;
        java.util.Map<Integer, int[]> rows = new java.util.HashMap<>();
        for (Slot sl : slots) {
            int[] bounds = rows.computeIfAbsent(sl.y, ignored -> new int[]{sl.x, sl.x + sl.size});
            bounds[0] = Math.min(bounds[0], sl.x);
            bounds[1] = Math.max(bounds[1], sl.x + sl.size);
        }
        for (int[] bounds : rows.values())
            bevel(g, bounds[0] - 6, slots.stream().filter(sl -> sl.x >= bounds[0] && sl.x + sl.size <= bounds[1])
                    .mapToInt(sl -> sl.y).findFirst().orElse(0) - 6, bounds[1] - bounds[0] + 12, 40, true);
        List<String> tiers = new ArrayList<>();
        if (ClientState.state != null) for (TitleData.Tier t : ClientState.state.titles.tiers) tiers.add(t.key);
        for (int i = 0; i < slots.size(); i++) {
            Slot sl = slots.get(i);
            boolean on = isCurrent(sl.key), hov = sl.hit(mx, my);
            bevel(g, sl.x, sl.y, sl.size, sl.size, !on);
            if (hov && !on) g.fill(sl.x + 2, sl.y + 2, sl.x + sl.size - 2, sl.y + sl.size - 2, 0x30FFFFFF);
            if (on) g.renderOutline(sl.x - 2, sl.y - 2, sl.size + 4, sl.size + 4, 0xFFFFFFFF);
            MapIcons.draw(g, MapIcons.keyFor(sl.key, tiers), sl.x + 4, sl.y + 4, 20);
            g.drawString(font, String.valueOf(i + 1), sl.x + 3, sl.y + 2, 0xFFA6A8B3);
        }
    }

    private static final int MAP_TYPE_BAR_Y = 8;
    private static final int MAP_TYPE_BAR_H = 24;
    private static final int MAP_TYPE_GAP = 4;

    private record MapTypeButton(String id, String label, int width) {}

    private List<MapTypeButton> mapTypeButtons() {
        boolean compact = width < 440;
        boolean micro = width < 300;
        return List.of(
                new MapTypeButton("political", micro ? "P" : compact ? "POL" : "POLITICAL", mapTypeButtonWidth(78)),
                new MapTypeButton("exploration", micro ? "E" : compact ? "EXP" : "EXPLORATION", mapTypeButtonWidth(96)),
                new MapTypeButton("diorama", compact ? "3D" : "DIORAMA 3D", mapTypeButtonWidth(82)));
    }

    private int mapTypeButtonWidth(int preferred) {
        int availableWidth = panel.isOpen() ? panel.leftEdge() - 16 : width - 58;
        int available = Math.max(12, (availableWidth - MAP_TYPE_GAP * 2) / 3);
        return Math.min(preferred, available);
    }

    private int mapTypeBarWidth() {
        return mapTypeButtons().stream().mapToInt(MapTypeButton::width).sum()
                + MAP_TYPE_GAP * (mapTypeButtons().size() - 1);
    }

    private int mapTypeBarX() {
        // Reserve the persistent side-panel rail on the right; controls must never
        // be painted beneath it or become impossible to click.
        return panel.isOpen()
                ? Math.max(8, panel.leftEdge() - mapTypeBarWidth() - 8)
                : Math.max(8, width - mapTypeBarWidth() - 50);
    }

    private void drawMapTypeBar(GuiGraphics g, int mx, int my) {
        int x = mapTypeBarX();
        int y = MAP_TYPE_BAR_Y;
        for (MapTypeButton button : mapTypeButtons()) {
            int right = x + button.width();
            boolean active = button.id().equals("political")
                    ? !MapImages.TERRAIN.equals(ClientState.mode)
                    : button.id().equals("exploration") && MapImages.TERRAIN.equals(ClientState.mode);
            boolean hover = mx >= x && mx < right && my >= y && my < y + MAP_TYPE_BAR_H;
            bevel(g, x, y, button.width(), MAP_TYPE_BAR_H, !active);
            if (active) g.fill(x + 3, y + MAP_TYPE_BAR_H - 3, right - 3, y + MAP_TYPE_BAR_H - 1, 0xFFF0C85A);
            if (hover && !active) g.fill(x + 2, y + 2, right - 2, y + MAP_TYPE_BAR_H - 2, 0x24FFFFFF);
            g.drawCenteredString(font, button.label(), x + button.width() / 2, y + 8,
                    active ? 0xFFF0C85A : 0xFFE8E2D0);
            x = right + MAP_TYPE_GAP;
        }
    }

    private boolean handleMapTypeClick(double mx, double my) {
        int x = mapTypeBarX();
        int y = MAP_TYPE_BAR_Y;
        if (my < y || my >= y + MAP_TYPE_BAR_H) return false;
        for (MapTypeButton button : mapTypeButtons()) {
            int right = x + button.width();
            if (mx >= x && mx < right) {
                switch (button.id()) {
                    case "political" -> setMode(MapImages.REALMS);
                    case "exploration" -> openExplorationMap();

                    case "diorama" -> openDioramaView();
                    default -> { return false; }
                }
                return true;
            }
            x = right + MAP_TYPE_GAP;
        }
        return false;
    }

    /** Open the separate fog-of-war surface map backed by actually explored chunks. */
    private void openExplorationMap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            Class<?> viewType = Class.forName("com.maqan.galacticnavigator.client.NavigatorSurfaceMapScreen");
            Object view = viewType.getConstructor(String.class, Screen.class)
                    .newInstance(mc.level.dimension().location().toString(), this);
            if (!(view instanceof Screen screen)) return;
            Class<?> hostType = Class.forName("com.maqan.galacticnavigator.client.MaqanScreenHost");
            hostType.getMethod("open", Minecraft.class, Screen.class).invoke(null, mc, screen);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("Карта исследования недоступна: модуль Atlas не загружен"), true);
        }
    }

    /**
     * Reuse Atlas' real explored-terrain Diorama renderer. It draws only captured
     * chunks and can overlay WorldForge territories; no chunks are force-loaded.
     * Reflection keeps WorldForge usable when the optional Atlas module is absent.
     */
    private void openDioramaView() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            Class<?> viewType = Class.forName("com.maqan.galacticnavigator.client.NavigatorTerrain3DScreen");
            Object view = viewType.getConstructor(String.class, Screen.class)
                    .newInstance(mc.level.dimension().location().toString(), this);
            if (!(view instanceof Screen screen)) return;
            Class<?> hostType = Class.forName("com.maqan.galacticnavigator.client.MaqanScreenHost");
            hostType.getMethod("open", Minecraft.class, Screen.class).invoke(null, mc, screen);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("Diorama 3D недоступна: модуль Atlas не загружен"), true);
        }
    }

    private void drawPlate(GuiGraphics g) {
        String t = modeName(ClientState.mode);
        String sub = MapTextures.w + "x" + MapTextures.h + " px · " + Math.round(MapTextures.w * bpp() / 1000) + "x" + Math.round(MapTextures.h * bpp() / 1000) + " км";
        int w = Math.max(font.width(t) * 3 / 2, font.width(sub)) + 20;
        bevel(g, 8, 8, w, 34, false);
        g.pose().pushPose();
        g.pose().translate(15f, 13f, 0f);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawString(font, t, 0, 0, 0xFFF0C85A);
        g.pose().popPose();
        g.drawString(font, sub, 15, 29, 0xFFA6A8B3);
    }

    private void drawCoords(GuiGraphics g, int mx, int my) {
        if (overUi(mx, my) || !MapTextures.ready()) return;
        int[] p = screenToPixel(mx, my);
        TitleData d = MapTextures.titles;
        String t = "X " + Math.round((p[0] + 0.5 - d.w / 2.0) * d.bpp) + "  Z " + Math.round((p[1] + 0.5 - d.h / 2.0) * d.bpp);
        int w = font.width(t) + 14;
        bevel(g, 8, height - 26, w, 18, false);
        g.drawString(font, t, 15, height - 21, 0xFFECEBE4);
    }

    private void drawScale(GuiGraphics g) {
        double per = zoom / bpp(), target = 90, raw = target / per, pw = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / pw;
        double step = (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * pw;
        int px = (int) Math.round(step * per);
        String t = step >= 1000 ? (long) (step / 1000) + " км" : (long) step + " бл.";
        int w = Math.max(px, font.width(t)) + 14, x = width - w - 8, y = height - 24;
        if (ovwW > 0) x = ovwX + ovwW + 4 - w;
        bevel(g, x, y, w, 16, false);
        g.drawString(font, t, x + w - 7 - font.width(t), y + 2, 0xFFA6A8B3);
        g.fill(x + w - 7 - px, y + 12, x + w - 7, y + 13, 0xFFECEBE4);
        g.fill(x + w - 7 - px, y + 10, x + w - 6 - px, y + 13, 0xFFECEBE4);
        g.fill(x + w - 8, y + 10, x + w - 7, y + 13, 0xFFECEBE4);
    }

    /** Обзор: уменьшенная карта текущего режима с рамкой видимой области; клик и перетаскивание двигают вид. */
    private void drawOverview(GuiGraphics g) {
        int ow = Math.min(170, Math.max(100, width / 6)), oh = (int) Math.round(ow * (double) MapTextures.h / MapTextures.w);
        ovwW = ow; ovwH = oh; ovwX = width - ow - 14; ovwY = height - oh - 44;
        bevel(g, ovwX - 4, ovwY - 4, ow + 8, oh + 8, true);
        g.fill(ovwX, ovwY, ovwX + ow, ovwY + oh, 0xFF0A0C12);
        g.pose().pushPose();
        g.pose().translate(ovwX, ovwY, 0f);
        float k = ow / (float) MapTextures.w;
        g.pose().scale(k, k, 1f);
        g.blit(MapTextures.id, 0, 0, 0f, 0f, MapTextures.w, MapTextures.h, MapTextures.w, MapTextures.h);
        g.pose().popPose();
        double x0 = Math.max(0, -camX / zoom), y0 = Math.max(0, -camY / zoom), x1 = Math.min(MapTextures.w, (width - camX) / zoom), y1 = Math.min(MapTextures.h, (height - camY) / zoom);
        int rx = ovwX + (int) (x0 * k), ry = ovwY + (int) (y0 * k), rw = Math.max(3, (int) ((x1 - x0) * k)), rh = Math.max(3, (int) ((y1 - y0) * k));
        g.renderOutline(rx - 1, ry - 1, rw + 2, rh + 2, 0xFF000000);
        g.renderOutline(rx, ry, rw, rh, 0xFFF0F0F0);
    }

    private void overviewTo(double mx, double my) {
        double px = (mx - ovwX) / ovwW * MapTextures.w, py = (my - ovwY) / ovwH * MapTextures.h;
        px = Math.max(0, Math.min(MapTextures.w, px)); py = Math.max(0, Math.min(MapTextures.h, py));
        camX = width / 2.0 - px * zoom;
        camY = height / 2.0 - py * zoom;
    }

    private int legendRev = -2;
    private String legendMode = "";
    private final List<int[]> legendRgb = new ArrayList<>();
    private final List<String> legendNames = new ArrayList<>();
    private final List<Long> legendArea = new ArrayList<>();

    /** Легенда режима: значения свойства (культуры...) или державы; для остальных режимов не показывается. */
    private void drawLegend(GuiGraphics g) {
        String m = MapTextures.mode;
        TitleData d = MapTextures.titles;
        if (!(MapImages.isProp(m) || MapImages.REALMS.equals(m))) return;
        if (legendRev != ClientState.revision || !m.equals(legendMode)) {
            legendRev = ClientState.revision; legendMode = m;
            legendRgb.clear(); legendNames.clear(); legendArea.clear();
            List<Object[]> rows = new ArrayList<>();
            if (MapImages.isProp(m)) {
                MapImages.PropInfo pi = MapImages.propInfo(d, m.substring(2));
                for (int i = 0; i < pi.vals.size(); i++) rows.add(new Object[]{pi.vals.get(i), pi.rgb[i], pi.area[i]});
            } else {
                for (TitleData.Realm r : d.realmList()) if (r.overlord == null) rows.add(new Object[]{r.name, r.rgb, r.areaBlocks});
            }
            rows.sort((a, b) -> Long.compare((Long) b[2], (Long) a[2]));
            for (int i = 0; i < Math.min(14, rows.size()); i++) {
                legendNames.add((String) rows.get(i)[0]);
                legendRgb.add(new int[]{(Integer) rows.get(i)[1]});
                legendArea.add((Long) rows.get(i)[2]);
            }
        }
        if (legendNames.isEmpty()) return;
        int w = 150, h = 22 + legendNames.size() * 12, x = 8, y = height - 34 - h;
        bevel(g, x, y, w, h, false);
        g.drawString(font, MapImages.REALMS.equals(m) ? "Державы" : modeName(m), x + 8, y + 7, 0xFFF0C85A);
        for (int i = 0; i < legendNames.size(); i++) {
            int ly = y + 20 + i * 12;
            g.fill(x + 8, ly, x + 18, ly + 9, 0xFF000000);
            g.fill(x + 9, ly + 1, x + 17, ly + 8, 0xFF000000 | legendRgb.get(i)[0]);
            String n = font.plainSubstrByWidth(legendNames.get(i), w - 34);
            g.drawString(font, n, x + 23, ly + 1, 0xFFECEBE4);
        }
    }

    private int pixelProvince(int mx, int my) {
        int[] p = screenToPixel(mx, my);
        if (p[0] < 0 || p[1] < 0 || p[0] >= MapTextures.w || p[1] >= MapTextures.h) return 0;
        return MapTextures.titles.provinceIdAtPixel(p[0], p[1]);
    }

    private void drawLabels(GuiGraphics g) {
        if (MapImages.TERRAIN.equals(MapTextures.mode)) return;
        List<double[]> taken = new ArrayList<>();
        for (MapTextures.Label l : MapTextures.labels) {
            double size = Math.min(26, Math.sqrt(l.areaPx) * zoom * 0.14 + 2);
            if (size < 8) continue;
            double sx = camX + l.px * zoom, sy = camY + (l.py - 4 / zoom) * zoom;
            if (sx < -100 || sy < -20 || sx > width + 100 || sy > height + 20) continue;
            double scale = size / 9.0, w = font.width(l.text) * scale, h = size;
            double[] box = {sx - w / 2, sy - h / 2, sx + w / 2, sy + h / 2};
            boolean clash = false;
            for (double[] b : taken) if (!(box[2] < b[0] || box[0] > b[2] || box[3] < b[1] || box[1] > b[3])) { clash = true; break; }
            if (clash) continue;
            taken.add(box);
            g.pose().pushPose();
            g.pose().translate((float) sx, (float) sy, 0f);
            g.pose().scale((float) scale, (float) scale, 1f);
            g.drawCenteredString(font, l.text, 0, -4, 0xFFF4ECD2);
            g.pose().popPose();
        }
    }

    private void drawDiamond(GuiGraphics g, double sx, double sy, int rgb) {
        int x = (int) sx, y = (int) sy, c = 0xFF000000 | rgb;
        g.fill(x - 4, y - 4, x + 4, y + 4, 0xFF10141C);
        g.fill(x - 3, y - 3, x + 3, y + 3, c);
    }

    private void refreshHiddenArmyMarkers() {
        if (hiddenMarkerRevision == ClientState.revision) return;
        java.util.Set<Integer> occupiedProvinces = new java.util.HashSet<>();
        java.util.Map<Long, List<Markers.Marker>> settlementCells = new java.util.HashMap<>();
        for (Markers.Marker object : ClientState.markers.list()) {
            if (!("city".equals(object.type) || "settlement".equals(object.type)
                    || "village".equals(object.type) || "capital".equals(object.type)
                    || "castle".equals(object.type))) continue;
            int province = markerInt(object.desc, "province");
            if (province > 0) occupiedProvinces.add(province);
            int cellX = Math.floorDiv(object.x, 32), cellZ = Math.floorDiv(object.z, 32);
            long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
            settlementCells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(object);
        }
        java.util.Set<String> hidden = new java.util.HashSet<>();
        for (Markers.Marker army : ClientState.markers.list()) {
            if (!"army".equals(army.type)) continue;
            int title = markerInt(army.desc, "title");
            if (title > 0 && occupiedProvinces.contains(title)) {
                hidden.add(army.id);
                continue;
            }
            int cellX = Math.floorDiv(army.x, 32), cellZ = Math.floorDiv(army.z, 32);
            boolean garrisoned = false;
            for (int dxCell = -1; dxCell <= 1 && !garrisoned; dxCell++) {
                for (int dzCell = -1; dzCell <= 1 && !garrisoned; dzCell++) {
                    long key = ((long) (cellX + dxCell) << 32) ^ ((cellZ + dzCell) & 0xffffffffL);
                    for (Markers.Marker object : settlementCells.getOrDefault(key, List.of())) {
                        long dx = (long) army.x - object.x, dz = (long) army.z - object.z;
                        if (dx * dx + dz * dz <= 32L * 32L) { garrisoned = true; break; }
                    }
                }
            }
            if (garrisoned) hidden.add(army.id);
        }
        hiddenArmyMarkerIds = java.util.Set.copyOf(hidden);
        hiddenMarkerRevision = ClientState.revision;
    }

    private boolean hiddenArmyMarker(Markers.Marker marker) {
        if (!"army".equals(marker.type)) return false;
        refreshHiddenArmyMarkers();
        return hiddenArmyMarkerIds.contains(marker.id);
    }

    private static int markerInt(String description, String key) {
        if (description == null || key == null) return 0;
        String prefix = key + "=";
        for (String field : description.split(";")) {
            if (!field.startsWith(prefix)) continue;
            try { return Integer.parseInt(field.substring(prefix.length())); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private static List<Component> describeMarker(Markers.Marker marker) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(marker.name).withStyle(ChatFormatting.GOLD));
        if ("army".equals(marker.type)) {
            int men = markerInt(marker.desc, "men");
            String commander = markerValue(marker.desc, "commander");
            lines.add(Component.literal("Полевая армия · " + men + " бойцов").withStyle(ChatFormatting.GRAY));
            if (!commander.isBlank()) lines.add(Component.literal("Полководец: " + commander).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal(markerValue(marker.desc, "moving").equals("true") ? "В движении" : "На позиции")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            int population = markerInt(marker.desc, "population");
            int buildings = markerInt(marker.desc, "buildings");
            if (population > 0) lines.add(Component.literal("Население: " + population).withStyle(ChatFormatting.GRAY));
            if (buildings > 0) lines.add(Component.literal("Завершённых зданий: " + buildings).withStyle(ChatFormatting.GRAY));
            String realm = markerValue(marker.desc, "realm");
            if (!realm.isBlank()) lines.add(Component.literal("Государство: " + realm).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /**
     * Persistent inspection card for a selected strategic object. The card uses the same
     * visual profile and live leader entity as the map symbol, so selecting an army does not
     * degrade into a text-only entry when its commander is currently loaded.
     */
    private void drawSelectedMarkerInspector(GuiGraphics g) {
        if (selectedMarkerId == null || selectedMarkerId.isBlank()) return;
        Markers.Marker marker = null;
        for (Markers.Marker candidate : ClientState.markers.list()) {
            if (selectedMarkerId.equals(candidate.id)) { marker = candidate; break; }
        }
        if (marker == null) {
            selectedMarkerId = "";
            return;
        }

        int cardW = Math.max(1, Math.min(218, width - 16));
        int cardH = 94;
        // Keep the selected-object card away from the right-side province panel
        // and the top-right map-mode switcher.
        int x = 8;
        int y = 48;
        bevel(g, x, y, cardW, cardH, false);
        g.fill(x + 2, y + 2, x + cardW - 2, y + 4, 0xFFE2B85C);
        g.fill(x + 7, y + 9, x + 45, y + 47, 0xFF111923);
        g.renderOutline(x + 7, y + 9, 38, 38, 0xFF6E7888);

        LivingEntity leader = null;
        if ("army".equals(marker.type)) {
            String uuid = markerValue(marker.desc, "leaderEntity");
            leader = leaderBodies().get(uuid);
        }
        if (leader != null && !leader.isAlive()) leader = null;
        if (leader != null) {
            g.enableScissor(x + 8, y + 10, x + 44, y + 46);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g,
                        x + 26, y + 43, 17, 0.0F, 0.0F, leader);
            } catch (RuntimeException rendererFailure) {
                // Keep the stable item/vector fallback if a third-party entity renderer fails.
                leader = null;
            } finally {
                g.disableScissor();
            }
        }
        if (leader == null) {
            WorldObjectVisualCatalog.Profile visual = WorldObjectVisualCatalog.resolve(marker);
            String itemId = marker.visualItem == null || marker.visualItem.isBlank()
                    ? visual.mapItem() : marker.visualItem.trim();
            ResourceLocation parsed = ResourceLocation.tryParse(itemId);
            if (parsed != null) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(parsed));
                if (!stack.isEmpty()) g.renderItem(stack, x + 17, y + 18);
            } else {
                g.drawCenteredString(font, "◇", x + 26, y + 22, 0xFFF0C85A);
            }
        }

        int textX = x + 53;
        int textW = cardW - 61;
        String name = font.plainSubstrByWidth(marker.name, textW);
        g.drawString(font, name, textX, y + 10, 0xFFFFD778, false);
        String category = marker.type == null ? "world-object" : marker.type.toUpperCase(java.util.Locale.ROOT);
        g.drawString(font, category, textX, y + 23, 0xFF9EADBF, false);
        g.drawString(font, "X " + marker.x + "  Z " + marker.z, textX, y + 36, 0xFFCBD3DE, false);

        String detail;
        if ("army".equals(marker.type)) {
            int men = markerInt(marker.desc, "men");
            String commander = markerValue(marker.desc, "commander");
            detail = men + " бойцов" + (commander.isBlank() ? "" : " · " + commander);
        } else {
            int population = markerInt(marker.desc, "population");
            int buildings = markerInt(marker.desc, "buildings");
            detail = population > 0 ? "Население: " + population
                    : buildings > 0 ? "Зданий: " + buildings : markerValue(marker.desc, "realm");
        }
        if (detail == null || detail.isBlank()) detail = "Стратегический объект";
        g.drawString(font, font.plainSubstrByWidth(detail, cardW - 16),
                x + 8, y + 61, 0xFFE5E0D5, false);
        String realm = markerValue(marker.desc, "realm");
        if (!realm.isBlank() && !"army".equals(marker.type))
            g.drawString(font, font.plainSubstrByWidth("Государство: " + realm, cardW - 16),
                    x + 8, y + 74, 0xFF9EADBF, false);
        else
            g.drawString(font, "Выбрано · клик по карте — сменить", x + 8, y + 74, 0xFF77869A, false);
    }

    private static String markerValue(String description, String key) {
        if (description == null || key == null) return "";
        String prefix = key + "=";
        for (String field : description.split(";"))
            if (field.startsWith(prefix)) return field.substring(prefix.length());
        return "";
    }

    private Markers.Marker markerAt(double mx, double my) {
        Markers.Marker best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Markers.Marker marker : ClientState.markers.list()) {
            if (hiddenArmyMarker(marker)) continue;
            double[] point = blockToScreen(marker.x, marker.z);
            WorldObjectVisualCatalog.Profile profile = WorldObjectVisualCatalog.resolve(marker);
            double size = Math.max(16, Math.min(48,
                    profile.mapScale() * Math.sqrt(Math.max(0.16, zoom))));
            double radius = Math.max(12, size * 0.62);
            double dx = point[0] - mx, dy = point[1] - my;
            double distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        return best;
    }

    /**
     * Draws strategic map symbols with native GUI primitives. This deliberately avoids an
     * optional texture atlas: a missing atlas must never turn every strategic object into a
     * broken-texture square. The symbol remains crisp at every GUI scale.
     */
    private void drawStrategicMarker(GuiGraphics g, Markers.Marker marker, double sx, double sy,
                                      int mx, int my, LivingEntity commanderBody) {
        int x = (int) Math.round(sx), y = (int) Math.round(sy);
        boolean selected = marker.id.equals(selectedMarkerId);
        boolean hovered = Math.abs(mx - x) <= 24 && Math.abs(my - y) <= 24;
        WorldObjectVisualCatalog.Profile visual = WorldObjectVisualCatalog.resolve(marker);
        String symbol = switch (visual.icon()) {
            case "army" -> "⚑";
            case "capital" -> "★";
            case "city", "settlement" -> "⌂";
            case "village" -> "⌂";
            case "castle" -> "♜";
            case "port" -> "⚓";
            case "ruin" -> "⌑";
            case "caravan" -> "◆";
            case "camp" -> "⌃";
            case "temple" -> "✥";
            case "mine" -> "⛏";
            default -> "◇";
        };
        int size = Math.max(16, Math.min(48,
                (int) Math.round(visual.mapScale() * Math.sqrt(Math.max(0.16, zoom)))));
        int left = x - size / 2, top = y - size / 2;
        int realmColor = 0xFF000000 | (marker.rgb & 0xFFFFFF);
        int border = selected ? 0xFFFFD66B : hovered ? 0xFFE9D28B : realmColor;
        g.fill(left, top, left + size, top + size, 0xE91A1D24);
        g.fill(left + 2, top + 2, left + size - 2, top + 4, realmColor);
        g.fill(left + 2, top + size - 4, left + size - 2, top + size - 2, realmColor);
        g.renderOutline(left, top, size, size, border);

        boolean renderedLeader = false;
        if (!visual.mapTexture().isBlank()) {
            ResourceLocation texture = ResourceLocation.tryParse(visual.mapTexture());
            if (texture != null) {
                try {
                    g.blit(texture, left + 3, top + 3, size - 6, size - 6,
                            0, 0, 32, 32, 32, 32);
                    renderedLeader = true;
                } catch (RuntimeException missingTexture) {
                    // Keep the vector fallback if a resource disappears during a reload.
                }
            }
        }
        if (!renderedLeader && "army".equals(marker.type) && commanderBody != null && commanderBody.isAlive()) {
            g.enableScissor(left + 2, top + 2, left + size - 2, top + size - 2);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g,
                        x, y, Math.max(10, size - 4), 0.0F, 0.0F, commanderBody);
                renderedLeader = true;
            } catch (RuntimeException rendererFailure) {
                // A modded entity renderer may be unavailable during a client reload; retain the strategic fallback.
            } finally {
                g.disableScissor();
            }
        }
        String itemVisual = marker.visualItem == null || marker.visualItem.isBlank()
                ? visual.mapItem() : marker.visualItem.trim();
        if (!renderedLeader && zoom >= visual.modelLod() && !itemVisual.isBlank()) {
            ResourceLocation itemId = ResourceLocation.tryParse(itemVisual);
            if (itemId != null) {
                ItemStack iconStack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
                if (!iconStack.isEmpty()) {
                    int iconSize = Math.max(12, Math.min(32, size - 6));
                    g.pose().pushPose();
                    g.pose().translate(x - iconSize / 2.0F, y - iconSize / 2.0F, 0.0F);
                    float iconScale = iconSize / 16.0F;
                    g.pose().scale(iconScale, iconScale, 1.0F);
                    g.renderItem(iconStack, 0, 0);
                    g.pose().popPose();
                    renderedLeader = true;
                }
            }
        }
        if (!renderedLeader) {
            if ("army".equals(marker.type)) {
                int poleX = left + Math.max(5, size / 5);
                g.fill(poleX, top + 5, poleX + 1, top + size - 5, 0xFFE8D9B5);
                g.fill(poleX + 1, top + 5, poleX + Math.max(6, size / 3), top + Math.max(9, size / 3),
                        realmColor);
            }
            int textWidth = font.width(symbol);
            g.drawString(font, symbol, x - textWidth / 2,
                    y - font.lineHeight / 2, selected ? 0xFFFFE7A0 : 0xFFF4F0E6, false);
        }
        if (selected || hovered) {
            g.renderOutline(left - 2, top - 2, size + 4, size + 4,
                    selected ? 0xFFFFD66B : 0xFFE9D28B);
        }
    }
    private static Map<String, LivingEntity> leaderBodies() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            leaderIndexLevel = null;
            leaderIndexTick = Long.MIN_VALUE;
            leaderBodyIndex = Map.of();
            return leaderBodyIndex;
        }
        long tick = mc.level.getGameTime();
        if (leaderIndexLevel != mc.level || tick - leaderIndexTick >= 5L || tick < leaderIndexTick) {
            Map<String, LivingEntity> indexed = new HashMap<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity living && living.isAlive())
                    indexed.put(entity.getUUID().toString(), living);
            }
            leaderBodyIndex = Map.copyOf(indexed);
            leaderIndexLevel = mc.level;
            leaderIndexTick = tick;
        }
        return leaderBodyIndex;
    }

    private void drawMarkers(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        Map<String, LivingEntity> leaderBodies = leaderBodies();
        for (Markers.Marker marker : ClientState.markers.list()) {
            if (hiddenArmyMarker(marker)) continue;
            double[] point = blockToScreen(marker.x, marker.z);
            if (point[0] < -20 || point[1] < BAR - 20 || point[0] > width + 20 || point[1] > height + 20) continue;
            String leaderUuid = markerValue(marker.desc, "leaderEntity");
            drawStrategicMarker(g, marker, point[0], point[1], mx, my,
                    leaderBodies.get(leaderUuid));
            if (zoom > 1.15 || marker.id.equals(selectedMarkerId)
                    || Math.abs(mx - point[0]) < 12 && Math.abs(my - point[1]) < 12) {
                String label = marker.name;
                if ("army".equals(marker.type)) {
                    String commander = markerValue(marker.desc, "commander").trim();
                    if (!commander.isEmpty()) label = commander;
                }
                g.drawString(font, label, (int) point[0] + 11, (int) point[1] - 4,
                        marker.id.equals(selectedMarkerId) ? 0xFFFFD66B : 0xFFFFFFFF);
            }
        }
        for (Waypoints.Wp w : Waypoints.LIST) {
            double[] point = blockToScreen(w.x, w.z);
            if (point[0] < -10 || point[1] < BAR - 10 || point[0] > width + 10 || point[1] > height + 10) continue;
            drawDiamond(g, point[0], point[1], w.rgb);
            g.drawString(font, w.name, (int) point[0] + 7, (int) point[1] - 4, 0xFF9CF2E8);
        }
        if (mc.player != null) {
            double[] point = blockToScreen(mc.player.getX(), mc.player.getZ());
            g.fill((int) point[0] - 3, (int) point[1] - 3, (int) point[0] + 3, (int) point[1] + 3, 0xFFFFFFFF);
            g.fill((int) point[0] - 2, (int) point[1] - 2, (int) point[0] + 2, (int) point[1] + 2, 0xFFFF3B3B);
        }
    }

    private List<Component> describe(int prov, boolean full) {
        List<Component> out = new ArrayList<>();
        TitleData d = MapTextures.titles;
        TitleData.Title p = d.get(prov);
        if (p == null) return out;
        out.add(Component.literal(p.name).withStyle(Style.EMPTY.withColor(lighten(p.rgb)).withBold(true)));
        List<TitleData.Title> chain = d.chain(p);
        for (int i = 0; i < chain.size(); i++) {
            TitleData.Title t = chain.get(i);
            if (i > 0) out.add(Component.literal(d.tiers.get(t.tier).name + ": ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(t.name).withStyle(Style.EMPTY.withColor(lighten(t.rgb)))));
            Object holderName = t.props.get("politics.holder_name");
            if (holderName instanceof String name && !name.isBlank()) {
                String rankName = t.tier >= 0 && t.tier < d.tiers.size() ? d.tiers.get(t.tier).name : "Титул";
                out.add(Component.literal("  " + rankName + " · правитель: " + name).withStyle(ChatFormatting.GRAY));
            }
        }
        TitleData.Realm r = d.realmOf(p);
        if (r != null) {
            out.add(Component.literal("Государство: ").withStyle(ChatFormatting.GRAY).append(Component.literal(r.name).withStyle(Style.EMPTY.withColor(lighten(r.rgb)))));
            if (full) {
                if (!r.ruler.isEmpty()) out.add(Component.literal("Правитель: " + r.ruler).withStyle(ChatFormatting.GRAY));
                for (TitleData.Realm cur = r; cur.overlord != null; cur = cur.overlord) {
                    out.add(Component.literal(d.relationLabel(cur.relation) + ": " + cur.overlord.name).withStyle(ChatFormatting.GRAY));
                }
                if (!r.subjectIds.isEmpty()) {
                    out.add(Component.literal("Подчинённые:").withStyle(ChatFormatting.GRAY));
                    for (String id : r.subjectIds) {
                        TitleData.Realm sub = d.realms.get(id);
                        if (sub != null) out.add(Component.literal("  " + sub.name + " [" + d.relationLabel(sub.relation) + "]").withStyle(ChatFormatting.DARK_GRAY));
                    }
                }
            }
        } else {
            out.add(Component.literal("Государство: нет").withStyle(ChatFormatting.GRAY));
        }
        if (MapImages.isProp(MapTextures.mode)) {
            MapImages.PropInfo pi = MapImages.propInfo(d, MapTextures.mode.substring(2));
            int v = p.id < pi.lut.length ? pi.lut[p.id] : 0;
            if (v > 0) out.add(Component.literal(modeName(MapTextures.mode) + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(pi.vals.get(v - 1)).withStyle(Style.EMPTY.withColor(lighten(pi.rgb[v - 1])))));
        }
        if (full) {
            out.add(Component.literal("Площадь: " + p.areaBlocks / 1000 + " тыс. блоков²; соседей: " + p.neighbors.length).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private static int lighten(int rgb) {                    // тёмные цвета титулов нечитаемы на тёмном фоне
        int r = (rgb >> 16) & 255, gg = (rgb >> 8) & 255, b = rgb & 255;
        r = Math.min(255, r + (255 - r) / 2);
        gg = Math.min(255, gg + (255 - gg) / 2);
        b = Math.min(255, b + (255 - b) / 2);
        return (r << 16) | (gg << 8) | b;
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (panel.click(mx, my, button)) return true;
        if (embeddedInMaqan && button == 0 && handleMapTypeClick(mx, my)) return true;
        if (!embeddedInMaqan && button == 0) for (Slot sl : slots) if (sl.hit(mx, my)) { setMode(sl.key); return true; }
        if (!MapTextures.ready()) return false;
        if (button == 0 && ovwW > 0 && mx >= ovwX && mx < ovwX + ovwW && my >= ovwY && my < ovwY + ovwH) {
            dragOverview = true;
            overviewTo(mx, my);
            return true;
        }
        if (overUi((int) mx, (int) my)) return true;
        draggingMap = button == 0 || button == 1 || button == 2;
        mapDragButton = draggingMap ? button : -1;
        if (button == 0 && !panel.editing()) {
            Markers.Marker marker = markerAt(mx, my);
            if (marker != null) {
                selectedMarkerId = marker.id;
                centerOnBlock(marker.x, marker.z);
                PlatformWorkspaceBridge.publish("worldforge", marker.id, marker.type,
                        marker.name, marker.desc == null ? "" : marker.desc);
                return true;
            }
            selectedMarkerId = "";
        }
        int[] p = screenToPixel(mx, my);
        if (button == 0 && panel.editing()) {                      // редактор: клик = правка на сервере, Shift+клик = выбрать провинцию
            editAt(p, hasShiftDown());
            return true;
        }
        if (button == 0) {
            int prov = pixelProvince((int) mx, (int) my);
            if (prov > 0) {
                selected = prov;
                selLabel = labelOfProvince(prov);
                setOverlay(selLabel);
                TitleData.Realm r = MapTextures.titles.realmOf(MapTextures.titles.get(prov));
                if (MapImages.isDip(ClientState.mode) && r != null && !ClientState.mode.equals(MapImages.DIP + r.id)) setMode(MapImages.DIP + r.id, true);
                panel.onSelected(prov);
                centerOnProvince(prov);
            } else {
                selected = 0;
                selLabel = -1;
                freeOverlay();
            }
            return true;
        }
        if (button == 1 && hasShiftDown()) {
            Minecraft mc = Minecraft.getInstance();
            TitleData d = MapTextures.titles;
            int bx = (int) Math.round((p[0] - d.w / 2.0) * d.bpp), bz = (int) Math.round((p[1] - d.h / 2.0) * d.bpp);
            if (mc.player != null) mc.player.connection.sendCommand("worldforge tpxz " + bx + " " + bz);
            onClose();
            return true;
        }
        return button == 1;
    }

    /** Отправляет серверу правку в точке карты (пиксель p). */
    private void editAt(int[] p, boolean pick) {
        TitleData d = MapTextures.titles;
        if (p[0] < 0 || p[1] < 0 || p[0] >= d.w || p[1] >= d.h) return;
        long bx = Math.round((p[0] + 0.5 - d.w / 2.0) * d.bpp), bz = Math.round((p[1] + 0.5 - d.h / 2.0) * d.bpp);
        if (pick) {
            command("worldforge editor at " + bx + " " + bz + " pick");
            panel.onPicked(d.provinceIdAtPixel(p[0], p[1]));
        } else {
            command("worldforge editor at " + bx + " " + bz + " apply");
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (super.mouseDragged(mx, my, button, dx, dy)) return true;
        if (dragOverview) { overviewTo(mx, my); return true; }
        if (button == 0 && panel.editing() && !overUi((int) mx, (int) my)) {                // ведём кистью, не чаще 8 раз в секунду
            long now = System.currentTimeMillis();
            if (now - lastEditApply > 125 && !hasShiftDown()) {
                lastEditApply = now;
                editAt(screenToPixel(mx, my), false);
            }
            return true;
        }
        if (draggingMap && (button == 0 || button == 1 || button == 2)) {
            camX += dx;
            camY += dy;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragOverview && button == 0) dragOverview = false;
        if (draggingMap && button == mapDragButton) {
            draggingMap = false;
            mapDragButton = -1;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (panel.scrolled(mx, my, delta)) return true;
        if (panel.contains(mx, my)) return true;
        if (overUi((int) mx, (int) my)) return true;
        if (!MapTextures.ready()) return false;
        double nz = Math.max(0.2, Math.min(24, zoom * Math.exp(delta * 0.25)));
        camX = mx - (mx - camX) * nz / zoom;
        camY = my - (my - camY) * nz / zoom;
        zoom = nz;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (argBox != null && argBox.visible && argBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (pendingAction != null) command("wfaction run " + pendingAction.id + " " + pendingTarget + " " + argBox.getValue().trim());
                pendingAction = null;
                argBox.setValue("");
                argBox.setVisible(false);
                argBox.setFocused(false);
                setFocused(null);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                pendingAction = null;
                argBox.setVisible(false);
                argBox.setFocused(false);
                setFocused(null);
                return true;
            }
            return argBox.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_TAB) { panel.toggle(); return true; }
        if (nameBox != null && nameBox.visible && nameBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                String n = nameBox.getValue().trim();
                if (!n.isEmpty()) Waypoints.add(n, pendingX, pendingZ);
                nameBox.setValue("");
                nameBox.setVisible(false);
                nameBox.setFocused(false);
                setFocused(null);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                nameBox.setVisible(false);
                nameBox.setFocused(false);
                setFocused(null);
                return true;
            }
            return nameBox.keyPressed(key, scan, mods);
        }
        if (ClientSetup.OPEN.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            List<String> m = modes();
            int i = key - GLFW.GLFW_KEY_1;
            if (i < m.size()) { setMode(m.get(i)); return true; }
        }
        if (key == GLFW.GLFW_KEY_C) { centerOnPlayer(); return true; }
        if (key == GLFW.GLFW_KEY_B && MapTextures.ready()) {
            Minecraft mc = Minecraft.getInstance();
            double mxx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth(), myy = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
            int[] p = screenToPixel(mxx, myy);
            TitleData d = MapTextures.titles;
            pendingX = (int) Math.round((p[0] - d.w / 2.0) * d.bpp);
            pendingZ = (int) Math.round((p[1] - d.h / 2.0) * d.bpp);
            nameBox.setVisible(true);
            nameBox.setValue("Метка " + (Waypoints.LIST.size() + 1));
            setFocused(nameBox);
            nameBox.setFocused(true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE && MapTextures.ready()) {
            Minecraft mc = Minecraft.getInstance();
            double mxx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth(), myy = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
            Waypoints.Wp best = null;
            double bd = 100;
            for (Waypoints.Wp w : Waypoints.LIST) {
                double[] s = blockToScreen(w.x, w.z);
                double dd = (s[0] - mxx) * (s[0] - mxx) + (s[1] - myy) * (s[1] - myy);
                if (dd < bd) { bd = dd; best = w; }
            }
            if (best != null) Waypoints.remove(best);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
