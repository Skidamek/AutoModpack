package pl.skidam.automodpack.forge;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.forge.networking.ModPackets;
import pl.skidam.automodpack.forge.networking.packet.LoginS2CPacket;

@Mod(AutoModpack.MOD_ID)
public class AutoModpackForge {

    public AutoModpackForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        ModPackets.register();
        AutoModpack.onPreInitialize();
        AutoModpack.onInitialize();
    }

    // That's a bit slow way...
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayerEntity player = (ServerPlayerEntity) event.getEntity();
        ModPackets.sendToClient(new LoginS2CPacket(AutoModpack.VERSION), player);
    }


    /*
     TODO make it work by this we could send packets much faster and slow down the login
      process which would be helpful to have then less bugs because of this we could
      send packets that client don't/downloads modpack and then login them

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onPlayerNegotiation(PlayerNegotiationEvent event) {
            String username = event.getProfile().getName();
            AutoModpack.LOGGER.error(username + " is connecting to server!");

            event.enqueueWork(CompletableFuture.runAsync(() -> {
                onPlayerNegotiationAsync(event.getConnection(), username);
            }));
        }

        private void onPlayerNegotiationAsync(ClientConnection connection, String username) {
            connection.send(new LoginS2CPacket(AutoModpack.VERSION));
            AutoModpack.LOGGER.error("Sent login packets to " + username);
        }



        // There as well...
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onPlayerLoadFromFile(PlayerEvent.LoadFromFile event) {

        }
    */

}
