# Shared world-object visual profiles

One profile is the presentation identity shared by the political map, RTS and Diorama. It is
metadata only and never creates a second settlement, army, structure or Character.

## Registration

- Built-in semantic profiles: capital, city, settlement, village, castle, port, mine, temple,
  ruin, camp, caravan, army and other.
- Resource packs: `assets/<namespace>/worldforge/visuals/*.json`.
- Mods can register profiles through `WorldObjectVisualCatalog.register(Profile)`.
- Marker resolution: explicit marker `visual` override, semantic `type`, then safe fallback.
- Legacy marker saves remain valid. Ordinary workers are not promoted to strategic markers.

## Manifest

```json
{
  "id": "maqan:citadel",
  "category": "fortification",
  "icon": "castle",
  "mapScale": 42,
  "mapTexture": "maqan:textures/worldmap/citadel.png",
  "mapItem": "minecraft:stone_bricks",
  "rtsModel": "maqan:world/citadel.glb",
  "dioramaModel": "maqan:world/citadel.glb",
  "iconLod": 0.0,
  "modelLod": 2.0
}
```

The political map uses a profile texture when supplied; otherwise it renders the profile's actual
Minecraft item/block model through the native item renderer (gold capital, brick city, timber
village, stone castle, barrel port, shield army, and so on). Army commanders retain their live
Minecraft entity renderer. Diorama uses the same semantic item-model family for exported
WorldForge features over its explored-terrain 3D surface.

## Current rendering boundary

Map textures and item models are live map rendering paths. The Diorama uses actual captured
terrain with isometric/top-down camera, terrain styles, and WorldForge political overlays; it
now draws block/item models for strategic feature anchors. The `rtsModel` and `dioramaModel`
fields reserve custom GLB model assets for the full mesh renderer. The current GLB parser and
CPU animation sampler do not yet provide GPU mesh upload/skinning or full building placement.
No custom GLB models are bundled in this repository yet; missing assets therefore use native
Minecraft item/block model fallbacks rather than invisible placeholders.
