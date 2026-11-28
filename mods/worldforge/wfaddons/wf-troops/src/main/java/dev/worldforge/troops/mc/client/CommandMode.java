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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
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
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;
import java.util.prefs.BackingStoreException;

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
    /** Hold Alt to open the contextual radial order wheel while in first-person command mode. */
    static final KeyMapping RADIAL_COMMAND = new KeyMapping("key.wf_troops.radial_command", GLFW.GLFW_KEY_LEFT_ALT, "key.categories.wf_troops");
    static boolean firstPersonCommand;
    static boolean active;
    private static CommandScreen hostedCommandView;
    private static boolean radialSuppressed;
    static final UnitSelection SEL = new UnitSelection();
    static final CameraRig RIG = new CameraRig();
    static double[] vp, invVp;
    static Vec3 cam = Vec3.ZERO;
    static int formation, stance = 1;
    static Vec3 marker;
    static long markerUntil;
    private static CameraType prevCamera = CameraType.FIRST_PERSON;
    private static Marker camEntity;
    private static boolean cameraPositionInitialized;
    private static double cameraSmoothX, cameraSmoothY, cameraSmoothZ;
    private static long lastFrame;
    private static CameraRig.View lastView = CameraRig.View.SKY;
    private static final Preferences CAMERA_PREFS =
            Preferences.userNodeForPackage(CommandMode.class).node("camera-state");
    private static long lastCameraPersistNanos;
    private static final long CAMERA_PERSIST_INTERVAL_NANOS = 10_000_000_000L;

    private static String cameraDimension(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    private static String cameraKey(String dimension) {
        return "d." + Integer.toUnsignedString(dimension.hashCode(), 16);
    }

    private static boolean restoreCamera(Minecraft mc) {
        String dimension = cameraDimension(mc);
        if (dimension.isBlank()) return false;
        String key = cameraKey(dimension);
        if (!dimension.equals(CAMERA_PREFS.get("dimension." + key, ""))) return false;
        try {
            CameraRig.View view = CameraRig.View.valueOf(CAMERA_PREFS.get("view." + key, ""));
            CameraRig.Snapshot saved = new CameraRig.Snapshot(view,
                    CAMERA_PREFS.getDouble("fx." + key, Double.NaN),
                    CAMERA_PREFS.getDouble("fz." + key, Double.NaN),
                    CAMERA_PREFS.getDouble("yaw." + key, Double.NaN),
                    CAMERA_PREFS.getDouble("pitch." + key, Double.NaN),
                    CAMERA_PREFS.getDouble("zoom." + key, Double.NaN),
                    CAMERA_PREFS.getDouble("leash." + key, Double.NaN));
            if (!RIG.restore(saved)) return false;
            for (CameraRig.View cameraView : CameraRig.View.values()) {
                RIG.restoreHeight(cameraView,
                        CAMERA_PREFS.getDouble("height." + key + "." + cameraView.name(), Double.NaN),
                        CAMERA_PREFS.getBoolean("height.lock." + key + "." + cameraView.name(), false));
            }
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void persistCamera(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) return;
        String dimension = cameraDimension(mc);
        if (dimension.isBlank()) return;
        CameraRig.Snapshot saved = RIG.snapshot();
        String key = cameraKey(dimension);
        CAMERA_PREFS.put("dimension." + key, dimension);
        CAMERA_PREFS.put("view." + key, saved.view().name());
        CAMERA_PREFS.putDouble("fx." + key, saved.fx());
        CAMERA_PREFS.putDouble("fz." + key, saved.fz());
        CAMERA_PREFS.putDouble("yaw." + key, saved.yaw());
        CAMERA_PREFS.putDouble("pitch." + key, saved.pitch());
        CAMERA_PREFS.putDouble("zoom." + key, saved.zoom());
        CAMERA_PREFS.putDouble("leash." + key, saved.leash());
        for (CameraRig.View cameraView : CameraRig.View.values()) {
            CAMERA_PREFS.putDouble("height." + key + "." + cameraView.name(), RIG.heightFor(cameraView));
            CAMERA_PREFS.putBoolean("height.lock." + key + "." + cameraView.name(), RIG.heightLockedFor(cameraView));
        }
        try {
            CAMERA_PREFS.flush();
            lastCameraPersistNanos = System.nanoTime();
        }
        catch (BackingStoreException ignored) {
            // A failed preference write must never interrupt camera restoration or gameplay.
        }
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
        e.register(FIRST_PERSON_COMMAND);
        e.register(RADIAL_COMMAND);
        MinecraftForge.EVENT_BUS.register(new Runtime());
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register(CommandScreen.BUILD_VIEW, payload -> {
            CommandScreen screen = currentCommandView();
            if (screen != null) screen.setBuilds(payload);
        }));
    }

    static CommandScreen currentCommandView() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CommandScreen screen) return screen;
        return isHostedCommandViewActive(mc) ? hostedCommandView : null;
    }

    /** Public bridge for MAQAN's shell-level camera controls. */
    public static boolean isCommandCameraActive() { return active && camEntity != null; }

    public static String commandCameraView() { return RIG.view.name(); }

    public static void setCommandCameraView(String name) {
        if (!active || name == null) return;
        try {
            RIG.transitionTo(CameraRig.View.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
            frame(Minecraft.getInstance());
        } catch (IllegalArgumentException ignored) {
            // Ignore stale or unsupported toolbar actions.
        }
    }

    static void send(OrderPacket p) { TroopsMod.NET.sendToServer(p); }
    static void suppressRadialUntilRelease() { radialSuppressed = true; }

    private static boolean down(long window, int key) { return InputConstants.isKeyDown(window, key); }

    /** First-person command input uses the vanilla crosshair hit result and the exact same server order pipeline as RTS. */
    private static void firstPersonInput(Minecraft mc, InputEvent.InteractionKeyMappingTriggered event) {
        if (!firstPersonCommand || active || mc.player == null || mc.level == null || mc.screen != null) return;
        boolean attack = event.isAttack();
        boolean use = event.isUseItem();
        if (!attack && !use) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (use && mc.hitResult instanceof EntityHitResult residentHit
                && residentHit.getEntity() instanceof LivingEntity resident && isWorkerEntity(resident)) {
            mc.player.connection.sendCommand("wfresident open " + resident.getUUID());
            return;
        }
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
    static boolean isOwnedNpcEntity(LivingEntity e){
        return e!=null&&"wf_people:npc".equals(e.getType().builtInRegistryHolder().key().location().toString());
    }
    private static String npcOwnerId(LivingEntity entity){
        if(!isOwnedNpcEntity(entity))return "";
        try{return String.valueOf(entity.getClass().getMethod("ownerId").invoke(entity));}
        catch(ReflectiveOperationException|RuntimeException ignored){return "";}
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
        if(e==mc.player)return true;
        String owner=mc.player.getUUID().toString();
        if (e instanceof TroopEntity t) return owner.equals(t.ownerId());
        if(isWorkerEntity(e)){
            String workerOwner=workerString(e,"ownerId");
            return owner.equals(workerOwner)||mc.hasSingleplayerServer();
        }
        if(isOwnedNpcEntity(e))return owner.equals(npcOwnerId(e));
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

    /**
     * Zoom around the world point currently under the cursor. The view direction
     * is unchanged by dolly zoom, so intersect its screen ray with the anchor
     * plane after changing distance and shift the focus by the resulting delta.
     */
    static void zoomAtCursor(Minecraft mc, double sx, double sy, double steps) {
        if (mc == null || mc.level == null || mc.player == null || invVp == null
                || !Double.isFinite(steps) || Math.abs(steps) < 1.0e-6) {
            if (Double.isFinite(steps)) RIG.zoomBy(steps);
            return;
        }
        Vec3 anchor = groundAt(mc, sx, sy);
        if (anchor == null) {
            RIG.zoomBy(steps);
            frame(mc);
            return;
        }
        double[] ray = Projection.ray(invVp, sx, sy,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        Vec3 direction = new Vec3(ray[0], ray[1], ray[2]);
        RIG.zoomBy(steps);
        double[] desired = RIG.positionRelativeToPlayer(mc.player.getY());
        Vec3 origin = new Vec3(desired[0], desired[1], desired[2]);
        if (Math.abs(direction.y) > 1.0e-5) {
            double distance = (anchor.y - origin.y) / direction.y;
            if (distance > 0.0 && distance < 512.0) {
                Vec3 after = origin.add(direction.scale(distance));
                RIG.focusOn(RIG.fx + anchor.x - after.x, RIG.fz + anchor.z - after.z);
            }
        }
        RIG.clamp(mc.player.getX(), mc.player.getZ());
        frame(mc);
    }

    /** Exact block under the cursor; used for single-tree/single-vein orders rather than a nearby surface point. */
    static net.minecraft.core.BlockPos blockAt(Minecraft mc, double sx, double sy) {
        if (mc == null || mc.level == null || invVp == null) return null;
        double[] d = Projection.ray(invVp, sx, sy, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        Vec3 direction = new Vec3(d[0], d[1], d[2]);
        HitResult hit = mc.level.clip(new ClipContext(cam, cam.add(direction.scale(250)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit instanceof BlockHitResult block ? block.getBlockPos() : null;
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

    /** Resident under the RTS cursor; the same screen-space hitbox is used by click and box selection. */
    static LivingEntity residentAt(Minecraft mc, double sx, double sy) {
        if (mc == null || mc.level == null || vp == null) return null;
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || !isWorkerEntity(living)) continue;
            double[] point = Projection.project(vp, living.getX() - cam.x,
                    living.getY() + living.getBbHeight() * 0.5 - cam.y,
                    living.getZ() - cam.z, width, height);
            if (point[2] <= 0) continue;
            double distance = Math.hypot(point[0] - sx, point[1] - sy);
            if (distance <= Math.max(8, 90 / point[2]) && distance < bestDistance) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }


    // ---- вход и выход ----

    /** Public entry point used by the settlement HUD bridge; all management opens the same command workspace. */
    public static void openUnified() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Opening RTS from the MAQAN workspace happens while a Screen is already
        // installed. The old null-screen guard silently ignored that legitimate path,
        // leaving a decorative RTS page with no camera controller behind it.
        if (!active) {
            firstPersonCommand = false;
            enter(mc);
            return;
        }
        if (!(mc.screen instanceof CommandScreen) && !isHostedCommandViewActive(mc))
            openCommandView(mc, new CommandScreen());
    }

    private static boolean isPlatformWorkspace(Screen screen) {
        return screen != null && screen.getClass().getName().equals("dev.maqan.platform.client.PlatformShellScreen");
    }

    private static boolean isHostedCommandViewActive(Minecraft mc) {
        if (hostedCommandView == null || !isPlatformWorkspace(mc.screen)) return false;
        try {
            Class<?> shell = Class.forName("dev.maqan.platform.client.PlatformShellScreen");
            return Boolean.TRUE.equals(shell.getMethod("isHostedProviderViewActive", Screen.class)
                    .invoke(null, hostedCommandView));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    /**
     * Host the existing RTS command screen inside MAQAN's shared workspace when
     * Platform is installed. The troop addon remains optional and keeps its
     * standalone screen when the host is absent.
     */
    private static void openCommandView(Minecraft mc, CommandScreen view) {
        hostedCommandView = view;
        try {
            Class<?> shell = Class.forName("dev.maqan.platform.client.PlatformShellScreen");
            shell.getMethod("openProviderView", String.class, Screen.class)
                    .invoke(null, "wf_troops", view);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            mc.setScreen(view);
        }
    }

    static void enter(Minecraft mc) {
        if (active || mc.level == null || mc.player == null) return;
        boolean preserveSelection=firstPersonCommand;
        firstPersonCommand=false;
        active = true;
        if(!preserveSelection){
            SEL.clear();
            prevCamera = mc.options.getCameraType();
        }
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        RIG.start(mc.player.getX(), mc.player.getZ(), mc.player.getYRot(), lastView);
        if (!restoreCamera(mc)) RIG.focusOn(mc.player.getX(), mc.player.getZ());
        lastView = RIG.view;
        cameraPositionInitialized = false;
        camEntity = new Marker(EntityType.MARKER, mc.level);                  // камера смотрит из этой сущности; игрок остаётся на месте
        lastFrame = System.nanoTime();
        frame(mc);
        mc.setCameraEntity(camEntity);
        openCommandView(mc, new CommandScreen());
        if (ModList.get().isLoaded("wf_settle")) mc.player.connection.sendCommand("wfsettle view hud");
    }

    /** Leave the RTS camera and return control to the player's body without discarding the current selection. */
    static void enterBodyCommand(Minecraft mc){
        if(!active||mc==null||mc.player==null)return;
        persistCamera(mc);
        lastView=RIG.view;
        active=false;
        hostedCommandView=null;
        camEntity=null;
        mc.setCameraEntity(mc.player);
        mc.options.setCameraType(prevCamera);
        firstPersonCommand=true;
        mc.setScreen(null);
        mc.player.displayClientMessage(Component.literal(
                "Управление персонажем: WASD/мышь · ЛКМ выбрать · Shift+ЛКМ добавить · ПКМ приказ · Alt — круг команд · R вернуться в RTS"),false);
    }

    static void exit(Minecraft mc) {
        if (!active) return;
        active = false; // Set first: removing the hosted view calls removed() synchronously.
        persistCamera(mc);
        lastView = RIG.view;
        SEL.clear();
        CommandScreen hosted = hostedCommandView;
        boolean inShell = isHostedCommandViewActive(mc);
        hostedCommandView = null;
        camEntity = null;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        mc.options.setCameraType(prevCamera);
        if (inShell && hosted != null) {
            try {
                Class<?> shell = Class.forName("dev.maqan.platform.client.PlatformShellScreen");
                shell.getMethod("closeEmbeddedProviderView", Screen.class).invoke(null, hosted);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // The shell may have been removed already; camera restoration is still complete.
            }
        } else if (mc.screen instanceof CommandScreen) {
            mc.setScreen(null);
        }
    }

    /** A provider screen may be removed while another provider replaces it. Only the
     * currently hosted command view owns the camera lifecycle. */
    static void onCommandViewRemoved(CommandScreen view) {
        if (view == null || hostedCommandView != view || !active) return;
        Minecraft mc = Minecraft.getInstance();
        active = false;
        persistCamera(mc);
        cameraPositionInitialized = false;
        lastView = RIG.view;
        SEL.clear();
        hostedCommandView = null;
        camEntity = null;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        mc.options.setCameraType(prevCamera);
    }

    /** Покадровое движение камеры: WASD / края экрана - сдвиг, Q/E - поворот, стрелки - поворот и наклон, Shift - быстрее. */
    static void frame(Minecraft mc) {
        if (!active || camEntity == null || mc.player == null || mc.level == null) return;
        long now = System.nanoTime();
        if (now - lastCameraPersistNanos >= CAMERA_PERSIST_INTERVAL_NANOS) persistCamera(mc);
        double dt = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        RIG.updateTransition(dt);
        long win = mc.getWindow().getWindow();
        // Resolve the actual embedded command view; mc.screen is the MAQAN shell.
        CommandScreen commandScreen = mc.screen instanceof CommandScreen standalone
                ? standalone : isHostedCommandViewActive(mc) ? hostedCommandView : null;
        boolean ui = commandScreen != null;
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
            if (mc.isWindowActive() && !commandScreen.busyWithMouse()) {
                double[] es = CameraRig.edgeScroll(commandScreen.mouseX(), commandScreen.mouseY(),
                        commandScreen.width, commandScreen.height);
                right += es[0]; fwd += es[1];
            }
        }
        boolean fast = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_SHIFT);
        RIG.pan(fwd, right, dt, fast);
        RIG.clamp(mc.player.getX(), mc.player.getZ());
        // Vertical camera placement is anchored to the live player position.
        // Terrain height at the map focus must never redefine the camera's Y coordinate.
        double[] p = RIG.positionRelativeToPlayer(mc.player.getY());
        if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]) || !Double.isFinite(p[2])) return;

        // Keep the requested player-relative height exact. Obstruction/terrain
        // correction used to silently push the camera upward or underground.
        cameraSmoothX = p[0];
        cameraSmoothY = p[1];
        cameraSmoothZ = p[2];
        cameraPositionInitialized = true;

        camEntity.setPos(cameraSmoothX, cameraSmoothY, cameraSmoothZ);
        camEntity.xo = cameraSmoothX; camEntity.yo = cameraSmoothY; camEntity.zo = cameraSmoothZ;
        camEntity.xOld = cameraSmoothX; camEntity.yOld = cameraSmoothY; camEntity.zOld = cameraSmoothZ;
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
        public void logout(ClientPlayerNetworkEvent.LoggingOut e) {
            if (active) persistCamera(Minecraft.getInstance());
            active = false; firstPersonCommand = false; camEntity = null;
            cameraPositionInitialized = false;
            hostedCommandView = null; SEL.clear();
        }

        @SubscribeEvent
        public void interaction(InputEvent.InteractionKeyMappingTriggered e) {
            firstPersonInput(Minecraft.getInstance(), e);
        }

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) { active = false; firstPersonCommand = false; camEntity = null; return; }
            boolean radialHeld = RADIAL_COMMAND.isDown();
            if (!radialHeld) radialSuppressed = false;
            if (mc.screen instanceof RadialCommandScreen radial) {
                if (!radialHeld) {
                    radial.commit();
                    mc.setScreen(null);
                }
            } else if (firstPersonCommand && !active && mc.screen == null
                    && radialHeld && !radialSuppressed && !SEL.selected().isEmpty()) {
                mc.setScreen(new RadialCommandScreen());
            }
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
                enter(mc);
            }
            if (active && !(mc.screen instanceof CommandScreen) && !isHostedCommandViewActive(mc))
                exit(mc); // Exit only when the embedded command view is no longer active.
            if (active) {
                java.util.Set<String> alive = new java.util.HashSet<>();
                for (net.minecraft.world.entity.Entity en : mc.level.entitiesForRendering()) if (en.isAlive()) alive.add(en.getUUID().toString());
                SEL.retain(alive);
            }
        }
    }
}
