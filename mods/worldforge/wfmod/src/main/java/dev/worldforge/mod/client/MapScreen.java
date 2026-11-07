package dev.worldforge.mod.client;

import dev.worldforge.mod.MapImages;
import dev.worldforge.mod.Markers;
import dev.worldforge.mod.TitleData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Большая карта: режимы как в Paradox (рельеф, провинции...империи, государства), колесо = масштаб, перетаскивание, клик по провинции
 * показывает цепочку титулов и государство, метки сервера и личные вейпойнты (B), Shift+ПКМ = телепорт для оператора.
 */
public class MapScreen extends Screen {
    private static final int BAR = 0, BOTTOM = 46;
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
        int total = 0, prevG = -1;
        for (String k : modes) { int g = group(k); total += size + gap + (prevG >= 0 && g != prevG ? sep : 0); prevG = g; }
        int x = (width - total) / 2, y = height - size - 10;
        prevG = -1;
        for (String k : modes) {
            int g = group(k);
            if (prevG >= 0 && g != prevG) x += sep;
            prevG = g;
            slots.add(new Slot(k, x, y, size));
            x += size + gap;
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
        double zx = (width - 24) / (double) MapTextures.w, zy = (height - BAR - BOTTOM - 14) / (double) MapTextures.h;
        zoom = Math.min(zx, zy);
        camX = (width - MapTextures.w * zoom) / 2;
        camY = BAR + (height - BAR - BOTTOM - MapTextures.h * zoom) / 2;
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
        drawLegend(g);
        drawOverview(g);
        drawScale(g);
        drawHotbar(g, mx, my);
        drawCoords(g, mx, my);
        panel.render(g, mx, my);
        super.render(g, mx, my, pt);
        if (hoverProv > 0 && !overUi) {
            List<Component> tip = describe(hoverProv, false);
            if (!tip.isEmpty()) g.renderComponentTooltip(font, tip, mx, my);
        }
        for (Slot sl : slots) if (sl.hit(mx, my)) g.renderTooltip(font, Component.literal(isCurrent(sl.key) ? modeName(sl.key) : (slots.indexOf(sl) + 1) + ": " + modeName(sl.key)), mx, my);
    }

    // ---- оформление: рамки со скосом, как у слотов инвентаря
    static void bevel(GuiGraphics g, int x, int y, int w, int h, boolean inset) {
        WFUi.bevel(g, x, y, w, h, inset);
    }

    private boolean overUi(int mx, int my) {
        for (Slot sl : slots) if (sl.hit(mx, my)) return true;
        if (!slots.isEmpty()) {
            Slot f = slots.get(0), l = slots.get(slots.size() - 1);
            if (mx >= f.x - 6 && mx <= l.x + l.size + 6 && my >= f.y - 6) return true;
        }
        if (mx >= ovwX - 6 && my >= ovwY - 6 && ovwW > 0) return true;
        return panel.contains(mx, my);
    }

    private void drawHotbar(GuiGraphics g, int mx, int my) {
        if (slots.isEmpty()) return;
        Slot f = slots.get(0), l = slots.get(slots.size() - 1);
        bevel(g, f.x - 6, f.y - 6, l.x + l.size - f.x + 12, f.size + 12, true);
        List<String> tiers = new ArrayList<>();
        if (ClientState.state != null) for (TitleData.Tier t : ClientState.state.titles.tiers) tiers.add(t.key);
        int prevX = -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot sl = slots.get(i);
            if (prevX >= 0 && sl.x - prevX > sl.size + 4) {                                   // разделитель групп
                int sx = sl.x - 6;
                g.fill(sx, sl.y, sx + 2, sl.y + sl.size, 0xFF000000);
                g.fill(sx + 2, sl.y, sx + 4, sl.y + sl.size, 0xFF5D5D66);
            }
            prevX = sl.x;
            boolean on = isCurrent(sl.key), hov = sl.hit(mx, my);
            bevel(g, sl.x, sl.y, sl.size, sl.size, !on);
            if (hov && !on) g.fill(sl.x + 2, sl.y + 2, sl.x + sl.size - 2, sl.y + sl.size - 2, 0x30FFFFFF);
            if (on) g.renderOutline(sl.x - 2, sl.y - 2, sl.size + 4, sl.size + 4, 0xFFFFFFFF);
            MapIcons.draw(g, MapIcons.keyFor(sl.key, tiers), sl.x + 4, sl.y + 4, 20);
            g.drawString(font, String.valueOf(i + 1), sl.x + 3, sl.y + 2, 0xFFA6A8B3);
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

    private void drawMarkers(GuiGraphics g, int mx, int my) {
        for (Markers.Marker m : ClientState.markers.list()) {
            double[] s = blockToScreen(m.x, m.z);
            if (s[0] < -10 || s[1] < BAR - 10 || s[0] > width + 10 || s[1] > height + 10) continue;
            drawDiamond(g, s[0], s[1], 0xFFD24A);
            if (zoom > 0.9 || Math.abs(mx - s[0]) < 8 && Math.abs(my - s[1]) < 8) g.drawString(font, m.name, (int) s[0] + 7, (int) s[1] - 4, 0xFFFFFFFF);
        }
        for (Waypoints.Wp w : Waypoints.LIST) {
            double[] s = blockToScreen(w.x, w.z);
            if (s[0] < -10 || s[1] < BAR - 10 || s[0] > width + 10 || s[1] > height + 10) continue;
            drawDiamond(g, s[0], s[1], w.rgb);
            g.drawString(font, w.name, (int) s[0] + 7, (int) s[1] - 4, 0xFF9CF2E8);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double[] s = blockToScreen(mc.player.getX(), mc.player.getZ());
            g.fill((int) s[0] - 3, (int) s[1] - 3, (int) s[0] + 3, (int) s[1] + 3, 0xFFFFFFFF);
            g.fill((int) s[0] - 2, (int) s[1] - 2, (int) s[0] + 2, (int) s[1] + 2, 0xFFFF3B3B);
        }
    }

    private List<Component> describe(int prov, boolean full) {
        List<Component> out = new ArrayList<>();
        TitleData d = MapTextures.titles;
        TitleData.Title p = d.get(prov);
        if (p == null) return out;
        out.add(Component.literal(p.name).withStyle(Style.EMPTY.withColor(lighten(p.rgb)).withBold(true)));
        List<TitleData.Title> chain = d.chain(p);
        for (int i = 1; i < chain.size(); i++) {
            TitleData.Title t = chain.get(i);
            out.add(Component.literal(d.tiers.get(t.tier).name + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(t.name).withStyle(Style.EMPTY.withColor(lighten(t.rgb)))));
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
        if (button == 0) for (Slot sl : slots) if (sl.hit(mx, my)) { setMode(sl.key); return true; }
        if (!MapTextures.ready()) return false;
        if (button == 0 && ovwW > 0 && mx >= ovwX && mx < ovwX + ovwW && my >= ovwY && my < ovwY + ovwH) {
            dragOverview = true;
            overviewTo(mx, my);
            return true;
        }
        if (overUi((int) mx, (int) my)) return false;
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
        if (button == 0 || button == 1 || button == 2) {
            camX += dx;
            camY += dy;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragOverview = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (panel.scrolled(mx, my, delta)) return true;
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
