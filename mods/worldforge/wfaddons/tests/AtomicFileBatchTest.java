package dev.worldforge.tests;

import dev.worldforge.mod.AtomicFileBatch;
import dev.worldforge.mod.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Crash-recovery tests for the political title/marker/war multi-file checkpoint. */
public final class AtomicFileBatchTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("wf-political-batch-test");
        Path root = temp.resolve("worldforge");
        Path titles = root.resolve("maps/north/titles.json");
        Path war = root.resolve("maps/north/wf_war.json");
        Files.createDirectories(titles.getParent());
        Files.writeString(titles, "old titles", StandardCharsets.UTF_8);

        Map<Path, byte[]> writes = new LinkedHashMap<>();
        writes.put(titles, "new titles".getBytes(StandardCharsets.UTF_8));
        writes.put(war, "new war".getBytes(StandardCharsets.UTF_8));
        AtomicFileBatch.commit(root, writes);
        check(Files.readString(titles).equals("new titles")
                        && Files.readString(war).equals("new war")
                        && !Files.exists(AtomicFileBatch.manifestPath(root)),
                "complete political file batch replaces existing and new files and removes its commit manifest");

        String interruptedId = UUID.randomUUID().toString();
        Path interruptedExisting = root.resolve("maps/south/realms.json");
        Path interruptedNew = root.resolve("maps/south/provinces.png");
        Files.createDirectories(interruptedExisting.getParent());
        Files.writeString(interruptedExisting, "partially replaced realms", StandardCharsets.UTF_8);
        Files.writeString(interruptedNew, "partially created province image", StandardCharsets.UTF_8);
        Path backup = interruptedExisting.resolveSibling(
                interruptedExisting.getFileName() + ".wf-txn-" + interruptedId + ".bak");
        Path temporary = interruptedNew.resolveSibling(
                interruptedNew.getFileName() + ".wf-txn-" + interruptedId + ".tmp");
        Files.writeString(backup, "previous realms", StandardCharsets.UTF_8);
        Files.writeString(temporary, "staged province image", StandardCharsets.UTF_8);
        List<Object> interruptedEntries = List.of(
                Map.of("target", root.relativize(interruptedExisting).toString(), "existed", true),
                Map.of("target", root.relativize(interruptedNew).toString(), "existed", false));
        Files.writeString(AtomicFileBatch.manifestPath(root),
                Json.write(Map.of("format", AtomicFileBatch.MANIFEST_FORMAT,
                        "transaction", interruptedId, "entries", interruptedEntries)),
                StandardCharsets.UTF_8);
        AtomicFileBatch.recover(root);
        check(Files.readString(interruptedExisting).equals("previous realms")
                        && !Files.exists(interruptedNew)
                        && !Files.exists(AtomicFileBatch.manifestPath(root)),
                "recovery restores old files and deletes files created by an interrupted batch");

        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "must remain untouched", StandardCharsets.UTF_8);
        String maliciousId = UUID.randomUUID().toString();
        Files.writeString(AtomicFileBatch.manifestPath(root),
                Json.write(Map.of("format", AtomicFileBatch.MANIFEST_FORMAT,
                        "transaction", maliciousId,
                        "entries", List.of(Map.of("target", "../outside.txt", "existed", false)))),
                StandardCharsets.UTF_8);
        boolean traversalRejected = false;
        try { AtomicFileBatch.recover(root); }
        catch (java.io.IOException expected) { traversalRejected = true; }
        check(traversalRejected && Files.readString(outside).equals("must remain untouched")
                        && Files.exists(AtomicFileBatch.manifestPath(root)),
                "corrupt path-traversal manifest fails closed and preserves both outside file and recovery manifest");
        Files.delete(AtomicFileBatch.manifestPath(root));

        String missingBackupId = UUID.randomUUID().toString();
        Path needsBackup = root.resolve("maps/east/titles.json");
        Files.createDirectories(needsBackup.getParent());
        Files.writeString(needsBackup, "current partial data", StandardCharsets.UTF_8);
        Files.writeString(AtomicFileBatch.manifestPath(root),
                Json.write(Map.of("format", AtomicFileBatch.MANIFEST_FORMAT,
                        "transaction", missingBackupId,
                        "entries", List.of(Map.of("target", root.relativize(needsBackup).toString(), "existed", true)))),
                StandardCharsets.UTF_8);
        boolean missingBackupRejected = false;
        try { AtomicFileBatch.recover(root); }
        catch (java.io.IOException expected) { missingBackupRejected = true; }
        check(missingBackupRejected && Files.readString(needsBackup).equals("current partial data")
                        && Files.exists(AtomicFileBatch.manifestPath(root)),
                "missing rollback backup fails closed without replacing the current target or deleting the manifest");
        Files.delete(AtomicFileBatch.manifestPath(root));

        boolean outsideWriteRejected = false;
        try {
            AtomicFileBatch.commit(root, Map.of(temp.resolve("escape.txt"),
                    "bad".getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException expected) { outsideWriteRejected = true; }
        check(outsideWriteRejected, "transaction writer rejects targets outside the worldforge root");

        Path stagingRoot = temp.resolve("staging-failure");
        Path preserved = stagingRoot.resolve("north/wf_war.json");
        Path blocker = stagingRoot.resolve("blocked-parent");
        Files.createDirectories(preserved.getParent());
        Files.writeString(preserved, "previous war", StandardCharsets.UTF_8);
        Files.writeString(blocker, "parent is a file", StandardCharsets.UTF_8);
        Map<Path, byte[]> partialWrites = new LinkedHashMap<>();
        partialWrites.put(preserved, "new war".getBytes(StandardCharsets.UTF_8));
        partialWrites.put(blocker.resolve("cannot-create.json"), "invalid".getBytes(StandardCharsets.UTF_8));
        boolean stagingFailure = false;
        try { AtomicFileBatch.commit(stagingRoot, partialWrites); }
        catch (java.io.IOException expected) { stagingFailure = true; }
        boolean orphanStage = false;
        try (var stream = Files.list(preserved.getParent())) {
            orphanStage = stream.anyMatch(file -> file.getFileName().toString().contains(".wf-txn-"));
        }
        check(stagingFailure && Files.readString(preserved).equals("previous war")
                        && !Files.exists(AtomicFileBatch.manifestPath(stagingRoot)) && !orphanStage,
                "failure before manifest publication cleans every staged file and preserves the previous snapshot");

        if (failures > 0) throw new AssertionError(failures + " AtomicFileBatch tests failed");
        System.out.println("AtomicFileBatch: ALL OK");
    }
}
