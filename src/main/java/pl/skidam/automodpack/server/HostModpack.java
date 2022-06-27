package pl.skidam.automodpack.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import pl.skidam.automodpack.config.Config;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static pl.skidam.automodpack.AutoModpackMain.*;

public class HostModpack implements HttpHandler {

    private static final Path MODPACK_FILE = Path.of(FabricLoader.getInstance().getGameDir().toFile() + "/AutoModpack/modpack.zip");
    private static HttpServer server = null;
    private static ExecutorService threadPool = null;
    public static String modpackHostIp;
    public static String modpackHostIpForLocalPlayers;
    private static String serverIpForOthers = "0.0.0.0";


    public static void stop() {
        if (!Config.MODPACK_HOST || !Config.EXTERNAL_MODPACK_HOST.equals("")) {
            return;
        }
        if (server != null) {
            server.stop(1);
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    public static void start(MinecraftServer minecraftServer) {

        if (!Config.MODPACK_HOST || !Config.EXTERNAL_MODPACK_HOST.equals("")) {
            LOGGER.info("Modpack host is disabled");
            if (!Config.EXTERNAL_MODPACK_HOST.equals("")) {
                if (validateURL(Config.EXTERNAL_MODPACK_HOST)) {
                    LOGGER.info("Using external host server: " + Config.EXTERNAL_MODPACK_HOST);
                    link = Config.EXTERNAL_MODPACK_HOST;
                    modpackHostIpForLocalPlayers = Config.EXTERNAL_MODPACK_HOST;
                } else {
                    LOGGER.error("EXTERNAL_MODPACK_HOST is not valid url or is not end with /modpack");
                }
            }
            return;
        }

        threadPool = Executors.newFixedThreadPool(Config.HOST_THREAD_COUNT, new ThreadFactoryBuilder().setNameFormat("AutoModpack-Modpack-Host-%d").build());

        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Starting modpack server...");

                String localIp = InetAddress.getLocalHost().getHostAddress();
                String subUrl = "modpack";

                if (!Config.HOST_EXTERNAL_IP.equals("")) {
                    if (validateURL(Config.HOST_EXTERNAL_IP)) {
                        serverIpForOthers = Config.HOST_EXTERNAL_IP;
                        LOGGER.info("Using external IP: " + serverIpForOthers);
                    } else {
                        LOGGER.error("External IP is not valid url or is not end with /modpack");
                        useIPV4Address();
                        LOGGER.warn("Using local ip: " + serverIpForOthers);
                    }
                }

                modpackHostIp = String.format("http://%s:%s/%s", serverIpForOthers, Config.HOST_PORT, subUrl);
                modpackHostIpForLocalPlayers = String.format("http://%s:%s/%s", localIp, Config.HOST_PORT, subUrl);

                server = HttpServer.create(new InetSocketAddress("0.0.0.0", Config.HOST_PORT), 0);
                server.createContext("/" + subUrl, new HostModpack());
                server.setExecutor(threadPool);
                server.start();

                link = modpackHostIp;

                LOGGER.info("Modpack host started at {} and {} for local players.", modpackHostIp, modpackHostIpForLocalPlayers);
            } catch (Exception e) {
                LOGGER.error("Failed to start the modpack server!", e);
            }
        }, threadPool);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (Objects.equals(exchange.getRequestMethod(), "GET")) {
            OutputStream outputStream = exchange.getResponseBody();
            File pack = MODPACK_FILE.toFile();

            exchange.getResponseHeaders().add("User-Agent", "Java/modpack-host");
            exchange.sendResponseHeaders(200, pack.length());

            FileInputStream fis = new FileInputStream(pack);
            BufferedInputStream bis = new BufferedInputStream(fis);
            bis.transferTo(outputStream);
            bis.close();
            fis.close();

            outputStream.flush();
            outputStream.close();
        } else {
            exchange.sendResponseHeaders(400, 0);
        }
    }

    private static void useIPV4Address() {
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
            serverIpForOthers = s.next();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean validateURL(String url) {
        String localIp = "0.0.0.0";
        try {
            localIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) { // ignore
        }
        if (!url.isEmpty() && !url.equals(localIp) && !url.equals("0.0.0.0") && !url.equals("localhost")) {
            try {
                URI URI = new URI(url);
                String string = URI.getScheme();
                if ("http".equals(string) || "https".equals(string) || "level".equals(string)) {
                    if (!"level".equals(string) || !url.contains("..") && url.endsWith("/modpack")) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            } catch (URISyntaxException e) {
                return false;
            }
        } else {
            return false;
        }
    }
}
