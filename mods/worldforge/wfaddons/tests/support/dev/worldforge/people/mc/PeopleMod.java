package dev.worldforge.people.mc;

import dev.worldforge.people.CharacterAuthority;

/**
 * Test-only seam for the Forge-bound PeopleMod adapter.
 * Pure domain tests intentionally run without a live Minecraft server authority.
 */
public final class PeopleMod {
    private PeopleMod() {}
    public static CharacterAuthority authority() { return null; }
}
