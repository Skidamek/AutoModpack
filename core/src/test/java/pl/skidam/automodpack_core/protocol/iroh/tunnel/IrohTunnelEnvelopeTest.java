package pl.skidam.automodpack_core.protocol.iroh.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IrohTunnelEnvelopeTest {

    @Test
    void roundTripPreservesEmptyEnvelope() throws Exception {
        IrohTunnelEnvelope envelope = new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            42L,
            (byte) 0,
            null,
            List.of(),
            null
        );

        ByteBuf encoded = envelope.encode(UnpooledByteBufAllocator.DEFAULT);
        try {
            IrohTunnelEnvelope decoded = IrohTunnelEnvelope.decode(encoded);
            assertEquals(envelope.sessionId(), decoded.sessionId());
            assertEquals(0, decoded.flags());
            assertTrue(decoded.packets().isEmpty());
            assertNull(decoded.errorMessage());
        } finally {
            encoded.release();
        }
    }

    @Test
    void roundTripPreservesOpenAndMultiplePackets() throws Exception {
        byte[] endpointId = HexFormat.of().parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        IrohTunnelEnvelope envelope = new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            99L,
            IrohTunnelEnvelope.FLAG_OPEN,
            endpointId,
            List.of("first".getBytes(), "second".getBytes()),
            null
        );

        ByteBuf encoded = envelope.encode(UnpooledByteBufAllocator.DEFAULT);
        try {
            IrohTunnelEnvelope decoded = IrohTunnelEnvelope.decode(encoded);
            assertTrue(decoded.isOpen());
            assertArrayEquals(endpointId, decoded.endpointId());
            assertEquals(2, decoded.packets().size());
            assertArrayEquals("first".getBytes(), decoded.packets().get(0));
            assertArrayEquals("second".getBytes(), decoded.packets().get(1));
        } finally {
            encoded.release();
        }
    }

    @Test
    void roundTripPreservesCloseAndError() throws Exception {
        IrohTunnelEnvelope envelope = new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            7L,
            (byte) (IrohTunnelEnvelope.FLAG_CLOSE | IrohTunnelEnvelope.FLAG_ERROR),
            null,
            List.of(),
            "boom"
        );

        ByteBuf encoded = envelope.encode(UnpooledByteBufAllocator.DEFAULT);
        try {
            IrohTunnelEnvelope decoded = IrohTunnelEnvelope.decode(encoded);
            assertTrue(decoded.isClose());
            assertTrue(decoded.isError());
            assertEquals("boom", decoded.errorMessage());
        } finally {
            encoded.release();
        }
    }

    @Test
    void roundTripPreservesReady() throws Exception {
        IrohTunnelEnvelope envelope = new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            17L,
            IrohTunnelEnvelope.FLAG_READY,
            null,
            List.of(),
            null
        );

        ByteBuf encoded = envelope.encode(UnpooledByteBufAllocator.DEFAULT);
        try {
            IrohTunnelEnvelope decoded = IrohTunnelEnvelope.decode(encoded);
            assertTrue(decoded.isReady());
            assertFalse(decoded.isOpen());
            assertFalse(decoded.isClose());
            assertFalse(decoded.isError());
        } finally {
            encoded.release();
        }
    }

    @Test
    void roundTripPreservesOpenAndReady() throws Exception {
        byte[] endpointId = HexFormat.of().parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        IrohTunnelEnvelope envelope = new IrohTunnelEnvelope(
            IrohTunnelEnvelope.CURRENT_VERSION,
            123L,
            (byte) (IrohTunnelEnvelope.FLAG_OPEN | IrohTunnelEnvelope.FLAG_READY),
            endpointId,
            List.of("hello".getBytes()),
            null
        );

        ByteBuf encoded = envelope.encode(UnpooledByteBufAllocator.DEFAULT);
        try {
            IrohTunnelEnvelope decoded = IrohTunnelEnvelope.decode(encoded);
            assertTrue(decoded.isOpen());
            assertTrue(decoded.isReady());
            assertArrayEquals(endpointId, decoded.endpointId());
            assertEquals(1, decoded.packets().size());
        } finally {
            encoded.release();
        }
    }

    @Test
    void fromQueueSplitsPacketsAtPayloadBudget() throws Exception {
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
        queue.add(new byte[128 * 1024]);
        queue.add(new byte[128 * 1024]);
        queue.add(new byte[4]);

        IrohTunnelEnvelope.EncodedTunnelEnvelope encoded = IrohTunnelEnvelope.buildFromQueue(UnpooledByteBufAllocator.DEFAULT, 5L, (byte) 0, null, queue, null);
        assertEquals(1, encoded.packetCount());
        assertEquals(2, queue.size());
        encoded.buffer().release();
    }

    @Test
    void oversizePacketStaysQueuedWhenBatching() throws Exception {
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
        queue.add(new byte[IrohTunnelEnvelope.MAX_ENCODED_BYTES]);

        IrohTunnelEnvelope.EncodedTunnelEnvelope encoded = IrohTunnelEnvelope.buildFromQueue(UnpooledByteBufAllocator.DEFAULT, 5L, (byte) 0, null, queue, null);
        assertEquals(0, encoded.packetCount());
        assertEquals(1, queue.size());
        encoded.buffer().release();
    }

    @Test
    void fromQueuePreservesReadyFlagWhileBatching() throws Exception {
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
        queue.add("hello".getBytes());

        IrohTunnelEnvelope.EncodedTunnelEnvelope encoded = IrohTunnelEnvelope.buildFromQueue(UnpooledByteBufAllocator.DEFAULT, 5L, IrohTunnelEnvelope.FLAG_READY, null, queue, null);
        ByteBuf copy = Unpooled.copiedBuffer(encoded.buffer());
        try {
            IrohTunnelEnvelope envelope = IrohTunnelEnvelope.decode(copy);
            assertTrue(envelope.isReady());
            assertEquals(1, encoded.packetCount());
            assertTrue(queue.isEmpty());
        } finally {
            copy.release();
            encoded.buffer().release();
        }
    }

    @Test
    void oversizeEncodedEnvelopeIsRejected() {
        IOException error = assertThrows(IOException.class, () ->
            new IrohTunnelEnvelope(
                IrohTunnelEnvelope.CURRENT_VERSION,
                5L,
                (byte) 0,
                null,
                List.of(new byte[IrohTunnelEnvelope.MAX_ENCODED_BYTES]),
                null
            ).encode(UnpooledByteBufAllocator.DEFAULT)
        );
        assertTrue(error.getMessage().contains("exceeds"));
    }
}
