package dev.maqan.platform.client;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDockTreeTest {
    @Test
    void dockingCreatesRealAdjacentRegionsAndHonorsMinimumSizes() {
        WorkspaceDockTree.Node tree = new WorkspaceDockTree.Panel("map");
        tree = WorkspaceDockTree.dock(tree, "map", "inspector",
                WorkspaceDockTree.Side.RIGHT, 300);

        Map<String, WorkspaceDockTree.Rect> layout = WorkspaceDockTree.layout(tree,
                new WorkspaceDockTree.Rect(0, 0, 1_000, 600),
                Map.of("map", new WorkspaceDockTree.MinimumSize(500, 200),
                       "inspector", new WorkspaceDockTree.MinimumSize(250, 200)));

        assertEquals(new WorkspaceDockTree.Rect(0, 0, 700, 600), layout.get("map"));
        assertEquals(new WorkspaceDockTree.Rect(700, 0, 300, 600), layout.get("inspector"));
    }

    @Test
    void tabGroupsKeepInactiveViewsAndSwitchTheVisiblePanel() {
        WorkspaceDockTree.Node tree = WorkspaceDockTree.group(
                new WorkspaceDockTree.Panel("map"), "map", "character");
        assertEquals(Set.of("map", "character"), WorkspaceDockTree.panelIds(tree));
        assertEquals(Set.of("character"), WorkspaceDockTree.layout(tree,
                new WorkspaceDockTree.Rect(10, 20, 400, 300)).keySet());

        tree = WorkspaceDockTree.activateTab(tree, "map");
        assertEquals(Set.of("map"), WorkspaceDockTree.layout(tree,
                new WorkspaceDockTree.Rect(10, 20, 400, 300)).keySet());
    }

    @Test
    void removingPanelsCollapsesEmptySplitAndSingleTabGroups() {
        WorkspaceDockTree.Node tree = WorkspaceDockTree.dock(
                new WorkspaceDockTree.Panel("map"), "map", "inspector",
                WorkspaceDockTree.Side.RIGHT, 300);
        tree = WorkspaceDockTree.remove(tree, "inspector");
        assertEquals(new WorkspaceDockTree.Panel("map"), tree);

        tree = WorkspaceDockTree.group(tree, "map", "events");
        tree = WorkspaceDockTree.remove(tree, "events");
        assertEquals(new WorkspaceDockTree.Panel("map"), tree);
    }

    @Test
    void nestedDockAndGroupOperationsPreserveUniqueStableIds() {
        WorkspaceDockTree.Node tree = new WorkspaceDockTree.Panel("map");
        tree = WorkspaceDockTree.dock(tree, "map", "inspector", WorkspaceDockTree.Side.RIGHT, 300);
        tree = WorkspaceDockTree.group(tree, "inspector", "character");
        tree = WorkspaceDockTree.dock(tree, "map", "events", WorkspaceDockTree.Side.BOTTOM, 250);

        WorkspaceDockTree.validate(tree);
        assertEquals(Set.of("map", "inspector", "character", "events"),
                WorkspaceDockTree.panelIds(tree));
        assertEquals(3, WorkspaceDockTree.layout(tree,
                new WorkspaceDockTree.Rect(0, 0, 1_200, 800)).size(),
                "Only active tabs occupy visible layout rectangles");
        assertEquals(4, WorkspaceDockTree.panelIds(tree).size(),
                "Inactive tabs must remain registered in the workspace tree");
    }

    @Test
    void splittersExposeResizeHandlesAndUpdateNestedRatios() {
        WorkspaceDockTree.Node tree = new WorkspaceDockTree.Panel("map");
        tree = WorkspaceDockTree.dock(tree, "map", "inspector",
                WorkspaceDockTree.Side.RIGHT, 300);
        tree = WorkspaceDockTree.dock(tree, "map", "timeline",
                WorkspaceDockTree.Side.BOTTOM, 250);
        var bounds = new WorkspaceDockTree.Rect(0, 0, 1_000, 800);

        assertEquals(2, WorkspaceDockTree.splitters(tree, bounds).size());
        tree = WorkspaceDockTree.resize(tree, "0", 600);
        assertInstanceOf(WorkspaceDockTree.Split.class, tree);
        assertEquals(600, ((WorkspaceDockTree.Split) tree).firstWeight());
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceDockTree.resize(tree, "8", 500));
    }

    @Test
    void rejectsDuplicateIdsAndInvalidSplitWeights() {
        WorkspaceDockTree.Node duplicate = new WorkspaceDockTree.Split(
                WorkspaceDockTree.Axis.HORIZONTAL, 500,
                new WorkspaceDockTree.Panel("same"), new WorkspaceDockTree.Panel("same"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceDockTree.validate(duplicate));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceDockTree.Split(
                WorkspaceDockTree.Axis.HORIZONTAL, 950,
                new WorkspaceDockTree.Panel("a"), new WorkspaceDockTree.Panel("b")));
    }
}
