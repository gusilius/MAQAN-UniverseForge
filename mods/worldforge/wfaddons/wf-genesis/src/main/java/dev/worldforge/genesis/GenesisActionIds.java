package dev.worldforge.genesis;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable request identifiers for Genesis commands. */
public final class GenesisActionIds {
    private GenesisActionIds() { }

    /** Same actor, target and draft always produce the same reset request ID, independent of tick timing. */
    public static String resetDraft(String actorId, String targetPlayerId, String draftFingerprint) {
        require(actorId, "actorId");
        require(targetPlayerId, "targetPlayerId");
        require(draftFingerprint, "draftFingerprint");
        String seed = "genesis-reset|" + actorId + "|" + targetPlayerId + "|" + draftFingerprint;
        return "genesis-reset:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** A confirmation is stable within one server session; recoveries get an explicit monotonic attempt suffix. */
    public static String confirm(String playerUuid, String sessionId, String draftFingerprint, int recoveryAttempt) {
        require(playerUuid, "playerUuid");
        require(sessionId, "sessionId");
        require(draftFingerprint, "draftFingerprint");
        if (recoveryAttempt < 0) throw new IllegalArgumentException("recoveryAttempt cannot be negative");
        String base = "genesis:" + playerUuid.replace("-", "") + ":" + sessionId
                + ":" + draftFingerprint.replace("-", "");
        return recoveryAttempt == 0 ? base : base + ":recover:" + recoveryAttempt;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
