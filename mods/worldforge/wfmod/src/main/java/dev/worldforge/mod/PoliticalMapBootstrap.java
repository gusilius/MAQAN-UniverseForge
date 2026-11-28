package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        long pixels = (long) map.w * map.h;
        if (map.w <= 0 || map.h <= 0 || pixels > 16L * 1024L * 1024L)
            throw new IllegalArgumentException("Political bootstrap map dimensions exceed the 16M pixel budget");

        // At most 64x64 base cells: a predictable number of titles on large planets.
        int cellSize = Math.max(4, (Math.max(map.w, map.h) + 63) / 64);
        int cellsX = (map.w + cellSize - 1) / cellSize;
        int cellsZ = (map.h + cellSize - 1) / cellSize;
        int cellCount = Math.multiplyExact(cellsX, cellsZ);
        long[] counts = new long[cellCount], sumX = new long[cellCount], sumZ = new long[cellCount];
        long totalLand = 0;

        for (int z = 0; z < map.h; z++) {
            for (int x = 0; x < map.w; x++) {
                if (!isLand(map, x, z)) continue;
                int cell = (z / cellSize) * cellsX + x / cellSize;
                counts[cell]++;
                sumX[cell] += x;
                sumZ[cell] += z;
                totalLand++;
            }
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
                parent.sumX += child.sumX;
                parent.sumZ += child.sumZ;
                child.parentId = parent.id;
            }
        }

        short[] provincePixels = new short[(int) pixels];
        for (int z = 0; z < map.h; z++) {
            for (int x = 0; x < map.w; x++) {
                if (!isLand(map, x, z)) continue;
                Node province = baseByCell[(z / cellSize) * cellsX + x / cellSize];
                if (province != null) provincePixels[z * map.w + x] = (short) province.id;
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

        for (int level = 0; level < levels.length; level++) {
            for (Node node : levels[level]) {
                double nx = (node.centerX() + 0.5) / map.w;
                double nz = (node.centerZ() + 0.5) / map.h;
                long area = Math.max(1L, Math.round(node.landPixels * map.bpp * map.bpp));
                Map<String, Object> capital = new LinkedHashMap<>();
                capital.put("x", Math.round((node.centerX() + 0.5 - map.w * 0.5) * map.bpp));
                capital.put("z", Math.round((node.centerZ() + 0.5 - map.h * 0.5) * map.bpp));
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
                row.put("children", children); row.put("neighbors", List.of()); row.put("props", props);
                titleRows.add(row);
            }
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
            realm.put("area_blocks", Math.max(1L, Math.round(kingdom.landPixels * map.bpp * map.bpp)));
            realm.put("props", props);
            realmRows.add(realm);
        }

        Map<String, Object> titlesRoot = new LinkedHashMap<>();
        titlesRoot.put("format", "worldforge-titles/1");
        titlesRoot.put("size_px", List.of(map.w, map.h));
        titlesRoot.put("blocks_per_pixel", map.bpp);
        titlesRoot.put("tiers", tierRows); titlesRoot.put("titles", titleRows);

        Map<String, Object> realmsRoot = new LinkedHashMap<>();
        realmsRoot.put("format", "worldforge-realms/1");
        realmsRoot.put("relation_types", Map.of());
        realmsRoot.put("realms", realmRows);
        realmsRoot.put("relations", List.of());

        return TitleData.create(Json.write(titlesRoot), Json.write(realmsRoot),
                provincePixels, map.w, map.h);
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
