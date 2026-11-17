import unittest

from image_worldgen import (
    allocate_counts, select_independent_islands, aggregate_realm_regions,
    classify_realm_scale, select_wilderness_mask, SETTLEMENT_PROFILES, partition,
    create_test_image, convert_image, apply_province_aligned_wilderness, validate_package,
)


class PoliticalDistributionTests(unittest.TestCase):
    def test_settlement_profiles_do_not_define_political_budgets(self):
        for profile in SETTLEMENT_PROFILES.values():
            for key in ("realms", "provinces", "minimum_pixels", "merge_fraction", "realm_area_cap"):
                self.assertNotIn(key, profile)

    def test_all_density_profiles_emit_geography_without_canonical_polities(self):
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.png"
            create_test_image(source)
            for level in range(1, 8):
                result = convert_image(
                    source, root / f"level-{level}.worldmap",
                    seed=424242, settlement_level=level)
                self.assertNotIn("realm_count", result)
                self.assertNotIn("realms", result)
                self.assertEqual(result["province_count"], len(result["provinces"]))
                self.assertEqual(result["region_count"], len(result["regions"]))
                self.assertTrue(all(not row["name"] for row in result["settlements"]))
                self.assertTrue(all(not row["realm_id"] and row["type"] == "site"
                                    for row in result["settlements"]))
                self.assertEqual(validate_package(root / f"level-{level}.worldmap")["realm_count"], 0)

    def test_wilderness_reserves_whole_provinces_and_protects_realm_cores(self):
        import numpy as np
        labels = np.asarray([
            [1, 1, 2, 2, 3, 3],
            [1, 1, 2, 2, 3, 3],
            [1, 1, 2, 2, 3, 3],
            [1, 1, 2, 2, 3, 3],
        ], dtype=np.uint32)
        meta = {
            1: {"realm": 0, "seed": 0, "area": 8},
            2: {"realm": 0, "seed": 2, "area": 8},
            3: {"realm": 1, "seed": 4, "area": 8},
        }
        realms = {0: [1, 2], 1: [3]}
        cells = {0: list(range(16)), 1: list(range(4, 24))}
        candidates = np.zeros((4, 6), dtype=bool)
        candidates[:, 2:4] = True
        (result, result_meta, result_realms, cells_by_realm,
         wilderness, report) = apply_province_aligned_wilderness(
            labels.ravel(), meta, realms, candidates, 6, 4)
        self.assertTrue(np.all(result.reshape(4, 6)[:, 2:4] == 0))
        self.assertTrue(np.all(wilderness[:, 2:4]))
        self.assertEqual(set(result_meta), {1, 3})
        self.assertEqual(result_realms, {0: [1], 1: [3]})
        self.assertTrue(cells_by_realm[0])
        self.assertTrue(cells_by_realm[1])
        self.assertEqual(report["selected_provinces"], 1)
        self.assertEqual(report["protected_capital_provinces"], 2)
        self.assertEqual(int(np.count_nonzero(result)), 16)

    def test_realm_counts_respect_each_landmass_capacity(self):
        components = [list(range(1000)), list(range(1000, 1500)),
                      list(range(1500, 1600))]
        counts = allocate_counts(components, total=6, minimum_pixels=100)
        self.assertEqual(sum(counts), 6)
        self.assertGreaterEqual(min(counts), 1)
        for component, count in zip(components, counts):
            self.assertLessEqual(count, max(1, len(component) // 100))

    def test_realm_allocation_handles_fragmented_continents(self):
        components = [list(range(150)), list(range(150, 300)),
                      list(range(300, 450))]
        counts = allocate_counts(components, total=3, minimum_pixels=100)
        self.assertEqual(counts, [1, 1, 1])

    def test_realm_aggregation_absorbs_undersized_regions_before_global_target(self):
        import numpy as np
        # Region 0 is a one-cell sliver. It must be absorbed even though the
        # normal consolidation target would otherwise permit two final realms.
        raster = np.asarray([
            [0, 1, 1, 1, 1, 1],
            [0, 1, 1, 1, 1, 1],
            [2, 2, 2, 2, 2, 2],
            [2, 2, 2, 2, 2, 2],
        ], dtype=np.int32).ravel()
        seeds = {0: 0, 1: 1, 2: 12}
        result, new_seeds, report = aggregate_realm_regions(
            raster, seeds, width=6, height=4, seed=19,
            minimum_pixels=4, desired_fraction=0.75, max_area_share=0.95)
        self.assertEqual(report["undersized_regions_after_merge"], 0)
        self.assertGreaterEqual(report["merge_operations"], 1)
        self.assertEqual(len(new_seeds), report["merged_regions"])
        self.assertTrue(np.all(result >= 0))

    def test_realm_aggregation_does_not_absorb_disconnected_small_island(self):
        import numpy as np
        # A tiny disconnected landmass has no shared political frontier and
        # must remain eligible for later island-city-state viability analysis.
        raster = np.asarray([
            [0, 0, -1, -1, -1, -1],
            [0, 0, -1, -1, 1, -1],
            [0, 0, -1, -1, -1, -1],
            [0, 0, -1, -1, -1, -1],
        ], dtype=np.int32).ravel()
        seeds = {0: 0, 1: 10}
        result, new_seeds, report = aggregate_realm_regions(
            raster, seeds, width=6, height=4, seed=23,
            minimum_pixels=4, desired_fraction=0.75, max_area_share=0.95)
        self.assertEqual(len(new_seeds), 2)
        self.assertEqual(report["merged_regions"], 2)
        self.assertEqual(int(np.count_nonzero(result == 1)), 1)

    def test_realm_allocation_rejects_impossible_request(self):
        components = [list(range(100)), list(range(100, 200))]
        with self.assertRaises(ValueError):
            allocate_counts(components, total=3, minimum_pixels=100)

    def test_island_city_states_are_selected_by_viability_and_stably(self):
        components = [list(range(10)), list(range(10, 210)),
                      list(range(210, 360)), list(range(360, 440))]
        first = select_independent_islands(components, seed=424242,
                                           budget=2, minimum_pixels=64)
        second = select_independent_islands(list(reversed(components)), seed=424242,
                                            budget=2, minimum_pixels=64)
        self.assertEqual(first, second)
        self.assertEqual(first, {10, 210})

    def test_too_small_islands_never_become_city_states(self):
        components = [list(range(8)), list(range(8, 40)), list(range(40, 90))]
        selected = select_independent_islands(components, seed=7,
                                              budget=5, minimum_pixels=64)
        self.assertEqual(selected, set())


    def test_adjacent_regions_merge_into_connected_larger_realms(self):
        import numpy as np
        raster = np.asarray([
            [0, 0, 1, 1, 2, 2],
            [0, 0, 1, 1, 2, 2],
            [0, 0, 1, 1, 2, 2],
            [0, 0, 1, 1, 2, 2],
            [3, 3, 3, 3, 2, 2],
            [3, 3, 3, 3, 2, 2],
        ], dtype=np.int32).ravel()
        seeds = {0: 0, 1: 2, 2: 4, 3: 24}
        result, new_seeds, report = aggregate_realm_regions(
            raster, seeds, width=6, height=6, seed=424242, minimum_pixels=2)
        self.assertEqual(len(new_seeds), report["merged_regions"])
        self.assertLess(report["merged_regions"], report["initial_regions"])
        self.assertTrue(np.all(result[result >= 0] >= 0))
        self.assertEqual(set(np.unique(result[result >= 0])), set(new_seeds))

    def test_realm_scale_has_city_state_and_empire_tiers(self):
        self.assertEqual(classify_realm_scale(100, 10000, True), ("city_state", 1))
        self.assertEqual(classify_realm_scale(50, 10000), ("tiny", 1))
        self.assertEqual(classify_realm_scale(500, 10000), ("medium", 2))
        self.assertEqual(classify_realm_scale(3000, 10000), ("huge", 3))


    def test_dense_profiles_have_no_wilderness_reserve(self):
        import numpy as np
        from image_map_analysis import CLASSES
        land = np.full((40, 60), CLASSES.index("grassland"), dtype=np.uint8)
        for level in (6, 7):
            mask = select_wilderness_mask(
                land, 60, 40, SETTLEMENT_PROFILES[level]["wilderness_share"], 42)
            self.assertEqual(int(mask.sum()), 0)

    def test_early_profiles_create_coherent_wilderness_candidates(self):
        import numpy as np
        from image_map_analysis import CLASSES
        land = np.full((96, 128), CLASSES.index("grassland"), dtype=np.uint8)
        land[20:75, 35:95] = CLASSES.index("forest")
        mask = select_wilderness_mask(land, 128, 96,
                                      SETTLEMENT_PROFILES[1]["wilderness_share"], 424242)
        self.assertGreater(int(mask.sum()), 0)
        self.assertLess(int(mask.sum()), land.size)
        self.assertTrue(np.all(mask[land == CLASSES.index("ocean")] == 0))

    def test_organic_watershed_keeps_each_seed_region_connected(self):
        import numpy as np
        from scipy import ndimage
        width, height = 12, 10
        cells = []
        for z in range(height):
            for x in range(width):
                if not (x in (0, 11) or z in (0, 9)):
                    cells.append(z * width + x)
        scratch = np.full(width * height, -2, dtype=np.int32)
        boundary = np.zeros((height, width), dtype=np.uint8)
        boundary[:, 5] = 220
        labels, _ = partition(cells, [2 * width + 2, 7 * width + 9],
                              width, height, 0, scratch, boundary)
        for region in (0, 1):
            mask = (labels.reshape(height, width) == region)
            _, count = ndimage.label(mask, structure=np.array(
                [[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=np.uint8))
            self.assertEqual(count, 1)


if __name__ == "__main__":
    unittest.main()
