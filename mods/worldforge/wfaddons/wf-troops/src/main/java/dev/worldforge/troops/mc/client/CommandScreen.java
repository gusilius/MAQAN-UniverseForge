package dev.worldforge.troops.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.troops.BuildGhost;
import dev.worldforge.troops.CameraRig;
import dev.worldforge.troops.Formations;
import dev.worldforge.troops.Projection;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.UnitSelection;
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

    private boolean dragging, midDrag;
    private double dragX, dragY, mx, my;
    // стройка
    private boolean buildOpen;
    private final List<BuildGhost.Def> defs = new ArrayList<>();
    private final Map<String, Integer> stock = new HashMap<>();
    private final List<BuildGhost.Rect> taken = new ArrayList<>();
    private int colX, colZ, scroll, refreshIn;
    private String colName = "", buildError = "", cat = "";
    private BuildGhost.Def picked;
    private int[] ghostAt;
    private String ghostMsg = "";

    public CommandScreen() { super(Component.literal("Командный режим")); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void removed() { CommandMode.exit(Minecraft.getInstance()); }

    double mouseX() { return mx; }
    double mouseY() { return my; }

    /** Мышь занята делом (рамка, вращение, панель) - край экрана не двигает камеру. */
    boolean busyWithMouse() { return dragging || midDrag || overPanel(mx, my); }

    // ---------- данные стройки ----------
    @SuppressWarnings("unchecked")
    void setBuilds(String json) {
        defs.clear(); stock.clear(); taken.clear();
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

    // ---------- раскладка ----------
    private record Btn(String label, int x, int y, int w, boolean on, Runnable run) {
        boolean hit(double px, double py) { return px >= x && px < x + w && py >= y && py < y + BTN_H; }
    }

    private List<Btn> buttons() {
        List<Btn> l = new ArrayList<>();
        int x = width - 4 - BTN_W;
        l.add(new Btn("Выйти (R)", x, 4, BTN_W, false, () -> CommandMode.exit(minecraft)));
        x -= BTN_W + 4;
        l.add(new Btn("Строить (B)", x, 4, BTN_W, buildOpen, this::toggleBuild));
        x -= BTN_W + 4;
        l.add(new Btn("Тактика", x, 4, BTN_W, CommandMode.RIG.view == CameraRig.View.TACTICS, () -> CommandMode.RIG.setView(CameraRig.View.TACTICS)));
        x -= BTN_W + 4;
        l.add(new Btn("Сверху", x, 4, BTN_W, CommandMode.RIG.view == CameraRig.View.SKY, () -> CommandMode.RIG.setView(CameraRig.View.SKY)));
        return l;
    }

    private int panelX() { return width - PANEL_W - 4; }
    private int panelY() { return 26; }
    private int panelH() { return Math.max(80, height - 26 - 90); }

    private boolean inBuildPanel(double px, double py) { return buildOpen && px >= panelX() && px < panelX() + PANEL_W && py >= panelY() && py < panelY() + panelH(); }

    private boolean inSelPanel(double px, double py) { return px < 192 && py > height - 90; }

    boolean overPanel(double px, double py) {
        if (py < 4 + BTN_H + 2 && px > width - 4 * (BTN_W + 4) - 4) return true;
        return inBuildPanel(px, py) || inSelPanel(px, py);
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
    private static final int INSPECT_X=4, INSPECT_Y=34, INSPECT_W=250, INSPECT_ROW=19;

    private boolean inspectAt(double x,double y) {
        if(x<INSPECT_X||x>=INSPECT_X+INSPECT_W||y<INSPECT_Y+22)return false;
        int row=(int)((y-(INSPECT_Y+22))/INSPECT_ROW);
        List<String> ids=CommandMode.selected();
        if(row<0||row>=Math.min(8,ids.size()))return false;
        if(row>=ids.size())return y<INSPECT_Y+22+8*INSPECT_ROW;
        String id=ids.get(row);
        for(net.minecraft.world.entity.Entity entity:minecraft.level.entitiesForRendering()){
            if(!entity.isAlive()||!entity.getUUID().toString().equals(id))continue;
            CommandMode.SEL.set(List.of(id));
            CommandMode.RIG.focusOn(entity.getX(),entity.getZ());
            return true;
        }
        return true;
    }

    private void drawUnitInspector(GuiGraphics g) {
        List<String> ids=CommandMode.selected();
        if(ids.isEmpty())return;
        int rows=Math.min(8,ids.size());
        int h=24+rows*INSPECT_ROW;
        g.fill(INSPECT_X,INSPECT_Y,INSPECT_X+INSPECT_W,INSPECT_Y+h,0xD910121A);
        g.fill(INSPECT_X,INSPECT_Y,INSPECT_X+INSPECT_W,INSPECT_Y+1,0xFF8A7040);
        g.drawString(font,"ИНСПЕКТОР · "+ids.size()+" ВЫБРАНО",INSPECT_X+6,INSPECT_Y+6,0xFFE6C875,false);
        int row=0;
        for(String id:ids){
            if(row>=8)break;
            net.minecraft.world.entity.Entity entity=null;
            for(net.minecraft.world.entity.Entity candidate:minecraft.level.entitiesForRendering())
                if(candidate.getUUID().toString().equals(id)){entity=candidate;break;}
            int y=INSPECT_Y+22+row*INSPECT_ROW;
            if(entity==null){g.drawString(font,"Недоступен · "+id.substring(0,Math.min(8,id.length())),INSPECT_X+6,y+4,0xFF888888,false);row++;continue;}
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
            }else if(entity instanceof dev.worldforge.settle.mc.WFWorker w){
                role=w.professionId().isBlank()?"Рабочий":w.professionId();
                identity=w.characterId();
                state=w.statusLabel();
                if(w.assignedBuildingId()>0)state+=" · здание #"+w.assignedBuildingId();
            }else role="Юнит";
            g.fill(INSPECT_X+3,y,INSPECT_X+INSPECT_W-3,y+INSPECT_ROW-1,
                    (row&1)==0?0x55333344:0x33222230);
            int health=entity instanceof LivingEntity living&&living.getMaxHealth()>0
                    ?Math.round(100*living.getHealth()/living.getMaxHealth()):0;
            String detail=role+" · HP "+health+"%"+(morale>=0?" · Мораль "+morale+"%":"")+(xp>=0?" · XP "+xp:"")+(state.isBlank()?"":" · "+state);
            int nameWidth=identity.isBlank()?INSPECT_W-14:INSPECT_W-88;
            g.drawString(font,font.plainSubstrByWidth(name,nameWidth),INSPECT_X+7,y+2,
                    troop?0xFFE6C875:0xFFB8D8A0,false);
            g.drawString(font,font.plainSubstrByWidth(detail,INSPECT_W-14),INSPECT_X+7,y+11,0xFFB5B7C2,false);
            if(!identity.isBlank()){
                String shortId=identity.length()>16?identity.substring(0,16)+"…":identity;
                g.drawString(font,shortId,INSPECT_X+INSPECT_W-7-font.width(shortId),y+2,0xFF777F91,false);
            }
            row++;
        }
        if(ids.size()>8)g.drawString(font,"Ещё "+(ids.size()-8)+" · выберите строку для фокуса",
                INSPECT_X+6,INSPECT_Y+h-10,0xFF9EA4B5,false);
    }

    // ---------- ввод ----------
    @Override
    public boolean mouseClicked(double x, double y, int button) {
        mx = x; my = y;
        boolean shift = hasShiftDown(), ctrl = hasControlDown();
        if(button==0&&inspectAt(x,y))return true;
        if (button == 0) {
            for (Btn b : buttons()) if (b.hit(x, y)) { b.run().run(); return true; }
            if (buildOpen && inBuildPanel(x, y)) { panelClick(x, y); return true; }
            if (inSelPanel(x, y)) return true;
            if (picked != null) { place(shift); return true; }
            dragging = true; dragX = x; dragY = y;
            return true;
        }
        if (button == 2) { midDrag = true; return true; }
        if (button == 1) {
            if (picked != null) { picked = null; return true; }
            if (overPanel(x, y) || CommandMode.SEL.selected().isEmpty()) return true;
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
            if (cx + w > panelX() + PANEL_W - 4) { cx = px; row++; }
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
        if (button == 0 && dragging) {
            dragging = false;
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
        if (button == 2) CommandMode.RIG.rotate(dx * 0.35, dy * 0.25);
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) { mx = x; my = y; }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
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
        if (key == GLFW.GLFW_KEY_TAB) { CommandMode.RIG.cycle(); return true; }
        if (key == GLFW.GLFW_KEY_B) { toggleBuild(); return true; }
        if (key == GLFW.GLFW_KEY_HOME) { CommandMode.RIG.focusOn(minecraft.player.getX(), minecraft.player.getZ()); return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { focusSelection(); return true; }
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
        mx = mxi; my = myi;
        Minecraft mc = minecraft;
        if (mc.level == null || mc.player == null) return;
        int w = width, h = height;
        if (CommandMode.vp != null) {
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
        }
        if (dragging) {
            int x1 = (int) Math.min(dragX, mx), x2 = (int) Math.max(dragX, mx), y1 = (int) Math.min(dragY, my), y2 = (int) Math.max(dragY, my);
            g.fill(x1, y1, x2, y2, 0x2266FF66);
            g.fill(x1, y1, x2, y1 + 1, 0xFF66FF66); g.fill(x1, y2 - 1, x2, y2, 0xFF66FF66);
            g.fill(x1, y1, x1 + 1, y2, 0xFF66FF66); g.fill(x2 - 1, y1, x2, y2, 0xFF66FF66);
        }
        for (Btn b : buttons()) button(g, b, mxi, myi);
        drawSelection(g, h);
        drawUnitInspector(g);
        if (buildOpen) drawBuildPanel(g, mxi, myi);
        String hint = picked != null ? "ЛКМ - поставить здание (Shift - ещё одно) · ПКМ/Esc - отмена · колесо - высота камеры"
                : "ЛКМ выбор/рамка · ПКМ приказ · WASD и края экрана - камера · колесо - высота · СКМ - вращать · Q/E поворот · Tab вид · Space к выбранным · V командир / Ctrl+V снять · P патруль · B стройка · R выход";
        g.drawString(font, hint, 6, 6, 0xFFC8C8C8, true);
        g.drawString(font, "Вид: " + CommandMode.RIG.view.title + "   Строй: " + Formations.Type.values()[CommandMode.formation].title + "   Поза: " + Squads.Stance.values()[CommandMode.stance].title,
                6, 18, 0xFF9CCCFF, true);
    }

    /** Занятые места поселения на земле: тонкие рамки, видно, где уже стоят здания, пока открыто меню стройки. */
    private void drawTaken(GuiGraphics g, int w, int h) {
        if (!buildOpen || picked == null) return;
        for (BuildGhost.Rect r : taken) outline(g, (int) Math.floor(r.cx()), (int) Math.floor(r.cz()), r.sx(), r.sz(), groundYFor(r.cx(), r.cz()), 0x88BBBBBB, w, h);
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
        Minecraft mc = minecraft;
        Map<String, Integer> counts = new TreeMap<>();
        for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) {
            if (en instanceof TroopEntity t && CommandMode.SEL.isSelected(t.getUUID().toString())) counts.merge(t.def() != null ? t.def().name() : t.unitId(), 1, Integer::sum);
            else if (en instanceof LivingEntity le && CommandMode.SEL.isSelected(le.getUUID().toString())) counts.merge("Рабочий", 1, Integer::sum);
        }
        int morSum = 0, morN = 0;
        for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) if (en instanceof TroopEntity t && CommandMode.SEL.isSelected(t.getUUID().toString())) { morSum += t.moralePct(); morN++; }
        int rows = Math.min(5, Math.max(1, counts.size()));
        int top = h - 14 - rows * 11 - 10;
        g.fill(4, top, 190, h - 4, 0xAA101018);
        g.drawString(font, "Выбрано: " + CommandMode.SEL.selected().size(), 10, top + 4, 0xFFE6C875, false);
        if (morN > 0) {
            int mor = morSum / morN;
            String ms = "Мораль " + mor + "%" + (mor < 20 ? " - бегут!" : "");
            g.drawString(font, ms, 186 - font.width(ms), top + 4, mor < 20 ? 0xFFFF5555 : mor < 50 ? 0xFFFFDD55 : 0xFF88DD88, false);
        }
        int yy = top + 15;
        int shown = 0;
        for (var en : counts.entrySet()) { if (shown++ >= 5) break; g.drawString(font, en.getValue() + " × " + en.getKey(), 10, yy, 0xFFDDDDDD, false); yy += 11; }
    }

    private void drawBuildPanel(GuiGraphics g, int mxi, int myi) {
        int x = panelX(), y = panelY(), pw = PANEL_W, ph = panelH();
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
