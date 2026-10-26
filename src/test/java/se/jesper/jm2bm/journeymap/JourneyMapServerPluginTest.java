package se.jesper.jm2bm.journeymap;

import journeymap.api.v2.common.event.ServerEventRegistry;
import journeymap.api.v2.common.event.impl.EventImpl;
import journeymap.api.v2.server.IServerAPI;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JourneyMapServerPluginTest {
    @Test
    void listenersCanBeStoppedAndRegisteredAgainWithoutDuplicates() {
        JourneyMapServerPlugin.unregisterListeners();
        int waypointBaseline = listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT);
        int groupBaseline = listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT);
        JourneyMapServerPlugin plugin = new JourneyMapServerPlugin();
        IServerAPI api = (IServerAPI) Proxy.newProxyInstance(IServerAPI.class.getClassLoader(),
                new Class<?>[]{IServerAPI.class}, (proxy, method, arguments) -> null);

        try {
            plugin.initialize(api);
            plugin.initialize(api);
            assertSame(api, JourneyMapServerPlugin.pendingApi());
            assertEquals(waypointBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT));
            assertEquals(groupBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT));

            JourneyMapServerPlugin.unregisterListeners();
            assertEquals(waypointBaseline, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT));
            assertEquals(groupBaseline, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT));

            JourneyMapServerPlugin.registerListeners();
            assertEquals(waypointBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT));
            assertEquals(groupBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT));

            JourneyMapServerPlugin.clearPendingApi();
            assertNull(JourneyMapServerPlugin.pendingApi());
        } finally {
            JourneyMapServerPlugin.unregisterListeners();
            JourneyMapServerPlugin.clearPendingApi();
        }
    }

    private static int listenerCount(Object event) {
        return ((EventImpl<?>) event).getListeners().size();
    }
}
