# Image WorldForge — performance and installation

Install the native numerical stack once:

```powershell
python -m pip install -r requirements.txt
```

The generator uses NumPy/SciPy for semantic masks, connected components,
coherent reserve selection, terrain-aware watershed partitioning, island
proximity, border extraction and preview rendering. The older flat-array
multi-source flood-fill remains only as a fallback for callers that do not
supply a boundary-cost surface; normal image conversion uses the organic
watershed path.

## Political islands

- Major landmasses are identified from the complete land mask with a
  world-area threshold shared by all seven settlement profiles.
- Realm count is derived from explored land area and geographic capacity;
  settlement density does not change the realm budget or aggregation rules.
- Minor connected land components become overseas provinces of the nearest
  realm; a deterministic subset of viable islands can become independent
  city-states using a profile-independent size threshold.
- Smaller fragments remain unclaimed map detail rather than generating
  hundreds of meaningless one-pixel polities.
- An overseas province can belong to a mainland realm without sharing a land
  border; this is an explicit archipelago/overseas-holding relationship.

The generated map is deterministic for the same source image, seed, profile,
and dependency versions. The Windows quality gate runs the synthetic
byte-for-byte determinism test; this does not replace testing a user's own map.

## Political geography (CK3-style territorial scale)

The image generator separates **territorial geometry** from political identity.
It builds a stable realm skeleton over the complete explored land mask, merges
adjacent seed regions only across real shared land borders, and only then applies
the profile's wilderness reserve. A reserve can split an occupied province into
multiple connected provincial holdings, but those holdings retain the same
realm identity. The realm registry and political scale are therefore stable
across all seven settlement-density profiles for the same image and seed.

Realm metadata exposes neutral fields such as `formation_scale`, `title_tier`,
`area_pct_of_land`, `province_count`, and `identity_status`. Names, dynasties,
government forms, laws, cultures and other political identity are intentionally
left unassigned for the dedicated political/identity mod. The generated title
tiers are province, small realm, kingdom and empire.

Minor islands remain visible as political candidates. A viable subset can become
independent island city-states; the rest become overseas provinces of a nearby
realm. Tiny raster fragments remain unclaimed instead of creating meaningless
microstates.

The world package also includes `terrain-preview.png`: a shaded-relief view
using the generated elevation and biome palette. It is a visual QA layer; the
underlying `height16.png`, `biomes.png` and political raster remain authoritative.

## Seven settlement-density profiles and wilderness

The `--settlement-level` option selects one of seven world-development profiles.
It controls the target wilderness reserve and population density only. Realm
count, major-landmass classification, island city-state eligibility, minimum
territorial scale and realm aggregation are derived from explored land area and
geography, not settlement density. The profile-suite gate asserts that the
realm count remains identical across all seven profiles.

| Level | Profile | Target unclaimed wilderness |
|---:|---|---:|
| 1 | Wild world | 92% |
| 2 | Early settlements | 82% |
| 3 | Regional civilizations | 68% |
| 4 | Developed states | 16% |
| 5 | Dense civilization | 8% |
| 6 | Highly developed | 0% |
| 7 | Planetary civilization | 0% |

Wilderness is selected as broad, deterministic, terrain-biased reserves from
the completed political geography. Political seed cells are protected as
settled cores. If a reserve cuts through a province, the remaining connected
pieces become separate provinces under the same realm. Levels 6 and 7 have no
reserve and require every classified land pixel to have political ownership;
the package validator checks this coverage. On levels 1–5, `wilderness.png`
marks unclaimed land. Province ID zero remains the no-political-owner value,
while `biomes.png` distinguishes land from sea.

## Organic political borders

Realm and province regions are partitioned with a deterministic, terrain-aware
watershed surface. Coherent multi-scale noise avoids straight Voronoi-like
slices; biome-transition cues influence the watershed while a landmass mask
prevents the partition from crossing oceans. Province connectivity and capital
membership are validated. Province borders are rendered thinner than realm
borders, so the map reads as a hierarchy rather than a grid.

The world package contains `wilderness.png`, `political-preview.png` and
`terrain-preview.png` alongside the semantic rasters. The previews are QA
outputs; the province, wilderness, biome and height rasters remain authoritative.

The command-line converter accepts `--settlement-level 1..7`, optional
`--realms` / `--provinces-per-realm` overrides, and optional `--planet-id`.
By default the political budget is derived from explored land area. The same
image and seed produce the same realm registry across all seven profiles;
the same image, seed and profile produce byte-deterministic output.
