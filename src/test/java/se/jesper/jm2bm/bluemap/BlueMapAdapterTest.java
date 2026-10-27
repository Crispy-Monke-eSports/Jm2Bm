package se.jesper.jm2bm.bluemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.config.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueMapAdapterTest {
    @TempDir
    Path directory;

    @Test
    void cleanupOnlyCompletesWhenEveryDestinationWasAvailableAndSuccessful() {
        assertTrue(BlueMapAdapter.cleanupComplete(List.of(), Set.of()));
        assertFalse(BlueMapAdapter.cleanupComplete(List.of(), Set.of("removed-set")));
        assertFalse(BlueMapAdapter.cleanupComplete(List.of("write failed"), Set.of()));
    }

    @Test
    void markerSetIsMissingOnlyWhenItExistsOnNoMap() {
        Set<String> missing = BlueMapAdapter.missingMarkerSets(
                Set.of("buildings", "farms", "cities", "removed"),
                List.of(Set.of("buildings", "farms"), Set.of("farms"), Set.of("cities")));

        assertEquals(Set.of("removed"), missing);
    }

    @Test
    void keepsDifferentIconsForTheSameGroupInDifferentMarkerSets() throws Exception {
        UUID group = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.writeString(manager.iconDirectory().resolve("first.svg"), "<svg>first</svg>");
        Files.writeString(manager.iconDirectory().resolve("second.svg"), "<svg>second</svg>");
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(group, "first-set", "first.svg"),
                new BridgeConfig.Mapping(group, "second-set", "second.svg")));

        IconCatalog catalog = IconCatalog.load(config, manager, LoggerFactory.getLogger("test"));

        assertNotEquals(catalog.forMapping(group, "first-set").assetName(),
                catalog.forMapping(group, "second-set").assetName());
    }

    @Test
    void usesBlueMapDefaultWhenConfiguredAndFallbackIconsAreMissing() throws Exception {
        UUID group = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.delete(manager.iconDirectory().resolve("default.svg"));
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(group, "towns", "missing.svg")));

        IconCatalog catalog = IconCatalog.load(config, manager, LoggerFactory.getLogger("test"));

        assertNull(catalog.forMapping(group, "towns"));
    }

    @Test
    void onlyPublishesIconsForMarkerSetsAvailableOnTheMap() throws Exception {
        UUID group = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.writeString(manager.iconDirectory().resolve("town.svg"), "<svg>town</svg>");
        Files.writeString(manager.iconDirectory().resolve("shop.svg"), "<svg>shop</svg>");
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(group, "towns", "town.svg"),
                new BridgeConfig.Mapping(group, "shops", "shop.svg")));
        IconCatalog catalog = IconCatalog.load(config, manager, LoggerFactory.getLogger("test"));

        Map<String, IconCatalog.IconAsset> assets = BlueMapAdapter.assetsForMarkerSets(
                catalog, config, Set.of("towns"));

        assertEquals(Set.of(catalog.forMapping(group, "towns").assetName()), assets.keySet());
    }
}
