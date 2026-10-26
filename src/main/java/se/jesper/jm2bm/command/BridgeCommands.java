package se.jesper.jm2bm.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import se.jesper.jm2bm.bluemap.BlueMapAdapter;
import se.jesper.jm2bm.config.BridgeConfig;
import se.jesper.jm2bm.config.ConfigException;
import se.jesper.jm2bm.config.ConfigManager;
import se.jesper.jm2bm.journeymap.JourneyMapAdapter;
import se.jesper.jm2bm.journeymap.SnapshotException;
import se.jesper.jm2bm.sync.SyncCoordinator;
import se.jesper.jm2bm.sync.SyncStatus;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BridgeCommands {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private BridgeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ConfigManager configManager,
                                SyncCoordinator coordinator, JourneyMapAdapter journeyMap,
                                BlueMapAdapter blueMap) {
        dispatcher.register(Commands.literal("jm2bm")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("link")
                        .then(Commands.argument("journeymap-global-group", StringArgumentType.string())
                                .suggests((context, builder) -> suggestGroups(journeyMap, builder))
                                .then(Commands.argument("bluemap-marker-set", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestMarkerSets(blueMap, builder))
                                        .executes(context -> changeLink(context, configManager, coordinator,
                                                journeyMap, blueMap, true)))))
                .then(Commands.literal("unlink")
                        .then(Commands.argument("journeymap-global-group", StringArgumentType.string())
                                .suggests((context, builder) -> suggestLinkedGroups(
                                        configManager, journeyMap, builder))
                                .then(Commands.argument("bluemap-marker-set", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestLinkedMarkerSets(
                                                context, configManager, journeyMap, blueMap, builder))
                                        .executes(context -> changeLink(context, configManager, coordinator,
                                                journeyMap, blueMap, false)))))
                .then(Commands.literal("list").executes(context -> listLinks(
                        context, configManager, journeyMap, blueMap)))
                .then(Commands.literal("reload").executes(context -> {
                    try {
                        ConfigManager.ReloadResult result = configManager.reload();
                        coordinator.trigger("command reload", result.removedMarkerSets());
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Jm2Bm: config loaded (" + result.current().mappings().size()
                                        + " mappings); full reconciliation queued."), false);
                        return 1;
                    } catch (ConfigException e) {
                        context.getSource().sendFailure(Component.literal(
                                "Jm2Bm: config rejected; previous config remains active: " + e.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("status").executes(context -> showStatus(context, configManager,
                        coordinator))));
    }

    private static int changeLink(CommandContext<CommandSourceStack> context, ConfigManager configManager,
                                  SyncCoordinator coordinator, JourneyMapAdapter journeyMap,
                                  BlueMapAdapter blueMap, boolean link) {
        String groupInput = StringArgumentType.getString(context, "journeymap-global-group");
        String markerSetInput = StringArgumentType.getString(context, "bluemap-marker-set");
        try {
            JourneyMapAdapter.GroupRef group;
            BlueMapAdapter.MarkerSetRef markerSet;
            if (link) {
                group = resolveGroup(journeyMap.groups(), groupInput);
                markerSet = resolveMarkerSet(blueMap.markerSets(), markerSetInput);
            } else {
                BridgeConfig configured = configManager.configured();
                group = resolveLinkedGroup(configured, availableGroups(journeyMap), groupInput);
                markerSet = resolveLinkedMarkerSet(configured, group.id(), availableMarkerSets(blueMap),
                        markerSetInput);
            }
            ConfigManager.MutationResult result = link
                    ? configManager.link(group.id(), markerSet.id())
                    : configManager.unlink(group.id(), markerSet.id());
            if (!result.changed()) {
                if (result.activeConfigurationChanged()) {
                    coordinator.trigger("command refreshed externally changed config",
                            result.removedMarkerSets());
                }
                context.getSource().sendFailure(Component.literal("Jm2Bm: '" + group.name() + "' and '"
                        + markerSet.label() + "' are " + (link ? "already linked." : "not linked.")));
                return 0;
            }
            coordinator.trigger("command " + (link ? "link" : "unlink"), result.removedMarkerSets());
            context.getSource().sendSuccess(() -> Component.literal("Jm2Bm: " + group.name() + " "
                    + (link ? "linked to " : "unlinked from ") + markerSet.label()
                    + "; config.yaml saved and reconciliation queued."), false);
            return 1;
        } catch (ConfigException | SnapshotException | IOException | IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal("Jm2Bm: " + e.getMessage()));
            return 0;
        }
    }

    static JourneyMapAdapter.GroupRef resolveGroup(List<JourneyMapAdapter.GroupRef> groups, String input) {
        if (input.startsWith("name:")) return resolveGroupName(groups, input.substring("name:".length()), false);
        if (input.startsWith("uuid:")) {
            UUID id = parseUuidArgument(input.substring("uuid:".length()), "JourneyMap group UUID");
            return groups.stream().filter(group -> group.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No JourneyMap global group has UUID " + id));
        }
        List<JourneyMapAdapter.GroupRef> nameMatches = groupNameMatches(groups, input);
        if (!nameMatches.isEmpty()) return requireUniqueGroupName(nameMatches, input);
        try {
            UUID id = UUID.fromString(input);
            return groups.stream().filter(group -> group.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No JourneyMap global group has UUID " + id));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "No JourneyMap global group is named '" + input + "'. Use TAB to choose one.");
        }
    }

    static BlueMapAdapter.MarkerSetRef resolveMarkerSet(List<BlueMapAdapter.MarkerSetRef> sets, String input) {
        if (input.startsWith("name:")) {
            return resolveMarkerSetName(sets, input.substring("name:".length()), false);
        }
        if (input.startsWith("id:")) {
            String id = input.substring("id:".length());
            return sets.stream().filter(set -> set.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No BlueMap marker set has internal ID " + id));
        }
        List<BlueMapAdapter.MarkerSetRef> matches = sets.stream()
                .filter(set -> set.label().equalsIgnoreCase(input)).toList();
        if (!matches.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>(matches.stream().map(BlueMapAdapter.MarkerSetRef::id).toList());
            if (ids.size() > 1) throw new IllegalArgumentException(
                    "Several BlueMap marker sets are named '" + input + "'; use id:<internal ID> instead.");
            return matches.getFirst();
        }
        return sets.stream().filter(set -> set.id().equals(input)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No BlueMap marker set is named '" + input + "'. Use TAB to choose one."));
    }

    static JourneyMapAdapter.GroupRef resolveLinkedGroup(BridgeConfig config,
            List<JourneyMapAdapter.GroupRef> groups, String input) {
        Set<UUID> linked = config.mappings().stream().map(BridgeConfig.Mapping::journeyMapGroupUuid)
                .collect(java.util.stream.Collectors.toSet());
        if (input.startsWith("name:")) {
            return resolveGroupName(groups.stream().filter(group -> linked.contains(group.id())).toList(),
                    input.substring("name:".length()), true);
        }
        if (input.startsWith("uuid:")) {
            return resolveLinkedGroupId(linked, groups,
                    parseUuidArgument(input.substring("uuid:".length()), "JourneyMap group UUID"));
        }
        List<JourneyMapAdapter.GroupRef> nameMatches = groupNameMatches(
                groups.stream().filter(group -> linked.contains(group.id())).toList(), input);
        if (!nameMatches.isEmpty()) return requireUniqueGroupName(nameMatches, input);
        try {
            return resolveLinkedGroupId(linked, groups, UUID.fromString(input));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "No linked JourneyMap group is named '" + input + "'. Use TAB or uuid:<stored UUID>.");
        }
    }

    private static JourneyMapAdapter.GroupRef resolveLinkedGroupId(Set<UUID> linked,
            List<JourneyMapAdapter.GroupRef> groups, UUID id) {
        if (!linked.contains(id)) throw new IllegalArgumentException("JourneyMap group " + id + " is not linked.");
        return groups.stream().filter(group -> group.id().equals(id)).findFirst()
                .orElse(new JourneyMapAdapter.GroupRef(id, id.toString()));
    }

    private static JourneyMapAdapter.GroupRef resolveGroupName(List<JourneyMapAdapter.GroupRef> groups,
            String name, boolean linkedOnly) {
        List<JourneyMapAdapter.GroupRef> matches = groupNameMatches(groups, name);
        if (matches.isEmpty()) throw new IllegalArgumentException(
                "No " + (linkedOnly ? "linked " : "") + "JourneyMap global group is named '" + name + "'.");
        return requireUniqueGroupName(matches, name);
    }

    private static List<JourneyMapAdapter.GroupRef> groupNameMatches(
            List<JourneyMapAdapter.GroupRef> groups, String name) {
        return groups.stream().filter(group -> group.name().equalsIgnoreCase(name)).toList();
    }

    private static JourneyMapAdapter.GroupRef requireUniqueGroupName(
            List<JourneyMapAdapter.GroupRef> matches, String name) {
        Set<UUID> ids = new LinkedHashSet<>(matches.stream().map(JourneyMapAdapter.GroupRef::id).toList());
        if (ids.size() > 1) throw new IllegalArgumentException(
                "Several JourneyMap global groups are named '" + name + "'; use uuid:<UUID> instead.");
        return matches.getFirst();
    }

    private static UUID parseUuidArgument(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is invalid: " + value);
        }
    }

    static BlueMapAdapter.MarkerSetRef resolveLinkedMarkerSet(BridgeConfig config, UUID groupId,
            List<BlueMapAdapter.MarkerSetRef> sets, String input) {
        Set<String> linked = config.mappings().stream()
                .filter(mapping -> mapping.journeyMapGroupUuid().equals(groupId))
                .map(BridgeConfig.Mapping::blueMapMarkerSet)
                .collect(java.util.stream.Collectors.toSet());
        if (input.startsWith("name:")) {
            return resolveMarkerSetName(sets.stream().filter(set -> linked.contains(set.id())).toList(),
                    input.substring("name:".length()), true);
        }
        if (input.startsWith("id:")) {
            String id = input.substring("id:".length());
            if (!linked.contains(id)) throw new IllegalArgumentException(
                    "BlueMap marker set " + id + " is not linked to this group.");
            return sets.stream().filter(set -> set.id().equals(id)).findFirst()
                    .orElse(new BlueMapAdapter.MarkerSetRef(id, id));
        }
        List<BlueMapAdapter.MarkerSetRef> matches = sets.stream()
                .filter(set -> linked.contains(set.id()) && set.label().equalsIgnoreCase(input)).toList();
        if (!matches.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>(matches.stream().map(BlueMapAdapter.MarkerSetRef::id).toList());
            if (ids.size() > 1) throw new IllegalArgumentException(
                    "Several linked marker sets are named '" + input + "'; use id:<internal ID> instead.");
            return matches.getFirst();
        }
        if (linked.contains(input)) {
            return sets.stream().filter(set -> set.id().equals(input)).findFirst()
                    .orElse(new BlueMapAdapter.MarkerSetRef(input, input));
        }
        throw new IllegalArgumentException(
                "No marker set named '" + input + "' is linked to this group. Use TAB or id:<stored ID>.");
    }

    private static BlueMapAdapter.MarkerSetRef resolveMarkerSetName(List<BlueMapAdapter.MarkerSetRef> sets,
            String name, boolean linkedOnly) {
        List<BlueMapAdapter.MarkerSetRef> matches = sets.stream()
                .filter(set -> set.label().equalsIgnoreCase(name)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException(
                "No " + (linkedOnly ? "linked " : "") + "BlueMap marker set is named '" + name + "'.");
        Set<String> ids = new LinkedHashSet<>(matches.stream().map(BlueMapAdapter.MarkerSetRef::id).toList());
        if (ids.size() > 1) throw new IllegalArgumentException(
                "Several BlueMap marker sets are named '" + name + "'; use id:<internal ID> instead.");
        return matches.getFirst();
    }

    private static CompletableFuture<Suggestions> suggestGroups(JourneyMapAdapter adapter,
                                                                  SuggestionsBuilder builder) {
        try {
            return suggest(adapter.groups().stream().map(JourneyMapAdapter.GroupRef::name)
                    .map(BridgeCommands::groupNameArgument).toList(), builder);
        } catch (SnapshotException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestMarkerSets(BlueMapAdapter adapter,
                                                                     SuggestionsBuilder builder) {
        try {
            return suggest(adapter.markerSets().stream().map(BlueMapAdapter.MarkerSetRef::label)
                    .map(BridgeCommands::markerSetNameArgument).toList(), builder);
        } catch (IOException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestLinkedGroups(ConfigManager configManager,
            JourneyMapAdapter journeyMap, SuggestionsBuilder builder) {
        try {
            return suggest(linkedGroupArguments(configManager.configured(), availableGroups(journeyMap)), builder);
        } catch (ConfigException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestLinkedMarkerSets(
            CommandContext<CommandSourceStack> context, ConfigManager configManager,
            JourneyMapAdapter journeyMap, BlueMapAdapter blueMap, SuggestionsBuilder builder) {
        try {
            String input = StringArgumentType.getString(context, "journeymap-global-group");
            BridgeConfig configured = configManager.configured();
            UUID groupId = resolveLinkedGroup(configured, availableGroups(journeyMap), input).id();
            return suggest(linkedMarkerSetArguments(
                    configured, groupId, availableMarkerSets(blueMap)), builder);
        } catch (ConfigException | IllegalArgumentException e) {
            return builder.buildFuture();
        }
    }

    static CompletableFuture<Suggestions> suggest(List<String> values, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT).replaceFirst("^\"", "");
        values.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(value -> suggestionFriendlyValue(value).toLowerCase(Locale.ROOT).startsWith(remaining))
                .map(BridgeCommands::quoteArgument)
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static String suggestionFriendlyValue(String value) {
        return value.startsWith("name:") ? value.substring("name:".length()) : value;
    }

    static List<String> linkedGroupNames(BridgeConfig config, List<JourneyMapAdapter.GroupRef> groups) {
        Set<UUID> linked = config.mappings().stream().map(BridgeConfig.Mapping::journeyMapGroupUuid)
                .collect(java.util.stream.Collectors.toSet());
        return linked.stream().map(id -> groups.stream().filter(group -> group.id().equals(id)).findFirst()
                .map(JourneyMapAdapter.GroupRef::name).orElse(id.toString())).toList();
    }

    static List<String> linkedGroupArguments(BridgeConfig config, List<JourneyMapAdapter.GroupRef> groups) {
        Set<UUID> linked = config.mappings().stream().map(BridgeConfig.Mapping::journeyMapGroupUuid)
                .collect(java.util.stream.Collectors.toSet());
        return linked.stream().map(id -> groups.stream().filter(group -> group.id().equals(id)).findFirst()
                .map(group -> groupNameArgument(group.name())).orElse("uuid:" + id)).toList();
    }

    static List<String> linkedMarkerSetNames(BridgeConfig config, UUID groupId,
                                              List<BlueMapAdapter.MarkerSetRef> sets) {
        Set<String> linked = config.mappings().stream()
                .filter(mapping -> mapping.journeyMapGroupUuid().equals(groupId))
                .map(BridgeConfig.Mapping::blueMapMarkerSet)
                .collect(java.util.stream.Collectors.toSet());
        return linked.stream().map(id -> sets.stream().filter(set -> set.id().equals(id)).findFirst()
                .map(BlueMapAdapter.MarkerSetRef::label).orElse(id)).toList();
    }

    static List<String> linkedMarkerSetArguments(BridgeConfig config, UUID groupId,
                                                  List<BlueMapAdapter.MarkerSetRef> sets) {
        Set<String> linked = config.mappings().stream()
                .filter(mapping -> mapping.journeyMapGroupUuid().equals(groupId))
                .map(BridgeConfig.Mapping::blueMapMarkerSet)
                .collect(java.util.stream.Collectors.toSet());
        return linked.stream().map(id -> sets.stream().filter(set -> set.id().equals(id)).findFirst()
                .map(set -> markerSetNameArgument(set.label())).orElse("id:" + id)).toList();
    }

    private static List<JourneyMapAdapter.GroupRef> availableGroups(JourneyMapAdapter journeyMap) {
        try {
            return journeyMap.groups();
        } catch (SnapshotException ignored) {
            return List.of();
        }
    }

    private static List<BlueMapAdapter.MarkerSetRef> availableMarkerSets(BlueMapAdapter blueMap) {
        try {
            return blueMap.markerSets();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    static String quoteArgument(String value) {
        if (value.matches("[A-Za-z0-9_.:+-]+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String groupNameArgument(String name) {
        if (name.startsWith("name:") || name.startsWith("uuid:") || isUuid(name)) return "name:" + name;
        return name;
    }

    static String markerSetNameArgument(String name) {
        if (name.startsWith("name:") || name.startsWith("id:")) return "name:" + name;
        return name;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static int listLinks(CommandContext<CommandSourceStack> context, ConfigManager configManager,
                                 JourneyMapAdapter journeyMap, BlueMapAdapter blueMap) {
        List<JourneyMapAdapter.GroupRef> groups;
        List<BlueMapAdapter.MarkerSetRef> markerSets;
        try {
            groups = journeyMap.groups();
        } catch (SnapshotException ignored) {
            groups = List.of();
        }
        try {
            markerSets = blueMap.markerSets();
        } catch (IOException ignored) {
            markerSets = List.of();
        }
        final List<BridgeConfig.Mapping> mappings;
        try {
            mappings = configManager.configured().mappings();
        } catch (ConfigException e) {
            context.getSource().sendFailure(Component.literal("Jm2Bm: config.yaml is invalid: " + e.getMessage()));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "Jm2Bm links (" + mappings.size() + "):"), false);
        if (mappings.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("  No groups are linked."), false);
            return 1;
        }
        List<JourneyMapAdapter.GroupRef> availableGroups = groups;
        List<BlueMapAdapter.MarkerSetRef> availableSets = markerSets;
        for (BridgeConfig.Mapping mapping : mappings) {
            String groupName = availableGroups.stream()
                    .filter(group -> group.id().equals(mapping.journeyMapGroupUuid()))
                    .map(JourneyMapAdapter.GroupRef::name).findFirst()
                    .orElse(mapping.journeyMapGroupUuid().toString());
            String setName = availableSets.stream()
                    .filter(set -> set.id().equals(mapping.blueMapMarkerSet()))
                    .map(BlueMapAdapter.MarkerSetRef::label).findFirst()
                    .orElse(mapping.blueMapMarkerSet());
            context.getSource().sendSuccess(() -> Component.literal("  " + groupName + " -> " + setName), false);
        }
        return mappings.size();
    }

    private static int showStatus(CommandContext<CommandSourceStack> context, ConfigManager configManager,
                                  SyncCoordinator coordinator) {
        SyncStatus status = coordinator.status();
        context.getSource().sendSuccess(() -> Component.literal("Jm2Bm"), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "JourneyMap: " + (se.jesper.jm2bm.Jm2BmMod.journeyMapConnected()
                        ? "Connected" : "Disconnected")), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "BlueMap: " + (se.jesper.jm2bm.Jm2BmMod.blueMapConnected()
                        ? "Connected" : "Disconnected")), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "Mappings: " + configManager.active().mappings().size()
                        + ", managed sets: " + status.managedSets()
                        + ", maps: " + status.managedMaps()), false);
        status.markerCounts().forEach((destination, count) ->
                context.getSource().sendSuccess(() -> Component.literal(
                        "  " + destination + ": " + count + " markers"), false));
        if (!status.missingMarkerSets().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Missing marker sets: " + String.join(", ", status.missingMarkerSets())), false);
        }
        if (!status.missingGroups().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Missing JourneyMap groups: " + status.missingGroups()), false);
        }
        String when = status.completedAt() == null ? "never" : TIME.format(status.completedAt());
        context.getSource().sendSuccess(() -> Component.literal(
                "Last sync: " + (status.success() ? "OK" : "ERROR") + " at " + when
                        + " — " + status.message()), false);
        return 1;
    }
}
