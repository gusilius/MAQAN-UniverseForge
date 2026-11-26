package com.maqan.galacticnavigator.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** In-game controls for the Cosmic Horizons native celestial-motion integration. */
public final class CosmicOrbitSettingsScreen extends Screen {
    private final Screen parent;
    private final List<Button> controls = new ArrayList<>();

    public CosmicOrbitSettingsScreen(Screen parent) {
        super(Component.literal("MAQAN · Cosmic Motion"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        controls.clear();
        CosmicOrbitSettings.Config config = CosmicOrbitSettings.get();
        int columnWidth = Math.min(220, (width - 36) / 2);
        int gap = 10;
        int left = (width - columnWidth * 2 - gap) / 2;
        int right = left + columnWidth + gap;
        int y = Math.max(62, height / 2 - 84);
        int row = 28;

        addControl(left, y, columnWidth, () -> "NATIVE CELESTIAL MOTION: " + onOff(config.enabled),
                () -> config.enabled = !config.enabled);
        addControl(right, y, columnWidth, () -> "ORBITAL REVOLUTION: " + onOff(config.orbitalMotion),
                () -> config.orbitalMotion = !config.orbitalMotion);
        y += row;
        addControl(left, y, columnWidth, () -> "AXIAL ROTATION: " + onOff(config.axialRotation),
                () -> config.axialRotation = !config.axialRotation);
        addControl(right, y, columnWidth, () -> "SIMULATION SPEED: " + format(config.simulationSpeed) + "x",
                () -> config.simulationSpeed = next(config.simulationSpeed,
                        new double[]{0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 10.0}));
        y += row;
        addControl(left, y, columnWidth, () -> "PLANET ORBIT: " + format(config.defaultPlanetOrbitTicks) + " ticks",
                () -> config.defaultPlanetOrbitTicks = next(config.defaultPlanetOrbitTicks,
                        new double[]{6000, 12000, 24000, 48000, 96000, 240000}));
        addControl(right, y, columnWidth, () -> "MOON ORBIT: " + format(config.defaultMoonOrbitTicks) + " ticks",
                () -> config.defaultMoonOrbitTicks = next(config.defaultMoonOrbitTicks,
                        new double[]{600, 1200, 2400, 4800, 9600, 24000}));
        y += row;
        addControl(left, y, columnWidth, () -> "AXIAL PERIOD: " + format(config.defaultAxialRotationTicks) + " ticks",
                () -> config.defaultAxialRotationTicks = next(config.defaultAxialRotationTicks,
                        new double[]{200, 600, 1200, 2400, 4800, 12000, 24000}));
        addControl(right, y, columnWidth, () -> "RESET DEFAULTS",
                CosmicOrbitSettings::resetDefaults);
        y += row + 8;
        controls.add(addRenderableWidget(Button.builder(Component.literal("SAVE & BACK"), button -> {
            CosmicOrbitSettings.save();
            onClose();
        }).bounds(width / 2 - 100, y, 200, 20).build()));
    }

    private void addControl(int x, int y, int width, Supplier<String> label, Runnable action) {
        Button button = addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
            action.run();
            CosmicOrbitSettings.save();
            rebuildControls();
        }).bounds(x, y, width, 20).build());
        controls.add(button);
    }

    private void rebuildControls() {
        clearWidgets();
        init();
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static String format(double value) {
        if (value >= 1000 || value == Math.rint(value))
            return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double next(double current, double[] values) {
        for (int i = 0; i < values.length; i++)
            if (Math.abs(values[i] - current) < Math.max(1.0E-9, Math.abs(current) * 1.0E-6))
                return values[(i + 1) % values.length];
        return values[0];
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MaqanUiTheme.backdrop(graphics, width, height);
        int panelWidth = Math.min(480, width - 24);
        int left = (width - panelWidth) / 2;
        int top = Math.max(58, height / 2 - 104);
        MaqanUiTheme.panel(graphics, left - 12, top - 18, left + panelWidth + 12,
                Math.min(height - 12, top + 190));
        MaqanUiTheme.title(graphics, font, "COSMIC MOTION", "Simulation controls · native Cosmic Horizons renderer", width);
        super.render(graphics, mouseX, mouseY, partialTick);
        for (Button button : controls) MaqanUiTheme.button(graphics, font, button);
    }

    @Override
    public void onClose() {
        CosmicOrbitSettings.save();
        if (minecraft != null) MaqanScreenHost.back(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
