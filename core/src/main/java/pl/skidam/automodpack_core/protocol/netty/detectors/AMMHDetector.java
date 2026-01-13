package pl.skidam.automodpack_core.protocol.netty.detectors;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

import static pl.skidam.automodpack_core.protocol.NetUtils.MAGIC_AMMH;

public class AMMHDetector {

    private static final byte[] MAGIC_AMMH_ARRAY = {
        (byte) (MAGIC_AMMH >>> 24),
        (byte) (MAGIC_AMMH >>> 16),
        (byte) (MAGIC_AMMH >>> 8),
        (byte) MAGIC_AMMH
    };

    public static MatchResult check(ByteBuf in) {
        final int readable = in.readableBytes();
        final int start = in.readerIndex();

        for (int i = 0; i < MAGIC_AMMH_ARRAY.length; i++) {
            if (readable <= i) return MatchResult.PARTIAL;
            if (in.getByte(start + i) != MAGIC_AMMH_ARRAY[i]) return MatchResult.MISMATCH;
        }
        return MatchResult.MATCHED;
    }

    public record DecodeResult(String hostname, int consumedBytes) { }

    public static DecodeResult decode(ByteBuf in) {
        // [4: Magic] [2: Len] [Len: Hostname]
        try {
            if (in.readableBytes() < 6) return null;

            int len = in.getUnsignedShort(in.readerIndex() + 4);
            int totalLen = 6 + len;

            if (in.readableBytes() < totalLen) return null;

            byte[] bytes = new byte[len];
            in.getBytes(in.readerIndex() + 6, bytes);
            String hostname = new String(bytes, StandardCharsets.UTF_8);

            return new DecodeResult(hostname, totalLen);
        } catch (Exception ignored) {
            return new DecodeResult(null, 0);
        }
    }
}