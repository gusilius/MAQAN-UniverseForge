# wf-settlements

Поселение считается числами (`Settlement.tick`), поэтому тянет много поселений на сервере. Тела рабочих и стройка блоков в мире - только отображение, их делает слой Minecraft рядом с игроком.

Приказы как в RTS: `place` (заложить здание), `assign` / `unassign`, `setProfession`, `setPriority`, `cancel` (возврат 100% / 50% / 25%). При `auto = true` свободные работники сами идут на стройку и свободные места.

Новое здание или профессия - JSON `data/<ns>/wf_settle/*.json`, см. `Defs`:
`cost`, `work`, `housing`, `storage`, `jobs`, `recipe {in,out,cycle}`, `requires`, `limit`, `builders`, `size`, `blueprint`.
