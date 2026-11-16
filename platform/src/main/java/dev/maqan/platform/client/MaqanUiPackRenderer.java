package dev.maqan.platform.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Native Minecraft renderer for the supplied MAQAN concept frame.
 *
 * The game/provider view is rendered first by PlatformShellScreen. This class
 * then draws the concept's decorative layers using responsive screen-space
 * rectangles derived from the imported per-theme reference geometry.
 * It deliberately does not own world data, camera state, map state or actions.
 */
public final class MaqanUiPackRenderer {
    public static final int REFERENCE_WIDTH = 1368;
    public static final int REFERENCE_HEIGHT = 760;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> FRAME_LAYERS =
            List.of("mm", "bot", "top", "cor", "lcol", "rcol", "lp", "rp");
    private static final List<String> REQUIRED_TEXTURES = List.of(
            "top_bar.png", "left_column.png", "right_column.png", "corner.png",
            "minimap.png", "rts_console.png", "window_state.png", "window_diplomacy.png",
            "window_left_blank.png", "window_right_blank.png"
    );
    private static final Map<String, Map<String, int[]>> SOURCE_SIZES = Map.of(
            "royal", Map.of("top", new int[]{2188,99}, "lcol", new int[]{92,694},
                    "lp", new int[]{444,739}, "rp", new int[]{480,739}, "rcol", new int[]{67,764},
                    "mm", new int[]{467,361}, "bot", new int[]{1353,281}, "cor", new int[]{217,150}),
            "iron", Map.of("top", new int[]{2188,99}, "lcol", new int[]{100,675},
                    "lp", new int[]{363,675}, "rp", new int[]{323,665}, "rcol", new int[]{57,764},
                    "mm", new int[]{489,345}, "bot", new int[]{1344,278}, "cor", new int[]{217,150}),
            "grim", Map.of("top", new int[]{2188,99}, "lcol", new int[]{102,662},
                    "lp", new int[]{473,732}, "rp", new int[]{502,740}, "rcol", new int[]{83,768},
                    "mm", new int[]{470,355}, "bot", new int[]{1414,304}, "cor", new int[]{217,131}),
            "elf", Map.of("top", new int[]{2188,99}, "lcol", new int[]{99,760},
                    "lp", new int[]{428,793}, "rp", new int[]{460,809}, "rcol", new int[]{60,771},
                    "mm", new int[]{464,345}, "bot", new int[]{1334,272}, "cor", new int[]{217,150})
    );
    private static final java.util.prefs.Preferences THEME_PREFS =
            java.util.prefs.Preferences.userNodeForPackage(MaqanUiPackRenderer.class).node("ui-pack");
    private static String activeTheme = THEME_PREFS.get("theme", "royal");
    private static MaqanUiPackLayout loadedLayout;
    private static boolean availabilityLogged;
    private static boolean availabilityChecked;
    private static boolean assetsReady;

    private MaqanUiPackRenderer() {}

    public static void setTheme(String theme) {
        if (theme == null || !MaqanUiPackLayout.load().map(layout -> layout.hasTheme(theme)).orElse(false)) return;
        activeTheme = theme;
        try { THEME_PREFS.put("theme", theme); THEME_PREFS.flush(); }
        catch (java.util.prefs.BackingStoreException error) {
            LOGGER.warn("Could not persist MAQAN UI theme {}", theme, error);
        }
    }

    public static String activeTheme() {
        return activeTheme;
    }

    public static int topBarHeight(int screenWidth, int screenHeight) {
        if (!assetsAvailable()) return 34;
        MaqanUiPackAdaptiveLayout.Rect rect = MaqanUiPackAdaptiveLayout
                .calculate(screenWidth, screenHeight, loadedLayout.skin(activeTheme)).get("top");
        return rect == null ? 34 : rect.height();
    }

    public static int compactDockTop(int screenWidth, int screenHeight) {
        if (!assetsAvailable()) return screenHeight;
        MaqanUiPackAdaptiveLayout.Rect rect = MaqanUiPackAdaptiveLayout
                .calculate(screenWidth, screenHeight, loadedLayout.skin(activeTheme)).get("mm");
        return rect == null ? screenHeight : rect.y();
    }

    public static String cycleTheme() {
        List<String> themes = List.of("royal", "iron", "grim", "elf");
        int index = themes.indexOf(activeTheme);
        String next = themes.get((index + 1 + themes.size()) % themes.size());
        setTheme(next);
        return activeTheme;
    }

    /**
     * Returns false until all 40 imported images exist. This prevents a
     * half-rendered frame or accidental use of Minecraft's missing texture.
     */
    public static boolean assetsAvailable() {
        if (availabilityChecked) return assetsReady;
        Minecraft minecraft = Minecraft.getInstance();
        loadedLayout = MaqanUiPackLayout.load().orElse(null);
        if (loadedLayout == null) {
            availabilityChecked = true;
            assetsReady = false;
            return false;
        }
        if (!loadedLayout.hasTheme(activeTheme)) activeTheme = "royal";
        for (String theme : List.of("royal", "iron", "grim", "elf")) {
            for (String filename : REQUIRED_TEXTURES) {
                ResourceLocation location = texture(theme, filename);
                if (minecraft.getResourceManager().getResource(location).isEmpty()) {
                    if (!availabilityLogged) {
                        LOGGER.info("MAQAN UI Pack is not active yet: missing {}", location);
                        availabilityLogged = true;
                    }
                    availabilityChecked = true;
                    assetsReady = false;
                    return false;
                }
            }
        }
        availabilityChecked = true;
        assetsReady = true;
        return true;
    }

    public static void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0 || !assetsAvailable()) return;
        Map<String, int[]> themeLayout = loadedLayout.skin(activeTheme);
        MaqanUiPackAdaptiveLayout.Result adaptive =
                MaqanUiPackAdaptiveLayout.calculate(screenWidth, screenHeight, themeLayout);
        for (String key : FRAME_LAYERS) {
            // Compact layouts replace the side windows with the shell's drawer
            // model; drawing their desktop frames here would cover the map.
            if (adaptive.compact() && (key.equals("lp") || key.equals("rp") || key.equals("cor"))) continue;
            MaqanUiPackAdaptiveLayout.Rect rect = adaptive.get(key);
            if (rect == null || rect.width() <= 0 || rect.height() <= 0) continue;
            ResourceLocation texture = texture(activeTheme, loadedLayout.textureName(key));
            int[] sourceSize = SOURCE_SIZES.get(activeTheme).get(key);
            graphics.blit(texture, rect.x(), rect.y(), rect.width(), rect.height(),
                    0, 0, sourceSize[0], sourceSize[1], sourceSize[0], sourceSize[1]);
        }
    }

    private static ResourceLocation texture(String theme, String filename) {
        return new ResourceLocation("maqan", "textures/gui/" + theme + "/" + filename);
    }
}
