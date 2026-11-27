package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.NavigatorExplorationStore;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPosition;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPositionTracker;
import com.maqan.galacticnavigator.navigation.NavigatorWaypoint;
import com.maqan.galacticnavigator.navigation.NavigatorWaypointStore;
import com.maqan.galacticnavigator.navigation.NavigatorTerritory;
import com.maqan.galacticnavigator.navigation.NavigatorTerritoryStore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
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

    private int mapTop = 118;

    private int[] toolbarCell(int index, int columns, int buttonWidth) {
        int gap = 4;
        int row = index / columns;
        int column = index % columns;
        return new int[]{18 + column * (buttonWidth + gap), 44 + row * 22, buttonWidth, 20};
    }

    @Override
    protected void init() {
        com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.reload();
        com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.ensureLiveFeed();

        // One responsive toolbar grid replaces the old fixed pixel coordinates.
        // At narrow widths controls wrap instead of overlapping or leaving the screen.
        int columns = width >= 1180 ? 8 : width >= 900 ? 6 : width >= 620 ? 4 : width >= 360 ? 3 : 2;
        int gap = 4;
        int buttonWidth = Math.max(1, (width - 36 - (columns - 1) * gap) / columns);
        int index = 0;

        int[] b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("ROT −"), w -> yaw -= Math.PI / 12.0)
                .bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("ROT +"), w -> yaw += Math.PI / 12.0)
                .bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("TILT −"), w -> pitch = Math.max(Math.toRadians(8),
                Math.min(Math.toRadians(78), pitch - Math.toRadians(5))))
                .bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("TILT +"), w -> pitch = Math.max(Math.toRadians(8),
                Math.min(Math.toRadians(78), pitch + Math.toRadians(5))))
                .bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("RELIEF −"), w -> relief = Math.max(0.08, relief - 0.05))
                .bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("RELIEF +"), w -> relief = Math.min(0.8, relief + 0.05))
                .bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("RESET"), w -> {
            yaw = Math.PI * 0.25; pitch = Math.toRadians(32); zoom = 1.0; relief = 0.28; panX = 0; panY = 0;
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        styleButton = addRenderableWidget(Button.builder(Component.literal("STYLE"), w -> {
            mapStyle = MapStyle.values()[(mapStyle.ordinal() + 1) % MapStyle.values().length];
            w.setMessage(Component.literal("STYLE: " + mapStyle.name().replace('_', ' ')));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        viewButton = addRenderableWidget(Button.builder(Component.literal("VIEW: 3D"), w -> {
            viewMode = viewMode == ViewMode.ISOMETRIC ? ViewMode.TOP_DOWN : ViewMode.ISOMETRIC;
            w.setMessage(Component.literal(viewMode == ViewMode.ISOMETRIC ? "VIEW: 3D" : "VIEW: TOP"));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        markerFilterButton = addRenderableWidget(Button.builder(Component.literal("MARKERS"), w -> {
            markerFilter = MarkerFilter.values()[(markerFilter.ordinal() + 1) % MarkerFilter.values().length];
            w.setMessage(Component.literal("MARKERS: " + markerFilter.name()));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        playerLayerButton = addRenderableWidget(Button.builder(Component.literal("PLAYER: ON"), w -> {
            showPlayer = !showPlayer;
            w.setMessage(Component.literal("PLAYER: " + (showPlayer ? "ON" : "OFF")));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        gridLayerButton = addRenderableWidget(Button.builder(Component.literal("GRID: ON"), w -> {
            showGrid = !showGrid;
            w.setMessage(Component.literal("GRID: " + (showGrid ? "ON" : "OFF")));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("LABELS: ON"), w -> {
            showWaypointLabels = !showWaypointLabels;
            w.setMessage(Component.literal("LABELS: " + (showWaypointLabels ? "ON" : "OFF")));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("TERRITORY"), w -> {
            drawingTerritory = !drawingTerritory;
            draftTerritory.clear();
            w.setMessage(Component.literal(drawingTerritory ? "DRAW: ON" : "TERRITORY"));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        territoryNameField = new EditBox(font, b[0], b[1], b[2], b[3], Component.literal("Territory"));
        territoryNameField.setMaxLength(48);
        territoryNameField.setValue("New Territory");
        addRenderableWidget(territoryNameField);
        b = toolbarCell(index++, columns, buttonWidth);
        territoryOwnerField = new EditBox(font, b[0], b[1], b[2], b[3], Component.literal("Owner"));
        territoryOwnerField.setMaxLength(48);
        territoryOwnerField.setValue("Unassigned");
        addRenderableWidget(territoryOwnerField);
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("KIND: CLAIM"), w -> {
            territoryKind = NavigatorTerritory.Kind.values()[
                    (territoryKind.ordinal() + 1) % NavigatorTerritory.Kind.values().length];
            w.setMessage(Component.literal("KIND: " + territoryKind.name()));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("WORLDFORGE: ON"), w -> {
            showWorldForge = !showWorldForge;
            w.setMessage(Component.literal("WORLDFORGE: " + (showWorldForge ? "ON" : "OFF")));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("RELOAD DATA"), w -> {
            boolean loaded = com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.reload();
            w.setMessage(Component.literal(loaded ? "DATA: LOADED" : "NO DATA"));
        }).bounds(b[0], b[1], b[2], b[3]).build());
        b = toolbarCell(index++, columns, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("BACK"), w -> onClose())
                .bounds(b[0], b[1], b[2], b[3]).build());

        int rows = (index + columns - 1) / columns;
        mapTop = 44 + rows * 22 + 10;
        updateDepthButtons();
    }

    @Override
    public void tick() {
        super.tick();
        com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.ensureLiveFeed();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MaqanUiTheme.backdrop(g,width,height);
        MaqanUiTheme.panel(g,12,10,width-12,36);
        MaqanUiTheme.titleRight(g,font,"MAQAN  /  PLANETARY TERRAIN  /  " + (viewMode == ViewMode.ISOMETRIC ? "3D" : "TOP-DOWN"),dimension,width);

        int left = 16, top = mapTop, right = width - 16, bottom = Math.max(mapTop, height - 42);
        g.fill(left, top, right, bottom, 0xC908111C);
        if (showGrid) {
            for (int x = left; x < right; x += 40) g.fill(x, top, x + 1, bottom, 0x183A6680);
            for (int y = top; y < bottom; y += 40) g.fill(left, y, right, y + 1, 0x183A6680);
        }

        List<NavigatorExplorationStore.ExploredChunk> all = NavigatorExplorationStore.chunks(dimension);
        var worldSnapshot = com.maqan.galacticnavigator.navigation.NavigatorWorldForgeMapStore.current();
        var worldTerrain = worldSnapshot == null ? null : worldSnapshot.terrainInDimension(dimension);
        NavigatorPlayerPosition player = NavigatorPlayerPositionTracker.current();
        boolean playerHere = player != null && dimension.equals(player.dimensionId());
        double terrainCenterX = worldTerrain == null ? 0
                : worldTerrain.origin_x + worldTerrain.width * worldTerrain.step_pixels * worldTerrain.blocks_per_pixel * 0.5;
        double terrainCenterZ = worldTerrain == null ? 0
                : worldTerrain.origin_z + worldTerrain.height * worldTerrain.step_pixels * worldTerrain.blocks_per_pixel * 0.5;
        double centerBlockX = playerHere ? player.x()
                : !all.isEmpty() ? averageChunkCenter(all, true) : terrainCenterX;
        double centerBlockZ = playerHere ? player.z()
                : !all.isEmpty() ? averageChunkCenter(all, false) : terrainCenterZ;
        double centerHeight = playerHere ? player.y()
                : !all.isEmpty() ? averageSurfaceHeight(all) : averageSnapshotHeight(worldTerrain);

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
        if (visible.isEmpty() && worldTerrain != null) {
            rendered += renderSnapshotTerrainRaster(g, worldTerrain, centerBlockX, centerBlockZ,
                    centerHeight, cos, sin, tile, originX, originY, left, top, right, bottom);
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
        g.drawString(font, (viewMode == ViewMode.ISOMETRIC ? "LEFT-DRAG: ROTATE · RIGHT-DRAG: TILT/ROTATE · " : "DRAG: PAN · ") + "WHEEL: ZOOM · WORLD MAP + EXPLORED DETAIL",
                Math.max(22, width - 430), height - 25, 0xFF65D8F2, false);
        super.render(g, mouseX, mouseY, partialTick);
        MaqanUiTheme.skinWidgets(this,g,font);
    }


    private static double averageSnapshotHeight(
            com.maqan.galacticnavigator.navigation.WorldForgeMapSnapshot.Terrain terrain) {
        if (terrain == null || terrain.heights == null || terrain.heights.length == 0) return 64;
        long sum = 0;
        for (int height : terrain.heights) sum += height;
        return sum / (double) terrain.heights.length;
    }

    /** Draws the bounded authoritative WorldForge terrain raster when no explored chunks are visible. */
    private int renderSnapshotTerrainRaster(GuiGraphics g,
            com.maqan.galacticnavigator.navigation.WorldForgeMapSnapshot.Terrain terrain,
            double centerX, double centerZ, double centerY, double cos, double sin,
            double tile, double originX, double originY, int left, int top, int right, int bottom) {
        if (terrain == null || terrain.sampleCount() <= 0) return 0;
        double sampleBlocks = terrain.step_pixels * terrain.blocks_per_pixel;
        if (!Double.isFinite(sampleBlocks) || sampleBlocks <= 0) return 0;
        int stride = terrain.sampleCount() > 4096 ? (zoom < 0.75 ? 8 : zoom < 1.5 ? 4 : 2) : 1;
        double cellBlocks = sampleBlocks * stride;
        double visibleRadius = Math.max(width, height) / Math.max(0.12, tile * 0.5) * 0.8;
        List<TerrainCell> cells = new ArrayList<>(Math.max(64, terrain.sampleCount() / (stride * stride)));
        for (int z = 0; z < terrain.height; z += stride) for (int x = 0; x < terrain.width; x += stride) {
            int index = z * terrain.width + x;
            double worldX = terrain.sampleX(index), worldZ = terrain.sampleZ(index);
            double dx = worldX - centerX, dz = worldZ - centerZ;
            if (dx * dx + dz * dz > visibleRadius * visibleRadius) continue;
            double rx = dx * cos - dz * sin, rz = dx * sin + dz * cos;
            int height = terrain.heights[index];
            int biome = terrain.biome_indices[index];
            int color = biome >= 0 && terrain.biome_colors != null && biome < terrain.biome_colors.length
                    ? terrain.biome_colors[biome] : 0xFF557A68;
            color = 0xFF000000 | (color & 0x00FFFFFF);
            if (terrain.river_values != null && index < terrain.river_values.length
                    && terrain.river_values[index] > 0) color = 0xFF347FA8;
            double vertical = (height - centerY) * relief;
            double projectedX, projectedY, depth;
            if (viewMode == ViewMode.TOP_DOWN) {
                projectedX = originX + rx * tile;
                projectedY = originY + rz * tile;
                depth = vertical;
            } else {
                double pitchedDepth = rz * Math.sin(pitch) + vertical * Math.cos(pitch);
                double pitchedY = rz * Math.cos(pitch) - vertical * Math.sin(pitch);
                projectedX = originX + (rx - rz * Math.cos(pitch)) * tile * 0.72;
                projectedY = originY + (rx * 0.34 + pitchedY * 0.34) * tile;
                depth = rx + rz + pitchedDepth;
            }
            cells.add(new TerrainCell(projectedX, projectedY, height, color, depth));
        }
        cells.sort(Comparator.comparingDouble(TerrainCell::depth));
        int halfW = Math.max(1, (int) Math.ceil(cellBlocks * tile
                * (viewMode == ViewMode.TOP_DOWN ? 0.5 : 0.72)));
        int halfH = Math.max(1, (int) Math.ceil(cellBlocks * tile
                * (viewMode == ViewMode.TOP_DOWN ? 0.5 : 0.34)));
        int rendered = 0;
        for (TerrainCell cell : cells) {
            if (cell.x() < left - halfW || cell.x() > right + halfW
                    || cell.y() < top - halfH || cell.y() > bottom + halfH) continue;
            int heightShade = clamp((int) Math.round((cell.height() - centerY) * 0.12), -26, 30);
            drawDiamond(g, (int) Math.round(cell.x()), (int) Math.round(cell.y()),
                    halfW, halfH, shade(cell.color(), heightShade));
            rendered++;
        }
        return rendered;
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
            if ("administrative_title".equals(feature.kind) && zoom < 1.15) continue;
            double[] point = project(feature.x, feature.z, feature.y, centerX, centerZ, centerY,
                    cos, sin, pitch, tile, originX, originY, viewMode);
            int x = (int) Math.round(point[0]), y = (int) Math.round(point[1]);
            if (x < left || x > right || y < top || y > bottom) continue;
            int color = feature.color == 0 ? worldForgeFeatureColor(feature.kind) : feature.color;
            ResourceLocation itemId = worldForgeFeatureItem(feature.kind, feature.subtype, feature.attributes);
            boolean renderedItem = false;
            if (itemId != null && zoom >= 0.9) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
                if (!stack.isEmpty()) {
                    int iconSize = Math.max(10, Math.min(26, (int) Math.round(15 * Math.sqrt(zoom))));
                    g.fill(x - iconSize / 2 - 2, y - iconSize / 2 - 2,
                            x + iconSize / 2 + 2, y + iconSize / 2 + 2, 0xC908111C);
                    g.pose().pushPose();
                    g.pose().translate(x - iconSize / 2.0F, y - iconSize / 2.0F, 0.0F);
                    float scale = iconSize / 16.0F;
                    g.pose().scale(scale, scale, 1.0F);
                    g.renderItem(stack, 0, 0);
                    g.pose().popPose();
                    renderedItem = true;
                }
            }
            if (!renderedItem) {
                g.fill(x - 5, y - 5, x + 6, y + 6, 0xE608111C);
                g.fill(x - 3, y - 3, x + 4, y + 4, color);
            }
            if (zoom > 0.85 || "region_capital".equals(feature.kind))
                g.drawString(font, feature.name, x + 9, y - 4, color, false);
        }
    }

    private static ResourceLocation worldForgeFeatureItem(String kind, String subtype,
                                                           java.util.Map<String, String> attributes) {
        String item = attributes == null ? null : attributes.get("map_item");
        if (item != null && !item.isBlank()) {
            ResourceLocation custom = ResourceLocation.tryParse(item);
            if (custom != null) return custom;
            item = null;
        }
        String profile = attributes == null ? "" : attributes.getOrDefault("visual_profile", "");
        if (profile.startsWith("worldforge:")) subtype = profile.substring("worldforge:".length());
        if (item == null && "marker".equals(kind)) {
            item = switch (subtype == null ? "" : subtype.toLowerCase(java.util.Locale.ROOT)) {
                case "capital" -> "minecraft:gold_block";
                case "city" -> "minecraft:bricks";
                case "settlement", "village" -> "minecraft:oak_planks";
                case "castle" -> "minecraft:stone_bricks";
                case "port" -> "minecraft:barrel";
                case "mine" -> "minecraft:iron_pickaxe";
                case "temple" -> "minecraft:chiseled_stone_bricks";
                case "ruin" -> "minecraft:mossy_stone_bricks";
                case "camp" -> "minecraft:campfire";
                case "caravan" -> "minecraft:chest";
                case "army" -> "minecraft:shield";
                default -> "minecraft:compass";
            };
        } else if (item == null && "region_capital".equals(kind)) {
            item = "minecraft:compass";
        } else if (item == null && "administrative_title".equals(kind)) {
            item = "minecraft:gold_block";
        }
        return item == null ? null : ResourceLocation.tryParse(item);
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
        g.drawString(font, "DRAFT " + draftTerritory.size()
                + " POINTS · ENTER SAVE · BACKSPACE UNDO · ESC CANCEL",
                24, Math.min(height - 54, mapTop + 12), 0xFFFFD36B, false);
    }

    private void fillPolygon(GuiGraphics g, List<double[]> points, int color) {
        if (points.size() < 3 || (color >>> 24) == 0) return;
        double minY=Double.POSITIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        for (double[] p:points) { minY=Math.min(minY,p[1]);maxY=Math.max(maxY,p[1]); }
        int top=Math.max(mapTop,(int)Math.floor(minY)),bottom=Math.min(height-42,(int)Math.ceil(maxY));
        for(int y=top;y<=bottom;y++) {
            List<Double> intersections=new ArrayList<>();
            for(int i=0,j=points.size()-1;i<points.size();j=i++) {
                double[] a=points.get(j),b=points.get(i);
                if((a[1]<=y&&b[1]>y)||(b[1]<=y&&a[1]>y))
                    intersections.add(a[0]+(y-a[1])*(b[0]-a[0])/(b[1]-a[1]));
            }
            intersections.sort(Double::compare);
            for(int i=0;i+1<intersections.size();i+=2) {
                int x1=Math.max(16,(int)Math.ceil(intersections.get(i)));
                int x2=Math.min(width-16,(int)Math.floor(intersections.get(i+1)));
                if(x2>=x1)g.fill(x1,y,x2+1,y+1,color);
            }
        }
    }

    private void drawPolygonOutline(GuiGraphics g,List<double[]> points,int color) {
        if(points.size()<2)return;
        for(int i=0;i<points.size();i++) {
            double[] a=points.get(i),b=points.get((i+1)%points.size());
            int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]))));
            for(int step=0;step<=steps;step++) {
                double t=step/(double)steps;
                int x=(int)Math.round(a[0]+(b[0]-a[0])*t),y=(int)Math.round(a[1]+(b[1]-a[1])*t);
                if (x >= 16 && x < width - 16 && y >= mapTop && y < height - 42) g.fill(x-1,y-1,x+2,y+2,color);
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
        if (drawingTerritory && button == 0 && mouseY >= mapTop && mouseY <= height - 42) {
            addDraftVertex(mouseX, mouseY);
            return true;
        }
        if ((button == 0 || button == 1) && mouseX >= 16 && mouseX < width - 16
                && mouseY >= mapTop && mouseY < height - 42) {
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
        // Toolbar and footer must never steal map zoom input.
        if (super.mouseScrolled(mouseX, mouseY, delta)) return true;
        if (mouseX < 16 || mouseX >= width - 16 || mouseY < mapTop || mouseY >= height - 42) return false;
        double factor = Math.pow(1.12, delta);
        zoom = Math.max(0.35, Math.min(2.6, zoom * factor));
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
        double tile=0.92*zoom, originX=width*0.5+panX,originY=(mapTop+height-42)*0.5+panY+26;
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
