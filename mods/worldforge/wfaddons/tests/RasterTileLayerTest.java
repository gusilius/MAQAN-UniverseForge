package dev.worldforge.tests;

import dev.worldforge.mod.MapData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Exercises lazy PNG tile decoding across tile edges and all supported core raster types. */
public final class RasterTileLayerTest {
    public static void main(String[] args) throws Exception {
        int width = 513, height = 259;
        BufferedImage heights = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        BufferedImage biomes = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage rivers = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage wilderness = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                heights.getRaster().setSample(x, z, 0, (x * 97 + z * 193) & 0xffff);
                biomes.getRaster().setSample(x, z, 0, (x / 256 + z / 256) & 1);
                rivers.getRaster().setSample(x, z, 0, (x == 256 && z == 256) ? 255 : 0);
                wilderness.getRaster().setSample(x, z, 0, (x == 512 && z == 258) ? 255 : 0);
            }
        }
        Map<String, byte[]> files = new HashMap<>();
        files.put("meta.json", ("{\"size_px\":[" + width + "," + height + "],"
                + "\"blocks_per_pixel\":16,\"sea_level\":63,\"min_y\":-64,\"max_y\":320,"
                + "\"biomes\":[{\"index\":0,\"key\":\"plains\",\"color\":\"#8db360\","
                + "\"minecraft_id\":\"minecraft:plains\"},"
                + "{\"index\":1,\"key\":\"forest\",\"color\":\"#228833\","
                + "\"minecraft_id\":\"minecraft:forest\"}],\"spawn\":[0.5,0.5]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("height16.png", png(heights));
        files.put("biomes.png", png(biomes));
        files.put("rivers.png", png(rivers));
        files.put("wilderness.png", png(wilderness));
        MapData map = MapData.load("tile-raster-test", files::get);
        check(map.rawHeightPx(255, 255) == ((255 * 97 + 255 * 193) & 0xffff), "tile 0 edge sample");
        check(map.rawHeightPx(256, 256) == ((256 * 97 + 256 * 193) & 0xffff), "tile 1 edge sample");
        check(map.rawHeightPx(512, 258) == ((512 * 97 + 258 * 193) & 0xffff), "partial last tile sample");
        check(map.biomeAtPx(255, 255) == 0, "first biome tile");
        check(map.biomeAtPx(256, 256) == 0, "second biome tile index");
        check(map.biomeAtPx(512, 258) == 1, "last biome tile");
        check(map.riverAtPx(256, 256) == 255, "river layer tile sample");
        check(map.isWildernessPx(512, 258), "wilderness layer tile sample");
        check(map.hasRiverLayer() && map.hasWildernessLayer(), "optional raster layers remain available");
        System.out.println("RasterTileLayerTest: PASS");
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new AssertionError("PNG writer unavailable");
        return output.toByteArray();
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
