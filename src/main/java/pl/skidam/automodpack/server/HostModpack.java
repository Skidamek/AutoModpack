package pl.skidam.automodpack.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.utils.JarUtilities;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;

import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.AutoModpackServer.publicServerIP;
import static pl.skidam.automodpack.utils.ValidateURL.ValidateURL;

public class HostModpack {

    private static final Path MODPACK_FILE = Path.of(FabricLoader.getInstance().getGameDir().toFile() + "/AutoModpack/modpack.zip");
    public static HttpServer server = null;
    public static String modpackHostIp;
    public static String modpackHostIpForLocalPlayers;
    public static boolean isRunning;

    public static void init() {
        if (!Config.MODPACK_HOST || !Config.EXTERNAL_MODPACK_HOST.equals("")) {
            if (Config.EXTERNAL_MODPACK_HOST.equals("")) {
                LOGGER.info("Modpack host is disabled");
            } else if (ValidateURL(Config.EXTERNAL_MODPACK_HOST)) {
                // google drive link fixer (make it direct download link)
                if (Config.EXTERNAL_MODPACK_HOST.startsWith("https://drive.google.com/")) {

                    if (Config.EXTERNAL_MODPACK_HOST.contains("/file/d/")) {
                        Config.EXTERNAL_MODPACK_HOST = Config.EXTERNAL_MODPACK_HOST.replace("/file/d/", "/uc?id=");
                    }
                    if (Config.EXTERNAL_MODPACK_HOST.contains("/view?usp=sharing")) {
                        Config.EXTERNAL_MODPACK_HOST = Config.EXTERNAL_MODPACK_HOST.replace("/view?usp=sharing", "&confirm=true");
                    }

                    if (ValidateURL(Config.EXTERNAL_MODPACK_HOST)) {
                        new Config().save();
                    } else {
                        LOGGER.error("External modpack host is not valid");
                    }
                }

                // dropbox link fixer (make it direct download link)
                if (Config.EXTERNAL_MODPACK_HOST.startsWith("https://www.dropbox.com/s/")) {
                    if (Config.EXTERNAL_MODPACK_HOST.contains("?dl=0")) {
                        Config.EXTERNAL_MODPACK_HOST = Config.EXTERNAL_MODPACK_HOST.replace("?dl=0", "?dl=1");
                        if (ValidateURL(Config.EXTERNAL_MODPACK_HOST)) {
                            new Config().save();
                        } else {
                            LOGGER.error("External modpack host is not valid");
                        }
                    }
                }

                LOGGER.info("Using external host server: " + Config.EXTERNAL_MODPACK_HOST);
                link = Config.EXTERNAL_MODPACK_HOST;
                modpackHostIpForLocalPlayers = Config.EXTERNAL_MODPACK_HOST;

                start();
            } else {
                LOGGER.error("EXTERNAL_MODPACK_HOST is not valid url or is not end with /modpack");
            }
        } else {
            start();
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            isRunning = false;
        }
    }

    public static HttpServer start() {
        try {
            LOGGER.info("Starting modpack server...");

            String serverIpForOthers = publicServerIP;

            String localIp = InetAddress.getLocalHost().getHostAddress();
            if (!Config.HOST_EXTERNAL_IP_FOR_LOCAL_PLAYERS.equals("")) {
                localIp = Config.HOST_EXTERNAL_IP_FOR_LOCAL_PLAYERS;
            }
            String subUrl = "modpack";

            if (!Config.HOST_EXTERNAL_IP.equals("")) {
                serverIpForOthers = Config.HOST_EXTERNAL_IP;
                LOGGER.info("Using external IP: " + serverIpForOthers);
            }

            modpackHostIp = String.format("http://%s:%s/%s", serverIpForOthers, Config.HOST_PORT, subUrl);
            modpackHostIpForLocalPlayers = String.format("http://%s:%s/%s", localIp, Config.HOST_PORT, subUrl);

            server = HttpServer.create(new InetSocketAddress("0.0.0.0", Config.HOST_PORT), 0); // it can not work with HOST_EXTERNAL_IP need testing...
            server.createContext("/", HostModpack::handle);
            server.setExecutor(Executors.newFixedThreadPool(Config.HOST_THREAD_COUNT));
            server.start();

            link = modpackHostIp;

            LOGGER.info("Modpack host started at {} and {} for local players.", modpackHostIp, modpackHostIpForLocalPlayers);
            isRunning = true;
            return server;
        } catch (Exception e) {
            LOGGER.error("Failed to start the modpack server!", e);
            e.printStackTrace();
            isRunning = false;
            return null;
        }
    }

    public static void handle(HttpExchange exchange) throws IOException {
        if (Objects.equals(exchange.getRequestMethod(), "GET")) {


            OutputStream outputStream = exchange.getResponseBody();

            String subUrl = exchange.getRequestURI().getPath().substring(1);
//            LOGGER.info("Requested: " + subUrl);

            File pack;
            if (subUrl.equals("modpack")) {
                pack = MODPACK_FILE.toFile();
            } else {
                String mod = JarUtilities.getJarFileOfMod(subUrl);
                LOGGER.warn("Modpack host is trying to download mod: " + mod);
                pack = new File(modsPath + "\\" + mod);
                LOGGER.warn(pack.toString());
            }

            exchange.getResponseHeaders().add("User-Agent", "Java/AutoModpack-host");
            exchange.sendResponseHeaders(200, pack.length());

            FileInputStream fis = new FileInputStream(pack);
            BufferedInputStream bis = new BufferedInputStream(fis);

            // There was some good idea I guess, but well... it didn't work bc ipcache aren't always generated? idk..... https://pastebin.com/qDPH2Jpn  so if you want to make it better feel free to do it (super fun with json) :)
            if (exchange.getRequestHeaders().getFirst("X-Minecraft-Username") != null) {
                if (!exchange.getRequestHeaders().getFirst("X-Minecraft-Username").equals("other-packet")) {
                    String playerUsername = exchange.getRequestHeaders().getFirst("X-Minecraft-Username");
                    LOGGER.info("{} downloading {}", playerUsername, subUrl);
                }
            } else {
                LOGGER.info("Non-minecraft client downloading {}", subUrl);
            }

            bis.transferTo(outputStream);
            bis.close();
            fis.close();
            outputStream.flush();
            outputStream.close();

        } else {
            exchange.sendResponseHeaders(400, 0);
        }
    }
}