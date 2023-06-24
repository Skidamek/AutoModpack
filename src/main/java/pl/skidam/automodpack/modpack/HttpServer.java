/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.modpack;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.Ip;
import pl.skidam.automodpack.utils.Url;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static pl.skidam.automodpack.GlobalVariables.*;
import static pl.skidam.automodpack.modpack.Modpack.hostModpackContentFile;
import static pl.skidam.automodpack.modpack.Modpack.hostModpackDir;

public class HttpServer {
    private static final int BUFFER_SIZE = 32 * 1024;
    public static List<String> filesList = new ArrayList<>();
    public static ExecutorService HTTPServerExecutor;
    public static Object server = null;

    public static boolean isRunning() {
        if (server == null) return false;

        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) server;
        return serverSocketChannel.isOpen();
    }

    public static void start() {
        if (isRunning()) return;

        if (!serverConfig.modpackHost) {
            LOGGER.warn("Modpack hosting is disabled in config");
            return;
        }

        if (serverConfig.hostIp == null || serverConfig.hostIp.equals("")) {
            String publicIp = Ip.getPublic();
            if (publicIp != null) {
                serverConfig.hostIp = publicIp;
                ConfigTools.saveConfig(serverConfigFile, serverConfig);
                LOGGER.warn("Host IP isn't set in config! Setting it to {}", serverConfig.hostIp);
            } else {
                LOGGER.error("Host IP isn't set in config, please change it manually! Couldn't get public IP");
                return;
            }
        }

        if (serverConfig.hostLocalIp == null || serverConfig.hostLocalIp.equals("")) {
            try {
                serverConfig.hostLocalIp = Ip.getLocal();
                ConfigTools.saveConfig(serverConfigFile, serverConfig);
                LOGGER.warn("Host local IP isn't set in config! Setting it to {}", serverConfig.hostLocalIp);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        new HttpServer();
    }

    public static void stop() {

        if (server == null) return;

        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) server;

        if (!isRunning()) {
            LOGGER.warn("Modpack hosting isn't running, can't stop it");
            return;
        }

        try {
            serverSocketChannel.close();
            serverSocketChannel.socket().close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        HTTPServerExecutor.shutdownNow();
        try {
            if (!HTTPServerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                HTTPServerExecutor.shutdownNow();
                if (!HTTPServerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.error("HTTP Executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            HTTPServerExecutor.shutdownNow();
        }

        if (serverSocketChannel.isOpen()) {
            try {
                serverSocketChannel.close();
                serverSocketChannel.socket().close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (serverSocketChannel.socket().isClosed() && HTTPServerExecutor.isTerminated()) {
            LOGGER.info("Stopped modpack hosting");
        } else {
            LOGGER.error("Failed to stop modpack hosting");
        }
    }

    private HttpServer() {
        try {
            Jsons.ModpackContentFields serverModpackContent = ConfigTools.loadModpackContent(hostModpackContentFile);
            if (serverModpackContent == null) {
                LOGGER.error("Modpack content is null! Can't start hosting modpack");
                return;
            }

            filesList.clear();
            for (Jsons.ModpackContentFields.ModpackContentItems item : serverModpackContent.list) {
                filesList.add(item.file);
            }

            ThreadFactory threadFactory = new ThreadFactoryBuilder()
                    .setNameFormat("AutoModpackHost-%d")
                    .build();

            HTTPServerExecutor = Executors.newFixedThreadPool(
                    serverConfig.hostThreads,
                    threadFactory
            );

            InetSocketAddress address = new InetSocketAddress("0.0.0.0", serverConfig.hostPort);


            HTTPServerExecutor.submit(() -> {

                try {
                    Selector selector = Selector.open();

                    try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
                        ServerSocket serverSocket = serverSocketChannel.socket();
                        serverSocket.setReuseAddress(true);
                        serverSocket.bind(address);
                        serverSocketChannel.configureBlocking(false);
                        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

                        LOGGER.info("Modpack hosting started! on port {}", serverConfig.hostPort);

                        server = serverSocketChannel;

                        while (isRunning()) {
                            selector.select();
                            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                            while (keys.hasNext()) {
                                SelectionKey key = keys.next();
                                keys.remove();

                                if (!key.isValid()) {
                                    continue;
                                }

                                if (key.isAcceptable()) {
                                    SocketChannel client = serverSocketChannel.accept();
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
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    stop();
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
                    Path file;
                    if (requestUrl.equals("") || requestUrl.equals("/")) {
                        file = hostModpackContentFile;
                    } else if (requestUrl.contains("..")) {
                        sendError(client, 403);
                        return;
                    } else if (filesList.contains(requestUrl)) {
                        file = Paths.get(hostModpackDir + File.separator + requestUrl);
                        if (!Files.exists(file)) {
                            file = Paths.get("./" + requestUrl);
                            if (!Files.exists(file)) {
                                sendError(client, 404);
                                return;
                            }
                        }
                    } else {
                        sendError(client, 404);
                        return;
                    }

                    if (!Files.exists(file) || !Files.isRegularFile(file)) {
                        sendError(client, 404);
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
                } catch (IOException ignore) {
                }
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

        private static final String OK_RESPONSE_JSON =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: %d\r\n" +
                        "\r\n";


        private static void sendFile(SocketChannel client, Path file) throws IOException {
            if (!client.isOpen()) return;

            if (!Files.exists(file)) {
                sendError(client, 404);
                return;
            }

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                long fileSize = raf.length();
                String response = String.format(OK_RESPONSE, fileSize);

                if (file.getFileName().endsWith(".json")) {
                    response = String.format(OK_RESPONSE_JSON, fileSize);
                }

                client.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));

                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                int bytesRead;
                while ((bytesRead = raf.read(buffer.array())) > 0) {
                    buffer.limit(bytesRead);
                    while (buffer.hasRemaining() && client.isOpen()) {
                        client.write(buffer);
                    }
                    buffer.clear();
                }
            } catch (IOException e) {
                e.printStackTrace();
                if (client.isOpen()) {
                    sendError(client, 500);
                }
            } finally {
                if (client.isOpen()) {
                    client.close();
                }
            }
        }
    }
}
