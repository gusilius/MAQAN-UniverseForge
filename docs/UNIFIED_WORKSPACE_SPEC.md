# MAQAN Unified Workspace — Source of Truth

## Product rule

MAQAN is one interface platform, not a launcher for separate Minecraft menus.
Atlas, WorldForge and future providers contribute data and actions to shared workspaces.
A provider must never replace the global workspace with its own standalone screen.

The interface is complete when no provider mods are installed. Missing providers leave
their workspace, navigation entries, panels and visual surfaces visible in a clearly
unavailable / no-data state. They must not fabricate entities, simulation values,
maps, history, routes or commands.

## Global visual language

- Minimal futuristic space-platform aesthetic; restrained dark surfaces, fine separators,
  subtle grid, cyan focus accent and readable neutral typography.
- Icons are the primary controls. Text labels belong in tooltips, compact panel headings,
  inspector values and contextual information, not large permanent button plaques.
- A slim top mode switcher and slim vertical icon rail.
- The main work surface remains visible and usable. Clicking a panel or control never
  closes or replaces the global workspace.
- Panels are compact, independently show/hide, draggable, resizable, minimizable and
  pinnable. Settings are a small floating panel, never a full-screen settings page.
- Panels may float or dock. Their geometry must remain inside the available viewport.
- The user can keep the screen sparse; no mode may force every panel open.

## Unified workspaces

All modes share the same shell, navigation, panel behavior, inspector, timeline,
command palette, settings and input conventions.

- Cosmos / Atlas: universe, galaxy, sector, star-system and body navigation.
- Politics: CK3-inspired dynasties, realms, titles, vassals, diplomacy and laws.
- Settlements: Manor Lords-inspired district, plot, road, household and production views.
- RTS: Warcraft III-inspired tactical map, unit selection, formations, orders and battle log.
- Society, economy, army, quests, origins, relations and other registered capabilities
  are subviews in the same workspace, not separate UI applications.

## Provider availability

The capability catalogue is static interface metadata and remains visible with no mods.
Runtime availability is discovered from installed provider mod IDs. A missing provider is
shown as unavailable and has no live actions. An installed provider may populate its
registered view and data. Never confuse "provider absent" with "provider installed but
currently has no data".

## Atlas spatial contract

Preserve the existing square / cuboid Cosmic Horizons planet models. Do not replace them
with spheres.

The Atlas opens at universe scale. Every spatial layer must retain navigable context and
show miniature system structures where applicable:

1. Universe: galaxy artwork and galaxy groups, each containing miniature star systems
   with visible stars, planets and orbital paths.
2. Galaxy: the selected galaxy's star systems and their miniature orbital structures.
3. Sector: sector groupings inside the selected galaxy, each with miniature systems.
   Selecting a sector reveals the systems assigned to it.
4. Star system: star, planets, moons and other catalogued objects with their real
   parent relationships, orbit paths, positions and motion.

Object identity is metadata-driven:
- Stars use explicit stellar classification / star type metadata.
- Planets use planet classification or the safe default for unclassified primary bodies.
- Moons / satellites remain distinct and orbit their resolved parent planet.
- Unknown parent relationships must not be silently assigned to an arbitrary body.
- Labels, filters, inspector fields, hover cards, colours and orbital hierarchy must agree
  on object type.

Atlas continues to own its catalogue, navigation, orbital simulation, routes, map data,
waypoints, territories and travel handoff. The Platform owns presentation and workspace
composition only. No duplicate ownership or invented spatial data.

## WorldForge screen migration

Every legacy WorldForge screen opened through Minecraft's client screen lifecycle is
intercepted by the Platform and hosted inside the matching MAQAN workspace. This includes
the core political map and addon screens for settlements, army, war, court, relations,
people, trade, quests, dialogue, origins, progress, forms, troops, orders and genesis.
Provider screens temporarily remain as compatibility views/controllers so existing input
and command semantics continue to work; they no longer replace the global MAQAN shell.

This interception is a migration bridge, not the final visual replacement. Each domain
must progressively move its data presentation and controls into shared MAQAN panel
components. The final state removes legacy full-screen layouts while retaining provider
logic, network messages, permissions, persistence and simulation. Do not delete provider
screens or domain code until an equivalent shared view adapter is implemented and tested.

## Implementation status discipline

A successful Gradle compile is not proof that the game launches or that every interaction
works. A release candidate requires:
- Windows x64 Platform, Atlas Java and Atlas Rust builds.
- WorldForge core, all addons and native build/tests.
- Artifact inspection (JAR contents, native library resource path, mod metadata).
- Launch verification in the target Forge instance with and without optional providers.
- Smoke tests for mode switching, panel drag/resize, settings, tooltips, Atlas layer
  navigation, body selection and parent/orbit rendering.

Do not publish or recommend a raw preview JAR as a finished release.
