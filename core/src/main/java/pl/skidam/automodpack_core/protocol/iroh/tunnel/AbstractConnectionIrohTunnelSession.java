package pl.skidam.automodpack_core.protocol.iroh.tunnel;

import io.netty.buffer.ByteBuf;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionMetrics;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionTransport;
import pl.skidam.automodpack_core.protocol.iroh.IrohThreading;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;

abstract class AbstractConnectionIrohTunnelSession implements AutoCloseable {
    private static final ScheduledExecutorService CLOSE_GRACE_EXECUTOR =
        Executors.newSingleThreadScheduledExecutor(IrohThreading.daemonThreadFactory("AutoModpack Iroh Tunnel Close"));

    protected final long sessionId;
    protected final AutoModpackConnectionTransport transport;
    protected final Queue<byte[]> outgoingPackets = new ConcurrentLinkedQueue<>();
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final AtomicBoolean handlerRegistered = new AtomicBoolean(false);
    protected final AtomicBoolean terminalFrameSent = new AtomicBoolean(false);
    protected volatile String terminalError;

    private final String sideLabel;
    private final AtomicBoolean localClosePending = new AtomicBoolean(false);
    private final AtomicBoolean cleanupCompleted = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> cleanupFuture;

    protected AbstractConnectionIrohTunnelSession(String sideLabel, long sessionId, AutoModpackConnectionTransport transport) {
        this.sideLabel = sideLabel;
        this.sessionId = sessionId;
        this.transport = transport;
    }

    public long getSessionId() {
        return sessionId;
    }

    public boolean isActive() {
        return !closed.get();
    }

    protected final synchronized void startSession() throws IOException {
        if (!handlerRegistered.compareAndSet(false, true)) {
            return;
        }

        transport.registerHandler(AutoModpackConnectionTransport.KIND_IROH_TUNNEL, this::handleFrame);
        afterStart();
    }

    protected void afterStart() throws IOException {
    }

    protected abstract void handleIncoming(IrohTunnelEnvelope envelope) throws IOException;

    protected final void flushOutgoing() {
        if (closed.get() || terminalError != null) {
            return;
        }

        long startedAt = AutoModpackConnectionMetrics.startDebugTimer();
        synchronized (this) {
            while (!closed.get() && terminalError == null && !outgoingPackets.isEmpty()) {
                try {
                    sendEnvelope((byte) 0, null);
                } catch (IOException e) {
                    markError("Failed to send tunneled " + sideLabel.toLowerCase() + " packet");
                    return;
                }
            }
        }
        AutoModpackConnectionMetrics.logDebugDuration(sideLabel + " tunnel flushOutgoing", startedAt);
    }

    protected final void markError(String message) {
        finish(message, true, true);
    }

    protected final void finish(String message, boolean sendClose, boolean sendError) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        terminalError = message;
        onFinished(message);

        byte flags = 0;
        if (sendClose) {
            flags |= IrohTunnelEnvelope.FLAG_CLOSE;
        }
        if (sendError) {
            flags |= IrohTunnelEnvelope.FLAG_ERROR;
        }
        if (flags != 0) {
            sendTerminalFrame(flags, message);
        }

        if (sendClose) {
            localClosePending.set(true);
            scheduleCleanupAfterLocalClose();
            return;
        }

        finalizeCleanup();
    }

    protected void onFinished(String message) {
    }

    protected abstract void sendEnvelope(byte flags, String errorMessage) throws IOException;

    protected final void unregisterHandler() {
        if (handlerRegistered.compareAndSet(true, false)) {
            transport.unregisterHandler(AutoModpackConnectionTransport.KIND_IROH_TUNNEL);
        }
    }

    protected abstract void cleanupLocal();

    private void handleFrame(ByteBuf payload) throws IOException {
        IrohTunnelEnvelope envelope = IrohTunnelEnvelope.decode(payload);
        if (!closed.get()) {
            handleIncoming(envelope);
            return;
        }

        handleFrameAfterClose(envelope);
    }

    private void sendTerminalFrame(byte flags, String errorMessage) {
        if (!terminalFrameSent.compareAndSet(false, true) || !transport.isOpen()) {
            return;
        }

        try {
            sendEnvelope(flags, errorMessage);
        } catch (Exception e) {
            LOGGER.debug("Failed to send terminal {} tunnel envelope", sideLabel.toLowerCase(), e);
        }
    }

    protected long localCloseGraceMillis() {
        return 2_000L;
    }

    private void handleFrameAfterClose(IrohTunnelEnvelope envelope) throws IOException {
        if (!localClosePending.get()) {
            throw new IOException(sideLabel + " tunnel session is closed");
        }

        if (envelope.sessionId() != sessionId) {
            throw new IOException("Mismatched tunnel session id");
        }

        if (envelope.isClose() || envelope.isError()) {
            finalizeCleanup();
            return;
        }

        LOGGER.debug(
            "Ignoring late {} tunnel envelope for session {} after local close",
            sideLabel.toLowerCase(),
            sessionId
        );
    }

    private void scheduleCleanupAfterLocalClose() {
        long graceMillis = Math.max(0L, localCloseGraceMillis());
        if (graceMillis == 0L) {
            finalizeCleanup();
            return;
        }

        try {
            cleanupFuture = CLOSE_GRACE_EXECUTOR.schedule(this::finalizeCleanup, graceMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException error) {
            LOGGER.debug("Failed to schedule {} tunnel cleanup grace period", sideLabel.toLowerCase(), error);
            finalizeCleanup();
        }
    }

    private void finalizeCleanup() {
        if (!cleanupCompleted.compareAndSet(false, true)) {
            return;
        }

        localClosePending.set(false);
        ScheduledFuture<?> future = cleanupFuture;
        cleanupFuture = null;
        if (future != null) {
            future.cancel(false);
        }

        cleanupLocal();
    }
}
