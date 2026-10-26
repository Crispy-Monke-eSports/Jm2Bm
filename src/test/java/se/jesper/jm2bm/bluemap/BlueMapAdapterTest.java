package se.jesper.jm2bm.bluemap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueMapAdapterTest {
    @Test
    void cleanupOnlyCompletesWhenEveryDestinationWasAvailableAndSuccessful() {
        assertTrue(BlueMapAdapter.cleanupComplete(List.of(), Set.of()));
        assertFalse(BlueMapAdapter.cleanupComplete(List.of(), Set.of("world/removed-set")));
        assertFalse(BlueMapAdapter.cleanupComplete(List.of("write failed"), Set.of()));
    }
}
