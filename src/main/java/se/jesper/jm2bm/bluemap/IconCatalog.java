package se.jesper.jm2bm.bluemap;

import org.slf4j.Logger;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.config.ConfigException;
import se.jesper.jm2bm.config.ConfigManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

public final class IconCatalog {
    private final Map<MappingKey, IconAsset> byMapping;

    private IconCatalog(Map<MappingKey, IconAsset> byMapping) {
        this.byMapping = Map.copyOf(byMapping);
    }

    public static IconCatalog load(BridgeConfig config, ConfigManager configManager, Logger logger) {
        IconAsset defaultAsset = loadOne(config.defaultIcon(), configManager, logger, "defaultIcon");
        Map<MappingKey, IconAsset> result = new HashMap<>();
        for (BridgeConfig.Mapping mapping : config.mappings()) {
            IconAsset asset = null;
            if (mapping.icon() != null) {
                asset = loadOne(mapping.icon(), configManager, logger,
                        "group " + mapping.journeyMapGroupUuid());
            }
            if (asset == null) asset = defaultAsset;
            result.put(new MappingKey(mapping.journeyMapGroupUuid(), mapping.blueMapMarkerSet()), asset);
        }
        return new IconCatalog(result);
    }

    public IconAsset forMapping(UUID groupId, String markerSetId) {
        return byMapping.get(new MappingKey(groupId, markerSetId));
    }

    private static IconAsset loadOne(String name, ConfigManager configManager, Logger logger, String owner) {
        try {
            Path path = configManager.resolveIcon(name);
            if (!Files.isRegularFile(path)) {
                logger.warn("[Jm2Bm] Icon '{}' for {} is missing", name, owner);
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 12);
            String extension = name.substring(name.lastIndexOf('.')).toLowerCase();
            return new IconAsset("jm2bm-" + hash + extension, bytes);
        } catch (IOException | NoSuchAlgorithmException | ConfigException e) {
            logger.warn("[Jm2Bm] Could not load icon '{}' for {}; using fallback: {}", name, owner, e.getMessage());
            return null;
        }
    }

    public record IconAsset(String assetName, byte[] bytes) {
        public IconAsset {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record MappingKey(UUID groupId, String markerSetId) {
    }
}
