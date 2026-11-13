package dev.worldforge.tests;

import dev.worldforge.mod.MapData;
import dev.worldforge.worldsim.MapGeography;

import java.util.List;
import java.util.Map;

/** Geographic-only map inspection must work without political overlays or prebuilt entities. */
public final class MapGeographyTest {
    public static void main(String[] args) {
        MapData map = MapData.fallback("geography-test");
        Map<String, Object> first = MapGeography.describe(map);
        Map<String, Object> second = MapGeography.describe(map);

        check(Boolean.FALSE.equals(first.get("political_layers_present")),
                "geography-only map does not require political layers");
        check(Boolean.FALSE.equals(first.get("political_layers_ignored"))
                        && Boolean.TRUE.equals(first.get("political_ownership_included")),
                "only canonical territorial ownership is included as a founding exclusion signal");
        Map<?, ?> firstCell = (Map<?, ?>) ((List<?>) first.get("cells")).get(0);
        check("".equals(firstCell.get("owner_realm_id")),
                "unclaimed geography is explicitly marked as available for autonomous emergence");
        check(((Number) first.get("sampled_cells")).intValue() > 0,
                "geographic raster produces natural samples");
        check(((Number) first.get("land_samples")).intValue() > 0,
                "elevation and biome identify land without pre-seeded provinces");
        check(first.equals(second), "geographic sampling is deterministic");
        check(((List<?>) first.get("cells")).size() <= 4096,
                "large maps are bounded by adaptive sampling, not fixed world entity counts");
        check(((List<?>) first.get("missing_layers")).contains("climate raster"),
                "missing climate and resource rasters are reported instead of fabricated");
        System.out.println("MapGeographyTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
