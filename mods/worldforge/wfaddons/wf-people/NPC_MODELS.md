# Custom NPC model assets

The built-in NPC model pipeline uses **binary glTF 2.0 (.glb)** at runtime. Keep the
original Blockbench `.bbmodel` as the editable authoring source; ship the optimized
GLB as the runtime mesh.

## Resource layout

```text
src/main/resources/assets/wf_people/
  models/npc/orc_peon/
    model.json
    model.glb
```

Example manifest:

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

Set canonical Character property `visual.model_profile` to the profile ID. A profile
is visual metadata only: it must not create another Character, inventory, entity, or
identity. Vanilla and third-party mobs keep their registered Minecraft/Forge renderers.

## Runtime pipeline status

The resource reload listener discovers manifests and decodes GLB data off the render
thread into CPU-side model structures: GLB v2 chunks; embedded buffer and accessor
bounds/strides; triangle POSITION/NORMAL/UV/index data; optional JOINTS_0/WEIGHTS_0;
node transforms; skin joints and inverse-bind matrices; animation samplers/channels;
and PBR base color, metallic/roughness and texture indices.

Decoded snapshots are available through `NpcModelCatalog.decoded(profileId)` and
replace atomically on resource reload. Malformed assets are rejected with a warning
without blocking other profiles.

**Not yet implemented:** embedded image decoding into Minecraft textures, GPU buffer
allocation, skinning/render submission, animation sampling/blending at runtime, and
distance-based LOD. The existing humanoid NPC renderer remains the visual fallback.
The CPU decoder performs no OpenGL calls and must not run from an entity render callback.
