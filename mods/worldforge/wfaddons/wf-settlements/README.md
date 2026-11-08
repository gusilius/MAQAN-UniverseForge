# wf-settlements

Поселение считается числами (`Settlement.tick`), поэтому тянет много поселений на сервере. Тела рабочих и стройка блоков в мире - только отображение, их делает слой Minecraft рядом с игроком.

Приказы как в RTS: `place` (заложить здание), `assign` / `unassign`, `setProfession`, `setPriority`, `cancel` (возврат 100% / 50% / 25%). При `auto = true` свободные работники сами идут на стройку и свободные места.

Новое здание или профессия - JSON `data/<ns>/wf_settle/*.json`, см. `Defs`:
`cost`, `work`, `housing`, `storage`, `jobs`, `recipe {in,out,cycle}`, `requires`, `limit`, `builders`, `size`, `blueprint`.


## Settlement map (phase III)

`SettlementMap` is the authoritative, persisted planning layer for a colony. Coordinates are block offsets from the colony centre; terrain is sampled at 8-block cell spacing. Sampling only reads already-loaded chunks. Map coverage expands on demand when a player refreshes the map, zones land, plans a building or draws a road; there is no fixed build radius.

- Terrain cells retain height, biome, water and fertility; slope is recomputed locally.
- Parcels provide non-overlapping residential, agricultural, industrial, commercial, civic, military and storage zoning.
- Roads are A* paths over sampled terrain. Water and excessive slope are impassable. Road segments materialize as gravel/dirt/cobblestone only near players and never overwrite structures.
- Construction plans reference the existing Settlement building UID and mirror its authoritative construction progress.
- Completed storage buildings create spatial warehouse nodes. New production output is allocated to the nearest suitable warehouse; the map reconciles warehouse inventory partitions with the canonical Settlement stock.
- Logistics jobs move resources between warehouses only when both ends share a connected road network; transfer throughput and progress are persisted.
- The map simulation ticks for every colony without loading its world dimension or chunks. Existing Settlement production/consumption remains the canonical production engine.

Commands (coordinates are relative to the settlement centre):
- `/wfmap refresh` — sample nearby loaded terrain.
- `/wfmap zone <id> <residential|agriculture|industry|commerce|civic|military|storage> <x> <z> <width> <depth>`
- `/wfmap road <x1> <z1> <x2> <z2>`
- `/wfmap warehouse <id> <x> <z> <capacity>`
- `/wfmap logistics <from> <to> <resource> <units-per-minute>`
- `/wfmap info` — map coverage and logistics summary.

The in-game map view renders sampled terrain, parcels, road paths, existing buildings and workers. Pure logic coverage is in `SettlementMapTest`; actual Forge world verification is still required.
