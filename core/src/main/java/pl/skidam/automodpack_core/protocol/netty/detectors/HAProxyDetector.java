package pl.skidam.automodpack_core.protocol.netty.detectors;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

public class HAProxyDetector {

    private static final byte[] V1_SIG = { 'P', 'R', 'O', 'X', 'Y', ' ' };
    private static final byte[] V2_SIG = {
            (byte) 0x0D, (byte) 0x0A, (byte) 0x0D, (byte) 0x0A,
            (byte) 0x00, (byte) 0x0D, (byte) 0x0A, (byte) 0x51,
            (byte) 0x55, (byte) 0x49, (byte) 0x54, (byte) 0x0A
    };

    public static MatchResult check(ByteBuf in) {
        final int start = in.readerIndex();
        final int readable = in.readableBytes();

        boolean v1Possible = true;
        boolean v2Possible = true;

        // Check V2
        for (int i = 0; i < V2_SIG.length; i++) {
            if (readable <= i) break;
            if (in.getByte(start + i) != V2_SIG[i]) {
                v2Possible = false;
                break;
            }
            if (i == V2_SIG.length - 1) return MatchResult.MATCHED;
        }

        // Check V1
        for (int i = 0; i < V1_SIG.length; i++) {
            if (readable <= i) break;
            if (in.getByte(start + i) != V1_SIG[i]) {
                v1Possible = false;
                break;
            }
            if (i == V1_SIG.length - 1) return MatchResult.MATCHED;
        }

        if (!v1Possible && !v2Possible) return MatchResult.MISMATCH;
        return MatchResult.PARTIAL;
    }

    public record DecodeResult(HAProxyMessage message, int consumedBytes) { }

    public static DecodeResult decode(ByteBuf in) {
        EmbeddedChannel channel = new EmbeddedChannel(new HAProxyMessageDecoder());
        try {
            ByteBuf slice = in.slice(in.readerIndex(), in.readableBytes());
            if (channel.writeInbound(slice.retain())) {
                HAProxyMessage msg = channel.readInbound();
                return new DecodeResult(msg, slice.readerIndex());
            }
        } catch (Exception ignored) {
            return new DecodeResult(null, 0);
        } finally {
            channel.finishAndReleaseAll();
        }
        return null;
    }
}