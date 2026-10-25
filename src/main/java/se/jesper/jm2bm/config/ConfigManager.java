package se.jesper.jm2bm.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ConfigManager {
    private static final String DEFAULT_CONFIG = """
            {
              "syncOnStartup": true,
              "defaultIcon": "default.svg",
              "mappings": []
            }
            """;

    private final Logger logger;
    private final Path directory;
    private final Path iconDirectory;
    private final Path configFile;
    private volatile BridgeConfig active;

    public ConfigManager(Logger logger) {
        this(logger, FabricLoader.getInstance().getConfigDir().resolve("jm2bm"));
    }

    ConfigManager(Logger logger, Path directory) {
        this.logger = Objects.requireNonNull(logger);
        this.directory = directory.toAbsolutePath().normalize();
        this.iconDirectory = this.directory.resolve("icons");
        this.configFile = this.directory.resolve("config.json");
    }

    public BridgeConfig initialize() throws ConfigException {
        try {
            Files.createDirectories(iconDirectory);
            if (Files.notExists(configFile)) {
                Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
                logger.info("[Jm2Bm] Created default configuration at {}", configFile);
            }
            Path defaultIcon = iconDirectory.resolve("default.svg");
            if (Files.notExists(defaultIcon)) {
                try (var input = ConfigManager.class.getResourceAsStream("/assets/jm2bm/default.svg")) {
                    if (input == null) throw new IOException("Bundled default icon is missing");
                    Files.copy(input, defaultIcon);
                }
            }
            active = readAndValidate();
            return active;
        } catch (IOException e) {
            throw new ConfigException("Could not initialize " + directory + ": " + e.getMessage(), e);
        }
    }

    public synchronized ReloadResult reload() throws ConfigException {
        BridgeConfig candidate = readAndValidate();
        BridgeConfig previous = active;
        Set<String> removedSets = new HashSet<>();
        if (previous != null) {
            removedSets.addAll(previous.markerSetIds());
            removedSets.removeAll(candidate.markerSetIds());
        }
        active = candidate;
        return new ReloadResult(previous, candidate, Set.copyOf(removedSets));
    }

    public BridgeConfig active() {
        BridgeConfig config = active;
        if (config == null) throw new IllegalStateException("Configuration has not been initialized");
        return config;
    }

    public Path iconDirectory() {
        return iconDirectory;
    }

    public Path resolveIcon(String icon) throws ConfigException {
        validateIconPath(icon, "icon");
        Path resolved = iconDirectory.resolve(icon).normalize();
        if (!resolved.startsWith(iconDirectory)) {
            throw new ConfigException("Icon path escapes the icons directory: " + icon);
        }
        return resolved;
    }

    private BridgeConfig readAndValidate() throws ConfigException {
        final JsonObject root;
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new ConfigException("config.json must contain a JSON object");
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            throw new ConfigException("Could not read config.json: " + e.getMessage(), e);
        }

        boolean syncOnStartup = true;
        if (root.has("syncOnStartup")) {
            JsonElement value = root.get("syncOnStartup");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw new ConfigException("syncOnStartup must be a boolean");
            }
            syncOnStartup = value.getAsBoolean();
        }
        String defaultIcon = root.has("defaultIcon")
                ? requiredString(root.get("defaultIcon"), "defaultIcon")
                : "default.svg";
        validateIconPath(defaultIcon, "defaultIcon");

        List<BridgeConfig.Mapping> mappings = new ArrayList<>();
        Set<String> uniquePairs = new HashSet<>();
        JsonArray rawMappings;
        if (!root.has("mappings")) {
            rawMappings = new JsonArray();
        } else if (!root.get("mappings").isJsonArray()) {
            throw new ConfigException("mappings must be an array");
        } else {
            rawMappings = root.getAsJsonArray("mappings");
        }
        for (int i = 0; i < rawMappings.size(); i++) {
            JsonElement element = rawMappings.get(i);
            if (!element.isJsonObject()) throw new ConfigException("mappings[" + i + "] must be an object");
            JsonObject mapping = element.getAsJsonObject();
            UUID groupId;
            try {
                groupId = UUID.fromString(requiredString(mapping.get("journeyMapGroupUuid"),
                        "mappings[" + i + "].journeyMapGroupUuid"));
            } catch (IllegalArgumentException e) {
                throw new ConfigException("mappings[" + i + "].journeyMapGroupUuid is not a UUID", e);
            }
            String markerSet = requiredString(mapping.get("blueMapMarkerSet"),
                    "mappings[" + i + "].blueMapMarkerSet");
            String icon = mapping.has("icon") && !mapping.get("icon").isJsonNull()
                    ? requiredString(mapping.get("icon"), "mappings[" + i + "].icon")
                    : null;
            if (icon != null) validateIconPath(icon, "mappings[" + i + "].icon");

            String pair = groupId + "\u0000" + markerSet;
            if (!uniquePairs.add(pair)) {
                throw new ConfigException("Duplicate mapping for group " + groupId + " and marker set '"
                        + markerSet + "'");
            }
            mappings.add(new BridgeConfig.Mapping(groupId, markerSet, icon));
        }
        return new BridgeConfig(syncOnStartup, defaultIcon, mappings);
    }

    private static String requiredString(JsonElement element, String field) throws ConfigException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new ConfigException(field + " must be a string");
        }
        return requireText(element.getAsString(), field);
    }

    private static String requireText(String value, String field) throws ConfigException {
        if (value == null || value.isBlank()) throw new ConfigException(field + " must not be blank");
        return value.trim();
    }

    private static void validateIconPath(String value, String field) throws ConfigException {
        if (value == null || value.isBlank()) throw new ConfigException(field + " must not be blank");
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException e) {
            throw new ConfigException(field + " is not a valid path", e);
        }
        if (path.isAbsolute() || value.contains("..") || path.normalize().startsWith("..")) {
            throw new ConfigException(field + " must stay below the icons directory");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".svg")) {
            throw new ConfigException(field + " must reference a .png or .svg file");
        }
    }

    public record ReloadResult(BridgeConfig previous, BridgeConfig current, Set<String> removedMarkerSets) {
    }

}
