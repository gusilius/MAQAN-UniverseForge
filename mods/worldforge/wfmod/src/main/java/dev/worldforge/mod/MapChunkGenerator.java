package dev.worldforge.mod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Генератор чанков: рельеф берётся из height16.png, биомы из biomes.png, реки из rivers.png. */
public class MapChunkGenerator extends ChunkGenerator {
    public static final Codec<MapChunkGenerator> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("map").forGetter(g -> g.mapName),
            Codec.BOOL.optionalFieldOf("caves", true).forGetter(g -> g.caves),
            Codec.FLOAT.optionalFieldOf("detail", 1.0F).forGetter(g -> g.detail)
    ).apply(i, i.stable(MapChunkGenerator::new)));

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final String mapName;
    private final boolean caves;
    private final float detail;
    private volatile TerrainSampler sampler;

    public MapChunkGenerator(String mapName, boolean caves, float detail) {
        super(new MapBiomeSource(mapName));
        this.mapName = mapName;
        this.caves = caves;
        this.detail = detail;
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    public MapData data() {
        return sampler().map();
    }

    private TerrainSampler sampler() {
        TerrainSampler s = sampler;
        if (s == null) {
            synchronized (this) {
                if (sampler == null) sampler = new TerrainSampler(WorldForgeMod.map(mapName), detail);
                s = sampler;
            }
        }
        return s;
    }

    /** Блок над поверхностью (или водой) в точке (x, z). */
    public BlockPos surfaceAt(int x, int z) {
        TerrainSampler.Col c = sampler().sample(x, z);
        return new BlockPos(x, Math.max(c.top, c.water) + 1, z);
    }

    /** Точка появления игрока для WorldForgeMod. */
    public BlockPos findSpawn() {
        int[] p = sampler().findSpawn();
        return new BlockPos(p[0], p[1], p[2]);
    }

    // ------------------------------------------------------------------ рельеф

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random,
                                                        StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.supplyAsync(() -> {
            fill(chunk);
            return chunk;
        }, Util.backgroundExecutor());
    }

    private void fill(ChunkAccess chunk) {
        TerrainSampler ts = sampler();
        MapData m = ts.map();
        ChunkPos cp = chunk.getPos();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cp.getMinBlockX() + lx, wz = cp.getMinBlockZ() + lz;
                TerrainSampler.Col c = ts.sample(wx, wz);
                String key = m.biomeKey(c.biome);
                int top = Math.min(c.top, maxY);
                int hash = SurfaceBlocks.hash(wx, wz);
                int fillerDepth = SurfaceBlocks.fillerDepth(key);
                boolean under = c.water > c.top;
                BlockState topState = SurfaceBlocks.STONE;

                for (int y = minY; y <= top; y++) {
                    BlockState s;
                    int d = y - minY;
                    if (d == 0 || (d < 5 && SurfaceBlocks.hash3(wx, y, wz) % 5 >= d)) {
                        s = SurfaceBlocks.BEDROCK;
                    } else {
                        boolean deep = y < 0 || (y < 8 && (SurfaceBlocks.hash3(wx, y, wz) & 7) < (8 - y));
                        s = deep ? SurfaceBlocks.DEEPSLATE : SurfaceBlocks.STONE;
                        int depth = c.top - y;
                        if (depth == 0) {
                            s = under ? SurfaceBlocks.underwater(key, m.seaLevel - c.top, hash) : SurfaceBlocks.top(key, hash);
                        } else if (depth <= fillerDepth) {
                            s = under ? SurfaceBlocks.underwater(key, m.seaLevel - c.top, hash ^ 5) : SurfaceBlocks.filler(key, depth);
                        } else if (caves && y < c.top - 7 && ts.cave(wx, y, wz)) {
                            s = y < -54 ? SurfaceBlocks.LAVA : AIR;
                        }
                    }
                    if (y == top) topState = s;
                    if (s != AIR) chunk.setBlockState(pos.set(wx, y, wz), s, false);
                }
                int waterTop = Math.min(c.water, maxY);
                for (int y = top + 1; y <= waterTop; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), SurfaceBlocks.WATER, false);
                }
                oceanFloor.update(lx, top, lz, topState);
                worldSurface.update(lx, Math.max(top, waterTop), lz, waterTop > top ? SurfaceBlocks.WATER : topState);
            }
        }
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
        // поверхность уже построена в fillFromNoise
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
                             StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {
        // пещеры делаются в fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        ChunkPos cp = region.getCenter();
        Holder<Biome> biome = region.getBiome(cp.getWorldPosition().atY(region.getMaxBuildHeight() - 1));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(region.getSeed(), cp.getMinBlockX(), cp.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(region, biome, cp, random);
    }

    // ------------------------------------------------------------------ параметры мира

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        try {
            return sampler().map().seaLevel;
        } catch (RuntimeException e) {
            return 63;
        }
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        TerrainSampler.Col c = sampler().sample(x, z);
        boolean includeWater = type == Heightmap.Types.WORLD_SURFACE || type == Heightmap.Types.WORLD_SURFACE_WG
                || type == Heightmap.Types.MOTION_BLOCKING || type == Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        int y = (includeWater ? Math.max(c.top, c.water) : c.top) + 1;
        return Mth.clamp(y, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        TerrainSampler.Col c = sampler().sample(x, z);
        BlockState[] column = new BlockState[level.getHeight()];
        for (int i = 0; i < column.length; i++) {
            int y = level.getMinBuildHeight() + i;
            column[i] = y <= c.top ? SurfaceBlocks.STONE : (y <= c.water ? SurfaceBlocks.WATER : AIR);
        }
        return new NoiseColumn(level.getMinBuildHeight(), column);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        try {
            MapData m = sampler().map();
            TerrainSampler.Col c = sampler().sample(pos.getX(), pos.getZ());
            info.add("WorldForge: " + m.name + ", биом карты: " + m.biomeKey(c.biome) + (c.river ? " (река)" : ""));
        } catch (RuntimeException e) {
            info.add("WorldForge: карта не загружена");
        }
    }
}
