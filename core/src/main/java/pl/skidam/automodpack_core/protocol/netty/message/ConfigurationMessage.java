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

	/** The one wire encoding of this message: [version][type][payload]; every transport sinks these bytes. */
	public byte[] toBytes() {
		return new byte[]{version, type};
	}

	public void toByteBuf(ByteBuf buf) {
		buf.writeByte(version);
		buf.writeByte(type);
	}
}
