package dev.worldforge.troops.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.troops.BuildGhost;
import dev.worldforge.troops.CameraRig;
import dev.worldforge.troops.Formations;
import dev.worldforge.troops.Projection;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.UnitSelection;
import dev.worldforge.troops.WorkArea;
import dev.worldforge.troops.mc.OrderPacket;
import dev.worldforge.troops.mc.TroopEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Экран командного режима: прозрачный, не ставит игру на паузу. Выбор мышкой (клик, рамка, Shift, двойной клик, группы 1-9),
 * приказы (ПКМ), камера (колесо - высота, средняя кнопка - вращение, Tab - вид, Space - к выбранным), меню стройки (B) с призраком здания.
 * Все клавиши забирает экран, поэтому «F» не меняет руки, а цифры не листают панель предметов.
 */
public final class CommandScreen extends Screen {
    public static final String BUILD_VIEW = "wf_troops:builds";
    private static final String[][] CATS = {{"housing", "Жильё"}, {"food", "Еда"}, {"industry", "Производство"}, {"storage", "Склад"}, {"military", "Армия"},
            {"trade", "Торговля"}, {"civic", "Общество"}, {"schem", "Схемы"}, {"other", "Прочее"}};
    private static final Map<String, String> RES = Map.of("wood", "дерево", "stone", "камень", "food", "еда", "iron", "железо", "gold", "золото", "planks", "доски", "bread", "хлеб", "tools", "инструменты");
    private static final int BTN_W = 70, BTN_H = 16, PANEL_W = 184, ROW = 12;

    private boolean embeddedInMaqan;
    private int cachedSkinWidth = -1, cachedSkinHeight = -1;
    private final Map<String, int[]> cachedSkinRects = new HashMap<>();
    private boolean dragging, midDrag, zoomDragging;
    private double dragX, dragY, mx, my;
    // стройка
    private static volatile Settlement incomingSettlement;
    private Settlement settlement = incomingSettlement;
    private boolean settlementOpen = true;
    private boolean buildOpen;
    // Профессиональные команды скрыты до явного открытия компактной палитры.
    private boolean professionalToolsOpen;
    private boolean workAreaDrag;
    private int workMode = -1;
    private int workOverlay = 2;
    private final List<BuildGhost.Def> defs = new ArrayList<>();
    private record ConstructionGhost(int x, int y, int z, String block) {}
    private final List<ConstructionGhost> constructionGhosts = new ArrayList<>();
    private final Map<String, Integer> stock = new HashMap<>();
    private final List<BuildGhost.Rect> taken = new ArrayList<>();
    private int colX, colZ, scroll, refreshIn;
    private String colName = "", buildError = "", cat = "";
    private BuildGhost.Def picked;
    private int[] ghostAt;
    private String ghostMsg = "";

    public CommandScreen() {
        super(Component.literal("Командный режим"));
        registerMaqanWorkspaceProvider();
    }

    /** Publish RTS selection metadata; entity state and orders remain in wf-troops/wf-settlements. */
    private void registerMaqanWorkspaceProvider() {
        dev.worldforge.mod.client.PlatformWorkspaceBridge.registerInspector("wf_troops", entityId -> {
            List<net.minecraft.world.entity.LivingEntity> selected = selectedEntities(entityId);
            if (selected.isEmpty()) return java.util.Collections.singletonList(
                    new String[]{"RTS", "Status", "Selected units are no longer loaded"});
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Force", "Selected entities", Integer.toString(selected.size())});
            double x = 0, z = 0;
            int troops = 0, workers = 0, morale = 0, moraleCount = 0, ordered = 0;
            double orderX = 0, orderZ = 0;
            Map<String, Integer> composition = new TreeMap<>();
            for (net.minecraft.world.entity.LivingEntity entity : selected) {
                x += entity.getX();
                z += entity.getZ();
                if (entity instanceof TroopEntity troop) {
                    troops++;
                    composition.merge(troop.def() != null ? troop.def().name : troop.unitId(), 1, Integer::sum);
                    morale += troop.moralePct();
                    moraleCount++;
                    if (troop.hasDest) {
                        ordered++;
                        orderX += troop.destX;
                        orderZ += troop.destZ;
                    }
                } else if (CommandMode.isWorkerEntity(entity)) {
                    workers++;
                    composition.merge("Worker", 1, Integer::sum);
                } else {
                    composition.merge(entity.getType().toString(), 1, Integer::sum);
                }
            }
            rows.add(new String[]{"Force", "Soldiers", Integer.toString(troops)});
            rows.add(new String[]{"Force", "Workers", Integer.toString(workers)});
            rows.add(new String[]{"Force", "Average position", Math.round(x / selected.size()) + ", " + Math.round(z / selected.size())});
            if (moraleCount > 0)
                rows.add(new String[]{"Condition", "Average morale", Math.round((double) morale / moraleCount) + "%"});
            rows.add(new String[]{"Orders", "Formation",
                    Formations.Type.values()[Math.floorMod(CommandMode.formation, Formations.Type.values().length)].title});
            rows.add(new String[]{"Orders", "Stance",
                    Squads.Stance.values()[Math.floorMod(CommandMode.stance, Squads.Stance.values().length)].title});
            rows.add(new String[]{"Orders", "Units with destination", ordered + " / " + troops});
            if (ordered > 0)
                rows.add(new String[]{"Orders", "Average destination",
                        Math.round(orderX / ordered) + ", " + Math.round(orderZ / ordered)});
            for (var entry : composition.entrySet())
                rows.add(new String[]{"Composition", entry.getKey(), Integer.toString(entry.getValue())});
            net.minecraft.world.entity.LivingEntity lead = selected.get(0);
            rows.add(new String[]{"Lead unit", "Name", lead.getName().getString()});
            rows.add(new String[]{"Lead unit", "UUID", lead.getUUID().toString()});
            rows.add(new String[]{"Lead unit", "Health", Math.round(lead.getHealth() * 10.0) / 10.0
                    + " / " + Math.round(lead.getMaxHealth() * 10.0) / 10.0});
            if (lead instanceof TroopEntity troop) {
                rows.add(new String[]{"Lead unit", "Type", troop.def() == null ? troop.unitId() : troop.def().name});
                rows.add(new String[]{"Lead unit", "Morale", troop.moralePct() + "%"});
                if (!troop.syncedCharacterId().isBlank())
                    rows.add(new String[]{"Identity", "Character ID", troop.syncedCharacterId()});
                if (troop.armyId > 0)
                    rows.add(new String[]{"Command", "Army ID", Integer.toString(troop.armyId)});
                if (!troop.squadId().isBlank())
                    rows.add(new String[]{"Command", "Squad", troop.squadId()});
            } else if (CommandMode.isWorkerEntity(lead)) {
                rows.add(new String[]{"Lead unit", "Profession", CommandMode.workerString(lead, "professionId")});
                rows.add(new String[]{"Lead unit", "Status", CommandMode.workerString(lead, "statusLabel")});
                rows.add(new String[]{"Lead unit", "Character ID", CommandMode.workerString(lead, "characterId")});
            }
            return rows;
        });
        dev.worldforge.mod.client.PlatformWorkspaceBridge.registerEntityOpener("wf_troops", entityId -> {
            List<String> ids = selectedEntityIds(entityId);
            if (ids.isEmpty()) return;
            CommandMode.SEL.set(ids);
            focusSelectedEntities();
        });
        dev.worldforge.mod.client.PlatformWorkspaceBridge.registerAction(
                "wf_troops.focus", "Focus selected force", "wf_troops",
                entityId -> !selectedEntityIds(entityId).isEmpty(),
                entityId -> focusSelectedEntities());
    }

    private List<String> selectedEntityIds(String entityId) {
        if (minecraft.level == null || entityId == null || !entityId.startsWith("selection:")) return List.of();
        Set<String> requested = new HashSet<>(List.of(entityId.substring("selection:".length()).split(",")));
        List<String> result = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()) {
                String id = entity.getUUID().toString();
                if (requested.contains(id)) result.add(id);
            }
        }
        return result;
    }

    private List<net.minecraft.world.entity.LivingEntity> selectedEntities(String entityId) {
        List<net.minecraft.world.entity.LivingEntity> result = new ArrayList<>();
        if (minecraft.level == null) return result;
        Set<String> selected = new HashSet<>(selectedEntityIds(entityId));
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering())
            if (entity instanceof net.minecraft.world.entity.LivingEntity living
                    && living.isAlive() && selected.contains(entity.getUUID().toString())) result.add(living);
        return result;
    }

    private void publishMaqanSelection() {
        List<String> ids = CommandMode.selected();
        if (minecraft.level == null || ids.isEmpty()) {
            dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_troops");
            return;
        }
        List<net.minecraft.world.entity.LivingEntity> selected = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering())
            if (entity instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()
                    && ids.contains(entity.getUUID().toString())) selected.add(living);
        if (selected.isEmpty()) {
            dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_troops");
            return;
        }
        // Stable lead selection keeps the same real body in the inspector across entity iteration changes.
        selected.sort(java.util.Comparator.comparing(entity -> entity.getUUID().toString()));
        String stableIds = selected.stream().map(entity -> entity.getUUID().toString())
                .sorted().collect(java.util.stream.Collectors.joining(","));
        String label = selected.size() == 1 ? selected.get(0).getName().getString()
                : selected.size() + " selected units";
        dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_troops",
                "selection:" + stableIds, "RTS_FORCE", label,
                selected.size() + " entities · " + selected.stream().filter(TroopEntity.class::isInstance).count()
                        + " soldiers · " + selected.stream().filter(CommandMode::isWorkerEntity).count() + " workers",
                selected.get(0));
    }

    private void focusSelectedEntities() {
        if (minecraft.level == null) return;
        double x = 0, z = 0;
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity living
                    && living.isAlive() && CommandMode.SEL.isSelected(entity.getUUID().toString())) {
                x += entity.getX();
                z += entity.getZ();
                count++;
            }
        }
        if (count > 0) CommandMode.RIG.focusOn(x / count, z / count);
    }

    /** Hosted command overlay uses the same responsive MAQAN texture frames as Atlas and WorldForge. */
    public void setEmbeddedInMaqan(boolean embedded) {
        embeddedInMaqan = embedded;
        cachedSkinRects.clear();
        cachedSkinWidth = cachedSkinHeight = -1;
    }

    private int[] skinRect(String key) {
        if (!embeddedInMaqan) return null;
        if (cachedSkinWidth != width || cachedSkinHeight != height) {
            cachedSkinRects.clear();
            cachedSkinWidth = width;
            cachedSkinHeight = height;
        }
        if (cachedSkinRects.containsKey(key)) return cachedSkinRects.get(key);
        try {
            Class<?> renderer = Class.forName("dev.maqan.platform.client.MaqanUiPackRenderer");
            Object layout = renderer.getMethod("adaptiveLayout", int.class, int.class)
                    .invoke(null, width, height);
            if (layout == null) { cachedSkinRects.put(key, null); return null; }
            Object rect = layout.getClass().getMethod("get", String.class).invoke(layout, key);
            if (rect == null) { cachedSkinRects.put(key, null); return null; }
            Class<?> type = rect.getClass();
            int[] values = new int[]{(Integer) type.getMethod("x").invoke(rect),
                    (Integer) type.getMethod("y").invoke(rect),
                    (Integer) type.getMethod("width").invoke(rect),
                    (Integer) type.getMethod("height").invoke(rect)};
            cachedSkinRects.put(key, values);
            return values;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            cachedSkinRects.put(key, null);
            return null;
        }
    }

    private boolean compactSkin() { return embeddedInMaqan && (width < 840 || height < 560); }

    private int[] workspaceViewport() {
        int[] left = skinRect("lp"), right = skinRect("rp"), top = skinRect("top");
        int[] minimap = skinRect("mm"), console = skinRect("bot");
        int x = left == null ? 44 : left[0] + left[2] + 8;
        int y = top == null ? 62 : top[1] + top[3] + 8;
        int r = right == null ? width - 12 : right[0] - 8;
        int b = Math.min(minimap == null ? height - 120 : minimap[1],
                console == null ? height - 120 : console[1]) - 8;
        if (r <= x) r = width - 12;
        if (b <= y) b = height - 120;
        return new int[]{x, y, Math.max(1, r - x), Math.max(1, b - y)};
    }

    private boolean inWorkspaceViewport(double x, double y) {
        if (!embeddedInMaqan) return true;
        int[] r = workspaceViewport();
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    @SuppressWarnings("unchecked")
    public static void setSettlementPayload(String payload) {
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            Defs defs = new Defs();
            if (m.get("defs") instanceof List<?> raw) for (Object o : raw) if (o instanceof String id) defs.add(id);
            incomingSettlement = Settlement.fromJson((String) m.get("s"), defs);
            CommandScreen screen = hostedView(Minecraft.getInstance());
            if (screen != null) screen.setSettlement(incomingSettlement);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger("wf_troops").error("Не удалось подключить модель поселения к общей RTS-панели", ex);
        }
    }
    static CommandScreen hostedView(Minecraft minecraft) {
        if (minecraft == null || minecraft.screen == null) return null;
        if (minecraft.screen instanceof CommandScreen screen) return screen;
        try {
            Object hosted = minecraft.screen.getClass()
                    .getMethod("providerView", String.class)
                    .invoke(minecraft.screen, "worldforge.troops");
            return hosted instanceof CommandScreen screen ? screen : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public void setSettlement(Settlement model) { settlement = model; }

    @Override public boolean isPauseScreen() { return false; }

    /** MAQAN renders the live Minecraft world behind this command overlay. */
    public boolean isWorldOverlay() { return true; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void removed() {
        dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_troops");
        CommandMode.exit(Minecraft.getInstance());
    }

    double mouseX() { return mx; }
    double mouseY() { return my; }

    /** Мышь занята делом (рамка, вращение, панель) - край экрана не двигает камеру. */
    boolean busyWithMouse() { return dragging || midDrag || overPanel(mx, my); }

    // ---------- данные стройки ----------
    @SuppressWarnings("unchecked")
    void setBuilds(String json) {
        defs.clear(); stock.clear(); taken.clear(); constructionGhosts.clear();
        buildError = "";
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(json);
            if (m.get("error") != null) { buildError = String.valueOf(m.get("error")); return; }
            colName = String.valueOf(m.getOrDefault("colony", ""));
            colX = ((Number) m.getOrDefault("cx", 0)).intValue();
            colZ = ((Number) m.getOrDefault("cz", 0)).intValue();
            defs.addAll(BuildGhost.parseDefs(m.get("defs")));
            if (m.get("stock") instanceof Map<?, ?> s) for (var e : s.entrySet()) stock.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
            if (m.get("taken") instanceof List<?> l) for (Object o : l) {
                Map<String, Object> t = (Map<String, Object>) o;
                taken.add(new BuildGhost.Rect(((Number) t.get("x")).doubleValue(), ((Number) t.get("z")).doubleValue(), ((Number) t.get("sx")).intValue(), ((Number) t.get("sz")).intValue()));
            }
            if (m.get("construction") instanceof List<?> blocks) for (Object o : blocks) {
                if (!(o instanceof Map<?, ?> t)) continue;
                constructionGhosts.add(new ConstructionGhost(
                        ((Number) t.get("x")).intValue(), ((Number) t.get("y")).intValue(),
                        ((Number) t.get("z")).intValue(), (t.get("block") instanceof String block ? block : "minecraft:stone")));
            }
        } catch (RuntimeException ex) {
            buildError = "Не удалось прочитать список зданий";
        }
    }

    private void requestBuilds() {
        CommandMode.send(new OrderPacket(OrderPacket.BUILD_LIST, List.of(), 0, CommandMode.RIG.fx, CommandMode.RIG.fz, "", false, 0));
        refreshIn = 40;
    }

    private void toggleBuild() {
        buildOpen = !buildOpen;
        picked = null;
        if (buildOpen) requestBuilds();
    }
    private static final String[] WORK_LABELS = Arrays.stream(WorkArea.values()).map(area -> area.label).toArray(String[]::new);
    private void cycleWorkMode() {
        workMode = (workMode + 1) % (WORK_LABELS.length + 1);
        if (workMode == WORK_LABELS.length) workMode = -1;
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(
                workMode < 0 ? "Режим выбора участков выключен" :
                        "Выделите участок для задачи: " + WORK_LABELS[workMode] + " · Y — сменить задачу · O — видимость"), true);
    }
    private List<String> selectedWorkerIds() {
        List<String> ids = new ArrayList<>();
        Set<String> selected = new HashSet<>(CommandMode.selected());
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering())
            if (entity instanceof LivingEntity living && CommandMode.isWorkerEntity(living)
                    && selected.contains(entity.getUUID().toString())) ids.add(entity.getUUID().toString());
        return ids;
    }

    private void sendWorkArea(Vec3 a, Vec3 b, int radius) {
        List<String> workers = selectedWorkerIds();
        if (workers.isEmpty()) {
            minecraft.player.displayClientMessage(Component.literal("Для добычи и земляных работ выделите рабочих поселения"), true);
            return;
        }
        double x = (a.x + b.x) * 0.5, z = (a.z + b.z) * 0.5;
        int y = (int) Math.floor((a.y + b.y) * 0.5);
        int halfX = Math.min(64, (int) Math.ceil(Math.abs(b.x - a.x) * 0.5));
        int halfZ = Math.min(64, (int) Math.ceil(Math.abs(b.z - a.z) * 0.5));
        int extent = Math.max(halfX, halfZ);
        CommandMode.send(new OrderPacket(OrderPacket.WORK_AREA, workers, workMode,
                x, z, workOverlay + ":" + y + ":" + halfX + ":" + halfZ, false, extent));
        CommandMode.marker = new Vec3(x, y, z);
        CommandMode.markerUntil = System.currentTimeMillis() + 1800;
    }

    private int zoomSliderWidth() {
        int[] console = skinRect("bot");
        if (console == null) return ZOOM_SLIDER_W;
        return Math.max(80, Math.min(ZOOM_SLIDER_W, console[2] - 72));
    }
    private int zoomSliderX() {
        int[] console = skinRect("bot");
        int sliderWidth = zoomSliderWidth();
        return console == null ? width / 2 - sliderWidth / 2 : console[0] + (console[2] - sliderWidth) / 2;
    }
    private int zoomSliderY() {
        int[] console = skinRect("bot");
        return console == null ? height - 25 : console[1] + console[3] - 19;
    }
    private static final int ZOOM_SLIDER_W = 200;
    private void setZoomFromMouse(double x) {
        int sliderWidth = zoomSliderWidth();
        double t = Math.max(0, Math.min(1, (x - zoomSliderX()) / sliderWidth));
        CommandMode.RIG.setZoom(CameraRig.MIN_ZOOM + t * (CameraRig.MAX_ZOOM - CameraRig.MIN_ZOOM));
    }
    private boolean onZoomSlider(double x, double y) {
        int sliderWidth = zoomSliderWidth();
        return x >= zoomSliderX() - 8 && x <= zoomSliderX() + sliderWidth + 8
                && y >= zoomSliderY() - 8 && y <= zoomSliderY() + 8;
    }
    private void drawZoomSlider(GuiGraphics g) {
        int x = zoomSliderX(), y = zoomSliderY(), sliderWidth = zoomSliderWidth();
        double t = (CommandMode.RIG.zoom - CameraRig.MIN_ZOOM) / (CameraRig.MAX_ZOOM - CameraRig.MIN_ZOOM);
        int knob = x + (int) Math.round(t * sliderWidth);
        g.fill(x - 8, y - 10, x + sliderWidth + 8, y + 11, 0xB910121A);
        g.drawString(font, "КАМЕРА", x - 7, y - 8, 0xFF9CCCFF, false);
        g.fill(x, y + 3, x + sliderWidth, y + 6, 0xFF596273);
        g.fill(x, y + 3, knob, y + 6, 0xFF72C7FF);
        g.fill(knob - 4, y, knob + 5, y + 10, 0xFFFFFFFF);
        String value = String.format(java.util.Locale.ROOT, "%.0f бл.", CommandMode.RIG.zoom);
        g.drawString(font, value, x + sliderWidth + 12, y - 8, 0xFFE6E8EE, false);
    }

    // ---------- раскладка ----------
    private record Btn(String label, int x, int y, int w, boolean on, Runnable run) {
        boolean hit(double px, double py) { return px >= x && px < x + w && py >= y && py < y + BTN_H; }
    }

    private static final String[] WORK_ICONS = {"♣", "◆", "⛏", "⌁", "▦", "✿", "✧", "✚"};

    private int[] professionalPanelBounds() {
        int[] console = skinRect("bot");
        int x = console == null ? width - 148 : console[0] + console[2] - 148;
        int y = console == null ? height - 64 : console[1] + 30;
        if (x + 140 > width - 4) x = Math.max(4, width - 144);
        if (y + 46 > height - 4) y = Math.max(4, height - 50);
        return new int[]{x, y, 140, 46};
    }

    private boolean inProfessionalPanel(double px, double py) {
        if (!professionalToolsOpen) return false;
        int[] r = professionalPanelBounds();
        return px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3];
    }

    private void activateWorkArea(int kind) {
        workMode = kind;
        professionalToolsOpen = false;
        if (minecraft.player != null)
            minecraft.player.displayClientMessage(Component.literal(
                    "Задача: " + WORK_LABELS[kind] + " · выделите точку или участок"), true);
    }

    private void drawProfessionalPanel(GuiGraphics g) {
        int[] r = professionalPanelBounds();
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xEE101820);
        g.fill(r[0], r[1], r[0] + r[2], r[1] + 1, 0xFF8A7040);
        g.drawString(font, "ПРОФЕССИОНАЛЬНЫЕ ЗАДАЧИ", r[0] + 5, r[1] + 4, 0xFFE6C875, false);
    }

    private List<Btn> buttons() {
        List<Btn> l = new ArrayList<>();
        int[] console = skinRect("bot");
        if (console != null) {
            int y = console[1] + 10;
            if (compactSkin()) {
                int w = Math.max(30, Math.min(60, (console[2] - 28) / 3));
                int x = console[0] + 10;
                l.add(new Btn("1-е лицо", x, y, w, false, CommandMode::exitFromMaqan));
                x += w + 4;
                l.add(new Btn("Стройка", x, y, w, buildOpen, this::toggleBuild));
                x += w + 4;
                l.add(new Btn("Участки", x, y, w, workMode >= 0, this::cycleWorkMode));
                if (!selectedWorkerIds().isEmpty())
                    l.add(new Btn("⚒", x + w + 4, y, 24, professionalToolsOpen, () -> professionalToolsOpen = !professionalToolsOpen));
                if (professionalToolsOpen) addProfessionalTaskButtons(l);
                return l;
            }
            int x = console[0] + 12;
            l.add(new Btn("1-е лицо", x, y, BTN_W, false, CommandMode::exitFromMaqan)); x += BTN_W + 4;
            l.add(new Btn("Стройка (B)", x, y, BTN_W, buildOpen, this::toggleBuild)); x += BTN_W + 4;
            l.add(new Btn("Участки (Y)", x, y, BTN_W, workMode >= 0, this::cycleWorkMode)); x += BTN_W + 4;
            l.add(new Btn("Стратегия", x, y, BTN_W, CommandMode.RIG.view == CameraRig.View.SKY,
                    () -> CommandMode.setCameraView(CameraRig.View.SKY))); x += BTN_W + 4;
            l.add(new Btn("Изометрия", x, y, BTN_W, CommandMode.RIG.view == CameraRig.View.TACTICS,
                    () -> CommandMode.setCameraView(CameraRig.View.TACTICS))); x += BTN_W + 4;
            l.add(new Btn("Свободная", x, y, BTN_W, CommandMode.RIG.view == CameraRig.View.FREE,
                    () -> CommandMode.setCameraView(CameraRig.View.FREE)));
            if (!selectedWorkerIds().isEmpty())
                l.add(new Btn("⚒", console[0] + console[2] - 34, y, 24, professionalToolsOpen,
                        () -> professionalToolsOpen = !professionalToolsOpen));
            if (professionalToolsOpen) addProfessionalTaskButtons(l);
            return l;
        }
        int x = width - 4 - BTN_W;
        l.add(new Btn("Выход (R)", x, 4, BTN_W, false, () -> CommandMode.exit(minecraft))); x -= BTN_W + 4;
        l.add(new Btn("Стройка (B)", x, 4, BTN_W, buildOpen, this::toggleBuild)); x -= BTN_W + 4;
        l.add(new Btn("Участки (Y)", x, 4, BTN_W, workMode >= 0, this::cycleWorkMode)); x -= BTN_W + 4;
        l.add(new Btn("Стратегия", x, 4, BTN_W, CommandMode.RIG.view == CameraRig.View.SKY,
                () -> CommandMode.setCameraView(CameraRig.View.SKY))); x -= BTN_W + 4;
        l.add(new Btn("Изометрия", x, 4, BTN_W, CommandMode.RIG.view == CameraRig.View.TACTICS,
                () -> CommandMode.setCameraView(CameraRig.View.TACTICS))); x -= BTN_W + 4;
        l.add(new Btn("Свободная", x, 4, BTN_W, CommandMode.RIG.view == CameraRig.View.FREE,
                () -> CommandMode.setCameraView(CameraRig.View.FREE)));
        if (!selectedWorkerIds().isEmpty())
            l.add(new Btn("⚒", x - 28, 4, 24, professionalToolsOpen, () -> professionalToolsOpen = !professionalToolsOpen));
        if (professionalToolsOpen) addProfessionalTaskButtons(l);
        return l;
    }

    private void addProfessionalTaskButtons(List<Btn> out) {
        int[] r = professionalPanelBounds();
        for (int i = 0; i < WorkArea.values().length; i++) {
            final int kind = i;
            int col = i % 4, row = i / 4;
            out.add(new Btn(WORK_ICONS[i], r[0] + 6 + col * 32, r[1] + 17 + row * 18,
                    28, workMode == i, () -> activateWorkArea(kind)));
        }
    }

    private int panelX() { int[] r = skinRect("rp"); return r == null ? (compactSkin() ? width - panelW() - 12 : width - PANEL_W - 4) : r[0] + 12; }
    private int panelY() { int[] r = skinRect("rp"); if (r != null) return r[1] + 34; int[] top = skinRect("top"); return compactSkin() && top != null ? top[1] + top[3] + 8 : 26; }
    private int panelW() { int[] r = skinRect("rp"); return r == null ? (compactSkin() ? Math.max(112, Math.min(212, width / 2 - 24)) : PANEL_W) : Math.max(120, r[2] - 24); }
    private int panelH() { int[] r = skinRect("rp"); if (r != null) return Math.max(80, r[3] - 48); int[] console = skinRect("bot"); return compactSkin() && console != null ? Math.max(80, console[1] - panelY() - 8) : Math.max(80, height - 26 - 90); }

    private boolean inBuildPanel(double px, double py) {
        return buildOpen && px >= panelX() && px < panelX() + panelW()
                && py >= panelY() && py < panelY() + panelH();
    }

    private int selectionPanelX() { int[] r = skinRect("lp"); return r == null ? (compactSkin() ? 52 : 4) : r[0] + 12; }
    private int selectionPanelW() { int[] r = skinRect("lp"); return r == null ? (compactSkin() ? Math.max(96, Math.min(180, width / 2 - 68)) : 184) : Math.max(120, r[2] - 24); }
    private int selectionPanelY() { int[] r = skinRect("lp"); if (r != null) return r[1] + r[3] - 86; int[] console = skinRect("bot"); return compactSkin() && console != null ? console[1] - 86 : height - 90; }
    private boolean inSelPanel(double px, double py) {
        return px >= selectionPanelX() && px < selectionPanelX() + selectionPanelW()
                && py >= selectionPanelY() && py < selectionPanelY() + 82;
    }

    boolean overPanel(double px, double py) {
        if (embeddedInMaqan && !inWorkspaceViewport(px, py)) return true;
        for (Btn button : buttons()) if (button.hit(px, py)) return true;
        if (onZoomSlider(px, py)) return true;
        return inBuildPanel(px, py) || inSelPanel(px, py) || inProfessionalPanel(px, py);
    }

    private List<String> categories() {
        List<String> out = new ArrayList<>();
        for (String[] c : CATS) for (BuildGhost.Def d : defs) if (d.category().equals(c[0])) { out.add(c[0]); break; }
        return out;
    }

    private static String catName(String id) { for (String[] c : CATS) if (c[0].equals(id)) return c[1]; return id; }

    private List<BuildGhost.Def> visibleDefs() {
        List<BuildGhost.Def> l = new ArrayList<>();
        for (BuildGhost.Def d : defs) if (cat.isEmpty() || d.category().equals(cat)) l.add(d);
        return l;
    }

    private int rowsFit() { return Math.max(3, (panelH() - 62) / ROW); }

    // ---------- инспектор выбранных персонажей ----------
    private static final int INSPECT_ROW=19;
    private int inspectX() { int[] r = skinRect("lp"); return r == null ? (compactSkin() ? 52 : 4) : r[0] + 12; }
    private int inspectW() { int[] r = skinRect("lp"); return r == null ? (compactSkin() ? Math.max(96, width / 2 - 68) : 250) : Math.max(130, r[2] - 24); }
    private int inspectY() {
        int[] r = skinRect("lp");
        if (r != null) return r[1] + 34;
        int[] top = skinRect("top");
        return compactSkin() && top != null ? top[1] + top[3] + 8 : (settlementOpen ? 148 : 34);
    }

    private boolean inspectAt(double x,double y) {
        if(x<inspectX()||x>=inspectX()+inspectW()||y<inspectY()+22)return false;
        int row=(int)((y-(inspectY()+22))/INSPECT_ROW);
        List<String> ids=CommandMode.selected();
        int maxRows = compactSkin() ? 3 : 8;
        if(row<0||row>=Math.min(maxRows,ids.size()))return false;
        if(row>=ids.size())return y<inspectY()+22+maxRows*INSPECT_ROW;
        String id=ids.get(row);
        for(net.minecraft.world.entity.Entity entity:minecraft.level.entitiesForRendering()){
            if(!entity.isAlive()||!entity.getUUID().toString().equals(id))continue;
            CommandMode.SEL.set(List.of(id));
            CommandMode.RIG.focusOn(entity.getX(),entity.getZ());
            return true;
        }
        return true;
    }

    private void drawSettlementMinimap(GuiGraphics g) {
        int[] frame = skinRect("mm");
        if (frame == null) return;
        int x = frame[0] + 12, y = frame[1] + 14;
        int w = Math.max(1, frame[2] - 24), h = Math.max(1, frame[3] - 28);
        g.fill(x, y, x + w, y + h, 0xFF172019);
        g.renderOutline(x, y, w, h, 0xFF080B0A);
        if (settlement == null) {
            g.drawCenteredString(font, "ПОСЕЛЕНИЕ", x + w / 2, y + h / 2 - 4, 0xFF9EA98D);
            return;
        }

        double minX = -24, maxX = 24, minZ = -24, maxZ = 24;
        for (var building : settlement.buildings) {
            if (building == null || building.def == null) continue;
            double hx = Math.max(1, building.def.sizeX) * 0.5;
            double hz = Math.max(1, building.def.sizeZ) * 0.5;
            minX = Math.min(minX, building.x - hx); maxX = Math.max(maxX, building.x + hx);
            minZ = Math.min(minZ, building.z - hz); maxZ = Math.max(maxZ, building.z + hz);
        }
        for (var worker : settlement.workers) {
            if (worker == null) continue;
            minX = Math.min(minX, worker.x); maxX = Math.max(maxX, worker.x);
            minZ = Math.min(minZ, worker.z); maxZ = Math.max(maxZ, worker.z);
        }
        minX -= 6; maxX += 6; minZ -= 6; maxZ += 6;
        double spanX = Math.max(48, maxX - minX), spanZ = Math.max(48, maxZ - minZ);
        double scale = Math.min(w / spanX, h / spanZ);
        double drawW = spanX * scale, drawH = spanZ * scale;
        double ox = x + (w - drawW) * 0.5, oy = y + (h - drawH) * 0.5;
        double baseMinX = minX, baseMinZ = minZ;
        java.util.function.DoubleUnaryOperator sx = value -> ox + (value - baseMinX) * scale;
        java.util.function.DoubleUnaryOperator sy = value -> oy + (value - baseMinZ) * scale;

        g.enableScissor(x, y, x + w, y + h);
        for (var building : settlement.buildings) {
            if (building == null || building.def == null) continue;
            int bw = Math.max(3, (int) Math.round(building.def.sizeX * scale));
            int bh = Math.max(3, (int) Math.round(building.def.sizeZ * scale));
            int cx = (int) Math.round(sx.applyAsDouble(building.x));
            int cz = (int) Math.round(sy.applyAsDouble(building.z));
            int color = switch (building.state) {
                case DONE -> 0xFF71945E;
                case BUILDING -> 0xFFC49A54;
                case PLANNED -> 0xFF687383;
            };
            g.fill(cx - bw / 2 - 1, cz - bh / 2 - 1, cx + bw / 2 + 2, cz + bh / 2 + 2, 0xFF080B0A);
            g.fill(cx - bw / 2, cz - bh / 2, cx + bw / 2 + 1, cz + bh / 2 + 1, color);
        }
        for (var worker : settlement.workers) {
            if (worker == null) continue;
            int px = (int) Math.round(sx.applyAsDouble(worker.x));
            int pz = (int) Math.round(sy.applyAsDouble(worker.z));
            int color = "guard".equals(worker.profession) ? 0xFFE06A5C
                    : "builder".equals(worker.profession) ? 0xFFE5B65E : 0xFFB6D4A0;
            g.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFF080B0A);
            g.fill(px - 1, pz - 1, px + 2, pz + 2, color);
        }
        if (minecraft.player != null) {
            double localX = minecraft.player.getX() - colX;
            double localZ = minecraft.player.getZ() - colZ;
            if (localX >= minX && localX <= maxX && localZ >= minZ && localZ <= maxZ) {
                int px = (int) Math.round(sx.applyAsDouble(localX));
                int pz = (int) Math.round(sy.applyAsDouble(localZ));
                g.fill(px - 3, pz - 1, px + 4, pz + 2, 0xFFFFFFFF);
                g.fill(px, pz - 4, px + 2, pz + 5, 0xFFFFFFFF);
            }
            double focusX = CommandMode.RIG.fx - colX;
            double focusZ = CommandMode.RIG.fz - colZ;
            if (focusX >= minX && focusX <= maxX && focusZ >= minZ && focusZ <= maxZ) {
                int fx = (int) Math.round(sx.applyAsDouble(focusX));
                int fz = (int) Math.round(sy.applyAsDouble(focusZ));
                int radius = Math.max(4, (int) Math.round(CommandMode.RIG.zoom * scale * 0.65));
                g.renderOutline(fx - radius, fz - radius, radius * 2, radius * 2, 0xFF69D6FF);
                g.fill(fx - 2, fz - 2, fx + 3, fz + 3, 0xFF69D6FF);
            }
        }
        g.disableScissor();
    }

    private void drawSettlementPanel(GuiGraphics g) {
        if (settlement == null || buildOpen) return;
        int[] frame = skinRect("rp");
        int[] top = skinRect("top");
        int w = frame == null ? (compactSkin() ? Math.max(112, Math.min(144, width / 2 - 24)) : 250) : Math.max(120, frame[2] - 24);
        int x = frame == null ? (compactSkin() ? width - w - 12 : 4) : frame[0] + 12;
        int y = frame == null ? (compactSkin() && top != null ? top[1] + top[3] + 8 : 34) : frame[1] + 34;
        int h = frame == null ? 106 : Math.min(150, Math.max(88, frame[3] / 3));
        g.fill(x, y, x + w, y + h, 0xD910121A);
        g.fill(x, y, x + w, y + 1, 0xFF8A7040);
        g.drawString(font, "ПОСЕЛЕНИЕ · " + settlement.name, x + 6, y + 5, 0xFFE6C875, false);
        String pop = "Жители " + settlement.workers.size() + " / жильё " + settlement.housing()
                + " · настроение " + settlement.approval() + " · здания " + settlement.buildings.size();
        g.drawString(font, font.plainSubstrByWidth(pop, w - 12), x + 6, y + 17, 0xFFD7DCE8, false);
        int rx = x + 6, ry = y + 31, col = 0;
        for (var entry : settlement.defs.resourceNames.entrySet()) {
            String text = entry.getValue() + " " + (int) settlement.amount(entry.getKey());
            int tw = font.width(text) + 12;
            if (rx + tw > x + w - 4) { rx = x + 6; ry += 14; col++; }
            if (ry > y + h - 12) break;
            g.drawString(font, text, rx, ry, 0xFFB8D8A0, false);
            rx += tw;
        }
        g.drawString(font, "Рабочие, армия, стройка и приказы — в этом командном пространстве", x + 6, y + h - 11, 0xFF9EA4B5, false);
    }

    private void drawUnitInspector(GuiGraphics g) {
        List<String> ids=CommandMode.selected();
        if(ids.isEmpty())return;
        int ix = inspectX(), iw = inspectW(), iy = inspectY();
        int rows=Math.min(compactSkin() ? 3 : 8,ids.size());
        int h=24+rows*INSPECT_ROW;
        g.fill(ix,iy,ix+iw,iy+h,0xD910121A);
        g.fill(ix,iy,ix+iw,iy+1,0xFF8A7040);
        g.drawString(font,"ИНСПЕКТОР · "+ids.size()+" ВЫБРАНО",ix+6,iy+6,0xFFE6C875,false);
        int row=0;
        for(String id:ids){
            if(row>=(compactSkin() ? 3 : 8))break;
            net.minecraft.world.entity.Entity entity=null;
            for(net.minecraft.world.entity.Entity candidate:minecraft.level.entitiesForRendering())
                if(candidate.getUUID().toString().equals(id)){entity=candidate;break;}
            int y=iy+22+row*INSPECT_ROW;
            if(entity==null){g.drawString(font,"Недоступен · "+id.substring(0,Math.min(8,id.length())),ix+6,y+4,0xFF888888,false);row++;continue;}
            boolean troop=entity instanceof TroopEntity;
            String name=entity.getDisplayName().getString();
            String role,identity="";
            int morale=-1,xp=-1;
            String state="";
            if(entity instanceof TroopEntity t){
                role=t.def()==null?t.unitId():t.def().role();
                morale=t.moralePct();
                identity=t.syncedCharacterId()==null?"":t.syncedCharacterId();
                xp=t.syncedCombatXp();
                state=t.squadId()==null||t.squadId().isBlank()?"":"Отряд "+t.squadId();
            }else if(entity instanceof LivingEntity worker&&CommandMode.isWorkerEntity(worker)){
                role=CommandMode.workerString(worker,"professionId");
                if(role.isBlank())role="Рабочий";
                identity=CommandMode.workerString(worker,"characterId");
                state=CommandMode.workerString(worker,"statusLabel");
                int assigned=CommandMode.workerInt(worker,"assignedBuildingId");
                if(assigned>0)state+=" · здание #"+assigned;
            }else role="Юнит";
            g.fill(ix+3,y,ix+iw-3,y+INSPECT_ROW-1,
                    (row&1)==0?0x55333344:0x33222230);
            int health=entity instanceof LivingEntity living&&living.getMaxHealth()>0
                    ?Math.round(100*living.getHealth()/living.getMaxHealth()):0;
            String detail=role+" · HP "+health+"%"+(morale>=0?" · Мораль "+morale+"%":"")+(xp>=0?" · XP "+xp:"")+(state.isBlank()?"":" · "+state);
            int nameWidth=identity.isBlank()?iw-14:iw-88;
            g.drawString(font,font.plainSubstrByWidth(name,nameWidth),ix+7,y+2,
                    troop?0xFFE6C875:0xFFB8D8A0,false);
            g.drawString(font,font.plainSubstrByWidth(detail,iw-14),ix+7,y+11,0xFFB5B7C2,false);
            if(!identity.isBlank()){
                String shortId=identity.length()>16?identity.substring(0,16)+"…":identity;
                g.drawString(font,shortId,ix+iw-7-font.width(shortId),y+2,0xFF777F91,false);
            }
            row++;
        }
        if(ids.size()>(compactSkin()?3:8))g.drawString(font,"Ещё "+(ids.size()-(compactSkin()?3:8))+" · выберите строку для фокуса",
                ix+6,iy+h-10,0xFF9EA4B5,false);
    }

    // ---------- ввод ----------
    @Override
    public boolean mouseClicked(double x, double y, int button) {
        mx = x; my = y;
        boolean shift = hasShiftDown(), ctrl = hasControlDown();
        if(button==0&&onZoomSlider(x,y)){zoomDragging=true;setZoomFromMouse(x);return true;}
        if(button==0&&inspectAt(x,y))return true;
        if (button == 0) {
            for (Btn b : buttons()) if (b.hit(x, y)) { b.run().run(); return true; }
            if (buildOpen && inBuildPanel(x, y)) { panelClick(x, y); return true; }
            if (inProfessionalPanel(x, y)) return true;
            if (inSelPanel(x, y)) return true;
            if (embeddedInMaqan && !inWorkspaceViewport(x, y)) return true;
            if (picked != null) { place(shift); return true; }
            if (workMode >= 0) { workAreaDrag = true; dragging = true; dragX = x; dragY = y; return true; }
            dragging = true; dragX = x; dragY = y;
            return true;
        }
        if (button == 2) {
            if (embeddedInMaqan && !inWorkspaceViewport(x, y)) return true;
            midDrag = true;
            return true;
        }
        if (button == 1) {
            if (picked != null) { picked = null; return true; }
            if (overPanel(x, y)) return true;
            LivingEntity resident = CommandMode.residentAt(minecraft, x, y);
            if (resident != null && minecraft.player != null) {
                minecraft.player.connection.sendCommand("wfresident open " + resident.getUUID());
                return true;
            }
            if (CommandMode.SEL.selected().isEmpty()) return true;
            LivingEntity foe = CommandMode.enemyAt(minecraft, x, y);
            Vec3 g = CommandMode.groundAt(minecraft, x, y);
            if (foe != null) {
                CommandMode.send(new OrderPacket(OrderPacket.ORDER, CommandMode.selected(), Squads.Kind.ATTACK.ordinal(), 0, 0, foe.getUUID().toString(), shift, 0));
            } else if (g != null) {
                // The single ORDER packet is routed by the server to workers and troops independently.
                Squads.Kind k = ctrl ? Squads.Kind.ATTACK_MOVE : Squads.Kind.MOVE;
                CommandMode.send(new OrderPacket(OrderPacket.ORDER, CommandMode.selected(), k.ordinal(), g.x, g.z, "", shift, 0));
                CommandMode.marker = g;
                CommandMode.markerUntil = System.currentTimeMillis() + 1500;
            }
            return true;
        }
        return true;
    }

    private void panelClick(double x, double y) {
        int px = panelX() + 6, cy = panelY() + 28;
        List<String> cats = categories();
        int cx = px;
        int row = 0;
        // чипы разделов: «Все» и найденные
        List<String> chips = new ArrayList<>();
        chips.add("");
        chips.addAll(cats);
        for (String c : chips) {
            String label = c.isEmpty() ? "Все" : catName(c);
            int w = font.width(label) + 8;
            if (cx + w > panelX() + panelW() - 4) { cx = px; row++; }
            int yy = cy + row * 12;
            if (x >= cx && x < cx + w && y >= yy && y < yy + 11) { cat = c; scroll = 0; picked = null; return; }
            cx += w + 3;
        }
        int listY = cy + (row + 1) * 12 + 4;
        List<BuildGhost.Def> vis = visibleDefs();
        int i = (int) ((y - listY) / ROW) + scroll;
        if (y >= listY && i >= 0 && i < vis.size() && (y - listY) / ROW < rowsFit()) picked = vis.get(i);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        mx = x; my = y;
        if (button == 2) midDrag = false;
        if (button == 0) zoomDragging = false;
        if (button == 0 && dragging) {
            dragging = false;
            if (embeddedInMaqan && !inWorkspaceViewport(x, y)) {
                workAreaDrag = false;
                return true;
            }
            if (workAreaDrag) {
                workAreaDrag = false;
                boolean areaSelection = UnitSelection.isBox(dragX, dragY, x, y);
                if (!areaSelection) {
                    net.minecraft.core.BlockPos block = CommandMode.blockAt(minecraft, x, y);
                    if (block != null) {
                        Vec3 exact = new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
                        sendWorkArea(exact, exact, 0);
                        return true;
                    }
                }
                Vec3 a = CommandMode.groundAt(minecraft, dragX, dragY);
                Vec3 b = CommandMode.groundAt(minecraft, x, y);
                if (a != null && b != null) {
                    int radius = areaSelection
                            ? Math.min(64, (int) Math.ceil(Math.hypot(b.x - a.x, b.z - a.z) * 0.5)) : 0;
                    sendWorkArea(a, b, radius);
                }
                return true;
            }
            List<UnitSelection.Screen> all = CommandMode.screenList(minecraft);
            boolean shift = hasShiftDown();
            if (UnitSelection.isBox(dragX, dragY, x, y)) CommandMode.SEL.box(all, dragX, dragY, x, y, shift);
            else CommandMode.SEL.click(all, x, y, shift, System.currentTimeMillis());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        mx = x; my = y;
        if (button == 2 && (!embeddedInMaqan || inWorkspaceViewport(x, y)))
            CommandMode.RIG.rotate(dx * 0.35, dy * 0.25);
        if (button == 0 && zoomDragging) setZoomFromMouse(x);
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) { mx = x; my = y; }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (embeddedInMaqan && !inWorkspaceViewport(x, y) && !inBuildPanel(x, y)) return false;
        if (buildOpen && inBuildPanel(x, y)) {
            int max = Math.max(0, visibleDefs().size() - rowsFit());
            scroll = Math.max(0, Math.min(max, scroll + (delta < 0 ? 1 : -1)));
        } else CommandMode.RIG.zoomBy(delta);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (picked != null) picked = null;
            else if (buildOpen) buildOpen = false;
            else CommandMode.exit(minecraft);
            return true;
        }
        if (CommandMode.TOGGLE.matches(key, scan)) { CommandMode.exit(minecraft); return true; }
        boolean ctrl = hasControlDown();
        if (key == GLFW.GLFW_KEY_TAB) { CommandMode.cycleCameraView(); return true; }
        if (key == GLFW.GLFW_KEY_B) { toggleBuild(); return true; }
        if (key == GLFW.GLFW_KEY_H) {
            settlementOpen = !settlementOpen;
            if (settlement == null && minecraft.player != null) minecraft.player.connection.sendCommand("wfsettle view hud");
            return true;
        }
        if (key == GLFW.GLFW_KEY_Y) { cycleWorkMode(); return true; }
        if (key == GLFW.GLFW_KEY_O) {
            workOverlay = (workOverlay + 1) % 3;
            if (!CommandMode.SEL.selected().isEmpty()) {
                CommandMode.send(new OrderPacket(OrderPacket.WORK_AREA, selectedWorkerIds(), 0,
                        0, 0, workOverlay + ":0", false, -1));
            }
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(
                    "Видимость рабочих зон: " + (workOverlay == 0 ? "выключена" : workOverlay == 1 ? "слабая" : "яркая")), true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) { CommandMode.RIG.focusOn(minecraft.player.getX(), minecraft.player.getZ()); return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { focusSelection(); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_A) {
            selectForce(hasShiftDown());
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            int n = key - GLFW.GLFW_KEY_0;
            if (ctrl) {
                CommandMode.SEL.saveGroup(n);
                minecraft.player.displayClientMessage(Component.literal("Группа " + n + ": " + CommandMode.SEL.selected().size()), true);
            } else {
                Set<String> alive = new HashSet<>();
                for (net.minecraft.world.entity.Entity en : minecraft.level.entitiesForRendering()) if (en.isAlive()) alive.add(en.getUUID().toString());
                if (CommandMode.SEL.recallGroup(n, alive)) focusSelection();
            }
            return true;
        }
        if (CommandMode.SEL.selected().isEmpty()) return true;
        List<String> sel = CommandMode.selected();
        switch (key) {
            case GLFW.GLFW_KEY_Z ->
                    CommandMode.send(new OrderPacket(OrderPacket.ORDER, sel, Squads.Kind.STOP.ordinal(), 0, 0, "", false, 0));
            case GLFW.GLFW_KEY_X ->
                    CommandMode.send(new OrderPacket(OrderPacket.ORDER, sel, Squads.Kind.HOLD.ordinal(), 0, 0, "", false, 0));
            case GLFW.GLFW_KEY_C -> {
                CommandMode.formation = (CommandMode.formation + 1) % Formations.Type.values().length;
                CommandMode.send(new OrderPacket(OrderPacket.FORMATION, sel, 0, 0, 0, "", false, CommandMode.formation));
            }
            case GLFW.GLFW_KEY_M -> {
                CommandMode.stance = (CommandMode.stance + 1) % Squads.Stance.values().length;
                CommandMode.send(new OrderPacket(OrderPacket.STANCE, sel, 0, 0, 0, "", false, CommandMode.stance));
            }
            case GLFW.GLFW_KEY_F -> CommandMode.send(new OrderPacket(OrderPacket.GROUP, sel, 0, 0, 0, "", false, 0));
            case GLFW.GLFW_KEY_V -> CommandMode.send(new OrderPacket(OrderPacket.APPOINT,sel,0,0,0,"",false,ctrl?-1:1));
            case GLFW.GLFW_KEY_P -> {
                if(!overPanel(mx,my)){
                    Vec3 patrol=CommandMode.groundAt(minecraft,mx,my);
                    if(patrol!=null){
                        CommandMode.send(new OrderPacket(OrderPacket.ORDER,sel,Squads.Kind.PATROL.ordinal(),
                                patrol.x,patrol.z,"",hasShiftDown(),0));
                        CommandMode.marker=patrol;
                        CommandMode.markerUntil=System.currentTimeMillis()+1500;
                    }
                }
            }
            case GLFW.GLFW_KEY_DELETE -> CommandMode.send(new OrderPacket(OrderPacket.RELEASE, sel, 0, 0, 0, "", false, 0));
            default -> {}
        }
        return true;
    }

    @Override public boolean keyReleased(int key, int scan, int mods) { return true; }
    @Override public boolean charTyped(char c, int mods) { return true; }

    /** Ctrl+A selects every controllable body; Ctrl+Shift+A selects the selected army or settlement population. */
    private void selectForce(boolean currentGroupOnly) {
        if (minecraft.level == null) return;
        net.minecraft.world.entity.Entity anchor = null;
        if (currentGroupOnly) {
            for (String selected : CommandMode.selected()) {
                for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                    if (entity.getUUID().toString().equals(selected)) { anchor = entity; break; }
                }
                if (anchor != null) break;
            }
        }
        List<String> ids = new ArrayList<>();
        int armyId = anchor instanceof TroopEntity troop ? troop.armyId : 0;
        String squadId = anchor instanceof TroopEntity troop ? troop.squadId() : "";
        String colonyId = anchor instanceof LivingEntity living && CommandMode.isWorkerEntity(living)
                ? CommandMode.workerString(living, "syncedColonyId") : "";
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || !CommandMode.selectable(minecraft, living)) continue;
            if (currentGroupOnly && anchor instanceof TroopEntity) {
                if (!(living instanceof TroopEntity troop)) continue;
                if (armyId > 0 ? troop.armyId != armyId : !squadId.equals(troop.squadId())) continue;
            } else if (currentGroupOnly && anchor instanceof LivingEntity selectedWorker
                    && CommandMode.isWorkerEntity(selectedWorker)) {
                if (!CommandMode.isWorkerEntity(living)
                        || !colonyId.equals(CommandMode.workerString(living, "syncedColonyId"))) continue;
            }
            ids.add(entity.getUUID().toString());
            if (ids.size() >= 200) break;
        }
        CommandMode.SEL.set(ids);
        focusSelection();
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(
                currentGroupOnly ? "Выделена армия/поселение: " + ids.size() : "Все подконтрольные: " + ids.size()), true);
    }

    private void focusSelection() {
        double sx = 0, sz = 0;
        int n = 0;
        for (net.minecraft.world.entity.Entity en : minecraft.level.entitiesForRendering()) {
            if (en instanceof LivingEntity le && CommandMode.SEL.isSelected(le.getUUID().toString())) { sx += le.getX(); sz += le.getZ(); n++; }
        }
        if (n > 0) CommandMode.RIG.focusOn(sx / n, sz / n);
    }

    private void place(boolean keep) {
        if (ghostAt == null) return;
        if (!ghostMsg.isEmpty()) { minecraft.player.displayClientMessage(Component.literal("Здесь нельзя: " + ghostMsg), true); return; }
        CommandMode.send(new OrderPacket(OrderPacket.BUILD, CommandMode.selected(), 0, ghostAt[0], ghostAt[1], picked.id(), false, 0));
        requestBuilds();
        if (!keep) picked = null;
    }

    @Override
    public void tick() {
        if (buildOpen && --refreshIn <= 0) requestBuilds();
    }

    // ---------- рисование ----------
    private static void line(GuiGraphics g, double x1, double y1, double x2, double y2, int c) {
        int n = (int) Math.min(2500, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            int x = (int) (x1 + (x2 - x1) * t), y = (int) (y1 + (y2 - y1) * t);
            g.fill(x, y, x + 2, y + 2, c);
        }
    }

    private void button(GuiGraphics g, Btn b, int mxx, int myy) {
        boolean over = b.hit(mxx, myy);
        g.fill(b.x, b.y, b.x + b.w, b.y + BTN_H, b.on ? 0xEE3A5A2A : over ? 0xEE3A3A52 : 0xDD15151F);
        g.fill(b.x, b.y, b.x + b.w, b.y + 1, b.on ? 0xFF8AD060 : 0xFF6A5A30);
        g.drawCenteredString(font, b.label, b.x + b.w / 2, b.y + 4, b.on ? 0xFFDDFFCC : 0xFFE6C875);
    }

    @Override
    public void render(GuiGraphics g, int mxi, int myi, float pt) {
        publishMaqanSelection();
        mx = mxi; my = myi;
        Minecraft mc = minecraft;
        if (mc.level == null || mc.player == null) return;
        int w = width, h = height;
        if (CommandMode.active && CommandMode.vp != null) {
            int[] viewport = workspaceViewport();
            if (embeddedInMaqan) g.enableScissor(viewport[0], viewport[1],
                    viewport[0] + viewport[2], viewport[1] + viewport[3]);
            for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) {
                if (!(en instanceof LivingEntity le) || !le.isAlive() || !CommandMode.selectable(mc, le)) continue;
                double[] p = Projection.project(CommandMode.vp, le.getX() - CommandMode.cam.x, le.getY() - CommandMode.cam.y, le.getZ() - CommandMode.cam.z, w, h);
                if (p[2] <= 0) continue;
                int r = (int) Math.max(4, 40 / p[2]);
                boolean sel = CommandMode.SEL.isSelected(le.getUUID().toString());
                int col = sel ? 0xFF66FF66 : 0x6688AA88;
                int x = (int) p[0], y = (int) p[1];
                g.fill(x - r, y - 1, x + r, y + 1, col);
                g.fill(x - r, y + 1, x + r, y + 2, sel ? 0xFF225522 : 0x44224422);
                if (sel) {
                    double[] top = Projection.project(CommandMode.vp, le.getX() - CommandMode.cam.x, le.getY() + le.getBbHeight() + 0.2 - CommandMode.cam.y, le.getZ() - CommandMode.cam.z, w, h);
                    int bw = Math.max(8, r * 2), hp = (int) (bw * le.getHealth() / le.getMaxHealth());
                    g.fill((int) top[0] - bw / 2, (int) top[1] - 3, (int) top[0] + bw / 2, (int) top[1], 0xFF331111);
                    g.fill((int) top[0] - bw / 2, (int) top[1] - 3, (int) top[0] - bw / 2 + hp, (int) top[1], 0xFFCC3333);
                }
            }
            if (CommandMode.marker != null && System.currentTimeMillis() < CommandMode.markerUntil) {
                double[] p = Projection.project(CommandMode.vp, CommandMode.marker.x - CommandMode.cam.x, CommandMode.marker.y - CommandMode.cam.y, CommandMode.marker.z - CommandMode.cam.z, w, h);
                if (p[2] > 0) { int x = (int) p[0], y = (int) p[1]; g.fill(x - 6, y - 1, x + 6, y + 1, 0xFFFFFF55); g.fill(x - 1, y - 6, x + 1, y + 6, 0xFFFFFF55); }
            }
            drawGhost(g, mxi, myi, w, h);
            drawTaken(g, w, h);
            drawWorkAreas(g, w, h);
            drawConstructionGhosts(g, w, h, mxi, myi);
            if (embeddedInMaqan) g.disableScissor();
        }
        if (dragging) {
            int[] viewport = embeddedInMaqan ? workspaceViewport() : new int[]{0, 0, width, height};
            int x1 = Math.max(viewport[0], (int) Math.min(dragX, mx));
            int x2 = Math.min(viewport[0] + viewport[2], (int) Math.max(dragX, mx));
            int y1 = Math.max(viewport[1], (int) Math.min(dragY, my));
            int y2 = Math.min(viewport[1] + viewport[3], (int) Math.max(dragY, my));
            if (x2 > x1 && y2 > y1) {
                g.fill(x1, y1, x2, y2, 0x2266FF66);
                g.fill(x1, y1, x2, y1 + 1, 0xFF66FF66); g.fill(x1, y2 - 1, x2, y2, 0xFF66FF66);
                g.fill(x1, y1, x1 + 1, y2, 0xFF66FF66); g.fill(x2 - 1, y1, x2, y2, 0xFF66FF66);
            }
        }
        drawSettlementMinimap(g);
        if (professionalToolsOpen && !selectedWorkerIds().isEmpty()) drawProfessionalPanel(g);
        else professionalToolsOpen = false;
        for (Btn b : buttons()) button(g, b, mxi, myi);
        drawSelection(g, h);
        if (settlementOpen) drawSettlementPanel(g);
        drawUnitInspector(g);
        drawZoomSlider(g);
        if (buildOpen) drawBuildPanel(g, mxi, myi);
        String hint = workMode >= 0 ? "Участок: " + WORK_LABELS[workMode] + " · ЛКМ/рамка — назначить выделенным рабочим · O — видимость зоны · Y — сменить задачу" : picked != null ? "ЛКМ - поставить здание (Shift - ещё одно) · ПКМ/Esc - отмена · колесо - высота камеры"
                : "ЛКМ выбор/рамка · ПКМ приказ · WASD и края экрана - камера · колесо - высота · СКМ - вращать · Q/E поворот · Tab вид · Space к выбранным · V командир / Ctrl+V снять · P патруль · B стройка · R выход";
        if (!embeddedInMaqan) {
            g.drawString(font, hint, 6, 6, 0xFFC8C8C8, true);
            g.drawString(font, "Вид: " + CommandMode.RIG.view.title + "   Строй: "
                    + Formations.Type.values()[CommandMode.formation].title + "   Поза: "
                    + Squads.Stance.values()[CommandMode.stance].title, 6, 18, 0xFF9CCCFF, true);
        }
    }

    /** Занятые места поселения на земле: тонкие рамки, видно, где уже стоят здания, пока открыто меню стройки. */
    private void drawTaken(GuiGraphics g, int w, int h) {
        if (!buildOpen || picked == null) return;
        for (BuildGhost.Rect r : taken) outline(g, (int) Math.floor(r.cx()), (int) Math.floor(r.cz()), r.sx(), r.sz(), groundYFor(r.cx(), r.cz()), 0x88BBBBBB, w, h);
    }

    private void drawConstructionGhosts(GuiGraphics g, int w, int h, int mouseX, int mouseY) {
        if (constructionGhosts.isEmpty() || CommandMode.vp == null || minecraft.level == null) return;
        int visible = 0;
        for (ConstructionGhost ghost : constructionGhosts) {
            double[] p = Projection.project(CommandMode.vp,
                    ghost.x() + 0.5 - CommandMode.cam.x,
                    ghost.y() + 0.5 - CommandMode.cam.y,
                    ghost.z() + 0.5 - CommandMode.cam.z, w, h);
            if (p[2] <= 0 || p[2] > 180) continue;
            int x = (int) p[0], y = (int) p[1];
            int size = Math.max(2, Math.min(6, (int) (18 / p[2])));
            g.fill(x - size, y - size, x + size + 1, y + size + 1, 0x6638CFFF);
            g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF9BE8FF);
            if (Math.hypot(mouseX - x, mouseY - y) <= Math.max(8, size + 3)) {
                String label = ghost.block() + " · " + ghost.x() + " " + ghost.y() + " " + ghost.z();
                int tx = Math.min(w - font.width(label) - 8, mouseX + 12), ty = Math.max(28, mouseY + 10);
                g.fill(tx - 3, ty - 2, tx + font.width(label) + 4, ty + 11, 0xDD101820);
                g.drawString(font, label, tx, ty, 0xFF9BE8FF, false);
            }
            visible++;
        }
        if (visible > 0) {
            String label = "Чертёж · недостающих блоков: " + constructionGhosts.size() + " · в кадре: " + visible;
            int[] console = skinRect("bot");
            int x = console == null ? 6 : console[0] + 12;
            int y = console == null ? height - 34 : console[1] + console[3] - 36;
            g.fill(x, y, x + font.width(label) + 12, y + 15, 0xCC101018);
            g.drawString(font, label, x + 6, y + 4, 0xFF9BE8FF, false);
        }
    }

    private void drawWorkAreas(GuiGraphics g, int w, int h) {
        if (CommandMode.vp == null || minecraft.level == null) return;
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !CommandMode.SEL.isSelected(entity.getUUID().toString())
                    || !CommandMode.isWorkerEntity(living)) continue;
            String data = CommandMode.workerString(living, "workAreaLabel");
            if (data.isBlank()) continue;
            String[] p = data.split("\\|");
            if (p.length < 6) continue;
            try {
                int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                int radius = Math.max(0, Math.min(64, Integer.parseInt(p[3])));
                int level = Integer.parseInt(p[4]), visibility = Integer.parseInt(p[5]);
                int halfX = p.length > 6 ? Math.max(0, Math.min(64, Integer.parseInt(p[6]))) : radius;
                int halfZ = p.length > 7 ? Math.max(0, Math.min(64, Integer.parseInt(p[7]))) : radius;
                if (visibility == 0) continue;
                double y = level > minecraft.level.getMinBuildHeight() && level < minecraft.level.getMaxBuildHeight()
                        ? level + 0.15 : groundYFor(x, z);
                int color = visibility == 1 ? 0x6688CCFF : 0xDD55DFFF;
                outline(g, x, z, Math.max(1, halfX * 2 + 1), Math.max(1, halfZ * 2 + 1), y, color, w, h);
                if (visibility == 2) {
                    double[] center = Projection.project(CommandMode.vp, x - CommandMode.cam.x, y - CommandMode.cam.y, z - CommandMode.cam.z, w, h);
                    if (center[2] > 0) g.drawString(font, p[0] + " · r" + radius, (int) center[0] + 4, (int) center[1] - 8, color, true);
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    private double groundYFor(double x, double z) {
        return minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z)) + 0.1;
    }

    private void outline(GuiGraphics g, int cx, int cz, int sx, int sz, double y, int color, int w, int h) {
        double[][] cs = BuildGhost.corners(cx, cz, sx, sz);
        double[][] pr = new double[4][];
        for (int i = 0; i < 4; i++) {
            pr[i] = Projection.project(CommandMode.vp, cs[i][0] - CommandMode.cam.x, y - CommandMode.cam.y, cs[i][1] - CommandMode.cam.z, w, h);
            if (pr[i][2] <= 0) return;
        }
        for (int i = 0; i < 4; i++) line(g, pr[i][0], pr[i][1], pr[(i + 1) % 4][0], pr[(i + 1) % 4][1], color);
    }

    private void drawGhost(GuiGraphics g, int mxi, int myi, int w, int h) {
        ghostAt = null;
        if (picked == null || overPanel(mxi, myi)) return;
        Vec3 gp = CommandMode.groundAt(minecraft, mxi, myi);
        if (gp == null) return;
        ghostAt = BuildGhost.snap(gp.x, gp.z);
        ghostMsg = BuildGhost.check(picked, ghostAt[0], ghostAt[1], colX, colZ, taken, stock);
        String miss = BuildGhost.missing(picked, stock);
        int col = !ghostMsg.isEmpty() ? 0xFFFF5555 : miss.isEmpty() ? 0xFF55FF55 : 0xFFFFDD55;
        double y = gp.y + 0.1;
        outline(g, ghostAt[0], ghostAt[1], picked.sx(), picked.sz(), y, col, w, h);
        double[][] cs = BuildGhost.corners(ghostAt[0], ghostAt[1], picked.sx(), picked.sz());
        double[] a = Projection.project(CommandMode.vp, cs[0][0] - CommandMode.cam.x, y - CommandMode.cam.y, cs[0][1] - CommandMode.cam.z, w, h);
        double[] b = Projection.project(CommandMode.vp, cs[2][0] - CommandMode.cam.x, y - CommandMode.cam.y, cs[2][1] - CommandMode.cam.z, w, h);
        if (a[2] > 0 && b[2] > 0) { line(g, a[0], a[1], b[0], b[1], col & 0x88FFFFFF); line(g, a[0], b[1], b[0], a[1], col & 0x88FFFFFF); }
        String label = picked.name() + " " + picked.sx() + "×" + picked.sz();
        String sub = !ghostMsg.isEmpty() ? ghostMsg : miss.isEmpty() ? "Можно строить" : "Не хватает: " + miss + " (стройка будет ждать)";
        int tx = mxi + 14, ty = myi + 10;
        g.fill(tx - 3, ty - 2, tx + Math.max(font.width(label), font.width(sub)) + 4, ty + 21, 0xCC101018);
        g.drawString(font, label, tx, ty, 0xFFFFFFFF, false);
        g.drawString(font, sub, tx, ty + 10, col, false);
    }

    private void drawSelection(GuiGraphics g, int h) {
        if (compactSkin()) return;
        Minecraft mc = minecraft;
        Map<String, Integer> counts = new TreeMap<>();
        for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) {
            if (en instanceof TroopEntity t && CommandMode.SEL.isSelected(t.getUUID().toString())) counts.merge(t.def() != null ? t.def().name() : t.unitId(), 1, Integer::sum);
            else if (en instanceof LivingEntity le && CommandMode.SEL.isSelected(le.getUUID().toString())) counts.merge("Рабочий", 1, Integer::sum);
        }
        int morSum = 0, morN = 0;
        for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) if (en instanceof TroopEntity t && CommandMode.SEL.isSelected(t.getUUID().toString())) { morSum += t.moralePct(); morN++; }
        int rows = Math.min(5, Math.max(1, counts.size()));
        int top = embeddedInMaqan ? selectionPanelY() : h - 14 - rows * 11 - 10;
        int sx = embeddedInMaqan ? selectionPanelX() : 4;
        int sw = embeddedInMaqan ? selectionPanelW() : 186;
        int bottom = embeddedInMaqan ? top + 82 : h - 4;
        g.fill(sx, top, sx + sw, bottom, 0xAA101018);
        g.drawString(font, "Выбрано: " + CommandMode.SEL.selected().size(), sx + 6, top + 4, 0xFFE6C875, false);
        if (morN > 0) {
            int mor = morSum / morN;
            String ms = "Мораль " + mor + "%" + (mor < 20 ? " - бегут!" : "");
            g.drawString(font, ms, sx + sw - 6 - font.width(ms), top + 4, mor < 20 ? 0xFFFF5555 : mor < 50 ? 0xFFFFDD55 : 0xFF88DD88, false);
        }
        int yy = top + 15;
        int shown = 0;
        for (var en : counts.entrySet()) {
            if (shown++ >= 5) break;
            g.drawString(font, font.plainSubstrByWidth(en.getValue() + " × " + en.getKey(), sw - 12),
                    sx + 6, yy, 0xFFDDDDDD, false);
            yy += 11;
        }
    }

    private void drawBuildPanel(GuiGraphics g, int mxi, int myi) {
        int x = panelX(), y = panelY(), pw = panelW(), ph = panelH();
        g.fill(x, y, x + pw, y + ph, 0xDD101018);
        g.fill(x, y, x + pw, y + 1, 0xFF6A5A30);
        g.drawString(font, buildError.isEmpty() ? "Стройка: " + (colName.isEmpty() ? "…" : colName) : buildError, x + 6, y + 5, buildError.isEmpty() ? 0xFFE6C875 : 0xFFFF8888, false);
        if (!buildError.isEmpty()) return;
        StringBuilder st = new StringBuilder();
        for (var e : new TreeMap<>(stock).entrySet()) if (e.getValue() > 0 && st.length() < 60) st.append(st.length() > 0 ? " " : "").append(RES.getOrDefault(e.getKey(), e.getKey())).append(' ').append(e.getValue());
        g.drawString(font, font.plainSubstrByWidth(st.length() == 0 ? "Склад пуст" : st.toString(), pw - 12), x + 6, y + 16, 0xFFAAAAAA, false);
        int px = x + 6, cy = y + 28, cx = px, row = 0;
        List<String> chips = new ArrayList<>();
        chips.add("");
        chips.addAll(categories());
        for (String c : chips) {
            String label = c.isEmpty() ? "Все" : catName(c);
            int cw = font.width(label) + 8;
            if (cx + cw > x + pw - 4) { cx = px; row++; }
            int yy = cy + row * 12;
            boolean on = c.equals(cat);
            g.fill(cx, yy, cx + cw, yy + 11, on ? 0xFF3A5A2A : 0xFF22222E);
            g.drawString(font, label, cx + 4, yy + 2, on ? 0xFFDDFFCC : 0xFFBBBBBB, false);
            cx += cw + 3;
        }
        int listY = cy + (row + 1) * 12 + 4;
        List<BuildGhost.Def> vis = visibleDefs();
        int fit = rowsFit();
        BuildGhost.Def hover = null;
        for (int i = 0; i < fit && i + scroll < vis.size(); i++) {
            BuildGhost.Def d = vis.get(i + scroll);
            int yy = listY + i * ROW;
            boolean over = mxi >= x && mxi < x + pw && myi >= yy && myi < yy + ROW;
            if (over) hover = d;
            boolean on = picked != null && picked.id().equals(d.id());
            if (on || over) g.fill(x + 3, yy, x + pw - 3, yy + ROW, on ? 0xFF3A5A2A : 0x66444466);
            boolean afford = BuildGhost.missing(d, stock).isEmpty();
            g.drawString(font, font.plainSubstrByWidth(d.name(), pw - 50), x + 6, yy + 2, afford ? 0xFFE8E8E8 : 0xFFB0A080, false);
            String sz = d.sx() + "×" + d.sz();
            g.drawString(font, sz, x + pw - 6 - font.width(sz), yy + 2, 0xFF888899, false);
        }
        BuildGhost.Def info = hover != null ? hover : picked;
        if (info != null) {
            StringBuilder c = new StringBuilder("Цена: ");
            for (var e : info.cost().entrySet()) c.append(RES.getOrDefault(e.getKey(), e.getKey())).append(' ').append(e.getValue()).append("  ");
            g.drawString(font, font.plainSubstrByWidth(c.toString(), pw - 12), x + 6, y + ph - 12, 0xFFE6C875, false);
        }
    }
}
