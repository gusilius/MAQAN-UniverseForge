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
 * then draws the concept's decorative layers in reference-viewport space.
 * It deliberately does not own world data, camera state, map state or actions.
 */
public final class MaqanUiPackRenderer {
    public static final int REFERENCE_WIDTH = 1368;
    public static final int REFERENCE_HEIGHT = 760;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> FRAME_LAYERS =
            List.of("mm", "bot", "top", "cor", "lcol", "rcol", "lp", "rp");
    private static final Map<String, String> TEXTURES = Map.of(
            "top", "top_bar.png",
            "lcol", "left_column.png",
            "lp", "window_left_blank.png",
            "rp", "window_right_blank.png",
            "rcol", "right_column.png",
            "mm", "minimap.png",
            "bot", "rts_console.png",
            "cor", "corner.png",
            "lw", "window_left_blank.png",
            "rw", "window_right_blank.png"
    );
    private static final List<String> REQUIRED_TEXTURES = List.of(
            "top_bar.png", "left_column.png", "right_column.png", "corner.png",
            "minimap.png", "rts_console.png", "window_state.png", "window_diplomacy.png",
            "window_left_blank.png", "window_right_blank.png"
    );
    private static final Map<String, int[][]> LAYOUTS = Map.of(
            "royal", new int[][]{
                    {0,0,1368,62}, {0,46,58,480}, {58,60,336,522},
                    {1028,60,1328,522}, {1326,46,1368,524}, {0,534,292,760},
                    {304,584,1150,760}, {0,0,136,94}
            },
            "iron", new int[][]{
                    {0,0,1368,62}, {0,48,63,470}, {63,110,290,532},
                    {1132,114,1334,530}, {1332,48,1368,526}, {0,544,306,760},
                    {312,586,1152,760}, {0,0,136,94}
            },
            "grim", new int[][]{
                    {0,0,1368,62}, {0,48,64,462}, {62,82,358,540},
                    {1006,82,1320,545}, {1316,48,1368,528}, {0,538,294,760},
                    {308,570,1192,760}, {0,0,136,82}
            },
            "elf", new int[][]{
                    {0,0,1368,62}, {0,50,62,525}, {62,60,330,556},
                    {1046,60,1334,566}, {1330,50,1368,532}, {0,544,290,760},
                    {308,590,1142,760}, {0,0,136,94}
            }
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
    private static String activeTheme = "royal";
    private static boolean availabilityLogged;
    private static boolean availabilityChecked;
    private static boolean assetsReady;

    private MaqanUiPackRenderer() {}

    public static void setTheme(String theme) {
        if (theme != null && LAYOUTS.containsKey(theme)) activeTheme = theme;
    }

    public static String activeTheme() {
        return activeTheme;
    }

    /**
     * Returns false until all 40 imported images exist. This prevents a
     * half-rendered frame or accidental use of Minecraft's missing texture.
     */
    public static boolean assetsAvailable() {
        if (availabilityChecked) return assetsReady;
        Minecraft minecraft = Minecraft.getInstance();
        for (String theme : LAYOUTS.keySet()) {
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
        double scale = Math.min(screenWidth / (double) REFERENCE_WIDTH,
                screenHeight / (double) REFERENCE_HEIGHT);
        int offsetX = (int) Math.round((screenWidth - REFERENCE_WIDTH * scale) / 2.0);
        int offsetY = (int) Math.round((screenHeight - REFERENCE_HEIGHT * scale) / 2.0);
        int[][] themeLayout = LAYOUTS.get(activeTheme);
        for (String key : FRAME_LAYERS) {
            int index = switch (key) {
                case "top" -> 0;
                case "lcol" -> 1;
                case "lp" -> 2;
                case "rp" -> 3;
                case "rcol" -> 4;
                case "mm" -> 5;
                case "bot" -> 6;
                case "cor" -> 7;
                default -> -1;
            };
            if (index < 0) continue;
            int[] rect = themeLayout[index];
            int x = offsetX + (int) Math.round(rect[0] * scale);
            int y = offsetY + (int) Math.round(rect[1] * scale);
            int width = Math.max(1, (int) Math.round((rect[2] - rect[0]) * scale));
            int height = Math.max(1, (int) Math.round((rect[3] - rect[1]) * scale));
            ResourceLocation texture = texture(activeTheme, TEXTURES.get(key));
            int[] sourceSize = SOURCE_SIZES.get(activeTheme).get(key);
            graphics.blit(texture, x, y, width, height,
                    0, 0, sourceSize[0], sourceSize[1], sourceSize[0], sourceSize[1]);
        }
    }

    private static ResourceLocation texture(String theme, String filename) {
        return new ResourceLocation("maqan", "textures/gui/" + theme + "/" + filename);
    }
}
