package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.modpack.ModpackContent;
import pl.skidam.automodpack_core.protocol.netty.message.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.FileRequestMessage;
import pl.skidam.automodpack_core.protocol.netty.message.ProtocolMessage;
import pl.skidam.automodpack_core.protocol.netty.message.RefreshRequestMessage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

public class ServerMessageHandler extends SimpleChannelInboundHandler<ProtocolMessage> {

    private final Map<byte[], String> secretLookup = new HashMap<>();
    private byte clientProtocolVersion = 0;

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        hostServer.removeConnection(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage msg) throws Exception {
        clientProtocolVersion = msg.getVersion();
        SocketAddress address = ctx.channel().remoteAddress();

        // Validate the secret
        if (!validateSecret(ctx, address, msg.getSecret())) {
            sendError(ctx, clientProtocolVersion, "Authentication failed");
            return;
        }

        switch (msg.getType()) {
            case ECHO_TYPE:
                EchoMessage echoMsg = (EchoMessage) msg;
                ByteBuf echoBuf = Unpooled.buffer(1 + 1 + msg.getSecret().length + echoMsg.getData().length);
                echoBuf.writeByte(clientProtocolVersion);
                echoBuf.writeByte(ECHO_TYPE);
                echoBuf.writeBytes(echoMsg.getSecret());
                echoBuf.writeBytes(echoMsg.getData());
                ctx.writeAndFlush(echoBuf);
                ctx.channel().close();
                break;
            case FILE_REQUEST_TYPE:
                FileRequestMessage fileRequest = (FileRequestMessage) msg;
                sendFile(ctx, fileRequest.getFileHash());
                break;
            case REFRESH_REQUEST_TYPE:
                RefreshRequestMessage refreshRequest = (RefreshRequestMessage) msg;
                refreshModpackFiles(ctx, refreshRequest.getFileHashesList());
                break;
            default:
                sendError(ctx, clientProtocolVersion, "Unknown message type");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    private void refreshModpackFiles(ChannelHandlerContext context, byte[][] FileHashesList) throws IOException {
        List<String> hashes = new ArrayList<>();
        for (byte[] hash : FileHashesList) {
            hashes.add(new String(hash));
        }
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
        sendFile(context, new byte[0]);
    }


    private boolean validateSecret(ChannelHandlerContext ctx, SocketAddress address, byte[] secret) {
        String decodedSecret = secretLookup.get(secret);
        boolean addConnection = false;
        if (decodedSecret == null) {
            decodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            addConnection = true;
            secretLookup.put(secret, decodedSecret);
        }

        boolean valid = Secrets.isSecretValid(decodedSecret, address);

        if (addConnection && valid) {
            hostServer.addConnection(ctx.channel(), decodedSecret);
        }

        return valid;
    }

    private void sendFile(ChannelHandlerContext ctx, byte[] bsha1) throws IOException {
        final String sha1 = new String(bsha1, CharsetUtil.UTF_8);
        final Optional<Path> optionalPath = resolvePath(sha1);

        if (optionalPath.isEmpty() || !Files.exists(optionalPath.get())) {
            sendError(ctx, (byte) 1, "File not found");
            return;
        }

        final Path path = optionalPath.get();
        final long fileSize = Files.size(path);

        // Send file response header: version, FILE_RESPONSE type, then file size (8 bytes)
        ByteBuf responseHeader = Unpooled.buffer(1 + 1 + 8);
        responseHeader.writeByte(clientProtocolVersion);
        responseHeader.writeByte(FILE_RESPONSE_TYPE);
        responseHeader.writeLong(fileSize);
        ctx.writeAndFlush(responseHeader);

        if (fileSize == 0) {
            sendEOT(ctx);
            return;
        }

        // Stream the file using ChunkedFile (chunk size set to 131072 bytes = 128 KB) - suitable value for zstd
        try {
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
            ChunkedFile chunkedFile = new ChunkedFile(raf, 0, raf.length(), 131072);
            ctx.writeAndFlush(chunkedFile).addListener((ChannelFutureListener) future -> sendEOT(ctx));
        } catch (IOException e) {
            sendError(ctx, (byte) 1, "File transfer error: " + e.getMessage());
        }
    }

    public Optional<Path> resolvePath(final String sha1) {
        if (sha1.isBlank()) {
            return Optional.of(hostModpackContentFile);
        }

        return hostServer.getPath(sha1);
    }

    private void sendError(ChannelHandlerContext ctx, byte version, String errorMessage) {
        byte[] errMsgBytes = errorMessage.getBytes(CharsetUtil.UTF_8);
        ByteBuf errorBuf = Unpooled.buffer(1 + 1 + 4 + errMsgBytes.length);
        errorBuf.writeByte(version);
        errorBuf.writeByte(ERROR);
        errorBuf.writeInt(errMsgBytes.length);
        errorBuf.writeBytes(errMsgBytes);
        ctx.writeAndFlush(errorBuf);
        ctx.channel().close();
    }

    private void sendEOT(ChannelHandlerContext ctx) {
        ByteBuf eot = Unpooled.buffer(2);
        eot.writeByte((byte) 1);
        eot.writeByte(END_OF_TRANSMISSION);
        ctx.writeAndFlush(eot);
    }
}
