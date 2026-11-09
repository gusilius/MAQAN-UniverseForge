# MAQAN UniverseForge — End-to-End Open Sandbox Simulation Audit

Audit date: 2026-11-09  
Target: `integration/maqan-unified`  
Scope: character creation, race, life, family, professions, settlement RTS, first-person construction, army RTS, character transfer between civilian and military roles, persistence and time continuity.

## Executive verdict

**The repository contains several real cross-module gameplay bridges, but it does not yet demonstrate one complete continuous sandbox life.**

Verified integrated slices:
- Genesis creates a player origin/progression profile and can found a settlement.
- Settlement workers are reconciled to Character records; the settlement runs a bounded LifeSimulation executor for those workers.
- Troop entities can bind to Character records; soldiers can be promoted from existing settlement Characters and demobilized back to a settlement.
- RTS squads support selection, orders, formations and canonical Character squad leaders.
- Settlement building plans can be placed through player-owned settlement control, assigned to workers, and materialized into Minecraft blocks.
- Character, settlement, army, squad and player-origin state each have persistence paths.

Blocking gaps:
- Genesis player is not created as a CharacterRegistry Character; player life/family/aging therefore do not share the NPC identity model.
- Race is not a canonical field on Character; player race lives in Origins profile and settlement worker race is copied from the realm into props.
- CharacterRegistry remains per MapData, with locally generated IDs such as c1, c2.
- Settlement Worker still owns its own id/name/profession/building state and only optionally references Character; synchronization copies settlement-owned profession into Character.
- LifeSimulation is wired for settlement workers, not proven as a world-wide executor for all Characters; several successful actions are status/need changes rather than physical/social behavior.
- Family APIs and court marriage logic exist, but the tested flow does not prove births, child upbringing, player family, inheritance of the player identity, or a full generational life.
- Strategic ArmyEngine aggregates and physical RTS squads are connected through an ArmyLink, but are separate models with different ownership/command representations.
- Global time and save boundaries are split across WorldForge clock, Settlement.time, ArmyEngine war.day/dayAccumulator and multiple files.
- No automated test currently walks the complete scenario from Genesis through life, family, work, construction, military service, return to civilian life, death and inheritance.

This is a source-level integration audit, not a claim that the full game was run in Minecraft. A successful build is not gameplay acceptance.

## Scenario walkthrough

### 1. Create a character and choose a race

**Status: PARTIAL / BLOCKED for one world-wide Character identity**

Verified:
- Genesis has a race-aware creation draft, appearance, origin/start, professions, attributes, skills and class selection.
- `GenesisMod.apply` writes the race to `Origins`, initializes the player progression profile, applies profession/class choices and places the player at the selected start.
- Genesis player data is keyed by `p:<player UUID>` and saved in `worldforge/wf_genesis.json`.

Gap:
- `GenesisMod.apply` does not create a corresponding `wf_people.Character` in `CharacterRegistry`.
- `Character` has name, house, culture, gender, birth year, family links, traits and props, but no canonical race field.
- Settlement reconciliation copies the realm's race into `population.wf_race_id` for workers. That is realm-derived context, not an individually owned race identity.
- The player therefore has an Origins/Progress identity while NPCs have CharacterRegistry identities. A single family/lifecycle/role system cannot yet address both as one Character.

Required:
- Create the player as a canonical Character at Genesis confirmation, with stable WorldId + CharacterId.
- Make race, culture, ancestry/subrace and appearance part of the authoritative Character/origin model; views and combat stats must derive from it.
- Keep race differences meaningful but avoid using race as a hard lock that prevents broad player creativity.

### 2. Life, needs, aging and autonomous behavior

**Status: PARTIAL**

Verified:
- PopulationSimulation defines needs, life stages, professions, duties, skill training and deterministic intents.
- CharacterRegistry.refreshLifeStages derives age from a year and emits life-stage transitions.
- PeopleMod advances PopulationSimulation for loaded map registries using WorldForgeApi.clock().pull("wf-people").
- SettleMod.syncPeople reconciles settlement workers to Character IDs and persists lifecycle departures.
- SettleMod.runLifeAI calls LifeSimulation.tick for settlement workers with an LOD based on player proximity.
- EAT consumes settlement food; TRAIN adds skill XP; WORK checks that a worker has a building assignment; sleep/safety/social/recreation update worker status and successful LifeSimulation outcomes.

Gaps:
- LifeSimulation is not wired as a universal executor for every Character in the world. The verified runtime adapter iterates settlement workers.
- Sleep, seek-safety, socializing and recreation do not yet demonstrate actual bed use, pathing to shelter, relationship changes, social events or activity simulation; they set status and allow the AI kernel to reduce need.
- WORK reports success when a building assignment exists; the separate settlement simulation handles production/work progress. There is no demonstrated per-task committed receipt tying that worker's work action to a production transaction.
- WFNpc's own goals remain limited movement/look behavior; they are not the full autonomous life simulation.
- Remote/off-settlement people, families, travelers, court members and ordinary world NPCs are not proven to use the same LifeSimulation executor.

Required:
- One life scheduler over canonical Characters with adapters for settlement, travel, court, army, household and off-settlement contexts.
- Real action execution: path/animation/interaction, resource mutation, relationship/event changes, task receipt, then need effects.
- LOD must reduce detail, not replace autonomous world behavior with fake success.

### 3. Professions and skills

**Status: PARTIAL, with working bridges**

Verified:
- Genesis applies player Origins professions and Progress attributes/skills.
- PopulationSimulation stores profession history, duties, skills and training on Character.
- SettleMod syncPeople maps settlement worker professions to Character and attaches worker/specialist/soldier duties.
- TroopsMod.syncPerson maps physical troop profession and combat XP/rank to Character.
- A Character can retain skill ranks across profession changes.

Gaps:
- Player Origins profession and NPC PopulationSimulation profession are separate profiles; no verified shared player Character progression.
- Settlement Worker profession is a second mutable value owned by Settlement and copied into Character on sync, so there are two authorities.
- Profession catalogs and eligibility checks exist, but a universal career path spanning personal crafting, settlement employment, army service, court office and trade is not proven.
- Profession XP, salary, production quality, workplace access and Character skill progression are not shown to share one authoritative transaction pipeline.

Required:
- Character owns profession, skills, XP and career history; settlement/army/court store assignments and roles referencing CharacterId.
- Domain adapters validate assignment, workplace, tools, materials, permissions and age; successful work posts durable skill/production receipts.

### 4. Family, marriage, children and inheritance

**Status: PARTIAL / BLOCKING for a continuous player life**

Verified:
- Character stores father, mother, spouse and child IDs.
- CharacterRegistry provides marry(), addChild(), death and lifecycle events.
- Court Marriages evaluates/proposes marriages, updates spouse links and political kinship ties.
- MarriageTest contains court marriage and long-running court AI checks.

Gaps:
- MarriageTest is skipped by the current CI because it requires a datapack fixture not supplied to the test command.
- Its manual setup creates test children directly; it does not demonstrate autonomous childbirth or upbringing.
- No verified player-to-Character link means player marriage, children, aging and death cannot yet use this same registry.
- No complete test proves inheritance transfers the playable identity, possessions, titles, settlement command rights and family links to an heir while the world continues.
- Family household, childcare, education, parent-child relationships and family-driven migration are not proven end-to-end.

Required:
- Player and NPCs share CharacterRegistry and one lifecycle.
- Add deterministic fertility/birth/childcare/education/aging systems with configurable world rules.
- Tie succession and inheritance to CharacterId, estate/inventory ownership, titles, settlement governance and player control handoff.
- Test at least three generations with save/load between major transitions.

### 5. Settlement RTS and autonomous village

**Status: PARTIAL / SUBSTANTIAL FOUNDATION**

Verified:
- Settlement has workers, buildings, stockpiles, production, taxes, seasons, raids and simulation time.
- SettlementMap provides sampled terrain, zoning, parcel validation, road planning, warehouses and logistics.
- SettleMod runs settlement simulation and logistics; it also synchronizes workers to Character records and runs LifeSimulation for those workers.
- WorkerControl supports commands to move/hold/unassign workers and assign them to buildings, with settlement ownership checks.
- BuildControl.place places a building plan in a player-owned settlement and assigns selected WFWorker entities as builders.
- BuildingSite materializes blueprint blocks in the Minecraft world, incrementally and with a per-call budget.
- Genesis can found a starting settlement and assign it to the player.

Gaps:
- Worker remains a separate Settlement aggregate with its own ID/name/profession/position/building assignment. Character is an optional link and is reconciled later.
- Worker identity migration is tested with a manually assigned string such as c42; that is not proof all historical and newly spawned workers always enter the canonical registry before gameplay.
- Worker production and movement simulation are still mostly settlement-domain projections; the worker body and Character AI need stronger synchronization and actual path/interaction proof.
- Settlement.time is advanced independently as a local simulation clock using the WorldForge speed multiplier. It is not yet proven to be a direct projection of the shared GameClock.
- Special SettleWand first-person blueprint placement requires trusted/operator permissions. The player-owned BuildControl path exists separately and should be the normal non-operator path.
- No automated test proves the player can freely switch between placing ordinary Minecraft blocks, blueprint construction, selecting workers and returning to direct character control without state divergence.

Required:
- Character is authoritative worker identity; worker is a job/position/task projection.
- One command path for first-person construction and RTS construction; player permission and settlement ownership, not operator-only tools, govern ordinary gameplay.
- Verify pathing, material consumption, building progress, block placement, damage/repair, save/load and worker task completion in a running Forge world.

### 6. Army RTS, soldiers and transition between civilian and military life

**Status: PARTIAL / SUBSTANTIAL FOUNDATION**

Verified:
- ArmyEngine models strategic armies, unit counts, movement on barony adjacency, upkeep, supplies, morale, battles, sieges and AI.
- Physical TroopEntity stores a Character ID; TroopsMod binds it to the CharacterRegistry.
- TroopsMod.assignExistingCharacter can move an existing settlement Character into military service, detach the worker projection, and attach the soldier duty.
- TroopsMod.demobilizeToSettlement can return the same living Character to a settlement.
- Squads support unit membership, orders, formations, leaderCharacterId, morale and persistent queues.
- UnitSelection supports click, shift selection, type selection, box selection and groups 1–9.
- TroopController executes movement, attack, hold/follow and formation positioning.
- ArmyLink transfers troop counts between strategic Army aggregates and physical troop deployments.

Gaps:
- Strategic Army commander is a string plus martial score; Squad leaderCharacterId is canonical. The relationship between strategic commander, physical squad leader, hero Character, title holder and player command authority is not fully unified.
- ArmyEngine still has its own realm gold map and upkeep path, separate from the canonical TreasuryLedger.
- Strategic army aggregate and physical soldiers/squads are separate state stores. ArmyLink has take/give operations, but no end-to-end proof of crash-safe atomic deployment, casualties, desertion, demobilization and exact roster reconciliation across both layers.
- Soldier Character behavior during service (hunger, rest, safety, morale, family contact, leave, injury, veteran progression and death) is only partially shared with settlement life simulation.
- RTS command/control exists, but no verified test switches between direct first-person play, army selection, tactical orders, settlement selection and personal control in one live world.

Required:
- One Character identity and one role/assignment model for soldier, hero, commander and civilian.
- One army roster keyed by CharacterId; unit formations and troop entities are projections.
- Deployment, casualty, desertion and return-to-civilian must be transactional and idempotent.
- Use one task/command execution service and canonical treasury for upkeep/recruitment/supply.
- Verify the same commander and soldier identities in army view, RTS view, Character inspector, settlement and save data.

### 7. First-person construction and seamless control switching

**Status: PARTIAL**

Verified:
- Minecraft first-person block placement remains available.
- Blueprint-based settlement construction is real: BuildControl lays down a settlement building plan and BuildingSite places the blueprint blocks.
- RTS worker assignment and unit selection/control are implemented as separate interaction paths.

Gaps:
- The special SettleWand requires trusted/operator privileges; it is an administrative tool, not the complete player construction loop.
- No evidence of one seamless interaction model for personal building, blueprint placement, selecting workers, issuing orders, tactical camera and returning to first-person control.
- No in-game acceptance evidence for input focus, camera restoration, selection persistence, multiplayer permissions or UI lifecycle across these views.

Required:
- One world interaction layer that supports first-person, settlement, tactical and strategic lenses without switching simulation or ownership models.
- Keep operator tools as optional administration, not as a prerequisite for normal play.

## Automated verification evidence

The unified CI workflow run `42625287440` completed successfully on the central integration PR with all six jobs green:
- Atlas native tests/build and Forge JAR build.
- WorldForge Core Forge build.
- WorldForge logic tests.
- WorldForge native tests/build.
- WorldForge addons build.
- MAQAN Platform shell build.

The WorldForge logic test job reported **31 passed, 8 skipped, 0 failed**. Skipped tests included ArmyTest, CourtTest, LedgerTest, MarriageTest, PeopleTest, PlayerTest, RealmEconomyTest and WarTest because the test command did not receive the required datapack fixture. Several of the most relevant full-domain tests therefore were not executed in that run.

A newer run for the audit-document commit had the first five jobs green while WorldForge Addons was still running at the time of the audit. CI success verifies compilation and the tests actually run; it does not verify Minecraft runtime gameplay.

## Overall status by requested experience

| Experience | Status | Reason |
|---|---|---|
| Player race and origin | Partial | Genesis/Origins profile exists, but no canonical Character link |
| NPC race identity | Partial | Mostly realm-derived metadata, not Character-owned race |
| Aging and life stages | Partial | Character API and clock-based age exist; world-wide runtime coverage not proven |
| Needs and daily life | Partial | LifeSimulation runs for settlement workers; several actions are status-only |
| Professions and skills | Partial | NPC/worker/soldier bridges exist; player profile remains separate |
| Marriage and family | Partial | Court marriage and family references exist; player family and autonomous birth not proven |
| Child growth and inheritance | Missing as an end-to-end loop | No player Character and no three-generation acceptance test |
| Settlement management RTS | Partial / substantial | Real settlement simulation, worker commands, maps, buildings and logistics |
| First-person construction | Partial | Vanilla building works; special blueprint wand is operator-gated; player BuildControl exists |
| Army strategy | Partial / substantial | ArmyEngine with wars, movement, supplies, battles and sieges |
| Tactical RTS control | Partial / substantial | Selection, groups, squads, formations and physical troop commands |
| Civilian ↔ soldier transition | Partial / promising | Same Character can be detached and reassigned; atomic world acceptance not proven |
| Unified world clock | Partial | Several local clocks/accumulators remain |
| One persistent save/identity model | Partial | Multiple versioned stores and per-map registries |
| Seamless play across all lenses | Not verified | No integrated Minecraft scenario test |

## Recommended implementation order after this audit

1. **World Character unification:** introduce WorldId, canonical CharacterId, player Character creation at Genesis, canonical race/culture/ancestry and migration from per-map registries.
2. **Role and profession authority:** eliminate duplicate worker person/profession identity; make worker, soldier, hero, spouse, child, ruler and commander assignments refer to CharacterId.
3. **Life execution:** wire one LOD-aware LifeSimulation scheduler to settlement, household, court, travel, army and remote NPC contexts; successful actions require authoritative receipts.
4. **Family lifecycle:** player/NPC marriage, birth, childhood, education, aging, death and succession on the same Character registry.
5. **Unified construction/settlement:** first-person and RTS build commands share one validated construction service, material accounting and world block execution.
6. **Unified army roster:** reconcile strategic Army, physical TroopEntity, Squad and Character rosters transactionally; one canonical economy and commander identity.
7. **One clock and durable world transactions:** derive subsystem time from GameClock; persist/replay action receipts and reconcile cross-file crash windows.
8. **Automated vertical acceptance:** add a deterministic end-to-end simulation test with fixture data and a Forge in-game test world. The test must create a player, choose race, found a settlement, hire workers, build a structure, form a family, recruit the same Character as soldier, command a squad, demobilize, return to family, age/death/succession, save/restart and verify every ID and resource balance.

## Release gate

Do not declare Unified Living World complete until the complete acceptance scenario passes in both deterministic domain tests and a running Forge integration environment. Do not substitute screen presence, separate unit tests or green compilation for the scenario.


## CI follow-up: deterministic fixture and zero skipped logic tests

The previous audit reported eight tests skipped because they expected a map archive at argv[0]. That was a test-harness gap, not a reason to delete those tests.

The harness now generates a deterministic synthetic `.worldmap` fixture at runtime:
- 64x64 grayscale terrain and biome maps;
- 32 baronies grouped into 16 counties and 8 independent realms;
- adjacent realms and valid title hierarchy for war/army/court scenarios;
- the same resource path expected by the existing tests: `data/planets/worldforge_maps/game_of_thrones/`.

`tools/test-all.sh` now always supplies this fixture to every test class. Missing fixture input is no longer interpreted as a successful skip. An explicitly supplied archive must exist or the test job fails immediately. The runner reports `skipped=0` and fails if any test fails.

The fixture is synthetic and test-only; it does not replace or modify production maps. Existing map-specific tests remain intact and continue to exercise their actual domain logic against deterministic input.

CI installs Python 3.12 in the WorldForge logic-test job because the fixture generator uses only the Python standard library. No additional workflow was added; this remains inside the single `universeforge.yml` quality gate.

**Verification status:** changes are committed to `integration/maqan-unified`; the corresponding GitHub Actions run must finish before this is considered green. The repository integration tools do not provide a local Windows/Gradle execution environment, so no claim of local test execution is made here.

## Next-build implementation update — 2026-11-09

The central integration line now includes a first-person command layer that shares the
existing RTS order packet and server-side troop controller:

- Hold **Alt** while first-person command mode (**G**) is active to open the radial order wheel.
- The wheel exposes move, attack-move, attack target, hold, stop, patrol, follow, guard and retreat.
- Shift while confirming queues the selected order through the existing squad order queue.
- Retreat is resolved server-side from nearby enemy positions; without nearby enemies the squad falls back toward its owner instead of moving to world origin.
- The existing RTS screen continues to provide box selection, control groups 1–9, formations, stances, patrol, worker selection and construction controls.

This is an implementation checkpoint, not a claim that the whole sandbox loop is complete.
Resident conversation/quest commands, unified Character-backed task execution, end-to-end
worker job chains, and in-game multiplayer/save-reload acceptance remain open.

The deterministic CI fixture now has a connected barony adjacency graph. Recent CI failures
also exposed a null-liege economy report edge case and over-sized court client snapshots;
both have been corrected in the integration line. Final acceptance depends on the current
single MAQAN UniverseForge Windows quality-gate run.

## Resident + army command vertical slice — 2026-11-09

Implemented on the same central integration branch:

### RTS and first-person control
- RTS now has Ctrl+A to select all controllable bodies in the current dimension and Ctrl+Shift+A to select the current troop army/squad or the current settlement's residents.
- Existing click/box selection, Shift add/remove, double-click type selection, groups 1–9, formations, stances, mixed worker/troop movement and construction remain the same selection/order path.
- Right-clicking a resident body in RTS opens the same resident conversation used by first-person interaction.
- First-person G mode keeps LMB selection / Shift+LMB multi-selection; holding Alt opens the radial wheel. Follow is now a distinct mixed-order action for residents rather than a move to (0,0); military retreat maps to civilian stop, never a false march to origin.
- Resident follow stores the issuing player's UUID, survives settlement save/reload, and the physical worker body executes the follow target while the owner is online.

### Resident conversation and social loop
- Interacting with a resident opens a contextual clickable conversation menu.
- The same menu can be opened from RTS by right-clicking a resident.
- Available actions: talk, inspect status/family/age/relationship, gift an emerald to improve the persistent Character relationship, follow, stop, and return to a compatible job/building.
- Builder residents can resume a building under construction; other professions are assigned only to compatible completed job slots.
- Resident contracts are stored on the canonical Character properties: offer, accept, deliver bread, track partial progress, complete and reward the settlement treasury.
- Delivery is committed against settlement stock and player inventory; quest progress changes only after the stock accepts the goods.
- Quest and relationship services have pure logic tests; settlement save tests cover the resident follow target and player UUID.

### Acceptance boundary
This is a connected gameplay slice, not a claim that all family/social simulation or every worker profession is complete. Conversation is currently a contextual in-game chat menu rather than a dedicated cinematic dialogue screen. Full multiplayer runtime playtest, loaded-chunk navigation, save/restart verification, and the latest Windows CI run are still required before calling this feature set accepted.

## Этап XIII — Resident Economy, Life Simulation, Army Hierarchy и Dialogue Workspace (2026-11-09)

Реализация внесена в единую ветку `integration/maqan-unified`.

### Исполняемая экономика жителей
- Разделены постоянная профессия Character и временная назначенная работа Worker. Автоматическое распределение и экстренное назначение строителей больше не переписывают профессию жителя.
- Общий рабочий (`laborer`) может выполнять любую совместимую работу с базовой производительностью; он не получает профессиональные ранги даже при прямом вызове skill-training service.
- Профильные роли ограничены возможностями профессии: лесоруб — лесозаготовка, строитель — строительство, шахтёр — добыча, фермер — сельское хозяйство, носильщик — логистика. Профильные бонусы производительности применяются отдельно от числа рабочих мест.
- Добавлена роль носильщика. Производственный цикл теперь физически проходит через локальный буфер здания, назначенного носильщика, общий склад и последующее потребление материалов производством/строительством.
- Добавлен сохраняемый ResidentTaskGraph с причинно связанными receipts: EXTRACTED → HAULED → STORED → PROCESSED → PRODUCED → HAULED → STORED → CONSTRUCTION → BUILT. Ресурсы не зачисляются на общий склад, пока доставка не принята складской службой.
- Назначение носильщика возможно автоматически при наличии локального груза или вручную через Settlement API. Если носильщика нет, локальный буфер заполняется и производство останавливается вместо создания ресурсов в общем складе из воздуха.

### Единая жизнь жителей
- AI-действия теперь переводят жителя в реальные временные состояния с координатной целью: сон у ближайшего готового дома, приём пищи у пищевого/общественного здания, социальный визит к соседу, бегство в укрытие.
- Во время сна, еды, общения, бегства или защиты житель временно не производит и не строит; его постоянная профессия и назначение сохраняются и восстанавливаются после действия.
- Солдаты и жители с чертами brave/courageous/protective направляются защищать поселение при набеге; остальные гражданские ищут укрытие.
- Социальные встречи обновляют отношения взаимно. Черты характера и культура влияют на исход: доброжелательность повышает расположение, агрессивность/жестокость и культурное различие могут формировать соперничество и вражду.
- Добавлен годовой детерминированный семейный проход: взаимная высокая привязанность может привести к браку; супруги могут иметь детей через тот же CharacterRegistry с двусторонними ссылками родителей и сохранением.
- Диалоговый профиль показывает пол, культуру, семью, черты, навыки, отношения и социальные связи.

### Командование армией
- Добавлена сохраняемая иерархия Army → Corps → Squads с назначением отрядов в корпуса и командующих через canonical Character ID.
- Иерархия сверяется с физическими TroopEntity и стратегическим wf-army roster; смешанные отряды из разных стратегических армий не связываются ошибочно.
- Команды уровня армии и корпуса передаются всем подчинённым RTS-отрядам через существующий Squad order queue.
- Добавлены команды управления: `/wftroops corps create`, `/wftroops corps assign`, `/wftroops commander army`, `/wftroops commander corps`, `/wftroops order army`, `/wftroops corps order`, `/wftroops hierarchy`.
- Назначения корпуса не перезаписываются периодической синхронизацией.

### Диалоги
- Контекстное меню чата заменено отдельным контейнером и клиентским ResidentDialogueScreen.
- В диалоге есть ветви «о семье», «о работе», «о мире» и «мнение о вас», а также действия разговора, следования, остановки, возвращения к работе, подарка и поручения.
- RTS и первое лицо открывают один и тот же экран и используют UUID физического жителя для серверных действий; Character ID остаётся канонической идентичностью, а entity UUID — только адресом текущего тела.

### Добавленные/расширенные тесты
- `SettleTest`: специализация, бонус производительности, локальный буфер, доставка носильщиком, сохранение логистики, сон и защита.
- `ResidentTaskGraphTest`: происхождение ресурса, связанные receipts, обработка, строительство и round-trip save.
- `PopulationSimulationTest`: общий рабочий не может получать профессиональные skill ranks.
- `ResidentSocialTest` и `ResidentLifeEventsTest`: взаимные отношения, характер, культура, дружба/вражда, брак, дети и CharacterRegistry save.
- `ArmyHierarchyTest`: Army/Corps/Squad membership, назначение командиров, приказы, изоляция владельцев и сохранение.

### Граница приёмки
- Исходники и тесты добавлены в PR #16; это ещё не подтверждение зелёной сборки. На текущем проходе GitHub Actions не вернул workflow run для последнего head SHA, поэтому компиляция Forge-клиентского меню, Brigadier-команд и полный набор логических тестов пока не подтверждены CI.
- Полный multiplayer acceptance (два клиента, чужие приказы, chunk unload/reload, сохранение/перезапуск сервера и продолжение симуляции без игрока) требует реального запуска Forge-сервера. Его нельзя честно отметить пройденным по чистым unit-тестам.
- Не заявляется полная симуляция каждого неработающего NPC за пределами поселения, полноценная брачная/семейная драма с разводами и конфликтами, либо боевое участие гражданских без военной роли. Эти границы остаются открытыми для следующего hardening-прохода.
