package pl.skidam.automodpack_core.networking.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;

public final class AutoModpackConnectionFrame implements AutoCloseable {
    public static final int MAGIC = 0x414D5454;
    public static final byte CURRENT_VERSION = 1;
    public static final int HEADER_SIZE = Integer.BYTES + 2;

    private final byte kind;
    private final ByteBuf payload;

    private AutoModpackConnectionFrame(byte kind, ByteBuf payload) {
        this.kind = kind;
        this.payload = payload;
    }

    public byte kind() {
        return kind;
    }

    public ByteBuf payload() {
        return payload;
    }

    public int encodedLength() {
        return HEADER_SIZE + payload.readableBytes();
    }

    public static ByteBuf encode(ByteBufAllocator allocator, byte kind, ByteBuf payload) throws IOException {
        if (kind <= 0) {
            throw new IOException("Invalid AutoModpack frame kind: " + kind);
        }
        if (payload == null) {
            throw new IOException("AutoModpack frame payload cannot be null");
        }

        long startedAt = AutoModpackConnectionMetrics.startDebugTimer();
        int payloadBytes = payload.readableBytes();
        ByteBuf encoded = allocator.buffer(HEADER_SIZE + payloadBytes, HEADER_SIZE + payloadBytes);
        try {
            encoded.writeInt(MAGIC);
            encoded.writeByte(CURRENT_VERSION);
            encoded.writeByte(kind);
            encoded.writeBytes(payload, payload.readerIndex(), payloadBytes);
            return encoded;
        } catch (Throwable error) {
            encoded.release();
            throw error;
        } finally {
            AutoModpackConnectionMetrics.logDebugDuration("AutoModpack frame encode", startedAt);
        }
    }

    public static boolean matches(ByteBuf buf) {
        return buf.readableBytes() >= Integer.BYTES && buf.getInt(buf.readerIndex()) == MAGIC;
    }

    public static AutoModpackConnectionFrame decode(ByteBuf buf) throws IOException {
        long startedAt = AutoModpackConnectionMetrics.startDebugTimer();
        try {
            if (buf.readableBytes() < HEADER_SIZE) {
                throw new IOException("AutoModpack connection frame is too short");
            }

            int magic = buf.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid AutoModpack connection frame magic");
            }

            byte version = buf.readByte();
            if (version != CURRENT_VERSION) {
                throw new IOException("Unsupported AutoModpack connection frame version: " + version);
            }

            byte kind = buf.readByte();
            if (kind <= 0) {
                throw new IOException("Invalid AutoModpack connection frame kind: " + kind);
            }

            return new AutoModpackConnectionFrame(kind, buf.readRetainedSlice(buf.readableBytes()));
        } finally {
            AutoModpackConnectionMetrics.logDebugDuration("AutoModpack frame decode", startedAt);
        }
    }

    @Override
    public void close() {
        ReferenceCountUtil.safeRelease(payload);
    }

    @Override
    public String toString() {
        return "AutoModpackConnectionFrame{" +
            "kind=" + kind +
            ", payload=" + payload.readableBytes() +
            '}';
    }
}
