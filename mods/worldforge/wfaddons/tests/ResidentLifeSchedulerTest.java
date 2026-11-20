package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.settle.ResidentLifeEvents;

public final class ResidentLifeSchedulerTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        for (int i = 0; i < 11; i++) {
            Character person = registry.create("Resident " + i, "House " + i, "test",
                    i % 2 == 0 ? "m" : "f", 1980);
            person.props.put("population.birth_year", 1980);
        }
        java.util.List<Character> liveView = registry.all();
        registry.create("Late arrival", "House late", "test", "f", 1982);
        check(liveView.size() == 12, "registry exposes a stable append-only view without copying the population");
        int calls = 0;
        boolean complete = false;
        while (!complete && calls < 100) {
            long tick = calls;
            complete = ResidentLifeEvents.advanceYearly(registry, 2000, null, 3, tick);
            boolean duplicate = ResidentLifeEvents.advanceYearly(registry, 2000, null, 3, tick);
            check(!duplicate || complete, "same-tick duplicate call cannot finish a partial pass");
            calls++;
        }
        check(complete, "annual family simulation eventually completes");
        check(calls >= 4, "small budget spreads population processing over multiple ticks");
        for (Character person : registry.all()) {
            check(Integer.valueOf(2000).equals(person.props.get("social.last_family_year")),
                    "every canonical resident receives the annual family marker");
            check(Integer.valueOf(2000).equals(person.props.get("family.development_year")),
                    "every resident's development stage advances");
        }
        System.out.println("ResidentLifeSchedulerTest: OK");
    }
}
