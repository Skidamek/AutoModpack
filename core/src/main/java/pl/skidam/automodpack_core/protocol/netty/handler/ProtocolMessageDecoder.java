package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;

public class ProtocolMessageDecoder extends ByteToMessageDecoder {
	private static final int COMMON_HEADER_LENGTH = 2 * Byte.BYTES + Secrets.BYTE_LENGTH;

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
		if (in.readableBytes() < COMMON_HEADER_LENGTH) return;

		in.markReaderIndex();
		byte version = in.readByte();
		byte type = in.readByte();
		byte[] secret = new byte[Secrets.BYTE_LENGTH];
		in.readBytes(secret);

		switch (type) {
			case ECHO_TYPE :
				if (in.readableBytes() < Integer.BYTES) {
					in.resetReaderIndex();
					return;
				}
				int dataLength = readFieldLength(in);
				if (in.readableBytes() < dataLength) {
					in.resetReaderIndex();
					return;
				}
				byte[] data = new byte[dataLength];
				in.readBytes(data);
				out.add(new EchoMessage(version, secret, data));
				break;
			case FILE_REQUEST_TYPE :
				if (in.readableBytes() < Integer.BYTES) {
					in.resetReaderIndex();
					return;
				}
				int fileHashLength = readFieldLength(in);
				if (in.readableBytes() < fileHashLength) {
					in.resetReaderIndex();
					return;
				}
				byte[] fileHash = new byte[fileHashLength];
				in.readBytes(fileHash);
				out.add(new FileRequestMessage(version, secret, fileHash));
				break;
			default :
				throw new IllegalArgumentException("Unknown message type: " + type);
		}
	}

	private static int readFieldLength(ByteBuf in) {
		int length = in.readInt();
		if (length < 0 || length > MAX_CHUNK_SIZE) throw new IllegalArgumentException("Protocol message field is too large: " + length);
		return length;
	}
}
