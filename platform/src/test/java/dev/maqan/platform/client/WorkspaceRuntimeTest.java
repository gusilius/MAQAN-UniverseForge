package dev.maqan.platform.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRuntimeTest {
    @AfterEach
    void clear() { WorkspaceRuntime.clearTransientState(); }

    @Test
    void searchIndexesAndRanksProviderOwnedEntities() {
        WorkspaceRuntime.index(new WorkspaceRuntime.SearchEntry(
                "city-1", "wf_settle", "CITY", "Minas Tirith", "middle-earth",
                "Capital city", List.of("gondor", "capital")));
        WorkspaceRuntime.index(new WorkspaceRuntime.SearchEntry(
                "person-1", "wf_people", "CHARACTER", "Faramir", "middle-earth",
                "Captain of Gondor", List.of("steward")));

        assertEquals("Minas Tirith", WorkspaceRuntime.search("minas", 10).get(0).name());
        assertEquals("Faramir", WorkspaceRuntime.search("steward", 10).getFirst().name());
        WorkspaceRuntime.unregisterProvider("wf_settle");
        assertTrue(WorkspaceRuntime.search("minas", 10).isEmpty());
    }

    @Test
    void inspectorAndContextActionsAreProviderScoped() {
        var selection = new PlatformSelectionContext.Selection(
                "wf_people", "person-1", "CHARACTER", "Faramir",
                "middle-earth", "Gondor", 1);
        WorkspaceRuntime.registerInspector("wf_people", ignored ->
                List.of(new WorkspaceRuntime.InspectorField("Identity", "Name", "Faramir")));
        AtomicInteger executions = new AtomicInteger();
        WorkspaceRuntime.registerAction(new WorkspaceRuntime.Action(
                "wf_people.inspect", "Inspect", "wf_people", ignored -> true,
                ignored -> executions.incrementAndGet()));

        assertEquals("Faramir", WorkspaceRuntime.inspect(selection).getFirst().value());
        assertEquals(1, WorkspaceRuntime.actionsFor(selection).size());
        assertTrue(WorkspaceRuntime.executeAction("wf_people.inspect", selection));
        assertEquals(1, executions.get());
        assertFalse(WorkspaceRuntime.executeAction("wf_people.inspect", null));
    }

    @Test
    void eventJournalIsOrderedBoundedAndNotBrokenByListeners() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        AutoCloseable subscription = WorkspaceRuntime.subscribe(event -> delivered.incrementAndGet());
        for (int i = 0; i < 510; i++)
            WorkspaceRuntime.publish(WorkspaceRuntime.Severity.INFO, "test", "unit",
                    "event-" + i, "", "");
        subscription.close();

        List<WorkspaceRuntime.WorkspaceEvent> events = WorkspaceRuntime.journal(1_000);
        assertEquals(500, events.size());
        assertEquals("event-10", events.getFirst().message());
        assertEquals("event-509", events.get(events.size() - 1).message());
        assertEquals(510, delivered.get());
    }

    @Test
    void shortcutRegistryRejectsConflictsAndProviderUnloadCleansRegistrations() {
        var chord = new WorkspaceRuntime.KeyChord(70, 2);
        WorkspaceRuntime.registerShortcut(new WorkspaceRuntime.Shortcut(
                "wf_people.search", "Search people", chord, "search", "wf_people", true));
        assertEquals("wf_people.search", WorkspaceRuntime.shortcutFor(70, 2).orElseThrow().id());
        assertThrows(IllegalArgumentException.class, () -> WorkspaceRuntime.registerShortcut(
                new WorkspaceRuntime.Shortcut("wf_army.search", "Search army", chord,
                        "search-army", "wf_army", true)));

        WorkspaceRuntime.registerComponent(new WorkspaceRuntime.ComponentDescriptor(
                "wf_people.person-card", "inspector", "wf_people", 1, "Character card"));
        WorkspaceRuntime.unregisterProvider("wf_people");
        assertTrue(WorkspaceRuntime.shortcuts().isEmpty());
        assertTrue(WorkspaceRuntime.components().isEmpty());
        assertTrue(WorkspaceRuntime.shortcutFor(70, 2).isEmpty());
    }
}
