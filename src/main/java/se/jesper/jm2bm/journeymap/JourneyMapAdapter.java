package se.jesper.jm2bm.journeymap;

import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import journeymap.api.v2.server.IServerAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JourneyMapAdapter {
    private volatile IServerAPI api;

    public void connect(IServerAPI api) {
        this.api = Objects.requireNonNull(api);
    }

    public void disconnect() {
        this.api = null;
    }

    public boolean isConnected() {
        return api != null;
    }

    public List<GroupRef> groups() throws SnapshotException {
        IServerAPI current = api;
        if (current == null) throw new SnapshotException("JourneyMap API is not connected");
        try {
            List<WaypointGroup> groups = current.getAllGlobalGroups();
            if (groups == null) throw new SnapshotException("JourneyMap returned a null group collection");
            List<GroupRef> result = new ArrayList<>();
            Set<UUID> ids = new HashSet<>();
            for (WaypointGroup group : groups) {
                if (group == null) throw new SnapshotException("JourneyMap returned an incomplete global group");
                UUID id = parseSelectableGroupUuid(group.getGuid());
                if (id == null) continue;
                String name = requireText(group.getName(), "global group name");
                if (!ids.add(id)) throw new SnapshotException("JourneyMap returned duplicate global group " + id);
                result.add(new GroupRef(id, name));
            }
            return List.copyOf(result);
        } catch (SnapshotException e) {
            throw e;
        } catch (Throwable t) {
            throw new SnapshotException("Could not list JourneyMap groups: " + t.getMessage(), t);
        }
    }

    public JourneySnapshot snapshot(Set<UUID> requiredGroups) throws SnapshotException {
        IServerAPI current = api;
        if (current == null) throw new SnapshotException("JourneyMap API is not connected");

        try {
            List<WaypointGroup> rawGroups = current.getAllGlobalGroups();
            List<Waypoint> rawWaypoints = current.getGlobalWaypoints();
            if (rawGroups == null || rawWaypoints == null) {
                throw new SnapshotException("JourneyMap returned a null collection");
            }

            Set<UUID> presentGroups = new HashSet<>();
            for (WaypointGroup group : rawGroups) {
                if (group == null || group.getGuid() == null) {
                    throw new SnapshotException("JourneyMap returned an incomplete global group");
                }
                UUID id = parseSelectableGroupUuid(group.getGuid());
                if (id == null) continue;
                if (!presentGroups.add(id)) {
                    throw new SnapshotException("JourneyMap returned duplicate global group " + id);
                }
            }

            List<JourneySnapshot.WaypointData> waypoints = new ArrayList<>();
            Map<String, JourneySnapshot.WaypointData> uniqueWaypoints = new HashMap<>();
            for (Waypoint waypoint : rawWaypoints) {
                if (waypoint == null || waypoint.getGroupId() == null) {
                    throw new SnapshotException("JourneyMap returned an incomplete global waypoint");
                }
                UUID groupId;
                try {
                    groupId = UUID.fromString(waypoint.getGroupId());
                } catch (IllegalArgumentException ignored) {
                    // An unrelated built-in/non-UUID group cannot be selected by this mod's UUID config.
                    continue;
                }
                if (!requiredGroups.contains(groupId)) continue;
                if (!presentGroups.contains(groupId)) {
                    throw new SnapshotException("Waypoint references mapped group " + groupId
                            + " which is absent from the group snapshot");
                }
                String guid = requireText(waypoint.getGuid(), "waypoint GUID");
                parseUuid(guid, "waypoint GUID");
                String name = requireText(waypoint.getName(), "waypoint name");
                if (waypoint.getDimensions() == null) {
                    throw new SnapshotException("Waypoint " + guid + " has no dimensions collection");
                }
                Set<String> dimensions = new HashSet<>();
                for (String dimension : waypoint.getDimensions()) {
                    dimensions.add(requireText(dimension, "dimension on waypoint " + guid));
                }
                if (dimensions.isEmpty() && waypoint.getPrimaryDimension() != null
                        && !waypoint.getPrimaryDimension().isBlank()) {
                    dimensions.add(waypoint.getPrimaryDimension());
                }
                if (dimensions.isEmpty()) {
                    throw new SnapshotException("Waypoint " + guid + " has no dimension");
                }
                JourneySnapshot.WaypointData data = new JourneySnapshot.WaypointData(
                        guid, groupId, name, waypoint.getX(), waypoint.getY(), waypoint.getZ(), dimensions);
                JourneySnapshot.WaypointData previous = uniqueWaypoints.putIfAbsent(guid, data);
                if (previous != null && !previous.equals(data)) {
                    throw new SnapshotException("Conflicting global waypoint GUID " + guid);
                }
                if (previous == null) waypoints.add(data);
            }
            return new JourneySnapshot(presentGroups, waypoints);
        } catch (SnapshotException e) {
            throw e;
        } catch (Throwable t) {
            throw new SnapshotException("JourneyMap snapshot failed: " + t.getMessage(), t);
        }
    }

    private static UUID parseUuid(String value, String field) throws SnapshotException {
        try {
            return UUID.fromString(requireText(value, field));
        } catch (IllegalArgumentException e) {
            throw new SnapshotException("Invalid " + field + ": " + value, e);
        }
    }

    static UUID parseSelectableGroupUuid(String value) throws SnapshotException {
        String guid = requireText(value, "global group GUID");
        try {
            return UUID.fromString(guid);
        } catch (IllegalArgumentException ignored) {
            // JourneyMap exposes built-in groups such as "journeymap_global_default" alongside
            // administrator-created UUID groups. Only UUID groups can be stored in Jm2Bm config.
            return null;
        }
    }

    private static String requireText(String value, String field) throws SnapshotException {
        if (value == null || value.isBlank()) throw new SnapshotException("Missing " + field);
        return value;
    }

    public record GroupRef(UUID id, String name) {
    }
}
