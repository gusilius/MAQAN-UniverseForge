package com.maqan.galacticnavigator.navigation;

import java.util.*;

/**
 * Directed graph of explicitly discovered travel connections.
 *
 * The graph does not invent links. Datapack/catalog adapters must add only
 * routes that actually exist and are available to the current player.
 */
public final class RouteNetwork {
    public enum Objective { SHORTEST, FASTEST, SAFEST }

    public record Edge(String from, String to, NavigatorTravelPlanner.Drive drive,
                       double distance, double durationSeconds, double risk,
                       boolean discovered, boolean available, String sourceId) {
        public Edge {
            if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to))
                throw new IllegalArgumentException("A route requires two distinct node IDs");
            Objects.requireNonNull(drive, "drive");
            if (!Double.isFinite(distance) || distance < 0
                    || !Double.isFinite(durationSeconds) || durationSeconds < 0
                    || !Double.isFinite(risk) || risk < 0 || risk > 1)
                throw new IllegalArgumentException("Invalid route metrics");
            sourceId = sourceId == null ? "" : sourceId;
        }
        public boolean traversable() {
            return discovered && available && (!drive.requiresLink || !sourceId.isBlank());
        }
    }

    public record Route(boolean found, String message, List<Edge> legs,
                        double totalDistance, double totalDurationSeconds,
                        double cumulativeRisk) {
        public Route { legs = List.copyOf(legs); }
    }

    private final Map<String, List<Edge>> outgoing = new LinkedHashMap<>();

    public void add(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
    }

    public void addBidirectional(Edge forward, Edge reverse) {
        if (!forward.from().equals(reverse.to()) || !forward.to().equals(reverse.from()))
            throw new IllegalArgumentException("Reverse edge endpoints do not match");
        add(forward);
        add(reverse);
    }

    public Set<String> nodes() {
        Set<String> result = new LinkedHashSet<>(outgoing.keySet());
        for (List<Edge> edges : outgoing.values()) for (Edge edge : edges) result.add(edge.to());
        return Collections.unmodifiableSet(result);
    }

    public List<Edge> outgoing(String nodeId) {
        return List.copyOf(outgoing.getOrDefault(nodeId, List.of()));
    }

    /** Dijkstra search with a selectable objective; unavailable/undiscovered edges are ignored. */
    public Route find(String origin, String destination, Objective objective) {
        if (origin == null || destination == null || objective == null)
            throw new IllegalArgumentException("Origin, destination and objective are required");
        if (origin.equals(destination))
            return new Route(true, "Already at destination.", List.of(), 0, 0, 0);

        Map<String, Double> costs = new HashMap<>();
        Map<String, Edge> previous = new HashMap<>();
        PriorityQueue<NodeCost> queue = new PriorityQueue<>(Comparator.comparingDouble(NodeCost::cost));
        costs.put(origin, 0.0);
        queue.add(new NodeCost(origin, 0.0));

        while (!queue.isEmpty()) {
            NodeCost current = queue.poll();
            if (current.cost() > costs.getOrDefault(current.id(), Double.POSITIVE_INFINITY)) continue;
            if (current.id().equals(destination)) break;
            for (Edge edge : outgoing(current.id())) {
                if (!edge.traversable()) continue;
                double candidate = current.cost() + weight(edge, objective);
                if (candidate < costs.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    costs.put(edge.to(), candidate);
                    previous.put(edge.to(), edge);
                    queue.add(new NodeCost(edge.to(), candidate));
                }
            }
        }

        if (!previous.containsKey(destination))
            return new Route(false, "No discovered and available route connects these destinations.",
                    List.of(), 0, 0, 1);

        List<Edge> reversed = new ArrayList<>();
        String cursor = destination;
        while (!cursor.equals(origin)) {
            Edge edge = previous.get(cursor);
            if (edge == null) return new Route(false, "Route graph is incomplete.", List.of(), 0, 0, 1);
            reversed.add(edge);
            cursor = edge.from();
        }
        Collections.reverse(reversed);
        double distance = 0, duration = 0, survival = 1;
        for (Edge edge : reversed) {
            distance += edge.distance();
            duration += edge.durationSeconds();
            survival *= 1.0 - edge.risk();
        }
        return new Route(true, reversed.size() + " travel segment(s) found.",
                reversed, distance, duration, 1.0 - survival);
    }

    private static double weight(Edge edge, Objective objective) {
        return switch (objective) {
            case SHORTEST -> edge.distance();
            case FASTEST -> edge.durationSeconds();
            case SAFEST -> -Math.log(Math.max(1.0E-9, 1.0 - edge.risk()));
        };
    }

    private record NodeCost(String id, double cost) {}
}
