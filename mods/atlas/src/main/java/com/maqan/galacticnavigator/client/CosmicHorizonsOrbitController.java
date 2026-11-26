package com.maqan.galacticnavigator.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maqan.galacticnavigator.MaqanGalacticNavigator;
import com.maqan.galacticnavigator.data.GalaxyCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;

/**
 * Connects MAQAN's shared orbital simulation to Cosmic Horizons 0.0.7.3.
 *
 * Cosmic Horizons' own RenderMINTProcedure draws native planet_data bodies
 * from WorldVariables.render_data_map and sorts them with global_position_map.
 * This adapter updates those two client-side lists before the native AFTER_SKY
 * render pass. Textures, rings, clouds, atmospheres, lighting and geometry remain
 * entirely owned by Cosmic Horizons.
 */
@Mod.EventBusSubscriber(modid = MaqanGalacticNavigator.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CosmicHorizonsOrbitController {
    private static final String VARIABLE_CLASS =
            "net.lointain.cosmos.network.CosmosModVariables$WorldVariables";

    private static Method worldVariablesGetter;
    private static Field renderDataField;
    private static Field globalPositionField;
    private static boolean reflectionResolved;
    private static boolean reflectionFailed;
    private static String lastMismatch = "";
    private static final Set<String> loggedDimensions = new HashSet<>();

    private CosmicHorizonsOrbitController() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeCosmicHorizonsSkyRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || !level.isClientSide) return;

        CosmicOrbitSettings.Config settings = CosmicOrbitSettings.get();
        String dimensionId = level.dimension().location().toString();
        List<NativeBody> bodies = collectBodiesForDimension(dimensionId);
        if (bodies.isEmpty()) return;

        try {
            Object variables = worldVariables(level);
            if (variables == null) return;

            CompoundTag renderRoot = (CompoundTag) renderDataField.get(variables);
            CompoundTag positionRoot = (CompoundTag) globalPositionField.get(variables);
            if (renderRoot == null || positionRoot == null
                    || !renderRoot.contains(dimensionId, Tag.TAG_LIST)
                    || !positionRoot.contains(dimensionId, Tag.TAG_LIST)) return;

            ListTag renderList = renderRoot.getList(dimensionId, Tag.TAG_COMPOUND);
            ListTag positionList = positionRoot.getList(dimensionId, Tag.TAG_STRING);
            if (renderList.size() != bodies.size() || positionList.size() != bodies.size()) {
                reportMismatch(dimensionId + ": catalogue=" + bodies.size()
                        + ", CH render=" + renderList.size() + ", CH positions=" + positionList.size());
                return;
            }
            lastMismatch = "";
            if (loggedDimensions.add(dimensionId)) {
                System.out.println("[MAQAN] Cosmic Horizons native motion adapter connected: "
                        + dimensionId + " · " + bodies.size() + " celestial bodies");
            }

            // Use the same render partial tick source as NavigatorScreen and
            // CosmicBodyGuiRenderer so both views sample an identical phase.
            double gameTime = level.getGameTime() + minecraft.getFrameTime();
            double rotationTicks = advanceRotationClock(level, gameTime, settings);
            for (int i = 0; i < bodies.size(); i++) {
                NativeBody body = bodies.get(i);
                CompoundTag nativeBody = renderList.getCompound(i);
                String nativeId = nativeBody.getString("maqan_body_id");
                if (!nativeId.isEmpty() && !nativeId.equals(body.key())) {
                    reportMismatch(dimensionId + ": expected " + body.key()
                            + " at " + i + " but native list contains " + nativeId);
                    return;
                }
                if (nativeId.isEmpty() && !sameSourceCoordinate(nativeBody.getDouble("x"), body.baseX,
                        nativeBody.getDouble("y"), body.baseY,
                        nativeBody.getDouble("z"), body.baseZ)) {
                    reportMismatch(dimensionId + ": native source coordinates do not match "
                            + body.key() + " at index " + i);
                    return;
                }
                nativeBody.putString("maqan_body_id", body.key());

                double x = body.baseX;
                double y = body.baseY;
                double z = body.baseZ;
                if (settings.enabled && settings.orbitalMotion) {
                    x = NativeOrbitalSimulation.coordinate(body.systemId, body.system,
                            body.metadata, 0, gameTime);
                    y = NativeOrbitalSimulation.coordinate(body.systemId, body.system,
                            body.metadata, 1, gameTime);
                    z = NativeOrbitalSimulation.coordinate(body.systemId, body.system,
                            body.metadata, 2, gameTime);
                }
                nativeBody.putDouble("x", x);
                nativeBody.putDouble("y", y);
                nativeBody.putDouble("z", z);
                applyAxialRotation(nativeBody, body, rotationTicks, settings);
                positionList.set(i, StringTag.valueOf(encodePosition(x, y, z)));
            }
        } catch (ClassNotFoundException ignored) {
            // Cosmic Horizons is optional. No substitute renderer is installed.
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!reflectionFailed) {
                reflectionFailed = true;
                System.err.println("[MAQAN] Could not update Cosmic Horizons native render data: "
                        + error.getMessage());
            }
        }
    }

    private static synchronized Object worldVariables(Level level)
            throws ReflectiveOperationException, ClassNotFoundException {
        if (!reflectionResolved) {
            Class<?> type = Class.forName(VARIABLE_CLASS);
            worldVariablesGetter = type.getMethod("get", net.minecraft.world.level.LevelAccessor.class);
            renderDataField = type.getField("render_data_map");
            globalPositionField = type.getField("global_position_map");
            reflectionResolved = true;
        }
        return worldVariablesGetter.invoke(null, level);
    }

    /**
     * Cosmic Horizons appends each system's planet_data objects to the native
     * render lists in datapack order. Keep that exact order, including multiple
     * systems intentionally sharing one attached dimension.
     */
    private static List<NativeBody> collectBodiesForDimension(String dimensionId) {
        List<NativeBody> result = new ArrayList<>();
        for (String systemId : GalaxyCatalog.universes()) {
            JsonObject system = GalaxyCatalog.universe(systemId);
            if (!dimensionId.equals(GalaxyCatalog.text(system, "dimension", ""))) continue;
            if (!system.has("planet_data") || !system.get("planet_data").isJsonObject()) continue;
            JsonObject physical = system.getAsJsonObject("planet_data");
            JsonObject catalogue = system.has("planets") && system.get("planets").isJsonObject()
                    ? system.getAsJsonObject("planets") : new JsonObject();

            for (Map.Entry<String, JsonElement> entry : physical.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                String id = entry.getKey();
                JsonObject source = entry.getValue().getAsJsonObject();
                if (!catalogue.has(id) || !catalogue.get(id).isJsonObject()) continue;
                JsonObject metadata = catalogue.getAsJsonObject(id);
                result.add(new NativeBody(systemId, id, system, source, metadata,
                        number(source, number(metadata, 0.0, "x", "xpos"), "x", "xpos"),
                        number(source, number(metadata, 0.0, "y", "ypos"), "y", "ypos"),
                        number(source, number(metadata, 0.0, "z", "zpos"), "z", "zpos")));
            }
        }
        return result;
    }

    private static double advanceRotationClock(Level level, double gameTime,
                                               CosmicOrbitSettings.Config settings) {
        return CosmicMotionClock.rotationTicks(level, gameTime);
    }

    private static void applyAxialRotation(CompoundTag nativeBody, NativeBody body,
                                           double rotationTicks, CosmicOrbitSettings.Config settings) {
        double yaw = number(body.source, number(body.metadata, 0.0, "yaw"), "yaw");
        double pitch = number(body.source, number(body.metadata, 0.0, "pitch"), "pitch");
        double roll = number(body.source, number(body.metadata, 0.0, "roll"), "roll");

        CosmicOrbitSettings.BodyOverride override =
                settings.findBody(body.systemId, body.id);
        if (override == null) override = new CosmicOrbitSettings.BodyOverride();

        if (settings.enabled && settings.axialRotation && override.rotationEnabled) {
            double period = override.axialRotationPeriodTicks > 0.0
                    ? override.axialRotationPeriodTicks
                    : number(body.metadata, number(body.source, 0.0,
                    "axial_rotation_period_ticks", "rotation_period_ticks", "sidereal_period_ticks"),
                    "axial_rotation_period_ticks", "rotation_period_ticks", "sidereal_period_ticks");
            if (period <= 0.0) period = settings.defaultAxialRotationTicks;
            double yawSpeed = number(body.metadata, number(body.source, 0.0, "yaw_speed"), "yaw_speed");
            double pitchSpeed = number(body.metadata, number(body.source, 0.0, "pitch_speed"), "pitch_speed");
            double rollSpeed = number(body.metadata, number(body.source, 0.0, "roll_speed"), "roll_speed");
            yaw += yawSpeed != 0.0 ? yawSpeed * rotationTicks
                    : Math.PI * 2.0 * rotationTicks / period;
            pitch += pitchSpeed * rotationTicks;
            roll += rollSpeed * rotationTicks;
        }

        if (settings.enabled) {
            double tiltDegrees = override.useAxialTiltOverride
                    ? override.axialTiltDegrees
                    : number(body.metadata, number(body.source, 0.0,
                    "axial_tilt_degrees", "axial_tilt"), "axial_tilt_degrees", "axial_tilt");
            roll += Math.toRadians(tiltDegrees);
        }
        nativeBody.putDouble("yaw", yaw);
        nativeBody.putDouble("pitch", pitch);
        nativeBody.putDouble("roll", roll);
    }

    /** Native Cosmic Horizons position string format: `x~y|z\\`. */
    private static String encodePosition(double x, double y, double z) {
        return "`" + Double.toString(x) + "~" + Double.toString(y)
                + "|" + Double.toString(z) + "\\";
    }

    private static double number(JsonObject object, double fallback, String... keys) {
        if (object == null) return fallback;
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value != null && value.isJsonPrimitive()) {
                    double result = value.getAsDouble();
                    if (Double.isFinite(result)) return result;
                }
            } catch (RuntimeException ignored) { }
        }
        return fallback;
    }

    private static boolean sameSourceCoordinate(double x, double expectedX,
                                                 double y, double expectedY,
                                                 double z, double expectedZ) {
        return close(x, expectedX) && close(y, expectedY) && close(z, expectedZ);
    }

    private static boolean close(double actual, double expected) {
        double tolerance = Math.max(1.0E-4, Math.abs(expected) * 1.0E-8);
        return Double.isFinite(actual) && Double.isFinite(expected)
                && Math.abs(actual - expected) <= tolerance;
    }

    private static void reportMismatch(String message) {
        if (message.equals(lastMismatch)) return;
        System.err.println("[MAQAN] Cosmic Horizons native body-map mismatch; motion update skipped: " + message);
        lastMismatch = message;
    }

    private record NativeBody(String systemId, String id, JsonObject system,
                              JsonObject source, JsonObject metadata,
                              double baseX, double baseY, double baseZ) {
        String key() { return systemId + "/" + id; }
    }
}
