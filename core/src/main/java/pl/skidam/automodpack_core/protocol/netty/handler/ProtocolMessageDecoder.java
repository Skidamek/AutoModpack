package pl.skidam.automodpack_core.protocol.netty.handler;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.DownloadBatchProtocol;
import pl.skidam.automodpack_core.protocol.netty.message.request.BatchFileRequestMessage;
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
			case BATCH_FILE_REQUEST_TYPE :
				decodeBatch(version, secret, in, out);
				break;
			default :
				throw new IllegalArgumentException("Unknown message type: " + type);
		}
	}

	private static void decodeBatch(byte version, byte[] secret, ByteBuf in, List<Object> out) {
		if (in.readableBytes() < Integer.BYTES) {
			in.resetReaderIndex();
			return;
		}

		int itemCount = in.readInt();
		if (itemCount < 0 || itemCount > DownloadBatchProtocol.MAX_ITEM_COUNT) throw new IllegalArgumentException("Invalid batch item count: " + itemCount);
		List<BatchFileRequestMessage.Item> items = new ArrayList<>(itemCount);
		Set<Integer> itemIds = new HashSet<>(itemCount);
		long requestBytes = COMMON_HEADER_LENGTH + Integer.BYTES;
		for (int index = 0; index < itemCount; index++) {
			if (in.readableBytes() < Integer.BYTES * 2) {
				in.resetReaderIndex();
				return;
			}

			int itemId = in.readInt();
			int keyLength = in.readInt();
			if (itemId <= 0 || !itemIds.add(itemId)) throw new IllegalArgumentException("Batch item IDs must be positive and unique");
			if (keyLength <= 0 || keyLength > DownloadBatchProtocol.MAX_KEY_BYTES) throw new IllegalArgumentException("Invalid batch key length: " + keyLength);
			requestBytes += Integer.BYTES + Integer.BYTES + keyLength;
			if (requestBytes > DownloadBatchProtocol.MAX_REQUEST_BYTES) throw new IllegalArgumentException("Batch request is too large");
			if (in.readableBytes() < keyLength) {
				in.resetReaderIndex();
				return;
			}

			byte[] keyBytes = new byte[keyLength];
			in.readBytes(keyBytes);
			String key = new String(keyBytes, StandardCharsets.UTF_8);
			DownloadBatchProtocol.validateKey(key, keyBytes);
			items.add(new BatchFileRequestMessage.Item(itemId, key));
		}

		out.add(new BatchFileRequestMessage(version, secret, items));
	}

	private static int readFieldLength(ByteBuf in) {
		int length = in.readInt();
		if (length < 0 || length > MAX_CHUNK_SIZE) throw new IllegalArgumentException("Protocol message field is too large: " + length);
		return length;
	}
}
