# Migration manifest

## Imported source trees

- Atlas source: `MAQAN-Galactic-Navigator-feature-modular-navigation-workspace`
- WorldForge source: `MAQAN-WorldForge-main`

## Destination mapping

- Atlas source root → `mods/atlas`
- WorldForge `wfmod` → `mods/worldforge/wfmod`
- WorldForge `wfaddons` → `mods/worldforge/wfaddons`

All source files, tests, data assets, documentation, native Rust sources, tools and addon-local build files were copied from the supplied archive. The source project roots were retained inside their respective mod directories rather than flattened into a shared classpath.

## Compatibility-sensitive values retained

- Atlas mod id: `maqan_galactic_navigator`
- Atlas Java package: `com.maqan.galacticnavigator`
- Atlas Forge: `1.20.1-47.4.28`
- WorldForge core/addons mod IDs and package namespaces
- WorldForge Forge: `1.20.1-47.3.0`
- Java 17 across projects
- Existing addon-local dependencies and soft-integration approach

## Deliberate changes

- Repository root and orchestration Gradle project added.
- Root CI added with separate Atlas, WorldForge core and WorldForge addon jobs.
- Atlas artifact/display branding updated to `MAQAN Atlas`; compatibility mod id remains unchanged.
- Architecture documentation and shared-contract boundaries added.

## Validation status

Source migration and static structure checks are performed locally. Gradle/Rust build validation requires installed Gradle and Cargo; the current execution environment does not provide either executable. In-game validation with Forge and Cosmic Horizons has not been performed.
