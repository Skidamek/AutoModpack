package pl.skidam.automodpack_core.networking.connection;

import io.netty.buffer.ByteBuf;

import java.io.IOException;

public interface AutoModpackConnectionTransport {
    byte KIND_IROH_TUNNEL = 1;

    void registerHandler(byte kind, AutoModpackFrameHandler handler);

    void unregisterHandler(byte kind);

    void sendFrame(byte kind, ByteBuf payload) throws IOException;

    boolean isOpen();
}
