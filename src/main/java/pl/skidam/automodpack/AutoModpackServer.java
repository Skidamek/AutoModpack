package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.server.HostModpack;
import pl.skidam.automodpack.utils.Zipper;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Objects;

import static org.apache.commons.lang3.ArrayUtils.contains;
import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.getIPV4Adress.getIPV4Address;

public class AutoModpackServer implements DedicatedServerModInitializer {

    public static final File modpackDir = new File("./AutoModpack/modpack/");
    public static final File modpackZip = new File("./AutoModpack/modpack.zip");
    public static final File modpackClientModsDir = new File("./AutoModpack/modpack/[CLIENT] mods/");
    public static final File modpackModsDir = new File("./AutoModpack/modpack/mods/");
    // public static final File modpackConfDir = new File("./AutoModpack/modpack/config/");
    public static final File modpackDeleteTxt = new File("./AutoModpack/modpack/delmods.txt");
    public static final File serverModsDir = new File("./mods/");
    public static String publicServerIP;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome to AutoModpack on Server!");

        publicServerIP = getIPV4Address();

        genModpack();

        // Packets
        ServerLoginNetworking.registerGlobalReceiver(AM_CHECK, this::onClientResponse);
        ServerLoginNetworking.registerGlobalReceiver(AM_LINK, this::onSuccess);
        ServerLoginConnectionEvents.QUERY_START.register(this::onLoginStart);

        if (modpackZip.exists()) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> HostModpack.start());
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostModpack.stop());
        }
    }

    public static void genModpack() {

        clientMods();

        // sync mods/clone mods and automatically generate delmods.txt
        if (Config.SYNC_MODS) {
            LOGGER.info("Synchronizing mods from server to modpack");

            // Make array of mods
            String[] oldMods = modpackModsDir.list();
            deleteAllMods();
            cloneMods();
            clientMods();
            String[] newMods = modpackModsDir.list();
            // Compare new to old mods and generate delmods.txt
            assert oldMods != null;
            for (String mod : oldMods) {
                if (!contains(newMods, mod)) {
                    try {
                        // Check if mod is not already in delmods.txt
                        if (!FileUtils.readLines(modpackDeleteTxt, Charset.defaultCharset()).contains(mod)) {
                            LOGGER.info("Writing " + mod + " to delmods.txt");
                            FileUtils.writeStringToFile(modpackDeleteTxt, mod + "\n", Charset.defaultCharset(), true);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Check if in delmods.txt there are not mods which are in serverModsDir
            try {
                for (String delMod : FileUtils.readLines(modpackDeleteTxt, Charset.defaultCharset())) {
                    if (Objects.requireNonNull(serverModsDir.listFiles()).length > 0) {
                        for (File file : Objects.requireNonNull(serverModsDir.listFiles())) {
                            String FNLC = file.getName().toLowerCase(); // fileNameLowerCase
                            if (FNLC.endsWith(".jar") && !FNLC.contains("automodpack")) {
                                if (FNLC.equals(delMod)) {
                                    LOGGER.info("Removing " + delMod + " from delmods.txt");
                                    FileUtils.deleteQuietly(modpackDeleteTxt);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        LOGGER.info("Creating modpack");
        if (modpackZip.exists()) {
            FileUtils.deleteQuietly(modpackZip);
        }

        try {
            new Zipper(modpackDir, modpackZip);
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.error(e.getMessage());
        }

        LOGGER.info("Modpack created");
    }

    private static void cloneMods() {
        for (File file : Objects.requireNonNull(serverModsDir.listFiles())) {
            if (file.getName().endsWith(".jar") && !file.getName().toLowerCase().contains("automodpack")) {
                try {
                    FileUtils.copyFileToDirectory(file, modpackModsDir);
                } catch (IOException e) {
                    LOGGER.error("Error while cloning mods from server to modpack");
                    e.printStackTrace();
                }
            }
        }
    }

    private static void clientMods() {
        for (File file : Objects.requireNonNull(modpackClientModsDir.listFiles())) {
            if (file.getName().endsWith(".jar") && !file.getName().toLowerCase().contains("automodpack")) {
                try {
                    FileUtils.copyFileToDirectory(file, modpackModsDir);
                } catch (IOException e) {
                    LOGGER.error("Error while cloning mods from client to modpack");
                    e.printStackTrace();
                }
            }
        }
    }

    private static void deleteAllMods() {
        for (File file : Objects.requireNonNull(modpackModsDir.listFiles())) {
            if (!file.delete()) {
                LOGGER.error("Error while deleting the file: " + file);
            }
        }
    }

    private void onSuccess(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean b, PacketByteBuf packetByteBuf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        // Successfully sent link to client, client can join and play on server.
    }

    private void onLoginStart(ServerLoginNetworkHandler serverLoginNetworkHandler, MinecraftServer minecraftServer, PacketSender sender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {
        sender.sendPacket(AutoModpackMain.AM_CHECK, PacketByteBufs.empty());
    }

    private void onClientResponse(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {

        if (!understood || buf.readInt() != 1) {
            if (!Config.ONLY_OPTIONAL_MODPACK) { // Accept player to join while optional modpack is enabled // TODO make it better
                serverLoginNetworkHandler.disconnect(Text.of("You have to install \"AutoModpack\" mod to play on this server! https://modrinth.com/mod/automodpack/versions"));
            }

        } else {
            // Get minecraft player ip if player is in local network give him local address to modpack
            String playerIp = serverLoginNetworkHandler.getConnection().getAddress().toString();

            PacketByteBuf outBuf = PacketByteBufs.create();

            if (playerIp.contains("127.0.0.1") || playerIp.contains(publicServerIP)) {
                outBuf.writeString(HostModpack.modpackHostIpForLocalPlayers);
            } else {
                outBuf.writeString(AutoModpackMain.link);
            }

            sender.sendPacket(AutoModpackMain.AM_LINK, outBuf);
        }
    }
}