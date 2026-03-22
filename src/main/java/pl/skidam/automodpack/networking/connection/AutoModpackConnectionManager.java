package pl.skidam.automodpack.networking.connection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import pl.skidam.automodpack.mixin.core.ConnectionAccessor;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionFrame;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionMetrics;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionTransport;
import pl.skidam.automodpack_core.networking.connection.AutoModpackFrameHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class AutoModpackConnectionManager implements AutoModpackConnectionTransport, AutoCloseable {
    private final Connection connection;
    private final Map<Byte, AutoModpackFrameHandler> handlers = new ConcurrentHashMap<>();
    private final Queue<ByteBuf> outboundFrames = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);

    private volatile ChannelHandlerContext outboundBridgeContext;

    public AutoModpackConnectionManager(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void registerHandler(byte kind, AutoModpackFrameHandler handler) {
        if (kind <= 0) {
            throw new IllegalArgumentException("Frame kind must be positive");
        }

        AutoModpackFrameHandler previous = handlers.putIfAbsent(kind, handler);
        if (previous != null && previous != handler) {
            throw new IllegalStateException("Frame handler already registered for kind " + kind);
        }
    }

    @Override
    public void unregisterHandler(byte kind) {
        handlers.remove(kind);
    }

    @Override
    public void sendFrame(byte kind, ByteBuf payload) throws IOException {
        ByteBuf encoded = null;
        try {
            ensureWritable(kind);

            Channel channel = channel();
            encoded = AutoModpackConnectionFrame.encode(channel.alloc(), kind, payload);
            AutoModpackConnectionMetrics.recordFrameSent(encoded.readableBytes());
            outboundFrames.add(encoded);

            if (!scheduleDrain(channel)) {
                throw new IOException("Failed to schedule AutoModpack connection frame drain");
            }
            encoded = null;
        } finally {
            ReferenceCountUtil.safeRelease(payload);
            ReferenceCountUtil.safeRelease(encoded);
        }
    }

    @Override
    public boolean isOpen() {
        Channel channel = channel();
        return !closed.get() && channel != null && channel.isOpen();
    }

    public void handleFrame(AutoModpackConnectionFrame frame) throws Exception {
        if (closed.get()) {
            throw new IOException("AutoModpack connection manager is closed");
        }

        AutoModpackFrameHandler handler = handlers.get(frame.kind());
        if (handler == null) {
            throw new IOException("Unexpected AutoModpack frame kind " + frame.kind());
        }

        AutoModpackConnectionMetrics.recordFrameReceived(frame.encodedLength());
        handler.handle(frame.payload());
    }

    public void protocolError(String message, Throwable error) {
        if (error == null) {
            LOGGER.warn("AutoModpack connection transport protocol error: {}", message);
        } else {
            LOGGER.warn("AutoModpack connection transport protocol error: {}", message, error);
        }

        Channel channel = channel();
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            handlers.clear();
            outboundBridgeContext = null;
            drainScheduled.set(false);
            clearPendingFrames();
        }
    }

    void bindOutboundBridgeContext(ChannelHandlerContext context) {
        if (!closed.get()) {
            outboundBridgeContext = context;
        }
    }

    void unbindOutboundBridgeContext(ChannelHandlerContext context) {
        if (outboundBridgeContext == context) {
            outboundBridgeContext = null;
        }
    }

    private Channel channel() {
        return ((ConnectionAccessor) connection).automodpack$getChannel();
    }

    private void ensureWritable(byte kind) throws IOException {
        if (closed.get()) {
            throw new IOException("AutoModpack connection manager is closed");
        }
        if (kind <= 0) {
            throw new IOException("Invalid AutoModpack frame kind: " + kind);
        }

        Channel channel = channel();
        if (channel == null || !channel.isOpen()) {
            throw new IOException("Minecraft connection is not open");
        }
        if (outboundBridgeContext == null) {
            throw new IOException("AutoModpack outbound bridge is not installed");
        }
    }

    private boolean scheduleDrain(Channel channel) {
        if (!drainScheduled.compareAndSet(false, true)) {
            return true;
        }

        try {
            channel.eventLoop().execute(this::drainOutboundQueue);
            return true;
        } catch (RejectedExecutionException error) {
            drainScheduled.set(false);
            protocolError("Failed to schedule AutoModpack connection frame drain", error);
            return false;
        }
    }

    private void drainOutboundQueue() {
        while (true) {
            if (closed.get()) {
                clearPendingFrames();
                drainScheduled.set(false);
                return;
            }

            ChannelHandlerContext bridgeContext = outboundBridgeContext;
            if (bridgeContext == null || !bridgeContext.channel().isOpen()) {
                drainScheduled.set(false);
                protocolError("AutoModpack outbound bridge is unavailable", null);
                return;
            }

            long startedAt = AutoModpackConnectionMetrics.startDebugTimer();
            int drainedFrames = 0;
            try {
                ByteBuf frame;
                while ((frame = outboundFrames.poll()) != null) {
                    drainedFrames++;
                    try {
                        bridgeContext.write(frame).addListener((ChannelFutureListener) future -> {
                            if (!future.isSuccess() && bridgeContext.channel().isOpen()) {
                                LOGGER.debug("Failed to write AutoModpack connection frame", future.cause());
                                bridgeContext.channel().close();
                            }
                        });
                    } catch (Throwable error) {
                        ReferenceCountUtil.safeRelease(frame);
                        throw error;
                    }
                }

                if (drainedFrames > 0) {
                    AutoModpackConnectionMetrics.recordQueueDrain();
                    bridgeContext.flush();
                    AutoModpackConnectionMetrics.recordFlush();
                }
            } catch (Throwable error) {
                protocolError("Failed to drain AutoModpack connection frame queue", error);
                return;
            } finally {
                AutoModpackConnectionMetrics.logDebugDuration("AutoModpack queue drain", startedAt);
            }

            drainScheduled.set(false);
            if (outboundFrames.isEmpty() || !drainScheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void clearPendingFrames() {
        ByteBuf frame;
        while ((frame = outboundFrames.poll()) != null) {
            ReferenceCountUtil.safeRelease(frame);
        }
    }
}
