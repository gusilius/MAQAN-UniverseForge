# WorldForge Gameplay and Management Surfaces

WorldForge is the living-world and civilization simulation mod family. Its UI must expose the depth of a connected simulation, taking design inspiration from Manor Lords, Crusader Kings III and Mount & Blade II: Bannerlord without copying their proprietary layouts or assets.

## 1. Geographic hierarchy

World → continent → region → territory/province → realm/domain → settlement → district/quarter → plot/building.

Not every addon or world format will provide every level. The hierarchy is capability- and data-driven. The map must show the actual supported granularity and preserve source identity.

### Map layers
- political borders, claims, realm and vassal boundaries;
- terrain, elevation, rivers, forests, roads and navigable routes;
- settlement footprints, districts, plots and buildings;
- population density, culture, religion and species/race distribution;
- ownership, influence, control, unrest and devastation;
- resources, fertility, production, trade and supply;
- armies, parties, patrols, sieges, battles and movement routes;
- events, discoveries, quests and points of interest.

Each layer needs legend, visibility, opacity, filters and a clear data source.

## 2. Realm / state inspector

Suggested tabs:
Overview · Ruler · Dynasty · Titles · Domains · Vassals · Diplomacy · Laws · Economy · Military · Culture · Faith · Claims · History.

Show government type, ruler, succession, legitimacy, stability, treasury, income/expenses, domain holdings, vassal obligations, alliances, wars, claims, laws, cultural/religious composition and recent events. Actions depend on permissions, game rules and available addon capabilities.

## 3. Dynasty and character inspector

Overview · Family · Relationships · Titles · Skills · Traits · Culture · Faith · Health/age (only when modeled) · Allegiance · Activities · History.

Include lineage/family tree, succession position, marriages, heirs, guardianship, relationships, court role, ambitions, traits and skills. Avoid displaying health, fertility, age or other simulation attributes unless the underlying module actually models them.

## 4. Settlement inspector

Overview · Population · Families · Districts · Buildings · Workforce · Production · Resources · Trade · Needs · Logistics · Defense · Events · History.

Population views should distinguish households, individuals, professions, workers, dependents, migration and demographic changes where supported. Economy views connect input resources, recipes, labor, storage, transport, output, demand and market prices. Seasonal agriculture includes land quality, crop rotation, planting/harvest windows, weather impact and food reserves only where simulation systems supply these values.

## 5. Building and production inspector

Building · Workforce · Inputs · Outputs · Storage · Upkeep · Access · Upgrade · Ownership · History.

Display production chains as inspectable graphs. Show bottlenecks, missing inputs, labor shortages, transport delays and capacity. Never claim production is active merely because a building exists.

## 6. Army and party inspector

Overview · Troops · Commanders · Organization · Equipment · Morale · Food/Supply · Movement · Orders · Battles · History.

Campaign map units are entities with position, route, movement speed, status and command authority. Composition includes troop types, strength, wounded/missing only if modeled, equipment, experience and cohesion. Supply should connect to actual inventories and logistics networks. Siege views include besieger/defender strength, fortifications, siege progress, supply, breaches and relief forces when implemented.

## 7. Diplomacy and war room

Relations · Treaties · Alliances · Vassalage · Claims · Negotiations · Wars · Objectives · Truces · War history.

War is a connected state, not a decorative banner: it links participants, causes, objectives, fronts, armies, sieges, costs, territorial control, peace terms and historical outcomes. Negotiation options must be validated against game rules and permissions.

## 8. Economy and logistics

Treasury, taxation, wages, upkeep, trade routes, markets, storage, production chains, resource flows, shortages, demand and prices. Separate stock, flow, capacity and forecast. Forecasts must disclose assumptions and uncertainty.

## 9. Events, quests and history

Chronicle · Events · Decisions · Quests · Discoveries · Notifications. Events link to affected characters, settlements, armies, realms and territories. Historical records should be queryable and filterable.

## 10. Addon capability model

WorldForge core defines entity identity, world ownership, event vocabulary and UI contribution contracts. Addons contribute their own domain panels and actions:
- wf-people / wf-court / wf-origins: characters, families, courts, origins;
- wf-realms / wf-relations / wf-reputation: realms, diplomacy, reputation;
- wf-settlements / wf-forms: settlements, forms and structures;
- wf-trade / wf-progress: economy, trade and progression;
- wf-army / wf-troops / wf-war / wf-rts: military organization, troops, war and tactical control;
- wf-quests / wf-dialogue / wf-orders: quests, dialogue, decisions and orders;
- wf-genesis: world creation and foundational simulation.

This list is a mapping guide, not an assertion that every feature is already implemented. During integration, inspect each addon’s actual code and expose only capabilities it provides.

## 11. Cross-system causality

Examples of required relationships:
- war changes military orders, supply demand, treasury, casualties, control and settlement conditions;
- harvest changes food stock, market supply, prices, tax base and migration pressure;
- succession changes ruler, titles, vassal relations, diplomacy and potential conflicts;
- road/route disruption changes trade delivery, army movement and settlement availability;
- population change affects labor, production, recruitment and consumption.

The UI should navigate these causal links and show provenance, timestamps and responsible systems.

## 12. Validation

Every displayed number and action must map to a real model/API. Missing addon means its panels are absent or explicitly unavailable. No fake data, placeholder buttons or fabricated outcomes.
