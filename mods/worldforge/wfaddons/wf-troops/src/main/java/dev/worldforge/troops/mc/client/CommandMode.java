package dev.worldforge.troops.mc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.worldforge.mod.client.ClientViews;
import dev.worldforge.troops.CameraRig;
import dev.worldforge.troops.Projection;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.UnitSelection;
import dev.worldforge.troops.mc.OrderPacket;
import dev.worldforge.troops.mc.TroopEntity;
import dev.worldforge.troops.mc.TroopsMod;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Командный режим (R): камера отвязывается от игрока и летает над миром как в стратегии (вид «Сверху» и «Тактика»; Tab - переключить),
 * курсор свободен. Ввод и интерфейс - в {@link CommandScreen} (экран перехватывает клавиши, и игра не машет предметами и не ходит).
 * Здесь: клавиша входа, камера-сущность, матрицы для выбора мышкой, служебные события.
 */
@Mod.EventBusSubscriber(modid = TroopsMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CommandMode {
    private CommandMode() {}

    static final KeyMapping TOGGLE = new KeyMapping("key.wf_troops.command", GLFW.GLFW_KEY_R, "key.categories.wf_troops");
    /** Direct command layer while remaining in first-person; LMB selects owned people, RMB issues orders. */
    static final KeyMapping FIRST_PERSON_COMMAND = new KeyMapping("key.wf_troops.first_person_command", GLFW.GLFW_KEY_G, "key.categories.wf_troops");
    static boolean firstPersonCommand;
    static boolean active;
    static final UnitSelection SEL = new UnitSelection();
    static final CameraRig RIG = new CameraRig();
    static double[] vp, invVp;
    static Vec3 cam = Vec3.ZERO;
    static int formation, stance = 1;
    static Vec3 marker;
    static long markerUntil;
    private static CameraType prevCamera = CameraType.FIRST_PERSON;
    private static Marker camEntity;
    private static double groundY;
    private static long lastFrame;
    private static CameraRig.View lastView = CameraRig.View.SKY;

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
        e.register(FIRST_PERSON_COMMAND);
        MinecraftForge.EVENT_BUS.register(new Runtime());
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register(CommandScreen.BUILD_VIEW, payload -> {
            if (Minecraft.getInstance().screen instanceof CommandScreen s) s.setBuilds(payload);
        }));
    }

    static void send(OrderPacket p) { TroopsMod.NET.sendToServer(p); }

    private static boolean down(long window, int key) { return InputConstants.isKeyDown(window, key); }

    /** First-person command input uses the vanilla crosshair hit result and the exact same server order pipeline as RTS. */
    private static void firstPersonInput(Minecraft mc, InputEvent.InteractionKeyMappingTriggered event) {
        if (!firstPersonCommand || active || mc.player == null || mc.level == null || mc.screen != null) return;
        boolean attack = event.isAttack();
        boolean use = event.isUseItem();
        if (!attack && !use) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (attack) {
            if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity target
                    && target.isAlive() && selectable(mc, target)) {
                boolean shift = down(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                        || down(mc.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
                String id = target.getUUID().toString();
                if (shift && SEL.isSelected(id)) {
                    List<String> next = new ArrayList<>(SEL.selected());
                    next.remove(id);
                    SEL.set(next);
                } else if (shift) {
                    List<String> next = new ArrayList<>(SEL.selected());
                    if (next.size() < 200 && !next.contains(id)) next.add(id);
                    SEL.set(next);
                } else SEL.set(List.of(id));
                mc.player.displayClientMessage(Component.literal("Выбрано: " + SEL.selected().size()), true);
            } else if (!down(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                    && !down(mc.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT)) {
                SEL.clear();
                mc.player.displayClientMessage(Component.literal("Выделение снято"), true);
            }
            return;
        }
        if (SEL.selected().isEmpty()) {
            mc.player.displayClientMessage(Component.literal("Сначала выберите рабочих или солдат: ЛКМ, Shift+ЛКМ"), true);
            return;
        }
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity target
                && target.isAlive() && !selectable(mc, target)) {
            send(new OrderPacket(OrderPacket.ORDER, selected(), Squads.Kind.ATTACK.ordinal(),
                    0, 0, target.getUUID().toString(), false, 0));
            mc.player.displayClientMessage(Component.literal("Приказ: атаковать цель"), true);
            return;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            Vec3 point = hit.getLocation();
            boolean queue = down(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                    || down(mc.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
            send(new OrderPacket(OrderPacket.ORDER, selected(), Squads.Kind.MOVE.ordinal(),
                    point.x, point.z, "", queue, 0));
            mc.player.displayClientMessage(Component.literal(queue ? "Добавлена точка маршрута" : "Приказ: перемещение"), true);
        }
    }

    static String typeOf(LivingEntity e) { return e.getType().builtInRegistryHolder().key().location().toString() + (e instanceof TroopEntity t ? ":" + t.unitId() : ""); }

    private static final Map<Class<?>,Map<String,Method>> OPTIONAL_WORKER_METHODS=new ConcurrentHashMap<>();
    static boolean isWorkerEntity(LivingEntity e){
        return e!=null&&"wf_settle:worker".equals(e.getType().builtInRegistryHolder().key().location().toString());
    }
    private static Object workerValue(LivingEntity e,String method){
        if(!isWorkerEntity(e))return null;
        try{
            Map<String,Method> methods=OPTIONAL_WORKER_METHODS.computeIfAbsent(e.getClass(),k->new ConcurrentHashMap<>());
            Method accessor=methods.get(method);
            if(accessor==null){accessor=e.getClass().getMethod(method);methods.put(method,accessor);}
            return accessor.invoke(e);
        }catch(ReflectiveOperationException|RuntimeException ignored){return null;}
    }
    static String workerString(LivingEntity e,String method){
        Object value=workerValue(e,method);return value instanceof String text?text:"";
    }
    static int workerInt(LivingEntity e,String method){
        Object value=workerValue(e,method);return value instanceof Number number?number.intValue():0;
    }

    /** Мои солдаты и рабочие поселений: то, что можно выделить. */
    static boolean selectable(Minecraft mc, LivingEntity e) {
        if (mc == null || mc.player == null || e == null) return false;
        String owner=mc.player.getUUID().toString();
        if (e instanceof TroopEntity t) return owner.equals(t.ownerId());
        if(isWorkerEntity(e)){
            String workerOwner=workerString(e,"ownerId");
            return owner.equals(workerOwner)||mc.hasSingleplayerServer();
        }
        return false;
    }

    static List<UnitSelection.Screen> screenList(Minecraft mc) {
        List<UnitSelection.Screen> out = new ArrayList<>();
        if (vp == null) return out;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof LivingEntity le) || !le.isAlive() || !selectable(mc, le)) continue;
            double[] p = Projection.project(vp, le.getX() - cam.x, le.getY() + le.getBbHeight() * 0.5 - cam.y, le.getZ() - cam.z, w, h);
            double scale = p[2] > 0 ? Math.max(7, 90 / p[2]) : 0;
            out.add(new UnitSelection.Screen(le.getUUID().toString(), typeOf(le), p[0], p[1], scale, p[2] > 0));
        }
        return out;
    }

    /** Точка земли под пикселем (координаты экрана в масштабе интерфейса) или null. */
    static Vec3 groundAt(Minecraft mc, double sx, double sy) {
        if (invVp == null) return null;
        double[] d = Projection.ray(invVp, sx, sy, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        Vec3 dir = new Vec3(d[0], d[1], d[2]);
        var hit = mc.level.clip(new ClipContext(cam, cam.add(dir.scale(250)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    /** Живой объект под курсором, по которому можно ударить (монстр или чужой солдат). */
    static LivingEntity enemyAt(Minecraft mc, double sx, double sy) {
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof LivingEntity le) || !le.isAlive() || le == mc.player) continue;
            if (!(le instanceof Enemy) && !(le instanceof TroopEntity && !selectable(mc, le))) continue;
            double[] p = Projection.project(vp, le.getX() - cam.x, le.getY() + le.getBbHeight() * 0.5 - cam.y, le.getZ() - cam.z, w, h);
            if (p[2] <= 0) continue;
            double d = Math.hypot(p[0] - sx, p[1] - sy);
            if (d < Math.max(8, 110 / p[2]) && d < bd) { bd = d; best = le; }
        }
        return best;
    }

    static List<String> selected() { return new ArrayList<>(SEL.selected()); }

    // ---- вход и выход ----

    static void enter(Minecraft mc) {
        if (active || mc.level == null || mc.player == null) return;
        active = true;
        SEL.clear();
        prevCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        RIG.start(mc.player.getX(), mc.player.getZ(), mc.player.getYRot(), lastView);
        groundY = mc.player.getY();
        camEntity = new Marker(EntityType.MARKER, mc.level);                  // камера смотрит из этой сущности; игрок остаётся на месте
        lastFrame = System.nanoTime();
        frame(mc);
        mc.setCameraEntity(camEntity);
        mc.setScreen(new CommandScreen());
    }

    static void exit(Minecraft mc) {
        if (!active) return;
        active = false;
        lastView = RIG.view;
        SEL.clear();
        camEntity = null;
        mc.setCameraEntity(mc.player);
        mc.options.setCameraType(prevCamera);
        if (mc.screen instanceof CommandScreen) mc.setScreen(null);
    }

    /** Покадровое движение камеры: WASD / края экрана - сдвиг, Q/E - поворот, стрелки - поворот и наклон, Shift - быстрее. */
    static void frame(Minecraft mc) {
        if (!active || camEntity == null || mc.player == null || mc.level == null) return;
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        long win = mc.getWindow().getWindow();
        boolean ui = mc.screen instanceof CommandScreen;
        double fwd = 0, right = 0;
        if (ui) {
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_W)) fwd += 1;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_S)) fwd -= 1;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_D)) right += 1;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_A)) right -= 1;
            double yaw = 0, pitch = 0;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_Q) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT)) yaw -= 90 * dt;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_E) || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT)) yaw += 90 * dt;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_UP)) pitch -= 45 * dt;
            if (InputConstants.isKeyDown(win, GLFW.GLFW_KEY_DOWN)) pitch += 45 * dt;
            if (yaw != 0 || pitch != 0) RIG.rotate(yaw, pitch);
            CommandScreen cs = (CommandScreen) mc.screen;
            if (mc.isWindowActive() && !cs.busyWithMouse()) {
                double[] es = CameraRig.edgeScroll(cs.mouseX(), cs.mouseY(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                right += es[0]; fwd += es[1];
            }
        }
        boolean fast = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_SHIFT);
        RIG.pan(fwd, right, dt, fast);
        RIG.clamp(mc.player.getX(), mc.player.getZ());
        int gx = (int) Math.floor(RIG.fx), gz = (int) Math.floor(RIG.fz);
        if (mc.level.hasChunkAt(new net.minecraft.core.BlockPos(gx, 64, gz))) {
            double target = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
            groundY += (target - groundY) * Math.min(1, dt * 6);
        }
        double[] p = RIG.position(groundY);
        camEntity.setPos(p[0], p[1], p[2]);
        camEntity.xo = p[0]; camEntity.yo = p[1]; camEntity.zo = p[2];
        camEntity.xOld = p[0]; camEntity.yOld = p[1]; camEntity.zOld = p[2];
        camEntity.setYRot((float) RIG.yaw); camEntity.setXRot((float) RIG.pitch);
        camEntity.yRotO = (float) RIG.yaw; camEntity.xRotO = (float) RIG.pitch;
    }

    static final class Runtime {
        @SubscribeEvent
        public void matrices(RenderLevelStageEvent e) {
            if (!active || e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            float[] p = new float[16], v = new float[16];
            e.getProjectionMatrix().get(p);
            e.getPoseStack().last().pose().get(v);
            double[] pm = new double[16], vm = new double[16];
            for (int i = 0; i < 16; i++) { pm[i] = p[i]; vm[i] = v[i]; }
            vp = Projection.mul(pm, vm);
            invVp = Projection.invert(vp);
            cam = e.getCamera().getPosition();
        }

        @SubscribeEvent
        public void renderTick(TickEvent.RenderTickEvent e) {
            if (e.phase == TickEvent.Phase.START && active) frame(Minecraft.getInstance());
        }

        @SubscribeEvent
        public void hand(RenderHandEvent e) { if (active) e.setCanceled(true); }

        @SubscribeEvent
        public void overlay(RenderGuiOverlayEvent.Pre e) {
            if (active && e.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id())) e.setCanceled(true);
        }

        @SubscribeEvent
        public void logout(ClientPlayerNetworkEvent.LoggingOut e) { active = false; firstPersonCommand = false; camEntity = null; SEL.clear(); }

        @SubscribeEvent
        public void interaction(InputEvent.InteractionKeyMappingTriggered e) {
            firstPersonInput(Minecraft.getInstance(), e);
        }

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) { active = false; firstPersonCommand = false; camEntity = null; return; }
            while (FIRST_PERSON_COMMAND.consumeClick()) {
                if (!active && mc.screen == null) {
                    firstPersonCommand = !firstPersonCommand;
                    SEL.clear();
                    mc.player.displayClientMessage(Component.literal(firstPersonCommand
                            ? "Командование от первого лица: ЛКМ — выбрать, Shift+ЛКМ — добавить, ПКМ — приказ, G — выход"
                            : "Командование от первого лица выключено"), true);
                }
            }
            while (TOGGLE.consumeClick()) if (mc.screen == null && !active) {
                firstPersonCommand = false;
                enter(mc);
            }
            if (active && !(mc.screen instanceof CommandScreen)) exit(mc);                     // открылось другое окно (смерть, меню): выходим
            if (active) {
                java.util.Set<String> alive = new java.util.HashSet<>();
                for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) if (en.isAlive()) alive.add(en.getUUID().toString());
                SEL.retain(alive);
            }
        }
    }
}
