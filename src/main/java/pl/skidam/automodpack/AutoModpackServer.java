package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import pl.skidam.automodpack.Server.HostModpack;
import pl.skidam.automodpack.utils.SetupFiles;
import pl.skidam.automodpack.utils.ShityCompressor;

import java.io.*;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class AutoModpackServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome to AutoModpack on Server!");

        // TODO generate configs for the server
        // TODO add chad integration to the server who when you join the server, it will download the mods and update the mods by ping the server -- networking
        // TODO kick player if they don't have the AutoModpack
        // TODO add commands to gen modpack etc.



        ServerPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, (minecraftServer, handler, buf, responseSender, client) -> {
            LOGGER.error("Received packet from client!");
        });


        new SetupFiles();

        File modpackDir = new File("./AutoModpack/modpack/");
        File modpackZip = new File("./AutoModpack/modpack.zip");

        if (modpackDir.exists() && modpackDir.getTotalSpace() > 1) {
            LOGGER.info("Creating modpack zip");
            new ShityCompressor(modpackDir, modpackZip);
            LOGGER.info("Modpack zip created");
        }

        if (modpackZip.exists()) {
            ServerLifecycleEvents.SERVER_STARTED.register(HostModpack::start);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostModpack.stop());
        }
    }
}
