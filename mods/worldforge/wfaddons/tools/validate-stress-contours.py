#!/usr/bin/env python3
"""Validate the exact 12-contour stress inventory and driver declarations."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[4]
manifest = json.loads((root / "docs/testing/MAQAN_STRESS_CONTOURS.json").read_text(encoding="utf-8"))
expected = [f"{i:02d}" for i in range(1, 13)]
items = manifest.get("contours", [])
if manifest.get("required_order") != expected or [item.get("id") for item in items] != expected:
    raise SystemExit("Stress contour IDs must be exactly 01..12 in order")
for item in items:
    if not item.get("name") or not item.get("systems"):
        raise SystemExit(f"Contour {item['id']} has no production-system mapping")
    for requirement in ("isolated_forge_server", "unique_save", "staged_maximum_load", "soak", "restart_recovery", "pass_requires_evidence"):
        if item.get(requirement) is not True:
            raise SystemExit(f"Contour {item['id']} missing requirement {requirement}")
    driver, status = item.get("driver"), item.get("status")
    if status == "NOT_IMPLEMENTED" and driver:
        raise SystemExit(f"Contour {item['id']} marked NOT_IMPLEMENTED but declares a driver")
    if status != "NOT_IMPLEMENTED" and not driver:
        raise SystemExit(f"Contour {item['id']} declares driver status without a path")
    if driver and not (root / driver).is_file():
        raise SystemExit(f"Contour {item['id']} driver path is missing: {driver}")
    if status == "PASS":
        raise SystemExit(f"Inventory cannot grant PASS without runtime evidence: {item['id']}")
print("MAQAN_STRESS_CONTOURS=12")
for item in items:
    print(f"CONTOUR_{item['id']}={item['name']} STATUS={item['status']} DRIVER={item.get('driver') or 'NONE'}")
print("STRESS_INVENTORY_VALID=1")
