package dev.worldforge.people.mc.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPU glTF animation sampler. Produces node-local TRS poses; it deliberately does not
 * touch Minecraft/OpenGL and can therefore be evaluated off-thread or tested in isolation.
 */
public final class GlbAnimationSampler {
    private GlbAnimationSampler() {}

    public record Transform(float[] translation, float[] rotation, float[] scale) {
        public Transform {
            translation = translation.clone();
            rotation = normalize(rotation.clone());
            scale = scale.clone();
        }
    }

    public record Pose(Map<Integer, Transform> nodes, float timeSeconds, float durationSeconds) {
        public Pose { nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes)); }
    }

    public static Pose sample(GlbModelLoader.Model model, String clipName, float seconds, boolean loop) {
        if (model == null) throw new IllegalArgumentException("model");
        GlbModelLoader.Animation animation = model.animations().stream()
                .filter(a -> a.name().equals(clipName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown animation clip: " + clipName));
        float duration = duration(animation);
        float time = normalizeTime(seconds, duration, loop);
        Map<Integer, Transform> pose = new LinkedHashMap<>();
        for (int i = 0; i < model.nodes().size(); i++) {
            GlbModelLoader.Node node = model.nodes().get(i);
            pose.put(i, new Transform(node.translation(), node.rotation(), node.scale()));
        }
        for (GlbModelLoader.AnimationChannel channel : animation.channels()) {
            if (channel.node() < 0 || channel.node() >= model.nodes().size()
                    || channel.sampler() < 0 || channel.sampler() >= animation.samplers().size()) continue;
            GlbModelLoader.AnimationSampler sampler = animation.samplers().get(channel.sampler());
            float[] value = evaluate(sampler, time, "rotation".equals(channel.path()));
            Transform base = pose.get(channel.node());
            switch (channel.path()) {
                case "translation" -> { if (value.length == 3) pose.put(channel.node(), new Transform(value, base.rotation(), base.scale())); }
                case "scale" -> { if (value.length == 3) pose.put(channel.node(), new Transform(base.translation(), base.rotation(), value)); }
                case "rotation" -> { if (value.length == 4) pose.put(channel.node(), new Transform(base.translation(), value, base.scale())); }
                default -> { /* Morph weights are intentionally not treated as node TRS. */ }
            }
        }
        return new Pose(pose, time, duration);
    }

    public static float duration(GlbModelLoader.Animation animation) {
        float result = 0;
        for (GlbModelLoader.AnimationSampler sampler : animation.samplers())
            if (sampler.times().length > 0) result = Math.max(result, sampler.times()[sampler.times().length - 1]);
        return result;
    }

    public static float normalizeTime(float seconds, float duration, boolean loop) {
        if (!Float.isFinite(seconds) || !Float.isFinite(duration) || duration <= 0) return 0;
        if (!loop) return Math.max(0, Math.min(duration, seconds));
        float t = seconds % duration;
        return t < 0 ? t + duration : t;
    }

    public static float[] evaluate(GlbModelLoader.AnimationSampler sampler, float time, boolean quaternion) {
        float[] times = sampler.times(), values = sampler.values();
        int components = sampler.components();
        if (times.length == 0 || components <= 0) return new float[0];
        int factor = "CUBICSPLINE".equals(sampler.interpolation()) ? 3 : 1;
        if (values.length != times.length * components * factor)
            throw new IllegalArgumentException("Animation output count does not match input keys");
        if (time <= times[0]) return keyValue(values, 0, components, factor);
        int last = times.length - 1;
        if (time >= times[last]) return keyValue(values, last, components, factor);
        int upper = 1;
        while (upper < times.length && times[upper] < time) upper++;
        int lower = upper - 1;
        float span = times[upper] - times[lower];
        if (!(span > 0)) return keyValue(values, upper, components, factor);
        float t = (time - times[lower]) / span;
        String interpolation = sampler.interpolation();
        if ("STEP".equals(interpolation)) return keyValue(values, lower, components, factor);
        if ("CUBICSPLINE".equals(interpolation))
            return cubic(values, lower, upper, components, t, span, quaternion);
        float[] a = keyValue(values, lower, components, 1);
        float[] b = keyValue(values, upper, components, 1);
        if (quaternion && components == 4) return slerp(a, b, t);
        float[] result = new float[components];
        for (int i = 0; i < components; i++) result[i] = a[i] + (b[i] - a[i]) * t;
        return result;
    }

    private static float[] keyValue(float[] values, int key, int components, int factor) {
        int offset = key * components * factor + (factor == 3 ? components : 0);
        float[] result = new float[components];
        System.arraycopy(values, offset, result, 0, components);
        return result;
    }

    private static float[] cubic(float[] values, int lower, int upper, int components,
                                 float t, float span, boolean quaternion) {
        int a = lower * components * 3, b = upper * components * 3;
        float t2 = t * t, t3 = t2 * t;
        float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t;
        float h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        float[] out = new float[components];
        for (int i = 0; i < components; i++) {
            float p0 = values[a + components + i];
            float m0 = values[a + components * 2 + i] * span;
            float p1 = values[b + components + i];
            float m1 = values[b + i] * span;
            out[i] = h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1;
        }
        return quaternion && components == 4 ? normalize(out) : out;
    }

    private static float[] slerp(float[] a, float[] b, float t) {
        float[] qa = normalize(a.clone()), qb = normalize(b.clone());
        float dot = qa[0]*qb[0] + qa[1]*qb[1] + qa[2]*qb[2] + qa[3]*qb[3];
        if (dot < 0) {
            dot = -dot;
            for (int i = 0; i < 4; i++) qb[i] = -qb[i];
        }
        if (dot > 0.9995F) {
            float[] out = new float[4];
            for (int i = 0; i < 4; i++) out[i] = qa[i] + t * (qb[i] - qa[i]);
            return normalize(out);
        }
        dot = Math.max(-1, Math.min(1, dot));
        double theta = Math.acos(dot), sin = Math.sin(theta);
        float wa = (float) (Math.sin((1 - t) * theta) / sin);
        float wb = (float) (Math.sin(t * theta) / sin);
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) out[i] = qa[i] * wa + qb[i] * wb;
        return normalize(out);
    }

    private static float[] normalize(float[] q) {
        if (q.length != 4) return q;
        double length = Math.sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        if (length < 1.0e-8) return new float[]{0, 0, 0, 1};
        for (int i = 0; i < 4; i++) q[i] /= (float) length;
        return q;
    }
}
