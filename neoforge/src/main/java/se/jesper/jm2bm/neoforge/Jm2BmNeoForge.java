package se.jesper.jm2bm.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import se.jesper.jm2bm.Jm2BmMod;

@Mod(value = Jm2BmMod.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class Jm2BmNeoForge {
    public Jm2BmNeoForge() {
        Jm2BmMod.initialize(FMLPaths.CONFIGDIR.get());
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        Jm2BmMod.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        Jm2BmMod.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppingEvent event) {
        Jm2BmMod.onServerStopping();
    }

    @SubscribeEvent
    public void serverStopped(ServerStoppedEvent event) {
        Jm2BmMod.onServerStopped();
    }
}
