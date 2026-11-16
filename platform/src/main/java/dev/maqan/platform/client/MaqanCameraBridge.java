package dev.maqan.platform.client;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional adapter to the canonical wf_troops camera. Platform owns mode
 * presentation; wf_troops owns the camera entity, movement, projection and
 * command-selection state.
 */
public final class MaqanCameraBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("maqan-platform.camera");
    private static boolean warnedMissing;
    private MaqanCameraBridge() {}

    public static boolean available() {
        return ModList.get().isLoaded("wf_troops");
    }

    public static void apply(WorkspaceViewState.CameraMode mode) {
        if (mode == null) return;
        if (!available()) {
            if (mode != WorkspaceViewState.CameraMode.FIRST_PERSON) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    LOGGER.info("RTS camera controls require the optional wf_troops module");
                }
                WorkspaceViewState.setCameraMode(WorkspaceViewState.CameraMode.FIRST_PERSON);
            }
            return;
        }
        boolean applied = invokeBoolean("setMode", new Class<?>[]{String.class}, new Object[]{mode.name()});
        if (!applied) WorkspaceViewState.setCameraMode(WorkspaceViewState.CameraMode.FIRST_PERSON);
    }

    public static boolean active() {
        return invokeBoolean("active", new Class<?>[0], new Object[0]);
    }

    public static boolean zoom(double steps) {
        return invokeBoolean("zoom", new Class<?>[]{double.class}, new Object[]{steps});
    }

    public static boolean rotateByMouse(double dx, double dy) {
        return invokeBoolean("rotateByMouse", new Class<?>[]{double.class, double.class},
                new Object[]{dx, dy});
    }

    public static void focus(double x, double z) {
        invokeBoolean("focus", new Class<?>[]{double.class, double.class}, new Object[]{x, z});
    }

    private static boolean invokeBoolean(String method, Class<?>[] parameterTypes, Object[] arguments) {
        if (!available()) return false;
        try {
            Class<?> bridge = Class.forName(
                    "dev.worldforge.troops.mc.client.MaqanTroopsCameraBridge");
            Object result = bridge.getMethod(method, parameterTypes).invoke(null, arguments);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError error) {
            LOGGER.debug("Optional wf_troops camera bridge method {} is unavailable", method, error);
            return false;
        }
    }
}
