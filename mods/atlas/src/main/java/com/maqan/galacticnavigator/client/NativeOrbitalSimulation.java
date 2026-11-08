package com.maqan.galacticnavigator.client;

import com.google.gson.*;
import java.util.*;

/**
 * MAQAN-native N-body orbital simulation, adapted from the VS Orbit model.
 *
 * Uses optional MAQAN physics fields (mass, astronomical_compute, pos, speed)
 * when a datapack supplies complete initial conditions. Otherwise it provides a
 * presentation-only Kepler orbit around an explicitly linked parent, or around
 * the unique star in a single-star system. Cosmic Horizons' standard position
 * fields remain the source of the initial orbital geometry.
 */
public final class NativeOrbitalSimulation {
    private static final double G = 6.676E-11;
    private static final double DT = 0.05; // one Minecraft tick, 20 ticks per second
    private static final int MAX_CATCH_UP = 1200;
    private static final Map<String, State> SYSTEMS = new HashMap<>();
    private static net.minecraft.world.level.Level lastLevel;

    private NativeOrbitalSimulation() {}

    /** Immutable, same-clock sample of every uniquely identified body in one system. */
    public record OrbitalSnapshot(String systemId, double gameTime, double simulationTicks,
                                  boolean orbitalMotionEnabled, Map<String, double[]> positions) {
        public OrbitalSnapshot {
            Map<String, double[]> copy = new LinkedHashMap<>();
            if (positions != null) positions.forEach((id, value) -> {
                if (id != null && value != null && value.length == 3 && finite(value))
                    copy.put(id, value.clone());
            });
            positions = Collections.unmodifiableMap(copy);
        }

        @Override public Map<String, double[]> positions() {
            Map<String, double[]> copy = new LinkedHashMap<>();
            positions.forEach((id, value) -> copy.put(id, value.clone()));
            return Collections.unmodifiableMap(copy);
        }

        public Optional<double[]> position(String bodyId) {
            double[] value = positions.get(bodyId);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
    }

    /**
     * Captures the authoritative simulation once at a single game-time value.
     * Coordinate calls share the simulator's frame cache, so all axes and bodies
     * are sampled against the same simulation clock without advancing per axis.
     */
    public static synchronized OrbitalSnapshot snapshot(String systemId, JsonObject system,
                                                         double gameTime) {
        if (systemId == null || systemId.isBlank() || system == null
                || !Double.isFinite(gameTime))
            return new OrbitalSnapshot(systemId == null ? "" : systemId, gameTime,
                    Double.NaN, false, Map.of());

        JsonObject bodies = object(system, "planets");
        Map<String, double[]> positions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : bodies.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject body = entry.getValue().getAsJsonObject();
            String id = bodyId(body);
            if (id.isBlank() || positions.containsKey(id)) continue;
            double[] position = new double[]{
                    coordinate(systemId, system, body, 0, gameTime),
                    coordinate(systemId, system, body, 1, gameTime),
                    coordinate(systemId, system, body, 2, gameTime)
            };
            if (finite(position)) positions.put(id, position);
        }

        net.minecraft.world.level.Level level = net.minecraft.client.Minecraft.getInstance().level;
        double ticks = CosmicMotionClock.orbitTicks(level, gameTime);
        CosmicOrbitSettings.Config settings = CosmicOrbitSettings.get();
        boolean enabled = settings.enabled && settings.orbitalMotion;
        return new OrbitalSnapshot(systemId, gameTime, ticks, enabled, positions);
    }

    public static synchronized double coordinate(String systemId, JsonObject system,
                                                  JsonObject body, int axis, double gameTime) {
        if (systemId == null || system == null || body == null) return sourceCoordinate(body, axis);
        net.minecraft.world.level.Level currentLevel = net.minecraft.client.Minecraft.getInstance().level;
        if (currentLevel != lastLevel) {
            SYSTEMS.clear();
            lastLevel = currentLevel;
        }
        CosmicOrbitSettings.Config orbitSettings = CosmicOrbitSettings.get();
        double sharedOrbitTicks = CosmicMotionClock.orbitTicks(currentLevel, gameTime);
        if (!orbitSettings.enabled || !orbitSettings.orbitalMotion) {
            State paused = SYSTEMS.get(systemId);
            if (paused != null) {
                paused.lastClockTime = gameTime;
                paused.lastSimulationTicks = sharedOrbitTicks;
                paused.dynamicAccumulator = 0.0;
                paused.frameTime = Double.NaN;
                paused.framePositions.clear();
            }
            return sourceCoordinate(body, axis);
        }
        JsonObject planetObjects = object(system, "planets");
        State state = SYSTEMS.get(systemId);
        // GalaxyCatalog replaces this JSON object on datapack reload. Identity
        // comparison avoids serializing the entire catalogue on every frame.
        if (state == null || state.sourceObjects != planetObjects || gameTime < state.lastClockTime) {
            state = createState(planetObjects, gameTime, sharedOrbitTicks);
            SYSTEMS.put(systemId, state);
        }
        advance(state, sharedOrbitTicks);
        state.simulationTicks = sharedOrbitTicks;
        if (axis < 0 || axis > 2) return 0.0;
        if (Double.compare(state.frameTime, gameTime) != 0) {
            state.frameTime = gameTime;
            state.framePositions.clear();
        }
        double[] position = calculatePosition(systemId, state, planetObjects, body, gameTime,
                new HashSet<>(), 0);
        return position[axis];
    }

    /**
     * Creates a reusable forecast session based on a copy of the authoritative
     * live simulation. Multiple obstacle ephemerides can sample the same future
     * time without cloning or advancing the live simulation repeatedly.
     */
    public static synchronized ForecastSession forecastSession(String systemId,JsonObject system,
                                                                 double currentGameTime){
        if(systemId==null||system==null||!Double.isFinite(currentGameTime))return null;
        JsonObject objects=object(system,"planets");
        JsonObject firstBody=null;
        for(Map.Entry<String,JsonElement> entry:objects.entrySet()){
            JsonElement element=entry.getValue();
            if(element.isJsonObject()){firstBody=element.getAsJsonObject();break;}
        }
        if(firstBody==null)return null;
        coordinate(systemId,system,firstBody,0,currentGameTime);
        net.minecraft.world.level.Level level=net.minecraft.client.Minecraft.getInstance().level;
        if(level==null)return null;
        CosmicOrbitSettings.Config settings=CosmicOrbitSettings.get();
        double currentTicks=CosmicMotionClock.orbitTicks(level,currentGameTime);
        boolean motionEnabled=settings.enabled&&settings.orbitalMotion;
        State live=SYSTEMS.get(systemId);
        if(!motionEnabled){
            State staticState=createState(objects,currentGameTime,currentTicks);
            return new ForecastSession(systemId,objects,staticState,currentGameTime,
                    currentTicks,false,0.0);
        }
        if(live==null||live.sourceObjects!=objects)return null;
        return new ForecastSession(systemId,objects,copyState(live),currentGameTime,
                currentTicks,true,NavigatorPreferences.orbitMultiplier()*settings.simulationSpeed);
    }

    public static final class ForecastSession {
        private final String systemId;
        private final JsonObject objects;
        private final State baseState;
        private final double baseGameTime,baseSimulationTicks,simulationRate;
        private final boolean enabled;
        private State state;
        private double currentSimulationTicks;
        private double lastGameTime=Double.NaN;

        private ForecastSession(String systemId,JsonObject objects,State baseState,
                                double baseGameTime,double baseSimulationTicks,
                                boolean enabled,double simulationRate){
            this.systemId=systemId;this.objects=objects;this.baseState=baseState;
            this.baseGameTime=baseGameTime;this.baseSimulationTicks=baseSimulationTicks;
            this.enabled=enabled;this.simulationRate=simulationRate;
            this.state=copyState(baseState);
            this.currentSimulationTicks=baseSimulationTicks;
        }

        public synchronized double[] positionAt(JsonObject body,double targetGameTime){
            if(body==null||!Double.isFinite(targetGameTime))return sourcePosition(body);
            targetGameTime=Math.max(baseGameTime,targetGameTime);
            if(!enabled)return sourcePosition(body);
            if(Double.isFinite(lastGameTime)&&targetGameTime<lastGameTime-1.0E-9){
                state=copyState(baseState);
                currentSimulationTicks=baseSimulationTicks;
                state.frameTime=Double.NaN;
                state.framePositions.clear();
            }
            double targetTicks=baseSimulationTicks+(targetGameTime-baseGameTime)*simulationRate;
            if(targetTicks>currentSimulationTicks){
                advance(state,targetTicks);
                currentSimulationTicks=targetTicks;
            }
            state.simulationTicks=targetTicks;
            if(Double.compare(state.frameTime,targetGameTime)!=0){
                state.frameTime=targetGameTime;
                state.framePositions.clear();
            }
            lastGameTime=targetGameTime;
            return calculatePosition(systemId,state,objects,body,targetGameTime,
                    new HashSet<>(),0);
        }
    }

    /**
     * Samples a future position without advancing the live shared clock or
     * mutating the state used by the in-world renderer and Navigator.
     *
     * Both time arguments are Minecraft game ticks (including partial ticks).
     */
    public static synchronized double forecastCoordinate(String systemId,JsonObject system,
                                                          JsonObject body,int axis,
                                                          double currentGameTime,double targetGameTime){
        if(systemId==null||system==null||body==null||axis<0||axis>2
                ||!Double.isFinite(currentGameTime)||!Double.isFinite(targetGameTime))
            return sourceCoordinate(body,axis);
        if(targetGameTime<currentGameTime)targetGameTime=currentGameTime;
        net.minecraft.world.level.Level level=net.minecraft.client.Minecraft.getInstance().level;
        if(level==null)return sourceCoordinate(body,axis);

        // Bring the authoritative live state exactly to departure time first.
        coordinate(systemId,system,body,0,currentGameTime);
        CosmicOrbitSettings.Config settings=CosmicOrbitSettings.get();
        if(!settings.enabled||!settings.orbitalMotion)return sourceCoordinate(body,axis);

        JsonObject objects=object(system,"planets");
        State live=SYSTEMS.get(systemId);
        if(live==null||live.sourceObjects!=objects)return sourceCoordinate(body,axis);
        double currentTicks=CosmicMotionClock.orbitTicks(level,currentGameTime);
        double futureTicks=currentTicks+(targetGameTime-currentGameTime)
                *NavigatorPreferences.orbitMultiplier()*settings.simulationSpeed;
        State forecast=copyState(live);
        advance(forecast,futureTicks);
        forecast.simulationTicks=futureTicks;
        forecast.frameTime=targetGameTime;
        forecast.framePositions.clear();
        return calculatePosition(systemId,forecast,objects,body,targetGameTime,
                new HashSet<>(),0)[axis];
    }

    private static State copyState(State source){
        State copy=new State(source.sourceObjects,source.lastClockTime,source.lastSimulationTicks);
        copy.simulationTicks=source.simulationTicks;
        copy.dynamicAccumulator=source.dynamicAccumulator;
        copy.frameTime=source.frameTime;
        for(Map.Entry<String,BodyState> entry:source.bodies.entrySet()){
            BodyState body=entry.getValue();
            copy.bodies.put(entry.getKey(),new BodyState(body.position,body.velocity,body.mass,
                    body.compute,body.dynamic,body.collisionRadius,body.collisionAvoidance));
        }
        source.framePositions.forEach((key,value)->copy.framePositions.put(key,value.clone()));
        return copy;
    }

    private static double[] calculatePosition(String systemId, State state, JsonObject objects,
                                              JsonObject body, double gameTime,
                                              Set<String> visited, int depth) {
        double[] authored = sourcePosition(body);
        String id = bodyId(body);
        double[] cached = state.framePositions.get(id);
        if (cached != null) return cached.clone();
        if (depth > 12 || id.isBlank() || !visited.add(id)) return authored;

        // GUI-category entries may be aliases of one physical planet.
        // catalog_id preserves the actual key used by system["planets"].
        BodyState simulated = state.bodies.get(id);
        if (simulated != null && simulated.dynamic) {
            state.framePositions.put(id, simulated.position.clone());
            return simulated.position.clone();
        }

        JsonObject orbit = orbitData(body);
        CosmicOrbitSettings.BodyOverride bodyOverride = CosmicOrbitSettings.get().findBody(systemId, id);
        if (bodyOverride == null) bodyOverride = new CosmicOrbitSettings.BodyOverride();
        if (!bodyOverride.orbitEnabled) return authored;
        String manualParent = !bodyOverride.parentId.isBlank()
                ? bodyOverride.parentId : NavigatorPreferences.manualParent(systemId, id);
        String parentReference = !manualParent.isBlank() ? manualParent : firstString(orbit, body,
                "orbit_parent_id", "orbit_parent", "parent_id", "parent",
                "parent_dimension_id", "satellite_of", "parent_body", "central_body");
        String parentId = null;
        JsonObject parent = null;
        if (!parentReference.isBlank()) {
            // Resolve exact object IDs first. A dimension is accepted only if
            // exactly one body owns it; attached system dimensions are often shared.
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject candidate = entry.getValue().getAsJsonObject();
                if (entry.getKey().equals(parentReference)
                        || parentReference.equals(string(candidate, "catalog_id", ""))
                        || parentReference.equals(string(candidate, "id", ""))) {
                    parentId = entry.getKey();
                    parent = candidate;
                    break;
                }
            }
            if (parent == null) {
                for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject candidate = entry.getValue().getAsJsonObject();
                    String dimension = string(candidate, "dimension",
                            string(candidate, "dimension_id",
                                    string(candidate, "attached_dimension_id", "")));
                    if (!parentReference.equals(dimension)) continue;
                    if (parent != null) { parent = null; parentId = null; break; }
                    parentId = entry.getKey();
                    parent = candidate;
                }
            }
        }
        if (parentId != null && parentId.equals(id)) {
            parent = null;
            parentId = null;
        }
        if (parent == null && isMoon(body)) {
            // A moon can use the only planet in a simple system as its parent.
            // Multi-planet systems require explicit parent metadata.
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject candidate = entry.getValue().getAsJsonObject();
                if (!"planet".equalsIgnoreCase(string(candidate, "kind", ""))) continue;
                if (parent != null) return authored;
                parentId = entry.getKey();
                parent = candidate;
            }
        } else if (parent == null && canDefaultOrbit(body)) {
            // A unique star is an unambiguous primary. Binary/multiple-star
            // systems are deliberately left alone unless a parent is authored.
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject candidate = entry.getValue().getAsJsonObject();
                if (!isStar(candidate)) continue;
                if (parent != null) return authored;
                parentId = entry.getKey();
                parent = candidate;
            }
        }
        if (parent == null || parentId == null || parentId.equals(id)) return authored;

        double[] parentPosition = calculatePosition(systemId, state, objects, parent, gameTime,
                visited, depth + 1);
        double[] parentAuthored = sourcePosition(parent);
        double dx = authored[0] - parentAuthored[0];
        double dy = authored[1] - parentAuthored[1];
        double dz = authored[2] - parentAuthored[2];
        double sourceRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double manualRadius = bodyOverride.orbitRadius > 0.0
                ? bodyOverride.orbitRadius : NavigatorPreferences.manualOrbitRadius(systemId, id);
        double radiusOverride = manualRadius > 0.0 ? manualRadius : firstNumber(orbit, body, Double.NaN,
                "orbit_radius", "orbital_distance", "semi_major_axis");
        if (!Double.isFinite(sourceRadius) || sourceRadius <= 0.0) return authored;
        boolean hasRadiusOverride = Double.isFinite(radiusOverride) && radiusOverride > 0.0;

        double eccentricity = bodyOverride.useOrbitOverrides ? bodyOverride.eccentricity
                : firstNumber(orbit, body, 0.0, "eccentricity");
        eccentricity = Math.max(0.0, Math.min(0.95, eccentricity));
        double inclinationDegrees = bodyOverride.useOrbitOverrides
                ? bodyOverride.inclinationDegrees : firstNumber(orbit, body, Double.NaN,
                "inclination_degrees", "orbit_inclination_degrees", "inclination");
        // If no orbital plane is authored, derive a plane containing the
        // original parent/body vector instead of snapping a tilted body to Y=parentY.
        if (!Double.isFinite(inclinationDegrees))
            inclinationDegrees = Math.toDegrees(Math.atan2(-dy, dz));
        double inclination = Math.toRadians(inclinationDegrees);
        double ascendingNode = Math.toRadians(firstNumber(orbit, body, 0.0,
                "ascending_node_degrees", "ascending_node"));
        double periapsis = Math.toRadians(firstNumber(orbit, body, 0.0,
                "argument_of_periapsis_degrees", "argument_of_periapsis"));

        // Recover the authored true anomaly in the selected orbital plane.
        double zUninclined = -dy * Math.sin(inclination) + dz * Math.cos(inclination);
        double xNodeLocal = dx * Math.cos(ascendingNode) - zUninclined * Math.sin(ascendingNode);
        double zNodeLocal = dx * Math.sin(ascendingNode) + zUninclined * Math.cos(ascendingNode);
        double xLocal = xNodeLocal * Math.cos(periapsis) + zNodeLocal * Math.sin(periapsis);
        double zLocal = -xNodeLocal * Math.sin(periapsis) + zNodeLocal * Math.cos(periapsis);
        double sourceTrueAnomaly = Math.atan2(zLocal, xLocal);

        double phaseDegrees = bodyOverride.useOrbitOverrides
                ? bodyOverride.phaseDegrees : firstNumber(orbit, body, Double.NaN,
                "phase_degrees", "orbit_phase_degrees", "orbit_phase");
        double meanAnomaly;
        if (Double.isFinite(phaseDegrees)) {
            meanAnomaly = Math.toRadians(phaseDegrees);
        } else {
            double eccentricAnomaly = 2.0 * Math.atan2(
                    Math.sqrt(1.0 - eccentricity) * Math.sin(sourceTrueAnomaly / 2.0),
                    Math.sqrt(1.0 + eccentricity) * Math.cos(sourceTrueAnomaly / 2.0));
            meanAnomaly = eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly);
        }

        // If the datapack only gives a position, derive the semi-major axis
        // from that instantaneous radius and eccentricity. This guarantees
        // that the first animated frame remains at the authored coordinates.
        double radius = hasRadiusOverride ? radiusOverride : sourceRadius;
        if (!hasRadiusOverride && eccentricity > 0.0 && !Double.isFinite(phaseDegrees)) {
            radius = sourceRadius * (1.0 + eccentricity * Math.cos(sourceTrueAnomaly))
                    / (1.0 - eccentricity * eccentricity);
        }
        // One shared collision-aware orbit solution feeds both Navigator and
        // Cosmic Horizons' native in-world render adapter.
        radius = OrbitalCollisionAvoidance.separateSiblingOrbit(
                objects, body, parentId, parent, radius, eccentricity);

        double periodTicks = bodyOverride.orbitPeriodTicks > 0.0
                ? bodyOverride.orbitPeriodTicks : firstNumber(orbit, body, Double.NaN,
                "period_ticks", "orbital_period_ticks", "orbit_period_ticks");
        if (!Double.isFinite(periodTicks) || periodTicks <= 0.0) {
            double periodSeconds = firstNumber(orbit, body, Double.NaN,
                    "period_seconds", "orbital_period_seconds", "orbit_period_seconds");
            if (Double.isFinite(periodSeconds) && periodSeconds > 0.0)
                periodTicks = periodSeconds * 20.0;
        }
        if (!Double.isFinite(periodTicks) || periodTicks <= 0.0) {
            // Generic orbital_period fields are interpreted as real-world days.
            double periodDays = firstNumber(orbit, body, Double.NaN,
                    "period_days", "orbital_period_days", "orbit_period_days",
                    "orbital_period", "orbit_period");
            if (Double.isFinite(periodDays) && periodDays > 0.0)
                periodTicks = periodDays * 24000.0;
        }
        if (!Double.isFinite(periodTicks) || periodTicks <= 0.0) {
            // Configurable presentation fallback; never represented as an
            // astrophysical measurement when the datapack has no period.
            double fallback = isMoon(body)
                    ? CosmicOrbitSettings.get().defaultMoonOrbitTicks
                    : CosmicOrbitSettings.get().defaultPlanetOrbitTicks;
            periodTicks = Math.max(400.0,
                    Math.min(2400000.0, fallback * Math.sqrt(Math.max(0.01, radius / 10000.0))));
        }

        double elapsedTicks = Math.max(0.0, state.simulationTicks);
        double mean = meanAnomaly + (Math.PI * 2.0 * elapsedTicks / periodTicks);
        mean = Math.IEEEremainder(mean, Math.PI * 2.0);
        double eccentricAnomaly = mean;
        for (int i = 0; i < 8; i++)
            eccentricAnomaly -= (eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - mean)
                    / (1.0 - eccentricity * Math.cos(eccentricAnomaly));

        double xOrbit = radius * (Math.cos(eccentricAnomaly) - eccentricity);
        double zOrbit = radius * Math.sqrt(1.0 - eccentricity * eccentricity)
                * Math.sin(eccentricAnomaly);
        double xPeriapsis = xOrbit * Math.cos(periapsis) - zOrbit * Math.sin(periapsis);
        double zPeriapsis = xOrbit * Math.sin(periapsis) + zOrbit * Math.cos(periapsis);
        double xNode = xPeriapsis * Math.cos(ascendingNode) + zPeriapsis * Math.sin(ascendingNode);
        double zNode = -xPeriapsis * Math.sin(ascendingNode) + zPeriapsis * Math.cos(ascendingNode);
        double yInclined = -zNode * Math.sin(inclination);
        double zInclined = zNode * Math.cos(inclination);

        double[] result = new double[]{parentPosition[0] + xNode,
                parentPosition[1] + yInclined,
                parentPosition[2] + zInclined};
        state.framePositions.put(id, result.clone());
        return result;
    }

    private static State createState(JsonObject objects, double gameTime, double sharedOrbitTicks) {
        State state = new State(objects, gameTime, sharedOrbitTicks);
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject json = entry.getValue().getAsJsonObject();
            double[] position = sourcePosition(json);
            double[] velocity = vector(json, "speed", null, null, null);
            double mass = number(json, "mass", 0);
            boolean compute = bool(json, "astronomical_compute", false);
            boolean dynamic = velocity != null && finite(position) && finite(velocity);
            double collisionRadius = number(json, "collision_radius",
                    number(json, "physical_radius", 0.5));
            if (!Double.isFinite(collisionRadius) || collisionRadius <= 0.0) collisionRadius = 0.5;
            boolean collisionAvoidance = bool(json, "collision_avoidance", true);
            state.bodies.put(entry.getKey(), new BodyState(position,
                    velocity == null ? new double[3] : velocity, mass, compute, dynamic,
                    collisionRadius, collisionAvoidance));
        }
        return state;
    }

    private static void advance(State state, double targetSimulationTicks) {
        if (targetSimulationTicks <= state.lastSimulationTicks) return;
        double elapsed = targetSimulationTicks - state.lastSimulationTicks;
        state.lastSimulationTicks = targetSimulationTicks;
        state.dynamicAccumulator += elapsed;
        long steps = Math.min((long)state.dynamicAccumulator, MAX_CATCH_UP);
        for (long i = 0; i < steps; i++) step(state);
        state.dynamicAccumulator -= steps;
        if (state.dynamicAccumulator > MAX_CATCH_UP) state.dynamicAccumulator = 0.0;
    }

    /** Velocity-Verlet integration: stable, time-reversible N-body update. */
    private static void step(State state) {
        Map<String, double[]> acceleration = accelerations(state);
        for (Map.Entry<String, BodyState> entry : state.bodies.entrySet()) {
            BodyState body = entry.getValue();
            if (!body.dynamic) continue;
            double[] a = acceleration.get(entry.getKey());
            if (a == null) continue;
            for (int axis = 0; axis < 3; axis++) {
                body.position[axis] += body.velocity[axis] * DT + 0.5 * a[axis] * DT * DT;
                body.velocity[axis] += 0.5 * a[axis] * DT;
            }
        }
        Map<String, double[]> next = accelerations(state);
        for (Map.Entry<String, BodyState> entry : state.bodies.entrySet()) {
            BodyState body = entry.getValue();
            if (!body.dynamic) continue;
            double[] a = next.get(entry.getKey());
            if (a == null) continue;
            for (int axis = 0; axis < 3; axis++)
                body.velocity[axis] += 0.5 * a[axis] * DT;
        }
    }

    private static Map<String, double[]> accelerations(State state) {
        Map<String, double[]> result = new HashMap<>();
        for (Map.Entry<String, BodyState> entry : state.bodies.entrySet()) {
            BodyState body = entry.getValue();
            if (!body.dynamic) continue;
            double[] a = new double[3];
            for (BodyState source : state.bodies.values()) {
                if (source == body) continue;
                double dx = source.position[0] - body.position[0];
                double dy = source.position[1] - body.position[1];
                double dz = source.position[2] - body.position[2];
                double r2 = dx * dx + dy * dy + dz * dz;
                if (r2 < 1.0E-12) continue;
                double distance = Math.sqrt(r2);
                if (source.compute && source.mass > 0.0) {
                    double factor = G * source.mass / (r2 * distance);
                    a[0] += factor * dx; a[1] += factor * dy; a[2] += factor * dz;
                }

                // Near-collision steering for dynamically integrated bodies.
                // It is inactive outside a small safety corridor and never changes
                // datapack initial conditions or static Cosmic Horizons bodies.
                if (body.collisionAvoidance) {
                    double safeDistance = body.collisionRadius + source.collisionRadius;
                    double influenceDistance = safeDistance * 2.5;
                    if (safeDistance > 0.0 && distance < influenceDistance) {
                        // Normal points from the obstacle toward the moving body.
                        double nx = -dx / distance, ny = -dy / distance, nz = -dz / distance;
                        double rvx = body.velocity[0] - (source.dynamic ? source.velocity[0] : 0.0);
                        double rvy = body.velocity[1] - (source.dynamic ? source.velocity[1] : 0.0);
                        double rvz = body.velocity[2] - (source.dynamic ? source.velocity[2] : 0.0);
                        double closingSpeed = -(rvx * nx + rvy * ny + rvz * nz);
                        double penetration = (influenceDistance - distance) / influenceDistance;
                        double steering = Math.min(100.0,
                                0.25 * penetration * penetration + Math.max(0.0, closingSpeed) * 0.8);
                        a[0] += nx * steering;
                        a[1] += ny * steering;
                        a[2] += nz * steering;
                    }
                }
            }
            result.put(entry.getKey(), a);
        }
        return result;
    }

    private static String bodyId(JsonObject body) {
        return string(body, "catalog_id", string(body, "id", ""));
    }

    private static JsonObject orbitData(JsonObject body) {
        for (String key : new String[]{"maqan_orbit", "orbit_data", "orbit"}) {
            JsonObject nested = object(body, key);
            if (nested.size() > 0) return nested;
        }
        return new JsonObject();
    }

    private static String firstString(JsonObject nested, JsonObject body, String... keys) {
        for (String key : keys) {
            String value = string(nested, key, "");
            if (!value.isBlank()) return value;
            value = string(body, key, "");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static double firstNumber(JsonObject nested, JsonObject body,
                                      double fallback, String... keys) {
        for (String key : keys) {
            double value = number(nested, key, Double.NaN);
            if (Double.isFinite(value)) return value;
            value = number(body, key, Double.NaN);
            if (Double.isFinite(value)) return value;
        }
        return fallback;
    }

    private static boolean isStar(JsonObject body) {
        String identity = (string(body, "kind", "") + " " +
                string(body, "display_type", "") + " " +
                string(body, "type", "") + " " +
                string(body, "classification", "") + " " +
                string(body, "name", "") + " " +
                string(body, "id", "")).toLowerCase(Locale.ROOT);
        return identity.contains("star") || identity.contains("sun")
                || identity.matches(".*\\bsol\\b.*");
    }

    private static boolean isMoon(JsonObject body) {
        String kind = (string(body, "kind", "") + " " +
                string(body, "display_type", "") + " " +
                string(body, "type", "") + " " +
                string(body, "classification", "")).toLowerCase(Locale.ROOT);
        return kind.contains("moon") || kind.contains("satellite");
    }

    private static boolean canDefaultOrbit(JsonObject body) {
        String kind = string(body, "kind", string(body, "display_type", ""))
                .toLowerCase(Locale.ROOT);
        return kind.equals("planet") || kind.equals("asteroid") || kind.equals("comet");
    }

    private static double[] sourcePosition(JsonObject body) {
        if (body == null) return new double[]{0.0, 0.0, 0.0};
        double[] position = vector(body, "pos", "xpos", "ypos", "zpos");
        if (position != null) return position;
        if (body != null && body.has("map_pos") && body.get("map_pos").isJsonArray()) {
            JsonArray map = body.getAsJsonArray("map_pos");
            if (map.size() >= 3) {
                try {
                    return new double[]{map.get(0).getAsDouble(),
                            map.get(1).getAsDouble(), map.get(2).getAsDouble()};
                } catch (RuntimeException ignored) { }
            }
        }
        return new double[]{number(body, "x", 0.0),
                number(body, "y", 0.0), number(body, "z", 0.0)};
    }

    private static double[] vector(JsonObject json, String key, String x, String y, String z) {
        if (key != null && json.has(key) && json.get(key).isJsonArray()) {
            JsonArray a = json.getAsJsonArray(key);
            if (a.size() == 3) {
                try { return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }
                catch (RuntimeException ignored) { return null; }
            }
        }
        if (x != null && json.has(x) && json.has(y) && json.has(z))
            return new double[]{number(json, x, 0), number(json, y, 0), number(json, z, 0)};
        return null;
    }

    private static double sourceCoordinate(JsonObject body, int axis) {
        if (axis < 0 || axis > 2) return 0.0;
        return sourcePosition(body)[axis];
    }
    private static JsonObject object(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject();
    }
    private static double number(JsonObject json, String key, double fallback) {
        try { return json.has(key) && json.get(key).isJsonNull() == false ? json.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try { return json.has(key) ? json.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static String string(JsonObject json, String key, String fallback) {
        try { return json.has(key) ? json.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static boolean finite(double[] values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
    private static final class State {
        final JsonObject sourceObjects;
        final Map<String, BodyState> bodies = new LinkedHashMap<>();
        final Map<String, double[]> framePositions = new HashMap<>();
        double lastClockTime;
        double simulationTicks;
        double dynamicAccumulator;
        double lastSimulationTicks;
        double frameTime = Double.NaN;
        State(JsonObject sourceObjects, double time, double sharedSimulationTicks) {
            this.sourceObjects = sourceObjects;
            this.lastClockTime = time;
            this.simulationTicks = sharedSimulationTicks;
            this.lastSimulationTicks = sharedSimulationTicks;
            this.dynamicAccumulator = 0.0;
        }
    }
    private static final class BodyState {
        final double[] position, velocity;
        final double mass;
        final double collisionRadius;
        final boolean compute, dynamic, collisionAvoidance;
        BodyState(double[] position, double[] velocity, double mass, boolean compute,
                  boolean dynamic, double collisionRadius, boolean collisionAvoidance) {
            this.position = position.clone(); this.velocity = velocity.clone();
            this.mass = mass; this.compute = compute; this.dynamic = dynamic;
            this.collisionRadius = collisionRadius;
            this.collisionAvoidance = collisionAvoidance;
        }
    }
}
