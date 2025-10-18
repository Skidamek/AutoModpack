package pl.skidam.automodpack_core.protocol.netty.message.configuration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pl.skidam.automodpack_core.protocol.netty.message.ConfigurationMessage;

import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_CHUNK_SIZE_TYPE;

public class ConfigurationChunkSizeMessage extends ConfigurationMessage {

    private final int chunkSize;

    public ConfigurationChunkSizeMessage(byte version, int chunkSize) {
        super(version, CONFIGURATION_CHUNK_SIZE_TYPE);
        this.chunkSize = chunkSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public ByteBuf toByteBuf() {
        ByteBuf buf = Unpooled.buffer(6);
        super.toByteBuf(buf);
        buf.writeInt(chunkSize);
        return buf;
    }
}
