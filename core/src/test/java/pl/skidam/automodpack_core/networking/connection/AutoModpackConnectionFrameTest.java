package pl.skidam.automodpack_core.networking.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AutoModpackConnectionFrameTest {

    @Test
    void roundTripPreservesKindAndPayload() throws Exception {
        ByteBuf payload = Unpooled.wrappedBuffer("hello".getBytes(StandardCharsets.UTF_8));
        ByteBuf encoded = AutoModpackConnectionFrame.encode(UnpooledByteBufAllocator.DEFAULT, (byte) 1, payload);
        try (AutoModpackConnectionFrame decoded = AutoModpackConnectionFrame.decode(encoded)) {
            assertEquals(1, decoded.kind());
            assertEquals("hello", decoded.payload().toString(StandardCharsets.UTF_8));
        } finally {
            encoded.release();
            payload.release();
        }
    }

    @Test
    void matchesRequiresMagicPrefix() throws Exception {
        assertFalse(AutoModpackConnectionFrame.matches(Unpooled.wrappedBuffer(new byte[]{0x01, 0x02, 0x03})));
        ByteBuf payload = Unpooled.buffer(0);
        ByteBuf encoded = AutoModpackConnectionFrame.encode(UnpooledByteBufAllocator.DEFAULT, (byte) 1, payload);
        try {
            assertTrue(AutoModpackConnectionFrame.matches(encoded));
        } finally {
            encoded.release();
            payload.release();
        }
    }

    @Test
    void decodeRejectsUnknownVersion() throws Exception {
        ByteBuf payload = Unpooled.buffer(0);
        ByteBuf encoded = AutoModpackConnectionFrame.encode(UnpooledByteBufAllocator.DEFAULT, (byte) 1, payload);
        encoded.setByte(4, 2);

        IOException error = assertThrows(IOException.class, () ->
            AutoModpackConnectionFrame.decode(encoded)
        );
        assertTrue(error.getMessage().contains("Unsupported"));
        encoded.release();
        payload.release();
    }

    @Test
    void decodeRejectsInvalidKind() throws Exception {
        ByteBuf payload = Unpooled.buffer(0);
        ByteBuf encoded = AutoModpackConnectionFrame.encode(UnpooledByteBufAllocator.DEFAULT, (byte) 1, payload);
        encoded.setByte(5, 0);

        IOException error = assertThrows(IOException.class, () ->
            AutoModpackConnectionFrame.decode(encoded)
        );
        assertTrue(error.getMessage().contains("Invalid"));
        encoded.release();
        payload.release();
    }

    @Test
    void decodeRejectsShortFrame() {
        IOException error = assertThrows(IOException.class, () ->
            AutoModpackConnectionFrame.decode(Unpooled.wrappedBuffer(new byte[]{0x41, 0x4d}))
        );
        assertTrue(error.getMessage().contains("short"));
    }

    @Test
    void matchesTreatsMagicPrefixAsFramedTraffic() {
        assertTrue(AutoModpackConnectionFrame.matches(Unpooled.wrappedBuffer(new byte[]{0x41, 0x4d, 0x54, 0x54})));
    }
}
