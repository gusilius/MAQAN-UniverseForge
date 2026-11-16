package dev.maqan.platform.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Validated runtime view of the imported MAQAN UI skin manifest.
 * Coordinates remain in source reference space. The adaptive layout converts
 * them into screen-space rectangles; rendering and future hit regions must
 * consume those same calculated rectangles.
 */
public final class MaqanUiPackLayout {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation MANIFEST = new ResourceLocation("maqan", "gui/layout.json");
    private static MaqanUiPackLayout cached;

    private final int referenceWidth;
    private final int referenceHeight;
    private final Map<String, Map<String, int[]>> skins;
    private final Map<String, String> textureNames;
    private final Map<String, Map<String, int[]>> textureSizes;

    private MaqanUiPackLayout(int referenceWidth, int referenceHeight,
                              Map<String, Map<String, int[]>> skins,
                              Map<String, String> textureNames,
                              Map<String, Map<String, int[]>> textureSizes) {
        this.referenceWidth = referenceWidth;
        this.referenceHeight = referenceHeight;
        this.skins = Map.copyOf(skins);
        this.textureNames = Map.copyOf(textureNames);
        this.textureSizes = Map.copyOf(textureSizes);
    }

    public static Optional<MaqanUiPackLayout> load() {
        if (cached != null) return Optional.of(cached);
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(MANIFEST);
            if (resource.isEmpty()) throw new IllegalStateException("Missing layout manifest: " + MANIFEST);
            try (var reader = new InputStreamReader(resource.orElseThrow().open(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject viewport = root.getAsJsonObject("referenceViewport");
            int width = viewport.get("width").getAsInt();
            int height = viewport.get("height").getAsInt();
            if (width <= 0 || height <= 0) throw new IllegalStateException("Invalid reference viewport");

            JsonObject textureObject = root.getAsJsonObject("textureNames");
            Map<String, String> parsedTextures = new LinkedHashMap<>();
            for (String key : new String[]{"top", "lcol", "lp", "rp", "rcol", "mm", "bot", "cor", "lw", "rw"}) {
                if (textureObject == null || !textureObject.has(key))
                    throw new IllegalStateException("Missing texture mapping: " + key);
                parsedTextures.put(key, textureObject.get(key).getAsString());
            }
            if (parsedTextures.values().stream().anyMatch(name -> name == null || name.isBlank()))
                throw new IllegalStateException("Texture names must not be blank");

            JsonObject sizeObjects = root.getAsJsonObject("textureSizes");
            if (sizeObjects == null) throw new IllegalStateException("Missing textureSizes metadata");
            Map<String, Map<String, int[]>> parsedSizes = new LinkedHashMap<>();
            for (String theme : new String[]{"royal", "iron", "grim", "elf"}) {
                JsonObject themeSizes = sizeObjects.getAsJsonObject(theme);
                if (themeSizes == null) throw new IllegalStateException("Missing texture sizes for " + theme);
                Map<String, int[]> sizes = new LinkedHashMap<>();
                for (String layer : new String[]{"top", "lcol", "lp", "rp", "rcol", "mm", "bot", "cor"}) {
                    JsonArray values = themeSizes.getAsJsonArray(layer);
                    if (values == null || values.size() != 2) throw new IllegalStateException("Invalid texture size: " + theme + "/" + layer);
                    int sourceWidth = values.get(0).getAsInt();
                    int sourceHeight = values.get(1).getAsInt();
                    if (sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalStateException("Non-positive texture size: " + theme + "/" + layer);
                    sizes.put(layer, new int[]{sourceWidth, sourceHeight});
                }
                parsedSizes.put(theme, Map.copyOf(sizes));
            }

            JsonObject skinObjects = root.getAsJsonObject("skins");
            Map<String, Map<String, int[]>> parsedSkins = new LinkedHashMap<>();
            for (String theme : new String[]{"royal", "iron", "grim", "elf"}) {
                JsonObject skin = skinObjects.getAsJsonObject(theme);
                if (skin == null) throw new IllegalStateException("Missing UI theme: " + theme);
                Map<String, int[]> rectangles = new LinkedHashMap<>();
                for (String layer : new String[]{"top", "lcol", "lp", "rp", "rcol", "mm", "bot", "cor"}) {
                    JsonArray values = skin.getAsJsonArray(layer);
                    if (values == null || values.size() != 4)
                        throw new IllegalStateException("Invalid " + theme + "/" + layer + " rectangle");
                    int[] rect = new int[4];
                    for (int i = 0; i < 4; i++) rect[i] = values.get(i).getAsInt();
                    if (rect[0] < 0 || rect[1] < 0 || rect[2] <= rect[0] || rect[3] <= rect[1]
                            || rect[2] > width || rect[3] > height)
                        throw new IllegalStateException("Out-of-bounds " + theme + "/" + layer + " rectangle");
                    rectangles.put(layer, rect);
                }
                parsedSkins.put(theme, Map.copyOf(rectangles));
            }
            cached = new MaqanUiPackLayout(width, height, parsedSkins, parsedTextures, parsedSizes);
            LOGGER.info("Loaded MAQAN UI layout manifest: {}x{}, {} themes",
                    width, height, parsedSkins.size());
            return Optional.of(cached);
            }
        } catch (Exception error) {
            LOGGER.error("Could not load MAQAN UI layout manifest {}; using native shell fallback", MANIFEST, error);
            return Optional.empty();
        }
    }

    public int referenceWidth() { return referenceWidth; }
    public int referenceHeight() { return referenceHeight; }
    public boolean hasTheme(String theme) { return skins.containsKey(theme); }
    public Map<String, int[]> skin(String theme) { return skins.get(theme); }
    public Map<String, String> textureNames() { return textureNames; }
    public String textureName(String layer) { return textureNames.get(layer); }
    public int[] textureSize(String theme, String layer) {
        Map<String, int[]> sizes = textureSizes.get(theme);
        int[] size = sizes == null ? null : sizes.get(layer);
        return size == null ? null : size.clone();
    }

    public ViewportTransform transform(int screenWidth, int screenHeight) {
        double scale = Math.min(screenWidth / (double) referenceWidth,
                screenHeight / (double) referenceHeight);
        int offsetX = (int) Math.round((screenWidth - referenceWidth * scale) / 2.0);
        int offsetY = (int) Math.round((screenHeight - referenceHeight * scale) / 2.0);
        return new ViewportTransform(scale, offsetX, offsetY);
    }

    public record ViewportTransform(double scale, int offsetX, int offsetY) {
        public int screenX(double referenceX) { return offsetX + (int) Math.round(referenceX * scale); }
        public int screenY(double referenceY) { return offsetY + (int) Math.round(referenceY * scale); }
        public int screenWidth(double referenceWidth) { return Math.max(1, (int) Math.round(referenceWidth * scale)); }
        public int screenHeight(double referenceHeight) { return Math.max(1, (int) Math.round(referenceHeight * scale)); }
        public double referenceX(double screenX) { return (screenX - offsetX) / scale; }
        public double referenceY(double screenY) { return (screenY - offsetY) / scale; }
        public boolean contains(int x, int y, int[] rect) {
            double rx = referenceX(x), ry = referenceY(y);
            return rx >= rect[0] && rx < rect[2] && ry >= rect[1] && ry < rect[3];
        }
    }
}
