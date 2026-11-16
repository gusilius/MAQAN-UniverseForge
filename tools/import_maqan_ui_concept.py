#!/usr/bin/env python3
"""Import the embedded MAQAN UI concept into native Minecraft PNG resources.

Requires: Python 3.10+ and Pillow (python -m pip install Pillow).
The source HTML is treated as an asset container only; it is never embedded in Minecraft.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import re
from pathlib import Path

from PIL import Image

THEME_KEYS = ("royal", "iron", "grim", "elf")
TEXTURE_NAMES = {
    "top": "top_bar.png",
    "lcol": "left_column.png",
    "lp": "window_state.png",
    "rp": "window_diplomacy.png",
    "rcol": "right_column.png",
    "mm": "minimap.png",
    "bot": "rts_console.png",
    "cor": "corner.png",
    "lw": "window_left_blank.png",
    "rw": "window_right_blank.png",
}


def extract_object(pattern: str, source: str, label: str) -> dict:
    match = re.search(pattern, source, re.DOTALL)
    if not match:
        raise ValueError(f"Could not locate {label} in the supplied concept HTML.")
    return json.loads(match.group(1))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--concept-html", required=True, type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    args = parser.parse_args()

    html = args.concept_html.read_text(encoding="utf-8")
    skins = extract_object(r"const SK=(\{.*?\}),MT=", html, "embedded skin assets")
    layouts = extract_object(r",MT=(\{.*?\});\s*for\s*\(", html, "theme layout coordinates")

    missing = [theme for theme in THEME_KEYS if theme not in skins or theme not in layouts]
    if missing:
        raise ValueError("Concept is missing required themes: " + ", ".join(missing))

    resource_root = args.repo_root / "platform/src/main/resources/assets/maqan"
    for theme in THEME_KEYS:
        theme_data = skins[theme]
        if set(theme_data) != set(TEXTURE_NAMES):
            raise ValueError(f"{theme}: expected exactly {len(TEXTURE_NAMES)} image layers.")
        target = resource_root / "textures/gui" / theme
        target.mkdir(parents=True, exist_ok=True)
        for key, filename in TEXTURE_NAMES.items():
            data_uri = theme_data[key]
            header, separator, encoded = data_uri.partition(",")
            if not separator or "base64" not in header or not header.startswith("data:image/"):
                raise ValueError(f"{theme}/{key}: invalid embedded image data.")
            raw = base64.b64decode(encoded, validate=True)
            with Image.open(io.BytesIO(raw)) as image:
                image.convert("RGBA").save(target / filename, format="PNG", optimize=True)

    manifest = {
        "schemaVersion": 2,
        "referenceViewport": {"width": 1368, "height": 760},
        "sourceScale": 2,
        "defaultTheme": "royal",
        "requiredThemes": list(THEME_KEYS),
        "textureNames": TEXTURE_NAMES,
        "skins": {theme: layouts[theme] for theme in THEME_KEYS},
        "layerOrder": ["world_scene", "minimap", "frame", "rts_console",
                       "top_bar", "corner", "left_column", "right_column", "windows"],
        "rules": {
            "blankWindowsAreCanonical": True,
            "bakedDemoValuesMustNeverBePresentedAsLiveData": True,
            "allInteractiveRegionsUseReferenceViewportCoordinates": True,
            "texturesMustPreserveAspectRatio": True,
            "themeAssetsAreRequiredAndMustNotBeSilentlySubstituted": True
        }
    }
    gui_dir = resource_root / "gui"
    gui_dir.mkdir(parents=True, exist_ok=True)
    (gui_dir / "layout.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    count = len(THEME_KEYS) * len(TEXTURE_NAMES)
    print(f"Imported {count} PNG textures and native layout metadata.")
    print(f"Resources: {resource_root}")
    print("HTML prototype was not copied or embedded; runtime remains native Minecraft GUI.")


if __name__ == "__main__":
    main()
