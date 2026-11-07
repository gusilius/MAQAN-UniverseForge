package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.data.GalaxyCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-body parent, orbital and axial-spin controls for the in-world simulation. */
public final class CosmicBodyOrbitSettingsScreen extends Screen {
    private final Screen parentScreen;
    private final String systemId;
    private final String bodyId;
    private final String bodyName;
    private final List<String> parentIds = new ArrayList<>();
    private final List<String> parentLabels = new ArrayList<>();
    private final List<Button> visualButtons = new ArrayList<>();

    public CosmicBodyOrbitSettingsScreen(Screen parentScreen, String systemId,
                                         String bodyId, String bodyName) {
        super(Component.literal("MAQAN · Body Motion"));
        this.parentScreen = parentScreen;
        this.systemId = systemId;
        this.bodyId = bodyId;
        this.bodyName = bodyName == null || bodyName.isBlank() ? bodyId : bodyName;
        CosmicOrbitSettings.migrateLegacyOverrides(systemId, bodyId);
        parentIds.add("");
        parentLabels.add("AUTO");
        for (var entry : GalaxyCatalog.objects(systemId)) {
            if (entry.getKey().equals(bodyId)) continue;
            parentIds.add(entry.getKey());
            parentLabels.add(GalaxyCatalog.text(entry.getValue(), "name", entry.getKey()));
        }
    }

    @Override
    protected void init() {
        visualButtons.clear();
        CosmicOrbitSettings.BodyOverride body = CosmicOrbitSettings.get().body(systemId, bodyId);
        int columnWidth = Math.min(230, (width - 36) / 2);
        int gap = 10;
        int left = (width - columnWidth * 2 - gap) / 2;
        int right = left + columnWidth + gap;
        int y = Math.max(68, height / 2 - 104);
        int row = 27;

        addControl(left, y, columnWidth, "ORBIT: " + onOff(body.orbitEnabled),
                () -> body.orbitEnabled = !body.orbitEnabled);
        addControl(right, y, columnWidth, "AXIAL SPIN: " + onOff(body.rotationEnabled),
                () -> body.rotationEnabled = !body.rotationEnabled);
        y += row;

        addControl(left, y, columnWidth, "PARENT: " + parentLabel(body.parentId),
                () -> body.parentId = nextParent(body.parentId));
        addControl(right, y, columnWidth, "ORBIT PERIOD: " + period(body.orbitPeriodTicks),
                () -> body.orbitPeriodTicks = next(body.orbitPeriodTicks,
                        new double[]{0, 1200, 2400, 6000, 12000, 24000, 48000, 96000, 240000}));
        y += row;

        addControl(left, y, columnWidth, "ORBIT RADIUS: " + period(body.orbitRadius),
                () -> body.orbitRadius = next(body.orbitRadius,
                        new double[]{0, 1000, 2500, 5000, 10000, 25000, 50000, 100000}));
        addControl(right, y, columnWidth, "ROTATION PERIOD: " + period(body.axialRotationPeriodTicks),
                () -> body.axialRotationPeriodTicks = next(body.axialRotationPeriodTicks,
                        new double[]{0, 200, 600, 1200, 2400, 4800, 12000, 24000, 48000}));
        y += row;

        addControl(left, y, columnWidth, "PHASE: " + degrees(body.phaseDegrees),
                () -> { body.useOrbitOverrides = true; body.phaseDegrees = wrap(body.phaseDegrees + 15); });
        addControl(right, y, columnWidth, "INCLINATION: " + degrees(body.inclinationDegrees),
                () -> { body.useOrbitOverrides = true; body.inclinationDegrees = wrapSigned(body.inclinationDegrees + 5); });
        y += row;

        addControl(left, y, columnWidth, "ECCENTRICITY: " + String.format(Locale.ROOT, "%.2f", body.eccentricity),
                () -> { body.useOrbitOverrides = true; body.eccentricity = next(body.eccentricity,
                        new double[]{0, .01, .03, .05, .1, .2, .4, .7}); });
        addControl(right, y, columnWidth, "AXIAL TILT: " + degrees(body.axialTiltDegrees),
                () -> { body.useAxialTiltOverride = true; body.axialTiltDegrees = wrapSigned(body.axialTiltDegrees + 5); });
        y += row + 10;

        visualButtons.add(addRenderableWidget(Button.builder(Component.literal("RESET BODY OVERRIDES"), button -> {
            CosmicOrbitSettings.get().bodies.remove(systemId + "/" + bodyId);
            NavigatorPreferences.clearManualOrbitOverrides(systemId, bodyId);
            CosmicOrbitSettings.save();
            rebuild();
        }).bounds(width / 2 - 100, y, 200, 20).build()));
        y += 26;
        visualButtons.add(addRenderableWidget(Button.builder(Component.literal("SAVE & BACK"), button -> {
            CosmicOrbitSettings.save();
            onClose();
        }).bounds(width / 2 - 100, y, 200, 20).build()));
    }

    private void addControl(int x, int y, int w, String label, Runnable action) {
        visualButtons.add(addRenderableWidget(Button.builder(Component.literal(label), button -> {
            action.run();
            CosmicOrbitSettings.save();
            rebuild();
        }).bounds(x, y, w, 20).build()));
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private String nextParent(String current) {
        int index = parentIds.indexOf(current == null ? "" : current);
        return parentIds.get((index + 1) % parentIds.size());
    }

    private String parentLabel(String id) {
        int index = parentIds.indexOf(id == null ? "" : id);
        return index < 0 ? "CUSTOM" : parentLabels.get(index);
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static String period(double value) { return value <= 0 ? "AUTO" : String.format(Locale.ROOT, "%.0f", value); }
    private static String degrees(double value) { return String.format(Locale.ROOT, "%.0f°", value); }
    private static double next(double current, double[] values) {
        for (int i = 0; i < values.length; i++)
            if (Math.abs(values[i] - current) < Math.max(1.0E-8, Math.abs(current) * 1.0E-6))
                return values[(i + 1) % values.length];
        return values[0];
    }
    private static double wrap(double value) { return ((value % 360) + 360) % 360; }
    private static double wrapSigned(double value) {
        double wrapped = wrap(value);
        return wrapped > 180 ? wrapped - 360 : wrapped;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MaqanUiTheme.backdrop(graphics, width, height);
        int panelWidth = Math.min(500, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(60, height / 2 - 126);
        MaqanUiTheme.panel(graphics, left - 12, top - 18, left + panelWidth + 12,
                Math.min(height - 12, top + 244));
        MaqanUiTheme.title(graphics, font, "BODY MOTION", bodyName + "  ·  " + systemId, width);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (Button button : visualButtons) MaqanUiTheme.button(graphics, font, button);
    }

    @Override
    public void onClose() {
        CosmicOrbitSettings.save();
        if (minecraft != null) minecraft.setScreen(parentScreen);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
