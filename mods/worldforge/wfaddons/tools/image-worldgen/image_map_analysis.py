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
    """Classify map pixels with mutually exclusive semantic masks and palette-aware land rules."""
    h, w = rgb.shape[:2]
    hue, sat, val = _hsv(rgb)
    r, g, b = [rgb[..., i].astype(np.float32) for i in range(3)]
    # Local luminance variance supplies a second cue when illustrated maps use
    # similar hues for smooth plains, dense canopy and rugged relief.
    luminance = 0.299 * r + 0.587 * g + 0.114 * b
    local_mean = ndimage.uniform_filter(luminance, size=5, mode="nearest")
    local_square = ndimage.uniform_filter(luminance * luminance, size=5, mode="nearest")
    local_texture = np.sqrt(np.maximum(0.0, local_square - local_mean * local_mean))
    del luminance, local_mean, local_square
    idx = {name: i for i, name in enumerate(CLASSES)}
    labels = np.full((h, w), idx["unknown"], np.uint8)
    assigned = np.zeros((h, w), bool)

    # Open ocean is blue water connected to the image boundary. Inland water
    # is segmented separately so coastlines and rivers are not mistaken for land.
    blue = (hue >= 165) & (hue <= 235) & (sat >= .38) & (b > g * 1.08) & (b > 55)
    edge = np.zeros((h, w), bool)
    edge[0] = blue[0]; edge[-1] = blue[-1]
    edge[:, 0] = blue[:, 0]; edge[:, -1] = blue[:, -1]
    ocean = ndimage.binary_propagation(edge, mask=blue,
        structure=np.array([[0,1,0],[1,1,1],[0,1,0]], bool))
    labels[ocean] = idx["ocean"]; assigned |= ocean

    inland = blue & ~ocean
    components, count = ndimage.label(inland, structure=np.ones((3,3), bool))
    if count:
        sizes = np.bincount(components.ravel())
        distance = ndimage.distance_transform_edt(inland)
        max_width = ndimage.maximum(distance, labels=components, index=np.arange(1, count + 1)) * 2
        lake_by_component = np.zeros(count + 1, dtype=bool)
        lake_by_component[1:] = (sizes[1:] >= 90) & (max_width >= 7)
        lake_mask = inland & lake_by_component[components]
        labels[inland] = idx["waterway"]
        labels[lake_mask] = idx["lake"]
        assigned |= inland

    # Snow/ice takes precedence over vegetation and earth tones. The wider
    # saturation threshold preserves shaded blue-white snowfields.
    snow = (~assigned) & (sat < .22) & (val > .68) & (b > 145) & (g > 145)
    labels[snow] = idx["snow"]; assigned |= snow

    # The source map uses olive/yellow-green as well as vivid green. Keep this
    # palette family together: the old warm-earth rule overlapped it and
    # overwrote large areas of grassland as desert.
    green = (~assigned) & (hue >= 48) & (hue <= 158) & (g >= r * .94) & (g > b * 1.05) & (g > 32)
    dark_green = green & (val < .48) & (sat > .28)
    textured_canopy = green & (val < .76) & (sat > .24) & (local_texture > 13.0)
    forest = dark_green | textured_canopy
    labels[green] = idx["grassland"]
    labels[forest] = idx["forest"]
    latitude = np.abs(np.linspace(-1.0, 1.0, h, dtype=np.float32))[:, None]
    # Taiga is a narrower cool/dark canopy subset, not every dark pixel near a pole.
    taiga = forest & (hue >= 82) & (latitude > .58)
    labels[taiga] = idx["taiga"]
    assigned |= green

    # Sandy ochre and brown relief have their own palette family. Excluding
    # already assigned greens prevents yellow-green plains from being relabeled.
    earth = (~assigned) & (hue >= 12) & (hue < 60) & (sat > .16) & (r > g * 1.01) & (g > b * 1.02)
    desert = earth & (val > .55) & (sat > .22)
    mountains = earth & ~desert
    labels[mountains] = idx["mountains"]
    labels[desert] = idx["desert"]
    assigned |= earth

    # Neutral shaded stone is kept distinct from brown mountains and snow.
    grey_rock = (~assigned) & (sat < .28) & (val > .16) & (val < .72)
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
    return labels, {
        "format": "maqan-image-analysis/2", "width": int(w), "height": int(h),
        "source_sha256": hashlib.sha256(rgb.tobytes()).hexdigest(),
        "classifier": "palette-texture-context-v4",
        "classes": {name: int(counts[i]) for i, name in enumerate(CLASSES)},
        "class_percent": {name: round(100.0 * int(counts[i]) / total, 3) for i, name in enumerate(CLASSES)},
        "unknown_pixels": unknown, "unknown_percent": round(100.0 * unknown / total, 3),
        "classified_percent": round(100.0 * (total - unknown) / total, 3),
        "unknown_color_samples": unknown_color_samples,
        "water_model": {"ocean": "blue components connected to image boundary",
                        "lake": "enclosed component >=90 px and estimated width >=7 px",
                        "waterway": "remaining enclosed blue components"},
        "land_model": {"grassland": "olive/yellow-green and green palette, excluding water and snow",
                       "forest": "dark saturated green or locally textured canopy",
                       "taiga": "cool dark-green canopy with a latitude context prior",
                       "desert": "bright saturated ochre/sand palette",
                       "mountains": "darker brown/ochre relief palette",
                       "rock": "neutral shaded stone palette"},
        "notes": [
            "Deterministic palette/context analysis; not general-purpose AI image understanding.",
            "Semantic masks are mutually exclusive; water and snow are protected from later land rules.",
            "Land classes combine source-palette color families with local luminance texture; legend-specific calibration remains future work.",
            "Taiga uses a conservative latitude prior and should be validated against the source map legend.",
            "River/lake split is geometric and may need map-specific threshold calibration."
        ]
    }

def make_heightfield(labels, seed, sea_level=63, min_y=-64, max_y=320):
    """Create deterministic multi-scale relief with explicit class height anchors."""
    h, w = labels.shape
    rng = np.random.default_rng(seed)
    def field(cell):
        gh, gw = max(2, (h + cell - 1)//cell + 1), max(2, (w + cell - 1)//cell + 1)
        coarse = Image.fromarray(np.uint8(rng.random((gh, gw)) * 255), "L")
        return np.asarray(coarse.resize((w, h), Image.Resampling.BICUBIC), np.float32) / 255.0 - .5
    continental = ndimage.gaussian_filter(field(320), sigma=1.4, mode="reflect")
    regional = ndimage.gaussian_filter(field(88), sigma=1.0, mode="reflect")
    detail = ndimage.gaussian_filter(field(24), sigma=0.7, mode="reflect")
    anchors = {"ocean": sea_level-30, "lake": sea_level-2, "waterway": sea_level-1,
        "grassland": sea_level+7, "forest": sea_level+10, "taiga": sea_level+13,
        "desert": sea_level+6, "mountains": sea_level+38, "snow": sea_level+22,
        "rock": sea_level+28, "unknown": sea_level+7}
    # Multi-scale coherent noise gives continents, rolling regions and local
    # terrain variation without the harsh pixel-scale spikes of raw noise.
    anchor_lut = np.asarray([anchors[name] for name in CLASSES], dtype=np.float32)
    base = anchor_lut[labels]
    relief = base + continental*54 + regional*24 + detail*8
    mountain = (labels == CLASSES.index("mountains")) | (labels == CLASSES.index("rock"))
    mountain_distance = ndimage.distance_transform_edt(~mountain)
    mountain_influence = np.exp(-mountain_distance / 18.0).astype(np.float32)
    ridge_noise = ndimage.gaussian_filter(field(34), sigma=1.0, mode="reflect")
    ridge = 1.0 - np.abs(2.0 * (ridge_noise + .5) - 1.0)
    relief += mountain_influence * (ridge * 48 + np.maximum(0, regional) * 30)
    # Give coasts a shallow continental shelf and preserve lakes as inland
    # basins instead of accidentally assigning them deep-ocean elevations.
    ocean = labels == CLASSES.index("ocean")
    if np.any(ocean):
        ocean_depth = ndimage.distance_transform_edt(ocean)
        shelf_depth = 5.0 + np.minimum(ocean_depth * 1.55, 39.0)
        relief[ocean] = sea_level - shelf_depth[ocean] + continental[ocean] * 7.0
    lake = labels == CLASSES.index("lake")
    relief[lake] = sea_level - 2.5 + regional[lake] * 1.5
    relief[labels == CLASSES.index("waterway")] = sea_level - 1
    norm = np.clip((relief - min_y) / (max_y - min_y), 0, 1)
    return np.round(norm * 65535).astype(np.uint16)

def render_labels(labels):
    # Direct indexed palette expansion: O(N), with no per-class image scans.
    palette = np.asarray([COLORS[name] for name in CLASSES], dtype=np.uint8)
    return Image.fromarray(palette[labels], "RGB")

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
