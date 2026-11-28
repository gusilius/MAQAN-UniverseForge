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
import java.util.Comparator;
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
    private boolean militaryView;
    private boolean layerMenuOpen;
    private int layerMenuScroll;
    public void setEmbeddedInMaqan(boolean embedded) { embeddedInMaqan = embedded; }
    private static final String[][] PROP_NAMES = {{"culture", "Культуры"}, {"religion", "Религии"}, {"faith", "Религии"}, {"race", "Народы"}, {"people", "Народы"}, {"house", "Дома"}, {"dynasty", "Династии"}, {"development", "Развитие"}, {"climate", "Климат"}};
    private double camX, camY, zoom;
    private boolean fitted;
    private Object lastMapState;
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
    private int heraldryRevision = Integer.MIN_VALUE;
    private Map<String, TitleData.Realm> realmsByName = Map.of();
    private int hiddenMarkerRevision = Integer.MIN_VALUE;
    private int markerOrderRevision = Integer.MIN_VALUE;
    private boolean markerOrderMilitary;
    private String markerOrderSelection = "";
    private List<Markers.Marker> markerOrder = List.of();
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
        int availableWidth = Math.max(1, width - 16);
        int columns = Math.max(1, Math.min(Math.max(1, modes.size()),
                (availableWidth + gap) / (size + gap)));
        int rows = Math.max(1, (modes.size() + columns - 1) / columns);
        // On short windows, shrink toolbar targets before allowing rows to leave the viewport.
        int availableHeight = Math.max(1, height - 24);
        if (rows * size + (rows - 1) * 5 > availableHeight) {
            size = Math.max(12, Math.min(size,
                    (availableHeight - Math.max(0, rows - 1) * 3) / rows));
            gap = 2;
        }
        int rowGap = size <= 20 ? 3 : 5;
        int y0 = Math.max(8, height - rows * size - (rows - 1) * rowGap - 10);
        for (int row = 0; row < rows; row++) {
            int first = row * columns;
            int rowCount = Math.min(columns, modes.size() - first);
            if (rowCount <= 0) continue;
            int transitions = 0;
            for (int column = 1; column < rowCount; column++) {
                if (group(modes.get(first + column - 1)) != group(modes.get(first + column)))
                    transitions++;
            }
            int baseWidth = rowCount * size + (rowCount - 1) * gap;
            int rowAvailable = Math.max(1, Math.min(availableWidth, baseWidth + transitions * sep));
            int actualSep = transitions == 0 ? 0
                    : Math.max(0, Math.min(sep, (rowAvailable - baseWidth) / transitions));
            int x = Math.max(8, (width - (baseWidth + transitions * actualSep)) / 2);
            int y = y0 + row * (size + rowGap);
            for (int column = 0; column < rowCount; column++) {
                if (column > 0 && group(modes.get(first + column - 1)) != group(modes.get(first + column)))
                    x += actualSep;
                slots.add(new Slot(modes.get(first + column), x, y, size));
                x += size + gap;
            }
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
        ClientWorldMapBootstrap.ensure();
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
        if (t != null) centerOnBlock(t.capX + mapOriginX(), t.capZ + mapOriginZ());
    }

    private double mapOriginX() { return ClientState.localWorldSnapshot ? ClientState.localWorldOriginX : 0.0; }
    private double mapOriginZ() { return ClientState.localWorldSnapshot ? ClientState.localWorldOriginZ : 0.0; }

    private boolean denyLocalPreviewEdit() {
        if (!ClientState.localWorldSnapshot) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(
                Component.literal("Локальная карта — обзор загруженных чанков; политическое редактирование доступно в серверной карте WorldForge"), true);
        return true;
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
        if (denyLocalPreviewEdit()) return;
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
        double px = (bx - mapOriginX()) / bpp() + MapTextures.w / 2.0;
        double pz = (bz - mapOriginZ()) / bpp() + MapTextures.h / 2.0;
        return new double[]{camX + px * zoom, camY + pz * zoom};
    }

    private int[] screenToPixel(double mx, double my) {
        return new int[]{(int) Math.floor((mx - camX) / zoom), (int) Math.floor((my - camY) / zoom)};
    }

    /** True only for the visible map image; blank canvas must not capture map gestures. */
    private boolean insideMapImage(double mx, double my) {
        if (!MapTextures.ready() || zoom <= 0.0) return false;
        double left = Math.max(0.0, camX);
        double top = Math.max(0.0, camY);
        double right = Math.min(width, camX + MapTextures.w * zoom);
        double bottom = Math.min(height, camY + MapTextures.h * zoom);
        return mx >= left && mx < right && my >= top && my < bottom;
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
        ClientWorldMapBootstrap.ensure();
        if (lastMapState != ClientState.state) {
            lastMapState = ClientState.state;
            fitted = false;
            selected = 0;
            hoverProv = 0;
            hoverLabel = selLabel = -1;
            freeOverlay();
            MapTextures.release();
        }
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
                (centerPixelX - MapTextures.w * 0.5) * mapBpp + mapOriginX(),
                (centerPixelZ - MapTextures.h * 0.5) * mapBpp + mapOriginZ(), zoom,
                militaryView ? "MILITARY" : MapImages.TERRAIN.equals(ClientState.mode) ? "GEOGRAPHIC" : "POLITICAL");
        int mxp = (int) camX, myp = (int) camY, mw = (int) (MapTextures.w * zoom), mh = (int) (MapTextures.h * zoom);
        g.fill(mxp + 5, myp + 6, mxp + mw + 5, myp + mh + 6, 0x66000000);                 // тень под картой
        g.pose().pushPose();
        g.pose().translate((float) camX, (float) camY, 0f);
        g.pose().scale((float) zoom, (float) zoom, 1f);
        g.blit(MapTextures.id, 0, 0, 0f, 0f, MapTextures.w, MapTextures.h, MapTextures.w, MapTextures.h);
        if (ovId != null) g.blit(ovId, ovX, ovY, 0f, 0f, ovW, ovH, ovW, ovH);
        g.pose().popPose();
        drawCompassRose(g, mxp, myp, mw, mh);

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
        if (embeddedInMaqan) {
            drawMapTypeBar(g, mx, my);
            drawLayerMenu(g, mx, my);
        }
        drawLegend(g);
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
                int y = entry.getKey();
                int[] bounds = entry.getValue();
                if (mx >= bounds[0] - 6 && mx <= bounds[1] + 6 && my >= y - 6 && my <= y + 34) return true;
            }
        }
        if (inLayerMenu(mx,my)) return true;
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
        boolean compact = width < 620;
        boolean micro = width < 360;
        return List.of(
                new MapTypeButton("layers", micro ? "L" : compact ? "LAY" : "СЛОИ", mapTypeButtonWidth(64)),
                new MapTypeButton("political", micro ? "P" : compact ? "POL" : "POLITICAL", mapTypeButtonWidth(78)),
                new MapTypeButton("geographic", micro ? "G" : compact ? "GEO" : "GEOGRAPHIC", mapTypeButtonWidth(88)),
                new MapTypeButton("military", micro ? "M" : compact ? "MIL" : "MILITARY", mapTypeButtonWidth(78)),
                new MapTypeButton("exploration", micro ? "E" : compact ? "EXP" : "EXPLORE", mapTypeButtonWidth(82)),
                new MapTypeButton("diorama", "3D", mapTypeButtonWidth(64)));
    }

    private int mapTypeButtonWidth(int preferred) {
        int availableWidth = panel.isOpen() ? panel.leftEdge() - 16 : width - 58;
        int available = Math.max(10, (availableWidth - MAP_TYPE_GAP * 5) / 6);
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
            boolean active = switch (button.id()) {
                case "layers" -> layerMenuOpen;
                case "political" -> !militaryView && !MapImages.TERRAIN.equals(ClientState.mode);
                case "geographic" -> !militaryView && MapImages.TERRAIN.equals(ClientState.mode);
                case "military" -> militaryView;
                default -> false;
            };
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
                    case "layers" -> { layerMenuOpen = !layerMenuOpen; layerMenuScroll = 0; }
                    case "political" -> { layerMenuOpen = false; militaryView = false; setMode(MapImages.REALMS); }
                    case "geographic" -> { layerMenuOpen = false; militaryView = false; setMode(MapImages.TERRAIN); }
                    case "military" -> { layerMenuOpen = false; militaryView = true; setMode(MapImages.TERRAIN); }
                    case "exploration" -> { layerMenuOpen = false; openExplorationMap(); }
                    case "diorama" -> { layerMenuOpen = false; openDioramaView(); }
                    default -> { return false; }
                }
                return true;
            }
            x = right + MAP_TYPE_GAP;
        }
        return false;
    }

    private record MapLayerRow(String key,String label,boolean heading) {}

    private List<MapLayerRow> mapLayerRows(){
        List<String> available=modes();
        List<MapLayerRow> rows=new ArrayList<>();
        rows.add(new MapLayerRow("","ГЕОГРАФИЯ",true));
        for(String key:available)if(MapImages.TERRAIN.equals(key))
            rows.add(new MapLayerRow(key,modeName(key),false));
        rows.add(new MapLayerRow("","ТЕРРИТОРИАЛЬНАЯ ИЕРАРХИЯ",true));
        for(String key:available)if(!MapImages.TERRAIN.equals(key)&&!MapImages.REALMS.equals(key)
                &&!MapImages.isDip(key)&&!MapImages.isProp(key))
            rows.add(new MapLayerRow(key,modeName(key),false));
        rows.add(new MapLayerRow("","ГОСУДАРСТВА И ДИПЛОМАТИЯ",true));
        for(String key:available)if(MapImages.REALMS.equals(key)||MapImages.isDip(key))
            rows.add(new MapLayerRow(key,modeName(key),false));
        rows.add(new MapLayerRow("","ОБЩЕСТВО И СВОЙСТВА",true));
        for(String key:available)if(MapImages.isProp(key))
            rows.add(new MapLayerRow(key,modeName(key),false));
        return rows;
    }

    private int[] layerMenuBounds(){
        int w=Math.min(228,Math.max(164,width-16));
        int x=Math.max(8,Math.min(mapTypeBarX(),width-w-8));
        int y=MAP_TYPE_BAR_Y+MAP_TYPE_BAR_H+5;
        int visible=Math.max(3,Math.min(mapLayerRows().size(),(height-y-20)/16));
        return new int[]{x,y,w,visible*16+8,visible};
    }

    private boolean inLayerMenu(double mx,double my){
        if(!embeddedInMaqan||!layerMenuOpen)return false;
        int[] b=layerMenuBounds();
        return mx>=b[0]&&mx<b[0]+b[2]&&my>=b[1]&&my<b[1]+b[3];
    }

    private void drawLayerMenu(GuiGraphics g,int mx,int my){
        if(!layerMenuOpen)return;
        List<MapLayerRow> rows=mapLayerRows();
        int[] b=layerMenuBounds();
        int x=b[0],y=b[1],w=b[2],h=b[3],visible=b[4];
        layerMenuScroll=Math.max(0,Math.min(layerMenuScroll,Math.max(0,rows.size()-visible)));
        g.fill(x,y,x+w,y+h,0xF0121822);
        g.fill(x,y,x+w,y+1,0xFFB58A4B);
        g.renderOutline(x,y,w,h,0xFF6C7888);
        g.enableScissor(x+2,y+3,x+w-2,y+h-3);
        for(int row=0;row<visible;row++){
            int index=layerMenuScroll+row;
            if(index>=rows.size())break;
            MapLayerRow entry=rows.get(index);
            int ry=y+4+row*16;
            if(entry.heading()){
                g.drawString(font,entry.label(),x+8,ry+3,0xFFE0BD78,false);
            }else{
                boolean active=entry.key().equals(ClientState.mode)
                        ||(MapImages.isDip(entry.key())&&MapImages.isDip(ClientState.mode));
                boolean hover=mx>=x+4&&mx<x+w-4&&my>=ry&&my<ry+16;
                if(active||hover)g.fill(x+4,ry,x+w-4,ry+15,active?0x553C7183:0x443A5368);
                g.drawString(font,active?"◆":"·",x+9,ry+3,active?0xFFFFD36A:0xFF9AAABA,false);
                g.drawString(font,font.plainSubstrByWidth(entry.label(),w-34),x+22,ry+3,
                        active?0xFFFFE7B0:0xFFE1E6ED,false);
            }
        }
        g.disableScissor();
    }

    private boolean handleLayerMenuClick(double mx,double my){
        if(!layerMenuOpen)return false;
        int[] b=layerMenuBounds();
        if(mx<b[0]||mx>=b[0]+b[2]||my<b[1]||my>=b[1]+b[3]){
            layerMenuOpen=false;
            return false;
        }
        int row=(int)((my-b[1]-4)/16)+layerMenuScroll;
        List<MapLayerRow> rows=mapLayerRows();
        if(row>=0&&row<rows.size()&&!rows.get(row).heading()){
            setMode(rows.get(row).key());
            layerMenuOpen=false;
        }
        return true;
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

    /** Engraved vellum frame that visually separates the political atlas from the game UI. */
    private void drawCartographicFrame(GuiGraphics g, int x, int y, int mapW, int mapH) {
        if (mapW <= 0 || mapH <= 0) return;
        g.enableScissor(0, 0, width, height);
        // Fill only the four frame rails; never paint over the parchment map itself.
        g.fill(x - 7, y - 7, x + mapW + 7, y - 4, 0xFF342719);
        g.fill(x - 7, y + mapH + 4, x + mapW + 7, y + mapH + 7, 0xFF342719);
        g.fill(x - 7, y - 4, x - 4, y + mapH + 4, 0xFF342719);
        g.fill(x + mapW + 4, y - 4, x + mapW + 7, y + mapH + 4, 0xFF342719);
        g.renderOutline(x - 7, y - 7, mapW + 14, mapH + 14, 0xFF21180F);
        g.renderOutline(x - 5, y - 5, mapW + 10, mapH + 10, 0xFFB58A4B);
        g.renderOutline(x - 3, y - 3, mapW + 6, mapH + 6, 0xFF6D4D2D);
        g.renderOutline(x - 1, y - 1, mapW + 2, mapH + 2, 0xFFE0C28A);
        // Corner rivets and short engraved flourishes.
        int[][] corners = {{x - 5, y - 5, 1, 1}, {x + mapW + 4, y - 5, -1, 1},
                {x - 5, y + mapH + 4, 1, -1}, {x + mapW + 4, y + mapH + 4, -1, -1}};
        for (int[] corner : corners) {
            int cx = corner[0], cy = corner[1], sx = corner[2], sy = corner[3];
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFD98B);
            g.fill(Math.min(cx + sx, cx + sx * 5), cy,
                    Math.max(cx + sx, cx + sx * 5) + 1, cy + 1, 0xFF9B7544);
            g.fill(cx, Math.min(cy + sy, cy + sy * 5),
                    cx + 1, Math.max(cy + sy, cy + sy * 5) + 1, 0xFF9B7544);
        }
        g.disableScissor();
    }

    /** Small north rose, intentionally restrained so it never competes with realm labels. */
    private void drawCompassRose(GuiGraphics g, int x, int y, int mapW, int mapH) {
        if (mapW < 220 || mapH < 150) return;
        int cx = x + mapW - 27, cy = y + 29;
        g.fill(cx - 13, cy - 13, cx + 14, cy + 14, 0x66503B22);
        g.renderOutline(cx - 13, cy - 13, 27, 27, 0xFFB58A4B);
        g.fill(cx - 1, cy - 11, cx + 2, cy + 11, 0xFFE8D3A0);
        g.fill(cx - 10, cy - 1, cx + 11, cy + 2, 0xFFE8D3A0);
        g.fill(cx - 2, cy - 12, cx + 2, cy - 3, 0xFF8D3528);
        g.fill(cx - 2, cy + 3, cx + 2, cy + 10, 0xFF4D3825);
        g.fill(cx - 9, cy - 2, cx - 3, cy + 2, 0xFF4D3825);
        g.fill(cx + 3, cy - 2, cx + 9, cy + 2, 0xFF4D3825);
        g.drawCenteredString(font, "N", cx, cy - 23, 0xFFFFE5B0);
    }

    private void drawPlate(GuiGraphics g) {
        String title = militaryView ? "Военная карта" : modeName(ClientState.mode);
        String sub = MapTextures.w + "x" + MapTextures.h + " px · "
                + Math.round(MapTextures.w * bpp()) + "x" + Math.round(MapTextures.h * bpp()) + " бл.";
        if (ClientClock.known()) {
            String clock = ClientClock.date();
            String pace = ClientClock.paused() ? "ПАУЗА" : ClientClock.speed();
            if (clock != null && !clock.isBlank()) sub += " · " + clock;
            if (pace != null && !pace.isBlank()) sub += " · " + pace;
        }
        int desired = Math.max(font.width(title) * 3 / 2, font.width(sub)) + 20;
        int w = Math.max(120, Math.min(Math.max(120, width - 16), desired));
        sub = font.plainSubstrByWidth(sub, Math.max(1, w - 14));
        bevel(g, 8, 8, w, 34, false);
        g.pose().pushPose();
        g.pose().translate(15f, 13f, 0f);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawString(font, title, 0, 0, 0xFFF0C85A);
        g.pose().popPose();
        g.drawString(font, sub, 15, 29, 0xFFA6A8B3);
        if (ClientState.localWorldSnapshot)
            g.drawString(font, "LOCAL PREVIEW", Math.max(8, width - font.width("LOCAL PREVIEW") - 12), 13, 0xFF9CCBEE, false);
    }

    private void drawCoords(GuiGraphics g, int mx, int my) {
        if (overUi(mx, my) || !MapTextures.ready()) return;
        int[] p = screenToPixel(mx, my);
        TitleData d = MapTextures.titles;
        String t = "X " + Math.round((p[0] + 0.5 - d.w / 2.0) * d.bpp + mapOriginX())
                + "  Z " + Math.round((p[1] + 0.5 - d.h / 2.0) * d.bpp + mapOriginZ());
        int w = font.width(t) + 14;
        bevel(g, 8, height - 26, w, 18, false);
        g.drawString(font, t, 15, height - 21, 0xFFECEBE4);
    }

    private void drawScale(GuiGraphics g) {
        double per = zoom / bpp(), target = 90, raw = target / per, pw = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / pw;
        double step = (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * pw;
        int px = (int) Math.round(step * per);
        String t = step >= 10000 ? String.format(java.util.Locale.ROOT, "%.1fk бл.", step / 1000.0)
                : step >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fk бл.", step / 1000.0)
                : Math.max(1L, Math.round(step)) + " бл.";
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
        // Fit the complete world into a bounded overview frame without stretching tall/narrow worlds.
        double fit = Math.min(170.0 / MapTextures.w, 140.0 / MapTextures.h);
        fit = Math.min(fit, Math.min((width - 28.0) / MapTextures.w, (height - 86.0) / MapTextures.h));
        fit = Math.max(0.01, fit);
        int ow = Math.max(1, (int) Math.round(MapTextures.w * fit));
        int oh = Math.max(1, (int) Math.round(MapTextures.h * fit));
        ovwW = ow; ovwH = oh; ovwX = width - ow - 14; ovwY = Math.max(38, height - oh - 44);
        bevel(g, ovwX - 4, ovwY - 4, ow + 8, oh + 8, true);
        g.fill(ovwX, ovwY, ovwX + ow, ovwY + oh, 0xFF0A0C12);
        g.enableScissor(ovwX, ovwY, ovwX + ow, ovwY + oh);
        g.pose().pushPose();
        g.pose().translate(ovwX, ovwY, 0f);
        g.pose().scale((float) fit, (float) fit, 1f);
        g.blit(MapTextures.id, 0, 0, 0f, 0f, MapTextures.w, MapTextures.h, MapTextures.w, MapTextures.h);
        g.pose().popPose();
        g.disableScissor();
        double x0 = Math.max(0, -camX / zoom), y0 = Math.max(0, -camY / zoom);
        double x1 = Math.min(MapTextures.w, (width - camX) / zoom), y1 = Math.min(MapTextures.h, (height - camY) / zoom);
        int rx = ovwX + (int) Math.floor(x0 * fit), ry = ovwY + (int) Math.floor(y0 * fit);
        int rw = Math.max(3, (int) Math.ceil((x1 - x0) * fit)), rh = Math.max(3, (int) Math.ceil((y1 - y0) * fit));
        g.enableScissor(ovwX, ovwY, ovwX + ow, ovwY + oh);
        g.renderOutline(rx - 1, ry - 1, rw + 2, rh + 2, 0xFF000000);
        g.renderOutline(rx, ry, rw, rh, 0xFFF0F0F0);
        g.disableScissor();
    }

    private void overviewTo(double mx, double my) {
        if (ovwW <= 0 || ovwH <= 0) return;
        double scale = Math.min(ovwW / (double) MapTextures.w, ovwH / (double) MapTextures.h);
        double px = (mx - ovwX) / scale, py = (my - ovwY) / scale;
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
        if (!(MapImages.isProp(m) || MapImages.REALMS.equals(m) || MapImages.isDip(m))) return;
        if (legendRev != ClientState.revision || !m.equals(legendMode)) {
            legendRev = ClientState.revision; legendMode = m;
            legendRgb.clear(); legendNames.clear(); legendArea.clear();
            List<Object[]> rows = new ArrayList<>();
            if (MapImages.isProp(m)) {
                MapImages.PropInfo pi = MapImages.propInfo(d, m.substring(2));
                for (int i = 0; i < pi.vals.size(); i++) rows.add(new Object[]{pi.vals.get(i), pi.rgb[i], pi.area[i]});
            } else if (MapImages.isDip(m)) {
                TitleData.Realm focus = d.realms.get(m.substring(MapImages.DIP.length()));
                if (focus != null) {
                    Map<String, java.util.Set<String>> adjacency = dev.worldforge.mod.api.Diplomacy.adjacency(d);
                    for (dev.worldforge.mod.api.Diplomacy.Rel relation : dev.worldforge.mod.api.Diplomacy.Rel.values()) {
                        long area = 0;
                        for (TitleData.Realm realm : d.realmList())
                            if (dev.worldforge.mod.api.Diplomacy.relation(d, focus, realm, adjacency) == relation)
                                area += realm.areaBlocks;
                        if (area > 0) rows.add(new Object[]{relation.label, relation.rgb, area});
                    }
                }
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
        g.drawString(font, MapImages.REALMS.equals(m) ? "Державы" : MapImages.isDip(m) ? "Дипломатия" : modeName(m), x + 8, y + 7, 0xFFF0C85A);
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

    private void refreshHeraldryIndex() {
        if (heraldryRevision == ClientState.revision) return;
        Map<String, TitleData.Realm> index = new HashMap<>();
        if (MapTextures.titles != null) {
            for (TitleData.Realm realm : MapTextures.titles.realmList()) {
                if (realm != null && realm.name != null && !realm.name.isBlank())
                    index.put(realm.name, realm);
            }
        }
        realmsByName = Map.copyOf(index);
        heraldryRevision = ClientState.revision;
    }

    /** Procedural heraldic fallback: crisp shield, field division and charge from realm identity. */
    private void drawHeraldicShield(GuiGraphics g, int x, int y, TitleData.Realm realm) {
        Object textureValue = realm.props.get("heraldry.texture");
        if (textureValue instanceof String asset && !asset.isBlank()) {
            ResourceLocation assetId = ResourceLocation.tryParse(asset.trim());
            if (assetId != null && Minecraft.getInstance().getResourceManager().getResource(assetId).isPresent()) {
                g.fill(x + 1, y, x + 11, y + 13, 0xFFE8D3A0);
                g.blit(assetId, x + 2, y + 1, 8, 11, 0, 0, 32, 32, 32, 32);
                return;
            }
        }
        int primary = 0xFF000000 | (realm.rgb & 0xFFFFFF);
        Object secondaryValue = realm.props.get("heraldry.secondary_color");
        int secondary = primary ^ 0x005A6470;
        if (secondaryValue instanceof String value && value.matches("#[0-9a-fA-F]{6}")) {
            try { secondary = 0xFF000000 | Integer.parseInt(value.substring(1), 16); }
            catch (NumberFormatException ignored) { /* deterministic palette fallback */ }
        }
        int seed = realm.id == null ? realm.name.hashCode() : realm.id.hashCode();
        g.fill(x + 2, y, x + 10, y + 2, 0xFFE8D3A0);
        g.fill(x + 1, y + 2, x + 11, y + 8, 0xFFE8D3A0);
        g.fill(x + 2, y + 8, x + 10, y + 11, 0xFFE8D3A0);
        g.fill(x + 4, y + 11, x + 8, y + 13, 0xFFE8D3A0);
        g.fill(x + 3, y + 1, x + 9, y + 2, primary);
        g.fill(x + 2, y + 2, x + 10, y + 8, primary);
        g.fill(x + 3, y + 8, x + 9, y + 10, primary);
        g.fill(x + 4, y + 10, x + 8, y + 11, primary);
        String field = String.valueOf(realm.props.getOrDefault("heraldry.pattern", (seed & 1) == 0 ? "per_pale" : "per_fess"));
        switch (field.toLowerCase(java.util.Locale.ROOT)) {
            case "per_fess" -> g.fill(x + 2, y + 6, x + 10, y + 10, secondary);
            case "quarterly" -> {
                g.fill(x + 2, y + 2, x + 6, y + 6, secondary);
                g.fill(x + 6, y + 6, x + 10, y + 10, secondary);
            }
            case "chevron" -> {
                g.fill(x + 2, y + 3, x + 4, y + 5, secondary);
                g.fill(x + 4, y + 5, x + 6, y + 7, secondary);
                g.fill(x + 6, y + 5, x + 8, y + 7, secondary);
                g.fill(x + 8, y + 3, x + 10, y + 5, secondary);
            }
            case "plain" -> { /* single-colour field */ }
            default -> g.fill(x + 6, y + 2, x + 10, y + 8, secondary);
        }
        int charge = 0xFFFFE7B0;
        String emblem = String.valueOf(realm.props.getOrDefault("heraldry.charge", ""));
        switch (emblem.toLowerCase(java.util.Locale.ROOT)) {
            case "cross" -> {
                g.fill(x + 5, y + 3, x + 7, y + 9, charge);
                g.fill(x + 3, y + 5, x + 9, y + 7, charge);
            }
            case "eagle" -> {
                g.fill(x + 2, y + 4, x + 5, y + 6, charge);
                g.fill(x + 7, y + 4, x + 10, y + 6, charge);
                g.fill(x + 4, y + 5, x + 8, y + 8, charge);
                g.fill(x + 5, y + 3, x + 7, y + 5, charge);
            }
            case "lion" -> {
                g.fill(x + 4, y + 3, x + 8, y + 8, charge);
                g.fill(x + 3, y + 4, x + 5, y + 6, charge);
                g.fill(x + 7, y + 4, x + 9, y + 6, charge);
                g.fill(x + 6, y + 2, x + 8, y + 4, charge);
            }
            case "tree" -> {
                g.fill(x + 5, y + 3, x + 7, y + 8, charge);
                g.fill(x + 3, y + 4, x + 9, y + 6, charge);
                g.fill(x + 4, y + 3, x + 8, y + 5, charge);
            }
            case "sword" -> {
                g.fill(x + 6, y + 2, x + 7, y + 8, charge);
                g.fill(x + 4, y + 6, x + 8, y + 7, charge);
                g.fill(x + 5, y + 8, x + 8, y + 9, charge);
            }
            case "star" -> {
                g.fill(x + 5, y + 2, x + 7, y + 9, charge);
                g.fill(x + 3, y + 4, x + 9, y + 7, charge);
            }
            default -> {
                switch (Math.floorMod(seed, 3)) {
                    case 0 -> {
                        g.fill(x + 5, y + 3, x + 7, y + 8, charge);
                        g.fill(x + 3, y + 5, x + 9, y + 7, charge);
                    }
                    case 1 -> {
                        g.fill(x + 4, y + 3, x + 8, y + 5, charge);
                        g.fill(x + 5, y + 5, x + 7, y + 8, charge);
                    }
                    default -> {
                        g.fill(x + 4, y + 3, x + 8, y + 7, charge);
                        g.fill(x + 5, y + 7, x + 7, y + 9, charge);
                    }
                }
            }
        }
    }

    private void drawLabels(GuiGraphics g) {
        if (MapImages.TERRAIN.equals(MapTextures.mode)) return;
        // Spatial occupancy replaces the old all-pairs collision scan. Label placement
        // stays bounded when a large political world contains thousands of provinces.
        final int cell = 48;
        boolean showHeraldry = MapImages.REALMS.equals(MapTextures.mode) || MapImages.isDip(MapTextures.mode);
        if (showHeraldry) refreshHeraldryIndex();
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        int left = Math.max(0, (int) Math.floor(camX));
        int top = Math.max(BAR, (int) Math.floor(camY));
        int right = Math.min(width, (int) Math.ceil(camX + MapTextures.w * zoom));
        int bottom = Math.min(height - (embeddedInMaqan ? 0 : BOTTOM), (int) Math.ceil(camY + MapTextures.h * zoom));
        if (right <= left || bottom <= top) return;
        g.enableScissor(left, top, right, bottom);
        for (MapTextures.Label l : MapTextures.labels) {
            double size = Math.min(26, Math.sqrt(Math.max(0, l.areaPx)) * zoom * 0.14 + 2);
            if (size < 8) continue;
            double sx = camX + l.px * zoom, sy = camY + (l.py - 4 / zoom) * zoom;
            if (sx < left - 100 || sy < top - 20 || sx > right + 100 || sy > bottom + 20) continue;
            double scale = size / 9.0, w = font.width(l.text) * scale, h = size;
            TitleData.Realm realm = showHeraldry ? realmsByName.get(l.text) : null;
            double x0=sx-w/2.0-(realm!=null?16.0:0.0),x1=sx+w/2.0,y0=sy-h/2.0,y1=sy+h/2.0;
            int gx0=(int)Math.floor(x0/cell),gx1=(int)Math.floor(x1/cell);
            int gy0=(int)Math.floor(y0/cell),gy1=(int)Math.floor(y1/cell);
            boolean clash=false;
            for(int gy=gy0;gy<=gy1&&!clash;gy++)for(int gx=gx0;gx<=gx1;gx++){
                long key=((long)gx<<32)^(gy&0xffffffffL);
                if(occupied.contains(key)){clash=true;break;}
            }
            if(clash)continue;
            for(int gy=gy0;gy<=gy1;gy++)for(int gx=gx0;gx<=gx1;gx++)
                occupied.add(((long)gx<<32)^(gy&0xffffffffL));
            g.pose().pushPose();
            g.pose().translate((float)sx,(float)sy,0f);
            g.pose().scale((float)scale,(float)scale,1f);
            g.drawCenteredString(font,l.text,0,-4,0xFFF4ECD2);
            g.pose().popPose();
            if (realm != null && size >= 12)
                drawHeraldicShield(g,(int)Math.round(sx-w/2.0-14.0),(int)Math.round(sy-7.0),realm);
        }
        g.disableScissor();
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
            if (militaryView && !("army".equals(marker.type) || "castle".equals(marker.type)
                    || "fort".equals(marker.type) || "capital".equals(marker.type)
                    || "city".equals(marker.type) || "settlement".equals(marker.type)
                    || "caravan".equals(marker.type) || "port".equals(marker.type))) continue;
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
        WorldObjectVisualCatalog.Profile visual = WorldObjectVisualCatalog.resolve(marker);
        int size = Math.max(16, Math.min(48,
                (int) Math.round(visual.mapScale() * Math.sqrt(Math.max(0.16, zoom)))));
        double hitRadius = Math.max(12.0, size * 0.62);
        double hitDx = mx - x, hitDy = my - y;
        boolean hovered = hitDx * hitDx + hitDy * hitDy <= hitRadius * hitRadius;
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
        if (!renderedLeader && ("army".equals(marker.type) || "commander".equals(marker.type))
                && commanderBody != null && commanderBody.isAlive()) {
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
        // At close strategic zoom, render the real Minecraft item/block model for every
        // world object, including cities and fortifications. The vector silhouette remains
        // the deterministic fallback when the item model is unavailable.
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
            if ("army".equals(marker.type) || "army".equals(visual.icon())) {
                drawArmyMiniature(g, x, y, size, realmColor, marker);
            } else {
                drawSettlementMiniature(g, x, y, size, realmColor, visual.icon(), marker.type);
            }
        }
        if (selected || hovered) {
            g.renderOutline(left - 2, top - 2, size + 4, size + 4,
                    selected ? 0xFFFFD66B : 0xFFE9D28B);
        }
    }
    /**
     * Architectural map miniatures drawn from vector primitives. These remain crisp at
     * strategic zoom and communicate object class without relying on missing font glyphs.
     */
    private void drawSettlementMiniature(GuiGraphics g, int screenX, int screenY, int size,
                                          int realmColor, String visualKind, String markerType) {
        String kind = visualKind == null || visualKind.isBlank() ? markerType : visualKind;
        int stone = 0xFFD5C29A, shadow = 0xFF46372A, roof = realmColor;
        float scale = Math.max(0.72f, Math.min(1.85f, size / 25.0f));
        g.pose().pushPose();
        g.pose().translate(screenX, screenY, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.fill(-12, 9, 12, 12, 0x55301810);
        switch (kind) {
            case "capital" -> {
                // Palace keep, paired towers and a small royal standard.
                g.fill(-9, -2, 9, 9, shadow);
                g.fill(-8, -5, 8, 8, stone);
                g.fill(-12, -10, -5, 7, stone);
                g.fill(5, -10, 12, 7, stone);
                g.fill(-10, -12, -4, -9, roof);
                g.fill(4, -12, 10, -9, roof);
                g.fill(-3, -12, 4, 8, stone);
                g.fill(-2, -15, 3, -12, roof);
                g.fill(-1, -8, 2, 0, 0xFF33271D);
                g.fill(7, -15, 8, -5, 0xFFD8C9A5);
                g.fill(8, -15, 14, -11, roof);
                g.fill(-6, 0, -4, 3, 0xFF80603A);
                g.fill(4, 0, 6, 3, 0xFF80603A);
            }
            case "city", "settlement" -> {
                // Walled town with a gatehouse and clustered roofs.
                g.fill(-12, 1, 12, 9, stone);
                g.fill(-10, -4, -3, 7, 0xFFB7A17B);
                g.fill(-11, -7, -2, -3, roof);
                g.fill(2, -7, 10, 7, 0xFFB7A17B);
                g.fill(1, -10, 11, -6, roof);
                g.fill(-2, -11, 3, 9, stone);
                g.fill(-3, -14, 4, -10, roof);
                g.fill(-1, 3, 2, 9, shadow);
                g.fill(-12, 0, -9, 3, 0xFF80603A);
                g.fill(9, 0, 12, 3, 0xFF80603A);
            }
            case "village" -> {
                // One timber-and-thatch homestead.
                g.fill(-9, -1, 9, 9, 0xFFB89A70);
                g.fill(-11, -5, 0, 0, roof);
                g.fill(-1, -8, 10, 0, 0xFF8C5A32);
                g.fill(-2, 2, 2, 9, shadow);
                g.fill(4, 2, 7, 5, 0xFF6B8A8D);
                g.fill(-12, 8, 12, 10, 0xFF6F7047);
            }
            case "castle", "fort" -> {
                // Compact fortified keep, crenellated curtain wall and gate.
                g.fill(-12, -1, 12, 9, stone);
                g.fill(-10, -11, -5, 8, 0xFFBBAA8B);
                g.fill(5, -11, 10, 8, 0xFFBBAA8B);
                g.fill(-11, -14, -4, -10, roof);
                g.fill(4, -14, 11, -10, roof);
                g.fill(-4, -7, 5, 8, stone);
                g.fill(-5, -11, 6, -7, roof);
                g.fill(-2, 2, 3, 9, shadow);
                g.fill(-12, -3, -8, -1, 0xFF80603A);
                g.fill(8, -3, 12, -1, 0xFF80603A);
            }
            case "port" -> {
                // Warehouse and pier, with a mast and sail.
                g.fill(-10, -4, 7, 7, stone);
                g.fill(-11, -8, 8, -3, roof);
                g.fill(8, -14, 9, 8, 0xFF684B30);
                g.fill(9, -13, 15, -6, 0xFFE7D5AC);
                g.fill(-12, 8, 13, 10, 0xFF557F91);
                g.fill(-8, 10, -5, 12, 0xFF9C7848);
                g.fill(2, 10, 5, 12, 0xFF9C7848);
            }
            case "mine" -> {
                g.fill(-11, 2, 11, 9, 0xFF786D5D);
                g.fill(-9, -5, -6, 4, shadow);
                g.fill(6, -5, 9, 4, shadow);
                g.fill(-10, -7, 10, -4, 0xFF9A835F);
                g.fill(-2, -1, 3, 9, shadow);
                g.fill(-1, -13, 1, -2, 0xFFD7C39B);
                g.fill(-6, -9, 6, -7, 0xFFD7C39B);
            }
            case "temple" -> {
                g.fill(-12, 6, 12, 9, stone);
                g.fill(-10, -7, 10, 6, 0xFFE0D1AF);
                for (int cx = -8; cx <= 8; cx += 8) g.fill(cx - 1, -6, cx + 2, 6, 0xFFB7A17B);
                g.fill(-12, -9, 12, -6, roof);
                g.fill(-7, -13, 7, -9, 0xFFE6D4A8);
                g.fill(-1, -16, 2, -12, 0xFFFFD77A);
            }
            case "ruin" -> {
                g.fill(-11, 4, 11, 9, stone);
                g.fill(-10, -9, -6, 5, 0xFF8F8876);
                g.fill(-10, -11, -5, -8, shadow);
                g.fill(5, -5, 9, 6, 0xFF8F8876);
                g.fill(-7, -1, -3, 4, shadow);
                g.fill(1, 1, 5, 6, shadow);
                g.fill(-12, 8, 12, 10, 0xFF6F7047);
            }
            case "caravan" -> {
                // Pack animals and covered merchant wagon.
                g.fill(-11, 0, 0, 7, 0xFF9C7547);
                g.fill(-8, -5, -1, 1, 0xFFE1D0A8);
                g.fill(-4, -8, 3, -4, roof);
                g.fill(3, 2, 11, 7, 0xFF8A633E);
                g.fill(4, -5, 10, 2, 0xFFD6C29A);
                g.fill(5, -8, 10, -5, roof);
                g.fill(-7, 7, -3, 10, shadow);
                g.fill(6, 7, 10, 10, shadow);
            }
            case "camp" -> {
                g.fill(-12, 7, 12, 10, 0xFF6F7047);
                g.fill(-9, -7, 0, 7, 0xFFC6A36C);
                g.fill(-10, -8, 1, -5, roof);
                g.fill(1, -5, 10, 7, 0xFFB98E55);
                g.fill(0, -7, 11, -4, 0xFF8A5A32);
                g.fill(-1, 1, 2, 7, shadow);
            }
            default -> {
                g.fill(-7, -7, 8, 8, stone);
                g.renderOutline(-7, -7, 15, 15, roof);
                g.fill(-2, -2, 3, 3, 0xFFFFD77A);
            }
        }
        g.pose().popPose();
    }

    /**
     * Compact tactical formation miniature. At strategic zoom it collapses to a single
     * readable standard-bearer; closer zoom reveals ranks, shields and marching animation.
     * The marker remains a map representation only: it never creates duplicate game entities.
     */
    private void drawArmyMiniature(GuiGraphics g, double screenX, double screenY, int size,
                                   int realmColor, Markers.Marker marker) {
        Minecraft mc = Minecraft.getInstance();
        boolean moving = "true".equalsIgnoreCase(markerValue(marker.desc, "moving"));
        int men = Math.max(0, markerInt(marker.desc, "men"));
        int heading = parseMarkerHeading(markerValue(marker.desc, "heading"));
        int phase = moving && mc.level != null ? (int) ((mc.level.getGameTime() / 6L) & 1L) : 0;
        float scale = Math.max(0.72f, Math.min(1.8f, size / 25.0f));
        g.pose().pushPose();
        g.pose().translate((float) screenX, (float) screenY, 0.0f);
        // Heading is quantized to eight directions to stay crisp at pixel scale.
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(heading));
        g.pose().scale(scale, scale, 1.0f);

        int figures = size < 22 ? 1 : size < 31 ? 3 : size < 42 ? 5 : 7;
        if (men > 0 && men < 12) figures = Math.min(figures, 1);
        int columns = figures <= 1 ? 1 : figures <= 3 ? 2 : 3;
        int rows = (figures + columns - 1) / columns;
        g.fill(-12, 9, 12, 12, 0x55301810);
        for (int i = 0; i < figures; i++) {
            int col = i % columns;
            int row = i / columns;
            int px = (col - (columns - 1) / 2) * 8;
            int py = (row - (rows - 1) / 2) * 7 + 2;
            int stride = moving ? (phase == 0 ? 1 : -1) : 0;
            int leg = (i & 1) == 0 ? stride : -stride;
            // Boots and alternating marching legs.
            g.fill(px - 2 + leg, py + 3, px, py + 7, 0xFF30251E);
            g.fill(px + 1 - leg, py + 3, px + 3, py + 7, 0xFF30251E);
            // Mail/coat, head and helmet.
            g.fill(px - 3, py - 2, px + 4, py + 4, realmColor);
            g.fill(px - 2, py - 6, px + 3, py - 1, 0xFFC79B70);
            g.fill(px - 3, py - 8, px + 4, py - 5, 0xFFD7C08B);
            g.fill(px - 1, py - 10, px + 3, py - 8, 0xFF8D7447);
            // Small shield and spear; silhouettes stay legible as the formation scales.
            g.fill(px - 6, py - 1, px - 4, py + 4, 0xFFE0C995);
            g.fill(px - 5, py, px - 4, py + 3, realmColor);
            g.fill(px + 5, py - 10, px + 6, py + 5, 0xFFD8C9A5);
        }
        // Command standard is anchored to the centre and remains visible at all zooms.
        g.fill(7, -15, 8, 6, 0xFFD8C9A5);
        int flagShift = moving ? phase : 0;
        g.fill(8, -14, 15 + flagShift, -9, realmColor);
        g.fill(8, -14, 15 + flagShift, -13, 0xFFFFD778);
        g.fill(8, -9, 12, -7, 0xFFE0C995);
        g.pose().popPose();
    }

    private static int parseMarkerHeading(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            double degrees = Double.parseDouble(raw.trim());
            return Math.floorMod((int) Math.round(degrees / 45.0) * 45, 360);
        } catch (NumberFormatException ignored) {
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "north", "n" -> 0;
                case "east", "e" -> 90;
                case "south", "s" -> 180;
                case "west", "w" -> 270;
                case "north_east", "ne" -> 45;
                case "south_east", "se" -> 135;
                case "south_west", "sw" -> 225;
                case "north_west", "nw" -> 315;
                default -> 0;
            };
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

    private boolean markerBelongsToMilitaryView(Markers.Marker marker) {
        return "army".equals(marker.type) || "castle".equals(marker.type)
                || "fort".equals(marker.type) || "capital".equals(marker.type)
                || "city".equals(marker.type) || "settlement".equals(marker.type)
                || "caravan".equals(marker.type) || "port".equals(marker.type);
    }

    private List<Markers.Marker> orderedMarkers() {
        if (markerOrderRevision != ClientState.revision || markerOrderMilitary != militaryView
                || !markerOrderSelection.equals(selectedMarkerId)) {
            List<Markers.Marker> visible = new ArrayList<>();
            for (Markers.Marker marker : ClientState.markers.list())
                if (!militaryView || markerBelongsToMilitaryView(marker)) visible.add(marker);
            visible.sort(Comparator.comparingInt(marker -> {
                if (marker.id.equals(selectedMarkerId)) return -5;
                if ("capital".equals(marker.type)) return 0;
                if ("army".equals(marker.type)) return 1;
                if ("city".equals(marker.type) || "settlement".equals(marker.type)) return 2;
                return 3;
            }));
            markerOrder = List.copyOf(visible);
            markerOrderRevision = ClientState.revision;
            markerOrderMilitary = militaryView;
            markerOrderSelection = selectedMarkerId;
        }
        return markerOrder;
    }

    private void drawMarkers(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        Map<String, LivingEntity> leaderBodies = leaderBodies();
        int clipLeft=Math.max(0,(int)Math.floor(camX));
        int clipTop=Math.max(BAR,(int)Math.floor(camY));
        int clipRight=Math.min(width,(int)Math.ceil(camX+MapTextures.w*zoom));
        int clipBottom=Math.min(height-(embeddedInMaqan?0:BOTTOM),(int)Math.ceil(camY+MapTextures.h*zoom));
        if(clipRight<=clipLeft||clipBottom<=clipTop)return;
        g.enableScissor(clipLeft,clipTop,clipRight,clipBottom);
        java.util.Set<Long> labelCells = new java.util.HashSet<>();
        final int labelCellSize = 64;
        int labelBudget = 96;
        for (Markers.Marker marker : orderedMarkers()) {
            if (hiddenArmyMarker(marker)) continue;
            double[] point = blockToScreen(marker.x, marker.z);
            if (point[0] < -20 || point[1] < BAR - 20 || point[0] > width + 20 || point[1] > height + 20) continue;
            String leaderUuid = markerValue(marker.desc, "leaderEntity");
            drawStrategicMarker(g, marker, point[0], point[1], mx, my,
                    leaderBodies.get(leaderUuid));
            boolean selected = marker.id.equals(selectedMarkerId);
            WorldObjectVisualCatalog.Profile profile = WorldObjectVisualCatalog.resolve(marker);
            double markerSize = Math.max(16.0, Math.min(48.0,
                    profile.mapScale() * Math.sqrt(Math.max(0.16, zoom))));
            double markerRadius = Math.max(12.0, markerSize * 0.62);
            double markerDx = mx - point[0], markerDy = my - point[1];
            boolean hovered = markerDx * markerDx + markerDy * markerDy <= markerRadius * markerRadius;
            if ((zoom > 1.15 || selected || hovered) && labelBudget > 0) {
                String label = marker.name;
                if ("army".equals(marker.type)) {
                    String commander = markerValue(marker.desc, "commander").trim();
                    if (!commander.isEmpty()) label = commander;
                }
                int lx=(int)point[0]+11,ly=(int)point[1]-4;
                int gx0=Math.floorDiv(lx,labelCellSize),gx1=Math.floorDiv(lx+font.width(label),labelCellSize);
                int gy0=Math.floorDiv(ly,labelCellSize),gy1=Math.floorDiv(ly+font.lineHeight,labelCellSize);
                boolean clash=false;
                for(int gy=gy0;gy<=gy1&&!clash;gy++)for(int gx=gx0;gx<=gx1;gx++)
                    if(labelCells.contains(((long)gx<<32)^(gy&0xffffffffL))){clash=true;break;}
                if(!clash||selected||hovered){
                    for(int gy=gy0;gy<=gy1;gy++)for(int gx=gx0;gx<=gx1;gx++)
                        labelCells.add(((long)gx<<32)^(gy&0xffffffffL));
                    g.drawString(font,label,lx,ly,selected?0xFFFFD66B:hovered?0xFFFFE6B0:0xFFFFFFFF);
                    labelBudget--;
                }
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
        g.disableScissor();
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
        if (button == 0 && handleLayerMenuClick(mx,my)) return true;
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
        if (!insideMapImage(mx, my)) return false;
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
                selectedMarkerId = "";
                selLabel = labelOfProvince(prov);
                setOverlay(selLabel);
                TitleData.Title selectedTitle = MapTextures.titles.get(prov);
                TitleData.Realm r = MapTextures.titles.realmOf(selectedTitle);
                String summary = "Провинция #" + prov + " · " + selectedTitle.name
                        + (r == null ? " · без государства" : " · государство: " + r.name);
                PlatformWorkspaceBridge.publish("worldforge", "province:" + prov,
                        "PROVINCE", selectedTitle.name, summary);
                if (MapImages.isDip(ClientState.mode) && r != null && !ClientState.mode.equals(MapImages.DIP + r.id)) setMode(MapImages.DIP + r.id, true);
                panel.onSelected(prov);
                centerOnProvince(prov);
            } else {
                selected = 0;
                selectedMarkerId = "";
                selLabel = -1;
                PlatformWorkspaceBridge.clear("worldforge");
                freeOverlay();
            }
            return true;
        }
        if (button == 1 && hasShiftDown()) {
            if (denyLocalPreviewEdit()) return true;
            Minecraft mc = Minecraft.getInstance();
            TitleData d = MapTextures.titles;
            int bx = (int) Math.round((p[0] - d.w / 2.0) * d.bpp + mapOriginX()), bz = (int) Math.round((p[1] - d.h / 2.0) * d.bpp + mapOriginZ());
            if (mc.player != null) mc.player.connection.sendCommand("worldforge tpxz " + bx + " " + bz);
            onClose();
            return true;
        }
        return button == 1;
    }

    /** Отправляет серверу правку в точке карты (пиксель p). */
    private void editAt(int[] p, boolean pick) {
        if (denyLocalPreviewEdit()) return;
        TitleData d = MapTextures.titles;
        if (p[0] < 0 || p[1] < 0 || p[0] >= d.w || p[1] >= d.h) return;
        long bx = Math.round((p[0] + 0.5 - d.w / 2.0) * d.bpp + mapOriginX()), bz = Math.round((p[1] + 0.5 - d.h / 2.0) * d.bpp + mapOriginZ());
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
        if (draggingMap && button == mapDragButton && (button == 0 || button == 1 || button == 2)) {
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
        if (inLayerMenu(mx,my)) {
            int[] bounds=layerMenuBounds();
            int visible=bounds[4];
            int max=Math.max(0,mapLayerRows().size()-visible);
            layerMenuScroll=Math.max(0,Math.min(max,layerMenuScroll+(delta<0?1:-1)));
            return true;
        }
        if (panel.scrolled(mx, my, delta)) return true;
        if (panel.contains(mx, my)) return true;
        if (overUi((int) mx, (int) my)) return true;
        if (!insideMapImage(mx, my)) return false;
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
        if (key == GLFW.GLFW_KEY_O && MapTextures.ready()) {
            if (denyLocalPreviewEdit()) return true;
            Minecraft mc = Minecraft.getInstance();
            double mxx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth();
            double myy = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
            int[] pixel = screenToPixel(mxx, myy);
            TitleData mapData = MapTextures.titles;
            int blockX = (int) Math.round((pixel[0] - mapData.w / 2.0) * mapData.bpp + mapOriginX());
            int blockZ = (int) Math.round((pixel[1] - mapData.h / 2.0) * mapData.bpp + mapOriginZ());
            mc.setScreen(new MarkerCreateScreen(this, blockX, blockZ));
            return true;
        }
        if (key == GLFW.GLFW_KEY_V) {
            Markers.Marker visualTarget = null;
            for (Markers.Marker marker : ClientState.markers.list()) {
                if (selectedMarkerId.equals(marker.id)) { visualTarget = marker; break; }
            }
            if (visualTarget != null) {
                Minecraft.getInstance().setScreen(new VisualCatalogScreen(this, visualTarget));
            } else if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("Сначала выберите политический объект или армию на карте"), true);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_B && MapTextures.ready()) {
            if (denyLocalPreviewEdit()) return true;
            Minecraft mc = Minecraft.getInstance();
            double mxx = mc.mouseHandler.xpos() * width / mc.getWindow().getScreenWidth(), myy = mc.mouseHandler.ypos() * height / mc.getWindow().getScreenHeight();
            int[] p = screenToPixel(mxx, myy);
            TitleData d = MapTextures.titles;
            pendingX = (int) Math.round((p[0] - d.w / 2.0) * d.bpp + mapOriginX());
            pendingZ = (int) Math.round((p[1] - d.h / 2.0) * d.bpp + mapOriginZ());
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
