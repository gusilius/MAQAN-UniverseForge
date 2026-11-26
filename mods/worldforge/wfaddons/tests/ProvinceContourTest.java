package dev.worldforge.tests;

import dev.worldforge.mod.WorldForgeProvinceContourBuilder;
import java.util.List;
import java.util.Map;

/** Raster-to-vector topology regression tests for WorldForge province exports. */
public final class ProvinceContourTest {
    private static int failures;
    private static void check(boolean condition,String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if(!condition)failures++;
    }
    public static void main(String[] args) {
        preservesDisconnectedIslands();
        preservesInteriorHoles();
        followsConcaveBorders();
        rejectsInvalidRasterDimensions();
        System.out.println(failures==0 ? "ALL OK" : failures+" FAIL");
        if(failures>0)System.exit(1);
    }
    private static void preservesDisconnectedIslands() {
        short[] pixels={1,0,0,0,1};
        Map<Integer,List<List<WorldForgeProvinceContourBuilder.Point>>> result=
                WorldForgeProvinceContourBuilder.build(pixels,5,1);
        check(result.get(1).size()==2,"same province keeps disconnected islands as separate rings");
        check(result.get(1).stream().allMatch(r->r.size()==4),"single-pixel islands have four corners");
    }
    private static void preservesInteriorHoles() {
        short[] pixels={
                1,1,1,1,1,
                1,1,1,1,1,
                1,1,0,1,1,
                1,1,1,1,1,
                1,1,1,1,1
        };
        var rings=WorldForgeProvinceContourBuilder.build(pixels,5,5).get(1);
        check(rings.size()==2,"province ring set contains outer border and enclosed hole");
        check(rings.stream().anyMatch(r->signedArea(r)>0)
                &&rings.stream().anyMatch(r->signedArea(r)<0),
                "outer and hole rings have opposite winding");
    }
    private static void followsConcaveBorders() {
        short[] pixels={
                1,1,1,1,1,
                1,1,1,1,1,
                1,1,0,1,1,
                1,1,0,1,1,
                1,1,0,1,1
        };
        var rings=WorldForgeProvinceContourBuilder.build(pixels,5,5).get(1);
        check(rings.size()==1,"concave province remains one continuous ring");
        check(rings.get(0).size()==8,"collinear raster edges are simplified without losing corners");
    }
    private static void rejectsInvalidRasterDimensions() {
        boolean rejected=false;
        try { WorldForgeProvinceContourBuilder.build(new short[]{1,2},2,2); }
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid raster dimensions are rejected");
    }
    private static long signedArea(List<WorldForgeProvinceContourBuilder.Point> ring) {
        long area=0;
        for(int i=0;i<ring.size();i++){
            var a=ring.get(i);var b=ring.get((i+1)%ring.size());
            area+=(long)a.x()*b.z()-(long)b.x()*a.z();
        }
        return area;
    }
}
