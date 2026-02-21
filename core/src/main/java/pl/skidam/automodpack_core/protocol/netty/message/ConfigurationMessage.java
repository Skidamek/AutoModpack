package pl.skidam.automodpack_core.protocol.netty.message;

import io.netty.buffer.ByteBuf;

public abstract class ConfigurationMessage {

    private final byte version; // 1 byte
    private final byte type; // 1 byte

    public ConfigurationMessage(byte version, byte type) {
        this.version = version;
        this.type = type;
    }

    public byte getVersion() {
        return version;
    }

    public byte getType() {
        return type;
    }

    public void toByteBuf(ByteBuf buf) {
        buf.writeByte(version);
        buf.writeByte(type);
    }
}
