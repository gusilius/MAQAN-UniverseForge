package dev.maqan.platform.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Versioned named workspace profiles backed by the local user preferences store.
 * Provider-owned game state is never serialized here.
 */
public final class WorkspaceProfileStore {
    private static final int MAGIC = 0x4D415157; // MAQW
    private static final int SCHEMA = 1;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_PANELS = 256;
    private static final String ACTIVE_KEY = "active";
    private final Preferences root;

    public WorkspaceProfileStore(Preferences root) {
        this.root = root;
    }

    public synchronized void save(String name, WorkspaceDockTree.Node tree) {
        String key = profileKey(name);
        WorkspaceDockTree.validate(tree);
        try {
            Preferences profile = root.node(key);
            profile.putInt("schema", SCHEMA);
            profile.put("tree", encode(tree));
            profile.put("displayName", clean(name));
            profile.flush();
            root.put(ACTIVE_KEY, key);
            root.flush();
        } catch (BackingStoreException | IOException error) {
            throw new IllegalStateException("Unable to save workspace profile " + name, error);
        }
    }

    public synchronized WorkspaceDockTree.Node load(String name) {
        try {
            Preferences profile = root.node(profileKey(name));
            if (profile.getInt("schema", -1) != SCHEMA)
                throw new IllegalStateException("Unsupported workspace profile schema");
            String encoded = profile.get("tree", "");
            if (encoded.isBlank()) throw new IllegalStateException("Workspace profile has no layout");
            WorkspaceDockTree.Node tree = decode(encoded);
            WorkspaceDockTree.validate(tree);
            return tree;
        } catch (BackingStoreException | IOException error) {
            throw new IllegalStateException("Unable to load workspace profile " + name, error);
        }
    }

    public synchronized List<String> list() {
        try {
            List<String> names = new ArrayList<>();
            for (String child : root.childrenNames()) {
                String displayName = root.node(child).get("displayName", "");
                if (!displayName.isBlank()) names.add(displayName);
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return List.copyOf(names);
        } catch (BackingStoreException error) {
            throw new IllegalStateException("Unable to list workspace profiles", error);
        }
    }

    public synchronized void delete(String name) {
        try {
            String key = profileKey(name);
            if (root.nodeExists(key)) root.node(key).removeNode();
            if (key.equals(root.get(ACTIVE_KEY, ""))) root.remove(ACTIVE_KEY);
            root.flush();
        } catch (BackingStoreException error) {
            throw new IllegalStateException("Unable to delete workspace profile " + name, error);
        }
    }

    public synchronized String activeProfile() {
        String key = root.get(ACTIVE_KEY, "");
        if (key.isBlank()) return "";
        try {
            if (!root.nodeExists(key)) return "";
            return root.node(key).get("displayName", "");
        } catch (BackingStoreException error) {
            return "";
        }
    }

    private static String profileKey(String name) {
        String value = clean(name);
        if (value.isBlank()) throw new IllegalArgumentException("Profile name is required");
        if (value.length() > 64) throw new IllegalArgumentException("Profile name is too long");
        return "profile-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String encode(WorkspaceDockTree.Node root) throws IOException {
        if (root == null) throw new IllegalArgumentException("Workspace layout cannot be empty");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA);
            writeNode(out, root, 0);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static WorkspaceDockTree.Node decode(String value) throws IOException {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid workspace layout encoding", error); }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid workspace layout signature");
            if (in.readInt() != SCHEMA) throw new IOException("Unsupported workspace layout version");
            WorkspaceDockTree.Node node = readNode(in, 0, new int[]{0});
            if (in.available() != 0) throw new IOException("Trailing data in workspace layout");
            return node;
        }
    }

    private static void writeNode(DataOutputStream out, WorkspaceDockTree.Node node, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Workspace layout is too deeply nested");
        if (node instanceof WorkspaceDockTree.Panel panel) {
            out.writeByte(1);
            out.writeUTF(panel.id());
        } else if (node instanceof WorkspaceDockTree.Split split) {
            out.writeByte(2);
            out.writeByte(split.axis().ordinal());
            out.writeInt(split.firstWeight());
            writeNode(out, split.first(), depth + 1);
            writeNode(out, split.second(), depth + 1);
        } else if (node instanceof WorkspaceDockTree.Tabs tabs) {
            out.writeByte(3);
            out.writeInt(tabs.activeIndex());
            out.writeInt(tabs.panels().size());
            for (WorkspaceDockTree.Panel panel : tabs.panels()) out.writeUTF(panel.id());
        } else {
            throw new IOException("Unknown workspace node type");
        }
    }

    private static WorkspaceDockTree.Node readNode(DataInputStream in, int depth, int[] count) throws IOException {
        if (depth > MAX_DEPTH || ++count[0] > MAX_PANELS * 2)
            throw new IOException("Workspace layout exceeds safety limits");
        int type = in.readUnsignedByte();
        if (type == 1) return new WorkspaceDockTree.Panel(in.readUTF());
        if (type == 2) {
            int axis = in.readUnsignedByte();
            if (axis >= WorkspaceDockTree.Axis.values().length) throw new IOException("Invalid split axis");
            int weight = in.readInt();
            return new WorkspaceDockTree.Split(WorkspaceDockTree.Axis.values()[axis], weight,
                    readNode(in, depth + 1, count), readNode(in, depth + 1, count));
        }
        if (type == 3) {
            int active = in.readInt();
            int size = in.readInt();
            if (size < 2 || size > MAX_PANELS) throw new IOException("Invalid tab count");
            List<WorkspaceDockTree.Panel> panels = new ArrayList<>();
            for (int i = 0; i < size; i++) panels.add(new WorkspaceDockTree.Panel(in.readUTF()));
            return new WorkspaceDockTree.Tabs(panels, active);
        }
        throw new IOException("Unknown workspace node type " + type);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
