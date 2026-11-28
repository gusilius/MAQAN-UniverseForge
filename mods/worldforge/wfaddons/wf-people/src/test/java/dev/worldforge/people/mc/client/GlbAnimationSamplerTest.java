package dev.worldforge.people.mc.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlbAnimationSamplerTest {
    @Test void linearTranslationInterpolatesBetweenKeys() {
        var sampler = new GlbModelLoader.AnimationSampler(
                new float[]{0, 2}, new float[]{0, 0, 0, 10, 4, 2}, "LINEAR", 3);
        assertArrayEquals(new float[]{5, 2, 1}, GlbAnimationSampler.evaluate(sampler, 1, false), 0.0001F);
    }

    @Test void stepKeepsPreviousKeyUntilNextKeyTime() {
        var sampler = new GlbModelLoader.AnimationSampler(
                new float[]{0, 1}, new float[]{1, 2, 3, 7, 8, 9}, "STEP", 3);
        assertArrayEquals(new float[]{1, 2, 3}, GlbAnimationSampler.evaluate(sampler, 0.999F, false));
        assertArrayEquals(new float[]{7, 8, 9}, GlbAnimationSampler.evaluate(sampler, 1, false));
    }

    @Test void cubicSplineUsesHermiteTangentsAndNormalizesQuaternion() {
        // in tangent, value, out tangent for each keyframe
        var sampler = new GlbModelLoader.AnimationSampler(
                new float[]{0, 1},
                new float[]{0,0,0, 0,0,0, 1,0,0,
                            1,0,0, 1,0,0, 0,0,0},
                "CUBICSPLINE", 3);
        float[] midpoint = GlbAnimationSampler.evaluate(sampler, 0.5F, false);
        assertEquals(0.5F, midpoint[0], 0.0001F);

        var rotation = new GlbModelLoader.AnimationSampler(
                new float[]{0, 1},
                new float[]{0,0,0,0, 0,0,0,1, 0,0,0,0,
                            0,0,0,0, 0,0,1,1, 0,0,0,0},
                "CUBICSPLINE", 4);
        float[] q = GlbAnimationSampler.evaluate(rotation, 0.5F, true);
        float length = (float)Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
        assertEquals(1.0F, length, 0.0001F);
    }

    @Test void quaternionLinearInterpolationUsesShortestArcAndUnitLength() {
        var sampler = new GlbModelLoader.AnimationSampler(
                new float[]{0, 1}, new float[]{0,0,0,1, 0,0,1,0}, "LINEAR", 4);
        float[] q = GlbAnimationSampler.evaluate(sampler, 0.5F, true);
        float length = (float)Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
        assertEquals(1.0F, length, 0.0001F);
        assertEquals(Math.sqrt(0.5), q[2], 0.001);
    }

    @Test void loopAndClampTimeAreDeterministic() {
        assertEquals(0.25F, GlbAnimationSampler.normalizeTime(2.25F, 2, true), 0.0001F);
        assertEquals(2.0F, GlbAnimationSampler.normalizeTime(9, 2, false));
        assertEquals(1.75F, GlbAnimationSampler.normalizeTime(-0.25F, 2, true), 0.0001F);
    }

    @Test void samplingPreservesUntouchedNodeTransforms() {
        var node = new GlbModelLoader.Node("root", -1, -1, new int[0],
                new float[]{0,0,0}, new float[]{0,0,0,1}, new float[]{1,1,1}, new float[0]);
        var sampler = new GlbModelLoader.AnimationSampler(new float[]{0,1},
                new float[]{0,0,0, 2,0,0}, "LINEAR", 3);
        var animation = new GlbModelLoader.Animation("walk", List.of(sampler),
                List.of(new GlbModelLoader.AnimationChannel(0, 0, "translation")));
        var model = new GlbModelLoader.Model(List.of(), List.of(node), List.of(), List.of(),
                List.of(animation), 1, 0, 0);
        var pose = GlbAnimationSampler.sample(model, "walk", 0.5F, false);
        assertArrayEquals(new float[]{1,0,0}, pose.nodes().get(0).translation(), 0.0001F);
        assertArrayEquals(new float[]{1,1,1}, pose.nodes().get(0).scale(), 0.0001F);
    }
}
