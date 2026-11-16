import unittest

from image_worldgen import allocate_counts, select_independent_islands


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


if __name__ == "__main__":
    unittest.main()
