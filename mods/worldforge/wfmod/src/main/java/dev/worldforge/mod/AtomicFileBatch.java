package dev.worldforge.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Crash-recoverable multi-file snapshot transaction for political map and campaign state.
 *
 * A manifest is written only after every new file and every previous-file backup is staged.
 * The manifest remains present while targets are replaced. On failure or the next server boot,
 * the batch restores every previous file (or deletes a file that did not previously exist).
 * Removing the manifest is the commit point; backup cleanup happens afterwards.
 */
public final class AtomicFileBatch {
    public static final String MANIFEST_FILE_NAME = ".wf-political-file-batch.json";
    public static final String MANIFEST_FORMAT = "wf-political-file-batch/1";
    private static final Object LOCK = new Object();
    private static final int MAX_FILES = 4096;

    private record Entry(Path target, Path temporary, Path backup, boolean existed) { }

    private AtomicFileBatch() { }

    public static Path manifestPath(Path root) {
        return root.toAbsolutePath().normalize().resolve(MANIFEST_FILE_NAME);
    }

    /** Commits a complete set of file contents, rolling back on IO failure. */
    public static void commit(Path root, Map<Path, byte[]> writes) throws IOException {
        synchronized (LOCK) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Files.createDirectories(normalizedRoot);
            if (Files.isSymbolicLink(normalizedRoot))
                throw new IOException("Political file batch root cannot be a symbolic link");
            recoverLocked(normalizedRoot);
            if (writes == null) throw new IllegalArgumentException("file batch is required");
            if (writes.isEmpty()) return;
            if (writes.size() > MAX_FILES) throw new IOException("Political file batch is too large");

            String transactionId = UUID.randomUUID().toString();
            Path manifest = manifestPath(normalizedRoot);
            if (Files.isSymbolicLink(manifest))
                throw new IOException("Political file batch manifest cannot be a symbolic link");
            Path manifestTemporary = manifest.resolveSibling(MANIFEST_FILE_NAME + "." + transactionId + ".tmp");
            List<Entry> entries = new ArrayList<>(writes.size());
            Set<Path> uniqueTargets = new HashSet<>();
            try {
                for (Map.Entry<Path, byte[]> write : writes.entrySet()) {
                    if (write.getValue() == null) throw new IOException("Political snapshot bytes are missing");
                    Path target = safeTarget(normalizedRoot, write.getKey());
                    if (!uniqueTargets.add(target)) throw new IOException("Duplicate political snapshot target: " + target);
                    if (Files.isSymbolicLink(target)) throw new IOException("Symbolic-link snapshot target is not allowed: " + target);
                    Files.createDirectories(target.getParent());
                    boolean existed = Files.isRegularFile(target);
                    if (Files.exists(target) && !existed)
                        throw new IOException("Snapshot target is not a regular file: " + target);

                    Path temporary = sibling(target, transactionId, "tmp");
                    Path backup = sibling(target, transactionId, "bak");
                    if (Files.isSymbolicLink(temporary) || Files.isSymbolicLink(backup))
                        throw new IOException("Symbolic-link staging path is not allowed for " + target);
                    Files.deleteIfExists(temporary);
                    Files.deleteIfExists(backup);
                    Entry staged = new Entry(target, temporary, backup, existed);
                    entries.add(staged); // Register before writing so every partial staging file is cleaned on failure.
                    writeNew(temporary, write.getValue());
                    if (existed) {
                        Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
                        forceFile(backup);
                    }
                }

                List<Object> manifestEntries = new ArrayList<>(entries.size());
                for (Entry entry : entries) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("target", normalizedRoot.relativize(entry.target()).toString());
                    row.put("existed", entry.existed());
                    manifestEntries.add(row);
                }
                Map<String, Object> manifestData = new LinkedHashMap<>();
                manifestData.put("format", MANIFEST_FORMAT);
                manifestData.put("transaction", transactionId);
                manifestData.put("entries", manifestEntries);
                writeNew(manifestTemporary, Json.write(manifestData).getBytes(StandardCharsets.UTF_8));
                moveReplace(manifestTemporary, manifest);

                for (Entry entry : entries) moveReplace(entry.temporary(), entry.target());

                // The manifest deletion commits the complete batch. Backups are garbage after this point.
                Files.delete(manifest);
                cleanup(entries, transactionId);
            } catch (Exception failure) {
                if (Files.exists(manifest)) {
                    try {
                        recoverLocked(normalizedRoot);
                    } catch (Exception rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                } else {
                    cleanup(entries, transactionId);
                    try { Files.deleteIfExists(manifestTemporary); } catch (IOException ignored) { }
                }
                if (failure instanceof IOException io) throw io;
                throw new IOException("Political file batch failed", failure);
            }
        }
    }

    /** Recovers an interrupted batch. Safe to call on every server start and before map overlays load. */
    public static void recover(Path root) throws IOException {
        synchronized (LOCK) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalizedRoot))
                throw new IOException("Political file batch root cannot be a symbolic link");
            recoverLocked(normalizedRoot);
        }
    }

    private static void recoverLocked(Path root) throws IOException {
        Path manifest = manifestPath(root);
        if (Files.isSymbolicLink(manifest))
            throw new IOException("Political file batch manifest cannot be a symbolic link");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) return;

        Object parsed;
        try {
            parsed = Json.parse(Files.readString(manifest, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("Political file batch manifest is corrupt; refusing to overwrite its files", error);
        }
        if (!(parsed instanceof Map<?, ?> data)
                || !MANIFEST_FORMAT.equals(data.get("format"))
                || !(data.get("transaction") instanceof String transactionId)
                || !(data.get("entries") instanceof List<?> rawEntries)
                || rawEntries.isEmpty() || rawEntries.size() > MAX_FILES)
            throw new IOException("Invalid political file batch manifest");

        try {
            UUID parsedId = UUID.fromString(transactionId);
            if (!parsedId.toString().equalsIgnoreCase(transactionId))
                throw new IOException("Non-canonical political file batch transaction ID");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid political file batch transaction ID", invalid);
        }

        List<Entry> entries = new ArrayList<>(rawEntries.size());
        Set<Path> unique = new HashSet<>();
        for (Object raw : rawEntries) {
            if (!(raw instanceof Map<?, ?> row)
                    || !(row.get("target") instanceof String relative)
                    || !(row.get("existed") instanceof Boolean existed))
                throw new IOException("Invalid political file batch entry");
            Path target = safeRelativeTarget(root, relative);
            if (!unique.add(target)) throw new IOException("Duplicate target in political file batch manifest");
            entries.add(new Entry(target, sibling(target, transactionId, "tmp"),
                    sibling(target, transactionId, "bak"), existed));
        }

        // Keep backups until every target has been restored. Recovery can itself be interrupted safely.
        for (Entry entry : entries) {
            if (entry.existed()) {
                if (Files.isSymbolicLink(entry.backup()) || !Files.isRegularFile(entry.backup(), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Political file batch backup is missing or unsafe for " + entry.target());
                Path restore = sibling(entry.target(), transactionId, "restore");
                if (Files.isSymbolicLink(restore))
                    throw new IOException("Symbolic-link restore path is not allowed for " + entry.target());
                Files.deleteIfExists(restore);
                Files.copy(entry.backup(), restore);
                forceFile(restore);
                moveReplace(restore, entry.target());
            } else {
                Files.deleteIfExists(entry.target());
            }
            Files.deleteIfExists(entry.temporary());
        }
        Files.delete(manifest);
        cleanup(entries, transactionId);
    }

    private static Path safeTarget(Path root, Path target) throws IOException {
        if (target == null) throw new IOException("Political snapshot target is missing");
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)
                || absolute.equals(manifestPath(root)) || absolute.getFileName().toString().startsWith(MANIFEST_FILE_NAME + "."))
            throw new IOException("Political snapshot target is outside the transaction root: " + target);
        return safeRelativeTarget(root, root.relativize(absolute).toString());
    }

    private static Path safeRelativeTarget(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new IOException("Empty political snapshot target");
        Path value = Path.of(relative);
        if (value.isAbsolute()) throw new IOException("Absolute path in political batch manifest");
        Path target = root.resolve(value).normalize();
        if (Files.isSymbolicLink(target))
            throw new IOException("Symbolic-link political batch target is not allowed: " + target);
        if (!target.startsWith(root) || target.equals(root)
                || target.equals(manifestPath(root)) || target.getFileName().toString().startsWith(MANIFEST_FILE_NAME + "."))
            throw new IOException("Path traversal in political batch manifest");
        Path cursor = target.getParent();
        while (cursor != null && cursor.startsWith(root) && !cursor.equals(root)) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic-link directory in political batch target");
            cursor = cursor.getParent();
        }
        return target;
    }

    private static Path sibling(Path target, String transactionId, String suffix) {
        return target.resolveSibling(target.getFileName() + ".wf-txn-" + transactionId + "." + suffix);
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanup(List<Entry> entries, String transactionId) {
        for (Entry entry : entries) {
            try { Files.deleteIfExists(entry.temporary()); } catch (IOException ignored) { }
            try { Files.deleteIfExists(entry.backup()); } catch (IOException ignored) { }
            try { Files.deleteIfExists(sibling(entry.target(), transactionId, "restore")); }
            catch (IOException ignored) { }
        }
    }
}
