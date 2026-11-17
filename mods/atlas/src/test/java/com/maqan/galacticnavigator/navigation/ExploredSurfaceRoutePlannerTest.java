package com.maqan.galacticnavigator.navigation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExploredSurfaceRoutePlannerTest {
    private static NavigatorExplorationStore.ExploredChunk cell(int x,int z,int y,String biome) {
        return new NavigatorExplorationStore.ExploredChunk(x,z,y,biome,0,1,1,null,null);
    }

    @Test void refusesUnknownStartAndDestinationBeforeNativeDispatch() {
        var known=List.of(cell(0,0,64,"minecraft:plains"));
        assertFalse(ExploredSurfaceRoutePlanner.find(known,1,0,0,0,
                ExploredSurfaceRoutePlanner.Mode.WALK).found());
        assertFalse(ExploredSurfaceRoutePlanner.find(known,0,0,1,0,
                ExploredSurfaceRoutePlanner.Mode.WALK).found());
    }

    @Test void sameChunkReturnsSinglePointWithoutNativeWork() {
        var result=ExploredSurfaceRoutePlanner.find(List.of(cell(-4,9,71,"minecraft:plains")),
                -4,9,-4,9,ExploredSurfaceRoutePlanner.Mode.WALK);
        assertTrue(result.found());
        assertEquals(List.of(new ExploredSurfaceRoutePlanner.Point(-4,9,71)),result.points());
        assertEquals(0.0,result.cost());
        assertEquals(0,result.expandedNodes());
    }

    @Test void nativeRoutePacketReturnsAuthoritativeCostAndValidEndpointsWhenAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(NavigatorNativeBridge.nativeAvailable(),
                "Native Atlas library is not staged in this local test environment");
        int[] explored={0,0,64,0, 1,0,64,0, 2,0,64,0};
        int[] packet=NavigatorNativeBridge.routeExploredSurface(explored,0,0,2,0,0,100);
        assertNotNull(packet);
        assertEquals(1,packet[0]);
        assertEquals(10,packet.length);
        double cost=Double.longBitsToDouble((Integer.toUnsignedLong(packet[3])<<32)
                |Integer.toUnsignedLong(packet[2]));
        assertEquals(2.0,cost,1.0e-9);
    }

    @Test void nativeRouteRejectsMalformedProjectionAndUnsupportedMode() {
        if(!NavigatorNativeBridge.nativeAvailable())return;
        assertNull(NavigatorNativeBridge.routeExploredSurface(new int[]{0,0,64},0,0,0,0,0,10));
        assertNull(NavigatorNativeBridge.routeExploredSurface(new int[]{0,0,64,0},0,0,0,0,99,10));
    }
}
