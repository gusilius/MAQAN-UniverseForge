package dev.worldforge.mod.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Canonical, transport-independent player command envelope.
 *
 * RTS screens, first-person input, radial menus and dialogue adapters should
 * translate their input into this value before handing it to a domain owner.
 * This class deliberately does not execute game logic or grant authority.
 */
public record CommandIntent(
        String requestId,
        String actorId,
        Source source,
        Domain domain,
        String action,
        List<String> subjectIds,
        String targetId,
        double x,
        double z,
        boolean queued,
        List<String> arguments) {

    public enum Source { RTS, FIRST_PERSON, RADIAL, DIALOGUE, SYSTEM }
    public enum Domain { MILITARY, SETTLEMENT, CONSTRUCTION, MIXED, QUERY }

    public static final int MAX_SUBJECTS = 200;
    public static final int MAX_ARGUMENTS = 48;
    public static final int MAX_ARGUMENT_LENGTH = 1024;
    private static final int IDS_PER_ARGUMENT = 20;

    public CommandIntent {
        if (requestId == null || requestId.isBlank() || requestId.length() > 96)
            throw new IllegalArgumentException("Invalid command request id");
        if (actorId == null || actorId.isBlank() || actorId.length() > 128)
            throw new IllegalArgumentException("Invalid command actor");
        if (source == null || domain == null)
            throw new IllegalArgumentException("Command source and domain are required");
        if (action == null || !action.matches("[a-z0-9_.:-]{1,48}"))
            throw new IllegalArgumentException("Invalid command action");
        if (targetId == null || targetId.length() > 256)
            throw new IllegalArgumentException("Invalid command target");
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000)
            throw new IllegalArgumentException("Invalid command coordinates");

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (subjectIds != null) {
            if (subjectIds.size() > MAX_SUBJECTS)
                throw new IllegalArgumentException("Too many command subjects");
            for (String id : subjectIds) {
                if (id == null || id.isBlank() || id.length() > 40)
                    throw new IllegalArgumentException("Invalid command subject id");
                try { UUID.fromString(id); }
                catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Subject id must be a UUID", ex); }
                unique.add(id);
            }
        }
        subjectIds = List.copyOf(unique);
        arguments = List.copyOf(arguments == null ? List.of() : arguments);
        if (arguments.size() > MAX_ARGUMENTS
                || arguments.stream().anyMatch(a -> a == null || a.length() > MAX_ARGUMENT_LENGTH))
            throw new IllegalArgumentException("Invalid command arguments");
    }

    /** Stable, bounded payload for the shared durable action journal. */
    public ActionExecutionService.Request toActionRequest(long worldMinute) {
        List<String> args = new ArrayList<>();
        args.add("source=" + source.name().toLowerCase(java.util.Locale.ROOT));
        args.add("domain=" + domain.name().toLowerCase(java.util.Locale.ROOT));
        args.add("target=" + targetId);
        args.add("x=" + Double.toHexString(x));
        args.add("z=" + Double.toHexString(z));
        args.add("queued=" + queued);
        args.addAll(arguments);
        for (int start = 0; start < subjectIds.size(); start += IDS_PER_ARGUMENT) {
            int end = Math.min(subjectIds.size(), start + IDS_PER_ARGUMENT);
            args.add("subjects=" + String.join(",", subjectIds.subList(start, end)));
        }
        if (args.size() > ActionExecutionService.Request.class.getRecordComponents().length * 0 + 64)
            throw new IllegalArgumentException("Canonical command payload exceeds journal limits");
        String durableId = "cmd:" + UUID.nameUUIDFromBytes(
                (actorId + ":" + requestId).getBytes(StandardCharsets.UTF_8));
        return new ActionExecutionService.Request(durableId, "world.command", actorId,
                domain.name().toLowerCase(java.util.Locale.ROOT), args, worldMinute);
    }
}
