# Image WorldForge — performance and installation

Install the native numerical stack once:

```powershell
python -m pip install -r requirements.txt
```

The generator keeps a pure-Python fallback, but Numba compiles the flat-array
multi-source territory flood-fill and caches the compiled kernel. The first
run pays the JIT compilation cost; later runs with the same environment reuse
the cache. NumPy/SciPy handle semantic masks, connected-component extraction,
island proximity, border extraction and preview colorization in native code.

## Political islands

- Main landmasses receive profile-sized realm and province partitions.
- Minor connected land components from 8 pixels upward become overseas
  provinces of the nearest realm.
- A deterministic, capped subset of islands from 16 pixels upward can become
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
