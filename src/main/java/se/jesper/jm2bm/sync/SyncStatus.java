package se.jesper.jm2bm.sync;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record SyncStatus(
        Instant completedAt,
        boolean success,
        String message,
        int created,
        int updated,
        int removed,
        int managedSets,
        int managedMaps,
        Map<String, Integer> markerCounts,
        Set<String> missingMarkerSets,
        Set<UUID> missingGroups
) {
    public SyncStatus {
        markerCounts = Map.copyOf(markerCounts);
        missingMarkerSets = Set.copyOf(missingMarkerSets);
        missingGroups = Set.copyOf(missingGroups);
    }

    public static SyncStatus never() {
        return new SyncStatus(null, false, "No sync has completed yet", 0, 0, 0, 0, 0,
                Map.of(), Set.of(), Set.of());
    }

    public static SyncStatus failed(String message) {
        return new SyncStatus(Instant.now(), false, message, 0, 0, 0, 0, 0,
                Map.of(), Set.of(), Set.of());
    }
}
