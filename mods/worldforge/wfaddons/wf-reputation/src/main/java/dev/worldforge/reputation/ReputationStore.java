package dev.worldforge.reputation;

import dev.worldforge.mod.Json;

import java.util.*;
import java.util.function.Predicate;

/**
 * Reputation values and reputation knowledge are deliberately separate.
 * A value is scoped to the world, a settlement, a polity or an organization.
 * Residents only know values they personally witnessed or received through an explicit
 * organization rumor-sharing operation. Merely sharing a world or map does not reveal it.
 */
public final class ReputationStore {
    public static final int MIN = -100, MAX = 100, RIPPLE_DEPTH = 3;

    public enum Scope { WORLD, SETTLEMENT, POLITY, ORGANIZATION }

    @FunctionalInterface
    public interface Listener {
        void changed(String player, String target, int oldValue, int newValue, String reason);
    }

    public static final class KnownReputation {
        public final int value;
        public final long observedAt;
        public final String source;
        KnownReputation(int value, long observedAt, String source) {
            this.value = value; this.observedAt = observedAt; this.source = source;
        }
    }

    private static final class Link {
        final String parent;
        final double factor;
        Link(String parent, double factor) { this.parent = parent; this.factor = factor; }
    }

    private final Map<String, Map<String, Integer>> values = new TreeMap<>();
    // resident -> player -> target -> observation
    private final Map<String, Map<String, Map<String, KnownReputation>>> knowledge = new TreeMap<>();
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    public static String target(Scope scope, String id) {
        Objects.requireNonNull(scope, "scope");
        if (scope == Scope.WORLD) return "world";
        if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("invalid scope id");
        return switch (scope) {
            case SETTLEMENT -> "settlement:" + id;
            case POLITY -> "polity:" + id;
            case ORGANIZATION -> "organization:" + id;
            case WORLD -> "world";
        };
    }

    public void addListener(Listener l) { listeners.add(Objects.requireNonNull(l)); }

    /** Explicit propagation only. No settlement/polity/world leakage is implicit. */
    public void link(String child, String parent, double factor) {
        if (child == null || parent == null || child.equals(parent)) throw new IllegalArgumentException("invalid reputation link");
        if (!Double.isFinite(factor) || factor < -1 || factor > 1) throw new IllegalArgumentException("Коэффициент должен быть от -1 до 1");
        for (String c = parent; c != null; c = links.containsKey(c) ? links.get(c).parent : null) {
            if (c.equals(child)) throw new IllegalArgumentException("Цикл связей: " + child + " <-> " + parent);
        }
        links.put(child, new Link(parent, factor));
    }

    public void link(Scope childScope, String childId, Scope parentScope, String parentId, double factor) {
        link(target(childScope, childId), target(parentScope, parentId), factor);
    }

    public void name(String target, String name) { names.put(target, name); }
    public String nameOf(String target) { return names.getOrDefault(target, target); }

    /** Legacy faction configuration remains supported. */
    @SuppressWarnings("unchecked")
    public void loadFactions(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map) || !(((Map<String, Object>) root).get("factions") instanceof List))
            throw new IllegalArgumentException("Нужен список factions");
        for (Object o : (List<Object>) ((Map<String, Object>) root).get("factions")) {
            Map<String, Object> f = (Map<String, Object>) o;
            String id = String.valueOf(f.get("id"));
            if (f.get("name") != null) name(id, String.valueOf(f.get("name")));
            if (f.get("parent") != null) link(id, String.valueOf(f.get("parent")),
                    f.get("factor") instanceof Number n ? n.doubleValue() : 0.5);
        }
    }

    public int get(String player, String target) {
        Map<String, Integer> m = values.get(player);
        return m == null ? 0 : m.getOrDefault(target, 0);
    }
    public int get(String player, Scope scope, String id) { return get(player, target(scope, id)); }
    public Standing standing(String player, String target) { return Standing.of(get(player, target)); }
    public Standing standing(String player, Scope scope, String id) { return Standing.of(get(player, scope, id)); }

    public Map<String, Integer> all(String player) {
        Map<String, Integer> m = values.get(player);
        return m == null ? Map.of() : new TreeMap<>(m);
    }

    public void set(String player, String target, int value, String reason) {
        int nv = Math.max(MIN, Math.min(MAX, value)), old = get(player, target);
        if (nv == old) return;
        if (nv == 0) {
            Map<String, Integer> current = values.get(player);
            if (current != null) {
                current.remove(target);
                if (current.isEmpty()) values.remove(player);
            }
        } else values.computeIfAbsent(player, k -> new TreeMap<>()).put(target, nv);
        for (Listener l : listeners) l.changed(player, target, old, nv, reason == null ? "" : reason);
    }

    public void set(String player, Scope scope, String id, int value, String reason) {
        set(player, target(scope, id), value, reason);
    }

    /** Apply configured propagation links only; unlinked scopes remain completely independent. */
    public void add(String player, String target, int delta, String reason) {
        int before = get(player, target);
        set(player, target, before + delta, reason);
        double d = get(player, target) - before;
        String cur = target;
        for (int depth = 0; depth < RIPPLE_DEPTH && links.containsKey(cur); depth++) {
            Link l = links.get(cur);
            d *= l.factor;
            int step = (int) d;
            if (step == 0) break;
            set(player, l.parent, get(player, l.parent) + step, reason);
            cur = l.parent;
        }
    }

    public void add(String player, Scope scope, String id, int delta, String reason) {
        add(player, target(scope, id), delta, reason);
    }

    /** Apply a witnessed event and record the resulting scoped reputation for that witness only. */
    public void witness(String residentId, String player, Scope scope, String scopeId, int delta,
                        long worldTime, String source) {
        String key = target(scope, scopeId);
        add(player, key, delta, source);
        observe(residentId, player, scope, scopeId, worldTime, source);
    }

    /** A resident learns only what they witnessed/heard. This does not alter actual reputation. */
    public void observe(String residentId, String player, Scope scope, String scopeId,
                        long worldTime, String source) {
        if (residentId == null || residentId.isBlank() || player == null || player.isBlank() || worldTime < 0)
            throw new IllegalArgumentException("invalid reputation observation");
        String key = target(scope, scopeId);
        knowledge.computeIfAbsent(residentId, k -> new TreeMap<>())
                .computeIfAbsent(player, k -> new TreeMap<>())
                .put(key, new KnownReputation(get(player, key), worldTime, source == null ? "witnessed" : source));
    }

    /** Null means the resident has no knowledge; unknown is not treated as neutral. */
    public KnownReputation known(String residentId, String player, Scope scope, String scopeId) {
        Map<String, Map<String, KnownReputation>> byPlayer = knowledge.get(residentId);
        if (byPlayer == null || byPlayer.get(player) == null) return null;
        return byPlayer.get(player).get(target(scope, scopeId));
    }

    /**
     * Deliberate gossip within one named organization. Only the specified target is shared;
     * no knowledge is broadcast to settlements, rival organizations or the whole world.
     */
    public boolean shareWithinOrganization(String witnessResidentId, String recipientResidentId,
                                            String player, String organizationId, long sharedAt, Predicate<String> recipientMembershipCheck) {
        KnownReputation heard = known(witnessResidentId, player, Scope.ORGANIZATION, organizationId);
        if (heard == null || recipientResidentId == null || recipientResidentId.isBlank()
                || recipientMembershipCheck == null || !recipientMembershipCheck.test(recipientResidentId)) return false;
        String key = target(Scope.ORGANIZATION, organizationId);
        Map<String, KnownReputation> recipient = knowledge
                .computeIfAbsent(recipientResidentId, k -> new TreeMap<>())
                .computeIfAbsent(player, k -> new TreeMap<>());
        KnownReputation old = recipient.get(key);
        if (old != null && old.observedAt > sharedAt) return false;
        recipient.put(key, new KnownReputation(heard.value, sharedAt, "rumor:" + witnessResidentId));
        return true;
    }

    public Map<String, Integer> knownValues(String residentId, String player) {
        Map<String, Map<String, KnownReputation>> byPlayer = knowledge.get(residentId);
        if (byPlayer == null || byPlayer.get(player) == null) return Map.of();
        Map<String, Integer> result = new TreeMap<>();
        byPlayer.get(player).forEach((k, v) -> result.put(k, v.value));
        return Collections.unmodifiableMap(result);
    }

    /** World-wide reputation drift is intentionally opt-in and never changes residents' knowledge automatically. */
    public void decay(int points) {
        if (points < 0) throw new IllegalArgumentException("points must be non-negative");
        for (String p : new ArrayList<>(values.keySet())) {
            for (Map.Entry<String, Integer> e : new ArrayList<>(values.get(p).entrySet())) {
                int v = e.getValue(), nv = v > 0 ? Math.max(0, v - points) : Math.min(0, v + points);
                set(p, e.getKey(), nv, "забвение");
            }
        }
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-reputation/2");
        root.put("values", values);
        Map<String, Object> people = new TreeMap<>();
        knowledge.forEach((resident, players) -> {
            Map<String, Object> p = new TreeMap<>();
            players.forEach((player, observations) -> {
                Map<String, Object> o = new TreeMap<>();
                observations.forEach((key, v) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("value", v.value); item.put("time", v.observedAt); item.put("source", v.source);
                    o.put(key, item);
                });
                p.put(player, o);
            });
            people.put(resident, p);
        });
        root.put("knowledge", people);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        values.clear(); knowledge.clear();
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map) || !(((Map<String, Object>) parsed).get("values") instanceof Map))
            throw new IllegalArgumentException("Неверный формат репутации");
        Map<String, Object> root = (Map<String, Object>) parsed;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("values")).entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Integer> m = new TreeMap<>();
            for (Map.Entry<?, ?> t : raw.entrySet()) {
                int v = ((Number) t.getValue()).intValue();
                if (v != 0) m.put(String.valueOf(t.getKey()), Math.max(MIN, Math.min(MAX, v)));
            }
            if (!m.isEmpty()) values.put(e.getKey(), m);
        }
        if (root.get("knowledge") instanceof Map<?, ?> residents) for (Map.Entry<?, ?> re : residents.entrySet()) {
            if (!(re.getValue() instanceof Map<?, ?> players)) continue;
            Map<String, Map<String, KnownReputation>> playerMap = new TreeMap<>();
            for (Map.Entry<?, ?> pe : players.entrySet()) {
                if (!(pe.getValue() instanceof Map<?, ?> observations)) continue;
                Map<String, KnownReputation> obs = new TreeMap<>();
                for (Map.Entry<?, ?> oe : observations.entrySet()) {
                    if (!(oe.getValue() instanceof Map<?, ?> item) || !(item.get("value") instanceof Number value)) continue;
                    long time = item.get("time") instanceof Number n ? n.longValue() : 0L;
                    obs.put(String.valueOf(oe.getKey()), new KnownReputation(
                            Math.max(MIN, Math.min(MAX, value.intValue())), time,
                            String.valueOf(item.containsKey("source") ? item.get("source") : "legacy")));
                }
                if (!obs.isEmpty()) playerMap.put(String.valueOf(pe.getKey()), obs);
            }
            if (!playerMap.isEmpty()) knowledge.put(String.valueOf(re.getKey()), playerMap);
        }
    }
}
