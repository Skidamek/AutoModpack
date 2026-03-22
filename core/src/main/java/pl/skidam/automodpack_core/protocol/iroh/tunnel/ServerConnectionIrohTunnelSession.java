package pl.skidam.automodpack_core.protocol.iroh.tunnel;

import dev.iroh.IrohPeer;
import dev.iroh.IrohPeerTransport;
import io.netty.buffer.PooledByteBufAllocator;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionTransport;
import pl.skidam.automodpack_core.protocol.HybridHostServer;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class ServerConnectionIrohTunnelSession extends AbstractConnectionIrohTunnelSession {
    private final HybridHostServer hostServer;
    private final AtomicBoolean readySent = new AtomicBoolean(false);

    private volatile IrohPeer peer;
    private volatile IrohPeerTransport minecraftTransport;

    public ServerConnectionIrohTunnelSession(long sessionId, HybridHostServer hostServer, String serverEndpointIdHex, AutoModpackConnectionTransport transport) throws IOException {
        super("Server", sessionId, transport);
        this.hostServer = hostServer;

        // Validate eagerly so misconfigured endpoint ids fail before DATA is sent.
        IrohIdentity.fromHex(serverEndpointIdHex);
    }

    public synchronized void start() {
        try {
            startSession();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start connection-level iroh tunnel session", e);
        }
    }

    @Override
    protected synchronized void handleIncoming(IrohTunnelEnvelope envelope) throws IOException {
        if (envelope.sessionId() != sessionId) {
            throw new IOException("Mismatched tunnel session id");
        }

        if (envelope.isError()) {
            finish(envelope.errorMessage(), false, false);
            return;
        }

        if (envelope.isClose()) {
            finish(null, false, false);
            return;
        }

        if (envelope.isOpen() && peer == null) {
            byte[] clientEndpointId = envelope.endpointId();
            if (clientEndpointId == null || clientEndpointId.length != IrohTunnelEnvelope.ENDPOINT_ID_LENGTH) {
                throw new IOException("Client tunnel OPEN did not include endpoint id");
            }

            if (!hostServer.isEndpointAuthorized(clientEndpointId)) {
                markError("Client endpoint is not authorized");
                return;
            }

            peer = hostServer.bootstrapIrohPeer(this, clientEndpointId);
            if (peer == null) {
                markError("Failed to bootstrap server iroh peer");
                return;
            }
            minecraftTransport = peer.getTransport(dev.iroh.IrohNode.MINECRAFT_CONNECTION_TRANSPORT_ID);

            LOGGER.info("Server bootstrapped connection-level iroh peer for session {}", sessionId);
            minecraftTransport.setOnTransmit(packet -> {
                if (!closed.get()) {
                    outgoingPackets.add(packet);
                    flushOutgoing();
                }
            });
            sendReady();
        }

        if (peer == null && !envelope.packets().isEmpty()) {
            throw new IOException("Client sent tunnel packets before OPEN");
        }

        if (peer != null) {
            for (byte[] packet : envelope.packets()) {
                if (!minecraftTransport.injectPacket(packet)) {
                    markError("Failed to inject client tunnel packet");
                    return;
                }
            }
        }
    }

    private synchronized void sendReady() {
        if (closed.get() || !readySent.compareAndSet(false, true)) {
            return;
        }

        try {
            sendEnvelope(IrohTunnelEnvelope.FLAG_READY, null);
        } catch (IOException e) {
            markError("Failed to send READY for connection-level tunnel");
        }
    }

    @Override
    protected synchronized void sendEnvelope(byte flags, String errorMessage) throws IOException {
        IrohTunnelEnvelope.EncodedTunnelEnvelope encoded = IrohTunnelEnvelope.buildFromQueue(
            PooledByteBufAllocator.DEFAULT,
            sessionId,
            flags,
            null,
            outgoingPackets,
            errorMessage
        );
        if (flags == 0 && encoded.packetCount() == 0) {
            encoded.buffer().release();
            return;
        }

        transport.sendFrame(AutoModpackConnectionTransport.KIND_IROH_TUNNEL, encoded.buffer());
    }

    @Override
    protected void cleanupLocal() {
        unregisterHandler();
        hostServer.unregisterIrohBootstrap(this);
    }

    @Override
    public void close() {
        finish(null, true, false);
    }
}
