package pl.skidam.automodpack.init;

//#if FORGE
//$$ import pl.skidam.automodpack.ModpackGenAdditions;
//$$ import pl.skidam.automodpack.client.ScreenImpl;
//$$ import pl.skidam.automodpack.client.audio.AudioManager;
//$$ import pl.skidam.automodpack.modpack.Commands;
//$$ import pl.skidam.automodpack.networking.ModPackets;
//$$ import pl.skidam.automodpack_loader_core.loader.LoaderManager;
//$$ import pl.skidam.automodpack_loader_core.loader.LoaderService;
//$$ import pl.skidam.automodpack_loader_core.screen.ScreenManager;
//$$ import pl.skidam.automodpack_core.netty.HttpServer;
//$$
//$$ import static pl.skidam.automodpack_core.GlobalVariables.*;
//$$ import net.minecraftforge.common.MinecraftForge;
//$$ import net.minecraftforge.event.RegisterCommandsEvent;
//$$ import net.minecraftforge.eventbus.api.SubscribeEvent;
//$$ import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//$$ import net.minecraftforge.fml.common.Mod;
//$$
//$$ @Mod(MOD_ID)
//$$ public class ForgeInit {
//$$
//$$     public ForgeInit() {
//$$         preload = false;
//$$         ScreenManager.INSTANCE = new ScreenImpl();
//$$
//$$         long start = System.currentTimeMillis();
//$$         LOGGER.info("Launching AutoModpack...");
//$$
//$$         // initialize httpserver
//$$         httpServer = new HttpServer();
//$$
//$$         if (new LoaderManager().getEnvironmentType() == LoaderService.EnvironmentType.SERVER) {
//$$             if (serverConfig.generateModpackOnStart) {
//$$                 LOGGER.info("Generating modpack...");
//$$                 long genStart = System.currentTimeMillis();
//$$                 if (ModpackGenAdditions.generate()) {
//$$                     LOGGER.info("Modpack generated! took " + (System.currentTimeMillis() - genStart) + "ms");
//$$                 } else {
//$$                     LOGGER.error("Failed to generate modpack!");
//$$                 }
//$$             }
//$$             ModPackets.registerS2CPackets();
//$$         } else {
//$$             ModPackets.registerC2SPackets();
//$$             new AudioManager(FMLJavaModLoadingContext.get().getModEventBus());
//$$         }
//$$
//$$
//$$         LOGGER.info("AutoModpack launched! took " + (System.currentTimeMillis() - start) + "ms");
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