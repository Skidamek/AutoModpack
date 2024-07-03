package pl.skidam.automodpack_core.netty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonSyntaxException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.modpack.ModpackContent;

import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class HttpServerHandler extends ChannelInboundHandlerAdapter {
    private final String HTTP_REQUEST_BASE = "/automodpack";
    private final String HTTP_REQUEST_GET = "GET " + HTTP_REQUEST_BASE;
    private final String HTTP_REQUEST_REFRESH = "POST " + HTTP_REQUEST_BASE + "/refresh";
    private final byte[] HTTP_REQUEST_GET_BASE_BYTES = HTTP_REQUEST_GET.getBytes(StandardCharsets.UTF_8);
    private final byte[] HTTP_REQUEST_REFRESH_BYTES = HTTP_REQUEST_REFRESH.getBytes(StandardCharsets.UTF_8);

    public boolean isAutoModpackRequest(ByteBuf buf) {
        boolean equals = false;
        try {
            // TODO optimize it if possible
            byte[] data1 = new byte[HTTP_REQUEST_GET_BASE_BYTES.length];
            buf.readBytes(data1);
            buf.resetReaderIndex();
            byte[] data2 = new byte[HTTP_REQUEST_REFRESH_BYTES.length];
            buf.readBytes(data2);
            buf.resetReaderIndex();

            equals = Arrays.equals(data1, HTTP_REQUEST_GET_BASE_BYTES) || Arrays.equals(data2, HTTP_REQUEST_REFRESH_BYTES);
        } catch (IndexOutOfBoundsException ignored) {
        } catch (Exception e) {
            LOGGER.error("Couldn't read channel!", e);
        }

        return equals;
    }

    private String parseRequestUri(String request) {
        final String[] requestLines = request.split("\r\n");
        final String[] requestFirstLine = requestLines[0].split(" ");
        final String requestUrl = requestFirstLine[1];

        if (requestUrl.contains(HTTP_REQUEST_BASE)) {
            return requestUrl.replaceFirst("/automodpack/", "");
        } else {
            return null;
        }
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
            e.printStackTrace();
        }

        return stringList;
    }

    private String getRequest(ByteBuf buf) {
        try {
            if (buf.readableBytes() > 4096 || buf.readableBytes() < HTTP_REQUEST_BASE.length()) {
                return null;
            }

            buf.resetReaderIndex();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            buf.resetReaderIndex();
            return new String(data);
        } catch (Exception e) {
            return null;
        }
    }

    // TODO append our handler in different place (should bring better performance), "context.fireChannelRead(msg)" here will result in breaking minecraft packet, we don't want it
    @Override
    public void channelRead(ChannelHandlerContext context, Object msg) {
        channelRead(context, (ByteBuf) msg);
    }

    public void channelRead(ChannelHandlerContext context, ByteBuf buf) {
        final String request = getRequest(buf);
        if (request == null) return;
        final String requestUri = parseRequestUri(request);
        if (requestUri == null) return;

        var firstContext = context.pipeline().firstContext();

        if (request.contains(HTTP_REQUEST_GET)) {
            sendFile(firstContext, requestUri);
        } else if (request.contains(HTTP_REQUEST_REFRESH)) {
            // TODO set limit for one ip max 1 request per 5 seconds
            refreshModpackFiles(firstContext, request);
        }
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

            Path modpackDir;
            if (path.toAbsolutePath().toString().contains(hostContentModpackDir.toAbsolutePath().toString())) {
                modpackDir = hostContentModpackDir;
            } else {
                modpackDir = Path.of(System.getProperty("user.dir"));
            }

            creationFutures.add(modpack.replaceAsync(modpackDir, path));
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
            LOGGER.error("Failed to open the file " + path, e);
            return;
        }

        try {
            long fileLength = raf.length();

            HttpResponse response = new HttpResponse(200);
            response.addHeader("Content-Type", "application/octet-stream");
            response.addHeader("Content-Length", String.valueOf(fileLength));

            // Those ByteBuf's are necessary!
            ByteBuf responseHeadersBuf = Unpooled.copiedBuffer(response.getResponseMessage(), StandardCharsets.UTF_8);
            context.pipeline().firstContext().write(responseHeadersBuf);

            // Write a file
            DefaultFileRegion fileRegion = new DefaultFileRegion(raf.getChannel(), 0, fileLength);
            context.pipeline().firstContext().writeAndFlush(fileRegion, context.newProgressivePromise()).addListener(future -> {
                if (!future.isSuccess()) {
                    LOGGER.error("Writing to channel error! " + future.cause() + " " + future.cause().getMessage());
                }
                raf.close();
                context.pipeline().firstContext().channel().close();
            });
        } catch (Exception e) {
            LOGGER.error("Couldn't read channel!", e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.error("Couldn't handle HTTP request!" + cause);
    }

    private void sendError(ChannelHandlerContext context, int status) {
        HttpResponse response = new HttpResponse(status);
        response.addHeader("Content-Length", String.valueOf(0));

        ByteBuf responseBuf = Unpooled.copiedBuffer(response.getResponseMessage(), StandardCharsets.UTF_8);
        context.pipeline().firstContext().writeAndFlush(responseBuf).addListener(ChannelFutureListener.CLOSE);
    }

    public Optional<Path> resolvePath(final String sha1) {
        if (sha1.isBlank()) {
            return Optional.of(hostModpackContentFile);
        }

        return httpServer.getPath(sha1);
    }
}
