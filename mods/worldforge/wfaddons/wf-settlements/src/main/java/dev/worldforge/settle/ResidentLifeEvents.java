package dev.worldforge.settle;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterDevelopment;
import dev.worldforge.people.CharacterHealth;
import dev.worldforge.people.FamilyPlanning;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterAccessService;
import dev.worldforge.people.PeopleMod;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.NameBank;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic yearly family/social event pass for the canonical Character registry.
 * It never creates a second resident identity and records its year markers on Character props.
 */
public final class ResidentLifeEvents {

    private static final int MAX_RELATIONSHIP_CANDIDATES_PER_PERSON_PER_YEAR = 32;
    private static final Map<CharacterRegistry, RegistryPassState> REGISTRY_PASSES = new WeakHashMap<>();
    private static final Map<CharacterRegistry, PregnancySchedule> PREGNANCY_SCHEDULES = new WeakHashMap<>();
    private static final Map<CharacterRegistry, HealthSchedule> HEALTH_SCHEDULES = new WeakHashMap<>();
    private static final AtomicLong SYNCHRONOUS_TOKENS = new AtomicLong();

    private record DueHealth(long dueDay, String characterGlobalId) {}
    private static final class HealthSchedule {
        final PriorityQueue<DueHealth> due = new PriorityQueue<>(Comparator
                .comparingLong(DueHealth::dueDay).thenComparing(DueHealth::characterGlobalId));
        final Map<String, Long> queued = new HashMap<>();
        int scanCursor;
    }

    private record DuePregnancy(long dueDay, String motherGlobalId) {}
    private static final class PregnancySchedule {
        final PriorityQueue<DuePregnancy> due = new PriorityQueue<>(Comparator
                .comparingLong(DuePregnancy::dueDay).thenComparing(DuePregnancy::motherGlobalId));
        final Map<String, Long> queued = new HashMap<>();
        int scanCursor;
    }

    private static final class YearSession {
        final int year;
        final long worldDay;
        final CharacterRegistry registry;
        final long membershipResetGeneration;
        final int initialSize;
        final List<String> newbornIds = new ArrayList<>();
        final List<String> eligibleSingleIds = new ArrayList<>();
        int phase;
        int cursor;
        long lastAdvanceToken = Long.MIN_VALUE;
        YearSession(CharacterRegistry registry, int year) {
            this.year = year;
            this.worldDay = dev.worldforge.mod.api.WorldForgeApi.clock().dayNumber();
            this.registry = registry;
            this.membershipResetGeneration = registry.membershipResetGeneration();
            this.initialSize = registry.size();
        }
    }

    private static final class StageRefresh {
        final int year, end;
        final CharacterRegistry registry;
        final long membershipResetGeneration;
        int cursor;
        long lastAdvanceToken = Long.MIN_VALUE;
        StageRefresh(CharacterRegistry registry, int year, int start, int end) {
            this.year = year;
            this.registry = registry;
            this.membershipResetGeneration = registry.membershipResetGeneration();
            this.cursor = start;
            this.end = end;
        }
    }

    private static final class RegistryPassState {
        int familyYear = Integer.MIN_VALUE;
        int stageSize = -1;
        int lastCallYear = Integer.MIN_VALUE;
        long lastCallToken = Long.MIN_VALUE;
        boolean lastCallResult;
        YearSession session;
        StageRefresh stageRefresh;
    }

    private ResidentLifeEvents() {}

    public static void yearly(CharacterRegistry registry, int year) { yearly(registry, year, null); }

    /** Synchronous compatibility API for domain tests and non-server callers. */
    public static void yearly(CharacterRegistry registry, int year, NameBank names) {
        if (registry == null || year < 1) return;
        // Every synchronous invocation needs a globally unique token. Reusing token 0
        // caused same-year calls to return a stale cached result and spin forever.
        for (int attempt = 0; attempt < 6; attempt++) {
            long token = SYNCHRONOUS_TOKENS.incrementAndGet();
            if (advanceYearly(registry, year, names, Integer.MAX_VALUE, token)) return;
        }
        throw new IllegalStateException("ResidentLifeEvents yearly pass did not complete within 6 synchronous advances (year=" + year + ")");
    }

    /**
     * Advances one canonical registry by at most residentBudget people.
     * tickToken prevents multiple settlements sharing a registry from multiplying the
     * annual family budget during one Minecraft server tick.
     */
    public static boolean advanceYearly(CharacterRegistry registry, int year, NameBank names,
                                        int residentBudget, long tickToken) {
        if (registry == null || year < 1) return true;
        if (residentBudget <= 0) return false;
        RegistryPassState state;
        synchronized (REGISTRY_PASSES) {
            state = REGISTRY_PASSES.computeIfAbsent(registry, ignored -> new RegistryPassState());
        }
        YearSession session = state.session;
        if (session != null && session.year != year) year = session.year;
        // Guard callback routes, including stage refresh, against same-tick duplicate work.
        if (state.lastCallYear == year && state.lastCallToken == tickToken) return state.lastCallResult;
        if (state.familyYear == year && session == null) {
            boolean refreshed = advanceStageRefresh(registry, state, year, residentBudget, tickToken);
            state.lastCallYear = year; state.lastCallToken = tickToken; state.lastCallResult = refreshed;
            return refreshed;
        }
        if (session == null) {
            session = new YearSession(registry, year);
            state.session = session;
        }
        if (session.lastAdvanceToken == tickToken) return false;
        session.lastAdvanceToken = tickToken;

        int remaining = residentBudget;
        while (remaining > 0 && session.phase < 4) {
            if (session.phase == 0 && session.cursor >= session.initialSize) {
                // Newborns remain in the canonical registry. Their calendar-derived
                // fields are refreshed in the bounded append-only catch-up after family phases.
                session.phase = 1;
                session.cursor = 0;
                continue;
            }
            int workSize = session.phase == 3 ? session.eligibleSingleIds.size()
                    : session.initialSize + (session.phase >= 1 ? session.newbornIds.size() : 0);
            if (session.cursor >= workSize) {
                session.phase++;
                session.cursor = 0;
                continue;
            }
            int end = session.cursor + Math.min(remaining, workSize - session.cursor);
            
            if (session.phase == 0) {
                for (int index = session.cursor; index < end; index++) {
                    String localId = registry.idAt(index);
                    String globalId = registry.globalIdForLocalId(localId);
                    if (globalId == null) continue;
                    try (CharacterAccessService.Lease lease = registry.access().acquire(localId)) {
                        if (lease == null) continue;
                        Character person = lease.get();
                        registry.refreshLifeStages(List.of(person), session.year);
                        Character newborn = processPregnancy(registry, person, session.year, session.worldDay, names);
                        if (newborn != null) session.newbornIds.add(newborn.globalId);
                        if (person.alive && (person.spouse == null || person.spouse.isBlank())
                                && PopulationSimulation.age(person, session.year) >= 18)
                            session.eligibleSingleIds.add(globalId);
                    }
                }
            } else if (session.phase == 1) {
                for (int index = session.cursor; index < end; index++)
                    withSessionPerson(session, index, person -> advanceChildhoodPerson(registry, person, session.year));
            } else if (session.phase == 2) {
                for (int index = session.cursor; index < end; index++)
                    withSessionPerson(session, index, person -> processMaritalConflict(registry, person, session.year));
            } else {
                for (int i = session.cursor; i < end; i++)
                    processRelationshipReviewById(registry, session.eligibleSingleIds, i, session.year);
            }
            int processed = end - session.cursor;
            session.cursor = end;
            remaining -= processed;
        }

        if (session.phase >= 4) {
            
            synchronized (REGISTRY_PASSES) {
                state.familyYear = session.year;
                state.stageSize = session.initialSize;
                state.session = null;
                state.stageRefresh = null;
            }
            // Stage catch-up runs on the next server tick; do not spend a second budget in this one.
            state.lastCallYear = year; state.lastCallToken = tickToken; state.lastCallResult = false;
            return false;
        }
        state.lastCallYear = year; state.lastCallToken = tickToken; state.lastCallResult = false;
        return false;
    }

    private static boolean advanceStageRefresh(CharacterRegistry registry, RegistryPassState state,
                                                int year, int residentBudget, long tickToken) {
        
        if (registry.size() <= state.stageSize) return true;
        StageRefresh refresh = state.stageRefresh;
        if (refresh == null || refresh.year != year) {
            refresh = new StageRefresh(registry, year, state.stageSize, registry.size());
            state.stageRefresh = refresh;
        }
        if (refresh.lastAdvanceToken == tickToken) return false;
        refresh.lastAdvanceToken = tickToken;
        int end = refresh.cursor + Math.min(residentBudget, refresh.end - refresh.cursor);
        if (end > refresh.cursor) {
            
            for (int index = refresh.cursor; index < end; index++) {
                String localId = registry.idAt(index);
                try (CharacterAccessService.Lease lease = registry.access().acquire(localId)) {
                    if (lease != null) registry.refreshLifeStages(List.of(lease.get()), year);
                }
            }
            refresh.cursor = end;
        }
        if (refresh.cursor >= refresh.end) {
            state.stageSize = refresh.end;
            state.stageRefresh = null;
            return registry.size() <= state.stageSize;
        }
        return false;
    }

    /**
     * Advances the persisted pregnancy due-date schedule under a strict work budget.
     * New conceptions are indexed immediately; a rotating compatibility scan discovers
     * pregnancies from existing saves without a population-wide tick scan.
     */
    public static int advanceDuePregnanciesBatch(CharacterRegistry registry, int year, long worldDay,
                                                  NameBank names, int budget) {
        if (registry == null || budget <= 0) return 0;
        PregnancySchedule schedule;
        synchronized (PREGNANCY_SCHEDULES) {
            schedule = PREGNANCY_SCHEDULES.computeIfAbsent(registry, ignored -> new PregnancySchedule());
        }
        List<Character> people = registry.all();
        int size = Math.min(registry.size(), people.size());
        if (size == 0) return 0;

        // Reserve half the budget for due deliveries, while still discovering legacy pregnancies.
        int scanBudget = schedule.due.isEmpty() ? budget : budget / 2;
        int start = Math.floorMod(schedule.scanCursor, size);
        int scanned = Math.min(scanBudget, size);
        for (int i = 0; i < scanned; i++) {
            Character person = people.get((start + i) % size);
            if (FamilyPlanning.isPregnant(person)) enqueuePregnancy(registry, person);
        }
        schedule.scanCursor = (start + scanned) % size;

        int processed = scanned;
        while (processed < budget && !schedule.due.isEmpty()
                && schedule.due.peek().dueDay() <= worldDay) {
            DuePregnancy due = schedule.due.poll();
            processed++;
            Long activeDueDay = schedule.queued.get(due.motherGlobalId());
            if (activeDueDay == null || activeDueDay.longValue() != due.dueDay()) continue;
            schedule.queued.remove(due.motherGlobalId());
            Character mother = registry.getByGlobalId(due.motherGlobalId());
            if (mother == null || !FamilyPlanning.isPregnant(mother)
                    || FamilyPlanning.dueDay(mother) != due.dueDay()) {
                continue;
            }
            if (mother.alive) {
                if (!FamilyPlanning.dueFatherId(mother, worldDay).isBlank())
                    deliverDuePregnancy(registry, mother, year, worldDay, names);
                else
                    FamilyPlanning.cancel(mother, null, "father-unavailable", year);
            } else {
                FamilyPlanning.cancel(mother, null, "mother-unavailable", year);
            }
        }
        return processed;
    }

    /** Advances only residents with a persisted health recovery deadline, under a strict scan budget. */
    public static int advanceHealthBatch(CharacterRegistry registry, long worldDay, int budget) {
        if (registry == null || budget <= 0 || worldDay < 0) return 0;
        HealthSchedule schedule;
        synchronized (HEALTH_SCHEDULES) {
            schedule = HEALTH_SCHEDULES.computeIfAbsent(registry, ignored -> new HealthSchedule());
        }
        List<Character> people = registry.all();
        int size = Math.min(registry.size(), people.size());
        if (size == 0) return 0;
        int scanBudget = schedule.due.isEmpty() ? budget : Math.max(1, budget / 2);
        int start = Math.floorMod(schedule.scanCursor, size);
        int scanned = Math.min(scanBudget, size);
        for (int i = 0; i < scanned; i++) {
            Character person = people.get((start + i) % size);
            long dueDay = CharacterHealth.nextEventDay(person);
            if (dueDay >= 0) enqueueHealth(registry, person, dueDay);
        }
        schedule.scanCursor = (start + scanned) % size;
        int processed = scanned;
        while (processed < budget && !schedule.due.isEmpty() && schedule.due.peek().dueDay() <= worldDay) {
            DueHealth due = schedule.due.poll();
            processed++;
            Long active = schedule.queued.get(due.characterGlobalId());
            if (active == null || active.longValue() != due.dueDay()) continue;
            schedule.queued.remove(due.characterGlobalId());
            Character person = registry.getByGlobalId(due.characterGlobalId());
            if (person == null || !person.alive || CharacterHealth.nextEventDay(person) != due.dueDay()) continue;
            CharacterHealth.advance(person, worldDay);
            long next = CharacterHealth.nextEventDay(person);
            if (next >= 0) enqueueHealth(registry, person, next);
        }
        return processed;
    }

    private static void enqueueHealth(CharacterRegistry registry, Character person, long dueDay) {
        if (person == null || dueDay < 0) return;
        HealthSchedule schedule;
        synchronized (HEALTH_SCHEDULES) {
            schedule = HEALTH_SCHEDULES.computeIfAbsent(registry, ignored -> new HealthSchedule());
        }
        Long existing = schedule.queued.get(person.globalId);
        if (existing == null || existing.longValue() != dueDay) {
            schedule.queued.put(person.globalId, dueDay);
            schedule.due.add(new DueHealth(dueDay, person.globalId));
        }
    }

    private static void enqueuePregnancy(CharacterRegistry registry, Character mother) {
        if (mother == null || !FamilyPlanning.isPregnant(mother)) return;
        PregnancySchedule schedule;
        synchronized (PREGNANCY_SCHEDULES) {
            schedule = PREGNANCY_SCHEDULES.computeIfAbsent(registry, ignored -> new PregnancySchedule());
        }
        long dueDay = FamilyPlanning.dueDay(mother);
        Long existingDueDay = schedule.queued.get(mother.globalId);
        if (existingDueDay == null || existingDueDay.longValue() != dueDay) {
            schedule.queued.put(mother.globalId, dueDay);
            schedule.due.add(new DuePregnancy(dueDay, mother.globalId));
        }
    }

    private static String personIdAt(YearSession session, int index) {
        if (index < session.initialSize) return session.registry.globalIdForLocalId(session.registry.idAt(index));
        return session.newbornIds.get(index - session.initialSize);
    }

    private static void withSessionPerson(YearSession session, int index,
                                          java.util.function.Consumer<Character> action) {
        String globalId = personIdAt(session, index);
        if (globalId == null || globalId.isBlank()) return;
        CharacterRegistry owner = PeopleMod.authority().registryForGlobal(globalId);
        if (owner == null) return;
        try (CharacterAccessService.Lease lease = owner.access().acquireGlobal(globalId)) {
            if (lease != null) action.accept(lease.get());
        }
    }

    private static Character processPregnancy(CharacterRegistry registry, Character person, int year,
                                               long worldDay, NameBank names) {
        if (!person.alive || year <= number(person.props.get("social.last_family_year"), 0)) return null;
        person.props.put("social.last_family_year", year);
        if (!"f".equalsIgnoreCase(person.gender)) return null;
        Character mother = person;
        if (FamilyPlanning.isPregnant(mother)) {
            if (FamilyPlanning.dueFatherId(mother, worldDay).isBlank()) return null;
            return deliverDuePregnancy(registry, mother, year, worldDay, names);
        }
        Character spouse = mother.spouse == null || mother.spouse.isBlank() ? null : registry.get(mother.spouse);
        if (spouse != null && spouse.alive && mother.id.equals(spouse.spouse)
                && FamilyPlanning.tryConceive(mother, spouse, year, worldDay,
                        Objects.hash(mother.globalId, spouse.globalId)))
            enqueuePregnancy(registry, mother);
        return null;
    }

    private static Character deliverDuePregnancy(CharacterRegistry registry, Character mother, int year,
                                                  long worldDay, NameBank names) {
        String dueFatherId = FamilyPlanning.dueFatherId(mother, worldDay);
        if (dueFatherId.isBlank()) return null;
        Character biologicalFather = registry.get(dueFatherId);
        if (biologicalFather == null || !biologicalFather.alive) {
            FamilyPlanning.cancel(mother, biologicalFather, "father-unavailable", year);
            return null;
        }
        String gender = Math.floorMod(Objects.hash(mother.id, biologicalFather.id, year, "gender"), 2) == 0 ? "f" : "m";
        String childName = "Дитя семьи " + (mother.house == null || mother.house.isBlank() ? mother.name : mother.house);
        if (names != null) {
            String race = mother.props.get("origin.race") instanceof String mr && !mr.isBlank() ? mr
                    : biologicalFather.props.get("origin.race") instanceof String fr ? fr : "";
            String culture = mother.culture == null || mother.culture.isBlank() ? biologicalFather.culture : mother.culture;
            try {
                childName = names.pickGivenName(race, culture, gender,
                        new Random(Objects.hash(mother.globalId, biologicalFather.globalId, year, "birth-name")));
            } catch (IllegalStateException unavailablePool) {
                // Preserve the stable fallback if a data pack has no compatible name pool.
            }
        }
        Character child = registry.addChild(biologicalFather, mother, childName, gender, year);
        child.traits.add("curious");
        child.props.put("family.father_name", biologicalFather.name);
        child.props.put("family.mother_name", mother.name);
        ResidentSocial.adjust(biologicalFather, child.globalId, 35);
        ResidentSocial.adjust(mother, child.globalId, 35);
        ResidentSocial.adjust(child, biologicalFather.globalId, 35);
        ResidentSocial.adjust(child, mother.globalId, 35);
        FamilyPlanning.complete(mother, biologicalFather, year, child.id);
        return child;
    }

    private static void processMaritalConflict(CharacterRegistry registry, Character person, int year) {
        if (!person.alive || person.spouse == null || person.spouse.isBlank()
                || person.id.compareTo(person.spouse) >= 0) return;
        Character spouse = registry.get(person.spouse);
        if (spouse == null || !spouse.alive || !person.id.equals(spouse.spouse)
                || year <= number(person.props.get("family.marital_review_year"), 0)) return;
        
        int affinity = Math.min(ResidentSocial.score(person, spouse.globalId), ResidentSocial.score(spouse, person.globalId));
        
        person.props.put("family.marital_review_year", year);
        spouse.props.put("family.marital_review_year", year);
        int conflictYears = number(person.props.get("family.marital_conflict_years"), 0);
        conflictYears = affinity <= -55 ? conflictYears + 1 : Math.max(0, conflictYears - 1);
        person.props.put("family.marital_conflict_years", conflictYears);
        spouse.props.put("family.marital_conflict_years", conflictYears);
        person.props.put("family.marital_affinity", affinity);
        spouse.props.put("family.marital_affinity", affinity);
        if (conflictYears >= 3 && registry.divorce(person.id, spouse.id, year)) {
            person.props.put("family.last_event", "divorced:" + spouse.id);
            spouse.props.put("family.last_event", "divorced:" + person.id);
            person.props.put("family.separation_year", year);
            spouse.props.put("family.separation_year", year);
            long minute = Math.max(0L, year * 525600L);
            CharacterDevelopment.recordExperience(person, CharacterDevelopment.Experience.DIVORCE,
                    "family:divorce:" + person.globalId + ":" + spouse.globalId, "marriage ended with " + spouse.name, minute, 2);
            CharacterDevelopment.recordExperience(spouse, CharacterDevelopment.Experience.DIVORCE,
                    "family:divorce:" + spouse.globalId + ":" + person.globalId, "marriage ended with " + person.name, minute, 2);
            CharacterDevelopment.remember(person, "family:divorce-memory:" + person.globalId + ":" + spouse.globalId,
                    "divorce", spouse.globalId, "marriage ended after prolonged conflict", -65, minute);
            CharacterDevelopment.remember(spouse, "family:divorce-memory:" + spouse.globalId + ":" + person.globalId,
                    "divorce", person.globalId, "marriage ended after prolonged conflict", -65, minute);
        }
    }

    private static void processRelationshipReviewById(CharacterRegistry registry, List<String> eligibleIds,
                                                       int index, int year) {
        String firstId = eligibleIds.get(index);
        if (firstId == null || firstId.isBlank()) return;
        CharacterRegistry firstOwner = PeopleMod.authority().registryForGlobal(firstId);
        if (firstOwner == null) return;
        try (CharacterAccessService.Lease lease = firstOwner.access().acquireGlobal(firstId)) {
            if (lease == null) return;
            Character first = lease.get();
            if (year <= number(first.props.get("family.relationship_review_year"), 0)) return;
            firstOwner.access().mutateGlobal(firstId,
                    current -> current.props.put("family.relationship_review_year", year));
        }

        int available = eligibleIds.size() - index - 1;
        if (available <= 0) return;
        int checks = Math.min(available, MAX_RELATIONSHIP_CANDIDATES_PER_PERSON_PER_YEAR);
        int startOffset = Math.floorMod(Objects.hash(firstId, year, "relationship-review"), available);
        for (int probe = 0; probe < checks; probe++) {
            String secondId = eligibleIds.get(index + 1 + ((startOffset + probe) % available));
            if (secondId == null || secondId.isBlank() || firstId.equals(secondId)) continue;
            CharacterAccessService.mutateGlobalsIfChanged(PeopleMod.authority(), List.of(firstId, secondId), people -> {
                Character first = people.get(firstId), second = people.get(secondId);
                return reviewRelationshipPair(registry, first, second, year);
            });
        }
    }

    private static boolean reviewRelationshipPair(CharacterRegistry registry, Character first,
                                                  Character second, int year) {
        if (!first.alive || !second.alive || (second.spouse != null && !second.spouse.isBlank())
                || closeFamily(registry, first, second)) return false;
        int affinity = Math.min(ResidentSocial.score(first, second.globalId),
                ResidentSocial.score(second, first.globalId));
        String firstDating = string(first.props.get("family.dating"));
        String secondDating = string(second.props.get("family.dating"));
        boolean mutualDating = second.id.equals(firstDating) && first.id.equals(secondDating);
        if (mutualDating && affinity < 20) {
            FamilyPlanning.cancelEngagement(first, second, year, "relationship-ended");
            first.props.remove("family.dating"); second.props.remove("family.dating");
            first.props.remove("family.dating_name"); second.props.remove("family.dating_name");
            first.props.put("family.last_event", "separated:" + second.id);
            second.props.put("family.last_event", "separated:" + first.id);
            long minute = Math.max(0L, year * 525600L);
            CharacterDevelopment.recordExperience(first, CharacterDevelopment.Experience.SOCIAL_REJECTION,
                    "family:dating-separation:" + first.globalId + ":" + second.globalId,
                    "relationship ended with " + second.name, minute, 1);
            CharacterDevelopment.recordExperience(second, CharacterDevelopment.Experience.SOCIAL_REJECTION,
                    "family:dating-separation:" + second.globalId + ":" + first.globalId,
                    "relationship ended with " + first.name, minute, 1);
            CharacterDevelopment.remember(first, "family:dating-separation-memory:" + first.globalId + ":" + second.globalId,
                    "relationship_separation", second.globalId, "mutual relationship ended", -35, minute);
            CharacterDevelopment.remember(second, "family:dating-separation-memory:" + second.globalId + ":" + first.globalId,
                    "relationship_separation", first.globalId, "mutual relationship ended", -35, minute);
            return true;
        }
        if ((!firstDating.isBlank() && !firstDating.equals(second.id))
                || (!secondDating.isBlank() && !secondDating.equals(first.id))) return false;
        boolean changed = false;
        if (!mutualDating && affinity >= 50
                && Math.floorMod(Objects.hash(first.id, second.id, year, "dating"), 3) == 0) {
            first.props.put("family.dating", second.id); second.props.put("family.dating", first.id);
            first.props.put("family.dating_name", second.name); second.props.put("family.dating_name", first.name);
            first.props.put("family.last_event", "dating:" + second.id);
            second.props.put("family.last_event", "dating:" + first.id);
            mutualDating = true;
            changed = true;
        }
        if (mutualDating && affinity >= 65 && !FamilyPlanning.engagedTo(first, second)
                && Math.floorMod(Objects.hash(first.id, second.id, year, "engagement"), 3) == 0) {
            FamilyPlanning.engage(first, second, year);
            changed = true;
        }
        int engagementYear = number(first.props.get("family.engagement_year"), year);
        if (!Objects.equals(first.gender, second.gender) && affinity >= 75
                && FamilyPlanning.engagedTo(first, second) && year - engagementYear >= 1
                && Math.floorMod(Objects.hash(first.id, second.id, year, "marriage"), 4) == 0) {
            registry.marry(first.id, second.id, year);
            FamilyPlanning.completeMarriage(first, second);
            first.props.remove("family.dating"); second.props.remove("family.dating");
            first.props.remove("family.dating_name"); second.props.remove("family.dating_name");
            first.props.put("family.spouse_name", second.name); second.props.put("family.spouse_name", first.name);
            first.props.put("family.last_event", "married:" + second.id);
            second.props.put("family.last_event", "married:" + first.id);
            long minute = Math.max(0L, year * 525600L);
            CharacterDevelopment.recordExperience(first, CharacterDevelopment.Experience.MARRIAGE,
                    "family:marriage:" + first.globalId + ":" + second.globalId, "married " + second.name, minute, 1);
            CharacterDevelopment.recordExperience(second, CharacterDevelopment.Experience.MARRIAGE,
                    "family:marriage:" + second.globalId + ":" + first.globalId, "married " + first.name, minute, 1);
            CharacterDevelopment.remember(first, "family:marriage-memory:" + first.globalId + ":" + second.globalId,
                    "marriage", second.globalId, "married " + second.name, 70, minute);
            CharacterDevelopment.remember(second, "family:marriage-memory:" + second.globalId + ":" + first.globalId,
                    "marriage", first.globalId, "married " + first.name, 70, minute);
            changed = true;
        }
        return changed;
    }

    private static void advanceChildhoodPerson(CharacterRegistry registry, Character child, int year) {
        if (!child.alive || year <= number(child.props.get("family.development_year"), 0)) return;
        int age = PopulationSimulation.age(child, year);
        if (age >= 18) {
            child.props.put("family.development_year", year);
            child.props.put("family.adulthood_year", year);
            child.props.put("family.care_status", "independent_adult");
            child.props.remove("family.guardian_id");
            return;
        }
        Character father = child.father.isBlank() ? null : registry.get(child.father);
        Character mother = child.mother.isBlank() ? null : registry.get(child.mother);
        boolean fatherAlive = father != null && father.alive;
        boolean motherAlive = mother != null && mother.alive;
        Character guardian = null;
        if (fatherAlive && motherAlive)
            guardian = ResidentSocial.score(child, father.globalId) >= ResidentSocial.score(child, mother.globalId) ? father : mother;
        else if (fatherAlive) guardian = father;
        else if (motherAlive) guardian = mother;
        else guardian = findKinGuardian(registry, child, year);
        child.props.put("family.guardian_id", guardian == null ? "" : guardian.id);
        child.props.put("family.care_status", guardian == null ? "orphaned" :
                fatherAlive && motherAlive ? "two_parent_home" :
                fatherAlive || motherAlive ? "single_parent_home" : "kin_guardianship");
        CharacterDevelopment.upbringingTick(child, father, mother, guardian, year);
        child.props.put("family.development_year", year);
        child.props.put("family.age_stage", PopulationSimulation.lifeStage(child, year).name().toLowerCase(Locale.ROOT));
        if (age >= 6 && age < 18) {
            int education = number(child.props.get("family.education_level"), 0);
            int progress = guardian == null ? 1 : 2;
            if (guardian != null && number(guardian.props.get("family.education_level"), 0) >= 5) progress++;
            int nextEducation = Math.min(100, education + progress);
            child.props.put("family.education_level", nextEducation);
            child.props.put("family.education_last_year", year);
            int milestone = nextEducation / 25;
            int priorMilestone = number(child.props.get("family.education.psychology_milestone"), 0);
            if (milestone > priorMilestone) {
                child.props.put("family.education.psychology_milestone", milestone);
                CharacterDevelopment.recordExperience(child, CharacterDevelopment.Experience.EDUCATION,
                        "family:education-milestone:" + child.globalId + ":" + milestone,
                        "reached education milestone " + (milestone * 25), Math.max(0L, year * 525600L), 1);
            }
            if (guardian != null) {
                ResidentSocial.adjust(child, guardian.globalId, 1);
                ResidentSocial.adjust(guardian, child.globalId, 1);
                if (age >= 10) {
                    int annualXp = age >= 13 ? 10 : 5;
                    PopulationSimulation.skillRanksSnapshot(guardian).entrySet().stream()
                            .filter(entry -> entry.getValue() > 0)
                            .sorted(Map.Entry.comparingByKey()).limit(5)
                            .forEach(entry -> {
                                int childRank = PopulationSimulation.skillRank(child, entry.getKey());
                                if (childRank < Math.min(3, entry.getValue()))
                                    PopulationSimulation.train(child, entry.getKey(), annualXp);
                            });
                }
            }
        }
        if (age == 6) child.props.put("family.school_started_year", year);
        if (age == 13) child.props.put("family.adolescence_started_year", year);
        if (age == 18) child.props.put("family.adulthood_year", year);
    }

    private static Character findKinGuardian(CharacterRegistry registry, Character child, int year) {
        Set<String> candidateIds = new HashSet<>();
        addGrandparents(registry, child.father, candidateIds);
        addGrandparents(registry, child.mother, candidateIds);
        addParentSiblings(registry, child.father, candidateIds);
        addParentSiblings(registry, child.mother, candidateIds);
        return candidateIds.stream().map(registry::get).filter(Objects::nonNull)
                .filter(candidate -> candidate.alive && PopulationSimulation.age(candidate, year) >= 18)
                .min(Comparator.comparingInt(candidate -> Math.abs(PopulationSimulation.age(candidate, year) - 35)))
                .orElse(null);
    }

    private static void addGrandparents(CharacterRegistry registry, String parentId, Set<String> candidates) {
        if (parentId == null || parentId.isBlank()) return;
        Character parent = registry.get(parentId);
        if (parent == null) return;
        if (!parent.father.isBlank()) candidates.add(parent.father);
        if (!parent.mother.isBlank()) candidates.add(parent.mother);
    }

    private static void addParentSiblings(CharacterRegistry registry, String parentId, Set<String> candidates) {
        if (parentId == null || parentId.isBlank()) return;
        Character parent = registry.get(parentId);
        if (parent == null) return;
        // Parent links already own their canonical child lists. Walk only the two
        // grandparent households instead of building a population-sized reverse index each year.
        addChildrenOf(registry, parent.father, parentId, candidates);
        addChildrenOf(registry, parent.mother, parentId, candidates);
    }

    private static void addChildrenOf(CharacterRegistry registry, String grandparentId,
                                      String parentId, Set<String> candidates) {
        if (grandparentId == null || grandparentId.isBlank()) return;
        Character grandparent = registry.get(grandparentId);
        if (grandparent == null) return;
        for (String siblingId : grandparent.children)
            if (siblingId != null && !siblingId.equals(parentId)) candidates.add(siblingId);
    }

    private static boolean closeFamily(CharacterRegistry registry, Character a, Character b) {
        if (a.id.equals(b.id)) return true;
        Set<String> aAncestors = ancestors(registry, a, 2);
        Set<String> bAncestors = ancestors(registry, b, 2);
        return aAncestors.contains(b.id) || bAncestors.contains(a.id)
                || aAncestors.stream().anyMatch(bAncestors::contains);
    }

    private static Set<String> ancestors(CharacterRegistry registry, Character person, int depth) {
        Set<String> result = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(person.father); frontier.add(person.mother);
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            Set<String> next = new HashSet<>();
            for (String id : frontier) {
                if (id == null || id.isBlank() || !result.add(id)) continue;
                Character ancestor = registry.get(id);
                if (ancestor != null) { next.add(ancestor.father); next.add(ancestor.mother); }
            }
            frontier = next;
        }
        return result;
    }

    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }

}
