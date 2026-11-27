# Custom NPC model assets

The built-in NPC model catalog uses **binary glTF 2.0 (.glb)** at runtime. Keep the
original Blockbench `.bbmodel` in the authoring/source-artifact area; do not ship it
as the runtime mesh. A GLB can contain the mesh, skeleton, animation clips, materials,
and embedded texture data in one binary asset.

## Resource layout

Place each profile under the `wf_people` resource namespace:

```text
src/main/resources/assets/wf_people/
  models/npc/orc_peon/
    model.json
    model.glb
```

A manifest is discovered automatically during Minecraft resource reload:

```json
{
  "id": "wf_people:orc_peon",
  "format": "gltf2",
  "model": "models/npc/orc_peon/model.glb",
  "scale": 1.0,
  "animations": {
    "idle": "stand_1",
    "walk": "walk_1",
    "attack": "attack_1",
    "work": "stand_work_1",
    "death": "death_1"
  }
}
```

The optional `texture` field may point to a separate resource, for example
`textures/npc/orc_peon.png`. Prefer embedded GLB textures unless sharing a texture
between several profiles is useful. The model path must resolve to an existing
`.glb`; scale must be finite and in the range `(0, 8]`.

## Character binding

Set the canonical Character property `visual.model_profile` to the profile ID,
for example `wf_people:orc_peon`. This is visual metadata only. It must never
create a second Character, entity, inventory, or identity. The Character registry
remains authoritative; the physical NPC entity remains the existing body binding.

Vanilla and third-party mobs are not converted to this format: they continue to use
their registered Minecraft/Forge entity renderers. Custom GLB profiles are for
MAQAN-authored mesh characters.

## Runtime status

The current implementation provides resource-reload discovery, manifest parsing,
resource existence checks, duplicate-ID rejection, immutable profile snapshots,
and animation-name mapping. It does **not yet** decode GLB geometry, upload GPU
buffers, skin meshes, or play animation clips. Until the GLB renderer stage is
implemented, the existing humanoid NPC renderer remains the visual fallback.
