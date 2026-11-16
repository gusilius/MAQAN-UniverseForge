import unittest

from image_worldgen import (allocate_counts, select_independent_islands,\n                            aggregate_realm_regions, classify_realm_scale)


class PoliticalDistributionTests(unittest.TestCase):
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
        self.assertEqual(classify_realm_scale(100, 10000), ("tiny", 1))
        self.assertEqual(classify_realm_scale(1000, 10000), ("medium", 2))
        self.assertEqual(classify_realm_scale(3000, 10000), ("huge", 3))


if __name__ == "__main__":
    unittest.main()
