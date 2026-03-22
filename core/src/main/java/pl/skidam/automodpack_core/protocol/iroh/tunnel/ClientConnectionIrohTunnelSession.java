package pl.skidam.automodpack_core.protocol.iroh.tunnel;

import dev.iroh.IrohConnection;
import dev.iroh.IrohNode;
import dev.iroh.IrohPeer;
import dev.iroh.IrohPeerTransport;
import dev.iroh.IrohRemoteAddressBook;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionTransport;
import pl.skidam.automodpack_core.protocol.iroh.AutoModpackIrohNodes;
import pl.skidam.automodpack_core.protocol.iroh.IrohAvailability;
import pl.skidam.automodpack_core.protocol.iroh.IrohIdentity;
import pl.skidam.automodpack_core.protocol.iroh.IrohPathSummary;
import pl.skidam.automodpack_core.protocol.iroh.IrohThreading;
import pl.skidam.automodpack_core.protocol.iroh.IrohTransportSupport;
import pl.skidam.automodpack_core.protocol.iroh.RawTcpIrohBootstrapClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public final class ClientConnectionIrohTunnelSession extends AbstractConnectionIrohTunnelSession {
    private static final Set<Long> INITIAL_TUNNEL_TRANSPORT_IDS =
        Set.of(IrohNode.MINECRAFT_CONNECTION_TRANSPORT_ID);

    private final IrohNode node;
    private final byte[] localEndpointId;
    private final byte[] serverEndpointId;
    private final IrohPeer peer;
    private final IrohPeerTransport minecraftTransport;
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor(IrohThreading.daemonThreadFactory("AutoModpack Iroh Tunnel Client"));
    private final ExecutorService rawBootstrapExecutor = Executors.newSingleThreadExecutor(IrohThreading.daemonThreadFactory("AutoModpack Raw TCP Bootstrap"));
    private final AtomicBoolean readySeen = new AtomicBoolean(false);
    private final AtomicBoolean connectStarted = new AtomicBoolean(false);
    private final AtomicBoolean rawBootstrapStarted = new AtomicBoolean(false);
    private final AtomicBoolean openSent = new AtomicBoolean(false);

    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    private final CompletableFuture<IrohConnection> connectionFuture = new CompletableFuture<>();
    private volatile IrohConnection connection;
    private volatile RawTcpIrohBootstrapClient rawBootstrapClient;
    private final InetSocketAddress rawBootstrapAddress;

    public ClientConnectionIrohTunnelSession(
        long sessionId,
        String serverEndpointIdHex,
        AutoModpackConnectionTransport transport,
        InetSocketAddress rawBootstrapAddress
    ) throws IOException {
        super("Client", sessionId, transport);
        IrohAvailability.requireAvailable();

        this.serverEndpointId = IrohIdentity.fromHex(serverEndpointIdHex);
        this.node = AutoModpackIrohNodes.createTunnelNode();
        this.localEndpointId = node.getId();
        this.peer = node.addPeer(this.serverEndpointId);
        this.rawBootstrapAddress = rawBootstrapAddress;
        this.minecraftTransport = peer.getTransport(IrohNode.MINECRAFT_CONNECTION_TRANSPORT_ID);
        this.minecraftTransport.setOnTransmit(packet -> {
            if (!closed.get()) {
                outgoingPackets.add(packet);
                flushOutgoing();
            }
        });
    }

    public synchronized void start() throws IOException {
        startSession();
    }

    public IrohConnection awaitConnection(long timeout, TimeUnit unit) throws IOException {
        if (terminalError != null) {
            throw new IOException(terminalError);
        }

        try {
            long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
            awaitReady(deadlineNanos);
            IrohConnection active = connection;
            if (active != null) {
                LOGGER.debug("Using established connection-level iroh connection on session {}", sessionId);
                return active;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new java.util.concurrent.TimeoutException("Timed out while waiting for connection-level iroh tunnel connection");
            }
            return connectionFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            throw new IOException("Failed to establish connection-level iroh tunnel connection", e);
        }
    }

    @Override
    protected synchronized void handleIncoming(IrohTunnelEnvelope envelope) throws IOException {
        if (envelope.sessionId() != sessionId) {
            throw new IOException("Mismatched tunnel session id");
        }

        if (envelope.isError()) {
            finish(envelope.errorMessage() == null ? "Server tunnel error" : envelope.errorMessage(), false, false);
            return;
        }

        if (envelope.isClose()) {
            finish("Server closed connection-level iroh tunnel session", false, false);
            return;
        }

        for (byte[] packet : envelope.packets()) {
            if (!minecraftTransport.injectPacket(packet)) {
                markError("Failed to inject server tunnel packet");
                return;
            }
        }

        if (envelope.isReady() && readySeen.compareAndSet(false, true)) {
            LOGGER.info("READY received for connection-level iroh tunnel session {}", sessionId);
            readyFuture.complete(null);
            startConnectIfNeeded();
        }
    }

    private synchronized void sendOpen() throws IOException {
        if (closed.get() || !openSent.compareAndSet(false, true)) {
            return;
        }

        sendEnvelope(IrohTunnelEnvelope.FLAG_OPEN, localEndpointId, null);
    }

    private void awaitReady(long deadlineNanos) throws Exception {
        if (readySeen.get()) {
            return;
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new java.util.concurrent.TimeoutException("Timed out while waiting for connection-level iroh tunnel READY");
        }
        readyFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private void startConnectIfNeeded() {
        if (!connectStarted.compareAndSet(false, true)) {
            return;
        }

        connectExecutor.execute(() -> {
            try {
                // The connection-level tunnel is guaranteed to have the minecraft carrier once READY is seen.
                // The raw TCP bootstrap is optional and may still be racing its own setup, so do not let it
                // gate the initial tunnel connection.
                IrohConnection created = IrohTransportSupport.connectWithRetries(
                    node,
                    IrohRemoteAddressBook.of(serverEndpointId, java.util.List.of(), INITIAL_TUNNEL_TRANSPORT_IDS),
                    5,
                    IrohTransportSupport.CONNECT_TIMEOUT_MS
                );
                if (created == null) {
                    throw new IOException("Failed to establish iroh connection over connection-level tunnel");
                }
                connection = created;
                LOGGER.info(
                    "Established connection-level iroh tunnel to {} via {}",
                    IrohTransportSupport.shortPeerId(serverEndpointId),
                    IrohPathSummary.describe(created)
                );
                connectionFuture.complete(created);
            } catch (Throwable error) {
                connectionFuture.completeExceptionally(error);
                markError("Failed to establish iroh connection over connection-level tunnel");
            }
        });
    }

    private void startRawBootstrapIfConfigured() {
        if (rawBootstrapAddress == null || !rawBootstrapStarted.compareAndSet(false, true)) {
            return;
        }

        rawBootstrapExecutor.execute(() -> {
            try {
                RawTcpIrohBootstrapClient client = new RawTcpIrohBootstrapClient(node, serverEndpointId, rawBootstrapAddress);
                if (closed.get()) {
                    client.close();
                    return;
                }
                rawBootstrapClient = client;
                LOGGER.info("Initialized optional raw TCP iroh bootstrap to {} alongside minecraft transport", rawBootstrapAddress);
            } catch (IOException e) {
                if (!closed.get()) {
                    LOGGER.warn(
                        "Optional raw TCP iroh bootstrap to {} failed, continuing with minecraft transport only: {}",
                        rawBootstrapAddress,
                        e.getMessage()
                    );
                    LOGGER.debug("Optional raw TCP iroh bootstrap failure alongside minecraft transport", e);
                }
            }
        });
    }

    @Override
    protected void afterStart() throws IOException {
        startRawBootstrapIfConfigured();
        sendOpen();
    }

    @Override
    protected void onFinished(String message) {
        IOException error = new IOException(message == null ? "Connection-level iroh tunnel session closed" : message);
        readyFuture.completeExceptionally(error);
        connectionFuture.completeExceptionally(error);
    }

    @Override
    protected synchronized void sendEnvelope(byte flags, String errorMessage) throws IOException {
        sendEnvelope(flags, null, errorMessage);
    }

    private synchronized void sendEnvelope(byte flags, byte[] endpointId, String errorMessage) throws IOException {
        IrohTunnelEnvelope.EncodedTunnelEnvelope encoded = IrohTunnelEnvelope.buildFromQueue(
            PooledByteBufAllocator.DEFAULT,
            sessionId,
            flags,
            endpointId,
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
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOGGER.debug("Failed to close connection-level iroh connection", e);
            }
        }
        if (rawBootstrapClient != null) {
            try {
                rawBootstrapClient.close();
            } catch (Exception e) {
                LOGGER.debug("Failed to close raw TCP iroh bootstrap client", e);
            }
        }
        node.close();
        connectExecutor.shutdownNow();
        rawBootstrapExecutor.shutdownNow();
    }

    @Override
    public void close() {
        finish(null, true, false);
    }
}
