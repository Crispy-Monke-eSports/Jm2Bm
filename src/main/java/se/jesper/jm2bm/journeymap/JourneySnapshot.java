package se.jesper.jm2bm.journeymap;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record JourneySnapshot(Set<UUID> presentGroups, List<WaypointData> waypoints) {
    public JourneySnapshot {
        presentGroups = Set.copyOf(presentGroups);
        waypoints = List.copyOf(waypoints);
    }

    public record WaypointData(
            String guid,
            UUID groupUuid,
            String name,
            int x,
            int y,
            int z,
            Set<String> dimensions
    ) {
        public WaypointData {
            dimensions = Set.copyOf(dimensions);
        }
    }
}
