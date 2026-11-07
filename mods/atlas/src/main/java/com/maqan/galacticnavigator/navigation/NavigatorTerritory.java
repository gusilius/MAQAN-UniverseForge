package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A player-authored political/geographic polygon bound to a dimension or a cosmic system frame. */
public final class NavigatorTerritory {
    public enum CoordinateFrame { DIMENSION, SYSTEM_SPACE }
    public enum Kind { CLAIM, KINGDOM, EMPIRE, ALLIANCE, PROTECTED, CONTESTED, CUSTOM }

    public static final class Vertex {
        public double x, y, z;
        public Vertex() {}
        public Vertex(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Territory vertex must be finite");
            this.x=x; this.y=y; this.z=z;
        }
    }

    public String id;
    public String name;
    public String owner;
    public String dimensionId;
    public String systemId;
    public CoordinateFrame frame;
    public Kind kind;
    public String group;
    public int color;
    public int fillAlpha;
    public boolean visible;
    public boolean locked;
    public long createdAt;
    public List<Vertex> vertices = new ArrayList<>();

    public NavigatorTerritory() {}

    public NavigatorTerritory(String name, String owner, String dimensionId, String systemId,
                              CoordinateFrame frame, Kind kind, String group, int color,
                              int fillAlpha, List<Vertex> vertices) {
        this.id=UUID.randomUUID().toString();
        this.name=clean(name,"Territory");
        this.owner=clean(owner,"Unassigned");
        this.dimensionId=clean(dimensionId,"");
        this.systemId=clean(systemId,"");
        this.frame=frame==null?CoordinateFrame.DIMENSION:frame;
        this.kind=kind==null?Kind.CLAIM:kind;
        this.group=clean(group,"Default");
        this.color=color;
        this.fillAlpha=Math.max(0,Math.min(160,fillAlpha));
        this.visible=true;
        this.createdAt=System.currentTimeMillis();
        this.vertices=vertices==null?new ArrayList<>():new ArrayList<>(vertices);
        validate();
    }

    public void validate() {
        if(id==null||id.isBlank()) id=UUID.randomUUID().toString();
        name=clean(name,"Territory");
        owner=clean(owner,"Unassigned");
        dimensionId=clean(dimensionId,"");
        systemId=clean(systemId,"");
        group=clean(group,"Default");
        if(frame==null)frame=CoordinateFrame.DIMENSION;
        if(kind==null)kind=Kind.CLAIM;
        fillAlpha=Math.max(0,Math.min(160,fillAlpha));
        if(frame==CoordinateFrame.DIMENSION && dimensionId.isBlank())
            throw new IllegalArgumentException("Dimension territory requires a dimension ID");
        if(frame==CoordinateFrame.SYSTEM_SPACE && systemId.isBlank())
            throw new IllegalArgumentException("System territory requires a system ID");
        if(vertices==null)vertices=new ArrayList<>();
        if(vertices.size()<3)throw new IllegalArgumentException("Territory requires at least three vertices");
        if(vertices.size()>4096)throw new IllegalArgumentException("Territory exceeds 4096 vertices");
        for(Vertex v:vertices) {
            if(v==null||!Double.isFinite(v.x)||!Double.isFinite(v.y)||!Double.isFinite(v.z))
                throw new IllegalArgumentException("Invalid territory vertex");
        }
    }

    private static String clean(String value,String fallback) {
        return value==null||value.isBlank()?fallback:value.trim();
    }
}
