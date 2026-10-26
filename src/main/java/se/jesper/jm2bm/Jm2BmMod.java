package se.jesper.jm2bm;

import de.bluecolored.bluemap.api.BlueMapAPI;
import com.mojang.brigadier.CommandDispatcher;
import journeymap.api.v2.server.IServerAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
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

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Jm2BmMod {
    public static final String MOD_ID = "jm2bm";
    public static final Logger LOGGER = LoggerFactory.getLogger("Jm2Bm");
    private static volatile Jm2BmMod instance;

    private final JourneyMapAdapter journeyMap = new JourneyMapAdapter();
    private final BlueMapAdapter blueMap = new BlueMapAdapter(LOGGER);
    private final AtomicBoolean blueMapPreviouslyEnabled = new AtomicBoolean();
    private final AtomicBoolean blueMapListenersRegistered = new AtomicBoolean();
    private ConfigManager configManager;
    private SyncCoordinator coordinator;
    private Consumer<BlueMapAPI> blueMapEnableListener;
    private Consumer<BlueMapAPI> blueMapDisableListener;

    private Jm2BmMod() {
    }

    public static synchronized void initialize(Path configDirectory) {
        if (instance != null) return;

        Jm2BmMod mod = new Jm2BmMod();
        mod.initializeInternal(configDirectory);
        instance = mod;
    }

    private void initializeInternal(Path configDirectory) {
        configManager = new ConfigManager(LOGGER, configDirectory.resolve(MOD_ID));
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
            boolean resumed = coordinator.resumeDeferred("BlueMap became available after a deferred event");
            if (!resumed && (reload || configManager.active().syncOnStartup())) {
                coordinator.trigger(reload ? "BlueMap re-enabled" : "BlueMap enabled");
            }
        };
        blueMapDisableListener = blueMap::disconnect;
        registerBlueMapListeners();

        LOGGER.info("[Jm2Bm] Initialized with {} mapping(s)", initialConfig.mappings().size());
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        Jm2BmMod mod = requireInstance();
        BridgeCommands.register(dispatcher, mod.configManager, mod.coordinator, mod.journeyMap, mod.blueMap);
    }

    public static void onServerStarted(MinecraftServer server) {
        Jm2BmMod mod = requireInstance();
        mod.registerBlueMapListeners();
        JourneyMapServerPlugin.registerListeners();
        mod.coordinator.setServer(server);
        if (mod.configManager.active().syncOnStartup()) mod.coordinator.trigger("server startup");
    }

    public static void onServerStopping() {
        Jm2BmMod mod = instance;
        if (mod != null) mod.coordinator.clearServer();
    }

    public static void onServerStopped() {
        Jm2BmMod mod = instance;
        if (mod == null) return;
        mod.unregisterBlueMapListeners();
        JourneyMapServerPlugin.unregisterListeners();
    }

    public static void onJourneyMapReady(IServerAPI api) {
        Jm2BmMod mod = instance;
        if (mod == null) return;
        mod.journeyMap.connect(api);
        boolean resumed = mod.coordinator.resumeDeferred("JourneyMap became available after a deferred event");
        if (!resumed && mod.configManager.active().syncOnStartup()) {
            mod.coordinator.trigger("JourneyMap ready");
        }
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

    private void registerBlueMapListeners() {
        if (!blueMapListenersRegistered.compareAndSet(false, true)) return;
        BlueMapAPI.onEnable(blueMapEnableListener);
        BlueMapAPI.onDisable(blueMapDisableListener);
    }

    private void unregisterBlueMapListeners() {
        if (!blueMapListenersRegistered.compareAndSet(true, false)) return;
        BlueMapAPI.unregisterListener(blueMapEnableListener);
        BlueMapAPI.unregisterListener(blueMapDisableListener);
    }

    private static Jm2BmMod requireInstance() {
        Jm2BmMod mod = instance;
        if (mod == null) throw new IllegalStateException("Jm2Bm has not been initialized");
        return mod;
    }
}
