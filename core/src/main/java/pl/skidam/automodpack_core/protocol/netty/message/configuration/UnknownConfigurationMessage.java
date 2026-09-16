package pl.skidam.automodpack_core.protocol.netty.message.configuration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import pl.skidam.automodpack_core.protocol.netty.message.ConfigurationMessage;

public class UnknownConfigurationMessage extends ConfigurationMessage {

	public UnknownConfigurationMessage(byte version) {
		super(version, (byte) 0x0);
	}

	public ByteBuf toByteBuf() {
		return Unpooled.wrappedBuffer(toBytes());
	}
}
