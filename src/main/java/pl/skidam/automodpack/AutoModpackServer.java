package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import pl.skidam.automodpack.server.HostModpack;
import pl.skidam.automodpack.utils.SetupFiles;
import pl.skidam.automodpack.utils.ShityCompressor;

import java.io.*;
import java.util.ArrayList;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class AutoModpackServer implements DedicatedServerModInitializer {

    public static ArrayList<String> PlayersHavingAM = new ArrayList<>();

    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome to AutoModpack on Server!");

        // TODO add commands to gen modpack etc.

        // client did not respond in time, disconnect client 1.25 second after login
        ServerPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, (server, player, handler, buf, sender) -> {
            PlayersHavingAM.add(player.getName().asString());
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
