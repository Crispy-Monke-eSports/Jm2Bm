package se.jesper.jm2bm.sync;

import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.journeymap.JourneySnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SyncPlanner {
    private SyncPlanner() {
    }

    public static Map<String, DesiredMarker> plan(
            BridgeConfig config,
            JourneySnapshot snapshot,
            Map<UUID, String> iconAssets,
            String dimension,
            String markerSetId
    ) throws PlanningException {
        Map<UUID, BridgeConfig.Mapping> mappings = new HashMap<>();
        for (BridgeConfig.Mapping mapping : config.mappings()) {
            if (mapping.blueMapMarkerSet().equals(markerSetId)) {
                mappings.put(mapping.journeyMapGroupUuid(), mapping);
            }
        }

        Map<String, DesiredMarker> desired = new HashMap<>();
        for (JourneySnapshot.WaypointData waypoint : snapshot.waypoints()) {
            if (!waypoint.dimensions().contains(dimension)) continue;
            if (!mappings.containsKey(waypoint.groupUuid())) continue;
            DesiredMarker candidate = new DesiredMarker(
                    waypoint.guid(), waypoint.name(), waypoint.x(), waypoint.y(), waypoint.z(),
                    iconAssets.get(waypoint.groupUuid()));
            DesiredMarker previous = desired.putIfAbsent(candidate.id(), candidate);
            if (previous != null && !previous.equals(candidate)) {
                throw new PlanningException("conflicting desired marker ID " + candidate.id());
            }
        }
        return Map.copyOf(desired);
    }

    public static final class PlanningException extends Exception {
        public PlanningException(String message) {
            super(message);
        }
    }
}
