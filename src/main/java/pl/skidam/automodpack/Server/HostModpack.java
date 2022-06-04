package pl.skidam.automodpack.Server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class HostModpack implements HttpHandler {

    private static final Path MODPACK_FILE = Path.of(FabricLoader.getInstance().getGameDir().toFile() + "/AutoModpack/modpack.zip");

    private static HttpServer server = null;
    private static ExecutorService threadPool = null;
    public static int port = 30037;

    public static String modpackIp;

    public static void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }


    public static void start(MinecraftServer minecraftServer) {
        threadPool = Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setNameFormat("AutoModpack-Modpack-Host-%d").build());

        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Starting modpack server...");;

                String serverIp = InetAddress.getLocalHost().getHostAddress();
                String subUrl = "modpack";

                server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
                server.createContext("/" + subUrl, new HostModpack());
                server.setExecutor(threadPool);
                server.start();

                modpackIp = String.format("http://%s:%s/%s", serverIp, port, subUrl);

                String hash = String.format("%040x", new BigInteger(1, MessageDigest
                        .getInstance("SHA-1")
                        .digest(new FileInputStream(MODPACK_FILE.toString()).readAllBytes()))
                );

                LOGGER.info("Modpack host started at {} (Hash: {})", modpackIp, hash);
            } catch (Exception e) {
                LOGGER.error("Failed to start the modpack server!", e);
            }
        }, HostModpack.threadPool);

    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (Objects.equals(exchange.getRequestMethod(), "GET")) {
//            LOGGER.error(exchange.getRequestURI().toString());
//            LOGGER.error(exchange.getRequestHeaders().toString());
//            if (exchange.getRequestHeaders().getFirst("X-Minecraft-Username") != null) {
//                LOGGER.info("Supplying modpack for Minecraft player: {}", exchange.getRequestHeaders().getFirst("X-Minecraft-Username"));
//            } else {
//                LOGGER.info("Supplying modpack to a non-Minecraft client");
//            }

            LOGGER.info("Supplying modpack to the client");

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
}
