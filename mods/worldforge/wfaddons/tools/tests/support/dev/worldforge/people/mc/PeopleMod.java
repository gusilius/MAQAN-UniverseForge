package dev.worldforge.people.mc;

import dev.worldforge.people.CharacterAuthority;

/**
 * Lightweight-harness seam for the Forge-bound PeopleMod adapter.
 * Production Gradle builds compile the real adapter; pure domain tests run
 * without Minecraft/Forge and intentionally have no live server authority.
 */
public final class PeopleMod {
    private PeopleMod() {}
    public static CharacterAuthority authority() { return null; }
}
