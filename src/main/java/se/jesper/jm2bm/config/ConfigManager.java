package se.jesper.jm2bm.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ConfigManager {
    private static final Set<String> ROOT_FIELDS = Set.of("syncOnStartup", "defaultIcon", "mappings");
    private static final Set<String> MAPPING_FIELDS = Set.of(
            "journeyMapGroupUuid", "blueMapMarkerSet", "icon");
    private final Logger logger;
    private final Path directory;
    private final Path iconDirectory;
    private final Path configFile;
    private volatile BridgeConfig active;

    public ConfigManager(Logger logger, Path directory) {
        this.logger = Objects.requireNonNull(logger);
        this.directory = directory.toAbsolutePath().normalize();
        this.iconDirectory = this.directory.resolve("icons");
        this.configFile = this.directory.resolve("config.yaml");
    }

    public BridgeConfig initialize() throws ConfigException {
        try {
            Files.createDirectories(iconDirectory);
            if (Files.notExists(configFile)) {
                write(new BridgeConfig(true, "default.svg", List.of()));
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
        Set<String> removedSets = removedSets(previous, candidate);
        active = candidate;
        return new ReloadResult(previous, candidate, removedSets);
    }

    public synchronized MutationResult link(UUID groupId, String markerSet) throws ConfigException {
        Objects.requireNonNull(groupId, "groupId");
        markerSet = requireText(markerSet, "blueMapMarkerSet");
        BridgeConfig previouslyActive = active();
        BridgeConfig source = readAndValidate();
        for (BridgeConfig.Mapping mapping : source.mappings()) {
            if (mapping.journeyMapGroupUuid().equals(groupId)
                    && mapping.blueMapMarkerSet().equals(markerSet)) {
                active = source;
                return new MutationResult(source, removedSets(previouslyActive, source),
                        !source.equals(previouslyActive));
            }
        }
        List<BridgeConfig.Mapping> mappings = new ArrayList<>(source.mappings());
        mappings.add(new BridgeConfig.Mapping(groupId, markerSet, null));
        BridgeConfig candidate = new BridgeConfig(source.syncOnStartup(), source.defaultIcon(), mappings);
        write(candidate);
        active = candidate;
        return new MutationResult(candidate, removedSets(previouslyActive, candidate), true);
    }

    public synchronized MutationResult unlink(UUID groupId, String markerSet) throws ConfigException {
        Objects.requireNonNull(groupId, "groupId");
        markerSet = requireText(markerSet, "blueMapMarkerSet");
        BridgeConfig previouslyActive = active();
        BridgeConfig source = readAndValidate();
        String selectedSet = markerSet;
        List<BridgeConfig.Mapping> mappings = source.mappings().stream()
                .filter(mapping -> !(mapping.journeyMapGroupUuid().equals(groupId)
                        && mapping.blueMapMarkerSet().equals(selectedSet)))
                .toList();
        if (mappings.size() == source.mappings().size()) {
            active = source;
            return new MutationResult(source, removedSets(previouslyActive, source),
                    !source.equals(previouslyActive));
        }
        BridgeConfig candidate = new BridgeConfig(source.syncOnStartup(), source.defaultIcon(), mappings);
        write(candidate);
        active = candidate;
        return new MutationResult(candidate, removedSets(previouslyActive, candidate), true);
    }

    public BridgeConfig active() {
        BridgeConfig config = active;
        if (config == null) throw new IllegalStateException("Configuration has not been initialized");
        return config;
    }

    public synchronized BridgeConfig configured() throws ConfigException {
        return readAndValidate();
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
        final Object document;
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            document = new Yaml(new SafeConstructor(loaderOptions)).load(reader);
        } catch (IOException | YAMLException e) {
            throw new ConfigException("Could not read config.yaml: " + e.getMessage(), e);
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new ConfigException("config.yaml must contain a YAML mapping");
        }
        rejectUnknownFields(root, ROOT_FIELDS, "config.yaml");

        boolean syncOnStartup = optionalBoolean(root, "syncOnStartup", true);
        String defaultIcon = optionalString(root, "defaultIcon", "default.svg");
        validateIconPath(defaultIcon, "defaultIcon");
        if (!root.containsKey("mappings")) {
            throw new ConfigException("mappings is required and must be a list");
        }
        Object rawMappings = root.get("mappings");
        if (!(rawMappings instanceof List<?> entries)) {
            throw new ConfigException("mappings must be a list");
        }
        List<BridgeConfig.Mapping> mappings = new ArrayList<>();
        Set<String> uniquePairs = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof Map<?, ?> mapping)) {
                throw new ConfigException("mappings[" + i + "] must be a mapping");
            }
            rejectUnknownFields(mapping, MAPPING_FIELDS, "mappings[" + i + "]");
            UUID groupId;
            try {
                groupId = UUID.fromString(requiredString(mapping, "journeyMapGroupUuid", "mappings[" + i + "]"));
            } catch (IllegalArgumentException e) {
                throw new ConfigException("mappings[" + i + "].journeyMapGroupUuid is not a UUID", e);
            }
            String markerSet = requiredString(mapping, "blueMapMarkerSet", "mappings[" + i + "]");
            String icon = mapping.get("icon") == null ? null
                    : requireTextValue(mapping.get("icon"), "mappings[" + i + "].icon");
            if (icon != null) validateIconPath(icon, "mappings[" + i + "].icon");
            String pair = groupId + "\u0000" + markerSet;
            if (!uniquePairs.add(pair)) {
                throw new ConfigException("Duplicate mapping for group " + groupId
                        + " and marker set '" + markerSet + "'");
            }
            mappings.add(new BridgeConfig.Mapping(groupId, markerSet, icon));
        }
        return new BridgeConfig(syncOnStartup, defaultIcon, mappings);
    }

    private void write(BridgeConfig config) throws ConfigException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("syncOnStartup", config.syncOnStartup());
        root.put("defaultIcon", config.defaultIcon());
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (BridgeConfig.Mapping mapping : config.mappings()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("journeyMapGroupUuid", mapping.journeyMapGroupUuid().toString());
            entry.put("blueMapMarkerSet", mapping.blueMapMarkerSet());
            if (mapping.icon() != null) entry.put("icon", mapping.icon());
            mappings.add(entry);
        }
        root.put("mappings", mappings);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "config.yaml.", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new Yaml(options).dump(root, writer);
            }
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | YAMLException e) {
            throw new ConfigException("Could not write config.yaml: " + e.getMessage(), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup after a failed write.
                }
            }
        }
    }

    private static Set<String> removedSets(BridgeConfig previous, BridgeConfig current) {
        if (previous == null) return Set.of();
        Set<String> removed = new HashSet<>(previous.markerSetIds());
        removed.removeAll(current.markerSetIds());
        return Set.copyOf(removed);
    }

    private static boolean optionalBoolean(Map<?, ?> root, String key, boolean fallback) throws ConfigException {
        Object value = root.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new ConfigException(key + " must be a boolean");
        return result;
    }

    private static String optionalString(Map<?, ?> root, String key, String fallback) throws ConfigException {
        Object value = root.get(key);
        return value == null ? fallback : requireTextValue(value, key);
    }

    private static String requiredString(Map<?, ?> root, String key, String prefix) throws ConfigException {
        if (!root.containsKey(key)) throw new ConfigException(prefix + "." + key + " must be a string");
        return requireTextValue(root.get(key), prefix + "." + key);
    }

    private static String requireTextValue(Object value, String field) throws ConfigException {
        if (!(value instanceof String text)) throw new ConfigException(field + " must be a string");
        return requireText(text, field);
    }

    private static String requireText(String value, String field) throws ConfigException {
        if (value == null || value.isBlank()) throw new ConfigException(field + " must not be blank");
        return value.trim();
    }

    private static void rejectUnknownFields(Map<?, ?> values, Set<String> allowed, String location)
            throws ConfigException {
        for (Object key : values.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new ConfigException(location + " contains unknown field '" + key + "'");
            }
        }
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

    public record MutationResult(BridgeConfig current, Set<String> removedMarkerSets, boolean changed) {
    }
}
