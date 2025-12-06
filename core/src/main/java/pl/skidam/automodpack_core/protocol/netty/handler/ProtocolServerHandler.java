package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;

public class ProtocolServerHandler extends ByteToMessageDecoder {

    // The first byte of our Magic Integers (0x414D4D48 or 0x414D4D43) is always 0x41 ('A')
    private static final byte MAGIC_HEADER_BYTE = 0x41;

    private final SslContext sslCtx;

    public ProtocolServerHandler(SslContext sslCtx) {
        this.sslCtx = sslCtx;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // We need at least 1 byte to make a decision
        if (!in.isReadable()) return;

        // Wait for full magic if we are hosting on Minecraft port or if we require magic packets
        if ((serverConfig.bindPort == -1 && hostServer.isRunning()) || serverConfig.requireMagicPackets) {
            if (in.readableBytes() < 4) return;

            int magic = in.getInt(in.readerIndex());
            if (isMagic(magic)) {
                handleMagicPacket(ctx, in, magic);
            } else if (ctx.channel().pipeline().get(this.getClass()) != null) {
                ctx.channel().pipeline().remove(this);
            }
            return;
        }

        // Otherwise, we accept both Magic Packets and Standard Protocol immediately
        int readerIndex = in.readerIndex();
        byte signatureByte = in.getByte(readerIndex);

        if (signatureByte != MAGIC_HEADER_BYTE) {
            fallbackToStandardProtocol(ctx, in);
            return;
        }

        if (in.readableBytes() < 4) {
            return;
        }

        int potentialMagic = in.getInt(readerIndex);
        if (isMagic(potentialMagic)) {
            handleMagicPacket(ctx, in, potentialMagic);
        } else {
            fallbackToStandardProtocol(ctx, in);
        }
    }

    private boolean isMagic(int magic) {
        return magic == MAGIC_AMMH || magic == MAGIC_AMMC;
    }

    private void handleMagicPacket(ChannelHandlerContext ctx, ByteBuf in, int magic) {
        if (magic == MAGIC_AMMH) {
            // AMMH requires: Magic (4) + Length (2) + Hostname (N)
            if (in.readableBytes() < 6) return; // Wait for length

            in.markReaderIndex();
            in.skipBytes(4);
            short hostnameLength = in.readShort();
            in.resetReaderIndex();

            if (in.readableBytes() < 4 + 2 + hostnameLength) return; // Wait for payload

            // Consume
            in.skipBytes(6);
            byte[] hostnameBytes = new byte[hostnameLength];
            in.readBytes(hostnameBytes);
            String hostname = new String(hostnameBytes, StandardCharsets.UTF_8);

            LOGGER.debug("Received AMMH handshake. Hostname: {}", hostname);
            finalizeHandshake(ctx);
        } else if (magic == MAGIC_AMMC) {
            in.skipBytes(4); // Consume magic
            LOGGER.debug("Received AMMC handshake.");
            finalizeHandshake(ctx);
        }
    }

    private void fallbackToStandardProtocol(ChannelHandlerContext ctx, ByteBuf in) {
        LOGGER.debug("No Magic Packet detected. Falling back to Standard Protocol.");

        // Setup the Standard Pipeline (Config, encoders, etc.)
        setupPipeline(ctx);

        // Remove THIS handler so we don't intercept anymore
        ctx.pipeline().remove(this);

        // We use retain() because fireChannelRead decrements the ref count, but we haven't read from 'in' yet.
        ctx.fireChannelRead(in.retain());

        // Skip bytes in THIS handler's context to satisfy Netty (we passed ownership downstream)
        in.skipBytes(in.readableBytes());
    }

    private void finalizeHandshake(ChannelHandlerContext ctx) {
        sendAck(ctx);

        // Clear pipeline of any pre-existing junk
        ctx.pipeline().toMap().forEach((k, v) -> ctx.pipeline().remove(v));

        // Setup fresh pipeline
        setupPipeline(ctx);
    }

    private void sendAck(ChannelHandlerContext ctx) {
        ByteBuf response = ctx.alloc().buffer(4);
        response.writeInt(MAGIC_AMOK);
        ctx.writeAndFlush(response);
    }

    private void setupPipeline(ChannelHandlerContext ctx) {
        ctx.pipeline().addLast("error-printer-first", new ErrorPrinter());
        ctx.pipeline().addLast("traffic-shaper", TrafficShaper.trafficShaper.getTrafficShapingHandler());

        if (sslCtx != null) {
            ctx.pipeline().addLast("tls", sslCtx.newHandler(ctx.alloc()));
            LOGGER.debug("Pipeline: TLS Enabled");
        } else {
            LOGGER.debug("Pipeline: TLS Disabled");
        }

        ctx.channel().attr(NettyServer.PROTOCOL_VERSION).set(PROTOCOL_VERSION);
        ctx.channel().attr(NettyServer.COMPRESSION_TYPE).set(COMPRESSION_ZSTD);
        ctx.channel().attr(NettyServer.CHUNK_SIZE).set(DEFAULT_CHUNK_SIZE);

        ctx.pipeline()
                .addLast("configuration-handler", new ConfigurationHandler())
                .addLast("compression-encoder", new CompressionEncoder())
                .addLast("compression-decoder", new CompressionDecoder())
                .addLast("chunked-write", new ChunkedWriteHandler())
                .addLast("protocol-msg-decoder", new ProtocolMessageDecoder())
                .addLast("msg-handler", new ServerMessageHandler())
                .addLast("error-printer-last", new ErrorPrinter());
    }
}