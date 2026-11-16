package dev.worldforge.troops.mc.client;

import dev.worldforge.troops.CameraRig;
import net.minecraft.client.Minecraft;

/**
 * Optional public bridge from MAQAN Platform to the canonical wf_troops
 * command camera. No second camera entity, selection model or input loop is
 * introduced by the Platform integration.
 */
public final class MaqanTroopsCameraBridge {
    private MaqanTroopsCameraBridge() {}

    public static boolean setMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return false;

        if ("FIRST_PERSON".equals(mode)) {
            CommandMode.exitFromMaqan();
            return true;
        }

        CameraRig.View view = switch (mode) {
            case "FREE" -> CameraRig.View.FREE;
            case "ISOMETRIC" -> CameraRig.View.TACTICS;
            case "RTS" -> CameraRig.View.SKY;
            default -> null;
        };
        if (view == null) return false;
        CommandMode.enterFromMaqan();
        if (!CommandMode.active) return false;
        CommandMode.setCameraView(view);
        return true;
    }

    public static boolean active() {
        return CommandMode.active;
    }

    public static boolean zoom(double steps) {
        if (!CommandMode.active) return false;
        CommandMode.RIG.zoomBy(steps);
        CommandMode.frame(Minecraft.getInstance());
        return true;
    }

    public static boolean rotateByMouse(double dx, double dy) {
        if (!CommandMode.active) return false;
        CommandMode.RIG.rotate(dx * 0.35, dy * 0.25);
        CommandMode.frame(Minecraft.getInstance());
        return true;
    }

    public static void focus(double x, double z) {
        if (!CommandMode.active) return;
        CommandMode.RIG.focusOn(x, z);
        CommandMode.frame(Minecraft.getInstance());
    }

    /** Keep MAQAN's camera badge synchronized with the legacy command-screen presets. */
    static void publishMode(CameraRig.View view) {
        String mode = switch (view) {
            case FREE -> "FREE";
            case TACTICS -> "ISOMETRIC";
            case SKY -> "RTS";
        };
        try {
            Class<?> state = Class.forName("dev.maqan.platform.client.WorkspaceViewState");
            Class<?> cameraMode = Class.forName(
                    "dev.maqan.platform.client.WorkspaceViewState$CameraMode");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class<? extends Enum>) cameraMode.asSubclass(Enum.class), mode);
            state.getMethod("setCameraMode", cameraMode).invoke(null, value);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Platform is optional; wf_troops continues to run standalone.
        }
    }
}
