package dev.worldforge.mod.client;

import dev.worldforge.mod.MapSync;
import dev.worldforge.mod.PoliticalMapBootstrap;
import dev.worldforge.mod.TitleData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.resources.ResourceLocation;

/**
 * Builds a bounded political/geographical overview from chunks already loaded by
 * the client when the active dimension has no WorldForge map package. It never
 * requests or force-loads chunks; the map refreshes as the player moves.
 */
public final class ClientWorldMapBootstrap {
    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;
    private static final double BLOCKS_PER_PIXEL = 2.0;
    private static String dimension = "";
    private static int centerX = Integer.MIN_VALUE;
    private static int centerZ = Integer.MIN_VALUE;

    private ClientWorldMapBootstrap() {}

    public static void ensure() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (ClientState.state != null && !ClientState.localWorldSnapshot) return;

        String currentDimension = mc.level.dimension().location().toString();
        int playerX = mc.player.blockPosition().getX();
        int playerZ = mc.player.blockPosition().getZ();
        int anchorX = Math.floorDiv(playerX, 64);
        int anchorZ = Math.floorDiv(playerZ, 64);
        if (ClientState.localWorldSnapshot && currentDimension.equals(dimension)
                && anchorX == centerX && anchorZ == centerZ) return;

        int originX = anchorX * 64;
        int originZ = anchorZ * 64;
        int[] terrain = new int[WIDTH * HEIGHT];
        boolean[] land = new boolean[WIDTH * HEIGHT];
        java.util.Arrays.fill(terrain, 0xFF182B40);
        for (int z = 0; z < HEIGHT; z++) {
            for (int x = 0; x < WIDTH; x++) {
                int worldX = originX + (int) Math.round((x + 0.5 - WIDTH * 0.5) * BLOCKS_PER_PIXEL);
                int worldZ = originZ + (int) Math.round((z + 0.5 - HEIGHT * 0.5) * BLOCKS_PER_PIXEL);
                BlockPos probe = new BlockPos(worldX, mc.level.getMinBuildHeight(), worldZ);
                if (!mc.level.hasChunkAt(probe)) continue;

                int surfaceY = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                BlockPos surface = new BlockPos(worldX, Math.max(mc.level.getMinBuildHeight(), surfaceY - 1), worldZ);
                FluidState fluid = mc.level.getFluidState(surface);
                ResourceLocation biomeKey = mc.level.getBiome(surface).unwrapKey()
                        .map(key -> key.location()).orElse(null);
                String biome = biomeKey == null ? "" : biomeKey.getPath();
                boolean oceanBiome = biome.contains("ocean") || biome.equals("river");
                boolean isLand = !fluid.is(FluidTags.WATER) && !oceanBiome
                        && surfaceY > mc.level.getSeaLevel();
                int color;
                if (!isLand) color = biome.contains("frozen") ? 0xFF9DBBCB : 0xFF315E83;
                else if (surfaceY > mc.level.getSeaLevel() + 80) color = 0xFF8D8A83;
                else if (surfaceY > mc.level.getSeaLevel() + 42) color = 0xFF9C987B;
                else if (biome.contains("desert") || biome.contains("badlands") || biome.contains("beach")) color = 0xFFC6AD72;
                else if (biome.contains("forest") || biome.contains("jungle") || biome.contains("taiga")) color = 0xFF47744C;
                else if (biome.contains("snow") || biome.contains("ice")) color = 0xFFCBD5D6;
                else color = 0xFF8EA66B;
                terrain[z * WIDTH + x] = color;
                land[z * WIDTH + x] = isLand;
            }
        }

        MapSync.State snapshot = new MapSync.State();
        snapshot.name = "local:" + currentDimension;
        snapshot.version = 1;
        snapshot.w = WIDTH;
        snapshot.h = HEIGHT;
        snapshot.bpp = BLOCKS_PER_PIXEL;
        snapshot.seaLevel = mc.level.getSeaLevel();
        snapshot.minY = mc.level.getMinBuildHeight();
        snapshot.maxY = mc.level.getMaxBuildHeight();
        snapshot.titles = PoliticalMapBootstrap.generate(WIDTH, HEIGHT, BLOCKS_PER_PIXEL, land);

        ClientState.state = snapshot;
        ClientState.terrain = terrain;
        ClientState.terrainHash = "";
        ClientState.markers = new dev.worldforge.mod.Markers();
        ClientState.actions = java.util.List.of();
        ClientState.localWorldSnapshot = true;
        ClientState.localWorldOriginX = originX;
        ClientState.localWorldOriginZ = originZ;
        ClientState.revision++;
        dimension = currentDimension;
        centerX = anchorX;
        centerZ = anchorZ;
    }
}
