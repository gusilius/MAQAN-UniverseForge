package dev.worldforge.mod;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Какие блоки лежат сверху в каком биоме (по ключам биомов карты). */
final class SurfaceBlocks {
    private SurfaceBlocks() {}

    static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    static final BlockState SAND = Blocks.SAND.defaultBlockState();
    static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
    static final BlockState MUD = Blocks.MUD.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
    static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    static final BlockState WATER = Blocks.WATER.defaultBlockState();
    static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState RED_SAND = Blocks.RED_SAND.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();
    private static final BlockState[] BANDS = {
            Blocks.TERRACOTTA.defaultBlockState(), Blocks.ORANGE_TERRACOTTA.defaultBlockState(),
            Blocks.TERRACOTTA.defaultBlockState(), Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.RED_TERRACOTTA.defaultBlockState(), Blocks.WHITE_TERRACOTTA.defaultBlockState(),
            Blocks.BROWN_TERRACOTTA.defaultBlockState()};

    static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }

    static int hash3(int x, int y, int z) {
        int h = x * 374761393 + y * 1103515245 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }

    /** Верхний блок на суше. */
    static BlockState top(String key, int h) {
        switch (key) {
            case "desert": case "beach": return SAND;
            case "badlands": return RED_SAND;
            case "oasis": return h % 5 == 0 ? SAND : GRASS;
            case "mountains": return h % 6 == 0 ? GRAVEL : STONE;
            case "snowy_mountains": case "peaks": case "glacier": return SNOW_BLOCK;
            case "volcanic_wasteland": return BLACKSTONE;
            case "wasteland": return COARSE_DIRT;
            case "savanna": return h % 10 == 0 ? COARSE_DIRT : GRASS;
            case "taiga": case "snowy_taiga": return h % 8 == 0 ? PODZOL : GRASS;
            case "mangrove": return MUD;
            case "marsh": return h % 4 == 0 ? MUD : GRASS;
            case "moor": return h % 7 == 0 ? COARSE_DIRT : GRASS;
            default: return GRASS;
        }
    }

    /** Сколько слоёв под верхним блоком не камень. */
    static int fillerDepth(String key) {
        switch (key) {
            case "mountains": case "peaks": case "snowy_mountains": return 1;
            case "badlands": return 14;
            case "desert": case "beach": return 5;
            case "glacier": return 3;
            default: return 4;
        }
    }

    /** Слой под верхним блоком на суше; depth = 1 для первого слоя под поверхностью. */
    static BlockState filler(String key, int depth) {
        switch (key) {
            case "desert": case "beach": return depth > 3 ? SANDSTONE : SAND;
            case "badlands": return BANDS[Math.floorMod(depth * 3 + 1, BANDS.length)];
            case "mountains": case "peaks": case "snowy_mountains": return STONE;
            case "glacier": return PACKED_ICE;
            case "volcanic_wasteland": return BLACKSTONE;
            case "mangrove": return MUD;
            default: return DIRT;
        }
    }

    /** Дно под водой: пески у берега, гравий глубже, иногда глина; в болотах ил. */
    static BlockState underwater(String key, int depthBelowSea, int h) {
        if (key.equals("mangrove") || key.equals("swamp") || key.equals("marsh")) return MUD;
        if (h % 9 == 0) return CLAY;
        if (key.equals("river") || key.equals("shallow_sea") || key.equals("beach") || depthBelowSea <= 3) {
            return h % 3 == 0 ? GRAVEL : SAND;
        }
        return GRAVEL;
    }
}
