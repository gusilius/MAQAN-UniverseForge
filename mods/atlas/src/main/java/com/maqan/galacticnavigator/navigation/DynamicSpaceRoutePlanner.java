package com.maqan.galacticnavigator.navigation;

import java.util.*;

/**
 * Time-dependent 3D route planner for arbitrary point-to-point navigation.
 * Obstacles are moving collision envelopes evaluated at the estimated time
 * the ship reaches each sampled point. A route is never considered clear
 * merely because the current positions are clear.
 *
 * This is a geometric navigation layer; drive-specific travel-time and
 * server-authoritative execution are supplied by higher layers.
 */
public final class DynamicSpaceRoutePlanner {
    private DynamicSpaceRoutePlanner() {}

    public record Vec3(double x, double y, double z) {
        public Vec3 {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Coordinates must be finite");
        }
        public Vec3 add(Vec3 b) { return new Vec3(x+b.x,y+b.y,z+b.z); }
        public Vec3 subtract(Vec3 b) { return new Vec3(x-b.x,y-b.y,z-b.z); }
        public Vec3 scale(double s) { return new Vec3(x*s,y*s,z*s); }
        public double length() { return Math.sqrt(x*x+y*y+z*z); }
        public double distance(Vec3 b) { return subtract(b).length(); }
        public Vec3 normalized() {
            double n=length();
            if (n<1.0E-10) throw new IllegalArgumentException("Zero direction");
            return scale(1.0/n);
        }
        public double dot(Vec3 b) { return x*b.x+y*b.y+z*b.z; }
        public Vec3 cross(Vec3 b) {
            return new Vec3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);
        }
    }

    @FunctionalInterface
    public interface Ephemeris { Vec3 positionAt(double absoluteTimeSeconds); }

    /** Local-space collision geometry. Distances are signed: negative is inside. */
    public interface CollisionGeometry {
        double signedDistance(Vec3 localPoint);
        Vec3 outwardNormal(Vec3 localPoint);
        double minimumFeatureSize();
    }

    public record SphereGeometry(double radius) implements CollisionGeometry {
        public SphereGeometry {
            if(!Double.isFinite(radius)||radius<=0)throw new IllegalArgumentException("Invalid sphere radius");
        }
        @Override public double signedDistance(Vec3 p){return p.length()-radius;}
        @Override public double minimumFeatureSize(){return radius;}
        @Override public Vec3 outwardNormal(Vec3 p){return p.length()<1.0E-9?new Vec3(1,0,0):p.normalized();}
    }

    /** Ellipsoid SDF approximation, using authored physical semi-axes. */
    public record EllipsoidGeometry(double radiusX,double radiusY,double radiusZ,
                                     double rotationX,double rotationY,double rotationZ) implements CollisionGeometry {
        public EllipsoidGeometry(double radiusX,double radiusY,double radiusZ){
            this(radiusX,radiusY,radiusZ,0,0,0);
        }
        public EllipsoidGeometry {
            if(!positive(radiusX)||!positive(radiusY)||!positive(radiusZ)
                    ||!Double.isFinite(rotationX)||!Double.isFinite(rotationY)||!Double.isFinite(rotationZ))
                throw new IllegalArgumentException("Invalid ellipsoid axes or rotation");
        }
        private Vec3 toLocal(Vec3 p){
            double cz=Math.cos(-rotationZ),sz=Math.sin(-rotationZ);
            double x1=p.x()*cz-p.y()*sz,y1=p.x()*sz+p.y()*cz;
            double cy=Math.cos(-rotationY),sy=Math.sin(-rotationY);
            double x2=x1*cy+p.z()*sy,z2=-x1*sy+p.z()*cy;
            double cx=Math.cos(-rotationX),sx=Math.sin(-rotationX);
            return new Vec3(x2,y1*cx-z2*sx,y1*sx+z2*cx);
        }
        private Vec3 toWorld(Vec3 p){
            double cx=Math.cos(rotationX),sx=Math.sin(rotationX);
            double y1=p.y()*cx-p.z()*sx,z1=p.y()*sx+p.z()*cx;
            double cy=Math.cos(rotationY),sy=Math.sin(rotationY);
            double x2=p.x()*cy+z1*sy,z2=-p.x()*sy+z1*cy;
            double cz=Math.cos(rotationZ),sz=Math.sin(rotationZ);
            return new Vec3(x2*cz-y1*sz,x2*sz+y1*cz,z2);
        }
        @Override public double minimumFeatureSize(){return Math.min(radiusX,Math.min(radiusY,radiusZ));}
        @Override public double signedDistance(Vec3 world){
            Vec3 p=toLocal(world);
            double k0=Math.sqrt(sq(p.x()/radiusX)+sq(p.y()/radiusY)+sq(p.z()/radiusZ));
            double k1=Math.sqrt(sq(p.x()/(radiusX*radiusX))+sq(p.y()/(radiusY*radiusY))+sq(p.z()/(radiusZ*radiusZ)));
            if(k1<1.0E-12)return -Math.min(radiusX,Math.min(radiusY,radiusZ));
            return k0*(k0-1.0)/k1;
        }
        @Override public Vec3 outwardNormal(Vec3 world){
            Vec3 p=toLocal(world);
            Vec3 local=new Vec3(p.x()/(radiusX*radiusX),p.y()/(radiusY*radiusY),p.z()/(radiusZ*radiusZ));
            if(local.length()<1.0E-9)local=new Vec3(1,0,0);
            return toWorld(local.normalized()).normalized();
        }
    }

    /** Finite-thickness annular torus-like belt, not a fake solid sphere. */
    public record AnnularBeltGeometry(double innerRadius,double outerRadius,double halfThickness,
                                      double inclinationRadians,double nodeRadians) implements CollisionGeometry {
        public AnnularBeltGeometry {
            if(!Double.isFinite(innerRadius)||innerRadius<0||!positive(outerRadius)
                    ||outerRadius<=innerRadius||!positive(halfThickness)
                    ||!Double.isFinite(inclinationRadians)||!Double.isFinite(nodeRadians))
                throw new IllegalArgumentException("Invalid annular belt geometry");
        }
        private Vec3 toLocal(Vec3 p){
            double cn=Math.cos(-nodeRadians),sn=Math.sin(-nodeRadians);
            double x=p.x()*cn-p.z()*sn,z=p.x()*sn+p.z()*cn;
            double ci=Math.cos(-inclinationRadians),si=Math.sin(-inclinationRadians);
            return new Vec3(x,p.y()*ci-z*si,p.y()*si+z*ci);
        }
        private Vec3 toWorld(Vec3 p){
            double ci=Math.cos(inclinationRadians),si=Math.sin(inclinationRadians);
            double y=p.y()*ci-p.z()*si,z=p.y()*si+p.z()*ci;
            double cn=Math.cos(nodeRadians),sn=Math.sin(nodeRadians);
            return new Vec3(p.x()*cn-z*sn,y,p.x()*sn+z*cn);
        }
        private double[] crossSection(Vec3 p){
            double radial=Math.hypot(p.x(),p.z());
            return new double[]{radial-(innerRadius+outerRadius)*0.5,
                    Math.abs(p.y())-halfThickness};
        }
        @Override public double minimumFeatureSize(){
            return Math.min(halfThickness,(outerRadius-innerRadius)*0.5);
        }
        @Override public double signedDistance(Vec3 point){
            Vec3 p=toLocal(point);double[] q=crossSection(p);
            double hx=(outerRadius-innerRadius)*0.5,hy=halfThickness;
            double dx=Math.abs(q[0])-hx,dy=q[1];
            double ox=Math.max(dx,0),oy=Math.max(dy,0);
            return Math.sqrt(ox*ox+oy*oy)+Math.min(Math.max(dx,dy),0);
        }
        @Override public Vec3 outwardNormal(Vec3 point){
            Vec3 p=toLocal(point);double radial=Math.hypot(p.x(),p.z());
            double[] q=crossSection(p);double hx=(outerRadius-innerRadius)*0.5;
            double dx=Math.abs(q[0])-hx,dy=q[1];
            double nx=0,ny=0,nr=radial<1.0E-9?1:p.x()/radial,nz=radial<1.0E-9?0:p.z()/radial;
            if(dx>0||dy>0){nx=Math.max(dx,0);ny=Math.max(dy,0);double len=Math.hypot(nx,ny);if(len>1.0E-9){nx/=len;ny/=len;}}
            else if(dx>dy)nx=Math.signum(q[0]);
            else ny=Math.signum(p.y());
            Vec3 local=new Vec3(nx*nr,ny,nx*nz);
            return toWorld(local).length()<1.0E-9?new Vec3(1,0,0):toWorld(local).normalized();
        }
    }

    public record Obstacle(String id, Ephemeris ephemeris, double collisionRadius,
                           double safetyMargin, String classification, CollisionGeometry geometry) {
        public Obstacle(String id,Ephemeris ephemeris,double collisionRadius,
                        double safetyMargin,String classification){
            this(id,ephemeris,collisionRadius,safetyMargin,classification,
                    new SphereGeometry(collisionRadius));
        }
        public Obstacle {
            if (id==null || id.isBlank()) throw new IllegalArgumentException("Obstacle ID required");
            Objects.requireNonNull(ephemeris, "ephemeris");
            if (!Double.isFinite(collisionRadius) || collisionRadius<=0
                    || !Double.isFinite(safetyMargin) || safetyMargin<0)
                throw new IllegalArgumentException("Invalid obstacle envelope");
            classification = classification==null ? "unknown" : classification;
            geometry=geometry==null?new SphereGeometry(collisionRadius):geometry;
        }
        public double envelope() { return collisionRadius+safetyMargin; }
        public double minimumFeatureSize(){return geometry.minimumFeatureSize();}
        public double signedDistanceAt(Vec3 point,double time){
            return geometry.signedDistance(point.subtract(ephemeris.positionAt(time)));
        }
        public double clearanceAt(Vec3 point,double time){
            return signedDistanceAt(point,time)-safetyMargin;
        }
        public Vec3 normalAt(Vec3 point,double time){
            return geometry.outwardNormal(point.subtract(ephemeris.positionAt(time)));
        }
    }

    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static double sq(double value){return value*value;}

    public record Request(Vec3 origin, Vec3 destination, double departureTimeSeconds,
                          double speedUnitsPerSecond, double clearance,
                          List<Obstacle> obstacles, int samplesPerSegment,
                          int maxDetours, int maxReplans) {
        public Request {
            Objects.requireNonNull(origin); Objects.requireNonNull(destination);
            obstacles=List.copyOf(obstacles==null ? List.of() : obstacles);
            if (!Double.isFinite(departureTimeSeconds)
                    || !Double.isFinite(speedUnitsPerSecond) || speedUnitsPerSecond<=0
                    || !Double.isFinite(clearance) || clearance<0)
                throw new IllegalArgumentException("Invalid time, speed or clearance");
            if (samplesPerSegment<8 || samplesPerSegment>4096)
                throw new IllegalArgumentException("Samples per segment must be 8..4096");
            if (maxDetours<0 || maxDetours>32 || maxReplans<1 || maxReplans>32)
                throw new IllegalArgumentException("Invalid planner limits");
        }
    }

    public record Segment(Vec3 from, Vec3 to, double departTimeSeconds,
                          double arriveTimeSeconds, double distance) {}
    public record Result(boolean valid, boolean clear, boolean requiresReplan,
                         String status, List<Vec3> waypoints, List<Segment> segments,
                         double distance, double durationSeconds,
                         List<String> encounteredObstacles, int replans) {
        public Result {
            waypoints=List.copyOf(waypoints);
            segments=List.copyOf(segments);
            encounteredObstacles=List.copyOf(encounteredObstacles);
        }
    }

    public static Result plan(Request request) {
        List<Vec3> points=new ArrayList<>();
        points.add(request.origin());
        points.add(request.destination());
        LinkedHashSet<String> encountered=new LinkedHashSet<>();
        double directDistance=request.origin().distance(request.destination());
        double directArrival=request.departureTimeSeconds()
                +directDistance/request.speedUnitsPerSecond();
        for(Obstacle obstacle:request.obstacles()){
            Vec3 atDeparture=obstacle.ephemeris().positionAt(request.departureTimeSeconds());
            if(obstacle.clearanceAt(request.origin(),request.departureTimeSeconds())<=request.clearance()){
                encountered.add(obstacle.id());
                return new Result(false,false,true,
                        "Unsafe departure: the origin intersects "+obstacle.classification()
                                +" ["+obstacle.id()+"]. Move to a verified safe point before routing.",
                        points,List.of(),0,0,new ArrayList<>(encountered),0);
            }
            Vec3 atArrival=obstacle.ephemeris().positionAt(directArrival);
            if(obstacle.clearanceAt(request.destination(),directArrival)<=request.clearance()){
                encountered.add(obstacle.id());
                return new Result(false,false,true,
                        "Unsafe destination: the requested point intersects "+obstacle.classification()
                                +" ["+obstacle.id()+"]. Choose a point outside its collision envelope.",
                        points,List.of(),0,0,new ArrayList<>(encountered),0);
            }
        }
        int replans=0;

        while (replans<=request.maxReplans()) {
            Build build=buildSegments(points,request);
            Collision collision=firstCollision(build.segments,request);
            if (collision==null) {
                return new Result(true,true,false,
                        replans==0 ? "Direct trajectory is clear." : "Detour trajectory is clear.",
                        points,build.segments,build.distance,
                        build.arrivalTime-request.departureTimeSeconds(),
                        new ArrayList<>(encountered),replans);
            }
            encountered.add(collision.obstacle.id());
            if (replans==request.maxReplans() || detourCount(points)>=request.maxDetours()) break;

            Vec3 detour=chooseDetour(collision,request);
            if (detour==null) break;
            int insertAt=Math.min(points.size()-1,collision.segmentIndex+1);
            points.add(insertAt,detour);
            replans++;
        }

        Build partial=buildSegments(points,request);
        Collision remaining=firstCollision(partial.segments,request);
        return new Result(true,false,true,
                remaining==null ? "Route needs another planning pass." :
                        "No collision-free corridor found within planner limits; do not execute this route.",
                points,partial.segments,partial.distance,
                partial.arrivalTime-request.departureTimeSeconds(),
                new ArrayList<>(encountered),replans);
    }

    /** Re-evaluate an active route after a clock tick or an ephemeris update. */
    public static boolean needsReplan(Result route, Request updatedRequest) {
        if (route==null || !route.valid() || !route.clear()) return true;
        return firstCollision(buildSegments(route.waypoints(),updatedRequest).segments,updatedRequest)!=null;
    }

    private static Vec3 chooseDetour(Collision collision, Request request) {
        Vec3 direction=collision.to().subtract(collision.from());
        if (direction.length()<1.0E-9) return null;
        Vec3 radial=collision.obstacle.normalAt(collision.point,collision.timeSeconds);
        if(radial.length()<1.0E-8)return null;
        Vec3 lateral=direction.cross(radial);
        if (lateral.length()<1.0E-8) lateral=direction.cross(new Vec3(0,1,0));
        if (lateral.length()<1.0E-8) lateral=direction.cross(new Vec3(1,0,0));
        lateral=lateral.normalized();

        double signed=collision.obstacle.signedDistanceAt(collision.point,collision.timeSeconds);
        double normalOffset=Math.max(2.0,collision.obstacle.safetyMargin()
                +request.clearance()-signed+2.0);
        double sideOffset=Math.max(2.0,request.clearance()+2.0);
        Vec3 outside=collision.point.add(radial.normalized().scale(normalOffset));
        Vec3 a=outside.add(lateral.scale(sideOffset));
        Vec3 b=outside.subtract(lateral.scale(sideOffset));
        // Prefer the side with less added distance, but validate both candidates
        // against every moving obstacle before accepting.
        Vec3 origin=collision.from(), destination=collision.to();
        Vec3 first=origin.distance(a)+a.distance(destination)
                <= origin.distance(b)+b.distance(destination) ? a : b;
        Vec3 second=first==a ? b : a;
        return clearDetour(first,collision,request) ? first
                : clearDetour(second,collision,request) ? second : null;
    }

    private static boolean clearDetour(Vec3 candidate, Collision collision, Request request) {
        double start=collision.segment.departTimeSeconds();
        double firstDuration=collision.from().distance(candidate)/request.speedUnitsPerSecond();
        double secondDuration=candidate.distance(collision.to())/request.speedUnitsPerSecond();
        Segment a=new Segment(collision.from(),candidate,start,start+firstDuration,
                collision.from().distance(candidate));
        Segment b=new Segment(candidate,collision.to(),a.arriveTimeSeconds(),
                a.arriveTimeSeconds()+secondDuration,candidate.distance(collision.to()));
        return firstCollision(List.of(a,b),request)==null;
    }

    private static Collision firstCollision(List<Segment> segments, Request request) {
        for (int si=0;si<segments.size();si++) {
            Segment segment=segments.get(si);
            int sampleCount=request.samplesPerSegment();
            double smallestFeature=Double.POSITIVE_INFINITY;
            double maximumObstacleMotion=0.0;
            for(Obstacle obstacle:request.obstacles()){
                smallestFeature=Math.min(smallestFeature,obstacle.minimumFeatureSize());
                Vec3 start=obstacle.ephemeris().positionAt(segment.departTimeSeconds());
                Vec3 end=obstacle.ephemeris().positionAt(segment.arriveTimeSeconds());
                maximumObstacleMotion=Math.max(maximumObstacleMotion,start.distance(end));
            }
            if(Double.isFinite(smallestFeature)&&smallestFeature>0){
                double targetSpacing=Math.max(0.05,smallestFeature*0.5);
                double required=Math.ceil(Math.max(segment.distance(),maximumObstacleMotion)/targetSpacing);
                if(Double.isFinite(required))sampleCount=Math.min(4096,
                        Math.max(sampleCount,(int)Math.min(4096,required)));
            }
            for (int sample=0;sample<=sampleCount;sample++) {
                double fraction=(double)sample/sampleCount;
                Vec3 point=segment.from().add(segment.to().subtract(segment.from()).scale(fraction));
                double time=segment.departTimeSeconds()
                        +(segment.arriveTimeSeconds()-segment.departTimeSeconds())*fraction;
                for (Obstacle obstacle:request.obstacles()) {
                    Vec3 center=obstacle.ephemeris().positionAt(time);
                    if (obstacle.clearanceAt(point,time)<=request.clearance())
                        return new Collision(si,segment,obstacle,point,time);
                }
            }
        }
        return null;
    }

    private static Build buildSegments(List<Vec3> points, Request request) {
        List<Segment> segments=new ArrayList<>();
        double time=request.departureTimeSeconds(),distance=0;
        for (int i=0;i<points.size()-1;i++) {
            Vec3 from=points.get(i),to=points.get(i+1);
            double length=from.distance(to),duration=length/request.speedUnitsPerSecond();
            segments.add(new Segment(from,to,time,time+duration,length));
            distance+=length; time+=duration;
        }
        return new Build(segments,distance,time);
    }
    private static int detourCount(List<Vec3> points) { return Math.max(0,points.size()-2); }
    private record Build(List<Segment> segments,double distance,double arrivalTime) {}
    private record Collision(int segmentIndex,Segment segment,Obstacle obstacle,Vec3 point,double timeSeconds) {
        Vec3 from(){return segment.from();}
        Vec3 to(){return segment.to();}
    }
}
