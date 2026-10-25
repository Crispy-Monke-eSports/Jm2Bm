package se.jesper.jm2bm.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import se.jesper.jm2bm.config.ConfigException;
import se.jesper.jm2bm.config.ConfigManager;
import se.jesper.jm2bm.sync.SyncCoordinator;
import se.jesper.jm2bm.sync.SyncStatus;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class BridgeCommands {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    private BridgeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ConfigManager configManager,
                                SyncCoordinator coordinator) {
        dispatcher.register(Commands.literal("jm2bm")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
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
                .then(Commands.literal("status").executes(context -> {
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
                })));
    }
}
