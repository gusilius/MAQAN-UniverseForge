package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Authoritative settlement planning map. Coordinates are local block offsets from Colony centre.
 * Terrain is sampled by the Minecraft adapter; all zoning, road finding, plans and logistics run
 * deterministically without loading chunks, so unloaded settlements keep simulating safely.
 */
public final class SettlementMap {
    public static final int CELL_SIZE = 4, MAX_CELLS = 65536;
    public static final double MAX_BUILDING_SLOPE = 1.0, MAX_ROAD_SLOPE = 1.0;

    public enum Zone { NONE, RESIDENTIAL, AGRICULTURE, INDUSTRY, COMMERCE, CIVIC, MILITARY, STORAGE }
    public enum PlanState { PLANNED, BUILDING, COMPLETE, BLOCKED }

    public static final class Cell {
        public final int gx, gz, height;
        public final String biome;
        public final double fertility, slope;
        public final boolean water;
        Cell(int gx, int gz, int height, String biome, double fertility, double slope, boolean water) {
            this.gx = gx; this.gz = gz; this.height = height; this.biome = biome;
            this.fertility = fertility; this.slope = slope; this.water = water;
        }
        public int x() { return gx * CELL_SIZE + CELL_SIZE / 2; }
        public int z() { return gz * CELL_SIZE + CELL_SIZE / 2; }
    }

    public static final class Parcel {
        public final String id;
        public final int x, z, width, depth;
        public final Zone zone;
        public String owner = "";
        Parcel(String id, int x, int z, int width, int depth, Zone zone) {
            this.id = id; this.x = x; this.z = z; this.width = width; this.depth = depth; this.zone = zone;
        }
        public boolean contains(int px, int pz, int w, int d) {
            return px >= x && pz >= z && px + w <= x + width && pz + d <= z + depth;
        }
        public boolean overlaps(int ox, int oz, int ow, int od) {
            return x < ox + ow && x + width > ox && z < oz + od && z + depth > oz;
        }
    }

    public record Node(int gx, int gz) {}
    public static final class Road {
        public final int id;
        public final List<Node> path;
        public String surface = "gravel";
        public int builtSegments;
        Road(int id, List<Node> path) { this.id = id; this.path = new ArrayList<>(path); }
        public double length() { return Math.max(0, path.size() - 1) * CELL_SIZE; }
    }

    public static final class ConstructionPlan {
        public final int buildingUid;
        public final String buildingId;
        public final int x, z, width, depth;
        public int priority = 5;
        public double progress;
        public PlanState state = PlanState.PLANNED;
        ConstructionPlan(int uid, String id, int x, int z, int width, int depth) {
            this.buildingUid = uid; this.buildingId = id; this.x = x; this.z = z; this.width = width; this.depth = depth;
        }
    }

    public static final class Warehouse {
        public final String id;
        public final int x, z;
        public double capacity;
        public final Map<String, Double> stock = new TreeMap<>();
        Warehouse(String id, int x, int z, double capacity) { this.id = id; this.x = x; this.z = z; this.capacity = capacity; }
        public double used() { return stock.values().stream().mapToDouble(Double::doubleValue).sum(); }
        public double amount(String resource) { return stock.getOrDefault(resource, 0.0); }
    }

    public static final class LogisticsJob {
        public final int id;
        public final String from, to, resource;
        public double rate, accumulator, moved;
        public String status = "waiting";
        LogisticsJob(int id, String from, String to, String resource, double rate) {
            this.id = id; this.from = from; this.to = to; this.resource = resource; this.rate = rate;
        }
    }

    private final Map<String, Cell> cells = new TreeMap<>();
    public final Map<String, Parcel> parcels = new LinkedHashMap<>();
    public final Map<Integer, Road> roads = new LinkedHashMap<>();
    public final Map<Integer, ConstructionPlan> plans = new LinkedHashMap<>();
    public final Map<String, Warehouse> warehouses = new LinkedHashMap<>();
    public final Map<Integer, LogisticsJob> logistics = new LinkedHashMap<>();
    private int nextRoad = 1, nextLogistics = 1;
    public double simulationTime, totalGoodsMoved;
    public long simulationSteps;

    public SettlementMap() {
        warehouses.put("central", new Warehouse("central", 0, 0, Settlement.BASE_CAP));
    }

    private static String key(int gx, int gz) { return gx + "," + gz; }
    public static int grid(int block) { return (int) Math.floor((double) block / CELL_SIZE); }
    public Collection<Cell> cells() { return Collections.unmodifiableCollection(cells.values()); }
    public Cell cell(int gx, int gz) { return cells.get(key(gx, gz)); }
    public Cell cellAt(int x, int z) { return cell(grid(x), grid(z)); }

    public void sample(int gx, int gz, int height, String biome, double fertility, boolean water) {
        if (cells.size() >= MAX_CELLS && !cells.containsKey(key(gx, gz))) throw new IllegalArgumentException("Terrain map cell limit reached");
        if (height < -2048 || height > 4096 || !Double.isFinite(fertility)) throw new IllegalArgumentException("Invalid terrain sample");
        cells.put(key(gx, gz), new Cell(gx, gz, height, biome == null ? "unknown" : biome,
                clamp(fertility, 0, 1), 0, water));
        recomputeSlope(gx, gz);
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) recomputeSlope(gx + d[0], gz + d[1]);
    }

    private void recomputeSlope(int gx, int gz) {
        Cell c = cells.get(key(gx, gz));
        if (c == null) return;
        int max = 0;
        for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            Cell n = cells.get(key(gx + d[0], gz + d[1]));
            if (n != null) max = Math.max(max, Math.abs(c.height - n.height));
        }
        cells.put(key(gx, gz), new Cell(gx, gz, c.height, c.biome, c.fertility, max, c.water));
    }

    public Parcel zone(String id, int x, int z, int width, int depth, Zone zone) {
        if (id == null || id.isBlank() || id.length() > 64) throw new IllegalArgumentException("Parcel id must be 1..64 characters");
        if (zone == null || zone == Zone.NONE) throw new IllegalArgumentException("Choose a concrete land-use zone");
        if (width < CELL_SIZE || depth < CELL_SIZE || width > 2048 || depth > 2048) throw new IllegalArgumentException("Parcel dimensions must be 8..2048 blocks");
        requireTerrain(x, z, width, depth, MAX_BUILDING_SLOPE);
        for (Parcel p : parcels.values()) if (!p.id.equals(id) && p.overlaps(x, z, width, depth)) throw new IllegalArgumentException("Parcel overlaps " + p.id);
        Parcel p = new Parcel(id, x, z, width, depth, zone);
        parcels.put(id, p);
        return p;
    }

    public List<Node> findRoad(int startX, int startZ, int endX, int endZ) {
        Node start = new Node(grid(startX), grid(startZ)), goal = new Node(grid(endX), grid(endZ));
        Cell a = cell(start.gx, start.gz), b = cell(goal.gx, goal.gz);
        if (a == null || b == null) throw new IllegalArgumentException("Road endpoints are outside the sampled settlement map");
        if (a.water || b.water) throw new IllegalArgumentException("Road cannot start or end in water");
        Map<Node, Double> cost = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        PriorityQueue<NodeCost> open = new PriorityQueue<>(Comparator.comparingDouble(NodeCost::score).thenComparingInt(n -> n.node.gx).thenComparingInt(n -> n.node.gz));
        cost.put(start, 0.0); open.add(new NodeCost(start, heuristic(start, goal)));
        while (!open.isEmpty()) {
            Node current = open.poll().node;
            if (current.equals(goal)) return reconstruct(previous, current);
            double base = cost.get(current);
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                Node next = new Node(current.gx + d[0], current.gz + d[1]);
                Cell c = cell(next.gx, next.gz);
                if (c == null || c.water || c.slope > MAX_ROAD_SLOPE) continue;
                double step = 1.0 + c.slope * 0.45 + Math.max(0, 0.5 - c.fertility) * 0.15;
                double candidate = base + step;
                if (candidate < cost.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    cost.put(next, candidate); previous.put(next, current);
                    open.add(new NodeCost(next, candidate + heuristic(next, goal)));
                }
            }
        }
        throw new IllegalArgumentException("No passable road route connects those points");
    }

    private record NodeCost(Node node, double score) {}
    private static double heuristic(Node a, Node b) { return Math.abs(a.gx - b.gx) + Math.abs(a.gz - b.gz); }
    private static List<Node> reconstruct(Map<Node, Node> prev, Node at) {
        List<Node> path = new ArrayList<>();
        path.add(at);
        while (prev.containsKey(at)) { at = prev.get(at); path.add(at); }
        Collections.reverse(path);
        return path;
    }

    public Road addRoad(int startX, int startZ, int endX, int endZ) {
        List<Node> path = findRoad(startX, startZ, endX, endZ);
        Road road = new Road(nextRoad++, path);
        roads.put(road.id, road);
        return road;
    }

    public boolean isRoad(int gx, int gz) {
        for (Road road : roads.values()) for (Node n : road.path) if (n.gx == gx && n.gz == gz) return true;
        return false;
    }

    public void validatePlacement(String category, int centerX, int centerZ, int width, int depth) {
        width = Math.max(1, width); depth = Math.max(1, depth);
        int x = centerX - width / 2, z = centerZ - depth / 2;
        requireTerrain(x, z, width, depth, MAX_BUILDING_SLOPE);
        for (Parcel p : parcels.values()) if (p.overlaps(x, z, width, depth)) {
            if (!p.contains(x, z, width, depth)) throw new IllegalArgumentException("Building must fit entirely inside parcel " + p.id);
            if (!zoneAllows(p.zone, category)) throw new IllegalArgumentException("Zone " + p.zone + " does not permit " + category);
        }
        for (int gx = grid(x); gx <= grid(x + width - 1); gx++) for (int gz = grid(z); gz <= grid(z + depth - 1); gz++)
            if (isRoad(gx, gz)) throw new IllegalArgumentException("Building footprint overlaps a road");
    }

    public ConstructionPlan planBuilding(Building b) {
        if (b == null) throw new IllegalArgumentException("Building is required");
        int width = Math.max(1, b.def.sizeX), depth = Math.max(1, b.def.sizeZ);
        int x = b.x - width / 2, z = b.z - depth / 2;
        validatePlacement(b.def.category, b.x, b.z, width, depth);
        for (ConstructionPlan p : plans.values()) if (p.buildingUid != b.uid && overlaps(x, z, width, depth, p.x, p.z, p.width, p.depth))
            throw new IllegalArgumentException("Construction plan overlaps building #" + p.buildingUid);
        for (Parcel p : parcels.values()) if (p.overlaps(x, z, width, depth)) {
            if (!p.contains(x, z, width, depth)) throw new IllegalArgumentException("Building must fit entirely inside parcel " + p.id);
            if (!zoneAllows(p.zone, b.def.category)) throw new IllegalArgumentException("Zone " + p.zone + " does not permit " + b.def.category);
        }
        for (int gx = grid(x); gx <= grid(x + width - 1); gx++) for (int gz = grid(z); gz <= grid(z + depth - 1); gz++)
            if (isRoad(gx, gz)) throw new IllegalArgumentException("Building footprint overlaps a road");
        ConstructionPlan p = new ConstructionPlan(b.uid, b.def.id, x, z, width, depth);
        p.progress = b.progress;
        p.state = b.state == Building.State.DONE ? PlanState.COMPLETE : b.state == Building.State.BUILDING ? PlanState.BUILDING : PlanState.PLANNED;
        plans.put(b.uid, p);
        return p;
    }

    private static boolean zoneAllows(Zone zone, String category) {
        return switch (zone) {
            case RESIDENTIAL -> category.equals("housing") || category.equals("civic");
            case AGRICULTURE -> category.equals("food");
            case INDUSTRY -> category.equals("industry");
            case COMMERCE -> category.equals("trade");
            case CIVIC -> category.equals("civic") || category.equals("other");
            case MILITARY -> category.equals("military");
            case STORAGE -> category.equals("storage");
            case NONE -> true;
        };
    }

    private void requireTerrain(int x, int z, int width, int depth, double maxSlope) {
        int minX = grid(x), maxX = grid(x + width - 1), minZ = grid(z), maxZ = grid(z + depth - 1);
        for (int gx = minX; gx <= maxX; gx++) for (int gz = minZ; gz <= maxZ; gz++) {
            Cell c = cell(gx, gz);
            if (c == null) throw new IllegalArgumentException("Terrain is not sampled at " + (gx * CELL_SIZE) + ", " + (gz * CELL_SIZE));
            if (c.water) throw new IllegalArgumentException("Land parcel contains water");
            if (c.slope > maxSlope) throw new IllegalArgumentException("Terrain slope is too steep at " + c.x() + ", " + c.z());
        }
    }

    private static boolean overlaps(int x, int z, int w, int d, int ox, int oz, int ow, int od) {
        return x < ox + ow && x + w > ox && z < oz + od && z + d > oz;
    }

    public Warehouse addWarehouse(String id, int x, int z, double capacity) {
        if (id == null || id.isBlank() || id.length() > 64 || id.equals("central")) throw new IllegalArgumentException("Invalid warehouse id");
        if (!Double.isFinite(capacity) || capacity <= 0 || capacity > 1_000_000) throw new IllegalArgumentException("Warehouse capacity must be 0..1,000,000");
        Cell c = cellAt(x, z);
        if (c == null || c.water || c.slope > MAX_BUILDING_SLOPE) throw new IllegalArgumentException("Warehouse needs sampled, buildable land");
        if (warehouses.containsKey(id)) throw new IllegalArgumentException("Warehouse already exists: " + id);
        Warehouse w = new Warehouse(id, x, z, capacity);
        warehouses.put(id, w);
        return w;
    }

    public LogisticsJob addLogistics(String from, String to, String resource, double rate) {
        Warehouse a = warehouses.get(from), b = warehouses.get(to);
        if (a == null || b == null) throw new IllegalArgumentException("Unknown warehouse");
        if (from.equals(to)) throw new IllegalArgumentException("Logistics needs two different warehouses");
        if (resource == null || resource.isBlank() || resource.equals(Defs.SILVER)) throw new IllegalArgumentException("Choose a physical resource");
        if (!Double.isFinite(rate) || rate <= 0 || rate > 10000) throw new IllegalArgumentException("Rate must be 0..10000 units per minute");
        if (roadDistanceBetween(a.x, a.z, b.x, b.z) == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Both warehouses must connect to the same road network");
        for (LogisticsJob j : logistics.values()) if (j.from.equals(from) && j.to.equals(to) && j.resource.equals(resource))
            throw new IllegalArgumentException("This logistics route already exists");
        LogisticsJob job = new LogisticsJob(nextLogistics++, from, to, resource, rate);
        logistics.put(job.id, job);
        return job;
    }

    private int roadDistanceBetween(int ax, int az, int bx, int bz) {
        if (roads.isEmpty()) return Integer.MAX_VALUE;
        Node start = nearestRoad(grid(ax), grid(az)), goal = nearestRoad(grid(bx), grid(bz));
        if (start == null || goal == null) return Integer.MAX_VALUE;
        Set<Node> seen = new HashSet<>();
        ArrayDeque<Node> q = new ArrayDeque<>();
        Map<Node, Integer> distance = new HashMap<>();
        q.add(start); seen.add(start); distance.put(start, 0);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n.equals(goal)) {
                int access = Math.abs(grid(ax) - start.gx) + Math.abs(grid(az) - start.gz)
                        + Math.abs(grid(bx) - goal.gx) + Math.abs(grid(bz) - goal.gz);
                return (distance.get(n) + access) * CELL_SIZE;
            }
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                Node v = new Node(n.gx + d[0], n.gz + d[1]);
                if (isRoad(v.gx, v.gz) && seen.add(v)) {
                    distance.put(v, distance.get(n) + 1);
                    q.addLast(v);
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private Node nearestRoad(int gx, int gz) {
        Node best = null; int distance = Integer.MAX_VALUE;
        for (Road r : roads.values()) for (Node n : r.path) {
            int d = Math.abs(n.gx - gx) + Math.abs(n.gz - gz);
            if (d < distance) { distance = d; best = n; }
        }
        return distance <= 1 ? best : null;
    }

    /** Reconcile map warehouses against the canonical Settlement inventory after its production/consumption tick. */
    public void tick(Settlement settlement, double dt) {
        if (settlement == null || !Double.isFinite(dt) || dt <= 0) return;
        reconcileStorageBuildings(settlement);
        reconcileInventory(settlement);
        for (LogisticsJob job : logistics.values()) {
            Warehouse from = warehouses.get(job.from), to = warehouses.get(job.to);
            if (from == null || to == null) { job.status = "missing warehouse"; continue; }
            int distance = roadDistanceBetween(from.x, from.z, to.x, to.z);
            if (distance == Integer.MAX_VALUE) { job.status = "road disconnected"; continue; }
            double distanceAdjustedRate = job.rate / (1.0 + distance / 64.0);
            job.accumulator += distanceAdjustedRate * dt / 60.0;
            double amount = Math.floor(job.accumulator);
            if (amount < 1) { job.status = "en route"; continue; }
            double free = Math.max(0, to.capacity - to.used());
            double moved = Math.min(amount, Math.min(from.amount(job.resource), free));
            if (moved > 0) {
                from.stock.put(job.resource, from.amount(job.resource) - moved);
                to.stock.merge(job.resource, moved, Double::sum);
                job.accumulator -= moved; job.moved += moved; totalGoodsMoved += moved;
                job.status = "delivered";
            } else job.status = free < 1 ? "destination full" : "waiting for stock";
        }
        simulationTime += dt; simulationSteps++;
    }

    public void removeBuilding(int uid) {
        plans.remove(uid);
        String warehouseId = "building:" + uid;
        warehouses.remove(warehouseId);
    }

    private void reconcileStorageBuildings(Settlement settlement) {
        Set<String> activeStorage = new HashSet<>();
        for (Building b : settlement.buildings) {
            if (b.state == Building.State.DONE && !b.def.storage.isEmpty()) {
                String id = "building:" + b.uid;
                activeStorage.add(id);
                Warehouse w = warehouses.get(id);
                double capacity = b.def.storage.values().stream().mapToDouble(Integer::doubleValue).sum();
                if (w == null) {
                    w = new Warehouse(id, b.x, b.z, capacity);
                    warehouses.put(id, w);
                } else {
                    w.capacity = capacity;
                }
            }
            ConstructionPlan p = plans.get(b.uid);
            if (p == null && cells.size() > 0) {
                try { p = planBuilding(b); } catch (IllegalArgumentException ignored) { /* legacy/out-of-map buildings remain visible but are not replanned */ }
            }
            if (p != null) {
                p.progress = b.progress;
                p.state = b.state == Building.State.DONE ? PlanState.COMPLETE : b.state == Building.State.BUILDING ? PlanState.BUILDING : PlanState.PLANNED;
            }
        }
        plans.keySet().removeIf(uid -> settlement.building(uid) == null);
        warehouses.keySet().removeIf(id -> id.startsWith("building:") && !activeStorage.contains(id));
    }

    /** Newly produced goods enter the nearest completed storage node to a building producing that resource. */
    private Warehouse productionWarehouse(Settlement settlement, String resource, Warehouse fallback) {
        Building producer = null;
        for (Building b : settlement.buildings) {
            if (b.state == Building.State.DONE && !b.paused && b.def.out.getOrDefault(resource, 0) > 0) {
                producer = b;
                break;
            }
        }
        if (producer == null) return fallback;
        Warehouse best = fallback;
        double distance = Double.POSITIVE_INFINITY;
        for (Warehouse warehouse : warehouses.values()) {
            if (warehouse == fallback || warehouse.capacity - warehouse.used() < 1) continue;
            double dx = warehouse.x - producer.x, dz = warehouse.z - producer.z;
            double d = dx * dx + dz * dz;
            if (d < distance) { distance = d; best = warehouse; }
        }
        return best;
    }

    private void reconcileInventory(Settlement settlement) {
        Warehouse central = warehouses.get("central");
        if (central == null) { central = new Warehouse("central", 0, 0, Settlement.BASE_CAP); warehouses.put("central", central); }
        Set<String> resources = new TreeSet<>(settlement.stock.keySet());
        for (Warehouse w : warehouses.values()) resources.addAll(w.stock.keySet());
        for (String resource : resources) {
            double total = 0;
            for (Warehouse w : warehouses.values()) total += w.amount(resource);
            double target = settlement.amount(resource);
            double delta = target - total;
            if (delta > 0.000001) {
                Warehouse destination = productionWarehouse(settlement, resource, central);
                destination.stock.merge(resource, delta, Double::sum);
            } else if (delta < -0.000001) {
                double remove = -delta;
                List<Warehouse> ordered = new ArrayList<>(warehouses.values());
                ordered.sort(Comparator.comparing(w -> w.id.equals("central") ? "" : w.id));
                for (Warehouse w : ordered) {
                    double take = Math.min(remove, w.amount(resource));
                    if (take > 0) w.stock.put(resource, w.amount(resource) - take);
                    remove -= take;
                    if (remove <= 0.000001) break;
                }
            }
        }
    }

    public String toJson() {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("format","wf-settlement-map/1"); root.put("nextRoad",nextRoad); root.put("nextLogistics",nextLogistics);
        root.put("simulationTime",simulationTime); root.put("totalGoodsMoved",totalGoodsMoved); root.put("simulationSteps",simulationSteps);
        List<Object> terrain = new ArrayList<>();
        for (Cell c : cells.values()) terrain.add(Map.of("gx",c.gx,"gz",c.gz,"height",c.height,"biome",c.biome,"fertility",c.fertility,"slope",c.slope,"water",c.water));
        root.put("terrain",terrain);
        List<Object> parcelData = new ArrayList<>();
        for (Parcel p : parcels.values()) parcelData.add(Map.of("id",p.id,"x",p.x,"z",p.z,"width",p.width,"depth",p.depth,"zone",p.zone.name(),"owner",p.owner));
        root.put("parcels",parcelData);
        List<Object> roadData = new ArrayList<>();
        for (Road r : roads.values()) {
            List<Object> nodes = new ArrayList<>(); for (Node n : r.path) nodes.add(List.of(n.gx,n.gz));
            roadData.add(Map.of("id",r.id,"surface",r.surface,"builtSegments",r.builtSegments,"path",nodes));
        }
        root.put("roads",roadData);
        List<Object> planData = new ArrayList<>();
        for (ConstructionPlan p : plans.values()) planData.add(Map.of("uid",p.buildingUid,"building",p.buildingId,"x",p.x,"z",p.z,"width",p.width,"depth",p.depth,"priority",p.priority,"progress",p.progress,"state",p.state.name()));
        root.put("plans",planData);
        List<Object> warehouseData = new ArrayList<>();
        for (Warehouse w : warehouses.values()) warehouseData.add(Map.of("id",w.id,"x",w.x,"z",w.z,"capacity",w.capacity,"stock",new TreeMap<>(w.stock)));
        root.put("warehouses",warehouseData);
        List<Object> jobs = new ArrayList<>();
        for (LogisticsJob j : logistics.values()) jobs.add(Map.of("id",j.id,"from",j.from,"to",j.to,"resource",j.resource,"rate",j.rate,"accumulator",j.accumulator,"moved",j.moved,"status",j.status));
        root.put("logistics",jobs);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static SettlementMap fromJson(String json) {
        Map<String,Object> root = (Map<String,Object>) Json.parse(json);
        if (!"wf-settlement-map/1".equals(root.get("format"))) throw new IllegalArgumentException("Unsupported settlement map format");
        SettlementMap map = new SettlementMap();
        map.cells.clear(); map.parcels.clear(); map.roads.clear(); map.plans.clear(); map.warehouses.clear(); map.logistics.clear();
        map.nextRoad = number(root.get("nextRoad"),1); map.nextLogistics = number(root.get("nextLogistics"),1);
        map.simulationTime = decimal(root.get("simulationTime"),0); map.totalGoodsMoved = decimal(root.get("totalGoodsMoved"),0); map.simulationSteps = number(root.get("simulationSteps"),0L);
        if (root.get("terrain") instanceof List<?> list) for (Object o : list) {
            Map<String,Object> m = (Map<String,Object>)o;
            map.cells.put(key(number(m.get("gx"),0),number(m.get("gz"),0)),new Cell(number(m.get("gx"),0),number(m.get("gz"),0),number(m.get("height"),0),String.valueOf(m.get("biome")),decimal(m.get("fertility"),0),decimal(m.get("slope"),0),Boolean.TRUE.equals(m.get("water"))));
        }
        if (root.get("parcels") instanceof List<?> list) for (Object o : list) {
            Map<String,Object> m = (Map<String,Object>)o;
            Parcel p = new Parcel(String.valueOf(m.get("id")),number(m.get("x"),0),number(m.get("z"),0),number(m.get("width"),8),number(m.get("depth"),8),Zone.valueOf(String.valueOf(m.get("zone"))));
            p.owner = String.valueOf(m.getOrDefault("owner","")); map.parcels.put(p.id,p);
        }
        if (root.get("roads") instanceof List<?> list) for (Object o : list) {
            Map<String,Object> m = (Map<String,Object>)o; List<Node> path = new ArrayList<>();
            if (m.get("path") instanceof List<?> nodes) for (Object raw : nodes) if (raw instanceof List<?> pair && pair.size() >= 2) path.add(new Node(((Number)pair.get(0)).intValue(),((Number)pair.get(1)).intValue()));
            Road r = new Road(number(m.get("id"),0),path); r.surface = String.valueOf(m.getOrDefault("surface","gravel")); r.builtSegments = Math.max(0, Math.min(Math.max(0, path.size()-1), number(m.get("builtSegments"),0))); map.roads.put(r.id,r);
        }
        if (root.get("plans") instanceof List<?> list) for (Object o : list) {
            Map<String,Object> m = (Map<String,Object>)o;
            ConstructionPlan p = new ConstructionPlan(number(m.get("uid"),0),String.valueOf(m.get("building")),number(m.get("x"),0),number(m.get("z"),0),number(m.get("width"),1),number(m.get("depth"),1));
            p.priority=number(m.get("priority"),5); p.progress=decimal(m.get("progress"),0); p.state=PlanState.valueOf(String.valueOf(m.getOrDefault("state","PLANNED"))); map.plans.put(p.buildingUid,p);
        }
        if (root.get("warehouses") instanceof List<?> list) for (Object o : list) {
            Map<String,Object> m = (Map<String,Object>)o;
            Warehouse w = new Warehouse(String.valueOf(m.get("id")),number(m.get("x"),0),number(m.get("z"),0),decimal(m.get("capacity"),Settlement.BASE_CAP));
            if (m.get("stock") instanceof Map<?,?> stock) for (var e : stock.entrySet()) if (e.getKey() instanceof String k && e.getValue() instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0) w.stock.put(k,n.doubleValue());
            map.warehouses.put(w.id,w);
        }
        if (!map.warehouses.containsKey("central")) map.warehouses.put("central",new Warehouse("central",0,0,Settlement.BASE_CAP));
        if (root.get("logistics") instanceof List<?> list) for (Object o : list) {
            Map<String,Object> m = (Map<String,Object>)o;
            LogisticsJob j = new LogisticsJob(number(m.get("id"),0),String.valueOf(m.get("from")),String.valueOf(m.get("to")),String.valueOf(m.get("resource")),decimal(m.get("rate"),0));
            j.accumulator=decimal(m.get("accumulator"),0); j.moved=decimal(m.get("moved"),0); j.status=String.valueOf(m.getOrDefault("status","waiting")); map.logistics.put(j.id,j);
        }
        return map;
    }

    private static int number(Object o,int fallback) { return o instanceof Number n ? n.intValue() : fallback; }
    private static long number(Object o,long fallback) { return o instanceof Number n ? n.longValue() : fallback; }
    private static double decimal(Object o,double fallback) { return o instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : fallback; }
    private static double clamp(double v,double min,double max) { return Math.max(min,Math.min(max,v)); }
}
