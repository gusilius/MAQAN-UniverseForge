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

def _priority_flood_hydrology(
    elevation: np.ndarray, land: np.ndarray, moisture: np.ndarray
) -> tuple[np.ndarray, np.ndarray]:
    """
    Build inland basins and a connected drainage network from a height field.

    Priority-Flood starts at every coast/outlet and raises only enclosed sinks
    to their spill elevation. A deterministic parent tree then carries runoff
    downhill. Work is bounded to a coarse hydrology lattice (at most 131,072
    cells); the resulting channels and lakes are expanded to the render grid.
    """
    import heapq

    height, width = land.shape
    area = height * width
    stride = max(1, int(np.ceil(np.sqrt(area / 131_072))))
    coarse_land = np.asarray(land[::stride, ::stride], dtype=bool)
    coarse_height = np.asarray(elevation[::stride, ::stride], dtype=np.float32)
    coarse_moisture = np.asarray(moisture[::stride, ::stride], dtype=np.float32)
    h, w = coarse_land.shape
    size = h * w
    if not np.any(coarse_land):
        return np.zeros((height, width), bool), np.zeros((height, width), bool)

    # A land cell touching ocean (or the map edge) is a valid sea-level outlet.
    adjacent_water = ndimage.binary_dilation(~coarse_land, structure=np.ones((3, 3), bool))
    outlets = coarse_land & adjacent_water
    outlets[0, :] |= coarse_land[0, :]
    outlets[-1, :] |= coarse_land[-1, :]
    outlets[:, 0] |= coarse_land[:, 0]
    outlets[:, -1] |= coarse_land[:, -1]
    if not np.any(outlets):
        outlets.flat[int(np.argmin(np.where(coarse_land, coarse_height, np.inf)))] = True

    flat_land = coarse_land.ravel()
    flat_height = coarse_height.ravel()
    filled = flat_height.copy()
    parent = np.full(size, -1, dtype=np.int64)
    visited = np.zeros(size, dtype=bool)
    queue: list[tuple[float, int]] = []
    for index in np.flatnonzero(outlets.ravel()):
        visited[index] = True
        heapq.heappush(queue, (float(flat_height[index]), int(index)))

    # Deterministic neighbour order gives byte-identical results for the same seed.
    offsets = (-w - 1, -w, -w + 1, -1, 1, w - 1, w, w + 1)
    while queue:
        level, current = heapq.heappop(queue)
        x, z = current % w, current // w
        for offset in offsets:
            neighbour = current + offset
            if neighbour < 0 or neighbour >= size:
                continue
            nx, nz = neighbour % w, neighbour // w
            if abs(nx - x) > 1 or abs(nz - z) > 1 or not flat_land[neighbour] or visited[neighbour]:
                continue
            visited[neighbour] = True
            parent[neighbour] = current
            next_level = max(float(flat_height[neighbour]), level + 1e-6)
            filled[neighbour] = next_level
            heapq.heappush(queue, (next_level, neighbour))

    # Every disconnected landmass has its own coast. This fallback is defensive
    # against malformed masks and prevents a component from silently losing runoff.
    for index in np.flatnonzero(flat_land & ~visited):
        visited[index] = True
        heapq.heappush(queue, (float(flat_height[index]), int(index)))
        while queue:
            level, current = heapq.heappop(queue)
            x, z = current % w, current // w
            for offset in offsets:
                neighbour = current + offset
                if neighbour < 0 or neighbour >= size:
                    continue
                nx, nz = neighbour % w, neighbour // w
                if abs(nx - x) > 1 or abs(nz - z) > 1 or not flat_land[neighbour] or visited[neighbour]:
                    continue
                visited[neighbour] = True
                parent[neighbour] = current
                next_level = max(float(flat_height[neighbour]), level + 1e-6)
                filled[neighbour] = next_level
                heapq.heappush(queue, (next_level, neighbour))

    depression_depth = np.maximum(0.0, filled - flat_height).reshape(h, w)
    depression = coarse_land & (depression_depth >= 0.035)
    components, count = ndimage.label(depression, structure=np.ones((3, 3), bool))
    lake = np.zeros((h, w), dtype=bool)
    if count:
        sizes = np.bincount(components.ravel(), minlength=count + 1)
        deep = ndimage.maximum(depression_depth, labels=components, index=np.arange(1, count + 1))
        # Lakes must be real basins, not one-cell numerical pits.
        accepted = np.zeros(count + 1, dtype=bool)
        accepted[1:] = (sizes[1:] >= 90) & (deep >= 0.055)
        lake = depression & accepted[components]

    accumulation = np.zeros(size, dtype=np.float64)
    accumulation[flat_land] = 1.0 + np.clip(coarse_moisture.ravel()[flat_land], 0.0, 1.0) * 2.0
    order = np.argsort(filled, kind="stable")[::-1]
    for index in order:
        downstream = parent[index]
        if downstream >= 0:
            accumulation[downstream] += accumulation[index]

    land_accumulation = accumulation[flat_land]
    if land_accumulation.size:
        threshold = max(18.0, float(np.quantile(land_accumulation, 0.992)))
        river = (coarse_land.ravel() & (accumulation >= threshold)).reshape(h, w)
        river &= ~lake
        # One-cell bank expansion makes channels visible at ordinary map zoom,
        # while preserving the tree's coastward continuity.
        river = ndimage.binary_dilation(river, structure=np.array(
            [[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=bool)) & coarse_land & ~lake
    else:
        river = np.zeros((h, w), dtype=bool)

    def expand(mask: np.ndarray) -> np.ndarray:
        return np.repeat(np.repeat(mask, stride, axis=0), stride, axis=1)[:height, :width]

    return expand(river), expand(lake)


def procedural_planet_image(width: int, height: int, seed: int) -> Image.Image:
    rng = np.random.default_rng(seed)

    def octave(cell: int) -> np.ndarray:
        gh = max(2, (height + cell - 1) // cell + 1)
        gw = max(2, (width + cell - 1) // cell + 1)
        coarse = rng.random((gh, gw), dtype=np.float32)
        return ndimage.zoom(coarse, (height / gh, width / gw), order=3)[:height, :width]

    # Low-frequency tectonic masses create continents; progressively finer
    # fields carve peninsulas, island arcs and small detached archipelagos.
    continental = octave(176) * 0.62 + octave(72) * 0.27 + octave(24) * 0.11
    relief = octave(56) * 0.55 + octave(20) * 0.30 + octave(7) * 0.15
    moisture = octave(64) * 0.65 + octave(22) * 0.35
    latitude = np.abs(np.linspace(-1.0, 1.0, height, dtype=np.float32))[:, None]
    threshold = 0.495 + 0.065 * latitude
    land = continental > threshold

    # Seeded island arcs occupy the continental shelf rather than appearing as
    # uniformly scattered dots in the middle of the ocean.
    island_field = octave(13)
    shelf = (continental > threshold - 0.075) & ~land
    island_arcs = shelf & (island_field > 0.79) & (latitude < 0.94)
    land |= island_arcs

    temperature = 1.0 - latitude * 0.72 - np.maximum(0.0, relief - 0.72) * 0.35
    biome = np.full((height, width), "ocean", dtype=object)
    biome[land] = "grassland"
    biome[land & (relief > 0.78)] = "mountains"
    biome[land & (relief > 0.70) & (relief <= 0.78)] = "rock"
    biome[land & (moisture < 0.27) & (temperature > 0.28)] = "desert"
    biome[land & (moisture > 0.62) & (temperature > 0.22)] = "forest"
    biome[land & (latitude > 0.58) & (moisture > 0.38)] = "taiga"
    biome[land & ((temperature < 0.17) | ((relief > 0.82) & (latitude > 0.30)))] = "snow"

    labels = np.full((height, width), CLASSES.index("ocean"), dtype=np.uint8)
    for name in ("grassland", "forest", "taiga", "desert", "mountains", "snow", "rock"):
        labels[biome == name] = CLASSES.index(name)

    # Rivers now follow a drainage basin and accumulated rainfall instead of
    # painting arbitrary low-noise pixels. Enclosed depressions become lakes.
    elevation = continental * 0.58 + relief * 0.42
    rivers, lakes = _priority_flood_hydrology(elevation, land, moisture)
    labels[lakes] = CLASSES.index("lake")
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
        result = convert_image(
            source, a.output, seed, realm_count=None, provinces_per_realm=None,
            blocks_per_pixel=bpp, settlement_level=level,
            planet_id=a.dimension_id, generation_mode=mode)
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
