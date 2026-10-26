package se.jesper.jm2bm.journeymap;

import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ServerEventRegistry;
import journeymap.api.v2.server.IServerAPI;
import journeymap.api.v2.server.IServerPlugin;
import se.jesper.jm2bm.Jm2BmMod;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapServerPlugin implements IServerPlugin {
    private static volatile IServerAPI pendingApi;
    private static volatile JourneyMapServerPlugin instance;
    private boolean waypointSubscribed;
    private boolean groupSubscribed;

    @Override
    public void initialize(IServerAPI api) {
        JourneyMapServerPlugin previous = instance;
        if (previous != null && previous != this) previous.unsubscribeEvents();
        instance = this;
        pendingApi = api;
        subscribeEvents();
        Jm2BmMod.onJourneyMapReady(api);
    }

    @Override
    public String getModId() {
        return Jm2BmMod.MOD_ID;
    }

    public static IServerAPI pendingApi() {
        return pendingApi;
    }

    public static void registerListeners() {
        JourneyMapServerPlugin current = instance;
        if (current != null) current.subscribeEvents();
    }

    public static void unregisterListeners() {
        JourneyMapServerPlugin current = instance;
        if (current != null) current.unsubscribeEvents();
    }

    private synchronized void subscribeEvents() {
        if (!waypointSubscribed) {
            ServerEventRegistry.GLOBAL_WAYPOINT_EVENT.subscribe(
                    this, Jm2BmMod.MOD_ID, event -> Jm2BmMod.onJourneyMapChanged());
            waypointSubscribed = true;
        }
        if (!groupSubscribed) {
            ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT.subscribe(
                    this, Jm2BmMod.MOD_ID, event -> Jm2BmMod.onJourneyMapChanged());
            groupSubscribed = true;
        }
    }

    private synchronized void unsubscribeEvents() {
        if (waypointSubscribed) {
            try {
                ServerEventRegistry.GLOBAL_WAYPOINT_EVENT.unsubscribe(this, Jm2BmMod.MOD_ID);
                waypointSubscribed = false;
            } catch (RuntimeException e) {
                Jm2BmMod.LOGGER.warn("[Jm2Bm] Could not unregister JourneyMap waypoint listener", e);
            }
        }
        if (groupSubscribed) {
            try {
                ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT.unsubscribe(this, Jm2BmMod.MOD_ID);
                groupSubscribed = false;
            } catch (RuntimeException e) {
                Jm2BmMod.LOGGER.warn("[Jm2Bm] Could not unregister JourneyMap group listener", e);
            }
        }
    }
}
