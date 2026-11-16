#!/usr/bin/env python3
"""Analyze illustrated world maps into semantic geography and a first-pass heightfield.

This is a deterministic, inspectable color/context classifier. It does not claim
general-purpose AI vision: uncertain pixels are counted and exposed in the report.
"""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path
import numpy as np
from PIL import Image
from scipy import ndimage

CLASSES = ("ocean", "waterway", "grassland", "forest", "taiga",
           "desert", "mountains", "snow", "rock")
COLORS = {
    "ocean": (5, 65, 105), "waterway": (30, 145, 210),
    "grassland": (151, 183, 83), "forest": (25, 91, 39),
    "taiga": (20, 66, 43), "desert": (224, 185, 105),
    "mountains": (119, 105, 88), "snow": (232, 241, 245),
    "rock": (103, 99, 91),
}
def _hsv(rgb):
    f = rgb.astype(np.float32) / 255.0
    mx, mn = f.max(2), f.min(2)
    d = mx - mn
    r, g, b = f[..., 0], f[..., 1], f[..., 2]
    h = np.zeros(mx.shape, np.float32)
    m = (d > 1e-6) & (mx == r); h[m] = ((g[m] - b[m]) / d[m]) % 6
    m = (d > 1e-6) & (mx == g); h[m] = (b[m] - r[m]) / d[m] + 2
    m = (d > 1e-6) & (mx == b); h[m] = (r[m] - g[m]) / d[m] + 4
    return h * 60, np.divide(d, mx, out=np.zeros_like(d), where=mx > 1e-6), mx

def classify(rgb):
    """Classify common illustrated-map palettes; output class IDs and diagnostics."""
    h, w = rgb.shape[:2]
    hue, sat, val = _hsv(rgb)
    r, g, b = [rgb[..., i].astype(np.float32) for i in range(3)]
    labels = np.full((h, w), CLASSES.index("grassland"), np.uint8)

    # Blue is split into open ocean (edge-connected) and inland rivers/lakes.
    blue = (hue >= 185) & (hue <= 225) & (sat >= .50) & (b > g * 1.10) & (b > 65)
    edge = np.zeros((h, w), bool)
    edge[0] = blue[0]; edge[-1] = blue[-1]
    edge[:, 0] = blue[:, 0]; edge[:, -1] = blue[:, -1]
    ocean = ndimage.binary_propagation(
        edge, mask=blue, structure=np.array([[0,1,0],[1,1,1],[0,1,0]], bool))
    labels[ocean] = CLASSES.index("ocean")
    labels[blue & ~ocean] = CLASSES.index("waterway")

    snow = (sat < .17) & (val > .68)
    labels[snow] = CLASSES.index("snow")

    green = (hue >= 65) & (hue <= 165) & (g > r * 1.02) & (g > b * 1.02) & (g > 35)
    labels[green] = CLASSES.index("grassland")
    dark_green = green & (val < .43)
    labels[dark_green] = CLASSES.index("forest")
    latitude = np.abs(np.linspace(-1.0, 1.0, h, dtype=np.float32))[:, None]
    labels[dark_green & (latitude > .52)] = CLASSES.index("taiga")

    # Warm pale tones are arid regions; darker brown/grey tones are rock/mountains.
    warm = (hue >= 12) & (hue <= 58) & (sat > .20) & (r > g * 1.02) & (g > b * 1.03)
    labels[warm] = CLASSES.index("mountains")
    labels[warm & (val > .52) & (sat > .25)] = CLASSES.index("desert")
    grey_rock = (sat < .23) & (val > .18) & (val < .68) & ~blue & ~snow
    labels[grey_rock] = CLASSES.index("rock")

    # A small number of neutral pixels outside the snow/rock palette remain grassland.
    counts = np.bincount(labels.ravel(), minlength=len(CLASSES))
    report = {
        "format": "maqan-image-analysis/1",
        "width": int(w), "height": int(h),
        "source_sha256": hashlib.sha256(rgb.tobytes()).hexdigest(),
        "classifier": "hsv-context-edge-water-v1",
        "classes": {name: int(counts[i]) for i, name in enumerate(CLASSES)},
        "unclassified_pixels": 0,
        "notes": [
            "Water touching the image border is ocean; enclosed blue regions and blue lines are waterway candidates.",
            "Taiga is inferred from dark-green color and map latitude; verify on the semantic preview.",
            "Mountains and deserts are inferred from warm/neutral color ranges; ambiguous art styles may need a user palette."
        ]
    }
    return labels, report

def make_heightfield(labels, seed, sea_level=63, min_y=-64, max_y=320):
    """First-pass relief: class anchors plus seeded multi-scale coherent noise."""
    h, w = labels.shape
    rng = np.random.default_rng(seed)
    def field(cell):
        gh, gw = max(2, (h + cell - 1)//cell + 1), max(2, (w + cell - 1)//cell + 1)
        coarse = Image.fromarray(np.uint8(rng.random((gh, gw)) * 255), "L")
        return np.asarray(coarse.resize((w, h), Image.Resampling.BICUBIC), np.float32) / 255.0 - .5
    continental, regional, detail = field(240), field(72), field(18)
    anchors = np.array([sea_level-44, sea_level-5, sea_level+7, sea_level+10,
                        sea_level+13, sea_level+6, sea_level+38, sea_level+22, sea_level+28], np.float32)
    base = anchors[labels]
    relief = base + continental*64 + regional*28 + detail*9
    mountain = (labels == CLASSES.index("mountains")) | (labels == CLASSES.index("rock"))
    ridge = 1.0 - np.abs(2.0 * (detail + .5) - 1.0)
    relief += mountain * (ridge * 74 + np.maximum(0, regional) * 36)
    relief[labels == CLASSES.index("ocean")] = sea_level - 42 + continental[labels == CLASSES.index("ocean")] * 20
    relief[labels == CLASSES.index("waterway")] = sea_level - 1
    norm = np.clip((relief - min_y) / (max_y - min_y), 0, 1)
    return np.round(norm * 65535).astype(np.uint16)

def render_labels(labels):
    rgb = np.zeros((*labels.shape, 3), np.uint8)
    for i, name in enumerate(CLASSES): rgb[labels == i] = COLORS[name]
    return Image.fromarray(rgb, "RGB")

def analyze(input_path, output_dir, seed):
    image = Image.open(input_path).convert("RGB")
    rgb = np.asarray(image, np.uint8)
    labels, report = classify(rgb)
    height = make_heightfield(labels, seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    render_labels(labels).save(output_dir / "semantic-preview.png", optimize=False)
    Image.fromarray(height, mode="I;16").save(output_dir / "height16.png", optimize=False)
    report["seed"] = seed
    report["height_min_raw"] = int(height.min())
    report["height_max_raw"] = int(height.max())
    report["height_unique_values"] = int(np.unique(height).size)
    (output_dir / "image-analysis-report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--seed", type=int, default=424242)
    a = p.parse_args()
    print(json.dumps(analyze(a.input, a.output, a.seed), indent=2, sort_keys=True))
if __name__ == "__main__":
    main()
