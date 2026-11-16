import unittest
import numpy as np
from PIL import Image, ImageDraw

from image_map_analysis import CLASSES, classify, make_heightfield


class ImageMapAnalysisTests(unittest.TestCase):
    def test_semantic_regions_are_recognized(self):
        image = Image.new("RGB", (160, 120), (0, 74, 119))
        draw = ImageDraw.Draw(image)
        draw.rectangle((5, 5, 35, 110), fill=(118, 183, 100))
        draw.rectangle((40, 5, 70, 110), fill=(40, 110, 45))
        draw.rectangle((75, 5, 105, 110), fill=(220, 180, 100))
        draw.rectangle((110, 5, 130, 110), fill=(115, 100, 85))
        draw.rectangle((135, 5, 150, 110), fill=(225, 232, 235))
        draw.line((20, 20, 20, 100), fill=(0, 145, 210), width=3)

        labels, report = classify(np.asarray(image, dtype=np.uint8))
        present = {CLASSES[i] for i in np.unique(labels)}
        for key in ("ocean", "waterway", "grassland", "forest",
                    "desert", "mountains", "snow"):
            self.assertIn(key, present)
        self.assertEqual(report["width"], 160)
        self.assertGreater(report["classified_percent"], 95.0)

    def test_lake_and_waterway_are_separated_by_geometry(self):
        image = Image.new("RGB", (128, 96), (0, 74, 119))
        draw = ImageDraw.Draw(image)
        draw.rectangle((8, 8, 120, 88), fill=(118, 183, 100))
        draw.ellipse((40, 25, 75, 60), fill=(0, 145, 210))
        draw.line((85, 10, 90, 80), fill=(0, 145, 210), width=2)

        labels, _ = classify(np.asarray(image, dtype=np.uint8))
        present = {CLASSES[i] for i in np.unique(labels)}
        self.assertIn("lake", present)
        self.assertIn("waterway", present)

    def test_unknown_pixels_are_not_silently_grassland(self):
        image = np.zeros((16, 16, 3), dtype=np.uint8)
        image[:] = (0, 74, 119)
        image[5:8, 5:8] = (255, 0, 180)

        labels, report = classify(image)
        self.assertIn("unknown", {CLASSES[i] for i in np.unique(labels)})
        self.assertGreater(report["unknown_pixels"], 0)
        self.assertGreater(report["unknown_color_samples"][0]["pixels"], 0)

    def test_seeded_relief_is_repeatable_and_non_flat(self):
        labels = np.full((96, 128), CLASSES.index("grassland"), np.uint8)
        labels[10:70, 10:70] = CLASSES.index("mountains")
        labels[:, 0:8] = CLASSES.index("ocean")
        first = make_heightfield(labels, 123)
        repeat = make_heightfield(labels, 123)
        other_seed = make_heightfield(labels, 124)

        self.assertTrue(np.array_equal(first, repeat))
        self.assertFalse(np.array_equal(first, other_seed))
        self.assertGreater(int(first.max()) - int(first.min()), 1000)


    def test_olive_grassland_is_not_overwritten_as_desert(self):
        image = np.zeros((32, 32, 3), dtype=np.uint8)
        image[:] = (0, 74, 119)
        image[4:28, 4:28] = (160, 160, 64)
        labels, _ = classify(image)
        center = labels[16, 16]
        self.assertEqual(CLASSES[int(center)], "grassland")

    def test_neutral_snow_is_not_overwritten_as_rock(self):
        image = np.zeros((32, 32, 3), dtype=np.uint8)
        image[:] = (0, 74, 119)
        image[4:28, 4:28] = (208, 208, 208)
        labels, _ = classify(image)
        self.assertEqual(CLASSES[int(labels[16, 16])], "snow")

    def test_land_masks_are_mutually_exclusive_and_reported(self):
        image = np.zeros((48, 64, 3), dtype=np.uint8)
        image[:] = (0, 74, 119)
        image[4:20, 4:20] = (154, 172, 58)
        image[4:20, 22:38] = (32, 64, 16)
        image[4:20, 40:60] = (224, 185, 105)
        labels, report = classify(image)
        self.assertEqual(CLASSES[int(labels[10, 10])], "grassland")
        self.assertEqual(CLASSES[int(labels[10, 28])], "forest")
        self.assertEqual(CLASSES[int(labels[10, 48])], "desert")
        self.assertEqual(report["classifier"], "palette-texture-context-v4")
        self.assertIn("land_model", report)


    def test_lakes_are_shallower_than_open_ocean(self):
        labels = np.full((96, 128), CLASSES.index("grassland"), np.uint8)
        labels[:, :24] = CLASSES.index("ocean")
        labels[28:68, 58:98] = CLASSES.index("lake")
        height = make_heightfield(labels, 424242)
        ocean_median = int(np.median(height[:, 4:20]))
        lake_median = int(np.median(height[36:60, 66:90]))
        self.assertGreater(lake_median, ocean_median)


    def test_local_texture_can_distinguish_canopy_from_smooth_plains(self):
        image = np.zeros((48, 80, 3), dtype=np.uint8)
        image[:] = (0, 74, 119)
        image[8:40, 4:36] = (145, 165, 75)
        for y in range(8, 40):
            for x in range(44, 76):
                image[y, x] = (78, 128, 42) if (x + y) % 2 else (160, 180, 72)
        labels, _ = classify(image)
        self.assertEqual(CLASSES[int(labels[24, 20])], "grassland")
        self.assertEqual(CLASSES[int(labels[24, 60])], "forest")


if __name__ == "__main__":
    unittest.main()
