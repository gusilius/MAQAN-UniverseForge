import unittest

import numpy as np
from scipy import ndimage

from image_map_analysis import CLASSES
from planet_generation import procedural_planet_image


class ProceduralPlanetGenerationTests(unittest.TestCase):
    def test_seed_reproduces_identical_planet(self):
        first = np.asarray(procedural_planet_image(512, 256, 424242))
        second = np.asarray(procedural_planet_image(512, 256, 424242))
        self.assertTrue(np.array_equal(first, second))

    def test_planet_has_connected_ocean_and_multiple_landmasses(self):
        image = np.asarray(procedural_planet_image(512, 256, 424242))
        labels = np.zeros(image.shape[:2], dtype=np.uint8)
        palette = {tuple(rgb): index for index, rgb in enumerate(
            [ (5, 65, 105), (45, 130, 190), (30, 175, 225), (151, 183, 83),
              (25, 91, 39), (20, 66, 43), (224, 185, 105), (119, 105, 88),
              (232, 241, 245), (103, 99, 91), (255, 0, 180) ])}
        for color, index in palette.items():
            labels[np.all(image == color, axis=2)] = index
        ocean = labels == CLASSES.index("ocean")
        land = np.isin(labels, [CLASSES.index(name) for name in
                                ("grassland", "forest", "taiga", "desert", "mountains", "snow", "rock")])
        edge = np.zeros_like(ocean)
        edge[0] = ocean[0]; edge[-1] = ocean[-1]
        edge[:, 0] = ocean[:, 0]; edge[:, -1] = ocean[:, -1]
        connected_ocean = ndimage.binary_propagation(
            edge, mask=ocean, structure=np.array([[0,1,0],[1,1,1],[0,1,0]], bool))
        self.assertTrue(np.array_equal(ocean, connected_ocean))
        components, count = ndimage.label(land, structure=np.ones((3,3), bool))
        sizes = np.bincount(components.ravel())[1:]
        self.assertGreaterEqual(count, 2)
        self.assertTrue(np.any(sizes >= 512))
        self.assertTrue(np.any((sizes >= 8) & (sizes < 512)))


    def test_rendered_palette_keeps_rivers_distinct_when_they_reach_ocean(self):
        from image_map_analysis import classify
        # A cyan river runs into the dark-blue ocean; the channel must not be
        # swallowed by the ocean's boundary-connected component.
        labels = np.full((32, 48), CLASSES.index("grassland"), dtype=np.uint8)
        labels[:, :8] = CLASSES.index("ocean")
        labels[15, 8:30] = CLASSES.index("waterway")
        labels[8:20, 30:42] = CLASSES.index("lake")
        rgb = np.asarray(__import__("image_map_analysis").render_labels(labels))
        classified, report = classify(rgb)
        self.assertEqual(int((classified == CLASSES.index("ocean")).sum()), 32 * 8)
        self.assertGreater(int((classified == CLASSES.index("waterway")).sum()), 0)
        self.assertGreater(int((classified == CLASSES.index("lake")).sum()), 0)
        self.assertEqual(report["classes"]["ocean"], 32 * 8)

    def test_rivers_and_lakes_are_terrain_derived_and_nonempty(self):
        image = np.asarray(procedural_planet_image(512, 256, 424242))
        colors = {name: np.asarray(rgb, dtype=np.uint8) for name, rgb in {
            "lake": (45, 130, 190), "river": (30, 175, 225),
            "ocean": (5, 65, 105)}.items()}
        lake = np.all(image == colors["lake"], axis=2)
        river = np.all(image == colors["river"], axis=2)
        ocean = np.all(image == colors["ocean"], axis=2)
        self.assertGreater(int(river.sum()), 0)
        self.assertGreater(int(lake.sum()), 0)
        self.assertFalse(np.any(lake & ocean))
        self.assertFalse(np.any(river & ocean))


if __name__ == "__main__":
    unittest.main()
