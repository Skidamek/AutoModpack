package pl.skidam.automodpack;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.server.GenerateModpack;
import pl.skidam.automodpack.server.HostModpack;

import java.io.*;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.GetIPV4Address.getIPV4Address;

public class AutoModpackServer implements DedicatedServerModInitializer {

    public static final File changelogsDir = new File("./AutoModpack/changelogs/");
    public static final File modpackDir = new File("./AutoModpack/modpack/");
    public static final File modpackZip = new File("./AutoModpack/modpack.zip");
    public static final File modpackClientModsDir = new File("./AutoModpack/modpack/[CLIENT] mods/");
    public static final File modpackModsDir = new File("./AutoModpack/modpack/mods/");
    // public static final File modpackConfDir = new File("./AutoModpack/modpack/config/");
    public static final File modpackDeleteTxt = new File("./AutoModpack/modpack/delmods.txt");
    public static String publicServerIP;
    public static File tempDir = new File("./AutoModpack/temp/");
    public static String[] oldMods;
    public static String[] newMods;
    public static String[] clientMods;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Welcome to AutoModpack on Server!");

        HostModpack.isRunning = false;

        publicServerIP = getIPV4Address();

        if (Config.GENERATE_MODPACK_ON_LAUNCH) {
            new GenerateModpack();
        }

        // Packets
        if (!isVelocity) {
            ServerLoginConnectionEvents.QUERY_START.register(AutoModpackServer::onLoginStart);
            ServerLoginNetworking.registerGlobalReceiver(AM_LINK, AutoModpackServer::onSuccessLogin);
        }

        // For velocity support
        if (isVelocity) {
            ServerPlayConnectionEvents.JOIN.register(AutoModpackServer::onJoinStart);
            ServerPlayNetworking.registerGlobalReceiver(AM_LINK, AutoModpackServer::onSuccessJoin);
        }

        if (modpackZip.exists()) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> HostModpack.init());
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> HostModpack.stop());
        }
    }

    private static void onJoinStart(ServerPlayNetworkHandler serverPlayNetworkHandler, PacketSender sender, MinecraftServer minecraftServer) {
        // Get minecraft player ip if player is in local network give him local address to modpack
        String playerIp = serverPlayNetworkHandler.getConnection().getAddress().toString();
        PacketByteBuf outBuf = PacketByteBufs.create();
        String HostIPForLocal = HostModpack.modpackHostIpForLocalPlayers.substring(HostModpack.modpackHostIpForLocalPlayers.indexOf("/") + 2); // Removes HTTP:// or HTTPS://
        String HostNetwork = HostIPForLocal.substring(0, HostIPForLocal.indexOf('.', HostIPForLocal.indexOf('.') + 1) + 1); // Reduces ip from x.x.x.x to x.x.

        if (playerIp.contains("127.0.0.1") || playerIp.contains(publicServerIP) || playerIp.startsWith("/" + HostNetwork)) {
            outBuf.writeString(HostModpack.modpackHostIpForLocalPlayers);
        } else {
            outBuf.writeString(AutoModpackMain.link);
        }
        sender.sendPacket(AutoModpackMain.AM_LINK, outBuf);
        LOGGER.info("Sent link to " + serverPlayNetworkHandler.getPlayer().getName().getString() + " through proxy");
    }

    private static void onSuccessJoin(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity, ServerPlayNetworkHandler serverPlayNetworkHandler, PacketByteBuf buf, PacketSender sender) {
        // Successfully sent link to client, client can join and play on server.
        if (!buf.readString().equals("1")) {
            if (!Config.ONLY_OPTIONAL_MODPACK) { // Accept player to join while optional modpack is enabled
                serverPlayNetworkHandler.disconnect(Text.of("You have to install \"AutoModpack\" mod to play on this server! https://modrinth.com/mod/automodpack/versions"));
                LOGGER.warn("Player " + serverPlayNetworkHandler.getPlayer().getName().getString() + " has not installed \"AutoModpack\" mod");
            } else {
                LOGGER.warn("Player " + serverPlayNetworkHandler.getPlayer().getName().getString() + " has not installed modpack");
            }
        }
    }

    private static void onLoginStart(ServerLoginNetworkHandler serverLoginNetworkHandler, MinecraftServer minecraftServer, PacketSender sender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {
        // Get minecraft player ip if player is in local network give him local address to modpack
        String playerIp = serverLoginNetworkHandler.getConnection().getAddress().toString();
        PacketByteBuf outBuf = PacketByteBufs.create();
        String HostIPForLocal = HostModpack.modpackHostIpForLocalPlayers.substring(HostModpack.modpackHostIpForLocalPlayers.indexOf("/") + 2); // Removes HTTP:// or HTTPS://
        String HostNetwork = HostIPForLocal.substring(0, HostIPForLocal.indexOf('.', HostIPForLocal.indexOf('.') + 1) + 1); // Reduces ip from x.x.x.x to x.x.

        if (playerIp.contains("127.0.0.1") || playerIp.contains(publicServerIP) || playerIp.startsWith("/" + HostNetwork)) {
            outBuf.writeString(HostModpack.modpackHostIpForLocalPlayers);
        } else {
            outBuf.writeString(AutoModpackMain.link);
        }
        sender.sendPacket(AutoModpackMain.AM_LINK, outBuf);
        LOGGER.info("Sent link to " + getPlayerNickInLogin(serverLoginNetworkHandler.getConnectionInfo()));
    }

    private static void onSuccessLogin(MinecraftServer minecraftServer, ServerLoginNetworkHandler serverLoginNetworkHandler, boolean understood, PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer loginSynchronizer, PacketSender sender) {
        // Successfully sent link to client, client can join and play on server.
        if (!understood || !buf.readString().equals("1")) {
            if (!Config.ONLY_OPTIONAL_MODPACK) { // Accept player to join while optional modpack is enabled
                serverLoginNetworkHandler.disconnect(Text.of("You have to install \"AutoModpack\" mod to play on this server! https://modrinth.com/mod/automodpack/versions"));
            }
            LOGGER.warn("Player " + getPlayerNickInLogin(serverLoginNetworkHandler.getConnectionInfo()) + " has not installed \"AutoModpack\" mod");
        }
    }

    private static String getPlayerNickInLogin(String connectionInfo) {
        String nick;
        try {
            nick = connectionInfo.split("name=")[1];
            nick = nick.split(",")[0];
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return nick;
    }
}