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
            JourneyMapAdapter.GroupRef group = resolveGroup(journeyMap.groups(), groupInput);
            BlueMapAdapter.MarkerSetRef markerSet = resolveMarkerSet(blueMap.markerSets(), markerSetInput);
            ConfigManager.MutationResult result = link
                    ? configManager.link(group.id(), markerSet.id())
                    : configManager.unlink(group.id(), markerSet.id());
            if (!result.changed()) {
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
        try {
            UUID id = UUID.fromString(input);
            return groups.stream().filter(group -> group.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No JourneyMap global group has UUID " + id));
        } catch (IllegalArgumentException ignored) {
            // Friendly names are the normal command input; UUID is only an explicit fallback.
        }
        List<JourneyMapAdapter.GroupRef> matches = groups.stream()
                .filter(group -> group.name().equalsIgnoreCase(input)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException(
                "No JourneyMap global group is named '" + input + "'. Use TAB to choose one.");
        Set<UUID> ids = new LinkedHashSet<>(matches.stream().map(JourneyMapAdapter.GroupRef::id).toList());
        if (ids.size() > 1) throw new IllegalArgumentException(
                "Several JourneyMap global groups are named '" + input + "'; use its UUID instead.");
        return matches.getFirst();
    }

    static BlueMapAdapter.MarkerSetRef resolveMarkerSet(List<BlueMapAdapter.MarkerSetRef> sets, String input) {
        List<BlueMapAdapter.MarkerSetRef> idMatches = sets.stream().filter(set -> set.id().equals(input)).toList();
        if (!idMatches.isEmpty()) return idMatches.getFirst();
        List<BlueMapAdapter.MarkerSetRef> matches = sets.stream()
                .filter(set -> set.label().equalsIgnoreCase(input)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException(
                "No BlueMap marker set is named '" + input + "'. Use TAB to choose one.");
        Set<String> ids = new LinkedHashSet<>(matches.stream().map(BlueMapAdapter.MarkerSetRef::id).toList());
        if (ids.size() > 1) throw new IllegalArgumentException(
                "Several BlueMap marker sets are named '" + input + "'; use its internal ID instead.");
        return matches.getFirst();
    }

    private static CompletableFuture<Suggestions> suggestGroups(JourneyMapAdapter adapter,
                                                                  SuggestionsBuilder builder) {
        try {
            return suggest(adapter.groups().stream().map(JourneyMapAdapter.GroupRef::name).toList(), builder);
        } catch (SnapshotException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestMarkerSets(BlueMapAdapter adapter,
                                                                     SuggestionsBuilder builder) {
        try {
            return suggest(adapter.markerSets().stream().map(BlueMapAdapter.MarkerSetRef::label).toList(), builder);
        } catch (IOException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestLinkedGroups(ConfigManager configManager,
            JourneyMapAdapter journeyMap, SuggestionsBuilder builder) {
        try {
            return suggest(linkedGroupNames(configManager.configured(), journeyMap.groups()), builder);
        } catch (SnapshotException | ConfigException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestLinkedMarkerSets(
            CommandContext<CommandSourceStack> context, ConfigManager configManager,
            JourneyMapAdapter journeyMap, BlueMapAdapter blueMap, SuggestionsBuilder builder) {
        try {
            String input = StringArgumentType.getString(context, "journeymap-global-group");
            UUID groupId = resolveGroup(journeyMap.groups(), input).id();
            return suggest(linkedMarkerSetNames(configManager.configured(), groupId, blueMap.markerSets()), builder);
        } catch (SnapshotException | IOException | ConfigException | IllegalArgumentException e) {
            return builder.buildFuture();
        }
    }

    static CompletableFuture<Suggestions> suggest(List<String> values, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT).replaceFirst("^\"", "");
        values.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(remaining))
                .map(BridgeCommands::quoteArgument)
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    static List<String> linkedGroupNames(BridgeConfig config, List<JourneyMapAdapter.GroupRef> groups) {
        Set<UUID> linked = config.mappings().stream().map(BridgeConfig.Mapping::journeyMapGroupUuid)
                .collect(java.util.stream.Collectors.toSet());
        return groups.stream().filter(group -> linked.contains(group.id()))
                .map(JourneyMapAdapter.GroupRef::name).toList();
    }

    static List<String> linkedMarkerSetNames(BridgeConfig config, UUID groupId,
                                              List<BlueMapAdapter.MarkerSetRef> sets) {
        Set<String> linked = config.mappings().stream()
                .filter(mapping -> mapping.journeyMapGroupUuid().equals(groupId))
                .map(BridgeConfig.Mapping::blueMapMarkerSet)
                .collect(java.util.stream.Collectors.toSet());
        return sets.stream().filter(set -> linked.contains(set.id()))
                .map(BlueMapAdapter.MarkerSetRef::label).toList();
    }

    static String quoteArgument(String value) {
        if (value.matches("[A-Za-z0-9_.:+-]+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
