package se.jesper.jm2bm.sync;

import org.junit.jupiter.api.Test;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.journeymap.JourneySnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncPlannerTest {
    private static final UUID TOWNS = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID DUNGEONS = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID SHOPS = UUID.fromString("6ba7b810-9dad-41d1-80b4-00c04fd430c8");

    @Test
    void unionsAllGroupsMappedToTheSameSetAndKeepsTheirIcons() throws Exception {
        BridgeConfig config = config();
        JourneySnapshot snapshot = new JourneySnapshot(Set.of(TOWNS, DUNGEONS, SHOPS), List.of(
                waypoint("00000000-0000-0000-0000-000000000001", TOWNS, "Town", "minecraft:overworld"),
                waypoint("00000000-0000-0000-0000-000000000002", DUNGEONS, "Dungeon", "minecraft:overworld"),
                waypoint("00000000-0000-0000-0000-000000000003", SHOPS, "Shop", "minecraft:overworld")
        ));

        Map<String, DesiredMarker> desired = SyncPlanner.plan(config, snapshot,
                Map.of(TOWNS, "town.svg", DUNGEONS, "dungeon.svg", SHOPS, "shop.png"),
                "minecraft:overworld", "towns");

        assertEquals(Set.of(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002"), desired.keySet());
        assertEquals("town.svg", desired.get("00000000-0000-0000-0000-000000000001").iconAssetName());
        assertEquals("dungeon.svg", desired.get("00000000-0000-0000-0000-000000000002").iconAssetName());
    }

    @Test
    void filtersByDimension() throws Exception {
        JourneySnapshot snapshot = new JourneySnapshot(Set.of(TOWNS), List.of(
                waypoint("00000000-0000-0000-0000-000000000001", TOWNS, "Both",
                        "minecraft:overworld", "minecraft:the_nether"),
                waypoint("00000000-0000-0000-0000-000000000002", TOWNS, "Overworld",
                        "minecraft:overworld")
        ));

        Map<String, DesiredMarker> desired = SyncPlanner.plan(config(), snapshot, Map.of(),
                "minecraft:the_nether", "towns");

        assertEquals(Set.of("00000000-0000-0000-0000-000000000001"), desired.keySet());
    }

    @Test
    void rejectsConflictingMarkerIdsInOneDestination() {
        String id = "00000000-0000-0000-0000-000000000001";
        JourneySnapshot snapshot = new JourneySnapshot(Set.of(TOWNS, DUNGEONS), List.of(
                waypoint(id, TOWNS, "One", "minecraft:overworld"),
                waypoint(id, DUNGEONS, "Two", "minecraft:overworld")
        ));

        assertThrows(SyncPlanner.PlanningException.class, () -> SyncPlanner.plan(
                config(), snapshot, Map.of(), "minecraft:overworld", "towns"));
    }

    private static BridgeConfig config() {
        return new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(TOWNS, "towns", "town.svg"),
                new BridgeConfig.Mapping(DUNGEONS, "towns", "dungeon.svg"),
                new BridgeConfig.Mapping(SHOPS, "shops", "shop.png")
        ));
    }

    private static JourneySnapshot.WaypointData waypoint(
            String id, UUID group, String name, String... dimensions) {
        return new JourneySnapshot.WaypointData(id, group, name, 1, 64, 2, Set.of(dimensions));
    }
}
