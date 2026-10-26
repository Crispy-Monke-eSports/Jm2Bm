package se.jesper.jm2bm.sync;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import se.jesper.jm2bm.bluemap.BlueMapAdapter;
import se.jesper.jm2bm.bluemap.IconCatalog;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.config.ConfigManager;
import se.jesper.jm2bm.journeymap.JourneyMapAdapter;
import se.jesper.jm2bm.journeymap.JourneySnapshot;
import se.jesper.jm2bm.journeymap.SnapshotException;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyncCoordinator {
    private final Logger logger;
    private final ConfigManager configManager;
    private final JourneyMapAdapter journeyMap;
    private final BlueMapAdapter blueMap;
    private final Set<String> pendingCleanupSets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean dependencyDeferred = new AtomicBoolean();
    private volatile MinecraftServer server;
    private volatile SyncStatus status = SyncStatus.never();

    public SyncCoordinator(Logger logger, ConfigManager configManager, JourneyMapAdapter journeyMap,
                           BlueMapAdapter blueMap) {
        this.logger = logger;
        this.configManager = configManager;
        this.journeyMap = journeyMap;
        this.blueMap = blueMap;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
        scheduleIfPossible();
    }

    public void clearServer() {
        this.server = null;
    }

    public void trigger(String reason) {
        logger.debug("[Jm2Bm] Sync requested: {}", reason);
        dirty.set(true);
        scheduleIfPossible();
    }

    public void trigger(String reason, Set<String> cleanupSets) {
        pendingCleanupSets.addAll(cleanupSets);
        trigger(reason);
    }

    public SyncStatus status() {
        return status;
    }

    public boolean resumeDeferred(String reason) {
        boolean resumed = dependencyDeferred.getAndSet(false);
        if (resumed) trigger(reason);
        return resumed;
    }

    private void scheduleIfPossible() {
        MinecraftServer currentServer = server;
        if (currentServer == null || !dirty.get()) return;
        if (!scheduled.compareAndSet(false, true)) return;
        currentServer.execute(() -> drain(currentServer));
    }

    private void drain(MinecraftServer expectedServer) {
        try {
            do {
                dirty.set(false);
                reconcile(expectedServer);
            } while (dirty.get() && server == expectedServer);
        } finally {
            scheduled.set(false);
            if (dirty.get()) scheduleIfPossible();
        }
    }

    private void reconcile(MinecraftServer expectedServer) {
        if (server != expectedServer) return;
        if (!journeyMap.isConnected()) {
            dependencyDeferred.set(true);
            status = SyncStatus.failed("JourneyMap API is not connected; no BlueMap data was changed");
            return;
        }
        if (!blueMap.isConnected()) {
            dependencyDeferred.set(true);
            status = SyncStatus.failed("BlueMap API is not connected; no BlueMap data was changed");
            return;
        }

        BridgeConfig config = configManager.active();
        Set<UUID> requiredGroups = config.mappings().stream()
                .map(BridgeConfig.Mapping::journeyMapGroupUuid)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final JourneySnapshot snapshot;
        try {
            snapshot = journeyMap.snapshot(requiredGroups);
        } catch (SnapshotException e) {
            status = SyncStatus.failed(e.getMessage() + "; no BlueMap data was changed");
            logger.error("[Jm2Bm] Aborted sync before mutation: {}", e.getMessage(), e);
            return;
        }

        IconCatalog icons = IconCatalog.load(config, configManager, logger);
        try {
            blueMap.publishIcons(icons, config);
        } catch (IOException | RuntimeException e) {
            status = SyncStatus.failed("Icon publication failed: " + e.getMessage()
                    + "; no BlueMap markers were changed");
            logger.error("[Jm2Bm] Aborted sync before marker mutation because icon publication failed", e);
            deferIfBlueMapDisconnected();
            return;
        }

        Set<String> cleanup = Set.copyOf(pendingCleanupSets);
        final BlueMapAdapter.ReconcileResult result;
        try {
            result = blueMap.reconcile(expectedServer, config, snapshot, icons, cleanup);
        } catch (RuntimeException e) {
            status = SyncStatus.failed("BlueMap reconciliation failed unexpectedly: " + e.getMessage()
                    + "; pending cleanup was retained");
            logger.error("[Jm2Bm] BlueMap reconciliation failed unexpectedly; pending cleanup was retained", e);
            deferIfBlueMapDisconnected();
            return;
        }
        status = new SyncStatus(Instant.now(), result.success(), result.message(), result.created(), result.updated(),
                result.removed(), result.managedSets(), result.managedMaps(), result.markerCounts(),
                result.missingMarkerSets(), result.missingGroups());
        if (result.cleanupComplete()) pendingCleanupSets.removeAll(cleanup);

        if (result.success()) {
            logger.info("[Jm2Bm] Sync completed: created={} updated={} removed={} sets={} maps={}",
                    result.created(), result.updated(), result.removed(), result.managedSets(), result.managedMaps());
        } else {
            logger.error("[Jm2Bm] Sync completed with errors: created={} updated={} removed={} - {}",
                    result.created(), result.updated(), result.removed(), result.message());
        }
    }

    private void deferIfBlueMapDisconnected() {
        if (!blueMap.isConnected()) dependencyDeferred.set(true);
    }
}
