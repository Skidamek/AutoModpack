package pl.skidam.automodpack;

import com.google.gson.*;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.server.HostModpack;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.UnZipper;
import pl.skidam.automodpack.utils.Zipper;

import java.io.*;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Objects;
import java.util.Scanner;

import static org.apache.commons.lang3.ArrayUtils.contains;
import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.GetIPV4Address.getIPV4Address;
import static pl.skidam.automodpack.utils.JarUtilities.correctName;

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
            genModpack();
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

    private static void onSuccessJoin(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity, ServerPlayNetworkHandler serverPlayNetworkHandler, PacketByteBuf buf, PacketSender sender) {
        // Successfully sent link to client, client can join and play on server.
        if (!buf.readString().equals("1")) {
            if (!Config.ONLY_OPTIONAL_MODPACK) { // Accept player to join while optional modpack is enabled
                serverPlayNetworkHandler.disconnect(Text.of("You have to install \"AutoModpack\" mod to play on this server! https://modrinth.com/mod/automodpack/versions"));
                LOGGER.warn("Player " + serverPlayNetworkHandler.getPlayer().getName().getString() + " has not installed \"AutoModpack\" mod");
            } else {
                LOGGER.warn("Player " + serverPlayNetworkHandler.getPlayer().getName().getString()  + " has not installed modpack");
            }
        }
    }

    private static void onJoinStart(ServerPlayNetworkHandler serverPlayNetworkHandler, PacketSender sender, MinecraftServer minecraftServer) {
        // Get minecraft player ip if player is in local network give him local address to modpack
        String playerIp = serverPlayNetworkHandler.getConnection().getAddress().toString();
        PacketByteBuf outBuf = PacketByteBufs.create();
        String HostIPForLocal = HostModpack.modpackHostIpForLocalPlayers.substring(HostModpack.modpackHostIpForLocalPlayers.indexOf("/")+2); // Removes HTTP:// or HTTPS://
        String HostNetwork = HostIPForLocal.substring(0, HostIPForLocal.indexOf('.', HostIPForLocal.indexOf('.')+1)+1); // Reduces ip from x.x.x.x to x.x.

        if (playerIp.contains("127.0.0.1") || playerIp.contains(publicServerIP) || playerIp.startsWith("/"+HostNetwork)) {
            outBuf.writeString(HostModpack.modpackHostIpForLocalPlayers);
        } else {
            outBuf.writeString(AutoModpackMain.link);
        }
        sender.sendPacket(AutoModpackMain.AM_LINK, outBuf);
        LOGGER.info("Sent link to " + serverPlayNetworkHandler.getPlayer().getName().getString() + " through velocity");
    }

    private static void onLoginStart(ServerLoginNetworkHandler serverLoginNetworkHandler, MinecraftServer minecraftServer, PacketSender sender, ServerLoginNetworking.LoginSynchronizer loginSynchronizer) {
        // Get minecraft player ip if player is in local network give him local address to modpack
        String playerIp = serverLoginNetworkHandler.getConnection().getAddress().toString();
        PacketByteBuf outBuf = PacketByteBufs.create();
        String HostIPForLocal = HostModpack.modpackHostIpForLocalPlayers.substring(HostModpack.modpackHostIpForLocalPlayers.indexOf("/")+2); // Removes HTTP:// or HTTPS://
        String HostNetwork = HostIPForLocal.substring(0, HostIPForLocal.indexOf('.', HostIPForLocal.indexOf('.')+1)+1); // Reduces ip from x.x.x.x to x.x.

        if (playerIp.contains("127.0.0.1") || playerIp.contains(publicServerIP) || playerIp.startsWith("/"+HostNetwork)) {
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

    public static void genModpack() { // TODO optimize loops

        if (!Config.SYNC_MODS) {
            autoExcludeMods();
        }

        clientMods();

        // Sync mods and automatically generate delmods.txt
        if (Config.SYNC_MODS) {
            LOGGER.info("Synchronizing mods from server to modpack");

            // Make array of mods
            oldMods = modpackModsDir.list();
            deleteAllMods();
            cloneMods();
            autoExcludeMods();
            clientMods();
            newMods = modpackModsDir.list();

            // client mods list
            clientMods = modpackClientModsDir.list();

            // Changelog generation
            SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd");
            String date = ft.format(new Date());

            // Check how many files is in the changelogsDir containing the date
            String[] changelogs = changelogsDir.list();
            int changelogsCount = 1;
            assert changelogs != null;
            for (String changelog : changelogs) {
                if (changelog.contains(date)) {
                    changelogsCount++;
                }
            }

            // Create changelog file
            File changelog = new File(changelogsDir + "/" + "changelog-" + date + "-" + changelogsCount + ".txt");
            try {
                changelog.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }

            assert newMods != null;
            for (String mod : newMods) {
                if (!contains(oldMods, mod)) {
                    // Added mod
                    try {
                        FileUtils.writeStringToFile(changelog, " + " + mod + "\n", Charset.defaultCharset(),true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Compare new to old mods and generate delmods.txt
            assert oldMods != null;
            for (String mod : oldMods) {
                if (!contains(newMods, mod) && !contains(clientMods, mod) && !mod.equals(correctName)) { // fix to #34
                    try {
                        // Check if mod is not already in delmods.txt
                        if (!FileUtils.readLines(modpackDeleteTxt, Charset.defaultCharset()).contains(mod)) {
                            LOGGER.info("Writing " + mod + " to delmods.txt");
                            FileUtils.writeStringToFile(modpackDeleteTxt, mod + "\n", Charset.defaultCharset(), true);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Deleted mod
                    try {
                        FileUtils.writeStringToFile(changelog, " - " + mod + "\n", Charset.defaultCharset(), true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Check if changelog is empty
            try {
                if (FileUtils.readLines(changelog, Charset.defaultCharset()).isEmpty()) {
                    changelog.delete();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Check if in delmods.txt there are not mods which are in serverModsDir
            try {
                for (String delMod : FileUtils.readLines(modpackDeleteTxt, Charset.defaultCharset())) {
                    for (File file : Objects.requireNonNull(modsPath.toFile().listFiles())) {
                        String FNLC = file.getName().toLowerCase(); // FileNameLowerCase
                        if (FNLC.endsWith(".jar") && !FNLC.contains("automodpack")) {
                            if (file.getName().equals(delMod)) {
                                LOGGER.error("Removing " + delMod + " from delmods.txt");
                                Scanner sc = new Scanner(modpackDeleteTxt);
                                StringBuilder sb = new StringBuilder();
                                while (sc.hasNextLine()) {
                                    sb.append(sc.nextLine()).append("\n");
                                }
                                sc.close();
                                String result = sb.toString();
                                result = result.replace(delMod, "");
                                PrintWriter writer = new PrintWriter(modpackDeleteTxt);
                                writer.append(result);
                                writer.flush();
                                writer.close();
                            }
                        }
                    }

                    if (delMod.equals(correctName)) {
                        Scanner sc = new Scanner(modpackDeleteTxt);
                        StringBuilder sb = new StringBuilder();
                        while (sc.hasNextLine()) {
                            sb.append(sc.nextLine()).append("\n");
                        }
                        sc.close();
                        String result = sb.toString();
                        result = result.replace(delMod, "");
                        PrintWriter writer = new PrintWriter(modpackDeleteTxt);
                        writer.append(result);
                        writer.flush();
                        writer.close();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Remove blank lines from delmods.txt
        try {
            Scanner file = new Scanner(modpackDeleteTxt);
            PrintWriter writer = new PrintWriter(modpackDeleteTxt + ".tmp");

            while (file.hasNext()) {
                String line = file.nextLine();
                if (!line.isEmpty()) {
                    writer.write(line);
                    writer.write("\n");
                }
            }

            file.close();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        FileUtils.deleteQuietly(modpackDeleteTxt);
        if (!new File (modpackDeleteTxt + ".tmp").renameTo(modpackDeleteTxt)) {
            LOGGER.error("Failed to rename " + modpackDeleteTxt + ".tmp to " + modpackDeleteTxt);
        }

        try {
            File blacklistModsTxt = new File("./AutoModpack/blacklistMods.txt");
            if (!FileUtils.readLines(blacklistModsTxt, Charset.defaultCharset()).isEmpty()) {
                for (String mod : FileUtils.readLines(blacklistModsTxt, Charset.defaultCharset())) {
                    try {
                        FileUtils.writeStringToFile(modpackDeleteTxt, mod + "\n", Charset.defaultCharset(), true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    File modFile = new File(modpackModsDir + "/" + mod);
                    if (modFile.exists()) {
                        if (modFile.delete()) {
                            LOGGER.info("Excluded " + modFile.getName() + " from modpack");
                        } else {
                            LOGGER.error("Could not delete blacklisted mod " + modFile.getName() + " from modpack mods");
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
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

        // TODO GDRIVE UPLOAD
//        if (Config.EXTERNAL_MODPACK_HOST.startsWith("https://drive.google.com/drive/")) {
//            LOGGER.warn("Uploading modpack to Google Drive");
//            try {
//                GoogleDriveUpload.uploadModpack();
//            } catch (IOException | GeneralSecurityException e) {
//                LOGGER.error("Failed upload modpack to Google Drive\n" + e);
//            }
//        }
    }

    private static void cloneMods() {
        for (File file : Objects.requireNonNull(modsPath.toFile().listFiles())) {
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

    private static void autoExcludeMods() {
        if (!Config.AUTO_EXCLUDE_SERVER_SIDE_MODS) return;
        LOGGER.info("Excluding server-side mods from modpack");

        Collection modList = JarUtilities.getListOfModsIDS();

        // make for loop for each mod in modList
        for (Object mod : modList) {
            String modId = mod.toString().split(" ")[0];
            String environment = FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getEnvironment().toString().toLowerCase() : "null";
            if (environment.equals("server")) {
                String modName = FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getName() : "null";
                File serverSideModInModpack = new File(modpackModsDir + "/" + modName);
                if (serverSideModInModpack.exists()) {
                    FileUtils.deleteQuietly(serverSideModInModpack);
                    for (String oldMod : oldMods) { // log to console if this mod was in modpack before
                        if (oldMod.equals(serverSideModInModpack.getName())) {
                            LOGGER.info(modName + " is server-side mod and has been auto excluded from modpack");
                        }
                    }
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
}