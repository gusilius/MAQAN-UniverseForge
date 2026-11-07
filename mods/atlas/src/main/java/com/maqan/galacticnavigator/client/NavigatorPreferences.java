package com.maqan.galacticnavigator.client;

import java.util.prefs.Preferences;

/**
 * Persistent client-side controls for orbital presentation, atlas visibility layers,
 * and user-authored hierarchy overrides. Atlas layers affect rendering only; they
 * never mutate the active Cosmic Horizons datapack catalogue.
 */
public final class NavigatorPreferences {
    private static final Preferences STORE = Preferences.userNodeForPackage(NavigatorPreferences.class);
    private static String speedMode = STORE.get("speed_mode", "auto");
    private static double rotationSpeed = STORE.getDouble("rotation_speed", 0.12);
    private static double orbitSpeed = STORE.getDouble("orbit_speed", 0.12);
    private static final WorkspaceState WORKSPACE = WorkspaceState.load(STORE);
    private static boolean orbitsVisible = STORE.getBoolean("orbits_visible", true);
    private static int orbitThickness = STORE.getInt("orbit_thickness", 1);
    private static String orbitMode = STORE.get("orbit_mode", "all");
    private static boolean atlasOrbits = STORE.getBoolean("atlas_orbits", true);
    private static boolean atlasLabels = STORE.getBoolean("atlas_labels", true);
    private static boolean atlasSectors = STORE.getBoolean("atlas_sectors", true);
    private static boolean atlasGuides = STORE.getBoolean("atlas_guides", false);
    private static boolean atlasSectorLinks = STORE.getBoolean("atlas_sector_links", false);
    private static boolean atlasPacks = STORE.getBoolean("atlas_packs", false);
    private static boolean atlasRoutes = STORE.getBoolean("atlas_routes", false);
    private static boolean atlasTextures = STORE.getBoolean("atlas_textures", true);
    private static boolean atlasStarGlow = STORE.getBoolean("atlas_star_glow", true);
    private static int atlasDetail = Math.max(0, Math.min(2, STORE.getInt("atlas_detail", 1)));
    private static int atlasScaleStep = Math.max(0, Math.min(3, STORE.getInt("atlas_scale_step", 1)));
    private static int atlasOrbitSpacingStep = Math.max(0, Math.min(4, STORE.getInt("atlas_orbit_spacing_step", 2)));

    private NavigatorPreferences() {}
    public static synchronized String speedMode() { return speedMode; }
    public static synchronized boolean paused() { return "paused".equals(speedMode); }
    public static synchronized boolean automatic() { return "auto".equals(speedMode); }
    public static synchronized double rotationMultiplier() { return automatic() ? 0.12 : paused() ? 0.0 : rotationSpeed; }
    public static synchronized double orbitMultiplier() { return automatic() ? 0.12 : paused() ? 0.0 : orbitSpeed; }
    public static synchronized double rotationSpeed() { return rotationSpeed; }
    public static synchronized double orbitSpeed() { return orbitSpeed; }
    public static synchronized void setSpeedMode(String mode) {
        speedMode = "manual".equals(mode) || "paused".equals(mode) ? mode : "auto";
        STORE.put("speed_mode", speedMode);
    }
    public static synchronized void setRotationSpeed(double value) {
        rotationSpeed = clamp(value, 0, 2); STORE.putDouble("rotation_speed", rotationSpeed);
    }
    public static synchronized void setOrbitSpeed(double value) {
        orbitSpeed = clamp(value, 0, 2); STORE.putDouble("orbit_speed", orbitSpeed);
    }
    public static synchronized boolean orbitsVisible() { return orbitsVisible && orbitThickness > 0; }
    public static synchronized void setOrbitsVisible(boolean value) {
        orbitsVisible = value; STORE.putBoolean("orbits_visible", value);
    }
    public static synchronized int orbitThickness() { return orbitThickness; }
    public static synchronized void setOrbitThickness(int value) {
        orbitThickness = Math.max(0, Math.min(4, value)); STORE.putInt("orbit_thickness", orbitThickness);
        orbitsVisible = orbitThickness > 0; STORE.putBoolean("orbits_visible", orbitsVisible);
    }
    public static synchronized boolean atlasOrbits() { return atlasOrbits; }
    public static synchronized void toggleAtlasOrbits() {
        atlasOrbits = !atlasOrbits; STORE.putBoolean("atlas_orbits", atlasOrbits);
    }
    public static synchronized boolean atlasLabels() { return atlasLabels; }
    public static synchronized void toggleAtlasLabels() {
        atlasLabels = !atlasLabels; STORE.putBoolean("atlas_labels", atlasLabels);
    }
    public static synchronized boolean atlasSectors() { return atlasSectors; }
    public static synchronized void toggleAtlasSectors() {
        atlasSectors = !atlasSectors; STORE.putBoolean("atlas_sectors", atlasSectors);
    }
    public static synchronized boolean atlasGuides() { return atlasGuides; }
    public static synchronized void toggleAtlasGuides() {
        atlasGuides = !atlasGuides; STORE.putBoolean("atlas_guides", atlasGuides);
    }
    public static synchronized boolean atlasSectorLinks() { return atlasSectorLinks; }
    public static synchronized void toggleAtlasSectorLinks() {
        atlasSectorLinks = !atlasSectorLinks; STORE.putBoolean("atlas_sector_links", atlasSectorLinks);
    }
    public static synchronized boolean atlasPacks() { return atlasPacks; }
    public static synchronized void toggleAtlasPacks() {
        atlasPacks = !atlasPacks; STORE.putBoolean("atlas_packs", atlasPacks);
    }
    public static synchronized boolean atlasRoutes() { return atlasRoutes; }
    public static synchronized void toggleAtlasRoutes() {
        atlasRoutes = !atlasRoutes; STORE.putBoolean("atlas_routes", atlasRoutes);
    }
    public static synchronized boolean atlasTextures() { return atlasTextures; }
    public static synchronized void toggleAtlasTextures() {
        atlasTextures = !atlasTextures; STORE.putBoolean("atlas_textures", atlasTextures);
    }
    public static synchronized boolean atlasStarGlow() { return atlasStarGlow; }
    public static synchronized void toggleAtlasStarGlow() {
        atlasStarGlow = !atlasStarGlow; STORE.putBoolean("atlas_star_glow", atlasStarGlow);
    }
    public static synchronized int atlasDetail() { return atlasDetail; }
    public static synchronized String atlasDetailName() {
        return switch (atlasDetail) { case 0 -> "COMPACT"; case 2 -> "DETAILED"; default -> "BALANCED"; };
    }
    public static synchronized int atlasBodyLimit() {
        return switch (atlasDetail) { case 0 -> 4; case 2 -> 12; default -> 8; };
    }
    public static synchronized int atlasOrbitLimit() {
        return switch (atlasDetail) { case 0 -> 3; case 2 -> 8; default -> 5; };
    }
    public static synchronized void cycleAtlasDetail() {
        atlasDetail = (atlasDetail + 1) % 3; STORE.putInt("atlas_detail", atlasDetail);
    }
    public static synchronized double atlasScale() {
        return switch (atlasScaleStep) { case 0 -> 0.8; case 2 -> 1.25; case 3 -> 1.5; default -> 1.0; };
    }
    public static synchronized String atlasScaleName() {
        return String.format(java.util.Locale.ROOT, "%.2fx", atlasScale());
    }
    public static synchronized void cycleAtlasScale() {
        atlasScaleStep = (atlasScaleStep + 1) % 4; STORE.putInt("atlas_scale_step", atlasScaleStep);
    }
    /** Presentation-only multiplier for spacing between rendered planetary orbits. */
    public static synchronized double atlasOrbitSpacing() {
        return switch (atlasOrbitSpacingStep) { case 0 -> 1.0; case 1 -> 1.35; case 3 -> 2.25; case 4 -> 2.75; default -> 1.8; };
    }
    public static synchronized String atlasOrbitSpacingName() {
        return String.format(java.util.Locale.ROOT, "%.2fx", atlasOrbitSpacing());
    }
    public static synchronized void cycleAtlasOrbitSpacing() {
        atlasOrbitSpacingStep = (atlasOrbitSpacingStep + 1) % 5;
        STORE.putInt("atlas_orbit_spacing_step", atlasOrbitSpacingStep);
    }
    public static synchronized String orbitMode() { return orbitMode; }
    public static synchronized void cycleOrbitMode() {
        orbitMode = switch (orbitMode) { case "all" -> "selected"; case "selected" -> "major"; default -> "all"; };
        STORE.put("orbit_mode", orbitMode);
    }
    public static synchronized boolean showOrbitFor(boolean selected, boolean major) {
        if (!orbitsVisible()) return false;
        return switch (orbitMode) { case "selected" -> selected; case "major" -> major; default -> true; };
    }
    public static synchronized String manualParent(String universe, String childId) {
        return STORE.get(parentKey(universe, childId), "");
    }
    public static synchronized void setManualParent(String universe, String childId, String parentId) {
        if (universe == null || childId == null || childId.isBlank()) return;
        String key = parentKey(universe, childId);
        if (parentId == null || parentId.isBlank()) STORE.remove(key);
        else if (!childId.equals(parentId)) STORE.put(key, parentId);
    }
    /** Zero means use the source datapack's original orbital radius. */
    public static synchronized double manualOrbitRadius(String universe, String childId) {
        if (universe == null || childId == null || childId.isBlank()) return 0.0;
        return STORE.getDouble(orbitRadiusKey(universe, childId), 0.0);
    }
    public static synchronized void setManualOrbitRadius(String universe, String childId, double radius) {
        if (universe == null || childId == null || childId.isBlank()) return;
        String key = orbitRadiusKey(universe, childId);
        if (!Double.isFinite(radius) || radius <= 0.0) STORE.remove(key);
        else STORE.putDouble(key, Math.max(1.0E-6, Math.min(1.0E15, radius)));
    }
    /** Removes and returns a legacy parent override during migration to CosmicOrbitSettings. */
    public static synchronized String consumeManualParent(String universe, String childId) {
        if (universe == null || childId == null || childId.isBlank()) return "";
        String key = parentKey(universe, childId);
        String value = STORE.get(key, "");
        STORE.remove(key);
        return value == null ? "" : value.trim();
    }
    /** Removes and returns a legacy orbit radius during migration to CosmicOrbitSettings. */
    public static synchronized double consumeManualOrbitRadius(String universe, String childId) {
        if (universe == null || childId == null || childId.isBlank()) return 0.0;
        String key = orbitRadiusKey(universe, childId);
        double value = STORE.getDouble(key, 0.0);
        STORE.remove(key);
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }
    /** Clears any legacy values when a body is reset to its datapack definition. */
    public static synchronized void clearManualOrbitOverrides(String universe, String childId) {
        if (universe == null || childId == null || childId.isBlank()) return;
        STORE.remove(parentKey(universe, childId));
        STORE.remove(orbitRadiusKey(universe, childId));
    }
    private static String orbitRadiusKey(String universe, String child) {
        return "orbit_radius." + Integer.toHexString(universe.hashCode()) + "." + Integer.toHexString(child.hashCode());
    }
    private static String parentKey(String universe, String child) {
        return "parent." + Integer.toHexString((universe == null ? "" : universe).hashCode())
                + "." + Integer.toHexString(child.hashCode());
    }
    // Workspace state is intentionally separate from navigator selection/camera/routes.
    public static synchronized WorkspaceState.Snapshot workspace() { return WORKSPACE.snapshot(); }
    public static synchronized String[] panelOrder() { return WORKSPACE.panelOrderKeys(); }
    public static synchronized void setPanelOrder(String[] order) { WORKSPACE.setPanelOrder(order); }
    public static synchronized boolean showListPanel() { return WORKSPACE.snapshot().listVisible(); }
    public static synchronized boolean showInfoPanel() { return WORKSPACE.snapshot().infoVisible(); }
    public static synchronized void setShowListPanel(boolean value) { WORKSPACE.setListVisible(value); }
    public static synchronized void setShowInfoPanel(boolean value) { WORKSPACE.setInfoVisible(value); }
    public static synchronized int listPanelWidth() { return WORKSPACE.snapshot().listWidth(); }
    public static synchronized int infoPanelWidth() { return WORKSPACE.snapshot().infoWidth(); }
    public static synchronized void setListPanelWidth(int value) { WORKSPACE.setListWidth(value); }
    public static synchronized void setInfoPanelWidth(int value) { WORKSPACE.setInfoWidth(value); }
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, Double.isFinite(value) ? value : min));
    }
}
