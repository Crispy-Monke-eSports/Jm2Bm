package se.jesper.jm2bm.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import se.jesper.jm2bm.Jm2BmMod;

public final class Jm2BmFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Jm2BmMod.initialize(FabricLoader.getInstance().getConfigDir());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                Jm2BmMod.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(Jm2BmMod::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Jm2BmMod.onServerStopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Jm2BmMod.onServerStopped());
    }
}
