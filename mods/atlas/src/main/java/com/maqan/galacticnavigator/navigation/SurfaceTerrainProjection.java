package com.maqan.galacticnavigator.navigation;

/**
 * Camera projection for MAQAN's explored-surface terrain view.
 * Coordinates are local chunk-space offsets and sampled block heights.
 * This is a view transform only; it never creates or reveals terrain data.
 */
public final class SurfaceTerrainProjection {
    public record Point(double x,double y,double depth) {}
    private SurfaceTerrainProjection() {}

    public static Point project(double chunkX,double chunkZ,double height,
                                double yaw,double pitch,double scale,
                                double originX,double originY,double heightScale) {
        double cosYaw=Math.cos(yaw),sinYaw=Math.sin(yaw);
        double rx=chunkX*cosYaw-chunkZ*sinYaw;
        double rz=chunkX*sinYaw+chunkZ*cosYaw;
        double cp=Math.cos(pitch),sp=Math.sin(pitch);
        double vertical=height*heightScale;
        double screenY=rz*cp-vertical*sp;
        double depth=rz*sp+vertical*cp;
        return new Point(originX+rx*scale,originY+screenY*scale,depth);
    }

    public static double clampPitch(double pitch) {
        return Math.max(Math.toRadians(12),Math.min(Math.toRadians(78),pitch));
    }
}
