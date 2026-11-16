#!/usr/bin/env python3
"""Deterministic image-to-WorldForge blueprint prototype.

Input contract for this first proof: a PNG map painted with the documented
semantic palette below. This is deliberately not advertised as arbitrary-image
AI recognition. The output is a normal .worldmap package plus a MAQAN blueprint.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
import time
import traceback
import zipfile
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw
import numpy as np

from image_map_analysis import CLASSES as ANALYSIS_CLASSES, classify, make_heightfield

GENERATOR_VERSION = "maqan-image-blueprint/0.7.0"
BIOMES = [
    {"index": 0, "key": "deep_ocean", "color": "#315f85", "candidates": ["minecraft:deep_ocean", "minecraft:ocean"]},
    {"index": 1, "key": "plains", "color": "#dac78f", "candidates": ["minecraft:plains"]},
    {"index": 2, "key": "forest", "color": "#306942", "candidates": ["minecraft:forest", "minecraft:birch_forest"]},
    {"index": 3, "key": "mountains", "color": "#858480", "candidates": ["minecraft:windswept_hills", "minecraft:stony_peaks"]},
    {"index": 4, "key": "desert", "color": "#cca053", "candidates": ["minecraft:desert"]},
    {"index": 5, "key": "river", "color": "#2f77be", "candidates": ["minecraft:river"]},
    {"index": 6, "key": "taiga", "color": "#14422b", "candidates": ["minecraft:taiga", "minecraft:old_growth_pine_taiga"]},
    {"index": 7, "key": "snow", "color": "#e8f1f5", "candidates": ["minecraft:snowy_plains", "minecraft:snowy_slopes"]},
    {"index": 8, "key": "lake", "color": "#2d82be", "candidates": ["minecraft:river", "minecraft:ocean"]},
    {"index": 9, "key": "rock", "color": "#67635b", "candidates": ["minecraft:stony_peaks", "minecraft:windswept_hills"]},
]
BIOME_INDEX = {name: i for i, name in enumerate(
    ("deep_ocean", "plains", "forest", "mountains", "desert", "river", "taiga", "snow", "lake", "rock"))}
ANALYSIS_TO_WORLD = {
    "ocean": "deep_ocean", "lake": "lake", "waterway": "river",
    "grassland": "plains", "forest": "forest", "taiga": "taiga",
    "desert": "desert", "mountains": "mountains", "snow": "snow",
    "rock": "rock", "unknown": "unknown",
}
LAND_CLASSES = {"plains", "forest", "taiga", "mountains", "desert", "snow", "rock"}
WATER_CLASSES = {"deep_ocean", "lake", "river"}
NAME_START = ("A", "Bel", "Cor", "Dor", "Eld", "Fen", "Gal", "Har", "Ith", "Kel", "Lor", "Mor", "Nor", "Or", "Pel", "Quel")

NAME_END = ("aria", "en", "oria", "gard", "mere", "hold", "wyn", "mark", "eth", "a", "or", "heim")

# Settlement density is independent from government form, technology and magic.
SETTLEMENT_PROFILES = {
    1: {"key": "wild_world", "wilderness_share": 0.92, "population_density": 0.02},
    2: {"key": "early_settlements", "wilderness_share": 0.82, "population_density": 0.08},
    3: {"key": "regional_civilizations", "wilderness_share": 0.68, "population_density": 0.20},
    4: {"key": "developed_states", "wilderness_share": 0.16, "population_density": 0.38},
    5: {"key": "dense_civilization", "wilderness_share": 0.08, "population_density": 0.58},
    6: {"key": "highly_developed", "wilderness_share": 0.00, "population_density": 0.78},
    7: {"key": "planetary_civilization", "wilderness_share": 0.00, "population_density": 1.00},
}


def stable_name(seed: int, index: int, suffix: str = "") -> str:
    rng = random.Random((seed & 0xFFFFFFFFFFFFFFFF) ^ (index * 0x9E3779B97F4A7C15))
    return rng.choice(NAME_START) + rng.choice(NAME_END) + suffix



def refine_open_coastline(labels: np.ndarray, seed: int) -> tuple[np.ndarray, dict]:
    """Gently round and perturb open-ocean contours; inland water is untouched."""
    from scipy import ndimage
    labels = np.asarray(labels, dtype=np.uint8).copy()
    ocean_code = ANALYSIS_CLASSES.index("ocean")
    land_codes = [ANALYSIS_CLASSES.index(n) for n in
                  ("grassland", "forest", "taiga", "desert", "mountains", "snow", "rock")]
    ocean, land = labels == ocean_code, np.isin(labels, land_codes)
    if not np.any(ocean) or not np.any(land):
        return labels, {"changed_pixels": 0, "model": "signed-distance-coast-v1"}
    signed = (ndimage.distance_transform_edt(land) -
              ndimage.distance_transform_edt(ocean)).astype(np.float32)
    band = (np.abs(signed) <= 3.25) & (ocean | land)
    if not np.any(band):
        return labels, {"changed_pixels": 0, "model": "signed-distance-coast-v1"}
    rng = np.random.default_rng((int(seed) & 0xFFFFFFFFFFFFFFFF) ^ 0x434F4153544C494E)
    def noise(cell):
        gh = max(2, (labels.shape[0] + cell - 1) // cell + 1)
        gw = max(2, (labels.shape[1] + cell - 1) // cell + 1)
        coarse = Image.fromarray(rng.integers(0, 256, (gh, gw), dtype=np.uint8), "L")
        return np.asarray(coarse.resize((labels.shape[1], labels.shape[0]),
            Image.Resampling.BICUBIC), dtype=np.float32) / 255.0 - 0.5
    smooth = ndimage.gaussian_filter(signed, sigma=0.8, mode="nearest")
    displacement = noise(48) * 1.5 + noise(17) * 0.55
    candidate_land = land.copy()
    candidate_land[band] = smooth[band] + displacement[band] >= 0.0
    # Protect tiny islands: they may be real map features, not anti-alias noise.
    component_ids, component_count = ndimage.label(land, structure=np.ones((3, 3), dtype=np.uint8))
    if component_count:
        sizes = np.bincount(component_ids.ravel(), minlength=component_count + 1)
        candidate_land[(component_ids > 0) & (sizes[component_ids] <= 8)] = True
    candidate_ocean = ocean.copy()
    candidate_ocean[band] = ~candidate_land[band]
    edge = np.zeros(ocean.shape, dtype=bool)
    edge[0] = candidate_ocean[0]; edge[-1] = candidate_ocean[-1]
    edge[:, 0] = candidate_ocean[:, 0]; edge[:, -1] = candidate_ocean[:, -1]
    connected = ndimage.binary_propagation(edge, mask=candidate_ocean,
        structure=np.array([[0,1,0],[1,1,1],[0,1,0]], dtype=bool))
    # Never close a source-map strait or disconnect an original ocean basin.
    if np.any(ocean & ~connected):
        return labels, {"changed_pixels": 0, "model": "signed-distance-coast-v1",
                        "reverted_to_preserve_ocean_connectivity": True}
    changed = land & connected
    labels[changed] = ocean_code
    return labels, {"changed_pixels": int(changed.sum()),
                    "model": "signed-distance-coast-v1",
                    "max_contour_displacement_pixels": 1.025,
                    "small_island_preservation_pixels": 8}


def geography_regression_tests() -> dict:
    """Fast deterministic guards for coast connectivity and watershed label integrity."""
    from scipy import ndimage
    test = np.full((96, 128), ANALYSIS_CLASSES.index("ocean"), dtype=np.uint8)
    test[18:78, 24:104] = ANALYSIS_CLASSES.index("grassland")
    test[8:10, 60:63] = ANALYSIS_CLASSES.index("forest")
    coast_a, report = refine_open_coastline(test, 424242)
    coast_b, _ = refine_open_coastline(test, 424242)
    if not np.array_equal(coast_a, coast_b):
        raise AssertionError("Coast refinement is not deterministic.")
    if not np.all(coast_a[8:10, 60:63] != ANALYSIS_CLASSES.index("ocean")):
        raise AssertionError("Coast refinement erased a small island.")
    ocean = coast_a == ANALYSIS_CLASSES.index("ocean")
    edge = np.zeros(ocean.shape, dtype=bool)
    edge[0] = ocean[0]; edge[-1] = ocean[-1]
    edge[:, 0] = ocean[:, 0]; edge[:, -1] = ocean[:, -1]
    connected = ndimage.binary_propagation(edge, mask=ocean,
        structure=np.array([[0,1,0],[1,1,1],[0,1,0]], dtype=bool))
    if not np.array_equal(ocean, connected):
        raise AssertionError("Coast refinement disconnected open ocean.")
    width, height = 14, 12
    cells = np.asarray([z * width + x for z in range(1, 11)
                        for x in range(1, 13)], dtype=np.int32)
    scratch = np.full(width * height, -2, dtype=np.int32)
    boundary = np.zeros((height, width), dtype=np.uint8)
    boundary[:, 6] = 240
    labels, _ = partition(cells, [3 * width + 3, 8 * width + 10],
                          width, height, 7, scratch, boundary)
    if not set(np.unique(labels[cells]).tolist()).issubset({7, 8}):
        raise AssertionError("Watershed emitted an unregistered territory label.")
    return {"coast_changed_pixels": report["changed_pixels"],
            "ocean_connectivity": "PASS", "watershed_registered_labels": "PASS"}


def png_pixels(image: Image.Image) -> tuple[int, int, np.ndarray, dict]:
    """Use the shared contextual analyzer; reject ambiguous maps instead of guessing."""
    rgb = np.asarray(image.convert("RGB"), dtype=np.uint8)
    h, w = rgb.shape[:2]
    labels, report = classify(rgb)
    if report["unknown_percent"] > 2.0:
        raise ValueError(
            f"Map contains {report['unknown_percent']:.3f}% unknown pixels; "
            "calibrate its legend before world generation."
        )
    return w, h, labels, report


def neighbors(index: int, width: int, height: int):
    x, z = index % width, index // width
    if x > 0:
        yield index - 1
    if x + 1 < width:
        yield index + 1
    if z > 0:
        yield index - width
    if z + 1 < height:
        yield index + width


def checked_bincount(values: np.ndarray, context: str, minlength: int = 0) -> np.ndarray:
    """Fail at the source with useful diagnostics instead of NumPy's opaque error."""
    values = np.asarray(values)
    if values.size and int(values.min()) < 0:
        negative = values[values < 0]
        raise ValueError(
            f"{context}: bincount received {negative.size} negative labels "
            f"(min={int(negative.min())}, max={int(values.max())})"
        )
    return np.bincount(values, minlength=minlength)


def land_components(analysis_labels: np.ndarray, width: int, height: int,
                    land_mask: np.ndarray | None = None) -> list[list[int]]:
    """Connected political landmasses via SciPy's native labeling implementation."""
    from scipy import ndimage
    if land_mask is None:
        land_codes = [i for i, name in enumerate(ANALYSIS_CLASSES)
                      if ANALYSIS_TO_WORLD[name] in LAND_CLASSES]
        land = np.isin(analysis_labels.reshape((height, width)), land_codes)
    else:
        land = np.asarray(land_mask, dtype=bool).reshape((height, width))
    labels, count = ndimage.label(land, structure=np.array([[0, 1, 0],
                                                            [1, 1, 1],
                                                            [0, 1, 0]], dtype=np.uint8))
    if count == 0:
        return []
    flat = labels.ravel()
    sizes = checked_bincount(flat, "land_components.component_labels", minlength=count + 1)
    order = np.argsort(flat, kind="stable")
    offsets = np.cumsum(sizes)
    result = []
    for component_id in range(1, count + 1):
        start, end = int(offsets[component_id - 1]), int(offsets[component_id])
        result.append(order[start:end].astype(np.int64).tolist())
    result.sort(key=lambda cells: (-len(cells), cells[0]))
    return result


def farthest_seeds(cells: list[int], count: int, width: int, seed: int) -> list[int]:
    """Deterministic bounded-pool farthest sampling: O(N + 32*K^2), not O(N*K)."""
    if not cells:
        return []
    count = max(1, min(count, len(cells)))
    if count == 1:
        return [cells[len(cells) // 2]]
    rng = random.Random((seed & 0xFFFFFFFFFFFFFFFF) ^ (len(cells) * 0x9E3779B97F4A7C15))
    candidates = sorted(rng.sample(cells, min(len(cells), max(count * 32, 256))))
    cx = sum(i % width for i in candidates) / len(candidates)
    cz = sum(i // width for i in candidates) / len(candidates)
    first = min(candidates, key=lambda i: ((i % width - cx) ** 2 + (i // width - cz) ** 2, i))
    selected, used = [first], {first}
    min_dist = [(i % width - first % width) ** 2 + (i // width - first // width) ** 2 for i in candidates]
    while len(selected) < count:
        pos = max(range(len(candidates)), key=lambda j: (min_dist[j], -candidates[j]))
        best = candidates[pos]
        if best in used:
            break
        selected.append(best); used.add(best)
        sx, sz = best % width, best // width
        for j, i in enumerate(candidates):
            d = (i % width - sx) ** 2 + (i // width - sz) ** 2
            if d < min_dist[j]:
                min_dist[j] = d
    return selected


def _partition_kernel(labels, cells, seeds, width, height, first_label):
    """Reusable flat-array BFS. -2=outside component, -1=unvisited component cell."""
    for i in range(cells.size):
        labels[cells[i]] = -1
    queue = np.empty(cells.size, dtype=np.int32)
    head = tail = 0
    for offset in range(seeds.size):
        start = seeds[offset]
        labels[start] = first_label + offset
        queue[tail] = start
        tail += 1
    while head < tail:
        current = queue[head]
        head += 1
        label = labels[current]
        x = current % width
        if x > 0:
            nxt = current - 1
            if labels[nxt] == -1:
                labels[nxt] = label; queue[tail] = nxt; tail += 1
        if x + 1 < width:
            nxt = current + 1
            if labels[nxt] == -1:
                labels[nxt] = label; queue[tail] = nxt; tail += 1
        if current >= width:
            nxt = current - width
            if labels[nxt] == -1:
                labels[nxt] = label; queue[tail] = nxt; tail += 1
        if current + width < labels.size:
            nxt = current + width
            if labels[nxt] == -1:
                labels[nxt] = label; queue[tail] = nxt; tail += 1
    return labels


NUMBA_ACCELERATED = False
try:
    from numba import njit
    _partition_kernel = njit(cache=True)(_partition_kernel)
    NUMBA_ACCELERATED = True
except ImportError:
    pass


def partition(cells, seeds, width: int, height: int, first_label: int,
             scratch: np.ndarray, boundary_cost: np.ndarray | None = None):
    """Partition a connected landmass using a masked, terrain-aware watershed."""
    cell_indices = np.asarray(cells, dtype=np.int64)
    seed_indices = np.asarray(seeds, dtype=np.int64)
    if boundary_cost is None:
        labels = _partition_kernel(scratch, cell_indices.astype(np.int32),
                                   seed_indices.astype(np.int32), width, height, first_label)
    else:
        from scipy import ndimage
        xs, zs = cell_indices % width, cell_indices // width
        x0, x1 = int(xs.min()), int(xs.max()) + 1
        z0, z1 = int(zs.min()), int(zs.max()) + 1
        # No synthetic exterior marker: it could leak a dummy ID into coastal
        # cells. Territory pixels cost <=254; surrounding water costs 255, so
        # the connected land basin is assigned before the flood reaches water.
        markers = np.zeros((z1 - z0, x1 - x0), dtype=np.int32)
        local_x, local_z = xs - x0, zs - z0
        seed_x, seed_z = seed_indices % width - x0, seed_indices // width - z0
        for offset, (sx, sz) in enumerate(zip(seed_x, seed_z)):
            markers[int(sz), int(sx)] = first_label + offset + 1
        cost = np.full((z1 - z0, x1 - x0), 255, dtype=np.uint8)
        local_cost = boundary_cost[z0:z1, x0:x1]
        cost[local_z, local_x] = np.minimum(local_cost[local_z, local_x], 254)
        result = ndimage.watershed_ift(cost, markers, structure=np.asarray(
            [[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=np.uint8))
        assigned = result[local_z, local_x].astype(np.int32) - 1
        if (np.any(assigned < first_label) or
                np.any(assigned >= first_label + len(seed_indices))):
            raise ValueError("Watershed produced an unregistered territory label.")
        scratch[cell_indices] = assigned
        labels = scratch
    if np.count_nonzero(labels[cell_indices] >= 0) != cell_indices.size:
        raise ValueError("Territory partition left disconnected land unassigned.")
    return labels, seeds


def make_boundary_cost(analysis_grid: np.ndarray, seed: int) -> np.ndarray:
    """Build a smooth, deterministic watershed surface with biome-edge guidance."""
    from scipy import ndimage
    h, w = analysis_grid.shape
    rng = np.random.default_rng(seed ^ 0x424F554E44415259)
    def field(cell: int) -> np.ndarray:
        gh, gw = max(2, (h + cell - 1) // cell + 1), max(2, (w + cell - 1) // cell + 1)
        coarse = Image.fromarray(rng.integers(0, 256, (gh, gw), dtype=np.uint8), "L")
        return np.asarray(coarse.resize((w, h), Image.Resampling.BICUBIC), dtype=np.float32) / 255.0
    broad = ndimage.gaussian_filter(field(72), sigma=1.2, mode="reflect")
    detail = ndimage.gaussian_filter(field(28), sigma=0.8, mode="reflect")
    edge = np.zeros((h, w), dtype=bool)
    edge[:, 1:] |= analysis_grid[:, 1:] != analysis_grid[:, :-1]
    edge[:, :-1] |= analysis_grid[:, 1:] != analysis_grid[:, :-1]
    edge[1:, :] |= analysis_grid[1:, :] != analysis_grid[:-1, :]
    edge[:-1, :] |= analysis_grid[1:, :] != analysis_grid[:-1, :]
    edge_strength = ndimage.gaussian_filter(edge.astype(np.float32), sigma=1.4, mode="nearest")
    mountain = np.isin(analysis_grid, [ANALYSIS_CLASSES.index("mountains"),
        ANALYSIS_CLASSES.index("rock"), ANALYSIS_CLASSES.index("snow")]).astype(np.float32)
    ridge = ndimage.gaussian_filter(mountain, sigma=2.2, mode="nearest")
    river = analysis_grid == ANALYSIS_CLASSES.index("waterway")
    river_bank = ndimage.binary_dilation(river, iterations=1).astype(np.float32)
    surface = broad * 0.49 + detail * 0.17 + edge_strength * 0.13 + ridge * 0.13 + river_bank * 0.08
    return np.asarray(np.clip(surface * 255.0, 0, 255), dtype=np.uint8)




def allocate_counts(components: list[list[int]], total: int,
                    minimum_pixels: int = 1) -> list[int]:
    """Allocate realm seeds proportionally while respecting each landmass capacity."""
    if not components:
        return []
    if total < len(components):
        raise ValueError("Every major landmass must receive at least one realm seed.")
    capacities = [max(1, len(component) // max(1, minimum_pixels))
                  for component in components]
    capacity_total = sum(capacities)
    if total > capacity_total:
        raise ValueError(
            f"Requested {total} realms but landmasses support at most "
            f"{capacity_total} at minimum size {minimum_pixels}."
        )
    counts = [1] * len(components)
    remaining = total - len(components)
    while remaining:
        eligible = [i for i in range(len(components)) if counts[i] < capacities[i]]
        if not eligible:
            raise ValueError("Realm allocation exhausted all landmass capacities.")
        weight_total = sum(len(components[i]) for i in eligible)
        quotas = {i: remaining * len(components[i]) / weight_total for i in eligible}
        assigned = 0
        for i in eligible:
            extra = min(capacities[i] - counts[i], int(math.floor(quotas[i])))
            if extra:
                counts[i] += extra
                assigned += extra
        remaining -= assigned
        if remaining:
            eligible = [i for i in eligible if counts[i] < capacities[i]]
            if not eligible:
                continue
            target = max(eligible, key=lambda i: (
                quotas.get(i, 0.0) - math.floor(quotas.get(i, 0.0)),
                len(components[i]), -i))
            counts[target] += 1
            remaining -= 1
    return counts


def select_independent_islands(components: list[list[int]], seed: int,
                               budget: int, minimum_pixels: int) -> set[int]:
    """Choose viable island city-states deterministically, without random dropouts."""
    candidates = [c for c in components if len(c) >= minimum_pixels]
    def stable_rank(component: list[int]) -> tuple[int, int, int]:
        selector = (seed ^ (component[0] * 0x9E3779B97F4A7C15) ^ len(component)) & 0xFFFFFFFF
        return (-len(component), selector, component[0])
    candidates.sort(key=stable_rank)
    return {component[0] for component in candidates[:max(0, budget)]}



def aggregate_realm_regions(realm_labels: np.ndarray, realm_seeds: dict[int, int],
                            width: int, height: int, seed: int,
                            minimum_pixels: int, desired_fraction: float = 0.58,
                            max_area_share: float = 0.30,
                            analysis_grid: np.ndarray | None = None) -> tuple[np.ndarray, dict[int, int], dict]:
    """Merge adjacent seed-regions into organically sized, connected polities."""
    active = np.flatnonzero(realm_labels >= 0)
    if not active.size:
        return realm_labels, {}, {"initial_regions": 0, "merged_regions": 0}
    ids, counts = np.unique(realm_labels[active], return_counts=True)
    ids = ids.astype(np.int64)
    area = {int(r): int(n) for r, n in zip(ids, counts)}
    parent = {int(r): int(r) for r in ids}
    members = {int(r): [int(r)] for r in ids}
    group_area = dict(area)
    grid = realm_labels.reshape((height, width))
    h_left, h_right = grid[:, :-1], grid[:, 1:]
    v_top, v_bottom = grid[:-1, :], grid[1:, :]
    h_mask = (h_left >= 0) & (h_right >= 0) & (h_left != h_right)
    v_mask = (v_top >= 0) & (v_bottom >= 0) & (v_top != v_bottom)
    pair_parts, affinity_parts = [], []
    affinity_grid = None
    if analysis_grid is not None:
        lut = np.zeros(len(ANALYSIS_CLASSES), dtype=np.float32)
        for name, value in {"grassland": 1.0, "forest": 0.55, "taiga": 0.25,
                            "desert": 0.15, "mountains": -0.85, "rock": -0.70,
                            "snow": -0.45, "waterway": -0.30}.items():
            lut[ANALYSIS_CLASSES.index(name)] = value
        affinity_grid = lut[np.asarray(analysis_grid, dtype=np.uint8)]
    if np.any(h_mask):
        pair_parts.append(np.column_stack((h_left[h_mask], h_right[h_mask])))
        if affinity_grid is not None:
            affinity_parts.append((affinity_grid[:, :-1][h_mask] + affinity_grid[:, 1:][h_mask]) * 0.5)
    if np.any(v_mask):
        pair_parts.append(np.column_stack((v_top[v_mask], v_bottom[v_mask])))
        if affinity_grid is not None:
            affinity_parts.append((affinity_grid[:-1, :][v_mask] + affinity_grid[1:, :][v_mask]) * 0.5)
    pairs = np.concatenate(pair_parts, axis=0) if pair_parts else np.empty((0, 2), dtype=np.int64)
    if not pairs.size or len(ids) < 2:
        return realm_labels, dict(realm_seeds), {
            "initial_regions": int(len(ids)), "merged_regions": int(len(ids)), "merge_operations": 0}
    pairs = np.sort(pairs.astype(np.int64), axis=1)
    unique_pairs, inverse, border_lengths = np.unique(pairs, axis=0, return_inverse=True, return_counts=True)
    if affinity_parts:
        edge_affinity = np.concatenate(affinity_parts)
        pair_affinity = np.bincount(inverse, weights=edge_affinity,
                                    minlength=len(unique_pairs)) / np.maximum(1, border_lengths)
    else:
        pair_affinity = np.zeros(len(unique_pairs), dtype=np.float64)

    def find(value: int) -> int:
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    desired_count = max(1, int(math.ceil(len(ids) * desired_fraction)))
    # A pair of viable seed-regions must be mergeable on small maps;
    # large maps remain bounded by the configured world-area share.
    world_cap = max(minimum_pixels * 8, int(active.size * max_area_share))
    edges = []
    for edge_index, ((a, b), border) in enumerate(zip(unique_pairs, border_lengths)):
        a, b = int(a), int(b)
        jitter = ((seed ^ (a * 0x9E3779B1) ^ (b * 0x85EBCA77)) & 0xFFFF) / 65535.0
        # Terrain biases frontiers but must not prevent ordinary consolidation.
        terrain_barrier = -float(pair_affinity[edge_index]) * 0.035
        score = ((area[a] + area[b]) / max(1, world_cap) -
                 min(1.0, int(border) / 64.0) * 0.10 +
                 terrain_barrier + jitter * 0.025)
        edges.append((score, a, b))
    edges.sort()
    merges, current_count = 0, len(ids)
    # First absorb undersized seed territories. Without this pass a perfectly
    # connected continent can retain one-pixel "states" when the global merge
    # target is reached, even though those fragments have an obvious neighbor.
    # The cap is relaxed only for the tiny side of such a merge; the resulting
    # polity grows by at most the undersized fragment's area.
    for _, a, b in edges:
        ra, rb = find(a), find(b)
        if ra == rb:
            continue
        area_a, area_b = group_area[ra], group_area[rb]
        if area_a >= minimum_pixels and area_b >= minimum_pixels:
            continue
        combined = area_a + area_b
        if combined > world_cap and min(area_a, area_b) >= minimum_pixels:
            continue
        if (area_a, -ra) < (area_b, -rb):
            ra, rb = rb, ra
        parent[rb] = ra
        group_area[ra] = combined
        members[ra].extend(members.pop(rb))
        del group_area[rb]
        current_count -= 1
        merges += 1

    for _, a, b in edges:
        if current_count <= desired_count:
            break
        ra, rb = find(a), find(b)
        if ra == rb:
            continue
        combined = group_area[ra] + group_area[rb]
        if combined > world_cap:
            continue
        if (group_area[ra], -ra) < (group_area[rb], -rb):
            ra, rb = rb, ra
        parent[rb] = ra
        group_area[ra] = combined
        members[ra].extend(members.pop(rb))
        del group_area[rb]
        current_count -= 1
        merges += 1

    roots = sorted({find(int(r)) for r in ids}, key=lambda root: min(members[root]))
    remap = np.full(int(ids.max()) + 1, -1, dtype=np.int32)
    new_seeds, root_areas = {}, []
    scales = {"tiny": 0, "small": 0, "medium": 0, "large": 0, "huge": 0}
    for new_id, root in enumerate(roots):
        old_ids = members[root]
        for old_id in old_ids:
            remap[old_id] = new_id
        capital_source = max(old_ids, key=lambda old_id: (area[old_id], -old_id))
        new_seeds[new_id] = int(realm_seeds[capital_source])
        size = group_area[root]
        root_areas.append(size)
        share = 100.0 * size / max(1, active.size)
        scale = ("tiny" if share < 0.6 else "small" if share < 2.5 else
                 "medium" if share < 7.5 else "large" if share < 18.0 else "huge")
        scales[scale] += 1
    valid = realm_labels >= 0
    realm_labels[valid] = remap[realm_labels[valid]]
    return realm_labels, new_seeds, {
        "initial_regions": int(len(ids)), "merged_regions": int(len(roots)),
        "merge_operations": int(merges), "target_regions": int(desired_count),
        "merge_area_cap_pixels": int(world_cap),
        "minimum_seed_region_pixels": int(minimum_pixels),
        "undersized_regions_after_merge": int(sum(area < minimum_pixels for area in root_areas)),
        "realm_scale_counts": scales,
        "realm_area_pixels": {"min": int(min(root_areas)), "max": int(max(root_areas)),
                              "mean": round(float(np.mean(root_areas)), 2)}
    }


def select_wilderness_mask(analysis_labels: np.ndarray, width: int, height: int,
                           share: float, seed: int) -> np.ndarray:
    """Select coherent, terrain-biased wilderness reserves, never random border gaps."""
    from scipy import ndimage
    labels = np.asarray(analysis_labels, dtype=np.uint8).reshape((height, width))
    land_codes = [i for i, name in enumerate(ANALYSIS_CLASSES)
                  if ANALYSIS_TO_WORLD[name] in LAND_CLASSES]
    land = np.isin(labels, land_codes)
    target = float(np.clip(share, 0.0, 0.95))
    if target <= 0.0 or not np.any(land):
        return np.zeros((height, width), dtype=bool)

    rng = np.random.default_rng(seed ^ 0x57494C4445524E45)
    def coherent_noise(cell: int) -> np.ndarray:
        gh = max(2, (height + cell - 1) // cell + 1)
        gw = max(2, (width + cell - 1) // cell + 1)
        coarse = Image.fromarray(rng.integers(0, 256, (gh, gw), dtype=np.uint8), "L")
        return np.asarray(coarse.resize((width, height), Image.Resampling.BICUBIC),
                          dtype=np.float32) / 255.0

    broad = coherent_noise(112)
    detail = coherent_noise(40)
    rugged = np.isin(labels, [ANALYSIS_CLASSES.index(n)
                              for n in ("mountains", "snow", "rock", "taiga")]).astype(np.float32)
    desert = (labels == ANALYSIS_CLASSES.index("desert")).astype(np.float32)
    score = np.clip(broad * 0.62 + detail * 0.16 + rugged * 0.17 + desert * 0.05, 0, 1)
    hist, edges = np.histogram(score[land], bins=2048, range=(0.0, 1.0))
    target_pixels = int(round(int(land.sum()) * target))
    cumulative = np.cumsum(hist[::-1])
    bin_from_high = min(len(hist) - 1, int(np.searchsorted(cumulative, target_pixels)))
    threshold = float(edges[len(edges) - 2 - bin_from_high])
    wilderness = land & (score >= threshold)
    # Round reserve edges at map scale. This is a dedicated wilderness mask,
    # not a post-hoc gap carved between political neighbors.
    smooth = ndimage.gaussian_filter(wilderness.astype(np.float32), sigma=2.2, mode="nearest")
    wilderness = (smooth >= 0.48) & land
    return wilderness


def split_disconnected_provinces(province_labels: np.ndarray,
                                  province_meta: dict[int, dict],
                                  realm_provinces: dict[int, list[int]],
                                  width: int, height: int) -> tuple[np.ndarray, dict, dict, dict]:
    """Split provinces cut by wilderness into connected holdings of the same realm."""
    from scipy import ndimage
    flat = np.asarray(province_labels, dtype=np.uint32).reshape(-1)
    valid = np.flatnonzero(flat > 0)
    if not valid.size:
        return flat, province_meta, realm_provinces, {rid: [] for rid in realm_provinces}
    order = valid[np.argsort(flat[valid], kind="stable")]
    ids, counts = np.unique(flat[valid], return_counts=True)
    offsets = np.concatenate(([0], np.cumsum(counts)))
    next_id = int(flat.max()) + 1
    structure = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=np.uint8)
    for index, raw_pid in enumerate(ids):
        pid = int(raw_pid)
        cells = order[offsets[index]:offsets[index + 1]]
        z, x = cells // width, cells % width
        x0, x1 = int(x.min()), int(x.max()) + 1
        z0, z1 = int(z.min()), int(z.max()) + 1
        local = np.zeros((z1 - z0, x1 - x0), dtype=bool)
        local[z - z0, x - x0] = True
        labels, component_count = ndimage.label(local, structure=structure)
        meta = province_meta[pid]
        if component_count <= 1:
            meta["area"] = int(cells.size)
            continue
        seed = int(meta["seed"])
        sx, sz = seed % width, seed // width
        keep = int(labels[sz - z0, sx - x0]) if (
            x0 <= sx < x1 and z0 <= sz < z1) else 0
        if keep == 0:
            sizes = np.bincount(labels.ravel(), minlength=component_count + 1)
            keep = int(1 + np.argmax(sizes[1:]))
        for component_id in range(1, component_count + 1):
            lz, lx = np.nonzero(labels == component_id)
            component_cells = (lz + z0) * width + (lx + x0)
            if component_id == keep:
                meta["area"] = int(component_cells.size)
                if seed not in component_cells:
                    cx, cz = float((lx + x0).mean()), float((lz + z0).mean())
                    nearest = int(np.argmin((lx + x0 - cx) ** 2 + (lz + z0 - cz) ** 2))
                    meta["seed"] = int(component_cells[nearest])
                continue
            new_id = next_id
            next_id += 1
            flat[component_cells] = new_id
            new_meta = dict(meta)
            new_meta["area"] = int(component_cells.size)
            cx, cz = float((lx + x0).mean()), float((lz + z0).mean())
            nearest = int(np.argmin((lx + x0 - cx) ** 2 + (lz + z0 - cz) ** 2))
            new_meta["seed"] = int(component_cells[nearest])
            new_meta["key"] = f"{meta['key']}_fragment_{component_id:02d}"
            province_meta[new_id] = new_meta
            realm_provinces.setdefault(int(meta["realm"]), []).append(new_id)

    valid = np.flatnonzero(flat > 0)
    order = valid[np.argsort(flat[valid], kind="stable")]
    ids, counts = np.unique(flat[valid], return_counts=True)
    offsets = np.concatenate(([0], np.cumsum(counts)))
    cells_by_realm: dict[int, list[int]] = {rid: [] for rid in realm_provinces}
    for index, raw_pid in enumerate(ids):
        pid = int(raw_pid)
        cells = order[offsets[index]:offsets[index + 1]]
        meta = province_meta[pid]
        meta["area"] = int(cells.size)
        cells_by_realm.setdefault(int(meta["realm"]), []).extend(cells.tolist())
    return flat, province_meta, realm_provinces, cells_by_realm


def apply_province_aligned_wilderness(province_labels: np.ndarray,
                                        province_meta: dict[int, dict],
                                        realm_provinces: dict[int, list[int]],
                                        wilderness_candidates: np.ndarray,
                                        width: int, height: int) -> tuple[np.ndarray, dict, dict, dict, np.ndarray, dict]:
    """Reserve whole provinces as wilderness; never fracture a political holding.

    The coherent pixel-level reserve is reduced to a province priority map. A
    realm's first province is its protected capital core, so every generated
    polity retains a meaningful connected territory at every settlement level.
    """
    flat = np.asarray(province_labels, dtype=np.uint32).reshape(-1).copy()
    candidates = np.asarray(wilderness_candidates, dtype=bool).reshape((height, width))
    valid = np.flatnonzero(flat > 0)
    if not valid.size:
        return flat, province_meta, realm_provinces, {}, candidates, {
            "selected_provinces": 0, "protected_capital_provinces": 0,
            "target_pixels": int(candidates.sum()), "actual_pixels": int(candidates.sum()),
            "model": "province-aligned-coherent-reserves-v1",
        }

    order = valid[np.argsort(flat[valid], kind="stable")]
    ids, counts = np.unique(flat[valid], return_counts=True)
    offsets = np.concatenate(([0], np.cumsum(counts)))
    protected = {int(pids[0]) for pids in realm_provinces.values() if pids}
    target_pixels = int(candidates.sum())
    unassigned_reserve = candidates.ravel().copy()
    unassigned_reserve[valid] = False
    target_from_provinces = max(0, target_pixels - int(unassigned_reserve.sum()))

    ranked = []
    cells_by_id = {}
    for index, raw_pid in enumerate(ids):
        pid = int(raw_pid)
        cells = order[offsets[index]:offsets[index + 1]]
        cells_by_id[pid] = cells
        if pid in protected:
            continue
        priority = float(candidates.ravel()[cells].mean())
        if priority > 0.0:
            ranked.append((-priority, -int(counts[index]), pid, cells))
    ranked.sort(key=lambda item: (item[0], item[1], item[2]))

    selected: set[int] = set()
    selected_pixels = 0
    remaining = target_from_provinces
    skipped = []
    for negative_priority, negative_area, pid, cells in ranked:
        area = int(cells.size)
        if area <= remaining:
            selected.add(pid)
            selected_pixels += area
            remaining -= area
        else:
            skipped.append((area, negative_priority, pid, cells))
    # If the remaining gap is smaller than every available province, take one
    # only when it reduces the absolute target error rather than overshooting
    # by an entire additional target-sized reserve.
    if remaining > 0 and skipped:
        area, negative_priority, pid, cells = min(
            skipped, key=lambda item: (abs(item[0] - remaining), item[1], item[2]))
        if area < 2 * remaining:
            selected.add(pid)
            selected_pixels += area
            remaining -= area

    final_wilderness = candidates.copy()
    final_wilderness.ravel()[valid] = False
    for pid in selected:
        cells = cells_by_id[pid]
        flat[cells] = 0
        final_wilderness.ravel()[cells] = True
        province_meta.pop(pid, None)

    filtered_realms = {
        int(rid): [int(pid) for pid in pids if int(pid) not in selected]
        for rid, pids in realm_provinces.items()
    }
    filtered_realms = {rid: pids for rid, pids in filtered_realms.items() if pids}
    cells_by_realm: dict[int, list[int]] = {rid: [] for rid in filtered_realms}
    for index, raw_pid in enumerate(ids):
        pid = int(raw_pid)
        if pid in selected:
            continue
        cells = cells_by_id[pid]
        meta = province_meta[pid]
        meta["area"] = int(cells.size)
        cells_by_realm.setdefault(int(meta["realm"]), []).extend(cells.tolist())

    actual_pixels = int(final_wilderness.sum())
    return flat, province_meta, filtered_realms, cells_by_realm, final_wilderness, {
        "model": "province-aligned-coherent-reserves-v1",
        "target_pixels": target_pixels,
        "target_from_provinces": target_from_provinces,
        "actual_pixels": actual_pixels,
        "selected_provinces": len(selected),
        "protected_capital_provinces": len(protected),
        "target_error_pixels": actual_pixels - target_pixels,
        "target_error_percent_of_land": round(
            100.0 * (actual_pixels - target_pixels) / max(1, int(np.count_nonzero(flat >= 0))), 4),
    }


def classify_realm_scale(area_pixels: int, total_land_pixels: int,
                         independent_island: bool = False) -> tuple[str, int]:
    """Neutral scale/tier metadata; political identity is assigned by another mod."""
    share = 100.0 * area_pixels / max(1, total_land_pixels)
    if independent_island and share < 2.5:
        return "city_state", 1
    if share < 0.6: return "tiny", 1
    if share < 2.5: return "small", 1
    if share < 7.5: return "medium", 2
    if share < 18.0: return "large", 2
    return "huge", 3


def color_for(index: int) -> str:
    hue = (index * 137.50776405 + 18.0) % 360.0
    c = 0.72
    x = c * (1 - abs((hue / 60.0) % 2 - 1))
    if hue < 60: r, g, b = c, x, 0
    elif hue < 120: r, g, b = x, c, 0
    elif hue < 180: r, g, b = 0, c, x
    elif hue < 240: r, g, b = 0, x, c
    elif hue < 300: r, g, b = x, 0, c
    else: r, g, b = c, 0, x
    m = 0.16
    return "#{:02x}{:02x}{:02x}".format(round((r + m) * 255), round((g + m) * 255), round((b + m) * 255))


def create_test_image(path: Path) -> None:
    w, h = 192, 128
    colors = {
        "ocean": (5, 65, 105), "grassland": (151, 183, 83),
        "forest": (25, 91, 39), "mountains": (119, 105, 88),
        "desert": (224, 185, 105), "waterway": (30, 175, 225),
    }
    img = Image.new("RGB", (w, h), colors["ocean"])
    draw = ImageDraw.Draw(img)
    draw.ellipse((17, 12, 170, 116), fill=colors["grassland"])
    draw.polygon([(34, 36), (66, 18), (102, 25), (121, 45), (96, 72), (58, 77), (29, 62)], fill=colors["forest"])
    draw.polygon([(84, 18), (111, 20), (139, 38), (131, 59), (111, 55), (95, 42)], fill=colors["mountains"])
    draw.polygon([(117, 75), (148, 69), (162, 90), (145, 107), (119, 101)], fill=colors["desert"])
    draw.line([(87, 30), (91, 48), (102, 62), (107, 82), (125, 98)], fill=colors["waterway"], width=4)
    draw.ellipse((151, 8, 180, 30), fill=colors["grassland"])
    draw.line([(162, 13), (166, 19)], fill=colors["waterway"], width=2)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, format="PNG", optimize=False)

def convert_image(input_path: Path, output_path: Path, seed: int, realm_count: int | None = None,
                  provinces_per_realm: int | None = None, blocks_per_pixel: int = 16,
                  settlement_level: int = 4, planet_id: str | None = None,
                  generation_mode: str = "image") -> dict:
    # Import before any coast/island processing. A later function-local import
    # otherwise makes ndimage a local variable and raises UnboundLocalError.
    from scipy import ndimage
    started_at = time.perf_counter()
    if settlement_level not in SETTLEMENT_PROFILES:
        raise ValueError("settlement_level must be between 1 and 7")
    settlement_profile = SETTLEMENT_PROFILES[settlement_level]
    analysis_started = time.perf_counter()
    image = Image.open(input_path).convert("RGB")
    width, height, analysis_labels, analysis_report = png_pixels(image)
    analysis_labels, coast_report = refine_open_coastline(analysis_labels, seed)
    analysis_report["coastline_model"] = coast_report
    refined_counts = np.bincount(analysis_labels.ravel(), minlength=len(ANALYSIS_CLASSES))
    refined_total = int(width * height)
    analysis_report["classes"] = {
        name: int(refined_counts[i]) for i, name in enumerate(ANALYSIS_CLASSES)}
    analysis_report["class_percent"] = {
        name: round(100.0 * int(refined_counts[i]) / refined_total, 3)
        for i, name in enumerate(ANALYSIS_CLASSES)}
    analysis_report["unknown_pixels"] = int(refined_counts[ANALYSIS_CLASSES.index("unknown")])
    analysis_report["unknown_percent"] = round(
        100.0 * analysis_report["unknown_pixels"] / refined_total, 3)
    analysis_report["classified_percent"] = round(
        100.0 - analysis_report["unknown_percent"], 3)
    analysis_grid = analysis_labels.reshape((height, width))
    analysis_flat = analysis_labels.ravel()
    boundary_cost = make_boundary_cost(analysis_grid, seed)
    land_codes = [i for i, name in enumerate(ANALYSIS_CLASSES)
                  if ANALYSIS_TO_WORLD[name] in LAND_CLASSES]
    full_land_mask = np.isin(analysis_grid, land_codes)
    land_count = int(full_land_mask.sum())
    # Realm scale is based only on explored land area, never on settlement density.
    if realm_count is None:
        realm_count = max(1, int(round(math.sqrt(max(1, land_count) / 900.0))))
    else:
        realm_count = max(1, int(realm_count))
    minimum_territory_pixels = max(64, int(land_count / max(1, realm_count * 8)))
    wilderness_mask = select_wilderness_mask(
        analysis_grid, width, height, settlement_profile["wilderness_share"], seed)
    political_land_mask = full_land_mask & ~wilderness_mask
    # Political continents are identified from complete geography. Wilderness
    # cannot fragment one continent into artificial independent countries.
    components = land_components(analysis_labels, width, height, full_land_mask)
    analysis_seconds = time.perf_counter() - analysis_started
    political_started = time.perf_counter()
    if not components or land_count < 32:
        raise ValueError("The map has too little classified land for territory generation.")

    # One world-area threshold for major landmasses, shared by all seven profiles.
    minimum_major_landmass_pixels = max(2048, int(round(land_count * 0.001)))
    major_components = [component for component in components
                        if len(component) >= minimum_major_landmass_pixels]
    if not major_components:
        major_components = [components[0]]
    major_component_ids = {component[0] for component in major_components}
    major_land_pixels = sum(map(len, major_components))
    realm_capacity = sum(max(1, len(component) // minimum_territory_pixels)
                         for component in major_components)
    realm_total = max(
        len(major_components),
        min(realm_count, realm_capacity,
            max(1, major_land_pixels // minimum_territory_pixels)),
    )
    realm_counts = allocate_counts(major_components, realm_total, minimum_territory_pixels)
    realm_labels = np.full(width * height, -2, dtype=np.int32)
    partition_scratch = np.full(width * height, -2, dtype=np.int32)
    realm_seeds: dict[int, int] = {}
    seed_landmass_by_pixel: dict[int, int] = {}
    main_landmass_id = major_components[0][0]
    next_realm = 0
    for component, count in zip(major_components, realm_counts):
        seeds = farthest_seeds(component, count, width, seed ^ (next_realm + 1))
        labels, _ = partition(component, seeds, width, height, next_realm,
                              partition_scratch, boundary_cost)
        component_indices = np.asarray(component, dtype=np.int64)
        realm_labels[component_indices] = labels[component_indices]
        partition_scratch[component_indices] = -2
        for offset, point in enumerate(seeds):
            realm_seeds[next_realm + offset] = point
            seed_landmass_by_pixel[point] = component[0]
        next_realm += count

    realm_labels, realm_seeds, aggregation_report = aggregate_realm_regions(
        realm_labels, realm_seeds, width, height, seed, minimum_territory_pixels,
        desired_fraction=0.58, max_area_share=0.30, analysis_grid=analysis_grid)
    realm_island_flags = {
        rid: seed_landmass_by_pixel.get(point, main_landmass_id) != main_landmass_id
        for rid, point in realm_seeds.items()
    }
    realm_total = len(realm_seeds)

    province_labels = np.zeros(width * height, dtype=np.uint32)
    province_meta: dict[int, dict] = {}
    realm_provinces: dict[int, list[int]] = {}
    # Build each realm's pixel list in one pass. Unclaimed minor islands remain
    # land in the biome/height layers but have no political province (ID 0).
    valid_realm_pixels = np.flatnonzero(realm_labels >= 0)
    realm_pixel_order = valid_realm_pixels[np.argsort(realm_labels[valid_realm_pixels], kind="stable")]
    realm_pixel_counts = checked_bincount(realm_labels[valid_realm_pixels], "realm_pixel_counts", minlength=realm_total)
    realm_pixel_offsets = np.concatenate(([0], np.cumsum(realm_pixel_counts)))
    realm_cells_by_id = {
        rid: realm_pixel_order[realm_pixel_offsets[rid]:realm_pixel_offsets[rid + 1]].tolist()
        for rid in range(realm_total)
    }
    next_province = 1
    for realm_id in range(realm_total):
        realm_cells = realm_cells_by_id[realm_id]
        if not realm_cells:
            continue
        natural_count = max(1, len(realm_cells) // minimum_territory_pixels)
        count = natural_count if provinces_per_realm is None else min(
            max(1, int(provinces_per_realm)), natural_count)
        seeds = farthest_seeds(realm_cells, count, width, seed ^ (realm_id * 7919))
        labels, _ = partition(realm_cells, seeds, width, height, next_province,
                              partition_scratch, boundary_cost)
        ids = list(range(next_province, next_province + len(seeds)))
        realm_indices = np.asarray(realm_cells, dtype=np.int64)
        # partition() returns a view of the reusable scratch buffer. Count the
        # province labels before releasing this realm's cells back to the -2
        # sentinel; otherwise every label is erased before np.bincount reads it.
        label_counts = checked_bincount(
            labels[realm_indices], "province_partition_labels",
            minlength=next_province + len(seeds))
        province_labels[realm_indices] = labels[realm_indices]
        realm_provinces[realm_id] = ids
        partition_scratch[realm_indices] = -2
        for offset, province_id in enumerate(ids):
            province_meta[province_id] = {
                "realm": realm_id, "seed": seeds[offset],
                "area": int(label_counts[province_id]),
                "island_realm": realm_island_flags.get(realm_id, False),
                "key": f"realm_{realm_id + 1:03d}_province_{offset + 1:03d}",
            }
        next_province += len(seeds)

    # Political archipelagos: every minor land component is retained. Most
    # become overseas provinces of the nearest mainland realm; a small,
    # deterministic subset of viable islands can become city-state realms.
    # Components below 8 pixels are treated as map speckle, not polities.
    minor_components = [component for component in components
                        if component[0] not in major_component_ids]
    tiny_components = [component for component in minor_components if len(component) < 8]
    if tiny_components and settlement_profile["wilderness_share"] > 0.0:
        # On sparse worlds, sub-8-pixel fragments are treated as geographic
        # speckle/wilderness. On the two fully settled profiles this is forbidden:
        # retain even tiny real land components as overseas provinces so every
        # classified land pixel has an owner.
        tiny_pixels = np.concatenate(tiny_components).astype(np.int64, copy=False)
        wilderness_mask.ravel()[tiny_pixels] = True
        minor_components = [component for component in minor_components if len(component) >= 8]
    island_province_count = 0
    independent_island_realm_count = 0
    if minor_components and realm_seeds:
        from scipy.spatial import cKDTree
        coast_water = np.isin(analysis_grid, [ANALYSIS_CLASSES.index("ocean"),
                                               ANALYSIS_CLASSES.index("lake")])
        coast_adjacent = ndimage.binary_dilation(coast_water, structure=np.array(
            [[0,1,0],[1,1,1],[0,1,0]], dtype=bool))
        realm_grid_coast = realm_labels.reshape((height, width))
        coast_cells_all = np.flatnonzero((realm_grid_coast >= 0) & coast_adjacent)
        coast_cells = coast_cells_all[::4]
        coast_tree = cKDTree(np.column_stack((coast_cells % width, coast_cells // width))) if coast_cells.size else None
        coast_owner = realm_grid_coast.ravel()[coast_cells] if coast_cells.size else None
        seed_ids = np.asarray(sorted(realm_seeds), dtype=np.int32)
        seed_pixels = np.asarray([realm_seeds[int(rid)] for rid in seed_ids], dtype=np.int64)
        seed_xy = np.column_stack((seed_pixels % width, seed_pixels // width))
        realm_tree = cKDTree(seed_xy)
        independent_budget = max(1, realm_count // 16)
        # A city-state needs enough land to support a minimal settlement.
        # The threshold scales with world development but never falls below 64 pixels.
        island_city_state_minimum = max(64, minimum_territory_pixels // 16)
        independent_components = select_independent_islands(
            minor_components, seed, independent_budget, island_city_state_minimum)

        for component in minor_components:
            # Use the geometric centroid, not the middle item in row-major pixel order.
            # This avoids bias toward the island's middle scanline on crescent/long islands.
            pixels = np.asarray(component, dtype=np.int64)
            xs, zs = pixels % width, pixels // width
            cx, cz = float(xs.mean()), float(zs.mean())
            centroid_distance = (xs - cx) ** 2 + (zs - cz) ** 2
            center = int(pixels[int(np.argmin(centroid_distance))])
            if coast_tree is not None:
                distances, nearest = coast_tree.query(np.column_stack((xs, zs)), k=1)
                owner_realm = int(coast_owner[int(nearest[int(np.argmin(distances))])])
            else:
                _, nearest = realm_tree.query((cx, cz), k=1)
                owner_realm = int(seed_ids[int(nearest)])
            if component[0] in independent_components:
                owner_realm = next_realm
                realm_provinces[owner_realm] = []
                realm_seeds[owner_realm] = center
                next_realm += 1
                independent_island_realm_count += 1
            province_id = next_province
            realm_provinces.setdefault(owner_realm, []).append(province_id)
            province_labels[np.asarray(component, dtype=np.int64)] = province_id
            province_meta[province_id] = {
                "realm": owner_realm, "seed": center, "area": len(component),
                "island_realm": component[0] in independent_components,
                "key": f"realm_{owner_realm + 1:03d}_island_{len(realm_provinces[owner_realm]):03d}",
            }
            realm_cells_by_id.setdefault(owner_realm, []).extend(component)
            island_province_count += 1
            next_province += 1

    # Formation scale is measured from the complete political skeleton,
    # before settlement reserves can hide any province from the map.
    realm_formation_area_pixels = {
        rid: sum(province_meta[pid]["area"] for pid in pids)
        for rid, pids in realm_provinces.items()
    }
    # Snap coherent terrain-biased wilderness candidates to whole province
    # boundaries. This protects capital cores and prevents thousands of tiny
    # disconnected province fragments on sparse settlement profiles.
    (province_labels, province_meta, realm_provinces, realm_cells_by_id,
     wilderness_mask, wilderness_report) = apply_province_aligned_wilderness(
        province_labels, province_meta, realm_provinces, wilderness_mask,
        width, height)
    unclaimed_land_pixels = int(wilderness_mask.sum())
    political_land_mask = full_land_mask & ~wilderness_mask

    province_neighbors = {pid: set() for pid in province_meta}
    realm_neighbors = {rid: set() for rid in realm_provinces}
    # Extract unique border pairs in vectorized NumPy passes. The old nested
    # Python scan visited every pixel and allocated neighbor iterators.
    province_grid = province_labels.reshape((height, width))
    left, right = province_grid[:, :-1], province_grid[:, 1:]
    top, bottom = province_grid[:-1, :], province_grid[1:, :]
    h_mask = (left > 0) & (right > 0) & (left != right)
    v_mask = (top > 0) & (bottom > 0) & (top != bottom)
    pair_parts = []
    if np.any(h_mask):
        pair_parts.append(np.column_stack((left[h_mask], right[h_mask])))
    if np.any(v_mask):
        pair_parts.append(np.column_stack((top[v_mask], bottom[v_mask])))
    pairs = np.concatenate(pair_parts, axis=0) if pair_parts else np.empty((0, 2), dtype=np.uint32)
    if pairs.size:
        pairs = np.unique(np.sort(pairs, axis=1), axis=0)
        for a_value, b_value in pairs:
            a, b = int(a_value), int(b_value)
            province_neighbors[a].add(b)
            province_neighbors[b].add(a)
            ra, rb = province_meta[a]["realm"], province_meta[b]["realm"]
            if ra != rb:
                realm_neighbors[ra].add(rb)
                realm_neighbors[rb].add(ra)

    political_seconds = time.perf_counter() - political_started
    realm_scale_metadata = {
        rid: classify_realm_scale(
            realm_formation_area_pixels.get(rid, 0), land_count,
            any(province_meta[pid].get("island_realm", False) for pid in pids))
        for rid, pids in realm_provinces.items()
    }
    county_id_for_realm = {rid: len(province_meta) + offset + 1 for offset, rid in enumerate(realm_provinces)}
    titles = []
    realms = []
    settlements = []
    for pid, meta in province_meta.items():
        rid = meta["realm"]
        x, z = meta["seed"] % width, meta["seed"] // width
        nx, ny = (x + 0.5) / width, (z + 0.5) / height
        titles.append({
            "id": pid, "key": meta["key"], "name": "",
            "tier": 0, "parent": county_id_for_realm[rid], "color": color_for(rid + 1),
            "capital": {"x": round((x + 0.5 - width * 0.5) * blocks_per_pixel), "z": round((z + 0.5 - height * 0.5) * blocks_per_pixel), "nx": nx, "ny": ny, "province": pid},
            "area_blocks": meta["area"] * blocks_per_pixel * blocks_per_pixel,
            "area_pct_of_land": 100.0 * meta["area"] / land_count,
            "children": [], "neighbors": sorted(province_neighbors[pid]), "props": {},
        })

    water_codes = [ANALYSIS_CLASSES.index(name) for name in ("ocean", "lake", "waterway")]
    water_mask = np.isin(analysis_grid, water_codes)
    adjacent_water = np.zeros((height, width), dtype=bool)
    adjacent_water[:, 1:] |= water_mask[:, :-1]
    adjacent_water[:, :-1] |= water_mask[:, 1:]
    adjacent_water[1:, :] |= water_mask[:-1, :]
    adjacent_water[:-1, :] |= water_mask[1:, :]
    coast_mask = adjacent_water.ravel()

    for rid, pids in realm_provinces.items():
        county_id = county_id_for_realm[rid]
        capital_pid = pids[0]
        capital = province_meta[capital_pid]["seed"]
        x, z = capital % width, capital // width
        total_area = sum(province_meta[pid]["area"] for pid in pids)
        county_key = f"realm_{rid + 1:02d}_county"
        titles.append({
            "id": county_id, "key": county_key, "name": "",
            "tier": realm_scale_metadata[rid][1], "parent": 0, "color": color_for(rid + 1),
            "capital": {"x": round((x + 0.5 - width * 0.5) * blocks_per_pixel), "z": round((z + 0.5 - height * 0.5) * blocks_per_pixel), "nx": (x + 0.5) / width, "ny": (z + 0.5) / height, "province": capital_pid},
            "area_blocks": total_area * blocks_per_pixel * blocks_per_pixel,
            "area_pct_of_land": 100.0 * total_area / land_count,
            "children": pids, "neighbors": sorted(county_id_for_realm[n] for n in realm_neighbors[rid]),
            "props": {"formation_scale": realm_scale_metadata[rid][0],
                      "formation_area_pixels": realm_formation_area_pixels.get(rid, 0),
                      "formation_area_pct_of_land": round(100.0 * realm_formation_area_pixels.get(rid, 0) / max(1, land_count), 4),
                      "identity_status": "unassigned"},
        })
        realms.append({
            "id": f"realm_{rid + 1:02d}", "name": "", "ruler": "",
            "color": color_for(rid + 1), "titles": [county_key], "overlord": None,
            "relation": "independent", "subjects": [],
            "area_blocks": total_area * blocks_per_pixel * blocks_per_pixel, "props": {
                "formation_scale": realm_scale_metadata[rid][0],
                "formation_area_pixels": realm_formation_area_pixels.get(rid, 0),
                "formation_area_pct_of_land": round(100.0 * realm_formation_area_pixels.get(rid, 0) / max(1, land_count), 4),
                "title_tier": realm_scale_metadata[rid][1],
                "area_pct_of_land": round(100.0 * total_area / max(1, land_count), 4),
                "province_count": len(pids), "identity_status": "unassigned",
                "identity_provider": "external_political_mod",
                "territorial_model": "connected_provinces_with_overseas_holdings",
            },
        })

        def add_settlement(kind: str, point: int, serial: int) -> None:
            sx, sz = point % width, point // width
            settlements.append({
                "id": f"{kind}_{rid + 1:02d}_{serial:02d}", "type": kind,
                "name": stable_name(seed, rid * 100 + serial, " " + kind.title()),
                "realm_id": f"realm_{rid + 1:02d}",
                "province_id": province_labels[point],
                "pixel": [sx, sz],
                "block": [round((sx + 0.5 - width / 2) * blocks_per_pixel),
                          round((sz + 0.5 - height / 2) * blocks_per_pixel)],
            })

        add_settlement("capital", capital, 1)
        for offset, pid in enumerate(pids[1:], 2):
            add_settlement(("city", "town", "village")[(offset - 2) % 3],
                           province_meta[pid]["seed"], offset)
        realm_cells = realm_cells_by_id.get(rid, [])
        mountain_code = ANALYSIS_CLASSES.index("mountains")
        mountain_cells = [i for i in realm_cells if analysis_flat[i] == mountain_code]
        if mountain_cells:
            add_settlement("fortress", mountain_cells[len(mountain_cells) // 2], 20 + rid)
        coast_cells = [i for i in realm_cells if coast_mask[i]]
        if coast_cells:
            coast_cells.sort(key=lambda i: (i * 2654435761 + seed) & 0xFFFFFFFF)
            add_settlement("port", coast_cells[0], 30 + rid)
        # Every polity needs at least a capital and one additional settlement
        # anchor, even when its territorial footprint is a single province.
        realm_settlement_count = sum(
            item["realm_id"] == f"realm_{rid + 1:02d}" for item in settlements)
        if realm_settlement_count < 2:
            fallback_point = next((point for point in reversed(realm_cells)
                                   if point != capital), capital)
            add_settlement("village", fallback_point, 90 + rid)

    geography_started = time.perf_counter()
    # Geographic layers come from the shared semantic classifier and seeded relief.
    height_values = make_heightfield(analysis_grid, seed).ravel().astype(np.uint16).tolist()
    biome_lut = np.asarray([BIOME_INDEX.get(ANALYSIS_TO_WORLD[name], 0)
                            for name in ANALYSIS_CLASSES], dtype=np.uint8)
    biome_values = biome_lut[analysis_flat].tolist()
    river_values = ((analysis_flat == ANALYSIS_CLASSES.index("waterway")).astype(np.uint8) * 255).tolist()

    spawn = province_meta[realm_provinces[0][0]]["seed"]
    spawn_x, spawn_z = spawn % width, spawn // width
    meta_doc = {
        "size_px": [width, height], "blocks_per_pixel": blocks_per_pixel,
        "sea_level": 63, "min_y": -64, "max_y": 320,
        "biomes": BIOMES, "spawn": [(spawn_x + 0.5) / width, (spawn_z + 0.5) / height],
    }
    titles_doc = {
        "format": "worldforge-titles/1", "size_px": [width, height],
        "blocks_per_pixel": blocks_per_pixel,
        "tiers": [{"index": 0, "key": "province", "name": "Province"},
                  {"index": 1, "key": "small_realm", "name": "Small Realm"},
                  {"index": 2, "key": "kingdom", "name": "Kingdom"},
                  {"index": 3, "key": "empire", "name": "Empire"}],
        "titles": sorted(titles, key=lambda item: item["id"]),
    }
    realms_doc = {
        "format": "worldforge-realms/1",
        "relation_types": {"vassal": {"label": "Vassal", "tribute": 10, "levy": 10, "can_declare_war": False}},
        "realms": realms,
    }
    geography_seconds = time.perf_counter() - geography_started
    province_ids = province_labels.tolist()  # zero is reserved for sea; labels are 16-bit safe.

    # Render a deterministic political QA layer: realm colors are shared by
    # their provinces, while thin dark borders expose province boundaries.
    realm_rgb = {
        rid: tuple(int(color_for(rid + 1)[i:i + 2], 16) for i in (1, 3, 5))
        for rid in realm_provinces
    }
    province_array = province_labels.reshape((height, width))
    province_palette = np.zeros((int(province_labels.max()) + 1, 3), dtype=np.uint8)
    for province_id, meta in province_meta.items():
        province_palette[province_id] = realm_rgb[meta["realm"]]
    political_rgb = province_palette[province_array]
    # Show unclaimed land as a distinct wilderness reserve, not as a hole
    # indistinguishable from ocean. Dense profiles have an empty wilderness mask.
    political_rgb[wilderness_mask] = (104, 111, 83)
    realm_lut = np.zeros(int(province_array.max()) + 1, dtype=np.int32)
    for province_id, meta in province_meta.items():
        realm_lut[province_id] = meta["realm"] + 1
    realm_grid = realm_lut[province_array]
    province_edge = np.zeros((height, width), dtype=bool)
    province_edge[:, 1:] |= (province_array[:, 1:] != province_array[:, :-1]) & (province_array[:, 1:] > 0) & (province_array[:, :-1] > 0)
    province_edge[1:, :] |= (province_array[1:, :] != province_array[:-1, :]) & (province_array[1:, :] > 0) & (province_array[:-1, :] > 0)
    realm_edge = np.zeros((height, width), dtype=bool)
    realm_edge[:, 1:] |= (realm_grid[:, 1:] != realm_grid[:, :-1]) & (realm_grid[:, 1:] > 0) & (realm_grid[:, :-1] > 0)
    realm_edge[1:, :] |= (realm_grid[1:, :] != realm_grid[:-1, :]) & (realm_grid[1:, :] > 0) & (realm_grid[:-1, :] > 0)
    from scipy import ndimage
    realm_edge = ndimage.binary_dilation(realm_edge, iterations=2)
    political_rgb[province_edge] = (63, 67, 72)
    political_rgb[realm_edge] = (20, 22, 28)
    political_preview = Image.fromarray(political_rgb, "RGB")
    from io import BytesIO
    political_buffer = BytesIO()
    political_preview.save(political_buffer, format="PNG", optimize=False)

    source_hash = hashlib.sha256(input_path.read_bytes()).hexdigest() if generation_mode == "image" else None
    blueprint = {
        "format": "maqan-world-blueprint/2", "generator": GENERATOR_VERSION,
        "planet_id": planet_id, "generation_mode": generation_mode,
        "settlement_level": settlement_level, "settlement_profile": settlement_profile,
        "seed": seed, "source_image_sha256": source_hash, "size_px": [width, height],
        "blocks_per_pixel": blocks_per_pixel, "land_pixels": land_count,
        "politically_unclaimed_land_pixels": unclaimed_land_pixels,
        "realm_count": len(realms), "province_count": len(province_meta),
        "settlement_count": len(settlements), "realms": realms,
        # Runtime timings are deliberately excluded from the deterministic
        # world artifact. They belong in CI telemetry, not seeded world data.
        "territories": [{"id": pid, "realm_id": f"realm_{meta['realm'] + 1:02d}",
                         "capital_pixel": [meta["seed"] % width, meta["seed"] // width],
                         "area_pixels": meta["area"], "neighbors": sorted(province_neighbors[pid])}
                        for pid, meta in sorted(province_meta.items())],
        "settlements": settlements,
        "generation_policy": {
            "chunk_materialization": "deferred",
            "territory_partition": "terrain-aware-organic-watershed",
            "partition_numba_accelerated": NUMBA_ACCELERATED,
            "boundary_model": "coherent-noise-plus-biome-edge-watershed",
            "wilderness_model": wilderness_report["model"],
            "wilderness_alignment": wilderness_report,
            "major_land_components": len(major_components),
            "minor_land_components": len(minor_components),
            "initial_seeded_realms": aggregation_report["initial_regions"],
            "final_connected_realms": aggregation_report["merged_regions"],
            "minor_island_provinces": island_province_count,
            "independent_island_realms": independent_island_realm_count,
            "independent_island_minimum_pixels": max(64, minimum_territory_pixels // 16),
            "realm_aggregation": aggregation_report,
            "identity_assignment": "external_political_mod",
            "unclaimed_land_pixels": unclaimed_land_pixels,
            "unclaimed_land_percent": round(100.0 * unclaimed_land_pixels / max(1, land_count), 4),
            "realm_area_pixels": {
                "min": min(sum(province_meta[pid]["area"] for pid in pids) for pids in realm_provinces.values()),
                "max": max(sum(province_meta[pid]["area"] for pid in pids) for pids in realm_provinces.values()),
                "mean": round(sum(sum(province_meta[pid]["area"] for pid in pids) for pids in realm_provinces.values()) / max(1, len(realm_provinces)), 2),
            },
            "political_seed": seed, "source_semantics": "maqan-image-analysis/2",
            "minimum_territory_pixels": minimum_territory_pixels,
            "wilderness_share_target": settlement_profile["wilderness_share"],
            "wilderness_pixels": int(wilderness_mask.sum()),
            "wilderness_percent_of_land": round(100.0 * int(wilderness_mask.sum()) / max(1, land_count), 4),
            "political_land_pixels": int(political_land_mask.sum()),
            "government_and_identity": "assigned_by_external_political_mod",
            "geography_classifier": analysis_report["classifier"],
            "unknown_percent": analysis_report["unknown_percent"],
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    terrain_preview = render_terrain_preview(
        np.asarray(biome_values, dtype=np.uint8),
        np.asarray(height_values, dtype=np.uint16), width, height)
    terrain_buffer = BytesIO()
    terrain_preview.save(terrain_buffer, format="PNG", optimize=False)
    files = {
        "meta.json": json.dumps(meta_doc, sort_keys=True, separators=(",", ":")).encode(),
        "biomes.png": image_bytes(width, height, biome_values, "L"),
        "height16.png": image_bytes(width, height, height_values, "I;16"),
        "rivers.png": image_bytes(width, height, river_values, "L"),
        "provinces.png": image_bytes(width, height, province_ids, "I;16"),
        "wilderness.png": image_bytes(
            width, height, (wilderness_mask.ravel().astype(np.uint8) * 255).tolist(), "L"),
        "political-preview.png": political_buffer.getvalue(),
        "terrain-preview.png": terrain_buffer.getvalue(),
        "titles.json": json.dumps(titles_doc, sort_keys=True, separators=(",", ":")).encode(),
        "realms.json": json.dumps(realms_doc, sort_keys=True, separators=(",", ":")).encode(),
        "world-blueprint.json": json.dumps(blueprint, sort_keys=True, separators=(",", ":"), default=lambda value: value.item() if isinstance(value, np.generic) else str(value)).encode(),
        "generation-profile.json": json.dumps({
            "format": "maqan-planet-world-profile/1",
            "planet_id": planet_id, "seed": seed, "generation_mode": generation_mode,
            "source_image_sha256": source_hash, "settlement_level": settlement_level,
            "settlement_profile": settlement_profile, "size_px": [width, height],
            "blocks_per_pixel": blocks_per_pixel,
        }, sort_keys=True, separators=(",", ":")).encode(),
        **({"source-image.sha256": (source_hash + "\n").encode()} if source_hash else {}),
        "image-analysis-report.json": json.dumps(analysis_report, sort_keys=True, separators=(",", ":")).encode(),
    }
    with zipfile.ZipFile(output_path, "w") as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return blueprint



def render_terrain_preview(biome_values: np.ndarray, height_values: np.ndarray,
                           width: int, height: int) -> Image.Image:
    """Render a shaded relief QA image with biome tint and coherent lighting."""
    palette = np.asarray([
        [49, 95, 133], [190, 180, 119], [48, 105, 66], [133, 132, 128],
        [204, 160, 83], [47, 119, 190], [36, 78, 56], [220, 231, 238],
        [57, 130, 190], [103, 99, 91]
    ], dtype=np.float32)
    biomes = np.asarray(biome_values, dtype=np.uint8).reshape((height, width))
    elevation = np.asarray(height_values, dtype=np.float32).reshape((height, width))
    elevation = elevation / 65535.0
    # Smooth the lighting surface only; the semantic coastline and biome
    # boundaries remain crisp in the underlying map layers.
    from scipy import ndimage
    smooth = ndimage.gaussian_filter(elevation, sigma=1.15, mode="nearest")
    dz_dy, dz_dx = np.gradient(smooth)
    nx, ny, nz = -dz_dx * 8.0, -dz_dy * 8.0, np.ones_like(smooth)
    norm = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / norm, ny / norm, nz / norm
    azimuth, altitude = math.radians(315), math.radians(42)
    lx, ly, lz = math.cos(altitude) * math.cos(azimuth), math.cos(altitude) * math.sin(azimuth), math.sin(altitude)
    shade = np.clip(nx * lx + ny * ly + nz * lz, 0.24, 1.0)
    shade = 0.58 + shade * 0.58
    color = palette[biomes] * shade[..., None]
    # Slight altitude tint makes highlands and snowfields read clearly.
    highland = np.clip((elevation - 0.34) * 1.25, 0.0, 0.18)
    color[..., 0] = color[..., 0] * (1.0 - highland * 0.30)
    color[..., 1] = color[..., 1] * (1.0 - highland * 0.08)
    color[..., 2] = color[..., 2] * (1.0 + highland * 0.35)
    return Image.fromarray(np.uint8(np.clip(color, 0, 255)), "RGB")


def image_bytes(width: int, height: int, values: list[int], mode: str) -> bytes:
    from io import BytesIO
    image = Image.new(mode, (width, height))
    image.putdata(values)
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False)
    return buffer.getvalue()



def validate_province_connectivity(province_ids: np.ndarray, width: int, height: int) -> dict:
    """Validate 4-connected province components with one raster pass per province bbox."""
    from scipy import ndimage
    grid = np.asarray(province_ids, dtype=np.uint32).reshape((height, width))
    flat = grid.ravel()
    valid = np.flatnonzero(flat)
    if not valid.size:
        return {"checked_provinces": 0, "disconnected_provinces": 0}
    # Group pixel indices once. Avoid rescanning the 26M-pixel source raster
    # separately for every province (the previous implementation was O(P*N)).
    order = valid[np.argsort(flat[valid], kind="stable")]
    ids, counts = np.unique(flat[valid], return_counts=True)
    offsets = np.concatenate(([0], np.cumsum(counts)))
    checked = 0
    disconnected = []
    structure = np.array([[0,1,0],[1,1,1],[0,1,0]], dtype=np.uint8)
    for index, pid in enumerate(ids):
        cells = order[offsets[index]:offsets[index + 1]]
        z, x = cells // width, cells % width
        x0, x1 = int(x.min()), int(x.max()) + 1
        z0, z1 = int(z.min()), int(z.max()) + 1
        mask = np.zeros((z1 - z0, x1 - x0), dtype=bool)
        mask[z - z0, x - x0] = True
        _, count = ndimage.label(mask, structure=structure)
        checked += 1
        if count != 1:
            disconnected.append({"province_id": int(pid), "components": int(count),
                                 "pixels": int(cells.size)})
    if disconnected:
        raise AssertionError(f"Disconnected provinces: {disconnected[:12]}")
    return {"checked_provinces": checked, "disconnected_provinces": 0}


def validate_package(path: Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        required = {"meta.json", "biomes.png", "height16.png", "rivers.png",
                    "provinces.png", "wilderness.png", "political-preview.png", "terrain-preview.png", "titles.json", "realms.json", "world-blueprint.json"}
        missing = required - names
        if missing:
            raise AssertionError(f"worldmap is missing layers: {sorted(missing)}")
        blueprint = json.loads(archive.read("world-blueprint.json"))
        titles = json.loads(archive.read("titles.json"))
        realms = json.loads(archive.read("realms.json"))
        province_image = Image.open(__import__("io").BytesIO(archive.read("provinces.png")))
        province_ids = np.asarray(province_image, dtype=np.uint32)
        width, height = province_image.size
        expected = {item["id"] for item in blueprint["territories"]}
        actual = set(int(value) for value in np.unique(province_ids) if int(value) != 0)
        if actual != expected:
            raise AssertionError(f"province raster mismatch: expected={expected}, actual={actual}")
        land_mask = province_ids > 0
        biome_image = Image.open(__import__("io").BytesIO(archive.read("biomes.png")))
        biome_values = np.asarray(biome_image, dtype=np.uint8).ravel()
        land_biomes = {1, 2, 3, 4, 6, 7, 9}
        actual_land_pixels = int(np.isin(biome_values, list(land_biomes)).sum())
        actual_covered_land_pixels = int(np.count_nonzero(province_ids > 0))
        actual_unclaimed = actual_land_pixels - actual_covered_land_pixels
        wilderness_image = Image.open(__import__("io").BytesIO(archive.read("wilderness.png")))
        wilderness_values = np.asarray(wilderness_image, dtype=np.uint8).ravel() > 0
        if int(wilderness_values.sum()) != int(blueprint["generation_policy"]["wilderness_pixels"]):
            raise AssertionError("wilderness raster count differs from the blueprint")
        # Wilderness and biome layers are flat pixel streams; provinces.png
        # is kept as a 2D image for coordinate/capital checks below.
        province_flat = province_ids.ravel()
        if np.any(wilderness_values & (province_flat > 0)):
            raise AssertionError("wilderness pixels overlap assigned political provinces")
        if np.any(wilderness_values & ~np.isin(biome_values, list(land_biomes))):
            raise AssertionError("wilderness raster includes water or non-land pixels")
        if actual_unclaimed != int(wilderness_values.sum()):
            raise AssertionError("unclaimed land must be exactly the explicit wilderness reserve")
        if actual_land_pixels != int(blueprint["land_pixels"]):
            raise AssertionError("land pixel count differs between semantic and political rasters")
        if actual_unclaimed != int(blueprint["politically_unclaimed_land_pixels"]):
            raise AssertionError("politically unclaimed land count differs from the blueprint")
        if int(blueprint["settlement_level"]) >= 6 and actual_unclaimed != 0:
            raise AssertionError("settlement levels 6-7 must have no politically unclaimed land")
        province_grid = province_ids.reshape((height, width))
        connectivity_report = validate_province_connectivity(province_grid, width, height)
        for item in blueprint["territories"]:
            pid = item["id"]
            cap_x, cap_z = item["capital_pixel"]
            if int(province_grid[cap_z, cap_x]) != pid:
                raise AssertionError(f"province {pid} capital is outside its province")
        realm_ids = {r["id"] for r in realms["realms"]}
        for settlement in blueprint["settlements"]:
            x, z = settlement["pixel"]
            if not (0 <= x < width and 0 <= z < height and land_mask[z, x]):
                raise AssertionError(f"settlement is not on land: {settlement}")
            if settlement["realm_id"] not in realm_ids:
                raise AssertionError(f"settlement references missing realm: {settlement}")
        if len(titles["titles"]) != blueprint["province_count"] + blueprint["realm_count"]:
            raise AssertionError("title hierarchy count does not match blueprint")
        if len(blueprint["settlements"]) < blueprint["realm_count"] * 2:
            raise AssertionError("not enough settlements were generated")
        return {
            "worldmap": path.name, "realm_count": blueprint["realm_count"],
            "province_count": blueprint["province_count"],
            "settlement_count": blueprint["settlement_count"],
            "land_pixels": blueprint["land_pixels"],
            "politically_unclaimed_land_pixels": blueprint["politically_unclaimed_land_pixels"],
            "actual_land_pixels": actual_land_pixels,
            "actual_covered_land_pixels": actual_covered_land_pixels,
            "actual_unclaimed_land_pixels": actual_unclaimed,
            "wilderness_percent_of_land": blueprint["generation_policy"]["wilderness_percent_of_land"],
            "settlement_level": blueprint["settlement_level"],
            "seed": blueprint["seed"],
            "source_image_sha256": blueprint["source_image_sha256"],
            "chunk_materialization": blueprint["generation_policy"]["chunk_materialization"],
            "connectivity": connectivity_report,
        }


def self_test(output_dir: Path) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    geography_regression_tests()
    source = output_dir / "synthetic-fantasy-map.png"
    first = output_dir / "image-world-seed-424242.worldmap"
    second = output_dir / "image-world-seed-424242-repeat.worldmap"
    other_seed = output_dir / "image-world-seed-424243.worldmap"
    create_test_image(source)
    convert_image(source, first, 424242)
    convert_image(source, second, 424242)
    convert_image(source, other_seed, 424243)
    if hashlib.sha256(first.read_bytes()).digest() != hashlib.sha256(second.read_bytes()).digest():
        raise AssertionError("same image + seed did not produce byte-identical worldmaps")
    if hashlib.sha256(first.read_bytes()).digest() == hashlib.sha256(other_seed.read_bytes()).digest():
        raise AssertionError("different seeds unexpectedly produced identical worldmaps")
    report = validate_package(first)
    report["same_seed_byte_identical"] = True
    report["different_seed_changes_output"] = True
    report["source_image"] = source.name
    (output_dir / "image-worldgen-test-report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    with zipfile.ZipFile(first) as archive:
        (output_dir / "world-blueprint.json").write_bytes(archive.read("world-blueprint.json"))
    return report



def run_profile_suite(input_path: Path, output_dir: Path, seed: int,
                      blocks_per_pixel: int = 16) -> dict:
    """Generate, validate and preview all seven settlement-density profiles."""
    output_dir.mkdir(parents=True, exist_ok=True)
    source_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()
    rows = []
    for level in range(1, 8):
        started = time.perf_counter()
        profile_dir = output_dir / f"level-{level}"
        profile_dir.mkdir(parents=True, exist_ok=True)
        worldmap = profile_dir / f"map-level-{level}-seed-{seed}.worldmap"
        blueprint = convert_image(
            input_path, worldmap, seed, blocks_per_pixel=blocks_per_pixel,
            settlement_level=level)
        validation = validate_package(worldmap)
        with zipfile.ZipFile(worldmap) as archive:
            for name in ("political-preview.png", "terrain-preview.png",
                         "world-blueprint.json", "image-analysis-report.json"):
                (profile_dir / name).write_bytes(archive.read(name))
        elapsed = time.perf_counter() - started
        policy = blueprint["generation_policy"]
        row = {
            "settlement_level": level,
            "profile": SETTLEMENT_PROFILES[level]["key"],
            "realm_count": blueprint["realm_count"],
            "province_count": blueprint["province_count"],
            "settlement_count": blueprint["settlement_count"],
            "land_pixels": blueprint["land_pixels"],
            "unclaimed_land_pixels": blueprint["politically_unclaimed_land_pixels"],
            "unclaimed_land_percent": policy["unclaimed_land_percent"],
            "major_land_components": policy["major_land_components"],
            "minor_land_components": policy["minor_land_components"],
            "minor_island_provinces": policy["minor_island_provinces"],
            "independent_island_realms": policy["independent_island_realms"],
            "realm_area_pixels": policy["realm_area_pixels"],
            "merge_report": policy["realm_aggregation"],
            "validation": validation,
            "elapsed_seconds": round(elapsed, 3),
            "worldmap": worldmap.name,
        }
        rows.append(row)
        print(json.dumps({"profile_progress": row}, sort_keys=True), flush=True)
    realm_counts = {row["realm_count"] for row in rows}
    if len(realm_counts) != 1:
        raise AssertionError(
            f"Political realm count changed with settlement density: {sorted(realm_counts)}")
    report = {
        "format": "maqan-image-profile-suite/1",
        "realm_count_invariant_across_profiles": True,
        "generator": GENERATOR_VERSION,
        "source_image": str(input_path.name),
        "source_image_sha256": source_hash,
        "seed": seed,
        "size_px": list(Image.open(input_path).size),
        "profiles": rows,
        "total_elapsed_seconds": round(sum(row["elapsed_seconds"] for row in rows), 3),
        "checks": {
            "all_seven_profiles_generated": len(rows) == 7,
            "levels_6_7_have_no_unclaimed_land": all(
                row["unclaimed_land_pixels"] == 0 for row in rows if row["settlement_level"] >= 6),
            "all_provinces_connected": all(
                row["validation"]["connectivity"]["disconnected_provinces"] == 0 for row in rows),
            "raster_area_matches_blueprint": all(
                row["validation"]["actual_land_pixels"] == row["land_pixels"] for row in rows),
        },
    }
    if not all(report["checks"].values()):
        raise AssertionError(f"Seven-profile QA failed: {report['checks']}")
    (output_dir / "seven-profile-comparison.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    test = sub.add_parser("self-test")
    test.add_argument("--output-dir", type=Path, required=True)
    convert = sub.add_parser("convert")
    convert.add_argument("--input", type=Path, required=True)
    convert.add_argument("--output", type=Path, required=True)
    convert.add_argument("--seed", type=int, required=True)
    convert.add_argument("--realms", type=int, default=None)
    convert.add_argument("--provinces-per-realm", type=int, default=None)
    convert.add_argument("--blocks-per-pixel", type=int, default=16)
    convert.add_argument("--settlement-level", type=int, choices=range(1, 8), default=4)
    convert.add_argument("--planet-id", type=str, default=None)
    suite_parser = sub.add_parser("profile-suite")
    suite_parser.add_argument("--input", type=Path, required=True)
    suite_parser.add_argument("--output-dir", type=Path, required=True)
    suite_parser.add_argument("--seed", type=int, default=424242)
    suite_parser.add_argument("--blocks-per-pixel", type=int, default=16)
    args = parser.parse_args()
    try:
        if args.command == "self-test":
            print(json.dumps(self_test(args.output_dir), indent=2, sort_keys=True))
        elif args.command == "profile-suite":
            print(json.dumps(run_profile_suite(args.input, args.output_dir,
                                               args.seed, args.blocks_per_pixel),
                             indent=2, sort_keys=True))
        else:
            blueprint = convert_image(args.input, args.output, args.seed, args.realms,
                                      args.provinces_per_realm, args.blocks_per_pixel,
                                      args.settlement_level, args.planet_id)
            print(json.dumps({
                "worldmap": str(args.output), "seed": args.seed,
                "realm_count": blueprint["realm_count"],
                "province_count": blueprint["province_count"],
                "settlement_count": blueprint["settlement_count"],
                "chunk_materialization": "deferred",
            }, indent=2, sort_keys=True))
        return 0
    except Exception as exc:
        print(f"IMAGE_WORLDGEN_FAILED: {exc}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
