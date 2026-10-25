package se.jesper.jm2bm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigManagerTest {
    @TempDir
    Path directory;

    @Test
    void createsSafeEmptyConfigurationOnFirstRun() throws Exception {
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);

        BridgeConfig config = manager.initialize();

        assertEquals(0, config.mappings().size());
        assertEquals(true, config.syncOnStartup());
        assertEquals(true, Files.isRegularFile(directory.resolve("icons/default.svg")));
    }

    @Test
    void rejectsDuplicatePairAndKeepsLastValidConfig() throws Exception {
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.writeString(directory.resolve("config.json"), configWithMappings("""
                {"journeyMapGroupUuid":"550e8400-e29b-41d4-a716-446655440000",
                 "blueMapMarkerSet":"towns","icon":"town.svg"}
                """));
        BridgeConfig valid = manager.reload().current();

        Files.writeString(directory.resolve("config.json"), configWithMappings("""
                {"journeyMapGroupUuid":"550e8400-e29b-41d4-a716-446655440000",
                 "blueMapMarkerSet":"towns"},
                {"journeyMapGroupUuid":"550e8400-e29b-41d4-a716-446655440000",
                 "blueMapMarkerSet":"towns"}
                """));

        assertThrows(ConfigException.class, manager::reload);
        assertSame(valid, manager.active());
    }

    @Test
    void rejectsIconsOutsideTheIconDirectory() throws Exception {
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.writeString(directory.resolve("config.json"), """
                {"syncOnStartup":true,"defaultIcon":"../secret.png","mappings":[]}
                """);

        assertThrows(ConfigException.class, manager::reload);
    }

    @Test
    void rejectsWrongJsonFieldTypes() throws Exception {
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.writeString(directory.resolve("config.json"), """
                {"syncOnStartup":"false","defaultIcon":"default.svg","mappings":[]}
                """);

        assertThrows(ConfigException.class, manager::reload);
    }

    @Test
    void reportsSetsWhoseLastMappingWasRemoved() throws Exception {
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger("test"), directory);
        manager.initialize();
        Files.writeString(directory.resolve("config.json"), configWithMappings("""
                {"journeyMapGroupUuid":"550e8400-e29b-41d4-a716-446655440000",
                 "blueMapMarkerSet":"towns"}
                """));
        manager.reload();
        Files.writeString(directory.resolve("config.json"), configWithMappings(""));

        assertEquals(java.util.Set.of("towns"), manager.reload().removedMarkerSets());
    }

    private static String configWithMappings(String mappings) {
        return """
                {"syncOnStartup":true,"defaultIcon":"default.svg","mappings":[%s]}
                """.formatted(mappings);
    }
}
