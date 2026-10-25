package se.jesper.jm2bm.journeymap;

import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ServerEventRegistry;
import journeymap.api.v2.server.IServerAPI;
import journeymap.api.v2.server.IServerPlugin;
import se.jesper.jm2bm.Jm2BmMod;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapServerPlugin implements IServerPlugin {
    private static volatile IServerAPI pendingApi;
    private final Object eventSubscriber = new Object();

    @Override
    public void initialize(IServerAPI api) {
        pendingApi = api;
        ServerEventRegistry.GLOBAL_WAYPOINT_EVENT.subscribe(
                eventSubscriber, Jm2BmMod.MOD_ID, event -> Jm2BmMod.onJourneyMapChanged());
        ServerEventRegistry.GLOBAL_WAYPOINT_GROUP_EVENT.subscribe(
                eventSubscriber, Jm2BmMod.MOD_ID, event -> Jm2BmMod.onJourneyMapChanged());
        Jm2BmMod.onJourneyMapReady(api);
    }

    @Override
    public String getModId() {
        return Jm2BmMod.MOD_ID;
    }

    public static IServerAPI pendingApi() {
        return pendingApi;
    }
}
