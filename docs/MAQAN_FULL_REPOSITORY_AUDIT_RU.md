# MAQAN UniverseForge — полный аудит модулей и архитектуры

**Дата:** 15 ноября 2026  
**Репозиторий:** `gusilius/MAQAN-UniverseForge`  
**Проверенная ветка:** `feature/autonomous-world-simulation-engine`  
**База аудита:** `73f566991a2e87075fe0c06b04f78538892c5262`  
**Сравнение с main:** 568 commits ahead, 0 behind.  
**Тип аудита:** repository-wide inventory, module/build/metadata/test inventory, source-level review of domain owners and integration callsites, architecture and CI review. Это статический аудит исходников. Он не заменяет игровое тестирование в Minecraft.

## 1. Главный вывод

**Проект не находится в состоянии «почти ничего не перенесено».** В нём уже реализовано много крупных систем и межмодульных мостов. Прежняя цифра «2 Rust operations» была корректной только как число операций, прошедших строгую приёмку Rust production ownership, но она не описывает объём уже существующего кода, число модулей, работающие Java-системы или Atlas Rust UI.

Нельзя также объявлять весь проект завершённым: несколько критических сквозных сценариев и архитектурных границ всё ещё не приняты. Правильная оценка — **большая функциональная база уже есть; основная незавершённая работа сосредоточена в межсистемной атомарности, полном автономном жизненном цикле, независимости модулей, масштабировании и доказательствах runtime-поведения**.

Никакие игровые функции, население, история, количество армий или масштаб мира не должны урезаться ради производительности. LOD/агрегация/батчинг могут менять частоту и представление вычислений, но не каноническое существование людей и последствий.

## 2. Масштаб репозитория

На проверенном commit рекурсивное Git tree содержит 1 369 записей (файлы и каталоги), без truncation.

| Область | Файлы | Java | Rust | Тестовые файлы |
|---|---:|---:|---:|---:|
| Atlas | 103 | 70 | 5 | 5 |
| WorldForge Core (`wfmod`) | 145 | 130 | 0 | 9 |
| WorldForge addons и общие addon tests | 574 | 321 | 0 | 65 |
| MAQAN Platform | 22 | 17 | 0 | 5 |
| Shared contracts / identifiers / utilities | 13 | 0 | 0 | 0 |
| **Всего в этих областях** | **857** | **538** | **5** | **84** |

Отдельно в WorldForge native находятся ещё 6 Rust source files в двух crates (`worldforge-sim` и `worldforge-worldsim`). В сумме в репозитории найдено 11 Rust source files: 5 Atlas + 6 WorldForge.

84 — это число файлов тестовых классов, а не количество assertions или успешно пройденных тестов.

## 3. Фактическая структура продуктов

Физически в репозитории 19 WorldForge addon-модулей. Это не противоречит целевой архитектуре шести продуктов: шесть продуктов — логические продуктовые границы, а 19 Forge addons — текущая декомпозиция исходников и артефактов. Не нужно механически сливать их в шесть JAR без отдельного dependency/save/registry audit.

### 3.1 Atlas — отдельная картографическая и космическая система

Путь: `mods/atlas`; mod id сохраняется как `maqan_galactic_navigator`; Forge 1.20.1-47.4.28; Java 17.

**Подтверждено в исходниках:**
- каталог Cosmic Horizons, сканирование datapack/world-variable данных, galaxy/system/body hierarchy;
- построение космической сцены и визуализация звёзд, планет и спутников;
- орбитальная динамика через `NativeOrbitalSimulation` и единый `CosmicMotionClock`;
- динамические маршруты, перехват движущихся целей, препятствия, планирование прибытия;
- исследование поверхности, terrain projection, explored chunks, waypoints и player marker;
- WorldForge map snapshot/geometry и Platform selection bridge;
- Rust JNI используется как production-слой композиции chrome, карточек, вкладок, меню, переходов и layout; Java передаёт draw packet существующему Minecraft `GuiGraphics`.

**Важно для Rust-аудита:** `NativeOrbitalSimulation` — Java-класс, несмотря на слово Native в имени. Его N-body/Kepler calculations нельзя автоматически записывать в Rust migration. Atlas Rust сейчас подтверждён прежде всего как UI composition/layout/motion layer. Он не является доказательством, что все космические расчёты уже перенесены.

**Открытые проверки:** parity GUI preview ↔ in-world orbit controller; единицы/оси/координатные преобразования; parent resolution (луны, бинарные звёзды, циклы); datapack reload; маршруты при смене измерения и движущейся цели; persistence разведанных областей; отсутствие/несовместимость native library; большие каталоги; точная совместимость с Cosmic Horizons 0.0.7.3 и datapacks пользователя. Atlas остаётся самостоятельной картой, не частью Simulation или Politics.

### 3.2 WorldForge Core — общая игровая инфраструктура

Путь: `mods/worldforge/wfmod`; mod id `worldforge`; Forge 1.20.1-47.3.0; Java 17.

**Подтверждённые владельцы и сервисы:**
- WorldId и WorldIdentityStore;
- карта, регионы, title/realm data, границы, map sync и политическая визуализация;
- GameClock / ClockService;
- ActionExecutionService, ActionContext, ActionResult, BuiltinActions;
- EventLog и доменные события;
- TreasuryLedger / TreasuryService и TreasuryApi;
- DomainMutationRevisions;
- WorldForgeComputeRuntime — общий bounded compute worker;
- API, permissions, network, persistence, file-batch support;
- Platform bridge и общие клиентские map/UI элементы.

**Оценка:** Core уже является настоящим владельцем общей инфраструктуры, а не пустой оболочкой. Но наличие общего API не означает, что каждый addon использует его как единственный путь записи: публичные mutable поля/коллекции и прямые addon writers ещё требуют аудита. Особенно важно не включать глобальный snapshot/apply fence на неполном revision vector.

### 3.3 WorldForge addons — 19 модулей

Все 19 модулей имеют собственные Gradle build-файлы, Forge mod metadata и Java 17 toolchain. У каждого есть собственная предметная ответственность.

| Addon | Фактическая область и подтверждённая база | Главная граница / незакрытая интеграция |
|---|---|---|
| `wf-army` | Стратегические армии, recruitment, levy, mercenaries, movement, battles, sieges, upkeep, army AI; есть idempotency receipts, staged recruitment и TreasuryApi migration. | Стратегический ArmyEngine и физические бойцы `wf-troops` — разные уровни модели; требуется end-to-end reconciliation roster ↔ Character/TroopEntity, supply и persistence. |
| `wf-court` | Двор, council, factions, schemes, opinions, regency, marriages, events, succession; CourtSim использует CharacterRegistry. | Сквозная автономная семейная жизнь, поколения и durable cross-domain succession должны быть проверены вместе с Character, estate и titles. |
| `wf-dialogue` | JSON dialogue trees, conditions/effects, sessions, registry, memory, network/UI; расширяется bridges в другие addons. | Dialogue effect не является фактом исполнения: реальные последствия должны подтверждаться доменным сервисом и receipt. Нужна общая action registry и NPC↔NPC scale. |
| `wf-forms` | Множественные формы/трансформации, стадии, триггеры, потребности, слабости, контроль и лечение через внешнюю среду. | Полная связка race/class/Character, save/reload и физические/визуальные формы в Minecraft требуют интеграционных тестов. |
| `wf-genesis` | Создание персонажа, race/appearance/origin, профессии, класс/атрибуты/abilities, выбор старта и основание поселения. Есть PlayerCharacterBinding и тесты global UUID. | Genesis, Origins, Progress и Character должны сохранять одну progression/identity truth при любом пути создания/восстановления. |
| `wf-orders` | Гильдии, ордена, братства, ранги, взносы, контракты и петиции. | Связать организационные полномочия и задания с общим Character, reputation, treasury и ActionExecution receipts. |
| `wf-origins` | Data-driven races, classes, professions, synergies, perks и progression; immutable generation-tagged definition snapshot. | Definitions snapshot уже укреплён; остаются междоменные Progress/Character transactions и runtime-проверки datapack reload. |
| `wf-people` | Canonical Character, CharacterAuthority, global UUID, lifecycle/family, profession/duty/skills, CharacterDevelopment, inventory, PopulationSimulation, LifeSimulation. | Самая центральная система. Нужны завершённый аудит прямых mutable writers, общий action executor для всех ролей и подтверждение автономной жизни вне поселений. |
| `wf-progress` | Общий RPG progression: XP, level, attributes, skills, passive tree, archetypes, stat sheet; owner-key bridge для NPC/игроков. | Синхронизация профиля Progress с Character должна оставаться однозначной, без второго конкурирующего уровня/skill store. |
| `wf-quests` | Quest chains, branching choices, giver types, requirements, world events, player progress и hooks в другие системы. | Quest completion должна зависеть от durable receipt реального действия, не от диалогового текста/намерения. |
| `wf-realms` | Government types, realm levels, doctrines, laws, political fit, automatic development и shared StatApi signals. | Политические writers должны участвовать в revision vector; проверить влияние законов/доктрин на реальные доменные операции, а не только modifiers/UI. |
| `wf-relations` | Affinity/love, dating/marriage, hiring, gifts, requests, cooldowns, Character traits и resident interaction. | NPC↔NPC отношения и долгосрочные социальные последствия ещё не подтверждены в масштабе всего мира. |
| `wf-reputation` | Scoped reputation (world/settlement/polity/organization), standing, witnesses/knowledge, rumor links и confidence. | Распространение знаний должно оставаться ограниченным свидетелями/организациями, а не глобально телепатическим; проверить event-driven rumor delivery. |
| `wf-rts` | Selection, control groups, command cards, order planning/dispatch, work priorities, map view и HUD. | Orders/command generation и domain execution/receipt — разные стадии. Все команды из RTS и first-person должны вызывать одну canonical command pipeline. |
| `wf-settlements` | Settlement, buildings, production, stockpiles, resource flows, roads, map/zones, workers, construction sites, schematics, resident life/social/task graph, vanilla village adapter. | Worker сохраняет legacy aggregate fields/ID и Character link; требуется единая инвентарная/ownership transaction и подтверждение реального pathing/work receipts для всех действий. |
| `wf-trade` | Markets, goods, production/consumption, prices, caravans, routes, tariffs, embargoes, trade volume и player/merchant flows. | Связать caravan cargo, stock, treasury, delivery, raiding и supply одной идемпотентной transaction chain. |
| `wf-troops` | Physical TroopEntity, Character binding, squads, formations, ArmyHierarchy, commander roles, muster, transition/death ledgers, morale, operational supply, campaign operations, orders. | Проверить отсутствие расхождения между strategic roster, physical entity и Character после смерти/плена/перевода/перезапуска. |
| `wf-war` | WarEngine, casus belli, declarations, alliances, truces, peace, occupation, diplomacy and durable action receipts. | Военные исходы и физические бои должны быть связаны с армейскими/экономическими последствиями через подтверждённые события и транзакции. |
| `wf-worldsim` | WorldSim coordinator, geography snapshots, emergent population/polities, chronicle, native compute scheduler/profiler, decision proposals and optional Population compute integration. | Нужны atomic apply/replay, complete writer freshness, campaign stability и decoupling from mandatory sibling compile dependencies. |

**Обнаружены ошибки metadata:** `wf-army/gradle.properties` описывает модуль как Court life и говорит, что он needs `wf_war`; `wf-rts/gradle.properties` также содержит скопированное описание Court life вместо RTS. Это не доказывает ошибку runtime-функций, но вводит в заблуждение при установке/каталоге и должно быть исправлено в metadata pass.

### 3.4 MAQAN Platform — единая оболочка

Путь: `platform`; mod id `maqan_platform`; Forge 1.20.1-47.4.28; Java 17.

**Уже есть:** PlatformShellScreen, CapabilityRegistry, PlatformSelectionContext, WorkspaceDockTree, WorkspaceRuntime, WorkspaceMapLayerRegistry, WorkspaceProfileStore, WorkspacePreviewRenderer, WorkspaceTheme/Ui; тесты registry, dock tree, map layers, profile persistence и runtime.

**Оценка:** это реальный platform shell с рабочими инфраструктурными частями, но нормативный документ MAQAN Platform прямо помечен как target architecture, а не доказательство, что каждый addon уже зарегистрировал полноценный workspace/provider. В текущем mainline есть Platform selection bridge и WorldForge timeline event projection; полная регистрация Atlas + всех installed module workspaces, inspector/search/actions и lifecycle isolation ещё не принята.

**Правило:** Atlas остаётся отдельной системой карты, но доступен из единого интерфейса. Platform владеет shell/selection/dock/theme; Atlas и WorldForge владеют своими данными и инструментами. Не создавать второй shell в NavigatorScreen и не переносить доменные вычисления в Platform.

## 4. Межсистемные инварианты и реальное состояние

### 4.1 Character identity

**Уже реализовано:** WorldId, CharacterAuthority, global Character UUID, player-to-Character binding, identity migration tests, Character-backed worker/soldier/commander paths, role/duty transitions, physical entity binding, family references and save/load tests.

`CharacterMilitaryIdentityTest` проверяет Worker → Troop → Worker на одном Character. `CharacterIdentityMigrationTest` проверяет детерминированные global UUID и семейные ссылки при миграции per-map saves. `GenesisPlayerIdentityTest` проверяет игрока и dedicated player partition.

**Остаток:** canonical identity уже есть, но это не означает один физический map/registry object: CharacterAuthority маршрутизирует global identity между map/player partitions. Внешние writers и публичные mutable Character fields/props требуют дальнейшего ограничения. Нельзя возвращаться к Worker/Troop как самостоятельным людям.

### 4.2 Inventory / ownership / treasury

**Уже реализовано:** Character-owned inventory, item/equipment records, conflict-aware player physical inventory sync, inventory transactions, container/estate support, canonical TreasuryLedger, TreasuryService, legacy balance migration, settlement/trade/army adapters, idempotent operation receipts.

**Остаток:** общий end-to-end transaction across Character inventory ↔ settlement stock ↔ crafting ↔ army requisition ↔ caravan/campaign consumption ещё не доказан. TreasuryLedger имеет canonical mutation APIs, но mutable Account compatibility surface требует осторожности. Нельзя заводить второй ledger, второй inventory authority или отдельные балансы ради новых функций.

### 4.3 Time / simulation

**Уже реализовано:** общий GameClock API, WorldSim orchestration, PopulationSimulation, settlement simulation, CourtSim, WarEngine/ArmyEngine day advancement, LOD, event timeline/chronicle, emergent world state.

**Остаток:** проверить каждый local accumulator/day counter на строгую derivation от GameClock и pause/reload semantics. Один существующий GameClock не доказывает, что каждый addon уже использует только его. В частности, военные и поселковые clocks должны быть проверены на drift, double-advance и save/load.

### 4.4 RTS / settlement / battle

**Уже реализовано:** выбор и приказы RTS, контрольные группы, worker commands, work priorities, task graph, construction blueprint/materialization, settlement logistics, ArmyHierarchy, physical troops, formations, muster, morale, operational supply, military death/transition receipts.

**Остаток:** полный единый typed command/action pipeline для RTS, dialogue, hotkeys и first-person; реальное pathfinding/replanning/failure diagnostics; связка recruitment building → canonical Character → strategic roster → physical TroopEntity; полноценный tactical combat parity/scale. Наличие меню/приказа не доказывает, что весь маршрут от команды до физического результата закрыт.

### 4.5 Social / life / generations

**Уже реализовано:** needs, professions, duties, skills, life stages, family references, marriage, succession, personality/temperament/drives, mood, memories, injuries, morale, social relations, reputation, quests and dialogue.

**Остаток:** общий executor действий для residents/travelers/court/soldiers/heroes; реальные физические и социальные результаты вместо status-only success; автономное рождение, воспитание, образование и взросление; многопоколенная симуляция с наследованием имущества/управления и save/load между поколениями. Это один из самых больших функциональных acceptance gaps.

### 4.6 Atlas ↔ WorldForge ↔ Platform

**Уже реализовано:** versioned WorldForge map snapshot/geometry, live WorldForge→Atlas snapshot transport в mainline, Platform selection bridge, WorldSim publication of timeline events to Platform (optional reflection).

**Остаток:** полный набор live spatial overlays/deltas, stable shared spatial IDs, все workspace providers и общий inspector/search/action routing. Atlas не должен превращаться в часть WorldSim/Politics: он остаётся владельцем картографической/навигационной системы.

## 5. Rust Compute — пересмотренная, точная классификация

Нужно разделять Rust source presence, Rust production callsite, ownership of a production operation и acceptance после parity/save-reload/freshness gates.

| Область | Текущее подтверждённое состояние | Статус |
|---|---|---|
| Atlas native core | Rust JNI production calls из NavigatorScreen для chrome, layout, cards, tabs, context menus, transitions; Java сохраняет Minecraft rendering host. | Rust UI composition в production; не считать переносом орбитальной физики. |
| `worldforge-worldsim`: emergence | Rust `emergence::advance` через JNI/shared WorldSim worker; Java snapshot/materialization/apply. | Production Rust owner; async/campaign acceptance ещё требует CI и campaign evidence. |
| `worldforge-worldsim`: decisions | Rust `decide` через тот же shared worker; Java валидирует/применяет proposals через domain owners. | Production Rust owner; parity/large snapshot/campaign verification pending. |
| `worldforge-worldsim`: population | Rust `population::advance`, JNI, immutable projection, shared runtime pipeline, per-Character receipts, shared Java/Rust fixture на 60 boundary Characters; fallback Java exclusive. | Pipeline подключён; полная migration acceptance pending. Не считать отдельной принятой миграцией. |
| `worldforge-worldsim`: world generation | Rust `worldgen::generate_world` + JNI wrapper; production caller не подтверждён в source audit. | Dormant/unverified; не считать production port. |
| `worldforge-sim` | Чистая Rust `advance_food` для SettlementSnapshot, без JNI/Forge/FS/clock; production callsite в текущем registry не зарегистрирован. | Library candidate/unverified, не считать production port. |
| Atlas orbital simulation | `NativeOrbitalSimulation` — Java calculation engine, не Rust. | Java owner; Rust port не подтверждён. |
| Snapshot, Character/realm/settlement materialization, ActionExecution, persistence, Minecraft side effects | Остаются Java по архитектурному контракту. | Намеренно не переносить в Rust. |

**Подтверждённый shared runtime:** `WorldForgeComputeRuntime` — один bounded process-wide worker; `WorldSimComputeScheduler` и Population coordinator используют его, а не создают отдельные executors. Не добавлять новый scheduler/worker/pool/cache/registry без доказанного bottleneck и согласованного ownership.

**Главное уточнение:** «2 принятые production операции» — не «в проекте перенесено только две вещи». Это лишь две операции, которые текущий registry принимает как Rust-owned production compute. Population уже подключён, Atlas уже использует Rust для UI, другие Rust crates присутствуют, но их нельзя записывать в production migration без caller/ownership evidence.

### 5.1 Кандидаты для дальнейшего compute audit

Только после source-level decomposition и profiling:
- экономика и trade pricing/market simulation;
- logistics/path planning и массовая навигация;
- дополнительные political/diplomatic planning operations;
- military/tactical simulation;
- spatial/geographic calculations.

Это пять candidate groups, не пять гарантированных Rust портов. Для каждой операции сначала определить текущую Java формулу, production callsites, владельца данных, existing indexes/caches, external optimizers, частоту и стоимость. Переносить только чистую детерминированную часть с доказанным выигрышем. Не переносить Minecraft API, persistence, canonical mutation и side effects.

## 6. Сборка, зависимости и тесты

### 6.1 Build topology

- Root Gradle orchestrates independent Atlas, WorldForge Core and addon builds.
- Atlas и WorldForge сохраняют отдельные mod IDs, JARs и Forge patch versions.
- Addon build tool `tools/build_order.py` строит dependency graph из `compileOnly wf_*.jar` declarations; `build-all.sh` строит core и addons в топологическом порядке, блокирует потомков упавшей зависимости и собирает dist.
- 19 addons имеют свои build.gradle/settings.gradle/gradle.properties и mods.toml.

**Архитектурное расхождение для исправления:** `wf-worldsim/build.gradle` содержит compileOnly references на большой список sibling gameplay addons. Это противоречит целевой границе «Simulation зависит от Core, а domain addons подключаются как optional participants». Build graph может корректно обеспечивать сборку, но сам по себе не доказывает runtime optionality. Следующий аудит должен отделить compile-time adapter artifacts от обязательной runtime зависимости и удалить прямые implementation links там, где достаточно versioned participant/contract. Не менять dependency metadata до проверки imports, reflective bridges, Forge load order и standalone-combination tests.

Также проверить каждый soft dependency на отсутствие class verification/linkage crash при отсутствии addon. `compileOnly` означает отсутствие автоматического bundling, а не гарантированную безопасную optional integration.

### 6.2 Test inventory

В tree найдено 84 тестовых файла:
- Atlas — 5;
- WorldForge Core/tool tests — 9;
- WorldForge shared addon tests — 65;
- Platform — 5.

Существуют отдельные suites для Character identity/lifecycle/inventory, military transitions, TreasuryLedger, settlement/task graph, RTS, trade, war, court, origins, quests, relations, reputation, world clock, worldsim scheduler, map sync/geometry и Platform workspace runtime.

**Ограничение:** наличие тестового файла не доказывает покрытие всех сценариев. Нужна матрица test → owner → behavior → standalone module combinations → persistence/fault injection. Отдельные datapack-dependent/manual tests могут не исполняться текущим CI workflow; это должно быть явно отражено в test inventory.

### 6.3 Текущий CI run

Run: [43709373998](https://github.com/gusilius/MAQAN-UniverseForge/actions/runs/43709373998)  
HEAD: `73f566991a2e87075fe0c06b04f78538892c5262`

На последней перепроверке:
- MAQAN Platform shell — success;
- Atlas Forge + Rust Windows — success;
- WorldForge native core Linux — success;
- WorldForge Core Forge — success;
- WorldForge logic tests — success;
- WorldForge native core Windows — success;
- WorldForge addons — **in progress**, dependency-order build ещё выполнялся.

Итого: 6 из 7 jobs завершились успешно; 1 ещё выполняется. **Полный CI не подтверждён зелёным.** Кроме того, CI содержит Linux native job, поэтому README формулировка «Windows x64 only» неполна: игровые Forge jobs Windows-only, но native Rust verification также выполняется на Linux.

CI build/logic tests/combined server boot не заменяют in-game campaign и Cosmic Horizons test.

## 7. Открытые блокеры и риски

### P0 — автономная кампания и server watchdog

`WF-RT-0001`: повторный Watchdog во время автономной кампании; точная первопричина не установлена. Предполагаемый участок — синхронная materialization emergent people/Origins, но это гипотеза, а не установленный виновник. Критерий закрытия — полный четырёхпоколенный прогон без Watchdog с полными canonical Characters/Origins; нужны crash reports и periodic JVM thread dumps. Не закрывать проблему снижением числа людей/государств или отключением систем.

### P0 — cross-domain apply atomicity

`WF-RT-0021`: WorldSim frame может затронуть Character/Origins/Settlement/EventLog/ActionExecution, а затем завершиться ошибкой до полного применения. Staging emergence state уже уменьшает риск продвижения native cursor, но общего durable frame receipt/resumable apply protocol ещё нет. Нужны fault-injection на каждом этапе, restart между этапами, стабильные event IDs, idempotent apply и отсутствие потери/дублирования фактов. Расширять существующие canonical receipts; не создавать второй EventLog/journal.

### P0 — mutation revision / freshness coverage

`WF-RT-0023`: DomainMutationRevisions уже существует; Character mutation batches, OriginDefs immutable snapshot, части Politics/Economy/EventLog writers подключены. Population может использовать scoped CHARACTERS dependency. Но global WorldSim snapshot/apply fence остаётся запрещённым, пока все поля snapshot не сопоставлены с canonical writers и все domain lanes не покрыты.

Особенно проверить Settlement/ResidentTaskGraph, Army/War, Inventory/Ownership, Progress/Origins cross-domain writers и публичные mutable DTO/Account/Character collections. Revision metadata не должна превращаться во вторую базу данных или увеличиваться на каждый derived/cache write.

### P1 — Population Rust acceptance

`worldsim.population.advance` уже подключён к shared runtime, с immutable projection, frame validation, durable per-Character day receipts и Java fallback, который не выполняется параллельно. Shared parity fixture расширен до 60 Characters и boundary cases. Осталось доказать полную parity, freshness, save/reload/restart, partial batch recovery, native error handling и длинную campaign. До этого не объявлять Population полностью migrated и не включать параллельный Java shadow production compute.

### P1 — RTS/military/settlement end-to-end

Проверить recruitment transaction → canonical Character → strategic roster → physical TroopEntity → command/formation → supply → combat outcome → casualty/injury/estate → save/reload. Существующие ArmyEngine/Troops/Settlement bridges уже значительны; задача — закрыть reconciliation и failure recovery, а не переписать их отдельными системами.

### P1 — единый Action/Task execution

Свести work, haul, build, move, attack, patrol, recruit, resupply, dialogue choices и first-person interactions к одному typed command contract с actor/target IDs, preconditions, revisions, idempotency key, failure reason, retry/alternative и durable receipt. Сейчас часть модулей имеет собственные command/order/task adapters. Нельзя принимать план/строку команды/диалог как успешное последствие.

### P1 — build/runtime optional composition

Проверить реальную dependency matrix всех 19 addons и standalone combinations. Особое внимание `wf-worldsim` compileOnly sibling dependencies, reflective adapters, Forge load order и корректному отключению provider при отсутствующем модуле. Целевая архитектура требует независимых продуктов; dependency graph должен это подтверждать, а не только позволять собрать всё вместе.

### P2 — scale/performance evidence

`WF-RT-0015`: profiler instrumentation добавлена, но измерения полного Forge campaign и всех ServerTickEvent listeners не подтверждены.  
`WF-RT-0016`: scale/battle contract документирован, но cohort combat, 1000×1000 simulation, renderer/entity scaling и сетевые тесты ещё не приняты.  
Не добавлять вторую оптимизацию поверх существующего индекса/кэша/пула. Не утверждать численный прирост без benchmark artifact.

### P2 — Platform provider coverage

Довести регистрацию каждого установленного workspace, search/index, inspector, map layers, context actions, typed command results и provider unload lifecycle. Atlas остаётся самостоятельным, но открывается из общего shell. Не копировать UI shell внутрь каждого addon.

### P2 — Atlas compatibility/performance

Проверить exact Cosmic Horizons build/datapacks, coordinate/units, orbital parity, route transitions, explored-surface persistence, native fallback и large catalog profiling.

## 8. Что уже не нужно делать заново

По текущим source/test evidence не нужно создавать заново:
- глобальную идентичность Character и базовый player binding;
- отдельный второй NPC registry;
- вторую систему инвентаря или казначейства;
- второй календарь как замену GameClock;
- новый ArmyHierarchy/strategic army engine/RTS selection;
- новый settlement production/task graph;
- новый dialogue/quest/reputation/relations layer;
- второй Atlas или встраивание Atlas в WorldSim;
- отдельный Rust worker/scheduler на каждый addon.

Нужно завершать и укреплять существующие owners/adapters/receipts, а не строить параллельные реализации.

## 9. Рекомендуемый порядок дальнейшей работы

1. Дождаться полного результата текущего CI run 43709373998; при ошибке исправлять конкретную первопричину, не ослабляя тесты.
2. Закрыть source-level dependency matrix 19 addons и исправить metadata descriptions; отдельно проверить optional runtime load/unload.
3. Продолжить WF-RT-0023 по доменным writer lanes и закрыть WF-RT-0021 durable frame apply/recovery. До этого не расширять WorldSim parallelism и не объявлять global freshness.
4. Завершить Population parity/save-reload acceptance и только затем зафиксировать точный production ownership count.
5. Параллельно закрыть campaign Watchdog с полными диагностическими артефактами, не снижая полноту мира.
6. Свести RTS/settlement/dialogue/first-person к единому ActionExecution/Task receipt contract.
7. Провести end-to-end generational life + military transition + inheritance + save/reload scenario.
8. После этого — большие profiling/scale passes, third-party optimizer inventory и только обоснованные Rust ports.
9. Закрыть Platform workspace provider matrix и Atlas exact compatibility/in-game acceptance.

## 10. Граница этого аудита

Этот документ фиксирует repository-wide module inventory и source-level audit главных владельцев/мостов/рисков. Он не заявляет, что все 538 Java files были вручную перепроверены построчно или что выполнен in-game playtest. Для полного line-by-line sign-off следующим шагом является проход по каждому module entrypoint, public API, Forge event subscriber, persistence adapter и external writer с привязкой к конкретным тестам. Никакая функция не помечается отсутствующей только потому, что её не нашёл короткий README; статус определяется исходниками и production callsites.

---
**Правило дальнейшей разработки:** один владелец данных, одна production формула на операцию, один shared compute runtime, один canonical action/receipt path. Сохранять все возможности, содержимое миров, масштаб населения, историю и игровые последствия.
