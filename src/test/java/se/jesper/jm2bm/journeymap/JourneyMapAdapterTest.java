package se.jesper.jm2bm.journeymap;

import journeymap.api.v2.common.waypoint.WaypointGroup;
import journeymap.api.v2.server.IServerAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JourneyMapAdapterTest {
    private static final UUID USER_GROUP = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void ignoresBuiltInTextIdsWhenListingGroupsForTabCompletion() throws Exception {
        JourneyMapAdapter adapter = adapter(List.of(
                group("journeymap_global_default", "Default"),
                group(USER_GROUP.toString(), "Towns")));

        assertEquals(List.of(new JourneyMapAdapter.GroupRef(USER_GROUP, "Towns")), adapter.groups());
    }

    @Test
    void ignoresBuiltInTextIdsInSynchronizationSnapshot() throws Exception {
        JourneyMapAdapter adapter = adapter(List.of(
                group("journeymap_global_default", "Default"),
                group(USER_GROUP.toString(), "Towns")));

        JourneySnapshot snapshot = adapter.snapshot(Set.of(USER_GROUP));

        assertEquals(Set.of(USER_GROUP), snapshot.presentGroups());
        assertEquals(List.of(), snapshot.waypoints());
    }

    @Test
    void distinguishesBuiltInIdsFromMissingIds() throws Exception {
        assertNull(JourneyMapAdapter.parseSelectableGroupUuid("journeymap_global_default"));
        assertThrows(SnapshotException.class, () -> JourneyMapAdapter.parseSelectableGroupUuid("  "));
        assertThrows(SnapshotException.class, () -> JourneyMapAdapter.parseSelectableGroupUuid(null));
    }

    private static JourneyMapAdapter adapter(List<WaypointGroup> groups) {
        IServerAPI api = proxy(IServerAPI.class, (method, arguments) -> switch (method.getName()) {
            case "getAllGlobalGroups" -> groups;
            case "getGlobalWaypoints" -> List.of();
            default -> defaultValue(method.getReturnType());
        });
        JourneyMapAdapter adapter = new JourneyMapAdapter();
        adapter.connect(api);
        return adapter;
    }

    private static WaypointGroup group(String id, String name) {
        return proxy(WaypointGroup.class, (method, arguments) -> switch (method.getName()) {
            case "getGuid" -> id;
            case "getName" -> name;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method, arguments));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
