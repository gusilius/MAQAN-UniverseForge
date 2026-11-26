# Аудит интеграции 19 WorldForge addons в автономную генерацию

Дата проверки: 2026-11-18  
Рабочая ветка: `feature/native-world-genesis-bootstrap`

## Архитектурное правило

Базовый мир всегда является sandbox. Никаких встроенных кампаний, сюжетных сценариев, обязательных целей или сценарного bootstrap. `wf-worldsim` создаёт и развивает мир сам. Будущая кампания может быть только отдельным необязательным addon.

Картографический мир уже использует `MapData` и `MapChunkGenerator`: стратегические данные карты доступны генератору, но физические чанки создаются по запросу Minecraft при открытии территории. Ванильное измерение использует ту же симуляцию, получая географию из реально наблюдённых чанков. Полный pre-generation планеты не нужен.

## Модули

| Addon | Роль в мире | Текущий статус / граница |
|---|---|---|
| `wf-worldsim` | Rust emergence, initial bootstrap, population bands, polities, Java materialization | Центральный координатор. Исправляются sparse vanilla bootstrap, стабильные координаты и независимые cursor partitions измерений. Встроенный campaign runner удалён из базового мода. |
| `wf-people` | Canonical CharacterRegistry/CharacterAuthority, population, family, lifecycle | Основной источник истины для персонажей; отдельные измерения используют независимые partitions одной общей authority. |
| `wf-origins` | 46 race/subrace definitions, classes, professions, traits | Каталог подключён к материализации NPC. Текущий emergent выбор использует общие веса; локальные biome/planet race profiles ещё требуется добавить. |
| `wf-settlements` | Canonical Colony, residents, workers, buildings, settlement economy and logistics | Импортирует именованные image-map anchors и симулирует Colony без необходимости загружать все чанки. WorldSim должен связывать emergent population bands с ближайшими импортированными anchors, чтобы не создавать соседние дубли. |
| `wf-realms` | Realms, Statecraft, titles, governments, taxes, vassalage | Emergent realms регистрируются в общем политическом реестре; government auto-development выполняется без сценарного preset. Исправляется недопустимое понижение ранга суверенного титула. |
| `wf-court` | CourtSim, court lifecycle and events | Realm-level offices создаются через существующий `Courts.ensureRealm`. Полный CourtSim сейчас ориентирован на map-backed CourtData; виртуальным emergent realms требуется отдельный adapter для долгосрочной придворной симуляции. |
| `wf-trade` | Markets, goods, tariffs, trade routes, caravans | Тикает по `RealmsMod.realms()`, включая emergent realms; соседство emergent realms берётся из WorldSim. |
| `wf-progress` | Skills, levels, attributes, progression | NPC profiles синхронизируются через `PeopleProgressBridge.syncAll`; применение игровых атрибутов игроку остаётся login/player path. |
| `wf-orders` | Durable order queue and order execution | Обрабатывает и сохраняет приказы; начальные поселковые рабочие задачи принадлежат settlement simulation, а не создаются сценарием. |
| `wf-quests` | Optional ambient events/quests | Может генерировать обычные локальные события из данных addon. Не является кампанией и не должен вводить обязательные цели или сценарные условия существования мира. |
| `wf-relations` | Player–NPC bonds, dating, family/social interaction | Персонажные отношения активируются взаимодействиями игрока; это не генератор NPC-политики. |
| `wf-reputation` | Player standing with factions/organizations | Событийная репутация игрока; создаётся/меняется после действий и свидетельств, а не при пустом мире. |
| `wf-dialogue` | Dialogue content and interaction | Игровой interaction layer; загружает данные, не формирует карту или государства. |
| `wf-forms` | Player forms/transformations | Игровой персонажный слой; не является обязательной стадией world genesis. |
| `wf-genesis` | Player character creation/start transaction | Создание игрока отделено от автономного создания цивилизаций. |
| `wf-army` | Army/Corps/formation aggregates | MapData-scoped engine; интеграция с vanilla WorldSpace и AI armies относится к отдельному RTS/military этапу. |
| `wf-war` | War engine, map political overlays and war snapshots | MapData-scoped; не включать в acceptance автономного населения как обязательную зависимость. |
| `wf-troops` | Physical troop entities, supply and military operations | Физические бойцы и военная materialization относятся к RTS/military этапу. |
| `wf-rts` | Player selection, command, camera, construction and battles | Исключён из текущего world-gen acceptance по требованию: управление, ручная стройка и сражения проверяются отдельно. |

## Исправления текущего прохода

1. Ванильная география использует фиксированное начало координат. Исследование новых областей больше не должно сдвигать ранее созданные поселения и политические центры.
2. Native bootstrap ванильного измерения выбирает сообщества из реально наблюдённых пригодных участков, а не требует, чтобы четыре кандидата случайно попали в одну маленькую корзину.
3. Расстояние между новыми frontier communities ограничивается сверху, чтобы увеличение исследованной площади не делало новые поселения всё более редкими.
4. Cursor материализации NPC разделяется по точному ID каждого измерения/карты.
5. Суверенный титул не понижается ниже ранга своих территориальных вассалов.
6. Базовый `WorldSimMod` больше не содержит автономный campaign runner, сценарные цели, принудительную загрузку settlement chunks или экспорт campaign reports.
7. Image-map population bands должны переиспользовать существующие `SettlementSeed` Colony anchors по близости и стабильному map ID.

## Что должно доказать runtime acceptance

- Один серверный процесс с обычным non-flat Overworld и image-derived dimension.
- В image dimension физические чанки создаются только при открытии/запросе; вся картографическая география не превращается в предварительно загруженные чанки.
- В обоих измерениях: communities, settlements, population, canonical Characters, rulers, dynasties, Statecraft titles, government profiles and race data.
- Image-map anchors переиспользуются, а не дублируются emergent Colony рядом.
- Economy/markets/settlement simulation тикают без игрока рядом; physical chunk construction и RTS commands не являются условиями создания цивилизации.
- Повторная загрузка и расширение исследованной области не меняют IDs и не создают повторных поселений, персонажей, титулов или династий.

