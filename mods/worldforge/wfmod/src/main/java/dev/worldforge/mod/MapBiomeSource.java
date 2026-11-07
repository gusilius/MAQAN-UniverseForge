package dev.worldforge.mod;

import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Источник биомов: биом берётся из biomes.png пакета карты. */
public class MapBiomeSource extends BiomeSource {
    public static final Codec<MapBiomeSource> CODEC =
            Codec.STRING.fieldOf("map").xmap(MapBiomeSource::new, s -> s.mapName).stable().codec();

    private final String mapName;
    private volatile List<Holder<Biome>> holders;

    public MapBiomeSource(String mapName) {
        this.mapName = mapName;
    }

    public String mapName() {
        return mapName;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    /** Для каждого индекса карты ищем первый существующий в реестре биом из списка кандидатов. */
    private List<Holder<Biome>> resolve() {
        List<Holder<Biome>> h = holders;
        if (h != null) return h;
        synchronized (this) {
            if (holders != null) return holders;
            Registry<Biome> reg = WorldForgeMod.biomeRegistry();
            if (reg == null) throw new IllegalStateException("WorldForge: реестр биомов ещё не готов");
            MapData m = WorldForgeMod.map(mapName);
            Holder<Biome> fallback = reg.getHolderOrThrow(ResourceKey.create(Registries.BIOME, new ResourceLocation("minecraft", "plains")));
            List<Holder<Biome>> out = new ArrayList<>();
            for (int i = 0; i < m.keys.length; i++) {
                Holder<Biome> pick = fallback;
                for (String id : m.candidates[i]) {
                    ResourceLocation rl = new ResourceLocation(id);
                    if (reg.containsKey(rl)) {
                        pick = reg.getHolderOrThrow(ResourceKey.create(Registries.BIOME, rl));
                        break;
                    }
                }
                out.add(pick);
            }
            holders = out;
            return out;
        }
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return resolve().stream().distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        MapData m = WorldForgeMod.map(mapName);
        int idx = m.biomeIndexAt(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2);
        return resolve().get(idx);
    }
}
