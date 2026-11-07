package com.maqan.galacticnavigator.navigation;

import java.util.*;

/**
 * A* route planner constrained to chunks the player has personally explored.
 * It never queries, loads, or assumes the shape of an unexplored chunk.
 */
public final class ExploredSurfaceRoutePlanner {
    public enum Mode { WALK, VEHICLE, FLIGHT }
    public record Point(int chunkX,int chunkZ,int surfaceY) {}
    public record Result(boolean found,String message,List<Point> points,
                         double cost,int expandedNodes) {
        public Result { points=List.copyOf(points); }
    }
    private record Node(int x,int z) {}
    private record QueueNode(Node node,double score) {}
    private static final int[][] DIRECTIONS={
            {-1,-1},{0,-1},{1,-1},{-1,0},{1,0},{-1,1},{0,1},{1,1}
    };
    private ExploredSurfaceRoutePlanner(){}

    public static Result find(List<NavigatorExplorationStore.ExploredChunk> explored,
                              int startX,int startZ,int goalX,int goalZ,Mode mode){
        Objects.requireNonNull(mode,"mode");
        Map<Node,NavigatorExplorationStore.ExploredChunk> known=new HashMap<>();
        for(var chunk:explored)known.put(new Node(chunk.chunkX(),chunk.chunkZ()),chunk);
        Node start=new Node(startX,startZ),goal=new Node(goalX,goalZ);
        if(!known.containsKey(start))
            return failure("Your current chunk has not been recorded yet.",0);
        if(!known.containsKey(goal))
            return failure("Destination is outside explored territory. Explore it before routing.",0);
        if(start.equals(goal)){
            var c=known.get(start);
            return new Result(true,"You are already in the destination chunk.",
                    List.of(new Point(c.chunkX(),c.chunkZ(),c.surfaceY())),0,0);
        }

        Map<Node,Double> gScore=new HashMap<>();
        Map<Node,Node> cameFrom=new HashMap<>();
        PriorityQueue<QueueNode> open=new PriorityQueue<>(Comparator.comparingDouble(QueueNode::score));
        Set<Node> closed=new HashSet<>();
        gScore.put(start,0.0);
        open.add(new QueueNode(start,heuristic(start,goal)));
        int expanded=0;
        int maxExpanded=Math.min(250_000,Math.max(2_000,known.size()*4));

        while(!open.isEmpty()&&expanded<maxExpanded){
            Node current=open.poll().node();
            if(!closed.add(current))continue;
            expanded++;
            if(current.equals(goal)){
                List<Node> reversed=new ArrayList<>();
                Node cursor=goal;reversed.add(cursor);
                while(!cursor.equals(start)){
                    cursor=cameFrom.get(cursor);
                    if(cursor==null)return failure("Route reconstruction failed.",expanded);
                    reversed.add(cursor);
                }
                Collections.reverse(reversed);
                List<Point> points=reversed.stream().map(n->{
                    var c=known.get(n);return new Point(n.x(),n.z(),c.surfaceY());
                }).toList();
                return new Result(true,"Route found through explored terrain.",
                        points,gScore.get(goal),expanded);
            }

            var from=known.get(current);
            for(int[] d:DIRECTIONS){
                Node next=new Node(current.x()+d[0],current.z()+d[1]);
                var to=known.get(next);
                if(to==null||closed.contains(next))continue;
                boolean diagonal=d[0]!=0&&d[1]!=0;
                if(diagonal&&(!known.containsKey(new Node(current.x()+d[0],current.z()))
                        ||!known.containsKey(new Node(current.x(),current.z()+d[1]))))continue;
                double step=diagonal?Math.sqrt(2.0):1.0;
                int heightDelta=Math.abs(to.surfaceY()-from.surfaceY());
                step+=heightCost(heightDelta,mode);
                step+=biomeCost(to.biome(),mode);
                double tentative=gScore.get(current)+step;
                if(tentative<gScore.getOrDefault(next,Double.POSITIVE_INFINITY)){
                    cameFrom.put(next,current);gScore.put(next,tentative);
                    open.add(new QueueNode(next,tentative+heuristic(next,goal)));
                }
            }
        }
        return failure(expanded>=maxExpanded
                ?"Search limit reached; zoom in or explore a smaller corridor."
                :"No continuous explored corridor connects these points.",expanded);
    }

    private static double heightCost(int delta,Mode mode){
        return switch(mode){
            case WALK -> delta*0.035;
            case VEHICLE -> delta*0.075;
            case FLIGHT -> delta*0.002;
        };
    }
    private static double biomeCost(String biome,Mode mode){
        String id=biome==null?"":biome.toLowerCase(Locale.ROOT);
        if(id.contains("lava")||id.contains("basalt_deltas")||id.contains("fire"))return mode==Mode.FLIGHT?2.0:80.0;
        if(id.contains("ocean")||id.contains("river")||id.contains("beach"))
            return mode==Mode.VEHICLE?35.0:mode==Mode.WALK?8.0:0.5;
        if(id.contains("mountain")||id.contains("peaks")||id.contains("cliff"))
            return mode==Mode.VEHICLE?4.0:mode==Mode.WALK?2.0:0.25;
        return 0.0;
    }
    private static double heuristic(Node a,Node b){
        int dx=Math.abs(a.x()-b.x()),dz=Math.abs(a.z()-b.z());
        return Math.max(dx,dz)+(Math.sqrt(2.0)-1.0)*Math.min(dx,dz);
    }
    private static Result failure(String message,int expanded){
        return new Result(false,message,List.of(),0,expanded);
    }
}
