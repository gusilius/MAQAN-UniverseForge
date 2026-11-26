package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds exact province boundary rings from the authoritative province-id raster. */
public final class WorldForgeProvinceContourBuilder {
    private WorldForgeProvinceContourBuilder() {}
    public record Point(int x, int z) {}
    private record Edge(long start, long end, int direction) {}

    public static Map<Integer, List<List<Point>>> build(short[] labels, int width, int height) {
        if (labels == null || width <= 0 || height <= 0 || (long) width * height != labels.length)
            throw new IllegalArgumentException("Province raster dimensions do not match");
        Map<Integer, List<Edge>> byProvince = new LinkedHashMap<>();
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            int index = z * width + x, id = labels[index] & 0xFFFF;
            if (id == 0) continue;
            List<Edge> edges = byProvince.computeIfAbsent(id, ignored -> new ArrayList<>());
            if (z == 0 || (labels[index - width] & 0xFFFF) != id) edges.add(edge(x,z,x+1,z,0));
            if (x+1 == width || (labels[index+1] & 0xFFFF) != id) edges.add(edge(x+1,z,x+1,z+1,1));
            if (z+1 == height || (labels[index+width] & 0xFFFF) != id) edges.add(edge(x+1,z+1,x,z+1,2));
            if (x == 0 || (labels[index-1] & 0xFFFF) != id) edges.add(edge(x,z+1,x,z,3));
        }
        Map<Integer, List<List<Point>>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer,List<Edge>> entry : byProvince.entrySet()) {
            List<List<Point>> rings = trace(entry.getValue());
            if (!rings.isEmpty()) result.put(entry.getKey(), List.copyOf(rings));
        }
        return Map.copyOf(result);
    }

    private static Edge edge(int x1,int z1,int x2,int z2,int dir) {
        return new Edge(pack(x1,z1),pack(x2,z2),dir);
    }
    private static long pack(int x,int z) { return ((long)z << 32) | (x & 0xFFFFFFFFL); }
    private static Point point(long value) { return new Point((int)value,(int)(value >> 32)); }

    private static List<List<Point>> trace(List<Edge> edges) {
        Map<Long,List<Edge>> outgoing = new HashMap<>();
        for (Edge edge:edges) outgoing.computeIfAbsent(edge.start(),k->new ArrayList<>()).add(edge);
        Set<Edge> used = new HashSet<>();
        List<List<Point>> rings = new ArrayList<>();
        for (Edge first:edges) {
            if (used.contains(first)) continue;
            List<Point> raw = new ArrayList<>();
            Edge current = first;
            long start = first.start();
            boolean closed = false;
            int guard = 0;
            while (current != null && used.add(current) && guard++ <= edges.size()) {
                raw.add(point(current.start()));
                long end = current.end();
                if (end == start) { closed = true; break; }
                current = next(outgoing.get(end),current.direction(),used);
            }
            if (!closed || raw.size()<3) continue;
            List<Point> ring = simplify(raw);
            if (ring.size()>=3) rings.add(List.copyOf(ring));
        }
        return rings;
    }

    /** Right-turn preference separates diagonally touching 4-connected components. */
    private static Edge next(List<Edge> candidates,int direction,Set<Edge> used) {
        if (candidates==null) return null;
        int[] priority={1,0,3,2};
        for (int turn:priority) for (Edge candidate:candidates)
            if (!used.contains(candidate) && candidate.direction()==((direction+turn)&3)) return candidate;
        return null;
    }

    private static List<Point> simplify(List<Point> points) {
        List<Point> result=new ArrayList<>();
        for(int i=0;i<points.size();i++){
            Point prev=points.get((i+points.size()-1)%points.size()), cur=points.get(i),
                    next=points.get((i+1)%points.size());
            int dx1=Integer.compare(cur.x()-prev.x(),0), dz1=Integer.compare(cur.z()-prev.z(),0);
            int dx2=Integer.compare(next.x()-cur.x(),0), dz2=Integer.compare(next.z()-cur.z(),0);
            if(dx1!=dx2||dz1!=dz2) result.add(cur);
        }
        return result;
    }
}
