# Мост WorldForge → MAQAN Galactic Navigator

## Текущий этап: импортируемый read-only snapshot

Navigator принимает экспорт WorldForge по пути:

`config/maqan/worldforge/map_snapshot.json`

Файл читается на клиенте при открытии 3D-карты. Он не изменяет игровые данные и не даёт Navigator права объявлять владельца территории или рассчитывать политический контроль.

Формат версии 1:

```json
{
  "schema_version": 1,
  "world_id": "save-or-server-stable-id",
  "generated_at": "2026-11-06T12:00:00Z",
  "territories": [
    {
      "id": "worldforge:realm/kingdom_north",
      "name": "Northern Kingdom",
      "realm_id": "worldforge:realm/kingdom_north",
      "realm_name": "Northern Kingdom",
      "government": "MONARCHY",
      "dimension_id": "minecraft:overworld",
      "administrative_title_id": "worldforge:title/north",
      "control_status": "CONTROLLED",
      "color": -11206657,
      "fill_alpha": 38,
      "vertices": [
        {"x": 0, "y": 64, "z": 0},
        {"x": 128, "y": 64, "z": 0},
        {"x": 128, "y": 64, "z": 128}
      ]
    }
  ],
  "features": [
    {
      "id": "wf_settle:capital/old_town",
      "kind": "capital",
      "name": "Old Town",
      "dimension_id": "minecraft:overworld",
      "x": 64,
      "y": 70,
      "z": 64,
      "realm_id": "worldforge:realm/kingdom_north",
      "territory_id": "worldforge:title/north",
      "race_id": "wf_origins:human",
      "culture_id": "worldforge:culture:northmen",
      "subtype": "city",
      "color": -1,
      "attributes": {"population": "1250"}
    }
  ]
}
```

## Rules

- `dimension_id` is mandatory and must match the Minecraft dimension registry ID exactly.
- Coordinates are block X/Y/Z, never galaxy or system-space coordinates.
- IDs are stable, namespaced identifiers; display names are not keys.
- `realm_id`, `territory_id`, `race_id`, and `culture_id` are independent relations.
- Territory polygons are authoritative only to the extent stated by WorldForge's `control_status`; a claim must not be rendered as confirmed ownership.
- Feature kinds are open strings, so new addons can publish POIs without changing Navigator code.
- Unknown optional attributes are retained as strings and ignored by the renderer until a layer understands them.
- Snapshot schema version mismatch, duplicate IDs, invalid coordinates, excessive file size, or malformed polygons reject the snapshot as a whole.
- The Navigator import is read-only and never writes back to WorldForge.

## Отображение в Atlas Surface Map

В текущей реализации Atlas:
- отбирает записи по точному `dimension_id`;
- обрезает полигоны по границам viewport;
- рисует прозрачную заливку по scanline spans и точные контуры, включая вогнутые полигоны;
- размещает подпись на внутренней scanline-позиции, а не на первой вершине;
- показывает POI-маркеры поверх исследованной поверхности.

Геометрические операции вынесены в независимый от Minecraft класс `WorldForgeMapGeometry` и покрыты unit-тестами. Политические полигоны не подменяют данные исследования поверхности и не открывают неисследованные чанки.

## Ownership and rollout

WorldForge remains authoritative for political and civilization state. Navigator owns map presentation, layer visibility, clustering, filtering, labels, and route visualization.

This file-based importer is the first transport-independent bridge boundary. It enables local validation and map rendering while a later optional WorldForge bridge adds server-authoritative snapshot/delta packets. The later bridge must use the same schema and explicit dimension-to-celestial-body mapping; no inferred planet mapping is allowed.
