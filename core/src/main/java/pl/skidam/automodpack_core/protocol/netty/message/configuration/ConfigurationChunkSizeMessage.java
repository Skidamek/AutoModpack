package pl.skidam.automodpack_core.protocol.netty.message.configuration;

import static pl.skidam.automodpack_core.protocol.NetUtils.CONFIGURATION_CHUNK_SIZE_TYPE;
import static pl.skidam.automodpack_core.protocol.NetUtils.MAX_CHUNK_SIZE;
import static pl.skidam.automodpack_core.protocol.NetUtils.MIN_CHUNK_SIZE;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import pl.skidam.automodpack_core.protocol.netty.message.ConfigurationMessage;

public class ConfigurationChunkSizeMessage extends ConfigurationMessage {

	private final int chunkSize;

	public ConfigurationChunkSizeMessage(byte version, int chunkSize) {
		super(version, CONFIGURATION_CHUNK_SIZE_TYPE);
		this.chunkSize = chunkSize;
	}

	public int getChunkSize() {
		return chunkSize;
	}

	@Override
	public byte[] toBytes() {
		return ByteBuffer.allocate(6).put(super.toBytes()).putInt(chunkSize).array();
	}

	/** Rebuilds the message from the payload half of a chunk-size exchange; the [version][type] header is already consumed. */
	public static ConfigurationChunkSizeMessage readFrom(byte version, DataInputStream in) throws IOException {
		int chunkSize = in.readInt();
		if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) throw new IOException("Chunk size out of bounds: " + chunkSize);
		return new ConfigurationChunkSizeMessage(version, chunkSize);
	}

	public ByteBuf toByteBuf() {
		return Unpooled.wrappedBuffer(toBytes());
	}
}
