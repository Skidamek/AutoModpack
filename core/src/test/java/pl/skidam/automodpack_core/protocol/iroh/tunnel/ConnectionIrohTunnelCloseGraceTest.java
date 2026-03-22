package pl.skidam.automodpack_core.protocol.iroh.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionTransport;
import pl.skidam.automodpack_core.networking.connection.AutoModpackFrameHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionIrohTunnelCloseGraceTest {
    @Test
    void localCloseKeepsHandlerUntilPeerCloseArrives() throws Exception {
        FakeTransport transport = new FakeTransport();
        TestSession session = new TestSession(4159437774626110579L, transport, 250L);
        session.start();

        session.close();

        assertFalse(session.isActive());
        assertNotNull(transport.handler);
        assertEquals(1, transport.sentEnvelopes.size());
        assertTrue(transport.sentEnvelopes.get(0).isClose());

        transport.deliver(new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            session.getSessionId(),
            (byte) 0,
            null,
            List.of(new byte[] {1, 2, 3}),
            null
        ));

        assertEquals(0, session.handledEnvelopeCount.get());
        assertEquals(0, session.cleanupCount.get());
        assertNotNull(transport.handler);

        transport.deliver(new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            session.getSessionId(),
            IrohTunnelEnvelope.FLAG_CLOSE,
            null,
            List.of(),
            null
        ));

        awaitCondition(() -> session.cleanupCount.get() == 1, 1_000L);
        assertEquals(1, session.cleanupCount.get());
        assertFalse(transport.isHandlerRegistered());
    }

    @Test
    void localCloseEventuallyCleansUpWithoutPeerClose() throws Exception {
        FakeTransport transport = new FakeTransport();
        TestSession session = new TestSession(4546488400254771556L, transport, 25L);
        session.start();

        session.close();

        assertNotNull(transport.handler);
        awaitCondition(() -> session.cleanupCount.get() == 1, 1_000L);
        assertEquals(1, session.cleanupCount.get());
        assertFalse(transport.isHandlerRegistered());
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        assertTrue(condition.getAsBoolean(), "Timed out waiting for tunnel cleanup");
    }

    private static final class FakeTransport implements AutoModpackConnectionTransport {
        private final List<IrohTunnelEnvelope> sentEnvelopes = new CopyOnWriteArrayList<>();
        private volatile AutoModpackFrameHandler handler;

        @Override
        public void registerHandler(byte kind, AutoModpackFrameHandler handler) {
            assertEquals(KIND_IROH_TUNNEL, kind);
            this.handler = handler;
        }

        @Override
        public void unregisterHandler(byte kind) {
            assertEquals(KIND_IROH_TUNNEL, kind);
            this.handler = null;
        }

        @Override
        public void sendFrame(byte kind, ByteBuf payload) throws IOException {
            assertEquals(KIND_IROH_TUNNEL, kind);
            ByteBuf copy = payload.copy();
            try {
                sentEnvelopes.add(IrohTunnelEnvelope.decode(copy));
            } finally {
                ReferenceCountUtil.safeRelease(copy);
                ReferenceCountUtil.safeRelease(payload);
            }
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        public boolean isHandlerRegistered() {
            return handler != null;
        }

        public void deliver(IrohTunnelEnvelope envelope) throws Exception {
            ByteBuf encoded = envelope.encode(UnpooledByteBufAllocator.DEFAULT);
            try {
                AutoModpackFrameHandler activeHandler = handler;
                assertNotNull(activeHandler);
                activeHandler.handle(encoded);
            } finally {
                ReferenceCountUtil.safeRelease(encoded);
            }
        }
    }

    private static final class TestSession extends AbstractConnectionIrohTunnelSession {
        private final long closeGraceMillis;
        private final AtomicInteger handledEnvelopeCount = new AtomicInteger();
        private final AtomicInteger cleanupCount = new AtomicInteger();

        private TestSession(long sessionId, AutoModpackConnectionTransport transport, long closeGraceMillis) {
            super("Test", sessionId, transport);
            this.closeGraceMillis = closeGraceMillis;
        }

        public void start() throws IOException {
            startSession();
        }

        @Override
        protected void handleIncoming(IrohTunnelEnvelope envelope) throws IOException {
            handledEnvelopeCount.incrementAndGet();

            if (envelope.isError()) {
                finish(envelope.errorMessage(), false, false);
            } else if (envelope.isClose()) {
                finish(null, false, false);
            }
        }

        @Override
        protected synchronized void sendEnvelope(byte flags, String errorMessage) throws IOException {
            ByteBuf encoded = new IrohTunnelEnvelope(
                IrohTunnelEnvelope.CURRENT_VERSION,
                sessionId,
                flags,
                null,
                List.of(),
                errorMessage
            ).encode(UnpooledByteBufAllocator.DEFAULT);
            transport.sendFrame(AutoModpackConnectionTransport.KIND_IROH_TUNNEL, encoded);
        }

        @Override
        protected void cleanupLocal() {
            cleanupCount.incrementAndGet();
            unregisterHandler();
        }

        @Override
        protected long localCloseGraceMillis() {
            return closeGraceMillis;
        }

        @Override
        public void close() {
            finish(null, true, false);
        }
    }
}
