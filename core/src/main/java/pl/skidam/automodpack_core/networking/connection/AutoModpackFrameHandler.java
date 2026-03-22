package pl.skidam.automodpack_core.networking.connection;

import io.netty.buffer.ByteBuf;

@FunctionalInterface
public interface AutoModpackFrameHandler {
    void handle(ByteBuf payload) throws Exception;
}
