package dev.maqan.platform.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * The single owner of MAQAN interface skins. layout.json is the source of
 * truth for texture names and 1368x760 logical rectangles.
 */
public final class ThemeManager {
    public static final int REFERENCE_WIDTH = 1368;
    public static final int REFERENCE_HEIGHT = 760;
    private static final String[] THEMES = {"royal", "iron", "grim", "elf"};
    private static final String[] FRAME_ELEMENTS = {"top", "lcol", "rcol", "mm", "cor"};
    private static final String[] CHROME_ELEMENTS = {"top", "lcol", "rcol", "mm", "bot", "cor"};
    private static final String[] ALL_ELEMENTS = {"top", "lcol", "lp", "rp", "rcol", "mm", "bot", "cor", "lw", "rw"};
    private static final Map<String, Texture> TEXTURES = new LinkedHashMap<>();
    private static final Map<String, int[]> RECTANGLES = new LinkedHashMap<>();
    private static String selectedTheme = "royal";
    private static float sourceScale = 1.6F;
    private static int windowBorderPixels = 48;
    private static int selectedAccent = 0xFFD7B56D;
    private static boolean initialized;
    private static boolean preferenceLoaded;
    private static boolean manifestValid;

    private record Texture(ResourceLocation location, int width, int height) { }

    private ThemeManager() { }

    public static synchronized void initialize() {
        if (initialized) return;
        Preferences prefs = Preferences.userNodeForPackage(PlatformShellScreen.class).node("workspace");
        if (!preferenceLoaded) {
            selectedTheme = normalize(prefs.get("theme", "royal"));
            preferenceLoaded = true;
        }
        loadSelectedTheme();
    }

    private static void loadSelectedTheme() {
        initialized = true;
        manifestValid = false;
        TEXTURES.clear();
        RECTANGLES.clear();
        try {
            Minecraft mc = Minecraft.getInstance();
            Resource manifest = mc.getResourceManager()
                    .getResource(new ResourceLocation("maqan", "gui/layout.json"))
                    .orElseThrow(() -> new IllegalStateException("Missing assets/maqan/gui/layout.json"));
            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(manifest.open(), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }

            if (root.get("schemaVersion").getAsInt() != 2)
                throw new IllegalStateException("Unsupported MAQAN layout schema");
            JsonObject viewport = root.getAsJsonObject("referenceViewport");
            if (viewport == null || viewport.get("width").getAsInt() != REFERENCE_WIDTH
                    || viewport.get("height").getAsInt() != REFERENCE_HEIGHT)
                throw new IllegalStateException("Expected 1368x760 reference viewport");
            sourceScale = root.has("sourceScale") ? root.get("sourceScale").getAsFloat() : 1.6F;
            if (!Float.isFinite(sourceScale) || sourceScale < 0.5F || sourceScale > 4.0F)
                throw new IllegalStateException("Invalid texture sourceScale");
            if (root.has("windowBorderPixels")) {
                windowBorderPixels = root.get("windowBorderPixels").getAsInt();
                if (windowBorderPixels < 8 || windowBorderPixels > 160)
                    throw new IllegalStateException("Invalid nine-slice border");
            }

            String defaultTheme = root.has("defaultTheme") ? root.get("defaultTheme").getAsString() : "royal";
            if (!isSupported(selectedTheme)) selectedTheme = normalize(defaultTheme);
            JsonObject names = root.getAsJsonObject("textureNames");
            JsonObject skins = root.getAsJsonObject("skins");
            if (names == null || skins == null) throw new IllegalStateException("Incomplete layout manifest");

            for (String element : ALL_ELEMENTS) {
                if (!names.has(element)) throw new IllegalStateException("Missing texture name for " + element);
            }
            for (String theme : THEMES) {
                JsonObject skin = skins.getAsJsonObject(theme);
                if (skin == null) throw new IllegalStateException("Missing theme layout " + theme);
                for (String element : CHROME_ELEMENTS) validateRectangle(skin, theme, element);
            }

            JsonObject activeSkin = skins.getAsJsonObject(selectedTheme);
            selectedAccent = activeSkin.has("accent")
                    ? parseAccent(activeSkin.get("accent").getAsString()) : 0xFFD7B56D;
            for (String element : CHROME_ELEMENTS) RECTANGLES.put(element, rectangle(activeSkin, element));
            loadAllTextures(mc, names, selectedTheme);
            manifestValid = true;
        } catch (Exception error) {
            TEXTURES.clear();
            RECTANGLES.clear();
            System.err.println("[MAQAN] Theme loading failed; retaining safe UI fallback: " + error.getMessage());
        }
    }

    private static void validateRectangle(JsonObject skin, String theme, String element) {
        JsonArray rect = skin.getAsJsonArray(element);
        if (rect == null || rect.size() != 4) throw new IllegalStateException("Invalid " + theme + "/" + element + " rectangle");
        int x1 = rect.get(0).getAsInt(), y1 = rect.get(1).getAsInt();
        int x2 = rect.get(2).getAsInt(), y2 = rect.get(3).getAsInt();
        if (x1 < 0 || y1 < 0 || x2 <= x1 || y2 <= y1 || x2 > REFERENCE_WIDTH || y2 > REFERENCE_HEIGHT)
            throw new IllegalStateException("Out-of-range " + theme + "/" + element + " rectangle");
    }

    private static int[] rectangle(JsonObject skin, String element) {
        JsonArray rect = skin.getAsJsonArray(element);
        return new int[] {rect.get(0).getAsInt(), rect.get(1).getAsInt(),
                rect.get(2).getAsInt(), rect.get(3).getAsInt()};
    }

    private static void loadAllTextures(Minecraft mc, JsonObject names, String theme) throws Exception {
        for (String element : ALL_ELEMENTS) {
            String filename = names.get(element).getAsString();
            ResourceLocation location = new ResourceLocation("maqan",
                    "textures/gui/" + theme + "/" + filename);
            Optional<Resource> resource = mc.getResourceManager().getResource(location);
            if (resource.isEmpty()) throw new IllegalStateException("Missing theme texture " + location);
            int[] dimensions = pngDimensions(resource.get().open());
            TEXTURES.put(element, new Texture(location, dimensions[0], dimensions[1]));
        }
    }

    private static int[] pngDimensions(InputStream stream) throws Exception {
        try (DataInputStream input = new DataInputStream(stream)) {
            byte[] signature = new byte[8];
            input.readFully(signature);
            if ((signature[0] & 255) != 137 || signature[1] != 80 || signature[2] != 78 || signature[3] != 71)
                throw new IllegalStateException("Invalid PNG signature");
            input.readInt();
            if (input.readInt() != 0x49484452) throw new IllegalStateException("PNG IHDR chunk missing");
            int width = input.readInt();
            int height = input.readInt();
            if (width <= 0 || height <= 0) throw new IllegalStateException("Invalid PNG dimensions");
            return new int[] {width, height};
        }
    }

    public static synchronized boolean setTheme(String theme) {
        initialize();
        String normalized = normalize(theme);
        if (!isSupported(theme == null ? "" : theme.toLowerCase(Locale.ROOT).trim())) return false;
        if (normalized.equals(selectedTheme) && manifestValid) return true;

        String previous = selectedTheme;
        selectedTheme = normalized;
        initialized = false;
        loadSelectedTheme();
        if (!manifestValid) {
            selectedTheme = previous;
            initialized = false;
            loadSelectedTheme();
            return false;
        }
        Preferences.userNodeForPackage(PlatformShellScreen.class).node("workspace")
                .put("theme", selectedTheme);
        return true;
    }

    public static synchronized String selectedTheme() {
        initialize();
        return selectedTheme;
    }

    /** Theme-owned accent loaded from the same skin manifest as its textures. */
    public static synchronized int accentColor() {
        initialize();
        return selectedAccent;
    }

    private static int parseAccent(String value) {
        if (value == null) throw new IllegalStateException("Missing theme accent");
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) throw new IllegalStateException("Theme accent must be #RRGGBB");
        try { return 0xFF000000 | Integer.parseInt(hex, 16); }
        catch (NumberFormatException error) { throw new IllegalStateException("Invalid theme accent", error); }
    }

    public static String[] themes() { return THEMES.clone(); }

    public static boolean isSupported(String value) {
        if (value == null) return false;
        for (String theme : THEMES) if (theme.equals(value)) return true;
        return false;
    }

    public static synchronized boolean isThemeLoaded() {
        initialize();
        return manifestValid && TEXTURES.size() == ALL_ELEMENTS.length;
    }

    private static String normalize(String value) {
        String candidate = value == null ? "royal" : value.toLowerCase(Locale.ROOT).trim();
        return isSupported(candidate) ? candidate : "royal";
    }

    public static synchronized void renderChrome(GuiGraphics graphics, int width, int height) {
        renderChrome(graphics, width, height, true);
    }

    /** Render the shared frame; the RTS console is intentionally absent outside command mode. */
    public static synchronized void renderChrome(GuiGraphics graphics, int width, int height,
                                                   boolean showRtsConsole) {
        initialize();
        if (!manifestValid || width <= 0 || height <= 0) return;
        float sx = width / (float) REFERENCE_WIDTH;
        float sy = height / (float) REFERENCE_HEIGHT;
        for (String element : FRAME_ELEMENTS) {
            drawChromeElement(graphics, element, sx, sy);
        }
        if (showRtsConsole) drawChromeElement(graphics, "bot", sx, sy);
    }

    private static void drawChromeElement(GuiGraphics graphics, String element, float sx, float sy) {
        int[] rect = RECTANGLES.get(element);
        Texture texture = TEXTURES.get(element);
        if (rect == null || texture == null) return;
        int x = Math.round(rect[0] * sx);
        int y = Math.round(rect[1] * sy);
        int w = Math.max(1, Math.round((rect[2] - rect[0]) * sx));
        int h = Math.max(1, Math.round((rect[3] - rect[1]) * sy));
        drawScaled(graphics, texture, x, y, w, h);
    }

    public static synchronized void renderWindow(GuiGraphics graphics, int x, int y,
                                                   int width, int height, String panelId) {
        initialize();
        if (!manifestValid || width <= 0 || height <= 0) return;
        // Blank frames are decorative only; live text and controls are rendered by the host.
        String element = ("inspector".equals(panelId) || "tools".equals(panelId)) ? "rw" : "lw";
        Texture texture = TEXTURES.get(element);
        if (texture != null) drawNineSlice(graphics, texture, x, y, width, height, windowBorderPixels);
    }

    /**
     * Preserve ornate source corners while stretching only the edges and center.
     * Source border is converted using the scale declared by the actual asset pack.
     */
    private static void drawNineSlice(GuiGraphics graphics, Texture texture,
                                      int x, int y, int width, int height, int requestedBorder) {
        int border = Math.max(1, Math.min(requestedBorder,
                Math.min(texture.width(), texture.height()) / 3));
        int logicalBorder = Math.max(1, Math.round(border / sourceScale));
        int targetX = Math.min(width / 2, logicalBorder);
        int targetY = Math.min(height / 2, logicalBorder);
        int[] sx = {0, border, texture.width() - border, texture.width()};
        int[] sy = {0, border, texture.height() - border, texture.height()};
        int[] dx = {x, x + targetX, x + width - targetX, x + width};
        int[] dy = {y, y + targetY, y + height - targetY, y + height};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int sw = sx[col + 1] - sx[col], sh = sy[row + 1] - sy[row];
                int dw = dx[col + 1] - dx[col], dh = dy[row + 1] - dy[row];
                if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) continue;
                graphics.pose().pushPose();
                graphics.pose().translate(dx[col], dy[row], 0.0D);
                graphics.pose().scale(dw / (float) sw, dh / (float) sh, 1.0F);
                graphics.blit(texture.location(), 0, 0, (float) sx[col], (float) sy[row],
                        sw, sh, texture.width(), texture.height());
                graphics.pose().popPose();
            }
        }
    }

    private static void drawScaled(GuiGraphics graphics, Texture texture,
                                   int x, int y, int width, int height) {
        // Fit the complete texture into its manifest-defined logical rectangle.
        // Transparent margins in the source PNG remain part of the authored skin.
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0D);
        graphics.pose().scale(width / (float) texture.width(),
                height / (float) texture.height(), 1.0F);
        graphics.blit(texture.location(), 0, 0, 0.0F, 0.0F,
                texture.width(), texture.height(), texture.width(), texture.height());
        graphics.pose().popPose();
    }
}
