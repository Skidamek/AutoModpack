package pl.skidam.automodpack_core.netty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonSyntaxException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.ModpackContent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HttpServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext context, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        channelRead(context, buf, msg);
    }

    public void channelRead(ChannelHandlerContext context, ByteBuf buf, Object msg) {
        final String request = getRequest(buf);
        if (request == null) {
            dropConnection(context, msg);
            return;
        }

        final String secret = parseSecret(request);
        if (secret == null || secret.isBlank()) {
            dropConnection(context, msg);
            return;
        }

        SocketAddress address = context.channel().remoteAddress();

        if (!Secrets.isSecretValid(secret, address)) {
            dropConnection(context, msg);
            return;
        }

        final String requestUri = parseRequestUri(request);
        if (requestUri == null) {
            dropConnection(context, msg);
            return;
        }

        var firstContext = context.pipeline().firstContext();

        if (request.contains(HttpServer.HTTP_REQUEST_GET)) {
            sendFile(firstContext, requestUri);
        } else if (request.contains(HttpServer.HTTP_REQUEST_REFRESH)) {
            // TODO set limit for one ip max 1 request per 5 seconds
            refreshModpackFiles(firstContext, request);
        }

        buf.release();
    }

    private void dropConnection(ChannelHandlerContext ctx, Object request) {
        ctx.pipeline().remove(MOD_ID);
        ctx.fireChannelRead(request);
    }

    private void refreshModpackFiles(ChannelHandlerContext context, String request) {
        List<String> hashes = parseBodyStrings(request);
        LOGGER.info("Received refresh request for files of hashes: {}", hashes);
        List<CompletableFuture<Void>> creationFutures = new ArrayList<>();
        List<ModpackContent> modpacks = new ArrayList<>();
        for (String hash : hashes) {
            final Optional<Path> optionalPath = resolvePath(hash);
            if (optionalPath.isEmpty()) continue;
            Path path = optionalPath.get();
            ModpackContent modpack = null;
            for (var content : GlobalVariables.modpack.modpacks.values()) {
                if (!content.pathsMap.getMap().containsKey(hash)) {
                    continue;
                }

                modpack = content;
                break;
            }

            if (modpack == null) {
                continue;
            }

            modpacks.add(modpack);

            creationFutures.add(modpack.replaceAsync(path));
        }

        creationFutures.forEach(CompletableFuture::join);
        modpacks.forEach(ModpackContent::saveModpackContent);

        LOGGER.info("Sending new modpack-content.json");

        // Sends new json
        sendFile(context, "");
    }

    private void sendFile(ChannelHandlerContext context, String requestUri) {
        final Optional<Path> optionalPath = resolvePath(requestUri);

        if (optionalPath.isEmpty()) {
            sendError(context, 404);
            return;
        }

        Path path = optionalPath.get();

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(path.toFile(), "r");
        } catch (FileNotFoundException e) {
            sendError(context, 404);
            LOGGER.error("Requested file not found!", e);
            return;
        } catch (Exception e) {
            sendError(context, 418);
            LOGGER.error("Failed to open the file {}", path, e);
            return;
        }

        try {
            long fileLength = raf.length();

            HttpResponse response = new HttpResponse(200);
            response.addHeader("Content-Type", "application/octet-stream");
            response.addHeader("Content-Length", String.valueOf(fileLength));

            // Response headers
            ByteBuf responseHeadersBuf = Unpooled.copiedBuffer(response.getResponseMessage(), StandardCharsets.UTF_8);
            context.pipeline().firstContext().write(responseHeadersBuf);

            // Write the file
            DefaultFileRegion fileRegion = new DefaultFileRegion(raf.getChannel(), 0, fileLength);
            context.pipeline().firstContext().writeAndFlush(fileRegion, context.newProgressivePromise())
                    .addListener(future -> {
                        try {
                            Throwable cause = future.cause();
                            if (cause != null && !(cause instanceof ClosedChannelException)) {
                                LOGGER.error("Error writing to channel: {} path: {}", cause, path);
                            }
                        } catch (Exception e) {
                            LOGGER.error("Unexpected error: {}", e.getMessage(), e);
                        } finally {
                            try {
                                raf.close(); // Ensure RandomAccessFile is closed
                            } catch (IOException e) {
                                LOGGER.error("Failed to close RandomAccessFile: {} of path: {}", e, path);
                            }

                            // Finally close channel
                            var firstContext = context.pipeline().firstContext();
                            if (firstContext != null) {
                                firstContext.channel().close();
                            } else if (context.channel() != null) {
                                context.channel().close();
                            }
                        }
                    });
        } catch (Exception e) {
            LOGGER.error("Error during file: {} handling {}", path, e);
            try {
                raf.close(); // Ensure RandomAccessFile is closed in case of exceptions
            } catch (IOException closeEx) {
                LOGGER.error("Failed to close RandomAccessFile: {} of path: {}", closeEx, path);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.error("Couldn't handle HTTP request!", cause.getCause());
    }

    private void sendError(ChannelHandlerContext context, int status) {
        HttpResponse response = new HttpResponse(status);
        response.addHeader("Content-Length", String.valueOf(0));

        ByteBuf responseBuf = Unpooled.copiedBuffer(response.getResponseMessage(), StandardCharsets.UTF_8);
        context.pipeline().writeAndFlush(responseBuf).addListener(ChannelFutureListener.CLOSE);
    }

    public Optional<Path> resolvePath(final String sha1) {
        if (sha1.isBlank()) {
            return Optional.of(hostModpackContentFile);
        }

        return httpServer.getPath(sha1);
    }

    public boolean isAutoModpackRequest(ByteBuf buf) {
        boolean equals = false;
        try {
            buf.markReaderIndex();
            byte[] data1 = new byte[HttpServer.HTTP_REQUEST_GET_BASE_BYTES.length];
            buf.readBytes(data1);
            buf.resetReaderIndex();
            byte[] data2 = new byte[HttpServer.HTTP_REQUEST_REFRESH_BYTES.length];
            buf.readBytes(data2);
            buf.resetReaderIndex();
            equals = Arrays.equals(data1, HttpServer.HTTP_REQUEST_GET_BASE_BYTES) || Arrays.equals(data2, HttpServer.HTTP_REQUEST_REFRESH_BYTES);
        } catch (IndexOutOfBoundsException ignored) {
        } catch (Exception e) {
            LOGGER.error("Couldn't read channel!", e.getCause());
        }

        return equals;
    }

    private String parseRequestUri(String request) {
        final String[] requestLines = request.split("\r\n");
        final String[] requestFirstLine = requestLines[0].split(" ");
        final String requestUrl = requestFirstLine[1];

        if (requestUrl.contains(HttpServer.HTTP_REQUEST_BASE)) {
            return requestUrl.replaceFirst(HttpServer.HTTP_REQUEST_BASE, "");
        } else {
            return null;
        }
    }

    private String parseSecret(String request) {
        final String[] requestLines = request.split("\r\n");
        for (String line : requestLines) {
            if (line.contains(SECRET_REQUEST_HEADER)) {
                return line.replace(SECRET_REQUEST_HEADER + ": ", "").trim();
            }
        }

        return null;
    }

    public List<String> parseBodyStrings(String requestPacket) {
        List<String> stringList = new ArrayList<>();
        if (!requestPacket.contains("[")) {
            return stringList;
        }
        String jsonPart = requestPacket.substring(requestPacket.lastIndexOf("[")).trim();
        try {
            JsonArray jsonArray = new Gson().fromJson(jsonPart, JsonArray.class);
            for (int i = 0; i < jsonArray.size(); i++) {
                stringList.add(jsonArray.get(i).getAsString());
            }
        } catch (JsonSyntaxException e) {
            LOGGER.error("Couldn't parse JSON from request body!", e.getCause());
        }

        return stringList;
    }

    private String getRequest(ByteBuf buf) {
        try {
            buf.markReaderIndex();
            if (buf.readableBytes() > 4096 || buf.readableBytes() < HttpServer.HTTP_REQUEST_BASE.length()) {
                return null;
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.resetReaderIndex();
            return new String(data);
        } catch (Exception e) {
            return null;
        }
    }
}
