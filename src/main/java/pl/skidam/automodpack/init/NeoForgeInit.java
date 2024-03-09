package pl.skidam.automodpack.init;

//#if NEOFORGE
//$$ import net.neoforged.bus.api.IEventBus;
//$$ import net.neoforged.bus.api.SubscribeEvent;
//$$ import net.neoforged.fml.common.Mod;
//$$ import net.neoforged.neoforge.common.NeoForge;
//$$ import net.neoforged.neoforge.event.RegisterCommandsEvent;
//$$ import pl.skidam.automodpack.ModpackGenAdditions;
//$$ import pl.skidam.automodpack.client.ScreenImpl;
//$$ import pl.skidam.automodpack.client.audio.AudioManager;
//$$ import pl.skidam.automodpack.modpack.Commands;
//$$ import pl.skidam.automodpack.networking.ModPackets;
//$$ import pl.skidam.automodpack_core.netty.HttpServer;
//$$ import pl.skidam.automodpack_loader_core.loader.LoaderManager;
//$$ import pl.skidam.automodpack_loader_core.loader.LoaderService;
//$$ import pl.skidam.automodpack_loader_core.screen.ScreenManager;
//$$
//$$ import static pl.skidam.automodpack_core.GlobalVariables.*;
//$$
//$$ @Mod(MOD_ID)
//$$ public class NeoForgeInit {
//$$     public NeoForgeInit(IEventBus eventBus) {
//$$          preload = false;
//$$          ScreenManager.INSTANCE = new ScreenImpl();
//$$
//$$          long start = System.currentTimeMillis();
//$$          LOGGER.info("Launching AutoModpack...");
//$$
//$$          // initialize httpserver
//$$          httpServer = new HttpServer();
//$$
//$$          if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.SERVER) {
//$$              if (serverConfig.generateModpackOnStart) {
//$$                  LOGGER.info("Generating modpack...");
//$$                  long genStart = System.currentTimeMillis();
//$$                  if (ModpackGenAdditions.generate()) {
//$$                      LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
//$$                  } else {
//$$                      LOGGER.error("Failed to generate modpack!");
//$$                  }
//$$              }
//$$              ModPackets.registerS2CPackets();
//$$          } else {
//$$              ModPackets.registerC2SPackets();
//$$              new AudioManager(eventBus);
//$$          }
//$$
//$$
//$$          LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
//$$     }
//$$
//$$     @Mod.EventBusSubscriber(modid = MOD_ID)
//$$     public static class events {
//$$         @SubscribeEvent
//$$         public static void onCommandsRegister(RegisterCommandsEvent event) {
//$$             Commands.register(event.getDispatcher());
//$$         }
//$$     }
//$$ }
//#endif