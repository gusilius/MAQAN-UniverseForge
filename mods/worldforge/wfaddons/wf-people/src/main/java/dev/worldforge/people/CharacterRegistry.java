package dev.worldforge.people;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Все персонажи мира и их должности. Сохраняется целиком в JSON вместе с миром. Аддоны подписываются на события
 * и спрашивают, что разрешает должность ({@link #unlocked}), не зная ничего о ролях друг друга.
 */
public final class CharacterRegistry {
    public enum LifecycleKind { BORN, CHILD_BORN, MARRIED, MARRIAGE_DISSOLVED, LIFE_STAGE_CHANGED, POST_APPOINTED, POST_DISMISSED, PROFESSION_CHANGED,
        HIRED, FIRED, MIGRATED, DUTY_ASSIGNED, DUTY_REMOVED, DIED, RESTORED }

    public record LifecycleEvent(LifecycleKind kind, String characterId, String from, String to, int year) { }

    public interface Listener {
        default void appointed(Character c, String role, String place) { }
        default void dismissed(Character c, String role, String place) { }
        default void died(Character c) { }
        default void born(Character c) { }
        default void lifecycle(Character c, LifecycleEvent event) { }
    }

    private final Map<String, Character> byId = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private CharacterAuthority authority;
    private int next = 1;

    /** The global authority is attached by CharacterAuthority when this registry joins a world. */
    void bindAuthority(CharacterAuthority value) {
        Objects.requireNonNull(value);
        if (authority != null && authority != value)
            throw new IllegalStateException("CharacterRegistry is already attached to another world authority");
        authority = value;
    }

    void unbindAuthority(CharacterAuthority value) {
        if (authority == value) authority = null;
    }
    private long revision;

    /** Changes only when registry membership or identity records are replaced. */
    public long revision() { return revision; }

    public void addListener(Listener l) { listeners.add(l); }

    public Character get(String id) { return byId.get(id); }

    /** Read-only view: callers must use registry operations so lifecycle events cannot be bypassed. */
    public Collection<Character> all() { return Collections.unmodifiableCollection(byId.values()); }

    public int size() { return byId.size(); }

    public Character create(String name, String house, String culture, String gender, int birthYear) {
        while (byId.containsKey("c" + next)) {
            if (next == Integer.MAX_VALUE) throw new IllegalStateException("Исчерпаны ID персонажей");
            next++;
        }
        if (next == Integer.MAX_VALUE) throw new IllegalStateException("Исчерпаны ID персонажей");
        Character c = new Character("c" + next++, name);
        c.house = house == null ? "" : house;
        c.culture = culture == null ? "" : culture;
        c.gender = gender;
        c.birthYear = birthYear;
        byId.put(c.id, c);
        revision++;
        for (Listener l : listeners) l.born(c);
        publish(c, new LifecycleEvent(LifecycleKind.BORN, c.id, "", "", birthYear));
        return c;
    }

    public void appoint(String id, String role, String place) { appoint(id, role, place, 0); }

    public void appoint(String id, String role, String place, int year) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " мёртв и не может занять должность");
        if (c.has(role, place)) return;
        c.posts.add(new Character.Post(role, place));
        for (Listener l : listeners) l.appointed(c, role, place);
        publish(c, new LifecycleEvent(LifecycleKind.POST_APPOINTED, c.id, "", role + "@" + place, year));
    }

    public void dismiss(String id, String role, String place) { dismiss(id, role, place, 0); }

    public void dismiss(String id, String role, String place, int year) {
        Character c = require(id);
        if (c.posts.removeIf(p -> p.role.equals(role) && p.place.equals(place))) {
            for (Listener l : listeners) l.dismissed(c, role, place);
            publish(c, new LifecycleEvent(LifecycleKind.POST_DISMISSED, c.id, role + "@" + place, "", year));
        }
    }

    /** Updates calendar-derived life stages and emits only real stage transitions. */
    public void refreshLifeStages(int year) {
        for (Character character : byId.values()) {
            if (!character.alive) continue;
            String current = PopulationSimulation.lifeStage(character, year).name().toLowerCase(Locale.ROOT);
            Object previous = character.props.put("population.life_stage", current);
            if (previous instanceof String old && !old.equals(current))
                emit(character, LifecycleKind.LIFE_STAGE_CHANGED, old, current, year);
            character.props.put("population.age_years", PopulationSimulation.age(character, year));
        }
    }

    public void changeProfession(String id, String professionId, int year) {
        Character c = require(id);
        String previous = PopulationSimulation.profession(c);
        PopulationSimulation.assignProfession(c, professionId, year);
        String current = PopulationSimulation.profession(c);
        if (!previous.equals(current))
            emit(c, LifecycleKind.PROFESSION_CHANGED, previous, current, year);
    }

    /** Employment is an assignment on the existing person, never a new population record. */
    public void hire(String id, String employerId, String professionId, int year) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " is dead");
        String employer = requireText(employerId, "employer");
        String profession = requireText(professionId, "profession");
        String previous = String.valueOf(c.props.getOrDefault("population.employer", ""));
        boolean changed = !employer.equals(previous)
                || !profession.equals(PopulationSimulation.profession(c))
                || !c.has("settlement_worker", employer)
                || !PopulationSimulation.hasDuty(c, PopulationSimulation.Duty.WORKER);
        if (!previous.isEmpty() && !previous.equals(employer)) fire(id, previous, year);
        changeProfession(id, profession, year);
        setDuty(id, PopulationSimulation.Duty.WORKER, true, year);
        c.props.put("population.employer", employer);
        appoint(id, "settlement_worker", employer, year);
        if (changed) emit(c, LifecycleKind.HIRED, previous, employer, year);
    }

    /** Releases employment without deleting the Character or any skills/family/history. */
    public void fire(String id, String employerId, int year) {
        Character c = require(id);
        String employer = employerId == null || employerId.isBlank()
                ? String.valueOf(c.props.getOrDefault("population.employer", "")) : employerId.trim();
        boolean employed = !employer.isEmpty() || PopulationSimulation.hasDuty(c, PopulationSimulation.Duty.WORKER);
        if (!employer.isEmpty()) dismiss(id, "settlement_worker", employer, year);
        c.props.remove("population.employer");
        setDuty(id, PopulationSimulation.Duty.WORKER, false, year);
        if (employed) emit(c, LifecycleKind.FIRED, employer, "", year);
    }

    /** Moves the same person to another population scope and releases the old settlement post. */
    public void migrate(String id, String destinationId, int year) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " is dead");
        String destination = requireText(destinationId, "destination");
        String previous = String.valueOf(c.props.getOrDefault("population.location", ""));
        String employer = String.valueOf(c.props.getOrDefault("population.employer", ""));
        if (!employer.isEmpty()) fire(id, employer, year);
        c.props.put("population.location", destination);
        emit(c, LifecycleKind.MIGRATED, previous, destination, year);
    }

    public void setDuty(String id, PopulationSimulation.Duty duty, boolean assigned, int year) {
        Character c = require(id);
        if (!c.alive && assigned) throw new IllegalStateException(c.name + " is dead");
        boolean before = PopulationSimulation.hasDuty(c, duty);
        if (before == assigned) return;
        if (assigned) PopulationSimulation.addDuty(c, duty);
        else PopulationSimulation.removeDuty(c, duty);
        emit(c, assigned ? LifecycleKind.DUTY_ASSIGNED : LifecycleKind.DUTY_REMOVED,
                duty.name().toLowerCase(Locale.ROOT), "", year);
    }

    /** Entity UUID is a replaceable body reference, scoped by body kind; Character.id remains authoritative. */
    public boolean bindEntity(String id, String bodyKind, String entityUuid) {
        Character c = require(id);
        if (!c.alive) return false;
        String kind = requireText(bodyKind, "body kind").toLowerCase(Locale.ROOT);
        String uuid = requireText(entityUuid, "entity UUID");
        for (Character other : byId.values()) {
            if (other == c) continue;
            Object raw = other.props.get("population.entity_bindings");
            if (raw instanceof Map<?, ?> bindings && bindings.containsValue(uuid)) return false;
        }
        Object raw = c.props.get("population.entity_bindings");
        Map<String, Object> bindings;
        if (raw instanceof Map<?, ?> map) {
            bindings = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String key) bindings.put(key, entry.getValue());
        } else bindings = new LinkedHashMap<>();
        bindings.put(kind, uuid);
        c.props.put("population.entity_bindings", bindings);
        return true;
    }

    public void unbindEntity(String id, String bodyKind, String entityUuid) {
        Character c = require(id);
        Object raw = c.props.get("population.entity_bindings");
        if (!(raw instanceof Map<?, ?> map)) return;
        Object current = map.get(bodyKind);
        if (!Objects.equals(current, entityUuid)) return;
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
            if (entry.getKey() instanceof String key && !key.equals(bodyKind)) bindings.put(key, entry.getValue());
        if (bindings.isEmpty()) c.props.remove("population.entity_bindings");
        else c.props.put("population.entity_bindings", bindings);
    }

    private static void addGlobalChild(Character parent, String childGlobalId) {
        Object raw = parent.props.get("family.children_global_ids");
        List<String> children = new ArrayList<>();
        if (raw instanceof List<?> list)
            for (Object value : list) if (value instanceof String id) {
                try { children.add(java.util.UUID.fromString(id).toString()); }
                catch (IllegalArgumentException ignored) { /* discard malformed legacy extension entry */ }
            }
        if (!children.contains(childGlobalId)) children.add(childGlobalId);
        parent.props.put("family.children_global_ids", children);
    }

    private void emit(Character c, LifecycleKind kind, String from, String to, int year) {
        publish(c, new LifecycleEvent(kind, c.id, from == null ? "" : from, to == null ? "" : to, year));
    }

    /**
     * Canonical, bounded life-event journal on Character.props. Other systems can consume the
     * same identity/history without inventing parallel NPC records.
     */
    private void publish(Character character, LifecycleEvent event) {
        Object raw = character.props.get("character.lifecycle.events");
        List<Object> history = new ArrayList<>();
        if (raw instanceof List<?> existing) history.addAll(existing);
        long sequence = character.props.get("character.lifecycle.sequence") instanceof Number n
                ? n.longValue() + 1 : 1;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sequence", sequence);
        entry.put("kind", event.kind().name());
        entry.put("from", event.from());
        entry.put("to", event.to());
        entry.put("year", event.year());
        history.add(entry);
        while (history.size() > 64) history.remove(0);
        character.props.put("character.lifecycle.sequence", sequence);
        character.props.put("character.lifecycle.events", history);
        for (Listener listener : listeners) listener.lifecycle(character, event);
    }

    public List<Map<String, Object>> recentLifecycle(String characterId, int limit) {
        Character character = get(characterId);
        if (character == null || limit <= 0) return List.of();
        Object raw = character.props.get("character.lifecycle.events");
        if (!(raw instanceof List<?> events)) return List.of();
        int start = Math.max(0, events.size() - Math.min(64, limit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = start; i < events.size(); i++) if (events.get(i) instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet())
                if (e.getKey() instanceof String key) copy.put(key, e.getValue());
            result.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    /** Смерть освобождает все должности. Возвращает их список (чтобы назначить преемников). */
    public List<Character.Post> kill(String id) {
        Character c = require(id);
        if (c.alive) c.alive = false;
        // A pending cross-registry transfer marker must be reconciled even when the estate
        // was previously marked settled: the recipient partition may have reached disk first.
        if (!Boolean.TRUE.equals(c.props.get("estate.settled"))
                || c.props.containsKey("estate.transfer_pending")) {
            CharacterInventory.settleEstate(authority, this, c);
        }
        if (Boolean.TRUE.equals(c.props.get("death.lifecycle.completed"))) return List.of();

        List<Character.Post> vacated = new ArrayList<>(c.posts);
        c.posts.clear();
        for (Character.Post p : vacated) for (Listener l : listeners) l.dismissed(c, p.role, p.place);
        c.props.remove("population.entity_bindings");
        int deathYear = c.props.get("population.death_year") instanceof Number n ? n.intValue() : 0;
        for (Listener l : listeners) l.died(c);
        publish(c, new LifecycleEvent(LifecycleKind.DIED, c.id, "", "", deathYear));
        c.props.put("death.lifecycle.completed", true);
        return vacated;
    }

    public List<Character> holders(String role, String place) {
        List<Character> out = new ArrayList<>();
        for (Character c : byId.values()) if (c.alive && c.has(role, place)) out.add(c);
        return out;
    }

    public Character holder(String role, String place) {
        List<Character> l = holders(role, place);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Все ли условия выполнены: есть ли в этом месте живой носитель роли, разрешающей действие (например "diplomacy"). */
    public boolean unlocked(RoleDefs defs, String place, String capability) {
        for (Role r : defs.all()) {
            if (!r.unlocks.contains(capability)) continue;
            if (!holders(r.id, place).isEmpty()) return true;
        }
        return false;
    }

    public void marry(String a, String b) { marry(a, b, 0); }

    public void marry(String a, String b, int year) {
        Character x = require(a), y = require(b);
        if (x == y) throw new IllegalArgumentException("Персонаж не может вступить в брак с самим собой");
        if (!x.alive || !y.alive) throw new IllegalStateException("Умерший персонаж не может вступить в брак");
        clearDeceasedSpouse(x);
        clearDeceasedSpouse(y);
        if (!x.spouse.isEmpty()) throw new IllegalStateException(x.name + " уже состоит в браке");
        if (!y.spouse.isEmpty()) throw new IllegalStateException(y.name + " уже состоит в браке");
        x.spouse = y.id;
        y.spouse = x.id;
        x.props.put("family.spouse_global_id", y.globalId);
        y.props.put("family.spouse_global_id", x.globalId);
        emit(x, LifecycleKind.MARRIED, "", y.id, year);
        emit(y, LifecycleKind.MARRIED, "", x.id, year);
    }

    /** A deceased or missing spouse does not permanently prevent remarriage. */
    private void clearDeceasedSpouse(Character character) {
        if (character.spouse.isEmpty()) return;
        Character spouse = byId.get(character.spouse);
        if (spouse != null && spouse.alive) return;
        if (spouse != null && character.id.equals(spouse.spouse)) {
            spouse.spouse = "";
            spouse.props.remove("family.spouse_global_id");
        }
        character.spouse = "";
        character.props.remove("family.spouse_global_id");
    }

    /** Ends a reciprocal marriage while preserving both Character identities and family links. */
    public boolean divorce(String firstId, String secondId, int year) {
        Character first = require(firstId), second = require(secondId);
        if (first == second || !first.id.equals(second.spouse) || !second.id.equals(first.spouse)) return false;
        first.spouse = "";
        second.spouse = "";
        first.props.remove("family.spouse_global_id");
        second.props.remove("family.spouse_global_id");
        first.props.remove("family.spouse_name");
        second.props.remove("family.spouse_name");
        emit(first, LifecycleKind.MARRIAGE_DISSOLVED, second.id, "", year);
        emit(second, LifecycleKind.MARRIAGE_DISSOLVED, first.id, "", year);
        return true;
    }

    public Character addChild(Character father, Character mother, String name, String gender, int year) {
        Character c = create(name, father.house, father.culture, gender, year);
        if (c.house.isEmpty()) {
            Object inheritedSurname = father.props.get("identity.surname");
            if (inheritedSurname instanceof String surname && !surname.isBlank())
                c.props.put("identity.surname", surname);
        }
        c.father = father.id;
        c.mother = mother.id;
        father.children.add(c.id);
        mother.children.add(c.id);
        c.props.put("family.father_global_id", father.globalId);
        c.props.put("family.mother_global_id", mother.globalId);
        addGlobalChild(father, c.globalId);
        addGlobalChild(mother, c.globalId);
        emit(c, LifecycleKind.CHILD_BORN, father.id, mother.id, year);
        return c;
    }

    private Character require(String id) {
        Character c = byId.get(id);
        if (c == null) throw new IllegalArgumentException("Нет персонажа " + id);
        return c;
    }

    // ------------------------------------------------------------------ сохранение

    public String toJson() { return toJson(null); }

    /** Только перечисленные персонажи (null - все): для окон на клиенте, которым не нужен весь мир. */
    public String toJson(Set<String> only) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-people/3");
        root.put("schema_version", 3);
        root.put("next", next);
        List<Object> list = new ArrayList<>();
        for (Character c : byId.values()) {
            if (only != null && !only.contains(c.id)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("global_id", c.globalId); m.put("name", c.name); m.put("house", c.house); m.put("culture", c.culture); m.put("gender", c.gender);
            m.put("birth", c.birthYear); m.put("alive", c.alive); m.put("father", c.father); m.put("mother", c.mother); m.put("spouse", c.spouse);
            m.put("children", new ArrayList<Object>(c.children));
            List<Object> posts = new ArrayList<>();
            for (Character.Post p : c.posts) posts.add(new ArrayList<Object>(List.of(p.role, p.place)));
            m.put("posts", posts);
            m.put("traits", new ArrayList<Object>(c.traits));
            m.put("props", c.props);
            list.add(m);
        }
        root.put("characters", list);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) { fromJson(json, "legacy-unscoped"); }

    /** Loads old map-local saves and deterministically assigns stable IDs within the supplied world/map namespace. */
    public void fromJson(String json, String identityNamespace) {
        if (identityNamespace == null || identityNamespace.isBlank()) throw new IllegalArgumentException("identityNamespace is required");
        // Parse and validate into an isolated snapshot. Never destroy the live registry
        // until the entire save has been accepted.
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Повреждён корень сохранения людей");
        String format = raw.get("format") instanceof String f ? f : "";
        if (!"wf-people/1".equals(format) && !"wf-people/2".equals(format) && !"wf-people/3".equals(format))
            throw new IllegalArgumentException("Неверный формат людей: " + format);
        // wf-people/1 is migrated in memory without regenerating IDs, family links or existing props.
        // Missing Phase-VII extension fields are intentionally defaulted by their accessors.
        Object rawNext = raw.get("next");
        if (!(rawNext instanceof Number nextNumber)) throw new IllegalArgumentException("Отсутствует счётчик персонажей");
        long requestedNext = nextNumber.longValue();
        if (requestedNext < 1 || requestedNext > Integer.MAX_VALUE) throw new IllegalArgumentException("Недопустимый счётчик персонажей");
        if (!(raw.get("characters") instanceof List<?> entries)) throw new IllegalArgumentException("Отсутствует список персонажей");

        Map<String, Character> loaded = new LinkedHashMap<>();
        Map<String, String> globalOwners = new HashMap<>();
        Map<String, String> entityOwners = new HashMap<>();
        int maxNumericId = 0;
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException("Повреждена запись персонажа");
            String id = requiredString(m, "id");
            String name = requiredString(m, "name");
            if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Пустой ID или имя персонажа");
            String globalId = optionalString(m, "global_id");
            if (globalId.isBlank()) {
                globalId = java.util.UUID.nameUUIDFromBytes((identityNamespace + "|" + id)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            } else {
                try { globalId = java.util.UUID.fromString(globalId).toString(); }
                catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректный global_id персонажа " + id, ex); }
            }
            String previousGlobalOwner = globalOwners.putIfAbsent(globalId, id);
            if (previousGlobalOwner != null)
                throw new IllegalArgumentException("Повторный global_id персонажа: " + globalId + " (" + previousGlobalOwner + ", " + id + ")");
            Character c = new Character(id, globalId, name);
            c.house = optionalString(m, "house");
            c.culture = optionalString(m, "culture");
            c.gender = optionalString(m, "gender");
            if (c.gender.isBlank()) c.gender = "m";
            Object birth = m.get("birth");
            if (!(birth instanceof Number birthNumber)) throw new IllegalArgumentException("Нет года рождения: " + id);
            c.birthYear = birthNumber.intValue();
            Object alive = m.get("alive");
            if (!(alive instanceof Boolean)) throw new IllegalArgumentException("Некорректный статус жизни персонажа " + id);
            c.alive = (Boolean) alive;
            c.father = optionalString(m, "father");
            c.mother = optionalString(m, "mother");
            c.spouse = optionalString(m, "spouse");

            readStringList(m.get("children"), c.children, "children", id);
            Object rawPosts = m.get("posts");
            if (!(rawPosts instanceof List<?> posts)) throw new IllegalArgumentException("Повреждены должности персонажа " + id);
            for (Object post : posts) {
                if (!(post instanceof List<?> pair) || pair.size() != 2
                        || !(pair.get(0) instanceof String role) || role.isBlank()
                        || !(pair.get(1) instanceof String place) || place.isBlank())
                    throw new IllegalArgumentException("Повреждена должность персонажа " + id);
                if (c.has(role, place)) throw new IllegalArgumentException("Повторная должность персонажа " + id);
                c.posts.add(new Character.Post(role, place));
            }
            Object rawTraits = m.get("traits");
            if (!(rawTraits instanceof List<?> traits)) throw new IllegalArgumentException("Повреждены черты персонажа " + id);
            Set<String> uniqueTraits = new HashSet<>();
            for (Object trait : traits) {
                if (!(trait instanceof String s) || s.isBlank() || !uniqueTraits.add(s))
                    throw new IllegalArgumentException("Повреждена или повторяется черта персонажа " + id);
                c.traits.add(s);
            }
            Object props = m.get("props");
            if (props != null) {
                if (!(props instanceof Map<?, ?> p)) throw new IllegalArgumentException("Повреждены свойства персонажа " + id);
                for (Map.Entry<?, ?> entry : p.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Некорректный ключ свойства " + id);
                    c.props.put(key, entry.getValue());
                }
            }
            Object bindingsRaw = c.props.get("population.entity_bindings");
            if (bindingsRaw != null && !(bindingsRaw instanceof Map<?, ?>))
                throw new IllegalArgumentException("Некорректная таблица привязок Minecraft Entity персонажа " + id);
            if (bindingsRaw instanceof Map<?, ?> bindings) {
                for (Object bindingValue : bindings.values()) {
                    if (!(bindingValue instanceof String uuid) || uuid.isBlank())
                        throw new IllegalArgumentException("Повреждена привязка Minecraft Entity персонажа " + id);
                    String previousOwner = entityOwners.putIfAbsent(uuid, id);
                    if (previousOwner != null && !previousOwner.equals(id))
                        throw new IllegalArgumentException("Minecraft Entity UUID " + uuid + " привязан к двум персонажам: " + previousOwner + " и " + id);
                }
            }
            if (loaded.putIfAbsent(id, c) != null) throw new IllegalArgumentException("Повторный ID персонажа: " + id);
            if (id.matches("c[0-9]+")) {
                try { maxNumericId = Math.max(maxNumericId, Integer.parseInt(id.substring(1))); }
                catch (NumberFormatException ignored) { /* non-allocatable legacy identifier */ }
            }
        }
        if (maxNumericId == Integer.MAX_VALUE) throw new IllegalArgumentException("Исчерпаны ID персонажей");
        int safeNext = (int) Math.max(requestedNext, (long) maxNumericId + 1L);
        // Upgrade legacy map-local family references to stable global UUID references.
        for (Character character : loaded.values()) {
            Character father = loaded.get(character.father);
            Character mother = loaded.get(character.mother);
            Character spouse = loaded.get(character.spouse);
            if (father != null) {
                character.props.putIfAbsent("family.father_global_id", father.globalId);
                addGlobalChild(father, character.globalId);
            }
            if (mother != null) {
                character.props.putIfAbsent("family.mother_global_id", mother.globalId);
                addGlobalChild(mother, character.globalId);
            }
            if (spouse != null) character.props.putIfAbsent("family.spouse_global_id", spouse.globalId);
        }
        byId.clear();
        byId.putAll(loaded);
        next = safeNext;
        revision++;
        for (Character character : byId.values()) {
            LifecycleEvent restored = new LifecycleEvent(LifecycleKind.RESTORED, character.id, "", "", 0);
            for (Listener listener : listeners) listener.lifecycle(character, restored);
        }
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("Ожидалась строка " + key);
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return "";
        if (!(value instanceof String s)) throw new IllegalArgumentException("Ожидалась строка " + key);
        return s;
    }

    private static void readStringList(Object raw, List<String> target, String field, String id) {
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("Повреждено поле " + field + " персонажа " + id);
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String s) || s.isBlank() || !unique.add(s))
                throw new IllegalArgumentException("Поврежден список " + field + " персонажа " + id);
            target.add(s);
        }
    }
}
