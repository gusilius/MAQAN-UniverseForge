#!/usr/bin/env python3
"""Deterministic semantic analysis for illustrated WorldForge maps.

Unknown colors remain explicit. Water bodies are separated by border connectivity
and component geometry; reports expose class coverage and uncertainty.
"""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path
import numpy as np
from PIL import Image
from scipy import ndimage

CLASSES = ("ocean", "lake", "waterway", "grassland", "forest", "taiga",
           "desert", "mountains", "snow", "rock", "unknown")
COLORS = {
    "ocean": (5, 65, 105), "lake": (45, 130, 190), "waterway": (30, 175, 225),
    "grassland": (151, 183, 83), "forest": (25, 91, 39), "taiga": (20, 66, 43),
    "desert": (224, 185, 105), "mountains": (119, 105, 88),
    "snow": (232, 241, 245), "rock": (103, 99, 91), "unknown": (255, 0, 180),
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
    """Classify an RGB map; never silently coerce unknown pixels into grassland."""
    h, w = rgb.shape[:2]
    hue, sat, val = _hsv(rgb)
    r, g, b = [rgb[..., i].astype(np.float32) for i in range(3)]
    idx = {name: i for i, name in enumerate(CLASSES)}
    labels = np.full((h, w), idx["unknown"], np.uint8)
    assigned = np.zeros((h, w), bool)

    # Only blue components connected to the image boundary are open ocean.
    blue = (hue >= 165) & (hue <= 235) & (sat >= .38) & (b > g * 1.08) & (b > 55)
    edge = np.zeros((h, w), bool)
    edge[0] = blue[0]; edge[-1] = blue[-1]
    edge[:, 0] = blue[:, 0]; edge[:, -1] = blue[:, -1]
    ocean = ndimage.binary_propagation(edge, mask=blue,
        structure=np.array([[0,1,0],[1,1,1],[0,1,0]], bool))
    labels[ocean] = idx["ocean"]; assigned |= ocean

    # Enclosed pools are lakes when broad enough; narrow components are waterways.
    inland = blue & ~ocean
    components, count = ndimage.label(inland, structure=np.ones((3,3), bool))
    if count:
        sizes = np.bincount(components.ravel())
        distance = ndimage.distance_transform_edt(inland)
        max_width = ndimage.maximum(distance, labels=components, index=np.arange(1, count + 1)) * 2
        for component_id in range(1, count + 1):
            mask = components == component_id
            is_lake = sizes[component_id] >= 90 and max_width[component_id - 1] >= 7
            labels[mask] = idx["lake"] if is_lake else idx["waterway"]
            assigned |= mask

    snow = (sat < .19) & (val > .70) & (b > 150) & (g > 150)
    labels[snow] = idx["snow"]; assigned |= snow

    # Vegetation: broad olive-green range for grassland; darker canopy for forest/taiga.
    green = (hue >= 48) & (hue <= 158) & (g >= r * .97) & (g > b * 1.08) & (g > 32)
    dark_green = green & (val < .48) & (sat > .28)
    labels[green] = idx["grassland"]
    labels[dark_green] = idx["forest"]
    latitude = np.abs(np.linspace(-1.0, 1.0, h, dtype=np.float32))[:, None]
    taiga = dark_green & (latitude > .56)
    labels[taiga] = idx["taiga"]
    assigned |= green

    # Ochre/brown: pale saturated tones are arid land; darker earth tones are relief.
    warm = (hue >= 12) & (hue <= 58) & (sat > .16) & (r > g * 1.015) & (g > b * 1.025)
    desert = warm & (val > .55) & (sat > .22)
    mountains = warm & ~desert
    labels[mountains] = idx["mountains"]
    labels[desert] = idx["desert"]
    assigned |= warm

    grey_rock = (sat < .25) & (val > .16) & (val < .70) & ~assigned
    labels[grey_rock] = idx["rock"]; assigned |= grey_rock

    counts = np.bincount(labels.ravel(), minlength=len(CLASSES))
    total = int(h * w)
    unknown_rgb = rgb[~assigned]
    if unknown_rgb.size:
        colors, color_counts = np.unique(unknown_rgb.reshape(-1, 3), axis=0, return_counts=True)
        order = np.argsort(color_counts)[::-1][:12]
        unknown_color_samples = [{"rgb": [int(v) for v in colors[i]], "pixels": int(color_counts[i])}
                                 for i in order]
    else:
        unknown_color_samples = []
    unknown = int(counts[idx["unknown"]])
    report = {
        "format": "maqan-image-analysis/2", "width": int(w), "height": int(h),
        "source_sha256": hashlib.sha256(rgb.tobytes()).hexdigest(),
        "classifier": "hsv-context-components-v2",
        "classes": {name: int(counts[i]) for i, name in enumerate(CLASSES)},
        "class_percent": {name: round(100.0 * int(counts[i]) / total, 3) for i, name in enumerate(CLASSES)},
        "unknown_pixels": unknown, "unknown_percent": round(100.0 * unknown / total, 3),
        "classified_percent": round(100.0 * (total - unknown) / total, 3),
        "unknown_color_samples": unknown_color_samples,
        "water_model": {"ocean": "blue components connected to image boundary",
                        "lake": "enclosed component >=90 px and estimated width >=7 px",
                        "waterway": "remaining enclosed blue components"},
        "notes": [
            "Deterministic palette/context analysis; not general-purpose AI image understanding.",
            "Unknown pixels are preserved as unknown and visualized magenta, never silently relabeled as grassland.",
            "Taiga is an initial latitude heuristic over dark vegetation; validate against the map legend.",
            "River/lake split is geometric and may need map-specific threshold calibration."
        ]
    }
    return labels, report

def make_heightfield(labels, seed, sea_level=63, min_y=-64, max_y=320):
    """Create deterministic multi-scale relief with explicit class height anchors."""
    h, w = labels.shape
    rng = np.random.default_rng(seed)
    def field(cell):
        gh, gw = max(2, (h + cell - 1)//cell + 1), max(2, (w + cell - 1)//cell + 1)
        coarse = Image.fromarray(np.uint8(rng.random((gh, gw)) * 255), "L")
        return np.asarray(coarse.resize((w, h), Image.Resampling.BICUBIC), np.float32) / 255.0 - .5
    continental, regional, detail = field(240), field(72), field(18)
    anchors = {"ocean": sea_level-44, "lake": sea_level-2, "waterway": sea_level-1,
        "grassland": sea_level+7, "forest": sea_level+10, "taiga": sea_level+13,
        "desert": sea_level+6, "mountains": sea_level+38, "snow": sea_level+22,
        "rock": sea_level+28, "unknown": sea_level+7}
    base = np.zeros(labels.shape, np.float32)
    for i, name in enumerate(CLASSES):
        base[labels == i] = anchors[name]
    relief = base + continental*64 + regional*28 + detail*9
    mountain = (labels == CLASSES.index("mountains")) | (labels == CLASSES.index("rock"))
    ridge = 1.0 - np.abs(2.0 * (detail + .5) - 1.0)
    relief += mountain * (ridge * 74 + np.maximum(0, regional) * 36)
    water = (labels == CLASSES.index("ocean")) | (labels == CLASSES.index("lake"))
    relief[water] = sea_level - 42 + continental[water] * 20
    relief[labels == CLASSES.index("waterway")] = sea_level - 1
    norm = np.clip((relief - min_y) / (max_y - min_y), 0, 1)
    return np.round(norm * 65535).astype(np.uint16)

def render_labels(labels):
    rgb = np.zeros((*labels.shape, 3), np.uint8)
    for i, name in enumerate(CLASSES):
        rgb[labels == i] = COLORS[name]
    return Image.fromarray(rgb, "RGB")

def analyze(input_path, output_dir, seed):
    image = Image.open(input_path).convert("RGB")
    rgb = np.asarray(image, np.uint8)
    labels, report = classify(rgb)
    height = make_heightfield(labels, seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    render_labels(labels).save(output_dir / "semantic-preview.png", optimize=False)
    Image.fromarray(height).save(output_dir / "height16.png", optimize=False)
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
