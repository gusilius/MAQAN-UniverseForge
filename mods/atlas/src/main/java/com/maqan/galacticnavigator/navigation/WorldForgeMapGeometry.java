package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure geometry helpers for clipping and rasterizing WorldForge map polygons. */
public final class WorldForgeMapGeometry {
    private WorldForgeMapGeometry() {}
    public record Point(double x,double y) {}
    public record Span(int y,int xStart,int xEndExclusive) {}

    public static List<Point> clipPolygon(List<Point> polygon,double left,double top,double right,double bottom) {
        if(polygon==null||polygon.size()<3||right<=left||bottom<=top)return List.of();
        List<Point> result=new ArrayList<>(polygon);
        result=clipEdge(result,0,left); result=clipEdge(result,1,right);
        result=clipEdge(result,2,top); result=clipEdge(result,3,bottom);
        return result.size()<3?List.of():List.copyOf(result);
    }
    private static List<Point> clipEdge(List<Point> input,int edge,double boundary) {
        if(input.isEmpty())return List.of();
        List<Point> out=new ArrayList<>();
        Point prev=input.get(input.size()-1); boolean prevIn=inside(prev,edge,boundary);
        for(Point cur:input){
            boolean curIn=inside(cur,edge,boundary);
            if(curIn!=prevIn)out.add(intersection(prev,cur,edge,boundary));
            if(curIn)out.add(cur);
            prev=cur;prevIn=curIn;
        }
        return out;
    }
    private static boolean inside(Point p,int edge,double b) {
        return switch(edge){case 0->p.x>=b;case 1->p.x<=b;case 2->p.y>=b;default->p.y<=b;};
    }
    private static Point intersection(Point a,Point b,int edge,double boundary) {
        if(edge<=1){double dx=b.x-a.x,t=Math.abs(dx)<1e-12?0:(boundary-a.x)/dx;
            return new Point(boundary,a.y+(b.y-a.y)*t);}
        double dy=b.y-a.y,t=Math.abs(dy)<1e-12?0:(boundary-a.y)/dy;
        return new Point(a.x+(b.x-a.x)*t,boundary);
    }

    public static List<Span> scanlineSpans(List<Point> polygon,int left,int top,int right,int bottom) {
        return scanlineSpansRings(singleRing(polygon),left,top,right,bottom);
    }

    /** Even-odd fill supports independent islands and holes without guessing ring winding. */
    public static List<Span> scanlineSpansRings(List<List<Point>> rings,int left,int top,int right,int bottom) {
        if(rings==null||rings.isEmpty()||right<=left||bottom<=top)return List.of();
        List<List<Point>> clippedRings=new ArrayList<>();
        double minY=Double.POSITIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        for(List<Point> ring:rings){
            List<Point> clipped=clipPolygon(ring,left,top,right,bottom);
            if(clipped.size()<3)continue;
            clippedRings.add(clipped);
            for(Point p:clipped){minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
        }
        if(clippedRings.isEmpty())return List.of();
        int first=Math.max(top,(int)Math.ceil(minY-0.5));
        int last=Math.min(bottom-1,(int)Math.floor(maxY-0.5));
        List<Span> spans=new ArrayList<>(); List<Double> xs=new ArrayList<>();
        for(int y=first;y<=last;y++){
            double sy=y+0.5; xs.clear();
            for(List<Point> ring:clippedRings)for(int i=0;i<ring.size();i++){
                Point a=ring.get(i),b=ring.get((i+1)%ring.size());
                if((a.y<=sy&&b.y>sy)||(b.y<=sy&&a.y>sy))
                    xs.add(a.x+(sy-a.y)*(b.x-a.x)/(b.y-a.y));
            }
            xs.sort(Comparator.naturalOrder());
            for(int i=0;i+1<xs.size();i+=2){
                int x1=Math.max(left,(int)Math.ceil(xs.get(i)-0.5));
                int x2=Math.min(right,(int)Math.ceil(xs.get(i+1)-0.5));
                if(x2>x1)spans.add(new Span(y,x1,x2));
            }
        }
        return List.copyOf(spans);
    }

    public static Point interiorLabelPoint(List<Point> polygon,int left,int top,int right,int bottom) {
        return interiorLabelPointRings(singleRing(polygon),left,top,right,bottom);
    }
    public static Point interiorLabelPointRings(List<List<Point>> rings,int left,int top,int right,int bottom) {
        Span widest=scanlineSpansRings(rings,left,top,right,bottom).stream()
                .max(Comparator.comparingInt(s->s.xEndExclusive-s.xStart)).orElse(null);
        if(widest!=null)return new Point((widest.xStart+widest.xEndExclusive)/2.0,widest.y+0.5);
        return rings==null||rings.isEmpty()?new Point(0,0):centroid(rings.get(0));
    }
    private static List<List<Point>> singleRing(List<Point> polygon) {
        return polygon == null ? List.of() : List.of(polygon);
    }
    public static boolean containsPoint(List<Point> polygon,double x,double y) {
        return containsPointRings(singleRing(polygon),x,y);
    }
    /** Boundary counts as selected; nested rings toggle interior/exterior using even-odd topology. */
    public static boolean containsPointRings(List<List<Point>> rings,double x,double y) {
        if(rings==null||rings.isEmpty()||!Double.isFinite(x)||!Double.isFinite(y))return false;
        boolean inside=false;
        for(List<Point> ring:rings){
            int relation=relation(ring,x,y);
            if(relation==0)return true;
            if(relation>0)inside=!inside;
        }
        return inside;
    }
    private static int relation(List<Point> polygon,double x,double y) {
        if(polygon==null||polygon.size()<3)return -1;
        boolean inside=false;
        for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
            Point a=polygon.get(j),b=polygon.get(i);
            if(a==null||b==null||!Double.isFinite(a.x)||!Double.isFinite(a.y)
                    ||!Double.isFinite(b.x)||!Double.isFinite(b.y))return -1;
            double dx=b.x-a.x,dy=b.y-a.y,cross=(x-a.x)*dy-(y-a.y)*dx;
            double scale=Math.max(1.0,Math.abs(dx)+Math.abs(dy));
            if(Math.abs(cross)<=1e-9*scale&&x>=Math.min(a.x,b.x)-1e-9&&x<=Math.max(a.x,b.x)+1e-9
                    &&y>=Math.min(a.y,b.y)-1e-9&&y<=Math.max(a.y,b.y)+1e-9)return 0;
            if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside;
        }
        return inside?1:-1;
    }
    public static Point centroid(List<Point> polygon) {
        if(polygon==null||polygon.isEmpty())return new Point(0,0);
        double area=0,cx=0,cy=0;
        for(int i=0;i<polygon.size();i++){
            Point a=polygon.get(i),b=polygon.get((i+1)%polygon.size());
            double cross=a.x*b.y-b.x*a.y;area+=cross;cx+=(a.x+b.x)*cross;cy+=(a.y+b.y)*cross;
        }
        if(Math.abs(area)>1e-9)return new Point(cx/(3*area),cy/(3*area));
        double x=0,y=0;for(Point p:polygon){x+=p.x;y+=p.y;}
        return new Point(x/polygon.size(),y/polygon.size());
    }
}
