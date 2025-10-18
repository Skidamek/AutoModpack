package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationChunkSizeMessage;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.ConfigurationCompressionMessage;
import pl.skidam.automodpack_core.protocol.netty.message.configuration.UnknownConfigurationMessage;
import pl.skidam.automodpack_core.utils.PlatformUtils;

public class ConfigurationHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        in.markReaderIndex();
        LOGGER.debug("Received a message (checking for configuration) with {} readable bytes", in.readableBytes());
        if (in.readableBytes() < 2) {
            return; // Not enough data to read version and type
        }
        byte version = in.readByte();
        byte type = in.readByte();
        LOGGER.debug("Message version: {}, type: {}, readable bytes: {}", version, type, in.readableBytes());
        if ((type & 0xF0) == 0x40) {
            if (type == CONFIGURATION_ECHO_TYPE) {
                // remove this channel from pipeline
                ctx.pipeline().remove(this);
                LOGGER.debug("Removed ConfigurationHandler from pipeline after receiving echo configuration message.");
            } else if (type == CONFIGURATION_COMPRESSION_TYPE) {
                if (in.readableBytes() < 1) {
                    in.resetReaderIndex();
                    return; // Not enough data to read compression type
                }

                byte clientCompressionType = in.readByte();

                // Negotiate compression type
                byte negotiatedCompressionType;
                if (PlatformUtils.isAndroid() && clientCompressionType == COMPRESSION_ZSTD) {
                    negotiatedCompressionType = COMPRESSION_GZIP; // Zstd unsupported on Android
                    LOGGER.warn("Client requested Zstd compression but we don't support it; falling back to Gzip.");
                } else if (clientCompressionType == COMPRESSION_ZSTD || clientCompressionType == COMPRESSION_GZIP || clientCompressionType == COMPRESSION_NONE) {
                    negotiatedCompressionType = clientCompressionType;
                } else {
                    negotiatedCompressionType = COMPRESSION_ZSTD; // Default to Zstd if unsupported
                }

                // Update channel attributes
                ctx.channel().attr(NettyServer.COMPRESSION_TYPE).set(negotiatedCompressionType);

                // Send negotiated values back to client
                ConfigurationCompressionMessage responseMsg = new ConfigurationCompressionMessage(PROTOCOL_VERSION, negotiatedCompressionType);
                ctx.writeAndFlush(responseMsg.toByteBuf());

                LOGGER.debug("Negotiated configuration: compression {}", negotiatedCompressionType);
            } else if (type == CONFIGURATION_CHUNK_SIZE_TYPE) {
                if (in.readableBytes() < 4) {
                    in.resetReaderIndex();
                    return; // Not enough data to read chunk size
                }

                int clientChunkSize = in.readInt();

                // Negotiate chunk size
                int negotiatedChunkSize;
                if (clientChunkSize >= MIN_CHUNK_SIZE && clientChunkSize <= MAX_CHUNK_SIZE) {
                    negotiatedChunkSize = clientChunkSize;
                } else {
                    negotiatedChunkSize = DEFAULT_CHUNK_SIZE; // Default if out of bounds
                }

                // Update channel attributes
                ctx.channel().attr(NettyServer.CHUNK_SIZE).set(negotiatedChunkSize);

                // Send negotiated values back to client
                ConfigurationChunkSizeMessage responseMsg = new ConfigurationChunkSizeMessage(PROTOCOL_VERSION, negotiatedChunkSize);
                ctx.writeAndFlush(responseMsg.toByteBuf());

                LOGGER.debug("Negotiated configuration: chunk size {}", negotiatedChunkSize);
            } else {
                LOGGER.debug("Received unknown configuration message type: {} version: {}", type, version);
                UnknownConfigurationMessage responseMsg = new UnknownConfigurationMessage(PROTOCOL_VERSION);
                ctx.writeAndFlush(responseMsg.toByteBuf());
            }
            in.release(); // Release the buffer after processing configuration message
        } else {
            LOGGER.debug("Received non-configuration message of type: {} version: {}", type, version);
            in.resetReaderIndex();
            ctx.fireChannelRead(in); // Pass to next handler
            ctx.pipeline().remove(this); // Remove this handler from pipeline
        }
    }
}
