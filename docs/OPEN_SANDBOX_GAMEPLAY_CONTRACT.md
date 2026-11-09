# MAQAN UniverseForge — Open Sandbox Gameplay Contract

Status: mandatory gameplay-direction contract for Stage XIII and every later stage.

## 1. Product vision: an open living-world sandbox

MAQAN is not a collection of mutually exclusive game modes and must not force the player to choose one identity such as RTS commander, colony manager, adventurer, ruler, or ordinary citizen.

It is one persistent, systemic world in which all of these forms of play coexist. The player may move between them at any time, combine them, ignore them, or invent a different way to play. The simulation must not impose a fixed campaign path, mandatory profession, faction, settlement type, victory condition, or prescribed sequence of progression.

The examples below are references for useful mechanics and interaction patterns, not templates to copy and not limits on scope:
- Hundred Years Warfare: NPC combat/production and multiple control methods including RTS.
- Reign of Nether: RTS camera and command experience, factions with distinct mechanics, match variants and a free-building sandbox.
- MineFortress: village leadership, resource gathering, custom blueprints, hiring specialists, expansion and warfare.
- MCA Reborn: individual villager relationships, personality/mood, marriage, children, growth and independent lives.
- MineColonies: player-shaped settlements, specialist workers, buildings, logistics, defense and raids.

MAQAN must unify and surpass the *combined design space* represented by these examples, rather than reproduce their separate mode boundaries.

## 2. No either/or gameplay

The following are simultaneous capabilities, not exclusive modes or character classes:
- First-person / third-person life simulation and direct interaction.
- Adventuring, exploration, survival, crafting, professions and trade.
- Character relationships, family, children, aging, inheritance and personal stories.
- Settlement founding, organic building, zoning, roads, production, storage and logistics.
- Direct worker, specialist, companion and army command.
- Tactical combat, formations, sieges, battles and strategic warfare.
- Realm management, law, diplomacy, politics, titles, dynasties and succession.
- Exploration and management at regional, planetary, interplanetary and larger scales where supported by the world.
- Creative construction, experimentation, custom scenarios and unrestricted sandbox play.

A player can personally mine resources, assign workers to mine elsewhere, build a settlement, leave it to adventure, return to manage a war, negotiate as a ruler, and later hand authority to an heir. These actions affect the same world state. They are not separate save slots or isolated minigames.

## 3. Control is a lens over the same simulation

Camera and command interfaces may change how the player observes and controls the world, but must not switch to a separate simulation:
- Immersive view: control the player character directly.
- Tactical view: select and command nearby or owned units.
- Settlement view: inspect and manage buildings, workforce, production and logistics.
- Strategic view: manage armies, territories, diplomacy and realm-level decisions.
- Atlas view: explore and interact with geographic/spatial information.
- Creative/sandbox tools: place, inspect, configure and experiment where permissions allow.

The player can open these views contextually and return without losing state, changing the underlying rules, or entering a separate match. Ownership, permissions, command range and costs are world rules; they are not arbitrary mode locks.

## 4. One world, continuous simulation

All views and systems operate on shared authoritative entities and state:
- One Character identity with attachable roles, relationships, professions, titles, memberships and lifecycle.
- One world calendar and simulation timeline.
- One authoritative resource, inventory, ownership and economy model.
- One task/action execution contract for player commands and autonomous AI.
- One canonical spatial identity and geography service.
- One persistent world state used by personal, settlement, military and political systems.

The player and NPCs use the same underlying rules wherever practical. NPCs can pursue their own goals, form relationships, change professions, migrate, establish settlements, trade, fight, negotiate, rise to power and leave legacies without requiring the player to activate a special game mode.

## 5. Freedom without brittle restrictions

The architecture must support new mechanics, mods, world types, factions, cultures, professions, building sets, combat styles, control schemes and simulation layers without requiring a redesign of the core game loop.

Requirements:
- Prefer data-driven definitions, stable extension points and versioned provider APIs.
- Modules contribute capabilities to the shared world; they do not create competing worlds, duplicate identities or isolated economies.
- Optional modules can be absent without breaking the core simulation.
- Scenarios, quests, campaigns, challenges and victory conditions are optional content layers. They may guide play but must never define the only valid way to play.
- Player-created rules, maps, factions, settlements and scenarios should be supported where technically and safely possible.
- Permissions, multiplayer authority, simulation budgets and server rules may constrain what a player is allowed to commit, but must not hard-code a narrow list of acceptable playstyles.

Canonical data ownership, transaction validation, persistence and performance controls are engineering guarantees. They are not restrictions on player creativity.

## 6. Scale continuity

The design must not assume the game is only a village RTS or only an individual RPG. The same world model should support meaningful play at multiple scales:
- Individual: needs, inventory, relationships, skills, combat, daily life.
- Household/community: family, home, work, social networks.
- Settlement: plots, buildings, workers, production, storage, roads and defense.
- Region/realm: borders, laws, diplomacy, armies, wars and dynasties.
- World/planet and beyond: geography, travel, factions, resources and large-scale simulation.

Not every detail must be simulated at full fidelity everywhere at all times. Use explicit simulation levels, deterministic/offline progression and load boundaries without creating contradictory world states or forcing all locations to remain actively loaded.

## 7. Acceptance scenarios

A release is not compliant merely because it has separate RTS, settlement, NPC or RPG screens. Verify that these experiences share the same world and can be mixed freely.

Required end-to-end scenarios:
1. A player starts with no prescribed profession, joins a community, changes profession, leaves, returns and sees the same persistent people and economy.
2. The player builds a home or settlement personally, then delegates construction and production to NPCs, while remaining free to adventure.
3. The player switches from direct character control to tactical unit selection, issues orders, returns to direct control and observes committed outcomes.
4. A settlement evolves through autonomous production, logistics, family growth, migration, threats and recovery while the player is elsewhere.
5. A player can become a ruler through world events and choices, but can also refuse titles, abandon power, become a wanderer or create a different path.
6. Family, inheritance and political succession continue through death or retirement without resetting the world.
7. A player-created sandbox scenario can disable quests or victory conditions and still use the same simulation systems.
8. Adding or removing an optional gameplay provider does not duplicate characters, balances, calendars, maps or task execution.

## 8. Non-goals and anti-patterns

Do not:
- Build separate mutually exclusive "RTS mode", "colony mode", "RPG mode" and "life mode" simulations.
- Force a player into a faction, class, profession, campaign, quest chain or win condition as the only route through the game.
- Treat workers, soldiers, heroes, villagers and rulers as separate kinds of person with disconnected identities.
- Make RTS orders, AI plans or animations count as completed work without authoritative execution.
- Create a parallel economy, calendar, inventory or geography for each gameplay mode.
- Confuse a large number of menu entries with systemic freedom.
- Claim open-world sandbox support based only on a free-build screen.

The goal is not simply to add every mode. The goal is to make every system participate in one coherent, persistent, extensible world where the player decides what kind of life and story to create.
