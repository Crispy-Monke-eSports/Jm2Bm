package se.jesper.jm2bm.command;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.junit.jupiter.api.Test;
import se.jesper.jm2bm.bluemap.BlueMapAdapter;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.journeymap.JourneyMapAdapter;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeCommandsTest {
    private static final UUID LINKED_GROUP = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OTHER_GROUP = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    @Test
    void resolvesFriendlyNamesCaseInsensitively() {
        JourneyMapAdapter.GroupRef group = new JourneyMapAdapter.GroupRef(LINKED_GROUP, "Global Towns");
        BlueMapAdapter.MarkerSetRef markerSet = new BlueMapAdapter.MarkerSetRef("towns-internal", "Town Markers");

        assertEquals(group, BridgeCommands.resolveGroup(List.of(group), "global towns"));
        assertEquals(markerSet, BridgeCommands.resolveMarkerSet(List.of(markerSet), "town markers"));
    }

    @Test
    void rejectsAmbiguousFriendlyNames() {
        assertThrows(IllegalArgumentException.class, () -> BridgeCommands.resolveGroup(List.of(
                new JourneyMapAdapter.GroupRef(LINKED_GROUP, "Towns"),
                new JourneyMapAdapter.GroupRef(OTHER_GROUP, "Towns")), "Towns"));
        assertThrows(IllegalArgumentException.class, () -> BridgeCommands.resolveMarkerSet(List.of(
                new BlueMapAdapter.MarkerSetRef("a", "Towns"),
                new BlueMapAdapter.MarkerSetRef("b", "Towns")), "Towns"));
    }

    @Test
    void friendlyMarkerSetNameWinsOverCollidingInternalId() {
        BlueMapAdapter.MarkerSetRef idMatch = new BlueMapAdapter.MarkerSetRef("towns", "Villages");
        BlueMapAdapter.MarkerSetRef labelMatch = new BlueMapAdapter.MarkerSetRef("shops", "towns");

        assertEquals(labelMatch, BridgeCommands.resolveMarkerSet(List.of(idMatch, labelMatch), "towns"));
        assertEquals(idMatch, BridgeCommands.resolveMarkerSet(List.of(idMatch, labelMatch), "id:towns"));

        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(LINKED_GROUP, "towns", null),
                new BridgeConfig.Mapping(LINKED_GROUP, "shops", null)));
        assertEquals(labelMatch, BridgeCommands.resolveLinkedMarkerSet(
                config, LINKED_GROUP, List.of(idMatch, labelMatch), "towns"));
        assertEquals(idMatch, BridgeCommands.resolveLinkedMarkerSet(
                config, LINKED_GROUP, List.of(idMatch, labelMatch), "id:towns"));
    }

    @Test
    void friendlyGroupNameWinsOverCollidingUuid() {
        JourneyMapAdapter.GroupRef idMatch = new JourneyMapAdapter.GroupRef(LINKED_GROUP, "Actual ID group");
        JourneyMapAdapter.GroupRef nameMatch = new JourneyMapAdapter.GroupRef(OTHER_GROUP, LINKED_GROUP.toString());
        List<JourneyMapAdapter.GroupRef> groups = List.of(idMatch, nameMatch);
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(LINKED_GROUP, "towns", null),
                new BridgeConfig.Mapping(OTHER_GROUP, "towns", null)));

        assertEquals(nameMatch, BridgeCommands.resolveGroup(groups, LINKED_GROUP.toString()));
        assertEquals(idMatch, BridgeCommands.resolveGroup(groups, "uuid:" + LINKED_GROUP));
        assertEquals(nameMatch, BridgeCommands.resolveLinkedGroup(config, groups, LINKED_GROUP.toString()));
        assertEquals(idMatch, BridgeCommands.resolveLinkedGroup(config, groups, "uuid:" + LINKED_GROUP));
        assertEquals("name:" + LINKED_GROUP, BridgeCommands.groupNameArgument(LINKED_GROUP.toString()));
    }

    @Test
    void markerSetNamesCanUseReservedPrefixes() throws Exception {
        BlueMapAdapter.MarkerSetRef markerSet = new BlueMapAdapter.MarkerSetRef("towns", "id:towns");
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(LINKED_GROUP, "towns", null)));

        assertEquals(markerSet, BridgeCommands.resolveMarkerSet(List.of(markerSet), "name:id:towns"));
        assertEquals(markerSet, BridgeCommands.resolveLinkedMarkerSet(
                config, LINKED_GROUP, List.of(markerSet), "name:id:towns"));
        assertEquals("name:id:towns", BridgeCommands.markerSetNameArgument("id:towns"));
        assertEquals("name:name:towns", BridgeCommands.markerSetNameArgument("name:towns"));
        var suggestions = BridgeCommands.suggest(List.of("name:id:towns"),
                new SuggestionsBuilder("id", 0)).get();
        assertEquals(List.of("name:id:towns"), suggestions.getList().stream()
                .map(suggestion -> suggestion.getText()).toList());
    }

    @Test
    void quotesTabSuggestionsContainingSpaces() throws Exception {
        var suggestions = BridgeCommands.suggest(List.of("Global Towns", "Shops"),
                new SuggestionsBuilder("", 0)).get();

        assertEquals(List.of("\"Global Towns\"", "Shops"), suggestions.getList().stream()
                .map(suggestion -> suggestion.getText()).toList());
    }

    @Test
    void unlinkSuggestionsOnlyContainLinkedGroupAndItsLinkedSets() {
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(LINKED_GROUP, "towns", null)));
        List<JourneyMapAdapter.GroupRef> groups = List.of(
                new JourneyMapAdapter.GroupRef(LINKED_GROUP, "Linked group"),
                new JourneyMapAdapter.GroupRef(OTHER_GROUP, "Other group"));
        List<BlueMapAdapter.MarkerSetRef> sets = List.of(
                new BlueMapAdapter.MarkerSetRef("towns", "Linked set"),
                new BlueMapAdapter.MarkerSetRef("shops", "Other set"));

        assertEquals(List.of("Linked group"), BridgeCommands.linkedGroupNames(config, groups));
        assertEquals(List.of("Linked set"),
                BridgeCommands.linkedMarkerSetNames(config, LINKED_GROUP, sets));
    }

    @Test
    void staleLinksCanBeResolvedAndSuggestedByStoredIdentifiers() {
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(LINKED_GROUP, "removed-set", null)));

        assertEquals(LINKED_GROUP,
                BridgeCommands.resolveLinkedGroup(config, List.of(), LINKED_GROUP.toString()).id());
        assertEquals("removed-set",
                BridgeCommands.resolveLinkedMarkerSet(config, LINKED_GROUP, List.of(), "removed-set").id());
        assertEquals(List.of(LINKED_GROUP.toString()), BridgeCommands.linkedGroupNames(config, List.of()));
        assertEquals(List.of("uuid:" + LINKED_GROUP), BridgeCommands.linkedGroupArguments(config, List.of()));
        assertEquals(List.of("removed-set"),
                BridgeCommands.linkedMarkerSetNames(config, LINKED_GROUP, List.of()));
        assertEquals(List.of("id:removed-set"),
                BridgeCommands.linkedMarkerSetArguments(config, LINKED_GROUP, List.of()));
    }

    @Test
    void detectsOnlyTheExactExistingLinkWhenDecidingWhetherToPrompt() {
        BridgeConfig config = new BridgeConfig(true, "default.svg", List.of(
                new BridgeConfig.Mapping(LINKED_GROUP, "towns", null),
                new BridgeConfig.Mapping(OTHER_GROUP, "shops", null)));

        assertTrue(BridgeCommands.hasLink(config, LINKED_GROUP, "towns"));
        assertFalse(BridgeCommands.hasLink(config, LINKED_GROUP, "shops"));
        assertFalse(BridgeCommands.hasLink(config, OTHER_GROUP, "towns"));
    }
}
