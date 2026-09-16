package pl.skidam.automodpack_core.protocol.netty.message.configuration;

import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_COMPRESSION_TYPE;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import pl.skidam.automodpack_core.protocol.compression.CompressionType;
import pl.skidam.automodpack_core.protocol.netty.message.ConfigurationMessage;

public class ConfigurationCompressionMessage extends ConfigurationMessage {

	private final CompressionType compressionType;

	public ConfigurationCompressionMessage(byte version, CompressionType compressionType) {
		super(version, CONFIGURATION_COMPRESSION_TYPE);
		this.compressionType = compressionType;
	}

	public CompressionType getCompressionType() {
		return compressionType;
	}

	@Override
	public byte[] toBytes() {
		return ByteBuffer.allocate(3).put(super.toBytes()).put(compressionType.wireId()).array();
	}

	/** Rebuilds the message from the payload half of a compression exchange; the [version][type] header is already consumed. */
	public static ConfigurationCompressionMessage readFrom(byte version, DataInputStream in) throws IOException {
		try {
			return new ConfigurationCompressionMessage(version, CompressionType.fromWireId(in.readByte()));
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsupported compression response", e);
		}
	}

	public ByteBuf toByteBuf() {
		return Unpooled.wrappedBuffer(toBytes());
	}
}
