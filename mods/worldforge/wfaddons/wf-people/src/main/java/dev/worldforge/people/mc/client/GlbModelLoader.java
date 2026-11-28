package dev.worldforge.people.mc.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Strict CPU-side glTF 2.0 binary reader for MAQAN NPC assets.
 *
 * This class intentionally performs no OpenGL calls: resource IO and binary validation
 * are separated from render-thread GPU upload. The returned arrays are ready for a
 * render-thread mesh uploader and never own a Character or Minecraft Entity.
 */
public final class GlbModelLoader {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final int ARRAY_BUFFER = 34962;
    private static final int ELEMENT_ARRAY_BUFFER = 34963;

    private GlbModelLoader() {}

    public record Material(float[] baseColor, float metallic, float roughness, int baseColorTexture) {}
    public record Primitive(float[] positions, float[] normals, float[] uv, int[] indices,
                            int[] joints, float[] weights, int material) {}
    public record Mesh(String name, List<Primitive> primitives) {}
    public record Node(String name, int mesh, int skin, int[] children,
                       float[] translation, float[] rotation, float[] scale, float[] matrix) {}
    public record Skin(String name, int[] joints, float[] inverseBindMatrices, int skeleton) {}
    public record AnimationSampler(float[] times, float[] values, String interpolation, int components) {}
    public record AnimationChannel(int sampler, int node, String path) {}
    public record Animation(String name, List<AnimationSampler> samplers, List<AnimationChannel> channels) {}
    public record Model(List<Mesh> meshes, List<Node> nodes, List<Skin> skins,
                        List<Material> materials, List<Animation> animations,
                        float durationSeconds, int vertexCount, int triangleCount) {}

    public static Model load(ResourceManager manager, NpcModelCatalog.Definition definition) throws IOException {
        Resource resource = manager.getResource(definition.model())
                .orElseThrow(() -> new IOException("Missing GLB resource: " + definition.model()));
        byte[] bytes;
        try (InputStream in = resource.open()) {
            bytes = readAll(in);
        }
        return decode(bytes);
    }

    public static Model decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20) throw new IOException("GLB is shorter than its header");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt(0) != GLB_MAGIC) throw new IOException("Invalid GLB magic");
        int version = input.getInt(4);
        long declaredLength = Integer.toUnsignedLong(input.getInt(8));
        if (version != 2) throw new IOException("Only GLB version 2 is supported; got " + version);
        if (declaredLength != bytes.length) throw new IOException("GLB length header does not match file size");

        JsonObject root = null;
        byte[] binary = null;
        int cursor = 12;
        while (cursor < bytes.length) {
            if (bytes.length - cursor < 8) throw new IOException("Truncated GLB chunk header");
            long chunkLengthLong = Integer.toUnsignedLong(input.getInt(cursor));
            int chunkType = input.getInt(cursor + 4);
            cursor += 8;
            if (chunkLengthLong > bytes.length - cursor) throw new IOException("GLB chunk exceeds file bounds");
            int chunkLength = (int) chunkLengthLong;
            if (chunkType == JSON_CHUNK) {
                if (root != null) throw new IOException("GLB contains multiple JSON chunks");
                String json = new String(bytes, cursor, chunkLength, StandardCharsets.UTF_8).stripTrailing();
                try { root = JsonParser.parseString(json).getAsJsonObject(); }
                catch (RuntimeException malformed) { throw new IOException("Malformed glTF JSON chunk", malformed); }
            } else if (chunkType == BIN_CHUNK && binary == null) {
                binary = new byte[chunkLength];
                System.arraycopy(bytes, cursor, binary, 0, chunkLength);
            }
            cursor += chunkLength;
        }
        if (root == null) throw new IOException("GLB has no JSON chunk");
        JsonObject asset = object(root, "asset");
        if (!"2.0".equals(string(asset, "version", "")))
            throw new IOException("glTF asset.version must be 2.0");

        JsonArray buffers = array(root, "buffers");
        if (buffers.size() > 1) throw new IOException("External/multiple GLB buffers are not supported");
        if (buffers.size() == 1) {
            JsonObject buffer = buffers.get(0).getAsJsonObject();
            if (buffer.has("uri")) throw new IOException("GLB runtime requires embedded buffer data (no URI)");
            int expected = integer(buffer, "byteLength", -1);
            if (expected < 0 || binary == null || binary.length < expected)
                throw new IOException("GLB BIN chunk is missing or shorter than buffers[0].byteLength");
        }
        Decoder decoder = new Decoder(root, binary == null ? new byte[0] : binary);
        return decoder.decode();
    }

    private static final class Decoder {
        private final JsonObject root;
        private final ByteBuffer bin;
        private final JsonArray views;
        private final JsonArray accessors;
        private int vertices;
        private int triangles;

        Decoder(JsonObject root, byte[] binary) {
            this.root = root;
            this.bin = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
            this.views = array(root, "bufferViews");
            this.accessors = array(root, "accessors");
        }

        Model decode() throws IOException {
            List<Material> materials = decodeMaterials();
            List<Mesh> meshes = decodeMeshes(materials.size());
            List<Node> nodes = decodeNodes();
            List<Skin> skins = decodeSkins();
            List<Animation> animations = decodeAnimations();
            float duration = 0;
            for (Animation animation : animations)
                for (AnimationSampler sampler : animation.samplers())
                    for (float time : sampler.times()) duration = Math.max(duration, time);
            return new Model(List.copyOf(meshes), List.copyOf(nodes), List.copyOf(skins),
                    List.copyOf(materials), List.copyOf(animations), duration, vertices, triangles);
        }

        private List<Material> decodeMaterials() throws IOException {
            List<Material> result = new ArrayList<>();
            for (JsonElement element : array(root, "materials")) {
                JsonObject material = element.getAsJsonObject();
                JsonObject pbr = object(material, "pbrMetallicRoughness");
                float[] color = floats(pbr.getAsJsonArray("baseColorFactor"), new float[]{1,1,1,1});
                if (color.length != 4) throw new IOException("baseColorFactor must have four values");
                int texture = -1;
                if (pbr.has("baseColorTexture"))
                    texture = integer(pbr.getAsJsonObject("baseColorTexture"), "index", -1);
                result.add(new Material(color, number(pbr, "metallicFactor", 1),
                        number(pbr, "roughnessFactor", 1), texture));
            }
            return result;
        }

        private List<Mesh> decodeMeshes(int materialCount) throws IOException {
            List<Mesh> result = new ArrayList<>();
            for (JsonElement meshElement : array(root, "meshes")) {
                JsonObject mesh = meshElement.getAsJsonObject();
                List<Primitive> primitives = new ArrayList<>();
                for (JsonElement primitiveElement : array(mesh, "primitives")) {
                    JsonObject primitive = primitiveElement.getAsJsonObject();
                    if (integer(primitive, "mode", 4) != 4) continue;
                    JsonObject attributes = object(primitive, "attributes");
                    if (!attributes.has("POSITION")) throw new IOException("Mesh primitive has no POSITION attribute");
                    float[] positions = readFloats(index(attributes, "POSITION"), 3);
                    int count = positions.length / 3;
                    float[] normals = attributes.has("NORMAL") ? readFloats(index(attributes, "NORMAL"), 3) : new float[0];
                    float[] uv = attributes.has("TEXCOORD_0") ? readFloats(index(attributes, "TEXCOORD_0"), 2) : new float[0];
                    int[] joints = attributes.has("JOINTS_0") ? readInts(index(attributes, "JOINTS_0"), 4) : new int[0];
                    float[] weights = attributes.has("WEIGHTS_0") ? readFloats(index(attributes, "WEIGHTS_0"), 4) : new float[0];
                    if (normals.length != 0 && normals.length != count * 3) throw new IOException("NORMAL count differs from POSITION");
                    if (uv.length != 0 && uv.length != count * 2) throw new IOException("TEXCOORD_0 count differs from POSITION");
                    if (joints.length != 0 && joints.length != count * 4) throw new IOException("JOINTS_0 count differs from POSITION");
                    if (weights.length != 0 && weights.length != count * 4) throw new IOException("WEIGHTS_0 count differs from POSITION");
                    int[] indices = primitive.has("indices")
                            ? readInts(index(primitive, "indices"), 1) : sequential(count);
                    if (indices.length % 3 != 0) throw new IOException("TRIANGLES index count is not divisible by three");
                    for (int value : indices) if (value < 0 || value >= count) throw new IOException("Mesh index is out of range");
                    int material = integer(primitive, "material", -1);
                    if (material >= materialCount) throw new IOException("Primitive material index is out of range");
                    vertices += count;
                    triangles += indices.length / 3;
                    primitives.add(new Primitive(positions, normals, uv, indices, joints, weights, material));
                }
                result.add(new Mesh(string(mesh, "name", "mesh_" + result.size()), List.copyOf(primitives)));
            }
            return result;
        }

        private List<Node> decodeNodes() throws IOException {
            List<Node> result = new ArrayList<>();
            for (JsonElement element : array(root, "nodes")) {
                JsonObject node = element.getAsJsonObject();
                result.add(new Node(string(node, "name", "node_" + result.size()),
                        integer(node, "mesh", -1), integer(node, "skin", -1),
                        ints(node.getAsJsonArray("children")),
                        floats(node.getAsJsonArray("translation"), new float[]{0,0,0}),
                        floats(node.getAsJsonArray("rotation"), new float[]{0,0,0,1}),
                        floats(node.getAsJsonArray("scale"), new float[]{1,1,1}),
                        floats(node.getAsJsonArray("matrix"), new float[0])));
            }
            return result;
        }

        private List<Skin> decodeSkins() throws IOException {
            List<Skin> result = new ArrayList<>();
            for (JsonElement element : array(root, "skins")) {
                JsonObject skin = element.getAsJsonObject();
                int[] joints = ints(skin.getAsJsonArray("joints"));
                float[] matrices = skin.has("inverseBindMatrices")
                        ? readFloats(integer(skin, "inverseBindMatrices", -1), 16) : new float[0];
                if (matrices.length != 0 && matrices.length != joints.length * 16)
                    throw new IOException("inverseBindMatrices count differs from skin joints");
                result.add(new Skin(string(skin, "name", "skin_" + result.size()), joints,
                        matrices, integer(skin, "skeleton", -1)));
            }
            return result;
        }

        private List<Animation> decodeAnimations() throws IOException {
            List<Animation> result = new ArrayList<>();
            for (JsonElement element : array(root, "animations")) {
                JsonObject animation = element.getAsJsonObject();
                List<AnimationSampler> samplers = new ArrayList<>();
                for (JsonElement samplerElement : array(animation, "samplers")) {
                    JsonObject sampler = samplerElement.getAsJsonObject();
                    int input = integer(sampler, "input", -1);
                    int output = integer(sampler, "output", -1);
                    float[] times = readFloats(input, 1);
                    int components = accessorComponents(output);
                    float[] values = readFloats(output, components);
                    if (times.length == 0 || values.length == 0) throw new IOException("Animation sampler is empty");
                    samplers.add(new AnimationSampler(times, values,
                            string(sampler, "interpolation", "LINEAR"), components));
                }
                List<AnimationChannel> channels = new ArrayList<>();
                for (JsonElement channelElement : array(animation, "channels")) {
                    JsonObject channel = channelElement.getAsJsonObject();
                    JsonObject target = object(channel, "target");
                    channels.add(new AnimationChannel(integer(channel, "sampler", -1),
                            integer(target, "node", -1), string(target, "path", "")));
                }
                result.add(new Animation(string(animation, "name", "animation_" + result.size()),
                        List.copyOf(samplers), List.copyOf(channels)));
            }
            return result;
        }

        private int accessorComponents(int accessorIndex) throws IOException {
            JsonObject accessor = accessor(accessorIndex);
            return components(string(accessor, "type", ""));
        }

        private float[] readFloats(int accessorIndex, int expectedComponents) throws IOException {
            JsonObject accessor = accessor(accessorIndex);
            int componentType = integer(accessor, "componentType", -1);
            int components = components(string(accessor, "type", ""));
            if (components != expectedComponents) throw new IOException("Unexpected accessor component count");
            int count = integer(accessor, "count", -1);
            if (count < 0 || count > 4_000_000) throw new IOException("Accessor count outside safety limit");
            ByteBuffer data = accessorData(accessor, componentType, components, count);
            float[] result = new float[count * components];
            for (int i = 0; i < result.length; i++) result[i] = componentFloat(data, componentType, accessorBoolean(accessor, "normalized"));
            return result;
        }

        private int[] readInts(int accessorIndex, int expectedComponents) throws IOException {
            JsonObject accessor = accessor(accessorIndex);
            int componentType = integer(accessor, "componentType", -1);
            int components = components(string(accessor, "type", ""));
            if (components != expectedComponents) throw new IOException("Unexpected integer accessor component count");
            if (componentType != 5121 && componentType != 5123 && componentType != 5125)
                throw new IOException("Integer accessor must use unsigned byte/short/int");
            int count = integer(accessor, "count", -1);
            if (count < 0 || count > 4_000_000) throw new IOException("Accessor count outside safety limit");
            ByteBuffer data = accessorData(accessor, componentType, components, count);
            int[] result = new int[count * components];
            for (int i = 0; i < result.length; i++) result[i] = componentInt(data, componentType);
            return result;
        }

        private ByteBuffer accessorData(JsonObject accessor, int componentType, int components, int count) throws IOException {
            if (accessor.has("sparse")) throw new IOException("Sparse accessors are not yet supported");
            int viewIndex = integer(accessor, "bufferView", -1);
            if (viewIndex < 0 || viewIndex >= views.size()) throw new IOException("Accessor bufferView is missing/out of range");
            JsonObject view = views.get(viewIndex).getAsJsonObject();
            if (integer(view, "buffer", 0) != 0) throw new IOException("Only embedded buffer 0 is supported");
            int componentBytes = componentBytes(componentType);
            int elementBytes = componentBytes * components;
            int stride = integer(view, "byteStride", elementBytes);
            if (stride < elementBytes || stride > 252) throw new IOException("Invalid accessor byteStride");
            int viewOffset = integer(view, "byteOffset", 0);
            int accessorOffset = integer(accessor, "byteOffset", 0);
            long lastEnd = (long) viewOffset + accessorOffset + (long) Math.max(0, count - 1) * stride + elementBytes;
            int viewLength = integer(view, "byteLength", -1);
            if (viewOffset < 0 || accessorOffset < 0 || viewLength < 0
                    || lastEnd > (long) viewOffset + viewLength || lastEnd > bin.capacity())
                throw new IOException("Accessor reads outside its bufferView/BIN chunk");
            ByteBuffer data = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            data.position(viewOffset + accessorOffset);
            // Return a compact copy so interleaved attributes honor byteStride safely.
            ByteBuffer compact = ByteBuffer.allocate(count * elementBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                ByteBuffer row = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                row.position(viewOffset + accessorOffset + i * stride);
                row.limit(viewOffset + accessorOffset + i * stride + elementBytes);
                compact.put(row);
            }
            compact.flip();
            return compact;
        }

        private JsonObject accessor(int index) throws IOException {
            if (index < 0 || index >= accessors.size()) throw new IOException("Accessor index out of range: " + index);
            return accessors.get(index).getAsJsonObject();
        }
    }

    private static int componentBytes(int type) throws IOException {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IOException("Unsupported glTF componentType: " + type);
        };
    }

    private static float componentFloat(ByteBuffer data, int type, boolean normalized) throws IOException {
        return switch (type) {
            case 5126 -> data.getFloat();
            case 5120 -> { byte v = data.get(); yield normalized ? Math.max(v / 127.0F, -1.0F) : v; }
            case 5121 -> { int v = Byte.toUnsignedInt(data.get()); yield normalized ? v / 255.0F : v; }
            case 5122 -> { short v = data.getShort(); yield normalized ? Math.max(v / 32767.0F, -1.0F) : v; }
            case 5123 -> { int v = Short.toUnsignedInt(data.getShort()); yield normalized ? v / 65535.0F : v; }
            case 5125 -> { long v = Integer.toUnsignedLong(data.getInt()); yield normalized ? (float)(v / 4294967295.0) : (float)v; }
            default -> throw new IOException("Unsupported float conversion componentType: " + type);
        };
    }

    private static int componentInt(ByteBuffer data, int type) {
        return switch (type) {
            case 5121 -> Byte.toUnsignedInt(data.get());
            case 5123 -> Short.toUnsignedInt(data.getShort());
            case 5125 -> data.getInt();
            default -> throw new IllegalArgumentException("Not an unsigned integer component type");
        };
    }

    private static int components(String type) throws IOException {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IOException("Unsupported glTF accessor type: " + type);
        };
    }

    private static int index(JsonObject object, String key) throws IOException {
        int value = integer(object, key, -1);
        if (value < 0) throw new IOException("Missing accessor index: " + key);
        return value;
    }

    private static int[] sequential(int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = i;
        return result;
    }

    private static int[] ints(JsonArray array) {
        if (array == null) return new int[0];
        int[] result = new int[array.size()];
        for (int i = 0; i < result.length; i++) result[i] = array.get(i).getAsInt();
        return result;
    }

    private static float[] floats(JsonArray array, float[] fallback) {
        if (array == null) return fallback.clone();
        float[] result = new float[array.size()];
        for (int i = 0; i < result.length; i++) result[i] = array.get(i).getAsFloat();
        return result;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static float number(JsonObject object, String key, float fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }

    private static boolean accessorBoolean(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (output.size() + read > 128 * 1024 * 1024)
                throw new IOException("GLB exceeds the 128 MiB safety limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
