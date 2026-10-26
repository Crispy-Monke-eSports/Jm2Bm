package se.jesper.jm2bm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    private static final UUID GROUP = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @TempDir
    Path directory;

    @Test
    void createsSafeYamlConfigurationOnFirstRun() throws Exception {
        ConfigManager manager = manager();

        BridgeConfig config = manager.initialize();

        assertEquals(0, config.mappings().size());
        assertTrue(config.syncOnStartup());
        assertTrue(Files.isRegularFile(directory.resolve("config.yaml")));
        assertFalse(Files.exists(directory.resolve("config.json")));
        assertTrue(Files.isRegularFile(directory.resolve("icons/default.svg")));
    }

    @Test
    void linkPersistsUuidAndInternalMarkerSetIdImmediately() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();

        ConfigManager.MutationResult result = manager.link(GROUP, "internal-towns");

        assertTrue(result.changed());
        String yaml = Files.readString(directory.resolve("config.yaml"));
        assertTrue(yaml.contains("journeyMapGroupUuid: " + GROUP));
        assertTrue(yaml.contains("blueMapMarkerSet: internal-towns"));
        assertFalse(yaml.contains("Friendly group"));
        assertEquals(GROUP, manager.reload().current().mappings().getFirst().journeyMapGroupUuid());
    }

    @Test
    void duplicateLinkDoesNotRewriteConfiguration() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        manager.link(GROUP, "towns");
        String before = Files.readString(directory.resolve("config.yaml"));

        ConfigManager.MutationResult result = manager.link(GROUP, "towns");

        assertFalse(result.changed());
        assertEquals(before, Files.readString(directory.resolve("config.yaml")));
    }

    @Test
    void commandMutationUsesCurrentYamlAsItsSourceOfTruth() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        UUID manuallyAdded = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        Files.writeString(directory.resolve("config.yaml"), yamlWithMappings("""
                - journeyMapGroupUuid: 123e4567-e89b-42d3-a456-426614174000
                  blueMapMarkerSet: manually-added
                """));

        manager.link(GROUP, "command-added");

        BridgeConfig persisted = manager.reload().current();
        assertEquals(2, persisted.mappings().size());
        assertTrue(persisted.mappings().stream().anyMatch(mapping ->
                mapping.journeyMapGroupUuid().equals(manuallyAdded)
                        && mapping.blueMapMarkerSet().equals("manually-added")));
    }

    @Test
    void unlinkPersistsAndReportsSetWhoseLastMappingWasRemoved() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        manager.link(GROUP, "towns");

        ConfigManager.MutationResult result = manager.unlink(GROUP, "towns");

        assertTrue(result.changed());
        assertEquals(java.util.Set.of("towns"), result.removedMarkerSets());
        assertTrue(manager.reload().current().mappings().isEmpty());
    }

    @Test
    void rejectsDuplicatePairAndKeepsLastValidConfig() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        manager.link(GROUP, "towns");
        BridgeConfig valid = manager.active();
        Files.writeString(directory.resolve("config.yaml"), yamlWithMappings("""
                - journeyMapGroupUuid: 550e8400-e29b-41d4-a716-446655440000
                  blueMapMarkerSet: towns
                - journeyMapGroupUuid: 550e8400-e29b-41d4-a716-446655440000
                  blueMapMarkerSet: towns
                """));

        assertThrows(ConfigException.class, manager::reload);
        assertSame(valid, manager.active());
    }

    @Test
    void rejectsIconsOutsideTheIconDirectory() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        Files.writeString(directory.resolve("config.yaml"), """
                syncOnStartup: true
                defaultIcon: ../secret.png
                mappings: []
                """);

        assertThrows(ConfigException.class, manager::reload);
    }

    @Test
    void rejectsIconSymbolicLinksOutsideTheIconDirectory() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        Path outside = Files.createTempFile(directory.getParent(), "outside-", ".svg");
        Files.writeString(outside, "<svg>outside</svg>");
        Files.createSymbolicLink(directory.resolve("icons/linked.svg"), outside);

        assertThrows(ConfigException.class, () -> manager.resolveIcon("linked.svg"));
    }

    @Test
    void rejectsWrongYamlFieldTypes() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        Files.writeString(directory.resolve("config.yaml"), """
                syncOnStartup: "false"
                defaultIcon: default.svg
                mappings: []
                """);

        assertThrows(ConfigException.class, manager::reload);
    }

    @Test
    void rejectsMissingOrNullMappingsWithoutReplacingActiveConfig() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        manager.link(GROUP, "towns");
        BridgeConfig valid = manager.active();

        Files.writeString(directory.resolve("config.yaml"), """
                syncOnStartup: true
                defaultIcon: default.svg
                mappings:
                """);
        assertThrows(ConfigException.class, manager::reload);
        assertSame(valid, manager.active());

        Files.writeString(directory.resolve("config.yaml"), """
                syncOnStartup: true
                defaultIcon: default.svg
                """);
        assertThrows(ConfigException.class, manager::reload);
        assertSame(valid, manager.active());
    }

    @Test
    void rejectsMisspelledAndUnknownFields() throws Exception {
        ConfigManager manager = manager();
        manager.initialize();
        Files.writeString(directory.resolve("config.yaml"), """
                syncOnStartup: true
                defaultIcon: default.svg
                mapping: []
                mappings: []
                """);
        assertThrows(ConfigException.class, manager::reload);

        Files.writeString(directory.resolve("config.yaml"), yamlWithMappings("""
                - journeyMapGroupUuid: 550e8400-e29b-41d4-a716-446655440000
                  blueMapMarkerSet: towns
                  icno: town.svg
                """));
        assertThrows(ConfigException.class, manager::reload);
    }

    private ConfigManager manager() {
        return new ConfigManager(LoggerFactory.getLogger("test"), directory);
    }

    private static String yamlWithMappings(String mappings) {
        return """
                syncOnStartup: true
                defaultIcon: default.svg
                mappings:
                %s
                """.formatted(mappings.indent(2));
    }
}
