package se.jesper.jm2bm.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
                .executes(BridgeCommands::showHelp)
                .then(Commands.literal("help").executes(BridgeCommands::showHelp))
                .then(Commands.literal("link")
                        .then(Commands.argument("journeymap-global-group", StringArgumentType.string())
                                .suggests((context, builder) -> suggestGroups(journeyMap, builder))
                                .then(Commands.argument("bluemap-marker-set", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestMarkerSets(blueMap, builder))
                                        .executes(context -> changeLink(context, configManager, coordinator,
                                                journeyMap, blueMap, true, false))
                                        .then(Commands.literal("confirm")
                                                .executes(context -> changeLink(context, configManager, coordinator,
                                                        journeyMap, blueMap, true, true)))))
                        .then(Commands.literal("cancel").executes(context -> {
                            sendNotice(context.getSource(), "Linking cancelled.", ChatFormatting.YELLOW);
                            return 1;
                        })))
                .then(Commands.literal("unlink")
                        .then(Commands.argument("journeymap-global-group", StringArgumentType.string())
                                .suggests((context, builder) -> suggestLinkedGroups(
                                        configManager, journeyMap, builder))
                                .then(Commands.argument("bluemap-marker-set", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestLinkedMarkerSets(
                                                context, configManager, journeyMap, blueMap, builder))
                                        .executes(context -> changeLink(context, configManager, coordinator,
                                                journeyMap, blueMap, false, false)))))
                .then(Commands.literal("icon")
                        .then(Commands.argument("bluemap-marker-set", StringArgumentType.string())
                                .suggests((context, builder) -> suggestManagedMarkerSets(
                                        configManager, blueMap, builder))
                                .then(Commands.argument("icon", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestIcons(configManager, builder))
                                        .executes(context -> changeIcon(
                                                context, configManager, coordinator, blueMap)))))
                .then(Commands.literal("list").executes(context -> listLinks(
                        context, configManager, journeyMap, blueMap)))
                .then(Commands.literal("reload").executes(context -> {
                    try {
                        ConfigManager.ReloadResult result = configManager.reload();
                        coordinator.trigger("command reload", result.removedMarkerSets());
                        sendSuccess(context.getSource(), "Configuration reloaded");
                        sendDetail(context.getSource(), result.current().mappings().size()
                                + " mappings · full reconciliation queued");
                        return 1;
                    } catch (ConfigException e) {
                        context.getSource().sendFailure(error(
                                "Config rejected; previous config remains active: " + e.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("status").executes(context -> showStatus(context, configManager,
                        coordinator))));
    }

    private static int changeLink(CommandContext<CommandSourceStack> context, ConfigManager configManager,
                                  SyncCoordinator coordinator, JourneyMapAdapter journeyMap,
                                  BlueMapAdapter blueMap, boolean link, boolean confirmed) {
        String groupInput = StringArgumentType.getString(context, "journeymap-global-group");
        String markerSetInput = StringArgumentType.getString(context, "bluemap-marker-set");
        try {
            JourneyMapAdapter.GroupRef group;
            BlueMapAdapter.MarkerSetRef markerSet;
            if (link) {
                group = resolveGroup(journeyMap.groups(), groupInput);
                markerSet = resolveMarkerSet(blueMap.markerSets(), markerSetInput);
                BridgeConfig configured = configManager.configured();
                if (!confirmed && !hasLink(configured, group.id(), markerSet.id())) {
                    int markerCount = blueMap.markerCount(markerSet.id());
                    if (markerCount > 0) {
                        askToClearOrCancel(context, group, markerSet, markerCount);
                        return 0;
                    }
                }
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
                context.getSource().sendFailure(error("'" + group.name() + "' and '"
                        + markerSet.label() + "' are " + (link ? "already linked." : "not linked.")));
                return 0;
            }
            coordinator.trigger("command " + (link ? "link" : "unlink"), result.removedMarkerSets());
            sendSuccess(context.getSource(), link ? "Link created" : "Link removed");
            sendDetail(context.getSource(), group.name() + (link ? "  →  " : "  ×  ") + markerSet.label());
            sendDetail(context.getSource(), "config.yaml saved · reconciliation queued");
            return 1;
        } catch (ConfigException | SnapshotException | IOException | IllegalArgumentException e) {
            context.getSource().sendFailure(error(e.getMessage()));
            return 0;
        }
    }

    static boolean hasLink(BridgeConfig config, UUID groupId, String markerSetId) {
        return config.mappings().stream().anyMatch(mapping ->
                mapping.journeyMapGroupUuid().equals(groupId)
                        && mapping.blueMapMarkerSet().equals(markerSetId));
    }

    private static void askToClearOrCancel(CommandContext<CommandSourceStack> context,
            JourneyMapAdapter.GroupRef group, BlueMapAdapter.MarkerSetRef markerSet, int markerCount) {
        String confirmCommand = "/jm2bm link " + quoteArgument("uuid:" + group.id()) + " "
                + quoteArgument("id:" + markerSet.id()) + " confirm";
        Component prompt = prefix()
                .append(Component.literal("Action required").withStyle(ChatFormatting.YELLOW,
                        ChatFormatting.BOLD));
        Component explanation = Component.literal("  '" + markerSet.label() + "' contains "
                        + markerCount + (markerCount == 1 ? " marker." : " markers."))
                .withStyle(ChatFormatting.GRAY);
        Component actions = Component.literal("  ")
                .append(Component.literal("[Clear and link]")
                        .withStyle(style -> style.withColor(ChatFormatting.RED).withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(confirmCommand))))
                .append(Component.literal(" "))
                .append(Component.literal("[Cancel]")
                        .withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/jm2bm link cancel"))));
        context.getSource().sendSuccess(() -> prompt, false);
        context.getSource().sendSuccess(() -> explanation, false);
        context.getSource().sendSuccess(() -> actions, false);
    }

    private static int changeIcon(CommandContext<CommandSourceStack> context, ConfigManager configManager,
            SyncCoordinator coordinator, BlueMapAdapter blueMap) {
        String markerSetInput = StringArgumentType.getString(context, "bluemap-marker-set");
        String icon = StringArgumentType.getString(context, "icon");
        try {
            BridgeConfig configured = configManager.configured();
            BlueMapAdapter.MarkerSetRef markerSet = resolveManagedMarkerSet(
                    configured, blueMap.markerSets(), markerSetInput);
            ConfigManager.MutationResult result = configManager.setIconForMarkerSet(markerSet.id(), icon);
            if (!result.changed()) {
                if (result.activeConfigurationChanged()) {
                    coordinator.trigger("command refreshed externally changed config",
                            result.removedMarkerSets());
                }
                context.getSource().sendFailure(error("'" + markerSet.label()
                        + "' already uses icon '" + icon + "'."));
                return 0;
            }
            coordinator.trigger("command icon", result.removedMarkerSets());
            long links = result.current().mappings().stream()
                    .filter(mapping -> mapping.blueMapMarkerSet().equals(markerSet.id())).count();
            sendSuccess(context.getSource(), "Icon updated");
            sendDetail(context.getSource(), markerSet.label() + "  →  " + icon);
            sendDetail(context.getSource(), links + (links == 1 ? " link updated" : " links updated")
                    + " · config.yaml saved · reconciliation queued");
            return 1;
        } catch (ConfigException | IOException | IllegalArgumentException e) {
            context.getSource().sendFailure(error(e.getMessage()));
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

    static BlueMapAdapter.MarkerSetRef resolveManagedMarkerSet(BridgeConfig config,
            List<BlueMapAdapter.MarkerSetRef> sets, String input) {
        Set<String> managed = config.markerSetIds();
        List<BlueMapAdapter.MarkerSetRef> available = sets.stream()
                .filter(set -> managed.contains(set.id())).toList();
        if (input.startsWith("name:")) {
            return resolveMarkerSetName(available, input.substring("name:".length()), true);
        }
        if (input.startsWith("id:")) {
            String id = input.substring("id:".length());
            if (!managed.contains(id)) {
                throw new IllegalArgumentException("BlueMap marker set " + id + " is not linked.");
            }
            return available.stream().filter(set -> set.id().equals(id)).findFirst()
                    .orElse(new BlueMapAdapter.MarkerSetRef(id, id));
        }
        List<BlueMapAdapter.MarkerSetRef> matches = available.stream()
                .filter(set -> set.label().equalsIgnoreCase(input)).toList();
        if (!matches.isEmpty()) return requireUniqueMarkerSetName(matches, input);
        if (managed.contains(input)) {
            return available.stream().filter(set -> set.id().equals(input)).findFirst()
                    .orElse(new BlueMapAdapter.MarkerSetRef(input, input));
        }
        throw new IllegalArgumentException(
                "No linked marker set is named '" + input + "'. Use TAB or id:<stored ID>.");
    }

    private static BlueMapAdapter.MarkerSetRef requireUniqueMarkerSetName(
            List<BlueMapAdapter.MarkerSetRef> matches, String name) {
        Set<String> ids = new LinkedHashSet<>(matches.stream().map(BlueMapAdapter.MarkerSetRef::id).toList());
        if (ids.size() > 1) throw new IllegalArgumentException(
                "Several linked marker sets are named '" + name + "'; use id:<internal ID> instead.");
        return matches.getFirst();
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

    private static CompletableFuture<Suggestions> suggestManagedMarkerSets(ConfigManager configManager,
            BlueMapAdapter blueMap, SuggestionsBuilder builder) {
        try {
            BridgeConfig config = configManager.configured();
            return suggest(managedMarkerSetArguments(config, availableMarkerSets(blueMap)), builder);
        } catch (ConfigException e) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestIcons(
            ConfigManager configManager, SuggestionsBuilder builder) {
        try {
            return suggest(configManager.iconNames(), builder);
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

    static List<String> managedMarkerSetArguments(BridgeConfig config,
            List<BlueMapAdapter.MarkerSetRef> sets) {
        return config.markerSetIds().stream()
                .map(id -> sets.stream().filter(set -> set.id().equals(id)).findFirst()
                        .map(set -> markerSetNameArgument(set.label())).orElse("id:" + id))
                .toList();
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
        if (value.matches("[A-Za-z0-9_.+-]+")) return value;
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
        final BridgeConfig configured;
        try {
            configured = configManager.configured();
        } catch (ConfigException e) {
            context.getSource().sendFailure(error("config.yaml is invalid: " + e.getMessage()));
            return 0;
        }
        List<BridgeConfig.Mapping> mappings = configured.mappings();
        sendHeader(context.getSource(), "Links");
        sendMetric(context.getSource(), "Configured", Integer.toString(mappings.size()), ChatFormatting.AQUA);
        if (mappings.isEmpty()) {
            sendDetail(context.getSource(), "No groups are linked. Use /jm2bm link to add one.");
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
            String icon = mapping.icon() == null ? configured.defaultIcon() : mapping.icon();
            Component line = Component.literal("  • ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(groupName).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  →  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(setName).withStyle(ChatFormatting.AQUA));
            Component iconLine = Component.literal("      icon: ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(icon).withStyle(ChatFormatting.GRAY));
            context.getSource().sendSuccess(() -> line, false);
            context.getSource().sendSuccess(() -> iconLine, false);
        }
        return mappings.size();
    }

    private static int showStatus(CommandContext<CommandSourceStack> context, ConfigManager configManager,
                                  SyncCoordinator coordinator) {
        SyncStatus status = coordinator.status();
        sendHeader(context.getSource(), "Status");
        sendSection(context.getSource(), "Connections");
        sendConnection(context.getSource(), "JourneyMap", se.jesper.jm2bm.Jm2BmMod.journeyMapConnected());
        sendConnection(context.getSource(), "BlueMap", se.jesper.jm2bm.Jm2BmMod.blueMapConnected());

        sendSection(context.getSource(), "Configuration");
        sendMetric(context.getSource(), "Mappings", Integer.toString(configManager.active().mappings().size()),
                ChatFormatting.AQUA);
        sendMetric(context.getSource(), "Managed sets", Integer.toString(status.managedSets()),
                ChatFormatting.AQUA);
        sendMetric(context.getSource(), "Maps", Integer.toString(status.managedMaps()), ChatFormatting.AQUA);

        sendSection(context.getSource(), "Destinations (" + status.markerCounts().size() + ")");
        if (status.markerCounts().isEmpty()) sendDetail(context.getSource(), "No destinations synchronized yet.");
        status.markerCounts().forEach((destination, count) ->
                context.getSource().sendSuccess(() -> destinationLine(destination, count), false));

        int warningCount = status.missingMarkerSets().size() + status.missingGroups().size();
        if (warningCount > 0) {
            sendSection(context.getSource(), "Warnings (" + warningCount + ")", ChatFormatting.YELLOW);
            status.missingMarkerSets().forEach(markerSet ->
                    sendWarning(context.getSource(), "Marker set unavailable: " + markerSet));
            status.missingGroups().forEach(group ->
                    sendWarning(context.getSource(), "JourneyMap group unavailable: " + group));
        }

        sendSection(context.getSource(), "Last synchronization");
        String when = status.completedAt() == null ? "never" : TIME.format(status.completedAt());
        ChatFormatting stateColor = status.completedAt() == null ? ChatFormatting.GRAY
                : status.success() ? ChatFormatting.GREEN : ChatFormatting.RED;
        String state = status.completedAt() == null ? "NOT RUN" : status.success() ? "OK" : "ERROR";
        Component lastSync = Component.literal("  " + state).withStyle(stateColor, ChatFormatting.BOLD)
                .append(Component.literal("  ·  " + when).withStyle(ChatFormatting.DARK_GRAY));
        context.getSource().sendSuccess(() -> lastSync, false);
        if (shouldShowStatusMessage(status, warningCount)) sendDetail(context.getSource(), status.message());
        return 1;
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        sendHeader(context.getSource(), "Commands");
        sendDetail(context.getSource(), "JourneyMap → BlueMap marker synchronization");
        sendSection(context.getSource(), "Inspect");
        sendCommand(context.getSource(), "/jm2bm status", "Connection and synchronization health");
        sendCommand(context.getSource(), "/jm2bm list", "Configured group-to-set links");
        sendSection(context.getSource(), "Configure");
        sendCommand(context.getSource(), "/jm2bm link ", "Link a JourneyMap group to a marker set");
        sendCommand(context.getSource(), "/jm2bm unlink ", "Remove an existing link");
        sendCommand(context.getSource(), "/jm2bm icon ", "Set the icon for a linked marker set");
        sendCommand(context.getSource(), "/jm2bm reload", "Reload config.yaml and synchronize");
        sendDetail(context.getSource(), "Click a command to put it in chat. TAB completes names.");
        return 1;
    }

    private static boolean shouldShowStatusMessage(SyncStatus status, int warningCount) {
        if (status.message() == null || status.message().isBlank()) return false;
        return warningCount == 0 || !status.message().startsWith("Completed with missing ");
    }

    private static MutableComponent prefix() {
        return Component.empty().append(Component.literal("[Jm2Bm] ")
                .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD));
    }

    private static Component error(String message) {
        return prefix().append(Component.literal("Error: ").withStyle(ChatFormatting.RED,
                ChatFormatting.BOLD)).append(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> prefix().append(Component.literal("✓ ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(message).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)), false);
    }

    private static void sendNotice(CommandSourceStack source, String message, ChatFormatting color) {
        source.sendSuccess(() -> prefix().append(Component.literal(message).withStyle(color)), false);
    }

    private static void sendHeader(CommandSourceStack source, String title) {
        Component header = Component.literal("━━ ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("Jm2Bm").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" · " + title + " ").withStyle(ChatFormatting.WHITE,
                        ChatFormatting.BOLD))
                .append(Component.literal("━━").withStyle(ChatFormatting.DARK_GRAY));
        source.sendSuccess(() -> header, false);
    }

    private static void sendSection(CommandSourceStack source, String title) {
        sendSection(source, title, ChatFormatting.GOLD);
    }

    private static void sendSection(CommandSourceStack source, String title, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal("\n" + title).withStyle(color, ChatFormatting.BOLD), false);
    }

    private static void sendDetail(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private static void sendMetric(CommandSourceStack source, String label, String value,
            ChatFormatting valueColor) {
        Component line = Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColor, ChatFormatting.BOLD));
        source.sendSuccess(() -> line, false);
    }

    private static void sendConnection(CommandSourceStack source, String label, boolean connected) {
        Component line = Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(connected ? "● Connected" : "● Disconnected")
                        .withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.RED));
        source.sendSuccess(() -> line, false);
    }

    private static Component destinationLine(String destination, int count) {
        return Component.literal("  • ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(destination).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(count + (count == 1 ? " marker" : " markers"))
                        .withStyle(ChatFormatting.AQUA));
    }

    private static void sendWarning(CommandSourceStack source, String warning) {
        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("  ! ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(warning).withStyle(ChatFormatting.YELLOW)), false);
    }

    private static void sendCommand(CommandSourceStack source, String command, String description) {
        Component line = Component.empty()
                .append(Component.literal("  " + command).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.SuggestCommand(command))))
                .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> line, false);
    }
}
