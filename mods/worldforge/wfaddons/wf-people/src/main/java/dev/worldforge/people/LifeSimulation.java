package dev.worldforge.people;

import java.util.*;

/**
 * Deterministic life-AI kernel shared by every Character-backed population adapter.
 * Utility scoring selects goals, a small behavior-tree selector handles emergencies,
 * and a bounded GOAP planner orders prerequisite actions. The environment owns effects.
 */
public final class LifeSimulation {
    public enum Action { EAT, SLEEP, SEEK_SAFETY, SOCIALIZE, WORK, TRAIN, RECREATE, IDLE }
    public enum Outcome { SUCCESS, BLOCKED, RETRY }
    public enum LOD { ACTIVE, SETTLEMENT, REGIONAL, REMOTE }

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
        Action selected = emergency != Action.IDLE ? emergency : chooseUtility(character, context, operators, plan);
        if (selected == Action.IDLE) {
            character.props.put("population.ai.last_action", "idle");
            return new Decision(Action.IDLE, Outcome.SUCCESS, 0, "no-applicable-goal", plan, now + interval);
        }

        Outcome outcome = executor.execute(character, selected, context);
        if (outcome == null) outcome = Outcome.BLOCKED;
        character.props.put("population.ai.last_action", selected.name().toLowerCase(Locale.ROOT));
        character.props.put("population.ai.last_outcome", outcome.name().toLowerCase(Locale.ROOT));
        character.props.put("population.ai.last_reason", reason(selected, context));
        if (outcome == Outcome.SUCCESS) applyEffects(character, selected);
        return new Decision(selected, outcome, utility(character, context, selected), reason(selected, context), plan, now + interval);
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
        return ops.stream().filter(o -> o.available() && o.goal() == target)
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
        return value;
    }

    private static void applyEffects(Character c, Action action) {
        switch (action) {
            case EAT -> PopulationSimulation.changeNeed(c, PopulationSimulation.Need.HUNGER, -70);
            case SLEEP -> PopulationSimulation.changeNeed(c, PopulationSimulation.Need.REST, -75);
            case SEEK_SAFETY -> PopulationSimulation.changeNeed(c, PopulationSimulation.Need.SAFETY, -60);
            case SOCIALIZE -> PopulationSimulation.changeNeed(c, PopulationSimulation.Need.SOCIAL, -60);
            case WORK -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.PURPOSE, -20);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.REST, 8);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.HUNGER, 6);
            }
            case TRAIN -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.PURPOSE, -16);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.REST, 5);
                String skill = PopulationSimulation.trainingTarget(c);
                if (!skill.isBlank()) PopulationSimulation.train(c, skill, 5);
            }
            case RECREATE -> {
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.LEISURE, -65);
                PopulationSimulation.changeNeed(c, PopulationSimulation.Need.SOCIAL, -10);
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
