package com.maqan.galacticnavigator.navigation;

import java.util.Objects;

/** Exact local player state; dimension coordinates are not mislabelled as system-space coordinates. */
public record NavigatorPlayerPosition(String dimensionId,double x,double y,double z,
                                      double velocityX,double velocityY,double velocityZ,
                                      float yaw,float pitch,long sampledAtMillis) {
    public NavigatorPlayerPosition {
        dimensionId=dimensionId==null?"":dimensionId;
        if(dimensionId.isBlank())throw new IllegalArgumentException("Dimension ID required");
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||!Double.isFinite(velocityX)||!Double.isFinite(velocityY)||!Double.isFinite(velocityZ)
                ||!Float.isFinite(yaw)||!Float.isFinite(pitch))
            throw new IllegalArgumentException("Player position must be finite");
    }
    public double speed(){return Math.sqrt(velocityX*velocityX+velocityY*velocityY+velocityZ*velocityZ);}
}
