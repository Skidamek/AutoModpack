package pl.skidam.automodpack_core.protocol.netty.detectors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMMH;

class AMMHDetectorTest {

    @Test
    void decodesExactLegacyFrameLayout() {
        byte[] hostnameBytes = "example.org".getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MAGIC_AMMH);
        buf.writeShort(hostnameBytes.length);
        buf.writeBytes(hostnameBytes);

        try {
            assertEquals(MatchResult.MATCHED, AMMHDetector.check(buf));
            AMMHDetector.DecodeResult decoded = AMMHDetector.decode(buf);
            assertEquals("example.org", decoded.hostname());
            assertEquals(6 + hostnameBytes.length, decoded.consumedBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void reportsPartialUntilFullLegacyFrameArrives() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte((byte) (MAGIC_AMMH >>> 24));
        buf.writeByte((byte) (MAGIC_AMMH >>> 16));

        try {
            assertEquals(MatchResult.PARTIAL, AMMHDetector.check(buf));
            assertNull(AMMHDetector.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsMismatchedMagic() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(0x00000000);

        try {
            assertEquals(MatchResult.MISMATCH, AMMHDetector.check(buf));
        } finally {
            buf.release();
        }
    }
}
