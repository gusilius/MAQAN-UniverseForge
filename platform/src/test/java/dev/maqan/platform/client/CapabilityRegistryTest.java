package dev.maqan.platform.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryTest {
    @AfterEach
    void cleanup() {
        CapabilityRegistry.unregisterProvider("test_provider");
    }

    @Test
    void providersCanRegisterDiscoverAndUnloadCapabilitiesDynamically() {
        var capability = new CapabilityRegistry.Capability(
                "test.worlds", "WORLDS", "Test worlds", "◇",
                "test_provider", "example.TestScreen", 100);
        CapabilityRegistry.register(capability);

        assertTrue(CapabilityRegistry.all().contains(capability));
        assertTrue(CapabilityRegistry.available().contains(capability));
        assertEquals(capability, CapabilityRegistry.forProvider("test_provider", "example.TestScreen"));

        CapabilityRegistry.unregisterProvider("test_provider");
        assertFalse(CapabilityRegistry.all().contains(capability));
    }

    @Test
    void rejectsCapabilityIdOwnedByAnotherProvider() {
        assertThrows(IllegalArgumentException.class, () -> CapabilityRegistry.register(
                new CapabilityRegistry.Capability("worldforge.rts", "TACTICAL", "Fake", "x",
                        "test_provider", "example.FakeScreen", 999)));
    }
}
