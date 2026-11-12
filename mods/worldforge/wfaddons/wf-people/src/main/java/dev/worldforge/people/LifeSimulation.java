package dev.worldforge.people;

import java.util.*;

/**
 * Deterministic life-AI kernel shared by every Character-backed population adapter.
 * Utility scoring selects goals, a small behavior-tree selector handles emergencies,
 * and a bounded GOAP planner orders prerequisite actions. The environment owns effects.
 */
public final class LifeSimulation {
    public enum Action { EAT, SLEEP, SEEK_SAFETY, SOCIALIZE, WORK, TRAIN, RECREATE, IDLE }
    public enum Outcome { SUCCESS, IN_PROGRESS, BLOCKED, RETRY }
    public enum LOD { ACTIVE, SETTLEMENT, REGIONAL, REMOTE }

    /**
     * Confirms that a persisted settlement-side physical action represents the Character AI action
     * currently being completed. A mismatch must fail closed: never grant sleep/food/social effects
     * for a different action merely because some worker animation is still in progress.
     */
    public static boolean matchesPhysicalAction(Action action, String workerAction) {
        if (action == null || workerAction == null) return false;
        return switch (action) {
            case EAT -> "eating".equals(workerAction);
            case SLEEP -> "sleeping".equals(workerAction);
            case SOCIALIZE -> "socializing".equals(workerAction);
            case SEEK_SAFETY -> "fleeing".equals(workerAction);
            case WORK, TRAIN, RECREATE, IDLE -> false;
        };
    }

    public record Context(long worldMinute, boolean foodAvailable, boolean shelterAvailable,
                          boolean safe, boolean socialAvailable, boolean workAvailable,
                          boolean trainingAvailable, boolean recreationAvailable,
                          boolean urgentWorldEvent, LOD lod) {
        public Context {
            if (worldMinute < 0) throw new IllegalArgumentException("worldMinute cannot be negative");
            lod = lod == null ? LOD.SETTLEMENT : lod;
        }
        public int minuteOfDay() { return (int) (worldMinute % 1440); }
    }

    @FunctionalInterface
    public interface Executor {
        Outcome execute(Character character, Action action, Context context);

        /** Adapters can report the concrete obstruction (missing tool, path, material, target or permission). */
        default String failureReason(Character character, Action action, Context context, Outcome outcome) {
            return outcome == Outcome.RETRY ? "executor-requested-retry" : "executor-blocked-without-detail";
        }
    }

    public record Decision(Action action, Outcome outcome, double utility, String reason,
                           List<Action> plan, long nextThinkMinute) { }

    private record Operator(Action action, PopulationSimulation.Need goal,
                            int effect, boolean available, int cost) { }

    private LifeSimulation() { }

    /** Runs one scheduled decision. No need is satisfied unless the authoritative adapter succeeds. */
    public static Decision tick(Character character, Context context, Executor executor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(executor, "executor");
        if (character == null || !character.alive)
            return new Decision(Action.IDLE, Outcome.BLOCKED, 0, "dead-or-missing", List.of(), context.worldMinute());

        long now = context.worldMinute();
        Object pendingRaw = character.props.get("population.ai.pending_action");
        if (pendingRaw instanceof String pendingName && !pendingName.isBlank()) {
            try {
                Action pending = Action.valueOf(pendingName);
                Outcome pendingOutcome = executor.execute(character, pending, context);
                if (pendingOutcome == Outcome.SUCCESS) {
                    completePending(character);
                    character.props.put("population.ai.next_think_minute", now + 1);
                    return new Decision(pending, Outcome.SUCCESS, utility(character, context, pending),
                            "authoritative-action-completed", List.of(pending), now + 1);
                }
                if (pendingOutcome == Outcome.BLOCKED || pendingOutcome == Outcome.RETRY) {
                    cancelPending(character, executor.failureReason(character, pending, context, pendingOutcome));
                    return new Decision(pending, pendingOutcome, utility(character, context, pending),
                            "pending-action-interrupted", List.of(pending), now + 1);
                }
                long pendingMinute = number(character.props.get("population.ai.pending_minute"), now);
                return new Decision(pending, Outcome.IN_PROGRESS, utility(character, context, pending),
                        "action-in-progress", List.of(pending), pendingMinute + 1);
            } catch (IllegalArgumentException invalidPending) {
                character.props.remove("population.ai.pending_action");
                character.props.remove("population.ai.pending_minute");
                character.props.remove("population.ai.pending_token");
            }
        }
        long next = number(character.props.get("population.ai.next_think_minute"), Long.MIN_VALUE);
        if (now < next) return new Decision(Action.IDLE, Outcome.RETRY, 0, "thinking-cooldown", List.of(), next);

        long interval = switch (context.lod()) {
            case ACTIVE -> 1;
            case SETTLEMENT -> 5;
            case REGIONAL -> 60;
            case REMOTE -> 360;
        };
        character.props.put("population.ai.next_think_minute", now + interval);
        Action emergency = behaviorTreeEmergency(character, context);
        List<Operator> operators = operators(character, context);
        List<Action> plan = goapPlan(character, operators);
        Map<String,Long> cooldowns = failureCooldowns(character);
        List<Action> candidates = new ArrayList<>();
        if (emergency != Action.IDLE) candidates.add(emergency);
        operators.stream().filter(Operator::available)
                .map(Operator::action).distinct()
                .filter(action -> !isCoolingDown(cooldowns, action, now))
                .sorted(Comparator.comparingDouble((Action action) -> utility(character, context, action)
                        + (plan.contains(action) ? 8 : 0)).reversed())
                .forEach(candidates::add);
        candidates = new ArrayList<>(new LinkedHashSet<>(candidates));
        if (candidates.isEmpty()) {
            character.props.put("population.ai.last_action", "idle");
            character.props.put("population.ai.last_outcome", "success");
            character.props.put("population.ai.last_reason", "no-applicable-goal");
            return new Decision(Action.IDLE, Outcome.SUCCESS, 0, "no-applicable-goal", plan, now + interval);
        }

        Action lastAction = Action.IDLE;
        Outcome lastOutcome = Outcome.BLOCKED;
        String lastReason = "all-candidates-blocked";
        int attempts = 0;
        for (Action candidate : candidates) {
            if (attempts++ >= 3) break; // bounded fallback: never loop through the whole action space
            lastAction = candidate;
            lastOutcome = executor.execute(character, candidate, context);
            if (lastOutcome == null) lastOutcome = Outcome.BLOCKED;
            if (lastOutcome == Outcome.IN_PROGRESS) {
                String token = candidate.name() + ":" + now;
                character.props.put("population.ai.pending_action", candidate.name());
                character.props.put("population.ai.pending_minute", now);
                character.props.put("population.ai.pending_token", token);
                character.props.put("population.ai.last_action", candidate.name().toLowerCase(Locale.ROOT));
                character.props.put("population.ai.last_outcome", "in_progress");
                character.props.put("population.ai.last_reason", "awaiting-authoritative-completion");
                return new Decision(candidate, Outcome.IN_PROGRESS, utility(character, context, candidate),
                        "awaiting-authoritative-completion", plan, now + interval);
            }
            if (lastOutcome == Outcome.SUCCESS) {
                applyEffects(character, candidate);
                character.props.put("population.ai.last_action", candidate.name().toLowerCase(Locale.ROOT));
                character.props.put("population.ai.last_outcome", "success");
                character.props.put("population.ai.last_reason", reason(candidate, context));
                character.props.put("population.ai.last_attempts", attempts);
                return new Decision(candidate, Outcome.SUCCESS, utility(character, context, candidate),
                        reason(candidate, context), plan, now + interval);
            }
            lastReason = safeReason(executor.failureReason(character, candidate, context, lastOutcome));
            recordFailure(character, candidate, lastOutcome, lastReason, now);
            cooldowns.put(candidate.name(), now + Math.max(5, interval * 3));
            character.props.put("population.ai.failure_cooldowns", new LinkedHashMap<>(cooldowns));
        }
        character.props.put("population.ai.last_action", lastAction.name().toLowerCase(Locale.ROOT));
        character.props.put("population.ai.last_outcome", lastOutcome.name().toLowerCase(Locale.ROOT));
        character.props.put("population.ai.last_reason", lastReason);
        character.props.put("population.ai.last_attempts", attempts);
        return new Decision(lastAction, lastOutcome, utility(character, context, lastAction),
                "fallback-exhausted:" + lastReason, plan, now + interval);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Long> failureCooldowns(Character character) {
        Map<String,Long> result = new LinkedHashMap<>();
        Object raw = character.props.get("population.ai.failure_cooldowns");
        if (raw instanceof Map<?,?> map) for (Map.Entry<?,?> entry : map.entrySet())
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number n)
                result.put(key, n.longValue());
        return result;
    }

    private static boolean isCoolingDown(Map<String,Long> cooldowns, Action action, long now) {
        return cooldowns.getOrDefault(action.name(), Long.MIN_VALUE) > now;
    }

    @SuppressWarnings("unchecked")
    private static void recordFailure(Character character, Action action, Outcome outcome, String reason, long minute) {
        List<Object> history;
        Object raw = character.props.get("population.ai.failure_history");
        if (raw instanceof List<?> list) history = (List<Object>) list;
        else { history = new ArrayList<>(); character.props.put("population.ai.failure_history", history); }
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("minute", minute);
        entry.put("action", action.name());
        entry.put("outcome", outcome.name());
        entry.put("reason", reason);
        history.add(entry);
        while (history.size() > 32) history.remove(0);
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) return "executor-reported-no-reason";
        return reason.length() > 256 ? reason.substring(0,256) : reason;
    }

    /** Behavior-tree emergency selector: critical survival branches always precede ordinary goals. */
    private static Action behaviorTreeEmergency(Character c, Context x) {
        if (PopulationSimulation.need(c, PopulationSimulation.Need.HUNGER) >= 85 && x.foodAvailable()) return Action.EAT;
        if (PopulationSimulation.need(c, PopulationSimulation.Need.SAFETY) >= 85 && !x.safe() && x.shelterAvailable()) return Action.SEEK_SAFETY;
        if (PopulationSimulation.need(c, PopulationSimulation.Need.REST) >= 90 && x.shelterAvailable()) return Action.SLEEP;
        return Action.IDLE;
    }

    /** GOAP: bounded one-goal plan with explicit availability preconditions and need-reducing effects. */
    public static List<Action> goapPlan(Character c, Context x) {
        return goapPlan(c, operators(c, x));
    }

    private static List<Action> goapPlan(Character c, List<Operator> ops) {
        PopulationSimulation.Need target = null;
        int highest = 0;
        for (PopulationSimulation.Need need : PopulationSimulation.Need.values()) {
            int deficit = PopulationSimulation.need(c, need);
            if (deficit > highest) { highest = deficit; target = need; }
        }
        if (target == null || highest < 20) return List.of();
        final PopulationSimulation.Need goal = target;
        return ops.stream().filter(o -> o.available() && o.goal() == goal)
                .sorted(Comparator.comparingInt(Operator::cost))
                .map(Operator::action).limit(2).toList();
    }

    private static List<Operator> operators(Character c, Context x) {
        return List.of(
                new Operator(Action.EAT, PopulationSimulation.Need.HUNGER, 70, x.foodAvailable(), 1),
                new Operator(Action.SLEEP, PopulationSimulation.Need.REST, 75, x.shelterAvailable(), 1),
                new Operator(Action.SEEK_SAFETY, PopulationSimulation.Need.SAFETY, 60, x.shelterAvailable(), 2),
                new Operator(Action.SOCIALIZE, PopulationSimulation.Need.SOCIAL, 60, x.socialAvailable(), 2),
                new Operator(Action.WORK, PopulationSimulation.Need.PURPOSE, 20, x.workAvailable(), 1),
                new Operator(Action.TRAIN, PopulationSimulation.Need.PURPOSE, 16, x.trainingAvailable(), 3),
                new Operator(Action.RECREATE, PopulationSimulation.Need.LEISURE, 65, x.recreationAvailable(), 2));
    }

    private static Action chooseUtility(Character c, Context x, List<Operator> ops, List<Action> plan) {
        Action best = Action.IDLE;
        double score = 0;
        for (Operator op : ops) {
            if (!op.available()) continue;
            double candidate = utility(c, x, op.action());
            if (plan.contains(op.action())) candidate += 8;
            if (candidate > score) { score = candidate; best = op.action(); }
        }
        return best;
    }

    public static double utility(Character c, Context x, Action action) {
        PopulationSimulation.Need need = switch (action) {
            case EAT -> PopulationSimulation.Need.HUNGER;
            case SLEEP -> PopulationSimulation.Need.REST;
            case SEEK_SAFETY -> PopulationSimulation.Need.SAFETY;
            case SOCIALIZE -> PopulationSimulation.Need.SOCIAL;
            case WORK, TRAIN -> PopulationSimulation.Need.PURPOSE;
            case RECREATE -> PopulationSimulation.Need.LEISURE;
            case IDLE -> null;
        };
        if (need == null) return 0;
        double value = PopulationSimulation.need(c, need) * 1.0;
        int minute = x.minuteOfDay();
        if (action == Action.SLEEP && (minute >= 1320 || minute < 360)) value += 28;
        if (action == Action.WORK && minute >= 480 && minute < 1020) value += 24;
        if (action == Action.TRAIN && minute >= 1020 && minute < 1140) value += 18;
        if (action == Action.EAT && ((minute >= 360 && minute < 480) || (minute >= 690 && minute < 810) || (minute >= 1080 && minute < 1200))) value += 22;
        if (action == Action.SOCIALIZE && minute >= 1140 && minute < 1320) value += 20;
        if (action == Action.RECREATE && minute >= 1140 && minute < 1320) value += 24;
        if (x.urgentWorldEvent() && action == Action.SEEK_SAFETY) value += 45;
        if (action == Action.SEEK_SAFETY && !x.safe()) value += 20;

        // Stable character psychology shapes priorities; it never overrides survival preconditions.
        int discipline = CharacterDevelopment.temperament(c, CharacterDevelopment.Temperament.DISCIPLINE);
        int sociability = CharacterDevelopment.temperament(c, CharacterDevelopment.Temperament.SOCIABILITY);
        int curiosity = CharacterDevelopment.temperament(c, CharacterDevelopment.Temperament.CURIOSITY);
        int risk = CharacterDevelopment.temperament(c, CharacterDevelopment.Temperament.RISK);
        int stability = CharacterDevelopment.temperament(c, CharacterDevelopment.Temperament.STABILITY);
        int stress = CharacterDevelopment.stress(c);
        switch (action) {
            case WORK -> value += discipline * 0.16
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.DUTY) * 0.12
                    - stress * 0.10;
            case TRAIN -> value += curiosity * 0.18
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.KNOWLEDGE) * 0.16
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.PRESTIGE) * 0.05
                    - stress * 0.06;
            case SOCIALIZE -> value += sociability * 0.18
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.COMMUNITY) * 0.14
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.FAMILY) * 0.04;
            case RECREATE -> value += (100 - stability) * 0.08 + stress * 0.18
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.ADVENTURE) * 0.06;
            case SLEEP -> value += stress * 0.10 + (100 - stability) * 0.04;
            case SEEK_SAFETY -> value += (100 - risk) * 0.12
                    + CharacterDevelopment.drive(c, CharacterDevelopment.Drive.SECURITY) * 0.10;
            case EAT -> value += CharacterDevelopment.drive(c, CharacterDevelopment.Drive.SECURITY) * 0.02;
            case IDLE -> { }
        }

        // Temporary moods modify decisions, but expire independently from permanent traits.
        CharacterDevelopment.Mood mood = CharacterDevelopment.mood(c, x.worldMinute());
        value += switch (mood) {
            case ANGRY -> action == Action.SOCIALIZE ? -12 : action == Action.WORK ? 3 : 0;
            case AFRAID -> action == Action.SEEK_SAFETY ? 18 : action == Action.WORK ? -8 : 0;
            case STRESSED -> action == Action.SLEEP || action == Action.RECREATE ? 10 : action == Action.WORK ? -8 : 0;
            case EXHAUSTED -> action == Action.SLEEP ? 18 : action == Action.WORK || action == Action.TRAIN ? -15 : 0;
            case SAD -> action == Action.SOCIALIZE ? 5 : action == Action.RECREATE ? 4 : 0;
            case INSPIRED -> action == Action.TRAIN ? 12 : action == Action.WORK ? 5 : 0;
            case HAPPY, NEUTRAL -> 0;
        };
        return value;
    }

    /**
     * Completes the pending action only after its authoritative adapter confirms completion.
     * The token makes replay after save/load idempotent for the same in-flight action.
     */
    public static boolean completePending(Character character) {
        if (character == null || !character.alive) return false;
        Object rawAction = character.props.get("population.ai.pending_action");
        Object rawToken = character.props.get("population.ai.pending_token");
        if (!(rawAction instanceof String actionName) || !(rawToken instanceof String token)
                || actionName.isBlank() || token.isBlank()) return false;
        if (token.equals(character.props.get("population.ai.last_completed_token"))) {
            clearPending(character);
            return false;
        }
        final Action action;
        try { action = Action.valueOf(actionName); }
        catch (IllegalArgumentException invalid) { clearPending(character); return false; }
        applyEffects(character, action);
        character.props.put("population.ai.last_completed_token", token);
        character.props.put("population.ai.last_action", action.name().toLowerCase(Locale.ROOT));
        character.props.put("population.ai.last_outcome", "success");
        character.props.put("population.ai.last_reason", "authoritative-action-completed");
        clearPending(character);
        return true;
    }

    /** Cancels an interrupted action without granting its need, skill or relationship effects. */
    public static boolean cancelPending(Character character, String reason) {
        if (character == null || !(character.props.get("population.ai.pending_action") instanceof String)) return false;
        character.props.put("population.ai.last_outcome", "blocked");
        character.props.put("population.ai.last_reason", safeReason(reason));
        clearPending(character);
        return true;
    }

    private static void clearPending(Character character) {
        character.props.remove("population.ai.pending_action");
        character.props.remove("population.ai.pending_minute");
        character.props.remove("population.ai.pending_token");
    }

    private static void applyEffects(Character c, Action action) {
        switch (action) {
            case EAT -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.HUNGER, -70);
                CharacterDevelopment.changeStress(c, -2);
            }
            case SLEEP -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.REST, -75);
                CharacterDevelopment.changeStress(c, -14);
            }
            case SEEK_SAFETY -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.SAFETY, -60);
                CharacterDevelopment.changeStress(c, -8);
            }
            case SOCIALIZE -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.SOCIAL, -60);
                CharacterDevelopment.changeStress(c, -5);
            }
            case WORK -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.PURPOSE, -20);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.REST, 8);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.HUNGER, 6);
                CharacterDevelopment.changeStress(c, 3);
            }
            case TRAIN -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.PURPOSE, -16);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.REST, 5);
                CharacterDevelopment.changeStress(c, 1);
                String skill = PopulationSimulation.trainingTarget(c);
                if (!skill.isBlank()) PopulationSimulation.train(c, skill, 5);
            }
            case RECREATE -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.LEISURE, -65);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.SOCIAL, -10);
                CharacterDevelopment.changeStress(c, -12);
            }
            case IDLE -> { }
        }
    }

    private static String reason(Action a, Context x) {
        return switch (a) {
            case EAT -> "hunger-and-food";
            case SLEEP -> "rest-and-shelter";
            case SEEK_SAFETY -> x.urgentWorldEvent() ? "world-event-threat" : "safety-deficit";
            case SOCIALIZE -> "social-need-and-nearby-people";
            case WORK -> "scheduled-work";
            case TRAIN -> "training-goal";
            case RECREATE -> "leisure-schedule";
            case IDLE -> "no-applicable-goal";
        };
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }
}
