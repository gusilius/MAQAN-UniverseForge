package dev.maqan.platform.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Owns only the client camera. The player entity, server position, selection,
 * simulation and provider map state are never moved or replaced.
 */
@Mod.EventBusSubscriber(modid = "maqan_platform", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaqanCameraController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double TICK_SECONDS = 1.0 / 20.0;

    private static MaqanCameraRig rig;
    private static CameraProxy proxy;
    private static Entity previousCamera;
    private static CameraType previousCameraType;
    private static WorkspaceViewState.CameraMode appliedMode =
            WorkspaceViewState.CameraMode.FIRST_PERSON;

    private MaqanCameraController() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            deactivate(minecraft);
            return;
        }

        WorkspaceViewState.CameraMode requested = WorkspaceViewState.cameraMode();
        if (requested == WorkspaceViewState.CameraMode.FIRST_PERSON) {
            deactivate(minecraft);
            appliedMode = requested;
            return;
        }

        if (proxy == null || proxy.level() != minecraft.level
                || minecraft.getCameraEntity() != proxy) {
            activate(minecraft, requested);
        } else if (requested != appliedMode) {
            rig.setPreset(preset(requested));
            appliedMode = requested;
        }

        if (proxy == null || rig == null) return;
        updateRig(minecraft);
    }

    private static void activate(Minecraft minecraft, WorkspaceViewState.CameraMode mode) {
        if (minecraft.player == null || minecraft.level == null) return;
        if (proxy != null && minecraft.getCameraEntity() == proxy) deactivate(minecraft);

        previousCamera = minecraft.getCameraEntity();
        previousCameraType = minecraft.options.getCameraType();
        proxy = new CameraProxy(minecraft.level);
        rig = new MaqanCameraRig();
        rig.start(minecraft.player.getX(), minecraft.player.getZ(),
                minecraft.player.getYRot(), preset(mode));
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        minecraft.setCameraEntity(proxy);
        appliedMode = mode;
        updateRig(minecraft);
        LOGGER.debug("MAQAN camera mode activated: {}", mode);
    }

    private static void deactivate(Minecraft minecraft) {
        if (proxy == null) return;
        if (minecraft.getCameraEntity() == proxy) {
            Entity restore = previousCamera;
            if (restore == null || restore.level() != minecraft.level) restore = minecraft.player;
            if (restore != null) minecraft.setCameraEntity(restore);
        }
        if (previousCameraType != null) minecraft.options.setCameraType(previousCameraType);
        proxy = null;
        rig = null;
        previousCamera = null;
        previousCameraType = null;
    }

    private static void updateRig(Minecraft minecraft) {
        if (rig == null || proxy == null || minecraft.player == null || minecraft.level == null) return;
        long window = minecraft.getWindow().getWindow();
        boolean forward = down(window, GLFW.GLFW_KEY_W) || down(window, GLFW.GLFW_KEY_UP);
        boolean backward = down(window, GLFW.GLFW_KEY_S) || down(window, GLFW.GLFW_KEY_DOWN);
        boolean left = down(window, GLFW.GLFW_KEY_A) || down(window, GLFW.GLFW_KEY_LEFT);
        boolean right = down(window, GLFW.GLFW_KEY_D) || down(window, GLFW.GLFW_KEY_RIGHT);
        boolean fast = down(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || down(window, GLFW.GLFW_KEY_RIGHT_SHIFT);

        rig.pan((forward ? 1 : 0) - (backward ? 1 : 0),
                (right ? 1 : 0) - (left ? 1 : 0), TICK_SECONDS, fast);
        if (down(window, GLFW.GLFW_KEY_Q)) rig.rotate(-2.0, 0.0);
        if (down(window, GLFW.GLFW_KEY_E)) rig.rotate(2.0, 0.0);
        if (down(window, GLFW.GLFW_KEY_R)) rig.rotate(0.0, -1.0);
        if (down(window, GLFW.GLFW_KEY_F)) rig.rotate(0.0, 1.0);

        rig.clampTo(minecraft.player.getX(), minecraft.player.getZ());
        int blockX = (int) Math.floor(rig.focusX());
        int blockZ = (int) Math.floor(rig.focusZ());
        int groundY = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(blockX, minecraft.player.blockPosition().getY(), blockZ));
        double[] position = rig.position(groundY);
        proxy.setPos(position[0], position[1], position[2]);
        proxy.setYRot((float) rig.yaw());
        proxy.setXRot((float) rig.pitch());
        proxy.yRotO = proxy.getYRot();
        proxy.xRotO = proxy.getXRot();
    }

    public static boolean zoom(double wheelSteps) {
        if (rig == null || proxy == null) return false;
        rig.zoomBy(wheelSteps);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.level != null) updateRig(minecraft);
        return true;
    }

    public static boolean active() { return proxy != null; }

    private static MaqanCameraRig.Preset preset(WorkspaceViewState.CameraMode mode) {
        return mode == WorkspaceViewState.CameraMode.FREE
                ? MaqanCameraRig.Preset.FREE : MaqanCameraRig.Preset.RTS;
    }

    private static boolean down(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    /** Unregistered client-only camera anchor; it is never added to or saved in a world. */
    private static final class CameraProxy extends Entity {
        private CameraProxy(Level level) {
            super(EntityType.ARMOR_STAND, level);
            setNoGravity(true);
        }

        @Override protected void defineSynchedData() { }
        @Override protected void readAdditionalSaveData(CompoundTag tag) { }
        @Override protected void addAdditionalSaveData(CompoundTag tag) { }
        @Override public float getEyeHeight(Pose pose) { return 0.0F; }
    }
}
