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
