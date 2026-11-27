# Shared world-object visual profiles

This shared visual identity layer is consumed by the political-map marker renderer and is the
stable profile contract for RTS inspection and Diorama adapters. A profile is presentation
metadata only: it never creates a second settlement, army, structure or Character.

## Registration and resolution

- Built-in semantic profiles: capital, city, settlement, village, castle, port, mine, temple,
  ruin, camp, caravan, army and other.
- Resource-pack profiles: `assets/<namespace>/worldforge/visuals/*.json`.
- Runtime integrations: `WorldObjectVisualCatalog.register(Profile)`.
- Resolution order: explicit marker `visual` override, semantic marker `type`, safe `other` fallback.
- Existing marker JSON remains compatible; `visual` is optional. Provider-owned snapshots need
  no changes. Only designated army markers are shown; ordinary NPCs/workers are not promoted.

## Manifest

```json
{
  "id": "maqan:citadel",
  "category": "fortification",
  "icon": "castle",
  "mapScale": 42,
  "mapTexture": "maqan:textures/worldmap/citadel.png",
  "rtsModel": "maqan:world/citadel.glb",
  "dioramaModel": "maqan:world/citadel.glb",
  "iconLod": 0.0,
  "modelLod": 2.0
}
```

Missing map textures automatically fall back to vector symbols. `rtsModel` and `dioramaModel`
are stable asset references for view adapters; `mapScale` and LOD thresholds keep scale policy
consistent across views. Mods should map their registered structure/block/entity identifiers to
profiles rather than introducing duplicate gameplay entities.

## Implementation boundary

Profile discovery, reload, marker override, optional map texture and vector fallback are wired.
The wf-people GLB parser decodes mesh/skin/animation source data, but shared GPU model rendering,
skeletal playback, structure scene placement and full RTS/Diorama model integration are not yet
implemented. The manifest fields do not claim otherwise.
