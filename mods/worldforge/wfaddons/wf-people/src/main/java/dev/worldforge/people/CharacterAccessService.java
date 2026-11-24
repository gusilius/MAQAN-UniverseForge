package dev.worldforge.people;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scoped access to canonical Characters. A lease pins the exact materialized object
 * against a future registry eviction until close(); it does not itself perform I/O.
 *
 * This is the migration boundary for storage-backed access. Existing get()/all()
 * callers remain compatible and are not implicitly pinned.
 */
public final class CharacterAccessService {
    private final CharacterRegistry registry;

    public CharacterAccessService(CharacterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Lease acquire(String localId) {
        return registry.acquire(localId);
    }

    public Lease acquireGlobal(String globalId) {
        return registry.acquireGlobal(globalId);
    }

    public static final class Lease implements AutoCloseable {
        private final CharacterRegistry registry;
        private final String localId;
        private final Character character;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(CharacterRegistry registry, String localId, Character character) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.localId = Objects.requireNonNull(localId, "localId");
            this.character = Objects.requireNonNull(character, "character");
        }

        public Character get() {
            if (closed.get()) throw new IllegalStateException("Character access lease is closed");
            return character;
        }

        public String localId() { return localId; }
        public String globalId() { return character.globalId; }
        public boolean isClosed() { return closed.get(); }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) registry.releaseAccess(localId, character);
        }
    }
}
