package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.server.HostModpack;
import pl.skidam.automodpack.utils.SetupFiles;
import pl.skidam.automodpack.utils.ShityCompressor;

import java.io.*;
import java.util.Objects;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.Others.config;

public class AutoModpackServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome to AutoModpack on Server!");

        new SetupFiles();
        genModpack();

        // mod check
        ServerLoginNetworking.registerGlobalReceiver(AM_CHECK, this::onClientResponse);
        ServerLoginNetworking.registerGlobalReceiver(AM_LINK, this::onSuccess);
        ServerLoginConnectionEvents.QUERY_START.register(this::onLoginStart);
    }

    public static void genModpack() {
        File modpackDir = new File("./AutoModpack/modpack/");
        File modpackZip = new File("./AutoModpack/modpack.zip");
        File modpackModsDir = new File("./AutoModpack/modpack/mods/");
        File modpackConfDir = new File("./AutoModpack/modpack/config/");
        File serverModsDir = new File("./mods/");

        // sync mods
        if (config.sync_mods) {
            try {
                FileUtils.deleteDirectory(modpackModsDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // clone every mod except for the AutoModpack mod
        if (config.clone_mods || config.sync_mods) {
            LOGGER.info("Cloning mods from server to modpack");
            for (File file : Objects.requireNonNull(serverModsDir.listFiles())) {
                if (!file.getName().toLowerCase().contains("automodpack")) {
                    try {
                        FileUtils.copyFileToDirectory(file, modpackModsDir);
                    } catch (IOException e) {
                        LOGGER.error("Error while cloning mods from server to modpack");
                        e.printStackTrace();
                    }
                }
            }
        }

        if (modpackDir.exists() && Objects.requireNonNull(modpackModsDir.listFiles()).length >= 1 || Objects.requireNonNull(modpackConfDir.listFiles()).length >= 1) {
            LOGGER.info("Creating modpack");
            new ShityCompressor(modpackDir, modpackZip);
            LOGGER.info("Modpack created");
        }

        if (modpackZip.exists()) {
            if (Objects.requireNonNull(modpackModsDir.listFiles()).length < 1 && Objects.requireNonNull(modpackConfDir.listFiles()).length < 1) {
                LOGGER.info("Modpack found, but no mods or configs inside. Deleting modpack.");
                modpackZip.delete();
                return;
            }

            ServerLifecycleEvents.SERVER_STARTED.register(HostModpack::start);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostModpack.stop());
        }
    }

    private void onSuccess(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean b, PacketByteBuf packetByteBuf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        // Successfully sent link to client, client can join and play on server.
    }

    private void onLoginStart(ServerLoginNetworkHandler serverLoginNetworkHandler, MinecraftServer minecraftServer, PacketSender sender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {
        sender.sendPacket(AutoModpackMain.AM_CHECK, PacketByteBufs.empty());
    }

    private void onClientResponse(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {

        if(!understood || buf.readInt() != 1) {
            serverLoginNetworkHandler.disconnect(Text.of("You have to install \"AutoModpack\" mod to play on this server! https://github.com/Skidamek/AutoModpack/releases"));
        } else {
            // get minecraft player ip if player is in local network give him local address to modpack
            String playerIp = serverLoginNetworkHandler.getConnection().getAddress().toString();

            PacketByteBuf outBuf = PacketByteBufs.create();

            if (playerIp.contains("127.0.0.1")) {
                outBuf.writeString(HostModpack.modpackHostIpForLocalPlayers);
            } else {
                outBuf.writeString(AutoModpackMain.link);
            }

            sender.sendPacket(AutoModpackMain.AM_LINK, outBuf);

            LOGGER.info("Sent modpack link to client");
        }
    }
}
