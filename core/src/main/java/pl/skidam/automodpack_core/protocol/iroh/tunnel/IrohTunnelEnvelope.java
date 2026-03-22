package pl.skidam.automodpack_core.protocol.iroh.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import pl.skidam.automodpack_core.networking.connection.AutoModpackConnectionMetrics;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

public record IrohTunnelEnvelope(
    byte version,
    long sessionId,
    byte flags,
    byte[] endpointId,
    List<byte[]> packets,
    String errorMessage
) {
    public static final byte CURRENT_VERSION = 1;
    public static final byte FLAG_OPEN = 0x01;
    public static final byte FLAG_CLOSE = 0x02;
    public static final byte FLAG_ERROR = 0x04;
    public static final byte FLAG_READY = 0x08;
    public static final int ENDPOINT_ID_LENGTH = 32;
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    public IrohTunnelEnvelope {
        endpointId = endpointId == null ? null : endpointId.clone();
        packets = packets == null ? List.of() : List.copyOf(packets);
    }

    public boolean isOpen() {
        return (flags & FLAG_OPEN) != 0;
    }

    public boolean isClose() {
        return (flags & FLAG_CLOSE) != 0;
    }

    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    public boolean isReady() {
        return (flags & FLAG_READY) != 0;
    }

    public ByteBuf encode(ByteBufAllocator allocator) throws IOException {
        long startedAt = AutoModpackConnectionMetrics.startDebugTimer();
        int encodedSize = encodedSize();
        ByteBuf encoded = allocator.buffer(encodedSize, encodedSize);
        try {
            writeTo(encoded);
            return encoded;
        } catch (Throwable error) {
            encoded.release();
            throw error;
        } finally {
            AutoModpackConnectionMetrics.logDebugDuration("Iroh tunnel envelope encode", startedAt);
        }
    }

    public static IrohTunnelEnvelope decode(ByteBuf encoded) throws IOException {
        long startedAt = AutoModpackConnectionMetrics.startDebugTimer();
        try {
            if (encoded.readableBytes() > MAX_ENCODED_BYTES) {
                throw new IOException("Tunnel envelope exceeds " + MAX_ENCODED_BYTES + " bytes");
            }
            if (!encoded.isReadable()) {
                throw new IOException("Tunnel envelope is missing version byte");
            }

            byte version = encoded.readByte();
            if (version != CURRENT_VERSION) {
                throw new IOException("Unsupported tunnel envelope version: " + version);
            }
            if (encoded.readableBytes() < Long.BYTES + 1) {
                throw new IOException("Tunnel envelope header is truncated");
            }

            long sessionId = encoded.readLong();
            byte flags = encoded.readByte();
            byte[] endpointId = null;
            if ((flags & FLAG_OPEN) != 0) {
                if (encoded.readableBytes() < ENDPOINT_ID_LENGTH) {
                    throw new IOException("Tunnel OPEN envelope is missing endpoint id bytes");
                }
                endpointId = new byte[ENDPOINT_ID_LENGTH];
                encoded.readBytes(endpointId);
            }

            int packetCount = readVarInt(encoded);
            if (packetCount < 0) {
                throw new IOException("Negative tunnel packet count");
            }

            List<byte[]> packets = new ArrayList<>(packetCount);
            for (int i = 0; i < packetCount; i++) {
                int length = readVarInt(encoded);
                if (length < 0) {
                    throw new IOException("Negative tunnel packet length");
                }
                if (encoded.readableBytes() < length) {
                    throw new IOException("Truncated tunnel packet");
                }
                byte[] packet = new byte[length];
                encoded.readBytes(packet);
                packets.add(packet);
            }

            String errorMessage = null;
            if ((flags & FLAG_ERROR) != 0) {
                int length = readVarInt(encoded);
                if (length < 0) {
                    throw new IOException("Negative tunnel error length");
                }
                if (encoded.readableBytes() < length) {
                    throw new IOException("Truncated tunnel error message");
                }
                byte[] errorBytes = new byte[length];
                encoded.readBytes(errorBytes);
                errorMessage = new String(errorBytes, StandardCharsets.UTF_8);
            }

            if (encoded.isReadable()) {
                throw new IOException("Unexpected trailing bytes in tunnel envelope");
            }

            return new IrohTunnelEnvelope(version, sessionId, flags, endpointId, packets, errorMessage);
        } finally {
            AutoModpackConnectionMetrics.logDebugDuration("Iroh tunnel envelope decode", startedAt);
        }
    }

    public static EncodedTunnelEnvelope buildFromQueue(
        ByteBufAllocator allocator,
        long sessionId,
        byte flags,
        byte[] endpointId,
        Queue<byte[]> queue,
        String errorMessage
    ) throws IOException {
        byte[] safeEndpointId = endpointId == null ? null : endpointId.clone();
        byte[] errorBytes = (flags & FLAG_ERROR) != 0
            ? Objects.requireNonNullElse(errorMessage, "").getBytes(StandardCharsets.UTF_8)
            : null;

        int fixedSize = 1 + Long.BYTES + 1;
        if ((flags & FLAG_OPEN) != 0) {
            if (safeEndpointId == null || safeEndpointId.length != ENDPOINT_ID_LENGTH) {
                throw new IOException("Tunnel OPEN envelope requires endpoint id");
            }
            fixedSize += ENDPOINT_ID_LENGTH;
        }
        if (errorBytes != null) {
            fixedSize += sizeOfVarInt(errorBytes.length) + errorBytes.length;
        }

        int packetCount = 0;
        int packetBytes = 0;
        List<byte[]> packets = new ArrayList<>();

        while (true) {
            byte[] next = queue.peek();
            if (next == null) {
                break;
            }

            int nextPacketCount = packetCount + 1;
            int nextPacketBytes = packetBytes + sizeOfVarInt(next.length) + next.length;
            int totalSize = fixedSize + sizeOfVarInt(nextPacketCount) + nextPacketBytes;
            if (totalSize > MAX_ENCODED_BYTES) {
                break;
            }

            queue.poll();
            packets.add(next);
            packetCount = nextPacketCount;
            packetBytes = nextPacketBytes;
        }

        int totalSize = fixedSize + sizeOfVarInt(packetCount) + packetBytes;
        if (totalSize > MAX_ENCODED_BYTES) {
            throw new IOException("Tunnel envelope exceeds " + MAX_ENCODED_BYTES + " bytes");
        }

        ByteBuf encoded = allocator.buffer(totalSize, totalSize);
        try {
            encoded.writeByte(CURRENT_VERSION);
            encoded.writeLong(sessionId);
            encoded.writeByte(flags);
            if ((flags & FLAG_OPEN) != 0) {
                encoded.writeBytes(safeEndpointId);
            }

            writeVarInt(encoded, packetCount);
            for (byte[] packet : packets) {
                writeVarInt(encoded, packet.length);
                encoded.writeBytes(packet);
            }

            if (errorBytes != null) {
                writeVarInt(encoded, errorBytes.length);
                encoded.writeBytes(errorBytes);
            }

            return new EncodedTunnelEnvelope(encoded, packetCount);
        } catch (Throwable error) {
            ReferenceCountUtil.safeRelease(encoded);
            throw error;
        }
    }

    private int encodedSize() throws IOException {
        int totalSize = 1 + Long.BYTES + 1;
        if (isOpen()) {
            if (endpointId == null || endpointId.length != ENDPOINT_ID_LENGTH) {
                throw new IOException("Tunnel OPEN envelope requires a 32-byte endpoint id");
            }
            totalSize += ENDPOINT_ID_LENGTH;
        }

        totalSize += sizeOfVarInt(packets.size());
        for (byte[] packet : packets) {
            totalSize += sizeOfVarInt(packet.length) + packet.length;
        }

        if (isError()) {
            byte[] errorBytes = Objects.requireNonNullElse(errorMessage, "").getBytes(StandardCharsets.UTF_8);
            totalSize += sizeOfVarInt(errorBytes.length) + errorBytes.length;
        }

        if (totalSize > MAX_ENCODED_BYTES) {
            throw new IOException("Tunnel envelope exceeds " + MAX_ENCODED_BYTES + " bytes");
        }
        return totalSize;
    }

    private void writeTo(ByteBuf out) throws IOException {
        out.writeByte(version);
        out.writeLong(sessionId);
        out.writeByte(flags);
        if (isOpen()) {
            if (endpointId == null || endpointId.length != ENDPOINT_ID_LENGTH) {
                throw new IOException("Tunnel OPEN envelope requires a 32-byte endpoint id");
            }
            out.writeBytes(endpointId);
        }

        writeVarInt(out, packets.size());
        for (byte[] packet : packets) {
            writeVarInt(out, packet.length);
            out.writeBytes(packet);
        }

        if (isError()) {
            byte[] errorBytes = Objects.requireNonNullElse(errorMessage, "").getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, errorBytes.length);
            out.writeBytes(errorBytes);
        }
    }

    private static void writeVarInt(ByteBuf out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }

    private static int readVarInt(ByteBuf in) throws IOException {
        int value = 0;
        int position = 0;
        byte current;
        do {
            if (position >= 35) {
                throw new IOException("VarInt is too big");
            }
            if (!in.isReadable()) {
                throw new EOFException("Unexpected EOF while reading VarInt");
            }
            current = in.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
        } while ((current & 0x80) != 0);
        return value;
    }

    private static int sizeOfVarInt(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }

    public record EncodedTunnelEnvelope(ByteBuf buffer, int packetCount) {}
}
