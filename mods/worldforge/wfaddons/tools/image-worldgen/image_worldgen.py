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
import zipfile
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw
import numpy as np

from image_map_analysis import CLASSES as ANALYSIS_CLASSES, classify, make_heightfield

GENERATOR_VERSION = "maqan-image-blueprint/0.2.0"
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

GOVERNMENT_MODELS = [
    {"government":"hereditary_monarchy","structure":"feudal_monarchy","ruler_title":"King","council":"royal_council","succession":"hereditary"},
    {"government":"elective_monarchy","structure":"elective_crown","ruler_title":"High King","council":"electors_council","succession":"elective"},
    {"government":"imperial_autocracy","structure":"composite_empire","ruler_title":"Emperor","council":"imperial_court","succession":"dynastic_or_conquest"},
    {"government":"merchant_republic","structure":"oligarchic_republic","ruler_title":"First Doge","council":"merchant_senate","succession":"elected_by_houses"},
    {"government":"civic_republic","structure":"federal_republic","ruler_title":"First Consul","council":"popular_assembly","succession":"elected"},
    {"government":"theocracy","structure":"temple_state","ruler_title":"Hierophant","council":"synod","succession":"clerical_election"},
    {"government":"tribal_confederation","structure":"clan_confederacy","ruler_title":"High Chieftain","council":"tribal moot","succession":"clan_acclamation"},
    {"government":"military_dictatorship","structure":"military_junta","ruler_title":"Lord Marshal","council":"war_council","succession":"military_selection"},
    {"government":"magocracy","structure":"magocratic_conclave","ruler_title":"Archmage","council":"arcane_conclave","succession":"magical_merit"},
    {"government":"guild_oligarchy","structure":"chartered_city_league","ruler_title":"Guildmaster","council":"guild_congress","succession":"guild_vote"},
    {"government":"sacred_order","structure":"order_state","ruler_title":"Grand Master","council":"chapter_council","succession":"order_election"},
    {"government":"necromantic_dynasty","structure":"undying_dynasty","ruler_title":"Death Sovereign","council":"court_of_lichlords","succession":"ritual_designation"},
]

NAME_END = ("aria", "en", "oria", "gard", "mere", "hold", "wyn", "mark", "eth", "a", "or", "heim")

# Settlement density is independent from government form, technology and magic.
SETTLEMENT_PROFILES = {
    1: {"key": "wild_world", "realms": 4, "provinces": 2, "minimum_pixels": 8192, "wilderness_share": 0.92, "population_density": 0.02},
    2: {"key": "early_settlements", "realms": 8, "provinces": 4, "minimum_pixels": 4096, "wilderness_share": 0.82, "population_density": 0.08},
    3: {"key": "regional_civilizations", "realms": 16, "provinces": 6, "minimum_pixels": 2048, "wilderness_share": 0.68, "population_density": 0.20},
    4: {"key": "developed_states", "realms": 32, "provinces": 8, "minimum_pixels": 1024, "wilderness_share": 0.52, "population_density": 0.38},
    5: {"key": "dense_civilization", "realms": 48, "provinces": 12, "minimum_pixels": 768, "wilderness_share": 0.38, "population_density": 0.58},
    6: {"key": "highly_developed", "realms": 64, "provinces": 16, "minimum_pixels": 512, "wilderness_share": 0.24, "population_density": 0.78},
    7: {"key": "planetary_civilization", "realms": 96, "provinces": 24, "minimum_pixels": 256, "wilderness_share": 0.12, "population_density": 1.00},
}


def stable_name(seed: int, index: int, suffix: str = "") -> str:
    rng = random.Random((seed & 0xFFFFFFFFFFFFFFFF) ^ (index * 0x9E3779B97F4A7C15))
    return rng.choice(NAME_START) + rng.choice(NAME_END) + suffix


def png_pixels(image: Image.Image) -> tuple[int, int, list[str], dict]:
    """Use the shared contextual analyzer; reject ambiguous maps instead of guessing."""
    rgb = np.asarray(image.convert("RGB"), dtype=np.uint8)
    h, w = rgb.shape[:2]
    labels, report = classify(rgb)
    classes = [ANALYSIS_TO_WORLD[ANALYSIS_CLASSES[int(value)]] for value in labels.ravel()]
    if report["unknown_percent"] > 2.0:
        raise ValueError(
            f"Map contains {report['unknown_percent']:.3f}% unknown pixels; "
            "calibrate its legend before world generation."
        )
    return w, h, classes, report


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


def land_components(classes: list[str], width: int, height: int) -> list[list[int]]:
    """Connected landmasses via SciPy's native implementation, not Python pixel BFS."""
    from scipy import ndimage
    land = np.isin(np.asarray(classes, dtype=object).reshape((height, width)),
                   tuple(LAND_CLASSES))
    labels, count = ndimage.label(land, structure=np.array([[0, 1, 0],
                                                            [1, 1, 1],
                                                            [0, 1, 0]], dtype=np.uint8))
    if count == 0:
        return []
    flat = labels.ravel()
    sizes = np.bincount(flat, minlength=count + 1)
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


def partition(cells: list[int], seeds: list[int], width: int, height: int, first_label: int) -> tuple[dict[int, int], list[int]]:
    allowed = set(cells)
    labels: dict[int, int] = {}
    queue = deque()
    for offset, start in enumerate(seeds):
        label = first_label + offset
        labels[start] = label
        queue.append(start)
    while queue:
        current = queue.popleft()
        label = labels[current]
        for nxt in neighbors(current, width, height):
            if nxt in allowed and nxt not in labels:
                labels[nxt] = label
                queue.append(nxt)
    if len(labels) != len(allowed):
        raise ValueError("Territory partition left disconnected land unassigned.")
    return labels, seeds


def allocate_counts(components: list[list[int]], total: int) -> list[int]:
    counts = [1] * len(components)
    while sum(counts) < total:
        target = max(range(len(components)), key=lambda i: (len(components[i]) / counts[i], len(components[i]), -i))
        counts[target] += 1
    return counts


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

def convert_image(input_path: Path, output_path: Path, seed: int, realm_count: int = 64,
                  provinces_per_realm: int = 32, blocks_per_pixel: int = 16,
                  settlement_level: int = 4, planet_id: str | None = None,
                  generation_mode: str = "image") -> dict:
    started_at = time.perf_counter()
    if settlement_level not in SETTLEMENT_PROFILES:
        raise ValueError("settlement_level must be between 1 and 7")
    settlement_profile = SETTLEMENT_PROFILES[settlement_level]
    realm_count = min(realm_count, settlement_profile["realms"])
    provinces_per_realm = min(provinces_per_realm, settlement_profile["provinces"])
    minimum_territory_pixels = settlement_profile["minimum_pixels"]
    image = Image.open(input_path).convert("RGB")
    width, height, classes, analysis_report = png_pixels(image)
    components = land_components(classes, width, height)
    if not components:
        raise ValueError("The image contains no classified land.")
    if sum(map(len, components)) < 32:
        raise ValueError("The map has too little land for meaningful territory generation.")

    # Avoid creating politically meaningless micro-states on tiny islands/components.
    realm_total = max(len(components), min(realm_count, max(1, sum(map(len, components)) // minimum_territory_pixels)))
    realm_counts = allocate_counts(components, realm_total)
    realm_labels = [-1] * (width * height)
    realm_seeds: dict[int, int] = {}
    next_realm = 0
    for component, count in zip(components, realm_counts):
        seeds = farthest_seeds(component, count, width, seed ^ (next_realm + 1))
        labels, _ = partition(component, seeds, width, height, next_realm)
        for index, label in labels.items():
            realm_labels[index] = label
        for offset, point in enumerate(seeds):
            realm_seeds[next_realm + offset] = point
        next_realm += count

    province_labels = np.zeros(width * height, dtype=np.uint32)
    province_meta: dict[int, dict] = {}
    realm_provinces: dict[int, list[int]] = {}
    # Build each realm's pixel list in one pass. Avoid O(realms * image_pixels)
    # repeated full-raster scans, which dominate generation on large maps.
    realm_cells_by_id = {rid: [] for rid in range(realm_total)}
    for pixel_index, realm_id in enumerate(realm_labels):
        if realm_id >= 0:
            realm_cells_by_id[realm_id].append(pixel_index)
    next_province = 1
    for realm_id in range(realm_total):
        realm_cells = realm_cells_by_id[realm_id]
        if not realm_cells:
            continue
        # At least the profile-defined minimum pixels per smallest political territory.
        count = min(provinces_per_realm, max(1, len(realm_cells) // minimum_territory_pixels))
        seeds = farthest_seeds(realm_cells, count, width, seed ^ (realm_id * 7919))
        labels, _ = partition(realm_cells, seeds, width, height, next_province)
        ids = list(range(next_province, next_province + len(seeds)))
        for index, label in labels.items():
            province_labels[index] = label
        realm_provinces[realm_id] = ids
        label_counts = np.bincount(np.fromiter(labels.values(), dtype=np.int32),
                                   minlength=next_province + len(seeds))
        for offset, province_id in enumerate(ids):
            province_meta[province_id] = {
                "realm": realm_id, "seed": seeds[offset],
                "area": int(label_counts[province_id]),
                "key": f"realm_{realm_id + 1:03d}_province_{offset + 1:03d}",
            }
        next_province += len(seeds)

    land_count = sum(1 for kind in classes if kind in LAND_CLASSES)
    province_neighbors = {pid: set() for pid in province_meta}
    realm_neighbors = {rid: set() for rid in realm_provinces}
    for z in range(height):
        for x in range(width):
            i = z * width + x
            a = province_labels[i]
            if not a:
                continue
            for j in ((i + 1) if x + 1 < width else -1, (i + width) if z + 1 < height else -1):
                if j < 0:
                    continue
                b = province_labels[j]
                if b and b != a:
                    province_neighbors[a].add(b)
                    province_neighbors[b].add(a)
                    ra, rb = province_meta[a]["realm"], province_meta[b]["realm"]
                    if ra != rb:
                        realm_neighbors[ra].add(rb)
                        realm_neighbors[rb].add(ra)

    realm_names = {rid: stable_name(seed, rid + 1) for rid in realm_provinces}
    government_by_realm = {rid: GOVERNMENT_MODELS[random.Random((seed & 0xFFFFFFFFFFFFFFFF) ^ (rid * 0xD1B54A32D192ED03)).randrange(len(GOVERNMENT_MODELS))] for rid in realm_provinces}
    county_id_for_realm = {rid: len(province_meta) + offset + 1 for offset, rid in enumerate(realm_provinces)}
    titles = []
    realms = []
    settlements = []
    for pid, meta in province_meta.items():
        rid = meta["realm"]
        x, z = meta["seed"] % width, meta["seed"] // width
        nx, ny = (x + 0.5) / width, (z + 0.5) / height
        titles.append({
            "id": pid, "key": meta["key"], "name": stable_name(seed, pid, " Hold"),
            "tier": 0, "parent": county_id_for_realm[rid], "color": color_for(rid + 1),
            "capital": {"x": round((x + 0.5 - width * 0.5) * blocks_per_pixel), "z": round((z + 0.5 - height * 0.5) * blocks_per_pixel), "nx": nx, "ny": ny, "province": pid},
            "area_blocks": meta["area"] * blocks_per_pixel * blocks_per_pixel,
            "area_pct_of_land": 100.0 * meta["area"] / land_count,
            "children": [], "neighbors": sorted(province_neighbors[pid]), "props": {},
        })

    for rid, pids in realm_provinces.items():
        county_id = county_id_for_realm[rid]
        capital_pid = pids[0]
        capital = province_meta[capital_pid]["seed"]
        x, z = capital % width, capital // width
        total_area = sum(province_meta[pid]["area"] for pid in pids)
        county_key = f"realm_{rid + 1:02d}_county"
        titles.append({
            "id": county_id, "key": county_key, "name": realm_names[rid] + " Realm",
            "tier": 1, "parent": 0, "color": color_for(rid + 1),
            "capital": {"x": round((x + 0.5 - width * 0.5) * blocks_per_pixel), "z": round((z + 0.5 - height * 0.5) * blocks_per_pixel), "nx": (x + 0.5) / width, "ny": (z + 0.5) / height, "province": capital_pid},
            "area_blocks": total_area * blocks_per_pixel * blocks_per_pixel,
            "area_pct_of_land": 100.0 * total_area / land_count,
            "children": pids, "neighbors": sorted(county_id_for_realm[n] for n in realm_neighbors[rid]),
            "props": {},
        })
        realms.append({
            "id": f"realm_{rid + 1:02d}", "name": realm_names[rid], "ruler": "",
            "color": color_for(rid + 1), "titles": [county_key], "overlord": None,
            "relation": "independent", "subjects": [],
            "area_blocks": total_area * blocks_per_pixel * blocks_per_pixel, "props": {
                **government_by_realm[rid], "political_seed": seed ^ rid,
                "territorial_model": "connected_land_partition",
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
        realm_cells = realm_cells_by_id[rid]
        mountain_cells = [i for i in realm_cells if classes[i] == "mountains"]
        if mountain_cells:
            add_settlement("fortress", mountain_cells[len(mountain_cells) // 2], 20 + rid)
        coast_cells = [i for i in realm_cells if any(classes[n] in WATER_CLASSES for n in neighbors(i, width, height))]
        if coast_cells:
            coast_cells.sort(key=lambda i: (i * 2654435761 + seed) & 0xFFFFFFFF)
            add_settlement("port", coast_cells[0], 30 + rid)

    # Geographic layers come from the shared semantic classifier and seeded relief.
    analysis_labels, _ = classify(np.asarray(image.convert("RGB"), dtype=np.uint8))
    height_values = make_heightfield(analysis_labels, seed).ravel().astype(np.uint16).tolist()
    biome_values = [BIOME_INDEX.get(kind, 0) for kind in classes]
    river_values = [255 if kind == "river" else 0 for kind in classes]

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
        "tiers": [{"index": 0, "key": "barony", "name": "Province"},
                  {"index": 1, "key": "county", "name": "Realm"}],
        "titles": sorted(titles, key=lambda item: item["id"]),
    }
    realms_doc = {
        "format": "worldforge-realms/1",
        "relation_types": {"vassal": {"label": "Vassal", "tribute": 10, "levy": 10, "can_declare_war": False}},
        "realms": realms,
    }
    province_ids = province_labels.tolist()  # zero is reserved for sea; labels are 16-bit safe.
    source_hash = hashlib.sha256(input_path.read_bytes()).hexdigest() if generation_mode == "image" else None
    blueprint = {
        "format": "maqan-world-blueprint/2", "generator": GENERATOR_VERSION,
        "planet_id": planet_id, "generation_mode": generation_mode,
        "settlement_level": settlement_level, "settlement_profile": settlement_profile,
        "seed": seed, "source_image_sha256": source_hash, "size_px": [width, height],
        "blocks_per_pixel": blocks_per_pixel, "land_pixels": land_count,
        "realm_count": len(realms), "province_count": len(province_meta),
        "settlement_count": len(settlements), "realms": realms,
        "generation_seconds": round(time.perf_counter() - started_at, 4),
        "territories": [{"id": pid, "realm_id": f"realm_{meta['realm'] + 1:02d}",
                         "capital_pixel": [meta["seed"] % width, meta["seed"] // width],
                         "area_pixels": meta["area"], "neighbors": sorted(province_neighbors[pid])}
                        for pid, meta in sorted(province_meta.items())],
        "settlements": settlements,
        "generation_policy": {
            "chunk_materialization": "deferred", "territory_partition": "connected-land-multisource-bfs",
            "political_seed": seed, "source_semantics": "maqan-image-analysis/2",
            "minimum_territory_pixels": minimum_territory_pixels,
            "government_variety": sorted({r["props"]["government"] for r in realms}),
            "geography_classifier": analysis_report["classifier"],
            "unknown_percent": analysis_report["unknown_percent"],
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    files = {
        "meta.json": json.dumps(meta_doc, sort_keys=True, separators=(",", ":")).encode(),
        "biomes.png": image_bytes(width, height, biome_values, "L"),
        "height16.png": image_bytes(width, height, height_values, "I;16"),
        "rivers.png": image_bytes(width, height, river_values, "L"),
        "provinces.png": image_bytes(width, height, province_ids, "I;16"),
        "titles.json": json.dumps(titles_doc, sort_keys=True, separators=(",", ":")).encode(),
        "realms.json": json.dumps(realms_doc, sort_keys=True, separators=(",", ":")).encode(),
        "world-blueprint.json": json.dumps(blueprint, sort_keys=True, separators=(",", ":")).encode(),
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


def image_bytes(width: int, height: int, values: list[int], mode: str) -> bytes:
    from io import BytesIO
    image = Image.new(mode, (width, height))
    image.putdata(values)
    buffer = BytesIO()
    image.save(buffer, format="PNG", optimize=False)
    return buffer.getvalue()


def validate_package(path: Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        required = {"meta.json", "biomes.png", "height16.png", "rivers.png",
                    "provinces.png", "titles.json", "realms.json", "world-blueprint.json"}
        missing = required - names
        if missing:
            raise AssertionError(f"worldmap is missing layers: {sorted(missing)}")
        blueprint = json.loads(archive.read("world-blueprint.json"))
        titles = json.loads(archive.read("titles.json"))
        realms = json.loads(archive.read("realms.json"))
        province_image = Image.open(__import__("io").BytesIO(archive.read("provinces.png")))
        province_ids = list(province_image.getdata())
        width, height = province_image.size
        expected = {item["id"] for item in blueprint["territories"]}
        actual = {int(value) for value in province_ids if int(value) != 0}
        if actual != expected:
            raise AssertionError(f"province raster mismatch: expected={expected}, actual={actual}")
        land_mask = [value != 0 for value in province_ids]
        for item in blueprint["territories"]:
            pid = item["id"]
            cells = [i for i, value in enumerate(province_ids) if int(value) == pid]
            if not cells:
                raise AssertionError(f"province {pid} has no land pixels")
            start = cells[0]
            allowed = set(cells)
            seen = {start}
            queue = deque([start])
            while queue:
                current = queue.popleft()
                for nxt in neighbors(current, width, height):
                    if nxt in allowed and nxt not in seen:
                        seen.add(nxt)
                        queue.append(nxt)
            if len(seen) != len(cells):
                raise AssertionError(f"province {pid} is disconnected ({len(seen)}/{len(cells)})")
            cap_x, cap_z = item["capital_pixel"]
            if int(province_ids[cap_z * width + cap_x]) != pid:
                raise AssertionError(f"province {pid} capital is outside its province")
        realm_ids = {r["id"] for r in realms["realms"]}
        for settlement in blueprint["settlements"]:
            x, z = settlement["pixel"]
            if not (0 <= x < width and 0 <= z < height and land_mask[z * width + x]):
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
            "land_pixels": blueprint["land_pixels"], "seed": blueprint["seed"],
            "source_image_sha256": blueprint["source_image_sha256"],
            "chunk_materialization": blueprint["generation_policy"]["chunk_materialization"],
        }


def self_test(output_dir: Path) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    test = sub.add_parser("self-test")
    test.add_argument("--output-dir", type=Path, required=True)
    convert = sub.add_parser("convert")
    convert.add_argument("--input", type=Path, required=True)
    convert.add_argument("--output", type=Path, required=True)
    convert.add_argument("--seed", type=int, required=True)
    convert.add_argument("--realms", type=int, default=32)
    convert.add_argument("--provinces-per-realm", type=int, default=16)
    convert.add_argument("--blocks-per-pixel", type=int, default=16)
    args = parser.parse_args()
    try:
        if args.command == "self-test":
            print(json.dumps(self_test(args.output_dir), indent=2, sort_keys=True))
        else:
            blueprint = convert_image(args.input, args.output, args.seed, args.realms,
                                      args.provinces_per_realm, args.blocks_per_pixel)
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
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
