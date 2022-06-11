package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import pl.skidam.automodpack.server.HostModpack;
import pl.skidam.automodpack.utils.SetupFiles;
import pl.skidam.automodpack.utils.ShityCompressor;

import java.io.*;
import java.util.ArrayList;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class AutoModpackServer implements DedicatedServerModInitializer {

    public static ArrayList<String> PlayersHavingAM = new ArrayList<>();

    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome to AutoModpack on Server!");

        // TODO add commands to gen modpack etc.

        // client did not respond in time, disconnect client 1.25 second after login
//        ServerPlayNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, (server, player, handler, buf, sender) -> {
//            PlayersHavingAM.add(player.getName().asString());
//        });


        new SetupFiles();

        File modpackDir = new File("./AutoModpack/modpack/");
        File modpackZip = new File("./AutoModpack/modpack.zip");
        File modsDir = new File("./AutoModpack/modpack/mods/");
        File confDir = new File("./AutoModpack/modpack/config/");

        if (modpackDir.exists() && Objects.requireNonNull(modsDir.listFiles()).length >= 1 || Objects.requireNonNull(confDir.listFiles()).length >= 1) {
            LOGGER.info("Creating modpack");
            new ShityCompressor(modpackDir, modpackZip);
            LOGGER.info("Modpack created");
        }

        if (modpackZip.exists()) {
            if (Objects.requireNonNull(modsDir.listFiles()).length < 1 && Objects.requireNonNull(confDir.listFiles()).length < 1) {
                LOGGER.info("Modpack found, but no mods or configs inside. Deleting modpack.");
                modpackZip.delete();
                return; // idk if it will work
            }
            ServerLifecycleEvents.SERVER_STARTED.register(HostModpack::start);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostModpack.stop());
        }


        // mod check
        ServerLoginNetworking.registerGlobalReceiver(AutoModpackMain.AM_CHECK, this::onClientResponse);
        ServerLoginConnectionEvents.QUERY_START.register(this::onLoginStart);
    }

    private void onLoginStart(ServerLoginNetworkHandler serverLoginNetworkHandler, MinecraftServer minecraftServer, PacketSender sender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {

        sender.sendPacket(AutoModpackMain.AM_CHECK, PacketByteBufs.empty());

    }

    private void onClientResponse(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {

        if(!understood || buf.readInt() != 1) {
            serverLoginNetworkHandler.disconnect(Text.of("Install AM"));
        } else {
            LOGGER.info("Client understood packet");
        }
    }


//    private void onLoginStart(ServerLoginNetworkHandler serverLoginPacketListener, MinecraftServer server, PacketSender sender, ServerLoginNetworking.LoginSynchronizer sync)
//    {
//        //request the client to send its sit version number
//        sender.sendPacket(PACKET_S2C, PacketByteBufs.empty());
//    }
}
