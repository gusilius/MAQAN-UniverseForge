package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.NavigatorExplorationStore;
import com.maqan.galacticnavigator.navigation.ExploredSurfaceRoutePlanner;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPosition;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPositionTracker;
import com.maqan.galacticnavigator.navigation.NavigatorWaypoint;
import com.maqan.galacticnavigator.navigation.NavigatorWaypointStore;
import com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore;
import com.maqan.galacticnavigator.navigation.WorldForgeMapSnapshot;
import com.maqan.galacticnavigator.navigation.SurfaceTerrainProjection;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Fog-of-war map: only chunks explicitly recorded while the player occupied them are rendered. */
public final class NavigatorSurfaceMapScreen extends Screen {
    private final String dimension;
    private final Screen parent;
    private double zoom=1.0,panX,panY;
    private boolean dragging;
    private double dragX,dragY;
    private int centerChunkX,centerChunkZ;
    private int selectedSectionY;
    private boolean underground;
    private boolean terrain3D;
    private double cameraYaw=Math.toRadians(35),cameraPitch=Math.toRadians(48),terrainHeightScale=0.055;
    private boolean cameraDrag;
    private Button viewModeButton;
    private Button layerButton,depthDownButton,depthUpButton,routeModeButton,clearRouteButton;
    private ExploredSurfaceRoutePlanner.Mode routeMode=ExploredSurfaceRoutePlanner.Mode.WALK;
    private ExploredSurfaceRoutePlanner.Result surfaceRoute;
    private String selectedWaypointId="";
    private int routeStartX=Integer.MIN_VALUE,routeStartZ=Integer.MIN_VALUE;
    private static final int BACKGROUND=0xF0060B16;

    public NavigatorSurfaceMapScreen(String dimension) { this(dimension,null); }
    public NavigatorSurfaceMapScreen(String dimension,Screen parent) {
        super(Component.literal("MAQAN · Surface Map"));
        this.parent=parent;
        this.dimension=dimension==null?"":dimension;
        NavigatorWorldForgeMapStore.reload();
        NavigatorPlayerPosition p=NavigatorPlayerPositionTracker.current();
        if(p!=null&&this.dimension.equals(p.dimensionId())){
            centerChunkX=((int)Math.floor(p.x()))>>4;
            centerChunkZ=((int)Math.floor(p.z()))>>4;
            selectedSectionY=Math.floorDiv((int)Math.floor(p.y()),16);
        }
    }

    @Override protected void init(){
        viewModeButton=addRenderableWidget(Button.builder(Component.literal("VIEW: 2D"),b->{
            terrain3D=!terrain3D;
            b.setMessage(Component.literal(terrain3D?"VIEW: 3D":"VIEW: 2D"));
        }).bounds(18,48,86,20).build());
        layerButton=addRenderableWidget(Button.builder(Component.literal("LAYER: SURFACE"),b->{
            underground=!underground;
            b.setMessage(Component.literal(underground?"LAYER: UNDERGROUND":"LAYER: SURFACE"));
            updateDepthButtons();
        }).bounds(110,48,124,20).build());
        depthDownButton=addRenderableWidget(Button.builder(Component.literal("DEPTH −"),b->{
            selectedSectionY--;updateDepthButtons();
        }).bounds(238,48,64,20).build());
        depthUpButton=addRenderableWidget(Button.builder(Component.literal("DEPTH +"),b->{
            selectedSectionY++;updateDepthButtons();
        }).bounds(306,48,64,20).build());
        routeModeButton=addRenderableWidget(Button.builder(Component.literal("ROUTE: WALK"),b->{
            var modes=ExploredSurfaceRoutePlanner.Mode.values();
            routeMode=modes[(routeMode.ordinal()+1)%modes.length];
            b.setMessage(Component.literal("ROUTE: "+routeMode.name()));
            recalculateRoute();
        }).bounds(374,48,104,20).build());
        clearRouteButton=addRenderableWidget(Button.builder(Component.literal("CLEAR ROUTE"),b->{
            selectedWaypointId="";surfaceRoute=null;routeStartX=routeStartZ=Integer.MIN_VALUE;
        }).bounds(482,48,96,20).build());
        addRenderableWidget(Button.builder(Component.literal("OPEN 3D PLANET MAP"),
                b->MaqanScreenHost.open(minecraft, new NavigatorTerrain3DScreen(dimension,this)))
                .bounds(Math.max(18,width-190),height-68,172,20).build());
        updateDepthButtons();
    }
    private void updateDepthButtons(){
        if(depthDownButton!=null)depthDownButton.setMessage(Component.literal("Y "+(selectedSectionY*16)));
        if(depthUpButton!=null)depthUpButton.setMessage(Component.literal("Y "+(selectedSectionY*16+15)));
    }

    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
        MaqanUiTheme.backdrop(g,width,height);
        MaqanUiTheme.panel(g,12,12,width-12,42);
        MaqanUiTheme.titleRight(g,font,"MAQAN  /  SURFACE CARTOGRAPHY",dimension,width);

        int mapLeft=18,mapTop=74,mapRight=width-18,mapBottom=height-48;
        g.fill(mapLeft,mapTop,mapRight,mapBottom,0xC908111C);
        for(int x=mapLeft;x<mapRight;x+=32)g.fill(x,mapTop,x+1,mapBottom,0x183A6680);
        for(int y=mapTop;y<mapBottom;y+=32)g.fill(mapLeft,y,mapRight,y+1,0x183A6680);

        NavigatorPlayerPosition player=NavigatorPlayerPositionTracker.current();
        if(player!=null&&dimension.equals(player.dimensionId())
                &&NavigatorPlayerMarkerPreferences.tracking()!=NavigatorPlayerMarkerPreferences.Tracking.FREE){
            centerChunkX=((int)Math.floor(player.x()))>>4;
            centerChunkZ=((int)Math.floor(player.z()))>>4;
        }
        double cell=18.0*zoom;
        List<NavigatorExplorationStore.ExploredChunk> chunks=NavigatorExplorationStore.chunks(dimension);
        if(!underground)refreshRouteIfPlayerMoved(player);
        List<NavigatorExplorationStore.ExploredLayer> layers=NavigatorExplorationStore.layers(dimension);
        int visibleCells=0;
        if(!underground&&!terrain3D){
            for(NavigatorExplorationStore.ExploredChunk chunk:chunks){
                double[] projected=project(chunk.chunkX(),chunk.chunkZ(),cell,mapLeft,mapTop,mapRight,mapBottom,player);
                double px=projected[0],py=projected[1];
                if(px+cell<mapLeft||px>mapRight||py+cell<mapTop||py>mapBottom)continue;
                int base=chunk.mapColor()==0?0xFF557A68:0xFF000000|chunk.mapColor();
                if(cell>=12.0){
                    // High-detail raster: only the 256 samples captured from this explored chunk.
                    for(int rz=0;rz<16;rz++)for(int rx=0;rx<16;rx++){
                        int index=rz*16+rx;
                        int terrain=chunk.colorAt(index);
                        if(terrain==0)terrain=base;
                        int heightDelta=chunk.heightAt(index)-chunk.surfaceY();
                        int relief=Math.max(-22,Math.min(22,heightDelta/2));
                        int x0=(int)Math.floor(px+cell*rx/16.0);
                        int x1=(int)Math.ceil(px+cell*(rx+1)/16.0);
                        int y0=(int)Math.floor(py+cell*rz/16.0);
                        int y1=(int)Math.ceil(py+cell*(rz+1)/16.0);
                        g.fill(x0,y0,x1,y1,shade(terrain,relief));
                    }
                }else{
                    int color=shade(base,Math.max(0,Math.min(28,(chunk.surfaceY()-64)/4)));
                    g.fill((int)px+1,(int)py+1,(int)(px+cell-1),(int)(py+cell-1),color);
                }
                g.fill((int)px,(int)py,(int)(px+cell),(int)py+1,0x665D8A91);
                visibleCells++;
            }
        }else if(underground){
            for(NavigatorExplorationStore.ExploredLayer layer:layers){
                if(layer.sectionY()!=selectedSectionY)continue;
                double[] projected=project(layer.chunkX(),layer.chunkZ(),cell,mapLeft,mapTop,mapRight,mapBottom,player);
                double px=projected[0],py=projected[1];
                if(px+cell<mapLeft||px>mapRight||py+cell<mapTop||py>mapBottom)continue;
                int base=layer.mapColor()==0?0xFF536A83:0xFF000000|layer.mapColor();
                int color=shade(base,Math.max(0,Math.min(24,selectedSectionY<0?Math.abs(selectedSectionY):0)));
                g.fill((int)px+1,(int)py+1,(int)(px+cell-1),(int)(py+cell-1),color);
                g.fill((int)px,(int)py,(int)(px+cell),(int)py+1,0x886B9AC4);
                visibleCells++;
            }
        }

        if(!underground&&terrain3D) renderTerrain3D(g,chunks,mapLeft,mapTop,mapRight,mapBottom,cell);
        if(!underground) renderWorldForgeOverlay(g,cell,mapLeft,mapTop,mapRight,mapBottom,player);

        if(!underground&&surfaceRoute!=null&&surfaceRoute.found()){
            List<ExploredSurfaceRoutePlanner.Point> routePoints=surfaceRoute.points();
            for(int i=1;i<routePoints.size();i++){
                var a=routePoints.get(i-1);var b=routePoints.get(i);
                double[] pa=project(a.chunkX()+0.5,a.chunkZ()+0.5,cell,mapLeft,mapTop,mapRight,mapBottom,player);
                double[] pb=project(b.chunkX()+0.5,b.chunkZ()+0.5,cell,mapLeft,mapTop,mapRight,mapBottom,player);
                if(insideMap(pa[0],pa[1],mapLeft,mapTop,mapRight,mapBottom)
                        &&insideMap(pb[0],pb[1],mapLeft,mapTop,mapRight,mapBottom))
                    drawLine(g,(int)Math.round(pa[0]),(int)Math.round(pa[1]),
                            (int)Math.round(pb[0]),(int)Math.round(pb[1]),0xFF55E7F0);
            }
            for(var point:routePoints){
                double[] pp=project(point.chunkX()+0.5,point.chunkZ()+0.5,cell,mapLeft,mapTop,mapRight,mapBottom,player);
                if(!insideMap(pp[0],pp[1],mapLeft,mapTop,mapRight,mapBottom))continue;
                int px=(int)Math.round(pp[0]),py=(int)Math.round(pp[1]);
                g.fill(px-2,py-2,px+3,py+3,0xFF07121C);
                g.fill(px-1,py-1,px+2,py+2,0xFFB5F8FF);
            }
        }

        for(NavigatorWaypoint waypoint:NavigatorWaypointStore.all()){
            if(!waypoint.visible||waypoint.frame!=NavigatorWaypoint.CoordinateFrame.DIMENSION
                    ||!dimension.equals(waypoint.dimensionId))continue;
            double[] projected=project(waypoint.x/16.0,waypoint.z/16.0,cell,mapLeft,mapTop,mapRight,mapBottom,player);
            double wx=projected[0],wy=projected[1];
            if(wx<mapLeft||wx>mapRight||wy<mapTop||wy>mapBottom)continue;
            int mx=(int)Math.round(wx),my=(int)Math.round(wy);
            int waypointColor=waypoint.color==0?0xFFFFC66D:waypoint.color;
            if(waypoint.id.equals(selectedWaypointId)){
                g.fill(mx-8,my-8,mx+9,my-6,0xFF55E7F0);
                g.fill(mx-8,my+7,mx+9,my+9,0xFF55E7F0);
                g.fill(mx-8,my-6,mx-6,my+7,0xFF55E7F0);
                g.fill(mx+7,my-6,mx+9,my+7,0xFF55E7F0);
            }
            g.fill(mx-5,my-5,mx+6,my+6,0xDD07101A);
            g.fill(mx-3,my-3,mx+4,my+4,waypointColor);
            g.drawString(font,waypoint.icon+" "+waypoint.name,mx+7,my-5,waypointColor,false);
        }

        double[] playerPoint=player!=null&&dimension.equals(player.dimensionId())
                ?project(player.x()/16.0,player.z()/16.0,cell,mapLeft,mapTop,mapRight,mapBottom,player)
                :new double[]{(mapLeft+mapRight)/2.0+panX,(mapTop+mapBottom)/2.0+panY};
        int cx=(int)Math.round(playerPoint[0]),cy=(int)Math.round(playerPoint[1]);
        drawPlayerMarker(g,cx,cy,player);

        g.fill(12,height-38,width-12,height-12,0xD9101C2B);
        g.drawString(font,(underground?"VISITED DEPTH CELLS: "+visibleCells+"  ·  SECTION Y "+selectedSectionY
                :"EXPLORED SURFACE CHUNKS: "+chunks.size()),24,height-28,0xFFB6C7D8,false);
        String routeLabel=surfaceRoute==null?"RIGHT-CLICK A WAYPOINT TO ROUTE"
                :surfaceRoute.message()+"  ·  "+(surfaceRoute.found()
                    ?surfaceRoute.points().size()+" CHUNKS  ·  COST "+String.format(java.util.Locale.ROOT,"%.1f",surfaceRoute.cost())
                    :"EXPANDED "+surfaceRoute.expandedNodes()+" NODES");
        g.drawString(font,routeLabel,width/2+20,height-28,
                surfaceRoute!=null&&!surfaceRoute.found()?0xFFFFB47A:0xFF8EA8BC,false);
        super.render(g,mouseX,mouseY,partialTick);
        MaqanUiTheme.skinWidgets(this,g,font);
    }
    /** Renders the last validated WorldForge export in its own Minecraft dimension frame. */
    private void renderWorldForgeOverlay(GuiGraphics g,double cell,int left,int top,int right,int bottom,
                                         NavigatorPlayerPosition player){
        WorldForgeMapSnapshot snapshot=NavigatorWorldForgeMapStore.current();
        if(snapshot==null)return;
        List<WorldForgeMapSnapshot.Territory> territories=
                NavigatorWorldForgeMapStore.territories(dimension);
        List<WorldForgeMapSnapshot.Feature> features=
                NavigatorWorldForgeMapStore.features(dimension);
        int drawnTerritories=0,drawnFeatures=0;
        for(WorldForgeMapSnapshot.Territory territory:territories){
            if(territory.vertices==null||territory.vertices.size()<3)continue;
            List<double[]> points=new ArrayList<>(territory.vertices.size());
            double minX=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
            double maxX=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
            for(WorldForgeMapSnapshot.Vertex vertex:territory.vertices){
                double[] point=project(vertex.x/16.0,vertex.z/16.0,cell,left,top,right,bottom,player);
                points.add(point);minX=Math.min(minX,point[0]);maxX=Math.max(maxX,point[0]);
                minZ=Math.min(minZ,point[1]);maxZ=Math.max(maxZ,point[1]);
            }
            if(maxX<left||minX>right||maxZ<top||minZ>bottom)continue;
            int color=territory.color==0?0xFF69B8E8:(territory.color|0xFF000000);
            int alpha=Math.max(0,Math.min(160,territory.fill_alpha));
            int fill=(alpha<<24)|(color&0x00FFFFFF);
            // A restrained translucent vertex fill and crisp border keep terrain legible.
            for(int i=1;i<points.size()-1;i++){
                double[] a=points.get(0),b=points.get(i),d=points.get(i+1);
                if(insideMap(a[0],a[1],left,top,right,bottom)
                        &&insideMap(b[0],b[1],left,top,right,bottom)
                        &&insideMap(d[0],d[1],left,top,right,bottom)){
                    g.fill((int)Math.floor(Math.min(a[0],Math.min(b[0],d[0]))),
                            (int)Math.floor(Math.min(a[1],Math.min(b[1],d[1]))),
                            (int)Math.ceil(Math.max(a[0],Math.max(b[0],d[0]))),
                            (int)Math.ceil(Math.max(a[1],Math.max(b[1],d[1]))),fill);
                }
            }
            for(int i=0;i<points.size();i++){
                double[] a=points.get(i),b=points.get((i+1)%points.size());
                if(segmentTouchesMap(a[0],a[1],b[0],b[1],left,top,right,bottom))
                    drawLine(g,(int)Math.round(a[0]),(int)Math.round(a[1]),
                            (int)Math.round(b[0]),(int)Math.round(b[1]),color);
            }
            double[] label=points.get(0);
            if(insideMap(label[0],label[1],left,top,right,bottom))
                g.drawString(font,territory.name,(int)Math.round(label[0])+3,
                        (int)Math.round(label[1])-10,color,false);
            drawnTerritories++;
        }
        for(WorldForgeMapSnapshot.Feature feature:features){
            double[] point=project(feature.x/16.0,feature.z/16.0,cell,left,top,right,bottom,player);
            if(!insideMap(point[0],point[1],left,top,right,bottom))continue;
            int x=(int)Math.round(point[0]),y=(int)Math.round(point[1]);
            int color=feature.color==0?0xFFFFC66D:(feature.color|0xFF000000);
            g.fill(x-4,y-4,x+5,y+5,0xDD07101A);
            g.fill(x-2,y-2,x+3,y+3,color);
            drawnFeatures++;
        }
        if(drawnTerritories>0||drawnFeatures>0)
            g.drawString(font,"WORLDFORGE  ·  "+drawnTerritories+" TERRITORIES  ·  "
                    +drawnFeatures+" FEATURES",left+8,top+8,0xFF9BD7F1,false);
    }

    private static boolean segmentTouchesMap(double x1,double y1,double x2,double y2,
                                             int left,int top,int right,int bottom){
        return !(Math.max(x1,x2)<left||Math.min(x1,x2)>right
                ||Math.max(y1,y2)<top||Math.min(y1,y2)>bottom);
    }

    private record TerrainPixel(double depth,int x,int y,int color) {}
    private void renderTerrain3D(GuiGraphics g,List<NavigatorExplorationStore.ExploredChunk> chunks,
                                 int left,int top,int right,int bottom,double cell){
        // Render only persisted samples from chunks explicitly explored by this player.
        List<TerrainPixel> pixels=new ArrayList<>();
        double unit=Math.max(1.0,Math.min(5.0,cell/16.0));
        double scale=Math.max(1.2,Math.min(8.0,cell/2.8));
        double originX=(left+right)/2.0+panX,originY=(top+bottom)/2.0+panY;
        for(var chunk:chunks){
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                int index=z*16+x;
                double localX=(chunk.chunkX()-centerChunkX)+(x+0.5)/16.0;
                double localZ=(chunk.chunkZ()-centerChunkZ)+(z+0.5)/16.0;
                double h=chunk.heightAt(index)-chunk.surfaceY();
                var p=SurfaceTerrainProjection.project(localX,localZ,h,cameraYaw,cameraPitch,
                        scale,originX,originY,terrainHeightScale);
                int px=(int)Math.round(p.x()),py=(int)Math.round(p.y());
                if(px+8<left||px>right||py+8<top||py>bottom)continue;
                int color=chunk.colorAt(index);
                if(color==0)color=chunk.mapColor()==0?0xFF557A68:chunk.mapColor();
                int relief=(int)Math.max(-30,Math.min(30,h/2.0));
                pixels.add(new TerrainPixel(p.depth(),px,py,shade(color,relief)));
            }
        }
        pixels.sort(Comparator.comparingDouble(TerrainPixel::depth));
        int pixelSize=(int)Math.max(2,Math.min(7,unit*1.6));
        for(TerrainPixel pixel:pixels)
            g.fill(pixel.x(),pixel.y(),pixel.x()+pixelSize,pixel.y()+pixelSize,pixel.color());
        g.drawString(font,"3D TERRAIN  ·  RIGHT-DRAG: ROTATE  ·  WHEEL: ZOOM",left+8,top+8,0xFF9CCBDE,false);
    }

    private void refreshRouteIfPlayerMoved(NavigatorPlayerPosition player){
        if(selectedWaypointId.isBlank()||player==null||!dimension.equals(player.dimensionId()))return;
        int sx=((int)Math.floor(player.x()))>>4,sz=((int)Math.floor(player.z()))>>4;
        if(sx!=routeStartX||sz!=routeStartZ)recalculateRoute();
    }
    private void recalculateRoute(){
        NavigatorWaypoint target=NavigatorWaypointStore.all().stream()
                .filter(w->w.id.equals(selectedWaypointId)&&w.visible
                        &&w.frame==NavigatorWaypoint.CoordinateFrame.DIMENSION
                        &&dimension.equals(w.dimensionId)).findFirst().orElse(null);
        NavigatorPlayerPosition player=NavigatorPlayerPositionTracker.current();
        if(target==null||player==null||!dimension.equals(player.dimensionId())){
            surfaceRoute=null;return;
        }
        routeStartX=((int)Math.floor(player.x()))>>4;
        routeStartZ=((int)Math.floor(player.z()))>>4;
        int goalX=((int)Math.floor(target.x))>>4,goalZ=((int)Math.floor(target.z))>>4;
        surfaceRoute=ExploredSurfaceRoutePlanner.find(NavigatorExplorationStore.chunks(dimension),
                routeStartX,routeStartZ,goalX,goalZ,routeMode);
    }
    private boolean selectWaypointAt(double mouseX,double mouseY){
        NavigatorPlayerPosition player=NavigatorPlayerPositionTracker.current();
        int mapLeft=18,mapTop=74,mapRight=width-18,mapBottom=height-48;
        if(mouseX<mapLeft||mouseX>mapRight||mouseY<mapTop||mouseY>mapBottom||underground)return false;
        double cell=18.0*zoom,best=100.0;
        NavigatorWaypoint selected=null;
        for(NavigatorWaypoint waypoint:NavigatorWaypointStore.all()){
            if(!waypoint.visible||waypoint.frame!=NavigatorWaypoint.CoordinateFrame.DIMENSION
                    ||!dimension.equals(waypoint.dimensionId))continue;
            double[] point=project(waypoint.x/16.0,waypoint.z/16.0,cell,
                    mapLeft,mapTop,mapRight,mapBottom,player);
            double dx=point[0]-mouseX,dy=point[1]-mouseY,dist=dx*dx+dy*dy;
            if(dist<best){best=dist;selected=waypoint;}
        }
        if(selected==null)return false;
        selectedWaypointId=selected.id;
        recalculateRoute();
        return true;
    }

    private static boolean insideMap(double x,double y,int left,int top,int right,int bottom){
        return x>=left&&x<=right&&y>=top&&y<=bottom;
    }

    private double[] project(double chunkX,double chunkZ,double cell,
                              int mapLeft,int mapTop,int mapRight,int mapBottom,
                              NavigatorPlayerPosition player){
        double dx=(chunkX-centerChunkX)*cell,dz=(chunkZ-centerChunkZ)*cell;
        if(player!=null&&NavigatorPlayerMarkerPreferences.tracking()==NavigatorPlayerMarkerPreferences.Tracking.LOCK_HEADING){
            double yaw=Math.toRadians(player.yaw());
            double headingX=-Math.sin(yaw),headingY=Math.cos(yaw);
            double rotation=-Math.PI/2.0-Math.atan2(headingY,headingX);
            double cos=Math.cos(rotation),sin=Math.sin(rotation);
            double rx=dx*cos-dz*sin,rz=dx*sin+dz*cos;
            dx=rx;dz=rz;
        }
        return new double[]{(mapLeft+mapRight)/2.0+dx+panX,
                (mapTop+mapBottom)/2.0+dz+panY};
    }

    private void drawPlayerMarker(GuiGraphics g,int x,int y,NavigatorPlayerPosition player){
        if(!NavigatorPlayerMarkerPreferences.visible())return;
        int color=NavigatorPlayerMarkerPreferences.color();
        int radius=Math.max(4,(int)Math.round(7*NavigatorPlayerMarkerPreferences.scale()));
        g.fill(x-radius-4,y-radius-4,x+radius+5,y-radius-2,0x6655DDEE);
        g.fill(x-radius-4,y+radius+3,x+radius+5,y+radius+5,0x6655DDEE);
        g.fill(x-radius-4,y-radius-2,x-radius-2,y+radius+4,0x6655DDEE);
        g.fill(x+radius+3,y-radius-2,x+radius+5,y+radius+4,0x6655DDEE);
        switch(NavigatorPlayerMarkerPreferences.shape()){
            case DOT -> g.fill(x-3,y-3,x+4,y+4,color);
            case TRIANGLE -> {
                g.fill(x,y-radius,x+1,y-radius+1,color);
                g.fill(x-1,y-radius+1,x+2,y-radius+2,color);
                g.fill(x-2,y-radius+2,x+3,y-radius+3,color);
                g.fill(x-3,y-radius+3,x+4,y+3,color);
                g.fill(x-2,y+3,x+3,y+4,color);
            }
            case ARROW,SHIP -> {
                g.fill(x-1,y-radius,x+2,y+radius+1,color);
                g.fill(x-2,y-radius+3,x-1,y+radius-2,color);
                g.fill(x+2,y-radius+3,x+3,y+radius-2,color);
            }
        }
        if(player!=null&&NavigatorPlayerMarkerPreferences.showHeading()){
            double yaw=NavigatorPlayerMarkerPreferences.tracking()==NavigatorPlayerMarkerPreferences.Tracking.LOCK_HEADING
                    ?Math.PI:Math.toRadians(player.yaw());
            int ex=x-(int)Math.round(Math.sin(yaw)*(radius+12));
            int ey=y+(int)Math.round(Math.cos(yaw)*(radius+12));
            drawLine(g,x,y,ex,ey,color);
        }
        if(player!=null&&NavigatorPlayerMarkerPreferences.showVelocity()&&player.speed()>0.01){
            double length=Math.sqrt(player.velocityX()*player.velocityX()+player.velocityZ()*player.velocityZ());
            if(length>0.001)drawLine(g,x,y,x+(int)Math.round(player.velocityX()/length*28),
                    y+(int)Math.round(player.velocityZ()/length*28),0xFFB8E9FF);
        }
        if(NavigatorPlayerMarkerPreferences.showLabel())
            g.drawString(font,"YOU",x+radius+8,y-radius-5,color,false);
        if(player!=null&&NavigatorPlayerMarkerPreferences.showRange())
            g.drawString(font,String.format(java.util.Locale.ROOT,"%.2f blocks/t",player.speed()),
                    x+radius+8,y+radius+2,0xFF9BB4C8,false);
    }
    private static void drawLine(GuiGraphics g,int x1,int y1,int x2,int y2,int color){
        int steps=Math.max(1,Math.max(Math.abs(x2-x1),Math.abs(y2-y1)));
        for(int i=0;i<=steps;i++){
            double t=(double)i/steps;
            int x=(int)Math.round(x1+(x2-x1)*t),y=(int)Math.round(y1+(y2-y1)*t);
            g.fill(x,y,x+2,y+2,color);
        }
    }
    private static int shade(int argb,int amount){
        int r=Math.min(255,((argb>>16)&255)+amount);
        int green=Math.min(255,((argb>>8)&255)+amount);
        int b=Math.min(255,(argb&255)+amount);
        return (argb&0xFF000000)|(r<<16)|(green<<8)|b;
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(super.mouseClicked(x,y,button))return true;
        if(button==1&&selectWaypointAt(x,y))return true;
        if((button==0||button==2)&&x>=18&&x<=width-18&&y>=74&&y<=height-48){
            dragging=true;dragX=x;dragY=y;cameraDrag=button==2;return true;
        }
        return false;
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        boolean handled=super.mouseReleased(x,y,button);
        if((button==0||button==2)&&dragging){dragging=false;cameraDrag=false;return true;}
        return handled;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(dragging&&button==2&&cameraDrag&&terrain3D){cameraYaw+=dx*0.012;cameraPitch=SurfaceTerrainProjection.clampPitch(cameraPitch+dy*0.009);return true;}
        if(dragging&&button==0){panX+=dx;panY+=dy;return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){
        zoom=Math.max(0.35,Math.min(4.0,zoom*(delta>0?1.15:1.0/1.15)));
        return true;
    }
    @Override public void onClose(){if(minecraft!=null)MaqanScreenHost.back(minecraft, parent);else super.onClose();}
}
