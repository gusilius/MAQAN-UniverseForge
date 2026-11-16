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

- Main landmasses receive profile-sized realm and province partitions.
- Minor connected land components from 8 pixels upward become overseas
  provinces of the nearest realm.
- A deterministic, capped subset of islands above the profile-scaled minimum (at least 64 pixels) can become
  independent island city-states.
- Smaller fragments remain unclaimed map detail rather than generating
  hundreds of meaningless one-pixel polities.
- An overseas province can belong to a mainland realm without sharing a land
  border; this is an explicit archipelago/overseas-holding relationship.

The generated map is deterministic for the same source image, seed, profile,
and dependency versions. The Windows quality gate runs the synthetic
byte-for-byte determinism test; this does not replace testing a user's own map.

## Political geography (CK3-style territorial scale)

The image generator now separates **territorial geometry** from political identity.
It creates connected province groups and merges adjacent seed regions only across
real shared land borders. A deterministic merge budget and area ceiling preserve
a mixed political map: independent small realms can coexist with medium and large
formations, while occasional very large connected realms can emerge on suitable
continents. The generator does not force every landmass into an empire.

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
It controls the target wilderness reserve, minimum viable territorial footprint,
realm/province budgets and the amount of adjacent realm aggregation. It does
not prescribe a fixed number of states: geography, viable land components and
shared borders determine the final count.

| Level | Profile | Target unclaimed wilderness |
|---:|---|---:|
| 1 | Wild world | 92% |
| 2 | Early settlements | 82% |
| 3 | Regional civilizations | 68% |
| 4 | Developed states | 16% |
| 5 | Dense civilization | 8% |
| 6 | Highly developed | 0% |
| 7 | Planetary civilization | 0% |

Wilderness is selected as broad, deterministic, terrain-biased reserves before
political partitioning. It is not created by leaving random holes between
neighboring borders. Levels 6 and 7 require every classified land pixel to
belong to a province; the package validator checks this coverage. On levels
1–5 the explicit `wilderness.png` layer marks the unclaimed land. Province ID
zero remains the no-political-owner value, while `biomes.png` distinguishes
land from sea.

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

The command-line converter accepts `--settlement-level 1..7` and optional
`--planet-id`. The same image and seed with the same profile produce the same
territorial result.
