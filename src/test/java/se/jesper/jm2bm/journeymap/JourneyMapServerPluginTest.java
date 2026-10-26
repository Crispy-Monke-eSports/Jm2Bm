package se.jesper.jm2bm.journeymap;

import journeymap.api.v2.common.event.ServerEventRegistry;
import journeymap.api.v2.common.event.impl.EventImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JourneyMapServerPluginTest {
    @Test
    void listenersCanBeStoppedAndRegisteredAgainWithoutDuplicates() {
        JourneyMapServerPlugin.unregisterListeners();
        int waypointBaseline = listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT);
        int groupBaseline = listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT);
        JourneyMapServerPlugin plugin = new JourneyMapServerPlugin();

        try {
            plugin.initialize(null);
            plugin.initialize(null);
            assertEquals(waypointBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT));
            assertEquals(groupBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT));

            JourneyMapServerPlugin.unregisterListeners();
            assertEquals(waypointBaseline, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT));
            assertEquals(groupBaseline, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT));

            JourneyMapServerPlugin.registerListeners();
            assertEquals(waypointBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_EVENT));
            assertEquals(groupBaseline + 1, listenerCount(ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT));
        } finally {
            JourneyMapServerPlugin.unregisterListeners();
        }
    }

    private static int listenerCount(Object event) {
        return ((EventImpl<?>) event).getListeners().size();
    }
}
