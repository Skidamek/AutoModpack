package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.Ip;
import pl.skidam.automodpack.utils.Url;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static pl.skidam.automodpack.modpack.Modpack.hostModpackContentFile;
import static pl.skidam.automodpack.modpack.Modpack.hostModpackDir;

public class HttpServer {
    private static final int BUFFER_SIZE = 128 * 1024;
    public static List<String> filesList = new ArrayList<>();
    public static ExecutorService HTTPServerExecutor;
    public static boolean isRunning = false;

    public static void start() {
        if (isRunning) return;

        if (!AutoModpack.serverConfig.modpackHost) {
            AutoModpack.LOGGER.warn("Modpack hosting is disabled in config");
            return;
        }

        if (AutoModpack.serverConfig.hostIp == null || AutoModpack.serverConfig.hostIp.equals("")) {
            AutoModpack.serverConfig.hostIp = Ip.getPublic();
            ConfigTools.saveConfig(AutoModpack.serverConfigFile, AutoModpack.serverConfig);
            AutoModpack.LOGGER.warn("Host IP isn't set in config! Setting it to {}", AutoModpack.serverConfig.hostIp);
        }

        if (AutoModpack.serverConfig.hostLocalIp == null || AutoModpack.serverConfig.hostLocalIp.equals("")) {
            try {
                AutoModpack.serverConfig.hostLocalIp = Ip.getLocal();
                ConfigTools.saveConfig(AutoModpack.serverConfigFile, AutoModpack.serverConfig);
                AutoModpack.LOGGER.warn("Host local IP isn't set in config! Setting it to {}", AutoModpack.serverConfig.hostLocalIp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        isRunning = true;
        new HttpServer();
    }

    public static void stop() {
        if (!isRunning) return;
        isRunning = false;
        HTTPServerExecutor.shutdown();
        AutoModpack.LOGGER.info("Stopped modpack hosting");
    }

    private HttpServer() {
        try {
            Config.ModpackContentFields serverModpackContent = ConfigTools.loadModpackContent(hostModpackContentFile);
            if (serverModpackContent == null) {
                AutoModpack.LOGGER.error("Modpack content is null! Can't start hosting modpack");
                return;
            }

            filesList.clear();
            for (Config.ModpackContentFields.ModpackContentItems item : serverModpackContent.list) {
                filesList.add(item.file);
            }

            HTTPServerExecutor = Executors.newFixedThreadPool(AutoModpack.serverConfig.hostThreads);
            InetSocketAddress address = new InetSocketAddress("0.0.0.0", AutoModpack.serverConfig.hostPort);

            HTTPServerExecutor.submit(() -> {
                try {
                    Selector selector = Selector.open();
                    ServerSocketChannel server = ServerSocketChannel.open();
                    server.bind(address);
                    server.configureBlocking(false);
                    server.register(selector, SelectionKey.OP_ACCEPT);

                    isRunning = true;
                    AutoModpack.LOGGER.info("Modpack hosting started!");

                    while (isRunning) {
                        selector.select();
                        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                        while (keys.hasNext()) {
                            SelectionKey key = keys.next();
                            keys.remove();

                            if (!key.isValid()) {
                                continue;
                            }

                            if (key.isAcceptable()) {
                                SocketChannel client = server.accept();
                                client.configureBlocking(false);
                                client.register(selector, SelectionKey.OP_READ);
                            } else if (key.isReadable()) {
                                SocketChannel client = (SocketChannel) key.channel();

                                if (!client.isOpen()) continue;

                                ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
                                int read = client.read(buffer);
                                if (read == -1) {
                                    client.close();
                                    continue;
                                }

                                buffer.flip();
                                String request = StandardCharsets.UTF_8.decode(buffer).toString();

                                HTTPServerExecutor.submit(new RequestHandler(client, request));
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class RequestHandler implements Runnable {
        private final SocketChannel client;
        private final String request;

        public RequestHandler(SocketChannel client, String request) {
            this.client = client;
            this.request = request;
        }

        @Override
        public void run() {
            String[] requestLines = request.split("\r\n");
            String[] requestFirstLine = requestLines[0].split(" ");
            String requestMethod = requestFirstLine[0];
            String requestUrl = requestFirstLine[1];

            requestUrl = Url.decode(requestUrl);

            try {
                if (requestMethod.equals("GET")) {
                    File file;
                    if (requestUrl.equals("") || requestUrl.equals("/")) {
                        file = hostModpackContentFile;
                    } else if (requestUrl.contains("..")) {
                        sendError(client,403);
                        return;
                    } else if (filesList.contains(requestUrl)) {
                        file = new File(hostModpackDir + File.separator + requestUrl);
                        if (!file.exists()) {
                            file = new File("./" + requestUrl);
                            if (!file.exists()) {
                                sendError(client,404);
                                return;
                            }
                        }
                    } else {
                        sendError(client,404);
                        return;
                    }

                    if (!file.exists() || !file.isFile()) {
                        sendError(client,404);
                        return;
                    }

                    sendFile(client, file);
                } else {
                    sendError(client, 405);
                }

                client.close();
            } catch (IOException e) {
                try {
                    sendError(client, 400);
                } catch (IOException ignore) { }
                e.printStackTrace();
            }
        }

        private static final String ERROR_RESPONSE =
                "HTTP/1.1 %d\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        private static void sendError(SocketChannel client, int code) throws IOException {
            if (!client.isOpen()) return;

            String response = String.format(ERROR_RESPONSE, code);
            client.write(StandardCharsets.UTF_8.encode(response));

            client.close();
        }

        private static final String OK_RESPONSE =
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: %d\r\n" +
                "\r\n";

        private static void sendFile(SocketChannel client, File file) throws IOException {
            if (!client.isOpen()) return;

            if (!file.exists()) {
                sendError(client, 404);
                return;
            }

            try (client; FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                long fileSize = fileChannel.size();
                String response = String.format(OK_RESPONSE, fileSize);
                client.write(StandardCharsets.UTF_8.encode(response));

                ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
                while (fileChannel.read(buffer) > 0) {
                    buffer.flip();
                    while (buffer.hasRemaining() && client.isOpen() && fileChannel.isOpen()) {
                        client.write(buffer);
                    }
                    buffer.clear();
                }
            } catch (Exception e) {
                if (!e.getMessage().contains("Broken pipe")) e.printStackTrace();
            }
        }
    }
}
