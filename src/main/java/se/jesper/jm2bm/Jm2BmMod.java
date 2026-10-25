package se.jesper.jm2bm;

import de.bluecolored.bluemap.api.BlueMapAPI;
import journeymap.api.v2.server.IServerAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.jesper.jm2bm.bluemap.BlueMapAdapter;
import se.jesper.jm2bm.command.BridgeCommands;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.config.ConfigException;
import se.jesper.jm2bm.config.ConfigManager;
import se.jesper.jm2bm.journeymap.JourneyMapAdapter;
import se.jesper.jm2bm.journeymap.JourneyMapServerPlugin;
import se.jesper.jm2bm.sync.SyncCoordinator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Jm2BmMod implements ModInitializer {
    public static final String MOD_ID = "jm2bm";
    public static final Logger LOGGER = LoggerFactory.getLogger("Jm2Bm");
    private static volatile Jm2BmMod instance;

    private final JourneyMapAdapter journeyMap = new JourneyMapAdapter();
    private final BlueMapAdapter blueMap = new BlueMapAdapter(LOGGER);
    private final AtomicBoolean blueMapPreviouslyEnabled = new AtomicBoolean();
    private ConfigManager configManager;
    private SyncCoordinator coordinator;
    private Consumer<BlueMapAPI> blueMapEnableListener;
    private Consumer<BlueMapAPI> blueMapDisableListener;

    @Override
    public void onInitialize() {
        instance = this;
        configManager = new ConfigManager(LOGGER);
        final BridgeConfig initialConfig;
        try {
            initialConfig = configManager.initialize();
        } catch (ConfigException e) {
            throw new IllegalStateException("[Jm2Bm] Cannot load initial configuration safely", e);
        }
        coordinator = new SyncCoordinator(LOGGER, configManager, journeyMap, blueMap);

        IServerAPI pendingJourneyMap = JourneyMapServerPlugin.pendingApi();
        if (pendingJourneyMap != null) journeyMap.connect(pendingJourneyMap);

        blueMapEnableListener = api -> {
            blueMap.connect(api);
            boolean reload = blueMapPreviouslyEnabled.getAndSet(true);
            coordinator.resumeDeferred("BlueMap became available after a deferred event");
            if (reload || configManager.active().syncOnStartup()) {
                coordinator.trigger(reload ? "BlueMap re-enabled" : "BlueMap enabled");
            }
        };
        blueMapDisableListener = blueMap::disconnect;
        BlueMapAPI.onEnable(blueMapEnableListener);
        BlueMapAPI.onDisable(blueMapDisableListener);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BridgeCommands.register(dispatcher, configManager, coordinator));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            coordinator.setServer(server);
            if (initialConfig.syncOnStartup()) coordinator.trigger("server startup");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> coordinator.clearServer());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BlueMapAPI.unregisterListener(blueMapEnableListener);
            BlueMapAPI.unregisterListener(blueMapDisableListener);
        });

        LOGGER.info("[Jm2Bm] Initialized with {} mapping(s)", initialConfig.mappings().size());
    }

    public static void onJourneyMapReady(IServerAPI api) {
        Jm2BmMod mod = instance;
        if (mod == null) return;
        mod.journeyMap.connect(api);
        mod.coordinator.resumeDeferred("JourneyMap became available after a deferred event");
        if (mod.configManager.active().syncOnStartup()) mod.coordinator.trigger("JourneyMap ready");
    }

    public static void onJourneyMapChanged() {
        Jm2BmMod mod = instance;
        if (mod != null) mod.coordinator.trigger("JourneyMap global waypoint event");
    }

    public static boolean journeyMapConnected() {
        Jm2BmMod mod = instance;
        return mod != null && mod.journeyMap.isConnected();
    }

    public static boolean blueMapConnected() {
        Jm2BmMod mod = instance;
        return mod != null && mod.blueMap.isConnected();
    }
}
