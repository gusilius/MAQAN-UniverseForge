package dev.worldforge.people.mc.client;

import java.util.Locale;
import java.util.Optional;

/**
 * Semantic clip resolver and deterministic playback clock for decoded NPC models.
 * The returned pose is CPU data; renderers consume it on the render thread.
 */
public final class NpcAnimationController {
    private NpcAnimationController() {}

    public record Playback(String state, long startedAtTick, boolean loop) {
        public Playback {
            state = state == null ? "idle" : state.trim().toLowerCase(Locale.ROOT);
        }

        public Optional<GlbAnimationSampler.Pose> pose(
                NpcModelCatalog.Definition definition,
                GlbModelLoader.Model model,
                long gameTick,
                float ticksPerSecond
        ) {
            if (definition == null || model == null || !Float.isFinite(ticksPerSecond) || ticksPerSecond <= 0)
                return Optional.empty();
            String clip = resolveClip(definition, model, state);
            if (clip == null) return Optional.empty();
            long elapsedTicks = Math.max(0, gameTick - startedAtTick);
            float elapsedSeconds = elapsedTicks / ticksPerSecond;
            return Optional.of(GlbAnimationSampler.sample(model, clip, elapsedSeconds, loop));
        }
    }

    public static String resolveClip(NpcModelCatalog.Definition definition,
                                     GlbModelLoader.Model model, String semanticState) {
        if (definition == null || model == null) return null;
        String state = semanticState == null ? "idle" : semanticState.trim().toLowerCase(Locale.ROOT);
        String explicit = definition.animations().get(state);
        if (explicit != null && hasClip(model, explicit)) return explicit;
        if (hasClip(model, state)) return state;
        String[] aliases = switch (state) {
            case "idle", "stand" -> new String[]{"idle", "stand", "stand_1", "stand_2", "stand_3", "rest"};
            case "walk", "move" -> new String[]{"walk", "walk_1", "walk_lumber_1", "walk_gold_1", "run"};
            case "attack", "combat" -> new String[]{"attack", "attack_1", "attack_2", "combat"};
            case "work", "gather" -> new String[]{"work", "stand_work_1", "stand_work_gold_1", "stand_work_lumber_1"};
            case "death", "dead" -> new String[]{"death", "death_1"};
            case "talk", "dialogue" -> new String[]{"talk", "stand_talk_1"};
            default -> new String[]{state};
        };
        for (String alias : aliases) if (hasClip(model, alias)) return alias;
        return null;
    }

    private static boolean hasClip(GlbModelLoader.Model model, String clip) {
        return clip != null && model.animations().stream().anyMatch(a -> a.name().equals(clip));
    }
}
