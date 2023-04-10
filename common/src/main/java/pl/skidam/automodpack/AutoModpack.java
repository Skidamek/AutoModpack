package pl.skidam.automodpack;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.modpack.Commands;
import pl.skidam.automodpack.modpack.HttpServer;
import pl.skidam.automodpack.modpack.Modpack;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.utils.MinecraftUserName;

import static pl.skidam.automodpack.StaticVariables.*;

public class AutoModpack {
    public static void onInitialize() {
        preload = false;
        LOGGER.info("AutoModpack is running on " + Platform.getPlatformType() + "!");

        Commands.register();

        if (Platform.getEnvironmentType().equals("SERVER")) {
            if (serverConfig.generateModpackOnStart) {
                LOGGER.info("Generating modpack...");
                Modpack.generate();
            }
            ModPackets.registerS2CPackets();

            ServerLifecycleEvents.SERVER_STARTED.register(server -> HttpServer.start());
            ServerLifecycleEvents.SERVER_STOPPING.register(server ->  HttpServer.stop());
        } else {
            MinecraftUserName.get(); // To save the username` to variable in MinecraftUserName class for later use
            ModPackets.registerC2SPackets();
            new AudioManager();
        }
    }
}