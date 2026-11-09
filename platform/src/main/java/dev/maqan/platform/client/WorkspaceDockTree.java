package dev.maqan.platform.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renderer-independent docking tree for MAQAN Workspace 2.0.
 *
 * <p>The tree is the canonical layout structure: split nodes allocate real
 * adjacent work areas and tab nodes retain several panels in one dock slot.
 * It deliberately contains no Minecraft Screen, provider, or simulation
 * references so it can be validated and tested without loading a game.</p>
 */
public final class WorkspaceDockTree {
    public static final int WEIGHT_SCALE = 1_000;
    public static final int MIN_SPLIT_WEIGHT = 100;
    public static final int MAX_SPLIT_WEIGHT = 900;

    public enum Axis { HORIZONTAL, VERTICAL }
    public enum Side { LEFT, RIGHT, TOP, BOTTOM }

    public sealed interface Node permits Panel, Split, Tabs {}

    public record Panel(String id) implements Node {
        public Panel {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Panel id is required");
            id = id.trim();
        }
    }

    /** firstWeight is expressed in thousandths; HORIZONTAL means left/right. */
    public record Split(Axis axis, int firstWeight, Node first, Node second) implements Node {
        public Split {
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            if (firstWeight < MIN_SPLIT_WEIGHT || firstWeight > MAX_SPLIT_WEIGHT)
                throw new IllegalArgumentException("Split weight must be between 100 and 900");
        }
    }

    /** Tabs are a flat group of panels; activeIndex identifies the visible view. */
    public record Tabs(List<Panel> panels, int activeIndex) implements Node {
        public Tabs {
            if (panels == null || panels.size() < 2)
                throw new IllegalArgumentException("A tab group requires at least two panels");
            panels = List.copyOf(panels);
            if (activeIndex < 0 || activeIndex >= panels.size())
                throw new IllegalArgumentException("Active tab index is outside the tab group");
        }
    }

    public record Rect(int x, int y, int width, int height) {
        public Rect {
            if (width < 0 || height < 0) throw new IllegalArgumentException("Negative layout size");
        }
    }

    public record MinimumSize(int width, int height) {
        public MinimumSize {
            if (width < 0 || height < 0) throw new IllegalArgumentException("Negative minimum size");
        }
    }

    private WorkspaceDockTree() {}

    public static Map<String, Rect> layout(Node root, Rect bounds) {
        return layout(root, bounds, Map.of());
    }

    public static Map<String, Rect> layout(Node root, Rect bounds, Map<String, MinimumSize> minimums) {
        Objects.requireNonNull(bounds, "bounds");
        Map<String, Rect> result = new LinkedHashMap<>();
        if (root != null) layoutInto(root, bounds, minimums == null ? Map.of() : minimums, result);
        return Collections.unmodifiableMap(result);
    }

    private static void layoutInto(Node node, Rect bounds, Map<String, MinimumSize> minimums,
                                   Map<String, Rect> result) {
        if (node instanceof Panel panel) {
            result.put(panel.id(), bounds);
            return;
        }
        if (node instanceof Tabs tabs) {
            Panel active = tabs.panels().get(tabs.activeIndex());
            result.put(active.id(), bounds);
            return;
        }
        Split split = (Split) node;
        boolean horizontal = split.axis() == Axis.HORIZONTAL;
        int available = horizontal ? bounds.width() : bounds.height();
        int firstMin = minimum(split.first(), minimums, horizontal);
        int secondMin = minimum(split.second(), minimums, horizontal);
        int firstSize = (int) (((long) available * split.firstWeight()) / WEIGHT_SCALE);
        if (available >= firstMin + secondMin) {
            firstSize = Math.max(firstMin, Math.min(available - secondMin, firstSize));
        } else {
            firstSize = Math.max(0, Math.min(available, firstSize));
        }
        int secondSize = Math.max(0, available - firstSize);
        Rect firstBounds = horizontal
                ? new Rect(bounds.x(), bounds.y(), firstSize, bounds.height())
                : new Rect(bounds.x(), bounds.y(), bounds.width(), firstSize);
        Rect secondBounds = horizontal
                ? new Rect(bounds.x() + firstSize, bounds.y(), secondSize, bounds.height())
                : new Rect(bounds.x(), bounds.y() + firstSize, bounds.width(), secondSize);
        layoutInto(split.first(), firstBounds, minimums, result);
        layoutInto(split.second(), secondBounds, minimums, result);
    }

    private static int minimum(Node node, Map<String, MinimumSize> minimums, boolean horizontal) {
        if (node instanceof Panel panel) {
            MinimumSize size = minimums.get(panel.id());
            return size == null ? 0 : (horizontal ? size.width() : size.height());
        }
        if (node instanceof Tabs tabs) {
            int value = 0;
            for (Panel panel : tabs.panels()) {
                MinimumSize size = minimums.get(panel.id());
                if (size != null) value = Math.max(value, horizontal ? size.width() : size.height());
            }
            return value;
        }
        Split split = (Split) node;
        if (split.axis() == (horizontal ? Axis.HORIZONTAL : Axis.VERTICAL))
            return minimum(split.first(), minimums, horizontal)
                    + minimum(split.second(), minimums, horizontal);
        return Math.max(minimum(split.first(), minimums, horizontal),
                minimum(split.second(), minimums, horizontal));
    }

    public static Node dock(Node root, String targetPanelId, String newPanelId,
                            Side side, int newPanelWeight) {
        if (root == null) throw new IllegalArgumentException("Cannot dock into an empty workspace");
        if (newPanelId == null || newPanelId.isBlank()) throw new IllegalArgumentException("New panel id is required");
        Objects.requireNonNull(side, "side");
        if (newPanelWeight < MIN_SPLIT_WEIGHT || newPanelWeight > MAX_SPLIT_WEIGHT)
            throw new IllegalArgumentException("New panel weight must be between 100 and 900");
        if (!panelIds(root).contains(targetPanelId)) throw new IllegalArgumentException("Target panel does not exist");
        if (panelIds(root).contains(newPanelId)) throw new IllegalArgumentException("Panel already exists: " + newPanelId);
        return replace(root, targetPanelId, target -> {
            Panel added = new Panel(newPanelId);
            boolean horizontal = side == Side.LEFT || side == Side.RIGHT;
            boolean before = side == Side.LEFT || side == Side.TOP;
            int firstWeight = before ? newPanelWeight : WEIGHT_SCALE - newPanelWeight;
            return before
                    ? new Split(horizontal ? Axis.HORIZONTAL : Axis.VERTICAL, firstWeight, added, target)
                    : new Split(horizontal ? Axis.HORIZONTAL : Axis.VERTICAL, firstWeight, target, added);
        });
    }

    public static Node group(Node root, String targetPanelId, String newPanelId) {
        if (root == null) throw new IllegalArgumentException("Cannot group into an empty workspace");
        if (newPanelId == null || newPanelId.isBlank()) throw new IllegalArgumentException("New panel id is required");
        if (!panelIds(root).contains(targetPanelId)) throw new IllegalArgumentException("Target panel does not exist");
        if (panelIds(root).contains(newPanelId)) throw new IllegalArgumentException("Panel already exists: " + newPanelId);
        return replace(root, targetPanelId, target -> {
            Panel added = new Panel(newPanelId);
            if (target instanceof Tabs tabs) {
                List<Panel> items = new ArrayList<>(tabs.panels());
                items.add(added);
                return new Tabs(items, items.size() - 1);
            }
            return new Tabs(List.of((Panel) target, added), 1);
        });
    }

    public static Node activateTab(Node root, String panelId) {
        if (root == null || panelId == null) return root;
        if (root instanceof Tabs tabs) {
            for (int i = 0; i < tabs.panels().size(); i++)
                if (tabs.panels().get(i).id().equals(panelId)) return new Tabs(tabs.panels(), i);
            return root;
        }
        if (root instanceof Split split) {
            Node first = activateTab(split.first(), panelId);
            Node second = activateTab(split.second(), panelId);
            return first == split.first() && second == split.second()
                    ? root : new Split(split.axis(), split.firstWeight(), first, second);
        }
        return root;
    }

    public static Node remove(Node root, String panelId) {
        if (root == null || panelId == null || panelId.isBlank()) return root;
        if (root instanceof Panel panel) return panel.id().equals(panelId) ? null : root;
        if (root instanceof Tabs tabs) {
            List<Panel> remaining = tabs.panels().stream()
                    .filter(panel -> !panel.id().equals(panelId)).toList();
            if (remaining.size() == tabs.panels().size()) return root;
            if (remaining.isEmpty()) return null;
            if (remaining.size() == 1) return remaining.get(0);
            int active = Math.min(tabs.activeIndex(), remaining.size() - 1);
            if (tabs.panels().get(tabs.activeIndex()).id().equals(panelId))
                active = Math.max(0, active - 1);
            return new Tabs(remaining, active);
        }
        Split split = (Split) root;
        Node first = remove(split.first(), panelId);
        Node second = remove(split.second(), panelId);
        if (first == null) return second;
        if (second == null) return first;
        if (first == split.first() && second == split.second()) return root;
        return new Split(split.axis(), split.firstWeight(), first, second);
    }

    public static Set<String> panelIds(Node root) {
        Set<String> ids = new LinkedHashSet<>();
        collectIds(root, ids);
        return Collections.unmodifiableSet(ids);
    }

    public static void validate(Node root) {
        Set<String> ids = new LinkedHashSet<>();
        validateInto(root, ids);
    }

    private static void validateInto(Node node, Set<String> ids) {
        if (node == null) return;
        if (node instanceof Panel panel) {
            if (!ids.add(panel.id())) throw new IllegalArgumentException("Duplicate panel id: " + panel.id());
        } else if (node instanceof Tabs tabs) {
            if (tabs.panels().size() < 2) throw new IllegalArgumentException("Tab group is too small");
            for (Panel panel : tabs.panels())
                if (!ids.add(panel.id())) throw new IllegalArgumentException("Duplicate panel id: " + panel.id());
        } else {
            Split split = (Split) node;
            validateInto(split.first(), ids);
            validateInto(split.second(), ids);
        }
    }

    private static void collectIds(Node node, Set<String> ids) {
        if (node == null) return;
        if (node instanceof Panel panel) ids.add(panel.id());
        else if (node instanceof Tabs tabs) for (Panel panel : tabs.panels()) ids.add(panel.id());
        else {
            Split split = (Split) node;
            collectIds(split.first(), ids);
            collectIds(split.second(), ids);
        }
    }

    private interface Replacement { Node apply(Node target); }

    private static Node replace(Node node, String targetId, Replacement replacement) {
        if (node instanceof Panel panel)
            return panel.id().equals(targetId) ? replacement.apply(panel) : node;
        if (node instanceof Tabs tabs) {
            for (Panel panel : tabs.panels()) {
                if (!panel.id().equals(targetId)) continue;
                Node changed = replacement.apply(tabs);
                return changed;
            }
            return node;
        }
        Split split = (Split) node;
        Node first = replace(split.first(), targetId, replacement);
        if (first != split.first()) return new Split(split.axis(), split.firstWeight(), first, split.second());
        Node second = replace(split.second(), targetId, replacement);
        return second == split.second() ? node
                : new Split(split.axis(), split.firstWeight(), split.first(), second);
    }
}
