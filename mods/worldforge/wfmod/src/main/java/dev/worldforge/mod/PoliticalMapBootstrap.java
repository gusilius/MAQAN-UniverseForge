package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic bootstrap politics for worlds whose map package contains terrain
 * but no authored provinces/titles/realms. Generated entities are ordinary
 * TitleData records, so the existing political renderer, selection and diplomacy
 * projections use the same data path as hand-authored polities.
 */
public final class PoliticalMapBootstrap {
    private static final String[] TIER_KEYS = {"province", "county", "duchy", "kingdom"};
    private static final String[] TIER_NAMES = {"Провинции", "Графства", "Герцогства", "Королевства"};
    private static final String[] ROOTS = {
            "Северное", "Южное", "Восточное", "Западное", "Высокое", "Зелёное",
            "Каменное", "Речное", "Лесное", "Степное", "Прибрежное", "Горное",
            "Солнечное", "Туманное", "Серебряное", "Янтарное"
    };

    private static final class Node {
        final int level, gx, gz, id;
        final List<Node> children = new ArrayList<>();
        long landPixels, sumX, sumZ;
        int parentId;
        Node(int level, int gx, int gz, int id) {
            this.level = level; this.gx = gx; this.gz = gz; this.id = id;
        }
        double centerX() { return landPixels == 0 ? 0 : sumX / (double) landPixels; }
        double centerZ() { return landPixels == 0 ? 0 : sumZ / (double) landPixels; }
    }

    private PoliticalMapBootstrap() {}

    public static TitleData generate(MapData map) {
        if (map == null) throw new IllegalArgumentException("map is required");
        long pixelCount = (long) map.w * map.h;
        if (map.w <= 0 || map.h <= 0 || pixelCount > 16L * 1024L * 1024L)
            throw new IllegalArgumentException("Political bootstrap map dimensions exceed the 16M pixel budget");
        boolean[] land = new boolean[(int) pixelCount];
        for (int z = 0; z < map.h; z++) for (int x = 0; x < map.w; x++)
            land[z * map.w + x] = isLand(map, x, z);
        return generate(map.w, map.h, map.bpp, land);
    }

    /** Same political bootstrap for a client-side snapshot of an ordinary Minecraft world. */
    public static TitleData generate(int width, int height, double blocksPerPixel, boolean[] landMask) {
        long pixelCount = (long) width * height;
        if (width <= 0 || height <= 0 || pixelCount > 16L * 1024L * 1024L
                || landMask == null || landMask.length != pixelCount
                || !Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0)
            throw new IllegalArgumentException("Invalid political bootstrap raster");

        // At most 64x64 base cells: a predictable number of titles on large maps.
        int cellSize = Math.max(4, (Math.max(width, height) + 63) / 64);
        int cellsX = (width + cellSize - 1) / cellSize;
        int cellsZ = (height + cellSize - 1) / cellSize;
        int cellCount = Math.multiplyExact(cellsX, cellsZ);
        long[] counts = new long[cellCount], sumX = new long[cellCount], sumZ = new long[cellCount];
        long totalLand = 0;
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            if (!landMask[z * width + x]) continue;
            int cell = (z / cellSize) * cellsX + x / cellSize;
            counts[cell]++; sumX[cell] += x; sumZ[cell] += z; totalLand++;
        }

        List<Node>[] levels = new List[4];
        for (int level = 0; level < levels.length; level++) levels[level] = new ArrayList<>();
        Node[] baseByCell = new Node[cellCount];
        int nextId = 1;
        for (int z = 0; z < cellsZ; z++) for (int x = 0; x < cellsX; x++) {
            int cell = z * cellsX + x;
            if (counts[cell] == 0) continue;
            Node node = new Node(0, x, z, nextId++);
            node.landPixels = counts[cell]; node.sumX = sumX[cell]; node.sumZ = sumZ[cell];
            levels[0].add(node); baseByCell[cell] = node;
        }
        for (int level = 1; level < levels.length; level++) {
            Map<Long, Node> grouped = new LinkedHashMap<>();
            for (Node child : levels[level - 1]) {
                int gx = child.gx / 2, gz = child.gz / 2;
                long key = (((long) gz) << 32) | (gx & 0xffffffffL);
                Node parent = grouped.get(key);
                if (parent == null) {
                    parent = new Node(level, gx, gz, nextId++);
                    grouped.put(key, parent); levels[level].add(parent);
                }
                parent.children.add(child);
                parent.landPixels += child.landPixels;
                parent.sumX += child.sumX; parent.sumZ += child.sumZ;
                child.parentId = parent.id;
            }
        }

        short[] provincePixels = new short[(int) pixelCount];
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            if (!landMask[z * width + x]) continue;
            Node province = baseByCell[(z / cellSize) * cellsX + x / cellSize];
            if (province != null) provincePixels[z * width + x] = (short) province.id;
        }

        // Build real same-tier adjacency from shared land-pixel edges. The previous
        // bootstrap emitted empty neighbor lists, which broke political geography
        // consumers despite the province raster and title hierarchy being present.
        Map<Integer, Node> nodesById = new HashMap<>();
        Map<Integer, Set<Integer>> adjacency = new HashMap<>();
        for (List<Node> level : levels) for (Node node : level) {
            nodesById.put(node.id, node);
            adjacency.put(node.id, new HashSet<>());
        }
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            int index = z * width + x;
            if (!landMask[index]) continue;
            Node here = baseByCell[(z / cellSize) * cellsX + x / cellSize];
            if (x + 1 < width && landMask[index + 1]) {
                Node right = baseByCell[(z / cellSize) * cellsX + (x + 1) / cellSize];
                link(adjacency, here, right);
            }
            if (z + 1 < height && landMask[index + width]) {
                Node below = baseByCell[((z + 1) / cellSize) * cellsX + x / cellSize];
                link(adjacency, here, below);
            }
        }
        for (int level = 1; level < levels.length; level++) {
            for (Node child : levels[level - 1]) {
                Node parent = nodesById.get(child.parentId);
                if (parent == null) continue;
                for (int neighborId : adjacency.get(child.id)) {
                    Node neighbor = nodesById.get(neighborId);
                    if (neighbor == null || neighbor.level != level - 1) continue;
                    Node otherParent = nodesById.get(neighbor.parentId);
                    if (otherParent != null && otherParent.id != parent.id)
                        link(adjacency, parent, otherParent);
                }
            }
        }

        List<Object> tierRows = new ArrayList<>();
        for (int level = 0; level < TIER_KEYS.length; level++) {
            Map<String, Object> tier = new LinkedHashMap<>();
            tier.put("index", level); tier.put("key", TIER_KEYS[level]); tier.put("name", TIER_NAMES[level]);
            tierRows.add(tier);
        }
        List<Object> titleRows = new ArrayList<>();
        Map<Node, String> keys = new java.util.IdentityHashMap<>();
        for (int level = 0; level < levels.length; level++) for (Node node : levels[level])
            keys.put(node, "emergent:" + TIER_KEYS[level] + "/" + node.gx + "/" + node.gz);

        for (int level = 0; level < levels.length; level++) for (Node node : levels[level]) {
            double nx = (node.centerX() + 0.5) / width;
            double nz = (node.centerZ() + 0.5) / height;
            long area = Math.max(1L, Math.round(node.landPixels * blocksPerPixel * blocksPerPixel));
            Map<String, Object> capital = new LinkedHashMap<>();
            capital.put("x", Math.round((node.centerX() + 0.5 - width * 0.5) * blocksPerPixel));
            capital.put("z", Math.round((node.centerZ() + 0.5 - height * 0.5) * blocksPerPixel));
            capital.put("nx", nx); capital.put("ny", nz);
            capital.put("province", level == 0 ? node.id : firstProvince(node));
            List<Integer> children = new ArrayList<>();
            for (Node child : node.children) children.add(child.id);
            int rgb = color(node.gx, node.gz, level);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("emergence.generated", true);
            props.put("emergence.source", "terrain-bootstrap");
            props.put("emergence.level", TIER_KEYS[level]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", node.id); row.put("key", keys.get(node));
            row.put("name", name(level, node.gx, node.gz));
            row.put("tier", level); row.put("parent", node.parentId);
            row.put("color", String.format("#%06x", rgb));
            row.put("capital", capital); row.put("area_blocks", area);
            row.put("area_pct_of_land", totalLand == 0 ? 0.0 : node.landPixels * 100.0 / totalLand);
            List<Integer> neighbors = new ArrayList<>(adjacency.getOrDefault(node.id, Set.of()));
            neighbors.sort(Integer::compareTo);
            row.put("children", children); row.put("neighbors", neighbors); row.put("props", props);
            titleRows.add(row);
        }

        List<Object> realmRows = new ArrayList<>();
        for (Node kingdom : levels[3]) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("emergence.generated", true);
            props.put("emergence.source", "terrain-bootstrap");
            props.put("emergence.status", "unclaimed");
            Map<String, Object> realm = new LinkedHashMap<>();
            realm.put("id", "emergent-" + kingdom.gx + "-" + kingdom.gz);
            realm.put("name", name(3, kingdom.gx, kingdom.gz));
            realm.put("ruler", "");
            realm.put("color", String.format("#%06x", color(kingdom.gx, kingdom.gz, 3)));
            realm.put("titles", List.of(keys.get(kingdom)));
            realm.put("overlord", null); realm.put("relation", "independent");
            realm.put("subjects", List.of());
            realm.put("area_blocks", Math.max(1L, Math.round(kingdom.landPixels * blocksPerPixel * blocksPerPixel)));
            realm.put("props", props);
            realmRows.add(realm);
        }
        Map<String, Object> titlesRoot = new LinkedHashMap<>();
        titlesRoot.put("format", "worldforge-titles/1");
        titlesRoot.put("size_px", List.of(width, height));
        titlesRoot.put("blocks_per_pixel", blocksPerPixel);
        titlesRoot.put("tiers", tierRows); titlesRoot.put("titles", titleRows);
        Map<String, Object> realmsRoot = new LinkedHashMap<>();
        realmsRoot.put("format", "worldforge-realms/1");
        realmsRoot.put("relation_types", Map.of());
        realmsRoot.put("realms", realmRows); realmsRoot.put("relations", List.of());
        return TitleData.create(Json.write(titlesRoot), Json.write(realmsRoot),
                provincePixels, width, height);
    }

    /** Adds real realm records to authored province maps that omitted political entities. */
    public static boolean ensureRealms(TitleData titles) {
        if (titles == null || !titles.realmList().isEmpty()) return false;
        int highest = -1;
        for (TitleData.Title title : titles.allTitles()) highest = Math.max(highest, title.tier);
        if (highest < 0) return false;

        int realmTier = Math.min(highest, 3);
        List<TitleData.Title> realmTitles = new ArrayList<>();
        for (TitleData.Title title : titles.allTitles()) if (title.tier == realmTier) realmTitles.add(title);
        if (realmTitles.isEmpty() && realmTier != highest) {
            realmTier = highest;
            for (TitleData.Title title : titles.allTitles()) if (title.tier == realmTier) realmTitles.add(title);
        }
        if (realmTitles.isEmpty()) return false;

        if (realmTier > 0) {
            for (TitleData.Title title : realmTitles) {
                TitleData.Realm realm = new TitleData.Realm();
                realm.id = "emergent-" + title.id;
                realm.name = title.name;
                realm.ruler = "";
                realm.rgb = title.rgb;
                realm.titleKeys.add(title.key);
                realm.areaBlocks = title.areaBlocks;
                realm.relation = "independent";
                realm.props.put("emergence.generated", true);
                realm.props.put("emergence.source", "authored-title-bootstrap");
                realm.props.put("emergence.status", "unclaimed");
                titles.realms.put(realm.id, realm);
            }
        } else {
            // A province-only map still needs visible political regions. Group its
            // existing provinces geographically; do not fabricate or rewrite land.
            Map<Long, TitleData.Realm> groups = new LinkedHashMap<>();
            for (TitleData.Title province : realmTitles) {
                int gx = Math.min(7, Math.max(0, (int) (province.capNx * 8)));
                int gz = Math.min(7, Math.max(0, (int) (province.capNy * 8)));
                long key = (((long) gz) << 32) | (gx & 0xffffffffL);
                TitleData.Realm realm = groups.computeIfAbsent(key, ignored -> {
                    TitleData.Realm created = new TitleData.Realm();
                    created.id = "emergent-grid-" + gx + "-" + gz;
                    created.name = ROOTS[Math.floorMod(gx * 31 + gz * 17, ROOTS.length)] + " " + (gx + 1) + "-" + (gz + 1);
                    created.ruler = "";
                    created.rgb = color(gx, gz, 3);
                    created.relation = "independent";
                    created.props.put("emergence.generated", true);
                    created.props.put("emergence.source", "province-grid-bootstrap");
                    created.props.put("emergence.status", "unclaimed");
                    return created;
                });
                realm.titleKeys.add(province.key);
                realm.areaBlocks += province.areaBlocks;
            }
            for (TitleData.Realm realm : groups.values()) titles.realms.put(realm.id, realm);
        }
        titles.refreshRealmIndex();
        return !titles.realmList().isEmpty();
    }

    private static void link(Map<Integer, Set<Integer>> adjacency, Node first, Node second) {
        if (first == null || second == null || first.id == second.id || first.level != second.level) return;
        adjacency.get(first.id).add(second.id);
        adjacency.get(second.id).add(first.id);
    }

    private static boolean isLand(MapData map, int x, int z) {
        int biome = map.biomeAtPx(x, z);
        if (biome < 0 || biome >= map.keys.length) return false;
        String key = map.biomeKey(biome);
        if (key == null || key.contains("ocean") || key.equals("deep_ocean")
                || key.equals("shallow_sea") || key.equals("river") || key.equals("lake")) return false;
        double y = map.minY + map.rawHeightPx(x, z) / 65535.0 * (map.maxY - map.minY);
        return y > map.seaLevel + 0.5;
    }

    private static int firstProvince(Node node) {
        while (!node.children.isEmpty()) node = node.children.get(0);
        return node.id;
    }

    private static String name(int level, int x, int z) {
        String root = ROOTS[Math.floorMod(x * 31 + z * 17, ROOTS.length)];
        return switch (level) {
            case 0 -> "Провинция " + (x + 1) + "-" + (z + 1);
            case 1 -> "Графство " + root + " " + (x + 1) + "-" + (z + 1);
            case 2 -> "Герцогство " + root + " " + (x + 1) + "-" + (z + 1);
            default -> "Королевство " + root + " " + (x + 1) + "-" + (z + 1);
        };
    }

    private static int color(int x, int z, int level) {
        int hash = x * 0x45d9f3b ^ z * 0x119de1f3 ^ level * 0x27d4eb2d;
        hash ^= hash >>> 16;
        int r = 72 + Math.floorMod(hash, 152);
        int g = 72 + Math.floorMod(hash >>> 8, 152);
        int b = 72 + Math.floorMod(hash >>> 16, 152);
        return (r << 16) | (g << 8) | b;
    }
}
