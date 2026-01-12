package pl.skidam.automodpack_core.protocol.netty;

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

    public enum MatchResult { MATCHED, MISMATCH, PARTIAL }

    /**
     * Checks if the incoming buffer starts with a PROXY protocol signature.
     * Returns MISMATCH immediately if the first few bytes don't match.
     */
    public static MatchResult check(ByteBuf in, int start, int readable) {
        boolean v1Possible = true;
        boolean v2Possible = true;

        for (int i = 0; i < V2_SIG.length; i++) {
            if (readable <= i) break;
            if (in.getByte(start + i) != V2_SIG[i]) {
                v2Possible = false;
                break;
            }
            if (i == V2_SIG.length - 1) return MatchResult.MATCHED;
        }

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

    /**
     * Decodes the PROXY header and advances the buffer's readerIndex.
     */
    public static HAProxyMessage decodeAndAdvance(ByteBuf in) {
        EmbeddedChannel channel = new EmbeddedChannel(new HAProxyMessageDecoder());
        try {
            ByteBuf slice = in.slice(in.readerIndex(), in.readableBytes());
            if (channel.writeInbound(slice.retain())) {
                HAProxyMessage msg = channel.readInbound();
                if (msg != null) {
                    in.skipBytes(slice.readerIndex());
                    return msg;
                }
            }
        } catch (Exception ignored) {
        } finally {
            channel.finishAndReleaseAll();
        }
        return null;
    }
}