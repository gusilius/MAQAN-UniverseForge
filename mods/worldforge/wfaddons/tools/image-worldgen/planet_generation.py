#!/usr/bin/env python3
"""Generate a planet for an Atlas dimension; an image is optional."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image
from scipy import ndimage
from image_map_analysis import CLASSES, render_labels
from image_worldgen import SETTLEMENT_PROFILES, convert_image

def procedural_planet_image(width: int, height: int, seed: int) -> Image.Image:
    rng = np.random.default_rng(seed)
    def octave(cell: int) -> np.ndarray:
        gh = max(2, (height + cell - 1) // cell + 1)
        gw = max(2, (width + cell - 1) // cell + 1)
        coarse = rng.random((gh, gw), dtype=np.float32)
        return ndimage.zoom(coarse, (height / gh, width / gw), order=3)[:height, :width]
    continents = octave(112)*0.55 + octave(48)*0.30 + octave(18)*0.15
    relief = octave(42)*0.55 + octave(15)*0.30 + octave(6)*0.15
    moisture = octave(58)*0.65 + octave(20)*0.35
    latitude = np.abs(np.linspace(-1.0, 1.0, height, dtype=np.float32))[:, None]
    land = continents > (0.485 + 0.055*latitude)
    temperature = 1.0 - latitude*0.72 - np.maximum(0.0, relief-0.72)*0.35
    biome = np.full((height,width), "ocean", dtype=object)
    biome[land] = "grassland"
    biome[land & (relief > 0.78)] = "mountains"
    biome[land & (relief > 0.70) & (relief <= 0.78)] = "rock"
    biome[land & (moisture < 0.27) & (temperature > 0.28)] = "desert"
    biome[land & (moisture > 0.62) & (temperature > 0.22)] = "forest"
    biome[land & (latitude > 0.58) & (moisture > 0.38)] = "taiga"
    biome[land & ((temperature < 0.17) | ((relief > 0.82) & (latitude > 0.30)))] = "snow"
    labels = np.full((height,width), CLASSES.index("ocean"), dtype=np.uint8)
    for name in ("grassland","forest","taiga","desert","mountains","snow","rock"):
        labels[biome == name] = CLASSES.index(name)
    if np.any(land):
        flow = ndimage.gaussian_filter(relief.astype(np.float32), sigma=2.2)
        rivers = land & (flow < np.quantile(flow[land], 0.11)) & (moisture > 0.34)
        labels[rivers] = CLASSES.index("waterway")
    return render_labels(labels)

def resolve_image(config: dict, dimension_id: str, explicit: Path | None) -> Path | None:
    if explicit is not None:
        return explicit if explicit.is_file() else None
    dimension = config.get("dimensions", {}).get(dimension_id, {})
    paths = []
    if dimension.get("image"):
        paths.append(Path(dimension["image"]))
    image_dir = Path(config.get("image_input_dir", "config/worldforge/images"))
    safe_id = dimension_id.replace(":", "_").replace("/", "_")
    paths.extend(image_dir / (safe_id + ext) for ext in (".png",".jpg",".jpeg",".webp"))
    return next((p for p in paths if p.is_file()), None)

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dimension-id", required=True)
    p.add_argument("--seed", type=int)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--config", type=Path, default=Path("config/worldforge/worldgen.json"))
    p.add_argument("--image", type=Path)
    p.add_argument("--settlement-level", type=int, choices=range(1,8))
    p.add_argument("--width", type=int)
    p.add_argument("--height", type=int)
    a = p.parse_args()
    cfg = json.loads(a.config.read_text(encoding="utf-8")) if a.config.is_file() else {}
    dim = cfg.get("dimensions", {}).get(a.dimension_id, {})
    seed = a.seed if a.seed is not None else int(dim.get("seed", cfg.get("default_seed", 424242)))
    level = a.settlement_level or int(dim.get("settlement_level", cfg.get("default_settlement_level", 4)))
    width = a.width or int(dim.get("width_px", cfg.get("default_width_px", 512)))
    height = a.height or int(dim.get("height_px", cfg.get("default_height_px", 256)))
    bpp = int(dim.get("blocks_per_pixel", cfg.get("default_blocks_per_pixel", 16)))
    if width < 64 or height < 64:
        p.error("world dimensions must be at least 64x64 pixels")
    if level not in SETTLEMENT_PROFILES:
        p.error("settlement level must be between 1 and 7")
    source = resolve_image(cfg, a.dimension_id, a.image)
    mode = "image" if source else "procedural"
    temporary = None
    if source is None:
        a.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = a.output.with_suffix(".generated-source.png")
        procedural_planet_image(width, height, seed).save(temporary, format="PNG", optimize=False)
        source = temporary
    profile = SETTLEMENT_PROFILES[level]
    try:
        result = convert_image(source, a.output, seed, profile["realms"], profile["provinces"],
                               bpp, level, a.dimension_id, mode)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    report = {"planet_id": a.dimension_id, "seed": seed, "generation_mode": mode,
              "settlement_level": level, "worldmap": str(a.output),
              "realm_count": result["realm_count"], "province_count": result["province_count"],
              "settlement_count": result["settlement_count"],
              "generation_seconds": result["generation_seconds"]}
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
