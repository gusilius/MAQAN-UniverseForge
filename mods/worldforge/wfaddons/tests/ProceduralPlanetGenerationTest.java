package dev.worldforge.tests;

import dev.worldforge.mod.MapData;
import dev.worldforge.mod.PlanetWorldProfile;
import dev.worldforge.mod.TerrainSampler;

import java.nio.charset.StandardCharsets;

/** Procedural planet determinism, body identity, and shared terrain-pipeline acceptance. */
public final class ProceduralPlanetGenerationTest {
    public static void main(String[] args) {
        String json = "{"
                + "\"format\":\"maqan-planet-world-profile/1\","
                + "\"planet_id\":\"maqan:test-world\","
                + "\"display_name\":\"Test World\","
                + "\"generation_mode\":\"procedural\","
                + "\"seed\":424242,"
                + "\"size_px\":[4096,4096],"
                + "\"blocks_per_pixel\":16,"
                + "\"settlement_level\":4,"
                + "\"settlement_profile\":{\"population_density\":0.38,\"wilderness_share\":0.25},"
                + "\"surface\":{\"sea_level\":63,\"min_y\":-64,\"max_y\":320}"
                + "}";
        PlanetWorldProfile profile=PlanetWorldProfile.parse(json.getBytes(StandardCharsets.UTF_8));
        MapData aMap=MapData.procedural("surface-a",profile);
        MapData bMap=MapData.procedural("surface-b",profile);
        if (!"maqan:test-world".equals(aMap.planetProfile.planetId))
            throw new AssertionError("Planet identity must not equal a Minecraft surface resource key");
        if (aMap.w!=4096 || aMap.h!=4096 || aMap.bpp!=16)
            throw new AssertionError("Procedural surface dimensions/profile were not preserved");
        TerrainSampler a=new TerrainSampler(aMap,1.0f), b=new TerrainSampler(bMap,1.0f);
        int[][] points={{0,0},{128,256},{-512,1024},{4096,-4096},{12000,8000},{2_000_000,-1_750_000}};
        for(int[] p:points) {
            TerrainSampler.Col ca=a.sample(p[0],p[1]), cb=b.sample(p[0],p[1]);
            if(ca.top!=cb.top || ca.water!=cb.water || ca.biome!=cb.biome)
                throw new AssertionError("Same seed/body/coordinate must be deterministic");
            if(ca.biome<0 || ca.biome>=aMap.keys.length)
                throw new AssertionError("Procedural biome index out of range: "+ca.biome);
            if(ca.top<aMap.minY || ca.top>aMap.maxY)
                throw new AssertionError("Terrain height escaped vertical bounds");
        }
        if(aMap.findSpawn().length!=2)throw new AssertionError("Procedural spawn is missing");
        System.out.println("ProceduralPlanetGenerationTest: PASS");
    }
}
