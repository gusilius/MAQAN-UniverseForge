package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.settle.ResidentLifeEvents;

public final class ResidentLifeSchedulerTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }


    private static Object registryPassField(CharacterRegistry registry, String fieldName) {
        try {
            java.lang.reflect.Field passesField = ResidentLifeEvents.class.getDeclaredField("REGISTRY_PASSES");
            passesField.setAccessible(true);
            java.util.Map<?, ?> passes = (java.util.Map<?, ?>) passesField.get(null);
            Object state = passes.get(registry);
            if (state == null) return null;
            java.lang.reflect.Field stateField = state.getClass().getDeclaredField(fieldName);
            stateField.setAccessible(true);
            return stateField.get(state);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect resident life pass state", failure);
        }
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
        ResidentLifeEvents.advanceYearly(registry, 2000, null, 3, 9001L);
        Object session = registryPassField(registry, "session");
        check(session != null, "bounded annual pass creates a resumable session");
        try {
            java.lang.reflect.Field peopleField = session.getClass().getDeclaredField("people");
            peopleField.setAccessible(true);
            check(peopleField.get(session) == registry.all(),
                    "annual life session retains the canonical append-only registry view without copying it");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect annual session population view", failure);
        }
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

        Character sameYearArrival = registry.create("Same-year arrival", "House late", "test", "f", 1982);
        boolean stageCatchup = ResidentLifeEvents.advanceYearly(registry, 2000, null, 3, calls + 100L);
        check(stageCatchup, "same-year population additions receive bounded stage catch-up");
        check(sameYearArrival.props.get("population.life_stage") instanceof String,
                "same-year arrival receives its calendar-derived life stage");
        check(sameYearArrival.props.get("population.age_years") instanceof Number,
                "same-year arrival receives its calendar-derived age");
        System.out.println("ResidentLifeSchedulerTest: OK");
    }
}
