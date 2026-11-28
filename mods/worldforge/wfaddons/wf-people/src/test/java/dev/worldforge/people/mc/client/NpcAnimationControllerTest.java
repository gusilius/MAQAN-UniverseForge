package dev.worldforge.people.mc.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NpcAnimationControllerTest {
    @Test void resolvesManifestAliasBeforeBuiltInSemanticAliases() {
        var definition = new NpcModelCatalog.Definition(
                new ResourceLocation("maqan", "worker"),
                new ResourceLocation("maqan", "models/worker.glb"), null, 1,
                Map.of("idle", "rest_custom"));
        var rest = new GlbModelLoader.Animation("rest_custom", List.of(
                new GlbModelLoader.AnimationSampler(new float[]{0, 1},
                        new float[]{0,0,0, 1,0,0}, "LINEAR", 3)), List.of());
        var stand = new GlbModelLoader.Animation("stand_1", List.of(
                new GlbModelLoader.AnimationSampler(new float[]{0, 1},
                        new float[]{0,0,0, 0,0,0}, "LINEAR", 3)), List.of());
        var model = new GlbModelLoader.Model(List.of(), List.of(), List.of(), List.of(),
                List.of(rest, stand), 1, 0, 0);
        assertEquals("rest_custom", NpcAnimationController.resolveClip(definition, model, "idle"));
    }

    @Test void mapsWorkStateToBlockbenchStyleClipNames() {
        var model = new GlbModelLoader.Model(List.of(), List.of(), List.of(), List.of(),
                List.of(new GlbModelLoader.Animation("stand_work_lumber_1", List.of(), List.of())),
                0, 0, 0);
        var definition = new NpcModelCatalog.Definition(
                new ResourceLocation("maqan", "worker"),
                new ResourceLocation("maqan", "models/worker.glb"), null, 1, Map.of());
        assertEquals("stand_work_lumber_1", NpcAnimationController.resolveClip(definition, model, "work"));
    }
}
