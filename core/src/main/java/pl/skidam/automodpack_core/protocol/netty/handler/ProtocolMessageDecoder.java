package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.netty.message.request.EchoMessage;
import pl.skidam.automodpack_core.protocol.netty.message.request.FileRequestMessage;
import pl.skidam.automodpack_core.utils.HashUtils;

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
				int dataLength = readFieldLength(in, MAX_ECHO_PAYLOAD_BYTES);
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
				int fileHashLength = readFieldLength(in, MAX_FILE_HASH_BYTES);
				if (in.readableBytes() < fileHashLength) {
					in.resetReaderIndex();
					return;
				}
				byte[] fileHash = new byte[fileHashLength];
				in.readBytes(fileHash);
				// Version 0x02 requests always carry the trailing extension section; a set flag bit means its field follows, in bit order.
				if (in.readableBytes() < Byte.BYTES) {
					in.resetReaderIndex();
					return;
				}
				byte flags = in.readByte();
				if ((flags & ~FILE_REQUEST_KNOWN_FLAGS) != 0) throw new IllegalArgumentException("Unknown file request extension flags: " + flags);
				if ((flags & FILE_REQUEST_END_FLAG) != 0 && (flags & FILE_REQUEST_OFFSET_FLAG) == 0) throw new IllegalArgumentException("File request end offset requires a range offset");
				int extensionBytes = ((flags & FILE_REQUEST_EXPECTED_SHA1_FLAG) != 0 ? HashUtils.SHA1_HEX_LENGTH : 0) + ((flags & FILE_REQUEST_OFFSET_FLAG) != 0 ? Long.BYTES : 0)
						+ ((flags & FILE_REQUEST_END_FLAG) != 0 ? Long.BYTES : 0);
				if (in.readableBytes() < extensionBytes) {
					in.resetReaderIndex();
					return;
				}
				byte[] expectedSha1 = null;
				long offset = 0;
				Long endInclusive = null;
				if ((flags & FILE_REQUEST_EXPECTED_SHA1_FLAG) != 0) {
					expectedSha1 = new byte[HashUtils.SHA1_HEX_LENGTH];
					in.readBytes(expectedSha1);
				}
				if ((flags & FILE_REQUEST_OFFSET_FLAG) != 0) offset = in.readLong();
				if ((flags & FILE_REQUEST_END_FLAG) != 0) endInclusive = in.readLong();
				out.add(new FileRequestMessage(version, secret, fileHash, expectedSha1, offset, endInclusive));
				break;
			default :
				throw new IllegalArgumentException("Unknown message type: " + type);
		}
	}

	/** Reads one length-prefixed field bounded by its per-field tripwire; the decoder runs pre-authentication, so no field may approach the frame sizes. */
	private static int readFieldLength(ByteBuf in, int maxLength) {
		int length = in.readInt();
		if (length < 0 || length > maxLength) throw new IllegalArgumentException("Protocol message field is too large: " + length);
		return length;
	}
}
