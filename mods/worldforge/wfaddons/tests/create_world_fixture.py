#!/usr/bin/env python3
"""Build a deterministic, synthetic political world used by WorldForge logic tests.

The fixture is intentionally generated from source so tests never depend on a
private/licensed game map archive. It contains eight independent realms, two
counties and four adjacent baronies per realm.
"""
from __future__ import annotations

import json
import struct
import sys
import zlib
import zipfile
from pathlib import Path

WIDTH, HEIGHT = 64, 64
REALMS = 8
BARONIES_PER_REALM = 4
ROWS_PER_BARONY = HEIGHT // BARONIES_PER_REALM
PREFIX = "data/planets/worldforge_maps/game_of_thrones/"


def png_gray(width: int, height: int, bit_depth: int, pixels: list[int]) -> bytes:
    if len(pixels) != width * height:
        raise ValueError("pixel buffer size mismatch")
    if bit_depth == 8:
        encode = lambda value: bytes((value & 0xFF,))
    elif bit_depth == 16:
        encode = lambda value: struct.pack(">H", value & 0xFFFF)
    else:
        raise ValueError("unsupported grayscale bit depth")
    rows = bytearray()
    stride = width
    for y in range(height):
        rows.append(0)  # PNG filter: None
        for x in range(width):
            rows.extend(encode(pixels[y * stride + x]))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, bit_depth, 0, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def build_world() -> dict[str, bytes]:
    barony_pixels = [0] * (WIDTH * HEIGHT)
    titles: list[dict] = []
    realms: list[dict] = []
    county_id_base = REALMS * BARONIES_PER_REALM + 1

    for realm_index in range(REALMS):
        realm_id = f"realm_{realm_index + 1}"
        color = f"#{((realm_index + 2) * 0x234567) & 0xFFFFFF:06x}"
        county_keys = []
        county_ids = [county_id_base + realm_index * 2,
                      county_id_base + realm_index * 2 + 1]

        for county_index in range(2):
            county_id = county_ids[county_index]
            county_key = f"{realm_id}_county_{county_index + 1}"
            county_keys.append(county_key)
            child_ids = []
            for local_row in range(2):
                row = county_index * 2 + local_row
                barony_id = realm_index * BARONIES_PER_REALM + row + 1
                child_ids.append(barony_id)
                x0, x1 = realm_index * (WIDTH // REALMS), (realm_index + 1) * (WIDTH // REALMS)
                z0, z1 = row * ROWS_PER_BARONY, (row + 1) * ROWS_PER_BARONY
                for z in range(z0, z1):
                    for x in range(x0, x1):
                        barony_pixels[z * WIDTH + x] = barony_id
                cx, cz = (x0 + x1 - 1) // 2, (z0 + z1 - 1) // 2
                titles.append({
                    "id": barony_id, "key": f"{realm_id}_barony_{row + 1}",
                    "name": f"Realm {realm_index + 1} Barony {row + 1}",
                    "tier": 0, "parent": county_id, "color": color,
                    "capital": {"x": cx, "z": cz, "nx": (cx + 0.5) / WIDTH,
                                "ny": (cz + 0.5) / HEIGHT, "province": barony_id},
                    "area_blocks": 1, "area_pct_of_land": 0.0,
                    "children": [], "neighbors": [], "props": {}
                })

            cx, cz = realm_index * (WIDTH // REALMS) + 3, (county_index * 2 + 1) * ROWS_PER_BARONY - 1
            titles.append({
                "id": county_id, "key": county_key,
                "name": f"Realm {realm_index + 1} County {county_index + 1}",
                "tier": 1, "parent": 0, "color": color,
                "capital": {"x": cx, "z": cz, "nx": (cx + 0.5) / WIDTH,
                            "ny": (cz + 0.5) / HEIGHT, "province": child_ids[0]},
                "area_blocks": 1, "area_pct_of_land": 0.0,
                "children": child_ids, "neighbors": [], "props": {}
            })

        realms.append({
            "id": realm_id, "name": f"Test Realm {realm_index + 1}",
            "ruler": "", "color": color, "titles": county_keys,
            "overlord": None, "relation": "independent", "subjects": [],
            "area_blocks": 1, "props": {}
        })

    # IDs are contiguous but the array is deliberately sorted by ID because
    # TitleData indexes titles by their numeric identifier.
    titles.sort(key=lambda item: item["id"])
    title_doc = {
        "format": "worldforge-titles/1",
        "size_px": [WIDTH, HEIGHT], "blocks_per_pixel": 16,
        "tiers": [
            {"index": 0, "key": "barony", "name": "Barony"},
            {"index": 1, "key": "county", "name": "County"}
        ],
        "titles": titles
    }
    realm_doc = {"format": "worldforge-realms/1", "realms": realms}
    meta_doc = {
        "size_px": [WIDTH, HEIGHT], "blocks_per_pixel": 16,
        "sea_level": 63, "min_y": -64, "max_y": 320,
        "biomes": [{"index": 0, "key": "plains", "color": "#8db360",
                    "minecraft_id": "minecraft:plains"}],
        "spawn": [0.5, 0.5]
    }
    return {
        "meta.json": json.dumps(meta_doc, separators=(",", ":")).encode(),
        "biomes.png": png_gray(WIDTH, HEIGHT, 8, [0] * (WIDTH * HEIGHT)),
        "height16.png": png_gray(WIDTH, HEIGHT, 16, [22870] * (WIDTH * HEIGHT)),
        "provinces.png": png_gray(WIDTH, HEIGHT, 16, barony_pixels),
        "titles.json": json.dumps(title_doc, separators=(",", ":")).encode(),
        "realms.json": json.dumps(realm_doc, separators=(",", ":")).encode()
    }


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: create_world_fixture.py OUTPUT.worldmap")
    output = Path(sys.argv[1])
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in build_world().items():
            archive.writestr(PREFIX + name, content)
    print(f"Created deterministic WorldForge fixture: {output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
