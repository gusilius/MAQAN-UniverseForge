# Character Creation, Origins and Campaign Start

This document defines the integrated character-start flow for WorldForge. It extends the existing wf-genesis, wf-origins, wf-progress, wf-forms, wf-people, wf-settlements, wf-realms, wf-quests and military addons. It is not a replacement for those modules.

## Product principle

Character creation is a campaign initialization pipeline, not a cosmetic wizard. The resulting character, origin, social status, starting location, profession, attributes, class, abilities and leadership role must be persisted as actual game state and used by simulation systems.

The future cosmic-origin module is deliberately left as an extension point. The first implementation targets supported WorldForge worlds and settlement locations; it must not invent a space system or create a hard dependency on Atlas.

## Guided flow

1. Campaign/world selection and rules.
2. Race and subrace.
3. Body and appearance.
4. Innate traits, acquired traits and forms.
5. Origin, culture, heritage and biography.
6. Social background, family and starting status.
7. Starting location and campaign scenario.
8. Optional leadership start and settlement founding/setup.
9. Starting professions and specializations.
10. Attribute allocation.
11. Class, subclass and progression path.
12. Starting abilities, talents and equipment.
13. Review, validation and world initialization.

The flow supports back/forward navigation, autosave, validation, summary and preset templates. Choices that invalidate later choices must explain why and offer compatible alternatives; they must never silently erase player selections.

## Race, subrace and body

Race selection must read from wf-origins content definitions. Race and subrace can contribute:
- body model/renderer profile and animation set;
- body proportions and supported morph ranges;
- skin, fur, scales, feathers, markings and color palettes;
- ears, horns, tusks, tails, wings and other anatomy slots;
- senses, movement modes, environmental tolerances and innate abilities;
- lifespan/aging rules, only where modeled;
- culture, language and social modifiers, without treating culture as biologically fixed.

The 3D preview is a live player-model preview with orbit, zoom, lighting presets and animation preview. Appearance editing includes body type, stature, proportions, face shape, brow, eyes, iris, nose, mouth, jaw, ears, hair, facial hair, skin/material tone, markings, scars, tattoos, accessories, voice profile and outfit. Each race declares supported slots and constraints. No human-only assumptions in the editor.

Race grants and appearance are separate: changing appearance must not alter gameplay stats. Race changes after creation are restricted by campaign rules and forms/transformations.

## Traits and forms

Keep separate categories:
- innate racial/subracial traits;
- inherited lineage traits;
- personality and temperament;
- upbringing and education;
- background and life events;
- acquired skills/advantages/disadvantages;
- supernatural blessings, curses and transformations from wf-forms;
- temporary effects.

Traits declare tags, modifiers, requirements, conflicts, visibility and provenance. Avoid stacking the same modifier twice through race + trait + form. Traits must be data-driven and extensible by addons/datapacks.

## Origin, culture and biography

Origin describes where and how the character was formed socially and historically: culture, homeland, family, class/caste, education, former occupation, faith, allegiance, migration and notable life events. Origin is not a synonym for race.

Origin choices affect starting contacts, reputation, languages, knowledge, legal status, available professions, claims, debts and faction relations only when corresponding systems exist. Store the selected origin as structured data and a readable biography.

## Starting scenarios and location

Starting scenario examples:
- resident of a village or rural settlement;
- resident of a town or major city;
- capital/court resident;
- noble, heir, courtier or appointed official;
- mercenary, caravan member or adventurer;
- outlaw/bandit start;
- isolated wilderness start;
- refugee, exile or migrant;
- custom/random valid start.

Location selection must filter by race compatibility, world rules, scenario, faction access, population state and actual generated/registered settlement data. Random start is seeded and explainable; it selects a valid location rather than arbitrary coordinates.

## Ruler / settlement-founder start

A ruler start is a campaign role with a bootstrap transaction, not just a title label. The player chooses a supported governance scenario and, where permitted:
- settlement type appropriate to race, culture and terrain;
- name, banner/emblem and colors;
- initial population/households and social composition;
- buildings and development stage;
- treasury, resources, food and starting stock;
- workforce, professions and production priorities;
- laws, taxes, policies and local authority;
- militia/guards, garrison and defense posture;
- relations, obligations, claims and nearby powers.

The initialization must create consistent entities in wf-settlements, wf-realms, wf-people, wf-trade, wf-army/wf-war and the relevant ledgers. No free resources or population may appear without a scenario budget/rule. A leader immediately receives the ruler/settlement management workspace, with permissions scoped to their actual authority. A village chief must not receive an empire's controls.

## Professions

Professions represent current or starting work and economic/social role: farmer, forester, miner, smith, builder, trader, healer, scholar, scout, guard, soldier, administrator, artisan and addon-defined professions. Professions may have specializations and prerequisites. They affect work eligibility, recipes, productivity, wages and social standing through the owning systems.

Profession is not the same as class: a character may be a scholar by profession and a mage by class, or a blacksmith by profession and a warrior by class.

## Attributes

Use a broad, extensible attribute registry rather than a fixed small RPG stat list. Initial categories:

### Physical
Strength, constitution, endurance, agility, dexterity, coordination, reflexes, balance, speed, resilience, pain tolerance, perception, senses.

### Mental
Intelligence, memory, learning, focus, reasoning, creativity, willpower, discipline, awareness, intuition.

### Social
Charisma, empathy, persuasion, deception, intimidation, leadership presence, diplomacy, reputation capacity.

### Political and administrative
Authority, legitimacy, statecraft, governance, law, stewardship, intrigue, negotiation, command, logistics, organization.

### Practical and survival
Craftsmanship, engineering, agriculture, medicine, tracking, hunting, survival, navigation, animal handling, trade, appraisal.

### Martial
Melee, ranged, defense, tactics, strategy, formation command, siegecraft, horsemanship, weapon familiarity.

### Arcane / spiritual
Magic aptitude, control, knowledge, resistance, faith, ritual, attunement. These appear only when the world/content provides them.

Attributes need definitions, ranges, derivation rules, soft caps, modifiers, UI explanations and owning source. Distinguish base attributes, derived values, temporary modifiers and skills. Do not use one attribute as a duplicate of another system's skill.

Point allocation uses campaign-defined budgets, min/max rules, race/background modifiers and clear validation. The system must show the consequences and derived-stat preview before confirmation.

## Classes, subclasses and abilities

Classes define a progression identity and ability access; subclasses specialize it. They are not professions and should not hard-lock a character into one economic job. Support:
- class prerequisites and mutually exclusive paths;
- subclass branches and multi-stage advancement;
- ability trees with prerequisite edges;
- active, passive, toggle and contextual abilities;
- ability ranks, costs, cooldowns and resource requirements;
- respec rules controlled by campaign settings;
- addon-provided classes and abilities.

Use wf-origins for class content definitions where already implemented and wf-progress for levels, attributes, skills and perk progression. Preserve existing class catalog and branching data; do not replace it with a smaller new taxonomy.

## Final validation and initialization

Before campaign start validate:
- all required selections and point budgets;
- race/model/appearance slot compatibility;
- origin and scenario prerequisites;
- location validity and spawn safety;
- leadership authority and bootstrap budget;
- profession/class/ability prerequisites;
- conflicts between traits, forms and selected content;
- addon availability and optional dependency state.

Initialization must be atomic or recoverable. Generate the player entity, character profile, inventory/equipment, relationships, reputation, spawn location and any ruler/settlement state as one campaign-start operation. If a required addon is missing, disable only the affected scenario/capability with a clear explanation.

## UI workspaces

The wizard uses a dedicated creation workspace with a persistent 3D character preview, left navigation/step list, contextual right inspector and a bottom summary/continue area. Detailed editors open as floating windows. After start:
- ordinary characters get Character, Skills, Inventory, Journal and World workspaces;
- settlement leaders get an additional Realm/Settlement Management workspace and contextual command panels;
- military leaders get Army/Command workspaces only when they have command authority.

The platform shell, window manager and sidebars are shared. The domain panels are contributed by WorldForge core and addons.

## Integration boundaries

- wf-genesis orchestrates the campaign-start flow and validation.
- wf-origins owns races, subraces, origins, professions, classes and their content definitions.
- wf-progress owns attribute allocation, skills, levels and progression.
- wf-forms owns transformations, supernatural forms and related state.
- wf-people owns character identity, family, lineage and social roles.
- wf-settlements owns settlement entities, buildings, population/workforce and local management.
- wf-realms owns realm/governance structures, laws and authority.
- wf-trade owns market/trade/merchant systems.
- wf-war, wf-army and wf-troops own military and command capabilities.
- Atlas integration is optional and later: cosmic origin and interplanetary spawn are future providers, not a prerequisite for this flow.

## Acceptance criteria

- Existing race/class/profession content remains available and data-driven.
- Character preview updates immediately from appearance choices.
- Gameplay traits and cosmetic appearance cannot accidentally modify one another.
- Starting scenarios only offer valid locations and permissions.
- Ruler starts create consistent settlement/realm/economy/population state.
- Profession, class, subclass and ability are distinct concepts with explicit links.
- Attribute point allocation is validated and fully reflected in derived values.
- Missing optional addons degrade gracefully.
- The full character creation state can be saved, resumed, reviewed and reproduced from a seed.
