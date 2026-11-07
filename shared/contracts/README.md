# Shared contracts

This directory is reserved for small, versioned cross-mod contracts.

Rules:
- No Minecraft gameplay implementation belongs here.
- No WorldForge or Atlas internal classes may be moved here merely to reduce duplication.
- Contracts use stable identifiers and serialized DTOs, not direct object references.
- Either mod must continue to load when the other mod is absent.
- Integrations are discovered optionally and fail closed when a peer mod or contract version is unavailable.

Initial ownership:
- Atlas owns universe, galaxy, sector, system, star, orbit and celestial-body identity.
- WorldForge owns world regions, realms, cultures, settlements, populations, titles and political control.
- A future bridge may associate a WorldForge campaign location with an Atlas celestial-body identifier without transferring ownership of either domain.
