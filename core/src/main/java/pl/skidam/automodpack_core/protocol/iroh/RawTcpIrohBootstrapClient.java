package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohNode;
import dev.iroh.IrohPeerTransport;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMID;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMMH;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMOK;

public final class RawTcpIrohBootstrapClient implements AutoCloseable {
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int MAX_FRAME_SIZE = 1024 * 1024;

    private final IrohPeerTransport transport;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ExecutorService readerExecutor;
    private final ExecutorService writerExecutor;
    private final Object writeLock = new Object();
    private final String peerLabel;
    private final String routeLabel;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean transmitLogged;
    private volatile boolean receiveLogged;

    public RawTcpIrohBootstrapClient(IrohNode node, byte[] remoteId, InetSocketAddress address) throws IOException {
        IrohPeerTransport createdTransport = node.addPeer(remoteId).getTransport(IrohNode.RAW_TCP_TRANSPORT_ID);
        ExecutorService createdReaderExecutor = newExecutor("AutoModpack Raw TCP Iroh Reader #%d");
        ExecutorService createdWriterExecutor = newExecutor("AutoModpack Raw TCP Iroh Writer #%d");
        Socket openedSocket = new Socket();

        DataInputStream openedIn = null;
        DataOutputStream openedOut = null;
        String createdRouteLabel = address.getHostString() + ":" + address.getPort();
        String createdPeerLabel = IrohTransportSupport.shortPeerId(remoteId);

        try {
            openedSocket.setTcpNoDelay(true);
            openedSocket.setKeepAlive(true);
            openedSocket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), HANDSHAKE_TIMEOUT_MS);
            openedSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            openedIn = new DataInputStream(new BufferedInputStream(openedSocket.getInputStream()));
            openedOut = new DataOutputStream(new BufferedOutputStream(openedSocket.getOutputStream()));

            LOGGER.info("Bootstrapping iroh pipe to {} for peer {}", createdRouteLabel, createdPeerLabel);
            writeHandshake(openedOut, buildBootstrapPayload(address.getHostString()));

            int response;
            try {
                response = openedIn.readInt();
            } catch (SocketTimeoutException e) {
                throw new IOException("Timed out waiting for iroh bootstrap acknowledgment", e);
            }
            if (response != MAGIC_AMOK) {
                throw new IOException("Invalid iroh bootstrap acknowledgment: " + response);
            }

            writeHandshake(openedOut, buildAmidPayload(node.getId()));
            openedSocket.setSoTimeout(0);
        } catch (IOException | RuntimeException e) {
            createdReaderExecutor.shutdownNow();
            createdWriterExecutor.shutdownNow();
            closeQuietly(openedSocket);
            throw e;
        }

        this.transport = createdTransport;
        this.socket = openedSocket;
        this.in = openedIn;
        this.out = openedOut;
        this.readerExecutor = createdReaderExecutor;
        this.writerExecutor = createdWriterExecutor;
        this.peerLabel = createdPeerLabel;
        this.routeLabel = createdRouteLabel;

        transport.setOnTransmit(this::writeAsync);
        readerExecutor.execute(this::readLoop);
        LOGGER.info("Iroh bootstrap acknowledged for peer {}", peerLabel);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        transport.setOnTransmit(null);
        closeQuietly(socket);
        writerExecutor.shutdownNow();
        readerExecutor.shutdownNow();
    }

    private void writeAsync(byte[] packet) {
        if (closed.get()) {
            return;
        }

        byte[] copy = Arrays.copyOf(packet, packet.length);
        try {
            writerExecutor.execute(() -> writeFrame(copy));
        } catch (RejectedExecutionException e) {
            if (!closed.get()) {
                fail("Failed to queue raw TCP iroh packet", e);
            }
        }
    }

    private void writeFrame(byte[] packet) {
        if (closed.get()) {
            return;
        }

        try {
            if (!transmitLogged) {
                transmitLogged = true;
                LOGGER.info("Observed first outbound raw TCP iroh packet on {} for peer {}", routeLabel, peerLabel);
            }
            synchronized (writeLock) {
                out.writeInt(packet.length);
                out.write(packet);
                out.flush();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                fail("Failed while writing raw TCP iroh packet", e);
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                int frameLength;
                try {
                    frameLength = in.readInt();
                } catch (EOFException e) {
                    if (!closed.get()) {
                        fail("Raw TCP iroh pipe closed by remote peer", e);
                    }
                    return;
                }

                if (frameLength < 0 || frameLength > MAX_FRAME_SIZE) {
                    throw new IOException("Invalid raw TCP iroh frame length: " + frameLength);
                }

                byte[] packet = in.readNBytes(frameLength);
                if (packet.length != frameLength) {
                    throw new EOFException("Unexpected EOF while reading raw TCP iroh frame");
                }

                if (!receiveLogged) {
                    receiveLogged = true;
                    LOGGER.info("Observed first inbound raw TCP iroh packet on {} for peer {}", routeLabel, peerLabel);
                }

                transport.injectPacket(packet);
            }
        } catch (IOException e) {
            if (!closed.get()) {
                fail("Failed while reading raw TCP iroh packet", e);
            }
        }
    }

    private void fail(String message, Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        LOGGER.warn("{} for peer {}", message, peerLabel, cause);
        transport.setOnTransmit(null);
        closeQuietly(socket);
        writerExecutor.shutdownNow();
        readerExecutor.shutdownNow();
    }

    private static void writeHandshake(DataOutputStream out, byte[] payload) throws IOException {
        out.write(payload);
        out.flush();
    }

    private static byte[] buildBootstrapPayload(String hostname) throws IOException {
        byte[] hostBytes = hostname.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(4 + 2 + hostBytes.length);
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(MAGIC_AMMH);
            out.writeShort(hostBytes.length);
            out.write(hostBytes);
        }
        return bytes.toByteArray();
    }

    private static byte[] buildAmidPayload(byte[] localNodeId) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(4 + 32);
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(MAGIC_AMID);
            out.write(localNodeId);
        }
        return bytes.toByteArray();
    }

    private static ExecutorService newExecutor(String nameFormat) {
        return Executors.newSingleThreadExecutor(
            new CustomThreadFactoryBuilder()
                .setNameFormat(nameFormat)
                .setDaemon(true)
                .build()
        );
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
