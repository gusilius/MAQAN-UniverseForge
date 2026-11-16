import unittest
import numpy as np
from PIL import Image, ImageDraw
from image_map_analysis import CLASSES, classify, make_heightfield

class ImageMapAnalysisTests(unittest.TestCase):
    def test_color_regions_are_recognized(self):
        im=Image.new("RGB",(160,120),(0,74,119))
        d=ImageDraw.Draw(im)
        d.rectangle((5,5,35,110),fill=(40,110,45))
        d.rectangle((40,5,70,110),fill=(220,180,100))
        d.rectangle((75,5,105,110),fill=(115,100,85))
        d.rectangle((110,5,150,110),fill=(225,232,235))
        d.line((20,20,20,100),fill=(0,145,210),width=3)
        labels,report=classify(np.asarray(im,dtype=np.uint8))
        present={CLASSES[i] for i in np.unique(labels)}
        for key in ("ocean","waterway","grassland","forest","desert","mountains","snow"):
            self.assertIn(key,present)
        self.assertEqual(report["width"],160)

    def test_seeded_relief_is_repeatable_and_non_flat(self):
        labels=np.full((96,128),CLASSES.index("grassland"),np.uint8)
        labels[10:70,10:70]=CLASSES.index("mountains")
        labels[:,0:8]=CLASSES.index("ocean")
        a=make_heightfield(labels,123)
        b=make_heightfield(labels,123)
        c=make_heightfield(labels,124)
        self.assertTrue(np.array_equal(a,b))
        self.assertFalse(np.array_equal(a,c))
        self.assertGreater(int(a.max())-int(a.min()),1000)

if __name__=="__main__": unittest.main()
