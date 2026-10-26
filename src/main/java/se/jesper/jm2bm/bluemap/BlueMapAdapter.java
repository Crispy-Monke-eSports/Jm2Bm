package se.jesper.jm2bm.bluemap;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.journeymap.JourneySnapshot;
import se.jesper.jm2bm.sync.DesiredMarker;
import se.jesper.jm2bm.sync.SyncPlanner;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BlueMapAdapter {
    private static final Vector2i ICON_ANCHOR = new Vector2i(25, 45);
    private final Logger logger;
    private volatile BlueMapAPI api;

    public BlueMapAdapter(Logger logger) {
        this.logger = logger;
    }

    public void connect(BlueMapAPI api) {
        this.api = Objects.requireNonNull(api);
    }

    public void disconnect(BlueMapAPI api) {
        if (this.api == api) this.api = null;
    }

    public boolean isConnected() {
        return api != null;
    }

    public List<MarkerSetRef> markerSets() throws IOException {
        BlueMapAPI current = api;
        if (current == null) throw new IOException("BlueMap API is not connected");
        Map<String, Set<String>> labelsById = new LinkedHashMap<>();
        try {
            for (BlueMapMap map : current.getMaps()) {
                if (map == null || map.getMarkerSets() == null) {
                    throw new IOException("BlueMap returned an incomplete map");
                }
                for (Map.Entry<String, MarkerSet> entry : map.getMarkerSets().entrySet()) {
                    String id = requireText(entry.getKey(), "marker-set ID");
                    if (entry.getValue() == null) throw new IOException("BlueMap returned an incomplete marker set");
                    String label = requireText(entry.getValue().getLabel(), "marker-set label");
                    labelsById.computeIfAbsent(id, ignored -> new HashSet<>()).add(label);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Could not list BlueMap marker sets: " + t.getMessage(), t);
        }
        List<MarkerSetRef> result = new ArrayList<>();
        labelsById.forEach((id, labels) -> labels.forEach(label -> result.add(new MarkerSetRef(id, label))));
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("Missing " + field);
        return value;
    }

    public ReconcileResult reconcile(
            MinecraftServer server,
            BridgeConfig config,
            JourneySnapshot snapshot,
            IconCatalog icons,
            Set<String> oneTimeCleanupSets
    ) {
        BlueMapAPI current = api;
        if (current == null) return ReconcileResult.failed("BlueMap API is not connected");

        Set<String> targetSets = new HashSet<>(config.markerSetIds());
        targetSets.addAll(oneTimeCleanupSets);
        Set<java.util.UUID> missingGroups = new HashSet<>(config.mappings().stream()
                .map(BridgeConfig.Mapping::journeyMapGroupUuid).toList());
        missingGroups.removeAll(snapshot.presentGroups());

        Counters total = new Counters();
        Map<String, Integer> markerCounts = new LinkedHashMap<>();
        Set<String> missingMarkerSets = new HashSet<>();
        List<String> errors = new ArrayList<>();
        Collection<MapContext> maps = findMaps(server, current);

        for (MapContext context : maps) {
            for (String setId : targetSets) {
                MarkerSet markerSet = context.map().getMarkerSets().get(setId);
                if (markerSet == null) {
                    String key = context.map().getId() + "/" + setId;
                    missingMarkerSets.add(key);
                    logger.error("[Jm2Bm] Marker set '{}' does not exist on BlueMap map '{}'",
                            setId, context.map().getId());
                    continue;
                }
                try {
                    Map<String, DesiredMarker> desired = oneTimeCleanupSets.contains(setId)
                            && !config.markerSetIds().contains(setId)
                            ? Map.of()
                            : desiredFor(config, snapshot, icons, context.dimension(), setId);
                    Counters destination = apply(context.map(), markerSet, desired);
                    total.add(destination);
                    markerCounts.put(context.map().getId() + "/" + setId, desired.size());
                } catch (Exception e) {
                    String message = "map " + context.map().getId() + ", set " + setId + ": " + e.getMessage();
                    errors.add(message);
                    logger.error("[Jm2Bm] Could not reconcile {}", message, e);
                }
            }
        }
        boolean success = errors.isEmpty() && missingMarkerSets.isEmpty();
        String message = !errors.isEmpty()
                ? "Partial failure: " + String.join("; ", errors)
                : !missingMarkerSets.isEmpty()
                ? "Completed with missing marker sets: " + String.join(", ", missingMarkerSets)
                : "OK";
        return new ReconcileResult(success, errors.isEmpty(), total.created, total.updated, total.removed,
                targetSets.size(), maps.size(), markerCounts, missingMarkerSets, missingGroups,
                message);
    }

    private Collection<MapContext> findMaps(MinecraftServer server, BlueMapAPI api) {
        Map<String, MapContext> result = new LinkedHashMap<>();
        server.getAllLevels().forEach(level -> api.getWorld(level.dimension()).ifPresent(world -> {
            String dimension = level.dimension().identifier().toString();
            for (BlueMapMap map : world.getMaps()) {
                result.put(map.getId(), new MapContext(map, dimension));
            }
        }));
        return result.values();
    }

    private Map<String, DesiredMarker> desiredFor(
            BridgeConfig config,
            JourneySnapshot snapshot,
            IconCatalog icons,
            String dimension,
            String setId
    ) throws SyncPlanner.PlanningException {
        Map<java.util.UUID, String> iconAssets = new HashMap<>();
        for (BridgeConfig.Mapping mapping : config.mappings()) {
            IconCatalog.IconAsset icon = icons.forGroup(mapping.journeyMapGroupUuid());
            if (icon != null) iconAssets.put(mapping.journeyMapGroupUuid(), icon.assetName());
        }
        return SyncPlanner.plan(config, snapshot, iconAssets, dimension, setId);
    }

    private Counters apply(BlueMapMap map, MarkerSet markerSet, Map<String, DesiredMarker> desired)
            throws IOException {
        Map<String, String> urls = publishAssets(map, desired.values());
        Map<String, POIMarker> candidates = new HashMap<>();
        for (DesiredMarker marker : desired.values()) {
            POIMarker poi;
            if (marker.iconAssetName() == null) {
                poi = new POIMarker(marker.label(), new Vector3d(marker.x(), marker.y(), marker.z()));
            } else {
                poi = new POIMarker(marker.label(), new Vector3d(marker.x(), marker.y(), marker.z()),
                        urls.get(marker.iconAssetName()), ICON_ANCHOR);
            }
            poi.setDetail(marker.label());
            candidates.put(marker.id(), poi);
        }

        Counters counters = new Counters();
        Map<String, Marker> actual = markerSet.getMarkers();
        for (Map.Entry<String, POIMarker> entry : candidates.entrySet()) {
            Marker existing = actual.get(entry.getKey());
            POIMarker candidate = entry.getValue();
            if (existing == null) {
                actual.put(entry.getKey(), candidate);
                counters.created++;
            } else if (!(existing instanceof POIMarker poi) || !poi.equals(candidate)
                    || !Objects.equals(poi.getDetail(), candidate.getDetail())) {
                actual.put(entry.getKey(), candidate);
                counters.updated++;
            }
        }
        for (String markerId : new ArrayList<>(actual.keySet())) {
            if (!candidates.containsKey(markerId)) {
                actual.remove(markerId);
                counters.removed++;
            }
        }
        return counters;
    }

    private Map<String, String> publishAssets(BlueMapMap map, Collection<DesiredMarker> desired) throws IOException {
        Map<String, String> urls = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (DesiredMarker marker : desired) {
            if (marker.iconAssetName() != null) names.add(marker.iconAssetName());
        }
        for (String name : names) {
            if (!map.getAssetStorage().assetExists(name)) {
                // The matching bytes are supplied by the caller through publishAsset below.
                // This branch is completed there before reconciliation starts.
                throw new IOException("icon asset was not prepared: " + name);
            }
            urls.put(name, map.getAssetStorage().getAssetUrl(name));
        }
        return urls;
    }

    public void publishIcons(IconCatalog catalog, BridgeConfig config) throws IOException {
        BlueMapAPI current = api;
        if (current == null) throw new IOException("BlueMap API is not connected");
        Set<IconCatalog.IconAsset> assets = new HashSet<>();
        for (BridgeConfig.Mapping mapping : config.mappings()) {
            IconCatalog.IconAsset asset = catalog.forGroup(mapping.journeyMapGroupUuid());
            if (asset != null) assets.add(asset);
        }
        for (BlueMapMap map : current.getMaps()) {
            for (IconCatalog.IconAsset asset : assets) {
                if (map.getAssetStorage().assetExists(asset.assetName())) continue;
                try (OutputStream output = map.getAssetStorage().writeAsset(asset.assetName())) {
                    output.write(asset.bytes());
                }
            }
        }
    }

    private record MapContext(BlueMapMap map, String dimension) {
    }

    public record MarkerSetRef(String id, String label) {
    }

    private static final class Counters {
        int created;
        int updated;
        int removed;

        void add(Counters other) {
            created += other.created;
            updated += other.updated;
            removed += other.removed;
        }
    }

    public record ReconcileResult(
            boolean success,
            boolean cleanupComplete,
            int created,
            int updated,
            int removed,
            int managedSets,
            int managedMaps,
            Map<String, Integer> markerCounts,
            Set<String> missingMarkerSets,
            Set<java.util.UUID> missingGroups,
            String message
    ) {
        public ReconcileResult {
            markerCounts = Map.copyOf(markerCounts);
            missingMarkerSets = Set.copyOf(missingMarkerSets);
            missingGroups = Set.copyOf(missingGroups);
        }

        static ReconcileResult failed(String message) {
            return new ReconcileResult(false, false, 0, 0, 0, 0, 0, Map.of(), Set.of(), Set.of(), message);
        }
    }
}
