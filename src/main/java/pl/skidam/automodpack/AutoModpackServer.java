package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
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


//        ServerLoginNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, this::onClientResponse);
//        ServerLoginNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_C2S, (MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean b, PacketByteBuf packetByteBuf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender packetSender) -> {
//            LOGGER.error("10Client responses!" + packetByteBuf.readInt());
//            LOGGER.error("20Client responses!");
//
//            minecraftServer.execute(() -> {
//                LOGGER.error("30Client responses!" + packetByteBuf.readInt());
//                LOGGER.error("40Client responses!");
//            });
//        });
//
//        ServerLoginNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, this::onClientResponse);
//        ServerLoginNetworking.registerGlobalReceiver(AutoModpackMain.PACKET_S2C, (MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean b, PacketByteBuf packetByteBuf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender packetSender) -> {
//            LOGGER.error("110Client responses!" + packetByteBuf.readInt());
//            LOGGER.error("220Client responses!");
//
//            minecraftServer.execute(() -> {
//                LOGGER.error("330Client responses!" + packetByteBuf.readInt());
//                LOGGER.error("440Client responses!");
//            });
//        });
//
//        ServerLoginConnectionEvents.QUERY_START.register(this::onLoginStart);


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

//    private void onLoginStart(ServerLoginNetworkHandler serverLoginNetworkHandler, MinecraftServer minecraftServer, PacketSender packetSender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {
//        LOGGER.error("I am here (SERVER123)");
//    }
//
//    private void onClientResponse(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean b, PacketByteBuf packetByteBuf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender packetSender) {
//        LOGGER.error("1Client responses!" + packetByteBuf.readInt());
//        LOGGER.error("2Client responses!");
//
//        minecraftServer.execute(() -> {
//            LOGGER.error("3Client responses!" + packetByteBuf.readInt());
//            LOGGER.error("4Client responses!");
//        });
//    }
}
