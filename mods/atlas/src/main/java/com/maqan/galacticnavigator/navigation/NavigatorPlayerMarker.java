package com.maqan.galacticnavigator.navigation;

import java.util.Objects;

/**
 * Player/ship marker expressed in the same system-space coordinate frame as
 * the active atlas. A coordinate adapter must produce this snapshot from the
 * actual runtime dimension/ship position; world block coordinates must never
 * be copied into system space without an explicit transform.
 */
public record NavigatorPlayerMarker(
        String dimensionId,
        String systemId,
        MovingTargetInterception.Vector3 systemPosition,
        MovingTargetInterception.Vector3 velocity,
        double headingRadians,
        double sampledAtSeconds,
        String displayName,
        boolean positionKnown) {
    public NavigatorPlayerMarker {
        dimensionId=dimensionId==null?"":dimensionId;
        systemId=systemId==null?"":systemId;
        Objects.requireNonNull(systemPosition,"systemPosition");
        Objects.requireNonNull(velocity,"velocity");
        if(!Double.isFinite(headingRadians)||!Double.isFinite(sampledAtSeconds))
            throw new IllegalArgumentException("Marker orientation and sample time must be finite");
        displayName=displayName==null||displayName.isBlank()?"You":displayName;
        // An empty systemId is valid when several Cosmic Horizons systems share
        // one attached Minecraft dimension. The dimension coordinate frame is
        // still exact; the atlas decides whether that shared frame is relevant.
    }
    public double speed(){return velocity.length();}
    public double distanceTo(MovingTargetInterception.Vector3 point){return systemPosition.distanceTo(point);}
}
