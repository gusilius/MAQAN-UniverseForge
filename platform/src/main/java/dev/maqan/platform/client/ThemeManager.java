package dev.maqan.platform.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * Loads the shipped MAQAN skin manifest and renders its PNG assets.
 * The manifest coordinates are authored in the 1368x760 reference viewport;
 * each destination is scaled to the live GUI dimensions.
 */
public final class ThemeManager {
    public static final int REFERENCE_WIDTH = 1368;
    public static final int REFERENCE_HEIGHT = 760;
    private static final String[] REQUIRED = {"royal", "iron", "grim", "elf"};
    private static final String[] LAYOUT_ELEMENTS = {"top", "lcol", "lp", "rp", "rcol", "mm", "bot", "cor"};
    private static final String[] CHROME = {"top", "lcol", "rcol", "mm", "bot", "cor"};
    private static final Map<String, Texture> TEXTURES = new LinkedHashMap<>();
    private static final Map<String, int[]> RECTANGLES = new LinkedHashMap<>();
    private static String selectedTheme = "royal";
    private static int sourceScale = 2;
    private static boolean initialized;
    private static boolean preferenceLoaded;
    private static boolean manifestValid;

    private record Texture(ResourceLocation location, int width, int height) { }

    private ThemeManager() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        Preferences prefs = Preferences.userNodeForPackage(PlatformShellScreen.class).node("workspace");
        if (!preferenceLoaded) {
            selectedTheme = normalize(prefs.get("theme", "royal"));
            preferenceLoaded = true;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Resource manifest = mc.getResourceManager()
                    .getResource(new ResourceLocation("maqan", "gui/layout.json"))
                    .orElseThrow(() -> new IllegalStateException("Missing assets/maqan/gui/layout.json"));
            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(manifest.open(), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonObject viewport = root.getAsJsonObject("referenceViewport");
            if (viewport == null || viewport.get("width").getAsInt() != REFERENCE_WIDTH
                    || viewport.get("height").getAsInt() != REFERENCE_HEIGHT)
                throw new IllegalStateException("Unexpected MAQAN reference viewport");
            sourceScale = root.has("sourceScale") ? Math.max(1, root.get("sourceScale").getAsInt()) : 1;
            JsonObject names = root.getAsJsonObject("textureNames");
            JsonObject skins = root.getAsJsonObject("skins");
            if (names == null || skins == null) throw new IllegalStateException("Incomplete MAQAN skin manifest");
            for (String theme : REQUIRED) {
                JsonObject skin = skins.getAsJsonObject(theme);
                if (skin == null) throw new IllegalStateException("Missing theme " + theme);
                for (String element : LAYOUT_ELEMENTS) {
                    JsonArray rect = skin.getAsJsonArray(element);
                    if (rect == null || rect.size() != 4) throw new IllegalStateException("Invalid " + theme + "/" + element + " layout");
                }
            }
            JsonObject activeSkin = skins.getAsJsonObject(selectedTheme);
            for (String element : CHROME) {
                JsonArray rect = activeSkin.getAsJsonArray(element);
                RECTANGLES.put(element, new int[] {
                        rect.get(0).getAsInt(), rect.get(1).getAsInt(),
                        rect.get(2).getAsInt(), rect.get(3).getAsInt()
                });
            }
            loadThemeTextures(names);
            manifestValid = true;
        } catch (Exception error) {
            manifestValid = false;
            TEXTURES.clear();
            RECTANGLES.clear();
            System.err.println("[MAQAN] Unable to load UI theme manifest; using vector fallback: " + error.getMessage());
        }
    }

    private static void loadThemeTextures(JsonObject names) throws Exception {
        TEXTURES.clear();
        String theme = selectedTheme;
        for (String element : CHROME) {
            String filename = names.get(element).getAsString();
            ResourceLocation location = new ResourceLocation("maqan",
                    "textures/gui/" + theme + "/" + filename);
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) throw new IllegalStateException("Missing theme texture " + location);
            int[] dimensions = pngDimensions(resource.get().open());
            TEXTURES.put(element, new Texture(location, dimensions[0], dimensions[1]));
        }
        // Window skins are not part of the fixed chrome rectangle set, but are
        // resolved through the same resource manager and theme selection.
        for (String filename : new String[] {
                "window_left_blank.png", "window_right_blank.png",
                "window_state.png", "window_diplomacy.png"
        }) {
            ResourceLocation location = new ResourceLocation("maqan",
                    "textures/gui/" + theme + "/" + filename);
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) throw new IllegalStateException("Missing theme texture " + location);
            int[] dimensions = pngDimensions(resource.get().open());
            TEXTURES.put(filename, new Texture(location, dimensions[0], dimensions[1]));
        }
    }

    private static int[] pngDimensions(InputStream stream) throws Exception {
        try (DataInputStream input = new DataInputStream(stream)) {
            byte[] signature = new byte[8];
            input.readFully(signature);
            if ((signature[0] & 255) != 137 || signature[1] != 80 || signature[2] != 78 || signature[3] != 71)
                throw new IllegalStateException("Invalid PNG header");
            input.readInt(); // IHDR chunk length
            if (input.readInt() != 0x49484452) throw new IllegalStateException("PNG has no IHDR first chunk");
            int width = input.readInt();
            int height = input.readInt();
            if (width < 1 || height < 1) throw new IllegalStateException("Invalid PNG dimensions");
            return new int[] {width, height};
        }
    }

    public static synchronized boolean setTheme(String theme) {
        String normalized = normalize(theme);
        if (!isSupported(normalized)) return false;
        if (normalized.equals(selectedTheme) && manifestValid) return true;
        String previous = selectedTheme;
        selectedTheme = normalized;
        Preferences.userNodeForPackage(PlatformShellScreen.class).node("workspace").put("theme", selectedTheme);
        initialized = false;
        initialize();
        if (!manifestValid) {
            selectedTheme = previous;
            Preferences.userNodeForPackage(PlatformShellScreen.class).node("workspace").put("theme", previous);
            initialized = false;
            initialize();
            return false;
        }
        return true;
    }

    public static synchronized String selectedTheme() { return selectedTheme; }
    public static String[] themes() { return REQUIRED.clone(); }
    public static boolean isSupported(String value) {
        for (String theme : REQUIRED) if (theme.equals(value)) return true;
        return false;
    }

    private static String normalize(String value) {
        String candidate = value == null ? "royal" : value.toLowerCase(Locale.ROOT).trim();
        return isSupported(candidate) ? candidate : "royal";
    }

    public static void renderChrome(GuiGraphics graphics, int width, int height) {
        initialize();
        if (!manifestValid || width <= 0 || height <= 0) return;
        float sx = width / (float) REFERENCE_WIDTH;
        float sy = height / (float) REFERENCE_HEIGHT;
        for (String element : CHROME) {
            int[] rect = RECTANGLES.get(element);
            Texture texture = TEXTURES.get(element);
            if (rect == null || texture == null) continue;
            int x = Math.round(rect[0] * sx);
            int y = Math.round(rect[1] * sy);
            int w = Math.max(1, Math.round((rect[2] - rect[0]) * sx));
            int h = Math.max(1, Math.round((rect[3] - rect[1]) * sy));
            blit(graphics, texture, x, y, w, h);
        }
    }

    public static void renderWindow(GuiGraphics graphics, int x, int y, int width, int height, String panelId) {
        initialize();
        if (!manifestValid || width <= 0 || height <= 0) return;
        String asset = switch (panelId) {
            case "navigation" -> "window_left_blank.png";
            case "inspector" -> "window_state.png";
            case "tools" -> "window_diplomacy.png";
            case "timeline" -> "window_right_blank.png";
            default -> "window_left_blank.png";
        };
        Texture texture = TEXTURES.get(asset);
        if (texture != null) blit(graphics, texture, x, y, width, height);
    }

    private static void blit(GuiGraphics graphics, Texture texture, int x, int y, int width, int height) {
        graphics.blit(texture.location(), x, y, 0.0F, 0.0F, width, height,
                texture.width(), texture.height());
    }
}
