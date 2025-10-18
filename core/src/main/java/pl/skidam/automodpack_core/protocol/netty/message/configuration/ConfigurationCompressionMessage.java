package pl.skidam.automodpack_core.protocol.netty.message.configuration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pl.skidam.automodpack_core.protocol.netty.message.ConfigurationMessage;

import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_COMPRESSION_TYPE;

public class ConfigurationCompressionMessage extends ConfigurationMessage {

    private final byte compressionType;

    public ConfigurationCompressionMessage(byte version, byte compressionType) {
        super(version, CONFIGURATION_COMPRESSION_TYPE);
        this.compressionType = compressionType;
    }

    public byte getCompressionType() {
        return compressionType;
    }

    public ByteBuf toByteBuf() {
        ByteBuf buf = Unpooled.buffer(3);
        super.toByteBuf(buf);
        buf.writeByte(compressionType);
        return buf;
    }
}
