package com.maqan.galacticnavigator.navigation;

import java.util.UUID;

/** A user-authored point of interest in either a Minecraft dimension or a star-system frame. */
public final class NavigatorWaypoint {
    public enum CoordinateFrame { DIMENSION, SYSTEM_SPACE }
    public enum Scope { LOCAL, GLOBAL }
    public enum Category {
        PERSONAL, BASE, PORTAL, STATION, RESOURCE, DANGER, QUEST, ROUTE, DEATH, OTHER
    }

    public String id;
    public String name;
    public String dimensionId;
    public String systemId;
    public CoordinateFrame frame;
    public Scope scope;
    public Category category;
    public String group;
    public String icon;
    public int color;
    public double x, y, z;
    public boolean visible;
    public boolean temporary;
    public long createdAt;
    public String note;

    public NavigatorWaypoint() {}

    public NavigatorWaypoint(String name, String dimensionId, String systemId,
                             CoordinateFrame frame, Scope scope, Category category,
                             String group, String icon, int color,
                             double x, double y, double z,
                             boolean visible, boolean temporary, String note) {
        this.id=UUID.randomUUID().toString();
        this.name=clean(name,"Waypoint");
        this.dimensionId=clean(dimensionId,"");
        this.systemId=clean(systemId,"");
        this.frame=frame==null?CoordinateFrame.DIMENSION:frame;
        this.scope=scope==null?Scope.LOCAL:scope;
        this.category=category==null?Category.OTHER:category;
        this.group=clean(group,"Default");
        this.icon=clean(icon,"◆");
        this.color=color;
        this.x=x; this.y=y; this.z=z;
        this.visible=true; this.temporary=temporary;
        this.createdAt=System.currentTimeMillis();
        this.note=clean(note,"");
        validate();
    }

    public void validate() {
        if(id==null||id.isBlank()) id=UUID.randomUUID().toString();
        name=clean(name,"Waypoint");
        dimensionId=clean(dimensionId,"");
        systemId=clean(systemId,"");
        if(frame==null) frame=CoordinateFrame.DIMENSION;
        if(scope==null) scope=Scope.LOCAL;
        if(category==null) category=Category.OTHER;
        group=clean(group,"Default");
        icon=clean(icon,"◆");
        note=clean(note,"");
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
            throw new IllegalArgumentException("Waypoint coordinates must be finite");
        if(frame==CoordinateFrame.SYSTEM_SPACE && systemId.isBlank())
            throw new IllegalArgumentException("System-space waypoint requires a system ID");
        if(frame==CoordinateFrame.DIMENSION && dimensionId.isBlank())
            throw new IllegalArgumentException("Dimension waypoint requires a dimension ID");
    }

    private static String clean(String value,String fallback) {
        return value==null||value.isBlank()?fallback:value.trim();
    }
}
