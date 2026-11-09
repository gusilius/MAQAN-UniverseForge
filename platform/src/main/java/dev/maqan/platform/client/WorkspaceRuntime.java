package dev.maqan.platform.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared, renderer-neutral services used by every MAQAN feature provider.
 *
 * Providers publish metadata and stable IDs only. The owning module retains
 * authority over its domain objects, commands, persistence and simulation.
 */
public final class WorkspaceRuntime {
    public enum Severity { INFO, SUCCESS, WARNING, ERROR }

    public record SearchEntry(String id, String providerId, String type, String name,
                              String worldId, String summary, List<String> keywords) {
        public SearchEntry {
            id = required(id, "id");
            providerId = required(providerId, "providerId");
            type = required(type, "type");
            name = required(name, "name");
            worldId = clean(worldId);
            summary = clean(summary);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record InspectorField(String section, String label, String value) {
        public InspectorField {
            section = clean(section);
            label = required(label, "label");
            value = clean(value);
        }
    }

    public record WorkspaceEvent(long sequence, long timestamp, Severity severity,
                                 String source, String category, String message,
                                 String entityId, String worldId) {}

    public record KeyChord(int keyCode, int modifiers) {}

    public record Shortcut(String id, String label, KeyChord chord, String actionId,
                           String providerId, boolean global) {
        public Shortcut {
            id = required(id, "id");
            label = required(label, "label");
            Objects.requireNonNull(chord, "chord");
            actionId = required(actionId, "actionId");
            providerId = clean(providerId);
        }
    }

    public record ComponentDescriptor(String id, String category, String providerId,
                                      int apiVersion, String description) {
        public ComponentDescriptor {
            id = required(id, "id");
            category = required(category, "category");
            providerId = required(providerId, "providerId");
            if (apiVersion < 1) throw new IllegalArgumentException("apiVersion must be positive");
            description = clean(description);
        }
    }

    @FunctionalInterface
    public interface InspectorProvider {
        List<InspectorField> inspect(PlatformSelectionContext.Selection selection);
    }

    public record Action(String id, String label, String providerId,
                         Predicate<PlatformSelectionContext.Selection> visibleWhen,
                         Consumer<PlatformSelectionContext.Selection> execute) {
        public Action {
            id = required(id, "id");
            label = required(label, "label");
            providerId = required(providerId, "providerId");
            visibleWhen = visibleWhen == null ? selection -> true : visibleWhen;
            Objects.requireNonNull(execute, "execute");
        }
    }

    private static final int MAX_EVENTS = 500;
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final Deque<WorkspaceEvent> JOURNAL = new ArrayDeque<>();
    private static final Map<String, SearchEntry> SEARCH_INDEX = new ConcurrentHashMap<>();
    private static final Map<String, Consumer<SearchEntry>> ENTITY_OPENERS = new ConcurrentHashMap<>();
    private static final Map<String, InspectorProvider> INSPECTORS = new ConcurrentHashMap<>();
    private static final Map<String, Action> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Shortcut> SHORTCUTS = new ConcurrentHashMap<>();
    private static final Map<KeyChord, String> SHORTCUT_BY_CHORD = new ConcurrentHashMap<>();
    private static final Map<String, ComponentDescriptor> COMPONENTS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Consumer<WorkspaceEvent>> EVENT_LISTENERS =
            new CopyOnWriteArrayList<>();

    private WorkspaceRuntime() {}

    public static void index(SearchEntry entry) {
        Objects.requireNonNull(entry, "entry");
        SEARCH_INDEX.put(key(entry.providerId(), entry.id()), entry);
    }

    /** Provider callback that opens/highlights an indexed entity in its owning module. */
    public static void registerEntityOpener(String providerId, Consumer<SearchEntry> opener) {
        ENTITY_OPENERS.put(required(providerId, "providerId"), Objects.requireNonNull(opener, "opener"));
    }

    public static boolean openSearchEntry(SearchEntry entry) {
        if (entry == null) return false;
        Consumer<SearchEntry> opener = ENTITY_OPENERS.get(entry.providerId());
        if (opener == null) return false;
        opener.accept(entry);
        return true;
    }

    public static void removeIndexed(String providerId, String entityId) {
        SEARCH_INDEX.remove(key(providerId, entityId));
    }

    public static void removeProviderIndex(String providerId) {
        SEARCH_INDEX.entrySet().removeIf(entry -> entry.getValue().providerId().equals(providerId));
    }

    public static List<SearchEntry> search(String query, int limit) {
        String needle = clean(query).toLowerCase(Locale.ROOT);
        if (needle.isBlank() || limit <= 0) return List.of();
        return SEARCH_INDEX.values().stream()
                .map(entry -> Map.entry(entry, score(entry, needle)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<Map.Entry<SearchEntry, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed().thenComparing(entry -> entry.getKey().name(), String.CASE_INSENSITIVE_ORDER))
                .limit(Math.min(limit, 100))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int score(SearchEntry entry, String needle) {
        String name = entry.name().toLowerCase(Locale.ROOT);
        String type = entry.type().toLowerCase(Locale.ROOT);
        String summary = entry.summary().toLowerCase(Locale.ROOT);
        if (name.equals(needle)) return 100;
        if (name.startsWith(needle)) return 80;
        if (name.contains(needle)) return 60;
        if (type.contains(needle)) return 35;
        if (entry.worldId().toLowerCase(Locale.ROOT).contains(needle)) return 25;
        if (summary.contains(needle)) return 15;
        for (String keyword : entry.keywords())
            if (keyword.toLowerCase(Locale.ROOT).contains(needle)) return 30;
        return 0;
    }

    public static void registerInspector(String providerId, InspectorProvider provider) {
        INSPECTORS.put(required(providerId, "providerId"), Objects.requireNonNull(provider, "provider"));
    }

    public static void unregisterInspector(String providerId) {
        INSPECTORS.remove(providerId);
    }

    public static List<InspectorField> inspect(PlatformSelectionContext.Selection selection) {
        if (selection == null) return List.of();
        InspectorProvider provider = INSPECTORS.get(selection.providerId());
        if (provider == null) return List.of();
        List<InspectorField> fields = provider.inspect(selection);
        return fields == null ? List.of() : List.copyOf(fields);
    }

    public static void registerAction(Action action) {
        Objects.requireNonNull(action, "action");
        ACTIONS.put(action.id(), action);
    }

    public static void unregisterAction(String actionId) {
        ACTIONS.remove(actionId);
    }

    public static List<Action> actionsFor(PlatformSelectionContext.Selection selection) {
        if (selection == null) return List.of();
        return ACTIONS.values().stream()
                .filter(action -> action.providerId().equals(selection.providerId())
                        || action.providerId().equals("*"))
                .filter(action -> safeVisible(action, selection))
                .sorted(Comparator.comparing(Action::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static boolean executeAction(String actionId, PlatformSelectionContext.Selection selection) {
        Action action = ACTIONS.get(actionId);
        if (action == null || selection == null || !safeVisible(action, selection)) return false;
        action.execute().accept(selection);
        return true;
    }

    private static boolean safeVisible(Action action, PlatformSelectionContext.Selection selection) {
        try { return action.visibleWhen().test(selection); }
        catch (RuntimeException ignored) { return false; }
    }

    public static void registerComponent(ComponentDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        COMPONENTS.put(descriptor.id(), descriptor);
    }

    public static void unregisterComponent(String componentId) {
        COMPONENTS.remove(componentId);
    }

    public static List<ComponentDescriptor> components() {
        return COMPONENTS.values().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::category)
                        .thenComparing(ComponentDescriptor::id))
                .toList();
    }

    public static void registerShortcut(Shortcut shortcut) {
        Objects.requireNonNull(shortcut, "shortcut");
        String previous = SHORTCUT_BY_CHORD.putIfAbsent(shortcut.chord(), shortcut.id());
        if (previous != null && !previous.equals(shortcut.id()))
            throw new IllegalArgumentException("Shortcut chord is already assigned to " + previous);
        Shortcut old = SHORTCUTS.put(shortcut.id(), shortcut);
        if (old != null && !old.chord().equals(shortcut.chord()))
            SHORTCUT_BY_CHORD.remove(old.chord(), old.id());
    }

    public static void unregisterShortcut(String shortcutId) {
        Shortcut removed = SHORTCUTS.remove(shortcutId);
        if (removed != null) SHORTCUT_BY_CHORD.remove(removed.chord(), removed.id());
    }

    public static Optional<Shortcut> shortcutFor(int keyCode, int modifiers) {
        String id = SHORTCUT_BY_CHORD.get(new KeyChord(keyCode, modifiers));
        return Optional.ofNullable(id == null ? null : SHORTCUTS.get(id));
    }

    public static List<Shortcut> shortcuts() {
        return SHORTCUTS.values().stream()
                .sorted(Comparator.comparing(Shortcut::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static void publish(Severity severity, String source, String category,
                               String message, String entityId, String worldId) {
        WorkspaceEvent event = new WorkspaceEvent(EVENT_SEQUENCE.incrementAndGet(),
                System.currentTimeMillis(), Objects.requireNonNull(severity, "severity"),
                clean(source), clean(category), required(message, "message"),
                clean(entityId), clean(worldId));
        synchronized (JOURNAL) {
            JOURNAL.addLast(event);
            while (JOURNAL.size() > MAX_EVENTS) JOURNAL.removeFirst();
        }
        for (Consumer<WorkspaceEvent> listener : EVENT_LISTENERS) {
            try { listener.accept(event); }
            catch (RuntimeException ignored) { /* One provider must not break the event bus. */ }
        }
    }

    public static List<WorkspaceEvent> journal(int limit) {
        if (limit <= 0) return List.of();
        synchronized (JOURNAL) {
            int skip = Math.max(0, JOURNAL.size() - Math.min(limit, MAX_EVENTS));
            List<WorkspaceEvent> result = new ArrayList<>();
            int index = 0;
            for (WorkspaceEvent event : JOURNAL) if (index++ >= skip) result.add(event);
            return List.copyOf(result);
        }
    }

    public static AutoCloseable subscribe(Consumer<WorkspaceEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        EVENT_LISTENERS.add(listener);
        return () -> EVENT_LISTENERS.remove(listener);
    }

    public static void unregisterProvider(String providerId) {
        String id = clean(providerId);
        if (id.isBlank()) return;
        removeProviderIndex(id);
        unregisterInspector(id);
        ENTITY_OPENERS.remove(id);
        ACTIONS.entrySet().removeIf(entry -> entry.getValue().providerId().equals(id));
        SHORTCUTS.entrySet().removeIf(entry -> {
            Shortcut shortcut = entry.getValue();
            if (!shortcut.providerId().equals(id)) return false;
            SHORTCUT_BY_CHORD.remove(shortcut.chord(), shortcut.id());
            return true;
        });
        COMPONENTS.entrySet().removeIf(entry -> entry.getValue().providerId().equals(id));
    }

    /** Test/support hook; clears transient registries without changing installed modules. */
    static void clearTransientState() {
        SEARCH_INDEX.clear();
        ENTITY_OPENERS.clear();
        INSPECTORS.clear();
        ACTIONS.clear();
        SHORTCUTS.clear();
        SHORTCUT_BY_CHORD.clear();
        COMPONENTS.clear();
        synchronized (JOURNAL) { JOURNAL.clear(); }
        EVENT_LISTENERS.clear();
        EVENT_SEQUENCE.set(0);
    }

    private static String key(String providerId, String entityId) {
        return required(providerId, "providerId") + ":" + required(entityId, "entityId");
    }

    private static String required(String value, String name) {
        String result = clean(value);
        if (result.isBlank()) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
