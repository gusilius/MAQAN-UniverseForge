package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.NavigatorExplorationStore;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPosition;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPositionTracker;
import com.maqan.galacticnavigator.navigation.NavigatorWaypoint;
import com.maqan.galacticnavigator.navigation.NavigatorWaypointStore;
import com.maqan.galacticnavigator.navigation.NavigatorTerritory;
import com.maqan.galacticnavigator.navigation.NavigatorTerritoryStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.lwjgl.glfw.GLFW;

/**
 * MAQAN's own isometric terrain atlas. It renders only rasters already captured
 * by NavigatorExplorationStore; it never queries or loads world chunks.
 */
public final class NavigatorTerrain3DScreen extends Screen {
    private static final int BG = 0xF0060B16;
    private static final int PANEL = 0xD9101C2B;

    private final String dimension;
    private final Screen parent;
    private double yaw = Math.PI * 0.25;
    private double pitch = Math.toRadians(32.0);
    private double zoom = 1.0;
    private double relief = 0.28;
    private double panX;
    private double panY;
    private MapStyle mapStyle = MapStyle.NATURAL;
    private ViewMode viewMode = ViewMode.ISOMETRIC;
    private Button styleButton;
    private Button viewButton;
    private Button markerFilterButton;
    private Button playerLayerButton;
    private Button gridLayerButton;
    private boolean dragging;
    private int dragButton = -1;
    private boolean showWaypoints = true;
    private boolean showWaypointLabels = true;
    private boolean showPlayer = true;
    private boolean showGrid = true;
    private boolean showWorldForge = true;
    private boolean drawingTerritory;
    private final List<NavigatorTerritory.Vertex> draftTerritory = new ArrayList<>();
    private MarkerFilter markerFilter = MarkerFilter.ALL;
    private EditBox territoryNameField;
    private EditBox territoryOwnerField;
    private NavigatorTerritory.Kind territoryKind = NavigatorTerritory.Kind.CLAIM;
    private enum MapStyle { NATURAL, BIOME, ELEVATION, SLOPE, EXPLORATION_AGE }
    private enum ViewMode { TOP_DOWN, ISOMETRIC }
    private enum MarkerFilter { ALL, PERSONAL, BASE, PORTAL, STATION, RESOURCE, DANGER, QUEST, ROUTE, DEATH, OTHER }
    
    private double lastMouseX;
    private double lastMouseY;

    private record TerrainCell(double x, double y, int height, int color, double depth) {}

    public NavigatorTerrain3DScreen(String dimension, Screen parent) {
        super(Component.literal("MAQAN · 3D Planet Map"));
        this.dimension = dimension == null ? "" : dimension;
        this.parent = parent;
    }

    @Override
    protected void init() {
        com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.reload();
        addRenderableWidget(Button.builder(Component.literal("ROTATE −"), b -> yaw -= Math.PI / 12.0)
                .bounds(18, 44, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal("ROTATE +"), b -> yaw += Math.PI / 12.0)
                .bounds(104, 44, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal("TILT −"), b -> pitch = Math.max(Math.toRadians(8), Math.min(Math.toRadians(78), pitch - Math.toRadians(5))))
                .bounds(190, 44, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("TILT +"), b -> pitch = Math.max(Math.toRadians(8), Math.min(Math.toRadians(78), pitch + Math.toRadians(5))))
                .bounds(260, 44, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal("RELIEF −"), b -> relief = Math.max(0.08, relief - 0.05))
                .bounds(330, 44, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("RELIEF +"), b -> relief = Math.min(0.8, relief + 0.05))
                .bounds(406, 44, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("RESET"), b -> {
            yaw = Math.PI * 0.25; pitch = Math.toRadians(32); zoom = 1.0; relief = 0.28; panX = 0; panY = 0;
        }).bounds(482, 44, 62, 20).build());
        styleButton = addRenderableWidget(Button.builder(Component.literal("STYLE: NATURAL"), b -> {
            mapStyle = MapStyle.values()[(mapStyle.ordinal() + 1) % MapStyle.values().length];
            b.setMessage(Component.literal("STYLE: " + mapStyle.name().replace('_', ' ')));
        }).bounds(550, 44, 132, 20).build());
        viewButton = addRenderableWidget(Button.builder(Component.literal("VIEW: 3D"), b -> {
            viewMode = viewMode == ViewMode.ISOMETRIC ? ViewMode.TOP_DOWN : ViewMode.ISOMETRIC;
            b.setMessage(Component.literal(viewMode == ViewMode.ISOMETRIC ? "VIEW: 3D" : "VIEW: TOP"));
        }).bounds(688, 44, 92, 20).build());
        markerFilterButton = addRenderableWidget(Button.builder(Component.literal("MARKERS: ALL"), b -> {
            markerFilter = MarkerFilter.values()[(markerFilter.ordinal() + 1) % MarkerFilter.values().length];
            b.setMessage(Component.literal("MARKERS: " + markerFilter.name()));
        }).bounds(18, 68, 112, 20).build());
        playerLayerButton = addRenderableWidget(Button.builder(Component.literal("PLAYER: ON"), b -> {
            showPlayer = !showPlayer;
            b.setMessage(Component.literal("PLAYER: " + (showPlayer ? "ON" : "OFF")));
        }).bounds(136, 68, 88, 20).build());
        gridLayerButton = addRenderableWidget(Button.builder(Component.literal("GRID: ON"), b -> {
            showGrid = !showGrid;
            b.setMessage(Component.literal("GRID: " + (showGrid ? "ON" : "OFF")));
        }).bounds(230, 68, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("LABELS: ON"), b -> {
            showWaypointLabels = !showWaypointLabels;
            b.setMessage(Component.literal("LABELS: " + (showWaypointLabels ? "ON" : "OFF")));
        }).bounds(314, 68, 92, 20).build());
        addRenderableWidget(Button.builder(Component.literal("TERRITORY: DRAW"), b -> {
            drawingTerritory = !drawingTerritory;
            draftTerritory.clear();
            b.setMessage(Component.literal(drawingTerritory ? "DRAWING: ON" : "TERRITORY: DRAW"));
        }).bounds(416, 68, 126, 20).build());
        territoryNameField = new EditBox(font, 550, 68, 132, 20, Component.literal("Territory name"));
        territoryNameField.setMaxLength(48);
        territoryNameField.setValue("New Territory");
        addRenderableWidget(territoryNameField);
        territoryOwnerField = new EditBox(font, 688, 68, 120, 20, Component.literal("Owner"));
        territoryOwnerField.setMaxLength(48);
        territoryOwnerField.setValue("Unassigned");
        addRenderableWidget(territoryOwnerField);
        addRenderableWidget(Button.builder(Component.literal("KIND: CLAIM"), b -> {
            territoryKind = NavigatorTerritory.Kind.values()[
                    (territoryKind.ordinal() + 1) % NavigatorTerritory.Kind.values().length];
            b.setMessage(Component.literal("KIND: " + territoryKind.name()));
        }).bounds(814, 68, 112, 20).build());
        addRenderableWidget(Button.builder(Component.literal("WORLDFORGE: ON"), b -> {
            showWorldForge = !showWorldForge;
            b.setMessage(Component.literal("WORLDFORGE: " + (showWorldForge ? "ON" : "OFF")));
        }).bounds(18, 92, 132, 20).build());
        addRenderableWidget(Button.builder(Component.literal("RELOAD WF DATA"), b -> {
            boolean loaded = com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.reload();
            b.setMessage(Component.literal(loaded ? "WF DATA: LOADED" : "WF: NO DATA"));
        }).bounds(156, 92, 132, 20).build());
        addRenderableWidget(Button.builder(Component.literal("BACK"), b -> onClose())
                .bounds(width - 92, 44, 74, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MaqanUiTheme.backdrop(g,width,height);
        MaqanUiTheme.panel(g,12,10,width-12,36);
        MaqanUiTheme.titleRight(g,font,"MAQAN  /  PLANETARY TERRAIN  /  " + (viewMode == ViewMode.ISOMETRIC ? "3D" : "TOP-DOWN"),dimension,width);

        int left = 16, top = 118, right = width - 16, bottom = height - 42;
        g.fill(left, top, right, bottom, 0xC908111C);
        if (showGrid) {
            for (int x = left; x < right; x += 40) g.fill(x, top, x + 1, bottom, 0x183A6680);
            for (int y = top; y < bottom; y += 40) g.fill(left, y, right, y + 1, 0x183A6680);
        }

        List<NavigatorExplorationStore.ExploredChunk> all = NavigatorExplorationStore.chunks(dimension);
        NavigatorPlayerPosition player = NavigatorPlayerPositionTracker.current();
        boolean playerHere = player != null && dimension.equals(player.dimensionId());
        double centerBlockX = playerHere ? player.x() : averageChunkCenter(all, true);
        double centerBlockZ = playerHere ? player.z() : averageChunkCenter(all, false);
        double centerHeight = playerHere ? player.y() : averageSurfaceHeight(all);

        // Cull by the actual viewport instead of silently limiting the atlas to
        // an arbitrary number of visited chunks.
        double tile = 0.92 * zoom;
        double visibleWorldRadius = Math.max(width, height) / Math.max(0.12, tile * 0.72) * 0.72;
        List<NavigatorExplorationStore.ExploredChunk> visible = new ArrayList<>();
        for (var chunk : all) {
            double dx = ((chunk.chunkX() << 4) + 8) - centerBlockX;
            double dz = ((chunk.chunkZ() << 4) + 8) - centerBlockZ;
            if (dx * dx + dz * dz <= visibleWorldRadius * visibleWorldRadius)
                visible.add(chunk);
        }
        visible.sort(Comparator.comparingDouble(c -> chunkDistanceSquared(c, centerBlockX, centerBlockZ)));

        // Reduce raster density only when the visible explored area is large.
        int stride = visible.size() > 512 ? 4 : visible.size() > 128 || zoom < 0.75 ? 2 : 1;
        int minExploredHeight = Integer.MAX_VALUE, maxExploredHeight = Integer.MIN_VALUE;
        for (var chunk : visible) for (int sample = 0; sample < 256; sample++) {
            int h = chunk.heightAt(sample);
            minExploredHeight = Math.min(minExploredHeight, h);
            maxExploredHeight = Math.max(maxExploredHeight, h);
        }
        if (minExploredHeight == Integer.MAX_VALUE) { minExploredHeight = 0; maxExploredHeight = 1; }
        List<TerrainCell> cells = new ArrayList<>(visible.size() * (256 / (stride * stride)));
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double originX = (left + right) * 0.5 + panX;
        double originY = (top + bottom) * 0.5 + panY + 26;

        for (NavigatorExplorationStore.ExploredChunk chunk : visible) {
            for (int z = 0; z < 16; z += stride) {
                for (int x = 0; x < 16; x += stride) {
                    int sample = z * 16 + x;
                    double worldX = (chunk.chunkX() << 4) + x + stride * 0.5;
                    double worldZ = (chunk.chunkZ() << 4) + z + stride * 0.5;
                    double dx = worldX - centerBlockX;
                    double dz = worldZ - centerBlockZ;
                    double rx = dx * cos - dz * sin;
                    double rz = dx * sin + dz * cos;
                    int h = chunk.heightAt(sample);
                    int color = chunk.colorAt(sample);
                    if (color == 0) color = chunk.mapColor() == 0 ? 0xFF557A68 : 0xFF000000 | chunk.mapColor();
                    color = applyMapStyle(color, chunk, sample, h, x, z, stride, minExploredHeight, maxExploredHeight);
                    double depth = rx + rz;
                    double vertical = (h - centerHeight) * relief;
                    double projectedX, projectedY, projectedDepth;
                    if (viewMode == ViewMode.TOP_DOWN) {
                        projectedX = originX + rx * tile;
                        projectedY = originY + rz * tile;
                        projectedDepth = vertical;
                    } else {
                        double pitchedDepth = rz * Math.sin(pitch) + vertical * Math.cos(pitch);
                        double pitchedY = rz * Math.cos(pitch) - vertical * Math.sin(pitch);
                        projectedX = originX + (rx - rz * Math.cos(pitch)) * tile * 0.72;
                        projectedY = originY + (rx * 0.34 + pitchedY * 0.34) * tile;
                        projectedDepth = depth + pitchedDepth;
                    }
                    int slopeShade = slopeShade(visible, chunk, x, z, stride);
                    cells.add(new TerrainCell(projectedX, projectedY, h, shade(color, slopeShade), projectedDepth));
                }
            }
        }

        cells.sort(Comparator.comparingDouble(TerrainCell::depth));
        int rendered = 0;
        for (TerrainCell cell : cells) {
            double dx = cell.x() - originX;
            double dy = cell.y() - originY;
            if (cell.x() < left - 16 || cell.x() > right + 16
                    || cell.y() < top - 16 || cell.y() > bottom + 16) continue;
            int heightShade = clamp((int) Math.round((cell.height() - centerHeight) * 0.12), -26, 30);
            int color = shade(cell.color(), heightShade);
            int halfW = Math.max(1, (int) Math.ceil(tile * 0.72 * stride));
            int halfH = Math.max(1, (int) Math.ceil(tile * 0.34 * stride));
            drawDiamond(g, (int) Math.round(cell.x()), (int) Math.round(cell.y()),
                    halfW, halfH, color);
            rendered++;
        }

        // Waypoints use the same world-to-isometric transform as the terrain.
        for (NavigatorWaypoint waypoint : NavigatorWaypointStore.all()) {
            if (!showWaypoints || !waypoint.visible || waypoint.frame != NavigatorWaypoint.CoordinateFrame.DIMENSION
                    || !dimension.equals(waypoint.dimensionId) || !matchesMarkerFilter(waypoint)) continue;
            double[] point = project(waypoint.x, waypoint.z, waypoint.y, centerBlockX, centerBlockZ,
                    centerHeight, cos, sin, pitch, tile, originX, originY, viewMode);
            int x = (int) Math.round(point[0]), y = (int) Math.round(point[1]);
            if (x < left || x > right || y < top || y > bottom) continue;
            int color = waypoint.color == 0 ? 0xFFFFC66D : waypoint.color;
            g.fill(x - 4, y - 4, x + 5, y + 5, 0xFF07121C);
            g.fill(x - 2, y - 2, x + 3, y + 3, color);
            if (showWaypointLabels) g.drawString(font, waypoint.icon + " " + waypoint.name, x + 7, y - 4, color, false);
        }

        renderTerritories(g, centerBlockX, centerBlockZ, centerHeight, cos, sin, tile, originX, originY);
        if (showWorldForge) renderWorldForge(g, centerBlockX, centerBlockZ, centerHeight, cos, sin, tile, originX, originY, left, top, right, bottom);
        if (drawingTerritory) renderDraftTerritory(g, centerBlockX, centerBlockZ, centerHeight, cos, sin, tile, originX, originY);
        if (showPlayer && playerHere && NavigatorPlayerMarkerPreferences.visible()) {
            double[] point = project(player.x(), player.z(), player.y(), centerBlockX, centerBlockZ,
                    centerHeight, cos, sin, pitch, tile, originX, originY, viewMode);
            int x = (int) Math.round(point[0]), y = (int) Math.round(point[1]);
            int color = NavigatorPlayerMarkerPreferences.color();
            int radius = Math.max(4, (int) Math.round(6 * NavigatorPlayerMarkerPreferences.scale()));
            g.fill(x - radius - 3, y - radius - 3, x + radius + 4, y + radius + 4, 0x9955DDEE);
            g.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
            if (NavigatorPlayerMarkerPreferences.showLabel())
                g.drawString(font, "YOU", x + radius + 7, y - radius, color, false);
        }

        g.fill(12, height - 34, width - 12, height - 10, PANEL);
        g.drawString(font, "EXPLORED CHUNKS " + all.size() + "  ·  RENDERED CELLS " + rendered
                + "  ·  ZOOM " + String.format(java.util.Locale.ROOT, "%.2f", zoom)
                + "  ·  RELIEF " + String.format(java.util.Locale.ROOT, "%.2f", relief),
                22, height - 25, 0xFFB6C7D8, false);
        g.drawString(font, "WF: " + com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.status(), 22, height - 13, 0xFF86C8B5, false);
        g.drawString(font, (viewMode == ViewMode.ISOMETRIC ? "LEFT-DRAG: ROTATE · RIGHT-DRAG: TILT/ROTATE · " : "DRAG: PAN · ") + "WHEEL: ZOOM · ONLY EXPLORED TERRAIN",
                Math.max(22, width - 430), height - 25, 0xFF65D8F2, false);
        super.render(g, mouseX, mouseY, partialTick);
        MaqanUiTheme.skinWidgets(this,g,font);
    }


    private void renderWorldForge(GuiGraphics g, double centerX, double centerZ, double centerY,
                                  double cos, double sin, double tile, double originX, double originY,
                                  int left, int top, int right, int bottom) {
        for (var territory : com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.territories(dimension)) {
            List<double[]> points = new ArrayList<>();
            for (var vertex : territory.vertices) {
                points.add(project(vertex.x, vertex.z, vertex.y, centerX, centerZ, centerY,
                        cos, sin, pitch, tile, originX, originY, viewMode));
            }
            int color = territory.color == 0 ? 0xFF58B9D7 : territory.color;
            int alpha = Math.max(0, Math.min(160, territory.fill_alpha));
            fillPolygon(g, points, (alpha << 24) | (color & 0x00FFFFFF));
            drawPolygonOutline(g, points, color);
            if (!points.isEmpty()) {
                double[] label = points.get(0);
                if (label[0] >= left && label[0] <= right && label[1] >= top && label[1] <= bottom)
                    g.drawString(font, territory.name + (territory.realm_name == null ? "" : " · " + territory.realm_name),
                            (int) label[0] + 4, (int) label[1] - 10, color, false);
            }
        }
        for (var feature : com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.features(dimension)) {
            double[] point = project(feature.x, feature.z, feature.y, centerX, centerZ, centerY,
                    cos, sin, pitch, tile, originX, originY, viewMode);
            int x = (int) Math.round(point[0]), y = (int) Math.round(point[1]);
            if (x < left || x > right || y < top || y > bottom) continue;
            int color = feature.color == 0 ? worldForgeFeatureColor(feature.kind) : feature.color;
            g.fill(x - 5, y - 5, x + 6, y + 6, 0xE608111C);
            g.fill(x - 3, y - 3, x + 4, y + 4, color);
            g.drawString(font, feature.name, x + 7, y - 4, color, false);
        }
    }

    private static int worldForgeFeatureColor(String kind) {
        if (kind == null) return 0xFFB8C9D8;
        return switch (kind.toLowerCase(java.util.Locale.ROOT)) {
            case "capital" -> 0xFFFFD36B;
            case "city", "settlement" -> 0xFF72C8F0;
            case "village" -> 0xFF8ED49B;
            case "fortress", "castle", "fort" -> 0xFFDE8B72;
            case "port", "market", "trade" -> 0xFFE3B7F0;
            case "army", "front", "siege" -> 0xFFFF7777;
            default -> 0xFFB8C9D8;
        };
    }

    private void renderTerritories(GuiGraphics g, double centerX, double centerZ, double centerY,
                                   double cos, double sin, double tile, double originX, double originY) {
        for (NavigatorTerritory territory : NavigatorTerritoryStore.all()) {
            if (!territory.visible || territory.frame != NavigatorTerritory.CoordinateFrame.DIMENSION
                    || !dimension.equals(territory.dimensionId) || territory.vertices.size() < 3) continue;
            List<double[]> points = new ArrayList<>();
            for (var vertex : territory.vertices) {
                points.add(project(vertex.x, vertex.z, vertex.y, centerX, centerZ, centerY,
                        cos, sin, pitch, tile, originX, originY, viewMode));
            }
            int color = territory.color == 0 ? 0xFF58B9D7 : territory.color;
            fillPolygon(g, points, (territory.fillAlpha << 24) | (color & 0x00FFFFFF));
            drawPolygonOutline(g, points, color);
            double[] label = points.get(0);
            g.drawString(font, territory.name + " · " + territory.owner,
                    (int) label[0] + 4, (int) label[1] - 10, color, false);
        }
    }

    private void renderDraftTerritory(GuiGraphics g, double centerX, double centerZ, double centerY,
                                      double cos, double sin, double tile, double originX, double originY) {
        List<double[]> points = new ArrayList<>();
        for (var vertex : draftTerritory) points.add(project(vertex.x, vertex.z, vertex.y,
                centerX, centerZ, centerY, cos, sin, pitch, tile, originX, originY, viewMode));
        drawPolygonOutline(g, points, 0xFFFFD36B);
        for (double[] p : points) g.fill((int)p[0]-3,(int)p[1]-3,(int)p[0]+4,(int)p[1]+4,0xFFFFD36B);
        g.drawString(font, "TERRITORY DRAFT: " + draftTerritory.size()
                + " POINTS · ENTER SAVE · BACKSPACE UNDO · ESC CANCEL",
                424, 74, 0xFFFFD36B, false);
    }

    private static void fillPolygon(GuiGraphics g, List<double[]> points, int color) {
        if (points.size() < 3 || (color >>> 24) == 0) return;
        double minY=Double.POSITIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        for (double[] p:points) { minY=Math.min(minY,p[1]);maxY=Math.max(maxY,p[1]); }
        int top=Math.max(98,(int)Math.floor(minY)),bottom=Math.min(g.guiHeight()-42,(int)Math.ceil(maxY));
        for(int y=top;y<=bottom;y++) {
            List<Double> intersections=new ArrayList<>();
            for(int i=0,j=points.size()-1;i<points.size();j=i++) {
                double[] a=points.get(j),b=points.get(i);
                if((a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y))
                    intersections.add(a[0]+(y-a[1])*(b[0]-a[0])/(b[1]-a[1]));
            }
            intersections.sort(Double::compare);
            for(int i=0;i+1<intersections.size();i+=2) {
                int x1=(int)Math.ceil(intersections.get(i)),x2=(int)Math.floor(intersections.get(i+1));
                if(x2>=x1)g.fill(x1,y,x2+1,y+1,color);
            }
        }
    }

    private static void drawPolygonOutline(GuiGraphics g,List<double[]> points,int color) {
        if(points.size()<2)return;
        for(int i=0;i<points.size();i++) {
            double[] a=points.get(i),b=points.get((i+1)%points.size());
            int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]))));
            for(int step=0;step<=steps;step++) {
                double t=step/(double)steps;
                int x=(int)Math.round(a[0]+(b[0]-a[0])*t),y=(int)Math.round(a[1]+(b[1]-a[1])*t);
                g.fill(x-1,y-1,x+2,y+2,color);
            }
        }
    }

    private void saveDraftTerritory() {
        if(draftTerritory.size()<3)return;
        String dim=dimension;
        String territoryName=territoryNameField==null?"New Territory":territoryNameField.getValue();
        String territoryOwner=territoryOwnerField==null?"Unassigned":territoryOwnerField.getValue();
        NavigatorTerritory territory=new NavigatorTerritory(territoryName,territoryOwner,dim,"",
                NavigatorTerritory.CoordinateFrame.DIMENSION,territoryKind,
                "Political",0xFF58B9D7,42,draftTerritory);
        NavigatorTerritoryStore.add(territory);
        draftTerritory.clear();
        drawingTerritory=false;
    }

    private boolean matchesMarkerFilter(NavigatorWaypoint waypoint) {
        if (markerFilter == MarkerFilter.ALL) return true;
        return waypoint.category != null && markerFilter.name().equals(waypoint.category.name());
    }

    private static double[] project(double worldX, double worldZ, double worldY,
                                    double centerX, double centerZ, double centerY,
                                    double cos, double sin, double pitch, double tile,
                                    double originX, double originY, ViewMode mode) {
        double dx = worldX - centerX, dz = worldZ - centerZ;
        double rx = dx * cos - dz * sin, rz = dx * sin + dz * cos;
        double vertical = (worldY - centerY) * 0.28;
        if (mode == ViewMode.TOP_DOWN)
            return new double[]{originX + rx * tile, originY + rz * tile};
        double pitchedY = rz * Math.cos(pitch) - vertical * Math.sin(pitch);
        return new double[]{originX + (rx - rz * Math.cos(pitch)) * tile * 0.72,
                originY + (rx * 0.34 + pitchedY * 0.34) * tile};
    }

    private static double chunkDistanceSquared(NavigatorExplorationStore.ExploredChunk chunk,
                                               double centerX, double centerZ) {
        double dx = (chunk.chunkX() << 4) + 8 - centerX;
        double dz = (chunk.chunkZ() << 4) + 8 - centerZ;
        return dx * dx + dz * dz;
    }

    private static double averageChunkCenter(List<NavigatorExplorationStore.ExploredChunk> chunks, boolean x) {
        if (chunks.isEmpty()) return 0;
        double sum = 0;
        for (var chunk : chunks) sum += ((x ? chunk.chunkX() : chunk.chunkZ()) << 4) + 8;
        return sum / chunks.size();
    }

    private static double averageSurfaceHeight(List<NavigatorExplorationStore.ExploredChunk> chunks) {
        if (chunks.isEmpty()) return 64;
        long sum = 0;
        for (var chunk : chunks) sum += chunk.surfaceY();
        return (double) sum / chunks.size();
    }

    private int applyMapStyle(int natural, NavigatorExplorationStore.ExploredChunk chunk,
                                  int sample, int height, int x, int z, int stride,
                                  int minHeight, int maxHeight) {
        return switch (mapStyle) {
            case NATURAL -> natural;
            case BIOME -> biomePalette(chunk.biome(), chunk.mapColor());
            case ELEVATION -> elevationColor(height, minHeight, maxHeight);
            case SLOPE -> slopePalette(chunk, x, z, stride);
            case EXPLORATION_AGE -> agePalette(chunk.lastVisited());
        };
    }

    private static int biomePalette(String biome, int fallback) {
        String id = biome == null ? "" : biome.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("ocean") || id.contains("river") || id.contains("beach")) return 0xFF347FA8;
        if (id.contains("desert") || id.contains("badlands") || id.contains("savanna")) return 0xFFC49A52;
        if (id.contains("snow") || id.contains("ice") || id.contains("frozen")) return 0xFFD5E8F0;
        if (id.contains("mountain") || id.contains("peak") || id.contains("slope")) return 0xFF9A9FA8;
        if (id.contains("swamp") || id.contains("mangrove")) return 0xFF587C62;
        if (id.contains("jungle") || id.contains("forest") || id.contains("grove")) return 0xFF397B50;
        if (id.contains("mushroom")) return 0xFF9B668C;
        if (id.contains("nether")) return 0xFF9A493D;
        if (id.contains("end")) return 0xFF8A79A8;
        return fallback == 0 ? 0xFF788B91 : 0xFF000000 | fallback;
    }

    private static int elevationColor(int y, int minHeight, int maxHeight) {
        double range = Math.max(1.0, maxHeight - (double) minHeight);
        double t = Math.max(0.0, Math.min(1.0, (y - minHeight) / range));
        if (t < 0.16) return 0xFF315C8C;
        if (t < 0.30) return 0xFF3D8E9B;
        if (t < 0.48) return 0xFF57945A;
        if (t < 0.66) return 0xFF9AA34E;
        if (t < 0.82) return 0xFFB58B52;
        if (t < 0.94) return 0xFF8A7770;
        return 0xFFE0E4E8;
    }

    private static int slopePalette(NavigatorExplorationStore.ExploredChunk chunk, int x, int z, int stride) {
        int lx = Math.max(0, x - stride), rx = Math.min(15, x + stride);
        int nz = Math.max(0, z - stride), sz = Math.min(15, z + stride);
        int dx = Math.abs(chunk.heightAt(z * 16 + rx) - chunk.heightAt(z * 16 + lx));
        int dz = Math.abs(chunk.heightAt(sz * 16 + x) - chunk.heightAt(nz * 16 + x));
        int slope = Math.min(100, (dx + dz) * 3);
        if (slope < 12) return 0xFF4D9661;
        if (slope < 30) return 0xFFD0B65A;
        if (slope < 55) return 0xFFD98745;
        return 0xFFB84E48;
    }

    private static int agePalette(long lastVisited) {
        long age = Math.max(0L, System.currentTimeMillis() - lastVisited);
        long day = 86_400_000L;
        if (age < day) return 0xFF54D69A;
        if (age < day * 7) return 0xFF6CB8E8;
        if (age < day * 30) return 0xFFE0B45C;
        return 0xFF9A789A;
    }

    private static int slopeShade(List<NavigatorExplorationStore.ExploredChunk> chunks,
                                  NavigatorExplorationStore.ExploredChunk current,
                                  int x, int z, int stride) {
        int left = sampledHeight(chunks, (current.chunkX() << 4) + x - stride, (current.chunkZ() << 4) + z);
        int right = sampledHeight(chunks, (current.chunkX() << 4) + x + stride, (current.chunkZ() << 4) + z);
        int north = sampledHeight(chunks, (current.chunkX() << 4) + x, (current.chunkZ() << 4) + z - stride);
        int south = sampledHeight(chunks, (current.chunkX() << 4) + x, (current.chunkZ() << 4) + z + stride);
        if (left == Integer.MIN_VALUE || right == Integer.MIN_VALUE
                || north == Integer.MIN_VALUE || south == Integer.MIN_VALUE) return 0;
        return clamp((int) Math.round(((south - north) - (right - left)) * 0.35), -22, 22);
    }

    private static int sampledHeight(List<NavigatorExplorationStore.ExploredChunk> chunks,
                                     int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16), chunkZ = Math.floorDiv(blockZ, 16);
        int localX = Math.floorMod(blockX, 16), localZ = Math.floorMod(blockZ, 16);
        for (var chunk : chunks) {
            if (chunk.chunkX() == chunkX && chunk.chunkZ() == chunkZ)
                return chunk.heightAt(localZ * 16 + localX);
        }
        return Integer.MIN_VALUE;
    }

    private static void drawDiamond(GuiGraphics g, int cx, int cy, int halfW, int halfH, int color) {
        int h = Math.max(1, halfH);
        g.fill(cx, cy - h, cx + 1, cy - h + 1, shade(color, 16));
        if (h >= 2) g.fill(cx - halfW / 2, cy - h / 2, cx + halfW / 2 + 1, cy - h / 2 + 1, shade(color, 8));
        g.fill(cx - halfW, cy, cx + halfW + 1, cy + 1, color);
        if (h >= 2) g.fill(cx - halfW / 2, cy + h / 2, cx + halfW / 2 + 1, cy + h / 2 + 1, shade(color, -8));
        g.fill(cx, cy + h - 1, cx + 1, cy + h, shade(color, -14));
    }

    private static int shade(int argb, int amount) {
        int r = clamp(((argb >> 16) & 255) + amount, 0, 255);
        int green = clamp(((argb >> 8) & 255) + amount, 0, 255);
        int b = clamp((argb & 255) + amount, 0, 255);
        return (argb & 0xFF000000) | (r << 16) | (green << 8) | b;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (drawingTerritory && button == 0 && mouseY >= 98 && mouseY <= height - 42) {
            addDraftVertex(mouseX, mouseY);
            return true;
        }
        if ((button == 0 || button == 1) && mouseY >= 98 && mouseY <= height - 42) {
            dragging = true; dragButton = button; lastMouseX = mouseX; lastMouseY = mouseY; return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (dragging && button == dragButton) { dragging = false; dragButton = -1; return true; }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == dragButton) {
            if (viewMode == ViewMode.TOP_DOWN) {
                panX += dragX;
                panY += dragY;
            } else if (dragButton == 1) {
                pitch = Math.max(Math.toRadians(8), Math.min(Math.toRadians(78), pitch + dragY * 0.006));
                yaw += dragX * 0.006;
            } else {
                yaw += dragX * 0.009;
            }
            lastMouseX = mouseX; lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        zoom = Math.max(0.35, Math.min(2.6, zoom * (delta > 0 ? 1.12 : 1.0 / 1.12)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (drawingTerritory) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                saveDraftTerritory(); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!draftTerritory.isEmpty()) draftTerritory.remove(draftTerritory.size()-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                drawingTerritory=false; draftTerritory.clear(); return true;
            }
        }
        return super.keyPressed(keyCode,scanCode,modifiers);
    }

    private void addDraftVertex(double mouseX,double mouseY) {
        NavigatorPlayerPosition player=NavigatorPlayerPositionTracker.current();
        double centerX=player!=null&&dimension.equals(player.dimensionId())?player.x():averageChunkCenter(
                NavigatorExplorationStore.chunks(dimension),true);
        double centerZ=player!=null&&dimension.equals(player.dimensionId())?player.z():averageChunkCenter(
                NavigatorExplorationStore.chunks(dimension),false);
        double centerY=player!=null&&dimension.equals(player.dimensionId())?player.y():
                averageSurfaceHeight(NavigatorExplorationStore.chunks(dimension));
        double tile=0.92*zoom, originX=width*0.5+panX,originY=(98+height-42)*0.5+panY+26;
        double sx=(mouseX-originX)/Math.max(0.001,tile),sy=(mouseY-originY)/Math.max(0.001,tile);
        double rx,rz;
        if(viewMode==ViewMode.TOP_DOWN) { rx=sx;rz=sy; }
        else {
            double a=sx/0.72,b=sy/0.34;
            rz=(a-b*Math.cos(pitch))/(1.0+Math.sin(pitch)*Math.cos(pitch));
            rx=a+rz*Math.cos(pitch);
        }
        double dx=rx*Math.cos(yaw)+rz*Math.sin(yaw);
        double dz=-rx*Math.sin(yaw)+rz*Math.cos(yaw);
        draftTerritory.add(new NavigatorTerritory.Vertex(centerX+dx,centerY,centerZ+dz));
    }

    @Override
    public void onClose() {
        if (minecraft != null) MaqanScreenHost.back(minecraft, parent);
        else super.onClose();
    }
}
